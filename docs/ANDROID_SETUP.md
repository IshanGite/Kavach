# Android setup (demo phone)

## Dev machine

```bash
brew install --cask android-commandlinetools          # already done on Ishan's Mac
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
yes | sdkmanager --licenses
sdkmanager "platform-tools" "platforms;android-34" "build-tools;34.0.0"
echo "sdk.dir=$ANDROID_HOME" > local.properties       # gitignored
```

JDK 17 for Gradle: `export JAVA_HOME=$(/usr/libexec/java_home -v 17)`.

## Demo phone (needs >= 8 GB RAM for the local model; the spec recommends 12 GB)

1. Developer options -> USB debugging on. `adb devices` should list it.
2. Install the sideloaded APK: `./gradlew :app:installDebug`.
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

## Testing without the phone

`./gradlew :core:eval` replays scripted episodes through the real orchestrator. The in-app **Simulate** button plays the same scenarios on the
device, which is the backup demo if the live call fails.
