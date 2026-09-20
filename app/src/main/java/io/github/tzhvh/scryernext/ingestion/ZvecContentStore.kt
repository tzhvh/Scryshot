/* -*- Mode: Java; c-basic-offset: 4; tab-width: 4; indent-tabs-mode: nil; -*-
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.tzhvh.scryernext.ingestion

import android.util.Log
import io.github.tzhvh.scryernext.ZvecEventRecorder
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
 *   [openOrCreate], which decides **open vs wipe-recreate by the schema marker, not path existence**
 *   (zvec Phase B / issue 02 B0): the sibling `$collectionPath.version` file records the
 *   [SCHEMA_VERSION] that created the collection. Marker present + equal → `open` (with **stale-LOCK
 *   recovery**, pinned Phase-1 contract #1: on an open failure whose [ZvecException.detail] carries
 *   the lock signal, delete `$dbPath/LOCK` and retry **once** — the caller's policy, not the SDK's).
 *   Marker absent or different → **wipe-and-recreate**: `deleteRecursively($dbPath)` →
 *   [onSchemaWipe] (the Room ingestion-queue reset — without it the producers' `WHERE processed = 0`
 *   queue and the metadata cache would both keep saying "done" and the re-ingest would never run) →
 *   `createAndOpen` → write the marker. The pre-marker legacy case (0.5.x-era dir, no marker file)
 *   lands in the wipe branch by construction: an unmarked dir cannot prove its schema, and the
 *   doctrine is hard cutover (H3 — nothing to migrate), so it is recreated exactly once and steady-
 *   state from then on. The marker is written only *after* `createAndOpen` succeeds, so a crash
 *   mid-sequence leaves no marker and the next launch re-runs the (idempotent, self-healing) wipe.
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
/**
 * The [markerState] verdicts. `Wipe` covers fresh-create (nothing to delete is a no-op).
 */
internal enum class SchemaMarkerState { Open, Wipe }

open class ZvecContentStore(
    private val filesDir: File,
    private val debug: Boolean,
    /**
     * The ingestion-queue reset invoked **inside the wipe branch** — after the collection dir is
     * deleted, before `createAndOpen`. Production wiring (ScryerApplication) passes the Room reset
     * (`UPDATE screenshot SET processed = 0` + `DELETE FROM content_metadata_cache`); without it a
     * wipe leaves both saying "done" and nothing re-ingests. Null (JVM tests) = no reset to run.
     * A throw here propagates: the dir is already gone, the marker is not yet written, so the next
     * launch re-runs the whole (idempotent) wipe-and-reset — failing loudly beats a half-reset.
     */
    private val onSchemaWipe: (suspend () -> Unit)? = null,
) {
    /** Fixed app-private path for the screenshot-content collection directory. */
    private val collectionPath: File = File(filesDir, "zvec/screenshots").also { it.parentFile?.mkdirs() }

    /**
     * The schema marker — sibling of [collectionPath] (outside it, so the wipe's
     * `deleteRecursively` can't take the marker down mid-operation). Written only after a
     * successful `createAndOpen`; its absence means "this dir proves nothing" (→ wipe).
     */
    private val schemaMarkerFile: File = File("${collectionPath.path}.version")

    /**
     * The one long-lived collection handle, or null after [onTrimMemoryComplete] until the next
     * data-path call re-opens it. Guarded by [openMutex] so a concurrent first-access pair (e.g. the
     * engine + a detail-page write racing on cold start) doesn't double-open. `@Volatile` so reads
     * from other threads (e.g. the inspector's [isCollectionOpen] on Dispatchers.Default) safely
     * observe the null set by [onTrimMemoryComplete] on the main thread.
     */
    @Volatile
    private var collection: ZvecCollection? = null
    private val openMutex = Mutex()

    @Volatile
    open var lastOpenOutcome: String = "never_opened"

    @Volatile
    open var lockRecoveriesCount: Int = 0

    /**
     * How many times the wipe branch ran **with a pre-existing dir** (i.e. a real wipe of previously
     * indexed data, not a first-launch create). A number > 0 on a device that hasn't changed
     * [SCHEMA_VERSION] between launches means the marker isn't surviving — investigate, don't ship.
     */
    @Volatile
    open var schemaWipesCount: Int = 0

    @Volatile
    open var lastFlushTimestamp: Long = 0L

    @Volatile
    open var lastCloseTimestamp: Long = 0L

    /**
     * The last `stats()` failure message, or null if the last read succeeded. Surfaced so the
     * inspector can distinguish "0 docs (empty corpus)" from "unread (stats threw)" — without this,
     * a failed read reports a fake "✅ PASS" drift verdict (docCount defaults to 0 → no orphans/holes).
     */
    @Volatile
    open var lastStatsError: String? = null

    open fun isCollectionOpen(): Boolean {
        val col = collection
        return col != null && !col.isClosed
    }

    open suspend fun getCollectionStats(): io.github.tzhvh.scryernext.zvec.CollectionStats? {
        ensureOpen()
        return withContext(Dispatchers.IO) {
            runCatching { collection?.stats() }.getOrElse {
                // Record the failure so the inspector shows "stats read FAILED" instead of a
                // misleading docCount=0 → "✅ PASS". A silent null here is the worst failure mode
                // for a diagnostic tool: it suppresses investigation of a real problem.
                lastStatsError = it.message
                ZvecEventRecorder.record { "stats() read failed: ${it.message}" }
                null
            }.also { if (it != null) lastStatsError = null }
        }
    }

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
     * test can subclass and fake the lock exception without the `.so` (the class KDoc's test-seam
     * pattern); [openOrCreate] itself is `protected` (not `private`) so the marker-machinery test can
     * drive a real open decision end-to-end the same way.
     */
    protected open suspend fun openOrCreate(): ZvecCollection = withContext(Dispatchers.IO) {
        try {
            val col = openOrRetry()
            ZvecEventRecorder.record { "Collection opened successfully" }
            col
        } catch (e: ZvecException) {
            if (isStaleLock(e) && collectionPath.resolve(LOCK_FILE).delete()) {
                lockRecoveriesCount++
                ZvecEventRecorder.record { "LOCK recovery triggered: stale LOCK deleted" }
                try {
                    val col = openOrRetry()
                    lastOpenOutcome = "LOCK-recovered"
                    ZvecEventRecorder.record { "Collection opened successfully after LOCK recovery" }
                    col
                } catch (retryEx: ZvecException) {
                    lastOpenOutcome = "error: ${retryEx.message}"
                    ZvecEventRecorder.record { "Collection open failed after LOCK recovery: ${retryEx.message}" }
                    throw retryEx
                }
            } else {
                lastOpenOutcome = "error: ${e.message}"
                ZvecEventRecorder.record { "Collection open failed: ${e.message}" }
                throw e
            }
        }
    }

    /**
     * The first-or-retry attempt — decides **open vs wipe-recreate by the schema marker** (issue 02
     * B0), not by path existence alone:
     *
     * - Marker equal to [SCHEMA_VERSION] **and** the dir present → [openExisting]. (A matched marker
     *   with a missing dir is external tampering or partial deletion — the wipe branch recreates
     *   rather than erroring on an `open` of nothing.)
     * - Anything else (marker absent, unreadable, different version, or dir gone) → the wipe branch:
     *   `deleteRecursively` → [runQueueReset] → [createNew] → [writeSchemaMarker], in that order.
     *   The queue reset runs on EVERY recreate — including a fresh install, where it's a no-op on an
     *   empty Room DB — because "marker matches but dir missing" is precisely the ghost-index state
     *   (engine empty, queue believes everything is done) the reset exists to undo. The marker is
     *   written last: a crash anywhere earlier leaves no marker, and the next launch re-runs the
     *   idempotent wipe. [schemaWipesCount] / the outcome string count only real wipes (a dir that
     *   held data), not fresh creates.
     */
    protected open suspend fun openOrRetry(): ZvecCollection {
        val markerMatches = markerState(readSchemaMarker(), SCHEMA_VERSION) == SchemaMarkerState.Open
        if (markerMatches && collectionPath.exists()) {
            lastOpenOutcome = "success"
            return openExisting(collectionPath, options())
        }
        val wipedExistingData = collectionPath.exists()
        if (wipedExistingData) {
            val deleted = collectionPath.deleteRecursively()
            ZvecEventRecorder.record { "Schema wipe: removed $collectionPath (deleted=$deleted)" }
        }
        runQueueReset()
        val col = createNew(collectionPath, screenshotContentSchema(), options())
        writeSchemaMarker()
        if (wipedExistingData) {
            schemaWipesCount++
            lastOpenOutcome = "wipe-recreated (schema v$SCHEMA_VERSION)"
        }
        return col
    }

    /**
     * The Room ingestion-queue reset — the other half of a wipe. Runs inside the wipe branch after
     * the dir deletion; without it `MediaStoreProducer`'s `WHERE processed = 0` queue stays empty
     * and the metadata cache keeps answering "already indexed", so a wiped engine re-ingests
     * nothing. Failure propagates (loud) — see [onSchemaWipe].
     */
    protected open suspend fun runQueueReset() {
        onSchemaWipe?.invoke()
        ZvecEventRecorder.record { "Schema wipe: ingestion queue reset (processed=0, cache cleared)" }
    }

    /** Raw marker content (trimmed), or null when absent/unreadable — null deliberately means wipe. */
    protected open fun readSchemaMarker(): String? =
        runCatching { schemaMarkerFile.takeIf { it.exists() }?.readText()?.trim() }.getOrNull()

    /**
     * Persist [SCHEMA_VERSION]. Called only after `createAndOpen` succeeded; a write failure is
     * logged and swallowed — the cost is one self-healing re-wipe next launch, not a failed open.
     */
    protected open fun writeSchemaMarker() {
        runCatching { schemaMarkerFile.writeText(SCHEMA_VERSION.toString()) }
            .onFailure { ZvecEventRecorder.record { "Schema marker write failed: ${it.message}" } }
    }

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
     * Issue 02 B6 — full-corpus walk passthrough ([ZvecCollection.iterDocs]). The corpus-audit and
     * bronze-set harvest path: enumerate every doc's text without FTS probing. Scores are null
     * (snapshot walk, not ranked); vectors excluded. `open` so a JVM test can record without the `.so`.
     */
    open suspend fun iterDocs(outputFields: List<String>? = null): List<ZvecDoc> {
        ensureOpen()
        return withContext(Dispatchers.IO) { collection!!.iterDocs(outputFields) }
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

    open suspend fun flush() {
        val col = collection ?: return
        if (col.isClosed) return
        withContext(Dispatchers.IO) {
            col.flush()
            lastFlushTimestamp = System.currentTimeMillis()
            ZvecEventRecorder.record { "Collection flushed successfully" }
        }
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
            lastCloseTimestamp = System.currentTimeMillis()
            ZvecEventRecorder.record { "Collection closed under memory pressure" }
        }.onFailure {
            Log.w(TAG, "onTrimMemoryComplete: flush/close failed", it)
            ZvecEventRecorder.record { "trim-close failure: ${it.message}" }
        }
        collection = null
    }

    companion object {
        private const val TAG = "ZvecContentStore"
        private const val LOCK_FILE = "LOCK"
        private const val COLLECTION_NAME = "screenshots"

        /**
         * The schema-generation const the marker file is checked against (issue 02 B0). **Bump this
         * exactly when [screenshotContentSchema] changes** — FTS params/filters/tokenizer are fixed
         * at collection creation (DDL rejects non-numeric changes; one tokenizer per FTS field,
         * forever) — and the next launch wipes + queue-resets + re-ingests from scratch (full
         * re-OCR, the doctrine's accepted cost; no back-fill, no migration: H3).
         *
         * Current value `1` describes the schema as of the marker mechanism's introduction
         * (`standard` + `lowercase` on `content`). The Phase-B FTS decision (B3/B4 — filters +
         * stemmer, maybe `content_ngram`) is expected to ship the first *production* bump.
         */
        const val SCHEMA_VERSION = 1

        /**
         * The pure wipe decision — total, disk-free, JVM-tested. [markerContent] is the raw marker
         * file content (null = absent or unreadable). `Open` only when the marker is exactly
         * [currentVersion] as written (decimal string, trimmed). Everything else — absent, garbage,
         * stale version — is `Wipe`: an unmarked or mismatched dir cannot prove its schema, and the
         * doctrine is hard cutover (H3), never migrate.
         */
        internal fun markerState(markerContent: String?, currentVersion: Int): SchemaMarkerState =
            if (markerContent?.trim() == currentVersion.toString()) SchemaMarkerState.Open
            else SchemaMarkerState.Wipe

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
