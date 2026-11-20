#!/usr/bin/env bash
# Builds libbigbai.so for Android (arm64-v8a) with the NDK toolchain.
#
# The resulting shared object is placed in llm/src/main/jniLibs so that Gradle
# packages it without needing a second native build during assemble.
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ANDROID_NDK="${ANDROID_NDK:-${ANDROID_NDK_HOME:-${ANDROID_HOME:-$HOME/android-sdk}/ndk/27.2.12479018}}"
ABI="${ABI:-arm64-v8a}"
API="${API:-31}"
JOBS="${JOBS:-2}"
BUILD_DIR="${ROOT_DIR}/build/native-${ABI}"
OUT_DIR="${ROOT_DIR}/llm/src/main/jniLibs/${ABI}"

if [[ ! -f "${ANDROID_NDK}/build/cmake/android.toolchain.cmake" ]]; then
  echo "ERROR: Android NDK not found at ${ANDROID_NDK}" >&2
  echo "Install it with: sdkmanager 'ndk;27.2.12479018'" >&2
  exit 1
fi

"${ROOT_DIR}/scripts/fetch-llama.sh"

CMAKE_BIN="${CMAKE_BIN:-cmake}"
if [[ -x "${ANDROID_HOME}/cmake/3.22.1/bin/cmake" ]]; then
  CMAKE_BIN="${ANDROID_HOME}/cmake/3.22.1/bin/cmake"
fi
NINJA_BIN="${NINJA_BIN:-${ANDROID_HOME}/cmake/3.22.1/bin/ninja}"

cmake_args=(
  -S "${ROOT_DIR}/llm/src/main/cpp"
  -B "${BUILD_DIR}"
  -DCMAKE_TOOLCHAIN_FILE="${ANDROID_NDK}/build/cmake/android.toolchain.cmake"
  -DANDROID_ABI="${ABI}"
  -DANDROID_PLATFORM="android-${API}"
  -DANDROID_STL=c++_static
  -DCMAKE_BUILD_TYPE=Release
  -DLLAMA_DIR="${ROOT_DIR}/third_party/llama.cpp"
)

if [[ -x "${NINJA_BIN}" ]]; then
  cmake_args+=(-G Ninja -DCMAKE_MAKE_PROGRAM="${NINJA_BIN}")
fi

"${CMAKE_BIN}" "${cmake_args[@]}"
"${CMAKE_BIN}" --build "${BUILD_DIR}" --target bigbai -j "${JOBS}"

mkdir -p "${OUT_DIR}"
cp "${BUILD_DIR}/libbigbai.so" "${OUT_DIR}/libbigbai.so"

# Release builds must not ship debug symbols: the unstripped object is ~68 MB,
# the stripped one is a few MB and keeps the APK small.
STRIP_BIN="${ANDROID_NDK}/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip"
if [[ -x "${STRIP_BIN}" ]]; then
  "${STRIP_BIN}" --strip-unneeded "${OUT_DIR}/libbigbai.so"
fi

echo "Built ${OUT_DIR}/libbigbai.so"
ls -lh "${OUT_DIR}/libbigbai.so"
