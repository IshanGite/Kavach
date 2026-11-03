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
| Last updated | 2026-11-03 (night), personal-mobile institution rule |
| `main` is | green: 171 core tests; offline evals 31/31 scams paused and 0/25 normal paused (3 nudges); **live gemma3:4b was 27/27 and 0/23 and was not re-run**; security gates pass; APK builds; lint clean |
| Held-out honesty | 20 unseen scenarios run BEFORE tuning: rule layer alone caught 6/12 scams. Those 20 are now used up for tuning; **we still need a fresh held-out set from someone outside the team** |
| Model latency (laptop, gemma3:4b, terse prompt) | median 5.4 s, p90 6.9 s. The phone will be slower; on the phone expect timeouts and rely on the keyword floor. Measure with Pre-flight |
| App has been run on a real phone? | **NO. Nothing in `app/` has ever executed on a device.** It compiles, lints and builds an APK. Treat every on-device behaviour as unverified until ticked in `docs/PHONE_TEST_PLAN.md`. |
| Biggest unknown | Whether the demo phone gives us microphone audio during a call (Day-0 test, T2) |

## Status by component

Legend: ✅ built and tested on the laptop · 🟡 built, compiles, **untested on a device** · ⬜ not started

| Area | State | Where | Notes |
|---|---|---|---|
| Risk engine, tiers, guards, overrides | ✅ | `core/.../risk` | Spec formula. Deviations in DECISIONS.md |
| Orchestrator, latching, guardian dedupe, HOLD auth | ✅ | `core/.../orchestrator` | |
| LLM routing (Ollama, cloud, timeout), keyword floor | ✅ | `core/.../llm`, `analysis` | Live `gemma3:4b` check passed on Mac (5-11 s) |
| Scenario simulator + 54 scenarios + scorecard | ✅ | `core/.../sim`, `resources/scenarios` | Drives CI evals and the in-app Simulate screen |
| Call-state tracker, screen parser, app config | ✅ | `core/.../sense`, `config` | Unit-tested logic; the Android glue around it is 🟡 |
| Overlay UI (nudge banner, pause screen, timer, 3 questions) | 🟡 | `app/.../ui/OverlayUi.kt`, `OverlayController.kt` | Hindi + English. Accessibility-overlay window with app-overlay fallback |
| **Live view** (gauge, highlighted transcript, rule chips, bank-signal JSON) for the judges' screen | 🟡 | `app/.../ui/LiveScreen.kt` | Mirror with scrcpy |
| **Pre-flight check** (real overlay, speech and model tests) | 🟡 | `app/.../ui/PreflightScreen.kt` | Run at T-15 min |
| Repeated-new-payee rule, per-payee baseline, bank-side signal, LLM grounding, terse prompt | ✅ | `core/.../risk`, `analysis`, `llm` | Decisions 21-31 |
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
| **Speech-to-text (Vosk)** | 🟡 | `audio/VoskAsr.kt`, `Audio.kt` | On-device when `vosk-model-small-en-in-0.4` / `vosk-model-small-hi-0.22` are in external files. Otherwise `NoopAsr` and Home shows `ASR: none (push Vosk model)`. Not run on a device. |
| Ollama on the phone (Termux) | ⬜ | `docs/ANDROID_SETUP.md` | Not set up. Laptop Ollama over `adb reverse` works for dev |
| Synthetic-voice detector (ONNX) | ⬜ | | Stretch. Weight is 0 when absent; spec says weak signal |
| Onboarding polish, safe-word capture | ⬜ | | Only a flag exists for the safe word |
| Demo runbook, pitch outline + judge Q&A | ✅ | `docs/DEMO_RUNBOOK.md`, `docs/PITCH.md` | Pitch slides themselves and the backup video: ⬜ |

## What is verified vs not

**Verified (laptop):** all `core` behaviour, 56 scenarios (scorecard 31/31 scams paused, 0/25 normal paused), fuzzed invariants, repo security gates (secrets, audio files, cleartext HTTP, logging, manifest
permissions/exported/backup), APK assembles with Vosk + JNA native libs, Android lint passes, companion script talks to a mock server.
Vosk model-folder resolution and result JSON parsing have JVM unit tests. Loading a model and transcribing audio have not run on a phone.

**NOT verified:** that any Android API behaves as assumed on a real device: overlay drawing over a UPI app, accessibility node text of GPay/PhonePe,
PHONE_STATE number delivery, mic during call, Vosk model load and transcription, TTS Hindi voice, SMS sending, foreground-service survival, Android 14 microphone-FGS rules.

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
6. **Speech-to-text on the phone.** Push the small Vosk models (`docs/ANDROID_SETUP.md`), force-stop, reopen. Done when Home shows `ASR: vosk on-device ...`,
   a spoken digital-arrest script produces `Transcript ...` lines in the Debug log, and L rises. If Day-0 is SILENT, use option C instead
   (`adb forward tcp:8765 tcp:8765`, Settings token, `python3 companion/listener.py --token <t>`). Set Settings > Language to the script's language before the call.
7. **Ollama on the phone** (45 min, optional). Termux from F-Droid, `pkg install ollama`, `ollama serve`, pull a small model, exempt Termux from battery optimisation.
   Measure latency in Debug > Ping. Expect > 8 s on a phone: then rely on the keyword analyst, or try `gemma3:1b`. Meanwhile `adb reverse tcp:11434 tcp:11434` lets the phone use the laptop's Ollama.
8. **Guardian SMS end to end.** Put a real second number in Settings > Guardians, trigger tier 3 via Debug, confirm the SMS arrives and that replying `HOLD` extends the timer.
9. **Pre-flight + Live view.** Home > Pre-flight check: all ✅, you have seen and heard the test pause, the model ping is under budget (T15, T16). Home > Live view while running Simulate (T14).
10. **Rehearse** the 4-minute demo from the spec 3 times using `docs/DEMO_RUNBOOK.md` (fill in its rehearsal log); tune `risk_config.json` if needed, and re-run `./gradlew :core:eval` after every weight change.

### Ishan / Claude

- Review Om's PRs; keep `main` green. Own `core/`, evals, docs, pitch.
- **Write a FRESH held-out scenario set** (or get someone outside the team to): the 20 `heldout-*` scenarios are burned for tuning. Report its first-run result before tuning, as before.
- Decide the demo language model after measuring the phone (T16): keep 4B terse, or cloud fallback on.
- Pitch deck, demo script, recorded backup video.

## Known risks and gotchas

| Risk | Mitigation / where to look |
|---|---|
| Mic returns silence during calls (Android 10+) | Day-0 test; option C companion; option D still reaches tier 2 (`scam-option-d-no-audio`) |
| Vosk runs one model per 5 s window (Settings language). The other model is used only when the first returns no words, so a wrong-language hallucination is not retried. Two full decodes per window were not wired: small-model confidence is too flat to choose, and it would be about 2x CPU. Windows overlap by 1 s, so the session transcript repeats words. | Match Settings language to the demo script. A later design can feed the 100 ms mic reads to a long-lived recogniser instead of re-decoding the 5 s ring. |
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

- Audio option A/B/C/D: Vosk (option A) is in the APK, but T2 on Om's phone still decides whether the mic works during a call. If it is silent, use option C.
- Cloud fallback on or off for the demo? Default is off. Judges like privacy; decide once phone LLM latency is known.
- Which UPI app to demo (GPay vs PhonePe): whichever `ScreenParser` reads best.

## Ownership (proposed; edit freely)

| Area | Owner |
|---|---|
| `core/`, `docs/`, evals, pitch | Ishan |
| `app/sense`, `app/audio`, on-device tuning, Vosk | Om |
| `app/ui` | whoever has the phone in hand; keep PRs small |

## Session log (newest first, append only)

- **2026-11-03 night (personal-mobile institution rule):** Unknown caller + bank/police/CBI/other-authority finding + a normal 10-digit mobile (not 140/160) is +10 (`INSTITUTION_FROM_PERSONAL_MOBILE`). Family and courier labels and saved contacts do not score. `:core:test` passed (171 tests). Scorecard 31/31 scams paused, 0/25 normal paused; the new 160-series twin is a nudge (tier 1), the personal-mobile twin pauses. TRAI series reading is unverified against the directive (decision 33). Live model suite not re-run. Nothing ran on a device.

- **2026-11-03 night (Vosk ASR):** Wired `com.alphacephei:vosk-android:0.3.75` behind `AsrEngine`. Models load off the main thread from external files; none present → `NoopAsr` (`ASR: none (push Vosk model)`). JNA comes in as the 5.18.1 AAR already declared by that POM (duplicate-class check passed; `jna:5.13.0` was not added). `docs/REPOS_TO_REUSE.md` was not in the tree. `:core:test` passed (167 tests, scorecard 30/30 scams paused, 0/24 normal paused), `:app:test` passed (Vosk JSON/dir tests included), `:app:assembleDebug` and `:app:lintDebug` passed. Nothing ran on a device. Next: push the two small models and do phone step 6.

- **2026-11-03 evening (Ishan + Claude):** Refinement round. Built a live-model eval (`:core:llmEval`) and a held-out set; first held-out run exposed that the rule layer caught
  only 6/12 unseen scams, so the lexicon now covers vocabulary classes. The live model exposed two real bugs: a business NAME matching as a dictated payee (clinic got tier 3), and
  4B terse mode answering risk 75 for benign money talk (fixed by tactic grounding). Added repeated-new-payee rule, per-payee baseline, `BankSignal`, terse prompt (latency median
  8.3 s -> 5.4 s), Live view, Pre-flight, DEMO_RUNBOOK, PITCH. Live suite with real gemma3:4b: 27/27 scams, 0/23 normal pauses, 1 accepted nudge. Still nothing run on a device.
  Next: Om, "Next actions > Om, step 1", then the Pre-flight and Live view tests.

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
