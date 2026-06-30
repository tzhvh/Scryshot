#include <jni.h>
#include <cstdint>
#include <cstring>
#include <string>
#include <vector>
#include <zvec/c_api.h>
#include "zvec_jni_marshalling.h"

// Issue 03 (single-doc write path) + issue 04 (bulk write path + per-doc
// results). The single-doc and bulk paths share one doc-build routine so the
// load-bearing per-field kind-switch (and its exact per-type byte-size contract)
// lives in exactly one place — a new field type added later extends one switch,
// not two.
//
// The byte-size contract is load-bearing: extract_scalar_value<T> (c_api.cc:2762)
// rejects anything but value_size == sizeof(T), and extract_vector_values<T>
// (c_api.cc:2775) requires value_size % sizeof(T) == 0. So BOOL is 1 byte
// (sizeof(bool)), not a JVM int; INT64 is 8; FLOAT/INT32 are 4; vectors are
// element_count * sizeof(element). Getting any of these wrong is an
// INVALID_ARGUMENT from the engine, silently dropped field.

namespace {

// Field-kind discriminator values (mirror DocDescriptor::Kind). Pinned explicit
// so a Kotlin reorder can't silently drift the int C++ switches on.
constexpr int KIND_STRING = 0;
constexpr int KIND_INT64 = 1;
constexpr int KIND_INT32 = 2;
constexpr int KIND_FLOAT64 = 3;
constexpr int KIND_FLOAT32 = 4;
constexpr int KIND_BOOL = 5;
constexpr int KIND_VEC_F32 = 6;
constexpr int KIND_VEC_F16 = 7;
constexpr int KIND_VEC_I8 = 8;
constexpr int KIND_NULL = 9;

// zvec_data_type_t (c_api.h:788) values the read path dispatches on. Pinned
// explicit (not the enum) so a header reorder can never silently drift the int
// the per-field type-switch keys on. Mirrors FieldType.toNative() in Kotlin.
constexpr zvec_data_type_t DT_BOOL = 3;
constexpr zvec_data_type_t DT_INT32 = 4;
constexpr zvec_data_type_t DT_INT64 = 5;
constexpr zvec_data_type_t DT_UINT32 = 6;
constexpr zvec_data_type_t DT_UINT64 = 7;
constexpr zvec_data_type_t DT_FLOAT = 8;
constexpr zvec_data_type_t DT_DOUBLE = 9;
constexpr zvec_data_type_t DT_STRING = 2;
constexpr zvec_data_type_t DT_VECTOR_FP16 = 22;
constexpr zvec_data_type_t DT_VECTOR_FP32 = 23;
constexpr zvec_data_type_t DT_VECTOR_INT8 = 26;

// Write op selector (mirror ZvecCollection's call sites). Insert / upsert /
// update select different C _with_results variants over the same doc-build path.
constexpr int OP_INSERT = 0;
constexpr int OP_UPSERT = 1;
constexpr int OP_UPDATE = 2;

// Run the selected single-doc write via the `_with_results` variant and surface
// the per-doc code. Returns the C per-doc code (ZVEC_OK on success); on a
// non-OK code throws ZvecException(code, message) BEFORE returning. The bare
// zvec_collection_insert/upsert return ZVEC_OK even on per-doc failure (they
// only bump error_count), so the `_with_results` variant is mandatory to get
// the real ALREADY_EXISTS / NOT_FOUND / … code the SDK contract promises.
//
// Used only by the single-doc path (nativeDocWrite), whose contract is THROW on
// any per-doc failure. The bulk path (nativeDocWriteBulk) does NOT throw on a
// per-doc failure — it returns the per-doc results — so it dispatches + collects
// directly rather than calling this.
//
// `message` is an out-param: the per-doc message is allocation-owned (copy_string
// in build_write_results, c_api.cc:870) and freed with zvec_write_results_free;
// we copy it into a std::string before the free.
zvec_error_code_t run_with_results(
    JNIEnv* env, zvec_collection_t* col,
    int op, const zvec_doc_t* doc, std::string& message) {
  const zvec_doc_t* docs[1] = {doc};
  zvec_write_result_t* results = nullptr;
  size_t result_count = 0;

  zvec_error_code_t api_code =
      (op == OP_UPSERT)
          ? zvec_collection_upsert_with_results(col, docs, 1, &results, &result_count)
          : zvec_collection_insert_with_results(col, docs, 1, &results, &result_count);

  // An API-level non-OK is an engine/argument failure (not a per-doc one);
  // surface it with zvec's last_error. results is unallocated in that case.
  if (api_code != ZVEC_OK) {
    if (results) zvec_write_results_free(results, result_count);
    char* msg = nullptr;
    zvec_get_last_error(&msg);
    std::string s = msg ? msg : zvec_code_label(api_code);
    if (msg) zvec_free(msg);
    zvec_throw(env, api_code, s.c_str());
    return api_code;
  }

  zvec_error_code_t per_doc_code = ZVEC_OK;
  if (result_count > 0 && results) {
    per_doc_code = results[0].code;
    if (results[0].message) message = results[0].message;
  }
  zvec_write_results_free(results, result_count);

  if (per_doc_code != ZVEC_OK) {
    // The per-doc message is a real reason ("already exists", …); fall back to
    // the code label if the engine set none, then to zvec_get_last_error.
    if (message.empty()) {
      char* msg = nullptr;
      zvec_get_last_error(&msg);
      if (msg) { message = msg; zvec_free(msg); }
    }
    if (message.empty()) message = zvec_code_label(per_doc_code);
    zvec_throw(env, per_doc_code, message.c_str());
  }
  return per_doc_code;
}

// Build ONE zvec_doc_t from a single doc's descriptor slice (the per-field
// kind-switch, faithful to the issue-03 single-doc path). Returns an OWNED doc
// the caller must zvec_doc_destroy; returns null with a pending exception set on
// any failure (the internal DocGuard frees the partially-built doc first).
//
// The eager-copy discipline (Rule 1: copy before touching zvec, no critical-
// array pinning) and the exact per-type byte-size contract are identical to the
// single-doc path — this is a mechanical extraction so the bulk path cannot
// drift from the proven single-doc field handling. The macros
// (ZVEC_CHECK_JNI_JINT / ZVEC_CHECK_PTR) all `return 0;`, which in a
// zvec_doc_t*-returning function is `return nullptr;` — the guard then frees the
// partial doc, so a mid-build failure leaks nothing.
zvec_doc_t* build_doc(
    JNIEnv* env,
    const std::string& pk, jint n,
    jobjectArray jfieldNames, jintArray jkinds, jintArray jscalarIndex,
    jbooleanArray jisNull,
    jlongArray jlongs, jdoubleArray jdoubles, jbooleanArray jbools,
    jobjectArray jstrings,
    jobjectArray jf32Vecs, jobjectArray jf16Vecs, jobjectArray ji8Vecs) {
  if (n < 0) {
    zvec_throw(env, ZVEC_ERROR_INVALID_ARGUMENT, "Negative field count");
    return nullptr;
  }

  // Bulk-copy the per-type value arrays once (eager copy, Rule 1: copy before
  // touching zvec, release before the doc build). The fixed scalars use
  // Get*Region into sized buffers; vectors fetch per-field below.
  std::vector<jint> kinds(n), scalarIndex(n);
  std::vector<jboolean> isNull(n);
  if (n > 0) {
    env->GetIntArrayRegion(jkinds, 0, n, kinds.data());
    env->GetIntArrayRegion(jscalarIndex, 0, n, scalarIndex.data());
    env->GetBooleanArrayRegion(jisNull, 0, n, isNull.data());
    if (env->ExceptionCheck()) return nullptr;
  }

  jsize nLong = jlongs ? env->GetArrayLength(jlongs) : 0;
  jsize nDouble = jdoubles ? env->GetArrayLength(jdoubles) : 0;
  jsize nBool = jbools ? env->GetArrayLength(jbools) : 0;
  std::vector<jlong> longs(nLong);
  std::vector<jdouble> doubles(nDouble);
  std::vector<jboolean> bools(nBool);
  if (nLong > 0) env->GetLongArrayRegion(jlongs, 0, nLong, longs.data());
  if (nDouble > 0) env->GetDoubleArrayRegion(jdoubles, 0, nDouble, doubles.data());
  if (nBool > 0) env->GetBooleanArrayRegion(jbools, 0, nBool, bools.data());
  if (env->ExceptionCheck()) return nullptr;

  zvec_doc_t* doc = zvec_doc_create();
  ZVEC_CHECK_PTR(env, doc, "doc_create");
  DocGuard doc_guard{doc};

  zvec_doc_set_pk(doc, pk.c_str());

  // One linear pass: per field, read its name + value slot, dispatch by kind.
  // Eager copy of each jstring/vector before the zvec call (Rule 1); vector
  // arrays are released right after the copy. No GetPrimitiveArrayCritical —
  // vectors go through Get<Type>ArrayElements (the marshalling Rule 1/2 pin).
  for (jint i = 0; i < n; i++) {
    jstring jname = static_cast<jstring>(env->GetObjectArrayElement(jfieldNames, i));
    if (env->ExceptionCheck()) return nullptr;
    std::string name = jstring_to_std(env, jname);
    env->DeleteLocalRef(jname);
    if (env->ExceptionCheck()) return nullptr;

    jint kind = kinds[i];
    jint idx = scalarIndex[i];

    if (isNull[i] == JNI_TRUE || kind == KIND_NULL) {
      ZVEC_CHECK_JNI_JINT(env, zvec_doc_set_field_null(doc, name.c_str()));
      continue;
    }

    switch (kind) {
      case KIND_STRING: {
        jstring jv = static_cast<jstring>(env->GetObjectArrayElement(jstrings, idx));
        if (env->ExceptionCheck()) return nullptr;
        std::string v = jstring_to_std(env, jv);
        env->DeleteLocalRef(jv);
        if (env->ExceptionCheck()) return nullptr;
        // value_size = UTF-8 BYTE length (c_api.cc:2936 constructs the string
        // from (ptr, size)), not the JVM char count.
        ZVEC_CHECK_JNI_JINT(env,
            zvec_doc_add_field_by_value(doc, name.c_str(), ZVEC_DATA_TYPE_STRING,
                                        v.c_str(), v.size()));
        break;
      }
      case KIND_INT64: {
        int64_t v = static_cast<int64_t>(longs[idx]);
        ZVEC_CHECK_JNI_JINT(env,
            zvec_doc_add_field_by_value(doc, name.c_str(), ZVEC_DATA_TYPE_INT64,
                                        &v, sizeof(int64_t)));
        break;
      }
      case KIND_INT32: {
        int32_t v = static_cast<int32_t>(longs[idx]);
        ZVEC_CHECK_JNI_JINT(env,
            zvec_doc_add_field_by_value(doc, name.c_str(), ZVEC_DATA_TYPE_INT32,
                                        &v, sizeof(int32_t)));
        break;
      }
      case KIND_FLOAT64: {
        double v = doubles[idx];
        ZVEC_CHECK_JNI_JINT(env,
            zvec_doc_add_field_by_value(doc, name.c_str(), ZVEC_DATA_TYPE_DOUBLE,
                                        &v, sizeof(double)));
        break;
      }
      case KIND_FLOAT32: {
        float v = static_cast<float>(doubles[idx]);
        ZVEC_CHECK_JNI_JINT(env,
            zvec_doc_add_field_by_value(doc, name.c_str(), ZVEC_DATA_TYPE_FLOAT,
                                        &v, sizeof(float)));
        break;
      }
      case KIND_BOOL: {
        // BOOL is 1 byte (sizeof(bool)); extract_scalar_value<bool> (c_api.cc:2941)
        // rejects any other size. bools[idx] is already 0/1.
        bool v = bools[idx] == JNI_TRUE;
        ZVEC_CHECK_JNI_JINT(env,
            zvec_doc_add_field_by_value(doc, name.c_str(), ZVEC_DATA_TYPE_BOOL,
                                        &v, sizeof(bool)));
        break;
      }
      case KIND_VEC_F32: {
        jfloatArray jv = static_cast<jfloatArray>(env->GetObjectArrayElement(jf32Vecs, idx));
        if (env->ExceptionCheck()) return nullptr;
        jsize len = env->GetArrayLength(jv);
        jfloat* elems = env->GetFloatArrayElements(jv, nullptr);
        if (!elems) { env->DeleteLocalRef(jv); return nullptr; }
        zvec_error_code_t c = zvec_doc_add_field_by_value(
            doc, name.c_str(), ZVEC_DATA_TYPE_VECTOR_FP32, elems,
            static_cast<size_t>(len) * sizeof(float));
        env->ReleaseFloatArrayElements(jv, elems, JNI_ABORT);
        env->DeleteLocalRef(jv);
        ZVEC_CHECK_JNI_JINT(env, c);
        break;
      }
      case KIND_VEC_F16: {
        jshortArray jv = static_cast<jshortArray>(env->GetObjectArrayElement(jf16Vecs, idx));
        if (env->ExceptionCheck()) return nullptr;
        jsize len = env->GetArrayLength(jv);
        jshort* elems = env->GetShortArrayElements(jv, nullptr);
        if (!elems) { env->DeleteLocalRef(jv); return nullptr; }
        // float16_t is 2 bytes; the JVM short is 2 bytes of raw half-16 bits.
        zvec_error_code_t c = zvec_doc_add_field_by_value(
            doc, name.c_str(), ZVEC_DATA_TYPE_VECTOR_FP16, elems,
            static_cast<size_t>(len) * 2);
        env->ReleaseShortArrayElements(jv, elems, JNI_ABORT);
        env->DeleteLocalRef(jv);
        ZVEC_CHECK_JNI_JINT(env, c);
        break;
      }
      case KIND_VEC_I8: {
        jbyteArray jv = static_cast<jbyteArray>(env->GetObjectArrayElement(ji8Vecs, idx));
        if (env->ExceptionCheck()) return nullptr;
        jsize len = env->GetArrayLength(jv);
        jbyte* elems = env->GetByteArrayElements(jv, nullptr);
        if (!elems) { env->DeleteLocalRef(jv); return nullptr; }
        zvec_error_code_t c = zvec_doc_add_field_by_value(
            doc, name.c_str(), ZVEC_DATA_TYPE_VECTOR_INT8, elems,
            static_cast<size_t>(len) * sizeof(int8_t));
        env->ReleaseByteArrayElements(jv, elems, JNI_ABORT);
        env->DeleteLocalRef(jv);
        ZVEC_CHECK_JNI_JINT(env, c);
        break;
      }
      default: {
        zvec_throw(env, ZVEC_ERROR_INVALID_ARGUMENT,
                   (std::string("Unknown field kind ") + std::to_string(kind)).c_str());
        return nullptr;
      }
    }
  }

  // Release ownership: the caller (single-doc path wraps it in a fresh DocGuard;
  // the bulk path owns it and destroys it after the _with_results call).
  doc_guard.d = nullptr;
  return doc;
}

// Shared per-doc result collection for the bulk DML paths (insert/upsert/update
// AND delete). Wraps the results array in WriteResultsGuard (freed on every exit
// path, including a throw-return), throws ZvecException on an API-level non-OK
// code, and otherwise copies EVERY per-doc (code, message) into the out-vectors.
//
// Does NOT throw on a per-doc non-OK — the bulk contract (Q10) returns per-doc
// results so the SDK can assemble WriteResult.failures. result index == input
// doc index (c_api.h:3144), so the caller's out-vectors stay index-aligned with
// the input batch.
//
// `results`/`result_count` are the raw outputs of the `_with_results` call; this
// function takes ownership of freeing them (via the guard).
zvec_error_code_t collect_results(
    JNIEnv* env, zvec_error_code_t api_code,
    zvec_write_result_t* results, size_t result_count,
    std::vector<int>& out_codes, std::vector<std::string>& out_messages) {
  WriteResultsGuard guard{results, result_count};

  if (api_code != ZVEC_OK) {
    char* msg = nullptr;
    zvec_get_last_error(&msg);
    std::string s = msg ? msg : zvec_code_label(api_code);
    if (msg) zvec_free(msg);
    zvec_throw(env, api_code, s.c_str());
    return api_code;
  }

  out_codes.resize(result_count);
  out_messages.resize(result_count);
  for (size_t i = 0; i < result_count; i++) {
    out_codes[i] = static_cast<int>(results[i].code);
    // An engine-null message is stored as an empty std::string; pack_write_results
    // maps empty → a null Kotlin slot (DocWriteFailure.detail = null).
    out_messages[i] = results[i].message ? results[i].message : std::string();
  }
  return api_code;
}

// Pack the per-doc (codes, messages) into the `Array<Any?>` the Kotlin
// assembleWriteResult expects: [0] = IntArray, [1] = Array<String?>. An empty
// message maps to a null String slot (engine set none); the index alignment is
// preserved (result index == input doc index).
jobjectArray pack_write_results(
    JNIEnv* env, const std::vector<int>& codes,
    const std::vector<std::string>& messages) {
  jsize n = static_cast<jsize>(codes.size());

  jintArray jcodes = env->NewIntArray(n);
  if (!jcodes) return nullptr;
  if (n > 0) env->SetIntArrayRegion(jcodes, 0, n, codes.data());
  if (env->ExceptionCheck()) { env->DeleteLocalRef(jcodes); return nullptr; }

  jclass str_class = env->FindClass("java/lang/String");
  jobjectArray jmessages = env->NewObjectArray(n, str_class, nullptr);
  if (!jmessages) { env->DeleteLocalRef(jcodes); return nullptr; }
  for (jsize i = 0; i < n; i++) {
    if (!messages[i].empty()) {
      jstring jm = env->NewStringUTF(messages[i].c_str());
      env->SetObjectArrayElement(jmessages, i, jm);
      env->DeleteLocalRef(jm);
      if (env->ExceptionCheck()) {
        env->DeleteLocalRef(jcodes);
        env->DeleteLocalRef(jmessages);
        return nullptr;
      }
    }
    // Empty message → leave the slot null (Kotlin maps it to detail = null).
  }

  jclass obj_class = env->FindClass("java/lang/Object");
  jobjectArray result = env->NewObjectArray(2, obj_class, nullptr);
  if (!result) { env->DeleteLocalRef(jcodes); env->DeleteLocalRef(jmessages); return nullptr; }
  env->SetObjectArrayElement(result, 0, jcodes);
  env->SetObjectArrayElement(result, 1, jmessages);
  env->DeleteLocalRef(jcodes);
  env->DeleteLocalRef(jmessages);
  return result;
}

struct DocsGuard {
  zvec_doc_t** docs = nullptr;
  size_t count = 0;
  ~DocsGuard() {
    if (docs) {
      zvec_docs_free(docs, count);
    }
  }
};

// Bulk input-doc cleanup. The C `_with_results` calls do NOT take ownership of
// the input docs (the Rust oracle's Doc drop calls zvec_doc_destroy itself), so
// the bulk path owns each built doc and must destroy it after the call — on the
// success path AND the throw-return path. This guard runs on any scope exit.
struct BuiltDocsGuard {
  std::vector<zvec_doc_t*> docs;
  ~BuiltDocsGuard() {
    for (zvec_doc_t* d : docs) {
      if (d) zvec_doc_destroy(d);
    }
  }
};

// ===========================================================================
// Issue 05: the read path — fetch + projection, and the `collect_docs` helper
// that issue 06 (query) reuses verbatim.
//
// The C read getters (zvec_doc_get_field_value_basic for fixed scalars,
// zvec_doc_get_field_value_pointer for variable-size strings/vectors) require a
// caller-supplied data_type per field — there is NO zvec_doc_get_field_type.
// The Rust oracle punts this to its caller (its Doc::get_* are typed). The SDK
// can't: the public fetch(pks, outputFields, includeVector) takes no types. So
// the JNI layer resolves each field's data_type from the COLLECTION SCHEMA
// (zvec_collection_get_schema + zvec_collection_schema_get_field + 
// zvec_field_schema_get_data_type), which is robust to schema drift (a later
// addColumn) and lets the read path be shared by query in issue 06.
//
// The value-type invariant (ADR 0006): every value is COPIED out of the doc
// before zvec_docs_free frees the whole array + each doc. No doc handle escapes.
// ===========================================================================

// JNI classes/methods the read path boxes scalars into. Resolved once per fetch
// (cached across the result array) — a FindClass/GetMethodID pair is the cost,
// not a per-doc cost. A null class means JVM-OOM, surfaced as a pending exception.
struct BoxedClasses {
  jclass bool_class = nullptr;
  jclass long_class = nullptr;
  jclass dbl_class = nullptr;
  jmethodID bool_ctor = nullptr;
  jmethodID long_ctor = nullptr;
  jmethodID dbl_ctor = nullptr;
};

// Resolve the JDK boxed-type classes + ctors used to wrap scalars into Object[].
// Returns false with a pending exception on a JVM-OOM (FindClass failure).
bool resolve_boxed_classes(JNIEnv* env, BoxedClasses& out) {
  out.bool_class = env->FindClass("java/lang/Boolean");
  out.long_class = env->FindClass("java/lang/Long");
  out.dbl_class = env->FindClass("java/lang/Double");
  if (!out.bool_class || !out.long_class || !out.dbl_class) return false;
  out.bool_ctor = env->GetMethodID(out.bool_class, "<init>", "(Z)V");
  out.long_ctor = env->GetMethodID(out.long_class, "<init>", "(J)V");
  out.dbl_ctor = env->GetMethodID(out.dbl_class, "<init>", "(D)V");
  return out.bool_ctor && out.long_ctor && out.dbl_ctor;
}

// Extract ONE field's value from `doc` into a boxed jobject (or a fresh JVM
// primitive array for vectors). The getter is chosen by the schema-resolved
// `dt` (the field's declared data_type): fixed scalars via get_field_value_basic
// into a sized buffer; variable-size (strings/vectors) via get_field_value_pointer
// into doc-internal storage, copied out into a fresh JVM array/string BEFORE the
// doc is freed (the borrowed-pointer contract). Returns null on a stored null
// (caller maps it to ZvecValue.Null); returns null with a pending exception on a
// getter failure. The caller must NOT hold the returned borrow past the doc's
// lifetime — this function copies, so no borrow escapes.
jobject extract_field_value(JNIEnv* env, const zvec_doc_t* doc,
                            const char* field_name, zvec_data_type_t dt,
                            const BoxedClasses& bc) {
  // Stored null → null slot (Kotlin maps it to ZvecValue.Null). Distinct from an
  // absent key (which never reaches here — only present fields are enumerated).
  if (zvec_doc_is_field_null(doc, field_name)) return nullptr;

  switch (dt) {
    // Fixed-size scalars via get_field_value_basic (exact-size buffer per type).
    case DT_BOOL: {
      bool v = false;
      ZVEC_CHECK_JNI_JINT(env, zvec_doc_get_field_value_basic(
          doc, field_name, ZVEC_DATA_TYPE_BOOL, &v, sizeof(bool)));
      return env->NewObject(bc.bool_class, bc.bool_ctor, v ? JNI_TRUE : JNI_FALSE);
    }
    case DT_INT32: {
      int32_t v = 0;
      ZVEC_CHECK_JNI_JINT(env, zvec_doc_get_field_value_basic(
          doc, field_name, ZVEC_DATA_TYPE_INT32, &v, sizeof(int32_t)));
      return env->NewObject(bc.long_class, bc.long_ctor,
                            static_cast<jlong>(v));
    }
    case DT_INT64: {
      int64_t v = 0;
      ZVEC_CHECK_JNI_JINT(env, zvec_doc_get_field_value_basic(
          doc, field_name, ZVEC_DATA_TYPE_INT64, &v, sizeof(int64_t)));
      return env->NewObject(bc.long_class, bc.long_ctor,
                            static_cast<jlong>(v));
    }
    case DT_UINT32: {
      uint32_t v = 0;
      ZVEC_CHECK_JNI_JINT(env, zvec_doc_get_field_value_basic(
          doc, field_name, ZVEC_DATA_TYPE_UINT32, &v, sizeof(uint32_t)));
      return env->NewObject(bc.long_class, bc.long_ctor,
                            static_cast<jlong>(v));
    }
    case DT_UINT64: {
      uint64_t v = 0;
      ZVEC_CHECK_JNI_JINT(env, zvec_doc_get_field_value_basic(
          doc, field_name, ZVEC_DATA_TYPE_UINT64, &v, sizeof(uint64_t)));
      return env->NewObject(bc.long_class, bc.long_ctor,
                            static_cast<jlong>(v));
    }
    case DT_FLOAT: {
      float v = 0.f;
      ZVEC_CHECK_JNI_JINT(env, zvec_doc_get_field_value_basic(
          doc, field_name, ZVEC_DATA_TYPE_FLOAT, &v, sizeof(float)));
      return env->NewObject(bc.dbl_class, bc.dbl_ctor,
                            static_cast<jdouble>(v));
    }
    case DT_DOUBLE: {
      double v = 0.0;
      ZVEC_CHECK_JNI_JINT(env, zvec_doc_get_field_value_basic(
          doc, field_name, ZVEC_DATA_TYPE_DOUBLE, &v, sizeof(double)));
      return env->NewObject(bc.dbl_class, bc.dbl_ctor,
                            static_cast<jdouble>(v));
    }
    // Variable-size via get_field_value_pointer (borrows doc-internal storage):
    // copy into a fresh JVM string/array before the doc is freed.
    case DT_STRING: {
      const void* ptr = nullptr;
      size_t sz = 0;
      ZVEC_CHECK_JNI_JINT(env, zvec_doc_get_field_value_pointer(
          doc, field_name, ZVEC_DATA_TYPE_STRING, &ptr, &sz));
      // ptr points at a null-terminated C string in the doc; NewStringUTF copies.
      return ptr ? env->NewStringUTF(static_cast<const char*>(ptr)) : nullptr;
    }
    case DT_VECTOR_FP32: {
      const void* ptr = nullptr;
      size_t sz = 0;
      ZVEC_CHECK_JNI_JINT(env, zvec_doc_get_field_value_pointer(
          doc, field_name, ZVEC_DATA_TYPE_VECTOR_FP32, &ptr, &sz));
      jsize len = static_cast<jsize>(sz / sizeof(float));
      jfloatArray arr = env->NewFloatArray(len);
      if (arr && len > 0) {
        env->SetFloatArrayRegion(arr, 0, len, static_cast<const jfloat*>(ptr));
      }
      return arr;
    }
    case DT_VECTOR_FP16: {
      const void* ptr = nullptr;
      size_t sz = 0;
      ZVEC_CHECK_JNI_JINT(env, zvec_doc_get_field_value_pointer(
          doc, field_name, ZVEC_DATA_TYPE_VECTOR_FP16, &ptr, &sz));
      // float16_t is 2 bytes; the JVM short is 2 bytes of raw half-16 bits.
      jsize len = static_cast<jsize>(sz / 2);
      jshortArray arr = env->NewShortArray(len);
      if (arr && len > 0) {
        env->SetShortArrayRegion(arr, 0, len, static_cast<const jshort*>(ptr));
      }
      return arr;
    }
    case DT_VECTOR_INT8: {
      const void* ptr = nullptr;
      size_t sz = 0;
      ZVEC_CHECK_JNI_JINT(env, zvec_doc_get_field_value_pointer(
          doc, field_name, ZVEC_DATA_TYPE_VECTOR_INT8, &ptr, &sz));
      jsize len = static_cast<jsize>(sz);
      jbyteArray arr = env->NewByteArray(len);
      if (arr && len > 0) {
        env->SetByteArrayRegion(arr, 0, len, static_cast<const jbyte*>(ptr));
      }
      return arr;
    }
    default:
      // A type the read path doesn't model (ARRAY_*, SPARSE_*, BINARY, …).
      // Leave the slot null — the Kotlin side treats an unmapped slot as
      // ZvecValue.Null rather than guessing the type. The supported set matches
      // ZvecValue's arms + FieldType.toNative(); adding a type is a new branch.
      return nullptr;
  }
}

// Copy ONE result doc into a flat Object[] row the Kotlin side unpacks into a
// ZvecDoc. Layout (names inline, so the read path is correct under a projection
// and the engine's nullable-field re-addition — the SDK doesn't have to guess
// which field each slot is):
//   [0]           = pk (String)
//   [1]           = score (Float — engine value; query-only, Kotlin fetch nulls it)
//   [2]           = present-field count (Int)
//   [3..]         = interleaved (name: String, value: boxed) per field
// Absent fields (under a projection) are never enumerated — the engine only
// returns requested fields. `schema` (non-owning borrow, may be null) supplies
// each field's data_type; when null or a field is unknown to the schema, the
// field maps to ZvecValue.Null.
//
// Returns null on a pending exception (the caller's DocsGuard still frees the
// whole array). Issue 06 calls this on query results against the same schema.
jobjectArray collect_one_doc(JNIEnv* env, const zvec_doc_t* doc,
                             const zvec_collection_schema_t* schema,
                             const BoxedClasses& bc) {
  // Resolve this doc's field names (allocated; freed below). Includes stored-
  // null fields (field_names() iterates the map keys regardless of monostate).
  char** field_names = nullptr;
  size_t field_count = 0;
  ZVEC_CHECK_JNI_JINT(env, zvec_doc_get_field_names(doc, &field_names, &field_count));

  jclass obj_class = env->FindClass("java/lang/Object");
  jclass int_class = env->FindClass("java/lang/Integer");
  jmethodID int_ctor = env->GetMethodID(int_class, "<init>", "(I)V");
  if (!obj_class || !int_class || !int_ctor) {
    zvec_free_str_array(field_names, field_count);
    return nullptr;
  }

  // pk + score + count + interleaved (name, value) pairs.
  jsize row_len = static_cast<jsize>(3 + 2 * field_count);
  jobjectArray row = env->NewObjectArray(row_len, obj_class, nullptr);
  if (!row || env->ExceptionCheck()) {
    zvec_free_str_array(field_names, field_count);
    return nullptr;
  }

  // [0] = pk (pointer is valid for the doc's lifetime — copy via NewStringUTF).
  const char* pk_ptr = zvec_doc_get_pk_pointer(doc);
  jstring jpk = env->NewStringUTF(pk_ptr ? pk_ptr : "");
  env->SetObjectArrayElement(row, 0, jpk);
  env->DeleteLocalRef(jpk);
  if (env->ExceptionCheck()) { zvec_free_str_array(field_names, field_count); return nullptr; }

  // [1] = score. Always read from the doc so the shared collect_docs path also
  // serves query results (issue 06); for a plain fetch the engine's score is 0.0
  // and the Kotlin fetch() nulls it out (score is a query-only concept).
  float score = zvec_doc_get_score(doc);
  jclass float_class = env->FindClass("java/lang/Float");
  jmethodID float_ctor = env->GetMethodID(float_class, "<init>", "(F)V");
  if (!float_class || !float_ctor) { zvec_free_str_array(field_names, field_count); return nullptr; }
  jobject jscore = env->NewObject(float_class, float_ctor, static_cast<jfloat>(score));
  env->SetObjectArrayElement(row, 1, jscore);
  env->DeleteLocalRef(jscore);
  env->DeleteLocalRef(float_class);
  if (env->ExceptionCheck()) { zvec_free_str_array(field_names, field_count); return nullptr; }

  // [2] = present-field count.
  jobject jcount = env->NewObject(int_class, int_ctor, static_cast<jint>(field_count));
  env->SetObjectArrayElement(row, 2, jcount);
  env->DeleteLocalRef(jcount);
  if (env->ExceptionCheck()) { zvec_free_str_array(field_names, field_count); return nullptr; }

  // [3..] = interleaved (name, value) pairs: resolve the field's declared
  // data_type from the schema, then extract. A field absent from the schema
  // (schema drift) maps to ZvecValue.Null — never a wrong-typed read.
  jsize slot = 3;
  for (size_t i = 0; i < field_count; i++) {
    const char* fname = field_names[i];
    jstring jname = env->NewStringUTF(fname);
    env->SetObjectArrayElement(row, slot, jname);
    env->DeleteLocalRef(jname);
    if (env->ExceptionCheck()) { zvec_free_str_array(field_names, field_count); return nullptr; }

    zvec_data_type_t dt = DT_STRING;  // default; overridden when the schema knows the field
    if (schema) {
      // get_field is a NON-owning borrow into `schema` (valid while the guard
      // holds it); returns NULL for a field the schema doesn't know.
      zvec_field_schema_t* fs = zvec_collection_schema_get_field(schema, fname);
      if (fs) dt = zvec_field_schema_get_data_type(fs);
    }
    jobject boxed = extract_field_value(env, doc, fname, dt, bc);
    if (env->ExceptionCheck()) {
      zvec_free_str_array(field_names, field_count);
      return nullptr;
    }
    env->SetObjectArrayElement(row, slot + 1, boxed);
    if (boxed) env->DeleteLocalRef(boxed);
    if (env->ExceptionCheck()) { zvec_free_str_array(field_names, field_count); return nullptr; }
    slot += 2;
  }

  zvec_free_str_array(field_names, field_count);
  return row;
}

// `collect_docs` — the shared read-path result-array reader (issue 05 fetch +
// issue 06 query). Walks `docs`/`count`, copies each doc out into a flat Object[]
// row (resolving each field's data_type from the collection schema), then the
// caller's DocsGuard frees the whole array + every doc. Because we copy out, no
// doc handle escapes (the value-type invariant, ADR 0006 / Q3).
//
// `col` is the owning collection handle: the schema is fetched once per call
// (under CollectionSchemaGuard) and shared across all result docs. Returns an
// Object[] (one row per doc) or null on a pending exception. Absent pks are
// already absent from `docs` (the engine omits them — `count` is found docs).
jobjectArray collect_docs(JNIEnv* env, zvec_collection_t* col,
                          zvec_doc_t** docs, size_t count) {
  // Fetch the schema once for the whole batch (per-field type resolution).
  // Failure to read it is non-fatal — fields fall back to the null mapping.
  zvec_collection_schema_t* raw_schema = nullptr;
  if (col) {
    zvec_collection_get_schema(col, &raw_schema);  // best-effort; ignore error
  }
  CollectionSchemaGuard schema_guard{raw_schema};

  BoxedClasses bc;
  if (!resolve_boxed_classes(env, bc)) return nullptr;
  if (env->ExceptionCheck()) return nullptr;

  jclass obj_class = env->FindClass("java/lang/Object");
  jobjectArray result =
      env->NewObjectArray(static_cast<jsize>(count), obj_class, nullptr);
  if (!result || env->ExceptionCheck()) return nullptr;

  for (size_t i = 0; i < count; i++) {
    if (!docs[i]) continue;  // defensive — engine omits absent pks
    jobjectArray row = collect_one_doc(env, docs[i], raw_schema, bc);
    if (env->ExceptionCheck() || !row) return nullptr;
    env->SetObjectArrayElement(result, static_cast<jsize>(i), row);
    env->DeleteLocalRef(row);
    if (env->ExceptionCheck()) return nullptr;
  }
  return result;
}

} // namespace

extern "C" {

// Single-doc insert / upsert. Builds the zvec_doc_t via the shared build_doc
// helper (the per-field kind-switch), then runs the `_with_results` write and
// surfaces the per-doc code. Returns the written pk (a new jstring) on success;
// throws on any failure.
JNIEXPORT jstring JNICALL
Java_io_github_tzhvh_scryernext_zvec_ZvecNative_nativeDocWrite(
    JNIEnv* env, jclass,
    jlong handle, jint jop, jstring jpk,
    jint jfieldCount,
    jobjectArray jfieldNames, jintArray jkinds, jintArray jscalarIndex,
    jbooleanArray jisNull,
    jlongArray jlongs, jdoubleArray jdoubles, jbooleanArray jbools,
    jobjectArray jstrings,
    jobjectArray jf32Vecs, jobjectArray jf16Vecs, jobjectArray ji8Vecs) {

  zvec_collection_t* col = reinterpret_cast<zvec_collection_t*>(handle);
  if (!col) {
    zvec_throw(env, ZVEC_ERROR_INVALID_ARGUMENT, "Collection handle is null");
    return nullptr;
  }

  std::string pk = jstring_to_std(env, jpk);
  if (env->ExceptionCheck()) return nullptr;

  zvec_doc_t* doc = build_doc(env, pk, jfieldCount, jfieldNames, jkinds,
                              jscalarIndex, jisNull, jlongs, jdoubles, jbools,
                              jstrings, jf32Vecs, jf16Vecs, ji8Vecs);
  if (env->ExceptionCheck() || !doc) return nullptr;

  DocGuard doc_guard{doc};

  std::string message;
  ZVEC_CHECK_JNI_JINT(env, run_with_results(env, col, jop, doc, message));
  return env->NewStringUTF(pk.c_str());
}

// Bulk insert / upsert / update over a batch of docs, returning PER-DOC results
// (Q10) instead of throwing on the first failure. Each doc's descriptor slice is
// extracted from the per-doc sub-arrays, built via the shared build_doc, packed
// into one `const zvec_doc_t**` batch, and the op-selected `_with_results`
// variant is called ONCE. The per-doc results[] array is then walked (result
// index == input doc index) and copied into the returned parallel arrays.
//
// An API-level non-OK throws ZvecException; a per-doc non-OK code is RETURNED
// (not thrown) so the SDK can assemble WriteResult.failures. The empty-batch
// short-circuit lives in Kotlin (zvec rejects doc_count==0 with INVALID_ARGUMENT,
// c_api.cc:6380/6450/6520) — this native function is never called with 0 docs.
//
// Returns `Array<Any?>` = [0]: IntArray per-doc codes, [1]: Array<String?>
// per-doc messages (null slots where the engine set none). null on a throw.
JNIEXPORT jobjectArray JNICALL
Java_io_github_tzhvh_scryernext_zvec_ZvecNative_nativeDocWriteBulk(
    JNIEnv* env, jclass,
    jlong handle, jint jop,
    jobjectArray jpks,
    jintArray jfieldCounts,
    jobjectArray jfieldNames, jobjectArray jkinds, jobjectArray jscalarIndex,
    jobjectArray jisNull,
    jobjectArray jlongs, jobjectArray jdoubles, jobjectArray jbools,
    jobjectArray jstrings,
    jobjectArray jf32Vecs, jobjectArray jf16Vecs, jobjectArray ji8Vecs) {

  zvec_collection_t* col = reinterpret_cast<zvec_collection_t*>(handle);
  if (!col) {
    zvec_throw(env, ZVEC_ERROR_INVALID_ARGUMENT, "Collection handle is null");
    return nullptr;
  }

  jsize doc_count = env->GetArrayLength(jpks);
  if (env->ExceptionCheck()) return nullptr;
  // doc_count == 0 is short-circuited in Kotlin; defend in depth anyway.
  if (doc_count == 0) {
    std::vector<int> codes;
    std::vector<std::string> messages;
    return pack_write_results(env, codes, messages);
  }

  std::vector<jint> fieldCounts(doc_count);
  env->GetIntArrayRegion(jfieldCounts, 0, doc_count, fieldCounts.data());
  if (env->ExceptionCheck()) return nullptr;

  // Build every doc into the batch. BuiltDocsGuard owns them and destroys them
  // on any exit path (the _with_results call does not take input ownership).
  BuiltDocsGuard built;
  built.docs.reserve(doc_count);
  std::vector<const zvec_doc_t*> doc_ptrs(doc_count);

  for (jsize d = 0; d < doc_count; d++) {
    // pk for this doc.
    jstring jpk = static_cast<jstring>(env->GetObjectArrayElement(jpks, d));
    if (env->ExceptionCheck()) return nullptr;
    std::string pk = jstring_to_std(env, jpk);
    env->DeleteLocalRef(jpk);
    if (env->ExceptionCheck()) return nullptr;

    // Extract this doc's descriptor sub-arrays (each is one row of the
    // array-of-arrays). Held as local refs, released after build_doc.
    jobjectArray jfieldNames_d =
        static_cast<jobjectArray>(env->GetObjectArrayElement(jfieldNames, d));
    jintArray jkinds_d =
        static_cast<jintArray>(env->GetObjectArrayElement(jkinds, d));
    jintArray jscalarIndex_d =
        static_cast<jintArray>(env->GetObjectArrayElement(jscalarIndex, d));
    jbooleanArray jisNull_d =
        static_cast<jbooleanArray>(env->GetObjectArrayElement(jisNull, d));
    jlongArray jlongs_d =
        static_cast<jlongArray>(env->GetObjectArrayElement(jlongs, d));
    jdoubleArray jdoubles_d =
        static_cast<jdoubleArray>(env->GetObjectArrayElement(jdoubles, d));
    jbooleanArray jbools_d =
        static_cast<jbooleanArray>(env->GetObjectArrayElement(jbools, d));
    jobjectArray jstrings_d =
        static_cast<jobjectArray>(env->GetObjectArrayElement(jstrings, d));
    jobjectArray jf32Vecs_d =
        static_cast<jobjectArray>(env->GetObjectArrayElement(jf32Vecs, d));
    jobjectArray jf16Vecs_d =
        static_cast<jobjectArray>(env->GetObjectArrayElement(jf16Vecs, d));
    jobjectArray ji8Vecs_d =
        static_cast<jobjectArray>(env->GetObjectArrayElement(ji8Vecs, d));
    if (env->ExceptionCheck()) return nullptr;

    zvec_doc_t* doc = build_doc(env, pk, fieldCounts[d], jfieldNames_d, jkinds_d,
                                jscalarIndex_d, jisNull_d, jlongs_d, jdoubles_d,
                                jbools_d, jstrings_d, jf32Vecs_d, jf16Vecs_d,
                                ji8Vecs_d);

    env->DeleteLocalRef(jfieldNames_d);
    env->DeleteLocalRef(jkinds_d);
    env->DeleteLocalRef(jscalarIndex_d);
    env->DeleteLocalRef(jisNull_d);
    env->DeleteLocalRef(jlongs_d);
    env->DeleteLocalRef(jdoubles_d);
    env->DeleteLocalRef(jbools_d);
    env->DeleteLocalRef(jstrings_d);
    env->DeleteLocalRef(jf32Vecs_d);
    env->DeleteLocalRef(jf16Vecs_d);
    env->DeleteLocalRef(ji8Vecs_d);
    if (env->ExceptionCheck() || !doc) return nullptr;

    built.docs.push_back(doc);
    doc_ptrs[d] = doc;
  }

  // Dispatch the op-selected `_with_results` variant over the whole batch at
  // once (NOT per-doc — the batch is one C call).
  zvec_write_result_t* results = nullptr;
  size_t result_count = 0;
  zvec_error_code_t api_code;
  switch (jop) {
    case OP_UPSERT:
      api_code = zvec_collection_upsert_with_results(
          col, doc_ptrs.data(), static_cast<size_t>(doc_count), &results, &result_count);
      break;
    case OP_UPDATE:
      api_code = zvec_collection_update_with_results(
          col, doc_ptrs.data(), static_cast<size_t>(doc_count), &results, &result_count);
      break;
    default:  // OP_INSERT
      api_code = zvec_collection_insert_with_results(
          col, doc_ptrs.data(), static_cast<size_t>(doc_count), &results, &result_count);
      break;
  }

  std::vector<int> codes;
  std::vector<std::string> messages;
  ZVEC_CHECK_JNI_JINT(env,
      collect_results(env, api_code, results, result_count, codes, messages));
  // BuiltDocsGuard frees the input docs at scope exit (collect_results already
  // freed the results array via its WriteResultsGuard).
  return pack_write_results(env, codes, messages);
}

// Bulk delete by pk via zvec_collection_delete_with_results. Same per-doc result
// discipline as nativeDocWriteBulk: a missing pk surfaces as a per-doc NOT_FOUND
// in the returned codes (NOT thrown). Empty-list short-circuit lives in Kotlin.
// Returns `Array<Any?>` = [0]: IntArray codes, [1]: Array<String?> messages.
JNIEXPORT jobjectArray JNICALL
Java_io_github_tzhvh_scryernext_zvec_ZvecNative_nativeDeleteAll(
    JNIEnv* env, jclass, jlong handle, jobjectArray jpks) {

  zvec_collection_t* col = reinterpret_cast<zvec_collection_t*>(handle);
  if (!col) {
    zvec_throw(env, ZVEC_ERROR_INVALID_ARGUMENT, "Collection handle is null");
    return nullptr;
  }

  jsize n = env->GetArrayLength(jpks);
  if (env->ExceptionCheck()) return nullptr;
  if (n == 0) {
    std::vector<int> codes;
    std::vector<std::string> messages;
    return pack_write_results(env, codes, messages);
  }

  // Copy pks into std::strings (owning) and collect const char* pointers for the
  // C call. The std::strings must outlive the call.
  std::vector<std::string> pks(n);
  std::vector<const char*> pk_ptrs(n);
  for (jsize i = 0; i < n; i++) {
    jstring jpk = static_cast<jstring>(env->GetObjectArrayElement(jpks, i));
    if (env->ExceptionCheck()) return nullptr;
    pks[i] = jstring_to_std(env, jpk);
    env->DeleteLocalRef(jpk);
    if (env->ExceptionCheck()) return nullptr;
    pk_ptrs[i] = pks[i].c_str();
  }

  zvec_write_result_t* results = nullptr;
  size_t result_count = 0;
  zvec_error_code_t api_code = zvec_collection_delete_with_results(
      col, pk_ptrs.data(), static_cast<size_t>(n), &results, &result_count);

  std::vector<int> codes;
  std::vector<std::string> messages;
  ZVEC_CHECK_JNI_JINT(env,
      collect_results(env, api_code, results, result_count, codes, messages));
  return pack_write_results(env, codes, messages);
}

// Delete by filter via zvec_collection_delete_by_filter. Returns void: the C
// engine does NOT report a deleted count (c_api.h:3284 has no count out-param
// despite its doc-comment; collection.cc:1633 returns only Status::OK(); the
// Rust and Go reference bindings surface only an error). A stats before/after
// diff was rejected — it races concurrent SAF ingestion and yields negative
// counts. Throws ZvecException on a non-OK engine code; the KDoc lives on
// ZvecCollection.deleteByFilter (incl. the injection/escape cross-link).
JNIEXPORT void JNICALL
Java_io_github_tzhvh_scryernext_zvec_ZvecNative_nativeDeleteByFilter(
    JNIEnv* env, jclass, jlong handle, jstring jfilter) {

  zvec_collection_t* col = reinterpret_cast<zvec_collection_t*>(handle);
  if (!col) {
    zvec_throw(env, ZVEC_ERROR_INVALID_ARGUMENT, "Collection handle is null");
    return;
  }

  std::string filter = jstring_to_std(env, jfilter);
  if (env->ExceptionCheck()) return;

  ZVEC_CHECK_JNI_VOID(env, zvec_collection_delete_by_filter(col, filter.c_str()));
}

// Single-doc delete via zvec_collection_delete_with_results. The bare
// zvec_collection_delete returns ZVEC_OK on a missing pk (only bumps
// error_count), so the `_with_results` variant is mandatory to surface NOT_FOUND.
// Returns the pk on success; throws on a per-doc non-OK code.
JNIEXPORT jstring JNICALL
Java_io_github_tzhvh_scryernext_zvec_ZvecNative_nativeDelete(
    JNIEnv* env, jclass, jlong handle, jstring jpk) {

  zvec_collection_t* col = reinterpret_cast<zvec_collection_t*>(handle);
  if (!col) {
    zvec_throw(env, ZVEC_ERROR_INVALID_ARGUMENT, "Collection handle is null");
    return nullptr;
  }

  std::string pk = jstring_to_std(env, jpk);
  if (env->ExceptionCheck()) return nullptr;

  const char* pk_ptrs[1] = {pk.c_str()};
  zvec_write_result_t* results = nullptr;
  size_t result_count = 0;
  zvec_error_code_t api_code = zvec_collection_delete_with_results(
      col, pk_ptrs, 1, &results, &result_count);
  if (api_code != ZVEC_OK) {
    if (results) zvec_write_results_free(results, result_count);
    char* msg = nullptr;
    zvec_get_last_error(&msg);
    std::string s = msg ? msg : zvec_code_label(api_code);
    if (msg) zvec_free(msg);
    zvec_throw(env, api_code, s.c_str());
    return nullptr;
  }

  zvec_error_code_t per_doc_code = ZVEC_OK;
  std::string message;
  if (result_count > 0 && results) {
    per_doc_code = results[0].code;
    if (results[0].message) message = results[0].message;
  }
  zvec_write_results_free(results, result_count);

  if (per_doc_code != ZVEC_OK) {
    if (message.empty()) {
      char* msg = nullptr;
      zvec_get_last_error(&msg);
      if (msg) { message = msg; zvec_free(msg); }
    }
    if (message.empty()) message = zvec_code_label(per_doc_code);
    zvec_throw(env, per_doc_code, message.c_str());
    return nullptr;
  }
  return env->NewStringUTF(pk.c_str());
}

// Minimal typed fetch for issue 03's round-trip test — the "scratch read helper"
// the issue sanctions when 05 (projection fetch) hasn't landed.
//
// There is no zvec_doc_get_field_type, so the read getters need a caller-supplied
// type per field; fieldTypes supplies it (the builder/round-trip test knows the
// types it wrote). Fetches the single doc for `pk` with include_vector=true and
// all scalar fields, copies each value out via the C getters, frees the doc
// array, and returns a flat Object[] the Kotlin side unpacks into a ZvecDoc.
//
// Layout of the returned Object[]:
//   [0] = pk (String)
//   [1] = score (Float, nullable — query result only, null on a plain fetch)
//   then, per field in fieldNames order, one of: Boolean / Long / Double / String /
//   float[] (VEC_F32) / short[] (VEC_F16) / byte[] (VEC_I8) / null (ZvecValue.Null).
// Returns null if the pk is absent (fetch returned 0 docs).
JNIEXPORT jobject JNICALL
Java_io_github_tzhvh_scryernext_zvec_ZvecNative_nativeFetchTyped(
    JNIEnv* env, jclass, jlong handle, jstring jpk,
    jobjectArray jfieldNames, jintArray jfieldTypes) {

  zvec_collection_t* col = reinterpret_cast<zvec_collection_t*>(handle);
  if (!col) {
    zvec_throw(env, ZVEC_ERROR_INVALID_ARGUMENT, "Collection handle is null");
    return nullptr;
  }

  std::string pk = jstring_to_std(env, jpk);
  if (env->ExceptionCheck()) return nullptr;

  jsize n = env->GetArrayLength(jfieldNames);
  if (env->ExceptionCheck()) return nullptr;

  // Copy field names + types eagerly (Rule 1).
  std::vector<std::string> names(n);
  std::vector<const char*> name_ptrs(n);
  for (jsize i = 0; i < n; i++) {
    jstring jn = static_cast<jstring>(env->GetObjectArrayElement(jfieldNames, i));
    if (env->ExceptionCheck()) return nullptr;
    names[i] = jstring_to_std(env, jn);
    env->DeleteLocalRef(jn);
    if (env->ExceptionCheck()) return nullptr;
    name_ptrs[i] = names[i].c_str();
  }
  std::vector<jint> types(n);
  if (n > 0) {
    env->GetIntArrayRegion(jfieldTypes, 0, n, types.data());
    if (env->ExceptionCheck()) return nullptr;
  }

  const char* pk_ptrs[1] = {pk.c_str()};
  zvec_doc_t** docs = nullptr;
  size_t found = 0;
  ZVEC_CHECK_JNI_JINT(env,
      zvec_collection_fetch(col, pk_ptrs, 1,
                            /*output_fields=*/nullptr, /*output_field_count=*/0,
                            /*include_vector=*/true, &docs, &found));

  DocsGuard docs_guard{docs, found};

  if (found == 0 || !docs[0]) {
    return nullptr;  // absent pk — caller treats null as "not found"
  }
  const zvec_doc_t* d = docs[0];

  // Resolve classes/methods for the boxed return. These are standard JDK types;
  // a FindClass failure here means a JVM-OOM, surfaced as a pending exception.
  jclass obj_class = env->FindClass("java/lang/Object");
  jclass bool_class = env->FindClass("java/lang/Boolean");
  jclass long_class = env->FindClass("java/lang/Long");
  jclass dbl_class = env->FindClass("java/lang/Double");
  jmethodID bool_ctor = env->GetMethodID(bool_class, "<init>", "(Z)V");
  jmethodID long_ctor = env->GetMethodID(long_class, "<init>", "(J)V");
  jmethodID dbl_ctor = env->GetMethodID(dbl_class, "<init>", "(D)V");

  // Total slots: pk + score + one per field.
  jobjectArray result = env->NewObjectArray(2 + n, obj_class, nullptr);
  if (!result || env->ExceptionCheck()) { return nullptr; }

  // [0] = pk.
  jstring jout_pk = env->NewStringUTF(zvec_doc_get_pk_pointer(d) ? zvec_doc_get_pk_pointer(d) : "");
  env->SetObjectArrayElement(result, 0, jout_pk);
  env->DeleteLocalRef(jout_pk);
  // [1] = score (null on a plain fetch — queries set it).
  env->SetObjectArrayElement(result, 1, nullptr);

  // Per field: dispatch on the caller-supplied type to the right C getter. The
  // getters copy out of the doc; values are then boxed/copied into the Object[]
  // before zvec_docs_free runs below.
  for (jsize i = 0; i < n; i++) {
    const char* fname = name_ptrs[i];
    jint ft = types[i];
    jobject boxed = nullptr;

    if (zvec_doc_is_field_null(d, fname)) {
      // Stored null → leave the slot null (Kotlin maps it to ZvecValue.Null).
      continue;
    }

    switch (ft) {
      // Fixed scalars via get_field_value_basic (exact-size buffer per type).
      case 3: {  // BOOL
        bool v = false;
        ZVEC_CHECK_JNI_JINT(env, zvec_doc_get_field_value_basic(d, fname, ZVEC_DATA_TYPE_BOOL, &v, sizeof(bool)));
        boxed = env->NewObject(bool_class, bool_ctor, v ? JNI_TRUE : JNI_FALSE);
        break;
      }
      case 4: {  // INT32
        int32_t v = 0;
        ZVEC_CHECK_JNI_JINT(env, zvec_doc_get_field_value_basic(d, fname, ZVEC_DATA_TYPE_INT32, &v, sizeof(int32_t)));
        boxed = env->NewObject(long_class, long_ctor, static_cast<jlong>(v));
        break;
      }
      case 5: {  // INT64
        int64_t v = 0;
        ZVEC_CHECK_JNI_JINT(env, zvec_doc_get_field_value_basic(d, fname, ZVEC_DATA_TYPE_INT64, &v, sizeof(int64_t)));
        boxed = env->NewObject(long_class, long_ctor, static_cast<jlong>(v));
        break;
      }
      case 8: {  // FLOAT
        float v = 0.f;
        ZVEC_CHECK_JNI_JINT(env, zvec_doc_get_field_value_basic(d, fname, ZVEC_DATA_TYPE_FLOAT, &v, sizeof(float)));
        boxed = env->NewObject(dbl_class, dbl_ctor, static_cast<jdouble>(v));
        break;
      }
      case 9: {  // DOUBLE
        double v = 0.0;
        ZVEC_CHECK_JNI_JINT(env, zvec_doc_get_field_value_basic(d, fname, ZVEC_DATA_TYPE_DOUBLE, &v, sizeof(double)));
        boxed = env->NewObject(dbl_class, dbl_ctor, static_cast<jdouble>(v));
        break;
      }
      // Variable-size via get_field_value_pointer (borrows doc-internal storage):
      // copy into a fresh JVM array before the docs_free below.
      case 2: {  // STRING
        const void* ptr = nullptr;
        size_t sz = 0;
        ZVEC_CHECK_JNI_JINT(env, zvec_doc_get_field_value_pointer(d, fname, ZVEC_DATA_TYPE_STRING, &ptr, &sz));
        boxed = ptr ? env->NewStringUTF(static_cast<const char*>(ptr)) : nullptr;
        break;
      }
      case 23: {  // VECTOR_FP32
        const void* ptr = nullptr;
        size_t sz = 0;
        ZVEC_CHECK_JNI_JINT(env, zvec_doc_get_field_value_pointer(d, fname, ZVEC_DATA_TYPE_VECTOR_FP32, &ptr, &sz));
        jsize len = static_cast<jsize>(sz / sizeof(float));
        jfloatArray arr = env->NewFloatArray(len);
        if (arr && len > 0) env->SetFloatArrayRegion(arr, 0, len, static_cast<const jfloat*>(ptr));
        boxed = arr;
        break;
      }
      case 22: {  // VECTOR_FP16
        const void* ptr = nullptr;
        size_t sz = 0;
        ZVEC_CHECK_JNI_JINT(env, zvec_doc_get_field_value_pointer(d, fname, ZVEC_DATA_TYPE_VECTOR_FP16, &ptr, &sz));
        jsize len = static_cast<jsize>(sz / 2);
        jshortArray arr = env->NewShortArray(len);
        if (arr && len > 0) env->SetShortArrayRegion(arr, 0, len, static_cast<const jshort*>(ptr));
        boxed = arr;
        break;
      }
      case 26: {  // VECTOR_INT8
        const void* ptr = nullptr;
        size_t sz = 0;
        ZVEC_CHECK_JNI_JINT(env, zvec_doc_get_field_value_pointer(d, fname, ZVEC_DATA_TYPE_VECTOR_INT8, &ptr, &sz));
        jsize len = static_cast<jsize>(sz);
        jbyteArray arr = env->NewByteArray(len);
        if (arr && len > 0) env->SetByteArrayRegion(arr, 0, len, static_cast<const jbyte*>(ptr));
        boxed = arr;
        break;
      }
      default:
        // Unsupported type in the scratch reader — leave null. The round-trip
        // test only exercises the types above.
        break;
    }
    if (env->ExceptionCheck()) { return nullptr; }
    if (boxed) {
      env->SetObjectArrayElement(result, 2 + i, boxed);
      env->DeleteLocalRef(boxed);
    }
  }

  return result;
}

// Issue 05: the public fetch + projection surface. Marshals pks + output_fields,
// calls zvec_collection_fetch (c_api.h:3332), wraps the result array in DocsGuard
// (frees the whole array + every doc on any exit path), and runs collect_docs to
// copy each doc out into a flat Object[] row (resolving each field's data_type
// from the collection schema — there is no zvec_doc_get_field_type).
//
// output_fields: a null joutputFields = all fields; otherwise the projection set.
// include_vector: whether vector fields are returned at all (the load-bearing
// `false` default — see ZvecCollection.fetch). Absent pks are omitted by the
// engine — the returned array carries only found docs (count == found_count).
// Result order is NOT guaranteed (verified live: a [d1, ghost, d2] fetch can
// return [d2, d1]); callers index by pk, not by input position.
//
// Returns `Array<Any?>` (one Object[] row per found doc). The Kotlin side maps
// null joutputFields and the per-row layout into ZvecDocs. Empty pks is short-
// circuited in Kotlin (fetch returns emptyList() without touching native).
JNIEXPORT jobjectArray JNICALL
Java_io_github_tzhvh_scryernext_zvec_ZvecNative_nativeFetch(
    JNIEnv* env, jclass, jlong handle,
    jobjectArray jpks, jobjectArray joutputFields, jboolean jincludeVector) {

  zvec_collection_t* col = reinterpret_cast<zvec_collection_t*>(handle);
  if (!col) {
    zvec_throw(env, ZVEC_ERROR_INVALID_ARGUMENT, "Collection handle is null");
    return nullptr;
  }

  jsize n = env->GetArrayLength(jpks);
  if (env->ExceptionCheck()) return nullptr;

  // Copy pks eagerly into owning std::strings (Rule 1); collect const char*
  // pointers for the C call. The std::strings must outlive the call.
  std::vector<std::string> pks(n);
  std::vector<const char*> pk_ptrs(n);
  for (jsize i = 0; i < n; i++) {
    jstring jpk = static_cast<jstring>(env->GetObjectArrayElement(jpks, i));
    if (env->ExceptionCheck()) return nullptr;
    pks[i] = jstring_to_std(env, jpk);
    env->DeleteLocalRef(jpk);
    if (env->ExceptionCheck()) return nullptr;
    pk_ptrs[i] = pks[i].c_str();
  }

  // output_fields: null = all fields; otherwise the projection set. Copied out
  // eagerly so no JNI reference is held across the zvec call (Rule 1).
  std::vector<std::string> out_fields_storage;
  std::vector<const char*> out_field_ptrs;
  const char* const* out_fields_ptr = nullptr;
  size_t out_field_count = 0;
  if (joutputFields != nullptr) {
    jsize ofn = env->GetArrayLength(joutputFields);
    if (env->ExceptionCheck()) return nullptr;
    out_fields_storage.resize(ofn);
    out_field_ptrs.resize(ofn);
    for (jsize i = 0; i < ofn; i++) {
      jstring jf = static_cast<jstring>(env->GetObjectArrayElement(joutputFields, i));
      if (env->ExceptionCheck()) return nullptr;
      out_fields_storage[i] = jstring_to_std(env, jf);
      env->DeleteLocalRef(jf);
      if (env->ExceptionCheck()) return nullptr;
      out_field_ptrs[i] = out_fields_storage[i].c_str();
    }
    out_fields_ptr = out_field_ptrs.data();
    out_field_count = static_cast<size_t>(ofn);
  }

  zvec_doc_t** docs = nullptr;
  size_t found_count = 0;
  ZVEC_CHECK_JNI_JINT(env,
      zvec_collection_fetch(col, pk_ptrs.data(), static_cast<size_t>(n),
                            out_fields_ptr, out_field_count,
                            jincludeVector == JNI_TRUE, &docs, &found_count));

  DocsGuard docs_guard{docs, found_count};
  return collect_docs(env, col, docs, found_count);
}

// Issue 06: single-vector / pure-FTS query. Builds a vector_query_t, sets
// field_name + topK + (optional) output_fields + (optional) filter, then either
// attaches a query vector (vector mode) or an fts_t payload carrying the FTS
// match string (FTS mode). fts_t is COPIED into the query by
// zvec_vector_query_set_fts, so FtsGuard always frees it. Then
// zvec_collection_query runs (c_api.h:3300); the result array is wrapped in
// DocsGuard (frees the whole array + every doc on any exit path) and the shared
// collect_docs (issue 05) copies each doc out into the same flat Object[] row
// fetch uses — including the engine's raw score at slot [1], which the Kotlin
// query() path keeps (R1: under COSINE score = cosine distance, lower = more
// similar, engine returns ascending — nearest first).
//
// All three handles (VectorQueryGuard / FtsGuard / DocsGuard) run on any exit
// path, including a macro throw-return mid-build: the caller never sees a query
// handle (Q7 — the one place in the SDK where a handle is never visible, even
// transiently). Vector mode = `jvector != null`; FTS mode = `jfts != null`. The
// Kotlin side validates exactly-one is set before calling.
//
// Returns `Array<Any?>` = one Object[] row per result doc (same row shape as
// nativeFetch). Empty result is a zero-length array.
JNIEXPORT jobjectArray JNICALL
Java_io_github_tzhvh_scryernext_zvec_ZvecNative_nativeQuery(
    JNIEnv* env, jclass, jlong handle,
    jstring jfield, jfloatArray jvector, jstring jfts, jstring jfilter,
    jint jtopk, jobjectArray joutputFields) {

  zvec_collection_t* col = reinterpret_cast<zvec_collection_t*>(handle);
  if (!col) {
    zvec_throw(env, ZVEC_ERROR_INVALID_ARGUMENT, "Collection handle is null");
    return nullptr;
  }

  // Copy field eagerly (Rule 1: copy, don't RAII-pin the JVM string across the
  // zvec setters — the const char* borrows into the std::string's storage).
  std::string field = jstring_to_std(env, jfield);
  if (env->ExceptionCheck()) return nullptr;

  // Build the vector query handle; the guard frees it on any exit path.
  zvec_vector_query_t* query = zvec_vector_query_create();
  ZVEC_CHECK_PTR(env, query, "vector_query_create");
  VectorQueryGuard query_guard{query};

  ZVEC_CHECK_JNI_JINT(env,
      zvec_vector_query_set_field_name(query, field.c_str()));
  ZVEC_CHECK_JNI_JINT(env,
      zvec_vector_query_set_topk(query, static_cast<int>(jtopk)));

  // output_fields: null = all fields; otherwise the projection set. Copied out
  // eagerly so no JNI reference is held across the zvec call (Rule 1).
  std::vector<std::string> out_fields_storage;
  std::vector<const char*> out_field_ptrs;
  if (joutputFields != nullptr) {
    jsize ofn = env->GetArrayLength(joutputFields);
    if (env->ExceptionCheck()) return nullptr;
    out_fields_storage.resize(ofn);
    out_field_ptrs.resize(ofn);
    for (jsize i = 0; i < ofn; i++) {
      jstring jf = static_cast<jstring>(env->GetObjectArrayElement(joutputFields, i));
      if (env->ExceptionCheck()) return nullptr;
      out_fields_storage[i] = jstring_to_std(env, jf);
      env->DeleteLocalRef(jf);
      if (env->ExceptionCheck()) return nullptr;
      out_field_ptrs[i] = out_fields_storage[i].c_str();
    }
    ZVEC_CHECK_JNI_JINT(env,
        zvec_vector_query_set_output_fields(
            query, out_field_ptrs.data(), static_cast<size_t>(ofn)));
  }

  // Optional filter — raw SQL-ish text (escape user values; see QueryRequest).
  if (jfilter != nullptr) {
    std::string filter = jstring_to_std(env, jfilter);
    if (env->ExceptionCheck()) return nullptr;
    ZVEC_CHECK_JNI_JINT(env,
        zvec_vector_query_set_filter(query, filter.c_str()));
  }

  // Modality: vector mode if a query vector is present, else FTS mode. The Kotlin
  // side guarantees exactly-one is set; here `jvector != null` wins the branch.
  if (jvector != nullptr) {
    jsize len = env->GetArrayLength(jvector);
    if (env->ExceptionCheck()) return nullptr;
    // GetFloatArrayElements copies (or pins, then we JNI_ABORT-release — no
    // writeback); either way we hand zvec a contiguous FP32 buffer of exactly
    // len*4 bytes. Eager copy, no GetPrimitiveArrayCritical (Phase-0 Rule 1/2).
    jfloat* vec = env->GetFloatArrayElements(jvector, nullptr);
    if (!vec) {
      if (!env->ExceptionCheck()) {
        zvec_throw(env, ZVEC_ERROR_INTERNAL_ERROR, "GetFloatArrayElements returned null");
      }
      return nullptr;
    }
    zvec_error_code_t rc = zvec_vector_query_set_query_vector(
        query, vec, static_cast<size_t>(len) * sizeof(float));
    env->ReleaseFloatArrayElements(jvector, vec, JNI_ABORT);
    ZVEC_CHECK_JNI_JINT(env, rc);
  } else if (jfts != nullptr) {
    // Pure-FTS: attach an fts_t payload carrying the match string. set_fts
    // COPIES the payload into the query, so the FtsGuard still owns + frees fts.
    std::string fts_match = jstring_to_std(env, jfts);
    if (env->ExceptionCheck()) return nullptr;
    zvec_fts_t* fts = zvec_fts_create();
    ZVEC_CHECK_PTR(env, fts, "fts_create");
    FtsGuard fts_guard{fts};
    ZVEC_CHECK_JNI_JINT(env,
        zvec_fts_set_match_string(fts, fts_match.c_str()));
    ZVEC_CHECK_JNI_JINT(env, zvec_vector_query_set_fts(query, fts));
  }
  // else: both null — the Kotlin side rejects this; defending in depth leaves
  // the query unconfigured, and zvec_collection_query will return an error.

  zvec_doc_t** docs = nullptr;
  size_t result_count = 0;
  ZVEC_CHECK_JNI_JINT(env,
      zvec_collection_query(col, query, &docs, &result_count));

  DocsGuard docs_guard{docs, result_count};
  return collect_docs(env, col, docs, result_count);
}

} // extern "C"
