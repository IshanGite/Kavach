# Kavach: SIM-phone setup and test (for Tushar and his Claude Code)

Goal: run Kavach fully ON the phone (no laptop, no cloud), then test it against a real call and a real payment screen.
Paste this file to Claude Code and say: "Follow docs/TUSHAR_SETUP.md step by step and ask me when a step needs my hands."

## What runs where (final architecture)
| Piece | Where | How it gets on the phone |
|---|---|---|
| Kavach app (engine, overlay, scoring) | phone | `Kavach-debug.apk` (`adb install -r`) |
| Speech-to-text, fast, English + Hindi | phone, Vosk | push 2 model folders (80 MB) to the app's files dir |
| Speech-to-text, multilingual (Hindi, Marathi, English), language detect | phone, Whisper via sherpa-onnx | push the `sherpa-whisper/` folder (about 160 MB) |
| Language scoring LLM (optional, accuracy booster) | phone, Termux + Ollama + `gemma3:1b` (806 MB) | Termux APK + `pkg install ollama` + `ollama pull gemma3:1b` |
| Keyword analyst (EN/HI/Hinglish/Marathi), risk engine | phone, inside the app | nothing to install, it is the always-on fallback |

The APK does NOT contain the model files (too large). Everything else is on the phone after setup, and needs no cable or Wi-Fi at demo time.

## Prerequisites
- Android phone with a SIM and a UPI app (GPay/PhonePe) set up, Android 10+, 6 GB+ RAM recommended.
- Laptop with `adb` (Android platform-tools), USB debugging ON. OnePlus/Oppo/Xiaomi: also enable the extra
  "USB debugging (Security settings)" / "Disable permission monitoring" toggle so `adb shell pm grant` works.
- Wi-Fi on the phone for the one-time Termux/Ollama download.
- Files from Om: `Kavach-debug.apk`, `vosk-models/*.zip`, `sherpa-whisper/` folder, `audio/*.wav`.

## Step 1: install the app
```bash
adb install -r Kavach-debug.apk
adb shell am start -n com.kavach.app/.ui.MainActivity     # open once
```

## Step 2: push speech models (once)
```bash
unzip vosk-models/vosk-model-small-en-in-0.4.zip && unzip vosk-models/vosk-model-small-hi-0.22.zip
D=/sdcard/Android/data/com.kavach.app/files
adb shell mkdir -p $D
adb push vosk-model-small-en-in-0.4 $D/
adb push vosk-model-small-hi-0.22   $D/
adb push sherpa-whisper             $D/          # optional, multilingual incl. Marathi
```
Open Kavach > Home: it should say `ASR: vosk on-device (en-in+hi)`. In Settings > Audio you can switch to Whisper.

## Step 3: permissions
Home > Fix now > grant Phone, Contacts, Mic, SMS, Notifications, Display over other apps, battery exemption.
Android 13+: App info > ⋮ > Allow restricted settings, THEN Settings > Accessibility > Kavach ON.
Setup > "Request caller ID role" > Allow (lets Kavach read the caller number without READ_CALL_LOG).
Reinstalling the app turns Accessibility OFF again: switch it back on.
Settings > "Seed demo payment history", add one guardian number, set language. Home > Protection ON.
Target: `Readiness: all set`.

## Step 4: on-phone LLM (optional but recommended)
1. Install Termux from GitHub (not Play Store): https://github.com/termux/termux-app/releases (the `arm64-v8a` debug APK).
2. In Termux:
```bash
pkg update -y && pkg upgrade -y
pkg install -y ollama
echo 'pgrep -x ollama >/dev/null || (OLLAMA_HOST=127.0.0.1:11434 OLLAMA_KEEP_ALIVE=24h nohup ollama serve >/dev/null 2>&1 &)' >> ~/.bashrc
source ~/.bashrc && sleep 4
ollama pull gemma3:1b
```
3. Phone settings: battery > Termux > Unrestricted. Open Termux once after every reboot (it starts the server via `.bashrc`).
4. Kavach > Settings: language model should be `gemma3:1b` (default). Debug > Ping model: expect a reply in under about 8 s.
Measured on a OnePlus 7 Pro: about 3.6 s per scoring call, 16 tokens/s. If it is slower than 8 s on your phone, leave it OFF; the keyword analyst still works.

## Step 5: tests (tick each and report)
| # | Do | Expect |
|---|---|---|
| 1 | Home > Simulate > `scam-digital-arrest-english-demo` @4x | pause screen, reasons, timer, Call 1930 button; timer ends and "continue" unlocks |
| 2 | Home > Audio test (normal call on speaker, then WhatsApp) | table of SILENT/SIGNAL per source; set the best source in Settings |
| 3 | A friend calls from an unsaved number | Debug log `CallStarted <number>`; mic level moves |
| 4 | Friend reads the script below (normal voice, then play `audio/el_en.wav` / `el_hinglish.wav` from another phone) | L above 60, nudge banner; Home shows detected language |
| 5 | During the call open GPay to a TEST contact, type the UPI id the caller said, amount 50000. STOP BEFORE THE PIN. NEVER PAY. | full pause BEFORE the PIN screen. If amount/payee are not read: Debug > Last accessibility screen > Copy dump |
| 6 | Saved contact call + pay Rs 200 | nothing appears |
| 7 | Tier 3 with a guardian number | guardian SMS arrives, reply `HOLD` extends the timer |
| 8 | Airplane mode ON, repeat 4 | still works (proves no cloud dependency) |

Caller script (read slowly): "I am Inspector Sharma from Cyber Crime CBI. Your Aadhaar is linked to a money laundering case. You are under digital arrest. Do not tell anyone, not even your family. Transfer fifty thousand rupees to sharma dot verify at okicici immediately or you will be arrested."

## Troubleshooting
- `adb shell pm grant` fails: enable "Disable permission monitoring" (OnePlus) or grant manually in App info > Permissions.
- No transcript lines in Debug: mic returns silence during calls on many phones. Use Day-0 Audio test; fallback is the laptop companion (`companion/listener.py`) or Debug > Send transcript.
- `Ollama HTTP 404` in the log: the model name does not match; `ollama list` in Termux, then set the model in Settings.
- Pause screen stuck: fixed (timer runs after Simulate ends); force-stop Kavach if you ever see it.
- Never complete a real payment during tests.

## Report back to Om
Phone model + Android version, test 2 table, Debug > Copy log for tests 3-5 and 8, and any screen that looked wrong.
