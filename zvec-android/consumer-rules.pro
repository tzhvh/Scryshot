# ZvecNative: the .so resolves native methods by class name; do not rename.
-keep class io.github.tzhvh.scryernext.zvec.ZvecNative {
    native <methods>;
}

# ZvecException: constructed by JNI (invisible to R8). The bridge ctor is
# internal (code: Int, detail: String?) — R8 strips internal members.
-keep class io.github.tzhvh.scryernext.zvec.ZvecException {
    <init>(int, java.lang.String);
}
-keepclassmembers class io.github.tzhvh.scryernext.zvec.ZvecException {
    <init>(int, java.lang.String);
}
