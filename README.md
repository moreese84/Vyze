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
- **Text Reading** — Reads labels, signs, prescriptions, menus aloud
- **Handwriting Recognition** — Reads sticky notes, handwritten documents
- **Multi-Language** — Auto-detects your spoken language and responds in it (English, Malay, Chinese, and more)
- **Continuous Mode** — Point-and-describe loop for hands-free exploration
- **Voice Control** — Ask questions naturally: "What medicine is this?"
- **Voice Bug Reporting** — Say "I want to make a report" to file issues hands-free
- **Permanent Audio Focus** — Suppresses TalkBack during session for clean audio output

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
| **VlmEngineManager** | `VlmEngineManager.kt` | Gemma 4 E2B engine lifecycle, NPU/GPU fallback, inference |
| **VyzeCoreController** | `VyzeCoreController.kt` | Pipeline orchestrator, session isolation, sentence streaming |
| **DynamicPromptBuilder** | `DynamicPromptBuilder.kt` | Intent-based prompt construction, language mirroring |
| **OcrHelper** | `OcrHelper.kt` | ML Kit on-device OCR (Latin + Chinese) |
| **TTSManager** | `TTSManager.kt` | Google neural TTS, utterance tracking, voice switching |
| **CameraSetupDelegate** | `CameraSetupDelegate.kt` | CameraX frame extraction, snapshot capture |
| **CameraFragment** | `CameraFragment.kt` | UI, speech callbacks, auto-snapshot loop |
| **MemoryRepository** | `MemoryRepository.kt` | Vector similarity search, adaptive intelligence |
| **MainActivity** | `MainActivity.kt` | Speech recognition, lifecycle, TTS orchestration |
| **ReportManager** | `ReportManager.kt` | Voice-driven bug reporting via email |

---

## Key Features

### Fully Offline
All inference runs on-device. No cloud APIs, no data uploads, no subscriptions. Works underground, on planes, in rural areas.

### Fast Response Times
- **OCR fast-path**: ~150ms (ML Kit only — skips Gemma when confidence ≥ 85%)
- **OCR + Gemma**: ~300-500ms (ML Kit text + Gemma interpretation)
- **Scene queries**: ~1.5-2s (Gemma with trimmed prompts)
- **First audio**: <3 characters (sentence-buffered streaming)

### Adaptive Memory
Vyze remembers your environment. Uses Room DB + vector similarity search to recall past observations and provide spatial continuity.

### Multi-Language
Automatically detects your spoken language via SpeechRecognizer and mirrors it to both Gemma's output and the TTS voice. Supports English, Malay, Chinese, and any language with an installed TTS voice pack. No hardcoded language lists — uses Android's dynamic Locale resolution.

### Hybrid OCR Pipeline
ML Kit handles fast text extraction (80-150ms), Gemma handles interpretation and context. OCR fast-path skips Gemma entirely when ML Kit confidence ≥ 85%, reducing latency to ~150ms for clear text.

### Dynamic Aspect Ratio
Gemma 4 handles dynamic aspect ratios natively via its vision token budget. Bitmaps are proportionally downscaled without rigid center-cropping, preserving spatial accuracy.

### Sentence-Buffered TTS Streaming
Tokens stream directly to TTS as they generate. First audio fires after just 3 characters — no waiting for full response. Punctuation in model output (commas, periods) triggers natural clause-level pauses.

### Continuous Mode
Point-and-describe loop with thermal safety — automatically throttles capture interval from 4s to 8s after 3 minutes of continuous use to prevent SoC throttling.

### Voice Bug Reporting
Say "I want to make a report" (English/Malay/Chinese) to enter report mode. Speak your issue, and the app saves a device-annotated report file and pre-fills an email — one tap to send.

### Permanent Audio Focus
Like Google Lens and Be My Eyes, Vyze holds `AUDIOFOCUS_GAIN` for the entire session — suppressing TalkBack and other accessibility audio during active use. TalkBack resumes cleanly when the app closes.

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
| **Camera** | CameraX 1.3.1 |
| **TTS** | Google Android TTS (neural voice selection) |
| **Speech Recognition** | Android SpeechRecognizer |
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

Push the Gemma 4 E2B model to your device:

```bash
adb push gemma-4-E2B-it.litertlm /storage/emulated/0/Download/
```

The app checks both `/storage/emulated/0/Download/` and the app-scoped external files directory.

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
| Continuous mode thermal safety (3min) | Prevents SoC throttling on mid-tier chips |
| NPU → GPU fallback chain | Optimal backend per device capability |
| Pre-flight RAM check | Prevents OOM crashes on constrained devices |

---

## License

Licensed under the Apache License, Version 2.0 — see [LICENSE](LICENSE) for details.

---

*Built for accessibility. Designed for independence. Runs everywhere.*
