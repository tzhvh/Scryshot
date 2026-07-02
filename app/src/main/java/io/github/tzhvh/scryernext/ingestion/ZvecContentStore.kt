/* -*- Mode: Java; c-basic-offset: 4; tab-width: 4; indent-tabs-mode: nil; -*-
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.tzhvh.scryernext.ingestion

import android.util.Log
import io.github.tzhvh.scryernext.zvec.CollectionOptions
import io.github.tzhvh.scryernext.zvec.CollectionSchema
import io.github.tzhvh.scryernext.zvec.FieldSchema
import io.github.tzhvh.scryernext.zvec.FieldType
import io.github.tzhvh.scryernext.zvec.IndexParams
import io.github.tzhvh.scryernext.zvec.QueryRequest
import io.github.tzhvh.scryernext.zvec.Zvec
import io.github.tzhvh.scryernext.zvec.ZvecCollection
import io.github.tzhvh.scryernext.zvec.ZvecConfig
import io.github.tzhvh.scryernext.zvec.ZvecDoc
import io.github.tzhvh.scryernext.zvec.ZvecException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * zvec Phase 2, issue 03 — owns the screenshot-content [ZvecCollection] and its lifecycle.
 *
 * The single place the app holds the zvec collection handle: one long-lived instance per app launch
 * ([ZvecCollection]'s class KDoc mandates "exactly one long-lived instance per collection per app
 * launch"), held here as a private field. No per-op `use {}` — the handle is closed only deliberately
 * via [onTrimMemoryComplete] (the one place `close()` is called outside tear-down; it trades a re-open
 * latency for reclaimed native memory under pressure). Constructed at app start by
 * `ScryerApplication`, which forwards `onTrimMemory(TRIM_MEMORY_COMPLETE)` here.
 *
 * ## Lifecycle
 *
 * - **Open:** lazy on first use via [ensureOpen] — `Zvec.init(androidDefaults)` (idempotent), then
 *   [openOrCreate], which `createAndOpen`s on first launch and `open`s on subsequent launches, with
 *   **stale-LOCK recovery** (pinned Phase-1 contract #1): on an open failure whose
 *   [ZvecException.detail] carries the lock signal, delete `$dbPath/LOCK` and retry **once**. This is
 *   deliberately the caller's policy, not the SDK's — the SDK performs no automatic recovery on
 *   crash-left lock files (`ZvecCollection.open` KDoc).
 * - **Close:** [onTrimMemoryComplete] flushes + closes + nulls the reference; the next data-path call
 *   re-opens via [openOrCreate].
 *
 * ## Schema
 *
 * The four-field screenshot-content schema (`content_hash` PK + `locator` + `content` FTS +
 * `collection_id` INVERT) is declared in full at [createAndOpen] — column DDL is deferred to Phase 3
 * (pinned contract #3). No `image_embedding` (Phase 3 adds it via `add_column`). Verified engine-valid
 * against the pinned source: `CollectionSchema::validate()` requires only a non-empty field list; the
 * vector check is an *upper* bound, so a zero-vector-field schema creates cleanly.
 *
 * ## Dispatcher
 *
 * Every native zvec call is wrapped in `withContext(Dispatchers.IO)` (ADR 0007 — the SDK never hops
 * threads internally; the caller owns the dispatcher and the cancellation boundary).
 *
 * Issue 04's `ZvecScreenshotRepository` façade absorbs this store for the read-flip (search +
 * `getContentText` route to [fetch]; the gallery + collections stay on Room).
 */
open class ZvecContentStore(
    private val filesDir: File,
    private val debug: Boolean,
) {
    /** Fixed app-private path for the screenshot-content collection directory. */
    private val collectionPath: File = File(filesDir, "zvec/screenshots").also { it.parentFile?.mkdirs() }

    /**
     * The one long-lived collection handle, or null after [onTrimMemoryComplete] until the next
     * data-path call re-opens it. Guarded by [openMutex] so a concurrent first-access pair (e.g. the
     * engine + a detail-page write racing on cold start) doesn't double-open.
     */
    private var collection: ZvecCollection? = null
    private val openMutex = Mutex()

    /**
     * Lazily open (or create) the collection if it isn't already held. Idempotent under concurrency
     * via [openMutex]. All native entry points route through here so the handle is guaranteed live
     * before any `nativeHandle()` call.
     */
    private suspend fun ensureOpen() {
        if (collection != null && !collection!!.isClosed) return
        openMutex.withLock {
            if (collection != null && !collection!!.isClosed) return@withLock
            initializeZvec()
            collection = openOrCreate()
        }
    }

    /**
     * Initialize the process-wide zvec library once. `open` so a JVM test can stub it out (the real
     * path touches native via [Zvec.init]; the LOCK-recovery unit test overrides this to a no-op so
     * it can exercise [openOrCreate] without the `.so`).
     */
    protected open suspend fun initializeZvec() {
        Zvec.init(ZvecConfig.androidDefaults(filesDir, debug))
    }

    /**
     * Create-or-open + stale-LOCK recovery (pinned Phase-1 contract #1). First launch
     * `createAndOpen`s; subsequent launches `open`. On an open failure whose [ZvecException.detail]
     * carries the lock signal, delete `$collectionPath/LOCK` and retry **once** — a second failure is
     * a real corruption/permission error, not a stale lock, so it rethrows. The `"lock"` substring
     * match couples this one method to zvec's error-string format; accepted (the SDK deliberately did
     * not codify the signal) and isolated here.
     *
     * `open` and `createAndOpen` are split into protected hooks ([openExisting]/[createNew]) so a JVM
     * test can subclass and fake the lock exception without the `.so` (see `ZvecContentStoreTest`).
     */
    private suspend fun openOrCreate(): ZvecCollection = withContext(Dispatchers.IO) {
        try {
            openOrRetry()
        } catch (e: ZvecException) {
            if (isStaleLock(e) && collectionPath.resolve(LOCK_FILE).delete()) {
                openOrRetry()
            } else throw e
        }
    }

    /** The first-or-retry attempt — picks `open` vs `createAndOpen` on path existence. */
    private suspend fun openOrRetry(): ZvecCollection =
        if (collectionPath.exists()) openExisting(collectionPath, options())
        else createNew(collectionPath, screenshotContentSchema(), options())

    /** The stale-LOCK heuristic: the lock signal in [ZvecException.detail] (case-insensitive). */
    private fun isStaleLock(e: ZvecException): Boolean =
        e.detail?.contains("lock", ignoreCase = true) == true

    /** Hook for tests: reopen an existing collection. Production calls [ZvecCollection.open]. */
    protected open suspend fun openExisting(path: File, options: CollectionOptions): ZvecCollection =
        ZvecCollection.open(path, options)

    /** Hook for tests: create + open a fresh collection. Production calls [ZvecCollection.createAndOpen]. */
    protected open suspend fun createNew(
        path: File,
        schema: CollectionSchema,
        options: CollectionOptions,
    ): ZvecCollection = ZvecCollection.createAndOpen(path, schema, options)

    /**
     * Upsert a screenshot-content doc on the content_hash PK. R8 (pinned contract #9, confirmed by
     * live probe) makes this idempotent: re-ingesting the same content is a no-op in-place overwrite,
     * not a duplicate. `content` is the OCR text (empty string for failed OCR — the processed-but-empty
     * case), `locator` the volatile uri, `collectionId` the FK to the Room `CollectionModel`.
     *
     * `open` so a JVM test can record calls without the `.so` (the production body touches native).
     */
    open suspend fun upsert(contentHash: String, locator: String, content: String, collectionId: String) {
        ensureOpen()
        withContext(Dispatchers.IO) {
            collection!!.upsert {
                pk = contentHash
                // content_hash is BOTH the document PK and a declared schema field — the engine
                // validates field presence (the error "field[content_hash] is required but not
                // provided" fires if only `pk` is set), so it must be populated as a field too.
                string(FIELD_CONTENT_HASH, contentHash)
                string(FIELD_LOCATOR, locator)
                string(FIELD_CONTENT, content)
                string(FIELD_COLLECTION_ID, collectionId)
            }
        }
    }

    /**
     * Fetch one content doc by its content_hash PK, projecting only `content`. Returns null for an
     * absent pk (the engine omits missing pks silently — a missing pk is not an error). Issue 04's
     * `getContentText` routes here via the façade; the Layer-2 round-trip test exercises it too.
     */
    open suspend fun fetch(contentHash: String): ZvecDoc? {
        ensureOpen()
        return withContext(Dispatchers.IO) {
            // Project content (the OCR text getContentText reads) + locator (the gallery-row bridge).
            // Both are small strings; the large payload (Phase 3's image_embedding) is excluded by
            // ZvecCollection.fetch's includeVector=false default.
            collection!!.fetch(listOf(contentHash), outputFields = listOf(FIELD_CONTENT, FIELD_LOCATOR)).firstOrNull()
        }
    }

    /**
     * The collection's live document count. The R8-idempotency instrumented test asserts this is
     * unchanged after a second upsert of the same PK.
     */
    open suspend fun docCount(): Long {
        ensureOpen()
        return withContext(Dispatchers.IO) { collection!!.stats().docCount }
    }

    /**
     * Issue 04 — the read-flip search path. Runs a pure-FTS query against the `content` field and
     * returns the matched docs in zvec's rank order (the engine returns ranked results; the SDK
     * surfaces the order untouched). The façade projects `locator` (the gallery-row bridge) + `content`
     * (so [getContentText] callers see text without a second round-trip), then resolves the gallery
     * rows in one batched Room query.
     *
     * [matchString] is the natural-language FTS payload (zvec's "match string" recall path), NOT the
     * boolean `query_string`. A live-engine probe (2026-07-01) confirmed: multi-term match strings
     * work, case is normalized by the `lowercase` filter, and a no-match query returns an empty list
     * (not an error). The old Room-era `processQuery` trailing-`*` wildcard ("wifi* bluetooth*") also
     * matches fine, so the existing UI query shape carries over unchanged.
     *
     * `open` so a JVM test can record the call without the `.so`.
     */
    open suspend fun search(matchString: String, topK: Int = DEFAULT_SEARCH_TOPK): List<ZvecDoc> {
        ensureOpen()
        return withContext(Dispatchers.IO) {
            collection!!.query(
                QueryRequest(
                    field = FIELD_CONTENT,
                    fts = matchString,
                    topK = topK,
                ),
                outputFields = listOf(FIELD_LOCATOR, FIELD_CONTENT),
            )
        }
    }

    /** Flush in-memory writes to disk (the engine's durability flush). No-op if not yet opened. */
    open suspend fun flush() {
        val col = collection ?: return
        if (col.isClosed) return
        withContext(Dispatchers.IO) { col.flush() }
    }

    /**
     * Bulk delete by content_hash PK. A pk that does not exist is reported in the result's failures
     * (NOT_FOUND), not thrown. Used by the benchmark screen to clean up its synthetic upsert docs.
     * `open` so a JVM test can record calls without the `.so`.
     */
    open suspend fun deleteAll(pks: List<String>): io.github.tzhvh.scryernext.zvec.WriteResult {
        ensureOpen()
        return withContext(Dispatchers.IO) { collection!!.deleteAll(pks) }
    }

    /**
     * The memory-pressure close path — the ONLY place `close()` is called outside tear-down. Flushes,
     * closes, and nulls the reference; the next data-path call re-opens via [ensureOpen]. Called from
     * `ScryerApplication.onTrimMemory(TRIM_MEMORY_COMPLETE)`. Trades a re-open latency for reclaimed
     * native memory under pressure (cheap on the Phase-2 corpus; revisit in Phase 3 once the HNSW
     * graph is large).
     *
     * Android invokes `onTrimMemory` on the main thread (not a coroutine), but [ZvecCollection.flush]
     * is `suspend` (a native blocking call — ADR 0007). `runBlocking` bridges the two: trim is rare
     * (only at TRIM_MEMORY_COMPLETE), and the flush+close are fast on the small Phase-2 collection, so
     * the brief main-thread block is the right trade for the durability-then-reclaim ordering.
     */
    fun onTrimMemoryComplete() {
        val col = collection ?: return
        runCatching {
            kotlinx.coroutines.runBlocking { col.flush() }
            col.close()
        }.onFailure { Log.w(TAG, "onTrimMemoryComplete: flush/close failed", it) }
        collection = null
    }

    companion object {
        private const val TAG = "ZvecContentStore"
        private const val LOCK_FILE = "LOCK"
        private const val COLLECTION_NAME = "screenshots"

        /**
         * The search topK. The old Room FTS query returned all matches (no LIMIT); zvec needs a
         * finite topK. 200 is comfortably above any realistic per-query match count on a personal
         * screenshot corpus while keeping the result set bounded. Tunable.
         */
        const val DEFAULT_SEARCH_TOPK = 200

        const val FIELD_CONTENT_HASH = "content_hash"
        const val FIELD_LOCATOR = "locator"
        const val FIELD_CONTENT = "content"
        const val FIELD_COLLECTION_ID = "collection_id"

        /** The default collection options (mmap enabled, read-write). */
        private fun options(): CollectionOptions = CollectionOptions()

        /**
         * The minimal screenshot-content schema — `content_hash` PK + `locator` + `content` (FTS,
         * `standard` tokenizer + `lowercase` filter) + `collection_id` (INVERT, so Phase 5's
         * push-down filters need no schema migration). No `image_embedding` — Phase 3 adds it via
         * `add_column` on the live collection.
         */
        fun screenshotContentSchema(): CollectionSchema = CollectionSchema(
            name = COLLECTION_NAME,
            fields = listOf(
                FieldSchema(name = FIELD_CONTENT_HASH, type = FieldType.STRING),
                FieldSchema(name = FIELD_LOCATOR, type = FieldType.STRING),
                FieldSchema(
                    name = FIELD_CONTENT,
                    type = FieldType.STRING,
                    indexParams = IndexParams.FtsParams(tokenizer = "standard", filters = listOf("lowercase")),
                ),
                FieldSchema(
                    name = FIELD_COLLECTION_ID,
                    type = FieldType.STRING,
                    indexParams = IndexParams.InvertParams(),
                ),
            ),
        )
    }
}
