# 03 — `ScreenshotRepository.isKnown` seam (load-bearing `suspend` contract)

- **Status:** ready-for-human (implemented)
- **Phase:** 0 — Pure foundations (no runtime path touched)
- **PRD:** [`../PRD.md`](../PRD.md) | **Source:** [`docs/INGESTION_WORK_SEQUENCING.md`](../../../docs/INGESTION_WORK_SEQUENCING.md) §Phase 0 (tasks 0.3, 0.4)
- **ADR authority:** [ADR 0004 §3](../../../docs/adr/0004-ingestion-engine-and-trigger-architecture-v2.md)
- **Effort:** ~0.5 day | **Risk:** Low — one interface method + its sole implementor; no call site yet.

## What to build

A **`suspend fun isKnown(candidate: Candidate): Boolean`** to the `ScreenshotRepository` interface and
implement it in `ScreenshotDatabaseRepository`. Nothing calls it yet — the engine (Phase 1) is its first
consumer. The app's behaviour is unchanged.

This is the **single most load-bearing forward-looking contract decision in the whole Phase 0 plan**
(sequencing doc, "The one forward-looking contract decision that matters now"). The `suspend` keyword is the
part that must not change later:

- Under Room today, the body is a synchronous locator/URI lookup.
- Under zvec (roadmap Phase 2) with a null `identity`, the repository must stream `byteHandle` to compute the
  content-hash — an I/O op that can throw or be cancelled. A non-`suspend` signature forecloses that and
  forces an interface break at the zvec transition. A `suspend` signature keeps the transition a repository-
  implementation swap behind an unchanged interface.

The interface is already fully `suspend`/`Flow` (modernization issue 26 landed that), so `isKnown` slots in
beside `getContentText`, `updateScreenshotContent`, etc. with no style clash.

### Signature (decision-dense)

```kotlin
// ScreenshotRepository.kt — additive method
/**
 * Has this candidate's content already been ingested? (ADR 0004 §3.)
 *
 * Identity resolution lives here, on the repository — the engine never resolves identity itself.
 * - If [Candidate.identity] is non-null (a producer pre-computed it, e.g. the SAF I/O-pool pre-hashed),
 *   reuse it directly — no re-derivation, no double-I/O.
 * - If [Candidate.identity] is null, the repository falls back to its own identity model: locator/URI lookup
 *   under Room; content_hash (computed from [Candidate.byteHandle] if needed) under zvec.
 *
 * `suspend` because the zvec-era content-hash computation streams bytes and can throw / be cancelled; a plain
 * `fun: Boolean` would foreclose that. This signature is the contract that survives the Room→zvec transition.
 *
 * TODO(Phase 1): "known" must mean `processed = true`, not merely "a row exists" — otherwise permanent-content
 * failures (corrupt/illegible, written with empty text + processed=true per ADR 0004 §7.2) re-poison the
 * backlog via the discovery worker. Until the `processed` flag lands, this returns true iff an indexed record
 * exists for the candidate's identity/locator — the same semantics as today's `getContentText == null` check.
 */
suspend fun isKnown(candidate: Candidate): Boolean
```

```kotlin
// ScreenshotDatabaseRepository.kt — Room-era implementation
override suspend fun isKnown(candidate: Candidate): Boolean {
    return withContext(Dispatchers.IO) {
        when {
            // A producer handed us a pre-computed identity. Under Room the locator *is* the identity (uri is
            // the unique index), so treat a non-null identity as the locator to look up.
            candidate.identity != null -> candidate.identity in dbKeysByLocator()
            // No pre-computed identity: fall back to the locator (URI) lookup.
            candidate.locator != null -> candidate.locator in dbKeysByLocator()
            // Neither identity nor locator: conservatively unknown (the engine will attempt it).
            else -> false
        }
    }
}

// Helper — the set of indexed locators (URIs). Mirrors the dedup the old OcrTextHelper loop did inline via
// `getContentText(uri) == null`. Kept here so isKnown is testable with a fake DB; see the acceptance test.
```

> The exact helper shape (a cached `Set<String>` vs a per-call DAO query) is an implementation detail for the
> agent to decide, but it must honour: under Room, the locator (content URI) is the unique index
> (`index_screenshot_uri`, see `ScreenshotDatabaseRepository.MIGRATION_2_3`), so "is this candidate known" is
> a URI-membership check. Under zvec this helper is replaced by a content-hash lookup — that swap must not
> change `isKnown`'s signature.

## Acceptance criteria

- [x] `ScreenshotRepository` interface gains `suspend fun isKnown(candidate: Candidate): Boolean` with a
      doc-comment matching the contract above (identity-on-repository, optional pre-computed identity,
      `suspend` rationale, Phase-1 `processed` TODO).
- [x] `ScreenshotDatabaseRepository` implements `isKnown`: honours `candidate.identity` when non-null, else
      falls back to the locator/URI lookup, else returns `false`.
- [x] A unit test exercises the contract via a **fake `ScreenshotRepository`** (not Room — Room can't be
      JVM-tested without Robolectric, which isn't in the project). The fake asserts: `isKnown` returns `true`
      for a candidate whose identity/locator is in the fake's known set, `false` otherwise; and `isKnown` is
      `suspend` (callable from a coroutine test). This pins the interface contract for Phase 1's engine.
- [x] `./gradlew :app:assembleGoDebug` green.
- [x] `./gradlew :app:testGoDebugUnitTest` green.
- [x] No call site invokes `isKnown` yet — it is additive. The old OCR loop (`OcrTextHelper`,
      `ForegroundScanner`, `BackgroundScanner`) is byte-identical.

## Blocked by

- **`01`** (Candidate type contracts) — `isKnown`'s parameter is `Candidate`, which `01` introduces.

## Verified context

- `ScreenshotRepository` is already fully `suspend`/`Flow` (modernization issue 26 — done). `isKnown` slots in
  with no migration; this issue is purely additive. See `app/src/main/java/io/github/tzhvh/scryernext/repository/ScreenshotRepository.kt`.
- `ScreenshotDatabaseRepository` is the **sole** implementor of the interface. Adding the method to the
  interface forces the implementor to gain it in the same change — that's why 0.3 and 0.4 are one issue, not
  two (the build breaks otherwise).
- Room identity today: `screenshot` table PK is `id` (UUID), with a **unique index on `uri`**
  (`index_screenshot_uri`, created in `MIGRATION_2_3`). The URI is therefore the de-facto identity under
  Room — exactly what the locator fallback should check against.
- The old loop's dedup was inlined as `getContentText(uri) == null` (`OcrTextHelper.kt`, per
  `OCR_CURRENT_STATE.md` §"The flat loop"). `isKnown` is the named, testable extraction of that check.
- Unit tests are plain JVM; no Robolectric. So the Room body of `isKnown` cannot be directly unit-tested here
  — the test pins the *interface contract* via a fake. (A repository integration test would need
  `androidx.test:core` / Robolectric, neither present; deferred, like issue 26's note.)

## Notes for the agent

- **`suspend` is mandatory.** Do not be tempted to a plain `fun: Boolean` because the Room body happens to be
  synchronous — the signature is the contract that survives zvec. The sequencing doc is explicit that getting
  this wrong "requires an interface break" at the zvec transition.
- **Honour `candidate.identity` first**, locator second. ADR 0004 §3: if a producer pre-computed an identity,
  reuse it directly — no re-derivation, no double-I/O. Under Room, identity and locator happen to be the same
  value (the URI), but the *branching order* matters: it documents the contract for the SAF I/O-pool
  (roadmap Phase 3b), which pre-hashes and will pass a non-null `identity`.
- **Do not implement content-hashing.** Identity under Room is the URI lookup; content-hash is a zvec-era
  concern and implementing it now is throwaway (ADR 0004 §1, sequencing doc "What this work explicitly does
  NOT build"). The `byteHandle` is *referenced in the contract* (it's what zvec streams) but the Room body
  must not read it.
- The Phase-1 `processed` TODO is important: without it, `isKnown`'s "known" = "a row exists", which under
  the §7.2 failure taxonomy lets permanent-content failures (written empty + processed) re-poison the backlog.
  Flag it now so Phase 1 doesn't miss it.
- **Scope:** this issue adds `isKnown` to the interface + sole implementor + a contract test. It does **not**
  wire `isKnown` into any engine or trigger (Phase 1/2), and does **not** add a `processed` column (Phase 1).

## Comments

_— Created from Phase 0 tasks 0.3 (add `isKnown` to interface) + 0.4 (implement in
`ScreenshotDatabaseRepository`). Bundled because adding an abstract method to the interface forces the sole
implementor to gain it in the same change — splitting would leave the build red between the two PRs. The
`processed`-flag coupling to ADR 0004 §7.2 is called out so Phase 1 doesn't re-discover the backlog-re-poison
bug._

_— **Implemented (2026-06-25).** Implemented `suspend fun isKnown` on the interface, `ScreenshotDatabaseRepository`, and `ScreenshotInMemoryRepository`. Verified that all tests compile and pass. Added the `IsKnownSeamTest` unit test to verify interface and repository contract behavior._
