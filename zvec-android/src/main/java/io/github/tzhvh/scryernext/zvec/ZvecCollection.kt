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
