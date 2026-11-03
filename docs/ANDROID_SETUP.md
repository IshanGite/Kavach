# Android setup (demo phone)

## Dev machine

```bash
brew install --cask android-commandlinetools          # already done on Ishan's Mac
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
yes | sdkmanager --licenses
sdkmanager "platform-tools" "platforms;android-35" "build-tools;35.0.0"   # compileSdk is 35, targetSdk 34
echo "sdk.dir=$ANDROID_HOME" > local.properties       # gitignored
```

JDK 17 for Gradle: `export JAVA_HOME=$(/usr/libexec/java_home -v 17)`.

## Demo phone (needs >= 8 GB RAM for the local model; the spec recommends 12 GB)

1. Developer options -> USB debugging on. `adb devices` should list it.
2. Install the sideloaded APK: `./gradlew :app:installDebug` (or `adb install -r app/build/outputs/apk/debug/app-debug.apk`).
   Android 13+: App info > ⋮ > **Allow restricted settings** BEFORE enabling the accessibility service and notification access.
3. **Day-0 audio test first (30 min).** Decides audio option A/B/C/D. See the spec's "Call audio capture: reality check".
4. Ollama in Termux (from F-Droid or GitHub, **not** the Play Store — the Play build is obsolete and rejects `RUN_COMMAND`).
   Install Termux before Kavach, or reinstall Kavach afterwards, so Android recognises Termux's permission.
   When Protection is switched ON, Kavach asks Termux to start `ollama serve` on `127.0.0.1:11434` if it is not already running, and again before a model call if the last call failed to connect (at most once a minute). The keyword analyst still scores the call if that fails.

   One-time, **inside Termux** (Kavach cannot write this file). `allow-external-apps=true` is required or Termux ignores the intent:

   ```bash
   pkg update -y && pkg upgrade -y
   pkg install -y ollama procps
   mkdir -p ~/.termux
   props=~/.termux/termux.properties
   touch "$props"
   grep -v '^allow-external-apps=' "$props" > "$props.tmp" || true
   echo 'allow-external-apps=true' >> "$props.tmp"
   mv "$props.tmp" "$props"
   ollama pull gemma3:4b        # or gemma3:1b / qwen2.5:3b if it is slow
   ```

   Termux reads `termux.properties` only at process start. Force-stop it, then open it once:

   ```bash
   adb shell am force-stop com.termux
   ```

   In Kavach > Setup, the row **Local AI auto-start (Termux)** requests `com.termux.permission.RUN_COMMAND` (the button opens Termux). From the computer, after Termux is installed:

   ```bash
   adb shell pm grant com.kavach.app com.termux.permission.RUN_COMMAND
   ```

   Exempt Termux from battery optimisation or Android will kill `ollama serve`. Debug log line when it works: `Asked Termux to start Ollama`.

   **Alternative: [Termux:Boot](https://github.com/termux/termux-boot)** starts Ollama at device boot, before anyone opens Kavach. Kavach does not listen for `BOOT_COMPLETED`; it starts Ollama when its own process comes up with Protection already on. Install Termux:Boot from F-Droid or GitHub (same rule: not Play), open it once, then in Termux:

   ```bash
   mkdir -p ~/.termux/boot
   cat > ~/.termux/boot/start-ollama << 'EOF'
   #!/data/data/com.termux/files/usr/bin/bash
   pgrep -x ollama >/dev/null || (OLLAMA_HOST=127.0.0.1:11434 OLLAMA_KEEP_ALIVE=24h nohup ollama serve >/dev/null 2>&1 &)
   EOF
   chmod +x ~/.termux/boot/start-ollama
   ```

   Exempt Termux:Boot from battery optimisation too. Both paths use `pgrep`, so they will not start two servers. `pgrep` comes from `procps` (`pkg install procps`) if the bootstrap does not have it.
5. Grant in order: Phone/Call log/Contacts, Microphone, Notifications, SMS, "Display over other apps", Notification access, Accessibility service, ignore battery optimisation.
6. Verify the foreground package names of the UPI apps on THIS phone: `adb shell dumpsys window | grep mCurrentFocus` while each app is open,
   then edit `core/src/main/resources/payment_apps.json` if they differ.

## On-device speech-to-text (Vosk, option A)

Models are not in git (see `.gitignore`). Download the small English (India) and Hindi packs from
[https://alphacephei.com/vosk/models](https://alphacephei.com/vosk/models):

* [vosk-model-small-en-in-0.4](https://alphacephei.com/vosk/models/vosk-model-small-en-in-0.4.zip) (~36 MB)
* [vosk-model-small-hi-0.22](https://alphacephei.com/vosk/models/vosk-model-small-hi-0.22.zip) (~42 MB)

Unzip so each folder contains `am/` and `conf/` (not a second nested copy of the same name, though the loader accepts that too).
Install the app once so the external-files directory exists, then push:

```bash
adb push vosk-model-small-en-in-0.4 /sdcard/Android/data/com.kavach.app/files/vosk-model-small-en-in-0.4
adb push vosk-model-small-hi-0.22 /sdcard/Android/data/com.kavach.app/files/vosk-model-small-hi-0.22
```

That path is `Context.getExternalFilesDir(null)`. A missing folder is skipped. If neither folder is a model, Home stays on
`ASR: none (push Vosk model)` and the app behaves as option D. Force-stop and reopen after pushing: models load once, off the
main thread, at process start.

Settings > Language picks which loaded model decodes each window (English → `en-in`, हिन्दी → `hi`). The other model stays
in memory and is used only when the preferred one returns no words. Audio is decoded in RAM only. It is never written to disk
and never uploaded. No extra permission is required beyond the microphone the app already asks for.

## On-device Whisper (sherpa-onnx, optional)

Vosk stays the default. Settings > Audio > **Whisper** selects a second engine: sherpa-onnx 2.0.1 with a multilingual
Whisper model (Hindi, Marathi, English). Language is auto-detected (`language=""`); Home shows `ASR: whisper (detected: mr)`.
The runtime does not download anything. If the model files are missing, the app stays on Vosk, then on `NoopAsr`.

sherpa-onnx is not on Maven. The Android AAR is Apache-2.0, about 47 MB (under the 50 MB cutoff, so it is not gitignored):

```bash
./scripts/fetch-sherpa-onnx.sh
# writes app/libs/sherpa-onnx-2.0.1.aar
# Gradle also fetches that URL when the file is absent, so a clean CI checkout still builds.
```

Weights are not in git. Fetch the int8 files (base, and tiny so a slow phone can drop down) and push them:

```bash
./scripts/fetch-sherpa-onnx.sh --models
# extracts int8 encoder, decoder and tokens into build/sherpa-models/sherpa-whisper/
```

Install the app once so the external-files directory exists, then:

```bash
DEST=/sdcard/Android/data/com.kavach.app/files/sherpa-whisper
adb shell mkdir -p "$DEST"
adb push build/sherpa-models/sherpa-whisper/. "$DEST/"
```

That path is `Context.getExternalFilesDir(null)/sherpa-whisper/`. The loader also accepts the unpacked
`sherpa-onnx-whisper-base` / `sherpa-onnx-whisper-tiny` folders (including one extra nested copy) and a generic
`encoder` / `decoder` / `tokens` trio in `sherpa-whisper/`. int8 is preferred over fp32. Force-stop and reopen, then
choose Whisper in Settings. The Debug log records each window as `asr window 5.0s decoded in X ms`. The engine uses
4 threads. If a base window takes longer than the audio in it and tiny is on disk, later windows use tiny and the log
says so. PCM stays in memory.

Official packs: [sherpa-onnx-whisper-base.tar.bz2](https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-whisper-base.tar.bz2)
and [sherpa-onnx-whisper-tiny.tar.bz2](https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-whisper-tiny.tar.bz2).

## Dev shortcuts

```bash
adb reverse tcp:11434 tcp:11434   # phone's 127.0.0.1:11434 -> laptop Ollama
# Protection ON also starts Termux's ollama on that port when no ollama process is running.
# Stop the phone server first (`pkill ollama` in Termux) if you want the laptop tunnel instead.
adb forward tcp:8765 tcp:8765     # laptop -> phone companion listener over USB
```

## Testing without the phone

`./gradlew :core:eval` replays scripted episodes through the real orchestrator. The in-app **Simulate** button plays the same scenarios on the
device, which is the backup demo if the live call fails.
