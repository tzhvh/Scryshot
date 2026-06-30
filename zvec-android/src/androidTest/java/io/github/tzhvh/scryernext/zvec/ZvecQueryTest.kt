package io.github.tzhvh.scryernext.zvec

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Issue 06: the single-vector + pure-FTS query surface — [ZvecCollection.query],
 * the internal `vector_query_t` / `fts_t` handle lifecycle (build → configure →
 * run → walk results → free), and the [R1] score-semantics discharge.
 *
 * R1 finding (verified against the live engine before this test was written, and
 * pinned by [r1ScoreSemanticsUnderCosineDistanceNotSimilarity]): under COSINE
 * `ZvecDoc.score` is the cosine DISTANCE (`1 − cos θ`), range [0, 2], and the
 * engine returns results ASCENDING — nearest first. So **lower = more similar**,
 * and `ZvecDoc.score` is a distance, not a similarity. The SDK surfaces the
 * engine's raw value untouched (no normalization) — Phase 4 interprets it.
 *
 * This suite also pins the engine's pure-FTS recall path (the FTS payload's
 * *match string*, set via `zvec_fts_set_match_string`) and the projection
 * semantics on the query result (reused from issue 05's `collect_docs`).
 */
@RunWith(AndroidJUnit4::class)
class ZvecQueryTest {
    private val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()

    // A HNSW/COSINE vector field `emb` (4-dim) + a string field `title` carrying
    // an FTS index. `emb` is the vector query target; `note` is a second string
    // field so a projection has something to omit.
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
     * Single-vector HNSW query returns docs ordered NEAREST FIRST with a score on
     * each result. Insert four docs with known cosine relationships to the query
     * vector `[1,0,0,0]`; assert the engine returns them in ascending-distance
     * order (near → mid → far → opposite) and every result carries a non-null
     * score. This is the basic single-vector contract; R1 is pinned next.
     */
    @Test fun singleVectorQueryReturnsNearestFirstWithScore() = runBlocking {
        val dir = File(ctx.cacheDir, "zvec_query_hnsw_${System.nanoTime()}")
        ZvecCollection.createAndOpen(dir, schema).use { col ->
            col.upsert { pk = "near"; full("near", v(1f, 0f, 0f, 0f)) }
            col.upsert { pk = "mid"; full("mid", v(0.7071f, 0.7071f, 0f, 0f)) }
            col.upsert { pk = "far"; full("far", v(0f, 0f, 1f, 0f)) }
            col.upsert { pk = "opposite"; full("opposite", v(-1f, 0f, 0f, 0f)) }

            val results = col.query(QueryRequest(field = "emb", vector = v(1f, 0f, 0f, 0f).toList(), topK = 4))

            assertEquals("topK=4 returns four results", 4, results.size)
            // Engine returns nearest first (R1: ascending cosine distance).
            assertEquals(listOf("near", "mid", "far", "opposite"),
                results.map { it.pk })
            results.forEach {
                assertNotNull("every result carries a score", it.score)
            }
        }
    }

    /**
     * **R1 DISCHARGE (load-bearing).** Under COSINE, `ZvecDoc.score` is the cosine
     * *distance* (`1 − cos θ`), range [0, 2]; the engine returns results ASCENDING
     * (nearest first). Pin each landmark:
     *  - identical vectors  → score ≈ 0.0   (cos θ = 1)
     *  - 45° vectors        → score ≈ 0.293 (cos θ ≈ 0.707)
     *  - orthogonal vectors → score ≈ 1.0   (cos θ = 0)
     *  - opposite vectors   → score ≈ 2.0   (cos θ = −1)
     * And assert strict ascent: score(near) < score(mid) < score(far) < score(opposite).
     *
     * This is the entire reason the test exists beyond a smoke check — the
     * convention goes in the issue's `## Comments` and drives how Phase 4
     * interprets `ZvecDoc.score`.
     */
    @Test fun r1ScoreSemanticsUnderCosineDistanceNotSimilarity() = runBlocking {
        val dir = File(ctx.cacheDir, "zvec_query_r1_${System.nanoTime()}")
        ZvecCollection.createAndOpen(dir, schema).use { col ->
            col.upsert { pk = "near"; full("near", v(1f, 0f, 0f, 0f)) }
            col.upsert { pk = "mid"; full("mid", v(0.7071f, 0.7071f, 0f, 0f)) }
            col.upsert { pk = "far"; full("far", v(0f, 0f, 1f, 0f)) }
            col.upsert { pk = "opposite"; full("opposite", v(-1f, 0f, 0f, 0f)) }

            val byPk = col.query(QueryRequest(field = "emb", vector = v(1f, 0f, 0f, 0f).toList(), topK = 4))
                .associateBy { it.pk }

            val near = byPk.getValue("near").score!!
            val mid = byPk.getValue("mid").score!!
            val far = byPk.getValue("far").score!!
            val opposite = byPk.getValue("opposite").score!!

            // Landmark distances: identical→0, 45°→0.293, orthogonal→1, opposite→2.
            assertEquals("identical vectors → distance 0", 0.0f, near, 1e-4f)
            assertEquals("45° vectors → distance ≈ 0.293", 0.2929f, mid, 1e-3f)
            assertEquals("orthogonal vectors → distance 1", 1.0f, far, 1e-4f)
            assertEquals("opposite vectors → distance 2", 2.0f, opposite, 1e-4f)

            // Strict ascent = nearest first. (R1 direction: lower = more similar.)
            assertTrue("score(near) < score(mid): $near < $mid", near < mid)
            assertTrue("score(mid) < score(far): $mid < $far", mid < far)
            assertTrue("score(far) < score(opposite): $far < $opposite", far < opposite)
        }
    }

    /**
     * Pure-FTS query recalls the matching docs. Insert docs with distinct text,
     * query with `fts = "receipts"`, and assert the docs whose `title` contains
     * "receipts" are recalled (and the non-matching doc is not). This pins the
     * engine's FTS payload *match string* path (`zvec_fts_set_match_string`) —
     * the FTS index lives on `title`, and the query carries the FTS payload, not
     * a vector.
     */
    @Test fun pureFtsQueryRecallsMatchingDocs() = runBlocking {
        val dir = File(ctx.cacheDir, "zvec_query_fts_${System.nanoTime()}")
        ZvecCollection.createAndOpen(dir, schema).use { col ->
            col.upsert { pk = "r1"; full("tax receipts 2025", v(1f, 0f, 0f, 0f)) }
            col.upsert { pk = "r2"; full("receipts for lunch", v(0f, 1f, 0f, 0f)) }
            col.upsert { pk = "i1"; full("invoice march", v(0f, 0f, 1f, 0f)) }

            val results = col.query(QueryRequest(field = "title", fts = "receipts", topK = 10))
                .map { it.pk }.toSet()

            assertTrue("r1 (tax receipts) recalled", "r1" in results)
            assertTrue("r2 (receipts lunch) recalled", "r2" in results)
            assertFalse("i1 (invoice, no match) not recalled", "i1" in results)
        }
    }

    /**
     * `outputFields` projection is honoured on the query result (reuses 05's
     * `collect_docs` semantics). A projection to `["title"]` returns ONLY `title`;
     * the non-projected `note` field is ABSENT from the returned `fields` map
     * (absent = not requested). The score is always present on a query result
     * regardless of the projection.
     */
    @Test fun outputFieldsProjectionHonouredOnQueryResult() = runBlocking {
        val dir = File(ctx.cacheDir, "zvec_query_proj_${System.nanoTime()}")
        ZvecCollection.createAndOpen(dir, schema).use { col ->
            col.upsert { pk = "p1"; full("alpha", v(1f, 0f, 0f, 0f)) }
            col.upsert { pk = "p2"; full("beta", v(0.9f, 0.1f, 0f, 0f)) }

            val results = col.query(
                QueryRequest(field = "emb", vector = v(1f, 0f, 0f, 0f).toList(), topK = 2),
                outputFields = listOf("title"),
            )

            assertEquals(2, results.size)
            results.forEach { doc ->
                assertTrue("title present under projection", doc.fields["title"] is ZvecValue.Str)
                assertFalse("note absent under projection (not requested)",
                    doc.fields.containsKey("note"))
                // The query vector field is not requested and not auto-included.
                assertFalse("emb absent (vector not requested)", doc.fields.containsKey("emb"))
                // Score is query-only — present regardless of the field projection.
                assertNotNull("score present on query result", doc.score)
            }
        }
    }

    /**
     * An empty result is a normal empty list (no throw). Querying a near-vector
     * against a collection with docs that all sit far away still returns them up
     * to topK; the empty case is a topK-bearing query that the engine ranks as
     * empty only when there are no docs at all.
     */
    @Test fun emptyCollectionQueryReturnsEmptyList() = runBlocking {
        val dir = File(ctx.cacheDir, "zvec_query_empty_${System.nanoTime()}")
        ZvecCollection.createAndOpen(dir, schema).use { col ->
            val results = col.query(QueryRequest(field = "emb", vector = v(1f, 0f, 0f, 0f).toList(), topK = 10))
            assertEquals(emptyList<ZvecDoc>(), results)
        }
    }

    /**
     * A query with neither vector nor fts is invalid — the SDK rejects it before
     * touching native. Pins the exactly-one-of contract on [QueryRequest].
     */
    @Test(expected = ZvecException::class)
    fun queryWithNeitherVectorNorFtsThrows() = runBlocking<Unit> {
        val dir = File(ctx.cacheDir, "zvec_query_invalid_${System.nanoTime()}")
        ZvecCollection.createAndOpen(dir, schema).use { col ->
            col.query(QueryRequest(field = "emb", vector = null, fts = null))
        }
    }

    // ---- helpers ---------------------------------------------------------

    /** Build a 4-dim vector. */
    private fun v(a: Float, b: Float, c: Float, d: Float) = floatArrayOf(a, b, c, d)

    /**
     * Populate every REQUIRED field with a trivial value (zvec requires every
     * declared field present on write). `emb` is the query target; `title` carries
     * the FTS index; `note` is the second string so a projection has something to
     * omit. Reuses the same "populate every required field" discipline as issue
     * 05's helper.
     */
    private fun ZvecDocBuilder.full(title: String, emb: FloatArray) {
        string("title", title)
        string("note", "n")
        vectorF32("emb", emb)
    }
}
