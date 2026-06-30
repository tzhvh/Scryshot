package io.github.tzhvh.scryernext.zvec

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Issue 07: the hybrid-search surface — [ZvecCollection.hybridSearch], the
 * `multi_query_t` + per-leg `sub_query_t` internal handle lifecycle (build → add
 * legs → set reranker → run → walk results → free), and the **[R3] discharge**.
 *
 * **[R3] is the entire reason this issue exists as a separate slice** (rather
 * than folded into 06): the v0.5.1 FTS-sub-query + vector-sub-query fusion path
 * was an unverified assumption until a test runs it. R3 was verified against the
 * live engine via zvec-studio *before* this test was written — and this suite
 * pins it on-device. The fusion path now carries zero research risk for Phase 4
 * (hybrid *tuning* becomes pure caller wiring).
 *
 * R3 findings (verified live, pinned here):
 *  - **Fusion works**: a vector leg + an FTS leg fused via RRF or Weighted both
 *    return a single ranked, de-duplicated result list combining both signals.
 *    Docs matching BOTH legs (near vector + FTS hit) fuse to the top.
 *  - **Fused score scale ≠ R1's cosine distance.** RRF yields tiny
 *    `1/(k+rank)`-summed values (≈0.03 on a 2-leg query); Weighted yields a
 *    summed weighted score mixing the legs' raw scales (cosine distance ∈
 *    [0,2] + BM25 ∈ [0,∞)). The SDK surfaces the raw fused score untouched.
 *  - **`topK` (post-fusion) is honoured**, distinct from each leg's
 *    `SubQuery.numCandidates` (the per-leg candidate count before fusion).
 *
 * The internal handle lifecycle is the second place in the SDK where the caller
 * NEVER sees a handle, even transiently (Q7): `nativeMultiQuery` builds the
 * multi-query + each leg's sub-query, adds each leg (`add_sub_query` COPIES — see
 * the corrected ownership note in `Query.kt`/the JNI), sets the reranker / topK /
 * filter / output_fields, runs `zvec_collection_multi_query`, and walks the
 * result array via 05's shared `collect_docs`.
 */
@RunWith(AndroidJUnit4::class)
class ZvecHybridSearchTest {
    private val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()

    // Same shape as ZvecQueryTest: a HNSW/COSINE vector field `emb` (4-dim) + an
    // FTS-indexed string field `title` + a plain string `note` (so a projection
    // has something to omit). `emb` is the vector leg's target; `title` carries
    // the FTS index the FTS leg queries.
    private val schema = CollectionSchema(
        name = "screenshots",
        fields = listOf(
            FieldSchema("title", FieldType.STRING, indexParams = IndexParams.FtsParams()),
            FieldSchema("note", FieldType.STRING),
            FieldSchema("emb", FieldType.VECTOR_FP32, dimension = 4,
                indexParams = IndexParams.HnswParams(m = 16, efConstruction = 200)),
        ),
    )

    /**
     * **[R3] DISCHARGE (load-bearing).** One vector leg + one FTS leg, fused by
     * the default RRF reranker, returns a single ranked list combining both
     * signals. Insert four docs:
     *  - `r1`, `r2`: near the query vector AND match the FTS term "receipts".
     *  - `i1`: far from the query vector but does NOT match "receipts".
     *  - `i2`: far from the query vector and does NOT match "receipts".
     *
     * RRF fuses the two ranked legs. The docs matching BOTH legs (r1, r2) rank
     * above the docs matching neither strongly (i1, i2). Assert the fused result
     * is non-empty, ordered, carries a score on each result, and that BOTH
     * matching docs (r1, r2) appear while the non-matching docs are recalled only
     * via their (weak) vector signal. This is the test whose existence discharges
     * R3 — Phase 4 fusion tuning is no longer research-risky.
     */
    @Test fun rrfFusesVectorAndFtsLegs() = runBlocking {
        val dir = File(ctx.cacheDir, "zvec_hybrid_rrf_${System.nanoTime()}")
        ZvecCollection.createAndOpen(dir, schema).use { col ->
            col.upsert { pk = "r1"; full("tax receipts 2025", v(1f, 0f, 0f, 0f)) }
            col.upsert { pk = "r2"; full("receipts for lunch", v(0.9f, 0.1f, 0f, 0f)) }
            col.upsert { pk = "i1"; full("invoice march", v(0f, 0f, 1f, 0f)) }
            col.upsert { pk = "i2"; full("sunset photo beach", v(0f, 1f, 0f, 0f)) }

            val results = col.hybridSearch(
                queries = listOf(
                    SubQuery(field = "emb", vector = v(1f, 0f, 0f, 0f).toList()),
                    SubQuery(field = "title", fts = "receipts"),
                ),
                topK = 4,
                reranker = RrfReranker(),
            )

            assertEquals("post-fusion topK=4 returns four results", 4, results.size)
            val pks = results.map { it.pk }
            assertTrue("r1 (vector-near + FTS match) recalled", "r1" in pks)
            assertTrue("r2 (vector-near + FTS match) recalled", "r2" in pks)
            // The two legs both put r1/r2 at the top, so they fuse above i1/i2.
            val topTwo = pks.take(2).toSet()
            assertTrue("r1 + r2 fuse to the top (matched both legs): $topTwo",
                "r1" in topTwo && "r2" in topTwo)
            results.forEach {
                assertNotNull("every fused result carries a score", it.score)
            }
        }
    }

    /**
     * Fused results are ordered by the engine's fused score (highest first). Pin
     * strict descent so a future regression that loses ordering is loud. (The
     * absolute fused-score scale is reranker-specific — see the class KDoc — so
     * this asserts the *order*, not the scale.)
     */
    @Test fun fusedResultsOrderedByScoreDescending() = runBlocking {
        val dir = File(ctx.cacheDir, "zvec_hybrid_order_${System.nanoTime()}")
        ZvecCollection.createAndOpen(dir, schema).use { col ->
            col.upsert { pk = "r1"; full("tax receipts 2025", v(1f, 0f, 0f, 0f)) }
            col.upsert { pk = "r2"; full("receipts for lunch", v(0.9f, 0.1f, 0f, 0f)) }
            col.upsert { pk = "i1"; full("invoice march", v(0f, 0f, 1f, 0f)) }
            col.upsert { pk = "i2"; full("sunset photo beach", v(0f, 1f, 0f, 0f)) }

            val results = col.hybridSearch(
                queries = listOf(
                    SubQuery(field = "emb", vector = v(1f, 0f, 0f, 0f).toList()),
                    SubQuery(field = "title", fts = "receipts"),
                ),
                topK = 4,
            )

            val scores = results.mapNotNull { it.score }
            assertEquals("every result has a score", results.size, scores.size)
            for (i in 0 until scores.size - 1) {
                assertTrue("fused score descends: ${scores[i]} >= ${scores[i + 1]}",
                    scores[i] >= scores[i + 1])
            }
        }
    }

    /**
     * The weighted reranker fuses the same legs via per-field weights. Same legs
     * as the RRF test, weights `{emb=1.0, title=1.0}` (balanced), assert results
     * are returned and the docs matching both legs (r1, r2) rank at the top — the
     * same directional behaviour RRF shows under a balanced weight. This proves
     * the weighted fusion setter + the field→positional weight resolution works.
     */
    @Test fun weightedRerankerFusesLegs() = runBlocking {
        val dir = File(ctx.cacheDir, "zvec_hybrid_weighted_${System.nanoTime()}")
        ZvecCollection.createAndOpen(dir, schema).use { col ->
            col.upsert { pk = "r1"; full("tax receipts 2025", v(1f, 0f, 0f, 0f)) }
            col.upsert { pk = "r2"; full("receipts for lunch", v(0.9f, 0.1f, 0f, 0f)) }
            col.upsert { pk = "i1"; full("invoice march", v(0f, 0f, 1f, 0f)) }
            col.upsert { pk = "i2"; full("sunset photo beach", v(0f, 1f, 0f, 0f)) }

            val results = col.hybridSearch(
                queries = listOf(
                    SubQuery(field = "emb", vector = v(1f, 0f, 0f, 0f).toList()),
                    SubQuery(field = "title", fts = "receipts"),
                ),
                topK = 4,
                reranker = WeightedReranker(weights = mapOf("emb" to 1.0f, "title" to 1.0f)),
            )

            assertEquals(4, results.size)
            val topTwo = results.take(2).map { it.pk }.toSet()
            assertTrue("r1 + r2 fuse to the top under balanced weights: $topTwo",
                "r1" in topTwo && "r2" in topTwo)
        }
    }

    /**
     * **Directional sanity check** (a calibration check, not a tuning calibration
     * — Phase 4 owns the latter). Cranking the FTS leg's weight far above the
     * vector leg's still leaves the docs matching the FTS term (r1, r2) dominant
     * at the top, because the weighted sum is dominated by the FTS contribution.
     * This proves the field→positional weight array is applied in the right order
     * (a transposed weights array would amplify the wrong leg and fail this).
     */
    @Test fun crankingFtsWeightKeepsFtsMatchesOnTop() = runBlocking {
        val dir = File(ctx.cacheDir, "zvec_hybrid_crank_${System.nanoTime()}")
        ZvecCollection.createAndOpen(dir, schema).use { col ->
            col.upsert { pk = "r1"; full("tax receipts 2025", v(1f, 0f, 0f, 0f)) }
            col.upsert { pk = "r2"; full("receipts for lunch", v(0f, 1f, 0f, 0f)) }
            col.upsert { pk = "i1"; full("invoice march", v(0.99f, 0f, 0f, 0f)) }
            col.upsert { pk = "i2"; full("sunset photo beach", v(0.99f, 0f, 0f, 0f)) }

            // i1/i2 are now the vector-nearest (0.99 along the query axis); r2 is
            // vector-far but FTS-matches; r1 matches both. Heavily weight FTS.
            val results = col.hybridSearch(
                queries = listOf(
                    SubQuery(field = "emb", vector = v(1f, 0f, 0f, 0f).toList()),
                    SubQuery(field = "title", fts = "receipts"),
                ),
                topK = 4,
                reranker = WeightedReranker(weights = mapOf("emb" to 0.001f, "title" to 10f)),
            )

            val topTwo = results.take(2).map { it.pk }.toSet()
            assertTrue("FTS matches (r1, r2) dominate under heavy FTS weight: $topTwo",
                "r1" in topTwo && "r2" in topTwo)
            assertFalse("vector-only-near docs (i1, i2) NOT both in top under heavy FTS weight",
                "i1" in topTwo && "i2" in topTwo)
        }
    }

    /**
     * `topK` (the post-fusion count) is honoured: `topK = 2` returns two results,
     * even though both legs have more candidates available. Distinct from each
     * leg's `SubQuery.numCandidates` (the per-leg count before fusion).
     */
    @Test fun postFusionTopKHonoured() = runBlocking {
        val dir = File(ctx.cacheDir, "zvec_hybrid_topk_${System.nanoTime()}")
        ZvecCollection.createAndOpen(dir, schema).use { col ->
            col.upsert { pk = "r1"; full("tax receipts 2025", v(1f, 0f, 0f, 0f)) }
            col.upsert { pk = "r2"; full("receipts for lunch", v(0.9f, 0.1f, 0f, 0f)) }
            col.upsert { pk = "i1"; full("invoice march", v(0f, 0f, 1f, 0f)) }
            col.upsert { pk = "i2"; full("sunset photo beach", v(0f, 1f, 0f, 0f)) }

            val results = col.hybridSearch(
                queries = listOf(
                    SubQuery(field = "emb", vector = v(1f, 0f, 0f, 0f).toList()),
                    SubQuery(field = "title", fts = "receipts"),
                ),
                topK = 2,
            )

            assertEquals("post-fusion topK=2 returns exactly two results", 2, results.size)
        }
    }

    /**
     * `outputFields` projection is honoured on the fused result (reuses 05's
     * `collect_docs` semantics — same as the single-query path). A projection to
     * `["title"]` returns ONLY `title`; the non-projected `note` is ABSENT
     * (absent = not requested). The score is always present on a fused result.
     */
    @Test fun outputFieldsProjectionHonouredOnFusedResult() = runBlocking {
        val dir = File(ctx.cacheDir, "zvec_hybrid_proj_${System.nanoTime()}")
        ZvecCollection.createAndOpen(dir, schema).use { col ->
            col.upsert { pk = "r1"; full("tax receipts 2025", v(1f, 0f, 0f, 0f)) }
            col.upsert { pk = "r2"; full("receipts for lunch", v(0.9f, 0.1f, 0f, 0f)) }

            val results = col.hybridSearch(
                queries = listOf(
                    SubQuery(field = "emb", vector = v(1f, 0f, 0f, 0f).toList()),
                    SubQuery(field = "title", fts = "receipts"),
                ),
                topK = 2,
                outputFields = listOf("title"),
            )

            assertEquals(2, results.size)
            results.forEach { doc ->
                assertTrue("title present under projection", doc.fields["title"] is ZvecValue.Str)
                assertFalse("note absent under projection (not requested)",
                    doc.fields.containsKey("note"))
                assertFalse("emb absent (vector not requested)", doc.fields.containsKey("emb"))
                assertNotNull("score present on fused result", doc.score)
            }
        }
    }

    /**
     * An empty queries list is invalid — the SDK rejects it before touching
     * native. Pins the at-least-one-leg contract on `hybridSearch`.
     */
    @Test(expected = ZvecException::class)
    fun emptyQueriesThrows() = runBlocking<Unit> {
        val dir = File(ctx.cacheDir, "zvec_hybrid_empty_${System.nanoTime()}")
        ZvecCollection.createAndOpen(dir, schema).use { col ->
            col.hybridSearch(queries = emptyList())
        }
    }

    /**
     * A leg with neither vector nor fts is invalid — the SDK rejects it before
     * touching native. Pins the exactly-one-payload-per-leg contract.
     */
    @Test(expected = ZvecException::class)
    fun legWithNeitherVectorNorFtsThrows() = runBlocking<Unit> {
        val dir = File(ctx.cacheDir, "zvec_hybrid_nopayload_${System.nanoTime()}")
        ZvecCollection.createAndOpen(dir, schema).use { col ->
            col.hybridSearch(
                queries = listOf(
                    SubQuery(field = "emb", vector = v(1f, 0f, 0f, 0f).toList()),
                    SubQuery(field = "title"),  // neither vector nor fts
                ),
            )
        }
    }

    /**
     * A leg with BOTH vector and fts is invalid (a sub-query carries one payload
     * in the C API) — the SDK rejects it before touching native. Use TWO legs.
     */
    @Test(expected = ZvecException::class)
    fun legWithBothVectorAndFtsThrows() = runBlocking<Unit> {
        val dir = File(ctx.cacheDir, "zvec_hybrid_bothpayload_${System.nanoTime()}")
        ZvecCollection.createAndOpen(dir, schema).use { col ->
            col.hybridSearch(
                queries = listOf(
                    SubQuery(field = "emb", vector = v(1f, 0f, 0f, 0f).toList(), fts = "receipts"),
                ),
            )
        }
    }

    // ---- helpers ---------------------------------------------------------

    /** Build a 4-dim vector. */
    private fun v(a: Float, b: Float, c: Float, d: Float) = floatArrayOf(a, b, c, d)

    /**
     * Populate every REQUIRED field with a trivial value (zvec requires every
     * declared field present on write). `emb` is the vector leg's target; `title`
     * carries the FTS index; `note` is the second string so a projection has
     * something to omit. Mirrors ZvecQueryTest's helper.
     */
    private fun ZvecDocBuilder.full(title: String, emb: FloatArray) {
        string("title", title)
        string("note", "n")
        vectorF32("emb", emb)
    }
}
