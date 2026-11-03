# Repos, libraries, models and datasets to reuse (researched 2026-11-03, demo 2026-11-04)

Method: `gh api repos/...` for license, stars and last push (numbers below are what the API returned on 2026-11-03), Maven Central and
Google Maven indexes for artifact versions, the Hugging Face API for model and dataset licenses, plus web search. "Last push" means the
repo's `pushed_at`. Where something is **unverified**, it says so. Read with `HANDOVER.md` and `docs/TEAM_DECISIONS.md` (D1, D8): this file
does not override those decisions. It tells you what to grab if you do the work.

---

## 0. Use these now (ranked by impact vs effort, demo is tomorrow)

| # | What | Why now | Effort | License / maintained |
|---|---|---|---|---|
| 1 | **Vosk Android** (`com.alphacephei:vosk-android:0.3.75`, Maven Central) + **`vosk-model-small-hi-0.22`** (42 MB) and **`vosk-model-small-en-in-0.4`** (36 MB) | Drops straight into our `AsrEngine.transcribe(ShortArray, 16000)` signature. Offline, streaming, has a real **Hindi** and an **Indian-English** small model. Output is Devanagari for Hindi, which `KeywordAnalyst` already matches. Turns option D into option A without JNI or NDK. | 1-2 h | Apache-2.0, alphacep/vosk-api 15.2k stars, pushed 2026-10-15 |
| 2 | **Android emulator console** (`adb emu gsm call`, `adb emu sms send`) + host-mic routing (+ a virtual audio cable on Windows) | Lets us test the call-state tracker, SMS receiver, overlay and ASR without the demo phone. Built into the SDK. Recipe in section 9. | 0.5 h | Part of Android SDK |
| 3 | **Hinglish / Indian scam datasets as a fresh held-out set**: `ysangam/Indian_Cyber_Scam_PhoneCall_Hinglish_Dataset` (Apache-2.0, 10k-100k rows), `karanverma19/Indian_Multilingual_Scam_Message_Dataset` (Apache-2.0, 120 rows, Hindi/Hinglish/English with reasons), `BothBosu/scam-dialogue` (Apache-2.0, English multi-turn calls, scam + non-scam) | HANDOVER says "we still need a fresh held-out set from someone outside the team". Run `KeywordAnalyst` over these tonight and quote the number honestly. Also mine missing Hinglish phrases. | 1-2 h | Licenses from HF cards. Synthetic, provenance unclear (see risks) |
| 4 | **`CallScreeningService` + `RoleManager.ROLE_CALL_SCREENING`** (platform API, reference impl: **aj3423/SpamBlocker**) | Gets the incoming caller's number on API 29+ **without READ_CALL_LOG** (a Play-restricted permission), and lets us mark "not in contacts" reliably. SpamBlocker shows the working pattern. | 1-2 h | Platform API. SpamBlocker MIT, 1.95k stars, pushed 2026-11-03 |
| 5 | **scrcpy** (Genymobile/scrcpy) | Mirror the phone to the projector for the Live view; already in our plan. Also `scrcpy --no-audio` to avoid echo of the scam audio. | 0 h | Apache-2.0, 154k stars, pushed 2026-11-02 |
| 6 | **ZXing core** (`com.google.zxing:core`) + plain `android.net.Uri` parsing of `upi://pay?pa=&pn=&am=&tn=` | Cheap "UPI QR / link checker" demo moment: flag `upi://pay` with an amount on a "receive money" QR, or a `pa` handle not matching `pn`. No network. | 1-2 h (optional) | Apache-2.0, zxing 34k stars, pushed 2026-11-02 |
| 7 | **TRAI number-series rule** (no repo): legitimate bank/service calls must come from the **160xxxx** series and promotional calls from **140xxxx**; a "bank/police/RBI" caller on a normal 10-digit mobile is a red flag. Optional: `hstsethi/in-mob-prefix` (CC-BY-4.0) for operator/state of a number. | One new context rule with a great judge line. No open India fraud-number blocklist exists (section 8). **Verify the 160-series directive wording before saying it on stage.** | 0.5-1 h | Rule is ours; dataset CC-BY-4.0 (attribution) |
| 8 | **(Only if time remains) sherpa-onnx** AAR v2.0.1 with Silero VAD + Whisper-tiny or a streaming model | Best long-term on-device ASR toolkit (VAD, ASR, KWS, speaker ID, audio tagging, language ID, all with Android demos). Heavier than Vosk; no official streaming Hindi model. | 3-5 h | Apache-2.0, 15.7k stars, pushed 2026-11-03 |

**Explicitly defer past the demo** (agrees with D8): on-phone LLM (LiteRT-LM), deepfake/ONNX spoof detector, whisper.cpp JNI.
Reasons are in each section.

---

## 1. On-device speech-to-text

| Candidate | URL | License | Stars / last push | What it gives us | Integration (hours) | Risk |
|---|---|---|---|---|---|---|
| **Vosk** (pick for tonight) | https://github.com/alphacep/vosk-api , demo https://github.com/alphacep/vosk-android-demo , models https://alphacephei.com/vosk/models | Apache-2.0 (code and the small models) | 15.2k / 2026-10-15; demo 1.06k / 2025-12-08 | Maven AAR `com.alphacephei:vosk-android:0.3.75` (latest on Maven Central, updated 2025-12-08). Kaldi-based, streaming, ~1-3 s model load, runs on low-end phones. Models: `vosk-model-small-hi-0.22` 42 MB (WER 20.9 IITM / 24.7 MUCS), `vosk-model-small-en-in-0.4` 36 MB | 1-2 h, see snippet below | Accuracy on speakerphone audio through the mic is the unknown (same for every engine). Hindi small model has a limited vocabulary; Hinglish words in Latin script will come out as Devanagari or be dropped. Use **both** models and concatenate |
| sherpa-onnx | https://github.com/k2-fsa/sherpa-onnx | Apache-2.0 | 15.7k / 2026-11-03 | AAR on GitHub releases (`sherpa-onnx-2.0.1.aar`, also a static-onnxruntime variant). Kotlin API, Android demos: `SherpaOnnxVadAsr`, `SherpaOnnxSimulateStreamingAsr`, `SherpaOnnxKws` (keyword spotting), `SherpaOnnxSpeakerIdentification`, `SherpaOnnxSpokenLanguageIdentification`. Runs Whisper, Zipformer, Paraformer, SenseVoice, NeMo models | 3-5 h (model choice is the time sink) | No official streaming Hindi model. Community: `meetsync/indic-conformer-onnx-sherpa` (MIT, AI4Bharat IndicConformer, int8; streaming on Android unverified), `ippocode/indic-asr-onnx` (Apache-2.0, whisper-small-hi int8, 358 MB, too big) |
| whisper.cpp | https://github.com/ggml-org/whisper.cpp (`examples/whisper.android`, `examples/whisper.android.java`) | MIT | 54.9k / 2026-11-03 | Best accuracy per MB for multilingual; `ggml-tiny`/`base` handle Hindi and code-switching better than Vosk | 4-6 h: NDK + CMake build of the example lib, JNI, model in assets | Not a Maven artifact. Tiny/base on a mid-range phone ~1-3 s per 5 s chunk; Hindi often comes out translated or in Urdu script on tiny. D1 says do not start tonight |
| whisper_android (TFLite) | https://github.com/vilassn/whisper_android | MIT | 693 / 2026-03-18 | Prebuilt Whisper TFLite models + Java/native samples | 3-4 h | English-centric models, not maintained actively |
| Trelis/whisper-hinglish-preview | https://huggingface.co/Trelis/whisper-hinglish-preview | Apache-2.0 | 1.5k downloads | Whisper fine-tuned for Hinglish (Latin output) | Needs conversion to ggml or ONNX: 2-3 h extra | Size (2 safetensors shards, not tiny); post-demo |
| AI4Bharat IndicConformer / Vistaar | https://github.com/AI4Bharat/IndicConformerASR , https://github.com/AI4Bharat/vistaar | MIT | 106 / 2025-06; 95 / 2025-06 | Best Hindi WER in open models | NeMo, 600M params; not phone-ready without work | Post-demo |
| Laptop companion (already built) | `companion/listener.py` uses faster-whisper | MIT (faster-whisper) | n/a | Option C in D1 | 0 h | Needs laptop on speakerphone |

### Vosk drop-in for `AsrEngine` (load-bearing; verify on device)

```kotlin
// app/build.gradle.kts:  implementation("com.alphacephei:vosk-android:0.3.75")  (pulls JNA; add "net.java.dev.jna:jna:5.13.0@aar" if the AAR does not)
// Models: adb push vosk-model-small-hi-0.22 and vosk-model-small-en-in-0.4 to
//   /sdcard/Android/data/<applicationId>/files/   (no APK bloat; or ship in assets and use org.vosk.android.StorageService.unpack)
class VoskAsr(dir: java.io.File) : AsrEngine {
    override val name = "vosk (hi + en-in, offline)"
    private val models = listOf("vosk-model-small-en-in-0.4", "vosk-model-small-hi-0.22")
        .map { java.io.File(dir, it) }.filter { it.isDirectory }.map { org.vosk.Model(it.absolutePath) } // load off the main thread
    override suspend fun transcribe(pcm: ShortArray, sampleRateHz: Int): String? =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            models.mapNotNull { m ->
                org.vosk.Recognizer(m, sampleRateHz.toFloat()).use { r ->
                    r.acceptWaveForm(pcm, pcm.size)
                    org.json.JSONObject(r.finalResult).optString("text").takeIf { it.isNotBlank() }
                }
            }.joinToString(" ").ifBlank { null }
        }
}
```

Wire it in `Container.asr` when the model dirs exist, else keep `NoopAsr`. Running both models doubles CPU (~2x real-time on a mid phone is
typical for Vosk small, **unverified on our phone**); if slow, pick one model from a Settings toggle. `vosk-android` ships arm64-v8a,
armeabi-v7a, x86 and x86_64 libs, so it also runs on the emulator.

---

## 2. On-device small LLM or scam-text classifier

Our design already has the right answer for tomorrow: Ollama (laptop via `adb reverse`) when available, and the deterministic keyword floor
always. Do not add a new model path tonight. For after the demo:

| Candidate | URL | License | Stars / last push | What it gives us | Integration | Risk |
|---|---|---|---|---|---|---|
| **LiteRT-LM** (Google's successor to MediaPipe LLM Inference) | https://github.com/google-ai-edge/LiteRT-LM | Apache-2.0 | 6.8k / 2026-11-03 | Google Maven `com.google.ai.edge.litertlm:litertlm-android` (0.17.x / 0.18 listed). Kotlin `Engine` + `Conversation`, CPU/GPU | 3-5 h incl. 0.6-3 GB model push | MediaPipe `tasks-genai` (latest 0.10.35) is now **maintenance-only** per Google docs, so do not start there |
| Models for it | `litert-community/Qwen3-0.6B` (Apache-2.0, int4 `.litertlm`), `litert-community/gemma-4-E2B-it-litert-lm` (tagged Apache-2.0, 1.36M downloads), `litert-community/Gemma3-1B-IT` (Gemma terms, gated) | see left | updated 2026-11-02 | Qwen3-0.6B is the realistic phone size; Hindi ability is weak, rely on keyword floor | n/a | Latency on a mid-range phone likely > our timeout; measure in Pre-flight |
| Google AI Edge Gallery | https://github.com/google-ai-edge/gallery | Apache-2.0 | 25.2k / 2026-11-03 | Reference Android app showing LiteRT-LM model download, chat, benchmarks. Copy its model-loading code | reference | n/a |
| llama.cpp / SmolChat-Android | https://github.com/ggml-org/llama.cpp , https://github.com/shubham0204/SmolChat-Android | MIT / Apache-2.0 | 133k / 2026-11-03; 905 / 2026-11-01 | GGUF on Android; SmolChat wraps llama.cpp in a Kotlin module (`smollm`) you can copy | 4-6 h (NDK) | Same latency issue |
| MLC LLM | https://github.com/mlc-ai/mlc-llm | Apache-2.0 | 23.3k / 2026-10-30 | GPU-accelerated Android | 6 h+ | Heavy build |
| ONNX Runtime Android | https://github.com/microsoft/onnxruntime , Maven `com.microsoft.onnxruntime:onnxruntime-android:1.31.1` | MIT | 22.2k / 2026-11-03 | Runtime for any small classifier (and the spoof model in section 4) | 1 h for the runtime; tokenizer is the work | n/a |
| Ready text classifiers | `mrm8488/bert-tiny-finetuned-sms-spam-detection` (29k downloads, **no license tag**), ONNX copy `onnx-community/bert-tiny-finetuned-sms-spam-detection-ONNX`; `Horizon-Labs/phishing-scam-detector-base` (Apache-2.0); `mradermacher/scamshield-0.5b-GGUF` (Apache-2.0) | mixed | | English SMS spam, not coercion calls | 2-3 h | Domain mismatch; unclear license on the most popular one. **Not worth it** vs our keyword analyst |

---

## 3. Hindi / Hinglish scam datasets (for eval and keyword mining)

| Dataset | URL | License | Size / updated | Use |
|---|---|---|---|---|
| Indian Cyber Scam PhoneCall Hinglish | https://huggingface.co/datasets/ysangam/Indian_Cyber_Scam_PhoneCall_Hinglish_Dataset | Apache-2.0 | 10k-100k rows / 2026-06-08 | Digital arrest, police/customs/FedEx, KYC, OTP theft transcripts. **Best match to our domain.** Many identical forks exist on HF (copies), so provenance is unclear; treat as synthetic |
| Indian Multilingual Scam Message | https://huggingface.co/datasets/karanverma19/Indian_Multilingual_Scam_Message_Dataset | Apache-2.0 | 120 rows / 2026-04-10 | Hindi (Devanagari), Hinglish, English with a `reason` column; good for a quick precision check |
| Indian scam SMS synthetic audited | https://huggingface.co/datasets/Ridham115/indian-scam-sms-synthetic-audited | CC-BY-4.0 | / 2026-09-26 | SMS-side signals (OTP, KYC) |
| scam-dialogue / multi-agent-scam-conversation | https://huggingface.co/datasets/BothBosu/scam-dialogue , https://huggingface.co/datasets/BothBosu/multi-agent-scam-conversation | Apache-2.0 | ~1-2k dialogues / 2024-06 | English, US-style (SSN, refund, tech support) but includes **non-scam** calls: good false-positive check |
| phone-scam-detection-synthetic | https://huggingface.co/datasets/shakeleoatmeal/phone-scam-detection-synthetic | MIT | 1,800 dialogues / 2025-10 | Llama-8B generated, labeled subtlety levels |
| SynthScamCall | https://github.com/WsgDcb/SynthCallScam_Dataset | none stated | / 2025-09 | Paper dataset (LLM-augmented expert rules). Reference only, no license |
| scam-nonscam-youtube-calls | https://huggingface.co/datasets/sumitkant/scam-nonscam-youtube-calls | none stated | small | Real-ish audio-derived; license unknown, do not redistribute |
| Hinglish ASR test audio | `addyo07/noisy-hinglish-asr` (Apache-2.0), `dianavdavidson/MUCS-Hinglish` (CC-BY-4.0) | | | To test Vosk/Whisper Hinglish WER offline |

Tonight's 1-hour job: a throwaway script that loads one of the first two, maps each row to a transcript-only scenario, and reports
KeywordAnalyst recall/false positives. Put the number in the pitch as "held-out, not tuned on".

---

## 4. Synthetic-voice / deepfake detection (stretch, defer)

| Candidate | URL | License | Stars / downloads | What it gives us | Integration | Risk |
|---|---|---|---|---|---|---|
| MelodyMachine/Deepfake-audio-detection-V2 | https://huggingface.co/MelodyMachine/Deepfake-audio-detection-V2 | Apache-2.0 | 8.6k downloads, 2024-06 | wav2vec2-base audio classifier (real/fake) | `optimum-cli export onnx` + int8 quantize (~95 MB), run with onnxruntime-android: 3-4 h | Trained on clean English TTS; phone-mic + codec audio will produce noise. Spec already says weak signal; weight stays 0 by default |
| mo-thecreator/Deepfake-audio-detection, Gustking/wav2vec2-large-xlsr-deepfake-audio-classification | HF | Apache-2.0 | 19k / 11k downloads | Same family; XLSR is multilingual but large (300M) | same | Size |
| clovaai/aasist | https://github.com/clovaai/aasist | MIT | 311 / 2023-06 code | Small (~300k params) ASVspoof SOTA-era model, exportable to ONNX | 3-4 h | Trained on ASVspoof 2019 LA; generalization to 2026 voice clones poor |
| Yaselley/deepfense-framework | https://github.com/Yaselley/deepfense-framework | Apache-2.0 | 34 / 2026-10 | Training framework, many backbones | research | Not Android-ready |
| sherpa-onnx audio tagging | (see section 1) | Apache-2.0 | | Could host a spoof model in the same runtime | | |

Recommendation: do not build. If asked: "plug point exists (`VoiceSpoofScore`), weight 0 until we have a model validated on phone-call audio."

---

## 5. Accessibility payment-screen parsing (GPay / PhonePe / Paytm)

No maintained, licensed open-source parser for UPI app screens exists. Found only hackathon repos **without a license** (all rights reserved,
so read for ideas, do not copy code):

| Repo | URL | License | Stars / last push | Notes |
|---|---|---|---|---|
| UPIShield | https://github.com/renuka747/UPIShield | none | 0 / 2026-08-02 | Kotlin accessibility service + ONNX random forest; extraction is plain text regex (`₹` amount, `x@y` handle), package `com.phonepe.app`. Same approach as our `ScreenParser` |
| PayShield | https://github.com/rishabhmaurya-04/PayShield | none | 6 / 2026-08-02 | Java, accessibility + overlay + Firebase report lookup |

What to do instead: keep our text-based `ScreenParser`, use the Debug screen's "copy accessibility dump" on the demo phone with each payment
app, and tune. **Risk found:** a Feb 2026 user report says **Paytm blocks use entirely when any accessibility service is enabled**; the Unhook
project also notes a growing number of UPI/banking apps refuse to pay with third-party accessibility on. Not confirmed for GPay/PhonePe.
**Test the exact demo payment app with Kavach's accessibility service ON before the demo**, and pick an app that still works.

---

## 6. Call state and call audio on Android 10+

| Item | Source | Notes |
|---|---|---|
| Call state | Platform `TelephonyCallback` (API 31+) / `PhoneStateListener` | Already in `sense/Receivers.kt` |
| Incoming number without READ_CALL_LOG | Platform `CallScreeningService` + `RoleManager.ROLE_CALL_SCREENING` (API 29+); reference: https://github.com/aj3423/SpamBlocker (MIT, 1.95k stars, pushed 2026-11-03, Android 10+) and https://github.com/adamff-dev/spam-call-blocker-app (**GPL-3.0**, reference only) | User grants the role once in Setup. Gives `Call.Details.handle` for incoming calls; check contacts ourselves. 1-2 h |
| Call audio | `VOICE_CALL`/`VOICE_DOWNLINK` sources are blocked for third-party apps since Android 10 | Workarounds that do not need root: speakerphone + `MIC` / `VOICE_RECOGNITION` / `UNPROCESSED` source (our Day-0 test picks the one with signal), or the laptop companion. Some OEMs silence the mic during calls; that is exactly what T2 checks |
| BCR (Basic Call Recorder) | https://github.com/chenxiaolong/BCR | **GPL-3.0**, 2.96k stars, pushed 2026-10-15 | Needs root / system-app install. Do not use; cite only as "the only reliable in-call capture needs root" |

---

## 7. Overlay / pause UI, guardian SMS, complaint report

Already built (🟡). Nothing worth importing. Notes:
- Compose in a `WindowManager` overlay needs a `ViewTreeLifecycleOwner` + `SavedStateRegistryOwner` set on the `ComposeView`; if the overlay
  crashes with "ViewTreeLifecycleOwner not found", that is why.
- **cybercrime.gov.in has no public API** and Chakshu (Sanchar Saathi) has no submission API. Correct pattern (what we do): generate the
  PDF/text, open `https://cybercrime.gov.in` in the browser and an `ACTION_DIAL` intent for `1930`. For suspected fraud calls/SMS, link
  `https://sancharsaathi.gov.in` (Chakshu).
- Guardian SMS: platform `SmsManager`; no library needed.

---

## 8. UPI QR / URL checkers and phone-number blocklists (India)

| Item | URL | License | Notes |
|---|---|---|---|
| ZXing core / zxing-android-embedded | https://github.com/zxing/zxing , https://github.com/journeyapps/zxing-android-embedded | Apache-2.0 | core: 34k stars, pushed 2026-11-02. embedded: 5.9k, last push 2024-08 (stable, not active). Decode QR offline |
| ML Kit barcode scanning | `com.google.mlkit:barcode-scanning` | Google ML Kit terms (free, not OSS) | Faster to integrate than ZXing if a camera screen is wanted |
| UPI deep-link parsing | NPCI `upi://pay?pa=&pn=&am=&cu=INR&tn=` | n/a | `Uri.parse(...).getQueryParameter("pa")`. Rules: amount present on a "you will receive money" QR, `pa` handle mismatching `pn`, collect/`upi://collect` style. No library needed |
| UPI-QR-scam-detector | https://github.com/aryansingh0704412-arch/UPI-QR-scam-detector | none | Django toy; skip |
| India fraud-number blocklist | none found | | No open GitHub/HF list of Indian fraud numbers. Chakshu and TRAI publish counts, not lists. Truecaller is proprietary |
| India mobile prefix dataset | https://github.com/hstsethi/in-mob-prefix | CC-BY-4.0 | 4-digit prefix to state/operator. 7 stars, pushed 2025-11. Attribution required |
| Discord-AntiScam/scam-links | https://github.com/Discord-AntiScam/scam-links | none | 24k scam URLs, gaming-centric; not India UPI. Skip |

---

## 9. Emulator testing recipe (no phone needed)

All built into the Android SDK emulator (works with an x86_64 Google APIs image, API 34):

```bash
# simulate an unknown incoming call, answer it, then hang up later
adb emu gsm call 9876543210
adb emu gsm accept 9876543210
adb emu gsm cancel 9876543210
# simulate an SMS (OTP / guardian HOLD reply)
adb emu sms send 9876500000 "HOLD"
adb emu sms send VK-HDFCBK "OTP 482913 for Rs 49,999 to sharma.verify@okicici. Do not share."
# open a payment intent as if a pay screen started
adb shell am start -a android.intent.action.VIEW -d "upi://pay?pa=sharma.verify@okicici&pn=Sharma&am=49999&cu=INR"
# grant overlay / notification-listener / accessibility from the shell (faster than Settings taps)
adb shell appops set <applicationId> SYSTEM_ALERT_WINDOW allow
adb shell settings put secure enabled_accessibility_services <applicationId>/<full.service.ClassName>
adb shell cmd notification allow_listener <applicationId>/<full.listener.ClassName>
```

Mic audio injection:
- Emulator: Extended controls > Microphone > **"Virtual microphone uses host audio input"** (off by default), or start with
  `emulator -avd <name> -allow-host-audio`.
- Windows host: install a virtual audio cable (VB-Audio "VB-CABLE", donationware, not OSS), set "CABLE Output" as the default
  recording device, and play the scam script WAV into "CABLE Input". The emulator then hears the WAV as mic audio, so Vosk/the Day-0
  test can be exercised end-to-end.
- No virtual cable: play the WAV through laptop speakers near the laptop mic (works, noisier).
- The emulator has no real telephony audio path, so this tests ASR + engine, not the in-call mic restriction (T2 still needs the phone).

---

## 10. Existing projects doing the same thing (for the "related work" answer)

| Project | URL | License | Status | Difference from Kavach |
|---|---|---|---|---|
| Google Scam Detection (Pixel, Gemini Nano) | product, not OSS | n/a | Pixel 9+ only in India | Covered in `docs/RESEARCH_USPS.md` |
| ScamGuard / ScamCall Guardian | https://github.com/JPisOP007/ScamGuard | none | 1 star, 2026-08 | Python, Whisper -> MuRIL -> LLaMA 3.3 (cloud), Hindi/Hinglish/Tamil transcripts; no payment context, not on-device |
| Scam-Call-Transcript-Detector | https://github.com/bhuvaneshsenthil03-ctrl/Scam-Call-Transcript-Detector | none | 45 stars, 2026-05 | Web app keyword scoring over pasted transcripts |
| VaniRakshak-AI | https://github.com/Tarkash-Labs/VaniRakshak-AI | none | idea phase (OmniKon 2026) | Vernacular phishing + digital arrest, no code of note |
| digital-arrest-risk-scoring-system | https://github.com/mukulsar143/digital-arrest-risk-scoring-system | none | 1 star | Rules + NLP, web |
| UPIShield / PayShield | section 5 | none | small | Payment screen only, no call/coercion context |
| SpamBlocker | https://github.com/aj3423/SpamBlocker | MIT | 1.95k, active | Call/SMS filtering, no payment context; reuse its call-screening pattern |
| Korean_Voice_Phishing_Detection | https://github.com/selfcontrol7/Korean_Voice_Phishing_Detection | other | 23 | Academic precedent for transcript-based vishing detection (Korea) |

Positioning line: every open project found does **one** of (transcript scoring, payment-screen scoring, number blocking). None fuses live
call + language + payment screen on-device and intervenes before the PIN. GitHub search for "scam call detection android" returned no
results at all.

---

## 11. Do not use (license or fit)

- **BCR**, **adamff-dev/spam-call-blocker-app**: GPL-3.0 (would force our app to GPL); BCR needs root anyway.
- Unlicensed hackathon repos (UPIShield, PayShield, ScamGuard): no license means no reuse of code.
- `mrm8488/bert-tiny...sms-spam`: no license tag, wrong domain.
- MediaPipe `tasks-genai` for new LLM work: maintenance-only, use LiteRT-LM.
- `Gemma3-1B-IT` litert build: gated, Gemma terms; prefer Apache-2.0 Qwen3-0.6B if an on-phone model is ever needed.

## 12. Risks summary

1. In-call mic audio may be silent on the demo phone regardless of ASR engine (T2). Vosk only helps if T2 shows signal.
2. Payment apps may refuse to work with an accessibility service enabled (confirmed report for Paytm). Test the demo app.
3. Datasets are synthetic, of unclear provenance, and some HF entries are copies of each other. Present results as indicative.
4. On-phone LLM and spoof models are unmeasured on our hardware; keep them out of the demo path.
5. TRAI 160-series rule: verify the current directive wording before stating it as fact.

## Sources

- GitHub API metadata (2026-11-03) for every repo listed; Maven Central `com.alphacephei:vosk-android`, `com.microsoft.onnxruntime:onnxruntime-android`; Google Maven `com.google.mediapipe`, `com.google.ai.edge.litertlm`.
- Vosk models and WER: https://alphacephei.com/vosk/models
- sherpa-onnx Android build: https://k2-fsa.github.io/sherpa/onnx/android/build-sherpa-onnx.html ; Indic Conformer ONNX: https://huggingface.co/meetsync/indic-conformer-onnx-sherpa ; https://huggingface.co/ippocode/indic-asr-onnx
- MediaPipe LLM Inference maintenance-only: https://developers.google.com/edge/mediapipe/solutions/genai/llm_inference/android ; https://dev.to/charleshartmann/mediapipe-llm-inference-is-maintenance-only-start-on-litert-lm-and-add-this-abi-check-48h2
- Emulator mic: https://developer.android.com/studio/run/emulator-extended-controls ; console commands: https://developer.android.com/studio/run/emulator-console
- Accessibility blocking: https://github.com/rogerantony-dev/wilt/releases/tag/v1.7.0 (Unhook note), Reddit snapshot https://reddit.sentinel-team.org/posts/1r8rmoc/snapshots/2026-02-21T01%3A00%3A03.405292Z
- Chakshu / Sanchar Saathi: https://www.angelone.in/news/economy/chakshu-under-sanchar-saathi-reports-fraud-39-43-lakh-mobiles-disconnected-over-1-000-crore-loss-prevented
- India prefix dataset: https://awesome.ecosyste.ms/projects/github.com%2Fhstsethi%2Fin-mob-prefix
- Hugging Face API for all model/dataset licenses and download counts.
