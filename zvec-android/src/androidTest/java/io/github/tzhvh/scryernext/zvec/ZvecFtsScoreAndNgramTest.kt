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
 * Issue 01 A5 gate probes (v0.7.0 retarget) — the two FTS facts the rest of the
 * suite does not yet pin:
 *
 *  - **BM25 ordering** (A5-7b): on a pure-FTS query `ZvecDoc.score` orders
 *    strongest match first. [ZvecQueryTest.pureFtsQueryRecallsMatchingDocs]
 *    pins recall membership; this adds the *ordering* half of the score-
 *    direction assert (the COSINE half is R1, pinned in ZvecQueryTest).
 *  - **ngram tokenizer reachability** (A5-9): `FtsParams(tokenizer = "ngram")`
 *    is accepted on 0.7 and recalls *partial* tokens the standard tokenizer
 *    misses — the surface 02-B3's golden-set ngram arm depends on. Also pins
 *    the `extraParams` shape (`ngram_min` / `ngram_max`, c_api.h:1224-1235).
 */
@RunWith(AndroidJUnit4::class)
class ZvecFtsScoreAndNgramTest {
    private val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()

    // Same shape as ZvecQueryTest: FTS-indexed `title` + plain `note` + the
    // 4-dim HNSW `emb` every collection in this suite carries.
    private val schema = CollectionSchema(
        name = "screenshots",
        fields = listOf(
            FieldSchema("title", FieldType.STRING, indexParams = IndexParams.FtsParams()),
            FieldSchema("note", FieldType.STRING),
            FieldSchema("emb", FieldType.VECTOR_FP32, dimension = 4,
                indexParams = IndexParams.HnswParams(m = 16, efConstruction = 200)),
        ),
    )

    private fun v(a: Float, b: Float, c: Float, d: Float) = floatArrayOf(a, b, c, d)

    private fun ZvecDocBuilder.full(title: String, emb: FloatArray) {
        string("title", title)
        string("note", "note-$title")
        vectorF32("emb", emb)
    }

    /**
     * BM25 orders the dense match above the diffuse one: a title that IS the
     * term ("receipts") must outscore a long title where the term appears
     * amid other tokens, and both matches must carry a positive score. Asserts
     * strict descent (s before w, s.score > w.score > 0) — the same direction
     * convention the fused ordering test relies on.
     */
    @Test fun bm25OrdersStrongerFtsMatchFirst() = runBlocking {
        val dir = File(ctx.cacheDir, "zvec_fts_bm25_${System.nanoTime()}")
        ZvecCollection.createAndOpen(dir, schema).use { col ->
            col.upsert { pk = "s"; full("receipts", v(1f, 0f, 0f, 0f)) }
            col.upsert { pk = "w"; full("march invoice batch receipts and papers receipts folder", v(0f, 1f, 0f, 0f)) }
            col.upsert { pk = "i"; full("sunset photo beach", v(0f, 0f, 1f, 0f)) }

            val results = col.query(QueryRequest(field = "title", fts = "receipts", topK = 10))

            val pks = results.map { it.pk }
            assertTrue("both matches recalled: $pks", setOf("s", "w").all { it in pks })
            assertFalse("non-match excluded: $pks", "i" in pks)

            val s = results.first { it.pk == "s" }
            val w = results.first { it.pk == "w" }
            assertNotNull("match carries a score", s.score)
            assertNotNull("weak match carries a score", w.score)
            assertTrue("strong match has positive score: ${s.score}", s.score!! > 0f)
            assertTrue("weak match has positive score: ${w.score}", w.score!! > 0f)
            assertEquals("strong match ranks first: $pks", "s", pks.first())
            assertTrue("BM25 descends: ${s.score} > ${w.score}", s.score!! > w.score!!)
        }
    }

    /**
     * The ngram tokenizer indexes mid-token substrings, so a query for a
     * fragment ("positGo" inside "ScreenshotGo") is recalled — the exact
     * capability the standard tokenizer lacks (contrast asserted). A second
     * scratch collection pins that explicit `extraParams` with `ngram_min` /
     * `ngram_max` is an accepted surface (c_api.h:1224-1235).
     */
    @Test fun ngramTokenizerRecallsPartialTokens() = runBlocking {
        val dir = File(ctx.cacheDir, "zvec_fts_ngram_${System.nanoTime()}")
        val ngramSchema = CollectionSchema(
            name = "screenshots",
            fields = listOf(
                FieldSchema("title", FieldType.STRING,
                    indexParams = IndexParams.FtsParams(tokenizer = "ngram")),
                FieldSchema("note", FieldType.STRING),
                FieldSchema("emb", FieldType.VECTOR_FP32, dimension = 4,
                    indexParams = IndexParams.HnswParams(m = 16, efConstruction = 200)),
            ),
        )
        ZvecCollection.createAndOpen(dir, ngramSchema).use { col ->
            col.upsert { pk = "n1"; full("ScreenshotGo_RepositoryManager", v(1f, 0f, 0f, 0f)) }
            col.upsert { pk = "n2"; full("unrelated text here", v(0f, 1f, 0f, 0f)) }

            val ngramHits = col.query(QueryRequest(field = "title", fts = "positGo", topK = 10))
                .map { it.pk }.toSet()
            assertTrue("ngram recalls mid-token fragment: $ngramHits", "n1" in ngramHits)
            assertFalse("ngram excludes non-match: $ngramHits", "n2" in ngramHits)
        }

        // Contrast: the standard tokenizer does NOT recall the fragment — the
        // recall above is ngram doing the work, not a generic substring path.
        val stdDir = File(ctx.cacheDir, "zvec_fts_std_${System.nanoTime()}")
        ZvecCollection.createAndOpen(stdDir, schema).use { col ->
            col.upsert { pk = "n1"; full("ScreenshotGo_RepositoryManager", v(1f, 0f, 0f, 0f)) }
            val stdHits = col.query(QueryRequest(field = "title", fts = "positGo", topK = 10))
                .map { it.pk }.toSet()
            assertFalse("standard tokenizer misses the fragment: $stdHits", "n1" in stdHits)
        }

        // extraParams surface: explicit ngram_min/ngram_max accepted at create.
        val sizedDir = File(ctx.cacheDir, "zvec_fts_ngram_sized_${System.nanoTime()}")
        val sizedSchema = CollectionSchema(
            name = "screenshots",
            fields = listOf(
                FieldSchema("title", FieldType.STRING,
                    indexParams = IndexParams.FtsParams(
                        tokenizer = "ngram",
                        extraParams = """{"ngram_min": 2, "ngram_max": 3}""",
                    )),
                FieldSchema("note", FieldType.STRING),
                FieldSchema("emb", FieldType.VECTOR_FP32, dimension = 4,
                    indexParams = IndexParams.HnswParams(m = 16, efConstruction = 200)),
            ),
        )
        ZvecCollection.createAndOpen(sizedDir, sizedSchema).use { col ->
            col.upsert { pk = "n1"; full("ScreenshotGo_RepositoryManager", v(1f, 0f, 0f, 0f)) }
            val hits = col.query(QueryRequest(field = "title", fts = "positGo", topK = 10))
                .map { it.pk }.toSet()
            assertTrue("ngram(min=2,max=3) recalls mid-token fragment: $hits", "n1" in hits)
        }
    }
}
