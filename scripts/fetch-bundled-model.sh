#!/usr/bin/env bash
# Downloads the embedding model that ships inside the APK.
#
# The model is not committed to the repository (it is 132 MB, above the
# practical size of a git object and pointless to duplicate in history). The
# build fetches the exact file, verifies its size and SHA-256, and copies it
# into the app assets. The same pinned digest is checked again at runtime before
# the model is used, and again by scripts/package-debug-apk.sh inside the APK.
#
# Provenance:
#   model  intfloat/multilingual-e5-small (MIT)
#   GGUF   TwinSunsLLC/multilingual-e5-small-gguf, multilingual-e5-small-q8_0.gguf
#
# Usage:
#   bash scripts/fetch-bundled-model.sh
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

FILE_NAME="multilingual-e5-small-q8_0.gguf"
EXPECTED_SIZE=132439008
EXPECTED_SHA="e011debc1208e31bf7b6aebee2d9fc8bd2ca11694a77ed66ac9d0c9d0a877c93"
URL="https://huggingface.co/TwinSunsLLC/multilingual-e5-small-gguf/resolve/main/multilingual-e5-small-q8_0.gguf?download=true"

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
  echo "Bundled model already present and verified: $ASSET"
  exit 0
fi

mkdir -p "$CACHE_DIR"
if ! verify "$CACHE"; then
  echo "Downloading $FILE_NAME (this is the only network step of the build)…"
  rm -f "$CACHE.part"
  curl -fL --retry 3 --retry-delay 2 -o "$CACHE.part" "$URL"
  mv "$CACHE.part" "$CACHE"
fi

if ! verify "$CACHE"; then
  echo "ERRORE: il modello scaricato non corrisponde alla dimensione/digest attesi." >&2
  echo "  atteso: $EXPECTED_SIZE byte, sha256 $EXPECTED_SHA" >&2
  exit 1
fi

mkdir -p "$ASSET_DIR"
cp "$CACHE" "$ASSET"
if ! verify "$ASSET"; then
  echo "ERRORE: copia del modello negli asset non riuscita." >&2
  exit 1
fi

echo "Bundled model ready: $ASSET ($(stat -c%s "$ASSET") byte)"
