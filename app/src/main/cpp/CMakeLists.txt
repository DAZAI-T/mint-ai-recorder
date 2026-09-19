cmake_minimum_required(VERSION 3.22.1)
project(gijiroku_whisper_jni)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# whisper.cpp is NOT vendored in this repo — clone it here before building.
# See ../../../../README.md for the exact command.
set(WHISPER_CPP_DIR ${CMAKE_CURRENT_SOURCE_DIR}/whisper.cpp)

if(NOT EXISTS ${WHISPER_CPP_DIR}/CMakeLists.txt)
    message(FATAL_ERROR
        "whisper.cpp not found at ${WHISPER_CPP_DIR}. "
        "Run: git clone https://github.com/ggerganov/whisper.cpp \"${WHISPER_CPP_DIR}\"")
endif()

# whisper.cpp benchmark-only build: no examples/tests needed here.
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
# Avoid packaging the NDK r26 OpenMP runtime, which is not 16 KB ELF-aligned.
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)

add_subdirectory(${WHISPER_CPP_DIR} whisper_build)

# kaldi-native-fbank is NOT vendored either — clone it here before building.
# See ../../../../README.md for the exact command. Used to compute the exact
# same log-mel filterbank features the speaker-embedding model (phase3) was
# trained on; note its CMake fetches kissfft from GitHub at configure time,
# so the first Gradle sync after cloning needs network access on the build machine.
set(KALDI_NATIVE_FBANK_DIR ${CMAKE_CURRENT_SOURCE_DIR}/kaldi-native-fbank)

if(NOT EXISTS ${KALDI_NATIVE_FBANK_DIR}/CMakeLists.txt)
    message(FATAL_ERROR
        "kaldi-native-fbank not found at ${KALDI_NATIVE_FBANK_DIR}. "
        "Run: git clone https://github.com/csukuangfj/kaldi-native-fbank \"${KALDI_NATIVE_FBANK_DIR}\"")
endif()

set(KALDI_NATIVE_FBANK_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(KALDI_NATIVE_FBANK_BUILD_PYTHON OFF CACHE BOOL "" FORCE)
set(KALDI_NATIVE_FBANK_ENABLE_CHECK OFF CACHE BOOL "" FORCE)

add_subdirectory(${KALDI_NATIVE_FBANK_DIR} fbank_build)

add_library(whisper_jni SHARED jni_bridge.cpp fbank_jni.cpp)

target_include_directories(whisper_jni PRIVATE
    ${WHISPER_CPP_DIR}/include
    ${WHISPER_CPP_DIR}/ggml/include
    ${KALDI_NATIVE_FBANK_DIR}
)

find_library(log-lib log)

target_link_libraries(whisper_jni whisper kaldi-native-fbank-core ${log-lib} android)
target_link_options(whisper_jni PRIVATE
    "-Wl,-z,max-page-size=16384"
    "-Wl,-z,common-page-size=16384"
)
