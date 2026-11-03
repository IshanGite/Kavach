# Checkpoint (Om + Claude, 2026-11-03)

Done: repo cloned; core tests + :app:assembleDebug pass on Windows (ANDROID_HOME=$HOME/AppData/Local/Android/Sdk, JDK17);
emulator AVD `kavach34` (Android 14, x86_64, WHPX) created; app installed; Simulate `scam-digital-arrest-english-demo` shows the pause overlay.
Docs added: RESEARCH_USPS.md, TEAM_DECISIONS.md, REPOS_TO_REUSE.md.

Stopped midway (nothing applied, runs discarded): (1) "Call 1930" button on pause overlay, (2) Vosk offline ASR (see REPOS_TO_REUSE.md section 1).
Next: redo those two via cursor-delegate (grok-4.7-xhigh, not fast); then emulator tests (adb emu gsm call / sms send); OnePlus 7 Pro tests when Om gives the green signal.
Nothing committed or pushed.
