package io.github.tzhvh.scryernext.ingestion.harness

import android.os.Debug
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/**
 * The harness's side-car memory/resource sampler (`INGESTION_ADHOC_REPRO_HARNESS.md`
 * §5.3) — the accumulator-*name* signal that runs alongside every arm and turns
 * "it stalled at N" into a root-cause shape (H2 sawtooth / H3 monotonic climb /
 * H1 native-to-cap / H4 flat).
 *
 * Runs **in-process** on a fixed cadence, which keeps it agent-runnable from one
 * `am instrument` invocation — no second shell needed:
 *
 * - [Debug.getMemoryInfo] — dalvik vs native vs total Pss, plus the summary
 *   graphics stat (the `dumpsys meminfo` numbers, read without the DUMP
 *   permission, PROFILING_FRAMEWORK §3.8's fields);
 * - `/proc/self/smaps_rollup` — process-wide RSS/Pss including the mmap'd zvec
 *   graph + `.so` footprint that heap-dump tools never see (front G's native
 *   caveat);
 * - [Runtime] — Java heap used/max (the allocator-pressure view).
 *
 * The collection-size axis does NOT come from here: calling `stats()` mid-run
 * would race the engine's native writes on the same collection handle, so the
 * driver owns that axis from its own progress counter and the post-run
 * `getCollectionStats()`/`lastStatsError` reads.
 *
 * Every sample is also mirrored to logcat ([TAG]) so a hard native crash (the
 * H1/OOM failure modes can kill the process) still leaves the curve on the host
 * side of `adb logcat` — the ring buffer dies with the process, the log does not.
 */
class MemorySampler(
    private val periodMs: Long = SAMPLING_PERIOD_MS,
    private val docCount: () -> Int,
) {
    private val samples = ArrayList<MemSample>(512)
    private val mutex = Mutex()
    private var job: Job? = null
    private val startMs = System.currentTimeMillis()

    /** Begin sampling on [scope]; one sample immediately, then every [periodMs]. */
    fun start(scope: CoroutineScope) {
        check(job == null) { "MemorySampler already started" }
        job = scope.launch(Dispatchers.Default) {
            while (true) {
                val sample = sample()
                mutex.withLock { samples.add(sample) }
                Log.i(TAG, sample.toLogLine())
                delay(periodMs)
            }
        }
    }

    /**
     * Stop sampling and return the captured curve. Takes one final sample
     * synchronously after cancelling the loop (issue `04` gate wiring): the
     * cadence loop's last tick can predate the run's terminal checkpoint by up
     * to one period, and that checkpoint's flush is exactly what seals the last
     * segment/SST files — without the closing sample, `fdsFinal`/`fdsMax` would
     * under-report the end state the fd assertion reads (§16).
     */
    suspend fun stop(): List<MemSample> {
        val wasRunning = job != null
        job?.cancel()
        job = null
        return mutex.withLock {
            if (wasRunning) samples.add(sample())
            samples.toList()
        }
    }

    private fun sample(): MemSample {
        val info = Debug.MemoryInfo()
        Debug.getMemoryInfo(info)
        val graphicsMb = runCatching { info.getMemoryStat("summary.graphics")?.toDoubleOrNull() }
            .getOrNull() ?: 0.0
        val (rssKb, pssKb) = readSmapsRollup()
        val runtime = Runtime.getRuntime()
        return MemSample(
            tMs = System.currentTimeMillis() - startMs,
            doc = docCount(),
            javaMb = ((runtime.totalMemory() - runtime.freeMemory()) / 1024.0) / 1024.0,
            nativeMb = info.nativePss / 1024.0,
            graphicsMb = graphicsMb / 1024.0,
            totalPssMb = info.totalPss / 1024.0,
            rssMb = rssKb / 1024.0,
            fds = openFdCount(),
        )
    }

    /** The process's open-fd count — /proc/self/fd is readable without permissions. */
    private fun openFdCount(): Int = runCatching {
        File("/proc/self/fd").list()?.size ?: 0
    }.getOrDefault(0)

    /**
     * The rollup file exists on the app's own /proc entry without permissions
     * (kernel 4.14+ / API 29+); a missing file (older kernel, odd OEM) degrades
     * to zeros rather than failing the run — the sampler is diagnostic, never
     * load-bearing.
     */
    private fun readSmapsRollup(): Pair<Long, Long> = runCatching {
        var rss = 0L
        var pss = 0L
        File("/proc/self/smaps_rollup").useLines { lines ->
            for (line in lines) {
                when {
                    line.startsWith("Rss:") -> rss = line.substringAfter(':').trim()
                        .substringBefore(' ').toLongOrNull() ?: 0L
                    line.startsWith("Pss:") -> pss = line.substringAfter(':').trim()
                        .substringBefore(' ').toLongOrNull() ?: 0L
                }
            }
        }
        rss to pss
    }.getOrDefault(0L to 0L)

    private fun MemSample.toLogLine(): String =
        "mem t=${tMs / 1000.0}s doc=$doc java=%.0f native=%.0f graphics=%.0f totalPss=%.0f rss=%.0f fds=%d"
            .format(java.util.Locale.US, javaMb, nativeMb, graphicsMb, totalPssMb, rssMb, fds)

    private companion object {
        const val TAG = "IngestionHarness"
        const val SAMPLING_PERIOD_MS = 1_000L
    }
}
