/* -*- Mode: Java; c-basic-offset: 4; tab-width: 4; indent-tabs-mode: nil; -*-
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.tzhvh.scryernext

import android.app.Application
import android.util.Log

import io.github.tzhvh.scryernext.ingestion.IngestionLogger
import io.github.tzhvh.scryernext.ingestion.IngestionProgressStore
import io.github.tzhvh.scryernext.repository.ScreenshotDatabaseRepository
import io.github.tzhvh.scryernext.repository.ScreenshotRepository
import io.github.tzhvh.scryernext.repository.ZvecScreenshotRepository
import io.github.tzhvh.scryernext.setting.PreferenceSettingsRepository
import io.github.tzhvh.scryernext.setting.SettingsRepository
import io.github.tzhvh.scryernext.ZvecEventRecorder
import io.github.tzhvh.scryernext.repository.LastModifiedBackfill
import io.github.tzhvh.scryernext.util.launchIO
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import io.github.tzhvh.scryernext.ingestion.IngestionEngine
import io.github.tzhvh.scryernext.ingestion.MediaStoreProducer
import io.github.tzhvh.scryernext.ingestion.MlKitOcrStage
import io.github.tzhvh.scryernext.ingestion.ZvecContentStore
import io.github.tzhvh.scryernext.ingestion.ZvecWriteSink
import io.github.tzhvh.scryernext.ingestion.triggers.DiscoveryWorker
import io.github.tzhvh.scryernext.ingestion.triggers.IngestionSession
import io.github.tzhvh.scryernext.ingestion.triggers.OnOpenTrigger

class ScryerApplication : Application() {
    companion object {
        /** Phase 2.1 step 4 — prefs file + flag key for the one-shot backfill marker. */
        private const val KEY_BACKFILL_DONE = "last_modified_backfill_done"
        private val PREFS_PHASE21 = io.github.tzhvh.scryernext.search.RecentSearchesPrefs.PREFS_FILE

        private val instance: ScryerApplication by lazy {
            ApplicationHolder.instance
        }

        fun getScreenshotRepository(): ScreenshotRepository {
            return instance.screenshotRepository
        }

        fun getSettingsRepository(): SettingsRepository {
            return instance.settingsRepository
        }

        /** Issue 10.5: app-scope ingestion state + §7.5 re-entrancy guard. */
        fun getIngestionProgressStore(): IngestionProgressStore {
            return instance.ingestionProgressStore
        }

        /** Issue 14: app-scope control + cross-process liveness surface (WorkInfo-derived). */
        fun getIngestionSession(): IngestionSession {
            return instance.ingestionSession
        }

        /** Issue 21: app-wide ContentResolver for decode/size queries against content URIs. */
        fun getContentResolver(): android.content.ContentResolver {
            return instance.contentResolver
        }

        /**
         * zvec Phase 2, issue 03: app-scope [ZvecContentStore] — the screenshot-content collection
         * lifecycle owner. Exposed so [IngestionWorker] (which pulls its deps from these accessors,
         * mirroring the [OnOpenTrigger] wiring) can construct the same [ZvecWriteSink].
         */
        fun getZvecContentStore(): ZvecContentStore {
            return instance.zvecContentStore
        }

        /**
         * zvec Runtime Inspector — app-scope [ScreenshotDao] for the drift meter. Exposed so the debug
         * [ZvecInspectorActivity] reads the production DB (no second Room handle). Unused in
         * release (the activity is debug-only); exposed regardless for accessor parity.
         */
        fun getScreenshotDao(): io.github.tzhvh.scryernext.persistence.ScreenshotDao =
            instance.screenshotDao

        /**
         * zvec Phase 2, issue 03 — app-scope [ContentMetadataCacheDao] for the WorkManager wiring
         * path. [IngestionWorker] pulls its deps from these accessors (mirroring [OnOpenTrigger]'s
         * wiring, which captures the Room DB as a local); without this getter the worker had to cast
         * its app-scope [ScreenshotRepository] down to [ZvecScreenshotRepository] to reach the cache
         * DAO — a runtime type assertion. Exposing the DAO here makes both `ZvecWriteSink` wiring
         * sites (this app scope + `onCreate`'s local-scope construction) symmetric and cast-free.
         */
        fun getMetadataCacheDao(): io.github.tzhvh.scryernext.persistence.ContentMetadataCacheDao =
            instance.metadataCacheDao

        /**
         * Phase 2.1 step 6 render gate: has the one-shot `last_modified` backfill completed?
         * The search screen's date-range chip renders only when this is true (R8: a filter
         * silently hides null rows — a visible-but-empty date filter is dead UI).
         */
        fun isLastModifiedBackfillDone(): Boolean = instance.isBackfillDone
    }

    private object ApplicationHolder {
        lateinit var instance: ScryerApplication
    }

    lateinit var screenshotRepository: ScreenshotRepository
    lateinit var settingsRepository: SettingsRepository

    /** Phase 2.1 step 4 — the one-shot `last_modified` backfill's completion marker. */
    lateinit var backfillMarker: LastModifiedBackfill.BackfillMarker

    /** Cached gate value for [Companion.isLastModifiedBackfillDone]; flipped by the marker. */
    @Volatile
    var isBackfillDone: Boolean = false
        private set

    /**
     * zvec Phase 2, issue 03 — the screenshot-content collection lifecycle owner. Constructed in
     * [onCreate] after the repository (it owns a [ZvecCollection] at `filesDir/zvec/screenshots`).
     * Forwarded [onTrimMemory] flush+close via [ZvecContentStore.onTrimMemoryComplete].
     */
    private lateinit var zvecContentStore: ZvecContentStore

    /**
     * zvec Runtime Inspector — the production Room DAO, held at app scope for the debug
     * [ZvecInspectorActivity]'s drift meter. Set in [onCreate] after the DB repo is constructed.
     */
    private lateinit var screenshotDao: io.github.tzhvh.scryernext.persistence.ScreenshotDao

    /**
     * zvec Phase 2, issue 03 — the metadata-cache DAO held at app scope so [IngestionWorker]'s
     * `ZvecWriteSink` wiring reaches it without a runtime cast to [ZvecScreenshotRepository]. Set
     * in [onCreate] after the DB repo is constructed (sibling to [screenshotDao]).
     */
    private lateinit var metadataCacheDao: io.github.tzhvh.scryernext.persistence.ContentMetadataCacheDao

    /**
     * Issue 10.5: app-scope ingestion progress surface + atomic §7.5 guard.
     * Wired with a tagged-logcat [IngestionLogger] (ADR 0004 §7.6); the store
     * itself is pure Kotlin (no `android.util.Log` dependency) so it remains
     * JVM-testable. Exposed via the [Companion.getIngestionProgressStore] accessor.
     */
    private val ingestionProgressStore = IngestionProgressStore(
        logger = IngestionLogger { msg -> Log.d("IngestionProgressStore", msg) }
    )

    /**
     * Issue 14: the user-facing control + cross-process liveness surface. Owns everything
     * Context-bound (WorkManager, SharedPreferences) so [ingestionProgressStore] stays pure.
     * Constructed after the store so it can observe it; `this` is the application [Context].
     *
     * Initialized in [onCreate], NOT as a field initializer: `IngestionSession` calls
     * `context.applicationContext` in its constructor, and field initializers run during the
     * `Application`'s implicit constructor — *before* `attachBaseContext()` attaches this
     * `ContextWrapper`'s base context, so `this.applicationContext` would NPE. The Context-bound
     * deps ([screenshotRepository], `onOpenTrigger`) follow the same pattern for the same reason.
     */
    private lateinit var ingestionSession: IngestionSession

    /**
     * Application scope for ingestion triggers. [kotlinx.coroutines.Dispatchers.Default]
     * (NOT Main): the engine's pipeline reads image bytes (`ContentResolver.openInputStream` +
     * `readBytes`) and decodes (`BitmapFactory.decodeByteArray`) on the collector's dispatcher —
     * running that on Main would jank/ANR the UI. `Default` is CPU-flavoured (decode), and the
     * repo queries self-relocate to IO. `SupervisorJob` so one run's failure doesn't cancel siblings.
     */
    private val applicationScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default
    )

    override fun onCreate() {
        super.onCreate()
        ApplicationHolder.instance = this

        // ADR 0008 — onboarding state migration runs before any activity can
        // consult the launch gate: capture_page_shown ⇒ onboarding_complete for
        // existing installs, access_model pinned on first run.
        io.github.tzhvh.scryernext.onboarding.OnboardingPrefs.applyLegacyMigration(this)

        // PROFILING_FRAMEWORK.md §3.10 — StrictMode guardrail, debug-only. Catches accidental
        // main-thread disk/network/Room/zvec calls for free — the bug class that would otherwise
        // send someone down a Perfetto rabbit hole for front D ("tap feels slow"). penaltyLog()
        // (not penaltyDeath): a crash on the first incidental violation would break dev flows before
        // we've audited the existing paths; log-to-logcat is loud enough to catch the regression
        // without halting the session. `isDebuggable` mirrors the zvec log-level gate.
        if (isDebuggable) {
            android.os.StrictMode.setThreadPolicy(
                android.os.StrictMode.ThreadPolicy.Builder()
                    .detectDiskReads()
                    .detectDiskWrites()
                    .detectNetwork()
                    .penaltyLog()
                    .build()
            )
            android.os.StrictMode.setVmPolicy(
                android.os.StrictMode.VmPolicy.Builder()
                    .detectActivityLeaks()
                    .detectCleartextNetwork()
                    .detectLeakedClosableObjects()
                    .detectLeakedSqlLiteObjects()
                    .penaltyLog()
                    .build()
            )
        }

        // zvec Phase 2, issue 04 — the read-flip. The gallery + collections stay on Room
        // (ScreenshotDatabaseRepository, unchanged); content search + content-text route to zvec via
        // the ZvecScreenshotRepository façade. The store is constructed after the DAO hold lines
        // below (issue 02 B0: its schema-wipe callback needs both DAOs); the façade wraps the Room
        // repo + the store. The UI never knows content moved — every Flow<List<ScreenshotModel>>
        // keeps its shape.
        val dbRepository = ScreenshotDatabaseRepository.create(this) {
            launchIO {
                screenshotRepository.setupDefaultContent(this@ScryerApplication)
            }
        }
        // zvec Runtime Inspector — hold the production DAO at app scope so the debug
        // ZvecInspectorActivity reads the same DB (no second Room handle). Set after dbRepository.
        screenshotDao = dbRepository.database.screenshotDao()
        // zvec Phase 2, issue 03 — hold the metadata-cache DAO at app scope so IngestionWorker's
        // ZvecWriteSink wiring reaches it without casting the app-scope repository (sibling to
        // screenshotDao above). Set after dbRepository.
        metadataCacheDao = dbRepository.database.contentMetadataCacheDao()
        // zvec Phase B, issue 02 B0 — the schema-wipe callback: the Room half of a wipe, run in the
        // SAME operation as the zvec dir deletion. Without the queue reset (processed=0) the
        // producers never re-pull; without the cache clear the dedup fast-path answers "already
        // indexed" — either alone leaves the fresh collection empty forever.
        zvecContentStore = ZvecContentStore(
            filesDir,
            debug = isDebuggable,
            onSchemaWipe = {
                screenshotDao.resetProcessedForReingest()
                metadataCacheDao.clearAll()
            },
        )
        // zvec Runtime Inspector — gate the event recorder to debuggable builds. Called before any
        // ingestion/store call so the recorder's `enabled` is published before first use. Release
        // builds leave the recorder disabled → zero overhead at the (inline) call sites. Debug
        // builds also mirror every event to logcat (issue 06 A2): open-path decisions (wipe,
        // ladder rungs, invalidation, ensureOpen entry/cancellation) are then observable in a
        // scripted smoke without reading the in-memory Inspector — the 2026-09-21 first-open
        // contention investigation needs exactly that timeline.
        ZvecEventRecorder.init(enabled = isDebuggable) { msg -> Log.d("ZvecRecorder", msg) }
        screenshotRepository = ZvecScreenshotRepository(
            delegate = dbRepository,
            store = zvecContentStore,
        )
        settingsRepository = PreferenceSettingsRepository.getInstance(this)

        // Phase 2.1 step 4 — the one-shot `last_modified` backfill (2.1-D2; R8 ruling:
        // mandatory-before-shipping). Runs at app start, once per install: the marker flips only
        // after a full idempotent pass, so a crash re-runs it next launch. The date-range chip's
        // render gate reads the same marker via [isLastModifiedBackfillDone].
        backfillMarker = object : LastModifiedBackfill.BackfillMarker {
            private val prefs = getSharedPreferences(PREFS_PHASE21, MODE_PRIVATE)
            override fun isDone(): Boolean = prefs.getBoolean(KEY_BACKFILL_DONE, false)
            override fun markDone() {
                isBackfillDone = true
                prefs.edit().putBoolean(KEY_BACKFILL_DONE, true).apply()
            }
        }
        isBackfillDone = backfillMarker.isDone()
        launchIO {
            // Guarded: launchIO has no exception handler, and runIfNeeded throws on any
            // store/Room failure — an unguarded throw here is process death at launch
            // (review P1 fix). A failed pass leaves the marker unset; the next start retries.
            runCatching {
                LastModifiedBackfill(
                    store = zvecContentStore,
                    rowSource = { screenshotRepository.getScreenshotList() },
                    marker = backfillMarker,
                ).runIfNeeded()
            }.onFailure {
                ZvecEventRecorder.record { "last_modified backfill failed (retries next start): ${it.message}" }
            }
        }

        // zvec Phase 2, issue 03 — the write-side cutover. The sink writes OCR content to zvec
        // (replacing the deleted RoomWriteSink). The cache-DAO provider reads the app-scope field
        // (the same one [getMetadataCacheDao] exposes), so this wiring site matches
        // [IngestionWorker]'s exactly — both cast-free, both reading one app-scope DAO.
        val zvecWriteSink = ZvecWriteSink(
            repository = screenshotRepository,
            zvecContentStore = zvecContentStore,
            metadataCacheDaoProvider = { metadataCacheDao },
        )

        // Issue 14: constructed here (not as a field initializer) so the base Context is attached.
        ingestionSession = IngestionSession(this, ingestionProgressStore)

        val onOpenTrigger = OnOpenTrigger(
            repository = screenshotRepository,
            producer = MediaStoreProducer(screenshotRepository, contentResolver),
            engine = IngestionEngine(
                screenshotRepository,
                MlKitOcrStage(),
                zvecWriteSink
            ),
            store = ingestionProgressStore,
            scope = applicationScope,
            logger = IngestionLogger { msg -> android.util.Log.d("OnOpenTrigger", msg) },
            persistCosmetic = { start, done -> ingestionSession.saveCosmetic(start, done) },
            clearCosmetic = { ingestionSession.clearCosmetic() }
        )

        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                onOpenTrigger.onForeground()
            }
            override fun onStop(owner: LifecycleOwner) {
                onOpenTrigger.cancel()
            }
        })

        // Issue 13: register the daily periodic discovery worker (count-and-notify). KEEP makes
        // this idempotent across process restarts — re-registering on every onCreate is a no-op
        // if the periodic is already scheduled. Counts the unindexed backlog, publishes it to the
        // shared progress store, and notifies (with "Index now" / "Snooze") only past the threshold.
        DiscoveryWorker.enqueuePeriodic(this)
    }

    /**
     * zvec Phase 2, issue 03 — the memory-pressure close path for the zvec collection. Under
     * [TRIM_MEMORY_COMPLETE] (and above) flush + close the [ZvecContentStore]'s handle and null it;
     * the next data-path call re-opens it. The ONLY place `close()` is called outside tear-down — it
     * trades a re-open latency for reclaimed native memory under pressure (cheap on the Phase-2
     * corpus; revisit in Phase 3 once the HNSW graph is large).
     */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_COMPLETE) {
            zvecContentStore.onTrimMemoryComplete()
        }
    }

    /** True for debuggable builds — selects the zvec log level via [ZvecConfig.androidDefaults]. */
    private val isDebuggable: Boolean
        get() = (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
}
