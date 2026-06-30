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
         * Flatten one [IndexParams] into the JNI-friendly per-field slot shape.
         * The single source of truth for the per-arm encoding — used by both
         * [encode] (a schema's per-field loop) and [encodeIndexParams] (a single
         * field, for runtime `createIndex`). Pure: no JNI, no `.so`.
         *
         * Writes exactly the slots the C-side `build_index_params` reads for one
         * field: the [IndexKind] discriminator + the per-arm scalar(s) + (for FTS)
         * the flat-packed filter list. Non-applicable slots stay at their defaults
         * (0 / empty), matching the zero-fill the multi-field [encode] uses.
         */
        internal fun flattenIndexParams(params: IndexParams): SingleIndexSlot {
            return when (params) {
                is IndexParams.HnswParams -> SingleIndexSlot(
                    indexKind = IndexKind.HNSW,
                    m = params.m,
                    efConstruction = params.efConstruction,
                    metric = params.metric.toNative(),
                )
                is IndexParams.FlatParams -> SingleIndexSlot(
                    indexKind = IndexKind.FLAT,
                    metric = params.metric.toNative(),
                )
                is IndexParams.IvfParams -> SingleIndexSlot(
                    indexKind = IndexKind.IVF,
                    nList = params.nList,
                    nIters = params.nIters,
                    metric = params.metric.toNative(),
                )
                is IndexParams.InvertParams -> SingleIndexSlot(
                    indexKind = IndexKind.INVERT,
                    enableRangeOpt = params.enableRangeOptimization,
                )
                is IndexParams.FtsParams -> SingleIndexSlot(
                    indexKind = IndexKind.FTS,
                    ftsTokenizer = params.tokenizer,
                    ftsExtraParams = params.extraParams ?: "",
                    ftsFilters = params.filters,
                )
            }
        }

        /**
         * Flatten ONE [IndexParams] for a runtime `createIndex(field, params)`
         * call (issue 10). Returns the one-slot descriptor arrays the JNI layer's
         * `nativeCreateIndex` consumes (single element each, slot [0]). Reuses
         * [flattenIndexParams] so the per-arm encoding is identical to schema
         * construction's — a runtime-created HNSW index is byte-for-byte the same
         * params object a schema-declared one would build.
         */
        internal fun encodeIndexParams(params: IndexParams): SingleIndexDescriptor {
            val slot = flattenIndexParams(params)
            return SingleIndexDescriptor(
                indexKind = slot.indexKind,
                indexM = intArrayOf(slot.m),
                indexEfConstruction = intArrayOf(slot.efConstruction),
                indexNList = intArrayOf(slot.nList),
                indexNIters = intArrayOf(slot.nIters),
                indexMetric = intArrayOf(slot.metric),
                indexEnableRangeOpt = booleanArrayOf(slot.enableRangeOpt),
                ftsTokenizer = arrayOf(slot.ftsTokenizer),
                ftsExtraParams = arrayOf(slot.ftsExtraParams),
                ftsFilterNames = slot.ftsFilters.toTypedArray(),
                ftsFilterFieldIndices = intArrayOf(slot.ftsFilters.size),
            )
        }

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
                val slot = flattenIndexParams(params)
                fieldIndexTypes[i] = slot.indexKind
                when (params) {
                    is IndexParams.HnswParams,
                    is IndexParams.FlatParams,
                    is IndexParams.IvfParams -> {
                        // Vector-index scalars are already in `slot`; lay them out by
                        // kind (HNSW m/ef; IVF n_list/n_iters; all three carry metric).
                        indexMetric[i] = slot.metric
                        if (slot.indexKind == IndexKind.HNSW) {
                            indexM[i] = slot.m
                            indexEfConstruction[i] = slot.efConstruction
                        } else if (slot.indexKind == IndexKind.IVF) {
                            indexNList[i] = slot.nList
                            indexNIters[i] = slot.nIters
                        }
                    }
                    is IndexParams.InvertParams -> {
                        indexEnableRangeOpt[i] = slot.enableRangeOpt
                    }
                    is IndexParams.FtsParams -> {
                        ftsTokenizer[i] = slot.ftsTokenizer
                        ftsExtraParams[i] = slot.ftsExtraParams
                        // Record the count for this field, then append its filters.
                        ftsFilterFieldIndices[i] = slot.ftsFilters.size
                        ftsFilterNames.addAll(slot.ftsFilters)
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

/**
 * One field's index-params, decoded into the scalar slots the C-side
 * `build_index_params` reads. The pure-KVM intermediate [flattenIndexParams]
 * returns; [encode] lays its slots out across a schema's arrays, and
 * [encodeIndexParams] packs it into the one-slot [SingleIndexDescriptor]
 * arrays a runtime `createIndex` hands to the JNI layer. Non-applicable fields
 * stay at their zero/empty defaults — matching the multi-field zero-fill.
 *
 * @param indexKind the [SchemaDescriptor.IndexKind] discriminator.
 * @param m HNSW connectivity (HNSW only).
 * @param efConstruction HNSW ef_construction (HNSW only).
 * @param nList IVF n_list (IVF only).
 * @param nIters IVF n_iters (IVF only).
 * @param metric vector-index metric type (HNSW/IVF/FLAT).
 * @param enableRangeOpt INVERT range-optimization flag (INVERT only).
 * @param ftsTokenizer FTS tokenizer name; "" = keep C default (FTS only).
 * @param ftsExtraParams FTS extra params; "" = keep C default (FTS only).
 * @param ftsFilters FTS token-filter names, flat (FTS only).
 */
internal data class SingleIndexSlot(
    val indexKind: Int,
    val m: Int = 0,
    val efConstruction: Int = 0,
    val nList: Int = 0,
    val nIters: Int = 0,
    val metric: Int = 0,
    val enableRangeOpt: Boolean = false,
    val ftsTokenizer: String = "",
    val ftsExtraParams: String = "",
    val ftsFilters: List<String> = emptyList(),
)

/**
 * The one-slot descriptor arrays a runtime `createIndex(field, params)` call
 * hands to `nativeCreateIndex`. Each array is a SINGLE element (the one field's
 * slot at [0]), matching the shape `nativeSchemaCreateAndOpen` consumes per
 * field — so the shared JNI `build_index_params` reads them unchanged.
 * Built by [SchemaDescriptor.encodeIndexParams] from a [SingleIndexSlot].
 */
internal data class SingleIndexDescriptor(
    val indexKind: Int,
    val indexM: IntArray,
    val indexEfConstruction: IntArray,
    val indexNList: IntArray,
    val indexNIters: IntArray,
    val indexMetric: IntArray,
    val indexEnableRangeOpt: BooleanArray,
    val ftsTokenizer: Array<String>,
    val ftsExtraParams: Array<String>,
    val ftsFilterNames: Array<String>,
    val ftsFilterFieldIndices: IntArray,
)
