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
| Last updated | 2026-11-04, Guardian floating widget (JVM only) |
| `main` is | green on this tree: `:core:test` 173 passed; scorecard from that run is 34/34 scams paused, 0/27 normal paused (4 nudges), 61/61 scenarios; **live gemma3:4b was not re-run**; `:app:testDebugUnitTest` 40 passed; APK builds; lint 0 errors, 23 warnings |
| Last updated | 2026-11-04, Termux Ollama auto-start (not run on a phone) |
| `main` is | Latest local run on this branch: 173 core tests (live-llm excluded); offline evals 34/34 scams paused and 0/27 normal paused (4 nudges); live gemma3:4b was not re-run; security gates pass; APK builds; lint 0 errors, 38 warnings |
| Held-out honesty | 20 unseen scenarios run BEFORE tuning: rule layer alone caught 6/12 scams. Those 20 are now used up for tuning; **we still need a fresh held-out set from someone outside the team** |
| Model latency (laptop, gemma3:4b, terse prompt) | median 5.4 s, p90 6.9 s. The phone will be slower; on the phone expect timeouts and rely on the keyword floor. Measure with Pre-flight |
| App has been run on a real phone? | **Partly.** OnePlus 7 Pro (Android 12): Simulate, Vosk, and Whisper-base (this session, speaker playback into the mic, not an in-call capture). In-call mic (T2), GPay amount/payee, guardian SMS and Hindi TTS are still unverified. |
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
| **Guardian floating widget** (collapsed shield, live card, scam alerts, history) | 🟡 | `app/.../ui/GuardianWidget.kt`, `WidgetMapper.kt`, `WidgetController.kt` | Settings “Show floating widget” defaults ON. Mapping rules have JVM tests. Not drawn on a device. Does not change tiers; tier 2/3 with a payment intent still uses the full-screen pause |
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
| **Speech-to-text** | 🟡 | `audio/VoskAsr.kt`, `audio/SherpaWhisperAsr.kt` | Vosk is the default. Settings > Audio > Whisper uses sherpa-onnx 2.0.1, int8 base, 4 threads, `language=""`. Missing Whisper files fall back to Vosk, then Noop. On the OnePlus 7 Pro, base decoded 5.0 s windows in about 0.4–1.3 s (typical ~1.1 s) and Home showed `ASR: whisper (detected: hi)`. Tiny was on disk and was not needed. |
| Ollama on the phone (Termux) | 🟡 | `engine/TermuxOllama.kt`, `docs/ANDROID_SETUP.md` | Auto-start via `RUN_COMMAND` is built; the 60 s decision is unit-tested. `allow-external-apps=true` is a one-time Termux step the app cannot do. Not verified on a device. Laptop Ollama over `adb reverse` still works if the phone's `ollama` is not holding port 11434. |
| Synthetic-voice detector (ONNX) | ⬜ | | Stretch. Weight is 0 when absent; spec says weak signal |
| Onboarding polish, safe-word capture | ⬜ | | Only a flag exists for the safe word |
| Demo runbook, pitch outline + judge Q&A | ✅ | `docs/DEMO_RUNBOOK.md`, `docs/PITCH.md` | Pitch slides themselves and the backup video: ⬜ |

## What is verified vs not

**Verified (laptop):** all `core` behaviour. This session’s `:core:test` wrote `core/build/reports/kavach-eval/scorecard.md`: 34/34 scams paused, 0/27 normal paused, 61/61 scenarios. Fuzzed invariants and repo security gates (secrets, audio files, cleartext HTTP, logging, manifest permissions/exported/backup) passed in that same run. APK assembles with Vosk + sherpa-onnx native libs. Android lint: 0 errors, 23 warnings. The companion script was not re-run this session.
Vosk model-folder resolution, Whisper file layout, and the floating-widget mapping rules (`WidgetMapperTest`, 17 tests) have JVM unit tests. Settings JSON without `showFloatingWidget` still decodes with the switch ON.

**Verified on the OnePlus 7 Pro (speaker into the mic, injected unknown call, not an in-call capture):** Whisper-base int8, 4 threads, decoded 5.0 s windows in 381–1327 ms. Home showed `ASR: whisper (detected: hi)`. Tiny was not needed.

**NOT verified:** overlay drawing over a UPI app, the floating widget on a real screen (bubble, drag, expand, coexistence with the pause screen), accessibility node text of GPay/PhonePe, PHONE_STATE number delivery, mic during a real call (T2), TTS Hindi voice, SMS sending, foreground-service survival, Android 14 microphone-FGS rules.
**Verified (laptop):** all `core` behaviour, this run's scorecard 34/34 scams paused and 0/27 normal paused (4 nudges), 61/61 scenarios, fuzzed invariants, repo security gates (secrets, audio files, cleartext HTTP, logging, manifest
permissions/exported/backup), APK assembles with Vosk + sherpa-onnx native libs, Android lint passes (0 errors, 38 warnings), companion script talks to a mock server.
Vosk model-folder resolution, Whisper file layout, and the Ollama auto-start rate-limit have JVM unit tests. The Termux intent itself was not sent.

**Verified on the OnePlus 7 Pro (speaker into the mic, injected unknown call, not an in-call capture):** Whisper-base int8, 4 threads, decoded 5.0 s windows in 381–1327 ms. Home showed `ASR: whisper (detected: hi)`. Tiny was not needed.

**NOT verified:** overlay drawing over a UPI app, accessibility node text of GPay/PhonePe, PHONE_STATE number delivery, mic during a real call (T2), TTS Hindi voice, SMS sending, foreground-service survival, Android 14 microphone-FGS rules. Termux `RUN_COMMAND` was not sent on a device: the rate-limit is JVM-tested only.

## Next actions

### Om (on the spare phone). In this order

1. **Install and set up** (30 min). `./gradlew :app:installDebug`, then follow the in-app Setup screen. On Android 13+ you must first do
   App info > ⋮ > *Allow restricted settings* or the Accessibility/Notification-access toggles are greyed out. Done when Home shows all ✅ except, until step 7, **Local AI auto-start (Termux)**. Keyword-only testing can leave that row red.
2. **Run Simulate** (10 min). Home > Simulate > play `scam-digital-arrest-english-demo` at 4x. Done when the amber/red pause screen appears with
   reasons, timer and questions, speech is audible, and the Sim log shows a dry-run guardian SMS. This proves overlay + TTS + engine on the device.
   With Protection ON and “Show floating widget” ON (the default), the shield bubble should sit over other apps, expand on an unknown call or a scam-looking notification, and shrink to a bubble while the full-screen pause is up. The pause must still appear before the PIN. Done when that is seen; the widget has no device run yet.
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
7. **Ollama on the phone** (45 min, optional). One-time, inside Termux: `allow-external-apps=true` in `~/.termux/termux.properties`, then `adb shell am force-stop com.termux` and open Termux once. Setup > **Local AI auto-start (Termux)** > Allow Termux commands. Done when Protection ON writes `Asked Termux to start Ollama` in the Debug log without you opening Termux again, and Debug > Ping returns. If Ping is over 8 s, rely on the keyword analyst. `adb reverse tcp:11434 tcp:11434` still reaches the laptop, but stop the phone's `ollama` first (`pkill ollama`) or it keeps the port. Termux:Boot (`docs/ANDROID_SETUP.md`) is the alternative that starts Ollama at device boot.
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
| Whisper-base on this OnePlus 7 Pro was faster than the 5 s window, so the tiny fallback did not run. Hinglish still comes out mixed (good English lines, repeated Devanagari, one Urdu token). Auto-detect is unrestricted (`language=""`). | Keep base. If a slower phone logs `dropped to tiny`, that is intended. A later build can pass `language_candidates=hi,mr,en` so auto-detect cannot wander. |
| Accessibility can't read amount/payee in a UPI app (FLAG_SECURE, custom views) | "Payment app opened during call" alone is enough for a nudge/pause; pick the app that exposes views best for the demo; tune `ScreenParser` |
| Sideloaded APK: accessibility/notification toggles greyed out (Android 13+) | App info > ⋮ > Allow restricted settings |
| Android 14 refuses the microphone foreground type if the service starts in the background | Service is started from the UI toggle; falls back to running without the mic type (logged) |
| Local LLM too slow on the phone | 8 s timeout, keyword analyst keeps working, `gemma3:1b`, cloud fallback (off by default) |
| Termux ignores the start until `allow-external-apps=true` and Termux is force-stopped once | Setup row **Local AI auto-start (Termux)**. A denied permission or a missing Termux is one Debug line. The keyword analyst still scores. |
| VoIP call detection is text-heuristic | Tune `VoipCallDetector`; the scammer-on-WhatsApp demo variant may need a manual `Debug > Unknown call` |
| OEM kills background services (Xiaomi, OnePlus, Samsung) | Battery exemption, autostart permission, lock the app in recents |
| Floating widget window is untested on a device. A mis-sized window would eat taps outside the card. | The window is a fixed 72 dp bubble or a 308×440 dp card (capped to 62% of the screen), with `FLAG_NOT_FOCUSABLE` and `FLAG_NOT_TOUCH_MODAL`, and never `FLAG_NOT_TOUCHABLE` or `MATCH_PARENT`. While the pause overlay is showing at tier 2+, the mapper forces the bubble. |
| Widget hooks sit in `Screens.kt` (one settings row) and `OverlayController.kt` (accessibility-context callback). A UI restyle of those files will conflict there. | Keep the settings row and the callback; do not fold the widget into `OverlayUi.kt`. |
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

- **2026-11-04 (Termux Ollama auto-start):** Protection ON sends Termux `RUN_COMMAND` (`com.termux.app.RunCommandService`) to run the fixed loopback `ollama serve` command if `pgrep` finds no ollama. The same ask runs before a model call only after a connect failure, at most once per 60 s. Setup row **Local AI auto-start (Termux)** shows installed/permission state and a button that requests the permission or opens Termux. `allow-external-apps=true` is documented as a one-time Termux step; the app cannot write that file. `RepoSecurityTest` now allows only this one non-Android permission, `com.termux.permission.RUN_COMMAND`. `:core:test` 173 passed, scorecard 34/34 scams paused, 0/27 normal paused (4 nudges). `:app:testDebugUnitTest` 36 passed, including the new rate-limit tests. `:app:assembleDebug` and `:app:lintDebug` passed (0 errors, 38 warnings). Nothing ran on a phone. Next: the one-time Termux property, then Protection ON and a Debug ping.

- **2026-11-03 night (UI overhaul):** Visual pass on the Compose screens only. Home is a protection hero, C/L/R meter and tier chip (Safe / Watching / Caution / Danger), on-device status chips, and a compact readiness list. Pause and nudge keep the same actions, timer, questions and labels, with larger type, 56dp targets, a countdown ring and darker amber so white text clears WCAG AA. Light and dark themes, edge-to-edge. `SimPlayer` and `core/` untouched. `:core:test`, `:app:testDebugUnitTest` (22), `:app:assembleDebug` and `:app:lintDebug` passed (0 errors, 38 warnings). Not installed on a device; Compose previews were not rendered.
- **2026-11-04 (Kavach Pay):** Mock UPI screen inside the app (`ui/KavachPay.kt`, `ui/KavachPayScreen.kt`), Home entry "Kavach Pay (demo payment app)". Pay emits live `PaymentIntent` OPENED then AMOUNT_ENTERED (payee rides on that stage; there is no `PAYEE_ENTERED`). Tier 2+ uses the existing pause overlay before a mock PIN that keeps only a length. Role-play normal / coerced injects into `c.live`. Coerced (unknown call + dictated `verify.pay@oksbi` + ₹50,000) reaches a pause on an empty baseline through `Orchestrator` and `KavachRuntime` before any PIN event. `:core:test`, `:app:testDebugUnitTest` (27, including 5 new), `:app:assembleDebug`, `:app:lintDebug` passed. Not installed on a phone. A tier 3 pause waits 120s and can text saved guardians. Completing a simulated payment records it on the baseline.
- **2026-11-04 (Guardian widget):** Floating shield while Protection is ON. Collapsed bubble in normal life; expands for an unknown live call, a scam-looking notification (classifier confidence ≥ 0.5), or a tier rise. Live card shows caller, engine tier and C/L/R, last three transcript lines with evidence quotes marked, planner reasons, Call 1930 (dialer), Call family, Dismiss, Collapse, and a recent-alerts list (8). The widget does not compute tiers. Tier 2/3 with the pause overlay showing forces the bubble so the full-screen pause stays usable. Settings “Show floating widget” defaults ON; old settings JSON without the field stays ON. `:core:test` 173 passed (scorecard 34/34 scams paused, 0/27 normal paused, 61/61). `:app:testDebugUnitTest` 40 passed, including `WidgetMapperTest` (17). `:app:assembleDebug` and `:app:lintDebug` passed (0 errors, 23 warnings). Nothing ran on a device, and this was not committed. Next: Om’s phone step 2, including the bubble/expand/pause check above.

- **2026-11-03 night (sherpa-onnx Whisper):** Second `AsrEngine` beside Vosk. Settings > Audio chooses Vosk (default) or Whisper. Whisper is sherpa-onnx 2.0.1 (AAR in `app/libs`, 47 MB, not gitignored), int8 base, 4 threads, `language=""`. Models live in `getExternalFilesDir(null)/sherpa-whisper/` and are not in git (`scripts/fetch-sherpa-onnx.sh`). Missing files fall back to Vosk, then Noop. `:core:test` 171 passed, scorecard 31/31 scams paused and 0/25 normal paused. `:app:testDebugUnitTest` passed (22 tests, including Whisper path tests). `:app:assembleDebug` and `:app:lintDebug` passed (lint: 0 errors, 38 warnings). On the OnePlus 7 Pro, Home showed `ASR: whisper (detected: hi)` after `el_hinglish.wav` was played from the laptop speaker into an injected unknown-call capture. Debug log: `asr window 5.0s decoded in` 381–1327 ms (typical about 1.1 s). Tiny was pushed and not used. One clear line was `I am CBI officer Sharma, I am speaking with you.` and L reached 65 (tier 1). Not an in-call mic test. Settings on that phone was left on Whisper, protection ON, session POST_INCIDENT.

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

- **2026-11-03 (Om + Claude):** First run on devices. OnePlus 7 Pro (Android 12) + Android 14 emulator: Simulate pause screen works, accessibility sees GPay open, Vosk on-device ASR (en-in+hi) heard an ElevenLabs voice and scored L~90 -> nudge, laptop Whisper companion handled Hinglish (L=84), gemma3:4b reachable via `adb reverse` (cold start 38 s, warm ~5 s). Added: Call 1930 button on pause, Vosk ASR, TRAI-series caller rule, call-screening caller ID, sim-clock fix (pause timer froze after Simulate ended). Branch `feat/om-helpline-vosk-asr`, not pushed. **Still unverified:** real call mic/number, GPay amount+payee parse (no SIM/bank on this phone), guardian SMS, Hindi TTS. SIM-tester package: D:/Kavach-dist (APK, Vosk models, sample voices, TESTER_GUIDE.md).

- **2026-11-04 ~02:30 (Om + Claude) CHECKPOINT:** Branch `feat/om-helpline-vosk-asr` (pushed) has: Guardian Widget (floating bubble, auto-expands on unknown call), Kavach Pay (FIN-11 mock payment app, pause before PIN), VoIP call detection via audio mode (Instagram verified), Whisper hi/en/mr language policy, gain normalisation + 8 s overlapping windows, ASR-noise-tolerant keyword analyst (37/37 scams paused, 0/32 normal), Termux auto-start of local Ollama (gemma3:1b), UI overhaul, plain-language Situation/Saying/Scam-risk labels. Live Instagram call test: detection + widget + warning WORK; transcript from speaker->mic is too garbled (L only ~13), so the scam script was not flagged. In progress: Whisper SMALL support with step-down (agent branch, not merged) + a transcript diagnostic switch (default OFF). Model files for small: download https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-whisper-small.tar.bz2, push int8 files as small-*.int8.onnx / small-tokens.txt to /sdcard/Android/data/com.kavach.app/files/sherpa-whisper/. Phone is OnePlus 7 Pro over wireless adb (172.20.10.2:5555); every reinstall turns Accessibility OFF (user re-enables). Do not force-stop the app before reading the Debug log (in-memory). Package for the SIM tester: D:\Kavach-dist + docs/TUSHAR_SETUP.md.
