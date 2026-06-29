package io.github.tzhvh.scryernext.zvec

/**
 * The flattened, JNI-friendly encoding of a [CollectionSchema] + [CollectionOptions].
 *
 * The schema tree is recursive (collection → fields → optional index params, where
 * FTS params carry a variable-length filter list). The C API wants it built handle
 * by handle under RAII guards. Rather than have the JNI layer walk the tree via JNI
 * reflection (slow, brittle, and impossible to unit-test on the JVM), this encoder
 * flattens it once, in pure Kotlin, into parallel primitive arrays. The JNI build
 * path then does a single linear pass: per field, read its slot from each array,
 * build the C handles, free them under guards.
 *
 * This is the same "flat primitives, no reflection" discipline Phase 0 uses for
 * `nativeInsert`'s two string fields, generalized. It keeps the JNI boundary
 * "(primitive arrays) -> jlong", with all typed construction on the Kotlin side —
 * where it can be JVM-unit-tested without the `.so`.
 *
 * **Layout (parallel arrays indexed by field index `0..fieldCount-1`):**
 *
 * - Fixed-per-field: [fieldNames], [fieldDataTypes], [fieldNullable],
 *   [fieldDimensions], [fieldIndexTypes].
 * - Index-param scalars ([indexM] … [indexEnableRangeOpt]): read only when the
 *   field's [fieldIndexTypes] slot names a param-bearing index kind; unused slots
 *   are zero-filled so the array length always equals [fieldCount] (one JNI read
 *   per slot, no sentinel juggling in C++).
 * - FTS variable data: FTS fields carry a variable-length token-filter list. We
 *   pack all fields' filters into one [ftsFilterNames] array and record, per FTS
 *   field, its start index in [ftsFilterFieldIndices] (same parallel-array slot).
 *   [ftsTokenizer] / [ftsExtraParams] are per-field string arrays (empty string =
 *   "keep C default"), also indexed by field.
 *
 * The index-kind ints are pinned by [IndexKind] (explicit values, not ordinals).
 */
internal class SchemaDescriptor private constructor(
    val schemaName: String,
    val fieldCount: Int,
    val fieldNames: Array<String>,
    val fieldDataTypes: IntArray,
    val fieldNullable: BooleanArray,
    val fieldDimensions: IntArray,
    val fieldIndexTypes: IntArray,
    // Vector-index scalars (HNSW m/efConstruction; IVF nList/nIters; metric). Read
    // only when fieldIndexTypes[i] is HNSW / IVF / FLAT; zero otherwise.
    val indexM: IntArray,
    val indexEfConstruction: IntArray,
    val indexNList: IntArray,
    val indexNIters: IntArray,
    val indexMetric: IntArray,
    // INVERT scalar. Read only when fieldIndexTypes[i] is INVERT.
    val indexEnableRangeOpt: BooleanArray,
    // FTS per-field string arrays (empty string = keep C default). Read only when
    // fieldIndexTypes[i] is FTS.
    val ftsTokenizer: Array<String>,
    val ftsExtraParams: Array<String>,
    // All FTS filters across all FTS fields, packed flat; ftsFilterFieldIndices[i]
    // is the count of filters belonging to field i (the JNI layer reads
    // `count = ftsFilterFieldIndices[i]` consecutive names per FTS field).
    val ftsFilterNames: Array<String>,
    val ftsFilterFieldIndices: IntArray,
    // Collection options (1:1 with CollectionOptions).
    val enableMmap: Boolean,
    val readOnly: Boolean,
    val maxBufferSize: Long,
) {
    /**
     * The index-kind discriminator the JNI layer switches on. Pinned explicit
     * values (mirroring `zvec_index_type_t`, `c_api.h:824`) so an enum reorder in
     * Kotlin can never drift the int handed to `zvec_index_params_create`.
     */
    internal object IndexKind {
        const val NONE = 0
        const val HNSW = 1
        const val IVF = 2
        const val FLAT = 3
        const val INVERT = 10
        const val FTS = 11
    }

    internal companion object {
        /**
         * Flatten [schema] + [options]. Pure: no JNI, no `.so` — so the layout can
         * be JVM-unit-tested. The C-side `zvec_collection_schema_validate` is the
         * real authority on validity; this encoder only lays out the data, and an
         * invalid schema (vector field missing dimension, etc.) surfaces as a
         * [ZvecException] from `createAndOpen`, not here.
         */
        fun encode(schema: CollectionSchema, options: CollectionOptions): SchemaDescriptor {
            val n = schema.fields.size
            val fieldNames = Array(n) { schema.fields[it].name }
            val fieldDataTypes = IntArray(n) { schema.fields[it].type.toNative() }
            val fieldNullable = BooleanArray(n) { schema.fields[it].nullable }
            // Vector fields require a dimension; non-vector fields pass 0 (ignored by C).
            // A null dimension on a vector field is left as 0 here and the C validator
            // rejects it — that's the fail-fast path the issue pins.
            val fieldDimensions = IntArray(n) { schema.fields[it].dimension ?: 0 }
            val fieldIndexTypes = IntArray(n) { IndexKind.NONE }
            val indexM = IntArray(n)
            val indexEfConstruction = IntArray(n)
            val indexNList = IntArray(n)
            val indexNIters = IntArray(n)
            val indexMetric = IntArray(n)
            val indexEnableRangeOpt = BooleanArray(n)
            val ftsTokenizer = Array(n) { "" }
            val ftsExtraParams = Array(n) { "" }
            val ftsFilterFieldIndices = IntArray(n)
            val ftsFilterNames = mutableListOf<String>()

            for (i in 0 until n) {
                val params = schema.fields[i].indexParams ?: continue
                when (params) {
                    is IndexParams.HnswParams -> {
                        fieldIndexTypes[i] = IndexKind.HNSW
                        indexM[i] = params.m
                        indexEfConstruction[i] = params.efConstruction
                        indexMetric[i] = params.metric.toNative()
                    }
                    is IndexParams.FlatParams -> {
                        fieldIndexTypes[i] = IndexKind.FLAT
                        indexMetric[i] = params.metric.toNative()
                    }
                    is IndexParams.IvfParams -> {
                        fieldIndexTypes[i] = IndexKind.IVF
                        indexNList[i] = params.nList
                        indexNIters[i] = params.nIters
                        indexMetric[i] = params.metric.toNative()
                    }
                    is IndexParams.InvertParams -> {
                        fieldIndexTypes[i] = IndexKind.INVERT
                        indexEnableRangeOpt[i] = params.enableRangeOptimization
                    }
                    is IndexParams.FtsParams -> {
                        fieldIndexTypes[i] = IndexKind.FTS
                        ftsTokenizer[i] = params.tokenizer
                        ftsExtraParams[i] = params.extraParams ?: ""
                        // Record the count for this field, then append its filters.
                        ftsFilterFieldIndices[i] = params.filters.size
                        for (f in params.filters) ftsFilterNames.add(f)
                    }
                }
            }

            return SchemaDescriptor(
                schemaName = schema.name,
                fieldCount = n,
                fieldNames = fieldNames,
                fieldDataTypes = fieldDataTypes,
                fieldNullable = fieldNullable,
                fieldDimensions = fieldDimensions,
                fieldIndexTypes = fieldIndexTypes,
                indexM = indexM,
                indexEfConstruction = indexEfConstruction,
                indexNList = indexNList,
                indexNIters = indexNIters,
                indexMetric = indexMetric,
                indexEnableRangeOpt = indexEnableRangeOpt,
                ftsTokenizer = ftsTokenizer,
                ftsExtraParams = ftsExtraParams,
                ftsFilterNames = ftsFilterNames.toTypedArray(),
                ftsFilterFieldIndices = ftsFilterFieldIndices,
                enableMmap = options.enableMmap,
                readOnly = options.readOnly,
                maxBufferSize = options.maxBufferSize,
            )
        }
    }
}
