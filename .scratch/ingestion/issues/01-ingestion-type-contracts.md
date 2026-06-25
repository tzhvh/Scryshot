# 01 — Ingestion type contracts — `Candidate` + `Progress`

- **Status:** ready-for-human
- **Phase:** 0 — Pure foundations (no runtime path touched)
- **PRD:** [`../PRD.md`](../PRD.md) | **Source:** [`docs/INGESTION_WORK_SEQUENCING.md`](../../../docs/INGESTION_WORK_SEQUENCING.md) §Phase 0 (tasks 0.1, 0.2)
- **ADR authority:** [ADR 0004 §2, §3, §7.2, §7.3](../../../docs/adr/0004-ingestion-engine-and-trigger-architecture-v2.md)
- **Effort:** ~0.5 day | **Risk:** Low — additive data classes; no call site touched.

## What to build

Land the two type contracts the rest of the ingestion engine builds on, in a new `ingestion/` package. These
are pure data — no behaviour, no runtime path, no DI wiring. The old OCR loop is untouched; nothing consumes
these types yet.

1. **`Candidate`** — the unit of work the engine processes. Carries a *locator* (volatile; nullable under
   some producers), a *byte-handle* (a stream provider — `suspend () -> InputStream`, **not** an already-open
   stream), and an **optional pre-computed identity** (`identity: String? = null`).

2. **`Progress`** — a sealed hierarchy modelling the engine's structured output:
   - `Indexing(current, total, failedCount, stageTimings?)` — the in-flight state.
   - `Completed(indexed, failed, total, stageTimings?)` — terminal success (failures included in completion).
   - `Error(throwable)` — terminal failure.
   - `Paused(...)` — presentation state (see ADR 0004 §5).

The `stageTimings` field is typed now (against the `StageTimings` concept from ADR 0004 §7.3) but may be
`null` until Phase 1 lands the per-stage `Stopwatch`. Declaring the field here keeps `Progress` stable across
Phase 1 — the engine's emission shape doesn't change when instrumentation arrives.

### Type shapes (decision-dense; derived from ADR 0004)

These encode ADR decisions more precisely than prose. Match them; do not "improve" them.

```kotlin
// ingestion/Candidate.kt
data class Candidate(
    val locator: String?,                          // volatile; nullable under some producers
    val byteHandle: suspend () -> InputStream,     // a provider, NOT a consumed stream
    val identity: String? = null                   // optional pre-computed; engine never resolves identity
)
```

```kotlin
// ingestion/Progress.kt
sealed interface Progress {
    data class Indexing(
        val current: Int,                          // (indexed + failed) so far in this run
        val total: Int,
        val failedCount: Int,
        val stageTimings: StageTimings? = null     // null until Phase 1 instruments stages
    ) : Progress

    data class Completed(
        val indexed: Int,
        val failed: Int,
        val total: Int,
        val stageTimings: StageTimings? = null
    ) : Progress

    data class Error(val throwable: Throwable) : Progress

    // Presentation state — pause/resume continuity. Cosmetic; no correctness impact (ADR 0004 §5).
    data class Paused(/* sessionStartTotal, doneCount as needed */) : Progress
}
```

`StageTimings` itself (per-stage rolling average — read/decode/OCR/write) is a **Phase 1** deliverable
(task 1.4). Here it is only *referenced* so `Progress` doesn't change shape later. Define a minimal
placeholder type the engine can fill in:

```kotlin
// ingestion/StageTimings.kt — placeholder, filled out in Phase 1 (issue for task 1.4)
// Rolling per-stage latency (ADR 0004 §7.3). Shape TBD in Phase 1; for Phase 0 it exists only so
// Progress.stageTimings has a concrete type and Phase 1 can extend it without a breaking change.
data class StageTimings(
    val readMs: Double = 0.0,
    val decodeMs: Double = 0.0,
    val ocrMs: Double = 0.0,
    val writeMs: Double = 0.0
)
```

## Acceptance criteria

- [x] New package `io.github.tzhvh.scryernext.ingestion` exists with `Candidate.kt`, `Progress.kt`, and a
      `StageTimings.kt` placeholder.
- [x] `Candidate` carries `locator: String?`, `byteHandle: suspend () -> InputStream`, `identity: String? = null`.
- [x] `Progress` is a sealed interface with `Indexing`, `Completed`, `Error`, `Paused` subtypes, each
      carrying the fields specified above; `stageTimings` is present and nullable.
- [x] A trivial unit test (`ingestion/TypeContractTest.kt`) constructs a `Candidate` and each `Progress`
      subtype and asserts the fields round-trip. (This is a compile-shape guard, not real logic.)
- [x] `./gradlew :app:assembleGoDebug` green.
- [x] `./gradlew :app:testGoDebugUnitTest` green.
- [x] No existing file is modified except the new files created here. The old OCR loop is byte-identical.

## Blocked by

None — can start immediately.

## Verified context

- No `ingestion/` package exists yet (`app/src/main/java/io/github/tzhvh/scryernext/`). This issue creates it.
- The repo is already on the stable coroutines package (`kotlinx.coroutines` 1.9.0); `suspend () -> InputStream`
  needs no extra dependency.
- Existing unit tests are plain JVM (`app/src/test/java/.../permission/`, `.../util/`). No Robolectric; this
  issue needs none — it's pure data + a construction test.
- Glossary (CONTEXT.md): **Candidate**, **Locator**, **Identity**, **Progress** are defined terms — the type
  names and doc-comments should use that vocabulary and avoid the retired "scan" word.

## Notes for the agent

- `Progress.Indexing.current` is `(indexed + failed) so far`, **not** just indexed. This is the
  `failedCount`-separate-from-`indexedCount` discipline from ADR 0004 §7.2 — a single failure must not park
  the progress bar forever. Document it in the field's doc-comment.
- Do **not** add a `Producer` interface here — that's issue `04` (the SAF fake declares it). Do **not** add
  `isKnown` here — that's issue `03`. Keep this issue to the three data files + the construction test.
- `StageTimings` is a placeholder. Resist filling in the rolling-average logic; Phase 1 owns that (task 1.4).
  The placeholder exists solely so `Progress` doesn't change shape when instrumentation lands.

## Comments

_— Created from Phase 0 tasks 0.1 (Candidate) + 0.2 (Progress). Bundled because both are ~15-line additive
data classes with no call sites; splitting into two PRs loses value. The `StageTimings` placeholder is a
prefactor so Phase 1 doesn't have to touch `Progress`._

### 2026-06-25 — Implemented (ready for human review/commit)

Built on branch `ingestion-phase0-work-items`. All three data files + the construction test landed exactly as
specified; shapes were matched verbatim, not "improved."

**Files added (4, all new — no existing file modified):**
- `app/src/main/java/io/github/tzhvh/scryernext/ingestion/Candidate.kt` — `locator`, `suspend () -> InputStream`
  `byteHandle`, `identity: String? = null`.
- `app/src/main/java/io/github/tzhvh/scryernext/ingestion/Progress.kt` — sealed interface with `Indexing` /
  `Completed` / `Error` / `Paused`; `Indexing.current` documented as `(indexed + failed) so far`.
- `app/src/main/java/io/github/tzhvh/scryernext/ingestion/StageTimings.kt` — Phase-0 placeholder only (no
  rolling-average logic; Phase 1 task 1.4 owns that).
- `app/src/test/java/io/github/tzhvh/scryernext/ingestion/TypeContractTest.kt` — 9 construction/round-trip
  tests (incl. exercising `byteHandle` as a provider and the optional `identity` nullability).

**Decisions made (all within spec):**
- `Progress.Paused` was the one open shape (`/* sessionStartTotal, doneCount as needed */` in the issue). Made
  it explicit as `data class Paused(sessionStartTotal: Int = 0, doneCount: Int = 0)` per ADR 0004 §5's
  "persist `(sessionStartTotal, doneCount)`" presentation contract. Defaults keep it trivially constructible.
- Test uses plain JUnit 4 (`org.junit.Assert.*`), matching the repo's existing test convention
  (`CollectionListHelperTest`, `PermissionFlowTest`) — the project has no kotest dependency.

**Verification (acceptance criteria):**
- `:app:testGoDebugUnitTest` green — `TypeContractTest`: 9 tests, 0 skipped, 0 failures, 0 errors.
- `:app:assembleGoDebug` green.
- `git status` confirms only the 4 new files; old OCR loop byte-identical (additive-only constraint honoured).

**⚠️ Conflict surfaced for human — do NOT resolve silently (PRD mandates surfacing, not editing, sources of
truth):** `CONTEXT.md` Glossary (Candidate, line 9) still states identity is *never* on the Candidate
("Identity is *never* on the Candidate"). ADR 0004 §3 — the authority — **refines** this to permit an
**optional pre-computed** `identity` (so the SAF I/O-pool can pre-hash). This issue and its `Candidate` follow
ADR 0004 correctly. The CONTEXT.md glossary is now stale relative to the decision of record and should be
updated to match ADR 0004 §3 (e.g. "identity is optionally carried; the engine never resolves it"). Left for a
human to decide whether to edit CONTEXT.md directly or note it elsewhere.
