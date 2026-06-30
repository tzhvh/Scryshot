# 06 — Single-vector query (discharges R1: score semantics)

- **Status:** done
- **Phase:** 1 — Query tier
- **PRD:** [`../PRD.md`](../PRD.md) | **Source:** [`docs/ZVEC_PHASE1.md`](../../../docs/ZVEC_PHASE1.md)
  §The query surface, §JNI marshalling blueprint, §Verification plan (R1)
- **ADR authority:** [ADR 0007](../../../docs/adr/0007-zvec-sdk-suspend-no-internal-dispatch.md)
- **Effort:** ~1.5 days | **Risk:** Medium — the query handle lifecycle (build → configure →
  run → walk results → free) is the most multi-step JNI sequence in the SDK, and R1
  (score semantics) is a research query this issue discharges.
- **Prereqs:** 03 (writes the docs this queries), 05 (reuses `collect_docs`). **Blocks:** 07
  (hybrid reuses the query-handle pattern), Phase 4 (tuning). Discharges R1 for Phase 4.

## What to build

The single-vector + pure-FTS query surface. All C query handles (`vector_query_t`,
`sub_query_t`, `fts_t`) are **internal** to the SDK — built, configured, run, results walked,
and freed inside one internal block. The caller never manages a query handle.

### The query surface (Q7)

```kotlin
suspend fun query(
    request: QueryRequest,
    outputFields: List<String>? = null,
): List<ZvecDoc>

data class QueryRequest(
    val field: String,
    val vector: List<Float>? = null,   // dense vector (FP32); null + fts set = pure-FTS query
    val fts: String? = null,            // FTS query string; null + vector set = pure-vector query
    val filter: String? = null,
    val topK: Int = 10,
)
```

Returns `List<ZvecDoc>`, **not `Flow<ZvecDoc>`** — zvec returns a finite ranked result set,
not an open-ended stream (divergence #1 from the roadmap sketch).

### Why internal query handles

This is the one place in the SDK where the caller *never* sees a handle, even transiently. The
SDK builds the C query handle, configures it, runs it, walks the result array (reusing 05's
`collect_docs`), copies out `ZvecDoc` values, frees the result array and the query handle, and
returns. Zero leak surface by construction; no `use {}` discipline required at the call site.
The adopt-on-success ownership transitions (Q1/Q2) happen entirely inside this internal
build-and-free block.

### Why no public `QueryParams` layer

Per-leg HNSW `ef`/IVF `nprobe` are a power-user knob with no product caller until
search-quality tuning matters. The roadmap's Phase-4 tunables are `rankConstant`, weighted
reranker weights, and per-modality topK — *not* per-leg engine params. Exposing them now means
a stable API for unmeasured behavior. **`QueryParams` is deferred to Phase 4 (or later) as a
non-breaking additive** — `QueryRequest` grows an optional `queryParams: QueryParams? = null`.

### The internal handle lifecycle + guards

New guards: `VectorQueryGuard`, `FtsGuard` (and `SubQueryGuard`, used by 07). Same RAII
pattern as existing guards — freed on any exit path including a macro throw-return. The JNI
sequence:

1. Build `vector_query_t` (or an FTS query) from `QueryRequest`.
2. Configure: set the field, the vector (or fts string), `topK`, and the `filter` if present
   (`zvec_vector_query_set_filter`, `c_api.h:1696`).
3. `zvec_collection_query(collection, query, &results, &count)`.
4. Walk `results` via **05's `collect_docs`** → `List<ZvecDoc>`.
5. Free `results` + the query handle (guards).

### Filter-value escaping

The `filter` string is a SQL filter-expression subset passed as raw `const char*` — no built-in
parameterization. Collection *names* are user-editable, so interpolating them unsafely is a
real injection vector. **The escape helper itself is issue 08**; this issue's `query` KDoc must
cross-link `ZvecFilters.escapeFilterValue` so no caller interpolates a user value unsafely.

## Tests (`ZvecQueryTest`) — discharges R1

Instrumentation test under `:zvec-android:connectedDebugAndroidTest`, unique collection path.

- **Single-vector HNSW query:** insert N docs with known vectors, query with a near-vector,
  assert ordering (nearest first) and `score` presence on each result.
- **Pure-FTS query:** insert docs with known text, query with `fts`, assert recall of the
  matching docs.
- **`outputFields` projection** honoured on the query result (reuse 05's semantics).
- **[R1] Score semantics discharge (load-bearing):** this test prints `score` for near vs far
  docs under `COSINE` and asserts whether **higher = more similar or lower = more similar** —
  pinning the score-semantics convention the roadmap's [R1] flags. Record the finding in
  `## Comments` (it determines how Phase 4 interprets `ZvecDoc.score` and whether the SDK needs
  to normalize). The `QueryResult` is returned in engine order; the comment records the
  direction.

> R1 is the entire reason this test exists beyond a smoke check — do not merge this issue
> without the score-direction assertion and a `## Comments` entry stating the pinned
> convention.

## Acceptance criteria

- [x] `query(QueryRequest, outputFields)` present, `suspend`, returns `List<ZvecDoc>`.
- [x] `QueryRequest` data class present (field/vector/fts/filter/topK); `vector`+`fts` mutually
      define pure-vector vs pure-FTS.
- [x] Internal query handles (`VectorQueryGuard`/`FtsGuard`) built, run, freed; caller sees no
      handle.
- [x] Result walk reuses 05's `collect_docs`.
- [x] `filter` KDoc cross-links `ZvecFilters.escapeFilterValue`.
- [x] `ZvecQueryTest` green: HNSW ordering + score presence; FTS recall; projection.
- [x] **R1 discharged:** score direction under COSINE asserted + recorded in `## Comments`.
- [x] Phase 0's `ZvecRoundtripTest` still green.
- [x] `:zvec-android:bundleDebugAar` + `checkAarPackaging` green.

## Comments

### [R1 discharged] Score semantics under COSINE — pinned

Verified against a live zvec instance (local `zvec-studio`, HNSW/COSINE, 4-dim
FP32) before the JNI was written, then re-pinned on-device by
`ZvecQueryTest#r1ScoreSemanticsUnderCosineDistanceNotSimilarity`:

**`ZvecDoc.score` under COSINE is the cosine *distance* (`1 − cos θ`), range
[0, 2], and the engine returns results ASCENDING — nearest first. So *lower =
more similar*.** Landmark distances (query vector `[1,0,0,0]`):

| doc       | vector                  | score (= 1 − cos θ) |
|-----------|-------------------------|---------------------|
| `near`    | `[1,0,0,0]`             | `0.0`   (cos θ = 1)   |
| `mid`     | `[.7071,.7071,0,0]`     | `0.293` (cos θ ≈ .707)|
| `far`     | `[0,0,1,0]`             | `1.0`   (cos θ = 0)   |
| `opposite`| `[-1,0,0,0]`            | `2.0`   (cos θ = −1)  |

Consequences for Phase 4: `ZvecDoc.score` is a **distance, not a similarity**.
The SDK surfaces the engine's raw value untouched (no normalization, no flip) —
Phase 4 interprets it. The screenshots schema is COSINE, so callers comparing
scores sort ascending and "best" = smallest score. (Other metrics carry their
own direction — L2 is also lower=nearer, IP is higher=nearer — but Phase 1 only
pins the COSINE convention that R1 asked for.)

### Implementation notes

- **One C query handle serves both modalities.** There is no separate FTS query
  handle: a pure-FTS query builds a `vector_query_t`, sets `field_name` + `topk`
  (+ optional `output_fields`/`filter`), and attaches an `fts_t` payload via
  `zvec_vector_query_set_fts`. The payload is **copied** into the query
  (`c_api.h:1876`), so `FtsGuard` always owns and frees the `fts_t`. The FTS
  match text is set with `zvec_fts_set_match_string` (natural-language recall),
  not `set_query_string` (boolean expression).
- **`collect_docs` reused verbatim** from issue 05 — the query result array is
  wrapped in the same `DocsGuard` and walked by the same `collect_one_doc`; the
  engine's raw score lands at row slot `[1]`. The fetch/query read paths now
  share one Kotlin unpacker (`unpackRow(row, keepScore)`); fetch nulls score,
  query keeps it.
- **`query` rejects `vector == fts == null` and both-set** in Kotlin with
  `ZvecErrorCode.INVALID_ARGUMENT` before touching native (both-set should use
  `hybridSearch`, issue 07).
- `QueryParams` (per-leg HNSW `ef`/IVF `nprobe`) deferred to Phase 4 as planned
  — additive `QueryRequest.queryParams: QueryParams? = null`.

### Verification (all green)

- `:zvec-android:connectedDebugAndroidTest` → **41 tests, 0 failures, 0 errors**
  on the api31 device, including the 6 `ZvecQueryTest` cases and Phase-0's
  `ZvecRoundtripTest` regression guard.
- `:zvec-android:bundleDebugAar` + `checkAarPackaging` → both green.

---

_— Issue published from `docs/ZVEC_PHASE1.md` implementation step 6. **R1 discharged
above.** No `QueryParams` layer (deferred Phase 4, additive). Query handles are
the one place the caller never sees a handle, even transiently._
