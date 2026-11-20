# Alexia AI pre Linux (SteamOS) – chat: llama.cpp s Vulkanom (grafika AMD cez RADV)
cmake_minimum_required(VERSION 3.22)
cmake_policy(SET CMP0091 NEW)
project(alexiaai C CXX)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
# Windows (MSVC): runtime pribalený staticky – netreba inštalovať Visual C++ Redistributable
set(CMAKE_MSVC_RUNTIME_LIBRARY "MultiThreaded$<$<CONFIG:Debug>:Debug>" CACHE STRING "" FORCE)
find_package(JNI REQUIRED)

include(FetchContent)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)      # beží aj na inom procesore než CI
set(GGML_AVX2 ON CACHE BOOL "" FORCE)         # Zen 3+ (Z2 Go) má AVX2/FMA/F16C
set(GGML_FMA ON CACHE BOOL "" FORCE)
set(GGML_F16C ON CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_VULKAN ON CACHE BOOL "" FORCE)
FetchContent_Declare(llama
    GIT_REPOSITORY https://github.com/ggml-org/llama.cpp.git
    GIT_TAG v1.0.0
    GIT_SHALLOW TRUE)
FetchContent_MakeAvailable(llama)

add_library(alexiaai SHARED ${CMAKE_CURRENT_LIST_DIR}/../../../app/src/main/cpp/llama_jni.cpp)
target_include_directories(alexiaai PRIVATE ${JNI_INCLUDE_DIRS})
if(NOT MSVC)
    target_compile_options(alexiaai PRIVATE -O3)
endif()
target_link_libraries(alexiaai llama)
if(NOT MSVC)
    target_link_options(alexiaai PRIVATE -static-libstdc++ -static-libgcc -Wl,--exclude-libs,ALL -Wl,--no-undefined)
endif()
