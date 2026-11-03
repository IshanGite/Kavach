#!/usr/bin/env bash
# Fetch the sherpa-onnx Android AAR (not on Maven) and, with --models, the Whisper int8 weights.
# The app never downloads these at runtime.
#
#   ./scripts/fetch-sherpa-onnx.sh            # app/libs/sherpa-onnx-2.0.1.aar
#   ./scripts/fetch-sherpa-onnx.sh --models   # also build/sherpa-models/sherpa-whisper/*.int8.onnx + tokens (base, tiny)
#   ./scripts/fetch-sherpa-onnx.sh --small    # same, plus whisper small (about 360 MB int8) as small-*.int8.onnx
#
# Keep the AAR URL in sync with app/build.gradle.kts.
set -euo pipefail
cd "$(dirname "$0")/.."

AAR_URL="https://github.com/k2-fsa/sherpa-onnx/releases/download/v2.0.1/sherpa-onnx-2.0.1.aar"
AAR_PATH="app/libs/sherpa-onnx-2.0.1.aar"
BASE_URL="https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-whisper-base.tar.bz2"
TINY_URL="https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-whisper-tiny.tar.bz2"
SMALL_URL="https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-whisper-small.tar.bz2"
WANT_MODELS=0
WANT_SMALL=0
for arg in "$@"; do
  case "$arg" in
    --models) WANT_MODELS=1 ;;
    --small) WANT_MODELS=1; WANT_SMALL=1 ;;
    *) echo "unknown option: $arg (use --models and/or --small)" >&2; exit 2 ;;
  esac
done
MODEL_DIR="build/sherpa-models"
OUT_DIR="$MODEL_DIR/sherpa-whisper"

mkdir -p app/libs
if [[ -f "$AAR_PATH" ]] && [[ "$(wc -c < "$AAR_PATH" | tr -d ' ')" -ge 40000000 ]]; then
  echo "AAR already present: $AAR_PATH"
else
  echo "Downloading $AAR_URL"
  curl -fL --retry 3 -o "$AAR_PATH.partial" "$AAR_URL"
  mv -f "$AAR_PATH.partial" "$AAR_PATH"
fi
echo "AAR bytes: $(wc -c < "$AAR_PATH" | tr -d ' ')"

if [[ "$WANT_MODELS" != 1 ]]; then
  echo "Models: re-run with --models (base + tiny) or --small (also small) to fetch int8 weights into $OUT_DIR"
  exit 0
fi

python_bin() {
  if command -v python3 >/dev/null 2>&1; then echo python3
  elif command -v python >/dev/null 2>&1; then echo python
  else echo ""; fi
}
PY="$(python_bin)"
if [[ -z "$PY" ]]; then
  echo "python is required to extract the Whisper archives" >&2
  exit 1
fi

mkdir -p "$MODEL_DIR" "$OUT_DIR"
fetch_pack() {
  local url="$1" name="$2"
  local arc="$MODEL_DIR/$name.tar.bz2"
  if [[ ! -f "$arc" ]]; then
    echo "Downloading $url"
    curl -fL --retry 3 -o "$arc.partial" "$url"
    mv -f "$arc.partial" "$arc"
  else
    echo "Archive already present: $arc"
  fi
  "$PY" - "$arc" "$OUT_DIR" <<'PY'
import sys, tarfile
from pathlib import Path
arc, out = Path(sys.argv[1]), Path(sys.argv[2])
out.mkdir(parents=True, exist_ok=True)
keep_suffixes = (".int8.onnx", "-tokens.txt")
with tarfile.open(arc, "r:bz2") as tar:
    for member in tar.getmembers():
        if not member.isfile():
            continue
        base = Path(member.name).name
        if not base.endswith(keep_suffixes):
            continue
        dest = out / base
        src = tar.extractfile(member)
        if src is None:
            continue
        with src, dest.open("wb") as fh:
            fh.write(src.read())
        print(f"extracted {base} ({dest.stat().st_size} bytes)")
PY
}
fetch_pack "$BASE_URL" "sherpa-onnx-whisper-base"
fetch_pack "$TINY_URL" "sherpa-onnx-whisper-tiny"
if [[ "$WANT_SMALL" == 1 ]]; then fetch_pack "$SMALL_URL" "sherpa-onnx-whisper-small"; fi

echo
echo "Push (after the app has been installed once):"
echo "  DEST=/sdcard/Android/data/com.kavach.app/files/sherpa-whisper"
echo "  adb shell mkdir -p \"\$DEST\""
echo "  adb push $OUT_DIR/. \"\$DEST/\""
