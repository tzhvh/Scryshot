package io.github.tzhvh.scryernext.zvec

/**
 * The typed write-path DSL: per-type methods that are the compile-time gate for
 * document fields. ADR 0006 / issue 03.
 *
 * Each method names its type ([string] → STRING, [int64] → INT64, [vectorF32] →
 * VECTOR_FP32, …) — there is **no `Any?`-typed entry point.** A `DoubleArray` or
 * custom class cannot sneak through; the SDK's single C call
 * (`zvec_doc_add_field_by_value(doc, name, dataType, value*, size)`) is
 * type-erased, so without these typed methods the write path would lose its
 * compile-time safety at exactly the point the engine stores the wrong shape.
 *
 * Pure Kotlin — no JNI here (mirrors [SchemaDescriptor]: typed construction stays
 * JVM-side, so a builder's field layout can be reasoned about without the `.so`).
 * [build] flattens the accumulated fields into a [DocDescriptor] the JNI layer
 * walks in one linear pass with no reflection.
 *
 * Constructed only by [ZvecCollection.insert] / [upsert] (the `internal`
 * constructor); callers use the `builder: ZvecDocBuilder.() -> Unit` lambda, so
 * the type is unconstructable from outside the SDK.
 *
 * The set of per-type methods is the immediate product path (screenshots =
 * scalars + one FP32 vector). UINT32/UINT64/FLOAT scalar methods are deferred —
 * they have no caller, and adding one is additive (a new method + a new JNI
 * branch), never a caller break.
 */
class ZvecDocBuilder internal constructor() {
    /** The document primary key. Must be set before [build]. */
    var pk: String = ""

    private val fields = mutableListOf<DocField>()

    /** `STRING` field. UTF-8 byte length is the C `value_size` (not `value.length`). */
    fun string(name: String, value: String): ZvecDocBuilder = apply {
        fields += DocField.StrField(name, value)
    }

    /** `INT64` field (8 bytes at the C boundary). */
    fun int64(name: String, value: Long): ZvecDocBuilder = apply {
        fields += DocField.Int64Field(name, value)
    }

    /** `INT32` field (4 bytes); the value is narrowed to 32 bits at the JNI layer. */
    fun int32(name: String, value: Int): ZvecDocBuilder = apply {
        fields += DocField.Int32Field(name, value)
    }

    /** `DOUBLE` field (8 bytes). */
    fun float64(name: String, value: Double): ZvecDocBuilder = apply {
        fields += DocField.Float64Field(name, value)
    }

    /** `FLOAT` field (4 bytes); the value is narrowed to 32 bits at the JNI layer. */
    fun float32(name: String, value: Float): ZvecDocBuilder = apply {
        fields += DocField.Float32Field(name, value)
    }

    /**
     * `BOOL` field. At the C boundary BOOL is **1 byte** (`sizeof(bool)`,
     * enforced exactly by `extract_scalar_value<bool>` in `c_api.cc:2941`); the
     * JNI layer writes `0`/`1`, not a JVM `int`.
     */
    fun bool(name: String, value: Boolean): ZvecDocBuilder = apply {
        fields += DocField.BoolField(name, value)
    }

    /** `VECTOR_FP32` field. `count * sizeof(float)` bytes at the C boundary. */
    fun vectorF32(name: String, value: FloatArray): ZvecDocBuilder = apply {
        fields += DocField.VecF32Field(name, value)
    }

    /** `VECTOR_FP16` field (raw half-16 bits; `count * sizeof(float16_t)` = 2 bytes/element). */
    fun vectorF16(name: String, value: ShortArray): ZvecDocBuilder = apply {
        fields += DocField.VecF16Field(name, value)
    }

    /** `VECTOR_INT8` field (8-bit quantized; `count * sizeof(int8_t)`). */
    fun vectorI8(name: String, value: ByteArray): ZvecDocBuilder = apply {
        fields += DocField.VecI8Field(name, value)
    }

    /**
     * Explicitly mark [name] null (via `zvec_doc_set_field_null`). Distinct from
     * simply omitting the field: a stored null satisfies a nullable field's
     * presence, where an omission does not.
     */
    fun fieldNull(name: String): ZvecDocBuilder = apply {
        fields += DocField.NullField(name)
    }

    /**
     * Flatten the accumulated fields into the JNI-friendly [DocDescriptor]. The
     * descriptor is a one-shot view of this builder; calling [build] twice yields
     * two independent snapshots.
     */
    internal fun build(): DocDescriptor {
        require(pk.isNotEmpty()) { "ZvecDocBuilder.pk must be set before build()" }
        return DocDescriptor.encode(pk, fields)
    }
}

/**
 * One accumulated field, typed. Sealed so [DocDescriptor.encode] is a flat `when`
 * that lays each field into the right per-type slot — no `Any?` reflection.
 * Internal: only [ZvecDocBuilder] constructs these.
 */
internal sealed interface DocField {
    val name: String

    data class StrField(override val name: String, val value: String) : DocField
    data class Int64Field(override val name: String, val value: Long) : DocField
    data class Int32Field(override val name: String, val value: Int) : DocField
    data class Float64Field(override val name: String, val value: Double) : DocField
    data class Float32Field(override val name: String, val value: Float) : DocField
    data class BoolField(override val name: String, val value: Boolean) : DocField
    data class VecF32Field(override val name: String, val value: FloatArray) : DocField
    data class VecF16Field(override val name: String, val value: ShortArray) : DocField
    data class VecI8Field(override val name: String, val value: ByteArray) : DocField
    data class NullField(override val name: String) : DocField
}
