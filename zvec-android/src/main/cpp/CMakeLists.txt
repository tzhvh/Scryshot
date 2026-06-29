cmake_minimum_required(VERSION 3.22.1)
project(zvec_jni LANGUAGES CXX)

# The prebuilt libzvec_c_api.so is a fixed binary, never compiled here — only linked.
# Gradle separately packages jniLibs/<abi>/libzvec_c_api.so into the AAR.
add_library(zvec_c_api SHARED IMPORTED)
set_target_properties(zvec_c_api PROPERTIES IMPORTED_LOCATION
    ${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libzvec_c_api.so)

add_library(zvec_jni SHARED
    zvec_jni_onload.cc
    zvec_jni_collection.cc
    zvec_jni_config.cc
    zvec_jni_schema.cc
)

target_include_directories(zvec_jni PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/../../../third_party/zvec/src/include
)

target_link_libraries(zvec_jni PRIVATE zvec_c_api log)
set_target_properties(zvec_jni PROPERTIES CXX_STANDARD 17)

# 16KB-page alignment for Android 15+ devices
target_link_options(zvec_jni PRIVATE -Wl,-z,max-page-size=16384)
