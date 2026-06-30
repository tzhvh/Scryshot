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

