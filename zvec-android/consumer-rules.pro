# ZvecNative: the .so resolves native methods by class name; do not rename.
-keep class io.github.tzhvh.scryernext.zvec.ZvecNative {
    native <methods>;
}

# ZvecException: constructed only from JNI (invisible to R8). Keep the ctor
# the C++ calls and the fields it reads.
-keep class io.github.tzhvh.scryernext.zvec.ZvecException {
    public <init>(int, java.lang.String);
}
