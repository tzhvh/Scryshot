package io.github.tzhvh.scryernext.zvec

/**
 * Injection-safe helpers for zvec filter expressions.
 *
 * The `filter` string on [QueryRequest.filter] / [ZvecCollection.hybridSearch]'s
 * `filter` / [ZvecCollection.deleteByFilter] is a SQL-like filter expression passed
 * as raw `const char*` (`zvec_vector_query_set_filter` / `zvec_multi_query_set_filter` /
 * `zvec_collection_delete_by_filter`, `c_api.h:1696 / 2182 / 3280`). The C API has
 * **no parameterized filter** — every value is interpolated into the string. When any
 * part of that value is user input (a tag name, a collection id, anything a user can
 * edit), interpolating it raw is a real filter-injection vector. This object ships the
 * one escape helper every filter caller inherits, so escaping is the default rather
 * than something each call site reinvents (or forgets).
 *
 * ### The escape rule (confirmed against the v0.5.1 parser source + live engine)
 *
 * zvec's filter language is tokenized by the ANTLR `SQLLexer` (the same lexer as the
 * full SQL path — `ZVecSQLParser::parse_filter`, `zvec_sql_parser.cc:501`). Its
 * single-quoted string-literal rule is:
 *
 * ```
 * SQUOTA_STRING: '\'' (~('\'' | '\\') | '\\'. )* '\'';
 * ```
 *
 * i.e. a single-quoted literal whose body is any char that is NOT `'` or `\`, **or**
 * a backslash-escape pair (`\` followed by any char). So the metacharacters that can
 * **break out** of a string literal are exactly two:
 *  - **`'`** (the closing quote) — escaped as **`\'`**;
 *  - **`\`** (the escape introducer) — escaped as **`\\`**.
 *
 * This is a **backslash-escape** grammar — NOT the SQL-standard quote-doubling the
 * issue's conservative fallback proposed. The live engine confirms it directly:
 *  - `tag = 'O\'Brien'` matches the stored `O'Brien` (correct);
 *  - `tag = 'O''Brien'` (SQL doubling) is a **syntax error** (`extraneous input
 *    ''Brien''`) — quote-doubling does NOT work and must not be what we ship.
 *
 * Doubling internal quotes would be actively wrong: it would fail to neutralize a
 * quote, and for a value ending in a quote it would emit a closing quote followed by
 * a stray opening one. Backslash-escaping both `\` and `'` is the verified-correct
 * rule, and it is the *complete* set — no other character can terminate a string
 * literal, so no other escaping is needed (or safe to add speculatively).
 *
 * **⚠ This only makes value interpolation safe.** It does not make a whole filter
 * safe: the column name, the operator, and the boolean glue (`AND`/`OR`) must still
 * be developer-controlled. The threat model is "a user can set the *value* that goes
 * inside the literal," not "a user can author arbitrary filter text."
 *
 * **⚠ Filter *semantics* (null-field pass-through, etc.) are Phase 5 (R4).** This
 * helper owns only the escaping safety rail; it does not model what a filter matches.
 *
 * @see escapeFilterValue
 */
object ZvecFilters {

    /**
     * Quote [value] as a single zvec string literal, escaping the two characters that
     * can break out of one. Returns the **complete literal**, including the wrapping
     * single quotes — interpolate it directly into a filter:
     *
     * ```kotlin
     * val filter = "tag = ${ZvecFilters.escapeFilterValue(userTag)}"
     * ```
     *
     * Every `\` becomes `\\` and every `'` becomes `\'`, per the `SQUOTA_STRING`
     * backslash-escape rule. No other character is touched (none can terminate the
     * literal). The result is a single closed string literal for any input, including:
     *  - a plain value → `'plain'`;
     *  - `O'Brien` → `'O\'Brien'`;
     *  - a backslash → `'a\\b'`;
     *  - an injection payload like `x' OR tag = 'plain` → `'x\' OR tag = \'plain'`
     *    (the `OR` is neutralized *inside* the literal — it matches nothing, exactly
     *    as the un-escaped `x` value would have);
     *  - the empty string → `''` (a safe empty literal, never omitted — omitting it
     *    would leave a dangling `=`, a syntax error).
     *
     * @param value the raw, untrusted value to interpolate into a filter.
     * @return the value wrapped as a closed single-quoted zvec string literal.
     */
    fun escapeFilterValue(value: String): String {
        val sb = StringBuilder(value.length + 2 + 4)
        sb.append('\'')
        for (c in value) {
            when (c) {
                // The two characters that can break out of a SQUOTA_STRING, escaped
                // per the backslash-escape rule. Backslash first matters: a literal
                // '\' is emitted as '\\' before any later quote is considered.
                '\\' -> sb.append("\\\\")
                '\'' -> sb.append("\\'")
                else -> sb.append(c)
            }
        }
        sb.append('\'')
        return sb.toString()
    }
}
