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
