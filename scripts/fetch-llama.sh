#!/usr/bin/env bash
# Fetches the pinned llama.cpp revision used by the native engine.
#
# The sources are intentionally NOT vendored in this repository: they are large,
# have their own license and history, and pinning a single revision keeps the
# build reproducible without bloating every commit.
set -euo pipefail

LLAMA_REPO="${LLAMA_REPO:-https://github.com/ggml-org/llama.cpp.git}"
LLAMA_TAG="${LLAMA_TAG:-v1.0.0}"
LLAMA_COMMIT="${LLAMA_COMMIT:-b1b770d90d94cad9e9ea5b993bdbc383a522fb28}"

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEST="${ROOT_DIR}/third_party/llama.cpp"

if [[ -f "${DEST}/include/llama.h" ]]; then
  CURRENT="$(git -C "${DEST}" rev-parse HEAD 2>/dev/null || echo unknown)"
  if [[ "${CURRENT}" == "${LLAMA_COMMIT}" ]]; then
    echo "llama.cpp already at ${LLAMA_COMMIT}"
    exit 0
  fi
  echo "Replacing llama.cpp checkout at ${CURRENT}"
  rm -rf "${DEST}"
fi

mkdir -p "$(dirname "${DEST}")"
git init -q "${DEST}"
git -C "${DEST}" remote add origin "${LLAMA_REPO}"
git -C "${DEST}" fetch --depth 1 origin "refs/tags/${LLAMA_TAG}"
git -C "${DEST}" checkout -q FETCH_HEAD

ACTUAL="$(git -C "${DEST}" rev-parse HEAD)"
if [[ "${ACTUAL}" != "${LLAMA_COMMIT}" ]]; then
  echo "ERROR: llama.cpp revision mismatch." >&2
  echo "  expected ${LLAMA_COMMIT}" >&2
  echo "  actual   ${ACTUAL}" >&2
  exit 1
fi

echo "llama.cpp ${LLAMA_TAG} (${LLAMA_COMMIT}) ready at ${DEST}"
