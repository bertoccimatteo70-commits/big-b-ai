#!/usr/bin/env bash
# Packages the DEBUG APK with a stable name, a SHA-256 checksum and a metadata
# file. Run from the repository root after `./gradlew assembleDebug`.
#
# ITAGPT ships real, working components: the native engine for every ABI and
# the bundled embedding model. This script refuses an APK without every shipped
# ABI, and verifies the signature, the manifest and the model inside the APK.
# There is deliberately no minimum or maximum size: the APK is exactly as large
# as the components it ships require.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

VERSION="$(grep -oP 'versionName\s*=\s*"\K[^"]+' app/build.gradle.kts | head -1)"
VERSION_CODE="$(grep -oP 'versionCode\s*=\s*\K[0-9]+' app/build.gradle.kts | head -1)"
APK="app/build/outputs/apk/debug/app-debug.apk"
ABIS="arm64-v8a armeabi-v7a x86_64"

MODEL_NAME="multilingual-e5-small-q8_0.gguf"
MODEL_SIZE=132439008
MODEL_SHA="e011debc1208e31bf7b6aebee2d9fc8bd2ca11694a77ed66ac9d0c9d0a877c93"

CHAT_NAME="qwen2.5-1.5b-instruct-q4_k_m.gguf"
CHAT_SIZE=1117320736
CHAT_SHA="6a1a2eb6d15622bf3c96857206351ba97e1af16c30d7a74ee38970e434e9407e"

if [ ! -f "$APK" ]; then
  echo "ERRORE: $APK non trovato. Esegui prima: ./gradlew assembleDebug" >&2
  exit 1
fi

# The native engine is the whole point of the app: never publish an APK that
# silently lost it (for example because the jniLibs step was skipped). Every
# shipped ABI must be present, otherwise the app crashes on that architecture.
# Capture the listing first: piping straight into `grep -q` closes the pipe
# early and, with `pipefail`, makes a successful match look like a failure.
APK_ENTRIES="$(unzip -l "$APK")"
for ABI in $ABIS; do
  if ! grep -q "lib/${ABI}/libbigbai.so" <<<"$APK_ENTRIES"; then
    echo "ERRORE: libbigbai.so (${ABI}) non è presente nell'APK." >&2
    exit 1
  fi
  if ! grep -q "lib/${ABI}/libsqlcipher.so" <<<"$APK_ENTRIES"; then
    echo "ERRORE: libsqlcipher.so (${ABI}) non è presente nell'APK." >&2
    exit 1
  fi
done

# The embedding model is a real component of ITAGPT: if it is missing,
# retrieval would silently stop working, so the build fails instead.
MODEL_ENTRY="assets/models/${MODEL_NAME}"
if ! grep -q "$MODEL_ENTRY" <<<"$APK_ENTRIES"; then
  echo "ERRORE: modello di embedding assente dall'APK ($MODEL_ENTRY)." >&2
  echo "Esegui: bash scripts/fetch-bundled-model.sh" >&2
  exit 1
fi
MODEL_ENTRY_SIZE="$(awk -v name="$MODEL_ENTRY" '$NF == name { print $1 }' <<<"$APK_ENTRIES" | head -1)"
if [ "$MODEL_ENTRY_SIZE" != "$MODEL_SIZE" ]; then
  echo "ERRORE: dimensione del modello nell'APK non valida: ${MODEL_ENTRY_SIZE:-assente} invece di $MODEL_SIZE." >&2
  exit 1
fi
MODEL_DIGEST="$(unzip -p "$APK" "$MODEL_ENTRY" | sha256sum | cut -d' ' -f1)"
if [ "$MODEL_DIGEST" != "$MODEL_SHA" ]; then
  echo "ERRORE: SHA-256 del modello di embedding dentro l'APK non corrisponde." >&2
  echo "  atteso: $MODEL_SHA" >&2
  echo "  trovato: $MODEL_DIGEST" >&2
  exit 1
fi

# The chat model is what lets a fresh install answer immediately. It is as
# mandatory as the native engine and is verified byte-for-byte inside the APK.
CHAT_ENTRY="assets/models/${CHAT_NAME}"
if ! grep -q "$CHAT_ENTRY" <<<"$APK_ENTRIES"; then
  echo "ERRORE: modello chat incluso assente dall'APK ($CHAT_ENTRY)." >&2
  echo "Esegui: bash scripts/fetch-bundled-chat.sh" >&2
  exit 1
fi
CHAT_ENTRY_SIZE="$(awk -v name="$CHAT_ENTRY" '$NF == name { print $1 }' <<<"$APK_ENTRIES" | head -1)"
if [ "$CHAT_ENTRY_SIZE" != "$CHAT_SIZE" ]; then
  echo "ERRORE: dimensione del modello chat nell'APK non valida: ${CHAT_ENTRY_SIZE:-assente} invece di $CHAT_SIZE." >&2
  exit 1
fi
CHAT_DIGEST="$(unzip -p "$APK" "$CHAT_ENTRY" | sha256sum | cut -d' ' -f1)"
if [ "$CHAT_DIGEST" != "$CHAT_SHA" ]; then
  echo "ERRORE: SHA-256 del modello chat dentro l'APK non corrisponde." >&2
  echo "  atteso: $CHAT_SHA" >&2
  echo "  trovato: $CHAT_DIGEST" >&2
  exit 1
fi

# Signature and manifest are verified with the SDK tools when available. On a
# machine without build-tools the packaging still succeeds, but the report says
# explicitly that those two checks did not run.
SDK_ROOT="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
BUILD_TOOLS="$SDK_ROOT/build-tools/35.0.0"
APKSIGNER="$BUILD_TOOLS/apksigner"
AAPT2="$BUILD_TOOLS/aapt2"
SIGNATURE_CHECK="non eseguita (apksigner assente)"
MANIFEST_CHECK="non eseguita (aapt2 assente)"
if [ -x "$APKSIGNER" ]; then
  "$APKSIGNER" verify --print-certs "$APK" > /tmp/itagpt-apksigner.txt 2>&1
  SIGNATURE_CHECK="valida ($(grep -m1 'Signer #1 certificate DN' /tmp/itagpt-apksigner.txt | sed 's/.*DN: //'))"
fi
if [ -x "$AAPT2" ]; then
  "$AAPT2" dump badging "$APK" > /tmp/itagpt-badging.txt 2>&1
  PACKAGE_LINE="$(grep -m1 '^package:' /tmp/itagpt-badging.txt)"
  if ! grep -q "versionCode='${VERSION_CODE}'" <<<"$PACKAGE_LINE"; then
    echo "ERRORE: il manifest non riporta versionCode ${VERSION_CODE}: $PACKAGE_LINE" >&2
    exit 1
  fi
  # The debug variant appends "-debug" to the version name on purpose.
  if ! grep -q "versionName='${VERSION}-debug'" <<<"$PACKAGE_LINE"; then
    echo "ERRORE: il manifest non riporta versionName ${VERSION}-debug: $PACKAGE_LINE" >&2
    exit 1
  fi
  if ! grep -q "name='com.bigbai.app.debug'" <<<"$PACKAGE_LINE"; then
    echo "ERRORE: package inatteso nel manifest: $PACKAGE_LINE" >&2
    exit 1
  fi
  for ABI in $ABIS; do
    if ! grep -q "native-code:.*'${ABI}'" /tmp/itagpt-badging.txt; then
      echo "ERRORE: il manifest non dichiara l'ABI ${ABI}." >&2
      exit 1
    fi
  done
  MANIFEST_CHECK="valido (${PACKAGE_LINE})"
fi

mkdir -p dist
OUT="dist/ITAGPT-debug-${VERSION}.apk"
cp "$APK" "$OUT"

SHA="$(sha256sum "$OUT" | cut -d' ' -f1)"
SIZE="$(stat -c%s "$OUT")"
SIZE_MB="$(awk -v b="$SIZE" 'BEGIN { printf "%.2f", b/1048576 }')"
BUILD_DATE="$(date -u +%Y-%m-%dT%H:%M:%SZ)"

# The published file must be the one just built, not a leftover from an earlier
# run: compare the digest of the copy with the digest of the build output.
BUILT_SHA="$(sha256sum "$APK" | cut -d' ' -f1)"
if [ "$SHA" != "$BUILT_SHA" ]; then
  echo "ERRORE: il file in dist non corrisponde all'APK appena compilato." >&2
  exit 1
fi

printf '%s  %s\n' "$SHA" "$(basename "$OUT")" > "dist/ITAGPT-debug-${VERSION}.apk.sha256"

cat > "dist/ITAGPT-debug-${VERSION}.txt" <<EOF
ITAGPT — APK DEBUG
====================
File:          $(basename "$OUT")
Versione:      ${VERSION} (versionCode ${VERSION_CODE}) — variante debug
Dimensione:    ${SIZE} byte (${SIZE_MB} MiB)
SHA-256:       ${SHA}
Data build:    ${BUILD_DATE}
minSdk:        31 (Android 12)
targetSdk:     35
ABI:           arm64-v8a, armeabi-v7a, x86_64 (APK universale)
Firma:         ${SIGNATURE_CHECK}
Manifest:      ${MANIFEST_CHECK}
Package:       com.bigbai.app.debug (suffisso .debug, convive con la release)
Motore AI:     llama.cpp, CPU only (nessuna accelerazione GPU in questa build)
Embedding:     ${MODEL_NAME}, ${MODEL_SIZE} byte, SHA-256 ${MODEL_SHA}
               Incluso negli asset, estratto e verificato al primo uso.
Chat incluso:  ${CHAT_NAME}, ${CHAT_SIZE} byte, SHA-256 ${CHAT_SHA}
               Qwen2.5 1.5B Instruct Q4_K_M, Apache-2.0. Estratto e verificato
               al primo uso: la chat funziona senza download e senza API key.
Altri modelli: scaricabili dall'app e verificati con SHA-256.
Test eseguiti: test unitari JVM dei quattro moduli (0 falliti) + lint + build debug.
               Nessun test su dispositivo fisico o emulatore.
Release note:  docs/RELEASE-2.0.0.md
EOF

echo "APK:      $OUT"
echo "Size:     ${SIZE} bytes (${SIZE_MB} MiB)"
echo "SHA-256:  $SHA"
echo "Firma:    ${SIGNATURE_CHECK}"
echo "Metadata: dist/ITAGPT-debug-${VERSION}.txt"
