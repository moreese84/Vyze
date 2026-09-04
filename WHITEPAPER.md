# Vyze — Technical Whitepaper

**On-Device, Voice-First Visual Assistance for Blind and Low-Vision Users**

| | |
|---|---|
| **Product** | Vyze (`com.vyze.app`) |
| **Version** | 1.0 |
| **Platform** | Android — minSdk 26 (Android 8.0), targetSdk 34, `arm64-v8a` |
| **Status** | Production candidate |

---

## 1. Abstract

Vyze is an Android application that gives blind and low-vision users real-time understanding of the world around them through a **fully offline, on-device multimodal large language model (VLM)**. A user points the phone's camera at a scene, taps once to "look" or double-taps to ask a spoken question, and Vyze describes what it sees in natural language — spoken aloud in the user's own language (English, Bahasa Melayu, or Chinese). Every byte of inference happens on the device: there is no network dependency, no account, and no image ever leaves the phone.

This document describes the system's architecture, the interaction model, the vision-language inference stack, the speech and audio subsystems, the on-device data layer, and the engineering measures taken for battery life, thermal control, privacy, and reliability.

---

## 2. Problem Statement

Visually impaired users navigate the world with assistive technologies that are often **remote-first** (they send images or live video to a human or cloud model), which carries four fundamental problems:

1. **Privacy** — scenes from a user's home and life are transmitted to a third party.
2. **Latency & connectivity** — cloud assistance fails in areas with poor coverage and adds round-trip delay to every query.
3. **Battery & cost** — always-on remote streaming drains the phone and depends on external infrastructure.
4. **Environmental noise** — hands-free voice interfaces that keep a microphone open in public spaces misfire on surrounding chatter, wasting GPU work and confusing the user.

A practical assistive device must therefore be **private, instant, offline, battery-conscious, and robust to noisy environments** — while remaining usable by a person who cannot see the screen.

---

## 3. Design Principles

The architecture is governed by five principles:

| Principle | Consequence |
|---|---|
| **100% on-device** | A 2.59 GB vision-language model runs locally via GPU acceleration. No network permissions are required for core function. |
| **Tap-first, voice on demand** | The microphone is **closed by default**. The user explicitly opens a voice session (double-tap) or triggers an action with a tap. This eliminates idle-listening battery drain and noise misfires. |
| **Audible-first UX** | All feedback is spoken or haptic; every screen and control is announced. Gestures — not screen elements — are the primary interaction surface. |
| **Language mirroring** | Vyze detects the language the user speaks (English, Bahasa Melayu, Chinese) and answers in that language, with per-language TTS voices. |
| **Graceful degradation** | The system has explicit fallback chains (NPU → GPU, OCR fast-path → full VLM, standard RAM → low-RAM profiles) so mid-range hardware still works. |

---

## 4. System Architecture

Vyze is a single-module Kotlin application organized into four layers. All components live in the process; there is no server component.

```
┌─────────────────────────────────────────────────────────────────────┐
│  UI LAYER                                                           │
│  PermissionsFragment → LoadingFragment → CameraFragment             │
│  └ GalleryFragment · TtsSettingsFragment (secondary screens)       │
├─────────────────────────────────────────────────────────────────────┤
│  ORCHESTRATION                                                      │
│  MainActivity ─── state machine + voice recognizer lifecycle       │
│  CameraFragment ─ gesture handling, conversation-window watchdog   │
│  VyzeCoreController ─ inference orchestration, watchdog, beeps     │
│  DynamicPromptBuilder ─ prompt assembly + few-shot examples        │
├─────────────────────────────────────────────────────────────────────┤
│  DELEGATES & MANAGERS                                               │
│  CameraSetupDelegate (CameraX preview/capture/luminance/torch)     │
│  GestureRouter + GestureDetectorHelper                             │
│  TTSManager · OcrHelper (ML Kit) · ColorAnalyzer                    │
│  FlashlightManager · HapticManager · ReportManager                 │
│  BatteryMonitor · AnnouncementCoordinator · BrandDotsView          │
├─────────────────────────────────────────────────────────────────────┤
│  INFERENCE & DATA                                                   │
│  VlmEngineManager (LiteRT-LM / Gemma 4 E2B)                        │
│  VyzeDatabase (Room): MedicineDao · MemoryDao · InteractionDao     │
│  ScanDao · ErrorLogDao · EmbeddingEngine · MemoryRepository        │
└─────────────────────────────────────────────────────────────────────┘
```

### 4.1 Application lifecycle

`VyzeApplication` is the composition root: it initializes the Room database (including pre-population of the medicine knowledge base), installs a **global uncaught-exception handler** that writes crash diagnostics to a local log file, prunes old error logs, and wires the shared `VyzeCoreController`.

On launch the user passes through `PermissionsFragment` (camera + microphone + notifications), then `LoadingFragment`, where the model file is located and the VLM engine is initialized with spoken progress announcements ("Waking up… Opening my eyes… Getting my vision ready…", plus download/load milestones at 25/50/75/100%). The destination is `CameraFragment`, the main screen.

### 4.2 Camera screen state machine

`VyzeCoreController` drives a small state machine that arbitrates all user actions:

```
            tap / voice query
IDLE ─────────────────────────▶ ANALYZING ──▶ SPEAKING ──▶ IDLE
  ▲                                                             │
  └─────────────────────────────────────────────────────────────┘
       (conversation window reopens mic ~600 ms after speech)
```

- **IDLE** — camera preview live; microphone **closed**; waiting for a gesture.
- **ANALYZING** — a frame is captured and the VLM is running; microphone closed.
- **SPEAKING** — the answer is streamed through TTS; microphone closed.
- After speech ends, if a *conversation window* is active, the mic silently reopens for follow-up questions.

---

## 5. Interaction Model

### 5.1 Gesture vocabulary

Vyze deliberately avoids a touchscreen UI for primary use. The entire gesture set is:

| Gesture | Action |
|---|---|
| **Single tap** | *Look* — describe the scene (tap position is passed as a spatial hint, e.g. "describe what is at this location"). |
| **Double tap** | *Ask* — open an on-demand voice session; the user speaks a question to the VLM. |
| **Long press** | *Light check* — announce whether the environment is dark and engage the flashlight. |
| **Triple tap** | *Color analysis* — announce the color of the object at the center of the frame. |
| **Triple tap + hold** | *Emergency SOS* — after a 1.5 s delay, open the dialer to **999**. |
| **Voice commands** | While a voice session is open: "read this", "voice settings", "report…", and language/gesture commands. |

Single-tap vs. double-tap disambiguation uses Android's standard ~250 ms gesture timeout; a legacy per-tap click fallback was deliberately removed so a double-tap cannot also trigger a scene analysis.

### 5.2 Microphone lifecycle — closed by default

The single most important power/noise decision: **Vyze never listens while idle.**

- The mic opens **only** inside an explicit voice session (started by double-tap or by report-mode capture).
- Sessions are self-closing: recognized speech ends them, as do silence, errors, the 12-second conversation deadline, and the noise guard.
- Previously the app auto-reopened the mic after every answer, onboarding step, and resume; all of those reopen paths were removed. Failed sessions (`NO_MATCH`, timeout, empty result) now end cleanly instead of looping — eliminating the "beep loop" and idle listening reported in early builds.

### 5.3 Conversation window

A single double-tap starts a **rolling hands-free conversation**:

1. Double-tap → "Listening. Ask your question." → the mic opens ~700 ms after the cue (the cue is spoken to completion first so the app never hears its own voice).
2. The user asks; the answer is spoken.
3. ~600 ms after the answer, the mic silently reopens — **no gesture required**.
4. Each accepted query resets a 12-second deadline (`CONVERSATION_WINDOW_MS`); the mic closes quietly when the user goes silent, returning to IDLE.

Any gesture barges in: single-tap *look* cancels the window, a second double-tap restarts a fresh session, and long-press runs the light check.

### 5.4 Noise robustness

Speech input passes a **three-stage chatter filter** in `MainActivity` before it can reach the model:

- **Confidence** — only recognitions above a confidence threshold are considered.
- **Length** — utterances too short or too long to be queries are dropped.
- **Stability** — the final text is compared against the last partial transcript (cleared per session) so stray or unstable recognitions are rejected.

If repeated ambient chatter is detected, Vyze announces *"The room is noisy. Tap or double tap to continue"* and pauses listening until an explicit gesture. The query-response beep uses **adaptive backoff**, and haptic patterns are one-shot (a historical bug passed repeat index `0` to the vibration API, causing endless vibration loops; patterns now use repeat `-1`).

---

## 6. Vision-Language Inference Stack

### 6.1 Model

| Parameter | Value |
|---|---|
| **Model** | Gemma 4 E2B (`gemma-4-E2B-it.litertlm`) |
| **File size** | 2.59 GB (local, user-downloaded to Downloads/ or app-scoped external storage) |
| **Format / runtime** | LiteRT-LM (`com.google.ai.edge.litertlm:litertlm-android:0.16.1`) |
| **Modality** | Multimodal — vision encoder + text decoder |
| **Acceleration** | **GPU only** (OpenCL/Vulkan); CPU inference unsupported at this size |
| **Peak memory** | ~3 GB (weights + KV-cache + image encoding) |

The model file is resolved from well-known local locations (public `Download/` directory first, app-scoped external files second) and validated with a size sanity check. The APK itself stays small; the model is a first-run acquisition step with spoken progress announcements.

### 6.2 Initialization & hardware fallback

- **Pre-flight RAM check** — before the engine is created, the app triggers a GC, measures available heap, and rejects early (with a clear spoken error) when free memory is below the threshold: 1200 MB on standard devices, 800 MB on devices flagged low-RAM. This prevents mid-inference OOM crashes.
- **NPU → GPU fallback chain** — the engine tries NPU acceleration first and falls back to GPU. In practice the NPU path is unreliable on some SoCs (e.g. MediaTek Dimensity) and is skipped; GPU execution is the production path.
- **Two-stage GPU warm-up** — the text decoder is warmed with a text-only message first (running a full image warm-up at init previously caused a `SIGSEGV` in the native LiteRT-LM JNI layer), then a 1×1 dummy image pre-compiles the OpenCL/Vulkan kernels so the first real inference is fast.

### 6.3 Prompt construction

Prompts follow Gemma 4's native turn format:

```
<|turn|>system [System Prompt]<|end_of_turn|>
<|turn|>user [User / Image Context]<|end_of_turn|>
<|turn|>model
```

Image patch tokens are bound natively by the LiteRT-LM engine when a `Bitmap` is passed — no literal image placeholder is required. A fixed system directive enforces the product's two behavioral contracts:

1. **Language mirroring** — respond only in the language requested, without cross-translating.
2. **No internal reasoning chains** — answers are direct and concise (the raw chain-of-thought of the base model is suppressed, both for latency and to avoid confusing narration).

`DynamicPromptBuilder` assembles query-specific context: language identification, recent memory recall (see §8.3), and **few-shot examples** for specialized tasks such as reading medicine packaging:

> *"Input: what medicine is this? Image shows Diclac Retard box. Output: Diclac Retard, diclofenac sodium 100mg. Take one tablet daily after meals."*

### 6.4 Image preprocessing

- Frames are **proportionally downscaled** to a max dimension of 512 px — no rigid center-crop, because Gemma 4 handles dynamic aspect ratios natively.
- JPEG compression at quality 75 for any intermediate encoding.
- All scaled bitmaps are **explicitly recycled** after inference to prevent native memory leaks.

### 6.5 Sampling & output controls

| Parameter | Value |
|---|---|
| Temperature | 0.1 |
| Top-K | 1 (greedy) |
| Top-P | 1.0 |
| Scene query tokens | 96 |
| Text/reading query tokens | 160 |

Greedy decoding minimizes latency and hallucinated verbosity. Token caps were raised from earlier tight values (48/96) after real-device testing showed responses being truncated mid-sentence.

### 6.6 Inference timeouts & watchdogs

- Per-inference timeout: **180 s** standard, **60 s** on low-RAM devices (fail fast instead of hanging through an OOM recovery).
- GPU warm-up timeout: 60 s.
- `VyzeCoreController` runs a **watchdog** that force-resets the engine if inference hangs, and monitors heap pressure during inference, logging warnings when available memory drops below a low-RAM threshold.

### 6.7 Streaming speech output

Answers are streamed token-by-token to TTS rather than spoken after full completion. Two details matter for perceived quality:

- **First-chunk flush policy** — the first chunk is only spoken once it forms a real phrase (2+ words or 8+ characters). An earlier implementation flushed as soon as the buffer held 1 word / 3 characters, which caused answers to begin with a stray spoken "A." from a leading model token.
- **Audio drain tail** — when generation completes, a silent tail is always queued so the TTS queue stays alive while the speaker drains, with a 600 ms drain-grace window so the final words are never clipped.

---

## 7. Speech & Audio Subsystems

### 7.1 Speech recognition

Voice input uses the platform `android.speech.SpeechRecognizer` with `RecognizerIntent`, configured for the active language (English, Malay, or Chinese). Recognition availability is checked before use. The recognizer is only ever instantiated for an explicit session — matching the closed-mic-by-default policy. Partial results feed the stability gate described in §5.4.

### 7.2 Text-to-speech (TTS)

Vyze forces the **Google TTS engine** (`com.google.android.tts`) for consistency of voice quality and language support (falling back to the system default engine only if Google TTS is absent), and speaks through a streaming queue managed by `TTSManager`.

Voice quality is a first-class concern because the perceived "human-ness" of the assistant is dominated by the installed voice pack, not by prosody settings:

- The app **auto-selects the best installed voice** for the active language, ordered by the engine's reported quality score.
- Users can **audition and choose** a voice per language, either from Voice Settings (Previous voice / Next voice / Use this voice buttons, each speaking a sample in the candidate voice) or **fully hands-free by voice**: double-tap → "voice settings" → hear each voice → say "next", "use this", or "cancel".
- A **weak-voice detector** inspects the engine's reported quality and flags voices below the high-quality threshold. On first run, right after "Vyze is ready…", Vyze may say: *"Your current voice sounds basic. A better voice is available for free from Google. Say yes to open the voice installer, or say skip."* "Yes" launches the system voice-data installer (`ACTION_INSTALL_TTS_DATA`); on return the app re-verifies and confirms. The prompt is one-time and skippable.
- Choices persist per language and are restored on every init and language switch.

### 7.3 Haptics & sound

`HapticManager` provides distinct one-shot patterns (gesture acknowledgment, warnings). `AnnouncementCoordinator` serializes spoken announcements so TTS output never overlaps itself, and any user gesture **barge-in** immediately silences active speech.

---

## 8. Specialized Visual Capabilities

### 8.1 OCR fast-path and medicine reading

Reading text through the full VLM is slow and error-prone, so Vyze implements a **hybrid reading pipeline**:

1. A spoken query is classified by reading keywords across English, Malay, and Chinese (e.g. "read", "label", "prescription", "baca", "读").
2. If it is a reading query, the frame goes through **ML Kit Text Recognition** (`TextRecognition` with Latin or Chinese recognizer options, chosen by the active language) — a dedicated on-device OCR model.
3. Extracted text is passed to a **confidence-scored OCR path**; high-confidence text is read out directly, often enriched by the **medicine knowledge base**: a Room database pre-populated at first launch (`MedicineDatabaseCallback`) with common medicines and their indications, dosages, and warnings. The VLM is used as the fallback or enrichment layer (prompted with few-shot examples) when OCR confidence is low or the query is a free-form question about the packaging.

The fast path cuts reading latency from seconds of VLM inference to milliseconds of OCR plus TTS.

### 8.2 Color analysis

`ColorAnalyzer` samples the center of the frame, converts to HSV, and classifies the dominant hue/saturation/value into a color name (localized to the active language). This is the triple-tap action and is pure CPU — near-instant with no model inference.

### 8.3 Light sensing & automatic flashlight

`CameraSetupDelegate` runs a **per-frame luminance analysis** on the CameraX `ImageAnalysis` stream and maintains a dark-environment flag. `FlashlightManager` engages the torch automatically using **dual-threshold hysteresis** (ON below ~35 lux, OFF above ~65 lux) so the torch does not rapidly flicker at the boundary. Long-press ("light check") announces the environment state and forces the torch if it has not yet flipped.

### 8.4 Scan history

Every recognized artifact — scene summaries, OCR text, barcodes, currency denominations, colors — is persisted through `ScanRepository`/`ScanDao` into a Room-backed history that the user can revisit via `GalleryFragment`.

### 8.5 Emergency SOS

Triple-tap-and-hold is a deliberately hard-to-trigger gesture that opens the dialer to the emergency number **999** after a 1.5-second confirm delay, giving the user time to abort.

---

## 9. On-Device Data, Memory & Personalization

All persistence is via **Room** (SQLite) inside the app sandbox. There is no cloud sync.

### 9.1 Database schema (VyzeDatabase)

| Entity | Purpose |
|---|---|
| `MedicineEntity` | Pre-populated medicine knowledge base (name, active ingredient, dose, warnings). |
| `MemoryDao` / `VyzeMemoryEntity` | Adaptive memory — preferences, environment observations, Q&A interactions, with pruning of entries older than a retention cutoff. |
| `InteractionDao` / `InteractionRecord` | Full Q&A history plus **serialized 256-float image embeddings** of the scene that prompted each interaction. |
| `ScanDao` / `ScanEntity` | Scan history by type (scene, OCR, barcode, currency, color). |
| `ErrorLogDao` / `ErrorLogEntity` | Local crash & diagnostic logs (pruned on launch). |

### 9.2 Lightweight embeddings

`EmbeddingEngine` produces a 256-float embedding from any frame by downscaling to a 16×16 grayscale grid and normalizing pixel intensities — ~1 ms on CPU, zero extra model weight. `MemoryRepository.findSimilar` ranks stored interactions by **cosine similarity** to the current scene and retrieves the top-k contextually similar past interactions, which `DynamicPromptBuilder` injects as "memory" so Vyze can adapt (e.g., recognizing that the user is in a room it has described before, or recalling previously read medicine). The authors document this as an intentional, lightweight proxy for visual similarity — not semantic understanding.

---

## 10. Multilingual Architecture

- **Recognition** — the speech recognizer is configured for the active UI language.
- **Detection** — spoken input is scored for Malay using a **multi-signal language detector** (replacing an earlier fragile fixed phrase list), and mirrored back for English/Chinese.
- **Mirroring** — the VLM system directive (§6.3) enforces answering in the language of the query.
- **TTS** — per-language voice selection with persisted user choice (§7.2).
- **Localization** — UI strings, announcements, gesture teaching, color names, and reading keywords all exist in three locales (`values`, `values-ms`, `values-zh`).

---

## 11. Battery & Thermal Engineering

The dominant energy consumer is not the microphone or the screen — it is a 2.5 GB GPU-resident model. Battery strategy therefore focuses on **minimizing GPU work and preventing idle subsystems**:

1. **Mic closed at IDLE** — the recognizer does not churn between actions (removes the second-largest continuous drain and the noise-trigger risk).
2. **On-demand inference** — the GPU is used only for explicit look/ask gestures; continuous auto-snapshot mode exists but throttles itself (4 s snapshot cadence, automatic throttling after 3 minutes of continuous use).
3. **Thermal monitoring** — a periodic thermal check (~every 8 s) plus watchdog-based recovery prevents sustained overheating from long inference runs.
4. **Battery monitor** — `BatteryMonitor` warns on low levels; it is deliberately decoupled from the inference core so UI policy cannot corrupt engine state.
5. **Clean exit** — the app kills its own process on exit, releasing the GPU context and native memory so the model does not linger in RAM after the user leaves.
6. **Failure fast-paths** — RAM pre-flight checks and shorter low-RAM inference timeouts prevent OOM death spirals that would otherwise waste battery retrying.

---

## 12. Privacy & Security

- **No network requirement** — core function (scene understanding, OCR, reading, color, light) runs with network access absent. There is no account and no telemetry.
- **Images never leave the device** — frames go from the camera directly into the local model, and are recycled after inference.
- **User-initiated reporting only** — the spoken "report…" command writes a local report file and, only if the user confirms, opens the user's email client to send it. Nothing is transmitted automatically.
- **Data sandboxing** — all memory, scan history, and logs live in app-private storage / Room.
- **Model integrity** — the model file is validated for size/location; the app documents an `adb push` path for sideloading, keeping the APK small while the model stays user-controlled.

---

## 13. Reliability Engineering

Production lessons are encoded directly in the code:

| Failure mode | Mitigation |
|---|---|
| Native SIGSEGV during init | Text-only warm-up before any image work; image warm-up removed |
| OOM during 2.5 GB model load | RAM pre-flight check + spoken early rejection |
| Hung inference | Per-inference timeouts + watchdog force-reset |
| Model file missing/corrupt | Multi-location resolution, size sanity check, human-readable setup instructions |
| Answer audio clipped | Always-queued drain tail + 600 ms grace |
| First chunk spoken alone | Phrase-aware first-chunk flush |
| Endless vibration loops | One-shot haptic patterns (repeat = -1) |
| Speech session loops ("beep loop") | Clean session termination on no-match/timeout; mic closed at IDLE |
| Background crashes | Global exception handler writing to `CrashLogFile`, logs pruned on launch |

---

## 14. Key Parameters Reference

| Constant | Value |
|---|---|
| `CONVERSATION_WINDOW_MS` | 12,000 ms |
| `CONVERSATION_WINDOW_TICK_MS` | 2,000 ms |
| `VOICE_SESSION_OPEN_DELAY_MS` | 700 ms |
| `FOLLOW_UP_OPEN_DELAY_MS` | 600 ms |
| `AUDIO_DRAIN_GRACE_MS` | 600 ms |
| Inference timeout (std / low-RAM) | 180 s / 60 s |
| GPU warm-up timeout | 60 s |
| Free-RAM preflight (std / low-RAM) | 1200 MB / 800 MB |
| Image max dimension | 512 px |
| Sampling | temp 0.1, top-K 1 |
| Scene / text token caps | 96 / 160 |
| Auto-torch hysteresis | ON < 35 lux, OFF > 65 lux |
| SOS dial delay | 1.5 s (number 999) |
| Continuous snapshot | 4 s cadence, throttled after 3 min |
| Thermal check interval | ~8 s |

---

## 15. Limitations & Future Work

**Known limitations**

- **Hardware floor** — requires a device with ~3 GB peak available RAM and a GPU-capable SoC; CPU-only and low-RAM devices are rejected early by design.
- **Model size** — the 2.59 GB model must be downloaded/staged outside the APK; first-run setup requires storage and a download.
- **Malay/Chinese VLM depth** — multilingual output depends on the underlying model's capability in those languages; OCR uses language-appropriate recognizers to compensate.
- **Embedding memory** is intentionally shallow (visual similarity only, no semantics) — a documented trade-off to keep it zero-cost.

**Future directions**

- **Conversation-window tuning** from real-device telemetry (window length is a single constant).
- A semantic image-embedding model (e.g., MediaPipe Image Embedder) to upgrade memory recall from visual to conceptual similarity.
- Bundled neural TTS voices (e.g., Kokoro/Piper-class, ~100–300 MB) for genuinely human speech in EN/ZH; Malay would continue on Google TTS until a quality open Malay voice exists.
- Continuous-mode refinements with stricter thermal budgeting for sustained outdoor use.

---

## 16. Dependency Snapshot

| Component | Version / detail |
|---|---|
| Language / toolchain | Kotlin 2.3.0, JVM 17 |
| Async | kotlinx-coroutines 1.10.1 |
| UI | AppCompat, Material, ConstraintLayout, Navigation (safe-args), DataBinding/ViewBinding |
| Camera | CameraX (core / camera2 / lifecycle / view) |
| VLM runtime | LiteRT-LM `litertlm-android:0.16.1` |
| Model | Gemma 4 E2B `gemma-4-E2B-it.litertlm` (2.59 GB) |
| OCR | ML Kit Text Recognition (Latin + Chinese options) |
| Persistence | Room + KSP |
| Speech | `android.speech.SpeechRecognizer` + Google TTS engine |
| ABIs | `arm64-v8a` |
