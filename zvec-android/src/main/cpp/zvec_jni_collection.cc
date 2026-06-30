#include <jni.h>
#include <string>
#include <vector>
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

// ---- Stats + flush (issue 09) --------------------------------------------
// The collection-observability surface. `nativeStats` reads
// zvec_collection_get_stats (c_api.h:3048) into a value-type struct: it calls
// get_stats (returns a caller-owned zvec_collection_stats_t*), walks
// get_index_count + per-index get_index_name/get_index_completeness, COPIES
// docCount + each (name, completeness) pair into JVM objects, then frees the C
// handle via StatsGuard on any exit path. No handle escapes (same value-type
// invariant as collect_docs — ADR 0006). `nativeFlush` is the engine's
// durability flush (c_api.h:3016).
//
// Returns `Array<Any?>` = `[0]: Long docCount, [1]: Array<String> indexNames,
// [2]: FloatArray indexCompleteness` (indexNames[i] paired with
// indexCompleteness[i], index-aligned). The Kotlin side folds these into a
// CollectionStats(docCount, List<IndexStat>). docCount is a uint64_t at the C
// boundary (c_api.h:2477) → jlong.

JNIEXPORT jobjectArray JNICALL
Java_io_github_tzhvh_scryernext_zvec_ZvecNative_nativeStats(
    JNIEnv* env, jclass, jlong handle) {
  zvec_collection_t* col = reinterpret_cast<zvec_collection_t*>(handle);
  if (!col) {
    zvec_throw(env, ZVEC_ERROR_INVALID_ARGUMENT, "Collection handle is null");
    return nullptr;
  }

  zvec_collection_stats_t* raw_stats = nullptr;
  // get_stats takes the collection as `const` (c_api.h:3048) but writes the
  // caller-owned handle through the out-param; a non-OK throws ZvecException
  // before the guard is constructed, so there is nothing to free in that path.
  ZVEC_CHECK_JNI_JLONG(env, zvec_collection_get_stats(col, &raw_stats));
  if (!raw_stats) {
    zvec_throw(env, ZVEC_ERROR_INTERNAL_ERROR,
               "zvec_collection_get_stats returned OK but a null stats handle");
    return nullptr;
  }
  StatsGuard stats_guard{raw_stats};

  const uint64_t doc_count =
      zvec_collection_stats_get_doc_count(raw_stats);
  const size_t index_count =
      zvec_collection_stats_get_index_count(raw_stats);

  // docCount → Long.
  jclass long_class = env->FindClass("java/lang/Long");
  jmethodID long_value_of = env->GetStaticMethodID(
      long_class, "valueOf", "(J)Ljava/lang/Long;");
  jobject jdoc_count =
      env->CallStaticObjectMethod(long_class, long_value_of,
                                  static_cast<jlong>(doc_count));
  // A pending exception from the boxing (JVM OOM) would make the array-building
  // below unsafe; bail before touching JNI further (same discipline as
  // jstring_to_std's guard).
  if (env->ExceptionCheck()) return nullptr;

  // indexNames → String[] (the engine iterates an unordered_map, so order is a
  // hash order, not schema order — the Kotlin IndexStat list inherits it).
  jclass string_class = env->FindClass("java/lang/String");
  jobjectArray jindex_names =
      env->NewObjectArray(static_cast<jsize>(index_count), string_class, nullptr);
  ZVEC_CHECK_PTR(env, jindex_names, "NewObjectArray(indexNames)");

  // indexCompleteness → float[].
  jfloatArray jindex_completeness = env->NewFloatArray(static_cast<jsize>(index_count));
  ZVEC_CHECK_PTR(env, jindex_completeness, "NewFloatArray(indexCompleteness)");

  std::vector<float> completeness_buf;
  completeness_buf.reserve(index_count);
  for (size_t i = 0; i < index_count; ++i) {
    const char* name = zvec_collection_stats_get_index_name(raw_stats, i);
    // get_index_name returns nullptr only on an out-of-range i (c_api.cc:1274)
    // — impossible here since i < index_count. Treat a null defensively as an
    // internal error rather than feeding a nullptr to NewStringUTF.
    if (!name) {
      zvec_throw(env, ZVEC_ERROR_INTERNAL_ERROR,
                 "zvec_collection_stats_get_index_name returned null");
      return nullptr;
    }
    jstring jname = env->NewStringUTF(name);
    if (env->ExceptionCheck()) return nullptr;
    env->SetObjectArrayElement(jindex_names, static_cast<jsize>(i), jname);
    if (env->ExceptionCheck()) return nullptr;
    env->DeleteLocalRef(jname);

    completeness_buf.push_back(
        zvec_collection_stats_get_index_completeness(raw_stats, i));
  }
  env->SetFloatArrayRegion(jindex_completeness, 0,
                           static_cast<jsize>(index_count),
                           completeness_buf.data());
  if (env->ExceptionCheck()) return nullptr;

  // Pack [docCount, indexNames, indexCompleteness] into Object[].
  jclass object_class = env->FindClass("java/lang/Object");
  jobjectArray result = env->NewObjectArray(3, object_class, nullptr);
  ZVEC_CHECK_PTR(env, result, "NewObjectArray(stats result)");
  env->SetObjectArrayElement(result, 0, jdoc_count);
  env->SetObjectArrayElement(result, 1, jindex_names);
  env->SetObjectArrayElement(result, 2, jindex_completeness);

  // StatsGuard frees the C stats handle here; the (name, completeness) data has
  // been copied out, so the borrowed index-name pointers are no longer needed.
  return result;
}

JNIEXPORT void JNICALL
Java_io_github_tzhvh_scryernext_zvec_ZvecNative_nativeFlush(
    JNIEnv* env, jclass, jlong handle) {
  zvec_collection_t* col = reinterpret_cast<zvec_collection_t*>(handle);
  if (!col) {
    zvec_throw(env, ZVEC_ERROR_INVALID_ARGUMENT, "Collection handle is null");
    return;
  }
  ZVEC_CHECK_JNI_VOID(env, zvec_collection_flush(col));
}

// ---- Runtime index DDL (issue 10) -----------------------------------------
// createIndex / dropIndex / optimize on an OPEN collection (Q8 runtime-DDL
// scope). nativeCreateIndex builds the one-field index-params handle via the
// shared build_index_params (the SAME per-arm switch schema construction uses —
// moved to zvec_jni_marshalling.h as `inline`), guards it, and calls
// zvec_collection_create_index, which DEEP-COPIES the params (c_api.h:3075:
// "caller retains ownership and should call zvec_index_params_destroy()"). The
// IndexParamsGuard always frees the transient params on any exit path — success
// OR failure — because the engine took a copy, never the handle itself (same
// discipline as the FtsGuard/SubQueryGuard pattern; NOT adopt-on-success).
//
// The params arrive as a SINGLE field's slots in the descriptor arrays
// (field_index=0, fts_base=0). This mirrors SchemaDescriptor.encodeIndexParams
// on the Kotlin side, which flattens one IndexParams into a one-slot descriptor
// — reusing build_index_params unchanged rather than a second per-arm switch.

JNIEXPORT void JNICALL
Java_io_github_tzhvh_scryernext_zvec_ZvecNative_nativeCreateIndex(
    JNIEnv* env, jclass, jlong handle, jstring jfield,
    jint jindexKind,
    jintArray jindexM, jintArray jindexEfConstruction,
    jintArray jindexNList, jintArray jindexNIters,
    jintArray jindexMetric,
    jbooleanArray jindexEnableRangeOpt,
    jobjectArray jftsTokenizer, jobjectArray jftsExtraParams,
    jobjectArray jftsFilterNames, jintArray jftsFilterFieldIndices) {
  zvec_collection_t* col = reinterpret_cast<zvec_collection_t*>(handle);
  if (!col) {
    zvec_throw(env, ZVEC_ERROR_INVALID_ARGUMENT, "Collection handle is null");
    return;
  }

  std::string field = jstring_to_std(env, jfield);
  if (env->ExceptionCheck()) return;

  // Single field → slot 0, fts_base 0 (no prior FTS fields to offset past).
  zvec_index_params_t* params = build_index_params(
      env, jindexKind,
      jindexM, jindexEfConstruction, jindexNList, jindexNIters, jindexMetric,
      jindexEnableRangeOpt, jftsTokenizer, jftsExtraParams,
      jftsFilterNames, jftsFilterFieldIndices,
      /*field_index=*/0, /*fts_base=*/0);
  if (env->ExceptionCheck()) return;
  IndexParamsGuard params_guard{params};

  if (!params) {
    // indexKind == NONE: a no-op create is a developer error (createIndex needs
    // real params), not a silent success. Surface it before touching the engine.
    zvec_throw(env, ZVEC_ERROR_INVALID_ARGUMENT,
               "createIndex: params had no index type (NONE)");
    return;
  }

  // create_index copies the params; params_guard frees our handle regardless.
  ZVEC_CHECK_JNI_VOID(env,
      zvec_collection_create_index(col, field.c_str(), params));
}

JNIEXPORT void JNICALL
Java_io_github_tzhvh_scryernext_zvec_ZvecNative_nativeDropIndex(
    JNIEnv* env, jclass, jlong handle, jstring jfield) {
  zvec_collection_t* col = reinterpret_cast<zvec_collection_t*>(handle);
  if (!col) {
    zvec_throw(env, ZVEC_ERROR_INVALID_ARGUMENT, "Collection handle is null");
    return;
  }
  std::string field = jstring_to_std(env, jfield);
  if (env->ExceptionCheck()) return;
  ZVEC_CHECK_JNI_VOID(env, zvec_collection_drop_index(col, field.c_str()));
}

JNIEXPORT void JNICALL
Java_io_github_tzhvh_scryernext_zvec_ZvecNative_nativeOptimize(
    JNIEnv* env, jclass, jlong handle) {
  zvec_collection_t* col = reinterpret_cast<zvec_collection_t*>(handle);
  if (!col) {
    zvec_throw(env, ZVEC_ERROR_INVALID_ARGUMENT, "Collection handle is null");
    return;
  }
  // Synchronous only — zvec_collection_optimize_async does not exist on the
  // pinned v0.5.1. The native call blocks until the index build/segment merge
  // finishes; the caller owns the dispatcher (ADR 0007), so it can run this on
  // Dispatchers.IO / a background scope as it sees fit.
  ZVEC_CHECK_JNI_VOID(env, zvec_collection_optimize(col));
}

} // extern "C"
