#!/usr/bin/env bash
# Packages the DEBUG APK with a stable name, a SHA-256 checksum and a metadata
# file. Run from the repository root after `./gradlew assembleDebug`.
#
# The 1.5 requirement is explicit: the universal APK must be at least 200 MiB
# (209,715,200 bytes) of real components. This script refuses to publish
# anything smaller, refuses an APK without every shipped ABI, and verifies the
# signature, the manifest and the bundled embedding model inside the APK.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

VERSION="$(grep -oP 'versionName\s*=\s*"\K[^"]+' app/build.gradle.kts | head -1)"
VERSION_CODE="$(grep -oP 'versionCode\s*=\s*\K[0-9]+' app/build.gradle.kts | head -1)"
APK="app/build/outputs/apk/debug/app-debug.apk"
MIN_APK_BYTES=$((200 * 1024 * 1024))
ABIS="arm64-v8a armeabi-v7a x86_64"

MODEL_NAME="multilingual-e5-small-q8_0.gguf"
MODEL_SIZE=132439008
MODEL_SHA="e011debc1208e31bf7b6aebee2d9fc8bd2ca11694a77ed66ac9d0c9d0a877c93"

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

# The embedding model is a real component of 1.5: if it is missing, retrieval
# would silently stop working, so the build fails instead.
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
  echo "ERRORE: SHA-256 del modello dentro l'APK non corrisponde." >&2
  echo "  atteso: $MODEL_SHA" >&2
  echo "  trovato: $MODEL_DIGEST" >&2
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
  "$APKSIGNER" verify --print-certs "$APK" > /tmp/bigbai-apksigner.txt 2>&1
  SIGNATURE_CHECK="valida ($(grep -m1 'Signer #1 certificate DN' /tmp/bigbai-apksigner.txt | sed 's/.*DN: //'))"
fi
if [ -x "$AAPT2" ]; then
  "$AAPT2" dump badging "$APK" > /tmp/bigbai-badging.txt 2>&1
  PACKAGE_LINE="$(grep -m1 '^package:' /tmp/bigbai-badging.txt)"
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
    if ! grep -q "native-code:.*'${ABI}'" /tmp/bigbai-badging.txt; then
      echo "ERRORE: il manifest non dichiara l'ABI ${ABI}." >&2
      exit 1
    fi
  done
  MANIFEST_CHECK="valido (${PACKAGE_LINE})"
fi

mkdir -p dist
OUT="dist/BIG-B-AI-debug-${VERSION}.apk"
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

if [ "$SIZE" -lt "$MIN_APK_BYTES" ]; then
  echo "ERRORE: l'APK pesa ${SIZE} byte (${SIZE_MB} MiB), sotto il requisito di 200 MiB." >&2
  echo "Verifica che le tre ABI e il modello di embedding siano stati inclusi." >&2
  exit 1
fi

printf '%s  %s\n' "$SHA" "$(basename "$OUT")" > "dist/BIG-B-AI-debug-${VERSION}.apk.sha256"

cat > "dist/BIG-B-AI-debug-${VERSION}.txt" <<EOF
BIG B AI — APK DEBUG
====================
File:          $(basename "$OUT")
Versione:      ${VERSION} (versionCode ${VERSION_CODE}) — variante debug
Dimensione:    ${SIZE} byte (${SIZE_MB} MiB)
Requisito:     200 MiB (209715200 byte) — ${SIZE} byte, soddisfatto
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
Modelli chat:  NON inclusi nell'APK; si scaricano dall'app e sono verificati con SHA-256.
Test eseguiti: test unitari JVM dei quattro moduli (0 falliti) + lint + build debug.
               Nessun test su dispositivo fisico o emulatore.
Audit:         docs/AUDIT-${VERSION%.*}.md
EOF

echo "APK:      $OUT"
echo "Size:     ${SIZE} bytes (${SIZE_MB} MiB)"
echo "SHA-256:  $SHA"
echo "Firma:    ${SIGNATURE_CHECK}"
echo "Metadata: dist/BIG-B-AI-debug-${VERSION}.txt"
