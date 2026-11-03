# Phone test plan

Everything in `app/` is unverified on a device until a row here is ticked. Work top to bottom; each test says what to do, what you should see, and where to look if not.
**Never complete a real payment.** Stop at the PIN screen. Use a test contact.

Debug aid: Home > Debug shows the event log, the last accessibility dump and lets you inject events. "Copy log" puts it on the clipboard so you can paste it to Claude.

| # | Test | Do | Expected | If it fails |
|---|---|---|---|---|
| T0 | Install | `./gradlew :app:installDebug` | App opens, Home shows protection OFF | `adb devices`; USB debugging; install blocked by Play Protect: choose "Install anyway" |
| T1 | Setup | Home > Fix now, grant everything. Android 13+: App info > ⋮ > Allow restricted settings first | Home readiness list all ✅ | Greyed-out toggles = restricted settings step missed |
| T1b | Simulate | Home > Simulate > `scam-digital-arrest-english-demo` at 4x | Banner, then full-screen pause with reasons, timer, 3 questions, spoken warning, dry-run SMS in Sim log | "OVERLAY BLOCKED" in log: enable accessibility or overlay permission. Silent: media volume, TTS engine installed |
| T1c | Hindi | Settings > Language हिन्दी, replay | Hindi text and Hindi speech (or English speech if no Hindi voice) | Install Hindi voice data in system TTS settings |
| T1d | Normal is invisible | Simulate `normal-pay-shopkeeper`, `normal-friend-long-call-pay-rent` | No overlay at all | Report as a bug with the Debug log: this must never happen |
| T2 | **Day-0 audio** | Call on speaker (caller keeps talking ~80 s), Home > Audio test > Start. Repeat with a WhatsApp call | Table below filled in | See "Audio decision" below |
| T3 | Cellular call detection | Ishan's phone calls the demo phone, answer it, Protection ON | Debug log: `event: CallStarted +91...(unknown)`, state MONITORING. A saved contact calling: `(contact Name)` and NO audio capture | No number: READ_CALL_LOG not granted. Nothing at all: protection toggle off, or battery killer |
| T4 | Pay screen reading | Open GPay/PhonePe to a pay screen for a test contact, stop before PIN | Debug > Last accessibility screen shows correct `stage/amount/payee`; log shows PaymentIntent events | Copy dump, fix `ScreenParser`, add a test (see HANDOVER step 4) |
| T5 | PIN screen | Continue to the UPI PIN prompt (do not type) | Stage becomes PIN_SCREEN, and the pause was ALREADY visible before this | Overlay late: check log timestamps; hints in `payment_apps.json` |
| T6 | Full live demo | Unknown call + coercive speech + open payment app + enter amount and payee | Pause (tier 2/3), spoken warning, guardian SMS, timer, 3 questions; "I understand" only after the timer | Use the Debug log + `docs/DECISIONS.md` to see which rule did or did not fire |
| T7 | Transcript | Vosk models pushed (`docs/ANDROID_SETUP.md`) or option C companion; read the digital-arrest script aloud | Home `ASR: vosk on-device ...`; Debug log `Transcript ...`; L rises; reasons quote the call | Home `none (push Vosk model)`: folder missing or app not restarted. Companion 401 = token mismatch; 0 = not reachable (`adb forward`) |
| T7b | Whisper (optional) | Push sherpa-whisper int8 files (`docs/ANDROID_SETUP.md`), Settings > Audio > Whisper, force-stop if the line stays on Vosk, then the same spoken script | Home `ASR: whisper` then `ASR: whisper (detected: hi)` (or `mr` / `en`); Debug log `asr window 5.0s decoded in X ms` | Line stays Vosk: model folder missing or the chip was not applied. `dropped to tiny` means base was slower than the window |
| T8 | Guardian SMS + HOLD | Real guardian number in Settings; trigger tier 3; guardian replies `HOLD` | SMS arrives with the spec wording, timer extends by 10 min; a HOLD from another number does nothing | SEND_SMS not granted; dual-SIM default SIM |
| T9 | LLM | Debug > Ping model | Provider and latency shown; JSON back | Timeout: see HANDOVER step 7 |
| T10 | WhatsApp call | Unknown WhatsApp video call, then open the payment app | Log `VoIP call detected`, then same as T6 | Heuristic needs tuning in `VoipCallDetector` |
| T11 | Report | After a session: Home > Complaint report | Fields filled, editable narrative, copy buttons, PDF share, Call 1930 first if "money sent" | |
| T12 | Survives | Lock the phone, wait 10 min, repeat T3 | Still works | OEM battery settings, autostart, lock app in recents |
| T14 | Live view | Home > Live view while running Simulate `scam-digital-arrest-english-demo` | Big gauge climbs with 35/60/80 marks, transcript lines appear with red highlights, rule chips with points, "bank-side signal" JSON, no personal data in it | Wrong engine shown: it follows whichever saw the latest activity |
| T15 | Pre-flight | Home > Pre-flight check, tap all three live tests | Test pause screen appears and is spoken; model ping shows provider and ms; all ✅ except items you chose to skip | Fix each ❌ line using its hint |
| T16 | Model latency on THIS phone | Pre-flight > Ping the language model, 3 times (first is cold) | Warm ping at or under the budget (8 s). If not, expect the keyword analyst to carry the language layer | Smaller model, or raise the timeout in Settings and accept a later (but still pre-PIN) language score |
| T13 | Pause switch | Notification > Pause protection | Everything stops; no mic capture | |

## Day-0 audio results (fill in)

| Phone | Call type | VOICE_RECOGNITION | MIC | CAMCORDER | UNPROCESSED | Decision |
|---|---|---|---|---|---|---|
| (model, Android version) | cellular | | | | | |
| | WhatsApp | | | | | |

**Audio decision:** first source that says HAS SIGNAL for BOTH call types wins (set it in Settings > Audio). If only cellular or only WhatsApp works, note it.
All SILENT: use option C (companion on the laptop), and keep option D (no audio) as the floor: the pause still fires on call + payment-app + new payee + amount.
