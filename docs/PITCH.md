# Pitch and judge Q&A

Rule for everything here: **only claim what the evals and the demo can back up, and state the limits first.** Judges forgive a limitation you named; they punish one they discovered.

## One-liner

> Kavach protects the one moment every other defence misses: when the victim themselves enters the real PIN under coercion. It reads the *context* around a payment, not the payment, and adds friction only when the context looks like a scam.

## 30-second version

OTP, PIN and biometrics prove **who** is paying, not **why**. In a digital-arrest or voice-clone scam the real owner enters the real PIN, so every authentication check passes.
The only place to intervene is the context just before the PIN screen. Kavach runs on the phone, notices *unknown caller live + urgency + secrecy + a payment app opening*, and pauses only then.
Normal payments see nothing. A suspicious one gets a banner. A strong coercion signature gets a pause: reasons quoted from that very call, a cooling-off timer, three questions, a one-tap call to family, and an SMS to a guardian.
It never blocks. It gives the victim back the one thing scammers take: a moment to think.

## Slide outline (10 slides, 4 minutes + demo)

1. **The gap.** "Every check passes because the victim is the one paying." One picture: PIN screen with a phone call still on the line.
2. **Why existing defences miss it.** OTP / PIN / biometrics / transaction limits / bank fraud models: all authenticate or score the *payment*. None sees the call.
3. **The coercion signature.** Five traits that repeat across scams (authority, urgency, secrecy, control of the channel, new payee). No single one is proof; together, while a payment starts, they are.
4. **How it works.** Sense (call, mic, messages, screen) -> Understand (local LLM + rules) -> **Fuse (code decides)** -> Intervene. One line: *the LLM interprets, deterministic code decides.*
5. **Friction in proportion.** The four tiers. "Normal life: nothing. Never blocks."
6. **LIVE DEMO** (runbook).
7. **After the event.** Complaint ready for cybercrime.gov.in / 1930, golden-hour mode.
8. **Proof, not claims.** The scorecard (below) and the safety invariants. Say the limits.
9. **Privacy by construction.** On-device by default, cloud off, no raw audio anywhere, tests that fail the build if that changes.
10. **Roadmap and the bank-side hook.** Banks/PSPs consume a privacy-clean risk signal and add a delay or call-back. The Live view shows that JSON.

## What to show on the proof slide

Run `./gradlew :core:eval` right before the event and paste the real scorecard. Current numbers and what they mean:

| Number | What it is | How to say it |
|---|---|---|
| 34 of 34 scam scenarios paused before the PIN screen; 0 of 27 normal scenarios paused | Our scenario suite, including a hijacked LLM, a 12 s LLM and a phone with no audio | "Replayed through the same engine the app runs." |
| **First run on 12 unseen scams the lexicon had never seen: 6 of 12** (rule-based language layer only) | The honest held-out result before we tuned | "Our first held-out test caught half. That's why the language layer is an LLM *plus* rules, and why we now generalise vocabulary classes, not phrases." |
| 0 of 8 unseen normal scenarios paused | Held-out normal life | "No false pauses on situations we didn't write for." |
| Live `gemma3:4b` in the loop: see `scorecard-live-*.md` | The real local model on the full suite | Quote the file's numbers, including latency |
| 300 fuzzed random event streams, safety invariants hold | `InvariantFuzzTest` | "A pause never appears without a payment starting; a saved payee is never interrupted." |

**Do not say** "99% accurate" or any accuracy figure. We have scenario results, not field data. Say that.

## Differentiators (say these, they are true)

1. **Context, not the transaction.** Competing ideas score the payment (amount, payee, velocity). Those are all normal in a coerced payment. The call is the signal.
2. **LLM interprets, code decides.** Explainable, predictable, safe when a small model hallucinates. We even test a *hijacked* model: the pause still fires.
3. **Degrades gracefully.** Works with no audio (call + payment app + new payee + amount reaches the pause tier), no LLM, no internet.
4. **Never blocks; friction scales.** The user can always proceed after the pause. Normal life is invisible.
5. **Breaks the isolation, not just warns.** Specific reasons from *their* call, a timer, a one-tap family call, a guardian alert with `HOLD`, a safe word against voice clones.
6. **Payee-from-call hard override.** The account read out on the call appearing on the pay screen is near-certain coercion.
7. **It helps after, too.** Complaint fields in the portal's own layout; 1930 first if money left.
8. **Evals that run constantly.** 60+ scenarios, fuzzed invariants, repo security gates in CI. A judge can run it.

## Judge Q&A

**Isn't this just a spam-call blocker?**
No. Call blockers act on the call. The victim here *wants* the call (it's "the police"). Kavach acts on the combination of the call and the payment.

**Why not just block the payment?**
Because false positives on a payment product destroy trust, and a blocked legitimate payment is a real harm. A pause that the user can always end keeps the human in charge and still breaks urgency and isolation, which is what the scam depends on.

**How do you avoid false positives?**
Two guards in code: a pause needs a payment to be starting, and paying a saved, frequently-paid person with no live call is never interrupted. Known callers are never analysed. The user's own payment history keeps everyday amounts quiet. We measure it: 0 of 27 normal scenarios paused, including a 3 am payment, a large payment to saved family, and a 40-minute call with a friend before paying rent.

**What if the scammer tells the AI to ignore its instructions?**
We test exactly that. A rule-based analyst, which cannot be talked into anything, sits as a floor under the LLM, and the context score never depended on the LLM. In the "hijacked model" scenario the model returns risk 0 and a made-up payee and the pause still fires.

**What if the phone won't let you listen during a call?**
It often won't (Android 10+). That's the spec's top risk and our Day-0 test decides it per phone. Options: mic on speaker; accessibility-assisted capture; a companion device that transcribes and sends text; or no audio at all. With *no* audio the call + payment-app + new payee + amount signals still reach the pause. Audio makes it more specific, it is not a single point of failure.

**Your accuracy?**
We don't have field accuracy and won't pretend to. We have scenario results and a held-out test. On 12 scam scripts written afterwards in different wording, the rule-based layer alone caught half on the first try, which is why we generalised the vocabulary and why the LLM is in the loop. The next step is real recorded calls with consent and a held-out set from people outside the team.

**Privacy and legality?**
On-device by default; the cloud fallback is off and, if enabled, sends only a short transcript window as text, never audio or phone numbers. Audio lives in a memory ring buffer and is zeroed after the call. Saved contacts are never recorded. Guardian SMS carries a summary, not a transcript. We follow the DPDP Act's purpose-limitation and consent ideas, frame this as the user's own protective tool, and say plainly that a real launch needs a legal opinion on call-audio capture.

**Play Store?**
Google restricts accessibility-based call capture and broad SMS access. For the hackathon it's a sideloaded APK. A real launch needs a policy exemption, an OEM or bank partnership (a bank app shipping the module), or the companion-device mode. That is also why the bank-side signal matters.

**Why a local LLM on a phone? Isn't it slow?**
It is, and we measured it: a 4B model took about 9 s per analysis on a laptop with the full prompt. So the design never waits on it: the rule score is instant, there is an 8 s timeout, a compact prompt, a keep-warm request, and the rule-based analyst covers whenever the model is late. The LLM makes the explanation more specific; it is not on the critical path.

**What about languages?**
Hindi, English and Hinglish today (warnings are spoken in the user's language). The language layer is prompt + lexicon, so adding Tamil, Bengali or Marathi is a data task, not a re-architecture.

**What can a bank do with it?**
The Live view shows the signal: risk, tier and *categories* of signal, no names, numbers, transcript or amounts. A bank or PSP could add a delay-and-confirm or a call-back on a high-risk transfer.

**Battery?**
Audio is only captured during calls from numbers not in contacts; the model runs every 20 s on new transcript only. We haven't measured battery on the demo phone yet (say so if asked).

**What doesn't it handle?**
Honest list: scams with no call and no obvious pattern beyond repeated new payees (partly covered by the repeated-new-payee rule); voice-clone detection is a weak signal on phone-line audio; VoIP call detection is heuristic; a victim who ignores the pause. We list our known gaps in the repo.

**What did you learn building it?**
Three things the evals taught us: averaging the language score *lowered* a correct alarm (the transcript window is cumulative), so we made it fast-attack/slow-release; "enter your PIN to receive money" has no authority and no threat but is the classic UPI scam, so asking for a PIN/OTP is itself a signal; and our first held-out test caught half the scams, which is why we stopped trusting our own scripts.

## Closing line

> "On-device, private, and it never blocks a real payment. It only gives the victim back the one thing scammers take: a moment to think."
