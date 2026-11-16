cmake_minimum_required(VERSION 3.22.1)
project(homebase_blake3 C)

add_library(homebase_blake3 SHARED
    blake3_jni.c
    blake3/blake3.c
    blake3/blake3_dispatch.c
    blake3/blake3_portable.c)
# The debug variant would otherwise hash at -O0.
target_compile_options(homebase_blake3 PRIVATE -O3)
# x86 builds are emulators only: the portable code, without BLAKE3's x86 assembly.
target_compile_definitions(homebase_blake3 PRIVATE BLAKE3_NO_SSE2 BLAKE3_NO_SSE41 BLAKE3_NO_AVX2 BLAKE3_NO_AVX512)
# The NDK builds armeabi-v7a with NEON on, as every 32-bit phone Android 9 runs on has it.
if(ANDROID_ABI MATCHES "arm64-v8a|armeabi-v7a")
    target_sources(homebase_blake3 PRIVATE blake3/blake3_neon.c)
    target_compile_definitions(homebase_blake3 PRIVATE BLAKE3_USE_NEON=1)
else()
    target_compile_definitions(homebase_blake3 PRIVATE BLAKE3_USE_NEON=0)
endif()
target_link_options(homebase_blake3 PRIVATE "-Wl,-z,max-page-size=16384")
