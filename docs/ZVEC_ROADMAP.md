# ScreenshotGo — zvec Migration Roadmap & Feature Wishlist

This document outlines the plan to migrate ScreenshotGo's storage and search
infrastructure from Room/SQLite FTS4 to **zvec** — the in-process vector database
that lives in the same repository.

zvec is already cross-compilable for Android (`arm64-v8a` via
`scripts/build_android.sh`) and exposes a complete C API. What doesn't exist yet
is the JNI bridge and Kotlin SDK that connect it to the Android app.

---

## Why zvec?

ScreenshotGo's 2018 search is a single modality: **keyword match over OCR text**.

zvec adds three new retrieval modalities and lets you combine them:

| Modality | zvec mechanism | Unlocks in ScreenshotGo |
|---|---|---|
| **Dense vector (visual)** | HNSW / IVF index, COSINE metric | "Find screenshots that *look like this one*" — matches charts, code, UI layouts by image content |
| **Full-text (OCR text)** | BM25 FTS with tokenizers, phrase queries, boolean operators | Better relevance ranking than FTS4, exact phrase support, `AND`/`OR` query syntax |
| **Structured (metadata)** | Inverted indexes on scalar fields | Filter by collection, date range, file path — all in one query, no post-filtering |

The killer feature is **hybrid search**: query all three modalities at once and fuse
results via Reciprocal Rank Fusion (RRF), weighted scoring, or a custom callback.

---

## Prerequisites

The modernization effort ([MODERNIZATION.md](./MODERNIZATION.md)) must complete
first. Specifically:

- Gradle 8.10+, AGP 8.5+, Kotlin 2.1.x — needed to build and link native libraries
- compileSdk 35 — needed for scoped storage and foreground service type declarations
- ViewBinding migration — removes `kotlinx.android.synthetic` dependency

Without these, you can't add a native dependency or run on modern Android.

---

## Roadmap

### Phase 0 — Native Library Build Integration (1–2 days)

Package zvec as an Android AAR with its native `.so` so ScreenshotGo can link it.

> **Authoritative plan:** the detailed Phase 0 design — module layout, Gradle/CMake
> wiring, JNI architecture (error macros, handle convention, marshalling rules),
> the Level-2 verification slice, version pinning, and toolchain requirements —
> lives in **[ZVEC_PHASE0.md](./ZVEC_PHASE0.md)**. It was produced by a structured
> grilling session and grounds every decision in the pinned `v0.5.1` source.
> This section is a one-paragraph summary; refer to that doc for implementation.

**What to build:**

- A Gradle module **`:zvec-android`** inside ScreenshotGo (not in the zvec repo)
  that packages a thin JNI `libzvec_jni.so` plus a prebuilt `libzvec_c_api.so` into an
  AAR.
- The prebuilt `libzvec_c_api.so` is produced *outside Gradle* by a ported
  `scripts/build_libzvec.sh` (ported from the **dart binding's** artifact-producer
  script — not upstream's test-harness `build_android.sh`, which doesn't produce a
  `.so`; see [corrections](#corrections-to-the-roadmap)). Gradle compiles only the
  thin JNI layer and links it against the prebuilt via a CMake `IMPORTED` target.
- zvec source is referenced via a git submodule pinned to a release tag (`v0.5.1`).

**Output:** `zvec-android.aar` containing `libzvec_jni.so` + `libzvec_c_api.so` for
**arm64-v8a and x86_64** (32-bit ABIs dropped — `minSdk = 29` makes them
irrelevant).

**Phase 0 exit gate:** an instrumentation test round-trips `nativeGetVersion()` /
`nativeCreateAndOpen` / `nativeInsertString` / `nativeClose` on an x86_64 emulator,
and a Gradle packaging check asserts all four `.so`s are present for both ABIs.

**Dependency:** Modernization Tier 1 (Gradle/AGP/Kotlin toolchain).

#### Corrections to the roadmap

The original Phase 0 section and File Inventory contained three factual errors,
checked against the pinned `v0.5.1` source during grounding:

1. **The doc API is type-erased.** The original file list referenced
   `zvec_jni_doc.cc` (typed setters) and `zvec_jni_schema.cc` as separate files.
   The real C API is `zvec_doc_add_field_by_value(doc, name, data_type, void*,
   size)` — one type-erased value path. The Phase 0 slice collapses to a single
   `zvec_jni_collection.cc` (plus `onload` + a marshalling header). See
   [ZVEC_PHASE0.md](./ZVEC_PHASE0.md) § "The Level-2 slice."
2. **Upstream `scripts/build_android.sh` is a test harness, not an artifact
   producer.** It builds zvec + tests and runs them in an emulator; it never sets
   `BUILD_C_BINDINGS=ON` and emits no `libzvec_c_api.so`. The dart binding's script is
   the artifact producer and the basis for our `build_libzvec.sh`.
3. **File paths.** The original placed files under `zvec/src/binding/jni/` and
   `zvec/android/`. The actual layout is `zvec-android/src/main/cpp/` and
   `zvec-android/src/main/java/io/github/tzhvh/scryernext/zvec/` (inside
   ScreenshotGo, not inside the zvec submodule).

---

### Phase 1 — Kotlin SDK (2–3 days)

A Kotlin API that wraps the JNI calls so the app never touches native code directly.

**Design principles:**

- No native types leak into app code. The SDK translates zvec C types to Kotlin
  data classes (`ZvecDoc`, `ZvecCollection`, `CollectionSchema`, `QueryRequest`).
- Coroutine-friendly. All JNI calls are potentially blocking (HNSW search, FTS
  scoring), so the SDK exposes `suspend` functions that dispatch to `Dispatchers.IO`.
- Kotlin `Flow` for reactive query results, matching the app's existing `LiveData`
  patterns from Room.

> **Five C-API capabilities the sketch below must expose** (verified against
> `v0.5.1`, `c_api.h`):
>
> 1. **Resource bounding via `ConfigData`** — `zvec_initialize` accepts a
>    `zvec_config_data_t` with `set_memory_limit`, `set_query_thread_count`,
>    `set_optimize_thread_count` (`c_api.h:600,635,707`). On Android this is
>    non-negotiable: an unbounded background `optimize()` can saturate every core
>    on a Pixel 4a. The SDK's `createAndOpen` must thread a `ZvecConfig` through
>    to `zvec_initialize`, with conservative Android defaults (e.g. memory cap
>    ~512 MB, query threads 2, optimize threads 1).
> 2. **Output field selection on every query type** —
>    `zvec_vector_query_set_output_fields`,
>    `zvec_group_by_vector_query_set_output_fields`,
>    `zvec_multi_query_set_output_fields`, and `zvec_collection_fetch`'s
>    `output_fields` param (`c_api.h:1747,2033,2216`). The SDK should accept an
>    optional `outputFields` on `query`/`fetch`/`hybridSearch` so gallery listing
>    doesn't deserialize full docs (incl. vectors) it won't render.
> 3. **Per-doc write results** — `insert`/`update`/`upsert`/`delete` all have
>    `_with_results` variants returning a `zvec_write_result_t { code, message }`
>    per doc (`c_api.h:3150,3180,3208,3236,3264`), freed via
>    `zvec_write_results_free`. The bulk-ingestion API (Phase 3b) must use these
>    so a 500-file SAF batch reports *which* doc failed, not a single aggregate
>    throw.
> 4. **MMAP toggle on collection options** —
>    `zvec_collection_options_set_enable_mmap` (`c_api.h:2422`). mmap behavior is
>    API-level-dependent on Android; the SDK should expose it and the default
>    documented, not silently inherited.
> 5. **Schema validation before open** —
>    `zvec_collection_schema_validate` / `zvec_field_schema_validate`
>    (`c_api.h:2693,2933`) return error code + message. The Kotlin
>    `CollectionSchema` builder should validate before `createAndOpen`, surfacing
>    a `ZvecException` with the reason rather than a JNI crash.
> 6. **Logging config** — `zvec_config_log_create_console(level)` /
>    `zvec_config_log_create_file(...)` with rotation, attached via
>    `zvec_config_data_set_log_config` (`c_api.h:460,471,618`). Without this,
>    debugging Phase 4 hybrid-fusion bugs means staring at a crash tombstone
>    instead of reading zvec's internal logs.
>
>    **Android caveat (verified against `v0.5.1` source): the console sink is
>    *not* logcat.** The default `ConsoleLogger` (`ailego/logger/logger.cc`) writes
>    to `std::cout`/`std::cerr`, which Android discards for non-debuggable apps.
>    So mapping `ZvecLogConfig.logcat(level)` → `zvec_config_log_create_console`
>    would silently produce nothing. For logcat, the SDK must either (a) add a
>    stdout/stderr→`__android_log_write` bridge in `JNI_OnLoad`, or (b) register a
>    custom `Logger` subclass that calls `__android_log_write` directly. For
>    crash *postmortems* (the primary use case), the **file sink** is the more
>    robust default — it survives the process death that a tombstone follows.
>    `ZvecConfig.androidDefaults()` should wire logcat for debug builds (via the
>    bridge/custom logger, *not* the bare console sink) and a file sink for
>    release, not the console sink in either case.
> 7. **Stats / index progress** — `zvec_collection_get_stats` returns
>    `doc_count` + per-index `index_completeness` (float 0–1,
>    `c_api.h:2478,2503,3048`). Exposed as `stats()` so the app can show
>    "Optimizing index: 45%" during `optimize()` and confirm build progress after
>    `createIndex`.
> 8. **Three scan-ratio tunables** — `set_invert_to_forward_scan_ratio`,
>    `set_brute_force_by_keys_ratio`, `set_fts_brute_force_by_keys_ratio`
>    (`c_api.h:653,671,689`) control when the engine picks inverted-index lookup
>    vs brute-force scan for scalar filters. Phase 5's push-down filters hit
>    these paths; a Pixel 4a may want different defaults than a Pixel 8. Expose
>    as optional `ZvecConfig` overrides (defaults inherited from zvec).

**Kotlin SDK surface (sketch):**

```kotlin
// ZvecCollection.kt
class ZvecCollection(private val ptr: Long) : Closeable {

    suspend fun insert(doc: ZvecDoc): String
    suspend fun upsertAll(docs: List<ZvecDoc>): WriteResult   // per-doc status

    suspend fun query(
        request: QueryRequest,
        outputFields: List<String>? = null        // projection — avoid full-doc deserialization
    ): Flow<ZvecDoc>
    suspend fun hybridSearch(
        queries: List<SubQuery>,
        topK: Int = 20,
        reranker: Reranker = RrfReranker(),
        filter: String? = null,                   // SQL filter-expression subset (see Phase 5)
        outputFields: List<String>? = null
    ): Flow<ZvecDoc>

    suspend fun fetch(pks: List<String>, outputFields: List<String>? = null): List<ZvecDoc>
    suspend fun delete(pks: List<String>)
    suspend fun deleteByFilter(filter: String)

    // Runtime DDL — see Phase 2 migration strategy. Online, no drop-and-reindex.
    suspend fun addColumn(field: FieldSchema, defaultExpression: String? = null)
    suspend fun dropColumn(name: String)
    suspend fun alterColumn(name: String, newName: String? = null, newSchema: FieldSchema? = null)
    suspend fun createIndex(field: String, params: IndexParams)
    suspend fun optimize()

    // Stats — doc count + per-index completeness (0–1). Powers "Optimizing: 45%" UI.
    suspend fun stats(): CollectionStats

    fun flush()  // synchronous — called on app background
    fun close()

    companion object {
        suspend fun createAndOpen(
            path: File,
            schema: CollectionSchema,
            options: CollectionOptions = CollectionOptions(),  // incl. enableMmap
            config: ZvecConfig = ZvecConfig.androidDefaults()  // memory/thread caps
        ): ZvecCollection
    }
}

data class WriteResult(val successCount: Int, val failures: List<DocWriteFailure>)
data class DocWriteFailure(val index: Int, val code: ErrorCode, val message: String)

data class CollectionStats(val docCount: Long, val indexes: List<IndexStat>)
data class IndexStat(val name: String, val completeness: Float)   // 0–1

data class ZvecConfig(
    val memoryLimitBytes: Long?,
    val queryThreadCount: Int,
    val optimizeThreadCount: Int,
    val logConfig: ZvecLogConfig? = null,                        // console/file sink (c_api.h:460,471)
    // Optional scan-ratio overrides (defaults inherited from zvec; tune per-device if needed).
    val invertToForwardScanRatio: Float? = null,                 // c_api.h:653
    val bruteForceByKeysRatio: Float? = null,                    // c_api.h:671
    val ftsBruteForceByKeysRatio: Float? = null                  // c_api.h:689
) {
    companion object {
        fun androidDefaults(debug: Boolean) = ZvecConfig(
            memoryLimitBytes = 512L * 1024 * 1024,
            queryThreadCount = 2,
            optimizeThreadCount = 1,
            logConfig = if (debug) ZvecLogConfig.logcat(level = LogLevel.DEBUG) else null
        )
    }
}

sealed interface ZvecLogConfig {
    data class logcat(val level: LogLevel) : ZvecLogConfig       // zvec_config_log_create_console
    data class file(val level: LogLevel, val dir: File, val basename: String,
                    val sizeMb: UInt, val overdueDays: UInt) : ZvecLogConfig  // _create_file (rotation)
}

data class CollectionOptions(val enableMmap: Boolean = true)

// Data types
data class ZvecDoc(
    val pk: String,
    val score: Float? = null,      // semantics per metric — verify via research query [R1]
    val vectors: Map<String, List<Float>> = emptyMap(),
    val fields: Map<String, Any?> = emptyMap()
)

data class CollectionSchema(
    val name: String,
    val fields: List<FieldSchema>
) {
    fun validate(): Result<Unit>   // calls zvec_collection_schema_validate before open
}

data class FieldSchema(
    val name: String,
    val type: FieldType,       // STRING, INT64, FLOAT, VECTOR_FP32, etc.
    val dimension: Int? = null,
    val indexParams: IndexParams? = null
)

sealed interface IndexParams
data class HnswParams(val m: Int, val efConstruction: Int) : IndexParams
// Tokenizer ("standard" | "jieba" | "whitespace") + a filters pipeline.
// For OCR text recall, a lowercase/ASCII-folding filter can help; jieba is
// irrelevant for this app (English-only). c_api.h:1075.
data class FtsParams(
    val tokenizer: String = "standard",
    val filters: List<String> = emptyList(),
    val extraParams: String? = null
) : IndexParams
data class InvertParams : IndexParams

data class QueryRequest(
    val field: String,
    val vector: List<Float>? = null,     // dense vector
    val fts: String? = null,              // FTS query string
    val filter: String? = null,           // SQL filter-expression subset (see Phase 5)
    val topK: Int = 10
)

sealed interface Reranker
data class RrfReranker(val rankConstant: Int = 60) : Reranker
// zvec normalizes per-metric-type BEFORE applying weights (atan for L2/IP,
// linear for COSINE) — so weights compose across metrics with different score
// scales. The SDK must not pre-normalize; pass weights through to the engine.
data class WeightedReranker(val weights: Map<String, Float>) : Reranker
// Optional: a callback reranker (CallbackParams / std::function in C++) exists
// for fully custom fusion. Not in the Phase 1 surface; note for later.
```

**Dependency:** Phase 0 (native AAR). Can be developed in parallel with Phase 0
by mocking the native layer with an in-memory implementation.

---

### Phase 2 — Replace Room Storage (2–3 days)

Migrate ScreenshotGo's data layer from SQLite/Room to zvec. The goal is
**feature parity first** — no new search capabilities yet, just the same searches
running on zvec instead of FTS4.

**Schema mapping:**

| Room entity | zvec collection field |
|---|---|
| **content_hash** (NEW — SHA-256 of file bytes) | **doc primary key (STRING)** |
| `screenshot.absolute_path` / `screenshot.uri` | `locator` (STRING) — opaque, volatile, non-unique |
| `screenshot.display_name` | `display_name` (STRING) |
| `screenshot.size` | `size` (INT64) |
| `screenshot.last_modified` | `last_modified` (INT64) |
| `screenshot.collection_id` | `collection_id` (STRING) — inverted index |
| `screenshot_content.content_text` | `ocr_text` (STRING) — FTS index |

> **Identity decision (2026-06-23): content_hash is the PK, not the URI.** The current Room
> schema (issue 21) keys screenshots by `id` (UUID assigned at insert) with a unique index on
> `uri` (the MediaStore content URI). That identity is fragile even under MediaStore (URIs shift
> across remounts/media scans) and **outright impossible under SAF** (a SAF URI is a permission
> grant, not a file identity — it changes on every re-grant). zvec's PK is therefore the
> **SHA-256 of the file content**, computed once on ingest. The locator (`path`/`uri`) becomes a
> volatile, non-unique field — "last known place this content was found." Dedup is free: `upsert`
> on PK collapses identical content automatically. This decision is made *now* (cheap — it's a
> schema field) to avoid re-indexing everything when SAF lands in Phase 3b. Implementing hashing
> in Room today (outside zvec) is explicitly out of scope and throwaway; the hash column is native
> to the zvec schema from day one.

**Files to change:**

| File | Change |
|---|---|
| `ScreenshotRepository.kt` | Interface already suspend/Flow + opaque identity (issue 26); `ZvecScreenshotRepository` implements it |
| `ScreenshotDatabaseRepository.kt` | Either gut and replace, or keep as fallback during migration |
| `ScreenshotViewModel.kt` | Swap repository constructor injection |
| `ContentScanner.kt` / `ForegroundScanner.kt` | Write to zvec instead of (or in addition to) Room |
| `ScreenshotDao.kt` / `ScreenshotDatabase.kt` | Can be deleted once migration is confirmed |

> **Depends on issue 26.** The `ScreenshotRepository` interface must be suspend/Flow and
> source-agnostic before `ZvecScreenshotRepository` can drop in behind it. Issue 26 ships that
> seam; this phase swaps the implementation.

**Search behavior preserved (FTS4 → zvec FTS):**

| Old query | New query |
|---|---|
| `MATCH 'query*'` (prefix match) | `ocr_text MATCH 'query*'` (zvec FTS prefix) |
| `LIKE '%term%'` (fallback) | `ocr_text MATCH 'term'` (zvec handles this natively) |
| `collection_id = ?` | Inverted index filter |

**Data migration:**

- On first launch after update, iterate all Room rows and insert into zvec.
- Use `upsert` so re-runs are idempotent.
- Keep Room alive during transition so users can downgrade if needed.
- After N successful launches with zvec active, drop Room tables.

> **Three zvec fundamentals that shape Phase 2's migration strategy**
> (verified against `v0.5.1`):
>
> 1. **Runtime DDL exists — but migration *tracking* doesn't.** *(Correction:
>    an earlier draft of this note said "no in-place schema migration" and
>    prescribed drop-and-reindex. That's wrong.)* The C API exposes full online
>    DDL on a live collection: `zvec_collection_add_column`,
>    `zvec_collection_drop_column`, `zvec_collection_alter_column` (rename +
>    re-schema, `c_api.h:3114,3124,3138`). The field schema is deep-copied
>    internally (same ownership rule as `schema_add_field` — caller still
>    destroys its own copy). **This changes Phase 3's plan**: adding the
>    `image_embedding` column is an `add_column` call on the live collection,
>    *not* a drop-and-reindex. What zvec *lacks* is Room's versioned-migration
>    *tracking* (no `Migration` objects, no schema-version bookkeeping). So the
>    version-marker-file pattern (below) is still needed — but to decide *which*
>    DDL statements to replay on open, not to trigger a wipe. A robust strategy:
>    write a sibling `$dbPath.version`/`$dbPath.migrations` file recording the
>    applied DDL sequence; on open, diff against the expected sequence and replay
>    missing `add_column` / `alter_column` / `drop_column` calls. `alter_column`
>    (`c_api.h:3138`) handles rename + re-schema in one step — cleaner than
>    add-migrate-drop when a field name or type evolves. Reserve
>    drop-and-reindex for genuinely incompatible changes (e.g. PK type changes,
>    which DDL can't do).
> 2. **Stale-`LOCK` recovery.** If a process is killed mid-write, zvec leaves a
>    `LOCK` file; detect `lock` in the error, delete `$dbPath/LOCK`, retry once
>    (PocketSearch pattern). Budget for this in the open path.
> 3. **No key-enumeration primitive.** zvec cannot list its own primary keys. The
>    naive reconciliation ("iterate all zvec PKs, compare to Room") is impossible.
>    Maintain an **external key set** — either a Room shadow table kept during the
>    transition, or a sidecar index — for dedup/sync work. (PocketSearch works
>    around this with a dummy zero-vector query at topK = docCount; that is a
>    hack, not a pattern to copy.) Doc serialization
>    (`zvec_doc_serialize`/`deserialize`/`merge`, `c_api.h:3682,3694,3702`) can
>    cache migrated docs during the transition and support the external key set.

**Dependency:** Phase 1 (Kotlin SDK). Room still works during this phase so the
app is never without search.

---

### Phase 3 — Image Embeddings (new modality) (3–5 days)

This is the biggest product unlock. It adds **visual similarity search** — a
capability ScreenshotGo has never had.

**How it works:**

1. When a screenshot is captured or imported, generate a dense vector embedding
   from the image pixels.
2. Store the embedding in zvec alongside the OCR text and metadata.
3. At search time, the user's query image (or a camera roll pick) gets embedded
   and used as the query vector against zvec's HNSW index.
4. Results are ranked by cosine distance — visually similar screenshots rank
   higher even if they share no OCR text.

> **Pipeline reuse:** Phase 3's embedding step slots into the **same per-file background scan**
> that SAF ingestion (Phase 3b) builds. One bounded `Channel` feeds two CPU-bound consumers —
> OCR and embedding — so a file is hashed, OCR'd, and embedded in a single pass rather than three.
> Phase 3 adds the embedding consumer to a pipeline that already exists after Phase 3b; if Phase 3b
> hasn't landed yet, the MediaStore producer drives the same channel.

**Embedding model options (on-device):**

| Model | Size | Dim | Quality | Notes |
|---|---|---|---|---|
| **ML Kit Image Labeling** | ~5 MB | N/A (label-based) | Low | Quick win but not a true embedding — gives labels, not vectors |
| **TFLite MobileNetV3-Large** | ~16 MB | 1024 | Medium | Good quality/size trade-off, 2–5ms inference on modern devices |
| **TFLite EfficientNet-Lite0** | ~5 MB | 1280 | Medium | Faster than MobileNet, similar quality |
| **TFLite Universal Sentence Encoder Lite** | ~5 MB | 512 | Low-Medium | Designed for images+text, 512-dim is fast for HNSW |
| **ONNX MobileCLIP** | ~30 MB | 512 | High | Multimodal (image + text), enables text-to-image search too |

> **MobileCLIP is production-proven on mobile, not theoretical.** PocketSearch
> (a reference app, see [Reference implementations](#reference-implementations))
> ships **MobileCLIP-S1** in production via MNN inference with FP16 quantization:
> 512-dim embeddings, ~1 ms zvec retrieval, ~24 MB index for 10,239 photos on a
> Xiaomi 14 Ultra. This finding is **model-level and version-independent** — it
> proves the on-device CLIP pipeline is viable regardless of zvec version. Strong
> default choice for Phase 3 unless a smaller model is needed for low-end devices.

**Recommended starting point:** EfficientNet-Lite0 (5 MB, 1280-dim, fast inference,
well-supported on Android via TFLite Task Library). Generate embeddings at capture
time in a coroutine on `Dispatchers.Default`.

**Schema addition — via runtime DDL, not drop-and-reindex:**

Because zvec supports online column DDL (`zvec_collection_add_column`,
`c_api.h:3114`), adding the embedding field is an `add_column` call on the
*live* collection (recorded in the Phase 2 migration ledger), not a collection
rebuild:

```diff
 CollectionSchema("screenshots"):
   - pk: STRING
   - path: STRING
   - collection_id: STRING (inverted index)
   - last_modified: INT64
   - ocr_text: STRING (FTS index)
+  - image_embedding: VECTOR_FP32 (1280) (HNSW index, COSINE metric)
```

**Search UX additions:**

- **"Find similar" button** on the detail page — embeds the current screenshot
  and queries the `image_embedding` field, returns visually similar results.
- **Gallery search by image** — user picks an image from the gallery or camera,
  it gets embedded, and results are shown ranked by visual similarity.
- **Automatically included in hybrid search** — the default search bar uses
  `MultiQuery` with both `image_embedding` vector search and `ocr_text` FTS,
  fused by RRF. Users searching for "receipt" get text matches *and* visually
  similar receipt screenshots.

**Dependency:** Phase 2 (zvec storage active). The Kotlin SDK's vector search
surface is already defined — this phase just adds the embedding pipeline.

---

### Phase 3b — SAF Ingestion (additive source) (2–3 days)

Add a **second ingestion producer** (Storage Access Framework tree-poller) behind the
existing `ScreenshotRepository` seam, alongside the MediaStore observer. zvec itself is
source-blind — a doc is a doc whether it came from MediaStore or SAF — so this phase changes
**only the ingestion layer**, not zvec, the ViewModel, or the UI.

**Why SAF is additive, not a replacement for MediaStore:** the two sources cover different cases.

| Source | Discovery model | Covers | User cost |
|---|---|---|---|
| **MediaStore** (`READ_MEDIA_IMAGES`) | Automatic, system-indexed | System-captured screenshots in standard folders | One permission tap; no folder selection |
| **SAF** (`ACTION_OPEN_DOCUMENT_TREE`) | User-grants a tree URI; app polls | User-chosen folders: cloud-synced dirs (Drive/OneDrive), secondary storage, the trust/marketing angle, avoids Play Store sensitive-permission review | User navigates a system picker to choose the folder |

The MediaStore path stays the "just works" default; SAF is an opt-in for users who want the
permission story or have screenshots outside MediaStore's reach.

**Architecture — the producer/consumer pipeline:**

SAF is I/O-bound (Binder IPC to `ExternalStorageProvider`); OCR/embedding are CPU-bound. They
must be decoupled with a **bounded `Channel`** (backpressure) to avoid OOM:

```
SAF enumerator ──► I/O worker pool (4–8 threads) ──► [bounded Channel, cap=3] ──► CPU consumer (OCR + embed, 1 thread)
   (poll tree)         hash + dedup + thumbnail            (backpressures I/O)        write to zvec
```

- **I/O workers** take a SAF document URI, stream bytes to compute `content_hash`, check zvec
  for dedup, generate+cache a thumbnail, and push `(hash, InputImage)` onto the channel.
- **Bounded channel (capacity 3)** suspends I/O workers when full — this is the only thing that
  prevents a queue of 50 decoded bitmaps (≈500 MB) from OOM-killing the app.
- **CPU consumer** pops payloads, runs OCR + (after Phase 3) embedding, writes the doc to zvec.
  Writes use the SDK's `upsertAll` (Phase 1) backed by zvec's `_with_results`
  variants (`c_api.h:3180+`), so a batch reports per-doc failures rather than a
  single aggregate throw — a 500-file first-sync can log *which* doc failed OCR
  or insert without aborting the batch.

This is the same channel Phase 3's embedding consumer attaches to — one pipeline, two
CPU-bound consumers, one backpressure point.

**The "shadow gallery" — thumbnails are local, not SAF:**

SAF `getDocumentThumbnail()` crosses Binder per call; calling it from `onBindViewHolder`
destroys scroll performance. Instead, during the background scan, generate a 256px WebP
thumbnail and save it to app-private storage (`filesDir/thumbnails/<hash>.webp`). The gallery
`RecyclerView` loads thumbnails from local files (direct filesystem access, zero IPC); SAF is
touched **only** when the user taps an image for the full-screen view. This makes SAF-backed
galleries scroll as fast as MediaStore-backed ones.

**SAF-specific handling (non-negotiable):**

- **Persist the tree URI** — call `contentResolver.takePersistableUriPermission(uri, takeFlags)`
  in the picker result callback, or the grant expires when the process dies and the background
  poller throws `SecurityException` on next run.
- **`EXTRA_LOCAL_ONLY`** on the picker `Intent` — without it, users can select a Google
  Drive/OneDrive folder and every `openFileDescriptor` silently downloads over the network,
  destroying battery and mobile data.
- **Validate the picked folder** — users pick the wrong folder; inspect the returned tree and
  re-prompt if it doesn't look like a screenshots directory.
- **Stale-locator cleanup** — SAF URIs get revoked; wrap `openFileDescriptor` in try/catch,
  and on `FileNotFoundException`/`SecurityException` drop the `locator` field (keep the doc —
  the content_hash + OCR/embedding are still valid for re-matching if the file resurfaces).
- **No reliable change notifications** — SAF doesn't offer `FileObserver`/inotify; poll the tree
  on a WorkManager cadence (e.g. every 15 min) rather than relying on `notifyChange`.
- **Respect the 128 (or 500, API 31+) persisted-URI limit** — one tree URI per user-selected
  folder; don't persist individual file URIs.

**Files to create:**

| File | Purpose |
|---|---|
| `app/.../ingestion/SafScreenshotSource.kt` | SAF tree enumerator + poller; implements the same `ScreenshotSource` interface as the MediaStore observer |
| `app/.../ingestion/IngestionPipeline.kt` | The bounded `Channel` + I/O worker pool + CPU consumer; both sources feed it |
| `app/.../ingestion/ThumbnailCache.kt` | App-private WebP thumbnail store keyed by `content_hash` |

**Files to change:**

| File | Change |
|---|---|
| `ScreenshotRepository.kt` | (Issue 26 already made it source-agnostic) — confirm SAF source registers alongside MediaStore |
| `PermissionFlow` / onboarding | Add an optional SAF-folder-picker step (parallel to, not replacing, the `READ_MEDIA_IMAGES` prompt) |

**First-sync UX (the real SAF cost):** when a user picks a folder with hundreds of historical
screenshots, the initial scan hashes + OCR's + (after P3) embeds all of them. This is a
foreground-service job with a progress UI ("Analyzing 45 of 842 screenshots…"), not a background
trickle. Budget for it as a feature — it's the difference between "SAF feels broken" and "SAF
feels polished."

**Dependency:** Phase 2 (zvec storage + content_hash PK) and **issue 26** (source-agnostic
repository seam). Phase 3 (embeddings) is *not* a dependency — SAF lands first if you want
optimal sequencing, since the ingestion pipeline it builds is what Phase 3 attaches its
embedding consumer to.

---

### Phase 4 — Hybrid Search (2–3 days)

Switch ScreenshotGo's single search bar from "FTS-only" to **hybrid**
(vector + FTS + filter). This is the moment where zvec earns its place.

**Implementation:**

```kotlin
// SearchFragment.kt (zvec-backed version)
viewModel.searchResults = zvecCollection.hybridSearch(
    queries = listOf(
        SubQuery(field = "image_embedding", vector = queryEmbedding),
        SubQuery(field = "ocr_text", fts = queryText),
    ),
    topK = 40,
    reranker = RrfReranker(rankConstant = 60)
)
```

**What the user experiences:**

- A single query text field.
- Behind the scenes, the query string is sent to both the FTS index *and*
  (optionally) embedded for vector search against image embeddings.
- Results blend: a screenshot of a receipt that visually looks like a receipt
  AND contains the word "total" ranks above one that only has the text match.
- The filter (`collection_id`, date range) narrows both legs before fusion,
  so filtered-out results never pollute the ranking.

**No mandatory UI changes** — the existing search UI (`SearchFragment.kt`,
`FullTextSearchFragment.kt`) can remain as-is. The results just get better.

**Tunable knobs:**

| Parameter | Effect |
|---|---|
| `rankConstant` (RRF) | Higher = more weight on top-ranked results per modality |
| Weighted reranker | Manual weight per field (e.g., 0.3 vector + 0.7 text) |
| Per-modality topK | How many candidates each modality contributes before fusion |

> **Two v0.5.1 verification tasks before the reranker math lands (do not assume
> from any reference):**
>
> 1. **Score semantics.** `zvec_doc_get_score()` returns a `float` whose meaning
>    depends on the metric. A v0.4.0-era reference observed COSINE behaving as a
>    *distance* (ascending sort = more similar), but v0.5.0 unified the query API
>    (dropped `VectorQuery`, unified on `SearchQuery`) and may have changed this.
>    **Verify empirically against our pinned v0.5.1**: run a known-vector query
>    with `ZVEC_METRIC_TYPE_COSINE`, print scores for near vs far docs, confirm
>    distance-vs-similarity. The reranker fusion math (RRF rank-blending,
>    weighted score combination) is only correct once this convention is pinned.
> 2. **FTS-as-sub-query is v0.5.1-native.** The MultiQuery + FTS sub-query
>    combination this phase depends on was added in **v0.5.1 (#520 "C API FTS
>    Sub-Query Support")**. Pinning v0.5.1 was therefore well-timed for Phase 4;
>    v0.4.0-era references (PocketSearch) literally cannot demonstrate it.

**Dependency:** Phases 2 + 3 (both text and vector data in zvec).

---

### Phase 5 — Scalar Filtering & Query Consolidation (1–2 days)

Migrate all remaining filtering logic into zvec queries instead of doing it in
Kotlin post-processing.

**Current pattern (post-filter in app code):**

```kotlin
val results = repo.search(query)  // returns ALL text matches
val filtered = results.filter { it.collectionId == selectedCollection }
```

**zvec pattern (push-down filter):**

```kotlin
val results = zvecCollection.query(
    QueryRequest(
        field = "ocr_text",
        fts = query,
        filter = "collection_id = '$selectedCollection'"
    )
)
```

This eliminates the N+1 filtering problem and lets zvec's inverted indexes
handle the work. Benefits:
- No unnecessary deserialization of filtered-out results
- Hybrid search fuses only relevant candidates
- `DELETE BY filter` instead of iterating and deleting one by one

**Files to change:**

- `ScreenshotViewModel` / repository layer — pass filter params through to zvec
- `CollectionFragment` — use `filter = "collection_id = '$id'"` instead of
  loading all screenshots and filtering in the adapter
- `FullTextSearchFragment` — add collection picker as a query filter, not a
  post-filter

**Dependency:** Phase 2 (zvec storage). Standalone work.

> **What the `filter` string actually is:** a *SQL filter-expression subset*
> (boolean predicates over scalar fields), passed as `const char*` to
> `zvec_multi_query_set_filter` / `zvec_vector_query_set_filter`
> (`c_api.h:1696,2182` — and `group_by_vector_query_set_filter` at `:1996`).
> Not a made-up DSL, and not full SQL — see the Phase 7
> [SQL grounding note](#sql-based-ad-hoc-queries) for the three-layer breakdown.
> Because it's SQL-expression territory, **treat user-supplied values as untrusted
> and parameterize/escape them** — the `filter = "collection_id = '$id'"` sketch
> above is illustrative; a literal `'$selectedCollection'` interpolation is a
> filter-injection vector if `selectedCollection` ever comes from user input.

> **v0.5.1 verification task before relying on push-down filters:** A v0.4.0-era
> reference reported that zvec treats **missing scalar fields as filter
> pass-through** — a doc without the filtered field is *recalled*, not excluded —
> and worked around it by always writing sentinel `0` for filterable fields. The
> v0.5.0 changelog lists a fix for *"null leakage through nullable scalar
> filters" (#416)*, which may have changed this behavior (the workaround may now
> be obsolete, or even harmful — sentinel zeros on a `size` field would become
> filterable in unintended ways). **Verify against v0.5.1 before deciding on
> sentinels**: insert one doc with a filterable field set and one without, filter
> on that field, observe whether the null-field doc is recalled. Do not encode a
> v0.4.0 scar as v0.5.1 doctrine.

---

### Phase 6 — Deprecate Room (0.5 days)

Once zvec has been running in production without issues:

1. Delete `ScreenshotDatabase.kt`, `ScreenshotDao.kt`, `ScreenshotModel.kt`,
   `ScreenshotContentModel.kt`, `FtsEntity.kt`, `CollectionModel.kt`.
2. Remove Room from `build.gradle.kts`.
3. Remove `Room` dependency from `libs.versions.toml`.
4. Delete room schema export JSON files (if any).

**Dependency:** All prior phases stable.

---

### Phase 7 — Advanced Features (wishlist, no commitment)

These use zvec capabilities that aren't needed today but become possible
once the infrastructure is in place.

#### Text-to-Image Search

Replace the per-screenshot embedding with a multimodal model (MobileCLIP /
FashionCLIP) that maps *both* images and text into the same embedding space.
Then users can type "code screenshot with red error message" and get results
ranked by visual relevance to that description — no OCR text match required.

Requires: Multimodal ONNX/TFLite model. The zvec vector field stays the same;
only the embedding source changes from "image pixels" to "CLIP image encoder."

#### Sparse Vector Search (BM25 as vectors)

zvec supports sparse vectors (`SPARSE_VECTOR_FP32`). Instead of using zvec's
FTS engine for OCR text, you could embed OCR text as BM25 sparse vectors and
search them via `FLAT_SPARSE` or `HNSW_SPARSE`. This lets you combine text
and visual search in a *single* vector query rather than needing `MultiQuery`
with RRF.

Not obviously better than zvec's built-in BM25 FTS, but worth knowing it's
an option if RRF tuning proves finicky.

#### Group-By-Vector Search

zvec's `GroupByVectorQuery` lets you cluster search results by a scalar field.
Example: "Show me the best-matching screenshot from each collection" — returns
one result per collection, ranked by similarity score. Could power a
deduped search view where similar screenshots are collapsed.

#### SQL-Based Ad-Hoc Queries

> **Grounding note (verified against `v0.5.1` source):** This splits into three
> distinct layers that the original roadmap conflated:
>
> 1. **Full SQL string parsing — NOT reachable from Android.** zvec ships a
>    complete SQL engine (`src/db/sqlengine`): an ANTLR4 grammar
>    (`ZVecSQLParser::parse`, `src/db/sqlengine/parser/zvec_sql_parser.cc:35`)
>    parses raw SQL strings into `SQLInfo`, executed via `SQLEngineImpl::execute`
>    over Apache Arrow Acero. **None of this is exposed in `c_api.h`.** There is
>    no `zvec_execute_sql(const char*)` or equivalent. The four official SDKs
>    (Python/Go/Rust/C++) don't wrap it. So "type a raw `SELECT ...` and run it"
>    is unreachable from the JNI/Android path without a new upstream C binding.
> 2. **SQL *filter expressions* — REACHABLE, and already in this roadmap.** Three
>    query types (`vector_query`, `group_by_vector_query`, `multi_query`) all
>    accept a `const char *filter` ("Filter expression string",
>    `c_api.h:1690,1991,2176`). This is the Phase 5 push-down-filter mechanism
>    (`filter = "collection_id = '$id'"`) and the Phase 4 hybrid `MultiQuery`
>    filter. It's a *subset* of SQL — boolean predicates over scalar fields, not
>    full `SELECT/FROM/JOIN` — but it's the part that actually matters for app
>    queries.
> 3. **Structured queries execute *through* the SQL engine.** Every
>    `SearchQuery`/`MultiQuery` is internally converted to `SQLInfo`
>    (`SQLInfoHelper::BuildSQLInfoFromSearchQuery`) and run by `SQLEngineImpl`.
>    So the Android path is *already* using the SQL engine — it just can't speak
>    raw SQL *to* it.
>
> **Net:** the only genuinely-missing capability is raw-SQL-string execution
> (layer 1) — useful for a debug/developer mode, not for app queries. Layers 2
> and 3 are reachable and already planned.

Could power a "developer mode" in settings (raw SQL inspection of a collection)
— but only if zvec adds a C SQL-string surface, which doesn't exist today. For
app queries, the SQL filter-expression subset (layer 2) is sufficient and already
in Phases 4/5.

#### On-Disk Graph Index (DiskANN / Vamana) for Large Collections

> **Grounding note (verified against `v0.5.1` source):** The roadmap previously
> claimed DiskANN "can be enabled with `-DBUILD_DISKANN=ON`" on Android. That is
> wrong on two counts. There is **no `-DBUILD_DISKANN` flag** — the gate is the
> auto-detected `DISKANN_SUPPORTED` (`CMakeLists.txt:126`), which is **hard-set
> `OFF` on Android** (`NOT ANDROID`) because it requires `libaio` (Linux kernel
> AIO) and an x86_64 host. The C API surface that *does* exist for on-disk graphs
> is **Vamana** (`ZVEC_INDEX_TYPE_VAMANA = 6`, `c_api.h:829`, with
> `zvec_index_params_set_vamana_params` and `zvec_query_params_vamana_create`).
> Vamana is the algorithmic name for the DiskANN family. Whether the Vamana C
> path is usable on Android without the libaio-backed disk plugin is
> **unverified** — treat on-disk indexing as not-available-on-Android unless
> empirically proven otherwise.

If a user has 100K+ screenshots, the in-memory HNSW index becomes expensive.
On-disk graph indexing (DiskANN / Vamana) keeps the index on disk and only loads
hot pages — but see the grounding note: it is Linux-x86_64-only today and
excluded on Android. For ScreenshotGo's scale (thousands, not billions, of
screenshots), HNSW in memory is the right index; this remains a far-future
consideration only if collection sizes grow by 10–100×.

---

## File Inventory

> **The Phase 0 file layout (the `:zvec-android` module, JNI `.cc` files, CMake,
> build script, AAR config) is authoritative in
> [ZVEC_PHASE0.md](./ZVEC_PHASE0.md)** — its "Module layout" section is the
> single source of truth and supersedes the earlier (now-removed) inventory that
> placed files under `zvec/src/binding/jni/`. Don't duplicate it here; it will
> drift. The inventory below covers only the *app-side* changes Phases 2–6 make.

### Files to modify (app module, Phases 2–6)

| Path | Change |
|---|---|
| `app/.../ScreenshotRepository.kt` | Add `ZvecScreenshotRepository` behind the existing suspend/Flow interface (issue 26) |
| `app/.../ScreenshotViewModel.kt` | Swap repository constructor injection |
| `app/.../ContentScanner.kt` / `ForegroundScanner.kt` | Write OCR text (and, P3, embeddings) to zvec |
| `app/.../build.gradle.kts` | `implementation(project(":zvec-android"))` |
| `gradle/libs.versions.toml` | zvec version entry |

### Files to delete (post-migration, Phase 6)

| Path | Reason |
|---|---|
| `ScreenshotDatabase.kt` | Room no longer used |
| `ScreenshotDao.kt` | Room DAO no longer used |
| `ScreenshotModel.kt` | Room entity no longer used |
| `ScreenshotContentModel.kt` | Room entity no longer used |
| `FtsEntity.kt` | FTS4 virtual table no longer used |
| `CollectionModel.kt` | Room entity no longer used |
| `ScreenshotDatabaseRepository.kt` | Replaced by `ZvecScreenshotRepository` |

---

## Reference implementations

Oracles we consult when designing each phase. Each is annotated with **what it
proves** and **what version it pins** — because v0.5.0 was a breaking API overhaul
(dropped `VectorQuery`, unified on `SearchQuery`; added FTS, MultiQuery hybrid,
DiskANN) and references pinned before it are **API-level misleading for v0.5.1
work**.

| Reference | Version | What it proves | What it does *not* prove |
|---|---|---|---|
| **zvec-rust SDK** (`zvec-ai/zvec-rust`) | v0.5.0-era | The v0.5.x C API surface and idiomatic handle/marshalling patterns. **Primary API oracle for this project** — version-matched to our pin. | Android build (it's a desktop binding); the JNI layer. |
| **zvec-dart SDK** (`zvec-ai/zvec-dart`) | tracks v0.5.x | The `build_android.sh` artifact producer (the only Android-proven `.so` build path). The basis for our `build_libzvec.sh`. | App-level patterns (it's a binding, not an app). |
| **zvec-go / zvec-node SDKs** | v0.5.x | Alternative API shapes (Go's cgo patterns, Node's N-API CMake from-source build — the latter is a contrast, not a template). | Anything Android-specific. |
| **PocketSearch** (`docs/references/POCKETSEARCH.md`) | **zvec v0.4.0** ⚠️ | The MobileCLIP-S1 on-device pipeline (model-level, version-independent); zvec fundamentals (no schema migration, no key enumeration, process-wide singleton, stale-LOCK recovery). | **v0.5.1 API** — its search path is built on the deleted `VectorQuery` and predates FTS/MultiQuery. Do not learn the query API from it. See its doc for the full version caveat. |

**The rule:** for API questions, the rust binding is the oracle. For mobile-CLIP
and zvec-fundamental questions, PocketSearch is a useful historical reference.
Never cite PocketSearch for query/score/filter semantics without re-verifying
against our pinned v0.5.1.

---

## Research queries

Open questions the roadmap depends on, ranked by which phase they gate. Each is
phrased as a concrete, testable query against our own pinned `libzvec_c_api.so` —
resolved by experiment, not by reading references. Logged here so they get
discharged at the right phase rather than discovered mid-build.

### Gating Phase 1 (Kotlin SDK)

- **[R1] Score semantics per metric.** Is `zvec_doc_get_score()` a *distance*
  (lower = more similar, sort ascending) or a *similarity* (higher = more
  similar, sort descending)? Depends on the metric (`COSINE` vs `L2` vs `IP`).
  *Test:* known-vector query, COSINE metric, print scores for near/far docs.
  *Why it gates P1:* the Kotlin `QueryResult` ordering and any default sort
  direction depend on the answer.

### Gating Phase 4 (hybrid search)

- **[R2] ~~Does `MIPSL2` matter on arm64?~~ — resolved by source read; no longer
  a research query.** The metric enum's `ZVEC_METRIC_TYPE_MIPSL2 = 4` maps
  internally to `kMIPSL2sq` (spherical MIPS, a legitimate maximum-inner-product
  variant with L2 normalization), handled in `core/interface/index.cc` with
  **no platform gate**. An earlier draft speculated it was an "x86
  SIMD-specialized path" to verify — that was wrong: the x86-only gates
  (`CMakeLists.txt:101,127`) control only **RaBitQ** (AVX2/AVX512) and
  **DiskAnn** (`NOT ANDROID`), not MIPSL2. **MIPSL2 works identically on arm64.**
  Kept here as a crossed-out entry so the wrong premise isn't re-raised. (If
  anything, the real Phase 4 research question is whether `MIPSL2` or `IP` beats
  `COSINE` for MobileCLIP embeddings — but that's a quality question, not an
  availability one, and not gating.)
- **[R3] MultiQuery fusion across FTS + vector sub-queries — does the v0.5.1
  FTS-sub-query path (#520) fuse cleanly with a vector sub-query in one
  `MultiQuery`?** This is the entire basis of Phase 4. *Test:* build a collection
  with one VECTOR_FP32 field (HNSW) + one STRING field (FTS), insert a few docs,
  issue a `MultiQuery` with both sub-queries, assert fused results.

### Gating Phase 5 (scalar filtering)

- **[R4] Null-field filter pass-through, post-#416.** Does a doc missing a
  filtered scalar field get recalled or excluded? Determines whether ScreenshotGo
  needs sentinel values on `collection_id` / `size` / `last_modified`. *Test:*
  insert one doc with `size=1000` and one without `size`, filter `size > 500`,
  observe recall.

### Gating Phase 3 (embeddings) — model questions

- **[R5] MobileCLIP-S1 inference latency on a low-end device.** PocketSearch
  reports ~1 ms *retrieval* on a Xiaomi 14 Ultra (flagship). *Inference* (the
  encoder) is the real cost at ingest. Measure on a mid-range device (e.g.
  Pixel 4a-class) to decide whether EfficientNet-Lite0 (smaller, faster, lower
  quality) is needed as a fallback, or MobileCLIP-S1 alone suffices.
- **[R6] Text-to-image quality on screenshot-like content.** MobileCLIP is
  trained on natural photos. Screenshots are dominated by UI chrome, text, and
  code — a different distribution. Measure recall@10 for text-to-image queries
  ("receipt", "code with red error", "chat conversation") on a real screenshot
  corpus before committing to CLIP for the text-to-image Phase 7 wishlist.

### Cross-cutting

- **[R7] arm64 runtime correctness.** Phase 0's packaging check proves the arm64
  `.so` *exists*, not that it *loads and runs*. A genuine arm64 bug (page-size
  flag, arm64-only code path, NDK STL quirk) won't surface until real hardware.
  Discharge by running *any* zvec operation on an arm64 device/emulator before
  Phase 2 ships.

---

## Testing notes

- **zvec is a process-wide singleton.** The native library is shared across all
  instances in one process; you cannot freely spin up and tear down isolated
  zvec collections in a single test process, and calling `shutdown()` in a
  `dispose()`/teardown breaks later tests in the same process. Use **unique
  collection paths per test**, not separate process-wide instances.

---

## Upstream gaps to watch

Capabilities that exist in zvec's **C++ layer** but have **no exposure in
`c_api.h`** — so they're unreachable from the Android/JNI path today and
**cannot be fixed locally**. Documented here so they aren't rediscovered as bugs,
and so each zvec version bump can be checked for newly-exposed surfaces. None
gate the current roadmap; all are optimizations or safety nets.

| Gap | C++ reality | Why we'd want it | Action |
|---|---|---|---|
| **HNSW-RaBitQ** | `IndexType::HNSW_RABITQ = 4`, full impl (`src/core/algorithm/hnsw_rabitq/`, ~29 files), ~7 bits/dim quantization | Phase 3's 1280-dim embeddings: ~4× smaller in-memory index than full-precision HNSW | Watch for `ZVEC_INDEX_TYPE_HNSW_RABITQ` + `set_hnsw_rabitq_params` in `c_api.h` |
| **Query prefetch** | `HnswQueryParams` has `prefetch_offset_`/`prefetch_lines_` (defaults PO=8, PL=0 from `constants.h`); same for Vamana | ARM handles prefetch differently from x86; PL=0 means graph traversal issues no prefetch hints — a silent perf knob | Watch for `zvec_query_params_hnsw_set_prefetch_*` accessors |
| **`is_dirty()`** | `Index::IsDirty()` exists in C++ | Lets `flush()` skip when there's nothing to write — the current "no-op-or-work gamble" | Watch for a C-API `zvec_collection_is_dirty` |
| **Per-op concurrency** | `CreateIndexOptions`/`OptimizeOptions`/`AddColumnOptions` carry a `concurrency_` field | Can't say "use 2 threads for this one op, not the pool default" if `optimize()` is already running | Watch for the field surfacing on the C `*_options` structs |
| **ExternalVectorSource** | Added v0.5.1 (#490) for flexible ingestion | Could decouple the SAF/MediaStore pipeline from direct `insert` calls | Watch for a C-API surface; currently C++-only |

---

## Risk Assessment

| Risk | Mitigation |
|---|---|
| **JNI crashes** — native code bugs crash the entire app process | Every JNI function wraps zvec calls in try-catch, converts C errors to Kotlin exceptions via `ZvecException`. Crash dump includes zvec error string. |
| **Data loss during migration** — writing to zvec fails, Room is already cleared | Keep Room as the source of truth during Phase 2. zvec is read-replica only until migration is confirmed stable (N successful launches, no rollbacks). |
| **NDK compatibility regressions** — new zvec versions break on older Android | Run `scripts/build_android.sh` in CI. Test on API 28–35 emulators. |
| **Embedding model size** — 16 MB model .tflite file bloats the APK | Use TFLite Task Library's model download API (download on first launch). Or use ML Kit's on-device model which is included in Google Play Services. |
| **Hybrid search latency** — two queries + RRF takes too long on device | zvec's in-process queries are fast (<10ms per leg on 10K docs). For larger collections, reduce per-modality `topK` and let RRF re-rank only the top candidates. |
| **zvec API changes** — zvec upstream evolves and the JNI layer breaks | Pin zvec version in the Android build. The C API is stable (semver). |

---

## Effort Summary

| Phase | Effort | Risk | Delivers |
|---|---|---|---|
| **P0** — Native AAR | 1–2 days | Medium | Buildable library |
| **P1** — Kotlin SDK | 2–3 days | Low | Usable API |
| **P2** — Replace Room | 2–3 days | Medium | Feature parity on zvec (content_hash PK) |
| **P3** — Image embeddings | 3–5 days | Medium | Visual similarity search |
| **P3b** — SAF ingestion | 2–3 days | Medium | Second source, user-granted folders, no broad-media permission |
| **P4** — Hybrid search | 2–3 days | Low | Combined ranking |
| **P5** — Scalar filters | 1–2 days | Low | Push-down predicates |
| **P6** — Deprecate Room | 0.5 days | Low | Cleanup |
| **P7** — Advanced features | Variable | Low | Optional |

**Total (core): ~14–21 days** for a complete migration from Room to zvec with
visual similarity, hybrid search, and SAF ingestion.
