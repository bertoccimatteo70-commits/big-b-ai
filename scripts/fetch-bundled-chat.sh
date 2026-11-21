#!/usr/bin/env bash
# Downloads the entry chat model that ships inside the APK.
#
# Without it a fresh install would have to download a model before the first
# answer, which is exactly the gap ITAGPT 2.0 closes. The file is not committed:
# the build fetches the pinned GGUF, verifies size and SHA-256, and copies it to
# the app assets. The same digest is checked again at runtime and by
# scripts/package-debug-apk.sh inside the packaged APK.
#
# Provenance:
#   model  Qwen/Qwen2.5-1.5B-Instruct-GGUF (Apache-2.0)
#   file   qwen2.5-1.5b-instruct-q4_k_m.gguf (1,117,320,736 bytes)
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

FILE_NAME="qwen2.5-1.5b-instruct-q4_k_m.gguf"
EXPECTED_SIZE=1117320736
EXPECTED_SHA="6a1a2eb6d15622bf3c96857206351ba97e1af16c30d7a74ee38970e434e9407e"
URL="https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/${FILE_NAME}?download=true"

CACHE_DIR="$ROOT_DIR/models"
CACHE="$CACHE_DIR/$FILE_NAME"
ASSET_DIR="$ROOT_DIR/app/src/main/assets/models"
ASSET="$ASSET_DIR/$FILE_NAME"

verify() {
  local path="$1"
  [ -f "$path" ] || return 1
  local size
  size="$(stat -c%s "$path")"
  [ "$size" = "$EXPECTED_SIZE" ] || return 1
  local digest
  digest="$(sha256sum "$path" | cut -d' ' -f1)"
  [ "$digest" = "$EXPECTED_SHA" ] || return 1
  return 0
}

if verify "$ASSET"; then
  echo "Bundled chat model already present and verified: $ASSET"
  exit 0
fi

mkdir -p "$CACHE_DIR"
if ! verify "$CACHE"; then
  echo "Downloading $FILE_NAME (1117 MB, the only large network step of the build)…"
  rm -f "$CACHE.part"
  curl -fL --retry 3 --retry-delay 3 --connect-timeout 30 -o "$CACHE.part" "$URL"
  mv "$CACHE.part" "$CACHE"
fi

if ! verify "$CACHE"; then
  echo "ERRORE: il modello chat scaricato non corrisponde a dimensione/digest attesi." >&2
  echo "  atteso: $EXPECTED_SIZE byte, sha256 $EXPECTED_SHA" >&2
  exit 1
fi

mkdir -p "$ASSET_DIR"
cp "$CACHE" "$ASSET"
if ! verify "$ASSET"; then
  echo "ERRORE: copia del modello chat negli asset non riuscita." >&2
  exit 1
fi

echo "Bundled chat model ready: $ASSET ($(stat -c%s "$ASSET") byte)"
