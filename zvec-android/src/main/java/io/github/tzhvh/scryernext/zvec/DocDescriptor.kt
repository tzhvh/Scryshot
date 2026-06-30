package io.github.tzhvh.scryernext.zvec

/**
 * The flattened, JNI-friendly encoding of a built [ZvecDocBuilder]. Mirrors the
 * [SchemaDescriptor] discipline: typed construction happens in pure Kotlin
 * ([ZvecDocBuilder]); the JNI build path does a single linear pass over
 * parallel primitive arrays with no reflection on `Any?`.
 *
 * **Why parallel arrays, not `Array<Any?>`.** zvec's `zvec_doc_add_field_by_value`
 * is one type-erased call whose per-type byte-size contract is exact
 * (`extract_scalar_value<bool>` rejects anything but `sizeof(bool)` = 1,
 * `extract_vector_values<float>` requires `size % 4 == 0`). The JNI layer must
 * read each field's value with the right `Get<Type>ArrayElements` flavor and
 * compute the right `value_size`. Laying fields out as parallel arrays
 * (per-type value arrays + a [kinds] discriminator) lets C++ do that in one
 * flat switch — one `jint` read per scalar, one array-element fetch per vector —
 * where `Array<Any?>` would force `IsInstanceOf` casts and boxing.
 *
 * **Layout (parallel arrays indexed by field index `0..<fieldCount`):**
 * - [fieldNames], [kinds], [isNull]: read for every field.
 * - [kinds] picks which per-type value array holds this field's value, and (for
 *   scalars) which slot in that array (sequential; [scalarIndex] gives the
 *   position so each field reads one element).
 * - Per-type value arrays: [longs] (INT64/INT32), [doubles] (DOUBLE/FLOAT),
 *   [bools] (BOOL), [strings] (STRING), [f32Vecs]/[f16Vecs]/[i8Vecs] (vectors).
 *   Fields with [isNull] = true carry no value and skip the arrays entirely.
 *
 * **`kinds`/scalarIndex** are kept on the Kotlin side so the JNI layer never
 * re-derives them. `internal`: only the JNI layer (via [ZvecNative]) consumes it.
 */
internal class DocDescriptor private constructor(
    val pk: String,
    val fieldCount: Int,
    val fieldNames: Array<String>,
    /** Per-field [Kind], one `jint` read in the JNI switch. */
    val kinds: IntArray,
    /** Per-field slot into the relevant per-type value array. */
    val scalarIndex: IntArray,
    /** True where the field is an explicit stored null ([ZvecDocBuilder.fieldNull]). */
    val isNull: BooleanArray,
    val longs: LongArray,
    val doubles: DoubleArray,
    val bools: BooleanArray,
    val strings: Array<String>,
    val f32Vecs: Array<FloatArray>,
    val f16Vecs: Array<ShortArray>,
    val i8Vecs: Array<ByteArray>,
) {
    /**
     * Per-field type discriminator the JNI layer switches on. Pinned explicit so
     * a Kotlin reorder can't drift the int C++ reads (mirrors
     * [SchemaDescriptor.IndexKind]).
     */
    internal object Kind {
        const val STRING = 0
        const val INT64 = 1
        const val INT32 = 2
        const val FLOAT64 = 3
        const val FLOAT32 = 4
        const val BOOL = 5
        const val VEC_F32 = 6
        const val VEC_F16 = 7
        const val VEC_I8 = 8
        const val NULL = 9
    }

    internal companion object {
        /**
         * Flatten the builder's accumulated [fields] into per-type value arrays.
         * Pure: no JNI, no `.so` — so the layout is JVM-reasonable without the
         * native build. The engine's exact-size validation (`add_field_by_value`)
         * is the real authority on value validity; this only lays out the data.
         */
        fun encode(pk: String, fields: List<DocField>): DocDescriptor {
            val n = fields.size
            val fieldNames = Array(n) { fields[it].name }
            val kinds = IntArray(n)
            val scalarIndex = IntArray(n)
            val isNull = BooleanArray(n)

            // Size each per-type array up front by counting, then fill in a second
            // pass — two linear passes, no List/boxing churn.
            var nLong = 0; var nDouble = 0; var nBool = 0; var nString = 0
            var nF32 = 0; var nF16 = 0; var nI8 = 0; var nNull = 0
            for (f in fields) when (f) {
                is DocField.StrField -> nString++
                is DocField.Int64Field, is DocField.Int32Field -> nLong++
                is DocField.Float64Field, is DocField.Float32Field -> nDouble++
                is DocField.BoolField -> nBool++
                is DocField.VecF32Field -> nF32++
                is DocField.VecF16Field -> nF16++
                is DocField.VecI8Field -> nI8++
                is DocField.NullField -> nNull++
            }

            val longs = LongArray(nLong)
            val doubles = DoubleArray(nDouble)
            val bools = BooleanArray(nBool)
            val strings = arrayOfNulls<String>(nString)
            val f32Vecs = arrayOfNulls<FloatArray>(nF32)
            val f16Vecs = arrayOfNulls<ShortArray>(nF16)
            val i8Vecs = arrayOfNulls<ByteArray>(nI8)

            var iLong = 0; var iDouble = 0; var iBool = 0; var iString = 0
            var iF32 = 0; var iF16 = 0; var iI8 = 0
            for (i in 0 until n) {
                val f = fields[i]
                when (f) {
                    is DocField.StrField -> {
                        kinds[i] = Kind.STRING; scalarIndex[i] = iString
                        strings[iString++] = f.value
                    }
                    is DocField.Int64Field -> {
                        kinds[i] = Kind.INT64; scalarIndex[i] = iLong
                        longs[iLong++] = f.value
                    }
                    is DocField.Int32Field -> {
                        kinds[i] = Kind.INT32; scalarIndex[i] = iLong
                        longs[iLong++] = f.value.toLong()
                    }
                    is DocField.Float64Field -> {
                        kinds[i] = Kind.FLOAT64; scalarIndex[i] = iDouble
                        doubles[iDouble++] = f.value
                    }
                    is DocField.Float32Field -> {
                        kinds[i] = Kind.FLOAT32; scalarIndex[i] = iDouble
                        doubles[iDouble++] = f.value.toDouble()
                    }
                    is DocField.BoolField -> {
                        kinds[i] = Kind.BOOL; scalarIndex[i] = iBool
                        bools[iBool++] = f.value
                    }
                    is DocField.VecF32Field -> {
                        kinds[i] = Kind.VEC_F32; scalarIndex[i] = iF32
                        f32Vecs[iF32++] = f.value
                    }
                    is DocField.VecF16Field -> {
                        kinds[i] = Kind.VEC_F16; scalarIndex[i] = iF16
                        f16Vecs[iF16++] = f.value
                    }
                    is DocField.VecI8Field -> {
                        kinds[i] = Kind.VEC_I8; scalarIndex[i] = iI8
                        i8Vecs[iI8++] = f.value
                    }
                    is DocField.NullField -> {
                        kinds[i] = Kind.NULL; isNull[i] = true
                    }
                }
            }

            @Suppress("UNCHECKED_CAST")
            return DocDescriptor(
                pk = pk,
                fieldCount = n,
                fieldNames = fieldNames,
                kinds = kinds,
                scalarIndex = scalarIndex,
                isNull = isNull,
                longs = longs,
                doubles = doubles,
                bools = bools,
                strings = strings as Array<String>,
                f32Vecs = f32Vecs as Array<FloatArray>,
                f16Vecs = f16Vecs as Array<ShortArray>,
                i8Vecs = i8Vecs as Array<ByteArray>,
            )
        }
    }
}
