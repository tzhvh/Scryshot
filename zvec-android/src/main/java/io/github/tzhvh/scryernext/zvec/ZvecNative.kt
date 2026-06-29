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
    @JvmStatic external fun nativeClose(handle: Long)
}
