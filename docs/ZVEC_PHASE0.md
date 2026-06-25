# Phase 0 — Native Library Build Integration (zvec): Implementation Plan

> **Status:** Implemented. All architectural decisions below were reached through
> a structured grilling session (2026-06-24) and grounded in the actual `c_api.h`
> from the pinned zvec tag. The implementation is complete and verified across all
> three layers of the verification plan on an x86_64 emulator.
>
> **Supersedes:** the "Phase 0" section of [ZVEC_ROADMAP.md](./ZVEC_ROADMAP.md).
> The roadmap's Phase 0 contained three factual errors (see
> [Corrections to the roadmap](#corrections-to-the-roadmap) below). This document
> is authoritative.
>
> **See also:** [Implementation notes](#implementation-notes-deviations-from-this-plan)
> below — five places where the shipped code intentionally (and one place where it
> had to be corrected to) diverge from the text written here. Read those before
> treating any code sketch in this document as copy-pasteable.

This plan details how to integrate the **zvec** vector database native library
into ScreenshotGo as an Android Archive (AAR) module, building the JNI bridge
that Phase 1's Kotlin SDK will sit on top of.

The goal of Phase 0 is narrow and load-bearing: **prove the toolchain and the JNI
architecture end-to-end on the smallest surface that carries real risk.** It is
explicitly *not* the full JNI surface or the Kotlin SDK — those are Phase 1.

---

## Table of contents

- [Corrections to the roadmap](#corrections-to-the-roadmap)
- [Architecture at a glance](#architecture-at-a-glance)
- [Module layout](#module-layout)
- [The prebuilt `.so` build path](#the-prebuilt-so-build-path)
- [Gradle & CMake wiring](#gradle--cmake-wiring)
- [The JNI layer](#the-jni-layer)
  - [Error model: the macro family](#error-model-the-macro-family)
  - [Handle convention](#handle-convention)
  - [Marshalling rules](#marshalling-rules)
  - [The Level-2 slice: four native functions](#the-level-2-slice-four-native-functions)
- [Version pinning & the bump procedure](#version-pinning--the-bump-procedure)
- [Verification plan](#verification-plan)
- [Toolchain & prerequisites](#toolchain--prerequisites)
- [Implementation order](#implementation-order)
- [Handoff to Phase 1](#handoff-to-phase-1)
- [Residual risks (deliberately accepted)](#residual-risks-deliberately-accepted)
- [Implementation notes (deviations from this plan)](#implementation-notes-deviations-from-this-plan)
- [Latent risks & known smells (Phase 1 pre-work)](#latent-risks--known-smells-phase-1-pre-work)
- [Decision log](#decision-log)

---

## Corrections to the roadmap

Three claims in [ZVEC_ROADMAP.md](./ZVEC_ROADMAP.md) Phase 0 / File Inventory
were checked against ground truth during planning and found wrong. They shape the
plan, so they're stated up front:

1. **The doc API is type-erased, not typed setters.** The roadmap's file list
   references `zvec_doc_add_string` / `add_vector_f32`-style typed setters. They
   do not exist. The real C API is
   `zvec_doc_add_field_by_value(doc, field_name, data_type, void* value, size_t)`,
   where `data_type` (e.g. `ZVEC_DATA_TYPE_STRING = 2`,
   `ZVEC_DATA_TYPE_VECTOR_FP32 = 23`) selects how the `void*` is interpreted. This
   *simplifies* the JNI surface (one value-path, dispatched by type) and reshapes
   the Level-2 slice (see [marshalling rules](#marshalling-rules)).

2. **The upstream `scripts/build_android.sh` does not produce an Android `.so`.**
   It builds zvec + unit tests and runs them in an emulator — it's upstream's CI
   test harness. It never sets `BUILD_C_BINDINGS=ON`, never builds the
   `zvec_c_api` target, and emits no `libzvec.so`. The *artifact producer* is the
   **dart binding's** `scripts/build_android.sh`, which sets `BUILD_C_BINDINGS=ON`,
   builds only `zvec_c_api`, and copies the result to `jniLibs/<abi>/`. We port
   the dart script, not upstream's.

3. **`zvec_get_last_error`'s message is freed with `zvec_free`, not `free`.** The
   header is self-contradictory (`zvec_get_last_error` says "free"; `zvec_free`
   says "use this for library-allocated memory"), but the rust binding resolves it
   with `zvec_free` (the conservative choice — it's always the matching
   deallocator, while a bare `free()` only matches if zvec's allocator happens to
   be the CRT malloc). This is a latent heap-corruption bug if gotten wrong.

---

## Architecture at a glance

```
                    ┌─────────────────────────────────────────────┐
                    │              zvec-android (AAR)              │
                    │                                             │
   zvec git         │  src/main/jniLibs/<abi>/libzvec.so   ◄──── prebuilt (dart path)
   submodule  ───►  │  src/main/cpp/CMakeLists.txt         ◄──── links JNI against ↑
   (pinned tag)     │  src/main/cpp/zvec_jni_*.cc          ◄──── thin JNI wrappers
                    │  src/main/java/.../ZvecNative.kt      ◄──── 4 external fns
                    └─────────────────────────────────────────────┘
                                       ▲
                                       │ implementation(project(":zvec-android"))
                                       │
                                  app module (Phase 2+)
```

**Two `.so` files ship in the AAR, produced by two different mechanisms:**

- `libzvec.so` — the full zvec engine (Arrow, RocksDB, Roaring, HNSW, FTS...),
  cross-compiled *outside Gradle* by a ported script, dropped into `jniLibs/` as
  a prebuilt blob. Gradle never compiles a line of zvec.
- `libzvec_jni.so` — the thin JNI bridge, compiled *by Gradle's*
  `externalNativeBuild` against the prebuilt `libzvec.so` via a CMake `IMPORTED`
  target.

This split exists because zvec's dependency tree requires a host-`protoc`
bootstrap step (Protobuf can't be cross-compiled without a host-built codegen)
that has no clean hook inside Gradle's per-ABI CMake. It's the model the dart
binding — the only one proven on Android — chose.

---

## Module layout

```
zvec-android/
├── build.gradle.kts                    # Android library module
├── consumer-rules.pro                  # keep ZvecException + native method names (R8)
├── ZVEC_VERSION                        # single source of truth: "v0.5.1\n"
├── README.md                           # prerequisites + build + verify + bump procedure
├── third_party/
│   └── zvec/                           # git submodule, pinned to a release tag
├── scripts/
│   └── build_libzvec.sh                # ported from dart binding; builds both ABIs
└── src/
    ├── main/
    │   ├── cpp/
    │   │   ├── CMakeLists.txt           # builds ONLY libzvec_jni.so
    │   │   ├── zvec_jni_marshalling.h   # macros + RAII guards + string/array helpers
    │   │   ├── zvec_jni_onload.cc       # JNI_OnLoad: cache jclass/jmethodID global refs
    │   │   └── zvec_jni_collection.cc   # nativeGetVersion/CreateAndOpen/Insert/Close
    │   ├── java/io/github/tzhvh/scryernext/zvec/
    │   │   ├── ZvecNative.kt            # object singleton; 4 external fns
    │   │   └── ZvecException.kt         # RuntimeException(code: Int, message: String)
    │   └── jniLibs/                     # GITIGNORED — populated by build_libzvec.sh
    │       ├── arm64-v8a/libzvec.so
    │       └── x86_64/libzvec.so
    └── androidTest/java/io/github/tzhvh/scryernext/zvec/
        └── ZvecRoundtripTest.kt         # the Phase 0 exit gate
```

- **Kotlin package: `io.github.tzhvh.scryernext.zvec`** — nested under the app's
  namespace. JNI C++ symbols derive from it
  (`Java_io_github_tzhvh_scryernext_zvec_ZvecNative_*`); nesting avoids future
  collision if zvec-android is ever extracted to a standalone artifact.
- **`jniLibs/` is gitignored.** The `.so` files are build artifacts, not source.
  A contributor runs `scripts/build_libzvec.sh` once to materialize them.

---

## The prebuilt `.so` build path

`scripts/build_libzvec.sh` is a port of the **dart binding's**
`scripts/build_android.sh` (not upstream's test-harness script — see
[correction #2](#corrections-to-the-roadmap)). It does three things per ABI:

1. **Build a host `protoc`.** Cross-compiling Protobuf requires a codegen that
   runs on the *build* machine, not the target. The script builds it once with
   the host compiler (cached in `build/host/bin/protoc`).
2. **Cross-compile the `zvec_c_api` target** with the NDK toolchain:
   `-DBUILD_C_BINDINGS=ON`, `-DANDROID_STL=c++_static`,
   `-DANDROID_PLATFORM=android-29` (must match `minSdk` — *not* the NDK default),
   pointing at the host protoc via `GLOBAL_CC_PROTOBUF_PROTOC`. This target
   produces a single fat `libzvec_c_api.so` with all internal static libraries
   embedded via `--whole-archive`.
3. **Copy + rename to `jniLibs/<abi>/libzvec.so`.**

> **Why `-DANDROID_PLATFORM=android-29`, not the upstream default.** Upstream's
> `build_android.sh` sets `API_LEVEL=${1:-35}`. If the port naively inherits
> that (or omits the flag and lets the toolchain default), `libzvec.so` may link
> a libc symbol added after API 29 and throw `UnsatisfiedLinkError` on an API-29
> device. `minSdkVersion` guards *Java* APIs but does not fully constrain *native*
> symbol linking — `ANDROID_PLATFORM` does. Pin it to the floor you support.

### Generator: Ninja (Make fallback)

Default generator is **Ninja** (`-G Ninja`), matching upstream's own
`build_android.sh`. At zvec's dependency scale (Arrow, RocksDB, etc.), Ninja's
parallelism materially shortens the per-ABI build — minutes saved across Phase 0
iteration. If `ninja` is not on `PATH`, the script falls back to Unix Makefiles
with a warning (the dart script proves Make works). This keeps the fast path
default while not hard-blocking a contributor who can't install one package.

### ABIs

**`arm64-v8a` and `x86_64` only.** `minSdk = 29` makes 32-bit ABIs
(`armeabi-v7a`, `x86`) nearly irrelevant; each ABI roughly doubles the `.so`
storage cost. The two chosen cover real devices (arm64) and the dev emulator
(x86_64). Enforced at the Gradle layer by `ndk.abiFilters`
(see [Gradle wiring](#gradle--cmake-wiring)).

### 16KB page alignment (real-device / Phase 2 concern)

Android 15 (API 35+) devices may run a **16KB** virtual page size rather than
the legacy 4KB. A `.so` linked without 16KB segment alignment fails to load on
those devices — an instant startup crash. `build_libzvec.sh` must pass
`-Wl,-z,max-page-size=16384` to the cross-compile link step for `libzvec.so`.
(NDK r30 may apply it by default; set it explicitly so a future NDK bump can't
silently regress.)

**This is a Phase-2 / real-device concern, not a Phase-0 gate.** The Phase 0
exit gate runs on an x86_64 emulator, whose page size the contributor controls
(≈always 4KB) — a missing flag won't surface there. It belongs in the same
residual-risk bucket as "arm64 is never executed in Phase 0": discharge before
shipping to a 16KB device. The `libzvec_jni.so` link (Gradle's CMake) inherits
the same flag via `CMAKE_SHARED_LINKER_FLAGS`.

---

## Gradle & CMake wiring

### `settings.gradle.kts` — register the module

```kotlin
include(":app")
include(":zvec-android")   // NEW
```

### `gradle/libs.versions.toml` — add the library plugin

```toml
[plugins]
android-application = { id = "com.android.application", version.ref = "agp" }
android-library     = { id = "com.android.library",     version.ref = "agp" }   # NEW — same agp version
kotlin-android      = { id = "org.jetbrains.kotlin.android", version.ref = "kotlin" }
...
```

### `zvec-android/build.gradle.kts`

```kotlin
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "io.github.tzhvh.scryernext.zvec"
    compileSdk = 35
    ndkVersion = "30.0.14904198"   // PIN — must match the NDK build_libzvec.sh uses;
                                    // mismatched c++_static runtimes across the libzvec.so /
                                    // libzvec_jni.so boundary are a rare, hard crash class.

    defaultConfig {
        minSdk = 29   // duplicated from app; keep in sync (library modules don't inherit it)

        // ZVEC_VERSION is the single source of truth for the pinned tag.
        // The instrumentation test asserts nativeGetVersion() matches this.
        buildConfigField(
            "String", "ZVEC_VERSION",
            "\"${rootProject.file("zvec-android/ZVEC_VERSION").readText().trim()}\""
        )

        externalNativeBuild { cmake { cppFlags("-std=c++17", "-fexceptions") } }
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }   // Q3 ABI enforcement

        consumerProguardFiles("consumer-rules.pro")   // keep ZvecException + native fns under R8
    }

    buildFeatures { buildConfig = true }

    externalNativeBuild { cmake { path("src/main/cpp/CMakeLists.txt") } }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions { jvmTarget = "1.8" }
}

dependencies {
    androidTestImplementation(libs.test.runner)
    androidTestImplementation(libs.junit)
}
```

### `zvec-android/consumer-rules.pro` — R8 keeps

R8 cannot see the JNI reflection the `.so` performs, so anything the C++ reaches
via `FindClass`/`GetMethodID` must be pinned explicitly or release builds break
on launch. `ZvecNative`'s native methods are named `Java_..._ZvecNative_*` in
the compiled `.so` — renaming the class makes the link unsatisfiable; and
`ZvecException`'s `(int, String)` constructor is invoked only from C++, so R8
would otherwise rename/strip it.

```proguard
# ZvecNative: the .so resolves native methods by class name; do not rename.
-keep class io.github.tzhvh.scryernext.zvec.ZvecNative {
    native <methods>;
}

# ZvecException: constructed only from JNI (invisible to R8). Keep the ctor
# the C++ calls and the fields it reads.
-keep class io.github.tzhvh.scryernext.zvec.ZvecException {
    public <init>(int, java.lang.String);
}
```

Keep the rules narrow (no blanket `-keep class ... { *; }`): over-broad keeps
defeat shrinking for no safety gain, since the JNI surface is exactly these two
classes.

### `zvec-android/src/main/cpp/CMakeLists.txt` — the IMPORTED target

```cmake
cmake_minimum_required(VERSION 3.22.1)
project(zvec_jni LANGUAGES CXX)

# The prebuilt libzvec.so is a fixed binary, never compiled here — only linked.
# Gradle separately packages jniLibs/<abi>/libzvec.so into the AAR.
add_library(zvec SHARED IMPORTED)
set_target_properties(zvec PROPERTIES IMPORTED_LOCATION
    ${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libzvec.so)

add_library(zvec_jni SHARED
    zvec_jni_onload.cc
    zvec_jni_collection.cc
)

target_include_directories(zvec_jni PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/../../third_party/zvec/src/include
)

target_link_libraries(zvec_jni PRIVATE zvec log)
set_target_properties(zvec_jni PROPERTIES CXX_STANDARD 17)

# 16KB-page alignment for Android 15+ devices — see "16KB page alignment" above.
# NDK r30 may set this by default; explicit here so an NDK bump can't regress it.
target_link_options(zvec_jni PRIVATE -Wl,-z,max-page-size=16384)
```

If `jniLibs/<abi>/libzvec.so` doesn't exist (contributor skipped
`build_libzvec.sh`), `IMPORTED_LOCATION` is missing and configuration fails with a
clear error — *better* than a silent `.so`-missing crash at runtime.

### `app/build.gradle.kts` — consume (Phase 2+, but wire it now)

```kotlin
implementation(project(":zvec-android"))
```

---

## The JNI layer

### Error model: the macro family

zvec's C API reports errors two ways, and the JNI layer must handle both:

- **Most calls return `zvec_error_code_t`** (`ZVEC_OK = 0` … `ZVEC_ERROR_UNKNOWN = 10`).
- **`*_create` functions return a pointer, `NULL` on failure** (not an error code).
- **The human message is thread-local**, read via
  `zvec_get_last_error(char**)` — *must be read on the same C++ thread,
  immediately after the failing call*, then freed with **`zvec_free`** (not
  `free` — see [correction #3](#corrections-to-the-roadmap)).

A macro family in `zvec_jni_marshalling.h` encodes this. Each macro reads the
last-error on the same thread/frame, throws a JVM `ZvecException` via cached
global refs, and forces a `return` so no wrapper can fall through into more JNI
calls with a pending exception.

```cpp
// zvec exports NO error-code-to-string helper (verified: no such symbol in
// c_api.h or the .so at v0.5.1). An earlier draft called a `zvec_code_name`,
// which does not exist and would not link. Roll our own label for the
// no-message fallback (the 11 codes are a closed enum, c_api.h:108-120).
inline const char* zvec_code_label(zvec_error_code_t c) {
  switch (c) {
    case ZVEC_OK:                       return "OK";
    case ZVEC_ERROR_NOT_FOUND:          return "NOT_FOUND";
    case ZVEC_ERROR_ALREADY_EXISTS:     return "ALREADY_EXISTS";
    case ZVEC_ERROR_INVALID_ARGUMENT:   return "INVALID_ARGUMENT";
    case ZVEC_ERROR_PERMISSION_DENIED:  return "PERMISSION_DENIED";
    case ZVEC_ERROR_FAILED_PRECONDITION:return "FAILED_PRECONDITION";
    case ZVEC_ERROR_RESOURCE_EXHAUSTED: return "RESOURCE_EXHAUSTED";
    case ZVEC_ERROR_UNAVAILABLE:        return "UNAVAILABLE";
    case ZVEC_ERROR_INTERNAL_ERROR:     return "INTERNAL_ERROR";
    case ZVEC_ERROR_NOT_SUPPORTED:      return "NOT_SUPPORTED";
    case ZVEC_ERROR_UNKNOWN:            return "UNKNOWN";
  }
  return "UNKNOWN";
}

// Throws ZvecException(code, message) and returns from the enclosing JNI fn.
// Family members differ only in the return type's zero-value.
#define ZVEC_CHECK_JNI_VOID(env, call)                                    \
  do {                                                                    \
    zvec_error_code_t _c = (call);                                        \
    if (_c != ZVEC_OK) {                                                  \
      char* _msg = nullptr;                                               \
      zvec_get_last_error(&_msg);          /* same thread, immediately */ \
      std::string _s = _msg ? _msg : zvec_code_label(_c);                 \
      if (_msg) zvec_free(_msg);           /* NOT free — zvec allocator */ \
      zvec_throw(env, _c, _s.c_str());                                    \
      return;                                                             \
    }                                                                     \
  } while (0)

#define ZVEC_CHECK_JNI_JLONG(env, call)                                   \
  do {                                                                    \
    zvec_error_code_t _c = (call);                                        \
    if (_c != ZVEC_OK) {                                                  \
      char* _msg = nullptr;                                               \
      zvec_get_last_error(&_msg);                                         \
      std::string _s = _msg ? _msg : zvec_code_label(_c);                 \
      if (_msg) zvec_free(_msg);                                          \
      zvec_throw(env, _c, _s.c_str());                                    \
      return 0;                                                           \
    }                                                                     \
  } while (0)

// For *_create functions that return NULL on failure (no error code).
#define ZVEC_CHECK_PTR(env, ptr, what)                                    \
  do {                                                                    \
    if (!(ptr)) {                                                         \
      zvec_throw(env, ZVEC_ERROR_INTERNAL_ERROR,                          \
                 (std::string("zvec ") + what + " returned null").c_str());\
      return 0;                                                           \
    }                                                                     \
  } while (0)
```

`zvec_throw()` uses a `jclass` cached as a **global ref** plus the
`jmethodID` stored **directly** (both created in `JNI_OnLoad`, see
`zvec_jni_onload.cc`). Note the JNI distinction: `jclass` is a `jobject` and
*must* be promoted via `NewGlobalRef`; `jmethodID` is an opaque pointer, **not**
a reference — `NewGlobalRef` on it is a category error. IDs are valid for the
class loader's lifetime once obtained, so they're stored as plain statics.
Neither is re-resolved per call: bare globals or per-call `FindClass`/`GetMethodID`
are use-after-free / classloader-hazard risks across attached threads.

**Why a macro, not RAII:** the macro bundles the `return` with the throw, so a
wrapper can't accidentally execute more JNI calls with a pending exception. RAII
destructors run *after* any code left in the scope (including extra JNI calls),
which is exactly the failure the macro exists to prevent. The `return`-type
"wart" is handled by the small family above.

### Handle convention

- **Collection handles cross the boundary as `jlong`** =
  `reinterpret_cast<jlong>(zvec_collection_t*)`. The Kotlin `ZvecCollection`
  (Phase 1) wraps it in a `Closeable` with a `closed: Boolean` guard.
- **No `Cleaner`/finalizer.** `minSdk = 29` can't use `Cleaner` (API 33+) without
  a deprecated `finalize()` fallback for 29–32 — two lifecycle paths for a benefit
  (automatic cleanup) the explicit `Closeable` already provides. The single
  collection handle is `Application`-scoped and closed deliberately.
- **Doc handles never escape to Kotlin.** A `zvec_doc_t` is built C-side inside
  the `insert` JNI call from the field *values* Kotlin passes, and freed before
  the call returns. This collapses the use-after-free/double-free surface to
  exactly one handle per collection — the easy, guardable case.

### Marshalling rules

Grounded in `c_api.h` ownership semantics. All six are encoded as named helpers
in `zvec_jni_marshalling.h`.

| # | Rule | Mechanism |
|---|---|---|
| 1 | **Inbound string → eager `std::string`.** `GetStringUTFChars` → copy → `ReleaseStringUTFChars` in the helper's scope, *then* call zvec. Null-on-OOM from `GetStringUTFChars` → `ThrowNew(OutOfMemoryError)` + caller checks `ExceptionCheck`. | `jstring_to_std(env, js)` |
| 2 | **Inbound arrays/vectors → eager `std::vector<T>`.** Same discipline. The C API is type-erased (`add_field_by_value(doc, name, data_type, void*, size)`); typed Kotlin arrays dispatch by `data_type` constant. | `jfloatarray_to_vec`, etc. |
| 3 | **C→Kotlin owned string** (`zvec_string_t*` returns) → `NewStringUTF` (copies) then `zvec_free_string`. | `zvec_string_to_jstring` |
| 4 | **C→Kotlin borrowed string** (e.g. `zvec_get_version`) → `NewStringUTF` only, nothing to free. | inline |
| 5 | **`zvec_get_last_error` message** → free with **`zvec_free`**, not `free`. | baked into the macros |
| 6 | **Transient C objects (doc, field schema) → RAII guards**, freed on any exit path including the macro's throw-return. Doc handles never cross to Kotlin. | `DocGuard`, `FieldSchemaGuard`, `SchemaGuard` |

**Why eager copy, not RAII-pinned JVM arrays:** an RAII release-guard *can* be
made safe (JNI permits `Release*` during the pending-exception unwind the macro
triggers), but its safety depends on an **unverified** property — that zvec
copies every input pointer during the call and retains nothing. The header
doc-comments don't guarantee this per-function. Eager copy is unconditionally
correct regardless of zvec's copy-vs-borrow behavior, at the cost of one small
allocation per call (negligible: OCR text is KB, embeddings are ~5 KB, both
off-hot-path at ingest). The copy-vs-borrow property is flagged as a Phase-1
task to discharge *if* the embedding-ingest hot path ever needs optimization.

**The `FieldSchemaGuard` is non-negotiable** because `zvec_collection_schema_add_field`
*clones* the field (`c_api.h:2740`: "will be cloned, caller retains ownership") —
the caller must still `zvec_field_schema_destroy` its own copy, and a throw
between `create` and `destroy` would leak it without the guard.

### The Level-2 slice: four native functions

Phase 0 exports exactly four `external` functions on one Kotlin object. This is
the smallest surface that exercises every JNI idiom the architecture depends on
(handles, the macro family, string marshalling, the type-erased value path, RAII
guards, schema construction) on real zvec I/O — without front-loading Phase 1's
type design.

```kotlin
// ZvecNative.kt
object ZvecNative {
    init { System.loadLibrary("zvec_jni") }

    external fun nativeGetVersion(): String
    external fun nativeCreateAndOpen(path: String, schemaName: String): Long  // handle
    external fun nativeInsertString(handle: Long, pk: String,
                                    fieldName: String, value: String): Int  // success count
    external fun nativeClose(handle: Long)
}
```

**What each function proves:**

| Function | C calls exercised | De-risks |
|---|---|---|
| `nativeGetVersion` | `zvec_get_version()` | `loadLibrary`, JNI link, borrowed-string out |
| `nativeCreateAndOpen` | `schema_create` → `field_schema_create` ×2 → `schema_add_field` ×2 → `collection_create_and_open` | schema construction, multi-field ownership (`add_field` clones), `ZVEC_CHECK_PTR`, `FieldSchemaGuard`, `SchemaGuard`, handle out |
| `nativeInsertString` | `doc_create` → `doc_set_pk` → `doc_add_field_by_value(STRING)` → `collection_insert` | the type-erased value path (Rule 1/2), `DocGuard`, `ZVEC_CHECK_JNI_*` |
| `nativeClose` | `collection_close` | handle-in, `ZVEC_CHECK_JNI_VOID` |

**Two string fields, not one.** The schema built inside `nativeCreateAndOpen`
declares two *content* fields (`title`, `content`) — both ordinary columns. A
single-field schema is a degenerate case where multi-field bugs hide (field-name
dispatch, ownership across multiple `add_field` calls). Two fields de-risk the
schema path at the cost of one extra line. Vector fields are *deferred to Phase
1* — the type-erased `add_field_by_value` path is the same JNI machinery for any
type, so a string field already exercises it; adding a vector would pull in
dimension handling and the schema's vector-index-params machinery without
additional marshalling de-risking.

> **The PK is a doc property, not a schema field (grounding correction).** An
> earlier draft declared a `pkFieldName` field in the schema and passed it to
> `nativeCreateAndOpen`. Verified against `v0.5.1` source, this is wrong:
> `Doc::pk_` is a doc-level member set by `zvec_doc_set_pk`
> (`c_api.h:3450`, `doc.h:66`), keyed in the id-map by that string
> (`segment.cc:853`), and `Doc::validate_and_sanitize` only checks the PK is
> non-empty + regex-valid — it never looks for a "PK field." `CollectionSchema`
> has no PK concept (`schema.h` has no `primary` member; `field_schema_create`
> has no `is_primary` flag). So the PK belongs on the doc (`nativeInsertString`
> already takes a `pk` and calls `doc_set_pk`), never in the schema. Declaring
> one would just create a redundant ordinary column. This is caught here, at the
> schema-design step, precisely to avoid reworking it in Phase 1.

**The schema is built C-side, not Kotlin-side.** `nativeCreateAndOpen` takes
`schemaName` only and does all schema construction internally. This avoids
exporting a *second* handle type (the schema builder) in Phase 0, preserving
"only the Collection handle survives across JNI calls." The hardcoded
two-string-field schema is a *probe*, not the real schema API; Phase 1's Kotlin
`CollectionSchema` builder replaces it.

### Reference implementation sketch — `nativeCreateAndOpen`

```cpp
JNIEXPORT jlong JNICALL
Java_io_github_tzhvh_scryernext_zvec_ZvecNative_nativeCreateAndOpen(
    JNIEnv* env, jclass, jstring jpath, jstring jschemaName) {

  std::string path       = jstring_to_std(env, jpath);
  std::string schemaName = jstring_to_std(env, jschemaName);
  if (env->ExceptionCheck()) return 0;   // OOM during GetStringUTFChars

  // Schema + field RAII: freed on any exit, including the macros' throw-return.
  // Note: the PK is NOT a schema field — it's a doc property (doc_set_pk).
  zvec_collection_schema_t* schema = zvec_collection_schema_create(schemaName.c_str());
  ZVEC_CHECK_PTR(env, schema, "collection_schema_create");
  SchemaGuard schema_guard{schema};

  // Two ordinary content fields. add_field CLONES (c_api.h:2740) — caller must
  // destroy its own copy. A second field de-risks the multi-field schema path.
  for (const char* fname : {"title", "content"}) {
    FieldSchemaGuard g;
    g.f = zvec_field_schema_create(fname, ZVEC_DATA_TYPE_STRING, false, 0);
    ZVEC_CHECK_PTR(env, g.f, "field_schema_create");
    ZVEC_CHECK_JNI_JLONG(env, zvec_collection_schema_add_field(schema, g.f));
  }

  zvec_collection_t* col = nullptr;
  ZVEC_CHECK_JNI_JLONG(env,
      zvec_collection_create_and_open(path.c_str(), schema, nullptr, &col));
  return reinterpret_cast<jlong>(col);
}
```

---

## Version pinning & the bump procedure

**Strategy: pin to release tags only; never track `main`.**

The submodule is pinned to **`v0.5.1`** (commit `d25f3a4`), a named tag — not a
mid-`main` commit. zvec ships roughly monthly with minor-rev feature drops
(v0.5.0 alone added FTS + hybrid search + DiskANN) and is pre-1.0 (semver doesn't
bind). Tracking `main` would silently move the compiled `.so`'s API under the JNI
layer on every `submodule update`; pinning to tags turns "frequent releases" into
a queue we drain deliberately.

### `ZVEC_VERSION` — single source of truth

A one-line file `zvec-android/ZVEC_VERSION` containing `v0.5.1`. Gradle reads it
at configuration time and emits `BuildConfig.ZVEC_VERSION`. The instrumentation
test asserts `nativeGetVersion()` matches it (see
[Layer 2](#layer-2--instrumentation-test)), closing the loop between the recorded
version, the submodule SHA, and the actual compiled `.so`. No one can run a stale
`.so` without that test failing.

### The bump procedure (documented in `zvec-android/README.md`)

1. `git -C zvec-android/third_party/zvec fetch && git checkout <new-tag>`
2. Diff `src/include/zvec/c_api.h` against the old tag — port any signature
   changes to the JNI layer.
3. Update `zvec-android/ZVEC_VERSION` to the new tag.
4. Rerun `scripts/build_libzvec.sh arm64-v8a && scripts/build_libzvec.sh x86_64`.
5. Run `:zvec-android:connectedDebugAndroidTest` — the version test enforces
   consistency.
6. Commit submodule pointer + `ZVEC_VERSION` + any JNI changes *together*.

---

## Verification plan

Three layers, each catching a different failure class. The instrumentation test
alone is insufficient — it runs on an x86_64 emulator and proves nothing about
arm64-v8a.

### Layer 1 — packaging check (Gradle task)

A Gradle task in `zvec-android/build.gradle.kts` that unzips the AAR after
`assembleDebug` and asserts four `.so` paths exist:

```
jni/arm64-v8a/libzvec.so
jni/arm64-v8a/libzvec_jni.so
jni/x86_64/libzvec.so
jni/x86_64/libzvec_jni.so
```

This is the **only** arm64-v8a verification available without arm64 hardware or
an arm64 emulator (impractical on an x86 host). Without it, a missing or corrupt
arm64 `.so` would ship broken to real devices while Phase 0 "passed" on the
emulator. Mandatory, not optional.

### Layer 2 — instrumentation test

`zvec-android/src/androidTest/.../ZvecRoundtripTest.kt`, self-contained (runs as
`:zvec-android:connectedDebugAndroidTest`, no app module needed):

```kotlin
@RunWith(AndroidJUnit4::class)
class ZvecRoundtripTest {
    @get:Rule val ctx = ApplicationProvider.getApplicationContext<Context>()

    @Test fun versionMatchesPinnedTag() {
        // zvec native format: "0.5.1-gXXXX (built ...)" — NO "v" prefix.
        // ZVEC_VERSION stores git convention "v0.5.1" → strip it before comparing.
        val native = ZvecNative.nativeGetVersion()
        val expectedPrefix = BuildConfig.ZVEC_VERSION.removePrefix("v")
        assertTrue("native='$native' expected prefix='$expectedPrefix'",
                   native.startsWith(expectedPrefix))
    }

    @Test fun createInsertCloseRoundtrips() {
        val dir = File(ctx.cacheDir, "zvec_test_${System.nanoTime()}").apply { mkdirs() }
        val h = ZvecNative.nativeCreateAndOpen(dir.absolutePath, "screenshots")
        assertTrue("handle must be non-zero", h != 0L)
        // pk is a doc property (doc_set_pk), not a schema field — see grounding note.
        assertEquals(1, ZvecNative.nativeInsertString(h, "deadbeef", "content", "hello ocr"))
        ZvecNative.nativeClose(h)
    }
}
```

**The `removePrefix("v")` is load-bearing.** `c_api.h:56` documents
`zvec_get_version()` returns `"0.5.1-gXXXX (built ...)"` (no `v`); git tags are
`v0.5.1` (with `v`). Get this wrong and the version test fails for a purely
cosmetic reason on the first run.

### Layer 3 — crash runbook (`zvec-android/README.md`)

The instrumentation test cannot assert *what* went wrong on a native crash — a
JNI `SIGSEGV` kills the process rather than throwing a JUnit failure. A short
README section documents the expected logcat signature (`signal 11`, tombstone,
faulting library in `libzvec_jni.so` or `libzvec.so`) and the capture command:

```
./gradlew :zvec-android:connectedDebugAndroidTest
adb logcat -d | grep -A30 'tombstone\|signal 11\|libzvec'
```

---

## Toolchain & prerequisites

Documented in `zvec-android/README.md`, four sections.

| Requirement | Version / value | Why |
|---|---|---|
| Android NDK | **r30** (`30.0.14904198`) — pinned in `build.gradle.kts` | must match the NDK `build_libzvec.sh` uses; mismatched `c++_static` runtimes across `libzvec.so` / `libzvec_jni.so` are a rare, hard crash class |
| CMake | ≥ 3.26 | zvec's `CMakeLists.txt` floor |
| Ninja | any recent | default generator; Make is the fallback |
| Host C++ compiler | gcc / clang / Xcode CLT | the protoc bootstrap step needs a *host* compiler (cross-compile can't produce one) |
| `ANDROID_NDK_HOME` | env var pointing at the NDK | the script auto-detects if unset |

### Build (two-step dance)

```bash
# 1. Build the prebuilt libzvec.so for both ABIs (one-time per version bump)
cd zvec-android
bash scripts/build_libzvec.sh arm64-v8a
bash scripts/build_libzvec.sh x86_64

# 2. Build the AAR (compiles the thin JNI .so, links against prebuilt, packages both)
./gradlew :zvec-android:assembleDebug
```

The two steps are separate because zvec's host-protoc bootstrap has no clean hook
inside Gradle's per-ABI CMake (see [architecture](#architecture-at-a-glance)).

### Verify

```bash
./gradlew :zvec-android:check                    # Layer 1: packaging
./gradlew :zvec-android:connectedDebugAndroidTest # Layer 2: round-trip on x86_64 emulator
# Layer 3: if a test hangs, see the crash runbook in README.md
```

---

## Implementation order

**Build the toolchain end-to-end before writing the full JNI surface.** At
zvec's dependency scale the toolchain is the most likely source of surprises;
finding them on a 5-line JNI file is far cheaper than on a 150-line one.

1. **Module skeleton.** `settings.gradle.kts` include, `libs.versions.toml`
   `android-library` plugin, `zvec-android/build.gradle.kts`, empty
   `CMakeLists.txt`, `ZVEC_VERSION`, `consumer-rules.pro`.
2. **Port `build_libzvec.sh`.** Run it for `x86_64` first (faster to iterate, and
   it's the emulator ABI). Get a real `libzvec.so` into `jniLibs/x86_64/`.
3. **Minimal JNI: `nativeGetVersion()` only.** One `external` function, the
   `JNI_OnLoad` global-ref caching, a stub `zvec_jni_marshalling.h`, and the
   CMake `IMPORTED` target. Run the instrumentation test — just the version
   assertion. **This proves the entire chain** (submodule → script → CMake → AAR
   → `loadLibrary` → C call → string out) on the cheapest possible function.
4. **Full macro family + guards.** `ZVEC_CHECK_JNI_VOID`/`_JLONG`, `ZVEC_CHECK_PTR`,
   `DocGuard`/`FieldSchemaGuard`/`SchemaGuard`, `jstring_to_std`, `zvec_throw`.
5. **The remaining three functions.** `nativeCreateAndOpen`,
   `nativeInsertString`, `nativeClose`. Run the full round-trip test.
6. **Build `arm64-v8a`.** Rerun `build_libzvec.sh arm64-v8a`; verify the packaging
   check passes for both ABIs.
7. **README + runbook.** The four-section prerequisites doc.

If step 3 passes, the toolchain is proven and steps 4–6 are mechanical. If step 3
fails, you're debugging the build on the smallest possible surface.

---

## Handoff to Phase 1

Three properties verified against the pinned `v0.5.1` source that Phase 0
*avoids* by scope but Phase 1 must confront directly. Logged here so the handoff
is explicit, not rediscovered mid-SDK-build.

### `zvec_initialize` is process-global, once-only, and not in the Phase 0 surface

Phase 0's four functions never call `zvec_initialize` — and this is safe *for
this slice specifically*, verified by source:

- `GlobalResource::initialize()` (`config.cc:148`) is **lazy**: `std::call_once`,
  with defaults auto-derived from `CgroupUtil` (memory limit = cgroup cap, thread
  counts = CPU count). Nothing in the version/create/insert/close path triggers
  it.
- The query/optimize thread pools are touched **only on the search path**
  (`segment.cc:1656`, `collection.cc:1762`). Phase 0 doesn't search, so the
  pools are never created. `collection_close` just deletes the shared_ptr
  (`c_api.cc:4562`) — no pool, no init dependency.

**What changes in Phase 1:** the roadmap (Phase 1, capability #1) makes
`ZvecConfig`/`zvec_initialize` central — memory caps, thread limits, logging.
But `zvec_initialize` is **once per process**: a second call returns
`ZVEC_ERROR_ALREADY_EXISTS` (`c_api.cc:700`, guarded by `g_initialized`). Three
consequences for the SDK design:

1. **Where it lives.** `zvec_initialize` belongs in `JNI_OnLoad` (or a one-shot
   `nativeInit(config)` called from `Application.onCreate`), *not* in
   `createAndOpen`. Per-collection init is impossible by design.
2. **Test isolation.** The "unique path per test" strategy (roadmap Testing
   notes) is necessary but *not sufficient*: config set in test A persists for
   test B in the same process. Config-dependent tests cannot be isolated by path
   alone — they need separate processes (`@TestParameter` won't help) or
   constraint to a single canonical config.
3. **`zvec_shutdown` is deliberately absent from Phase 0.** Calling it breaks
   every later test in the process (the singleton caveat). For a production
   Android app the lifecycle answer is **never call it** — the process owns the
   library for its lifetime; `Application.onTerminate` is not reliably invoked.
   Phase 1 should expose collection `close()` (already in the slice) but *not* a
   `shutdown()`, and document why.

### The `open` (not `create`) path is unexercised

Phase 0 uses `zvec_collection_create_and_open`, which **creates a new
collection and errors if the path exists** (`collection.cc` `create()`:
`"path[", path_, "] exists"`). Reopening an existing collection is
`zvec_collection_open` (`c_api.h`, near `:2985`) — a different C function Phase
0 does not expose. Implications for Phase 1:

- The stale-`LOCK` recovery procedure (roadmap Phase 2, fundamental #2: detect
  `lock`, delete `$dbPath/LOCK`, retry) depends on the **open** path. Phase 0's
  round-trip test only ever creates fresh collections, so it *cannot* hit a
  stale LOCK — those arise only on reopen after a kill. Phase 1 must add an
  `open` JNI function and its own reopen test before Phase 2 relies on the
  recovery procedure.
- Phase 0's crash runbook (Layer 3) is the only thing that could surface a
  LOCK bug, and only by accident. This is an accepted Phase 0 gap, not a test
  to add now.

### Input-pointer copy-vs-borrow: string path confirmed, vector path open

The marshalling "eager copy" decision (Rule 1/2) is the conservative choice.
For `ZVEC_DATA_TYPE_STRING` the C layer **copies** — confirmed by source
(`c_api.cc` `doc_add_field_by_value`: `std::string val(..., value_size)`), so
eager copy is strictly redundant-but-safe there. **The vector path is not
source-confirmed** either way, and vectors are the large payload (~5 KB × N).
Phase 1 should treat vector copy-vs-borrow as a **correctness** question
(potential use-after-free if zvec borrows and the JVM array is released), not
just an optimization — discharge it per-function before relaxing eager copy on
any vector hot path.

---

## Residual risks (deliberately accepted)

These are Phase-0 scoping decisions, not oversights. Named here so implementation
doesn't mistake them for gaps to fill now.

- **arm64-v8a is never executed in Phase 0.** The packaging check (Layer 1)
  proves the `.so` *exists and is packaged*, not that it *loads and runs*. A
  genuine arm64 runtime bug (page-size flag, arm64-only code path, NDK STL
  quirk) won't surface until real hardware. Accepted because arm64 hardware isn't
  in the Phase-0 loop and the dart binding's identical `.so` is a circumstantial
  oracle. **Phase 1 should run *something* on arm64 before Phase 2 ships.**
- **Only the create path is exercised; reopen-after-crash is not.** Phase 0's
  `create_and_open` errors if the path exists, so the round-trip test can never
  produce a stale `LOCK`. The recovery procedure (Phase 2) depends on the
  `open` path, which Phase 0 doesn't expose. Accepted for Phase 0; detail and
  the Phase-1 action item are in [Handoff to Phase 1](#handoff-to-phase-1).
- **Input-pointer copy-vs-borrow: string path source-confirmed, vector path
  open.** Eager copy is unconditionally safe, so this is moot for Phase-0
  *correctness* — but it is *not* merely an optimization question. The string
  path is confirmed to copy (`c_api.cc`); the vector path is unverified, and a
  borrow there would be a use-after-free if eager copy were relaxed. Detail in
  [Handoff to Phase 1](#handoff-to-phase-1); treat as a Phase-1 correctness
  discharge, not optional tuning.

---

## Implementation notes (deviations from this plan)

The text above is the design record. The shipped code diverges from it in the
places below. §1–2 were sound design discoveries made during implementation;
§3–5 were shortcuts taken during the first build pass that weakened
verification and have since been **reverted**; §6–8 were code-quality fixes
applied during review. Recorded here so the plan and the code stop quietly
disagreeing.

### 1. `libzvec_c_api.so`, not `libzvec.so` — **intentional, correct**

Throughout this document the prebuilt engine library is called `libzvec.so`.
The shipped artifact is `libzvec_c_api.so`, and that is the right name. The
upstream CMake target emits it with `SONAME = libzvec_c_api.so` baked into the
ELF; `libzvec_jni.so` records that `SONAME` as a `DT_NEEDED` dependency. Renaming
the file to `libzvec.so` (as an earlier draft of step 3 in the prebuilt build
path prescribed) would break the `SONAME` lookup and throw `UnsatisfiedLinkError`
at `loadLibrary`. `build_libzvec.sh` therefore copies the output **without**
renaming, `ZvecNative.kt` loads `zvec_c_api` then `zvec_jni`, and the packaging
check asserts `libzvec_c_api.so`. Wherever this doc says `libzvec.so`, read
`libzvec_c_api.so`.

### 2. Version string has a `v` prefix — **intentional, correct**

The Layer-2 sketch claims `zvec_get_version()` returns `"0.5.1-gXXXX (built ...)"`
*without* a `v`, and that `removePrefix("v")` is load-bearing. That is backwards.
`zvec_get_version()` returns the raw output of `git describe --tags`
(`src/binding/c/c_api.cc:189`, composed in `src/binding/c/CMakeLists.txt:23-31`),
which is exactly `"v0.5.1"` at the tag and `"v0.5.1-N-g<sha>"` once HEAD moves
past it. The prefix is `v`, not stripped. The shipped test uses **exact equality**
against `BuildConfig.ZVEC_VERSION`, not `startsWith` — see note 4 for why.

### 3. Schema fields are NON-nullable (`false`) — **was reverted; now correct**

Decision Q8 specifies two NON-nullable string fields (`title`, `content`) so the
round-trip test exercises the engine's required-field validation. A first
implementation pass shipped them as `nullable = true`, which silently bypassed
that validation path, done only to make an incomplete test payload pass. This is
the backwards workflow — production code must not be weakened to accommodate a
lazy test. Reverted: `zvec_jni_collection.cc` now declares both fields with
`nullable = false`, and `nativeInsertString` populates *both* (`title` and
`content`), matching how real app code would write a valid doc.

### 4. Version assertion is exact equality — **was reverted; now correct**

A first implementation pass changed the version assertion from exact match to
`native.removePrefix("v").startsWith(expectedPrefix)`. This *masked* the one
failure mode the test exists to catch: the `.so` being built from a commit past
the pinned tag (`v0.5.1-2-gcb0d3db` still `startsWith` `v0.5.1`). That exact
drift went undetected and shipped a `.so` built from an unreleased commit.
Reverted to `assertEquals(BuildConfig.ZVEC_VERSION, native)`. `git describe`
appends `-N-g<sha>` the moment HEAD leaves the tag, so equality fails loudly on
any drift.

### 5. The build script now re-pins the submodule — **added after a drift bug**

`build_libzvec.sh` previously built whatever commit the submodule worktree
happened to be on — it never checked out the tag in `ZVEC_VERSION`. Combined with
the recursive `git clean` it runs to reset patches between the host-protoc and
Android-cross configures, the worktree silently landed on a post-tag commit and
the `.so` drifted (the bug note 4 was masking). The script now checks out
`$(cat ZVEC_VERSION)` and re-inits nested submodules *before* building, so the
binary and the recorded version cannot diverge. The Layer-3 bump procedure
already told humans to do this; the script now enforces it machine-side.

### 6. `nativeCreateAndOpen` now uses an `OptionsGuard` — **code-quality fix**

A first implementation freed the `collection_options_t` with four manual
`zvec_collection_options_destroy(options)` calls scattered across the function's
error branches — the exact anti-pattern the `SchemaGuard`/`FieldSchemaGuard`/
`DocGuard` RAII family exists to eliminate, but with `options` left out. It
worked only by coincidence of the macros' throw-`return` ordering: add one line
between the manual `destroy` and the `CHECK` and it would double-free or leak.
Added an `OptionsGuard` to the family and rewrote the function so every native
object is guard-owned; all four manual `destroy` calls are gone. The function
now reads top-to-bottom with no manual cleanup, and is robust to future edits.

### 7. `jstring_to_std` now throws on OOM — **code-quality fix (correctness)**

The header doc-comment claimed that on `GetStringUTFChars` returning null (JVM
OOM) the helper would surface it to the caller's `ExceptionCheck`. It did not —
it silently returned `""`, and `GetStringUTFChars` is *not* required to leave a
pending exception on null. The result: an OOM-on-string-decode could pass an
empty string to zvec as if it were real input (e.g. a blank PK or field value),
which is a latent data-integrity bug. Fixed: on null the helper now throws
`OutOfMemoryError` (only if no exception is already pending, to avoid masking
the original cause) and returns `""`. Callers' existing
`if (env->ExceptionCheck()) return 0;` guards now correctly observe it.

### 8. Build script has no per-user paths — **code-quality fix (portability)**

`build_libzvec.sh` and the README previously hardcoded `/home/mser/Android/Sdk`
as an NDK fallback. That fails opaquely for every other contributor. Replaced
with a `resolve_sdk_dir` helper that reads `local.properties` →
`ANDROID_SDK_ROOT` → `ANDROID_HOME`, and a hard, explained error if none resolve.
No contributor's username lives in the build.

### Test harness

The Layer-2 sketch uses `@RunWith(AndroidJUnit4::class)` +
`ApplicationProvider.getApplicationContext()`. The shipped test uses exactly that
(via `androidx.test:core` + `androidx.test.ext:junit`, added to
`libs.versions.toml`). A first pass had swapped these for the lower-level
`JUnit4` + `InstrumentationRegistry` to avoid adding catalog entries; restored to
the idiomatic form so the module's test code matches the rest of the project.

---

## Latent risks & known smells (Phase 1 pre-work)

Items below are *not* Phase 0 bugs — the exit gate passes and the round-trip
runs on arm64. They are smells flagged in code review that Phase 1 should
confront before building the full SDK on top of this layer. Named here so they
are not rediscovered mid-SDK-build.

- **The error-path macros' return-type "wart".** `ZVEC_CHECK_JNI_JLONG` and
  `ZVEC_CHECK_PTR` expand to `return 0`. That is correct for `jlong`-returning
  functions and happens to convert to `jint` (`nativeInsertString`) and `void`
  (`nativeClose`, via `ZVEC_CHECK_JNI_VOID`) — but the macro *name* asserts a
  return type the call site may not have. A future change to a function's return
  type could silently break the macro. The plan acknowledged this; Phase 1
  should consider templating the macros on return type, or generating one macro
  per JNI function signature, rather than relying on `0` converting.

- **`ZvecException` exposes `code` but not the raw message.** The constructor
  embeds the message in a formatted string (`"zvec error $code: $message"`) but
  does not store it as a retrievable field. Phase 1 callers that want structured
  error handling (e.g. map a specific `code` to a retry, but still log the
  message) cannot get at it without string-parsing the message. Add a `val
  detailMessage` field.

- **`build_libzvec.sh` runs the host-protoc configure and the Android-cross
  configure against the *same* zvec source tree.** The script's recursive
  `git clean` resets patches between them, but the two configure passes want
  *different* patches (host: `arrow.patch`; Android: `arrow.android.patch`). This
  is fragile: a nested submodule whose marker file survives the clean (e.g. an
  `ignore = all` entry in zvec's `.gitmodules`) will cause the second configure
  to fail on a patch-does-not-apply. It works today because the recursive clean
  catches every nested tree, but it is a single `gitmodules ignore` change in
  upstream zvec away from breaking. Phase 1 should consider out-of-tree host and
  Android build dirs with the patches applied per-tree, or pin the patch step to
  a copy.

- **Vector copy-vs-borrow is unverified (carried from the Handoff).** Phase 0
  only exercises the STRING value path, which `c_api.cc` confirms copies. The
  large-payload VECTOR path is unverified either way; eager copy is safe but
  potentially wasteful, and relaxing it without per-function confirmation would
  be a use-after-free. This remains the Phase-1 correctness discharge noted in
  [Handoff to Phase 1](#handoff-to-phase-1).

- **16KB-page alignment is set but unverified on a 16KB device.** The
  `-Wl,-z,max-page-size=16384` flag is applied to both `.so`s, but the arm64
  device used in Phase 0 is API 31 (4KB pages), so the flag was not load-bearing
  in the test. It must be re-verified on a real Android 15+ (16KB-page) device
  before Phase 2 ships — a missing flag there is an instant startup crash. This
  is a residual risk carried from the plan; recorded here so it is not dropped.

- **`arm64-v8a` is no longer an *untested* ABI.** The original residual risk
  ("arm64 is never executed in Phase 0") is **discharged**: the round-trip test
  ran on a real arm64-v8a (API 31) device and passed with no native crash. Only
  the 16KB-page concern above remains for arm64.

---

## Decision log

Each row was reached by grilling and grounded in the code (zvec `c_api.h`, the
rust/dart/go/node bindings, the project's existing Gradle conventions).

| # | Decision | Rationale (one line) |
|---|---|---|
| Q1 | Prebuilt `libzvec.so` + thin JNI (dart path) | host-protoc bootstrap is impossible inside Gradle; dart binding is the only Android-proven model |
| Q2 | zvec as git submodule at `zvec-android/third_party/zvec` | source-reproducible, auditable SHA; references real header during JNI dev |
| Q3 | Port the dart `build_android.sh`; ABIs = arm64-v8a + x86_64 | upstream's script is a test harness, not an artifact producer; minSdk 29 makes 32-bit irrelevant |
| Q4 | Level 2 scope (version probe + one vertical slice) | Level 1 (probe only) de-risks nothing about JNI patterns; Level 3 (full surface) front-loads Phase-1 design |
| Q5 | Macro family (`_VOID`/`_JLONG`/`_PTR`) + unchecked `ZvecException`; `zvec_free` for last-error | macro bundles throw+return, preventing fall-through with pending exception; `zvec_free` per header |
| Q6 | `jlong` handle + Kotlin `Closeable`; docs never escape | one handle per collection = the guardable case; `Cleaner` needs API 33+ and a deprecated fallback |
| Q7 | Eager copy for all inbound marshalling | unconditionally safe vs zvec's unverified copy-vs-borrow; RAII pin reintroduces the OOM-path crash the macro prevents |
| Q8 | Four native fns + two string fields + guards | smallest surface exercising every JNI idiom; second field de-risks multi-field schema path |
| Q9 | `IMPORTED` CMake target, `abiFilters`, `consumerProguardFiles`, nested package | canonical prebuilt-lib pattern; ABI enforcement; R8-safe; collision-free JNI symbols |
| Q10 | Pin to release tags (v0.5.1) + `ZVEC_VERSION` + bump procedure | turns frequent upstream releases into a deliberate queue; test enforces consistency |
| Q11 | Packaging check + instrumentation test + crash runbook | three layers cover build/packaging, x86_64 runtime, and arm64-presence (the only arm64 check available) |
| Q12 | Ninja (Make fallback) + NDK r30 pin + four-section README | upstream's generator, faster at scale; NDK pin prevents cross-boundary STL mismatch |
