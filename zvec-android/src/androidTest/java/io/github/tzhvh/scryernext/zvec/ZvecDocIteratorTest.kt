package io.github.tzhvh.scryernext.zvec

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Issue 02 B6 — on-device contract for [ZvecCollection.iterDocs], the 0.7 DocIterator surface
 * (the SDK's first key-enumeration primitive).
 *
 * What Layers 1+2 cannot check and this pins on the live engine:
 *  - the walk is COMPLETE (every upserted pk comes back exactly once — no sampling);
 *  - projection is honored and vectors are excluded on the null-projection default;
 *  - snapshot walks are fetch-like: score is null on every doc (engine order is not a ranking);
 *  - the walk composes with the store's long-lived-handle lifecycle (01-A5 assert 8 pinned the
 *    symbols; this pins behavior).
 *
 * Snapshot isolation (post-create writes invisible) is documented on [ZvecCollection.iterDocs] but
 * deliberately NOT asserted here: the SDK never exposes an open iterator, so a walk and its
 * snapshot begin and end inside one call — there is no observable window to write into.
 */
@RunWith(AndroidJUnit4::class)
class ZvecDocIteratorTest {
    private val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()

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

    @Test fun iterDocsWalksEveryInsertedDocExactlyOnce() = runBlocking {
        val dir = File(ctx.cacheDir, "zvec_iter_all_${System.nanoTime()}")
        ZvecCollection.createAndOpen(dir, schema).use { col ->
            val pks = (1..7).map { "pk-$it" }
            pks.forEachIndexed { i, pk ->
                col.upsert { this.pk = pk; full("title $i", v(1f, 0f, 0f, 0f)) }
            }

            val docs = col.iterDocs()
            assertEquals("walk returns every doc exactly once", pks, docs.map { it.pk }.sorted())
        }
    }

    @Test fun iterDocsProjectionAndDefaultsHonoured() = runBlocking {
        val dir = File(ctx.cacheDir, "zvec_iter_proj_${System.nanoTime()}")
        ZvecCollection.createAndOpen(dir, schema).use { col ->
            col.upsert { pk = "d1"; full("alpha beta", v(1f, 0f, 0f, 0f)) }
            col.upsert { pk = "d2"; full("gamma delta", v(0f, 1f, 0f, 0f)) }

            // Null projection: all scalar fields, vectors excluded, score null.
            val all = col.iterDocs().associateBy { it.pk }
            assertEquals(2, all.size)
            all.values.forEach { doc ->
                assertNull("snapshot walk is not a ranking — score must be null", doc.score)
                assertFalse("vectors excluded on the default walk", doc.fields.containsKey("emb"))
                assertTrue("scalar fields present", doc.fields.keys.containsAll(listOf("title", "note")))
            }
            assertEquals("alpha beta", (all.getValue("d1").fields["title"] as ZvecValue.Str).value)

            // Projection: only the requested field comes back.
            val titles = col.iterDocs(outputFields = listOf("title")).associateBy { it.pk }
            assertEquals(setOf("title"), titles.getValue("d2").fields.keys)
        }
    }
}
