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

    @JvmStatic external fun nativeClose(handle: Long)
}
