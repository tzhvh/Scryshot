/* -*- Mode: Java; c-basic-offset: 4; tab-width: 4; indent-tabs-mode: nil; -*-
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.tzhvh.scryernext

import android.app.Application
import android.util.Log

import io.github.tzhvh.scryernext.ingestion.IngestionLogger
import io.github.tzhvh.scryernext.ingestion.IngestionProgressStore
import io.github.tzhvh.scryernext.repository.ScreenshotRepository
import io.github.tzhvh.scryernext.setting.PreferenceSettingsRepository
import io.github.tzhvh.scryernext.setting.SettingsRepository
import io.github.tzhvh.scryernext.util.launchIO
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import io.github.tzhvh.scryernext.ingestion.IngestionEngine
import io.github.tzhvh.scryernext.ingestion.MediaStoreProducer
import io.github.tzhvh.scryernext.ingestion.MlKitOcrStage
import io.github.tzhvh.scryernext.ingestion.RoomWriteSink
import io.github.tzhvh.scryernext.ingestion.triggers.DiscoveryWorker
import io.github.tzhvh.scryernext.ingestion.triggers.IngestionSession
import io.github.tzhvh.scryernext.ingestion.triggers.OnOpenTrigger

class ScryerApplication : Application() {
    companion object {
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
    }

    private object ApplicationHolder {
        lateinit var instance: ScryerApplication
    }

    lateinit var screenshotRepository: ScreenshotRepository
    lateinit var settingsRepository: SettingsRepository

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


        screenshotRepository = ScreenshotRepository.createRepository(this) {
            launchIO {
                screenshotRepository.setupDefaultContent(this@ScryerApplication)
            }
        }
        settingsRepository = PreferenceSettingsRepository.getInstance(this)

        // Issue 14: constructed here (not as a field initializer) so the base Context is attached.
        ingestionSession = IngestionSession(this, ingestionProgressStore)

        val onOpenTrigger = OnOpenTrigger(
            repository = screenshotRepository,
            producer = MediaStoreProducer(screenshotRepository, contentResolver),
            engine = IngestionEngine(
                screenshotRepository,
                MlKitOcrStage(),
                RoomWriteSink(screenshotRepository)
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
}
