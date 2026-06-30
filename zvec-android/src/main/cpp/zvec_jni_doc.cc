#include <jni.h>
#include <cstdint>
#include <cstring>
#include <string>
#include <vector>
#include <zvec/c_api.h>
#include "zvec_jni_marshalling.h"

// Issue 03: the single-doc write path (insert / upsert / delete) + a minimal
// typed fetch read helper for the round-trip test.
//
// The builder's typed fields arrive as parallel primitive arrays (DocDescriptor
// in Kotlin). This layer does ONE linear pass over them, building one zvec_doc_t
// under DocGuard and dispatching each field to the single type-erased
// zvec_doc_add_field_by_value call, computing its EXACT per-type byte size.
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

// Write op selector (mirror ZvecCollection's call sites). Insert vs. upsert
// select different C _with_results variants over the same doc-build path.
constexpr int OP_INSERT = 0;
constexpr int OP_UPSERT = 1;

// Run the selected single-doc write via the `_with_results` variant and surface
// the per-doc code. Returns the C per-doc code (ZVEC_OK on success); on a
// non-OK code throws ZvecException(code, message) BEFORE returning. The bare
// zvec_collection_insert/upsert return ZVEC_OK even on per-doc failure (they
// only bump error_count), so the `_with_results` variant is mandatory to get
// the real ALREADY_EXISTS / NOT_FOUND / … code the SDK contract promises.
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

} // namespace

extern "C" {

// Single-doc insert / upsert. Builds the zvec_doc_t from the DocDescriptor
// arrays, dispatching each field to zvec_doc_add_field_by_value with its exact
// byte size, then runs the `_with_results` write and surfaces the per-doc code.
// Returns the written pk (a new jstring) on success; throws on any failure.
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

  // Bulk-copy the per-type value arrays once (eager copy, Rule 1: copy before
  // touching zvec, release before the doc build). The fixed scalars use Get*Region
  // into stack/sized buffers; vectors fetch per-field below.
  jint n = jfieldCount;
  if (n < 0) {
    zvec_throw(env, ZVEC_ERROR_INVALID_ARGUMENT, "Negative field count");
    return nullptr;
  }

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

  std::string message;
  ZVEC_CHECK_JNI_JINT(env, run_with_results(env, col, jop, doc, message));
  return env->NewStringUTF(pk.c_str());
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

  if (found == 0 || !docs[0]) {
    zvec_docs_free(docs, found);
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
  if (!result || env->ExceptionCheck()) { zvec_docs_free(docs, found); return nullptr; }

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
    if (env->ExceptionCheck()) { zvec_docs_free(docs, found); return nullptr; }
    if (boxed) {
      env->SetObjectArrayElement(result, 2 + i, boxed);
      env->DeleteLocalRef(boxed);
    }
  }

  zvec_docs_free(docs, found);
  return result;
}

} // extern "C"
