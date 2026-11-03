# Team decisions before the 2026-11-04 demo

Audit of the working tree on 2026-11-03. Deadline is the demo on 2026-11-04. Owners below match `HANDOVER.md`: Ishan = `core/`, evals, docs, pitch; Om = phone, `app/sense`, `app/audio`.

**Verified** means it is in the files cited. **Assumed** means it is inferred (Android platform behaviour, or a spec this repo does not contain). Tests were not executed. Nothing in `app/` has been run on a device (`HANDOVER.md`).

The 21-page spec is not in the repo. Options named only in the spec (audio “B”) are marked assumed.

---

## 1. Decisions needed

| ID | Decision | Options | Recommendation | Owner | When |
|---|---|---|---|---|---|
| D1 | How the live demo gets caller speech | **A.** On-device mic + whisper.cpp behind `AsrEngine`. **B.** Not defined anywhere in this repo (only in the missing spec). **C.** Laptop `companion/listener.py` POSTs text. **D.** No transcript (`NoopAsr`). | **Do not start whisper.cpp tonight.** Run T2 (`docs/PHONE_TEST_PLAN.md`). If the mic has signal, still use **C or D for words**: `Container.asr` is `NoopAsr`, so a “HAS SIGNAL” source does not produce transcripts (`app/.../audio/Audio.kt`, `app/.../KavachApp.kt`). Prefer **C** if a laptop can sit on speakerphone; keep **D** as the floor. Lock this as soon as T2 is filled in. | Om (T2 + C wiring), Ishan (script) | Tonight, before rehearsal |
| D2 | Cloud LLM during the demo | On (Anthropic, key in Settings) or off (default) | **Off.** Privacy story, and the live language score waits on the cloud call if the toggle is on (`HybridTranscriptAnalyst` awaits `RoutingClient` before it returns keywords: `core/.../analysis/Analysts.kt`, `core/.../llm/LlmClient.kt`). Model id `claude-haiku-5-5` has not been called from this tree. | Ishan | Tonight |
| D3 | Local model during the payment moment | Ollama reachable (phone Termux or `adb reverse`) or unreachable | **Unreachable during the live payment.** Keywords are computed first but are not applied until Ollama returns or hits the 8 s timeout, and that call holds `modelLock` (`Analysts.kt`, `KavachRuntime.kt`, `SettingsState.llmTimeoutMs`). A dead `127.0.0.1:11434` fails in about 3 s (`UrlConnectionTransport` connect timeout) and then the keyword floor applies. Show “Ping model” on Debug as a separate beat, not on the critical path. `gemma3:4b` at 5–11 s is a laptop anecdote in `docs/DECISIONS.md`, not a phone measurement. | Om measures, Ishan agrees | Tomorrow morning, after T9 |
| D4 | What the 4 minutes actually show | Live call, in-app Simulate, or both. Hero file is `scam-digital-arrest-english-demo` | **Both, Simulate first.** That scenario is the only scripted tier-3 path (payee spoken, then typed, pause before PIN, guardian SMS). Live call is the proof the sensors exist. If T3/T4 fail, do not pretend the live path matched the scorecard. | Both | Tonight |
| D5 | Which UPI app is on camera | GPay, PhonePe, or another package in `payment_apps.json` | Whichever T4 reads `stage/amount/payee` in the **same package as the PIN screen**. PhonePe is what the hero scenario names. Do not enter a PIN. Do not pay. | Om | Tomorrow, after T4 |
| D6 | Guardian SMS on stage | Real SMS to a teammate, or skip (pause still shows) | **One real guardian number in Settings, one rehearsal SMS, then decide.** Empty guardians mean tier 3 still pauses and speaks, and no SMS is sent (`Orchestrator.maybeAlertGuardians`). Simulate never uses that number: it dry-runs to `+919800000001` (`ScenarioLoader.defaultProfile`, `KavachApp.newSimEngine`). Reply must start with `HOLD` (`GuardianLink.HOLD`). | Om (phone + SIM), Ishan (second handset) | Tomorrow morning |
| D7 | On-screen language | English or Hindi | **English for the live beat.** Hindi copy exists (`InterventionPlanner.kt` object `T`). Hindi speech depends on a system voice (`Speaker` falls back to `en-IN`). Run T1c only if there is time. | Whoever is on the phone | Tomorrow |
| D8 | Build the known core gaps tonight? | Investment/task rule, per-payee median, ONNX spoof detector, safe-word capture | **No.** One scenario is an explicit non-gate (`scam-investment-telegram-tasks.json` `known_gap`). Nothing in `app/` emits `VoiceSpoofScore`. Safe word is a Settings checkbox only (`SettingsState.safeWordSet`). Say the investment gap in one sentence if asked. | Ishan | Tonight (agree to defer) |
| D9 | Use the scam-number list (+40) in the demo | Add the caller’s real number to `scam_numbers.json`, or leave the fictional list | **Leave it.** List is `9000000001`, `9000000002`, `+85212345678` (`scam_numbers.json`). The hero script uses `+91 99887 76655`, which is not on the list. A blocklist hit hides the context story. | Ishan | Tonight |
| D10 | Companion on venue Wi-Fi | USB `adb forward` only, or listen on the LAN | **USB only, Wi-Fi off on that interface.** `CompanionServer` binds `ServerSocket(port)` on all interfaces, token or not (`CompanionServer.kt`). Token min 8 chars. | Om | Before leaving for the venue |
| D11 | Freeze `risk_config.json` | Tune on the phone vs rebuild | **Freeze.** Weights load from the classpath inside the APK (`RiskConfig.loadDefault`). `CLAUDE.md` says “tunable on stage without rebuilding”; that is not how the app loads them. A change needs `./gradlew :core:eval` and a reinstall. | Ishan | Tonight |
| D12 | `READ_SMS` on the permission screen | Keep, or remove in a tiny PR | **Keep for the demo and do not claim the inbox is read.** The manifest requests it; no code queries the SMS store (only `SMS_RECEIVED` in `Receivers.kt`). `RepoSecurityTest` allow-lists it, so removal is a code change. | Ishan if anyone asks | Can slip |

---

## 2. Inputs needed from teammates

| Need | From | Why it blocks | Evidence |
|---|---|---|---|
| Phone model, Android version, RAM, OEM (Xiaomi / Samsung / other), which UPI apps are installed | Om | Setup, battery killer, and T4 all depend on it. Spec line in `docs/ANDROID_SETUP.md` asks for ≥ 8 GB RAM if a model runs on the phone. | `docs/ANDROID_SETUP.md`, `HANDOVER.md` risks table |
| Day-0 table filled (cellular and WhatsApp × four sources) | Om | D1. Blank table at the bottom of `docs/PHONE_TEST_PLAN.md`. | `docs/PHONE_TEST_PLAN.md` |
| `adb devices` works on the machine that will install the APK. JDK 17 and Android SDK path. | Whoever builds | `docs/ANDROID_SETUP.md` and `HANDOVER.md` commands are macOS (`brew`, `/usr/libexec/java_home`, `/opt/homebrew`). This audit workspace is Windows. **Assumed:** Ishan’s Mac is still the build machine, as that doc says the SDK is already there. | `docs/ANDROID_SETUP.md`, `HANDOVER.md` Quick commands |
| Caller handset that is **not** a saved contact on the demo phone, and not named exactly like a contact (VoIP matches the whole accessibility node to a display name) | Ishan | Unknown-party rules and mic start depend on `isInContacts == false`. A saved contact scores none of the call rules. | `RiskEngine.kt` `unknownCallLive`, `CallStateTracker.kt`, `VoipCallDetector.kt`, `KavachApp.onCallEvents` |
| Test UPI handle the team controls, or an agreement to stop before any real VPA lookup | Both | Hero payee is `sharma.verify@okicici` (`scam-digital-arrest-english-demo.json`). Typing that into a live GPay/PhonePe can resolve a real account. Stop before PIN either way (`docs/PHONE_TEST_PLAN.md`). | scenario JSON, `PHONE_TEST_PLAN.md` |
| Guardian name and number that may receive one real SMS; which SIM sends if the phone is dual-SIM | Om | `SmsManager` default SIM (`Platform.kt`). T8. | `Platform.kt` `SmsSender`, `PHONE_TEST_PLAN.md` T8 |
| Demo persona name (Settings default is `Papa`, and that string is what the SMS says) | Both | `SettingsState.userName`, `GuardianLink.compose` | `Settings.kt`, `GuardianLink.kt` |
| Where the 21-page spec and the spoken 4-minute script are | Ishan | Not in the repo. The JSON “verbatim” script is three short lines over 78 scenario-seconds, not four minutes of stage time. | `HANDOVER.md` line 5, `scam-digital-arrest-english-demo.json` |
| Pitch deck and who speaks which minute | Ishan | No `.pdf` / `.pptx` / video in the tree. `HANDOVER.md` lists pitch and backup video as not started. | `HANDOVER.md` status table |
| `faster-whisper` install on the companion laptop (`pip install -r companion/requirements.txt`) and a ≥8 character token | Om | Option C. `--stdin` needs no mic and is the fallback if the laptop mic fails (`companion/listener.py`). | `companion/listener.py`, `companion/requirements.txt` |
| Confirm Play Protect will allow the sideload, and Android 13+ “Allow restricted settings” is done before accessibility | Om | `docs/PHONE_TEST_PLAN.md` T0–T1, `docs/ANDROID_SETUP.md` | those docs |

---

## 3. Blockers and unverified-on-device risks

Ranked for a failed demo, not for long-term product risk. Every `app/` behaviour is unverified on hardware.

1. **No transcript path is live.** `AsrEngine` is only `NoopAsr`. Without companion or Debug “Send transcript”, language score stays empty and the payee-from-call override cannot fire. **Verified:** `Audio.kt`, `KavachApp.kt`. Tier 3 in the hero scenario needs the spoken UPI (`expect.min_tier` 3 in `scam-digital-arrest-english-demo.json`). Extractors accept “sharma dot verify at okicici” (`Extractors.spokenToWritten`, handle `okicici`). **Assumed:** Whisper will not emit that phrasing reliably; rehearse the exact words and check the Debug log for `Payee detail spoken`.

2. **Cellular “option D” does not pause on an unseeded phone if the screen parse fails.** Points in `risk_config.json`: unknown live call 20, payment-app during call 25, new payee 10, amount > 3× median 10. Pause threshold is 60.
   - App open during an unknown cellular call, nothing else: **45 → nudge, not pause.**
   - That plus a new payee **or** a large amount, not both: **55 → still a nudge.**
   - Both, and history has ≥ 3 payments: **65 → tier 2.**
   - Unknown **VoIP** (extra 15) plus payment app: **60 → tier 2** even with no amount and no payee.
   Live history starts empty (`PrefsBaseline`). Seed is a Settings button (`KavachApp.seedDemoBaseline` → `ScenarioLoader.defaultBaseline`). Median of that seed is 120 (23 payments; 8×60, 5×120, 6×200, 4×1500), so 3× is 360 and ₹50,000 qualifies. **Verified** from `RiskEngine.kt` and `risk_config.json`. `HANDOVER.md` says payment-app-during-call alone is “enough for a nudge/pause”. The pause half is wrong for cellular.

3. **Caller number may never arrive.** Code assumes `READ_CALL_LOG` fills `EXTRA_INCOMING_NUMBER` (`Receivers.kt`, decision 14 in `docs/DECISIONS.md`). **Assumed (platform, not re-tested):** on recent Android the number extra is withheld unless the app is the default dialer. If the number is null, `CallStateTracker` marks the call unknown and mic capture starts (`CallStateTracker.kt` hidden-number branch, `KavachApp.onCallEvents`). A saved contact would then be treated as unknown. T3 is the check. Fallback already in the app: Debug “Unknown call” injects `+91 99887 76655` (`Screens.kt`).

4. **Accessibility text of GPay/PhonePe is untested**, including `FLAG_SECURE` and custom views (`HANDOVER.md`). `ScreenParser` only has laptop fixtures (`SenseTest.kt`). Service flags omit `flagRetrieveInteractiveWindows` (`app/src/main/res/xml/accessibility_service_config.xml`). If the PIN UI is a **different package**, `onLeftPaymentApp` emits `PaymentDismissed` after 2 s and the overlay comes down (`KavachAccessibilityService.kt`). T4/T5 decide the app (D5).

5. **WhatsApp call detection is a text heuristic** (`VoipCallDetector.kt`): needs an “end call” phrase and a mute/speaker/camera word. `com.whatsapp.w4b` is watched for notifications but is **not** in `voip_call_apps` (`payment_apps.json`). Notification listener is what ends the VoIP session; without it the call is assumed live for 2 minutes. T10. Fallback: Debug “Unknown call”.

6. **Overlay over the UPI app.** Preferred type is `TYPE_ACCESSIBILITY_OVERLAY` (`OverlayController.kt`, decision 12). Needs the accessibility service actually connected, or “display over other apps”. Android 13+ restricted settings can grey out the toggle (`docs/ANDROID_SETUP.md`). T1b is the first phone proof. Simulate and the live engine share one overlay; a live call during Simulate will fight it (`KavachApp`, `OverlayController`).

7. **Mic foreground service.** Android 14 can refuse `FOREGROUND_SERVICE_TYPE_MICROPHONE` if the service starts in the background; the code retries without the mic type (`KavachService`). Start protection from the Home switch while the activity is open. **Verified:** no `BOOT_COMPLETED` receiver, so a reboot does not start protection until the user opens the app. The comment in `KavachApp.onCreate` says “or reboot”.

8. **OEM kills the process** (Xiaomi, Samsung, …). Battery exemption is a Setup row (`Health.kt`). T12. Unverified.

9. **Hindi TTS voice missing** → English speech (`Platform.kt` `Speaker`). T1c.

10. **Slow or absent Ollama** delays L (see D3). C itself is applied on the event, before the model is asked (`KavachRuntime.handle`). The keyword floor is not.

---

## 4. Code gaps, bugs, stale docs

Verified by reading source. Not fixed (this audit only adds this file).

| Item | What’s wrong | Evidence |
|---|---|---|
| Handover pause claim | Cellular payment-app-during-call is 45, tier 1. See section 3.2. | `HANDOVER.md` risks table vs `risk_config.json`, `RiskEngine.kt` |
| “138 core tests” | `core/src/test` has **140** `@Test` methods. `:core:test` excludes tag `live-llm` (1 test, `LiveOllamaSmokeTest`), so the CI set is **139** methods. App module has **2** more (`SettingsStateTest`). Not re-run, so “green” is the 2026-11-03 handover claim. | `core/build.gradle.kts` `excludeTags("live-llm")`, test sources |
| Scenario counts | **Match the docs:** `scenarios/index.json` has 32 ids, 18 `scam-*`, 14 `normal-*`. One scam sets `known_gap` and does not gate CI. “17/17 paused, 0/14 normal” is what `EvalSuiteTest` enforces. README’s “min 6 s, median 18 s” lead time was **not** recomputed. | `index.json`, `EvalSuiteTest.kt`, `README.md` |
| Weights “without rebuilding” | False for the phone. Classpath resource only. | `CLAUDE.md`, `RiskConfig.kt` |
| Synthetic voice “weight is 0 when absent” | Configured weight is **10**. No app code emits a score, so the rule adds nothing. | `HANDOVER.md`, `risk_config.json`, grep of `VoiceSpoofScore` (tests and scenario loader only) |
| `docs/TEAM.md` | Named in the `CLAUDE.md` layout. File does not exist. | `CLAUDE.md` |
| Reboot restart | Comment claims it. No boot receiver. | `KavachApp.kt` |
| Simulate vs Settings | Sim profile is Anjali `+919800000001` and Rahul `+919800000002`, not Settings. The pause “call” button uses `ACTION_CALL` when `CALL_PHONE` is granted (`Platform.kt` `Actions.call`). Tapping it in Simulate dials that sample number. SMS in Simulate is a dry run. | `Scenario.kt` `defaultProfile`, `KavachApp.newSimEngine`, `InterventionPlanner.pickCallee` |
| Debug inject does not reproduce the hero tier-3 | Default transcript has no UPI id. “₹50k” sends payee `verify.pay@oksbi`, not `sharma.verify@okicici`. Payee-from-call will not trip. | `Screens.kt` Debug screen |
| Settings audio chips | Day-0 can store `UNPROCESSED`. Settings chips only list `VOICE_RECOGNITION`, `MIC`, `CAMCORDER`. `VOICE_COMMUNICATION` is accepted by `audioSourceOf` and shown nowhere. | `Screens.kt`, `Audio.kt`, `Settings.kt` |
| Keyword result waits on the LLM | Contradicts the spirit of “a rule score never waits on an LLM” for **L**, not for **C**. Evals use `KeywordTranscriptAnalyst` directly, so the scorecard does not include this wait. | `Analysts.kt`, `ScenarioRunner.analystFor`, `CLAUDE.md` rule 4 |
| Accessibility nodes | `collectTexts` walks children and never calls `recycle()`. Risk of node leaks on long sessions. **Assumed** severity: low for a 4-minute demo. | `KavachAccessibilityService.kt` |
| Unused call fields | `isInternational` and `firstSeenToday` are set and never scored. | `CallStateTracker.kt`, `Events.kt`; no use in `RiskEngine.kt` |
| `READ_SMS` | Requested, unused. | `AndroidManifest.xml`, `MainActivity.kt` Setup, `Receivers.kt` |
| Investment scam | Built as a known miss, not a silent pass. | `scam-investment-telegram-tasks.json`, `docs/DECISIONS.md` |
| Per-payee median | Global median only. Harmless while guards hold tier 0; do not “fix” tonight. | `RiskEngine.kt` `medianAmount`, `docs/DECISIONS.md` |
| `RiskConfig` KDoc | Says L is an average of the last N runs. Code is `max(latest, mean of last N)` (decision 2). | `RiskConfig.kt`, `RiskEngine.kt`, `docs/DECISIONS.md` |
| Seed button copy | Says “8x usual”. The rule is 3× median. | `Screens.kt`, `risk_config.json` |
| Companion test | `companion/test_listener.py` posts to a local mock. It does not open `CompanionServer` on a phone. Handover’s “script tested against a mock” matches that file; this audit did not run it. | `companion/test_listener.py`, `HANDOVER.md` |
| Dependabot | Handover says seven PRs were left open. Not checked (no GitHub calls in this audit). | `HANDOVER.md` session log |

Design notes, not demo blockers if D1–D5 are followed:

- Fusion can **lower** a scary context score when a benign transcript arrives (`RiskEngineTest` “benign transcript pulls a mid-strength context down”). A bad ASR of a real scam can delay the pause. Keyword floor limits how far (0.85×). **Verified** in `RiskEngine.kt` and `docs/DECISIONS.md`.
- Long-call points need 10 or 30 minutes (`risk_config.json`). They will not fire inside a 4-minute demo.
- Odd-hour (+5) is 23:00–06:00 `Asia/Kolkata` only. Do not count on it.

---

## 5. Demo-day readiness

### Missing artifacts

| Artifact | State | Owner |
|---|---|---|
| Spec / spoken 4-minute script | Not in the repo | Ishan |
| Pitch deck | Not in the repo | Ishan |
| Backup video | Not in the repo. `CLAUDE.md` order is live call → Simulate → recorded video. Record the phone screen the first time T1b succeeds, before chasing audio. | Om records, Ishan keeps the file off git (`*.wav` is gitignored; a screen recording is not a wav) |
| On-device whisper, on-phone Ollama, spoof model, safe-word capture | Not started (`HANDOVER.md`) | Defer (D8) |
| Phone test plan ticks | All blank | Om |
| Seeded baseline, guardian, family number, protection ON, accessibility ON | User actions, not code | Om |

### What a 4-minute demo can honestly be

**Assumed** run-of-show, because the spec script is absent. Times are stage time, not the JSON clock.

1. **0:00–0:40** One sentence: UPI auth proves who paid, not why. Show Home readiness all green, protection ON, ASR line “none (option D…)” unless companion is on. If you show ASR “none”, say so before the judges notice.
2. **0:40–2:10** Simulate `scam-digital-arrest-english-demo` at 4× (`HANDOVER.md` step 2). Expect a pause, three questions, spoken warning, timer (tier 3 is 120 s in config; do not wait it out), and a **dry-run** SMS in the Sim log to `+919800000001`. Do not tap the call button (D6 / section 4).
3. **2:10–3:10** One normal scenario (`normal-pay-shopkeeper` or `normal-friend-long-call-pay-rent`). Expect no overlay (`PHONE_TEST_PLAN.md` T1d). This is the “normal life shows nothing” claim.
4. **3:10–4:00** Only if T3 and T4 passed: unknown live call, open the chosen UPI app, stop before PIN. Otherwise use Debug inject and say it is injected. Optional last card: complaint report (T11), not a live 1930 call.

Do not play `scam-investment-telegram-tasks` as a success. Do not promise tier 3 on a phone with no transcript and no seeded baseline.

### Tonight, in order

1. Agree D1–D4, D8, D9, D11 in chat. No code required for those.
2. Om: install, restricted settings, T1, **T1b Simulate**, **T2 audio**, then T4 on one UPI app. Seed baseline before any live payment screen (`Settings` → “Seed demo payment history”).
3. If T2 is silent: enable companion, USB forward, `--stdin` first (proves the socket), then the mic.
4. Ishan: deck outline from `README.md` and `docs/DECISIONS.md` “Things the evals taught us”. Record Simulate once T1b works.
5. Rehearse the spoken line exactly: “sharma dot verify at okicici”, then type `sharma.verify@okicici`, and look for the payee line in the Debug log.

### Verified facts that are in good shape (no decision)

- Core is the only place a tier is produced; the LLM does not (`RiskEngine.kt`, `CLAUDE.md`).
- Cloud default off; cleartext only to localhost; guardian SMS has no transcript (`RepoSecurityTest` expectations, `GuardianLink.kt`, `network_security_config.xml`).
- User can proceed after the pause (`Orchestrator` `UserProceeded`, overlay Proceed). Tier 2 timer 30 s, tier 3 timer 120 s (`risk_config.json`).
- Option D **scenario** (with the bundled baseline and a parsed new payee and amount) expects tier 2 with no analyst (`scam-option-d-no-audio.json`). That is not the same as a fresh install.
- 32 scenario files are listed in `index.json` and the known gap is labelled, not hidden (`EvalSuiteTest`).
