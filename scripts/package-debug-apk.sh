#!/usr/bin/env bash
# Packages the DEBUG APK with a stable name, a SHA-256 checksum and a metadata
# file. Run from the repository root after `./gradlew assembleDebug`.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

VERSION="$(grep -oP 'versionName\s*=\s*"\K[^"]+' app/build.gradle.kts | head -1)"
VERSION_CODE="$(grep -oP 'versionCode\s*=\s*\K[0-9]+' app/build.gradle.kts | head -1)"
APK="app/build/outputs/apk/debug/app-debug.apk"
MIN_APK_BYTES=$((100 * 1024 * 1024))

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
for ABI in arm64-v8a armeabi-v7a x86_64; do
  if ! grep -q "lib/${ABI}/libbigbai.so" <<<"$APK_ENTRIES"; then
    echo "ERRORE: libbigbai.so (${ABI}) non è presente nell'APK." >&2
    exit 1
  fi
  if ! grep -q "lib/${ABI}/libsqlcipher.so" <<<"$APK_ENTRIES"; then
    echo "ERRORE: libsqlcipher.so (${ABI}) non è presente nell'APK." >&2
    exit 1
  fi
done

mkdir -p dist
OUT="dist/BIG-B-AI-debug-${VERSION}.apk"
cp "$APK" "$OUT"

SHA="$(sha256sum "$OUT" | cut -d' ' -f1)"
SIZE="$(stat -c%s "$OUT")"
SIZE_MB="$(awk -v b="$SIZE" 'BEGIN { printf "%.2f", b/1048576 }')"
BUILD_DATE="$(date -u +%Y-%m-%dT%H:%M:%SZ)"

if [ "$SIZE" -lt "$MIN_APK_BYTES" ]; then
  echo "ERRORE: l'APK pesa ${SIZE} byte (${SIZE_MB} MiB), sotto il requisito di 100 MiB." >&2
  echo "Verifica che le tre ABI siano state compilate: bash scripts/build-native.sh" >&2
  exit 1
fi

printf '%s  %s\n' "$SHA" "$(basename "$OUT")" > "dist/BIG-B-AI-debug-${VERSION}.apk.sha256"

cat > "dist/BIG-B-AI-debug-${VERSION}.txt" <<EOF
BIG B AI — APK DEBUG
====================
File:          $(basename "$OUT")
Versione:      ${VERSION} (versionCode ${VERSION_CODE}) — variante debug
Dimensione:    ${SIZE} byte (${SIZE_MB} MiB)
SHA-256:       ${SHA}
Data build:    ${BUILD_DATE}
minSdk:        31 (Android 12)
targetSdk:     35
ABI:           arm64-v8a, armeabi-v7a, x86_64 (APK universale)
Firma:         chiave DEBUG Android (CN=Android Debug)
Package:       com.bigbai.app.debug (suffisso .debug, convive con la release)
Motore AI:     llama.cpp, CPU only (nessuna accelerazione GPU in questa build)
Modelli:       NON inclusi nell'APK; si scaricano dall'app e sono verificati con SHA-256.
Test eseguiti: test unitari JVM dei quattro moduli (0 falliti) + lint + build debug.
               Nessun test su dispositivo fisico o emulatore.
Audit:         docs/AUDIT-1.3.md
EOF

echo "APK:      $OUT"
echo "Size:     ${SIZE} bytes (${SIZE_MB} MiB)"
echo "SHA-256:  ${SHA}"
echo "Metadata: dist/BIG-B-AI-debug-${VERSION}.txt"
