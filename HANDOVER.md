# HANDOVER: read this first, update it last

This is the living status file for Kavach. **Any Claude Code session (or human) starting work reads this first, and every session ends by
updating it** (run `/handover`, see `.claude/commands/handover.md`). Keep it short, true and current. If it is stale, the next person loses an hour.

Spec: the 21-page doc "Kavach: Interrupting Coerced Payments Before They Complete". Architecture rules: `CLAUDE.md`. Deviations from the spec: `docs/DECISIONS.md`.
Phone verification steps: `docs/PHONE_TEST_PLAN.md`.

**Deadline:** demo on 2026-11-04. **Team:** Ishan (core, AI, app skeleton) and Om (demo phone, on-device bring-up).

---

## Snapshot

| | |
|---|---|
| Last updated | 2026-11-03, Ishan + Claude |
| `main` is | green: 138 core tests, 17/17 scams paused, 0/14 normal paused, security gates passing, APK builds, lint clean |
| App has been run on a real phone? | **NO. Nothing in `app/` has ever executed on a device.** It compiles, lints and builds an APK. Treat every on-device behaviour as unverified until ticked in `docs/PHONE_TEST_PLAN.md`. |
| Biggest unknown | Whether the demo phone gives us microphone audio during a call (Day-0 test, T2) |

## Status by component

Legend: ✅ built and tested on the laptop · 🟡 built, compiles, **untested on a device** · ⬜ not started

| Area | State | Where | Notes |
|---|---|---|---|
| Risk engine, tiers, guards, overrides | ✅ | `core/.../risk` | Spec formula. Deviations in DECISIONS.md |
| Orchestrator, latching, guardian dedupe, HOLD auth | ✅ | `core/.../orchestrator` | |
| LLM routing (Ollama, cloud, timeout), keyword floor | ✅ | `core/.../llm`, `analysis` | Live `gemma3:4b` check passed on Mac (5-11 s) |
| Scenario simulator + 32 scenarios + scorecard | ✅ | `core/.../sim`, `resources/scenarios` | Drives CI evals and the in-app Simulate screen |
| Call-state tracker, screen parser, app config | ✅ | `core/.../sense`, `config` | Unit-tested logic; the Android glue around it is 🟡 |
| Overlay UI (nudge banner, pause screen, timer, 3 questions) | 🟡 | `app/.../ui/OverlayUi.kt`, `OverlayController.kt` | Hindi + English. Accessibility-overlay window with app-overlay fallback |
| Home / Setup / Settings / Debug screens | 🟡 | `app/.../ui` | Debug screen injects events, pings the LLM, copies accessibility dumps |
| **Simulate screen (backup demo)** | 🟡 | `ui/Screens.kt` `SimPlayer` | Plays any scenario through the real engine + overlay + TTS; SMS is a dry run. **First thing to try on the phone** |
| Cellular call monitor | 🟡 | `sense/Receivers.kt` | PHONE_STATE receiver + `CallStateTracker`. Needs READ_CALL_LOG for the number |
| SMS receiver + guardian HOLD | 🟡 | `sense/Receivers.kt` | |
| Notification listener | 🟡 | `sense/KavachNotificationListener.kt` | WhatsApp/Telegram/UPI notifications, remote-access apps |
| Accessibility service (payment screen, PIN, remote access) | 🟡 | `sense/KavachAccessibilityService.kt` | **Highest-value sensor and the one most likely to need per-app tuning** |
| VoIP (WhatsApp) call detection | 🟡 | `sense/VoipCallDetector.kt` | Heuristic over call-screen text; fragile, needs tuning |
| Audio capture + Day-0 audio test | 🟡 | `audio/Audio.kt`, `ui/Screens.kt` | Test screen records 20 s per source and prints a SILENT/SIGNAL verdict |
| Companion listener (option C): phone side + laptop script | 🟡 / ✅ | `audio/CompanionServer.kt`, `companion/listener.py` | Script tested against a mock; phone side untested |
| Guardian SMS, TTS (EN/HI), notifications, PDF report | 🟡 | `engine/Platform.kt`, `util/Files.kt` | |
| **Speech-to-text (whisper.cpp)** | ⬜ | `audio/Audio.kt` `AsrEngine` | Interface + `NoopAsr` only. Without it the app runs as option D. **Om's main build task** (or use the companion) |
| Ollama on the phone (Termux) | ⬜ | `docs/ANDROID_SETUP.md` | Not set up. Laptop Ollama over `adb reverse` works for dev |
| Synthetic-voice detector (ONNX) | ⬜ | | Stretch. Weight is 0 when absent; spec says weak signal |
| Onboarding polish, safe-word capture | ⬜ | | Only a flag exists for the safe word |
| Pitch deck, backup video | ⬜ | | |

## What is verified vs not

**Verified (laptop):** all `core` behaviour, 32 scenarios, fuzzed invariants, repo security gates (secrets, audio files, cleartext HTTP, logging, manifest
permissions/exported/backup), APK assembles, Android lint passes, companion script talks to a mock server.

**NOT verified:** that any Android API behaves as assumed on a real device: overlay drawing over a UPI app, accessibility node text of GPay/PhonePe,
PHONE_STATE number delivery, mic during call, TTS Hindi voice, SMS sending, foreground-service survival, Android 14 microphone-FGS rules.

## Next actions

### Om (on the spare phone). In this order

1. **Install and set up** (30 min). `./gradlew :app:installDebug`, then follow the in-app Setup screen. On Android 13+ you must first do
   App info > ⋮ > *Allow restricted settings* or the Accessibility/Notification-access toggles are greyed out. Done when Home shows all ✅.
2. **Run Simulate** (10 min). Home > Simulate > play `scam-digital-arrest-english-demo` at 4x. Done when the amber/red pause screen appears with
   reasons, timer and questions, speech is audible, and the Sim log shows a dry-run guardian SMS. This proves overlay + TTS + engine on the device.
3. **Day-0 audio test** (30 min, **do this early**, it decides the architecture). Home > Audio test with a normal call on speaker, then a WhatsApp call.
   Record the verdicts in the table at the bottom of `docs/PHONE_TEST_PLAN.md` and set the winning source in Settings. If every source is SILENT, use option C (laptop companion) and tell Ishan.
4. **Real payment screen tuning** (1-2 h). With the accessibility service on, open GPay / PhonePe to the pay screen for a *test* contact (do not complete a payment).
   Debug > "Last accessibility screen" > Copy dump. Check `stage/amount/payee` are right. If not, paste the dump into `core/src/test/.../SenseTest.kt`
   as a new `ScreenParserTest` case, fix `ScreenParser.kt`, run `./gradlew :core:test`. Verify package names in `payment_apps.json`
   (`adb shell dumpsys window | grep mCurrentFocus`). Done when the amount and payee are read on the app you will demo, before the PIN screen.
5. **Live call test** (30 min). Ishan's phone calls Om's phone (unknown number). Debug log must show CallStarted, then a payment intent when GPay opens,
   then the overlay. Check the number is captured (needs READ_CALL_LOG) and that a saved contact is NOT treated as unknown.
6. **Speech-to-text.** Either (a) option C: `pip install -r companion/requirements.txt`, `adb forward tcp:8765 tcp:8765`, set a token in Settings and enable the companion,
   run `python3 companion/listener.py --token <t>`; or (b) integrate whisper.cpp via JNI behind `AsrEngine` (`Container.asr`). Done when a spoken digital-arrest script produces
   `Transcript ...` lines in the Debug log and the language score L rises.
7. **Ollama on the phone** (45 min, optional). Termux from F-Droid, `pkg install ollama`, `ollama serve`, pull a small model, exempt Termux from battery optimisation.
   Measure latency in Debug > Ping. Expect > 8 s on a phone: then rely on the keyword analyst, or try `gemma3:1b`. Meanwhile `adb reverse tcp:11434 tcp:11434` lets the phone use the laptop's Ollama.
8. **Guardian SMS end to end.** Put a real second number in Settings > Guardians, trigger tier 3 via Debug, confirm the SMS arrives and that replying `HOLD` extends the timer.
9. **Rehearse** the 4-minute demo from the spec 3 times; tune `risk_config.json` if needed, and re-run `./gradlew :core:eval` after every weight change.

### Ishan / Claude

- Review Om's PRs; keep `main` green. Own `core/`, evals, docs, pitch.
- Add held-out scam/normal scenarios (written without looking at the lexicon) to avoid grading ourselves on our own homework.
- Per-payee amount baseline; investment-scam rule (see Known gaps).
- Pitch deck, demo script, recorded backup video.

## Known risks and gotchas

| Risk | Mitigation / where to look |
|---|---|
| Mic returns silence during calls (Android 10+) | Day-0 test; option C companion; option D still reaches tier 2 (`scam-option-d-no-audio`) |
| Accessibility can't read amount/payee in a UPI app (FLAG_SECURE, custom views) | "Payment app opened during call" alone is enough for a nudge/pause; pick the app that exposes views best for the demo; tune `ScreenParser` |
| Sideloaded APK: accessibility/notification toggles greyed out (Android 13+) | App info > ⋮ > Allow restricted settings |
| Android 14 refuses the microphone foreground type if the service starts in the background | Service is started from the UI toggle; falls back to running without the mic type (logged) |
| Local LLM too slow on the phone | 8 s timeout, keyword analyst keeps working, `gemma3:1b`, cloud fallback (off by default) |
| VoIP call detection is text-heuristic | Tune `VoipCallDetector`; the scammer-on-WhatsApp demo variant may need a manual `Debug > Unknown call` |
| OEM kills background services (Xiaomi, OnePlus, Samsung) | Battery exemption, autostart permission, lock the app in recents |
| Hindi TTS voice missing | Falls back to English; install the Hindi voice in system settings |
| API key is stored in app-private prefs (not encrypted) | Fine for a hackathon phone; do not reuse a real key |
| `Settings > Companion` token is sent over plain HTTP | Use `adb forward` (USB), not Wi-Fi |

## Open decisions

- Audio option A/B/C/D: decided by T2 on Om's phone.
- Cloud fallback on or off for the demo? Default is off. Judges like privacy; decide once phone LLM latency is known.
- Which UPI app to demo (GPay vs PhonePe): whichever `ScreenParser` reads best.

## Ownership (proposed; edit freely)

| Area | Owner |
|---|---|
| `core/`, `docs/`, evals, pitch | Ishan |
| `app/sense`, `app/audio`, on-device tuning, whisper | Om |
| `app/ui` | whoever has the phone in hand; keep PRs small |

## Session log (newest first, append only)

- **2026-11-03 (Ishan + Claude):** Built `core` (engine, orchestrator, LLM routing, keyword analyst, report, simulator, 32 scenarios, evals, security gates, CI).
  Built `app/` end to end: services, sensors, overlay, 7 screens, Day-0 audio test, companion server, PDF report. APK builds, lint clean, 138 core tests green.
  Wrote this handover, PHONE_TEST_PLAN, `/handover` command. **Nothing has run on a device yet.** Seven Dependabot PRs were left open on purpose (pinned toolchain);
  close them or ignore. Next: Om starts at "Next actions > Om, step 1".

## Quick commands

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 17)            # Gradle 8.13 needs JDK 17
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
./gradlew :core:test            # unit + evals + security gates. Before every push
./gradlew :core:eval            # scorecard
./gradlew :app:installDebug     # build and install on the connected phone (adb devices)
./gradlew :app:assembleDebug :app:lintDebug
adb logcat | grep -i kavach     # the app has no logcat output by design; use Debug screen > Copy log
adb reverse tcp:11434 tcp:11434 # phone uses the laptop's Ollama
adb forward tcp:8765 tcp:8765   # laptop companion -> phone, over USB
```
