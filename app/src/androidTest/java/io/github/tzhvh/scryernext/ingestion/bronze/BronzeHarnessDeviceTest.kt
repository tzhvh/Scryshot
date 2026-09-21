/* -*- Mode: Java; c-basic-offset: 4; tab-width: 4; indent-tabs-mode: nil; -*-
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.tzhvh.scryernext.ingestion.bronze

import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.AndroidJUnit4
import io.github.tzhvh.scryernext.zvec.CollectionSchema
import io.github.tzhvh.scryernext.zvec.FieldSchema
import io.github.tzhvh.scryernext.zvec.FieldType
import io.github.tzhvh.scryernext.zvec.IndexParams
import io.github.tzhvh.scryernext.zvec.QueryRequest
import io.github.tzhvh.scryernext.zvec.RrfReranker
import io.github.tzhvh.scryernext.zvec.SubQuery
import io.github.tzhvh.scryernext.zvec.WeightedReranker
import io.github.tzhvh.scryernext.zvec.ZvecCollection
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The bronze-harness device runner (issue 02 B2/B3): ingests the harvested corpus into a
 * SCRATCH collection under `-e bronzeConfig`, runs the derived query arms against the real
 * engine, and writes ranked results + per-query latency for offline scoring.
 *
 * Configs (the B3 A/B):
 *  - `baseline` — the schema shipping today: `content` = standard + lowercase, single-leg
 *    FTS query (the store's search path shape).
 *  - `fused_ngram` — the B3 candidate: `content` as today PLUS `content_ngram` (ngram
 *    tokenizer), searched as a two-leg FTS MultiQuery fused by RRF. This is the config the
 *    decision rule arbitrates; index size is also reported (recorded, not thresholded).
 *
 * Gated: skips unless `bronzeCorpus`/`bronzeQueries`/`bronzeOut` are all supplied, so
 * normal suite runs never ingest the corpus. Invocation pattern:
 *
 * ```
 * adb push corpus_dump.json bronze_queries.json /sdcard/Android/data/<pkg>/files/
 * adb shell am instrument -w \
 *   -e class ...BronzeHarnessDeviceTest \
 *   -e bronzeCorpus /sdcard/.../files/corpus_dump.json \
 *   -e bronzeQueries /sdcard/.../files/bronze_queries.json \
 *   -e bronzeOut /sdcard/.../files/bronze_results_baseline.json \
 *   -e bronzeConfig baseline <pkg>.test/androidx.test.runner.AndroidJUnitRunner
 * ```
 */
@RunWith(AndroidJUnit4::class)
class BronzeHarnessDeviceTest {

    @Test fun runBronzeHarness() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val corpusPath = args.getString("bronzeCorpus")
        val queriesPath = args.getString("bronzeQueries")
        val outPath = args.getString("bronzeOut")
        val config = args.getString("bronzeConfig") ?: "baseline"
        assumeTrue("skipped: supply bronzeCorpus/bronzeQueries/bronzeOut", corpusPath != null && queriesPath != null && outPath != null)

        val corpus = MiniJson.read(File(corpusPath!!).readText())
        val queryRows = MiniJson.read(File(queriesPath!!).readText())
        assertTrue("empty corpus file", corpus.isNotEmpty())
        assertTrue("empty queries file", queryRows.isNotEmpty())

        val scratchDir = File(
            InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
            "bronze-$config-${System.nanoTime()}",
        )
        scratchDir.deleteRecursively()

        val schema = when (config) {
            "baseline" -> schema(stemmed = false, ngramField = false)
            "fused_ngram" -> schema(stemmed = false, ngramField = true)
            // B3's spec'd A/B base: filters + stemmer on content.
            "stemmed_only" -> schema(stemmed = true, ngramField = false)
            // B3's ship candidate: stemmed content PLUS the ngram leg, RRF-fused.
            "fused_stemmed" -> schema(stemmed = true, ngramField = true)
            // Remedy points: weighted fusion favoring the stemmed leg (ngram weight 0.3 / 0.1).
            "fused_stemmed_w30" -> schema(stemmed = true, ngramField = true)
            "fused_stemmed_w10" -> schema(stemmed = true, ngramField = true)
            else -> throw IllegalArgumentException("unknown bronzeConfig: $config")
        }
        val ngramWeight = when (config) {
            "fused_stemmed_w30" -> 0.3f
            "fused_stemmed_w10" -> 0.1f
            else -> null
        }
        val sizeLabel = when (config) {
            "baseline" -> "shipping today (standard+lowercase)"
            "fused_ngram" -> "unstemmed + content_ngram"
            "stemmed_only" -> "stemmed content only"
            "fused_stemmed" -> "stemmed content + content_ngram (RRF)"
            "fused_stemmed_w30" -> "stemmed + ngram, weighted 1.0/0.3"
            "fused_stemmed_w10" -> "stemmed + ngram, weighted 1.0/0.1"
            else -> config
        }

        val results = mutableListOf<Map<String, String>>()
        var indexBytes = 0L
        ZvecCollection.createAndOpen(scratchDir, schema).use { col ->
            corpus.forEach { row ->
                col.upsert {
                    pk = row.getValue("pk")
                    string("content_hash", row.getValue("pk"))
                    string("locator", row.getValue("locator"))
                    string("content", row.getValue("content"))
                    string("collection_id", row.getValue("collection_id"))
                    if (config.startsWith("fused")) string("content_ngram", row.getValue("content"))
                }
            }
            col.flush()
            indexBytes = scratchDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }

            queryRows.forEach { q ->
                val query = q.getValue("query")
                val start = System.nanoTime()
                val pks = if (config.startsWith("fused")) {
                    val reranker = if (ngramWeight != null) {
                        WeightedReranker(weights = mapOf("content" to 1.0f, "content_ngram" to ngramWeight))
                    } else {
                        RrfReranker()
                    }
                    col.hybridSearch(
                        queries = listOf(
                            SubQuery(field = "content", fts = query),
                            SubQuery(field = "content_ngram", fts = query),
                        ),
                        topK = 200,
                        reranker = reranker,
                    )
                } else {
                    col.query(QueryRequest(field = "content", fts = query, topK = 200))
                }
                val latencyMs = (System.nanoTime() - start) / 1_000_000
                results += mapOf(
                    "id" to q.getValue("id"),
                    "arm" to q.getValue("arm"),
                    "ranked" to pks.map { it.pk }.joinToString(","),
                    "latency_ms" to latencyMs.toString(),
                )
            }
        }
        scratchDir.deleteRecursively()

        File(outPath!!).writeText(MiniJson.write(results))
        val message = "BRONZE RUN[$config]: ${results.size} queries, index=$indexBytes bytes ($sizeLabel), out=$outPath"
        Log.i("BronzeHarness", message)
        println(message)
    }

    /**
     * The A/B schemas. `stemmed` is B3's spec'd base (ascii_folding + english stemmer on top of
     * lowercase); `ngramField` adds the second FTS field the fused config searches. The baseline
     * arm is today's shipping config for continuity with the pre-B3 record.
     */
    private fun schema(stemmed: Boolean, ngramField: Boolean): CollectionSchema = CollectionSchema(
        name = "screenshots",
        fields = buildList {
            add(FieldSchema("content_hash", FieldType.STRING))
            add(FieldSchema("locator", FieldType.STRING))
            add(
                FieldSchema(
                    "content",
                    FieldType.STRING,
                    indexParams = if (stemmed) IndexParams.FtsParams(
                        tokenizer = "standard",
                        filters = listOf("lowercase", "ascii_folding", "stemmer"),
                        extraParams = """{"stemmer_lang":"english"}""",
                    ) else IndexParams.FtsParams(tokenizer = "standard", filters = listOf("lowercase")),
                ),
            )
            if (ngramField) {
                add(
                    FieldSchema(
                        "content_ngram",
                        FieldType.STRING,
                        indexParams = IndexParams.FtsParams(tokenizer = "ngram"),
                    ),
                )
            }
            add(FieldSchema("collection_id", FieldType.STRING, indexParams = IndexParams.InvertParams()))
        },
    )
}
