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
4. Ollama in Termux (from F-Droid, not Play Store):
   ```
   pkg install ollama
   ollama serve &
   ollama pull gemma3:4b        # or gemma3:1b / qwen2.5:3b if it is slow
   ```
   Exempt Termux from battery optimisation or Android will kill `ollama serve`.
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

## Dev shortcuts

```bash
adb reverse tcp:11434 tcp:11434   # phone's 127.0.0.1:11434 -> laptop Ollama (no Termux needed to start)
adb forward tcp:8765 tcp:8765     # laptop -> phone companion listener over USB
```

## Testing without the phone

`./gradlew :core:eval` replays scripted episodes through the real orchestrator. The in-app **Simulate** button plays the same scenarios on the
device, which is the backup demo if the live call fails.
