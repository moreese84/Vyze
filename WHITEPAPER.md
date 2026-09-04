# Vyze — Technical Whitepaper

**On-Device, Voice-First Visual Assistance for Blind and Low-Vision Users**

| | |
|---|---|
| **Product** | Vyze (`com.vyze.app`) |
| **Version** | 1.2 |
| **Platform** | Android — minSdk 26 (Android 8.0), targetSdk 34, `arm64-v8a` |
| **Status** | Production candidate |
| **Revision** | September 2026 — reflects the on-device audio, text-Q&A, memory, and speech-quality subsystems added in v1.1–1.2 |

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
  │                    voice answer: reopen mic (~450 ms)      │
  │                    tap answer:   stay closed at IDLE       │
  └─────────────────────────────────────────────────────────────┘
```

- **IDLE** — camera preview live; microphone **closed**; waiting for a gesture.
- **ANALYZING** — a frame is captured and the VLM is running; microphone closed.
- **SPEAKING** — the answer is streamed through TTS; microphone closed.
- **After speech ends the exit path depends on how the query was asked.** Answers to *voice* questions (double-tap / hands-free follow-up) silently reopen the mic so the conversation continues without a gesture. Answers to *tap* queries return to IDLE with the mic firmly closed — the user chose touch input, so an open mic is never left running behind a tap. This split is what keeps "mic closed at IDLE" true while preserving hands-free conversation.

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
- Tap answers never reopen the mic (see §4.2) — an earlier build reopened after *every* answer, which left the recognizer running after taps; the next tap then cancelled a live recognizer and its cancellation error surfaced as phantom "I did not catch that" speech before every result. Reopen is now restricted to voice-originated answers, and stale recognizer callbacks from aborted sessions are dropped at the source (§7.1). Failed sessions (`NO_MATCH`, timeout, empty result) end cleanly instead of looping — eliminating the "beep loop" and idle listening reported in early builds.

### 5.3 Conversation window

A single double-tap starts a **rolling hands-free conversation**:

1. Double-tap → a short spoken cue "Listening." → the mic opens ~500 ms after the cue ends. The cue is deliberately one word: a longer cue (which historically taught "or say voice settings…" on every session) delayed the mic by ~5 s, so users who spoke early lost their query and heard nothing back.
2. The user asks; the answer is spoken as whole, flowing sentences (§6.7).
3. ~450 ms after a *voice* answer, the mic silently reopens — **no gesture required**.
4. Each accepted query resets a 12-second deadline (`CONVERSATION_WINDOW_MS`); the mic closes quietly when the user goes silent, returning to IDLE.

Any gesture barges in: single-tap *look* cancels the window and marks the tap answer as one that must not reopen the mic, a second double-tap restarts a fresh session, and long-press runs the light check.

### 5.4 Noise robustness

Speech input passes a **three-stage chatter filter** in `MainActivity` before it can reach the model:

- **Confidence** — only recognitions above a confidence threshold are considered.
- **Length** — utterances too short or too long to be queries are dropped.
- **Stability** — the final text is compared against the last partial transcript (cleared per session) so stray or unstable recognitions are rejected.

If repeated ambient chatter is detected, Vyze announces *"The room is noisy. Tap or double tap to continue"* and pauses listening until an explicit gesture. The query-response beep uses **adaptive backoff**, and haptic patterns are one-shot (a historical bug passed repeat index `0` to the vibration API, causing endless vibration loops; patterns now use repeat `-1`).

### 5.5 Speech-session hygiene

Because speech callbacks are asynchronous, a recognizer session that the user **aborted with a gesture** (tap, double-tap, audition, report) can still deliver late `onResults`/`onError` events. Before the fix below, those stale events reached the UI mid-analysis — restarting the mic, clobbering `ANALYZING`/`SPEAKING` state, or speaking "I did not catch that" right before the real answer. Two mechanisms now contain this:

- **Session grant** — `MainActivity.voiceSessionWanted` is set true only while the fragment genuinely wants the mic open, and is revoked the instant the user taps or the session ends. All three recognizer callbacks (`onResults`, `onError`, `onPartialResults`) and the internal auto-restart paths check the grant first and drop the event if it was revoked. Auto-restarts on `ERROR_CLIENT`/`ERROR_RECOGNIZER_BUSY` therefore die as soon as the user moves on.
- **State guards in the fragment** — `onSpeechError` ignores errors that arrive while the app is not in `LISTENING`/`REPORTING` (a tap or answer is in flight), and the in-window quiet-reopen path can no longer force `ANALYZING`/`SPEAKING` back to `LISTENING`.

The model-ASR rescue (§7.4) follows the same rules: it only runs while the grant is held, its late transcription/error dispatch is also grant-gated, and its one-shot budget is restored on every fresh voice session.

---

## 6. Vision-Language Inference Stack

### 6.1 Model

| Parameter | Value |
|---|---|
| **Model** | Gemma 4 E2B (`gemma-4-E2B-it.litertlm`) |
| **File size** | 2.59 GB (local, user-downloaded to Downloads/ or app-scoped external storage) |
| **Format / runtime** | LiteRT-LM (`com.google.ai.edge.litertlm:litertlm-android:0.16.1`) |
| **Modality** | Multimodal — vision encoder + audio encoder + text decoder (the audio encoder powers fully offline speech recognition, §7.4) |
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

`DynamicPromptBuilder` assembles query-specific context: language identification, injected memory recall (see §9.2), OCR ground truth when present, and **few-shot examples** for specialized tasks such as reading medicine packaging:

> *"Input: what medicine is this? Image shows Diclac Retard box. Output: Diclac Retard, diclofenac sodium 100mg. Take one tablet daily after meals."*
> *"Input: what is this? Image shows a red packet. Output: This is a Maggi instant noodle packet…"*

The builder adds three behavior contracts on top of the base rules, in every relevant branch:

- **OCR as ground truth** — when the ML Kit pre-pass found text, the extracted block is injected verbatim with the rule *"The OCR text above is the ground truth. Read it as whole words and continuous sentences — never spell it letter by letter… read ALL of it in reading order."* This anchors reads to real pixels and stops the model from re-deriving (and mis-guessing) text from a downscaled image.
- **Brand-first for packaged goods** — for packets, boxes, bottles, and cans the model must open with the *brand name and product type exactly as printed*, then continue. This is what lets Vyze tell apart two visually similar products by their labels.
- **Never guess** — unreadable text is reported as "Text is unclear"; currency values and medicine names are never invented (see §8).

### 6.4 Image preprocessing

- Input resolution is **query-adaptive** (dynamic resolution scaling): text-reading, tap, currency, and pointing queries are downscaled to **384×384** so fine print survives; plain scene queries use **256×256** for speed; continuous-mode frames are center-cropped to 256. The VLM input itself is not rigidly center-cropped for non-continuous queries — Gemma 4 handles dynamic aspect ratios natively.
- JPEG compression at quality 75 for any intermediate encoding.
- All scaled bitmaps are **explicitly recycled** after inference to prevent native memory leaks.
- A **tap-position marker** ("tapped at position (x, y)") and a **pointing-query classifier** ("what is this", "apa ini", "这是什么", …) route object-focused asks through the 384 px + OCR path, because pointed-at objects usually carry labels — and the spoken brand/reading answers depend on those pixels (§8).

### 6.5 Sampling & output controls

| Parameter | Value |
|---|---|
| Temperature | 0.1 |
| Top-K | 1 (greedy) |
| Top-P | 1.0 |
| Scene query tokens | 96 |
| Text-only Q&A tokens | 192 |
| ASR transcription tokens | 96 |
| Text-reading tokens | **Adaptive** — 64 + (OCR chars ÷ 4), clamped to 192…1024 |

Greedy decoding minimizes latency and hallucinated verbosity. Text reads use an **adaptive output budget sized to the OCR text actually found**: the model mostly *echoes* the injected OCR block (~1 token per 4 characters) plus a 64-token intro/outro allowance, so a dense back-panel (~2,000 characters) automatically gets ~560 tokens while a short label stays small. The 192 floor keeps short reads complete; the 1024 ceiling (~800 words) is the practical bound of the model context and the engine's 180 s inference wall — as close to "unlimited for text" as the on-device stack allows.

### 6.6 Inference timeouts & watchdogs

- Per-inference timeout: **180 s** standard, **60 s** on low-RAM devices (fail fast instead of hanging through an OOM recovery).
- GPU warm-up timeout: 60 s.
- `VyzeCoreController` runs a **progress-aware watchdog** (15 s): every received token re-arms the stall clock, so a genuinely long read-back is never force-killed mid-sentence — only a stall with *no output at all* for 15 s triggers the force-reset. Heap pressure is monitored during inference with warnings below a low-RAM threshold.

### 6.7 Streaming speech output

Answers are streamed token-by-token to TTS rather than spoken after full completion. The flush policy determines perceived quality:

- **Sentence-boundary flushing only** — the token buffer is spoken only at real sentence ends (`.`, `!`, `?`, newline). Commas and colons stay *inside* the sentence, and fragments shorter than 10 characters are held to join the next sentence. An earlier build flushed at commas (and even spoke the first 2–3 words instantly), which chopped one answer into many tiny utterances — each spoken with full-stop intonation and a dead-air gap, so users heard *"A red can. …(pause)… with a white label."* Whole sentences are now the natural spoken unit: the TTS voice renders internal commas as short natural pauses within one flowing utterance.
- **Read-ahead ceiling** — if the model emits 200 characters without any sentence-ending punctuation (rare), the buffer is flushed anyway so speech never stalls mid-generation.
- **First-utterance flush** — the first sentence flushes any leftover status speech ("Analyzing scene…") so the answer starts clean.
- **Audio drain tail** — when generation completes, a silent tail is always queued so the TTS queue stays alive while the speaker drains, with a 600 ms drain-grace window so the final words are never clipped.

### 6.8 Text-only question answering (no camera)

Questions that do **not** reference the visual scene — *"what is paracetamol used for?", "bagaimana cara mengikat tali?", "为什么天是蓝色的"* — are answered by the model's **text decoder alone** through `VlmEngineManager.analyzeText()`. There is no camera capture and no image inference: it is faster, cheaper, and works without pointing the phone at anything.

Classification (`VyzeCoreController.isTextOnlyQuery`) is deliberately conservative: the query must contain a knowledge marker ("what is", "how to", "apa itu", "怎么"…) **and no scene reference** ("this", "here", "in front", "ini", "这个"…). Any phrase that points at the world falls through to the camera pipeline — a missed text-only route is safe; a wrongly routed visual question is not. The prompt carries a dedicated rule set (answer from knowledge, never invent a scene, 1–3 sentences), output is capped at 192 tokens, and the answer streams through the same sentence-boundary TTS path.

---

## 7. Speech & Audio Subsystems

### 7.1 Speech recognition

Voice input uses the platform `android.speech.SpeechRecognizer` with `RecognizerIntent`, configured for the active language (English, Malay, or Chinese). Recognition availability is checked before use. The recognizer is only ever instantiated for an explicit session — matching the closed-mic-by-default policy. Partial results feed the stability gate described in §5.4. Every recognizer callback is guarded by the session grant (§5.5), and a `speechAttempted` flag (set on `onBeginningOfSpeech`) distinguishes "the user spoke but recognition failed" from "nobody spoke" — only the former is eligible for the model-native ASR rescue (§7.4), so silent pauses can never hijack a session.

### 7.2 Text-to-speech (TTS)

Vyze forces the **Google TTS engine** (`com.google.android.tts`) for consistency of voice quality and language support (falling back to the system default engine only if Google TTS is absent), and speaks through a streaming queue managed by `TTSManager`.

Voice quality is a first-class concern because the perceived "human-ness" of the assistant is dominated by the installed voice pack, not by prosody settings:

- The app **auto-selects the best installed voice** for the active language, ordered by the engine's reported quality score.
- Users can **audition and choose** a voice per language, either from Voice Settings (Previous voice / Next voice / Use this voice buttons, each speaking a sample in the candidate voice) or **fully hands-free by voice**: double-tap → "voice settings" → hear each voice → say "next", "use this", or "cancel".
- A **weak-voice detector** inspects the engine's reported quality and flags voices below the high-quality threshold. On first run, right after "Vyze is ready…", Vyze may say: *"Your current voice sounds basic. A better voice is available for free from Google. Say yes to open the voice installer, or say skip."* "Yes" launches the system voice-data installer (`ACTION_INSTALL_TTS_DATA`); on return the app re-verifies and confirms. The prompt is one-time and skippable.
- Choices persist per language and are restored on every init and language switch.

Because TTS engines read brand names by orthography, a **pronunciation override dictionary** is applied to text before synthesis — whole-word, case-insensitive, scoped to the active voice language. Example: Google's English voice reads the noodle brand "Maggi" as *MAY-jee*; the dictionary respells it "Mayghee", which the engine voices as *MAY-ghee* (the Malay voice already reads "Maggi" correctly and is left untouched). The map is a small curated constant, extensible per brand + language. The map is bypassed for Malay/Chinese voices, whose native orthography is already correct for the covered brands.

Spoken cues are deliberately minimal: the double-tap voice session plays a single "Listening." (voice-settings teaching was removed from the cue after it proved to nag on every session — the command still works and is discoverable via the one-time voice-quality prompt and onboarding).

### 7.3 Haptics & sound

`HapticManager` provides distinct one-shot patterns (gesture acknowledgment, warnings). `AnnouncementCoordinator` serializes spoken announcements so TTS output never overlaps itself, and any user gesture **barge-in** immediately silences active speech.

### 7.4 Model-native ASR rescue (noisy rooms)

The classic failure of hands-free assistants is the room full of people: Android's recognizer commits ambient conversation as the user's query, or gives up entirely (`NO_MATCH`, `SPEECH_TIMEOUT`, `ERROR_AUDIO`). Vyze answers with the model's own ears.

- **Capability** — Gemma 4 E2B ships an audio encoder, and the installed LiteRT-LM 0.16.1 runtime exposes audio input (`Content.AudioBytes` + `EngineConfig(audioBackend = …)`). Speech recognition therefore does not depend on Google's recognizer.
- **Capture** — a new `AudioCapture` helper records **16 kHz mono float32 PCM** (up to 8 s, with a short-speech rejection for anything under 0.25 s) — the exact byte format Gemma's audio encoder expects.
- **Flow** — when the recognizer fails *after the user actually started speaking* (`speechAttempted`), Vyze speaks "Please say that again.", records the repeat, and transcribes it fully offline via `VlmEngineManager.transcribeAudio()` (cap 96 tokens, output mirrored to the user's language). The transcription then enters the normal query pipeline.
- **Guardrails** — one rescue attempt per conversation (budget restored on each fresh double-tap session); never fires on pure silence; suppressed while the voice audition is playing; late rescue results are dropped if the user has already tapped away. The result: no beep loops, no "please repeat" nagging during pauses, and a genuine offline escape hatch when the platform recognizer fails in noise.

---

## 8. Specialized Visual Capabilities

### 8.1 OCR fast-path and medicine reading

Reading text through the full VLM is slow and error-prone, so Vyze implements a **hybrid reading pipeline**:

1. A spoken query is classified by reading keywords across English, Malay, and Chinese (e.g. "read", "label", "prescription", "baca", "读") — or by the pointing-query classifier ("what is this", "apa ini", "这是什么") and any tap, since taps usually land on objects with text.
2. If it is a reading query, the frame goes through **ML Kit Text Recognition** (`TextRecognition` with Latin or Chinese recognizer options, chosen by the active language) — a dedicated on-device OCR model.
3. Extracted text is passed to a **confidence-scored OCR path**; high-confidence text is read out directly, often enriched by the **medicine knowledge base**: a Room database pre-populated at first launch (`MedicineDatabaseCallback`) with common medicines and their indications, dosages, and warnings. The VLM is used as the fallback or enrichment layer (prompted with few-shot examples) when OCR confidence is low or the query is a free-form question about the packaging.

The fast path cuts reading latency from seconds of VLM inference to milliseconds of OCR plus TTS.

**Reading whole panels.** Dense packaging (a box back panel full of indications) previously stopped halfway for two compounding reasons: a fixed 160-token cap truncated long reads, and a fixed 15 s watchdog could force-kill a generation that was still producing output. Today the output budget adapts to the OCR text found (up to ~800 words, §6.5), the watchdog re-arms on every token, and the prompt demands reading *the entire text in reading order — never stop halfway or summarize*. Brand names are spoken correctly via the TTS pronunciation dictionary (§7.2).

### 8.2 Color analysis

`ColorAnalyzer` samples the center of the frame, converts to HSV, and classifies the dominant hue/saturation/value into a color name (localized to the active language). This is the triple-tap action and is pure CPU — near-instant with no model inference.

### 8.3 Light sensing & automatic flashlight

`CameraSetupDelegate` runs a **per-frame luminance analysis** on the CameraX `ImageAnalysis` stream and maintains a dark-environment flag. `FlashlightManager` engages the torch automatically using **dual-threshold hysteresis** (ON below ~35 lux, OFF above ~65 lux) so the torch does not rapidly flicker at the boundary. Long-press ("light check") announces the environment state and forces the torch if it has not yet flipped.

### 8.4 Scan history

Every recognized artifact — scene summaries, OCR text, barcodes, currency denominations, colors — is persisted through `ScanRepository`/`ScanDao` into a Room-backed history that the user can revisit via `GalleryFragment`.

### 8.5 Emergency SOS

Triple-tap-and-hold is a deliberately hard-to-trigger gesture that opens the dialer to the emergency number **999** after a 1.5-second confirm delay, giving the user time to abort.

### 8.6 Currency reading (banknotes & coins)

A dedicated classifier (`isCurrencyQuery`; "what money is this", "read this note", "berapa nilai duit ini", "多少钱") routes money scans through the high-resolution text path with a dedicated prompt rule set: identify the VALUE from the large numerals and printed text, state value + currency + dominant color, and **never guess** — an unreadable note is reported as "I cannot read this clearly", never assigned a denomination. Confident reads are persisted to scan history as a currency entry. The no-guess rule is absolute here because a wrong denomination is worse than no answer for a blind user.

### 8.7 Pointing questions & packaged-brand identification

"What is this?" is the most common real-world question, and it is *not* a text-only knowledge question — it points at an object. Vyze treats pointing phrases (English, Malay, Chinese deictics plus "what am I holding"-style phrasings) as object-focused asks and routes them through the 384 px + OCR path (§6.4) so the answer is grounded in what is actually printed. Combined with the brand-first prompt rule (§6.3), scanning two visually similar products (e.g. two white instant-noodle packets from different brands) yields the printed brand and product name rather than a guess from a downscaled frame.

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

`EmbeddingEngine` produces a 256-float embedding from any frame by downscaling to a 16×16 grayscale grid and normalizing pixel intensities — ~1 ms on CPU, zero extra model weight. `MemoryRepository.findSimilar` ranks stored interactions by **cosine similarity** to the current scene.

The recall side is wired as **context injection, not replay** — an important distinction:

- Every scan still analyzes the fresh frame; memory never substitutes for inference (the model is instructed to confirm continuity, or to describe only what it now sees if the scene changed).
- A past scan qualifies as context only when its similarity is **≥ 0.6** and it is **younger than 24 hours**; the *most recent* qualifying match wins, and the injected snippet is capped at 240 characters.
- Memory is suppressed entirely when OCR text is present (OCR is already the ground truth), in currency mode, and in continuous mode.

The authors document the 16×16 whole-frame fingerprint as an intentional, lightweight proxy for visual similarity — not semantic understanding; the similarity threshold is a tunable constant pending on-device calibration.

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
| Hung inference | Per-inference timeouts + progress-aware watchdog force-reset |
| Model file missing/corrupt | Multi-location resolution, size sanity check, human-readable setup instructions |
| Answer audio clipped | Always-queued drain tail + 600 ms grace |
| Long text reads cut off halfway | Adaptive token budget + progress-aware watchdog (§8.1) |
| Mid-sentence pauses in spoken answers | Sentence-boundary TTS flush; no comma splits; 200-char read-ahead ceiling (§6.7) |
| Phantom "I did not catch that" before results | Session grant + stale-callback drop; state-guarded error handling (§5.5) |
| Rescue firing on ordinary silence pauses | Rescue gated on speech-attempt flag; budget per conversation (§7.4) |
| Wrong brand pronunciation | Per-language pronunciation dictionary applied pre-synthesis (§7.2) |
| Endless vibration loops | One-shot haptic patterns (repeat = -1) |
| Speech session loops ("beep loop") | Clean session termination on no-match/timeout; mic closed at IDLE |
| Recorder clip of early speech | One-word "Listening." cue + 500 ms mic-open delay (§5.3) |
| Background crashes | Global exception handler writing to `CrashLogFile`, logs pruned on launch |

---

## 14. Key Parameters Reference

| Constant | Value |
|---|---|
| `CONVERSATION_WINDOW_MS` | 12,000 ms (rolling) |
| `CONVERSATION_WINDOW_TICK_MS` | 2,000 ms |
| `VOICE_SESSION_OPEN_DELAY_MS` | 500 ms |
| `FOLLOW_UP_OPEN_DELAY_MS` | 450 ms |
| `AUDIO_DRAIN_GRACE_MS` | 600 ms |
| Inference timeout (std / low-RAM) | 180 s / 60 s |
| Progress-aware watchdog | 15 s with no output → force-reset |
| GPU warm-up timeout | 60 s |
| Free-RAM preflight (std / low-RAM) | 1200 MB / 800 MB |
| Image dimension (text/tap/currency) | 384 px |
| Image dimension (scene / continuous) | 256 px |
| Sampling | temp 0.1, top-K 1 |
| Token caps | scene 96 · text-only Q&A 192 · ASR 96 · text reads adaptive 192–1024 (64 + OCR chars ÷ 4) |
| OCR fast-path confidence | ≥ 0.85 (skips the VLM for pure text reads) |
| Memory injection | similarity ≥ 0.6, age ≤ 24 h, snippet ≤ 240 chars |
| Chatter filter | confidence ≥ 0.35 · single words ≥ 5 chars · 5 rejected cycles → noise pause |
| Sentence flush | sentence terminators only; read-ahead ceiling 200 chars |
| Model-ASR capture | 16 kHz mono float32 PCM, ≤ 8 s |
| Auto-torch hysteresis | ON < 35 lux, OFF > 65 lux |
| SOS dial delay | 1.5 s (number 999) |
| Continuous snapshot | 4 s cadence, throttled to 8 s after 3 min |
| Thermal check interval | ~8 s |

---

## 15. Limitations & Future Work

**Known limitations**

- **Hardware floor** — requires a device with ~3 GB peak available RAM and a GPU-capable SoC; CPU-only and low-RAM devices are rejected early by design.
- **Model size** — the 2.59 GB model must be downloaded/staged outside the APK; first-run setup requires storage and a download.
- **Malay/Chinese VLM depth** — multilingual output depends on the underlying model's capability in those languages; OCR uses language-appropriate recognizers to compensate.
- **Handwriting** — ML Kit OCR is trained for printed text, and no handwriting-specific path exists yet. Large neat block capitals sometimes read; cursive and small writing mostly do not, and Vyze's honesty rules make it say so rather than guess. A tap-point crop + handwriting-aware prompt branch is the planned offline remedy.
- **Video input & function calling** — the installed runtime (LiteRT-LM 0.16.1) supports audio input but exposes **no video content type**, and Google gates tool-calling to FunctionGemma-class models; the shipped model is not one. True video (motion, "is that car moving?") therefore requires a runtime + model upgrade, and routing stays keyword-based by design.
- **Embedding memory** is intentionally shallow — a 16×16 whole-frame grayscale fingerprint captures scene layout, not object identity, and similarity thresholds remain to be calibrated on device.

**Future directions**

- **Tap-region fingerprints** — embed the crop around the tap point instead of the whole frame, so "same object" recall survives angle/distance changes and similar-looking products stop colliding.
- **Handwriting branch** — upscale crop + handwriting-aware prompt ("this may be handwritten; read word by word; say exactly what you cannot read").
- A semantic image-embedding model (e.g., MediaPipe Image Embedder) to upgrade memory recall from visual to conceptual similarity.
- Bundled neural TTS voices (e.g., Kokoro/Piper-class, ~100–300 MB) for genuinely human speech in EN/ZH; Malay would continue on Google TTS until a quality open Malay voice exists.
- **Conversation-window tuning** from real-device telemetry (window length is a single constant).
- A LiteRT-LM + model upgrade unlocking video input, and evaluation of FunctionGemma-class tool calling for robust command routing.

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
| Speech | `android.speech.SpeechRecognizer` + Gemma 4 E2B native audio encoder (model-ASR rescue) + Google TTS engine |
| Audio capture | `android.media.AudioRecord` — 16 kHz mono, `ENCODING_PCM_FLOAT`, ≤ 8 s |
| ABIs | `arm64-v8a` |
