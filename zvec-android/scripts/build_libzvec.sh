#!/bin/bash
set -e

# build_libzvec.sh - builds the zvec C API shared library for a specified Android ABI.
# Usage:
#   ./scripts/build_libzvec.sh [arm64-v8a|x86_64] [Debug|Release]

ABI=$1
BUILD_TYPE=${2:-"Release"}

if [ "$ABI" != "arm64-v8a" ] && [ "$ABI" != "x86_64" ]; then
    echo "Usage: $0 [arm64-v8a|x86_64] [Debug|Release]"
    exit 1
fi

SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"
ZVEC_ANDROID_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
REPO_ROOT="$(cd "$ZVEC_ANDROID_DIR/.." && pwd)"
ZVEC_SUBMODULE_DIR="$ZVEC_ANDROID_DIR/third_party/zvec"

# Resolve the Android SDK directory once from the standard sources (no hardcoded
# paths — every contributor's machine is different). Order: local.properties,
# then the conventional env vars. Callers can override via ANDROID_SDK_ROOT.
resolve_sdk_dir() {
    if [ -f "$REPO_ROOT/local.properties" ]; then
        local d
        d=$(grep 'sdk.dir' "$REPO_ROOT/local.properties" | cut -d'=' -f2 | xargs)
        if [ -n "$d" ] && [ -d "$d" ]; then echo "$d"; return; fi
    fi
    if [ -n "$ANDROID_SDK_ROOT" ] && [ -d "$ANDROID_SDK_ROOT" ]; then echo "$ANDROID_SDK_ROOT"; return; fi
    if [ -n "$ANDROID_HOME" ] && [ -d "$ANDROID_HOME" ]; then echo "$ANDROID_HOME"; return; fi
    return 1
}

# Add SDK CMake to PATH if cmake is not on host PATH
if ! command -v cmake &> /dev/null; then
    SDK_DIR="$(resolve_sdk_dir)" || true
    if [ -d "$SDK_DIR/cmake" ]; then
        LATEST_CMAKE_BIN=$(ls -d "$SDK_DIR"/cmake/*/bin/cmake 2>/dev/null | sort -V | tail -n 1)
        if [ -n "$LATEST_CMAKE_BIN" ]; then
            export PATH="$(dirname "$LATEST_CMAKE_BIN"):$PATH"
            echo "Added SDK CMake to PATH: $(dirname "$LATEST_CMAKE_BIN")"
        fi
    fi
fi

# 1. Resolve Android NDK
if [ -z "$ANDROID_NDK_HOME" ]; then
    SDK_DIR="$(resolve_sdk_dir)" || true
    if [ -n "$SDK_DIR" ]; then
        ANDROID_NDK_HOME="$SDK_DIR/ndk/30.0.14904198"
    fi
fi

# Missing NDK is a hard, explained error — never guess a per-user path.
if [ -z "$ANDROID_NDK_HOME" ] || [ ! -d "$ANDROID_NDK_HOME" ]; then
    echo "ERROR: Android NDK r30 (30.0.14904198) not found."
    echo "       Set ANDROID_NDK_HOME, ANDROID_SDK_ROOT, or ANDROID_HOME,"
    echo "       or add 'sdk.dir=<path>' to local.properties. Resolved: ${ANDROID_NDK_HOME:-<unset>}"
    exit 1
fi

echo "Using NDK: $ANDROID_NDK_HOME"
echo "Building zvec for ABI: $ABI, Build Type: $BUILD_TYPE"

# Pin the zvec submodule to the release tag recorded in ZVEC_VERSION.
# The compiled .so must always come from the recorded tag, never whatever the
# worktree happens to be on — otherwise the binary silently drifts from
# ZVEC_VERSION (and the JNI layer, which is grounded against a specific c_api.h).
# `git describe --tags` (baked into the .so as ZVEC_VERSION_STRING) appends
# "-N-g<sha>" the moment HEAD moves past the tag, and the instrumentation test
# asserts exact equality, so any drift fails the build loudly. See ZVEC_PHASE0.md Q10.
ZVEC_TAG="$(cat "$ZVEC_ANDROID_DIR/ZVEC_VERSION")"
echo ">>> Pinning zvec submodule to $ZVEC_TAG (from ZVEC_VERSION)"
git -C "$ZVEC_SUBMODULE_DIR" checkout "$ZVEC_TAG" 2>/dev/null || {
    echo "ERROR: could not check out $ZVEC_TAG in $ZVEC_SUBMODULE_DIR."
    echo "       run: git -C \"$ZVEC_SUBMODULE_DIR\" fetch --tags"
    exit 1
}
# Align nested submodules (arrow, rocksdb, protobuf, ...) to the tag's pointers.
git -C "$ZVEC_SUBMODULE_DIR" submodule update --init --recursive

# 2. Build host protoc
HOST_BUILD_DIR="$ZVEC_ANDROID_DIR/build/host"
CORE_COUNT=$(sysctl -n hw.ncpu 2>/dev/null || nproc 2>/dev/null || echo 4)

echo ">>> Step 1: Building protoc for host..."
if [ ! -f "$HOST_BUILD_DIR/bin/protoc" ]; then
    cmake -S "$ZVEC_SUBMODULE_DIR" -B "$HOST_BUILD_DIR" \
        -DCMAKE_BUILD_TYPE="Release" \
        -DCMAKE_TOOLCHAIN_FILE="" \
        -G Ninja \
        -DCMAKE_POLICY_VERSION_MINIMUM=3.5
    cmake --build "$HOST_BUILD_DIR" --target protoc -j"$CORE_COUNT"
else
    echo "  (cached - skipping host protoc build)"
fi
PROTOC_EXECUTABLE="$HOST_BUILD_DIR/bin/protoc"
echo "Host protoc is at: $PROTOC_EXECUTABLE"

# 3. Cross-compile zvec C API for Android ABI
BUILD_DIR="$ZVEC_ANDROID_DIR/build/android_${ABI}"
CMAKE_TOOLCHAIN_FILE="$ANDROID_NDK_HOME/build/cmake/android.toolchain.cmake"

# Clean CMake cache to avoid configuration mismatches
rm -f "$BUILD_DIR/CMakeCache.txt"

# Reset submodules to clean state to avoid host/Android patch conflicts
echo ">>> Resetting submodules to clean state before cross-compilation..."
git -C "$ZVEC_SUBMODULE_DIR" checkout -- .
git -C "$ZVEC_SUBMODULE_DIR" clean -ffxd
git -C "$ZVEC_SUBMODULE_DIR" submodule foreach --recursive 'git checkout -- . && git clean -ffxd'

echo ">>> Step 2: Cross-compiling zvec for Android ($ABI)..."
cmake -S "$ZVEC_SUBMODULE_DIR" -B "$BUILD_DIR" -G Ninja \
    -DANDROID_NDK="$ANDROID_NDK_HOME" \
    -DCMAKE_TOOLCHAIN_FILE="$CMAKE_TOOLCHAIN_FILE" \
    -DANDROID_ABI="$ABI" \
    -DANDROID_NATIVE_API_LEVEL="29" \
    -DANDROID_STL="c++_static" \
    -DCMAKE_BUILD_TYPE="$BUILD_TYPE" \
    -DBUILD_PYTHON_BINDINGS=OFF \
    -DBUILD_TOOLS=OFF \
    -DENABLE_NATIVE=OFF \
    -DAUTO_DETECT_ARCH=OFF \
    -DGLOBAL_CC_PROTOBUF_PROTOC="$PROTOC_EXECUTABLE" \
    -DCMAKE_SHARED_LINKER_FLAGS="-Wl,-z,max-page-size=16384" \
    -DCMAKE_POLICY_VERSION_MINIMUM=3.5


# Build target zvec_c_api
cmake --build "$BUILD_DIR" --target zvec_c_api -j"$CORE_COUNT"

# 4. Copy and rename compiled shared library to jniLibs
JNI_LIBS_DIR="$ZVEC_ANDROID_DIR/src/main/jniLibs/$ABI"
mkdir -p "$JNI_LIBS_DIR"

COMPILED_SO="$BUILD_DIR/lib/libzvec_c_api.so"
if [ ! -f "$COMPILED_SO" ]; then
    COMPILED_SO="$BUILD_DIR/src/binding/c/libzvec_c_api.so"
fi

if [ ! -f "$COMPILED_SO" ]; then
    echo "ERROR: Compiled library not found at $COMPILED_SO"
    exit 1
fi

echo "Copying $COMPILED_SO to $JNI_LIBS_DIR/libzvec_c_api.so"
cp "$COMPILED_SO" "$JNI_LIBS_DIR/libzvec_c_api.so"
echo "Build and copy successful for $ABI!"
