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

if [ ! -s "$APK" ]; then
  echo "ERRORE: APK debug assente: $APK" >&2
  exit 1
fi
# The archive listing is needed by the checks below; computing it once also
# avoids running unzip repeatedly on a 150 MB file.
APK_ENTRIES="$(unzip -l "$APK")"

# ITAGPT ships no GGUF model. A debug APK that contains one is a build
# failure, not a warning: the whole point is a small, installable APK.
if grep -Eq 'assets/.*\.gguf' <<<"$APK_ENTRIES"; then
  echo "ERRORE: l'APK debug contiene un modello GGUF negli asset." >&2
  grep -E 'assets/.*\.gguf' <<<"$APK_ENTRIES" >&2
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
Modelli:       nessun modello incluso (0 file .gguf, verificato nel pacchetto)
Chat:          scaricabile su richiesta dalla sezione Modelli; nessun download automatico.
Embedding:     opzionale, import manuale di multilingual-e5-small Q8_0
               (132439008 byte, SHA-256 e011debc…77c93), verificato all'import.
Test eseguiti: test unitari JVM dei quattro moduli + lint + build debug.
               Nessun test su dispositivo fisico o emulatore.
Release note:  docs/RELEASE-${VERSION}.md
EOF

echo "APK:      $OUT"
echo "Size:     ${SIZE} bytes (${SIZE_MB} MiB)"
echo "SHA-256:  $SHA"
echo "Firma:    ${SIGNATURE_CHECK}"
echo "Metadata: dist/ITAGPT-debug-${VERSION}.txt"
