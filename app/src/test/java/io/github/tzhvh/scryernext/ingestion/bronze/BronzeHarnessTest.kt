/* -*- Mode: Java; c-basic-offset: 4; tab-width: 4; indent-tabs-mode: nil; -*-
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.tzhvh.scryernext.ingestion.bronze

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * JVM tests for the bronze-harness logic (issue 02 B2) — the instrument must be
 * trusted before its numbers decide anything. Covers the MiniJson codec round-trip,
 * the deriver's arm stratification + containment labels on a synthetic corpus, the
 * scorer's recall math, and (when `/tmp/corpus_dump.json` is present — the harvested
 * real corpus) regenerates `/tmp/bronze_queries.json`; the generation test skips on
 * CI where no harvest exists.
 */
class BronzeHarnessTest {

    // ---- MiniJson ----

    @Test fun miniJsonRoundTripsFlatStringRows() {
        val rows = listOf(
            mapOf("pk" to "abc123", "content" to "line1\nline2 \"quoted\" back\\slash\ttab"),
            mapOf("pk" to "é中文", "content" to "", "k2" to "plain"),
        )
        val text = MiniJson.write(rows)
        assertEquals(rows, MiniJson.read(text))
    }

    @Test fun miniJsonParsesTheShapeCorpusDumpWrites() {
        // CorpusDumpDeviceTest hand-builds exactly this shape; the parser must accept it.
        val text = """[{"a":"x","b":"y"},{"a":"z","b":""}]"""
        val rows = MiniJson.read(text)
        assertEquals(2, rows.size)
        assertEquals("z", rows[1]["a"])
        assertEquals("", rows[1]["b"])
    }

    // ---- BronzeQueryDeriver ----

    private fun syntheticCorpus(): List<BronzeQueryDeriver.CorpusDoc> = listOf(
        BronzeQueryDeriver.CorpusDoc(
            "d1",
            "wifi password is hunter2 for the home network\nInvoiceNumber 4711 due 2026-09-30\n" +
                "screenshot of the receipt totals",
        ),
        BronzeQueryDeriver.CorpusDoc(
            "d2",
            "settings screen showing wifi options and bluetooth toggles\nInvoiceNumber 4712\n" +
                "second receipt with totals and tax",
        ),
        BronzeQueryDeriver.CorpusDoc(
            "d3",
            "meeting notes: discuss the wifi router replacement\nScreenshotGo_RepositoryManager refactor\n" +
                "third receipt line",
        ),
        BronzeQueryDeriver.CorpusDoc(
            "d4",
            "random unrelated note about gardening schedules\nInvoiceNumber 4713 total due\n" +
                "wifi extender setup guide",
        ),
    )

    @Test fun deriverSplitsArmsByTokenShape() {
        val queries = BronzeQueryDeriver.derive(syntheticCorpus(), perArm = 25)
        val arms = queries.groupBy { it.arm }
        assertTrue("ngram arm exists", arms.containsKey("ngram"))
        assertTrue("general arm exists", arms.containsKey("general"))
        // Part-number-like tokens are queried whole in the ngram arm.
        val ngram = arms.getValue("ngram")
        assertTrue("'4711' is part-number-like", ngram.any { it.query == "4711" })
        // Long tokens are queried as strictly-interior FRAGMENTS (partial-token queries —
        // what a word tokenizer structurally cannot recall).
        assertTrue(
            "'invoicenumber' yields a fragment query",
            ngram.any { q -> q.query.length < "invoicenumber".length && "invoicenumber".contains(q.query) },
        )
        // Common 5-9 letter lowercase words in ≥3 docs land in the general arm.
        assertTrue("'receipt' is a general word here", arms.getValue("general").any { it.query == "receipt" })
        // Short tokens (<5) never appear in the general arm; long plain words never as whole ngram queries.
        assertTrue(arms.getValue("general").none { it.query.length < 5 })
        assertTrue(ngram.none { it.query == "settings" || it.query == "passwords" })
        // Every arm entry carries containment labels.
        queries.forEach { q -> assertTrue("labels for ${q.query}", q.expectedPks.isNotEmpty()) }
    }

    @Test fun deriverLabelsAreCaseInsensitiveContainment() {
        val queries = BronzeQueryDeriver.derive(syntheticCorpus(), perArm = 100)
        val invoiceFragment = queries.first { q ->
            q.arm == "ngram" && q.query != "invoicenumber" && "invoicenumber".contains(q.query)
        }
        // Every doc containing "InvoiceNumber" contains its fragment (case-insensitive).
        assertEquals(listOf("d1", "d2", "d4"), invoiceFragment.expectedPks)
    }

    @Test fun deriverIsDeterministic() {
        val a = BronzeQueryDeriver.derive(syntheticCorpus())
        val b = BronzeQueryDeriver.derive(syntheticCorpus())
        assertEquals(a, b)
    }

    // ---- BronzeScorer ----

    @Test fun scorerComputesRecallAtKPerArm() {
        val queries = listOf(
            BronzeQueryDeriver.BronzeQuery("ngram-x", "ngram", "x", listOf("d1", "d2")),
            BronzeQueryDeriver.BronzeQuery("general-y", "general", "y", listOf("d3")),
        )
        val ranked = mapOf(
            // ngram-x: d1 in top10 (recall@10 = 0.5); d2 arrives at rank 30 (recall@50 = 1.0).
            "ngram-x" to listOf("d9", "d1") + (2..29).map { "filler-$it" } + listOf("d2"),
            // general-y: expected doc missing entirely → 0.0 at every K.
            "general-y" to listOf("d8", "d7"),
        )
        val metrics = BronzeScorer.score(queries, ranked, latencyMsByQueryId = mapOf("ngram-x" to 5L, "general-y" to 9L))
        assertEquals(0.5, metrics.getValue("ngram").recallAt10, 1e-9)
        assertEquals(1.0, metrics.getValue("ngram").recallAt50, 1e-9)
        assertEquals(0.0, metrics.getValue("general").recallAt200, 1e-9)
        assertEquals(5L, metrics.getValue("ngram").medianLatencyMs)
    }

    // ---- real-corpus generation (skips without the harvest) ----

    @Test fun generateBronzeQueriesFromHarvestedCorpus() {
        val corpus = File("/tmp/corpus_dump.json")
        assumeTrue("no harvested corpus on this machine — skipping generation", corpus.exists())
        val docs = MiniJson.read(corpus.readText()).map {
            BronzeQueryDeriver.CorpusDoc(pk = it.getValue("pk"), content = it.getValue("content"))
        }
        val queries = BronzeQueryDeriver.derive(docs, perArm = 25)
        val rows = queries.map {
            mapOf("id" to it.id, "arm" to it.arm, "query" to it.query, "expected" to it.expectedPks.joinToString(","))
        }
        File("/tmp/bronze_queries.json").writeText(MiniJson.write(rows))
        val byArm = queries.groupBy { it.arm }
        println("BRONZE: ${queries.size} queries — ngram=${byArm["ngram"]?.size}, general=${byArm["general"]?.size}")
        assertTrue("ngram arm populated", (byArm["ngram"]?.size ?: 0) > 0)
        assertTrue("general arm populated", (byArm["general"]?.size ?: 0) > 0)
    }

    /**
     * Scores real device runs when their files are present on this machine. This is the
     * B3 decision read-out: prints per-arm Recall@K + median latency for the baseline and
     * fused_ngram configs. Skips on machines without the artifacts (CI) — the record lives
     * in issue 02, not in CI gates.
     */
    @Test fun scoreHarvestedResults() {
        val queriesFile = File("/tmp/bronze_queries.json")
        assumeTrue("no bronze artifacts on this machine — skipping scoring", queriesFile.exists())

        val queries = MiniJson.read(queriesFile.readText()).map {
            BronzeQueryDeriver.BronzeQuery(
                id = it.getValue("id"),
                arm = it.getValue("arm"),
                query = it.getValue("query"),
                expectedPks = it.getValue("expected").split(",").filter { s -> s.isNotEmpty() },
            )
        }
        fun parse(f: File): Pair<Map<String, List<String>>, Map<String, Long>> {
            val rows = MiniJson.read(f.readText())
            val ranked = rows.associate { it.getValue("id") to it.getValue("ranked").split(",").filter { s -> s.isNotEmpty() } }
            val latency = rows.associate { it.getValue("id") to it.getValue("latency_ms").toLong() }
            return ranked to latency
        }

        fun fmt(arm: String, m: BronzeScorer.ArmMetrics) =
            "[$arm] Recall@10=%.3f @50=%.3f @200=%.3f median=%dms n=%d".format(m.recallAt10, m.recallAt50, m.recallAt200, m.medianLatencyMs, m.queries)

        var scored = 0
        listOf("baseline", "fused_ngram", "stemmed_only", "fused_stemmed", "fused_stemmed_w30", "fused_stemmed_w10").forEach { config ->
            val f = File("/tmp/bronze_results_$config.json")
            if (!f.exists()) return@forEach
            val (ranked, latency) = parse(f)
            val metrics = BronzeScorer.score(queries, ranked, latency)
            println("=== BRONZE $config ===")
            metrics.forEach { (arm, m) -> println(fmt(arm, m)) }
            scored++
        }
        assertTrue("no result files scored", scored > 0)
    }
}
