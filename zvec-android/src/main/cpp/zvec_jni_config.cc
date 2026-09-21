#include <jni.h>
#include <string>
#include <zvec/c_api.h>
#include "zvec_jni_marshalling.h"

// Issue 01: config + init JNI.
//
// nativeInitialize returns a jint code (0 = OK, 2 = ALREADY_EXISTS, …) and does
// NOT throw on a zvec-level failure. Kotlin's Zvec object maps the int to
// ZvecErrorCode and constructs ZvecException itself, so a second init surfaces as
// ALREADY_EXISTS (a return value Kotlin can branch on as the documented no-op)
// rather than a native exception the caller must catch.
//
// The macro family is therefore not used here (it throws + returns 0). Each
// config step returns its raw code so the guards clean up on the early-exit
// path and Kotlin sees the exact failing step's code.

extern "C" {

JNIEXPORT jint JNICALL
Java_io_github_tzhvh_scryernext_zvec_ZvecNative_nativeInitialize(
    JNIEnv* env, jclass,
    jboolean jnullConfig,
    jlong jmemoryLimitBytes,
    jint jqueryThreadCount,
    jint joptimizeThreadCount,
    jboolean jnullLogConfig,
    jint jlogLevel,
    jstring jlogDir,
    jstring jlogBasename,
    jlong jlogSizeMb,
    jlong jlogOverdueDays) {

  // NULL config = zvec's cgroup-derived defaults. Hand zvec NULL directly.
  if (jnullConfig == JNI_TRUE) {
    return static_cast<jint>(zvec_initialize(nullptr));
  }

  // Eagerly copy the jstrings before touching zvec (marshalling Rule 1).
  std::string logDir;
  std::string logBasename;
  if (jnullLogConfig == JNI_FALSE) {
    logDir = jstring_to_std(env, jlogDir);
    if (env->ExceptionCheck()) return static_cast<jint>(ZVEC_ERROR_INVALID_ARGUMENT);
    logBasename = jstring_to_std(env, jlogBasename);
    if (env->ExceptionCheck()) return static_cast<jint>(ZVEC_ERROR_INVALID_ARGUMENT);
  }

  zvec_config_data_t* config = zvec_config_data_create();
  if (!config) {
    return static_cast<jint>(ZVEC_ERROR_INTERNAL_ERROR);
  }
  ConfigDataGuard config_guard{config};

  // memoryLimitBytes == 0 is the "leave zvec's default" sentinel (the Kotlin
  // type is Long?; null maps to 0L here). A real limit is always > 0.
  if (jmemoryLimitBytes > 0) {
    zvec_error_code_t c = zvec_config_data_set_memory_limit(
        config, static_cast<uint64_t>(jmemoryLimitBytes));
    if (c != ZVEC_OK) return static_cast<jint>(c);
  }
  {
    zvec_error_code_t c = zvec_config_data_set_query_thread_count(
        config, static_cast<uint32_t>(jqueryThreadCount));
    if (c != ZVEC_OK) return static_cast<jint>(c);
  }
  {
    zvec_error_code_t c = zvec_config_data_set_optimize_thread_count(
        config, static_cast<uint32_t>(joptimizeThreadCount));
    if (c != ZVEC_OK) return static_cast<jint>(c);
  }

  if (jnullLogConfig == JNI_FALSE) {
    zvec_log_config_t* log = zvec_config_log_create_file(
        static_cast<zvec_log_level_t>(jlogLevel),
        logDir.c_str(), logBasename.c_str(),
        static_cast<uint32_t>(jlogSizeMb),
        static_cast<uint32_t>(jlogOverdueDays));
    if (!log) {
      return static_cast<jint>(ZVEC_ERROR_INTERNAL_ERROR);
    }
    LogConfigGuard log_guard{log};
    // set_log_config transfers ownership of `log` to `config` on success: after
    // a successful call config owns it (freed by config_guard), so release the
    // borrow guard. On failure the guard still owns `log` and frees it.
    zvec_error_code_t c = zvec_config_data_set_log_config(config, log);
    if (c != ZVEC_OK) return static_cast<jint>(c);
    log_guard.l = nullptr;
  }

  // zvec_initialize takes a const* (reads/copies; does not take ownership), so
  // config_guard frees config (and the log it adopted) on return.
  return static_cast<jint>(zvec_initialize(config));
}

JNIEXPORT jboolean JNICALL
Java_io_github_tzhvh_scryernext_zvec_ZvecNative_nativeIsInitialized(
    JNIEnv* env, jclass) {
  return zvec_is_initialized() ? JNI_TRUE : JNI_FALSE;
}

// Best-effort detail string for the last zvec-level failure. Returns null when
// zvec did not set one. Used to make a config/init failure debuggable without
// changing nativeInitialize's "return a code, don't throw" contract.
JNIEXPORT jstring JNICALL
Java_io_github_tzhvh_scryernext_zvec_ZvecNative_nativeGetLastError(
    JNIEnv* env, jclass) {
  char* msg = nullptr;
  zvec_get_last_error(&msg);
  if (!msg) return nullptr;
  jstring jmsg = env->NewStringUTF(msg);
  zvec_free(msg);
  return jmsg;
}

} // extern "C"
