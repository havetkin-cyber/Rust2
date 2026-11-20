cmake_minimum_required(VERSION 3.22.1)
project(alexiaai C CXX)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# llama.cpp (MIT) sa stiahne z GitHubu pri zostavení
include(FetchContent)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON OFF CACHE BOOL "" FORCE)
set(LLAMA_CURL OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
# ako oficiálna Android ukážka llama.cpp: na ARM rýchle jadrá KleidiAI (podľa schopností procesora za behu)
if(ANDROID_ABI STREQUAL "arm64-v8a")
    set(GGML_CPU_KLEIDIAI ON CACHE BOOL "" FORCE)
    set(GGML_OPENMP ON CACHE BOOL "" FORCE)
else()
    set(GGML_CPU_KLEIDIAI OFF CACHE BOOL "" FORCE)
    set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
endif()
FetchContent_Declare(llama
    GIT_REPOSITORY https://github.com/ggml-org/llama.cpp.git
    GIT_TAG v1.0.0            # commit b1b770d
    GIT_SHALLOW TRUE)
FetchContent_MakeAvailable(llama)

add_library(alexiaai SHARED llama_jni.cpp)
target_compile_options(alexiaai PRIVATE -O3)
target_link_libraries(alexiaai llama android log)
target_link_options(alexiaai PRIVATE -Wl,--gc-sections -Wl,--exclude-libs,ALL)
