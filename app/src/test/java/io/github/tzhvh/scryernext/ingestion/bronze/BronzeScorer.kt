/* -*- Mode: Java; c-basic-offset: 4; tab-width: 4; indent-tabs-mode: nil; -*-
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.tzhvh.scryernext.ingestion.bronze

/**
 * Scores bronze-harness results (issue 02 B2). Input: the derived query set with its
 * containment labels, and per-query ranked pk lists from a device run. Output: per-arm
 * Recall@{10,50,200} (mean over queries of |expected ∩ topK| / |expected|) and median
 * query latency. The B3 decision rule reads straight off this: ngram ships iff the
 * fused config lifts ngram-arm Recall@10 by ≥10pp with ≤2pp regression on the general arm.
 */
object BronzeScorer {

    data class ArmMetrics(
        val queries: Int,
        val recallAt10: Double,
        val recallAt50: Double,
        val recallAt200: Double,
        val medianLatencyMs: Long,
    )

    fun score(
        queries: List<BronzeQueryDeriver.BronzeQuery>,
        rankedByQueryId: Map<String, List<String>>,
        latencyMsByQueryId: Map<String, Long> = emptyMap(),
    ): Map<String, ArmMetrics> {
        return queries.groupBy { it.arm }.mapValues { (_, armQueries) ->
            // Per-query recall mean at each K: |expected ∩ topK| / |expected|, averaged.
            fun recallMean(k: Int): Double {
                val vals = armQueries.map { q ->
                    val ranked = rankedByQueryId[q.id] ?: emptyList()
                    val expected = q.expectedPks.toSet()
                    if (expected.isEmpty()) 1.0
                    else ranked.take(k).count { it in expected }.toDouble() / expected.size
                }
                return vals.sum() / vals.size
            }

            val latencies = armQueries.mapNotNull { latencyMsByQueryId[it.id] }.sorted()
            val median = if (latencies.isEmpty()) 0L else latencies[latencies.size / 2]
            ArmMetrics(
                queries = armQueries.size,
                recallAt10 = recallMean(10),
                recallAt50 = recallMean(50),
                recallAt200 = recallMean(200),
                medianLatencyMs = median,
            )
        }
    }
}
