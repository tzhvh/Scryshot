#include <jni.h>
#include "zvec_jni_marshalling.h"

jclass g_zvec_exception_class = nullptr;
jmethodID g_zvec_exception_ctor = nullptr;

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* reserved) {
    JNIEnv* env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
        return JNI_ERR;
    }

    jclass local_class = env->FindClass("io/github/tzhvh/scryernext/zvec/ZvecException");
    if (!local_class) {
        return JNI_ERR;
    }

    g_zvec_exception_class = static_cast<jclass>(env->NewGlobalRef(local_class));
    env->DeleteLocalRef(local_class);
    if (!g_zvec_exception_class) {
        return JNI_ERR;
    }

    g_zvec_exception_ctor = env->GetMethodID(g_zvec_exception_class, "<init>", "(ILjava/lang/String;)V");
    if (!g_zvec_exception_ctor) {
        return JNI_ERR;
    }

    return JNI_VERSION_1_6;
}

JNIEXPORT void JNICALL JNI_OnUnload(JavaVM* vm, void* reserved) {
    JNIEnv* env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) == JNI_OK) {
        if (g_zvec_exception_class) {
            env->DeleteGlobalRef(g_zvec_exception_class);
            g_zvec_exception_class = nullptr;
        }
    }
}
