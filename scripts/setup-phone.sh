#!/usr/bin/env bash
# One command: build the app, download all on-device models from their public sources, push them to the phone, install.
# Needs: adb (phone connected, USB debugging on), JDK 17, ANDROID_HOME, curl, unzip, python. Works in Git Bash on Windows, macOS, Linux.
#   ./scripts/setup-phone.sh            # everything
#   ./scripts/setup-phone.sh --no-build # models only (APK already installed)
# Models are never committed and the app never downloads anything at runtime.
set -euo pipefail
cd "$(dirname "$0")/.."
export MSYS_NO_PATHCONV=1
command -v adb >/dev/null || { echo "adb not found (install Android platform-tools)"; exit 1; }
adb get-state >/dev/null 2>&1 || { echo "No phone: connect it, enable USB debugging, accept the prompt"; exit 1; }

PKG=com.kavach.app
DEST=/sdcard/Android/data/$PKG/files
CACHE=build/model-cache
mkdir -p "$CACHE"

if [[ "${1:-}" != "--no-build" ]]; then
  echo "== Building and installing the app"
  ./gradlew :app:installDebug
fi

echo "== Vosk (English-India + Hindi), about 80 MB"
for m in vosk-model-small-en-in-0.4 vosk-model-small-hi-0.22; do
  [[ -d "$CACHE/$m" ]] || { curl -fL --retry 3 -o "$CACHE/$m.zip" "https://alphacephei.com/vosk/models/$m.zip"; (cd "$CACHE" && unzip -q -o "$m.zip" && rm "$m.zip"); }
done

echo "== Whisper base int8 (Hindi, Marathi, English), about 250 MB"
if [[ ! -d build/sherpa-models/sherpa-whisper ]]; then ./scripts/fetch-sherpa-onnx.sh --models; fi
# Optional: ./scripts/fetch-sherpa-onnx.sh --small first. When small-*.int8.onnx is present it is pushed with the rest
# and the app starts on small, stepping down to base/tiny if a phone cannot keep up.
if ls build/sherpa-models/sherpa-whisper/small-encoder.int8.onnx >/dev/null 2>&1; then echo "   whisper small found, will push it"; fi

echo "== Pushing to the phone ($DEST)"
adb shell mkdir -p "$DEST"
for m in vosk-model-small-en-in-0.4 vosk-model-small-hi-0.22; do adb push "$CACHE/$m" "$DEST/"; done
adb push build/sherpa-models/sherpa-whisper "$DEST/"

echo "== Done. Next: open Kavach > Fix now (permissions), switch Accessibility ON, Protection ON."
echo "   Optional on-phone LLM: see docs/TUSHAR_SETUP.md step 4 (Termux + Ollama + gemma3:1b)."
