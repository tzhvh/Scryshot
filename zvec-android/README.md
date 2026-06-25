# zvec Android JNI Module

This module packages the **zvec** vector database native library and its thin JNI bridge into an Android Archive (AAR) for ScreenshotGo.

---

## 1. Prerequisites & Toolchain

To build this module, you need the following host tools:
- **Android NDK**: Version `30.0.14904198` (pinned in `build.gradle.kts`).
- **CMake**: Version `3.22.1+` (provided by Android SDK or system).
- **Ninja**: Build system (highly recommended; Make will be used as a fallback).
- **Host C++ Compiler**: `gcc`, `clang`, or Xcode Command Line Tools (required for building the host-side `protoc` compiler bootstrap step).
- **`ANDROID_NDK_HOME`**: Environment variable pointing at the NDK. If unset, the build script resolves the SDK from `ANDROID_SDK_ROOT`/`ANDROID_HOME`, or from `sdk.dir` in `local.properties`, then looks for `ndk/30.0.14904198` under it. If none resolve, it errors with instructions rather than guessing a path.

---

## 2. How to Build (The Two-Step Dance)

Due to zvec's protobuf host bootstrap requirements, the build is split into two steps:

### Step 1: Cross-compile `libzvec_c_api.so` for each ABI
Run the build script once per target ABI to compile the host `protoc` and the cross-compiled `libzvec_c_api.so`:
```bash
# In the repository root:
./zvec-android/scripts/build_libzvec.sh x86_64
./zvec-android/scripts/build_libzvec.sh arm64-v8a
```
This builds and copies the outputs to `zvec-android/src/main/jniLibs/<abi>/libzvec_c_api.so`. (The name is kept as the upstream `zvec_c_api` target rather than renamed to `libzvec` — the `.so`'s embedded `SONAME` must match the file name or `loadLibrary` throws `UnsatisfiedLinkError`.)

### Step 2: Build the AAR
Build the Android library module using Gradle. This compiles the thin `libzvec_jni.so` layer and packages both libraries into the AAR:
```bash
./gradlew :zvec-android:assembleDebug
```

---

## 3. How to Verify

### Layer 1: Packaging Check
To verify that all required native libraries (`libzvec_c_api.so` and `libzvec_jni.so` for both `arm64-v8a` and `x86_64`) are packaged:
```bash
./gradlew :zvec-android:check
```

### Layer 2: Instrumentation Tests
Run the JNI round-trip tests on a connected emulator (e.g., `x86_64`):
```bash
./gradlew :zvec-android:connectedDebugAndroidTest
```

> **Note on devices reached via `adb` port-forwarding / `socat`.** Gradle's
> Unified Test Platform (UTP) drives the device over a gRPC channel and collects
> results over it. Over an `adb`-host redirect (e.g. a `socat` tunnel to a remote
> `adb` host such as `172.18.192.1:5038`) that protocol is too chatty and UTP
> fails to report results — `connectedDebugAndroidTest` prints "There were
> failing tests" with a **0-tests** summary, even though the tests pass. This is
> an environment artifact, not a code regression. In that setup, run the
> instrumentation directly (the path Gradle's legacy bridge uses):
> ```bash
> ./gradlew :zvec-android:assembleDebugAndroidTest
> adb install -r zvec-android/build/outputs/apk/androidTest/debug/*.apk
> adb shell am instrument -w io.github.tzhvh.scryernext.zvec.test/androidx.test.runner.AndroidJUnitRunner
> ```
> A green `OK (N tests)` here is the authoritative on-device result.

---

## 4. Submodule Bump Procedure

The `zvec` submodule is pinned to a specific release tag, documented in `zvec-android/ZVEC_VERSION`. Do not track `main`.

To bump the version:
1. Fetch and checkout the new tag in the submodule:
   ```bash
   git -C zvec-android/third_party/zvec fetch && git -C zvec-android/third_party/zvec checkout <new-tag>
   ```
2. Inspect `zvec-android/third_party/zvec/src/include/zvec/c_api.h` for changes. Update the JNI code in `zvec-android/src/main/cpp/` if necessary.
3. Update `zvec-android/ZVEC_VERSION` file to match `<new-tag>`.
4. Clean and re-run Step 1 and Step 2 above.
5. Verify the bump by running the packaging check and instrumentation tests.
6. Commit the submodule pointer, `ZVEC_VERSION`, and JNI changes together.

---

## 5. JNI Crash Runbook

If the JVM/Android process crashes due to a native segmentation fault (`SIGSEGV` / signal 11) or abort, JUnit test runners cannot capture the C++ stack trace. Use `adb logcat` to diagnose:

1. Start the tests:
   ```bash
   ./gradlew :zvec-android:connectedDebugAndroidTest
   ```
2. If the test freezes or terminates abruptly, dump the logcat output:
   ```bash
   adb logcat -d | grep -A30 'tombstone\|signal 11\|libzvec'
   ```
3. Look for the crash tombstone mapping the crash site in `libzvec_jni.so` or `libzvec_c_api.so`.
