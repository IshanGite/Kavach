# Kavach: setup and test guide (for Tushar and his Claude Code)

Hackathon problem statement: **FIN-11, "Interrupting Coerced Payments Before They Complete"** (not FIN-06 or FIN-04).
Deliverables: simulated payment app with a risk engine, a scenario suite (normal vs coerced), and a role-play test of interventions.
Paste this file to Claude Code and say: "Follow docs/TUSHAR_SETUP.md step by step, run commands yourself, and ask me when a step needs my hands."
Nothing needs to be sent to you: everything below is downloaded from GitHub or the model authors' public sites.

## How it should behave (what we are testing)
Kavach is an always-on guardian. Once Protection is ON it sits in the background and shows nothing during normal life.
- **Incoming/active call from an unknown number**: it starts listening (on-device speech-to-text) and scoring the conversation.
- **Scam-sounding call + you open a payment app**: a full-screen pause appears BEFORE the PIN screen: the reasons, a cooling-off timer,
  three questions, one-tap "Call 1930" and "Call <family>", and a guardian SMS at the highest level. The user can always continue after the timer.
- **Scammy notification or SMS** (WhatsApp, Telegram, SMS, fake KYC/lottery/"police" messages): it raises a warning that explains
  why it looks like a scam and what to do (do not click, do not pay, call 1930). Normal notifications are ignored.
- **Normal life**: saved contact calls, ordinary payments, family chats: nothing appears.
Everything runs on the phone (no cloud, no laptop at demo time). Cloud fallback exists but is OFF by default.

## What runs on the phone
| Piece | Where | How you get it |
|---|---|---|
| Kavach app: engine, risk rules, overlay, Hindi/English/Marathi keyword analyst | inside the APK | build from source (Step 1) |
| Speech-to-text, fast, English + Hindi | Vosk, on the phone | auto-downloaded by `scripts/setup-phone.sh` (80 MB) |
| Speech-to-text, multilingual (Hindi, Marathi, English) + language detect | Whisper base + tiny int8 via sherpa-onnx | auto-downloaded by the same script (about 250 MB) |
| Better Hindi transcripts (optional) | Whisper small int8 (best size on disk is used; steps down to base/tiny if the phone is too slow) | `./scripts/fetch-sherpa-onnx.sh --small` before `setup-phone.sh` (about 360 MB more) |
| Optional scoring LLM | Termux + Ollama + `gemma3:1b` on the phone | Step 4 (0.8 GB) |

Total on the phone about 1.5 GB. A phone with 6 GB+ RAM is recommended.

## Prerequisites (install these first)
- Android phone with a SIM and a UPI app (GPay or PhonePe) already set up, Android 10+.
- Laptop with: Git, JDK 17, Android SDK (platform 35, build-tools 35, platform-tools / `adb`), Python 3, curl, unzip.
  `export ANDROID_HOME=<your sdk path>` (Windows: `$HOME/AppData/Local/Android/Sdk`). Use Git Bash on Windows.
- Phone: Developer options > USB debugging ON. OnePlus/Oppo/Xiaomi: also turn on "Disable permission monitoring" / "USB debugging (Security settings)".
- Wi-Fi on the phone for the one-time Termux/Ollama download.

## Step 1: get the code and install everything (one command)
```bash
git clone -b feat/om-helpline-vosk-asr https://github.com/IshanGite/Kavach.git
cd Kavach
./gradlew :core:test              # about 5 to 20 s, must be green (0 normal scenarios paused)
adb devices                       # the phone must show as "device"
./scripts/setup-phone.sh          # builds + installs the APK, downloads and pushes Vosk + Whisper models
```
If a download is blocked, the sources are: Vosk https://alphacephei.com/vosk/models (`vosk-model-small-en-in-0.4`, `vosk-model-small-hi-0.22`),
Whisper https://github.com/k2-fsa/sherpa-onnx/releases/tag/asr-models (`sherpa-onnx-whisper-base`). Models go in
`/sdcard/Android/data/com.kavach.app/files/`.

## Step 2: permissions (needs your hands)
Open Kavach > Home > **Fix now**: grant Phone, Contacts, Mic, SMS, Notifications, Display over other apps, battery exemption.
On OnePlus with "Disable permission monitoring" ON, Claude can grant runtime permissions with
`adb shell pm grant com.kavach.app android.permission.<NAME>` (READ_PHONE_STATE, READ_CALL_LOG, READ_CONTACTS, RECORD_AUDIO, SEND_SMS, RECEIVE_SMS, POST_NOTIFICATIONS).
Android 13+: App info > the three-dot menu > Allow restricted settings, THEN Settings > Accessibility > Kavach ON, and Notification access > Kavach ON.
Setup > **Request caller ID role** > Allow (reads the caller number without the restricted call-log permission).
Reinstalling the app turns Accessibility OFF again: switch it back on.
Then Settings > "Seed demo payment history", add one guardian number (a friend's), Home > Protection ON. Target: `Readiness: all set`.

## Step 3 (optional): Whisper instead of Vosk
Settings > Audio > Whisper (better for Hindi/Marathi, detects the language). Home shows `ASR: whisper (detected: hi)`.

## Step 4 (optional, recommended): on-phone LLM
Kavach starts Ollama when Protection is switched ON, and again before a model call if the last call could not connect (at most once a minute). You do not open Termux after every reboot. The keyword analyst still scores the call if Ollama is down. Debug log when the ask succeeds: `Asked Termux to start Ollama`.

1. Install Termux from F-Droid or GitHub, not the Play Store (the Play build cannot run this): https://github.com/termux/termux-app/releases (arm64-v8a APK) or https://f-droid.org/packages/com.termux/ then `adb install -r <apk>`. Install Termux before Kavach, or reinstall the Kavach APK afterwards, so Android recognises the permission.
2. One-time, inside Termux (Kavach cannot write Termux's home directory):
```bash
pkg update -y && pkg upgrade -y
pkg install -y ollama procps
mkdir -p ~/.termux
props=~/.termux/termux.properties
touch "$props"
grep -v '^allow-external-apps=' "$props" > "$props.tmp" || true
echo 'allow-external-apps=true' >> "$props.tmp"
mv "$props.tmp" "$props"
echo 'curl -sf 127.0.0.1:11434 >/dev/null 2>&1 || (termux-wake-lock 2>/dev/null; OLLAMA_HOST=127.0.0.1:11434 OLLAMA_KEEP_ALIVE=24h nohup ollama serve >/dev/null 2>&1 &)' >> ~/.bashrc
source ~/.bashrc && sleep 4 && ollama pull gemma3:1b
```
3. Force-stop Termux so it reloads `termux.properties`, then open it once. From the computer: `adb shell am force-stop com.termux`.
4. Kavach > Setup > row **Local AI auto-start (Termux)** > **Allow Termux commands** (or `adb shell pm grant com.kavach.app com.termux.permission.RUN_COMMAND`). The same row's button opens Termux. Battery > Termux > Unrestricted.
5. Home > Protection ON. Kavach talks only to `127.0.0.1:11434`.

### Alternative: Termux:Boot (Ollama at device boot)
Kavach does not receive `BOOT_COMPLETED`. It starts Ollama when its process is up and Protection is on. To start Ollama at device boot before anyone opens Kavach, install [Termux:Boot](https://github.com/termux/termux-boot) from F-Droid or GitHub (not Play), open Termux:Boot once, then in Termux:
```bash
mkdir -p ~/.termux/boot
cat > ~/.termux/boot/start-ollama << 'EOF'
#!/data/data/com.termux/files/usr/bin/bash
curl -sf 127.0.0.1:11434 >/dev/null 2>&1 || (termux-wake-lock 2>/dev/null; OLLAMA_HOST=127.0.0.1:11434 OLLAMA_KEEP_ALIVE=24h nohup ollama serve >/dev/null 2>&1 &)
EOF
chmod +x ~/.termux/boot/start-ollama
```
Exempt Termux:Boot from battery optimisation. The script and Kavach both check the port first, so they will not start two servers.

`adb reverse tcp:11434 tcp:11434` uses the same port for a laptop Ollama. Stop the phone's server first (`pkill ollama` in Termux) or the phone process keeps the port.

Measured on OnePlus 7 Pro: about 3.6 s per scoring call. If it is slower than 8 s on your phone, skip it: the keyword analyst still works.

## Step 5: make the test audio (no files needed)
```bash
pip install edge-tts
python -m edge_tts --voice en-IN-PrabhatNeural --text "I am Inspector Sharma from Cyber Crime CBI. Your Aadhaar is linked to a money laundering case. You are under digital arrest. Do not tell anyone, not even your family. Transfer fifty thousand rupees to sharma dot verify at okicici immediately or you will be arrested." --write-media scam_en.mp3
python -m edge_tts --voice hi-IN-MadhurNeural --text "Main CBI officer Sharma bol raha hoon. Aapke Aadhaar se money laundering hui hai, aap digital arrest mein hain. Kisi ko mat batana. Abhi pachaas hazaar rupaye transfer karo, warna arrest ho jaoge." --write-media scam_hi.mp3
```
Play them from a second phone or laptop speaker next to the test phone. (A friend reading the script in a normal voice is the other half of the test.)

## Step 6: tests (tick each, report the result)
| # | Do | Expect |
|---|---|---|
| 1 | Home > Simulate > `scam-digital-arrest-english-demo` @4x | pause screen, reasons, timer, Call 1930 button; after the timer "continue" unlocks and dismisses |
| 2 | Home > Audio test (normal call on speaker, then WhatsApp call) | SILENT/SIGNAL table; set the best source in Settings |
| 3 | A friend calls from an unsaved number | Debug log `CallStarted <number>`; mic level moves |
| 4 | Friend reads the caller script (normal voice, then play the AI-voice audio) | L above 60, nudge banner; Home shows detected language |
| 4b | **Kavach Pay (the FIN-11 simulated payment app, no bank or SIM needed):** Home > Kavach Pay. Tap "Role-play: normal" then Pay | nothing appears, goes straight to the mock PIN (any 4-6 digits), success screen |
| 4c | Kavach Pay > "Role-play: coerced" > Pay (it injects an unknown call + scam line and a new payee with Rs 50,000) | full pause BEFORE the PIN: countdown ring, reasons, Call 1930. The floating widget shows the live transcript. Judges can re-run it any number of times |
| 5 | (Real app, optional) During the call open GPay/PhonePe to a TEST contact, type the UPI id the caller said, amount 50000. **STOP BEFORE THE PIN. NEVER PAY.** | full pause BEFORE the PIN screen. If amount/payee are not read: Debug > Last accessibility screen > Copy dump |
| 6 | Saved-contact call, pay Rs 200 | nothing appears |
| 7 | Tier 3 with a guardian number set | guardian SMS arrives; reply `HOLD` extends the timer |
| 8 | Airplane mode ON, repeat 4 | still works (no cloud dependency) |
| 9 | Send the phone a scam-style SMS/WhatsApp ("Your KYC expires today, click link, pay Rs 10") | a scam-message warning that explains why (feature being finished: report exactly what you see, including if nothing appears) |
| 10 | Instagram/WhatsApp call from another phone (speaker on), caller script | `CallStarted`, transcript, nudge. If the call is not detected: Debug > Last accessibility screen > Copy dump |

Caller script (read slowly): "I am Inspector Sharma from Cyber Crime CBI. Your Aadhaar is linked to a money laundering case. You are under digital arrest. Do not tell anyone, not even your family. Transfer fifty thousand rupees to sharma dot verify at okicici immediately or you will be arrested."

## Time budget
About 1 to 1.5 h with Claude Code driving: 20 min build + models + permissions, 15 min Termux/Ollama, 30 to 40 min tests. Skip Step 4 if short on time.

## Troubleshooting
- `adb shell pm grant` fails: enable "Disable permission monitoring" (OnePlus) or grant in App info > Permissions.
- No transcript in Debug during a real call: many phones give the mic silence during calls. Run the Day-0 Audio test; fallbacks are speaker mode, the laptop companion (`companion/listener.py`), or Debug > Send transcript.
- `Ollama HTTP 404`: fixed (the app now uses whichever model is installed). `ollama list` in Termux to check.
- Setup row **Local AI auto-start (Termux)** stays "not granted": install Termux before Kavach, or reinstall Kavach, then tap **Allow Termux commands**.
- Debug log `Termux auto-start failed: SecurityException`: the Run command permission is missing.
- The log says `Asked Termux to start Ollama` but nothing answers on port 11434: `allow-external-apps=true` is missing, or Termux was not force-stopped after that edit.
- Never complete a real payment during tests.

## Report back to Om
Phone model + Android version, test 2 table, Debug > Copy log for tests 3, 4, 5, 8, 9, 10, and any screen that looked wrong.
