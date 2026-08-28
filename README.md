
# Vyze — Offline AI Vision Assistant

![Kotlin](https://img.shields.io/badge/Kotlin-2.1.0-blue?logo=kotlin)
![CameraX](https://img.shields.io/badge/CameraX-1.3.1-green)
![ML Kit](https://img.shields.io/badge/ML%20Kit-19.0.0-red)
![MediaPipe](https://img.shields.io/badge/MediaPipe-0.10.8-yellow)
![Min SDK](https://img.shields.io/badge/Min%20SDK-24-orange)
![License](https://img.shields.io/badge/License-Apache%202.0-blue)

> **Vyze** is a fully offline accessibility application for visually impaired users, built with Kotlin, CameraX, MediaPipe, and Google ML Kit. It provides real-time object detection, OCR text recognition, light-level sensing, and voice commands — all running on-device with zero internet dependency.

---

## Architecture

```
com.vyze.app
├── VyzeApplication.kt          ─── Global error handlers & initialization
├── LuminanceAnalyzer.kt        ─── Real-time light-level detection (Y-plane)
├── TTSManager.kt               ─── Text-to-Speech with safe lifecycle
├── HapticManager.kt            ─── Vibration patterns for gesture feedback
├── FlashlightManager.kt        ─── CameraX torch toggle + auto-torch
├── VoiceCommandManager.kt      ─── Speech recognition with intent parsing
├── TextRecognitionHelper.kt    ─── ML Kit OCR with bitmap recycling
├── OverlayView.kt              ─── Standard bounding-box overlay
├── HighContrastOverlayView.kt  ─── Low-vision yellow/black overlay + OCR text
├── ObjectDetectorHelper.kt     ─── YOLOv8n TFLite object detection
├── MainViewModel.kt            ─── Settings state management
├── MainActivity.kt             ─── Single-activity host
└── fragments/
    ├── CameraFragment.kt       ─── Camera pipeline orchestrator
    ├── GalleryFragment.kt      ─── Static image/video input
    └── PermissionsFragment.kt  ─── Runtime permission flow
```

### Core Managers

| Manager | Responsibility |
|---|---|
| **TTSManager** | Text-to-Speech with immediate and queued modes |
| **HapticManager** | 4 vibration patterns: tap, double-tap, long-press, warning |
| **FlashlightManager** | Torch toggle via CameraX + auto-torch on low light |
| **VoiceCommandManager** | Continuous speech recognition with keyword intent parsing |
| **TextRecognitionHelper** | ML Kit OCR with explicit bitmap recycling |
| **LuminanceAnalyzer** | Y-plane luminance extraction with dark-threshold callback |

### Gesture Controls

| Gesture | Action |
|---|---|
| **Single Tap** | Announce highest-confidence detected object |
| **Double Tap** | Trigger OCR text recognition |
| **Long Press** | Report light level + auto-torch toggle |
| **Voice "read/text/sign"** | Trigger OCR scan |
| **Voice "what/object/detect"** | Trigger object detection readout |
| **Voice "light/torch/dark"** | Trigger luminance check + torch toggle |

---

## Build

### Prerequisites

- Android Studio Hedgehog (2023.1.1) or later
- JDK 17+
- Android SDK 34

### Debug Build

```bash
./gradlew.bat assembleDebug
```

### Release Build

```bash
./gradlew.bat assembleRelease
```

> YOLOv8n model (`yolov8n.tflite`) is bundled in `app/src/main/assets/`. No download needed.

### Install

```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```

---

## Permissions

| Permission | Purpose |
|---|---|
| `CAMERA` | CameraX preview and analysis |
| `RECORD_AUDIO` | Voice command recognition |

---

## Tech Stack

- **Language:** Kotlin
- **Camera:** CameraX 1.3.1
- **Object Detection:** YOLOv8n (TFLite) — 601-class model on Open Images V7
- **OCR:** Google ML Kit Text Recognition 16.0.0 (bundled, offline)
- **Barcode:** Google ML Kit Barcode Scanning 17.2.0 (bundled, offline)
- **Face Detection:** Google ML Kit Face Detection 16.1.6 (bundled, offline)
- **LLM:** Gemini Nano via ML Kit GenAI Prompt API 1.0.0-beta1 (on-device, when AICore available)
- **UI:** Material Design, ViewBinding, DataBinding
- **Architecture:** Single-Activity, Fragment-based
- **Build:** Gradle 8.14, AGP 8.11, R8 minification

---

## License

Licensed under the Apache License, Version 2.0 — see [LICENSE](LICENSE) for details.

---

*Built for accessibility. Designed for independence.*