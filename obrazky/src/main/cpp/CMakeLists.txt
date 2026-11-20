cmake_minimum_required(VERSION 3.22.1)
project(alexiasd C CXX)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# stable-diffusion.cpp (MIT) + jeho upravené ggml – pevné verzie, stiahnu sa pri zostavení
include(FetchContent)
FetchContent_Declare(sdggml
    URL https://github.com/leejet/ggml/archive/eeda9ec48fc84a3df0bb9dcc19ae9053075a2539.tar.gz)
FetchContent_Populate(sdggml)
FetchContent_Declare(sdcpp
    URL https://github.com/leejet/stable-diffusion.cpp/archive/532d2c15c430a785a712b2d94281f04e733008a4.tar.gz)

set(SD_GGML_SOURCE_DIR "${sdggml_SOURCE_DIR}" CACHE PATH "" FORCE)
set(SD_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(SD_WEBP OFF CACHE BOOL "" FORCE)
set(SD_WEBM OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP ON CACHE BOOL "" FORCE)
FetchContent_MakeAvailable(sdcpp)

# sd_linux.cpp = sd_jni.cpp + vylepšenie fotky (Real-ESRGAN) a viacsúborové modely – rovnaký kód ako na počítači
add_library(alexiasd SHARED ${CMAKE_CURRENT_LIST_DIR}/../../../../linux/native/sd/sd_linux.cpp)
target_compile_options(alexiasd PRIVATE -O3)
target_link_libraries(alexiasd stable-diffusion android log)
target_link_options(alexiasd PRIVATE -Wl,--gc-sections -Wl,--exclude-libs,ALL)
