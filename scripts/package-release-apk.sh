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

EMBED_NAME="multilingual-e5-small-q8_0.gguf"
EMBED_SIZE=132439008
EMBED_SHA="e011debc1208e31bf7b6aebee2d9fc8bd2ca11694a77ed66ac9d0c9d0a877c93"

CHAT_NAME="qwen2.5-1.5b-instruct-q4_k_m.gguf"
CHAT_SIZE=1117320736
CHAT_SHA="6a1a2eb6d15622bf3c96857206351ba97e1af16c30d7a74ee38970e434e9407e"

if [ ! -f "$APK" ]; then
  echo "ERRORE: $APK non trovato. Esegui prima: ./gradlew assembleRelease" >&2
  exit 1
fi

APK_ENTRIES="$(unzip -l "$APK")"
for ABI in $ABIS; do
  if ! grep -q "lib/${ABI}/libbigbai.so" <<<"$APK_ENTRIES"; then
    echo "ERRORE: libbigbai.so (${ABI}) non è presente nella release." >&2
    exit 1
  fi
  if ! grep -q "lib/${ABI}/libsqlcipher.so" <<<"$APK_ENTRIES"; then
    echo "ERRORE: libsqlcipher.so (${ABI}) non è presente nella release." >&2
    exit 1
  fi
done

verify_model() {
  local entry="$1" expected_size="$2" expected_sha="$3" label="$4"
  if ! grep -q "$entry" <<<"$APK_ENTRIES"; then
    echo "ERRORE: $label assente dalla release ($entry)." >&2
    exit 1
  fi
  local size
  size="$(awk -v name="$entry" '$NF == name { print $1 }' <<<"$APK_ENTRIES" | head -1)"
  if [ "$size" != "$expected_size" ]; then
    echo "ERRORE: dimensione $label non valida: ${size:-assente} invece di $expected_size." >&2
    exit 1
  fi
  local digest
  digest="$(unzip -p "$APK" "$entry" | sha256sum | cut -d' ' -f1)"
  if [ "$digest" != "$expected_sha" ]; then
    echo "ERRORE: SHA-256 $label non corrisponde." >&2
    echo "  atteso: $expected_sha" >&2
    echo "  trovato: $digest" >&2
    exit 1
  fi
}

verify_model "assets/models/${EMBED_NAME}" "$EMBED_SIZE" "$EMBED_SHA" "modello di embedding"
verify_model "assets/models/${CHAT_NAME}" "$CHAT_SIZE" "$CHAT_SHA" "modello chat incluso"

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
Embedding:     ${EMBED_NAME}, ${EMBED_SIZE} byte, SHA-256 ${EMBED_SHA}
Chat incluso:  ${CHAT_NAME}, ${CHAT_SIZE} byte, SHA-256 ${CHAT_SHA}
               Qwen2.5 1.5B Instruct Q4_K_M, Apache-2.0.
Test eseguiti: test unitari JVM dei quattro moduli, lint e build release.
               Nessun test strumentale su dispositivo fisico o emulatore.
Release note:  docs/RELEASE-2.0.0.md
EOF

echo "APK:      $OUT"
echo "Size:     ${SIZE} bytes (${SIZE_MB} MiB)"
echo "SHA-256:  $SHA"
echo "Firma:    ${SIGNATURE_LABEL}"
echo "Metadata: dist/${BASE}.txt"
