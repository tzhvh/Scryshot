/* -*- Mode: Java; c-basic-offset: 4; tab-width: 4; indent-tabs-mode: nil; -*-
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.tzhvh.scryernext.ingestion.bronze

/**
 * Minimal JSON codec for the bronze-harness record files (issue 02 B2). The app has
 * no JSON dependency, and the harness controls every producer and consumer, so this
 * handles exactly one shape: a top-level array of flat objects with string values —
 * `[{"k":"v",...}, ...]`. Newlines/quotes/backslashes/control chars escaped on write;
 * the parser accepts exactly what [write] emits (plus arbitrary whitespace between
 * tokens). Values must not contain raw newlines after escaping — they never do.
 *
 * Deliberately NOT a general JSON parser:BronzeQueryDeriver/BronzeScorer/harness
 * are the only callers, and the corpus_dump.json producer (CorpusDumpDeviceTest)
 * escapes with the same rules.
 */
object MiniJson {

    fun write(rows: List<Map<String, String>>): String = buildString {
        append("[")
        rows.forEachIndexed { i, row ->
            if (i > 0) append(",")
            append("{")
            row.entries.forEachIndexed { j, (k, v) ->
                if (j > 0) append(",")
                append("\"${escape(k)}\":\"${escape(v)}\"")
            }
            append("}")
        }
        append("]")
    }

    fun read(text: String): List<Map<String, String>> {
        val p = Parser(text)
        p.skipWs()
        p.expect('[')
        val rows = mutableListOf<Map<String, String>>()
        p.skipWs()
        if (p.peek() == ']') return rows
        while (true) {
            p.skipWs()
            p.expect('{')
            val row = mutableMapOf<String, String>()
            p.skipWs()
            if (p.peek() == '}') {
                p.next()
            } else {
                while (true) {
                    p.skipWs()
                    val key = p.readString()
                    p.skipWs()
                    p.expect(':')
                    p.skipWs()
                    row[key] = p.readString()
                    p.skipWs()
                    when (p.next()) {
                        ',' -> continue
                        '}' -> break
                        else -> p.fail("expected , or }")
                    }
                }
            }
            rows += row
            p.skipWs()
            when (p.next()) {
                ',' -> continue
                ']' -> return rows
                else -> p.fail("expected , or ]")
            }
        }
    }

    private fun escape(s: String): String = buildString {
        for (c in s) when (c) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
        }
    }

    private class Parser(val s: String) {
        var i = 0

        fun skipWs() {
            while (i < s.length && s[i].isWhitespace()) i++
        }

        fun peek(): Char {
            if (i >= s.length) fail("eof")
            return s[i]
        }

        fun next(): Char {
            if (i >= s.length) fail("eof")
            return s[i++]
        }

        fun expect(c: Char) {
            if (next() != c) fail("expected '$c'")
        }

        fun readString(): String {
            expect('"')
            val b = StringBuilder()
            while (true) {
                when (val c = next()) {
                    '"' -> return b.toString()
                    '\\' -> when (val e = next()) {
                        '\\' -> b.append('\\')
                        '"' -> b.append('"')
                        'n' -> b.append('\n')
                        'r' -> b.append('\r')
                        't' -> b.append('\t')
                        'u' -> b.append(s.substring(i, i + 4).toInt(16).toChar()).also { i += 4 }
                        else -> fail("bad escape \\$e")
                    }
                    else -> b.append(c)
                }
            }
        }

        fun fail(msg: String): Nothing = throw IllegalArgumentException("MiniJson @$i: $msg")
    }
}
