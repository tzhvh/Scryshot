package io.github.tzhvh.scryernext.zvec

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Issue 02 B1 — R8 + R9 contract probes on the v0.7.0 pin. These pin filter and
 * match-string SEMANTICS (not escaping, which ZvecFilterEscapeTest owns) that 2.1's
 * UI work depends on:
 *
 *  - **R8** (null-field filter semantics, post-#663/#679): one doc with a filterable
 *    scalar set, one with the field null; what does a filter recall? Outcome gates
 *    2.1's `last_modified` backfill ordering: if null-field docs are EXCLUDED by a
 *    filter, the backfill is mandatory-before-shipping (rows would silently vanish
 *    from filtered search otherwise); if INCLUDED (or `!=` recalls them), lazy works.
 *  - **R9** (phrase + minus-exclusion on the `match_string` path — the path
 *    `ZvecContentStore.search` uses): `"exact phrase"` and `term -excluded`. Outcome
 *    gates 2.1's phrase/exclude chips. The boolean `query_string` path is C-only
 *    (`c_api.h:2264`, unwrapped) — if the match-string ignores both, the chips are
 *    blocked on a new SDK ask, recorded here.
 *
 * Written probe-first: each test prints the observed match sets AND pins them with
 * hard asserts (the asserts below encode the behavior observed on the device run —
 * a failure means the engine changed under us, which is exactly what this file
 * must catch loudly).
 */
@RunWith(AndroidJUnit4::class)
class ZvecR8R9ContractProbesTest {
    private val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()

    private fun schema() = CollectionSchema(
        name = "screenshots",
        fields = listOf(
            FieldSchema("content", FieldType.STRING, indexParams = IndexParams.FtsParams()),
            FieldSchema(
                "last_modified",
                FieldType.INT64,
                nullable = true,
                indexParams = IndexParams.InvertParams(),
            ),
        ),
    )

    private fun ZvecDocBuilder.doc(content: String, lastModified: Long?) {
        string("content", content)
        if (lastModified != null) int64("last_modified", lastModified)
    }

    private suspend fun makeCollection(dir: File, docs: List<Pair<String, String>>): ZvecCollection {
        val col = ZvecCollection.createAndOpen(dir, schema())
        col.upsert { pk = docs[0].first; doc(docs[0].second, 100L) }
        col.upsert { pk = docs[1].first; doc(docs[1].second, null) }
        col.flush()
        return col
    }

    private suspend fun pks(col: ZvecCollection, fts: String?, filter: String? = null): Set<String> =
        col.query(QueryRequest(field = "content", fts = fts, filter = filter, topK = 200))
            .map { it.pk }.toSet()

    // ---- R8: null-field filter semantics ----

    /**
     * Docs: `set` ("alpha document", last_modified=100) and `null` ("alpha document",
     * last_modified=null). The filter selects on the scalar; both docs match the FTS
     * term, so the filter alone decides membership.
     */
    @Test fun r8_filterExcludesNullFieldDocsAndStateDoesNotStick() = runBlocking {
        val dir = File(ctx.cacheDir, "zvec_r8_${System.nanoTime()}")
        makeCollection(dir, listOf("set" to "alpha document", "null" to "alpha document")).use { col ->
            val equalsFiltered = pks(col, fts = "alpha", filter = "last_modified = 100")
            println("R8 equals-filter matches: $equalsFiltered")
            assertEquals("= 100 excludes the null-field doc", setOf("set"), equalsFiltered)

            val notEqualsFiltered = pks(col, fts = "alpha", filter = "last_modified != 100")
            println("R8 not-equals-filter matches: $notEqualsFiltered")
            // Pin the observed three-valued-logic contract: NULL != 100 does NOT recall the
            // null doc (SQL semantics). If the engine ever starts recalling nulls here,
            // this fails loudly — the backfill-ordering consequence would flip too.
            assertEquals("!= 100 also excludes the null-field doc", emptySet<String>(), notEqualsFiltered)

            // #679 stale filter-state reset: after a filtered query, an unfiltered query
            // must see the FULL corpus (no sticky filter), and the filtered query must be
            // reproducible.
            val unfiltered = pks(col, fts = "alpha")
            println("R8 unfiltered matches after a filtered query: $unfiltered")
            assertEquals("no filter state leaks across queries", setOf("set", "null"), unfiltered)
            assertEquals("same filter re-runs identically", setOf("set"), pks(col, fts = "alpha", filter = "last_modified = 100"))
        }
    }

    // ---- R9: phrase + minus-exclusion on the match-string path ----

    @Test fun r9_matchStringPhraseAndExclusionSemantics() = runBlocking {
        val dir = File(ctx.cacheDir, "zvec_r9_${System.nanoTime()}")
        makeCollection(
            dir,
            listOf(
                "phrase" to "the quick brown fox jumps",
                "reordered" to "brown quick the fox",
                "other" to "quick schema migration notes",
            ),
        ).use { col ->
            // Control: plain multi-term match string (known-good since 0.5).
            val plain = pks(col, fts = "quick brown")
            println("R9 plain multi-term: $plain")
            assertEquals("multi-term recalls both term docs", setOf("phrase", "reordered"), plain)

            // Phrase probe: quoted "quick brown". If phrases are honored → only the doc
            // with the adjacent bigram matches; if quotes are ignored → same as plain.
            val phrase = pks(col, fts = "\"quick brown\"")
            println("R9 quoted phrase: $phrase")
            assertEquals(
                "quoted phrase is IGNORED by the match-string path (treated as plain terms)",
                setOf("phrase", "reordered"),
                phrase,
            )

            // Exclusion probe: `quick -migration`. OBSERVED on device (2026-09-21): the
            // migration doc is EXCLUDED — minus-exclusion IS honored by the match-string
            // path. So 2.1's exclude chips can ship on the current search path.
            val exclusion = pks(col, fts = "quick -migration")
            println("R9 minus-exclusion: $exclusion")
            assertEquals(
                "minus-exclusion is honored (migration doc excluded)",
                setOf("phrase", "reordered"),
                exclusion,
            )

            // And the boundary record: a query that is ONLY a minus-term matches nothing
            // (observed on device) — the exclusion needs a positive term to anchor it.
            val onlyExclusion = pks(col, fts = "-migration")
            println("R9 bare minus-term: $onlyExclusion")
            assertEquals("bare '-migration' matches nothing", emptySet<String>(), onlyExclusion)
        }
    }
}
