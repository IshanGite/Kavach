# Kavach

**Interrupting coerced payments before they complete.**

OTP, PIN and biometrics prove *who* is paying, not *why*. In digital-arrest and voice-clone scams the real owner enters the real PIN,
so every authentication check passes. Kavach is an on-device Android guardian that watches the **coercion context** around a payment and
adds friction only when it looks like a scam:

| Situation | What happens |
|---|---|
| Normal payment (no live call, known payee, normal amount) | **Nothing.** Zero friction. |
| Suspicious context | Gentle banner, one tap to call a trusted family member |
| Strong coercion signature | Full-screen pause, cooling-off timer, three yes/no questions, guardian SMS, one-tap callback |
| After the event | Ready-to-file complaint for cybercrime.gov.in / 1930 |

The user can always proceed. Kavach never blocks a payment; it breaks the scammer's grip (isolation, urgency, the open call).

## Status

| Part | State |
|---|---|
| `core`: events, risk fusion, orchestrator, LLM routing (Ollama + cloud), keyword analyst, guardian, report, simulator | built, tested, evaluated |
| Eval suite: 61 scenarios (34 scam / 27 normal), fuzzed safety invariants, security gates | passing in CI |
| `app`: Android sensors, overlay, TTS, SMS, PDF, Simulate backup demo, Day-0 audio test | built, APK + lint green, **not yet run on a device** |
| Speech-to-text (whisper.cpp) and on-phone Ollama | next (see `HANDOVER.md`) |

## Quick start

```bash
git clone https://github.com/IshanGite/Kavach.git && cd Kavach
export JAVA_HOME=$(/usr/libexec/java_home -v 17)     # JDK 17
./scripts/install-hooks.sh
./gradlew :core:test        # 230+ tests, scenario evals, security gates
./gradlew :core:eval        # scorecard
```

**New here (or a new Claude session)? Read [`HANDOVER.md`](HANDOVER.md) first.** See [`CLAUDE.md`](CLAUDE.md) for architecture rules and workflow, [`docs/DECISIONS.md`](docs/DECISIONS.md) for where we deviate from the
spec and why, [`docs/ANDROID_SETUP.md`](docs/ANDROID_SETUP.md) for the phone, and [`docs/PHONE_TEST_PLAN.md`](docs/PHONE_TEST_PLAN.md) for on-device verification.

## How the decision is made

```
Sense (events) -> Understand (analysts return findings) -> Fuse (RiskEngine: R = 0.6 C + 0.4 L) -> Intervene (Effects)
```

* **C**, the context score, is the sum of transparent rule points (unknown live call 20, payment app during call 25, new payee 10, ...).
  All weights in `risk_config.json`. Computed instantly; never waits on a model.
* **L**, the language score, comes from a local LLM (Ollama) reading the last ~90 s of transcript, with a deterministic English/Hindi/Hinglish
  keyword analyst as a floor and a fallback. The LLM interprets; **code decides**.
* Tiers: `< 33` nothing, `33-59` nudge, `60-79` pause, `>= 80` pause + guardian. Pause tiers require a payment intent. Hard overrides: remote-access
  app + payment, or a payee spoken on the call appearing on the pay screen.

## Evals

```
./gradlew :core:eval
```

```
| Scams paused before payment                          | 34 / 34 |
| Normal payments with a pause (tier 2+)               | 0 / 27  |
| Pause shown before the PIN screen, lead time         | min 6 s, median 18 s |
```

Scenarios are plain JSON under `core/src/main/resources/scenarios/`. They include a hijacked LLM (prompt injection), a 12 s LLM, and a phone
with no usable audio. The same files drive the in-app backup demo.
