cmake_minimum_required(VERSION 3.22.1)
project(zella_whisper LANGUAGES C CXX)

# whisper.cpp is a git submodule pinned to v1.9.5 (third_party/whisper.cpp).
set(WHISPER_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/whisper.cpp)

set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE)
# Baseline ARMv8 so the library runs on every 64-bit phone, including Cortex-A53 budget phones.
# A faster ARMv8.2 (dotprod, fp16) build is a measured follow-up, not a guess (BUILDLOG).
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_CPU_ARM_ARCH "armv8-a" CACHE STRING "" FORCE)

add_subdirectory(${WHISPER_DIR} whisper EXCLUDE_FROM_ALL)

add_library(zella_whisper SHARED whisper_jni.cpp)
target_link_libraries(zella_whisper PRIVATE whisper log android)
# Google Play requires 16 KB page support for native code.
target_link_options(zella_whisper PRIVATE "-Wl,-z,max-page-size=16384")
if (NOT CMAKE_BUILD_TYPE STREQUAL "Debug")
    target_compile_options(zella_whisper PRIVATE -O3)
endif ()

# RNNoise (classic, BSD), the same vendored copy the iPhone and Mac use. Voice clean-up.
set(RNNOISE_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/rnnoise)
add_library(zella_audio SHARED
    audio_jni.c
    ${RNNOISE_DIR}/denoise.c ${RNNOISE_DIR}/rnn.c ${RNNOISE_DIR}/rnn_data.c ${RNNOISE_DIR}/rnn_reader.c
    ${RNNOISE_DIR}/pitch.c ${RNNOISE_DIR}/kiss_fft.c ${RNNOISE_DIR}/celt_lpc.c)
target_include_directories(zella_audio PRIVATE ${RNNOISE_DIR} ${RNNOISE_DIR}/include)
target_compile_definitions(zella_audio PRIVATE HAVE_CONFIG_H)
target_compile_options(zella_audio PRIVATE -O3 -Wno-everything)
target_link_libraries(zella_audio PRIVATE m log)
target_link_options(zella_audio PRIVATE "-Wl,-z,max-page-size=16384")
