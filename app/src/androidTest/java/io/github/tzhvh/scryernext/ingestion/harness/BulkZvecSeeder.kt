package io.github.tzhvh.scryernext.ingestion.harness

import android.util.Log
import io.github.tzhvh.scryernext.ingestion.ZvecContentStore
import io.github.tzhvh.scryernext.zvec.ZvecDocBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The bulk zvec seeder — "seed N and keep" (`INGESTION_ADHOC_REPRO_HARNESS.md`
 * §5.1), the generalization of [ZvecBenchmarkRunner]'s UPSERT_BATCH/cleanup
 * pattern (FW3) onto `ZvecCollection.upsertAll`. Inflates the collection to the
 * cliff state (~8k docs of realistic OCR text) in seconds, without OCR-ing its
 * way there — the core insight of §2: synthesize the cliff *state*.
 *
 * Seeded docs are the pre-seed probe's *state*, not its output: they are written
 * on distinguishable pks/locators (`harness-seed-N`, `content://harness-seed/N`)
 * and carry the same content marker (`"harness synthetic"`) as the differential
 * corpus, so a killed run's leftovers are identifiable as harness synth docs in
 * the zvec inspector's orphan accounting (the O1 pattern — it subtracts
 * benchmark synth docs via the content probe; this seeder runs on a test-scoped
 * collection directory, so production drift is not reachable in the first place).
 *
 * C4 discipline (§10): [cleanup] deletes every seeded pk (batched, tolerant of
 * already-gone pks) and the driver additionally deletes the whole test-scoped
 * collection directory in its `finally` — a killed run cannot leak orphans into
 * anything production-owned.
 */
class BulkZvecSeeder(private val store: ZvecContentStore) {

    /** Seeded-pk prefix — NEVER collide with sink-written content hashes (sha256 hex). */
    val seededPks = ArrayList<String>(1024)

    /**
     * Seed [count] docs. [lastModified] rides into the schema's `last_modified`
     * scalar exactly as the production sink populates it (the v3 schema rejects
     * docs missing required fields; every field the sink writes, the seeder
     * writes). Returns after the final batch's upsert result is verified.
     */
    suspend fun seed(
        count: Int,
        textFor: (Int) -> String,
        batchSize: Int = SEED_BATCH,
        onProgress: (seeded: Int, total: Int) -> Unit = { _, _ -> },
    ): Unit = withContext(Dispatchers.IO) {
        for (start in 0 until count step batchSize) {
            val end = (start + batchSize).coerceAtMost(count)
            val docs = ArrayList<ZvecDocBuilder.() -> Unit>(end - start)
            for (i in start until end) {
                val docPk = seedPk(i)
                val text = textFor(i)
                seededPks.add(docPk)
                docs.add {
                    pk = docPk
                    string(ZvecContentStore.FIELD_CONTENT_HASH, docPk)
                    string(ZvecContentStore.FIELD_LOCATOR, seedLocator(i))
                    string(ZvecContentStore.FIELD_CONTENT, text)
                    string(ZvecContentStore.FIELD_CONTENT_NGRAM, text)
                    string(ZvecContentStore.FIELD_COLLECTION_ID, HarnessCorpus.COLLECTION_ID)
                    int64(ZvecContentStore.FIELD_LAST_MODIFIED, SEED_BASE_MTIME + i)
                }
            }
            val result = store.upsertAll(docs)
            if (result.failures.isNotEmpty()) {
                val first = result.failures.first()
                error(
                    "seeder: ${result.failures.size}/${end - start} upserts failed in batch @$start — " +
                        "first idx=${first.index} code=${first.code} detail=${first.detail}"
                )
            }
            onProgress(end, count)
            Log.i(TAG, "seeder: seeded $end/$count docs")
        }
    }

    /**
     * The per-index seeded pk. Deterministic so a re-run re-upserts in place (R8
     * idempotency) instead of leaking duplicates inside a reused directory.
     */
    fun seedPk(index: Int): String = "harness-seed-%08d".format(index)

    fun seedLocator(index: Int): String = "content://harness-seed/$index"

    /**
     * C4: delete the seeded docs. Failures for already-absent pks (NOT_FOUND —
     * the driver may be tearing down after a directory wipe) are tolerated and
     * logged, not thrown — cleanup must be best-effort, never mask the run's own
     * outcome.
     */
    suspend fun cleanup(batchSize: Int = CLEAN_BATCH): Int = withContext(Dispatchers.IO) {
        var deleted = 0
        for (start in 0 until seededPks.size step batchSize) {
            val batch = seededPks.subList(start, (start + batchSize).coerceAtMost(seededPks.size))
            val result = runCatching { store.deleteAll(batch) }
            deleted += result.getOrNull()?.successCount ?: 0
        }
        Log.i(TAG, "seeder: cleanup deleted $deleted/${seededPks.size} seeded docs")
        deleted
    }

    private companion object {
        const val TAG = "IngestionHarness"
        const val SEED_BATCH = 250
        const val CLEAN_BATCH = 1_000
        const val SEED_BASE_MTIME = 1_600_000_000_000L
    }
}
