# Vyze — Offline AI Vision Assistant

![Kotlin](https://img.shields.io/badge/Kotlin-2.3.0-blue?logo=kotlin)
![Gemma 4](https://img.shields.io/badge/Gemma-4%20E2B-orange)
![ML Kit](https://img.shields.io/badge/ML%20Kit-OCR-red)
![LiteRT-LM](https://img.shields.io/badge/LiteRT--LM-0.16.1-green)
![Min SDK](https://img.shields.io/badge/Min%20SDK-26-orange)
![License](https://img.shields.io/badge/License-Apache%202.0-blue)

> **Vyze** is a fully offline AI vision assistant for visually impaired users. Powered by Gemma 4 E2B (2B parameter multimodal model) running on-device via Google's LiteRT-LM framework. Zero internet dependency. Zero subscription costs. Works anywhere.

---

## What Vyze Does

Vyze speaks what the camera sees — instantly, privately, offline.

- **Scene Description** — "A wooden chair is directly ahead, about 3 steps away"
- **Text Reading** — Reads labels, signs, prescriptions, packaging and menus aloud — in whole sentences, in the text's original language, to the end
- **Product & Brand Naming** — Identifies packaged goods by brand and product type as printed ("Maggi instant noodle packet"), then reads the rest
- **Currency Reading** — Reads the value on banknotes and coins aloud, with a strict never-guess rule
- **Bank Card Identification** — Identifies bank name and card type (debit/credit/ATM) from logos and printed text, with a strict never-guess rule
- **Medicine Lookup** — Cross-references OCR against a local drug database to answer "what medicine is this?"
- **Multi-Language** — Auto-detects your spoken language and responds in it (English, Malay, Chinese, and more)
- **Text-Only Q&A** — Answers general-knowledge questions ("what is paracetamol used for?") without the camera
- **Barcode & QR Detection** — ML Kit barcode scanning runs alongside OCR on every text query; retail products and banknotes are identified by their barcode and logged to scan history
- **Conversational Follow-ups** — "What is this?" → "A red Maggi packet" → "What about the one behind it?" — recent voice exchanges are remembered so pronouns resolve and answers stay conversational
- **Auto-Learned Brevity** — Interrupt answers repeatedly and Vyze quietly learns to answer in fewer sentences; let answers play and it relaxes again. No settings, no UI
- **Gesture Map** — Single tap: look (grid-tagged) · Double tap: ask by voice · Long press: light check · Triple tap: color · Triple tap + hold: SOS
- **Hands-Free Voice Sessions** — After a double-tap question the mic stays open for follow-ups; pick your voice by voice ("voice settings")
- **Noisy-Room Rescue** — When the recognizer gets lost in a crowd, Gemma's own audio encoder transcribes your repeat, fully offline. Malay/Chinese first queries on English phones are retried automatically in ms-MY/zh-CN before giving up
- **Precise Code Reading** — Vehicle plates, serial and reference numbers are spoken character by character ("Q L B, three four six nine") — never as a quantity
- **Voice Bug Reporting** — Say "I want to make a report" to file issues hands-free
- **Audio** — Speaks through the media stream at exactly your phone's volume, so the hardware volume buttons work normally

---

## Architecture

```
Voice Input → SpeechRecognizer → Language Detection
    │                                  │
    ▼                                  ▼
Camera Frame ──→ ML Kit OCR ──→ DynamicPromptBuilder
    │            (fast text)        │
    │                              ▼
    └──────────────────→ Gemma 4 E2B (LiteRT-LM)
                              │
                              ▼
                        Sentence Buffer → TTS → User
```

### Core Components

| Component | File | Responsibility |
|---|---|---|
| **VlmEngineManager** | `core/VlmEngineManager.kt` | Gemma 4 E2B engine lifecycle, NPU/GPU fallback, inference |
| **VyzeCoreController** | `core/VyzeCoreController.kt` | Pipeline orchestrator, session isolation, sentence streaming |
| **DynamicPromptBuilder** | `core/DynamicPromptBuilder.kt` | Intent-based prompt construction, language mirroring |
| **OcrHelper** | `vision/OcrHelper.kt` | ML Kit on-device OCR (Latin + Chinese) |
| **TTSManager** | `speech/TTSManager.kt` | Platform TextToSpeech (Google TTS engine preferred), utterance tracking, voice switching, prosody & pronunciation smoothing |
| **BarcodeHelper** | `vision/BarcodeHelper.kt` | ML Kit on-device 1D/2D barcode detection (EAN/UPC/QR/Data Matrix) |
| **PreferenceLearner** | `memory/PreferenceLearner.kt` | Silent behavioral adaptation — learns answer brevity from speech-active interrupts |
| **CameraSetupDelegate** | `ui/delegates/CameraSetupDelegate.kt` | CameraX frame extraction, snapshot capture |
| **CameraFragment** | `ui/fragments/CameraFragment.kt` | UI, speech callbacks, auto-snapshot loop |
| **MemoryRepository** | `memory/MemoryRepository.kt` | Vector similarity search, adaptive intelligence |
| **AudioCapture** | `device/AudioCapture.kt` | 16 kHz mono float32 recorder for the model-native ASR rescue |
| **MainActivity** | `MainActivity.kt` | Speech recognition, lifecycle, TTS orchestration |
| **ReportManager** | `speech/ReportManager.kt` | Voice-driven bug reporting via email |

### Project structure

```text
app/src/main/java/com/vyze/app/
├── core/        Pipeline orchestration: controller, prompt builder, VLM engine
├── vision/      Perception: OCR, barcode, color analysis, scene embeddings
├── speech/      Voice output: TTS management, report capture
├── device/      Hardware interaction: gestures, haptics, flashlight, mic capture
├── ui/          Fragments, delegates, views, viewmodels
├── memory/      Adaptive intelligence: preference learning, scene memory
├── data/        Room database: DAOs, entities, repositories
└── util/        Crash logging
```

---

## Key Features

### Fully Offline
All inference runs on-device — including the TTS engine. No cloud APIs, no data uploads, no subscriptions, no Google TTS dependency. Works underground, on planes, in rural areas.

### Fast Response Times
- **OCR fast-path**: ~150ms (ML Kit only — skips Gemma when confidence ≥ 85%)
- **OCR + Gemma**: ~300-500ms (ML Kit text + Gemma interpretation)
- **Scene queries**: ~1.5-2s (Gemma with trimmed prompts)
- **First audio**: within ~3 characters of generated text (sentence-buffered streaming)

### Adaptive Memory
After every scan Vyze stores a lightweight visual fingerprint plus the scene description in a local Room database. Before analyzing a new frame it searches recent fingerprints; when a frame strongly matches a scan from the last 24 hours, the prior description is injected into the prompt as context — so the answer can open with "this looks like the box you scanned earlier" instead of describing from zero. Memory is context, never a substitute: every scan still analyzes the live frame fresh.

### Conversational Follow-ups (Dialogue Memory)
During hands-free voice sessions Vyze remembers the last two exchanges and injects them into follow-up prompts, so pronouns resolve across turns ("what about the one behind it?") and the model answers the follow-up directly instead of re-describing the scene. Dialogue memory expires when the conversation window closes or after 60 s of silence, is capped at ~150 tokens, and is skipped entirely for tap, continuous, currency and bank-card reads. Follow-up answers also generate faster — a shorter answer is fewer tokens to synthesize on low-end phones.

### Auto-Learned Brevity (Preference Learning)
Vyze silently adapts answer length to observed behavior: a capture triggered while the previous answer is still being spoken counts as an interruption. Three interrupts within five minutes shifts to concise answers, nine to one-sentence answers — enforced both in the prompt and by tightening the output-token budget (fewer tokens = faster generation). Every answer that plays to completion erodes one step of interrupt evidence. Upward adaptation persists in the memory database; decay never writes. Continuous auto-captures never count as interruptions — leaning back and listening is not impatience.

### Scan History
OCR text, barcodes/QR, currency reads, bank cards and color analyses are persisted to a Room-backed scan history at detection time — including on the OCR fast-path and OCR-fallback paths.

### Multi-Language
Automatically detects your spoken language via SpeechRecognizer and mirrors it to both Gemma's output and the TTS voice. Supports English, Malay, Chinese, and any language with an installed TTS voice pack. No hardcoded language lists — uses Android's dynamic Locale resolution.

### Hybrid OCR Pipeline
ML Kit handles fast text extraction (80-150ms), Gemma handles interpretation and context. OCR fast-path skips Gemma entirely when ML Kit confidence ≥ 85%, reducing latency to ~150ms for clear text.

### Dynamic Aspect Ratio
Gemma 4 handles dynamic aspect ratios natively via its vision token budget. Bitmaps are proportionally downscaled without rigid center-cropping, preserving spatial accuracy.

### Sentence-Buffered TTS Streaming
Tokens stream directly to TTS as they generate. First audio fires after just a few characters — no waiting for full response. Post-first flushes coalesce ~2 sentences into one utterance (eliminating the per-utterance hardware seam between sentences), and the optional first-fragment fast-start joins its continuation with listing intonation instead of a full-stop seam.

### Continuous Mode
Point-and-describe loop with thermal safety — automatically throttles capture interval from 4s to 8s after 3 minutes of continuous use to prevent SoC throttling. Each auto-capture is first gated by a ~1 ms visual-similarity check against the last spoken scene: unchanged scenes skip the multi-second Gemma run entirely (huge battery and latency saving), while any real change triggers a fresh description.

### Voice Bug Reporting
Say "I want to make a report" (English/Malay/Chinese) to enter report mode. Speak your issue, and the app saves a device-annotated report file and pre-fills an email — one tap to send.

### Voice Settings by Voice
Double tap, then say "voice settings" to audition every installed voice hands-free — "next" to hear the next voice, "use this" to keep it, "cancel" to stop. The choice persists per language. The double-tap cue stays a short "Listening." so it never delays the mic or nags.

### Audio Focus & TalkBack
Vyze holds audio focus for the whole session and speaks through the **media stream** at exactly the phone's volume, so the hardware volume buttons keep working and every utterance is stable. Android does not allow apps to duck or pause TalkBack, so Vyze detects TalkBack on launch, speaks a one-time advisory, and offers a guided voice command ("open accessibility settings") that takes the user straight to the TalkBack toggle — then re-checks and confirms on return.

### Text-Only Q&A (no camera)
Questions that don't reference the visual scene — "what is paracetamol used for?", "bagaimana cara mengikat tali?" — are answered by Gemma's text decoder alone: no camera capture, no image inference. Faster and cheaper, and it works without pointing the phone at anything. Any question mentioning "this / here / in front of me" is conservatively routed to the camera instead, so a pointing question is never stolen.

### Noisy-Room Rescue (model-native ASR)
When Android's recognizer gives up (no match, timeout, audio error — the classic "lost in a room full of people" case), Vyze speaks "Please say that again", records the repeat via `AudioCapture` (16 kHz mono float32 PCM), and transcribes it with Gemma 4 E2B's built-in audio encoder — fully offline, no Google services, one attempt per session, no beep loops.

### Reading Whole Panels
Tap or ask about dense packaging and the full text is read to the end: an adaptive output budget is sized to the OCR text actually found (up to ~800 words), the inference watchdog re-arms while tokens are still flowing so long reads are never cut off mid-way, and the prompt forbids letter-by-letter spelling and premature stops. Brand pronunciation is corrected before synthesis ("Maggi" is spoken MAY-ghee, not MAY-jee).

### Offline TTS
Vyze speaks through the Android platform TextToSpeech engine, preferring the Google TTS engine for voice quality and language coverage (system-default fallback), with fully on-device voice packs — no network, no subscriptions. Queue-based speech with barge-in support: a tap or new voice query immediately interrupts any active utterance.

---

## Hardware Acceleration

Vyze uses a multi-backend fallback chain:

1. **NPU** (Neural Processing Unit) — preferred for lower power draw on devices with dedicated AI accelerators
2. **GPU** (OpenCL/Vulkan) — universal fallback for all ARM64 devices
3. **CPU** — not supported for Gemma 4 E2B (model too large)

GPU kernels are pre-compiled during warm-up to eliminate first-inference cold-start latency.

### Mid-Tier Device Support
- Pre-flight RAM check rejects devices with insufficient memory (prevents OOM crashes)
- `ActivityManager.isLowRamDevice()` detection adjusts timeouts for constrained devices
- Runtime memory monitoring during inference with 400MB pressure warning threshold

---

## Tech Stack

| Component | Technology |
|---|---|
| **VLM Engine** | Gemma 4 E2B (2.59 GB, multimodal) via LiteRT-LM 0.16.1 |
| **OCR** | Google ML Kit Text Recognition (Latin + Chinese) |
| **Barcode** | Google ML Kit Barcode Scanning (1D + 2D) |
| **Camera** | CameraX 1.3.1 |
| **TTS** | Android platform TextToSpeech (Google TTS engine preferred), offline voice packs |
| **Speech Recognition** | Android SpeechRecognizer + Gemma 4 E2B native ASR fallback (offline) |
| **Database** | Room 2.7.0 (adaptive memory + vector search) |
| **Coroutines** | kotlinx-coroutines 1.10.1 |
| **Language** | Kotlin 2.3.0 |
| **Build** | Gradle 8.14, AGP 8.11 |

---

## Build

### Prerequisites

- Android Studio Hedgehog (2023.1.1) or later
- JDK 17+
- Android SDK 34
- ARM64 device (GPU/NPU required for Gemma 4 E2B)

### Debug Build

```bash
./gradlew.bat assembleDebug
```

### Model Setup

Vyze needs two sets of models:

1. **Gemma 4 E2B (VLM)** — push to your device:
   ```bash
   adb push gemma-4-E2B-it.litertlm /storage/emulated/0/Download/
   ```
   The app checks both `/storage/emulated/0/Download/` and the app-scoped external files directory.

2. **TTS** — no setup needed. Vyze uses the Android platform `TextToSpeech` engine (no bundled models since v1.2).

### Install

```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```

---

## Permissions

| Permission | Purpose |
|---|---|
| `CAMERA` | CameraX preview and frame analysis |
| `RECORD_AUDIO` | Voice command recognition |
| `READ_EXTERNAL_STORAGE` | Model file access (legacy) |
| `MANAGE_EXTERNAL_STORAGE` | Model file access (Android 11+) |

---

## Performance Optimizations

| Optimization | Impact |
|---|---|
| OCR fast-path (≥85% confidence) | ~150ms text reads — skips Gemma entirely |
| Sentence-buffered TTS streaming | First audio in <500ms |
| Punctuation-guided clause flushing | Natural pauses at commas/periods |
| Greedy decoding (topK=1, temp=0.1) | Fastest possible token generation |
| GPU warm-up (dummy 1x1 inference) | Eliminates first-inference cold start |
| Prompt trimming (~250 chars prefill) | ~60% faster prefill vs untrimmed |
| Dynamic aspect ratio (no center-crop) | Preserves spatial accuracy, faster encoding |
| JPEG quality 75 + pre-allocated buffer | 15-30ms faster frame encoding |
| ML Kit OCR pre-pass | 10-30x faster text extraction |
| Aggressive speech endpoints (300ms) | Faster voice query recognition |
| Session isolation (UUID gating) | Prevents stale results from previous queries |
| Watchdog timer (15s) | Prevents indefinite ANALYZING state |
| Scene-unchanged gating (continuous mode) | Skips Gemma when similarity ≥ 0.9 vs last spoken scene |
| Coalesced sentence flushing | ~2 sentences per utterance — removes inter-sentence seams |
| Continuous mode thermal safety (3min) | Prevents SoC throttling on mid-tier chips |
| NPU → GPU fallback chain | Optimal backend per device capability |
| Pre-flight RAM check | Prevents OOM crashes on constrained devices |

---

## License

Licensed under the Apache License, Version 2.0 — see [LICENSE](LICENSE) for details.

---

*Built for accessibility. Designed for independence. Runs everywhere.*
