package io.github.tzhvh.scryernext.zvec

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit test for [ZvecFilters.escapeFilterValue] — the **one pure-JVM seam** in
 * Phase 1 (the issue's "only JVM unit-test seam in Phase 1"). The helper is pure
 * string logic (no JNI, no `.so`), so it runs on the JVM; its round-trip half (does
 * an escaped filter query correctly?) needs the emulator and lives in the
 * instrumentation twin of this class.
 *
 * The escape rule this pins is **not** the issue's proposed "conservative SQL-literal
 * quoting (quote + double internal quotes + escape backslash)". That fallback was
 * written for the case where the v0.5.1 parser rules couldn't be confirmed from
 * source. They WERE confirmed — the ANTLR `SQUOTA_STRING` rule
 * (`'\'' (~('\'' | '\\') | '\\'. )* '\''`) is a **backslash-escape** grammar, and the
 * live engine confirms SQL quote-doubling is a *syntax error* (see
 * [ZvecFilters]). So this suite pins backslash-escaping of `\` and `'`, and
 * explicitly asserts that the SQL-doubling shape is NOT what the helper produces.
 */
class ZvecFilterEscapeTest {

    // ---- the value-literal contract: every output is a single closed literal ----

    /** A plain value is wrapped in single quotes, body unchanged. */
    @Test fun plainValueIsQuotedAndUnchanged() {
        assertEquals("'plain'", ZvecFilters.escapeFilterValue("plain"))
    }

    /** The output ALWAYS starts and ends with a single quote (a closed literal). */
    @Test fun outputAlwaysBookendedBySingleQuotes() {
        listOf(
            "",
            "plain",
            "O'Brien",
            """a\b""",
            "x' OR tag = 'plain",
            "ends with quote'",
            "'starts with quote",
            "tab\there",
        ).forEach { input ->
            val out = ZvecFilters.escapeFilterValue(input)
            assertTrue("must start with ' : $input -> $out", out.startsWith('\''))
            assertTrue("must end with ' (not an escape \\\\'): $input -> $out",
                out.endsWith('\''))
            // The closing quote is a real terminator, not the second char of a \'
            // pair: the char before the final quote must not be an unescaped backslash.
            assertTrue("closing quote must not be escaped: $input -> $out",
                out.length < 2 || out[out.length - 2] != '\\' ||
                    (out.length >= 3 && out[out.length - 3] == '\\'))
        }
    }

    // ---- the load-bearing metacharacter cases ----

    /** `O'Brien` → the quote is backslash-escaped, NOT SQL-doubled. */
    @Test fun singleQuoteIsBackslashEscapedNotDoubled() {
        assertEquals("'O\\'Brien'", ZvecFilters.escapeFilterValue("O'Brien"))
        // And explicitly NOT the SQL-doubling shape — that is a syntax error in zvec.
        assertNotEquals("'O''Brien'", ZvecFilters.escapeFilterValue("O'Brien"))
    }

    /** A backslash is doubled so it can't start an escape the value didn't intend. */
    @Test fun backslashIsDoubled() {
        assertEquals("'a\\\\b'", ZvecFilters.escapeFilterValue("""a\b"""))
    }

    /** Multiple quotes and backslashes are each escaped independently. */
    @Test fun multipleMetacharactersEachEscaped() {
        // value: ' \ ' \   (quote, backslash, quote, backslash)
        val value = "'\\'\\"
        assertEquals("'\\'\\\\\\'\\\\'", ZvecFilters.escapeFilterValue(value))
    }

    // ---- the injection payloads ----

    /**
     * The headline injection payload. An unescaped `x' OR tag = 'plain` interpolated
     * into `tag = '<v>'` becomes `tag = 'x' OR tag = 'plain'` — the attacker pivots
     * from a no-match to a successful match on an arbitrary doc. Escaped, the `OR`
     * is trapped *inside* the literal and matches nothing.
     *
     * Verified live against the engine: the escaped filter `'x\' OR tag = \'plain'`
     * returns zero results; the raw-interpolated `tag = 'x' OR tag = 'plain'`
     * matches `plain` (the injection twin in the instrumentation suite pins this).
     */
    @Test fun orInjectionPayloadIsNeutralizedIntoOneLiteral() {
        assertEquals(
            "'x\\' OR tag = \\'plain'",
            ZvecFilters.escapeFilterValue("x' OR tag = 'plain"),
        )
        // The result is exactly one literal: exactly two unescaped quotes (the
        // bookends), and no `OR` outside a string context.
        val out = ZvecFilters.escapeFilterValue("x' OR tag = 'plain")
        assertEquals("exactly the two bookend quotes are unescaped",
            2, countUnescapedSingleQuotes(out))
    }

    /** Other SQL-ish metacharacters are left untouched — they're inert inside a literal. */
    @Test fun sqlMetacharactersAreInertInsideLiteral() {
        // ; -- /* */ OR 1=1 contain no ' or \, so the body passes through verbatim.
        assertEquals("'; -- /* */ OR 1=1'",
            ZvecFilters.escapeFilterValue("; -- /* */ OR 1=1"))
    }

    /**
     * The empty string is a safe EMPTY literal — `''` — never omitted. Omitting it
     * would leave a dangling `=` (e.g. `tag = `), a syntax error; the whole point is
     * the caller interpolates a complete literal unconditionally.
     */
    @Test fun emptyStringIsSafeEmptyLiteral() {
        assertEquals("''", ZvecFilters.escapeFilterValue(""))
    }

    // ---- helpers ---------------------------------------------------------

    /** Count single quotes that are NOT the second char of a `\` escape pair. */
    private fun countUnescapedSingleQuotes(s: String): Int {
        var count = 0
        var i = 0
        while (i < s.length) {
            if (s[i] == '\\' && i + 1 < s.length) {
                i += 2 // skip an escaped pair (\x)
            } else {
                if (s[i] == '\'') count++
                i += 1
            }
        }
        return count
    }
}
