cmake_minimum_required(VERSION 3.22.1)
project(lisannova_whisper LANGUAGES C CXX)

include(FetchContent)
set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_VULKAN OFF CACHE BOOL "" FORCE)
set(GGML_CUDA OFF CACHE BOOL "" FORCE)
set(GGML_METAL OFF CACHE BOOL "" FORCE)

FetchContent_Declare(
    whisper_cpp
    GIT_REPOSITORY https://github.com/ggml-org/whisper.cpp.git
    GIT_TAG v1.7.6
    GIT_SHALLOW TRUE
)
FetchContent_MakeAvailable(whisper_cpp)

add_library(lisannova_whisper SHARED whisper_jni.cpp)
target_include_directories(lisannova_whisper PRIVATE ${whisper_cpp_SOURCE_DIR}/include)
target_compile_options(lisannova_whisper PRIVATE -O3 -fvisibility=hidden)
target_link_libraries(lisannova_whisper PRIVATE whisper log android)
