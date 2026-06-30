package io.github.tzhvh.scryernext.zvec

object ZvecNative {
    init {
        System.loadLibrary("zvec_c_api")
        System.loadLibrary("zvec_jni")
    }

    // @JvmStatic: these are native methods on a Kotlin object. Without it, kotlinc
    // emits them as INSTANCE methods, so JNI passes the singleton jobject (not the
    // jclass) as the 2nd arg — a type contract violation that only happens to work
    // because jobject/jclass are both opaque pointers at the ABI level. @JvmStatic
    // makes them true statics, so JNI hands jclass, matching the C++ signatures
    // (Java_..._ZvecNative_native*(JNIEnv*, jclass, ...)).
    @JvmStatic external fun nativeGetVersion(): String
    @JvmStatic external fun nativeCreateAndOpen(path: String, schemaName: String): Long
    // title + content both populated: the probe schema declares two NON-nullable
    // string fields, so a valid doc must supply both. Phase 1 replaces this with a
    // real typed builder.
    @JvmStatic external fun nativeInsertString(handle: Long, pk: String, title: String, content: String): Int
    @JvmStatic external fun nativeClose(handle: Long)
}
