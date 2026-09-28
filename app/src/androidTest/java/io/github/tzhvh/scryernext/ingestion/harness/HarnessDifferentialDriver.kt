package io.github.tzhvh.scryernext.ingestion.harness

import android.util.Log
import io.github.tzhvh.scryernext.ingestion.Candidate
import io.github.tzhvh.scryernext.ingestion.IngestionEngine
import io.github.tzhvh.scryernext.ingestion.IngestionEventRecorder
import io.github.tzhvh.scryernext.ingestion.OcrStage
import io.github.tzhvh.scryernext.ingestion.Progress
import io.github.tzhvh.scryernext.ingestion.WriteSink
import io.github.tzhvh.scryernext.ingestion.ZvecContentStore
import io.github.tzhvh.scryernext.ingestion.triggers.WorkResultDecision
import io.github.tzhvh.scryernext.ingestion.triggers.bulkResultDecision
import io.github.tzhvh.scryernext.repository.DedupResult
import io.github.tzhvh.scryernext.repository.ScreenshotInMemoryRepository
import io.github.tzhvh.scryernext.persistence.ScreenshotModel
import io.github.tzhvh.scryernext.util.sha256Hex
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.withTimeout
import java.util.Locale

/**
 * The differential driver (`INGESTION_ADHOC_REPRO_HARNESS.md` §5.2): drives
 * [IngestionEngine.process] to completion (or failure) at N candidates per arm,
 * and records everything the §6 decision matrix reads — the terminal
 * [Progress], the §5 `bulkResultDecision`, per-doc `writeMs` (via a timing
 * wrapper that keeps the real sink's cost *inside* the sample), the side-car
 * memory curve, dedup-skip count, and the post-run zvec health
 * (`docCount`, `lastStatsError`, lock/wipe counters).
 *
 * The watchdog matters as much as the run: H4's signature is "a crawl, not a
 * break" — a run that never terminates — so [run] bounds every arm with
 * [budgetMs] and maps a watchdog trip onto [ArmOutcomeKind.CRAWL_TIMEOUT] rather
 * than letting the instrumentation hang forever (agent-runnability, §8).
 *
 * An escaping [Throwable] (an OOM out of the decode stage above all) maps onto
 * [ArmOutcomeKind.RED_THROW] with the throwable recorded — H2's predicted
 * signature is precisely "an OOM escaping the collection loop".
 */
internal class HarnessDifferentialDriver(
    private val store: ZvecContentStore,
    private val repository: HarnessInMemoryRepository,
) {

    internal data class RunOutput(
        val arm: HarnessArm,
        val kind: ArmOutcomeKind,
        val indexed: Int,
        val failed: Int,
        val dedupSkips: Int,
        val total: Int,
        val terminalDescription: String,
        // internal: the §5 decision type is internal to the app module; the summary
        // JSON exposes it as a string, so nothing public leaks the type.
        internal val decision: WorkResultDecision,
        val errorMessage: String?,
        val durationMs: Long,
        val writeMsSamples: List<Double>,
        val writeTrend: WriteTrend,
        val memSamples: List<MemSample>,
        val memShape: MemoryShape,
        val docCountEnd: Long?,
        val lastStatsError: String?,
        val lastOpenOutcome: String,
        val lockRecoveries: Int,
        val schemaWipes: Int,
    ) {
        /**
         * The regression-gate inputs + verdict (issue `04`, §16) — computed, not
         * stored, so the summary line, the device assertion, and the host-script
         * grep all read one source of truth over the already-captured curves.
         */
        val fdsMax: Int get() = memSamples.maxOfOrNull { it.fds } ?: 0
        val fdsFinal: Int get() = memSamples.lastOrNull()?.fds ?: 0

        /** Mean per-commit writeMs — the amortized durability cost (see [HarnessGate]). */
        val writeMsAmort: Double? get() = writeMsSamples.takeIf { it.isNotEmpty() }?.average()
        val gate: HarnessGate.Verdict get() = HarnessGate.verdict(fdsMax, writeMsAmort)

        /** Compact single-line JSON for logcat (the host script greps this). */
        fun toSummaryJson(): String {
            val w = writeMsSamples
            fun pct(p: Double): Double {
                if (w.isEmpty()) return 0.0
                val sorted = w.sorted()
                return sorted[(p * (sorted.size - 1)).toInt().coerceIn(0, sorted.size - 1)]
            }
            return buildString {
                append("{")
                append("\"arm\":\"${arm.name}\",")
                append("\"kind\":\"$kind\",")
                append("\"indexed\":$indexed,\"failed\":$failed,")
                append("\"dedupSkips\":$dedupSkips,\"total\":$total,")
                append("\"terminal\":\"${terminalDescription.replace("\"", "'")}\",")
                append("\"decision\":\"$decision\",")
                append("\"error\":${errorMessage?.let { "\"${it.take(160).replace("\"", "'")}\"" } ?: "null"},")
                append("\"durationS\":${"%.1f".format(Locale.US, durationMs / 1000.0)},")
                append("\"writeN\":${w.size},")
                if (w.isNotEmpty()) {
                    append("\"writeMsP50\":${"%.2f".format(Locale.US, pct(0.5))},")
                    append("\"writeMsP99\":${"%.2f".format(Locale.US, pct(0.99))},")
                    append("\"writeMsFirst100\":${"%.2f".format(Locale.US, w.take(100).average())},")
                    append("\"writeMsLast100\":${"%.2f".format(Locale.US, w.takeLast(100).average())},")
                }
                append("\"writeTrend\":\"$writeTrend\",")
                if (writeMsAmort != null) {
                    append("\"writeMsAmort\":${"%.2f".format(Locale.US, writeMsAmort)},")
                }
                append("\"memShape\":\"$memShape\",")
                append("\"memSamples\":${memSamples.size},")
                if (memSamples.isNotEmpty()) {
                    append("\"nativeFirst\":${"%.0f".format(Locale.US, memSamples.first().nativeMb)},")
                    append("\"nativeLast\":${"%.0f".format(Locale.US, memSamples.last().nativeMb)},")
                    append("\"javaMax\":${"%.0f".format(Locale.US, memSamples.maxOf { it.javaMb })},")
                    // The fd assertion's inputs (§16): final + peak open-fd count.
                    append("\"fdsFinal\":$fdsFinal,\"fdsMax\":$fdsMax,")
                }
                append("\"docCountEnd\":$docCountEnd,")
                append("\"lastStatsError\":${lastStatsError?.let { "\"${it.take(120)}\"" } ?: "null"},")
                append("\"lastOpenOutcome\":\"$lastOpenOutcome\",")
                append("\"lockRecoveries\":$lockRecoveries,")
                append("\"schemaWipes\":$schemaWipes,")
                // The gate verdict rides last — the one token the driver script
                // greps for its exit-non-zero enforcement (issue `04`, §16).
                append("\"gate\":\"${gate.label}\"")
                append("}")
            }
        }
    }

    /**
     * Run one arm. [sink] is the write path under test (the real
     * `ZvecWriteSink`, or an injected-fault sink for the harness's own
     * red-capability self-check). [stage] is the arm's OCR seam.
     */
    suspend fun run(
        arm: HarnessArm,
        stage: OcrStage,
        sink: WriteSink,
        candidates: List<Candidate>,
        budgetMs: Long,
    ): RunOutput {
        // Fresh recorder per run, logcat-mirrored (§7): the 100-ring is UI-only;
        // the mirror keeps every per-item event on the host side of adb.
        val recorder = IngestionEventRecorder().apply {
            init(enabled = true, mirror = { msg -> Log.d(RECORDER_TAG, msg) })
        }
        val timed = TimedWriteSink(sink)
        val engine = IngestionEngine(repository, stage, timed, recorder)

        val sampler = MemorySampler(docCount = { repository.processedSoFar })
        val samplerScope = CoroutineScope(Dispatchers.Default + Job())
        sampler.start(samplerScope)

        val started = System.currentTimeMillis()
        var terminal: Progress = Progress.Idle
        var kind = ArmOutcomeKind.CLEAN
        var errorMessage: String? = null
        var lastHeartbeatDoc = 0

        try {
            withTimeout(budgetMs) {
                engine.process(candidates.asFlow()).collect { progress ->
                    when (progress) {
                        is Progress.Indexing -> {
                            if (progress.current - lastHeartbeatDoc >= HEARTBEAT_EVERY) {
                                lastHeartbeatDoc = progress.current
                                val t = progress.stageTimings
                                Log.i(
                                    TAG,
                                    "arm ${arm.name} ${progress.current}/${progress.total} " +
                                        "read=${"%.1f".format(Locale.US, t?.readMs ?: 0.0)} " +
                                        "ocr=${"%.1f".format(Locale.US, t?.ocrMs ?: 0.0)} " +
                                        "write=${"%.1f".format(Locale.US, t?.writeMs ?: 0.0)} " +
                                        "elapsed=${(System.currentTimeMillis() - started) / 1000.0}s"
                                )
                            }
                        }
                        is Progress.Completed -> terminal = progress
                        is Progress.Error -> {
                            terminal = progress
                            Log.w(TAG, "arm ${arm.name}: Progress.Error — ${progress.throwable}")
                        }
                        else -> Unit
                    }
                }
            }
            errorMessage = (terminal as? Progress.Error)?.throwable?.let { "${it.javaClass.simpleName}: ${it.message}" }
            kind = when (terminal) {
                is Progress.Error -> ArmOutcomeKind.RED_THROW
                is Progress.Completed -> {
                    val c = terminal as Progress.Completed
                    val nothingIndexed = c.indexed <= (c.total * STALL_INDEXED_FRACTION).toInt()
                    val mostlyFailed = c.total > 0 && c.failed >= (c.total * STALL_FAILED_FRACTION)
                    if (mostlyFailed || nothingIndexed) ArmOutcomeKind.STALL_FAILED_N else ArmOutcomeKind.CLEAN
                }
                // The engine always emits a terminal; reaching here means the
                // collection ended without one — treat as a run-level failure.
                else -> ArmOutcomeKind.RED_THROW
            }
        } catch (timeout: TimeoutCancellationException) {
            kind = ArmOutcomeKind.CRAWL_TIMEOUT
            errorMessage = "watchdog: no terminal within ${budgetMs / 60_000}min"
            Log.w(TAG, "arm ${arm.name}: $errorMessage (H4 candidate)")
        } catch (t: Throwable) {
            kind = ArmOutcomeKind.RED_THROW
            errorMessage = "${t.javaClass.simpleName}: ${t.message}"
            Log.w(TAG, "arm ${arm.name}: collection-loop throw — $errorMessage", t)
        }

        val memSamples = sampler.stop()
        samplerScope.cancel()
        val stats = runCatching { store.getCollectionStats() }.getOrNull()
        val output = RunOutput(
            arm = arm,
            kind = kind,
            indexed = (terminal as? Progress.Completed)?.indexed ?: 0,
            failed = (terminal as? Progress.Completed)?.failed ?: 0,
            dedupSkips = repository.knownHits,
            total = candidates.size,
            terminalDescription = terminalDescription(terminal),
            decision = bulkResultDecision(terminal),
            errorMessage = errorMessage,
            durationMs = System.currentTimeMillis() - started,
            writeMsSamples = timed.writeMs.toList(),
            writeTrend = HarnessSignals.classifyWriteTrend(timed.writeMs),
            memSamples = memSamples,
            memShape = HarnessSignals.classifyMemoryCurve(memSamples),
            docCountEnd = stats?.docCount,
            lastStatsError = store.lastStatsError,
            lastOpenOutcome = store.lastOpenOutcome,
            lockRecoveries = store.lockRecoveriesCount,
            schemaWipes = store.schemaWipesCount,
        )
        Log.i(TAG, "HARNESS_ARM ${output.toSummaryJson()}")
        return output
    }

    private fun terminalDescription(terminal: Progress): String = when (terminal) {
        is Progress.Completed -> "Completed(indexed=${terminal.indexed}, failed=${terminal.failed}, total=${terminal.total})"
        is Progress.Error -> "Error(${terminal.throwable.javaClass.simpleName})"
        is Progress.Indexing -> "Indexing(${terminal.current}/${terminal.total}) — no terminal"
        else -> terminal.toString()
    }

    /**
     * Per-doc writeMs around the real sink — the engine's own timing stays
     * untouched; this is the H4 latency-shape axis (§5.3). The engine loop is
     * sequential, so the list needs no synchronization.
     *
     * [checkpoint] must delegate to the wrapped sink (issue `04` gate wiring):
     * `WriteSink`'s default checkpoint is a no-op, so without the override the
     * engine's terminal `write.checkpoint()` would land on the wrapper's
     * default and the real sink's tail batch would never flush/drain — the
     * harness would measure a write path that silently skips its own terminal
     * durability step. Deliberately **untimed**: the terminal flush is a
     * per-run cost, and production's per-doc `writeMs` samples exclude it
     * (`ZvecWriteSink`'s latency-semantics note) — the amortized gate metric
     * must match that semantics, and the batch-boundary commits already carry
     * their flushes inside the samples.
     */
    private class TimedWriteSink(private val inner: WriteSink) : WriteSink {
        val writeMs = ArrayList<Double>(8_192)
        override suspend fun commit(
            candidate: Candidate,
            text: String?,
            processed: Boolean,
            bytes: ByteArray,
            precomputedContentHash: String?,
        ) {
            val start = System.nanoTime()
            inner.commit(candidate, text, processed, bytes, precomputedContentHash)
            writeMs.add((System.nanoTime() - start) / 1_000_000.0)
        }

        override suspend fun checkpoint() = inner.checkpoint()
    }

    private companion object {
        const val TAG = "IngestionHarness"
        const val RECORDER_TAG = "IngestionRecorder"
        const val HEARTBEAT_EVERY = 500
        // "Completed" but with nothing/failures ≈ N is the H5/whole-run-stall
        // signature, not a green (§6 row 5).
        const val STALL_INDEXED_FRACTION = 0.05
        const val STALL_FAILED_FRACTION = 0.5
    }
}

/**
 * The harness repository: the in-memory reference repo with two harness-shaped
 * overrides — a hash-indexed `getScreenshotByUri` (the sink's per-doc Model-B
 * lookup; the base class's linear scan is O(n²) at N=10k) and the real miss-path
 * `isKnown` contract (SHA-256 over the engine-read bytes threaded back out as
 * `resolvedContentHash`, roadmap V2 §0.4, exactly what the production
 * `ScreenshotDatabaseRepository` does — so the sink never re-hashes).
 *
 * [knownHits] doubles as the dedup-skip counter: a harness corpus must produce
 * zero, or the corpus is byte-colliding and the arm is measuring nothing.
 */
class HarnessInMemoryRepository : ScreenshotInMemoryRepository() {

    private val rowsByUri = HashMap<String, ScreenshotModel>(4_096)

    /** Dedup-skip counter — `isKnown` answered "known". Must stay 0 on a fresh corpus. */
    var knownHits: Int = 0
        private set

    /** Loop-position probe for the memory sampler's x-axis. */
    var processedSoFar: Int = 0
        private set

    override suspend fun addScreenshot(screenshots: List<ScreenshotModel>) {
        super.addScreenshot(screenshots)
        screenshots.forEach { rowsByUri[it.uri] = it }
    }

    override suspend fun getScreenshotByUri(uri: String): ScreenshotModel? = rowsByUri[uri]

    override suspend fun isKnown(candidate: Candidate, bytes: ByteArray): DedupResult {
        val row = candidate.locator?.let { rowsByUri[it] }
        val known = row?.processed == true
        val hash = sha256Hex(bytes)
        if (known) {
            knownHits++
            processedSoFar++
        }
        return DedupResult(known = known, resolvedContentHash = hash)
    }

    override suspend fun markContentIndexed(screenshot: ScreenshotModel, contentHash: String) {
        super.markContentIndexed(screenshot, contentHash)
        processedSoFar++
    }
}
