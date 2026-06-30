package io.github.tzhvh.scryernext.zvec

/**
 * A single-vector / pure-FTS query request — pure data, no handle (Q7).
 *
 * [vector] and [fts] are individually optional and together select the query
 * modality:
 *  - [vector] set, [fts] null → **pure-vector** similarity query.
 *  - [fts] set, [vector] null → **pure-FTS** (full-text) query.
 *  - both set → not supported by this entry point; use [ZvecCollection.hybridSearch]
 *    (issue 07) to fuse vector + FTS legs via a reranker.
 *  - both null → invalid; the SDK throws [ZvecErrorCode.INVALID_ARGUMENT].
 *
 * All C query handles (`vector_query_t`, `fts_t`) are **internal** to the SDK —
 * built, configured, run, walked, and freed inside one block of
 * [ZvecCollection.query]. The caller never manages a query handle; no `use {}`
 * discipline is required at the call site (the one place in the SDK where a
 * handle is never visible, even transiently — Q7).
 *
 * @param field the vector/FTS-indexed field to query against.
 * @param vector dense FP32 query vector; `null` + [fts] set selects pure-FTS.
 * @param fts FTS query string; `null` + [vector] set selects pure-vector. Passed
 *   to the engine as the FTS payload's **match string** (natural-language
 *   recall), not the boolean `query_string`.
 * @param filter optional SQL filter-expression subset. **⚠ Injection:** [filter]
 *   is raw SQL-ish text; if any part is user input, escape it first via
 *   `ZvecFilters.escapeFilterValue` (issue 08) — never interpolate a raw user
 *   value. Until 08 lands, only pass static, developer-controlled filters.
 * @param topK number of results to return (default 10).
 */
data class QueryRequest(
    val field: String,
    val vector: List<Float>? = null,
    val fts: String? = null,
    val filter: String? = null,
    val topK: Int = 10,
)

// ---- Hybrid search (issue 07) ---------------------------------------------
// The multi-query fusion surface. Each leg is a [SubQuery] (one vector OR one
// FTS payload, pure data, no handle); [ZvecCollection.hybridSearch] builds the C
// `multi_query_t` + each leg's `sub_query_t` internally, fuses via a [Reranker],
// and walks the result array through 05's `collect_docs` — the caller never sees
// a handle (Q7, the second handle-never-visible surface after 06's single query).
//
// The sub-query→multi-query add is NOT adopt-on-success: the C
// `zvec_multi_query_add_sub_query` COPIES the sub-query (`c_api.h:2144`,
// "sub_query to add (copied, caller retains ownership)"). Both reference
// bindings confirm this — the Rust oracle passes `&SubQuery` and the SubQuery
// owns its own Drop (`multi_query.rs:44`/`:121`); the Go oracle's test asserts
// `sub.handle` stays valid after AddSubQuery (`multi_query_test.go:122`). So the
// JNI SubQueryGuard ALWAYS frees the sub-query (the FtsGuard pattern from 06),
// never marks-it-closed-on-success. The issue's "adopt-on-success" note was a
// misread of the C ownership; this correction is recorded there in ## Comments.

/**
 * One leg of a hybrid search — pure data, no handle (Q7). A [SubQuery] targets a
 * single field and carries exactly one payload: a dense FP32 [vector] for a
 * similarity leg, or an [fts] match string for a full-text leg.
 *
 *  - [vector] set, [fts] null → **vector** similarity leg (queries a vector field).
 *  - [fts] set, [vector] null → **FTS** leg (queries an FTS-indexed text field).
 *  - both null → invalid; [ZvecCollection.hybridSearch] rejects it with
 *    [ZvecErrorCode.INVALID_ARGUMENT] before touching native.
 *  - both set → invalid for a single leg (a sub-query carries one payload in the
 *    C API); use TWO [SubQuery]s and let the reranker fuse them.
 *
 * [numCandidates] is the per-leg candidate count BEFORE fusion (the roadmap's
 * Phase-4 per-modality topK tunable) — `null` keeps the engine default. [topK]
 * (on [ZvecCollection.hybridSearch]) is the post-fusion count.
 *
 * @param field the vector or FTS-indexed field this leg queries.
 * @param vector dense FP32 query vector; `null` + [fts] set selects an FTS leg.
 *   Passed to `zvec_sub_query_set_query_vector`.
 * @param fts FTS match string; `null` + [vector] set selects a vector leg.
 *   Attached as an `fts_t` payload carrying the **match string** (natural-language
 *   recall), via `zvec_sub_query_set_fts` (`c_api.h:2377`). The fts handle is
 *   COPIED in, so it is always freed (FtsGuard).
 * @param numCandidates per-leg candidate count before fusion; `null` = engine
 *   default. Maps to `zvec_sub_query_set_num_candidates` (`c_api.h:2256`).
 */
data class SubQuery(
    val field: String,
    val vector: List<Float>? = null,
    val fts: String? = null,
    val numCandidates: Int? = null,
)

/**
 * The fusion strategy for a hybrid search. The engine does the math (RRF or
 * weighted score combination); this is the pure-data selector the SDK translates
 * to the matching `zvec_multi_query_set_rerank_*` setter. A custom-callback
 * reranker is a roadmap "note for later" — additive as a new sealed arm, not in
 * Phase 1.
 *
 * @see RrfReranker
 * @see WeightedReranker
 */
sealed interface Reranker

/**
 * Reciprocal Rank Fusion (the default reranker). Each leg contributes
 * `1 / (rankConstant + rank)` per candidate; the engine sums across legs and
 * returns the highest-fused-score first. Robust to incomparable score scales
 * (cosine distance vs. BM25), which is why it's the default for vector+FTS
 * fusion. [rankConstant] = 60 is the C default (`c_api.h:2109`).
 *
 * @see <a href="https://plg.uwaterloo.ca/~gvcormac/cormacksigir09-rrf.pdf">RRF</a>
 */
data class RrfReranker(
    val rankConstant: Int = 60,
) : Reranker

/**
 * Per-leg weighted score combination. Each leg's score is multiplied by its
 * weight, summed across legs, and the engine returns the highest combined score
 * first. The weights are **field-keyed** here for caller clarity (matching the
 * MCP's surface); the SDK resolves them to the **positional** `double*` array the
 * C API expects (`zvec_multi_query_set_rerank_weighted`, `c_api.h:2122`),
 * aligned to the [SubQuery] order. A leg whose field is absent from [weights]
 * defaults to weight `0.0` (the engine zeroes its contribution), so the map
 * should name every leg's field.
 *
 * **⚠ Score-scale caveat:** unlike RRF, weighted fusion mixes RAW scores of
 * incomparable scales (cosine distance ∈ [0,2] vs. BM25 ∈ [0,∞)). Cranking a
 * leg's weight shifts its top result up (the directional check in
 * [ZvecHybridSearchTest]), but the absolute weights are NOT calibrated —
 * calibration is Phase 4 (hybrid tuning). RRF is the safer default.
 */
data class WeightedReranker(
    val weights: Map<String, Float>,
) : Reranker
