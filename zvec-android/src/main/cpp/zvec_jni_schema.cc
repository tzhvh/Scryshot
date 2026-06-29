#include <jni.h>
#include <string>
#include <vector>
#include <zvec/c_api.h>
#include "zvec_jni_marshalling.h"

// Issue 02: schema construction + create/open/close.
//
// The schema tree arrives as parallel primitive arrays (flattened by
// SchemaDescriptor in Kotlin). This layer does ONE linear pass over them, building
// the C handles in the deepest ownership chain the API has —
//
//     field_schema ──borrows──> index_params
//     collection_schema ──borrows──> field_schema
//
// — with every transient handle freed by a guard on ANY exit path (incl. a macro
// throw-return). zvec_field_schema_set_index_params deep-copies the params into
// the field, and zvec_collection_schema_add_field deep-copies the field into the
// schema, so the source handles stay caller-owned (freed by their guards) —
// matching the Rust oracle's add_field / set_index_params pattern.
//
// Validation runs AFTER the schema is built but BEFORE zvec_collection_create_and_open
// touches disk (c_api.h:2933). A failed validate throws ZvecException(INVALID_ARGUMENT,
// detail) and returns before create_and_open, so no partial directory lands on disk.
// This is the load-bearing fail-fast-with-reason assertion the issue pins.

namespace {

// Index-kind discriminator values (mirror SchemaDescriptor::IndexKind). Pinned
// explicit so a Kotlin reorder can't silently drift the int the JNI layer reads.
constexpr int INDEX_KIND_NONE = 0;
constexpr int INDEX_KIND_HNSW = 1;
constexpr int INDEX_KIND_IVF = 2;
constexpr int INDEX_KIND_FLAT = 3;
constexpr int INDEX_KIND_INVERT = 10;
constexpr int INDEX_KIND_FTS = 11;

// Build one field's index-params handle from the descriptor arrays. Returns null
// when the field has no index. On failure the caller's pending exception surfaces
// (set via zvec_throw by the setters); the returned handle, if any, is guard-owned
// by the caller. fts_filter_names is the flat-packed filter list; fts_base is this
// field's offset into it (the cumulative count of filters from prior FTS fields).
zvec_index_params_t* build_index_params(
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

// Build the full collection_schema_t from the descriptor arrays. Returns the
// schema handle (guard-owned by the caller via SchemaGuard at the call site, or
// freed here on a failure throw). On any failure, throws + returns nullptr.
zvec_collection_schema_t* build_schema(
    JNIEnv* env,
    jstring jschemaName,
    jint jfieldCount,
    jobjectArray jfieldNames,
    jintArray jfieldDataTypes,
    jbooleanArray jfieldNullable,
    jintArray jfieldDimensions,
    jintArray jfieldIndexTypes,
    jintArray jindexM, jintArray jindexEfConstruction,
    jintArray jindexNList, jintArray jindexNIters,
    jintArray jindexMetric,
    jbooleanArray jindexEnableRangeOpt,
    jobjectArray jftsTokenizer, jobjectArray jftsExtraParams,
    jobjectArray jftsFilterNames, jintArray jftsFilterFieldIndices) {

    std::string schemaName = jstring_to_std(env, jschemaName);
    if (env->ExceptionCheck()) return nullptr;

    zvec_collection_schema_t* schema = zvec_collection_schema_create(schemaName.c_str());
    ZVEC_CHECK_PTR(env, schema, "collection_schema_create");
    SchemaGuard schema_guard{schema};

    jint n = jfieldCount;
    int fts_filter_cursor = 0;  // running offset into the flat-packed ftsFilterNames

    std::vector<jint> types(n), dims(n), itypes(n);
    std::vector<jboolean> nullable(n);
    env->GetIntArrayRegion(jfieldDataTypes, 0, n, types.data());
    env->GetIntArrayRegion(jfieldDimensions, 0, n, dims.data());
    env->GetIntArrayRegion(jfieldIndexTypes, 0, n, itypes.data());
    env->GetBooleanArrayRegion(jfieldNullable, 0, n, nullable.data());
    if (env->ExceptionCheck()) return nullptr;

    for (jint i = 0; i < n; i++) {
        jstring jname = static_cast<jstring>(env->GetObjectArrayElement(jfieldNames, i));
        if (env->ExceptionCheck()) return nullptr;
        std::string name = jstring_to_std(env, jname);
        env->DeleteLocalRef(jname);
        if (env->ExceptionCheck()) return nullptr;

        zvec_field_schema_t* field = zvec_field_schema_create(
            name.c_str(), static_cast<zvec_data_type_t>(types[i]),
            nullable[i] == JNI_TRUE, static_cast<uint32_t>(dims[i]));
        ZVEC_CHECK_PTR(env, field, "field_schema_create");
        FieldSchemaGuard field_guard{field};

        // set_index_params deep-copies into the field; the field still owns its own
        // copy and the caller-owned `params` is freed by params_guard. add_field then
        // deep-copies the field into the schema. So both the params and the field are
        // double-owned during the call and freed by their guards afterward — matching
        // the Rust oracle's pattern.
        int this_filter_count = 0;
        if (itypes[i] == INDEX_KIND_FTS) {
            env->GetIntArrayRegion(jftsFilterFieldIndices, i, 1, &this_filter_count);
            if (env->ExceptionCheck()) return nullptr;
        }

        zvec_index_params_t* params = build_index_params(
            env, itypes[i],
            jindexM, jindexEfConstruction, jindexNList, jindexNIters, jindexMetric,
            jindexEnableRangeOpt, jftsTokenizer, jftsExtraParams,
            jftsFilterNames, jftsFilterFieldIndices,
            i, fts_filter_cursor);
        if (env->ExceptionCheck()) return nullptr;
        IndexParamsGuard params_guard{params};

        fts_filter_cursor += this_filter_count;

        if (params) {
            zvec_error_code_t c = zvec_field_schema_set_index_params(field, params);
            if (c != ZVEC_OK) { ZVEC_CHECK_JNI_JLONG(env, c); }
        }
        ZVEC_CHECK_JNI_JLONG(env, zvec_collection_schema_add_field(schema, field));
    }

    schema_guard.s = nullptr;  // transfer ownership to caller
    return schema;
}

// Build the options handle from the three scalars (OptionsGuard-owned by caller).
zvec_collection_options_t* build_options(
    JNIEnv* env, jboolean jenableMmap, jboolean jreadOnly, jlong jmaxBufferSize) {

    zvec_collection_options_t* options = zvec_collection_options_create();
    ZVEC_CHECK_PTR(env, options, "collection_options_create");
    OptionsGuard options_guard{options};

    ZVEC_CHECK_JNI_JLONG(env, zvec_collection_options_set_enable_mmap(
        options, jenableMmap == JNI_TRUE));
    ZVEC_CHECK_JNI_JLONG(env, zvec_collection_options_set_read_only(
        options, jreadOnly == JNI_TRUE));
    if (jmaxBufferSize > 0) {
        ZVEC_CHECK_JNI_JLONG(env, zvec_collection_options_set_max_buffer_size(
            options, static_cast<size_t>(jmaxBufferSize)));
    }

    options_guard.o = nullptr;
    return options;
}

} // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_io_github_tzhvh_scryernext_zvec_ZvecNative_nativeSchemaCreateAndOpen(
    JNIEnv* env, jclass,
    jstring jpath, jstring jschemaName,
    jint jfieldCount,
    jobjectArray jfieldNames, jintArray jfieldDataTypes,
    jbooleanArray jfieldNullable, jintArray jfieldDimensions,
    jintArray jfieldIndexTypes,
    jintArray jindexM, jintArray jindexEfConstruction,
    jintArray jindexNList, jintArray jindexNIters, jintArray jindexMetric,
    jbooleanArray jindexEnableRangeOpt,
    jobjectArray jftsTokenizer, jobjectArray jftsExtraParams,
    jobjectArray jftsFilterNames, jintArray jftsFilterFieldIndices,
    jboolean jenableMmap, jboolean jreadOnly, jlong jmaxBufferSize) {

  std::string path = jstring_to_std(env, jpath);
  if (env->ExceptionCheck()) return 0;

  zvec_collection_options_t* options = build_options(env, jenableMmap, jreadOnly, jmaxBufferSize);
  if (env->ExceptionCheck()) return 0;
  OptionsGuard options_guard{options};

  zvec_collection_schema_t* schema = build_schema(
      env, jschemaName, jfieldCount, jfieldNames, jfieldDataTypes, jfieldNullable,
      jfieldDimensions, jfieldIndexTypes,
      jindexM, jindexEfConstruction, jindexNList, jindexNIters, jindexMetric,
      jindexEnableRangeOpt, jftsTokenizer, jftsExtraParams,
      jftsFilterNames, jftsFilterFieldIndices);
  if (env->ExceptionCheck()) return 0;
  SchemaGuard schema_guard{schema};

  // ---- Implicit validation, BEFORE create_and_open touches disk (Q8) ----
  // zvec_collection_schema_validate runs the C validator and returns code +
  // message via zvec_string_t** (freed with zvec_free_string). A non-OK code
  // throws here, before zvec_collection_create_and_open — so an invalid schema
  // fails fast with a reason and leaves NO directory on disk.
  {
    zvec_string_t* err_msg = nullptr;
    zvec_error_code_t c = zvec_collection_schema_validate(schema, &err_msg);
    if (c != ZVEC_OK) {
      std::string msg;
      if (err_msg) {
        const char* s = zvec_string_c_str(err_msg);
        if (s) msg = s;
        zvec_free_string(err_msg);
      }
      if (msg.empty()) msg = zvec_code_label(c);
      zvec_throw(env, c, msg.c_str());
      return 0;
    }
    if (err_msg) zvec_free_string(err_msg);
  }

  zvec_collection_t* col = nullptr;
  ZVEC_CHECK_JNI_JLONG(env,
      zvec_collection_create_and_open(path.c_str(), schema, options, &col));
  // create_and_open transfers ownership of `col`; the guards free schema/options.
  return reinterpret_cast<jlong>(col);
}

JNIEXPORT jlong JNICALL
Java_io_github_tzhvh_scryernext_zvec_ZvecNative_nativeCollectionOpen(
    JNIEnv* env, jclass,
    jstring jpath, jboolean jenableMmap, jboolean jreadOnly, jlong jmaxBufferSize) {

  std::string path = jstring_to_std(env, jpath);
  if (env->ExceptionCheck()) return 0;

  zvec_collection_options_t* options = build_options(env, jenableMmap, jreadOnly, jmaxBufferSize);
  if (env->ExceptionCheck()) return 0;
  OptionsGuard options_guard{options};

  zvec_collection_t* col = nullptr;
  // No LOCK recovery here — that's the caller's concern (see the phase doc).
  ZVEC_CHECK_JNI_JLONG(env, zvec_collection_open(path.c_str(), options, &col));
  return reinterpret_cast<jlong>(col);
}

// Minimal fetch-by-pk read path for issue 02's reopen test. Builds a String[] of
// found pks; the JNI layer copies each pk out and frees the whole doc array via
// zvec_docs_free before returning (no per-doc handle escapes — the Q3 value-type
// pattern). Issue 05 lands the real projection surface; this is the smallest read
// that proves the reopened collection retained data.
JNIEXPORT jobjectArray JNICALL
Java_io_github_tzhvh_scryernext_zvec_ZvecNative_nativeFetchPks(
    JNIEnv* env, jclass, jlong handle, jobjectArray jpks) {

  zvec_collection_t* col = reinterpret_cast<zvec_collection_t*>(handle);
  if (!col) {
    zvec_throw(env, ZVEC_ERROR_INVALID_ARGUMENT, "Collection handle is null");
    return nullptr;
  }

  jsize n = env->GetArrayLength(jpks);
  if (n <= 0) {
    jclass str_class = env->FindClass("java/lang/String");
    return env->NewObjectArray(0, str_class, nullptr);
  }

  // Copy the pks into std::strings + a C-string pointer array (Rule 1: copy
  // before touching zvec; the pointer array borrows the std::string storage).
  std::vector<std::string> pks;
  std::vector<const char*> pk_ptrs;
  pks.reserve(static_cast<size_t>(n));
  pk_ptrs.reserve(static_cast<size_t>(n));
  for (jsize i = 0; i < n; i++) {
    jstring jpk = static_cast<jstring>(env->GetObjectArrayElement(jpks, i));
    if (env->ExceptionCheck()) return nullptr;
    pks.push_back(jstring_to_std(env, jpk));
    env->DeleteLocalRef(jpk);
    if (env->ExceptionCheck()) return nullptr;
    pk_ptrs.push_back(pks.back().c_str());
  }

  zvec_doc_t** docs = nullptr;
  size_t found = 0;
  ZVEC_CHECK_JNI_JLONG(env,
      zvec_collection_fetch(col, pk_ptrs.data(), pk_ptrs.size(),
                            /*output_fields=*/nullptr, /*output_field_count=*/0,
                            /*include_vector=*/false, &docs, &found));

  // Copy pks out into a Java String[] before freeing the doc array. Each doc's pk
  // pointer is borrowed into the doc (zvec_doc_get_pk_pointer); copy immediately.
  jclass str_class = env->FindClass("java/lang/String");
  jobjectArray result = env->NewObjectArray(static_cast<jsize>(found), str_class, nullptr);
  if (!result) {
    zvec_docs_free(docs, found);
    return nullptr;
  }
  for (size_t i = 0; i < found; i++) {
    const char* pk = docs[i] ? zvec_doc_get_pk_pointer(docs[i]) : nullptr;
    jstring jpk = env->NewStringUTF(pk ? pk : "");
    env->SetObjectArrayElement(result, static_cast<jsize>(i), jpk);
    env->DeleteLocalRef(jpk);
  }
  zvec_docs_free(docs, found);
  return result;
}

} // extern "C"
