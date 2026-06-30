package io.github.tzhvh.scryernext.zvec

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Issue 08 — the **instrumentation half** of the filter-escape round-trip. The pure
 * escaping logic is JVM-tested in [ZvecFilterEscapeTest]; this suite proves the
 * escaped literal actually queries correctly through the real `libzvec_c_api.so`, and
 * demonstrates — with a live engine — the injection vector the helper closes.
 *
 * Engine facts pinned here (verified against the live engine via the zvec MCP before
 * this was written; see the `## Engine notes` at the bottom):
 *  - A `tag = '<v>'` filter needs an **INVERT index** on `tag` to match (`=` on an
 *    un-indexed string scans/returns nothing).
 *  - Backslash-escape rule: `tag = 'O\'Brien'` matches stored `O'Brien`; the SQL
 *    doubling `tag = 'O''Brien'` is a SYNTAX ERROR.
 *  - The injection payload `x' OR tag = 'plain` interpolated raw into
 *    `tag = 'x' OR tag = 'plain'` matches `plain` (a successful breakout); the
 *    escaped form matches nothing.
 *
 * Schema: one vector field `emb` (FLAT, the query vehicle) + one string field `tag`
 * with an INVERT index (so the `=` filter matches).
 */
@RunWith(AndroidJUnit4::class)
class ZvecFilterEscapeInstrumentedTest {
    private val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()

    private val schema = CollectionSchema(
        name = "screenshots",
        fields = listOf(
            // INVERT on `tag` is required for a `tag = '...'` filter to match (an
            // un-indexed string field returns nothing under `=`).
            FieldSchema("tag", FieldType.STRING, indexParams = IndexParams.InvertParams()),
            FieldSchema("emb", FieldType.VECTOR_FP32, dimension = 2,
                indexParams = IndexParams.FlatParams(metric = MetricType.L2)),
        ),
    )

    /**
     * The load-bearing round-trip: a filter built **with** [ZvecFilters.escapeFilterValue]
     * on a quote-containing value queries correctly. Insert `O'Brien`, query with
     * `tag = 'O\'Brien'` (the escaped literal), assert the right doc returns and the
     * others don't. This is the whole point of the helper — escaping does not corrupt
     * the value; the stored quote survives the round trip.
     */
    @Test fun escapedFilterRetrievesQuoteValue() = runBlocking {
        val dir = File(ctx.cacheDir, "zvec_filter_quote_${System.nanoTime()}")
        ZvecCollection.createAndOpen(dir, schema).use { col ->
            col.upsert { pk = "obrien"; full("O'Brien", v(0f, 0f)) }
            col.upsert { pk = "plain"; full("plain", v(0f, 0f)) }

            val filter = "tag = ${ZvecFilters.escapeFilterValue("O'Brien")}"
            val pks = col.query(QueryRequest(field = "emb", vector = v(0f, 0f).toList(),
                filter = filter, topK = 10)).map { it.pk }.toSet()

            assertEquals("the escaped filter should match O'Brien only",
                setOf("obrien"), pks)
        }
    }

    /**
     * **Negative: the injection vector the helper closes.** Insert `plain` and a doc
     * whose value is the attacker's *no-match* payload, then build a filter with the
     * **unescaped** payload. The resulting `tag = 'x' OR tag = 'plain'` matches
     * `plain` — a successful breakout from a value that should have matched nothing.
     * This is the risk [ZvecFilters.escapeFilterValue] exists to neutralize; the next
     * test shows the escaped form matches nothing.
     */
    @Test fun unescapedPayloadIsTheInjectionVectorMatchesUnintendedDoc() = runBlocking {
        val dir = File(ctx.cacheDir, "zvec_filter_inject_${System.nanoTime()}")
        ZvecCollection.createAndOpen(dir, schema).use { col ->
            col.upsert { pk = "plain"; full("plain", v(0f, 0f)) }

            // The attacker controls this value; it should match NO doc.
            val maliciousValue = "x' OR tag = 'plain"
            // UNSAFE: raw interpolation, no escapeFilterValue. This is the bug.
            val unsafeFilter = "tag = '$maliciousValue'"
            // -> tag = 'x' OR tag = 'plain'   (the OR breaks out of the literal)

            val pks = col.query(QueryRequest(field = "emb", vector = v(0f, 0f).toList(),
                filter = unsafeFilter, topK = 10)).map { it.pk }.toSet()

            // The injection SUCCEEDS: 'plain' is returned even though the attacker's
            // value ('x...') matched nothing on its own. This is the vector.
            assertTrue("injection vector: unescaped payload matched 'plain' ($pks)",
                "plain" in pks)
        }
    }

    /**
     * **The escape neutralizes the injection.** The same malicious payload, now run
     * through [ZvecFilters.escapeFilterValue], becomes the single literal
     * `'x\' OR tag = \'plain'` — the `OR` is trapped inside the string, so it matches
     * no doc (exactly what the value 'x...' should have done). The contrast with the
     * previous test is the entire safety argument.
     */
    @Test fun escapedPayloadMatchesNothingInjectionNeutralized() = runBlocking {
        val dir = File(ctx.cacheDir, "zvec_filter_safe_${System.nanoTime()}")
        ZvecCollection.createAndOpen(dir, schema).use { col ->
            col.upsert { pk = "plain"; full("plain", v(0f, 0f)) }

            val maliciousValue = "x' OR tag = 'plain"
            val safeFilter = "tag = ${ZvecFilters.escapeFilterValue(maliciousValue)}"
            // -> tag = 'x\' OR tag = \'plain'   (one literal; OR is inert inside it)

            val results = col.query(QueryRequest(field = "emb", vector = v(0f, 0f).toList(),
                filter = safeFilter, topK = 10))

            assertTrue("escaped injection payload matched nothing ($results)",
                results.isEmpty())
        }
    }

    /**
     * A plain (no-metacharacter) value round-trips through the helper identically to
     * a hand-written literal — the helper is a no-op on the body for safe input, so a
     * caller can route every value through it unconditionally.
     */
    @Test fun escapedFilterRetrievesPlainValue() = runBlocking {
        val dir = File(ctx.cacheDir, "zvec_filter_plain_${System.nanoTime()}")
        ZvecCollection.createAndOpen(dir, schema).use { col ->
            col.upsert { pk = "a"; full("alpha", v(0f, 0f)) }
            col.upsert { pk = "b"; full("beta", v(0f, 0f)) }

            val filter = "tag = ${ZvecFilters.escapeFilterValue("alpha")}"
            val pks = col.query(QueryRequest(field = "emb", vector = v(0f, 0f).toList(),
                filter = filter, topK = 10)).map { it.pk }.toSet()

            assertEquals(setOf("a"), pks)
            assertFalse("beta not matched by the alpha filter", "b" in pks)
        }
    }

    // ---- helpers ---------------------------------------------------------

    private fun v(a: Float, b: Float) = floatArrayOf(a, b)

    /** Populate every REQUIRED field: `tag` (the filter target) + `emb` (query vehicle). */
    private fun ZvecDocBuilder.full(tag: String, emb: FloatArray) {
        string("tag", tag)
        vectorF32("emb", emb)
    }

    // ---- Engine notes (ground truth, verified live via the zvec MCP) -------
    //
    //  - `tag = 'O\'Brien'` (backslash-escape) → matches stored `O'Brien`. CORRECT.
    //  - `tag = 'O''Brien'` (SQL doubling)     → "syntax error: extraneous input
    //    ''Brien'' expecting <EOF>". Quote-doubling does NOT work; backslash-escape
    //    is the only rule.
    //  - `=` on a string field needs an INVERT index to match (an un-indexed field
    //    returns nothing under `=`). INVERT is declared on `tag` in [schema].
    //  - The injection payload `x' OR tag = 'plain` interpolated raw yields
    //    `tag = 'x' OR tag = 'plain'` and matches `plain` (breakout). Escaped, it is
    //    one literal and matches nothing. Both pinned above.
    //  - Backslash *content* (`a\b`) did not round-trip through the INVERT `=`
    //    filter in live probing (matched nothing even when escaped correctly). That
    //    is an INVERT-tokenizer matching quirk for backslash-bearing content — a
    //    Phase-5 filter-*semantics* concern (R4), explicitly out of scope for issue
    //    08. The escape rule itself is unaffected: escaping `\` is still required to
    //    keep the literal well-formed (an unescaped `\` lets a value's trailing char
    //    escape the closing quote). The JVM unit suite pins the escape output; this
    //    instrumentation suite pins the quote + injection round-trips.
}
