# Kavach: setup and test guide (for Tushar and his Claude Code)

Hackathon problem statement: **FIN-11, "Interrupting Coerced Payments Before They Complete"** (not FIN-06 or FIN-04).

Deliverables this build is meant to show:

1. A simulated payment app with a risk engine (Kavach Pay, plus the on-device engine).
2. A scenario suite of normal vs coerced payments.
3. A role-play test of the interventions (pause, widget, 1930, guardian SMS).

Paste this file to Claude Code and say: "Follow docs/TUSHAR_SETUP.md step by step, run commands yourself, and ask me when a step needs my hands."

Nothing is sent to you. Clone the branch below. Models come from the authors' public URLs. Do not put an API key in this file, in chat, or in git. Leave **Allow cloud fallback** off.

## What you are testing

Kavach watches the coercion context around a payment (unknown live call, urgency, secrecy, a payment app opening). It stays quiet in normal life. The user can always continue after the pause. The LLM never picks the tier.

| Piece | What it actually does |
|---|---|
| Guardian widget | While Protection is on and **Show floating widget** is on (the default), a shield bubble sits over other apps. It expands on an unknown live call, on a scam-looking SMS/notification (classifier confidence at least 0.5), or when the tier rises. The card shows the caller, a live transcript (last 3 lines), **What they're saying**, **What Kavach noticed**, and a score line **Situation / Saying / Scam risk**. Buttons: **Call 1930** (opens the dialer and waits), **Call &lt;name&gt;** (first Family contact, or the first Guardian if Family is empty; this one places the call when `CALL_PHONE` is granted), **Dismiss**, **Collapse**. At tier 2+ while the full-screen pause is up, it shrinks back to the bubble. |
| Full-screen pause | Before the PIN, at tier 2 (30 s) or tier 3 (120 s). Headline, countdown, reasons, three yes/no questions, **Call &lt;name&gt; now** (first Guardian, otherwise the first Family contact), **Call 1930 (cybercrime helpline)** (dialer, waits). After the timer the button is **I understand the risk, continue**. |
| Kavach Pay | In-app mock UPI. Home button **Kavach Pay (demo payment app)**. Red ribbon **DEMO: no real money**. **Role-play: normal** and **Role-play: coerced**. Success text: **Payment simulated, no money moved**. |
| Cellular calls | Setup **Request caller ID role** (call screening). Kavach always allows the call. If the role is missing, it falls back to the phone-state broadcast; the number then needs `READ_CALL_LOG` and is often blank on Android 10+. |
| VoIP calls | While Protection is on, audio mode `MODE_IN_COMMUNICATION` (polled every 1.5 s) counts as a call for Instagram, WhatsApp, Telegram, Meet, and Messenger. Call-screen text is only a fallback. Instagram does not need readable call-screen text. |
| Speech-to-text | Vosk `en-in` + `hi` by default. Optional Whisper via sherpa-onnx: **base** int8, **tiny** int8 if base is slower than the window. Language policy is Hindi / English / Marathi (`hi` / `en` / `mr`). Settings **Speech language (Whisper)**. 8 s windows every 4.5 s, gain-normalised, overlap words dropped. Whisper **small** is optional (`./scripts/fetch-sherpa-onnx.sh --small`, about 360 MB, pushed with the same folder). The engine uses the best size on disk and steps down small to base to tiny if decoding is too slow. In Om's phone-speaker test small hallucinated on very faint audio while base was steadier: if you see junk transcripts, delete the `small-*` files from `sherpa-whisper/`. |
| Keyword analyst | English, Hindi, Hinglish, and Marathi (Devanagari and romanised). Tolerates some ASR noise. This is what the offline eval uses. |
| Local LLM (optional) | `gemma3:1b` in Termux. Kavach starts it with Termux `RUN_COMMAND` when Protection turns on, and again after a connect failure, at most once a minute. Default URL `http://127.0.0.1:11434`, timeout 8 s. |
| Laptop companion (fallback) | `companion/listener.py` when the phone mic is silent during a call. Off unless Settings **Companion listener (option C)** is on. |
| Guardian SMS + HOLD | Real SMS only from the live engine at tier 3, to numbers in Settings **Guardians**. Text is a short summary, never a transcript, and ends with `Reply HOLD to extend the pause by 10 minutes.` A reply that starts with `HOLD` from that number extends the timer by 10 minutes. Same alert is not sent again for 10 minutes. |
| Complaint PDF | Home **Complaint report**, then **Share as PDF**, after a scam session. |
| Scenario suite | `core/src/main/resources/scenarios/index.json`: **37 scam, 32 normal** (69). No `known_gap` entries. Last recorded run (HANDOVER checkpoint 2026-11-04; `core/build/reports/kavach-eval/scorecard.md` is not in this tree): **37/37 scams paused before payment, 0/32 normal paused**. README's 34/34 and 61 is stale. Re-run eval on your machine and trust that scorecard. |

Home risk meters are labelled **Situation**, **What they're saying**, and **Scam risk**. The widget's one-line score shortens the middle one to **Saying**.

## What runs on the phone

| Piece | Where | How you get it |
|---|---|---|
| App (engine, widget, Kavach Pay, overlay) | APK `com.kavach.app` | `./scripts/setup-phone.sh` |
| Vosk small en-IN + hi | `/sdcard/Android/data/com.kavach.app/files/vosk-model-small-en-in-0.4` and `.../vosk-model-small-hi-0.22` | same script (~80 MB) |
| Whisper base + tiny int8 | `.../files/sherpa-whisper/` (`base-encoder.int8.onnx`, `base-decoder.int8.onnx`, `base-tokens.txt`, and the `tiny-*` pair) | same script (base is about 250 MB; tiny is included) |
| Optional LLM | Termux + Ollama + `gemma3:1b` | Step 4 |

`setup-phone.sh` never commits models and the app does not download them at runtime. A phone with a SIM, Android 10+ (minSdk 29). `gemma3:1b` wants roughly 8 GB RAM; speech models run without it.

## Prerequisites

- Git, JDK 17 (Gradle 8.13; 21+ fails), Android SDK platform 35, build-tools 35.0.0, platform-tools (`adb`), Python 3, curl, unzip. Use Git Bash on Windows.
- `compileSdk` 35, `targetSdk` 34, `applicationId` `com.kavach.app`.
- Phone: Developer options, USB debugging on. OnePlus / Oppo / Xiaomi: also **Disable permission monitoring** (wording varies) or `pm grant` is rejected.
- Wi-Fi only for the one-time model, Termux, and Ollama downloads.

```bash
# JDK 17. macOS: export JAVA_HOME=$(/usr/libexec/java_home -v 17)
export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-17"   # adjust to your JDK 17
export ANDROID_HOME="$LOCALAPPDATA/Android/Sdk"               # Git Bash; macOS example: /opt/homebrew/share/android-commandlinetools
printf 'sdk.dir=%s\n' "C:/Users/YOU/AppData/Local/Android/Sdk" > local.properties
```

`local.properties` is gitignored. `sdk.dir` must be the real SDK path with forward slashes.

## Step 1 — clone, test, install

```bash
git clone -b feat/om-helpline-vosk-asr https://github.com/IshanGite/Kavach.git
cd Kavach
./gradlew :core:test          # unit tests + eval gate + security gates
./gradlew :core:eval          # writes core/build/reports/kavach-eval/scorecard.md
adb devices                   # a row ending in "device", not "unauthorized" or "offline"
./scripts/setup-phone.sh      # :app:installDebug, downloads Vosk + Whisper base and tiny, pushes them
```

Models-only later: `./scripts/setup-phone.sh --no-build`.

If a download is blocked, fetch by hand and push the same paths:

- Vosk: https://alphacephei.com/vosk/models (`vosk-model-small-en-in-0.4`, `vosk-model-small-hi-0.22`)
- Whisper: `./scripts/fetch-sherpa-onnx.sh --models` (base + tiny; add `--small` for the optional small model)

Then force-stop Kavach and open it once so the models load. Every `installDebug` turns **Accessibility off**. Turn it back on (Step 2) after this script.

## Step 2 — permissions (your hands)

Open Kavach. Home hero is **Off** until you flip the switch; it then says **Protected**. Debug log line: `note: Protection ON`.

1. Home **Fix now** (or **Review setup**) opens Setup.
2. **Grant runtime permissions.** On a OnePlus-style phone with permission monitoring disabled, the computer can grant the runtime ones:

```bash
PKG=com.kavach.app
for p in READ_PHONE_STATE READ_CALL_LOG READ_CONTACTS RECORD_AUDIO \
         RECEIVE_SMS SEND_SMS READ_SMS CALL_PHONE POST_NOTIFICATIONS; do
  adb shell pm grant $PKG android.permission.$p
done
```

`POST_NOTIFICATIONS` exists only on Android 13+. `pm grant` cannot grant Accessibility, Notification access, the caller-ID role, or overlay. Overlay, if you need the adb form: `adb shell appops set com.kavach.app SYSTEM_ALERT_WINDOW allow`.

3. Android 13+: App info, three-dot menu, **Allow restricted settings**, then Settings **Accessibility** (the in-app button is **Accessibility**) and turn Kavach on. Then **Notification access**.
4. **Allow overlay** (**Display over other apps**). The pause can use the accessibility overlay instead; the widget needs one of the two. If both are missing, Debug shows `WIDGET BLOCKED` or `OVERLAY BLOCKED`.
5. **Ignore battery optimisation.** On OnePlus / Xiaomi / Samsung also set Autostart / Unrestricted and lock Kavach in Recents.
6. **Request caller ID role** and allow it. The button becomes **Caller ID role granted**. If the phone has no such role, Setup says so and detection stays on the phone-state broadcast.
7. Settings **Seed demo payment history** (usual payees, so "new payee" and a large amount mean something). Debug: `note: Baseline seeded with demo payment history`.
8. Settings **Guardians**: Add, name + real mobile. That number receives the tier-3 SMS, and the pause screen's **Call &lt;name&gt; now** uses this person first. Settings **Family to call back**: Add a name and number. The widget's **Call &lt;name&gt;** uses Family first, then Guardian.
9. Home switch on. Settings **Show floating widget** stays on.

**Readiness: all set** means every Setup row is green, including **Local AI auto-start (Termux)**. If you skip Step 4, that one row stays red and Home says `Readiness: 1 item(s) need attention`. That is expected for keyword-only testing. Say which it was.

Reinstall (`./gradlew :app:installDebug` or `setup-phone.sh` without `--no-build`) turns Accessibility **off** again. Turn it back on before any test. Do not force-stop the app before **Debug > Copy log**; the log is in memory (last 300 lines).

## Step 3 — Whisper (do this before Hindi or Marathi)

Settings **Audio**: chip **Whisper** (not the default **Vosk**). Then **Speech language (Whisper)**:

| Chip | Use |
|---|---|
| **Auto (hi/en/mr)** | Default. A detect outside hi/en/mr is re-decoded as Hindi when it fits in the window. Urdu is treated as Hindi. |
| **Hindi** / **English** / **Marathi** | Force that language for the clip. |

Vosk ignores those chips. Vosk follows **Language: English** (`en-in`) or **हिन्दी** (`hi`). Home chip **On-device speech** should show `whisper (detected: hi)` (or `en` / `mr`) after a window, or `vosk on-device (en-in+hi, using en-in)` if you left it on Vosk. Missing Whisper files fall back to Vosk, then `none (push Vosk model)`.

The mic runs only during an **unknown** call (real unsaved number, or Debug **Unknown call**). A saved contact does not start capture.

## Step 4 — on-phone LLM (optional)

Skip this if time is short. The keyword analyst still scores. L may wait up to the 8 s timeout when the model is enabled and slow; C does not wait.

1. Install Termux from GitHub, not Play Store (the Play build rejects `RUN_COMMAND`): https://github.com/termux/termux-app/releases — the arm64-v8a APK. The in-app row also accepts F-Droid. `adb install -r` that APK. Install Termux before Kavach, or reinstall the Kavach APK afterwards, or Android will not recognise the permission.
2. Inside Termux, once:

```bash
pkg update -y && pkg upgrade -y
pkg install -y ollama procps
mkdir -p ~/.termux
props=~/.termux/termux.properties
touch "$props"
grep -v '^allow-external-apps=' "$props" > "$props.tmp" || true
echo 'allow-external-apps=true' >> "$props.tmp"
mv "$props.tmp" "$props"
ollama pull gemma3:1b
```

3. From the computer: `adb shell am force-stop com.termux`, then open Termux once so it reloads `termux.properties`. Kavach cannot write that file.
4. Kavach Setup, row **Local AI auto-start (Termux)**, button **Allow Termux commands**. Or:

```bash
adb shell pm grant com.kavach.app com.termux.permission.RUN_COMMAND
```

5. Battery: Termux **Unrestricted**.
6. Home switch off, then on. Debug must show `note: Asked Termux to start Ollama`. Kavach talks only to `127.0.0.1:11434`. Settings **Model** default is `gemma3:1b`. If that name is not installed, the app asks Ollama `/api/tags` and uses the configured name, else the first `gemma*`, else the first installed model.

Check: Debug **Ping model with the transcript line**. Expect a provider and a latency. Over 8 s, leave the model installed but treat keywords as the result that matters.

Optional boot helper (not required): Termux:Boot from GitHub or F-Droid, not Play. Open it once, then the script in `docs/ANDROID_SETUP.md` under `~/.termux/boot/start-ollama`. Kavach does not receive `BOOT_COMPLETED`.

Laptop Ollama instead: `adb reverse tcp:11434 tcp:11434`, and `pkill ollama` inside Termux first so the phone process is not holding the port.

## Step 5 — sample audio

```bash
python -m pip install edge-tts
python -m edge_tts --voice en-IN-PrabhatNeural --text "I am Inspector Sharma from Cyber Crime CBI. Your Aadhaar is linked to a money laundering case. You are under digital arrest. Do not tell anyone, not even your family. Transfer fifty thousand rupees to sharma dot verify at okicici immediately or you will be arrested." --write-media scam_en.mp3
python -m edge_tts --voice hi-IN-MadhurNeural --text "मैं इंस्पेक्टर शर्मा मुंबई साइबर क्राइम ब्रांच से बोल रहा हूं। आपके नाम पर एक पार्सल में ड्रग्स मिले हैं। आप पर मनी लॉन्ड्रिंग का केस दर्ज हुआ है। आपको डिजिटल अरेस्ट किया जा रहा है। किसी को मत बताना और कॉल मत काटना। अभी तुरंत वेरिफिकेशन अमाउंट ट्रांसफर करें।" --write-media scam_hi.mp3
python -m edge_tts --voice mr-IN-ManoharNeural --text "मी सीबीआय अधिकारी बोलतोय. तुम्ही अटकेत आहात. तुमचं खातं गोठवलं जाईल. कोणालाही सांगू नका. ताबडतोब पैसे ट्रान्सफर करा." --write-media scam_mr.mp3
```

Use `python3` if `python` is missing. Play from a second phone or a laptop speaker, loud, next to the test phone, with an unknown call already started (Step 3). These clips test speech-to-text only. There is no AI-voice detector in the app (see Known limits).

## Step 6 — tests

Protection **Protected**, widget on, Accessibility on. For any live-mic test, Home **Mic level** must move off −90 while the caller talks. Copy Debug **before** you force-stop.

| # | Do | Expect | Send back |
|---|---|---|---|
| 1 | Home **Simulate**. Speed **4x**. Play `scam-digital-arrest-english-demo`. Do not wait out a 120 s timer; Stop when you have seen the pause. | Full-screen pause: reasons, countdown, three questions, **Call 1930 (cybercrime helpline)**. Button stays **You can continue in m:ss** until the timer ends, then **I understand the risk, continue**. Sim log shows a **dry-run** guardian SMS (not a real text; Simulate does not use your Settings number). Widget drops to the bubble while that pause is up. | Did the pause appear, and one Sim-log line. |
| 2 | Home **Kavach Pay (demo payment app)**. **Role-play: normal** (fills Rahul, ₹500, clears any demo call). Tap **Pay**. Enter any 4–6 digits. | No pause. PIN screen **Enter UPI PIN**. Success **Payment simulated, no money moved**. Ribbon **DEMO: no real money**. | Yes/no, plus any banner that appeared. |
| 3 | Back to Kavach Pay. **Role-play: coerced** (status: new payee `verify.pay@oksbi`, ₹50,000, unknown call + scam line). Tap **Pay**. Stop on the pause. Do not finish unless you mean to. | Screen **Paused before the PIN** and the same full-screen pause, before **Enter UPI PIN**. Widget expands with the injected line under **What they're saying**. Tier 3 can text your saved guardian for real. | Tier number, whether the pause was before the PIN, whether an SMS arrived. |
| 4 | Home **Audio test** (title **Day-0 audio test**). Label `cellular`. Normal call on speaker, caller talks. **Start 20 s per source** (~80 s). Repeat with label `whatsapp` on a WhatsApp call. | Each of `VOICE_RECOGNITION`, `MIC`, `CAMCORDER`, `UNPROCESSED` is **HAS SIGNAL**, **VERY QUIET**, or **SILENT**. Tap **Use this source** on the first **HAS SIGNAL**. | The eight verdicts and which source you selected. |
| 5 | Friend calls from a number **not** in contacts. Answer. Talk 20 s. Then they read the English script in Step 5, slowly, on speaker, volume up. | Debug `event: CallStarted <number> (unknown)`. Home mic level moves. Widget expands (**Unknown caller**, **Listening for speech**, then transcript). Later `event: Transcript "..."` (60 characters, then …) and `note: mic rms ... dBFS gain +... dB`. A saved contact is `(contact Name)` and does **not** start the mic or expand the widget. | The CallStarted line, mic dBFS, one Transcript line, widget expanded yes/no. |
| 6 | During that unknown call, open GPay or PhonePe to a **saved test payee**. You may show amount and payee. **Stop before the PIN. Do not confirm. Do not pay.** A new VPA can hit a real lookup; prefer a payee you already have. | Pause before the PIN if the call still looks coerced. Debug **Last accessibility screen (for tuning a UPI app)** shows `stage=... amount=... payee=...` and package `com.google.android.apps.nbu.paisa.user` or `com.phonepe.app`. Button **Copy dump (paste into ScreenParserTest)**. | The summary line (`stage/amount/payee/pkg`). Paste the dump only if stage, amount, or payee is wrong. |
| 7 | Instagram or WhatsApp call from the other phone, **speaker on, volume high**. Protection already on. Read the English script. | Debug `note: VoIP call (audio mode) started: Instagram` (or WhatsApp). Then `event: CallStarted voip:... (unknown) VoIP`. Widget expands. Transcript may be garbled; report it anyway. Call-screen text is not required. | The VoIP note, mic dBFS, one Transcript line, whether a warning or pause appeared. |
| 8 | Airplane mode on. Repeat test 3 (Kavach Pay coerced). Optionally also Debug **Unknown call** and play `scam_en.mp3`. | Pause still appears. No cloud. On-device models need no network. Guardian SMS will **not** send with the radios off. | Pause yes/no. ASR lines if you played audio. |
| 9 | Airplane off. SMS the phone: `Your KYC will expire today, account blocked. https://sbi-kyc.xyz/u` (or the same text in WhatsApp). | Widget **Scam message**, why it includes KYC/account block, advice **Do not click the link.** / **Do not pay or share a PIN or OTP.** / **If money already moved, call 1930.** Normal chats stay quiet. | What the card said, or "nothing". |
| 10 | Guardian number saved. Trigger tier 3 (test 3, or Debug **Unknown call**, **Send transcript**, **Pay app**, **₹50k**). Wait for the SMS. From that phone reply `HOLD`. Wait 10 minutes before a second try or the cooldown swallows it. | SMS starts `Kavach alert:` and tells them to reply HOLD. It has no transcript. After HOLD the pause timer jumps by 10 minutes. A HOLD from any other number does nothing. | Did the SMS arrive, and did HOLD move the timer. |
| 11 | On the widget or the pause screen, tap **Call 1930**. | The dialer opens with `1930` filled in and waits. **Do not press call** unless you mean to reach the helpline; if you do, hang up at once. (Complaint report **Call 1930** is different: it can place the call when `CALL_PHONE` is granted. Use the widget or the pause button for this test.) | Dialer showed 1930 yes/no. |
| 12 | Settings **Speech language (Whisper)** = **Hindi**. Debug **Unknown call**. Play `scam_hi.mp3` loud on speaker. Repeat with **Marathi** and `scam_mr.mp3`. | Home **On-device speech** becomes `whisper (detected: hi)` then `mr`. Debug has `note: asr window 8.0s decoded in <ms> ms` (5.0 s only if it logged `falling back to 5 s windows`). Transcript lines in the log and on the widget. L / **What they're saying** moves up if the words survived. | For each language: detected code, one Transcript line, the `asr window` line, and the Saying score. |

Caller script, if a person reads it: "I am Inspector Sharma from Cyber Crime CBI. Your Aadhaar is linked to a money laundering case. You are under digital arrest. Do not tell anyone, not even your family. Transfer fifty thousand rupees to sharma dot verify at okicici immediately or you will be arrested."

### Transcripts to send back

There is **no** Settings switch **Show transcript in Debug log**. Do not look for it. Transcripts are already in Debug **Log (newest first)** as `event: Transcript "..."`, cut at 60 characters. **Copy log** puts that on the clipboard.

Privacy: send the test-script lines and the `mic rms` / `asr window` notes. Do not paste a real person's unrelated conversation, OTPs, account numbers, or the full call. The widget and Live view show more than the 60-character log line; quote only the scam-test sentences.

## Time budget

About 1 to 1.5 h with Claude Code driving: 20 min clone, `:core:test`, models, permissions; 15 min Termux if you do Step 4; 30–40 min tests. Skip Step 4 first if the clock is short. Do test 4 (Day-0) before you depend on a live call transcript.

## Known limits (say these plainly)

**Verified in this repo, not by a fresh eval in the session that wrote this file**

- Engine, keyword analyst (EN/HI/Hinglish/Marathi), 37 scam + 32 normal scenarios, pause-before-PIN gate, guardian HOLD rules, Kavach Pay role-play, widget mapping, call-screening role, VoIP audio-mode detector, Vosk, Whisper base+tiny (+ optional small), 8 s windows, gain normalisation, Termux `RUN_COMMAND` auto-start.
- Last recorded scorecard: **37/37 scams paused, 0/32 normal paused**. Confirm with `./gradlew :core:eval` on your laptop.

**Seen earlier on a OnePlus 7 Pro, not on your SIM phone**

- Simulate pause; Whisper from a speaker into the mic (`whisper (detected: hi)`); Vosk on a played voice.
- Instagram: audio-mode detection, widget, and a warning. Speaker-to-mic transcript was too garbled to flag the script (Saying stayed low, about 13).
- Accessibility can see GPay open. Amount and payee on that phone were not confirmed.
- Termux `gemma3:1b` was about 3.6 s per call on that phone. Yours may differ.

**Not verified on a SIM phone — your tests are the first**

- Mic audio during a real cellular call (Android often silences other apps).
- Caller-ID number on a real incoming call.
- GPay / PhonePe amount and payee text.
- Guardian SMS delivery and Hindi TTS (missing Hindi voice falls back to English).
- Widget drag, expand, and living beside the pause on your device.

**Other limits**

- AI-voice detection was measured and **not shipped** (`docs/DECISIONS.md`, section 35). Both models missed the bar on phone-quality audio. Do not expect a "synthetic voice" alert. Edge-TTS is only for speech-to-text.
- Speaker audio is faint. Use speakerphone and high volume. Gain lifts speech above about −55 dBFS, up to +30 dB, and does not amplify silence.
- Instagram / WhatsApp detection uses audio mode. A screenshot of the call screen is not required.
- Option D (no transcript) can still pause: unknown call + payment app + new payee + amount. Scenario `scam-option-d-no-audio`.
- The widget does not decide the tier. Only the risk engine does.
- Cloud fallback exists and is off. Leave it off.

## Troubleshooting

| Symptom | What to do |
|---|---|
| `adb` offline or unauthorized | Unplug, `adb kill-server`, `adb start-server`, replug, accept the RSA prompt. Revoke USB debugging authorizations on the phone if it keeps failing. |
| Wireless adb | Same Wi-Fi. `adb tcpip 5555` while USB still works, then `adb connect <phone-ip>:5555`. Android 11+: Developer options **Wireless debugging**, pair with the code it shows. |
| `pm grant` fails | Turn on **Disable permission monitoring** (OnePlus / Oppo) or grant in App info **Permissions**. |
| No transcript on a real call | Home mic level stuck at −90 means the OS gave silence. Use the Day-0 source that said **HAS SIGNAL**, speaker + high volume, or the laptop companion below. Or Debug **Send transcript** to inject a line. |
| Companion (mic silent) | Settings **Companion listener (option C)** on, token at least 8 characters (make one up; do not reuse a password). `adb forward tcp:8765 tcp:8765`. Then `python3 companion/listener.py --token <token>`. Type-only check: add `--stdin`. USB, not Wi-Fi. Debug: `Companion listener on port 8765`. 401 = token mismatch. |
| `Ollama HTTP 404` | In Termux, `ollama list`. Pull `gemma3:1b`. Settings **Model** should match a name from that list. The app rewrites a missing name from `/api/tags` when the server is up. A 404 with the server down means the tags call failed and the configured name was kept. |
| `Asked Termux to start Ollama` but nothing on port 11434 | `allow-external-apps=true` missing, or Termux was not force-stopped after the edit. |
| Setup row stays "not granted" / `Termux auto-start failed: SecurityException` | Install Termux, reinstall Kavach, tap **Allow Termux commands** or run the `pm grant` in Step 4. |
| Termux or Kavach killed | Battery Unrestricted for both. Lock Kavach in Recents. |
| Accessibility off after install | Turn it on again. Android 13+: **Allow restricted settings** first or the toggle is grey. |
| Overlay missing | Accessibility on, or **Allow overlay**. |
| Log empty after you killed the app | The Debug log is memory-only. Copy it first. |
| Second guardian SMS never arrives | 10-minute cooldown. |

Never complete a real payment. Never type a UPI PIN into GPay, PhonePe, or any bank app during these tests.

## Report back to Om

- Phone model, Android version, and whether Home said **Readiness: all set** or which row was red.
- `:core:eval` scorecard line (scams paused, normal paused), if you ran it.
- Test 4 table (eight verdicts).
- Debug **Copy log** for tests 5, 6, 7, and 12. Transcript excerpts from the test scripts only.
- Tests 2, 3, 8, 9, 10, 11: one line each, pass or what you saw.
- Any screen whose label did not match this guide.
