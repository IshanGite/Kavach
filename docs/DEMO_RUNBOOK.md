# Demo runbook

The demo is the product. Everything here exists so that a surprise on stage costs us seconds, not the demo.
Spec demo script (4 min) is in the spec; this is how to *run* it. Update the "Rehearsal log" at the bottom after every run-through.

## Roles

| Who | Does |
|---|---|
| Presenter | Narrates, never touches a phone during the live segment |
| Operator | Holds the "scammer" phone (Phone B) and reads the script; owns the fallback ladder |
| Both | One rehearsal out loud, with a timer, before going on |

## Kit

- **Phone A (victim):** Om's spare phone, Kavach installed, protection ON, set up completely (Home readiness all ✅).
- **Phone B (scammer):** Ishan's phone. Its number is NOT saved on Phone A. (Check: Contacts on Phone A.)
- **Laptop** mirroring Phone A for the audience: `brew install scrcpy`, then `scrcpy --no-audio --stay-awake` (USB). Put Kavach's **Live view** on screen.
- **Hot backup audio:** laptop running `companion/listener.py` (option C), even if the phone mic works.
- Chargers, a USB hub, a hotspot (venue Wi-Fi is not needed: everything is local), printed copy of the script.
- **A real second UPI ID** that you control, to be the "scammer's account". Say it on the call and type it in the payment app. **Do not use a made-up VPA:** the UPI app verifies it with NPCI and will block the screen before an amount, so the demo dies. Never press the final pay button.

## Countdown

**T-24 h**
- [ ] `./gradlew :core:test` green on `main`; APK built from `main` and installed on Phone A.
- [ ] Day-0 audio test done, source set in Settings (`docs/PHONE_TEST_PLAN.md` T2).
- [ ] `ScreenParser` reads amount and payee on the payment app you will use (T4, T5).
- [ ] Full live run (T6) works 3 times in a row.
- [ ] Recorded a backup video of a perfect run (screen record Phone A + audio).

**T-2 h**
- [ ] Phone A fully charged. Mute other apps' notifications (a stray WhatsApp banner on the mirrored screen is distracting) but keep Kavach's. Auto-rotate off, screen timeout long, brightness high.
- [ ] Settings: language, guardian number = the second phone you will hold up, family contact set. Seed demo payment history (Settings > Seed demo payment history).
- [ ] Media volume up (the spoken warning must be audible in the room).
- [ ] Battery optimisation off for Kavach (and Termux); Kavach locked in recents.
- [ ] Ollama warm (`ollama run` once) if the phone/laptop model is used; Debug > Ping shows a latency.

**T-15 min**
- [ ] **Home > Pre-flight check**: all ✅, and you have SEEN the test pause screen and HEARD it, and the model ping is under budget.
- [ ] Protection ON. Live view on the mirrored screen (it also shows the bank-side signal JSON: point at it in the roadmap slide).
- [ ] Phone B can call Phone A on speaker; hear a test word end to end.
- [ ] Simulate `scam-digital-arrest-english-demo` once at 8x to confirm overlay + speech, then **Stop**.
- [ ] Clear clutter: close other apps, clear Phone A's notification shade.

**T-2 min**
- [ ] Phone A on the Live view. Phone B ready to dial.

## The 4-minute script

| Time | Scene | Operator does | The audience sees | Presenter says |
|---|---|---|---|---|
| 0:00 | Problem | Nothing | Title | "OTP, PIN, biometrics prove *who* is paying, not *why*. In a digital-arrest scam the real owner enters the real PIN. Every check passes." |
| 0:30 | Normal payment | On Phone A, open GPay, pay a saved contact ₹200, stop before PIN | Nothing appears | "Kavach is invisible for normal life." |
| 1:00 | Digital-arrest call | Phone B calls Phone A. Read the script slowly (below) | Live view: unknown caller, risk climbing, transcript with red highlights, then a tier-1 banner "Real police never ask for money on a call" | "Unknown number, urgency, secrecy, on the line. Each signal alone means nothing; together they climb." |
| 2:15 | Payment attempt | Phone A: open the payment app, type the account the caller read out, ₹50,000 | Full-screen pause **before the PIN screen**: reasons quoted from this call, timer, three questions; spoken warning; guardian phone buzzes | "The account number was read out on the call, then typed here. That's a hard override." |
| 3:00 | Voice-clone | Optional: Simulate `scam-voice-clone-son-accident` | Banner asks for the family safe word, "Call Rahul on his saved number" | "A cloned voice can't answer a safe word." |
| 3:20 | Report | End the call; open Complaint report | PDF, 1930 button | "Ready to file, with the timeline." |
| 3:45 | Close | | Scorecard slide | "On-device, private, and it never blocks a real payment. It only gives the victim back the one thing scammers take: a moment to think." |

### Phone B script (read it, don't improvise)

> "Hello, I am Inspector Sharma from Mumbai Cyber Crime Branch. A parcel in your name has been caught with drugs.
> There is a money laundering case against you and you may be arrested. Stay on the call, do not disconnect, and do not tell anyone, not your family.
> To verify your innocence you must transfer fifty thousand rupees immediately to this verification account: **\<your real second UPI ID, spoken as "name dot something at bank"\>**."

## Fallback ladder (decide in 5 seconds, say it calmly)

1. **Live call misbehaves** (no transcript, late overlay): do NOT open Debug on stage. Keep going. The presenter says "audio is the hardest part on phones, so Kavach degrades gracefully", and the pause still fires from call + payment app + new payee + amount (option D).
2. **Live run fails entirely:** switch to **Simulate** > `scam-digital-arrest-english-demo` at 2x (Home > Simulate). Same engine, same overlay, same speech; only the call is scripted. Say so honestly: "Here is the same scenario replayed through the real engine."
3. **Phone dead / app crashed:** play the recorded video.

Never say "it should work". If something breaks, name it, show the ladder, move on.

## Failure playbook

| Symptom | Likely cause | 10-second fix |
|---|---|---|
| No overlay at all | Accessibility service got disabled; overlay blocked | Settings > Accessibility > Kavach on; or fall back to Simulate |
| Overlay only after the PIN screen | Amount/payee not readable in that app | Use the app that `ScreenParser` reads (T4); the app-opened rule still nudges earlier |
| No transcript | Mic silenced, no ASR | Companion laptop listener; else option D narrative |
| Hindi speech is English | Hindi TTS voice not installed | Say it is a voice-pack issue; text is Hindi |
| Spoken warning inaudible | Volume | Media volume up before the demo |
| Guardian SMS not arriving | Dual SIM default / no signal | Show the Sim log "Guardian SMS" line instead |
| Model slow | Phone CPU | Normal. The keyword analyst covers; say "the rule score never waits on the model" |
| Call from Phone B treated as known | Number is in Phone A's contacts | Delete it from contacts |

## Rehearsal log (append)

| Date | Who | Path run | Result | Fix needed |
|---|---|---|---|---|
| | | | | |
