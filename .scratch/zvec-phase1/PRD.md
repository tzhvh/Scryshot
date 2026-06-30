# PRD — zvec Phase 1: Kotlin SDK

- **Source of truth:** [`docs/ZVEC_PHASE1.md`](../../docs/ZVEC_PHASE1.md) — every
  decision here is reached by a structured grilling (2026-06-29) grounded in the pinned
  `v0.5.1` source (`c_api.h`), the Rust binding (API oracle), the Dart binding
  (Android-compatibility oracle), and the shipped Phase-0 module. The phase doc is
  **authoritative** for implementation; it supersedes the Phase-1 sketch in
  [`docs/ZVEC_ROADMAP.md`](../../docs/ZVEC_ROADMAP.md).
- **ADR authority:** [0005](../../docs/adr/0005-zvec-handle-capability-interfaces.md)
  (OwnedHandle/BorrowedHandle), [0006](../../docs/adr/0006-zvec-doc-value-type-and-typed-zvecvalue.md)
  (ZvecDoc value type / typed ZvecValue), [0007](../../docs/adr/0007-zvec-sdk-suspend-no-internal-dispatch.md)
  (suspend, no internal dispatcher hop) — all **Accepted**.
- **Builds on:** [`docs/ZVEC_PHASE0.md`](../../docs/ZVEC_PHASE0.md). Phase 1 sits on top of
  the shipped `:zvec-android` module (the JNI macro family, the RAII guards, the prebuilt-`.so`
  build path, the version-pinning discipline). This PRD only covers what Phase 1 *adds*;
  it does not re-litigate Phase-0 decisions.
- **Tracker convention:** [`docs/agents/issue-tracker.md`](../../docs/agents/issue-tracker.md) —
  issues are `.scratch/zvec-phase1/issues/<NN>-<slug>.md`, numbered from `01`.
- **Status:** in-progress; **Issue 06 (Single-vector Query) completed**. Prerequisites verified present
  against the tree (see "Prerequisites" below). Execution order is the issue index order;
  dependencies are linear where the data path demands and fan out at the query tier.

---

## Problem Statement

The app can't move off Room into vector storage — and can't ship semantic search — because
there is **no Kotlin surface over zvec**. The shipped `:zvec-android` module (Phase 0) is a
four-function probe: `ZvecNative` exposes `nativeCreate`, `nativeInsert`, `nativeQuery`,
`nativeClose`, plus a round-trip test. Everything an app consumer would do — building a
schema, opening a collection, projecting fields, running a hybrid query, escaping a filter,
reading per-doc write results, configuring native thread pools and a log sink — either
doesn't exist or is buried in C handles the caller would have to manage.

As a developer building `ZvecScreenshotRepository` (the Phase-2 Room replacement), I face a
choice: either write raw `ZvecNative` calls and JNI handle management throughout the app
module (leak-prone, uncancellable-native-call-obscuring, type-erased `Map<String, Any?>`
read paths), or build a real SDK layer first. The roadmap's Phase-1 sketch proposed the
latter but left the surface loosely specified; the grilling against the pinned `v0.5.1`
source surfaced four concrete divergences from that sketch and eleven load-bearing design
decisions (Q1–Q11) that must land together for the SDK to be safe to build against.

The blocking consequence of not doing this: Phase 2 (Room→zvec storage migration) cannot
start, Phase 4 (hybrid search tuning) carries unresolved research risk (R1 score semantics,
R3 FTS+vector fusion), and Phase 5 (filters) ships without an injection-safe filter helper.

## Solution

Build the **full roadmap Phase-1 surface** as a pure-JVM Kotlin SDK in the `:zvec-android`
module, sitting between the app module and the C library. The SDK owns: the typed handle
model (`OwnedHandle`/`BorrowedHandle` capability interfaces), the value-type data model
(`ZvecDoc` + sealed `ZvecValue`), the query surface (single-vector `query` + `hybridSearch`,
all C query handles internal), typed schema construction, bulk-write-per-doc-results, fetch
with projection, stats, process-wide config/init, and a file log sink.

Three principles, applied uniformly:

1. **Compile-time safety where the type system can carry it.** Every C-API raw `int`
   discriminator or `void*`+size pair becomes a typed envelope — `sealed interface`,
   `enum class`, or per-type builder method. The JNI dispatch is a `when`, not runtime
   reflection on `Any?`.
2. **The caller never manages a handle on the data path.** Handles (`jlong`-wrapped C
   pointers) exist only for long-lived, multi-step objects (Collection, build-time schema,
   build-time query, init-time config). On schema construction / query / fetch / write the
   caller passes pure data classes; the SDK builds and frees C handles internally with the
   RAII guards Phase 0 already ships.
3. **Don't pull the ladder up.** Every decision is recorded with what's *additive later*
   (non-breaking) vs. what's a *source-breaking change later*. The SDK grows without
   churning its callers — we have no visibility into upstream zvec's roadmap.

Phase 1 turns the four-function probe into an SDK the app can build against **without ever
touching native code**. It does *not* touch the app module — that's Phase 2. It does *not*
add embeddings — that's Phase 3. It does *not* tune hybrid search — that's Phase 4. It
delivers the engine-level surface those phases wire.

## User Stories

1. As the Phase-2 repository author, I want a typed `ZvecException` whose `code` is a
   `ZvecErrorCode` enum, so that I can write exhaustive `when (e.code)` handling instead of
   unchecked integer switches.
2. As the Phase-2 repository author, I want a separate `detail: String?` on `ZvecException`,
   so that I can read zvec's raw error message without string-parsing the formatted
   `RuntimeException.message`.
3. As the app developer, I want to call `Zvec.init(ZvecConfig.androidDefaults(filesDir, debug))`
   once from `Application.onCreate`, so that the native library is initialized with a real
   config (memory cap, native thread pools, log sink) before any collection is touched.
4. As the app developer, I want `Zvec.init` to be idempotent and once-per-process, so that
   a second call (or a test that forgets it) doesn't corrupt library state.
5. As a focused instrumentation-test author, I want a lazy `ensureInitialized()` fallback
   that initializes zvec with defaults on first SDK use, so that focused tests can skip the
   `Application` dance.
6. As the app developer, I want native zvec logs written to `filesDir/logs/zvec`, so that
   after a native crash I can `adb pull` the postmortem log from the app sandbox.
7. As the Phase-2 repository author, I want `CollectionSchema`/`FieldSchema`/`IndexParams`
   as pure data classes, so that I construct the fixed app schema as a compile-time value,
   not a live handle builder.
8. As the Phase-2 repository author, I want schema validation to run inside `createAndOpen`
   *before any disk write*, so that a schema bug fails fast with a `ZvecException` reason
   instead of leaving a partial collection directory on disk.
9. As the Phase-2 repository author, I want `ZvecCollection.open(path)` to reopen an existing
   collection, so that every app launch except the first reopens rather than recreates.
10. As the Phase-2 repository author, I want `ZvecCollection` to be `Closeable` and an
    application-scoped handle, so that there's exactly one long-lived handle and I know when
    to close it.
11. As the Phase-2 repository author, I want to write a doc via a typed builder DSL
    (`string`, `int64`, `vectorF32`, …), so that no wrong-typed value can sneak past the
    compiler into the type-erased C `add_field_by_value`.
12. As the Phase-2 repository author, I want a `ZvecValue` sealed hierarchy on the read path,
    so that `doc.fields["size"]` is `ScalarInt(123L)` with exhaustive pattern-matching, not
    `Any?` requiring `as? Long`.
13. As the Phase-2 repository author, I want `insert`/`upsert`/`delete` (single-doc) to throw
    `ZvecException` on failure, so that the one-failure-in-one-doc case is a normal exception,
    not ceremony around a one-element result wrapper.
14. As the Phase-2 migration author, I want `upsertAll` / `insertAll` / `updateAll` /
    `deleteAll` to return a per-doc `WriteResult`, so that a failed doc in a 500-file batch
    reports its index + reason, not a single aggregate throw.
15. As the Phase-2 migration-progress UI author, I want `WriteResult.successCount`, so that
    the "Analyzing 45 of 842…" bar can read incremental progress.
16. As the Phase-2 repository author, I want `fetch(pks, outputFields, includeVector)`, so
    that gallery-listing queries load only 3–4 scalar fields and never accidentally pull
    ~5 KB FP32 vectors.
17. As the Phase-2 repository author, I want `includeVector = false` as the load-bearing
    default, so that a list query cannot OOM by deserializing full vectors.
18. As a search-quality engineer, I want `query(QueryRequest)` to return a finite ranked
    `List<ZvecDoc>`, so that I consume a terminal result set rather than an open-ended stream
    that doesn't exist.
19. As a search-quality engineer, I want the score-semantics convention (higher = nearer or
    lower = nearer under COSINE) pinned by a test, so that I know how to interpret `ZvecDoc.score`.
20. As a search-quality engineer, I want `hybridSearch(List<SubQuery>, Reranker)` with RRF and
    Weighted rerankers, so that I can fuse vector and FTS legs.
21. As a search-quality engineer, I want the FTS-sub-query + vector-sub-query fusion path
    proven on the pinned `v0.5.1`, so that Phase 4 carries no fusion research risk.
22. As the Phase-5 filter author, I want `ZvecFilters.escapeFilterValue(value)`, so that a
    user-editable collection name interpolated into a filter cannot be an injection vector.
23. As the Phase-2 repository author, I want `collection.stats()` returning `docCount` and
    per-index completeness, so that I can report migration progress and index build state.
24. As the app developer, I want to call `collection.optimize()`, so that Phase-2's
    post-migration index build can be triggered.
25. As the app developer, I want `createIndex`/`dropIndex`, so that runtime index DDL is
    available without touching JNI (shared guards with `optimize`).
26. As the app developer, I want every SDK method `suspend` with **no internal dispatcher
    hop**, so that the uncancellable-native-call reality is honest and the SDK adds zero
    threads contending with the ingestion engine's bounded dispatcher.
27. As the app developer, I want `BorrowedHandle` to have no `close()` method, so that a
    use-after-free-via-borrow-close is a compile error, not a runtime crash.
28. As the app developer, I want the Phase-0 round-trip test kept green throughout, so that
    the SDK growth doesn't regress the shipped probe.
29. As a release engineer, I want the ProGuard rule for `ZvecException`'s internal JNI
    constructor kept, so that release builds don't silently break with `NullPointerException`
    from native code.
30. As a future maintainer, I want every decision documented with additive-vs-breaking
    later, so that the SDK can grow (new vector type, new reranker, logcat sink, scan-ratio
    knobs) without source-breaking churn to existing callers.

## Implementation Decisions

The eleven grilling decisions (Q1–Q11), each grounded in the pinned `v0.5.1` source and
the reference bindings. Full rationale lives in [`docs/ZVEC_PHASE1.md`](../../docs/ZVEC_PHASE1.md);
this PRD records the load-bearing shape and the divergences from the roadmap sketch. The
domain vocabulary below is the project's [`CONTEXT.md`](../../CONTEXT.md) "zvec SDK
vocabulary (Phase 1)" section.

### Modules / interfaces touched

- **`:zvec-android` Kotlin SDK** (`io.github.tzhvh.scryernext.zvec` package, extends Phase 0's):
  the public surface in the doc's "API surface sketch" — `Zvec`, `ZvecConfig`, `ZvecLogConfig`,
  `LogLevel`, `ZvecCollection` (+ companion `createAndOpen`/`open`), `ZvecDoc`, `ZvecValue`,
  `ZvecDocBuilder`, `CollectionSchema`/`FieldSchema`/`FieldType`/`IndexParams`/`MetricType`/
  `CollectionOptions`, `QueryRequest`/`SubQuery`/`Reranker`/`RrfReranker`/`WeightedReranker`,
  `WriteResult`/`DocWriteFailure`, `CollectionStats`/`IndexStat`, `ZvecErrorCode`/
  `ZvecException`, `ZvecFilters`. Plus the internal handle model: `ZvecHandle`/
  `OwnedHandle`/`BorrowedHandle`/`NativeOwnedHandle`/`NativeBorrowedHandle`.
- **`:zvec-android` JNI bridge** (`src/main/cpp/zvec_jni_*.cc`): grows from Phase 0's 4
  functions to the full surface. The macro family (`ZVEC_CHECK_JNI_VOID`/`_JLONG`/`_JINT`/`_PTR`),
  the RAII guards (`SchemaGuard`, `FieldSchemaGuard`, `DocGuard`, `OptionsGuard`), and the
  marshalling helpers (`jstring_to_std`, `zvec_throw`, `zvec_code_label`) all carry forward.
- **`:zvec-android` consumer ProGuard** (`consumer-rules.pro`): the `ZvecException` rule is
  updated for the redesign (see "Error surface" below).
- **Unchanged:** `libzvec_c_api.so` (prebuilt, both ABIs), the `ZVEC_VERSION` pin, the
  `checkAarPackaging` Gradle task, `scripts/build_libzvec.sh`. **Not touched in Phase 1:**
  the `app` module (Phase 2).

### The four roadmap divergences (each documented in the phase doc)

1. **Query returns `List<ZvecDoc>`, not `Flow<ZvecDoc>`.** zvec returns a finite ranked
   result set; `Flow` would model a stream that doesn't exist and force every caller into
   terminal collection.
2. **SDK is `suspend` but never hops threads internally** (ADR 0007). The caller owns the
   dispatcher. An internal `Dispatchers.IO` would mask uncancellable native calls and contend
   with the ingestion engine's bounded `flatMapMerge` dispatcher (CONTEXT.md's Binder-pool
   concern).
3. **`ZvecConfig` is a process-singleton set via `Zvec.init(config)` from `Application.onCreate`,
   not threaded through `createAndOpen`.** Config is process-global (memory limit, thread
   pools, log sink); the log sink needs `Context.getFilesDir()`, which `createAndOpen`
   doesn't (and shouldn't) have.
4. **`androidDefaults(filesDir: File, debug: Boolean)`** takes `filesDir`, because the file
   log sink needs the app sandbox to place logs.

### The eleven decisions (shape only — rationale in the phase doc)

- **Q1/Q2 Handle model (ADR 0005):** `OwnedHandle`/`BorrowedHandle` capability interfaces;
  `OwnedHandle` extends `Closeable`; `BorrowedHandle` deliberately has **no `close()`** —
  closing a borrow double-frees in the parent, so the dangerous direction is a compile error,
  not a runtime flag. No `java.lang.ref.Cleaner`, no `finalize()` (minSdk 29 can't use
  `Cleaner` cleanly; confirms Phase-0 Q6). Adopt-on-success (C pattern where `set_hnsw_params`
  etc. transfer the params handle) is handled at the JNI layer, internal to the SDK.
- **Q3/Q4 Data model (ADR 0006):** `ZvecDoc` is a pure immutable data class (GC-owned, no
  handle) on both read and write paths. Fields carry typed values via a `ZvecValue` sealed
  hierarchy (`ScalarBool`, `ScalarInt`, `ScalarFloat`, `Str`, `Bin`, `VecF32`, `VecF16`,
  `VecI8`, `ScalarArray`, `Null`) — not `Map<String, Any?>`. Vectors ride inside `fields`,
  not a separate map (no scalar/vector distinction exists in the C `Doc`). The write path
  uses a typed builder DSL (`string`, `int64`, `vectorF32`, …) whose per-type methods are
  the compile-time gate, dispatching to the one C type-erased `add_field_by_value`.
- **Q5 Async (ADR 0007):** every method `suspend`; SDK never hops internally; caller owns
  the dispatcher. `queryThreadCount`/`optimizeThreadCount` are zvec's *native* thread pools,
  independent of the JVM dispatcher — documented explicitly so no one conflates them.
- **Q6 Config/init:** `nativeInit(ZvecConfig?)` from `Application.onCreate`; surface =
  memory limit + native thread counts + `logConfig`. The three scan-ratio knobs are **deferred
  to Phase 5** (speculative, no measured caller). Process singleton — **no `shutdown()`**
  (calling it breaks later tests in-process; `Application.onTerminate` is unreliable). Lazy
  `ensureInitialized()` fallback for focused tests.
- **Q6b Logging:** file sink under `filesDir/logs/zvec` only, via the stable
  `zvec_config_log_create_file`. **No logcat bridge in Phase 1** — verified no stable C-API
  callback/writer hook exists; the stdout→logcat pipe shim is purely-additive `JNI_OnLoad`
  work for a later phase. The console sink is deliberately not exposed (Android discards
  stdout for non-debuggable apps).
- **Q7 Query surface:** two public entry points — `query(QueryRequest)` and
  `hybridSearch(List<SubQuery>, reranker)`. All C query handles (`vector_query_t`,
  `multi_query_t`, `sub_query_t`, `fts_t`) are **internal** — built, run, freed inside the
  SDK. `QueryParams` (per-leg HNSW `ef`/IVF `nprobe`) **deferred to Phase 4** as a non-
  breaking additive optional field. `group_by_vector_query` is Phase 7.
- **Q8 Schema construction:** `CollectionSchema`/`FieldSchema`/`IndexParams` are pure data
  classes; the SDK translates them to C handles internally inside `createAndOpen`.
  **Validation is implicit** — `createAndOpen` runs the C validator *before any disk write*
  and throws `ZvecException` with the reason. Runtime DDL scope: `createIndex`/`dropIndex`/
  `optimize` ship in Phase 1; column DDL (`addColumn`/`dropColumn`/`alterColumn`) **deferred
  to early Phase 3** (first caller is the Phase-3 image-embedding column).
- **Q9 Error surface:** `ZvecException.code` promoted from `Int` to a `ZvecErrorCode` enum
  (with `fromInt()` fallback `UNKNOWN`); separate `val detail: String?` carries the raw
  `zvec_get_last_error` message. The ProGuard rule must keep the internal `(Int, String?)`
  JNI constructor plus the `code` field — getting this wrong breaks release builds silently
  as `NullPointerException` from native code.
- **Q10 Write results:** bulk ops always return per-doc `WriteResult`; single-doc ops throw.
  No aggregate-only bulk caller exists; the C `_with_results` variants exist for exactly this.
- **Q11 Fetch/projection:** nullable-default projection parameters — `outputFields: List<String>? = null`
  (null = all fields), `includeVector: Boolean = false` (load-bearing: prevents OOM on list
  queries by not deserializing ~5 KB vectors).

### JNI marshalling blueprint (load-bearing internals)

- **New guards:** `VectorQueryGuard`, `MultiQueryGuard`, `SubQueryGuard`, `FtsGuard`,
  `IndexParamsGuard` (+ `HnswQueryParamsGuard` etc.), `WriteResultsGuard`, `ConfigDataGuard`,
  `LogConfigGuard`. Same RAII pattern as the existing guards.
- **Result-array reading (`collect_docs`):** query/fetch return `zvec_doc_t***` + count;
  the JNI layer walks the array, **copies** pk + score + requested `outputFields` (+ vectors
  if `includeVector`) into a Kotlin `ZvecDoc`, then `zvec_docs_free` frees the whole array
  *and* each doc. Because we copy out, no per-doc handle escapes (Q3). This is the key
  difference from the Rust oracle (which wraps each result doc as an owned handle).
- **Per-type field marshalling:** eager copy, no `GetPrimitiveArrayCritical` pinning (Phase-0
  Rule 1/2 — pinning safety is unverified for vectors). Fixed-size scalars use
  `zvec_doc_get_field_value_basic`; variable-size (strings/vectors/binary) use
  `zvec_doc_get_field_value_pointer` (borrowed pointer, copied out immediately before the
  doc is freed).

### Honest gaps (carried, not hidden)

- **R8 (mmap safety on minSdk 29):** `CollectionOptions.enableMmap = true` is the default
  (performance-correct), but its safety on `minSdk = 29` was **not** verified against the
  source or on device. Issue 02 includes an on-device create/insert/query cycle with
  `enableMmap = true`; if it misbehaves, the default flips to `false` (or becomes
  API-level-conditional in `androidDefaults`).
- **R7 (arm64 runtime):** Phase 0 discharged the round-trip on arm64; Phase 1's query path
  is the new arm64-executed code. Issue 10 runs one query on arm64 before Phase 2 ships.
- **Stale-`LOCK` recovery is Phase 2's responsibility, not the SDK's.** Issue 02's `open()`
  performs no LOCK recovery; Phase 2's `ZvecScreenshotRepository` catches the open failure,
  inspects `ZvecException.detail` for the LOCK signal, deletes the file, retries once.

## Testing Decisions

**What makes a good test here:** test external SDK behaviour through the real
`libzvec_c_api.so`, never the JNI layer's internals. The constraint is the same one that
forced the `OcrStage` seam in ADR 0004 — **the native `.so` cannot run on the JVM**, so
there is no Robolectric, no mocking the native layer. Two seams only, both pre-existing:

1. **Instrumentation tests on an x86_64 emulator** — `:zvec-android:connectedDebugAndroidTest`,
   **one test class per SDK surface**. Each uses a **unique collection path**
   (`cacheDir/zvec_test_${nanoTime}`) because zvec is a process-wide singleton (config set
   in test A persists for test B — `@TestParameter` won't help; this is a documented
   constraint). This is the highest possible seam: every surface exercised through the real
   native library. Test classes map 1:1 to issues:
   `ZvecConfigTest` (01), `ZvecSchemaTest` + `ZvecReopenTest` (02), `ZvecDocTest` (03),
   `ZvecWriteResultsTest` (04), `ZvecProjectionTest` (05), `ZvecQueryTest` (06),
   `ZvecHybridSearchTest` (07), `ZvecFilterEscapeTest`'s round-trip half (08).
2. **One JVM unit-test seam** — `:zvec-android:testDebugUnitTest` — for
   `ZvecFilters.escapeFilterValue`, the only pure-JVM logic (string escaping, no JNI).
   Its round-trip half (does an escaped filter query correctly?) still needs the emulator.

**Regression guard:** Phase 0's `ZvecRoundtripTest` is kept green throughout — every issue
must not regress the shipped probe. **Cross-cutting safety test:** `ZvecHandleSafetyTest`
(close-guard throws after `close()`, idempotent `close()`, borrowed-no-`close` compile check)
rides along issue 01 once `OwnedHandle`/`BorrowedHandle` exist.

**Prior art:** Phase 0's `ZvecRoundtripTest` (version probe + create/insert/close slice) is
the template — unique collection path, real `.so`, throws-on-failure assertions. The
ingestion PRD's "pure helper extracted for JVM test" pattern (e.g. `bannerMode`,
`shouldNotify`) is the precedent for the `escapeFilterValue` JVM seam.

**Definition of done** (per `docs/agents/triage-labels.md`): the issue's instrumentation
tests pass on the emulator; `./gradlew :zvec-android:connectedDebugAndroidTest` green
(`:zvec-android:testDebugUnitTest` for issue 08's JVM half); `:zvec-android:bundleDebugAar`
+ the `checkAarPackaging` task green (packaging integrity). Release-build ProGuard smoke
(issues that touch `ZvecException`'s JNI constructor) is flagged in the relevant issue but
runs against a release variant, not debug instrumentation.

**Research queries discharged by this PRD's tests:** [R1] score semantics per metric
(`ZvecQueryTest`, issue 06 — pins whether higher = nearer under COSINE); [R3] MultiQuery
fusion across FTS + vector sub-queries (`ZvecHybridSearchTest`, issue 07 — proves the v0.5.1
fusion path, de-risks Phase 4 entirely).

## Out of Scope

- **The app module** — `ZvecScreenshotRepository`, `ZvecWriteSink`, the metadata cache, the
  `isKnown(candidate, bytes)` content-hash identity re-grounding. All Phase 2.
- **Embeddings / semantic search** — Phase 3.
- **Hybrid search *tuning*** (per-leg `QueryParams`, weighted-reranker weight calibration) —
  Phase 4. Phase 1 delivers the engine-level surface Phase 4 wires.
- **Filter push-down semantics / scan-ratio knobs** — Phase 5. Phase 1 ships the
  `escapeFilterValue` safety helper; null-field filter *behavior* is a Phase-5 engine question.
- **Logcat bridge** — the stdout→logcat pipe shim is purely-additive `JNI_OnLoad` work for a
  later phase if live debugging proves necessary.
- **Column DDL** (`addColumn`/`dropColumn`/`alterColumn`) — early Phase 3 (first caller is the
  Phase-3 image-embedding column).
- **`group_by_vector_query`** — Phase 7.
- **Vamana/DiskANN params** — exists in C (`ZVEC_INDEX_TYPE_VAMANA`); deferred until on-disk
  indexing is proven on Android.
- **Stale-`LOCK` recovery** — Phase 2's repository-layer concern, not SDK surface.
- **Custom-callback reranker** — roadmap "note for later"; additive sealed arm.
- **`CollectionSchema.validate(): Result<Unit>` as a public method** — additive later; Phase 1
  validates implicitly inside `createAndOpen`.

## Further Notes

### Prerequisites (verified present before publishing)

- `:zvec-android` module shipped: `ZVEC_VERSION` = v0.5.1; prebuilt `libzvec_c_api.so` for
  both ABIs; JNI macro family + RAII guards + marshalling helpers in `zvec_jni_marshalling.h`;
  four Phase-0 native functions; `ZvecNative`/`ZvecException`; `ZvecRoundtripTest`;
  `checkAarPackaging` task; `consumer-rules.pro`; `scripts/build_libzvec.sh`.
- Pinned `c_api.h` (v0.5.1): every Phase-1 C function present, and the doc's specific line
  citations verified accurate (`:3180/3208/3236/3264` = `*_with_results`; `:1696/2182` =
  `set_filter`; `:2693/2933` = `validate`; config-data setters at `:600–730`).
- Reference bindings present: `docs/references/third_party/zvec-rust` (API oracle),
  `zvec-dart` (Android oracle).
- `app/` `minSdk = 29` confirmed — grounds the Cleaner-unavailability and mmap-safety reasoning.

### Sequencing (order of operations)

Linear where the data path demands, fanning out at the query tier. **Critical path to
unblock Phase 2:** `01 → 02 → 03 → {04, 05}`. **Critical path to discharge Phase 4:** `06 → 07`.
Issue 08 unblocks Phase 5 by shipping the safety helper. Each issue is a merge boundary —
it compiles, its tests pass, it merges on its own. Partial implementations are useful
(issue 03's single-doc writes work even before 04's bulk path lands).

### Sources of truth — do not edit

Per `docs/agents/triage-labels.md`: **do not edit** `docs/ZVEC_PHASE1.md`,
`docs/ZVEC_ROADMAP.md`, `docs/ZVEC_PHASE0.md`, the ADRs, or `CONTEXT.md`. They are sources
of truth / decision records. Surface conflicts in an issue's `## Comments` instead.

## Comments

_— PRD created from `docs/ZVEC_PHASE1.md` via the `to-prd` skill (no interview; synthesis of
the 2026-06-29 grilling). Ground truth verified against the tree: Phase-0 module and pinned
`c_api.h` confirmed present; the four ADRs (0005/0006/0007 + Phase-0's referenced Q6)
Accepted. Issue granularity and seam shape confirmed with the user: **10 issues 1:1 with the
doc's implementation steps**; **two seams** (instrumentation-per-surface on emulator + one
JVM unit seam for `escapeFilterValue`). All ten issues published as `ready-for-agent`._
