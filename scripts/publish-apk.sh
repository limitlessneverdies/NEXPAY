#!/usr/bin/env bash
set -euo pipefail
SOURCE="${1:?Pass the path to an actually built APK}"
DEST="${2:-server/downloads/paila-test.apk}"
[[ "$SOURCE" == *.apk && -f "$SOURCE" ]] || { echo 'Expected an existing .apk file'; exit 1; }
python3 - "$SOURCE" <<'PY'
import sys, zipfile
with zipfile.ZipFile(sys.argv[1]) as z:
    names=set(z.namelist())
    if 'AndroidManifest.xml' not in names or 'classes.dex' not in names:
        raise SystemExit('Not an Android APK. Do not rename a source ZIP.')
PY
if command -v apksigner >/dev/null; then apksigner verify --verbose --print-certs "$SOURCE"; else echo 'apksigner not on PATH. Add Android build-tools and verify the signature before publishing.'; exit 1; fi
mkdir -p "$(dirname "$DEST")"
cp "$SOURCE" "$DEST.tmp"
mv "$DEST.tmp" "$DEST"
if command -v sha256sum >/dev/null; then sha256sum "$DEST"; else shasum -a 256 "$DEST"; fi
echo 'Published to the local server download directory. This is NOT proof of public hosting.'
