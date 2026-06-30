#include <jni.h>
#include <string>
#include <zvec/c_api.h>
#include "zvec_jni_marshalling.h"

extern "C" {

JNIEXPORT jstring JNICALL
Java_io_github_tzhvh_scryernext_zvec_ZvecNative_nativeGetVersion(
    JNIEnv* env, jclass) {
  const char* version = zvec_get_version();
  return env->NewStringUTF(version);
}

JNIEXPORT jlong JNICALL
Java_io_github_tzhvh_scryernext_zvec_ZvecNative_nativeCreateAndOpen(
    JNIEnv* env, jclass, jstring jpath, jstring jschemaName) {

  std::string path       = jstring_to_std(env, jpath);
  std::string schemaName = jstring_to_std(env, jschemaName);
  if (env->ExceptionCheck()) return 0;

  // All native objects are guard-owned: on any exit (incl. a macro throw-return)
  // the guards free them, so there is no manual cleanup in this function. A prior
  // version freed `options` by hand across 4 error branches — fragile and the
  // reason OptionsGuard exists.
  zvec_collection_options_t* options = zvec_collection_options_create();
  ZVEC_CHECK_PTR(env, options, "collection_options_create");
  OptionsGuard options_guard{options};
  zvec_collection_options_set_enable_mmap(options, true);

  zvec_collection_schema_t* schema = zvec_collection_schema_create(schemaName.c_str());
  ZVEC_CHECK_PTR(env, schema, "collection_schema_create");
  SchemaGuard schema_guard{schema};

  // Two ordinary, NON-nullable content fields (ZVEC_PHASE0.md Q8): nullable=false
  // is load-bearing here — it makes the round-trip test exercise the engine's
  // "required field not provided" validation path, which nullable=true bypassed.
  for (const char* fname : {"title", "content"}) {
    FieldSchemaGuard g;
    g.f = zvec_field_schema_create(fname, ZVEC_DATA_TYPE_STRING, false, 0);
    ZVEC_CHECK_PTR(env, g.f, "field_schema_create");
    ZVEC_CHECK_JNI_JLONG(env, zvec_collection_schema_add_field(schema, g.f));
  }

  zvec_collection_t* col = nullptr;
  ZVEC_CHECK_JNI_JLONG(env,
      zvec_collection_create_and_open(path.c_str(), schema, options, &col));
  // create_and_open transfers ownership of `col`; the guards free schema/options.
  return reinterpret_cast<jlong>(col);
}

JNIEXPORT jint JNICALL
Java_io_github_tzhvh_scryernext_zvec_ZvecNative_nativeInsertString(
    JNIEnv* env, jclass, jlong handle, jstring jpk, jstring jtitle, jstring jcontent) {

  zvec_collection_t* col = reinterpret_cast<zvec_collection_t*>(handle);
  if (!col) {
    zvec_throw(env, ZVEC_ERROR_INVALID_ARGUMENT, "Collection handle is null");
    return 0;
  }

  std::string pk      = jstring_to_std(env, jpk);
  std::string title   = jstring_to_std(env, jtitle);
  std::string content = jstring_to_std(env, jcontent);
  if (env->ExceptionCheck()) return 0;

  zvec_doc_t* doc = zvec_doc_create();
  ZVEC_CHECK_PTR(env, doc, "doc_create");
  DocGuard doc_guard{doc};

  zvec_doc_set_pk(doc, pk.c_str());

  // Both schema fields are NON-nullable, so a valid doc must supply both. The
  // type-erased value path is identical for any field; populating two exercises
  // multi-field dispatch on the type-erased add_field_by_value path (Q8).
  // _JINT (not _JLONG): this function returns jint; the macro must match.
  ZVEC_CHECK_JNI_JINT(env,
      zvec_doc_add_field_by_value(doc, "title", ZVEC_DATA_TYPE_STRING, title.c_str(), title.size()));
  ZVEC_CHECK_JNI_JINT(env,
      zvec_doc_add_field_by_value(doc, "content", ZVEC_DATA_TYPE_STRING, content.c_str(), content.size()));

  const zvec_doc_t* docs[1] = {doc};
  size_t success_count = 0;
  size_t error_count = 0;
  ZVEC_CHECK_JNI_JINT(env, zvec_collection_insert(col, docs, 1, &success_count, &error_count));

  return static_cast<jint>(success_count);
}

JNIEXPORT void JNICALL
Java_io_github_tzhvh_scryernext_zvec_ZvecNative_nativeClose(
    JNIEnv* env, jclass, jlong handle) {
  zvec_collection_t* col = reinterpret_cast<zvec_collection_t*>(handle);
  if (col) {
    ZVEC_CHECK_JNI_VOID(env, zvec_collection_close(col));
  }
}

} // extern "C"
