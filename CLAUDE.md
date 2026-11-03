# Kavach: working agreement (for humans and Claude sessions)

> **First thing every session: read `HANDOVER.md`** (current status, what is verified, what to do next).
> **Last thing every session: update it** with `/handover`. Phone verification steps are in `docs/PHONE_TEST_PLAN.md`.

Android guardian that detects the **coercion context** around a UPI payment (unknown live call + urgency + secrecy + payment app
opened) and adds friction only then. Spec: "Kavach: Interrupting Coerced Payments Before They Complete". Hackathon build, two developers
(Ishan, Om) working in parallel on GitHub. **Demo is the product: a smooth, reliable 4-minute demo beats breadth.**

## Layout

```
core/   Pure Kotlin/JVM. Events, risk engine, orchestrator, LLM routing, keyword analyst, report, scenario simulator. NO Android imports.
app/    Android app (Compose + services). Thin: senses the OS, emits events, executes Effects. Builds; NOT yet verified on a device.
companion/  Python faster-whisper listener that POSTs transcripts to the phone (audio option C). `--stdin` mode needs no mic.
docs/   DECISIONS.md (deviations from the spec), ANDROID_SETUP.md, TEAM.md
core/src/main/resources/scenarios/*.json   Eval scenarios. Also power the in-app "Simulate" backup demo.
core/src/main/resources/risk_config.json   Every weight. Tunable on stage without rebuilding.
```

## Architecture rules (do not break)

1. **Signals flow one way.** Sensors emit typed `KavachEvent`s; analysts return findings that re-enter as events; **only `RiskEngine` produces a tier.** The LLM never decides.
2. **`core` is deterministic and pure.** Same events, same decisions. Time comes from event timestamps, never `System.currentTimeMillis()` inside logic.
3. **The core asks, the app does.** The orchestrator returns `Effect`s (ShowPause, SendGuardianSms...). Only `app/` touches the OS.
4. **A rule score must never wait on an LLM.** C is instant; the LLM can only refine it.
5. **Never block a legitimate payment.** The user can always proceed after the pause. Normal life must show nothing.
6. **Privacy by construction:** no logging in `core` main (a test enforces it), no raw audio in the llm/network layer, cloud fallback off by default, guardian SMS carries a summary, never a transcript.

## Commands

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 17)   # Gradle 8.13 needs JDK 17, not 21+/27
./gradlew :core:test          # unit + eval scenarios + security gates (~5 s). RUN BEFORE EVERY PUSH.
./gradlew :core:eval          # just the scenario evals; prints the scorecard
./gradlew :app:installDebug   # build + install on the connected phone
./gradlew :app:assembleDebug :app:lintDebug   # what CI runs for the app
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools   # SDK (see docs/ANDROID_SETUP.md)
./gradlew :core:llmEval       # same idea against a LIVE Ollama model (needs `ollama serve`; KAVACH_OLLAMA_MODEL, default gemma3:4b)
./scripts/install-hooks.sh    # one-time: pre-commit runs :core:test and blocks audio/keystores/secrets
```

Scorecard lands in `core/build/reports/kavach-eval/scorecard.md`. A failing scenario prints a step-by-step trace (C, L, R, tier, rule hits).

## Rules for changes

* **Every behaviour change ships with a scenario or a test.** Add a JSON scenario (and list it in `scenarios/index.json`) for any new scam or normal case you find.
* **The eval gate is non-negotiable:** 0 normal scenarios may reach tier 2+, every gated scam must pause before the PIN screen. If you must change a weight, re-run evals and look at BOTH sides of the scorecard.
* **Do not "fix" a failing scenario by loosening its expectation** unless you can say in `docs/DECISIONS.md` why the expectation was wrong.
* Compiler warnings are errors in `core`. Strict config parsing: a typo in `risk_config.json` fails loudly on purpose.
* Secrets (API keys) go in `local.properties` / env, never in git. `*.wav` and keystores are gitignored; a test fails if they appear.
* Wording lives in `intervention/InterventionPlanner.kt` (object `T`), English + Hindi. Edit copy there, not in UI code.

## Git workflow (two people, one repo)

* `main` is always green and demo-able. Work on branches `feat/<area>-<topic>` / `fix/...`; open a PR; CI must pass. Small PRs, merge often.
* **Ownership to avoid conflicts:** agree one owner per directory at the start (suggested: Ishan = `core/`, `docs/`; Om = `app/` sensors + UI). Touching the other's area? Say so in the PR title and keep it small.
* `core` API changes that `app` depends on: land them first, in their own PR.
* Rebase on `main` before opening a PR (`git pull --rebase origin main`).
* Commit messages: imperative, what and why. Never commit with failing `:core:test`.

## Demo-day checklist

* The 4-minute script is in the spec. `scenarios/scam-digital-arrest-english-demo.json` is that script as data.
* Backups in order: live scammer phone -> in-app Simulate button (same orchestrator) -> recorded video.
* Day-0 audio test decides audio option A/B/C/D. Option D (no audio) must still reach tier 2: it does (`scam-option-d-no-audio`).
* Seed the demo phone's baseline (usual payees/amounts) so "new payee" and "8x usual" fire in the right places.
