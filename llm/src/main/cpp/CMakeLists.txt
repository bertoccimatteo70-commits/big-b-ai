cmake_minimum_required(VERSION 3.22)
project(bigbai C CXX)

# Path to the pinned llama.cpp checkout (fetched by scripts/fetch-llama.sh).
# Override with -DLLAMA_DIR=... when building from CI.
if(NOT DEFINED LLAMA_DIR)
    set(LLAMA_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/llama.cpp")
endif()
get_filename_component(LLAMA_DIR "${LLAMA_DIR}" ABSOLUTE)
if(NOT EXISTS "${LLAMA_DIR}/include/llama.h")
    message(FATAL_ERROR "llama.cpp not found at ${LLAMA_DIR}. Run scripts/fetch-llama.sh first.")
endif()

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# --- llama.cpp build configuration -------------------------------------------
# CPU-only on purpose: Vulkan needs precompiled SPIR-V shaders that the NDK
# cannot produce, and an unverified GPU path would be worse than an honest CPU
# one. The app reports GPU acceleration as unavailable instead of pretending.
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_CURL OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
# llamafile's small-GEMM kernels use FP16 NEON intrinsics (vld1q_f16), which
# only exist on ARMv8. 32-bit ARMv7 must build without them.
if(ANDROID_ABI STREQUAL "armeabi-v7a")
    set(GGML_LLAMAFILE OFF CACHE BOOL "" FORCE)
else()
    set(GGML_LLAMAFILE ON CACHE BOOL "" FORCE)
endif()
set(GGML_CCACHE OFF CACHE BOOL "" FORCE)
set(GGML_VULKAN OFF CACHE BOOL "" FORCE)

add_subdirectory("${LLAMA_DIR}" "${CMAKE_BINARY_DIR}/llama.cpp" EXCLUDE_FROM_ALL)

# mtmd provides real multimodal (vision) support and links only against
# llama/ggml, so it can be embedded without pulling the CLI tooling.
add_subdirectory("${LLAMA_DIR}/tools/mtmd" "${CMAKE_BINARY_DIR}/mtmd" EXCLUDE_FROM_ALL)

add_library(bigbai SHARED bigbai_jni.cpp)

target_include_directories(bigbai PRIVATE
    "${LLAMA_DIR}/include"
    "${LLAMA_DIR}/ggml/include"
    "${LLAMA_DIR}/tools/mtmd"
)

target_link_libraries(bigbai PRIVATE
    llama
    mtmd
    android
    log
)

target_compile_options(bigbai PRIVATE
    -O3
    -fvisibility=hidden
    -ffunction-sections
    -fdata-sections
)

target_link_options(bigbai PRIVATE
    -Wl,--gc-sections
    -Wl,--exclude-libs,ALL
)
