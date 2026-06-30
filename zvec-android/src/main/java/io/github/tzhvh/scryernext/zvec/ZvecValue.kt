package io.github.tzhvh.scryernext.zvec

/**
 * Typed field value — one arm per zvec `zvec_data_type_t` the product path uses.
 * ADR 0006.
 *
 * This is the most-read type in the SDK: every read site pattern-matches on it,
 * and every write site constructs one of its arms. Sealing it makes both
 * exhaustive-checked, so a wrong-type access (`doc.fields["size"] as? Long` in
 * the roadmap's `Map<String, Any?>` sketch) is a **compile error here, not a
 * runtime `ClassCastException`** — the whole reason the roadmap's sketch was
 * rejected (ADR 0006 §Context).
 *
 * Each vector arm maps to its natural JVM primitive array. FP16 has no JVM
 * primitive, so it rides as the raw half-16 bits in a [ShortArray] (the JNI
 * layer hands zvec `sizeof(zvec::float16_t)` = 2 bytes per element); INT8 is a
 * [ByteArray]. A `List<Float>`-only representation (the roadmap sketch) would be
 * a Phase-3 landmine the moment anyone reaches for a quantized index.
 *
 * The JNI write layer (`zvec_doc_add_field_by_value`) dispatches off this sealed
 * hierarchy's [dataType] discriminator — a flat `when`, no reflection on `Any?`.
 * The read layer (issue 05) constructs these arms from the C getters.
 *
 * **`Null` vs. absent in [ZvecDoc.fields].** An absent key = "not requested"
 * (an `outputFields` projection was set and omitted it); [Null] = "the field
 * exists in storage and is null." Mirrors the C API's `zvec_doc_is_field_null`
 * vs. field-presence distinction.
 */
sealed interface ZvecValue {
    /** The C `zvec_data_type_t` value (pinned by [FieldType]); the JNI layer reads it. */
    val dataType: Int

    /** `BOOL` (`c_api.h:787`). Backed by 1 byte at the C boundary (see [ZvecDocBuilder.bool]). */
    data class ScalarBool(val value: Boolean) : ZvecValue {
        override val dataType: Int get() = FieldType.BOOL.toNative()
    }

    /** Fixed-width integers: INT32 / INT64 / UINT32 / UINT64 all widen to [Long]. */
    data class ScalarInt(val value: Long) : ZvecValue {
        override val dataType: Int get() = FieldType.INT64.toNative()
    }

    /** Floating-point: FLOAT / DOUBLE both widen to [Double]. */
    data class ScalarFloat(val value: Double) : ZvecValue {
        override val dataType: Int get() = FieldType.DOUBLE.toNative()
    }

    /** `STRING`. UTF-8 at the C boundary; byte length (not `String.length`) is the size. */
    data class Str(val value: String) : ZvecValue {
        override val dataType: Int get() = FieldType.STRING.toNative()
    }

    /** `BINARY`. No product caller yet; the arm exists so the hierarchy is total. */
    data class Bin(val value: ByteArray) : ZvecValue {
        override val dataType: Int get() = FieldType.STRING.toNative()
    }

    /** `VECTOR_FP32` — the screenshots embedding vector. */
    data class VecF32(val value: FloatArray) : ZvecValue {
        override val dataType: Int get() = FieldType.VECTOR_FP32.toNative()
    }

    /**
     * `VECTOR_FP16` — half-precision, raw half-16 bits in a [ShortArray]. No JVM
     * primitive for FP16, so the bits pass through unconverted; the JNI layer
     * hands zvec `count * sizeof(float16_t)` (= 2 bytes/element).
     */
    data class VecF16(val value: ShortArray) : ZvecValue {
        override val dataType: Int get() = FieldType.VECTOR_FP16.toNative()
    }

    /** `VECTOR_INT8` — 8-bit quantized. */
    data class VecI8(val value: ByteArray) : ZvecValue {
        override val dataType: Int get() = FieldType.VECTOR_INT8.toNative()
    }

    /**
     * Minimal placeholder for the `ARRAY_*` types. No product caller exists, so
     * the exact element shape is deferred (the data-model note in
     * `ZVEC_PHASE1.md`); the arm is present so the sealed hierarchy is total and
     * adding a real array type later is additive (a new arm + a new JNI branch),
     * never a caller break.
     */
    data class ScalarArray(val elements: List<ZvecValue>) : ZvecValue {
        override val dataType: Int get() = FieldType.STRING.toNative()
    }

    /** A field that exists in storage and is null (vs. an absent key). */
    data object Null : ZvecValue {
        override val dataType: Int get() = -1
    }
}

/**
 * A zvec document as a pure immutable value type — GC-owned, **no handle** — on
 * both the read and write paths. ADR 0006.
 *
 * Fields carry typed [ZvecValue]s (not `Any?`); vectors ride inside [fields],
 * not a separate map. This is what the write builder produces and the read path
 * (issue 05) copies fetched docs into: the C getters populate it, then the whole
 * result `zvec_doc_t*` array is `zvec_docs_free`d before return — no doc handle
 * ever escapes the SDK. Eliminates the use-after-free class the Rust-faithful
 * "owned result doc" alternative would introduce.
 *
 * @param pk the document primary key.
 * @param score similarity score (read path only; `null` on the write path).
 * @param fields typed field values. Absent key = "not requested" under an
 *   `outputFields` projection; [ZvecValue.Null] = "stored as null."
 */
data class ZvecDoc(
    val pk: String,
    val score: Float? = null,
    val fields: Map<String, ZvecValue>,
)
