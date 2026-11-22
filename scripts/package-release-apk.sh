#!/usr/bin/env bash
# Packages the RELEASE APK with a stable name, checksum and metadata.
#
# A release build is minified with R8 and resource shrinking. The script never
# calls a debug-key build an official release: unless a real release keystore is
# configured, the output is named ITAGPT-release-debug-signed-*.apk and its
# metadata says so. CI sets REQUIRE_OFFICIAL_RELEASE_SIGNATURE=1 when the
# GitHub Secrets are present, turning a missing/incorrect signature into a
# build failure.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

VERSION="$(grep -oP 'versionName\s*=\s*"\K[^"]+' app/build.gradle.kts | head -1)"
VERSION_CODE="$(grep -oP 'versionCode\s*=\s*\K[0-9]+' app/build.gradle.kts | head -1)"
APK="app/build/outputs/apk/release/app-release.apk"
ABIS="arm64-v8a armeabi-v7a x86_64"

if [ ! -s "$APK" ]; then
  echo "ERRORE: APK release assente: $APK" >&2
  exit 1
fi
APK_ENTRIES="$(unzip -l "$APK")"

# No model is packaged in the release either. The model manager downloads
# weights later, on explicit user request, and verifies them with SHA-256.
if grep -Eq 'assets/.*\.gguf' <<<"$APK_ENTRIES"; then
  echo "ERRORE: la release contiene un modello GGUF negli asset." >&2
  grep -E 'assets/.*\.gguf' <<<"$APK_ENTRIES" >&2
  exit 1
fi

SDK_ROOT="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
BUILD_TOOLS="$SDK_ROOT/build-tools/35.0.0"
APKSIGNER="$BUILD_TOOLS/apksigner"
AAPT2="$BUILD_TOOLS/aapt2"
if [ ! -x "$APKSIGNER" ] || [ ! -x "$AAPT2" ]; then
  echo "ERRORE: build-tools 35.0.0 (apksigner/aapt2) non disponibili: firma e manifest non verificabili." >&2
  exit 1
fi

"$APKSIGNER" verify --print-certs "$APK" > /tmp/itagpt-release-apksigner.txt 2>&1
CERT_DN="$(grep -m1 'Signer #1 certificate DN' /tmp/itagpt-release-apksigner.txt | sed 's/.*DN: //')"
if [ -z "$CERT_DN" ]; then
  echo "ERRORE: la release non è firmata." >&2
  cat /tmp/itagpt-release-apksigner.txt >&2
  exit 1
fi
DEBUG_SIGNED=false
if grep -qi 'CN=Android Debug' <<<"$CERT_DN"; then
  DEBUG_SIGNED=true
fi
if [ "${REQUIRE_OFFICIAL_RELEASE_SIGNATURE:-0}" = "1" ] && [ "$DEBUG_SIGNED" = true ]; then
  echo "ERRORE: REQUIRE_OFFICIAL_RELEASE_SIGNATURE=1 ma la release usa la chiave debug." >&2
  exit 1
fi

"$AAPT2" dump badging "$APK" > /tmp/itagpt-release-badging.txt 2>&1
PACKAGE_LINE="$(grep -m1 '^package:' /tmp/itagpt-release-badging.txt)"
if ! grep -q "versionCode='${VERSION_CODE}'" <<<"$PACKAGE_LINE"; then
  echo "ERRORE: il manifest non riporta versionCode ${VERSION_CODE}: $PACKAGE_LINE" >&2
  exit 1
fi
if ! grep -q "versionName='${VERSION}'" <<<"$PACKAGE_LINE"; then
  echo "ERRORE: il manifest non riporta versionName ${VERSION}: $PACKAGE_LINE" >&2
  exit 1
fi
if ! grep -q "name='com.bigbai.app'" <<<"$PACKAGE_LINE"; then
  echo "ERRORE: package inatteso nella release: $PACKAGE_LINE" >&2
  exit 1
fi
for ABI in $ABIS; do
  if ! grep -q "native-code:.*'${ABI}'" /tmp/itagpt-release-badging.txt; then
    echo "ERRORE: il manifest non dichiara l'ABI ${ABI}." >&2
    exit 1
  fi
done

mkdir -p dist
if [ "$DEBUG_SIGNED" = true ]; then
  BASE="ITAGPT-release-debug-signed-${VERSION}"
  SIGNATURE_LABEL="chiave DEBUG (non è una release ufficiale)"
else
  BASE="ITAGPT-release-${VERSION}"
  SIGNATURE_LABEL="release ufficiale (${CERT_DN})"
fi
OUT="dist/${BASE}.apk"
cp "$APK" "$OUT"

SHA="$(sha256sum "$OUT" | cut -d' ' -f1)"
SIZE="$(stat -c%s "$OUT")"
SIZE_MB="$(awk -v b="$SIZE" 'BEGIN { printf "%.2f", b/1048576 }')"
BUILD_DATE="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
BUILT_SHA="$(sha256sum "$APK" | cut -d' ' -f1)"
if [ "$SHA" != "$BUILT_SHA" ]; then
  echo "ERRORE: il file in dist non corrisponde alla release appena compilata." >&2
  exit 1
fi

printf '%s  %s\n' "$SHA" "$(basename "$OUT")" > "dist/${BASE}.apk.sha256"

cat > "dist/${BASE}.txt" <<EOF
ITAGPT — APK RELEASE
=====================
File:          $(basename "$OUT")
Versione:      ${VERSION} (versionCode ${VERSION_CODE})
Dimensione:    ${SIZE} byte (${SIZE_MB} MiB)
SHA-256:       ${SHA}
Data build:    ${BUILD_DATE}
minSdk:        31 (Android 12)
targetSdk:     35
ABI:           arm64-v8a, armeabi-v7a, x86_64 (APK universale)
Package:       com.bigbai.app
Firma:         ${SIGNATURE_LABEL}
Ottimizzazione: R8/minificazione e resource shrinking attivi
Motore AI:     llama.cpp, CPU only
Modelli:       nessun modello incluso (0 file .gguf, verificato nel pacchetto)
Chat:          scaricabile su richiesta dalla sezione Modelli; nessun download automatico.
Embedding:     opzionale, import manuale di multilingual-e5-small Q8_0
               (132439008 byte, SHA-256 e011debc…77c93), verificato all'import.
Test eseguiti: test unitari JVM dei quattro moduli, lint e build release.
               Nessun test strumentale su dispositivo fisico o emulatore.
Release note:  docs/RELEASE-${VERSION}.md
EOF

echo "APK:      $OUT"
echo "Size:     ${SIZE} bytes (${SIZE_MB} MiB)"
echo "SHA-256:  $SHA"
echo "Firma:    ${SIGNATURE_LABEL}"
echo "Metadata: dist/${BASE}.txt"
