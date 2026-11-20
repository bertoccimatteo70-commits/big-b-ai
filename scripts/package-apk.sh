#!/usr/bin/env bash
# Packages the release APK with a stable name, a SHA-256 checksum and a metadata
# file. Run from the repository root after `gradle assembleRelease`.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

VERSION="$(grep -oP 'versionName\s*=\s*"\K[^"]+' app/build.gradle.kts | head -1)"
VERSION_CODE="$(grep -oP 'versionCode\s*=\s*\K[0-9]+' app/build.gradle.kts | head -1)"
APK="app/build/outputs/apk/release/app-release.apk"

if [ ! -f "$APK" ]; then
  echo "ERRORE: $APK non trovato. Esegui prima: gradle assembleRelease" >&2
  exit 1
fi

mkdir -p dist
OUT="dist/BIG-B-AI-${VERSION}.apk"
cp "$APK" "$OUT"

SHA="$(sha256sum "$OUT" | cut -d' ' -f1)"
SIZE="$(stat -c%s "$OUT")"
SIZE_MB="$(awk -v b="$SIZE" 'BEGIN { printf "%.1f", b/1048576 }')"
BUILD_DATE="$(date -u +%Y-%m-%dT%H:%M:%SZ)"

echo "$SHA  $(basename "$OUT")" > "dist/BIG-B-AI-${VERSION}.apk.sha256"

cat > "dist/BIG-B-AI-${VERSION}.txt" <<EOF
BIG B AI
========
File:          $(basename "$OUT")
Versione:      ${VERSION} (versionCode ${VERSION_CODE})
Dimensione:    ${SIZE} byte (${SIZE_MB} MiB)
SHA-256:       ${SHA}
Data build:    ${BUILD_DATE}
minSdk:        31 (Android 12)
targetSdk:     35
ABI:           arm64-v8a
Firma:         chiave DEBUG Android (CN=Android Debug) — non adatta alla distribuzione
               su Play Store; l'APK è installabile con "installa da origini sconosciute".
Motore AI:     llama.cpp v1.0.0, CPU only (nessuna accelerazione GPU in questa build)
Modelli:       NON inclusi nell'APK; si scaricano dall'app e sono verificati con SHA-256.
Test eseguiti: build debug + release, 18 test unitari JVM. Nessun test su dispositivo.
EOF

echo "APK:      $OUT"
echo "Size:     ${SIZE} bytes (${SIZE_MB} MiB)"
echo "SHA-256:  ${SHA}"
echo "Metadata: dist/BIG-B-AI-${VERSION}.txt"
