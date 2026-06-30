package io.github.tzhvh.scryernext.zvec

object ZvecNative {
    init {
        System.loadLibrary("zvec_c_api")
        System.loadLibrary("zvec_jni")
    }

    // @JvmStatic: these are native methods on a Kotlin object. Without it, kotlinc
    // emits them as INSTANCE methods, so JNI passes the singleton jobject (not the
    // jclass) as the 2nd arg — a type contract violation that only happens to work
    // because jobject/jclass are both opaque pointers at the ABI level. @JvmStatic
    // makes them true statics, so JNI hands jclass, matching the C++ signatures
    // (Java_..._ZvecNative_native*(JNIEnv*, jclass, ...)).
    @JvmStatic external fun nativeGetVersion(): String

    // ---- Collection lifecycle (issue 02) ---------------------------------
    // The schema tree is flattened into parallel primitive arrays by
    // SchemaDescriptor (pure Kotlin); the JNI layer walks them once to build every
    // C schema/field/index-params/options handle under RAII guards, runs
    // zvec_collection_schema_validate BEFORE create_and_open touches disk, and
    // returns only the collection handle. No schema handle escapes.
    @JvmStatic external fun nativeSchemaCreateAndOpen(
        path: String,
        schemaName: String,
        fieldCount: Int,
        fieldNames: Array<String>,
        fieldDataTypes: IntArray,
        fieldNullable: BooleanArray,
        fieldDimensions: IntArray,
        fieldIndexTypes: IntArray,
        // Vector-index scalars (HNSW m/efConstruction; IVF nList/nIters; metric).
        indexM: IntArray,
        indexEfConstruction: IntArray,
        indexNList: IntArray,
        indexNIters: IntArray,
        indexMetric: IntArray,
        // INVERT.
        indexEnableRangeOpt: BooleanArray,
        // FTS — per-field tokenizer/extraParams (empty = keep C default) + the
        // packed filter list (ftsFilterNames) with a per-field count
        // (ftsFilterFieldIndices).
        ftsTokenizer: Array<String>,
        ftsExtraParams: Array<String>,
        ftsFilterNames: Array<String>,
        ftsFilterFieldIndices: IntArray,
        // Options.
        enableMmap: Boolean,
        readOnly: Boolean,
        maxBufferSize: Long,
    ): Long

    // Reopen an existing collection (zvec_collection_open, c_api.h:2989). No
    // stale-LOCK recovery — that's the caller's concern (see the phase doc's
    // "Handoff to Phase 2").
    @JvmStatic external fun nativeCollectionOpen(
        path: String,
        enableMmap: Boolean,
        readOnly: Boolean,
        maxBufferSize: Long,
    ): Long

    // Phase-0 probe: hardcoded two-field schema. Kept for the regression guard
    // (ZvecRoundtripTest); issue 02's nativeSchemaCreateAndOpen generalizes it.
    @JvmStatic external fun nativeCreateAndOpen(path: String, schemaName: String): Long

    // Minimal fetch-by-pk read path for issue 02's reopen test (issue 05 lands the
    // real projection surface). Returns the found pks; the reopen test only needs
    // to prove the reopened collection retained a doc. The JNI layer copies pks
    // out and frees the doc array via zvec_docs_free before returning.
    @JvmStatic external fun nativeFetchPks(handle: Long, pks: Array<String>): Array<String>?

    // ---- Config / init (issue 01) ---------------------------------------
    // nativeInitialize builds a zvec_config_data_t from the typed ZvecConfig
    // (memory limit, thread counts, optional file log sink) and calls
    // zvec_initialize. Returns the C zvec_error_code_t (0 = OK) so the SDK can
    // map it to ZvecErrorCode and build ZvecException itself — the C layer does
    // NOT throw, so that a second init surfaces as ALREADY_EXISTS rather than a
    // native exception. nullConfig = zvec's cgroup-derived defaults.
    @JvmStatic external fun nativeInitialize(
        nullConfig: Boolean,
        memoryLimitBytes: Long,
        queryThreadCount: Int,
        optimizeThreadCount: Int,
        // File-log sink. When nullLogConfig is true the rest are ignored.
        nullLogConfig: Boolean,
        logLevel: Int,
        logDir: String,
        logBasename: String,
        logSizeMb: Long,
        logOverdueDays: Long,
    ): Int

    @JvmStatic external fun nativeIsInitialized(): Boolean

    // Best-effort raw detail string for the last zvec-level failure (mirrors the
    // zvec_get_last_error call the throwing macros make). Null when zvec set none.
    @JvmStatic external fun nativeGetLastError(): String?
    // title + content both populated: the probe schema declares two NON-nullable
    // string fields, so a valid doc must supply both. Phase 1 replaces this with a
    // real typed builder.
    @JvmStatic external fun nativeInsertString(handle: Long, pk: String, title: String, content: String): Int

    // ---- Doc writes (issue 03) ------------------------------------------
    // Single-doc insert / upsert / delete. The builder's typed fields arrive
    // flattened into parallel primitive arrays (DocDescriptor); the JNI layer
    // walks them once, building one zvec_doc_t under DocGuard and dispatching each
    // field to zvec_doc_add_field_by_value with its exact per-type byte size.
    //
    // CRITICAL: these use the `_with_results` C variants and read results[0].code.
    // The bare zvec_collection_insert returns ZVEC_OK even when the doc fails
    // (duplicate pk etc.) — it only bumps error_count — so the bare call would
    // SILENTLY swallow a per-doc failure. `_with_results` surfaces the real code
    // (ALREADY_EXISTS / NOT_FOUND / …) which the JNI layer turns into the
    // ZvecException the SDK contract promises. `op` selects insert/upsert.
    //
    // Returns the written pk on success; throws ZvecException on any per-doc
    // non-OK code (the JNI layer throws — the Kotlin op lets it propagate).
    @JvmStatic external fun nativeDocWrite(
        handle: Long,
        op: Int,
        pk: String,
        fieldCount: Int,
        fieldNames: Array<String>,
        kinds: IntArray,
        scalarIndex: IntArray,
        isNull: BooleanArray,
        longs: LongArray,
        doubles: DoubleArray,
        bools: BooleanArray,
        strings: Array<String>,
        f32Vecs: Array<FloatArray>,
        f16Vecs: Array<ShortArray>,
        i8Vecs: Array<ByteArray>,
    ): String

    // Single-doc delete via zvec_collection_delete_with_results (same per-doc-code
    // discipline as nativeDocWrite — the bare delete returns ZVEC_OK on a missing
    // pk). Returns the pk on success; throws ZvecException on a per-doc non-OK code.
    @JvmStatic external fun nativeDelete(handle: Long, pk: String): String

    // ---- Doc writes (issue 04: bulk + per-doc results) -------------------
    // Bulk insert/upsert/update over a batch of docs, returning PER-DOC results
    // (Q10) instead of throwing on the first failure. The JNI layer builds each
    // zvec_doc_t from its DocDescriptor (the same per-field kind-switch as the
    // single-doc path), packs the whole batch into one `const zvec_doc_t**`
    // array, and calls the op-selected `_with_results` variant ONCE. It then
    // walks the full results[] array (result index = input doc index, c_api.h:3144)
    // under WriteResultsGuard, copying each per-doc code + message into the
    // returned parallel arrays. An API-level non-OK throws ZvecException; a
    // per-doc non-OK code is RETURNED (not thrown) so the SDK can assemble
    // WriteResult.failures.
    //
    // CRITICAL: the empty-batch short-circuit happens in Kotlin (ZvecCollection)
    // — zvec's `_with_results` calls reject doc_count==0 with INVALID_ARGUMENT
    // (c_api.cc:6380/6450/6520/6594), so an empty batch MUST NOT reach native.
    //
    // Returns [0]=int[] per-doc codes (index-aligned to the input docs), [1]=
    // String[] per-doc messages (nullable elements; null where the engine set
    // none). The Kotlin side folds these into WriteResult(successCount, failures).
    // op selects insert/upsert/update (mirrors nativeDocWrite's op).
    //
    // The descriptor arrays are PER-DOC parallel arrays-of-arrays (one slot per
    // doc): pks[doc], fieldCounts[doc], fieldNames[doc] (String[][]), and the
    // per-type value arrays[doc]. The JNI layer loops docs once, and for each
    // loops its fields once — the same nested structure as nativeDocWrite, lifted
    // one level. Not `internal` — same symbol-mangling caveat as nativeFetchTyped.
    @JvmStatic external fun nativeDocWriteBulk(
        handle: Long,
        op: Int,
        pks: Array<String>,
        fieldCounts: IntArray,
        fieldNames: Array<Array<String>>,
        kinds: Array<IntArray>,
        scalarIndex: Array<IntArray>,
        isNull: Array<BooleanArray>,
        longs: Array<LongArray>,
        doubles: Array<DoubleArray>,
        bools: Array<BooleanArray>,
        strings: Array<Array<String>>,
        f32Vecs: Array<Array<FloatArray>>,
        f16Vecs: Array<Array<ShortArray>>,
        i8Vecs: Array<Array<ByteArray>>,
    ): Array<Any?>

    // Bulk delete by pk via zvec_collection_delete_with_results. Same per-doc
    // result discipline as nativeDocWriteBulk (NOT_FOUND per missing pk, returned
    // not thrown). Returns [0]=int[] codes, [1]=String[] messages. Empty-list
    // short-circuit happens in Kotlin.
    @JvmStatic external fun nativeDeleteAll(handle: Long, pks: Array<String>): Array<Any?>

    // Delete by filter via zvec_collection_delete_by_filter. Returns Unit: the C
    // engine does NOT report a deleted count (c_api.h:3284 has no count out-param
    // despite its doc-comment; collection.cc:1633 returns only Status::OK(); both
    // the Rust and Go reference bindings surface only an error). A stats before/
    // after diff was considered and rejected — it races the concurrent SAF
    // ingestion worker and yields negative counts. Throws ZvecException on a
    // non-OK engine code; otherwise returns nothing. See ZvecCollection's KDoc.
    @JvmStatic external fun nativeDeleteByFilter(handle: Long, filter: String)

    // Minimal typed fetch for issue 03's round-trip test — the "scratch read
    // helper" the issue sanctions when 05 (the projection fetch surface) hasn't
    // landed. There is no zvec_doc_get_field_type, so the read getters need a
    // caller-supplied type per field; fieldTypes supplies it (the builder knows
    // the types it wrote). Returns the single fetched ZvecDoc serialized as a
    // flat array (pk + score + per-field values) or null if the pk is absent.
    //
    // NOTE: not `internal` — Kotlin mangles an internal external fun's symbol
    // with a module suffix (`nativeFetchTyped$zvec_android_debug`), so the JNI
    // lookup `Java_..._nativeFetchTyped` would never bind (UnsatisfiedLinkError).
    // Visibility is gated at ZvecCollection.fetchTyped (internal) instead.
    @JvmStatic external fun nativeFetchTyped(
        handle: Long,
        pk: String,
        fieldNames: Array<String>,
        fieldTypes: IntArray,
    ): Any?

    // ---- Fetch + projection (issue 05) ----------------------------------
    // The public read path: fetch by pks with an optional field projection and
    // the load-bearing includeVector=false default. The JNI layer calls
    // zvec_collection_fetch (c_api.h:3332), wraps the result array in DocsGuard,
    // and runs the shared `collect_docs` helper to copy each doc out into a flat
    // Object[] row. There is no zvec_doc_get_field_type, so collect_docs resolves
    // each field's data_type from the collection schema (via
    // zvec_collection_get_schema) — the public fetch() takes no caller types.
    //
    // Returns `Array<Any?>` = one Object[] row per FOUND doc (absent pks are
    // omitted by the engine; order is NOT guaranteed). Row layout (names inline,
    // so it's correct under a projection and the engine's nullable-field re-add):
    //   [0]     = pk (String)
    //   [1]     = score (Float — engine value; query-only, Kotlin fetch nulls it)
    //   [2]     = present-field count (Int)
    //   [3..]   = interleaved (name: String, value: boxed) pairs, one per field.
    // A boxed value of null = stored null → ZvecValue.Null (distinct from an
    // absent name = not requested under the projection).
    @JvmStatic external fun nativeFetch(
        handle: Long,
        pks: Array<String>,
        outputFields: Array<String>?,
        includeVector: Boolean,
    ): Array<Any?>

    // ---- Query (issue 06) --------------------------------------------------
    // Single-vector / pure-FTS query. The JNI layer builds a vector_query_t, sets
    // field + topK + output_fields + (optional) filter, and either attaches a
    // query vector (vector mode) or an fts_t payload carrying the FTS match string
    // (FTS mode). fts_t is COPIED into the query by zvec_vector_query_set_fts, so
    // the FtsGuard always frees it. Then zvec_collection_query runs, the result
    // array is wrapped in DocsGuard, and the shared collect_docs (issue 05) copies
    // each doc out into the same flat Object[] row fetch uses — including the
    // engine's raw score at slot [1], which the Kotlin query() path keeps (unlike
    // fetch, which nulls it).
    //
    // Kotlin validates exactly-one-of(vector, fts) is set before calling; the JNI
    // layer treats `vector != null` as vector mode and `fts != null` as FTS mode.
    // Returns the same `Array<Any?>` shape as nativeFetch (one Object[] row per
    // result doc). All query handles (VectorQueryGuard/FtsGuard/DocsGuard) are
    // freed on any exit path — the caller never sees a handle (Q7).
    @JvmStatic external fun nativeQuery(
        handle: Long,
        field: String,
        vector: FloatArray?,
        fts: String?,
        filter: String?,
        topK: Int,
        outputFields: Array<String>?,
    ): Array<Any?>

    // ---- Hybrid search (issue 07) ------------------------------------------
    // Multi-query fusion (vector + FTS legs, fused via RRF or weighted reranker).
    // The JNI layer builds a multi_query_t, then for EACH sub-query builds a
    // sub_query_t (set field_name + optional num_candidates + a query vector OR an
    // fts_t payload), and adds it to the multi-query. CRITICAL: add_sub_query
    // COPIES the sub-query (c_api.h:2144; confirmed by both reference bindings —
    // the caller ALWAYS retains/frees it), so the SubQueryGuard always frees it,
    // success or failure (the FtsGuard pattern from 06, NOT adopt-on-success).
    //
    // The reranker selects the fusion setter: RRF → zvec_multi_query_set_rerank_rrf
    // (rankConstant); Weighted → zvec_multi_query_set_rerank_weighted with a
    // POSITIONAL double[] aligned to the sub-query order (Kotlin passes a field-
    // keyed map; the Kotlin layer resolves field→position before calling). Then
    // zvec_multi_query_set_topk (post-fusion count), set_filter, set_output_fields,
    // zvec_collection_multi_query, and collect_docs (issue 05) walks the result
    // array into the same flat Object[] rows query/fetch use. All handles
    // (MultiQueryGuard / SubQueryGuard / FtsGuard / DocsGuard) freed on any exit.
    //
    // The sub-query legs arrive as parallel arrays (one slot per leg):
    //  - subFields[leg], subHasVector[leg] (vector vs FTS mode), subNumCands[leg]
    //  - subVectors[leg]: the FP32 vector (only meaningful when subHasVector[leg])
    //  - subFts[leg]: the FTS match string (only when !subHasVector[leg])
    // rerankerMode: 0 = RRF (rerankerParam = rankConstant), 1 = Weighted (the
    // weightedWeights array is consulted; rerankerParam unused). An empty subFields
    // is rejected by the engine — Kotlin validates queries.size >= 1 before calling.
    // Returns the same `Array<Any?>` shape as nativeQuery (one Object[] row per
    // result doc). Empty result is a zero-length array.
    @JvmStatic external fun nativeMultiQuery(
        handle: Long,
        subFields: Array<String>,
        subHasVector: BooleanArray,
        subVectors: Array<FloatArray>,
        subFts: Array<String?>,
        subNumCands: IntArray,
        rerankerMode: Int,
        rankConstant: Int,
        weightedWeights: DoubleArray,
        topK: Int,
        filter: String?,
        outputFields: Array<String>?,
    ): Array<Any?>

    @JvmStatic external fun nativeClose(handle: Long)
}
