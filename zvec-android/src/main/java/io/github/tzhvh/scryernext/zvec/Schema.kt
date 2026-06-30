package io.github.tzhvh.scryernext.zvec

/**
 * Typed mirror of zvec's `zvec_data_type_t` (`c_api.h:787`). The values handed to
 * zvec are a stability surface, so [toNative] uses an explicit map (not `ordinal`)
 * — a reorder here can never silently drift the int the engine receives.
 *
 * The set is the immediate product path (screenshots = scalars + one FP32
 * vector). ARRAY_* and SPARSE_VECTOR_* types are deferred with their matching
 * `ZvecValue` arms (the data-model `ScalarArray` note); adding one is a new enum
 * entry + a new `when` branch — additive, no caller breaks.
 */
enum class FieldType {
    STRING,
    INT32,
    INT64,
    UINT32,
    UINT64,
    FLOAT,
    DOUBLE,
    BOOL,
    VECTOR_FP32,
    VECTOR_FP16,
    VECTOR_INT8;

    /** C-side `zvec_data_type_t` value; the JNI layer consumes this. */
    internal fun toNative(): Int = ZVEC_DATA_TYPES[this] ?: ZVEC_DATA_TYPE_STRING

    private companion object {
        const val ZVEC_DATA_TYPE_STRING = 2

        // Explicit (not ordinal) so the C values are pinned regardless of enum order.
        val ZVEC_DATA_TYPES = mapOf(
            STRING to 2,
            BOOL to 3,
            INT32 to 4,
            INT64 to 5,
            UINT32 to 6,
            UINT64 to 7,
            FLOAT to 8,
            DOUBLE to 9,
            VECTOR_FP16 to 22,
            VECTOR_FP32 to 23,
            VECTOR_INT8 to 26,
        )
    }
}

/**
 * Typed mirror of zvec's `zvec_metric_type_t` (`c_api.h:840`). Used by the vector
 * index-param arms ([HnswParams] / [FlatParams] / [IvfParams]).
 *
 * `MIPSL2` exists in C (`ZVEC_METRIC_TYPE_MIPSL2`, 4) but is intentionally not
 * exposed: it has no caller and its semantics (a hybrid metric) belong to search-
 * quality tuning (Phase 4+), not the schema surface. Additive later as a new entry.
 */
enum class MetricType {
    L2,
    IP,
    COSINE;

    /** C-side `zvec_metric_type_t` value; the JNI layer consumes this. */
    internal fun toNative(): Int = ZVEC_METRICS[this] ?: ZVEC_METRIC_TYPE_COSINE

    private companion object {
        const val ZVEC_METRIC_TYPE_COSINE = 3

        val ZVEC_METRICS = mapOf(
            L2 to 1,
            IP to 2,
            COSINE to 3,
        )
    }
}

/**
 * Parameters for an index on a [FieldSchema]. Sealed so the JNI build path is a
 * flat `when`, not reflection on `Any?` — each arm maps to one C
 * `zvec_index_params_create(type)` + its setter(s), matching the Rust oracle's
 * per-type constructors.
 *
 * `VamanaParams` exists in C (`ZVEC_INDEX_TYPE_VAMANA`) but is deferred until
 * on-disk indexing is proven on Android (roadmap Phase 7). A quantize knob
 * (`set_quantize_type`) is intentionally absent — it has no measured caller until
 * Phase 3's embeddings, and is additive later as an optional field.
 */
sealed interface IndexParams {
    /** HNSW graph index (`c_api.h:971`). The common vector index for screenshots. */
    data class HnswParams(
        val m: Int,
        val efConstruction: Int,
        val metric: MetricType = MetricType.COSINE,
    ) : IndexParams

    /** Flat (brute-force) vector index (`c_api.h:107` — `create(FLAT)` + metric). */
    data class FlatParams(
        val metric: MetricType = MetricType.COSINE,
    ) : IndexParams

    /** IVF index (`c_api.h:1029`). `useSoar` is not exposed (no caller); C default false. */
    data class IvfParams(
        val nList: Int,
        val nIters: Int,
        val metric: MetricType = MetricType.COSINE,
    ) : IndexParams

    /** Inverted index for scalar fields (`c_api.h:1062`). `enableWildcard` is not
     *  exposed (no caller); C default false. */
    data class InvertParams(
        val enableRangeOptimization: Boolean = false,
    ) : IndexParams

    /** Full-text-search index (`c_api.h:1074`). [extraParams] null keeps the C default. */
    data class FtsParams(
        val tokenizer: String = "standard",
        val filters: List<String> = emptyList(),
        val extraParams: String? = null,
    ) : IndexParams
}

/**
 * One field's schema — pure data; the SDK translates it to a C
 * `zvec_field_schema_t` internally inside `createAndOpen` (the handle never
 * escapes). ADR 0006 / Q8.
 *
 * [dimension] is `null`-defaulted: a vector field *without* a dimension is an
 * invalid schema, and `createAndOpen` surfaces that as a [ZvecException] from the
 * C validator before any disk write (rather than rejecting it at the type level,
 * which would hide the failure behind a compile error instead of the loud
 * startup fail-fast the app needs). Non-vector fields ignore [dimension].
 */
data class FieldSchema(
    val name: String,
    val type: FieldType,
    val nullable: Boolean = false,
    val dimension: Int? = null,
    val indexParams: IndexParams? = null,
)

/**
 * A collection's schema — pure data. The SDK builds the matching C schema (+
 * each field's borrowed index params) inside `createAndOpen` with the RAII guards
 * Phase 0 ships, runs the C validator, and frees every transient handle. The
 * caller never manages a schema handle. Q8.
 */
data class CollectionSchema(
    val name: String,
    val fields: List<FieldSchema>,
)
