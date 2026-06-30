package io.github.tzhvh.scryernext.zvec

import java.io.Closeable
import java.io.File

/**
 * The collection handle: an application-scoped [OwnedHandle] wrapper over a zvec
 * `zvec_collection_t*`. Exactly one long-lived instance per collection per app
 * launch; close it deliberately (it is the single ownership boundary on the data
 * path). ADR 0005, Q8.
 *
 * The companion constructors ([createAndOpen] / [open]) build all transient C
 * handles (schema, fields, index params, options) internally and return only this
 * handle — the caller never manages a schema or query handle.
 *
 * Every method is `suspend` and the SDK **never hops threads internally** (ADR
 * 0007): zvec C calls are uncancellable native blocking calls, so the caller owns
 * the dispatcher and the cancellation boundary. Wrap a one-off call in
 * `withContext(Dispatchers.IO) { ... }`.
 *
 * Lifecycle: call [ensureNotClosed] (via the data-path methods landing in later
 * issues) before touching the native handle; [close] is idempotent.
 */
class ZvecCollection internal constructor(
    private val handle: OwnedHandle,
) : Closeable {

    /** True after [close]; data-path methods throw once this is set. */
    val isClosed: Boolean get() = handle.isClosed

    override fun close() = handle.close()

    /**
     * @throws ZvecException if the collection is closed.
     * @throws kotlin.coroutines.cancellation.CancellationException if the caller
     *   coroutine is cancelled (no internal dispatcher hop, so the call is
     *   uncancellable mid-flight — ADR 0007).
     */
    private fun ensureNotClosed() {
        check(!handle.isClosed) { "ZvecCollection is closed" }
    }

    /**
     * The raw `jlong`-wrapped collection pointer. Internal — the data path uses it
     * to dispatch to [ZvecNative]; no handle type escapes the SDK.
     */
    internal fun nativeHandle(): Long {
        ensureNotClosed()
        return handle.ptr
    }

    // ---- Single-doc writes (issue 03) -----------------------------------
    // insert / upsert take the typed builder DSL; the JNI layer flattens it and
    // dispatches each field to zvec_doc_add_field_by_value with its exact per-type
    // byte size, then runs the `_with_results` variant to surface the real per-doc
    // code (the bare insert returns ZVEC_OK even on a duplicate pk). All suspend:
    // zvec calls are uncancellable native blocking calls, so the caller owns the
    // dispatcher and the cancellation boundary (ADR 0007) — the SDK never hops.
    //
    // These throw [ZvecException] directly from the JNI layer (Q10): the native
    // call constructs it with the per-doc code + message, so the Kotlin op just
    // lets it propagate — no swallow, no rewrap.

    /**
     * Insert a single document built by [build]. Fails with
     * [ZvecErrorCode.ALREADY_EXISTS] if [ZvecDocBuilder.pk] already exists.
     *
     * @throws ZvecException on a duplicate pk, a required-field/validation error,
     *   or any engine error.
     */
    suspend fun insert(build: ZvecDocBuilder.() -> Unit) {
        ensureNotClosed()
        val desc = ZvecDocBuilder().apply(build).build()
        write(desc, WriteOp.OP_INSERT)
    }

    /**
     * Upsert (insert-or-overwrite) a single document built by [build]. Succeeds
     * whether or not [ZvecDocBuilder.pk] already exists.
     *
     * @throws ZvecException on a validation error or any engine error.
     */
    suspend fun upsert(build: ZvecDocBuilder.() -> Unit) {
        ensureNotClosed()
        val desc = ZvecDocBuilder().apply(build).build()
        write(desc, WriteOp.OP_UPSERT)
    }

    private fun write(desc: DocDescriptor, op: Int) {
        ZvecNative.nativeDocWrite(
            handle = nativeHandle(),
            op = op,
            pk = desc.pk,
            fieldCount = desc.fieldCount,
            fieldNames = desc.fieldNames,
            kinds = desc.kinds,
            scalarIndex = desc.scalarIndex,
            isNull = desc.isNull,
            longs = desc.longs,
            doubles = desc.doubles,
            bools = desc.bools,
            strings = desc.strings,
            f32Vecs = desc.f32Vecs,
            f16Vecs = desc.f16Vecs,
            i8Vecs = desc.i8Vecs,
        )
    }

    /**
     * Delete a single document by [pk]. Fails with [ZvecErrorCode.NOT_FOUND] if
     * [pk] does not exist.
     *
     * @throws ZvecException on a missing pk or any engine error.
     */
    suspend fun delete(pk: String) {
        ensureNotClosed()
        ZvecNative.nativeDelete(nativeHandle(), pk)
    }

    /**
     * Minimal typed fetch for issue 03's round-trip test — the "scratch read
     * helper" the issue sanctions when 05 (the projection fetch surface) hasn't
     * landed. There is no `zvec_doc_get_field_type`, so [fieldTypes] supplies the
     * caller-known type per field; the JNI layer reads each value with its getter
     * and returns a flat array this helper unpacks into a [ZvecDoc].
     *
     * Internal: 05's real projection surface supersedes this. Returns `null` when
     * [pk] is absent.
     */
    internal suspend fun fetchTyped(
        pk: String,
        fieldNames: Array<String>,
        fieldTypes: IntArray,
    ): ZvecDoc? {
        ensureNotClosed()
        val raw = ZvecNative.nativeFetchTyped(nativeHandle(), pk, fieldNames, fieldTypes)
            ?: return null
        return unpackFetchTyped(raw, fieldNames)
    }

    /**
     * Unpack the flat `Object[]` from [ZvecNative.nativeFetchTyped] into a typed
     * [ZvecDoc]. Layout: [0]=pk, [1]=score (null), then one boxed value per field
     * in [fieldNames] order. The JNI layer boxes each value to its JDK wrapper
     * (Boolean/Long/Double/String) or primitive array (float[]/short[]/byte[]);
     * a `null` slot is a stored null → [ZvecValue.Null]. This helper is the typed
     * construction the JNI layer deliberately keeps on the JVM side.
     */
    @Suppress("UNCHECKED_CAST")
    private fun unpackFetchTyped(raw: Any, fieldNames: Array<String>): ZvecDoc {
        val arr = raw as Array<Any?>
        val pk = arr[0] as String
        val score = arr[1] as? Float
        val fields = LinkedHashMap<String, ZvecValue>(fieldNames.size)
        fieldNames.forEachIndexed { i, name ->
            val v = arr[2 + i]
            fields[name] = when (v) {
                is Boolean -> ZvecValue.ScalarBool(v)
                is Long -> ZvecValue.ScalarInt(v)
                is Double -> ZvecValue.ScalarFloat(v)
                is String -> ZvecValue.Str(v)
                is FloatArray -> ZvecValue.VecF32(v)
                is ShortArray -> ZvecValue.VecF16(v)
                is ByteArray -> ZvecValue.VecI8(v)
                null -> ZvecValue.Null
                else -> error("unsupported fetch-typed value for '$name': ${v::class.java}")
            }
        }
        return ZvecDoc(pk = pk, score = score, fields = fields)
    }

    private object WriteOp {
        // Mirror of the OP_* constants in zvec_jni_doc.cc.
        const val OP_INSERT = 0
        const val OP_UPSERT = 1
    }

    companion object {
        /**
         * Create a new collection directory at [path] from [schema], opened for use.
         * Errors if [path] already exists (use [open] to reopen).
         *
         * **Implicit validation, before any disk write** (Q8): the C schema is built,
         * then `zvec_collection_schema_validate` runs, then — only on success —
         * `zvec_collection_create_and_open` touches disk. An invalid schema (e.g. a
         * vector field missing [FieldSchema.dimension]) throws [ZvecException] with
         * [ZvecErrorCode.INVALID_ARGUMENT] and a non-null [ZvecException.detail],
         * **and leaves no directory on disk**.
         *
         * @throws ZvecException on validation failure, a pre-existing path
         *   ([ZvecErrorCode.ALREADY_EXISTS]), or any engine error.
         */
        suspend fun createAndOpen(
            path: File,
            schema: CollectionSchema,
            options: CollectionOptions = CollectionOptions(),
        ): ZvecCollection {
            Zvec.ensureInitialized()
            val descriptor = SchemaDescriptor.encode(schema, options)
            val ptr = ZvecNative.nativeSchemaCreateAndOpen(
                path = path.absolutePath,
                schemaName = descriptor.schemaName,
                fieldCount = descriptor.fieldCount,
                fieldNames = descriptor.fieldNames,
                fieldDataTypes = descriptor.fieldDataTypes,
                fieldNullable = descriptor.fieldNullable,
                fieldDimensions = descriptor.fieldDimensions,
                fieldIndexTypes = descriptor.fieldIndexTypes,
                // HNSW / IVF (vector params).
                indexM = descriptor.indexM,
                indexEfConstruction = descriptor.indexEfConstruction,
                indexNList = descriptor.indexNList,
                indexNIters = descriptor.indexNIters,
                indexMetric = descriptor.indexMetric,
                // INVERT.
                indexEnableRangeOpt = descriptor.indexEnableRangeOpt,
                // FTS — variable-length, encoded into the separate arrays below.
                ftsTokenizer = descriptor.ftsTokenizer,
                ftsExtraParams = descriptor.ftsExtraParams,
                ftsFilterNames = descriptor.ftsFilterNames,
                ftsFilterFieldIndices = descriptor.ftsFilterFieldIndices,
                // Options.
                enableMmap = descriptor.enableMmap,
                readOnly = descriptor.readOnly,
                maxBufferSize = descriptor.maxBufferSize,
            )
            return wrapHandle(ptr)
        }

        /**
         * Reopen an existing collection at [path] (every app launch except the
         * first). Succeeds on a clean, previously-closed collection; errors
         * [ZvecErrorCode.NOT_FOUND] (or engine equivalent) on a nonexistent path.
         *
         * **No stale-`LOCK` recovery.** Crash-recovery heuristics (detect `lock` in
         * the error message, delete `$dbPath/LOCK`, retry once) are deliberately
         * out of scope — they're a caller policy about how aggressive to be after a
         * crash, and matching the error substring couples the SDK to zvec's error-
         * string format. Phase 2's repository layer owns that. See the phase doc's
         * "Handoff to Phase 2".
         *
         * @throws ZvecException on a nonexistent path, a corrupted collection, or
         *   any engine error.
         */
        suspend fun open(
            path: File,
            options: CollectionOptions = CollectionOptions(),
        ): ZvecCollection {
            Zvec.ensureInitialized()
            val ptr = ZvecNative.nativeCollectionOpen(
                path = path.absolutePath,
                enableMmap = options.enableMmap,
                readOnly = options.readOnly,
                maxBufferSize = options.maxBufferSize,
            )
            return wrapHandle(ptr)
        }

        private fun wrapHandle(ptr: Long): ZvecCollection {
            require(ptr != 0L) { "native create/open returned a null handle without throwing" }
            return ZvecCollection(
                NativeOwnedHandle(ptr) { ZvecNative.nativeClose(it) }
            )
        }
    }
}
