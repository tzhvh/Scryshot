#pragma once

#include <jni.h>
#include <string>
#include <zvec/c_api.h>

// Global refs cached in JNI_OnLoad
extern jclass g_zvec_exception_class;
extern jmethodID g_zvec_exception_ctor;

inline void zvec_throw(JNIEnv* env, zvec_error_code_t code, const char* msg) {
    if (env->ExceptionCheck()) {
        return; // already has a pending exception
    }
    jstring jmsg = env->NewStringUTF(msg);
    if (!jmsg) {
        return; // OOM on JVM side
    }
    jobject exception = env->NewObject(g_zvec_exception_class, g_zvec_exception_ctor, (jint)code, jmsg);
    if (exception) {
        env->Throw(static_cast<jthrowable>(exception));
    }
}

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

#define ZVEC_CHECK_JNI_VOID(env, call)                                    \
  do {                                                                    \
    zvec_error_code_t _c = (call);                                        \
    if (_c != ZVEC_OK) {                                                  \
      char* _msg = nullptr;                                               \
      zvec_get_last_error(&_msg);                                         \
      std::string _s = _msg ? _msg : zvec_code_label(_c);                 \
      if (_msg) zvec_free(_msg);                                          \
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

// jint-returning variant of the error-check family. Each macro hard-codes the
// zero-value of its return type so the throw and the return stay bundled (a
// wrapper can't fall through into more JNI with a pending exception). Match the
// macro to the enclosing function's return type — using _JLONG in a jint fn
// compiles today only because 0 converts, and would mask a future type change.
#define ZVEC_CHECK_JNI_JINT(env, call)                                    \
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

#define ZVEC_CHECK_PTR(env, ptr, what)                                    \
  do {                                                                    \
    if (!(ptr)) {                                                         \
      zvec_throw(env, ZVEC_ERROR_INTERNAL_ERROR,                          \
                 (std::string("zvec ") + what + " returned null").c_str());\
      return 0;                                                           \
    }                                                                     \
  } while (0)

// Eagerly copies a jstring into a std::string, releasing the JNI chars in this
// helper's scope before the caller touches zvec (see marshalling Rule 1: copy,
// don't RAII-pin the JVM array — zvec's copy-vs-borrow is per-function).
//
// Two failure guards, both load-bearing:
//  - `env->ExceptionCheck()` at entry: callers chain several jstring_to_std calls
//    before their own ExceptionCheck. If call N-1 failed and left an exception
//    pending, calling GetStringUTFChars here is JNI undefined behavior (only an
//    exception allowlist is legal with one pending). Bail before touching JNI.
//  - On GetStringUTFChars failure (JVM OOM) we throw OutOfMemoryError so the
//    caller's ExceptionCheck guard observes a pending exception. Returning ""
//    silently (an earlier version) would let a null-on-OOM path slip through and
//    feed an empty string to zvec as if it were real input.
inline std::string jstring_to_std(JNIEnv* env, jstring js) {
    if (!js || env->ExceptionCheck()) return "";
    const char* chars = env->GetStringUTFChars(js, nullptr);
    if (!chars) {
        if (!env->ExceptionCheck()) {
            jclass oom = env->FindClass("java/lang/OutOfMemoryError");
            if (oom) {
                env->ThrowNew(oom, "GetStringUTFChars returned null");
                env->DeleteLocalRef(oom);
            }
        }
        return "";
    }
    std::string s(chars);
    env->ReleaseStringUTFChars(js, chars);
    return s;
}

struct SchemaGuard {
    zvec_collection_schema_t* s = nullptr;
    ~SchemaGuard() {
        if (s) {
            zvec_collection_schema_destroy(s);
        }
    }
};

struct FieldSchemaGuard {
    zvec_field_schema_t* f = nullptr;
    ~FieldSchemaGuard() {
        if (f) {
            zvec_field_schema_destroy(f);
        }
    }
};

struct DocGuard {
    zvec_doc_t* d = nullptr;
    ~DocGuard() {
        if (d) {
            zvec_doc_destroy(d);
        }
    }
};

// Issue 04: wraps the per-doc result array the `_with_results` DML variants
// return (zvec_collection_insert/update/upsert/delete_with_results). The array
// is calloc'd by build_write_results (c_api.cc:846), with each slot's `message`
// a separate copy_string allocation; the whole thing is freed in one call to
// zvec_write_results_free(results, result_count). The guard runs on ANY exit
// path — including a macro throw-return after the array is populated but before
// we finish copying the per-doc messages out — so a result array can never leak.
struct WriteResultsGuard {
    zvec_write_result_t* results = nullptr;
    size_t count = 0;
    ~WriteResultsGuard() {
        if (results) {
            zvec_write_results_free(results, count);
        }
    }
};

// OptionsGuard closes the RAII gap that previously left options cleanup scattered
// across 4 manual destroy-calls in nativeCreateAndOpen's error branches. Like the
// other guards it runs on any exit path, including a macro throw-return.
struct OptionsGuard {
    zvec_collection_options_t* o = nullptr;
    ~OptionsGuard() {
        if (o) {
            zvec_collection_options_destroy(o);
        }
    }
};

// Config init guards (issue 01). config_data_create / config_log_create_file
// are paired with destroy; these run on any exit path, including a macro
// throw-return from one of the config_data_set_* setters below.
struct ConfigDataGuard {
    zvec_config_data_t* c = nullptr;
    ~ConfigDataGuard() {
        if (c) {
            zvec_config_data_destroy(c);
        }
    }
};

struct LogConfigGuard {
    zvec_log_config_t* l = nullptr;
    ~LogConfigGuard() {
        if (l) {
            zvec_config_log_destroy(l);
        }
    }
};

// Issue 02: schema-construction guards. Schema construction creates the deepest
// ownership chain in the C API (field → borrowed index params, schema → borrowed
// field). These guards free the per-field index-params handle and the FTS filter
// string-array on any exit path — including a macro throw-return from
// zvec_field_schema_set_index_params or schema_add_field. The Rust oracle marks
// these `owned: true` and frees them in Drop; we free them in the guard dtor so a
// single `return` from the build loop can't leak one.
struct IndexParamsGuard {
    zvec_index_params_t* p = nullptr;
    ~IndexParamsGuard() {
        if (p) {
            zvec_index_params_destroy(p);
        }
    }
};

// zvec_string_array_t for the FTS filters. set_fts_params does NOT adopt the
// array (it copies), so this guard always frees it — on success and on failure.
struct FtsStringArrayGuard {
    zvec_string_array_t* a = nullptr;
    ~FtsStringArrayGuard() {
        if (a) {
            zvec_string_array_destroy(a);
        }
    }
};

// ---- Shared index-params builder (issue 02 + issue 10) ---------------------
// Index-kind discriminator values (mirror SchemaDescriptor::IndexKind). Pinned
// explicit so a Kotlin reorder can't silently drift the int the C++ switches on.
constexpr int INDEX_KIND_NONE = 0;
constexpr int INDEX_KIND_HNSW = 1;
constexpr int INDEX_KIND_IVF = 2;
constexpr int INDEX_KIND_FLAT = 3;
constexpr int INDEX_KIND_INVERT = 10;
constexpr int INDEX_KIND_FTS = 11;

// Build one field's index-params handle from the descriptor arrays. Returns null
// when the field has no index (INDEX_KIND_NONE). On failure the caller's pending
// exception surfaces (set via zvec_throw by the setters); the returned handle,
// if any, is guard-owned by the caller (IndexParamsGuard). fts_filter_names is
// the flat-packed filter list; fts_base is this field's offset into it (the
// cumulative count of filters from prior FTS fields — 0 for the single-field DDL
// path, which has exactly one field).
//
// SHARED by schema construction (zvec_jni_schema.cc's per-field loop) and the
// runtime DDL surface (zvec_jni_collection.cc's nativeCreateIndex): both build
// the SAME one-field index-params handle, so the per-arm switch + its exact
// byte-size setter contract (metric for vector indexes; m/ef for HNSW;
// n_list/n_iters for IVF; the zvec_string_array filter build for FTS) lives in
// exactly one place. `inline` so each TU that includes this header gets its own
// definition without ODR violation; the body is pure (no statics).
inline zvec_index_params_t* build_index_params(
    JNIEnv* env,
    int index_kind,
    jintArray jindexM, jintArray jindexEfConstruction,
    jintArray jindexNList, jintArray jindexNIters,
    jintArray jindexMetric,
    jbooleanArray jindexEnableRangeOpt,
    jobjectArray jftsTokenizer, jobjectArray jftsExtraParams,
    jobjectArray jftsFilterNames, jintArray jftsFilterFieldIndices,
    int field_index, int fts_base) {

    if (index_kind == INDEX_KIND_NONE) return nullptr;

    jint buf_m = 0, buf_ef = 0, buf_nl = 0, buf_ni = 0, buf_metric = 0;
    jboolean buf_range = JNI_FALSE;
    env->GetIntArrayRegion(jindexM, field_index, 1, &buf_m);
    env->GetIntArrayRegion(jindexEfConstruction, field_index, 1, &buf_ef);
    env->GetIntArrayRegion(jindexNList, field_index, 1, &buf_nl);
    env->GetIntArrayRegion(jindexNIters, field_index, 1, &buf_ni);
    env->GetIntArrayRegion(jindexMetric, field_index, 1, &buf_metric);
    env->GetBooleanArrayRegion(jindexEnableRangeOpt, field_index, 1, &buf_range);
    if (env->ExceptionCheck()) return nullptr;

    zvec_index_type_t ctype;
    switch (index_kind) {
        case INDEX_KIND_HNSW:   ctype = ZVEC_INDEX_TYPE_HNSW;   break;
        case INDEX_KIND_IVF:    ctype = ZVEC_INDEX_TYPE_IVF;    break;
        case INDEX_KIND_FLAT:   ctype = ZVEC_INDEX_TYPE_FLAT;   break;
        case INDEX_KIND_INVERT: ctype = ZVEC_INDEX_TYPE_INVERT; break;
        case INDEX_KIND_FTS:    ctype = ZVEC_INDEX_TYPE_FTS;    break;
        default: return nullptr;
    }

    zvec_index_params_t* params = zvec_index_params_create(ctype);
    ZVEC_CHECK_PTR(env, params, "index_params_create");

    switch (index_kind) {
        case INDEX_KIND_HNSW:
        case INDEX_KIND_IVF:
        case INDEX_KIND_FLAT:
            // Vector indexes carry a metric; HNSW/IVF add their own scalars.
            {
                zvec_error_code_t c = zvec_index_params_set_metric_type(
                    params, static_cast<zvec_metric_type_t>(buf_metric));
                if (c != ZVEC_OK) { zvec_index_params_destroy(params); ZVEC_CHECK_JNI_JLONG(env, c); }
            }
            if (index_kind == INDEX_KIND_HNSW) {
                zvec_error_code_t c = zvec_index_params_set_hnsw_params(params, buf_m, buf_ef);
                if (c != ZVEC_OK) { zvec_index_params_destroy(params); ZVEC_CHECK_JNI_JLONG(env, c); }
            } else if (index_kind == INDEX_KIND_IVF) {
                zvec_error_code_t c = zvec_index_params_set_ivf_params(params, buf_nl, buf_ni, /*use_soar=*/false);
                if (c != ZVEC_OK) { zvec_index_params_destroy(params); ZVEC_CHECK_JNI_JLONG(env, c); }
            }
            break;
        case INDEX_KIND_INVERT: {
            zvec_error_code_t c = zvec_index_params_set_invert_params(
                params, buf_range == JNI_TRUE, /*enable_wildcard=*/false);
            if (c != ZVEC_OK) { zvec_index_params_destroy(params); ZVEC_CHECK_JNI_JLONG(env, c); }
            break;
        }
        case INDEX_KIND_FTS: {
            // Eagerly copy the per-field strings before touching zvec (Rule 1).
            jstring jtok = static_cast<jstring>(env->GetObjectArrayElement(jftsTokenizer, field_index));
            if (env->ExceptionCheck()) { zvec_index_params_destroy(params); return nullptr; }
            std::string tokenizer = jstring_to_std(env, jtok);
            env->DeleteLocalRef(jtok);
            if (env->ExceptionCheck()) { zvec_index_params_destroy(params); return nullptr; }

            jstring jextra = static_cast<jstring>(env->GetObjectArrayElement(jftsExtraParams, field_index));
            if (env->ExceptionCheck()) { zvec_index_params_destroy(params); return nullptr; }
            std::string extra = jstring_to_std(env, jextra);
            env->DeleteLocalRef(jextra);
            if (env->ExceptionCheck()) { zvec_index_params_destroy(params); return nullptr; }

            // This field's filters: fts_base consecutive entries in ftsFilterNames.
            jint this_filter_count = 0;
            env->GetIntArrayRegion(jftsFilterFieldIndices, field_index, 1, &this_filter_count);
            if (env->ExceptionCheck()) { zvec_index_params_destroy(params); return nullptr; }

            zvec_string_array_t* filter_arr = nullptr;
            if (this_filter_count > 0) {
                filter_arr = zvec_string_array_create(static_cast<size_t>(this_filter_count));
                ZVEC_CHECK_PTR(env, filter_arr, "string_array_create");
            }
            FtsStringArrayGuard filter_guard{filter_arr};

            for (jint k = 0; k < this_filter_count; k++) {
                jstring jf = static_cast<jstring>(
                    env->GetObjectArrayElement(jftsFilterNames, fts_base + k));
                if (env->ExceptionCheck()) return nullptr;
                std::string f = jstring_to_std(env, jf);
                env->DeleteLocalRef(jf);
                if (env->ExceptionCheck()) return nullptr;
                zvec_string_array_add(filter_arr, static_cast<size_t>(k), f.c_str());
            }

            // Empty strings = "keep C default" → pass NULL (matches the Rust oracle's
            // Option<&str> → null mapping).
            const char* tok_c = tokenizer.empty() ? nullptr : tokenizer.c_str();
            const char* extra_c = extra.empty() ? nullptr : extra.c_str();
            zvec_error_code_t c = zvec_index_params_set_fts_params(
                params, tok_c, filter_arr, extra_c);
            if (c != ZVEC_OK) { zvec_index_params_destroy(params); ZVEC_CHECK_JNI_JLONG(env, c); }
            // set_fts_params copies; filter_guard frees the array regardless of outcome.
            break;
        }
    }
    return params;
}

// Issue 05 (fetch/projection): wraps the collection schema returned by
// zvec_collection_get_schema (c_api.h:3026). The schema handle is owned by the
// caller and must be destroyed; the per-field handles it exposes
// (zvec_collection_schema_get_field, c_api.h:2782) are NON-owning borrows into
// it, so they are valid only while this guard's schema is alive. Runs on any
// exit path, including a macro throw-return mid-extraction.
//
// Reused verbatim by issue 06 (query results): the per-type read path resolves
// each field's data_type from this schema, because there is no
// zvec_doc_get_field_type — the read getters (zvec_doc_get_field_value_basic /
// _pointer) require a caller-supplied data_type per field.
struct CollectionSchemaGuard {
    zvec_collection_schema_t* s = nullptr;
    ~CollectionSchemaGuard() {
        if (s) {
            zvec_collection_schema_destroy(s);
        }
    }
};

// Issue 06 (query): the vector-query handle built/configured/run inside one
// internal block of nativeQuery (Q7 — the caller never sees a query handle, even
// transiently). zvec_vector_query_create / zvec_vector_query_destroy are paired;
// the guard runs on any exit path, including a macro throw-return from any of the
// set_field_name / set_topk / set_output_fields / set_filter / set_query_vector /
// set_fts setters below the create. (zvec_collection_query takes it as `const`,
// so it does NOT adopt it — the guard always owns it.)
//
// This same handle serves BOTH the pure-vector and pure-FTS query modalities:
// there is no separate FTS query handle in the C API. The FTS payload is built
// separately and attached via zvec_vector_query_set_fts (which COPIES the
// payload), so the FtsGuard below still owns and frees it.
struct VectorQueryGuard {
    zvec_vector_query_t* q = nullptr;
    ~VectorQueryGuard() {
        if (q) {
            zvec_vector_query_destroy(q);
        }
    }
};

// Issue 06 (query): the FTS payload (zvec_fts_t) carrying the match string.
// zvec_vector_query_set_fts COPIES the payload into the query (c_api.h:1876
// doc-comment: "payload is copied"), so the fts handle is NOT adopted and must
// be destroyed by the caller — this guard frees it on any exit path, success or
// failure, exactly like the Rust oracle's Fts Drop (which always calls
// zvec_fts_destroy).
struct FtsGuard {
    zvec_fts_t* f = nullptr;
    ~FtsGuard() {
        if (f) {
            zvec_fts_destroy(f);
        }
    }
};

// Issue 07 (hybrid search): the multi-query fusion handle. Built, configured with
// the reranker / topK / filter / output_fields, fed each leg's sub-query, run via
// zvec_collection_multi_query, then freed — all inside one internal block of
// nativeMultiQuery (Q7 — the caller never sees a multi-query handle). zvec takes
// it as `const` (c_api.h:3314), so the collection does NOT adopt it; this guard
// always owns + frees it on any exit path, including a macro throw-return from
// any of the set_rerank_* / set_topk / set_filter / set_output_fields / add_sub
// setters below the create.
struct MultiQueryGuard {
    zvec_multi_query_t* q = nullptr;
    ~MultiQueryGuard() {
        if (q) {
            zvec_multi_query_destroy(q);
        }
    }
};

// Issue 07 (hybrid search): one query leg's sub-query handle. CRITICAL OWNERSHIP
// NOTE: zvec_multi_query_add_sub_query COPIES the sub-query (c_api.h:2144:
// "sub_query to add (copied, caller retains ownership)") — it does NOT adopt it.
// Both reference bindings confirm this: the Rust oracle passes `&SubQuery`
// (multi_query.rs:44) and the SubQuery owns its own Drop (multi_query.rs:121);
// the Go oracle's test asserts `sub.handle` stays valid after AddSubQuery
// (multi_query_test.go:122). So this guard ALWAYS frees the sub-query on any
// exit path — success OR failure — exactly like the FtsGuard (issue 06). The
// issue's "adopt-on-success" JNI note was a misread of the C ownership; this is
// the corrected pattern.
struct SubQueryGuard {
    zvec_sub_query_t* s = nullptr;
    ~SubQueryGuard() {
        if (s) {
            zvec_sub_query_destroy(s);
        }
    }
};

// Issue 09 (stats): wraps the `zvec_collection_stats_t*` returned by
// zvec_collection_get_stats (c_api.h:3048). The handle is caller-owned and MUST
// be freed with zvec_collection_stats_destroy (c_api.h:3056); the per-index
// (name, completeness) pairs it exposes via the `stats_get_index_*` getters are
// borrowed into it (the index name is "owned by stats, do not free",
// c_api.h:2492), so they are valid only while this guard's stats is alive — the
// JNI layer COPIES name + completeness into Kotlin objects before the dtor runs.
// Runs on any exit path, including a macro throw-return mid-extraction, so the
// stats handle can never leak.
struct StatsGuard {
    zvec_collection_stats_t* s = nullptr;
    ~StatsGuard() {
        if (s) {
            zvec_collection_stats_destroy(s);
        }
    }
};

