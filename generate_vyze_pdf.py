#!/usr/bin/env python3
"""Generate a comprehensive PDF summary of the Vyze accessibility assistant."""

from fpdf import FPDF
import os

class VyzePDF(FPDF):
    def header(self):
        self.set_font("Helvetica", "B", 10)
        self.set_text_color(100, 100, 100)
        self.cell(0, 8, "Vyze - On-Device Visual Accessibility Assistant", align="R")
        self.ln(10)

    def footer(self):
        self.set_y(-15)
        self.set_font("Helvetica", "I", 8)
        self.set_text_color(128, 128, 128)
        self.cell(0, 10, f"Page {self.page_no()}/{{nb}}", align="C")

    def section_title(self, title):
        self.set_font("Helvetica", "B", 14)
        self.set_text_color(30, 80, 160)
        self.ln(4)
        self.cell(0, 10, title)
        self.ln(8)
        self.set_draw_color(30, 80, 160)
        self.line(self.get_x(), self.get_y(), self.get_x() + 190, self.get_y())
        self.ln(4)

    def sub_title(self, title):
        self.set_font("Helvetica", "B", 11)
        self.set_text_color(50, 50, 50)
        self.ln(2)
        self.cell(0, 7, title)
        self.ln(7)

    def body_text(self, text):
        self.set_font("Helvetica", "", 10)
        self.set_text_color(40, 40, 40)
        self.multi_cell(0, 5.5, text)
        self.ln(2)

    def bullet(self, text):
        self.set_font("Helvetica", "", 10)
        self.set_text_color(40, 40, 40)
        x = self.get_x()
        self.cell(8, 5.5, chr(149))
        self.multi_cell(0, 5.5, text)
        self.ln(1)

    def code_block(self, text):
        self.set_font("Courier", "", 9)
        self.set_fill_color(240, 240, 245)
        self.set_text_color(50, 50, 50)
        self.multi_cell(0, 5, text, fill=True)
        self.ln(2)

    def kv_row(self, key, value):
        self.set_font("Helvetica", "B", 10)
        self.set_text_color(60, 60, 60)
        self.cell(55, 6, key)
        self.set_font("Helvetica", "", 10)
        self.set_text_color(40, 40, 40)
        self.cell(0, 6, value)
        self.ln(6)


def main():
    pdf = VyzePDF()
    pdf.alias_nb_pages()
    pdf.set_auto_page_break(auto=True, margin=20)
    pdf.add_page()

    # -- Title Page --
    pdf.set_font("Helvetica", "B", 28)
    pdf.set_text_color(30, 80, 160)
    pdf.ln(30)
    pdf.cell(0, 15, "Vyze", align="C")
    pdf.ln(18)
    pdf.set_font("Helvetica", "", 14)
    pdf.set_text_color(80, 80, 80)
    pdf.cell(0, 10, "On-Device Visual Accessibility Assistant", align="C")
    pdf.ln(12)
    pdf.set_font("Helvetica", "", 11)
    pdf.cell(0, 8, "Technical Summary & Architecture Overview", align="C")
    pdf.ln(20)
    pdf.set_font("Helvetica", "", 10)
    pdf.set_text_color(120, 120, 120)
    pdf.cell(0, 6, "Version 1.0  |  Android (arm64-v8a)  |  minSdk 26  |  targetSdk 34", align="C")
    pdf.ln(6)
    pdf.cell(0, 6, "Kotlin 2.3.0  |  LiteRT-LM 0.16.1  |  Gemma 3n E2B int4", align="C")
    pdf.ln(30)

    # -- Table of Contents --
    pdf.set_font("Helvetica", "B", 16)
    pdf.set_text_color(30, 80, 160)
    pdf.cell(0, 10, "Table of Contents")
    pdf.ln(12)
    toc_items = [
        "1. Executive Summary",
        "2. Problem Statement",
        "3. Solution Architecture",
        "4. Core Components",
        "5. VLM Engine (Gemma 3n E2B)",
        "6. Camera Pipeline",
        "7. Speech Recognition & TTS",
        "8. Adaptive Memory System",
        "9. Performance Optimizations",
        "10. Safety & Stability",
        "11. Tech Stack",
        "12. File Structure",
    ]
    for item in toc_items:
        pdf.set_font("Helvetica", "", 11)
        pdf.set_text_color(40, 40, 40)
        pdf.cell(0, 7, item)
        pdf.ln(7)

    # -- 1. Executive Summary --
    pdf.add_page()
    pdf.section_title("1. Executive Summary")
    pdf.body_text(
        "Vyze is an offline, on-device visual accessibility assistant for Android that helps "
        "blind and visually impaired users understand their surroundings in real time. The app "
        "uses a local Gemma 3n E2B (Edge 2 Billion) multimodal vision-language model running "
        "entirely on-device via Google's LiteRT-LM GPU backend to describe camera scenes, "
        "read text, identify objects, and provide spatial navigation cues  --  all without any "
        "internet connection or cloud dependency."
    )
    pdf.body_text(
        "Users interact hands-free through continuous speech recognition: they speak a question "
        "like \"What is in front of me?\" or \"Read this label\", and Vyze captures a camera "
        "frame, runs VLM inference, and speaks the result aloud via text-to-speech. The app "
        "supports a continuous \"point-and-describe\" scanning mode similar to Gemini Live."
    )
    pdf.body_text(
        "Key differentiators: (1) 100% offline  --  no data leaves the device, (2) real-time "
        "voice-first UX with barge-in interruption, (3) adaptive memory that learns from past "
        "interactions, (4) sub-second time-to-first-token via streaming sentence-buffered TTS, "
        "and (5) aggressive latency optimizations including 256px image clamping, greedy "
        "decoding, and GPU kernel pre-warming."
    )

    # -- 2. Problem Statement --
    pdf.section_title("2. Problem Statement")
    pdf.body_text(
        "Over 2.2 billion people globally have vision impairments (WHO, 2023). Existing "
        "assistive technology relies heavily on cloud-based AI services that require internet "
        "connectivity, introduce latency (1-3 seconds per query), raise privacy concerns by "
        "uploading camera images, and fail in offline environments (rural areas, subways, "
        "international travel)."
    )
    pdf.body_text(
        "Vyze addresses these gaps by running the entire vision-language pipeline locally on "
        "the user's phone. The Gemma 3n E2B model (3.66 GB int4 quantized) fits within mobile "
        "GPU memory constraints while providing high-quality scene understanding and text "
        "extraction capabilities."
    )

    # -- 3. Solution Architecture --
    pdf.section_title("3. Solution Architecture")
    pdf.body_text(
        "Vyze follows a modular architecture with clear separation of concerns:"
    )
    pdf.bullet("Camera Layer: CameraX ImageAnalysis delivers frames to an in-memory buffer (zero disk I/O)")
    pdf.bullet("VLM Engine: LiteRT-LM + Gemma 3n E2B performs multimodal inference on GPU")
    pdf.bullet("Prompt Builder: Dynamic prompt system with navigation, direct-query, and continuous-mode rules")
    pdf.bullet("TTS Manager: Neural voice synthesis with sentence-buffered streaming for instant audio")
    pdf.bullet("Speech Recognizer: Android SpeechRecognizer with aggressive silence endpoints")
    pdf.bullet("Adaptive Memory: Room database with vector similarity search for contextual recall")
    pdf.bullet("Core Controller: Orchestrates the full pipeline with session isolation and state management")

    pdf.add_page()
    pdf.sub_title("Data Flow")
    pdf.code_block(
        "User speaks \"What is this?\"\n"
        "  -> SpeechRecognizer.onResults() extracts text\n"
        "  -> CameraFragment.bargeInAndCapture() stops TTS + captures frame\n"
        "  -> CameraSetupDelegate.takeSnapshot() copies fresh Bitmap from ImageAnalysis\n"
        "  -> VyzeCoreController.triggerSnapshot() builds prompt + starts VLM inference\n"
        "  -> VlmEngineManager.analyzeImage() sends Bitmap + prompt to Gemma 3n E2B\n"
        "  -> onTokenGenerated() streams tokens into sentenceBuffer\n"
        "  -> flushSentenceBufferIfReady() sends clause-level chunks to TTSManager\n"
        "  -> TTSManager.speak() plays audio with pendingUtteranceIds tracking\n"
        "  -> onDone() resets to IDLE + restarts SpeechRecognizer for next query"
    )

    # -- 4. Core Components --
    pdf.section_title("4. Core Components")

    pdf.sub_title("4.1 VyzeCoreController")
    pdf.body_text(
        "The central orchestrator that manages the full capture-to-speech pipeline. Holds "
        "references to VlmEngineManager, TTSManager, DynamicPromptBuilder, and MemoryRepository. "
        "Key responsibilities:"
    )
    pdf.bullet("Session isolation via UUID-based activeSessionId (prevents stale response leakage)")
    pdf.bullet("Coroutine-based inference with Job cancellation on new queries")
    pdf.bullet("Sentence-buffered TTS streaming with clause-boundary flushing")
    pdf.bullet("Duplicate detection via lastDescribedObject cache with 3s debounce")
    pdf.bullet("Bitmap center-cropping + downscaling for VLM input normalization")
    pdf.bullet("15-second watchdog timer to prevent indefinite ANALYZING state")

    pdf.sub_title("4.2 VlmEngineManager")
    pdf.body_text(
        "Wrapper around Google's LiteRT-LM framework that manages the Gemma 3n E2B engine lifecycle. "
        "Provides the analyzeImage() API for multimodal inference."
    )
    pdf.bullet("GPU-only execution via Backend.GPU() with OpenCL/Vulkan acceleration")
    pdf.bullet("Model resolution from /storage/emulated/0/Download/ or getExternalFilesDir(null)")
    pdf.bullet("Bitmap preprocessing: center-crop to 1:1 square + scale to 256x256 pixels")
    pdf.bullet("Gemma turn-format prompting: <start_of_turn>user\\n...<end_of_turn>\\n<start_of_turn>model\\n")
    pdf.bullet("Greedy decoding: maxTokens=35, temperature=0.1, topK=1 for fast mobile inference")
    pdf.bullet("GPU warm-up: dummy 1x1 image pre-compiles OpenCL/Vulkan kernels on init")
    pdf.bullet("Non-destructive interrupt: latch.countDown() without engine.close()")

    pdf.sub_title("4.3 CameraSetupDelegate")
    pdf.body_text(
        "Manages CameraX use cases (Preview + ImageAnalysis) and provides zero-disk-I/O "
        "frame extraction via in-memory Bitmap buffer."
    )
    pdf.bullet("ImageAnalysis.Analyzer decodes YUV_888 to ARGB_8888 Bitmap at camera framerate")
    pdf.bullet("Atomic frame swap with frameLock prevents recycling during snapshot extraction")
    pdf.bullet("Frame counter guarantees fresh frames on voice-triggered snapshots")
    pdf.bullet("takeSnapshot() polls for post-query frame with 300ms timeout + PreviewView fallback")

    # -- 5. VLM Engine --
    pdf.add_page()
    pdf.section_title("5. VLM Engine  --  Gemma 3n E2B")
    pdf.body_text(
        "The vision-language model is Google's Gemma 3n E2B (Edge 2 Billion parameters), "
        "int4 quantized to 3.66 GB. It runs entirely on-device via the LiteRT-LM framework "
        "with GPU acceleration."
    )

    pdf.sub_title("Model Specifications")
    pdf.kv_row("Model:", "gemma-3n-E2B-it-int4.litertlm")
    pdf.kv_row("Size:", "3.66 GB (int4 quantized)")
    pdf.kv_row("Parameters:", "~2 billion")
    pdf.kv_row("Backend:", "GPU (OpenCL/Vulkan)")
    pdf.kv_row("Framework:", "LiteRT-LM 0.16.1")
    pdf.kv_row("Max Output Tokens:", "35 (greedy)")
    pdf.kv_row("Temperature:", "0.1")
    pdf.kv_row("Top-K:", "1")
    pdf.kv_row("Image Input:", "256x256 center-cropped square")
    pdf.kv_row("Prompt Format:", "Gemma turn system (<start_of_turn>)")

    pdf.sub_title("Inference Pipeline")
    pdf.code_block(
        "1. Bitmap center-crop to 1:1 square (preserves spatial alignment)\n"
        "2. Scale to 256x256 pixels (minimizes prefill time)\n"
        "3. JPEG encode to byte array (avoids raw Bitmap overhead)\n"
        "4. Build Gemma turn-format prompt with system directive + query\n"
        "5. Create Conversation with ConversationConfig(samplerConfig)\n"
        "6. Engine.sendMessageAsync(contents, callback)\n"
        "7. Stream tokens via onTokenGenerated callback\n"
        "8. CountDownLatch.await() blocks until onDone fires\n"
        "9. Return full response string to VyzeCoreController"
    )

    # -- 6. Camera Pipeline --
    pdf.section_title("6. Camera Pipeline")
    pdf.body_text(
        "Vyze uses CameraX with two use cases: Preview (for the user to see what they're "
        "pointing at) and ImageAnalysis (for frame extraction). The pipeline is optimized "
        "for zero-disk-I/O and minimal latency."
    )

    pdf.sub_title("Frame Extraction Flow")
    pdf.bullet("ImageAnalysis.Analyzer runs on a single-thread ExecutorService (30fps)")
    pdf.bullet("YUV_888 -> ARGB_8888 conversion with rotation correction")
    pdf.bullet("New frame swapped into AtomicReference<Bitmap?> via getAndSet()")
    pdf.bullet("Old frame recycled after acquiring frameLock (prevents use-after-free)")
    pdf.bullet("Frame counter incremented to signal fresh frame availability")

    pdf.sub_title("Snapshot Capture")
    pdf.bullet("takeSnapshot() records frameCounter at query time")
    pdf.bullet("Polls for frameCounter > counterAtQuery (guarantees fresh frame)")
    pdf.bullet("300ms timeout with fallback to latestFrame.get() if no new frame arrives")
    pdf.bullet("Deep copy via Bitmap.copy(ARGB_8888, true) on analysis thread")
    pdf.bullet("Bitmap.isRecycled + getPixel(0,0) validation before VLM inference")

    # -- 7. Speech Recognition & TTS --
    pdf.add_page()
    pdf.section_title("7. Speech Recognition & TTS")

    pdf.sub_title("7.1 Speech Recognition")
    pdf.body_text(
        "Uses Android's SpeechRecognizer with aggressive silence endpoints for fast "
        "response to short queries like \"What is this?\""
    )
    pdf.bullet("COMPLETE_SILENCE_LENGTH: 400ms (was 1500ms)")
    pdf.bullet("POSSIBLY_COMPLETE_SILENCE_LENGTH: 300ms (was 1500ms)")
    pdf.bullet("MINIMUM_LENGTH: 1000ms (new)")
    pdf.bullet("PARTIAL_RESULTS: true (enables early intent parsing)")
    pdf.bullet("Auto-restart after TTS completes (continuous hands-free loop)")
    pdf.bullet("200ms settle delay after TTS stop to prevent mic hearing barge-in tap")

    pdf.sub_title("7.2 Text-to-Speech")
    pdf.body_text(
        "Neural voice synthesis via Google TTS engine with deterministic completion tracking."
    )
    pdf.bullet("Engine: Google TTS (com.google.android.tts) for neural voice quality")
    pdf.bullet("Voice selection: Highest QUALITY_HIGH/QUALITY_VERY_HIGH voice for locale")
    pdf.bullet("Pitch: 0.96f (-2%) for warmer, non-metallic tone")
    pdf.bullet("Rate: 0.98f (-2%) for conversational cadence")
    pdf.bullet("Audio: USAGE_ASSISTANCE_ACCESSIBILITY (non-duckable)")
    pdf.bullet("Volume: KEY_PARAM_VOLUME = 1.0f (stable full volume)")
    pdf.bullet("Audio Focus: AUDIOFOCUS_GAIN_TRANSIENT")
    pdf.bullet("Cadence: Punctuation-aware spacing for natural pauses")

    pdf.sub_title("7.3 Sentence-Buffered Streaming")
    pdf.body_text(
        "Tokens from VLM inference are buffered and flushed at natural clause boundaries "
        "for minimal time-to-first-audio."
    )
    pdf.bullet("First chunk: flush at >=1 word or >=3 characters (instant feedback)")
    pdf.bullet("Subsequent chunks: flush at commas/colons (12+ chars) or sentence terminators (20+ chars)")
    pdf.bullet("Final flush: force-flush all remaining text on onComplete (no minimum)")
    pdf.bullet("Utterance ID tracking via pendingUtteranceIds (deterministic, no polling)")
    pdf.bullet("400ms post-drain grace period for AudioTrack hardware buffer")

    # -- 8. Adaptive Memory --
    pdf.section_title("8. Adaptive Memory System")
    pdf.body_text(
        "Vyze maintains a local Room database that records interactions, user preferences, "
        "and environment context to provide increasingly personalized assistance."
    )

    pdf.sub_title("Data Schema")
    pdf.code_block(
        "InteractionRecord:\n"
        "  - id: Long (auto-generated)\n"
        "  - timestamp: Long\n"
        "  - imageEmbedding: FloatArray (MediaPipe Vision Embedder)\n"
        "  - rawPrompt: String\n"
        "  - generatedOutput: String\n"
        "  - userFeedback: String (edited text or preference tags)\n"
        "\n"
        "MemoryDao:\n"
        "  - getAllPreferences(): List<Preference>\n"
        "  - getRecentEnvironment(limit): List<EnvironmentMemory>\n"
        "  - findSimilar(embedding, k): List<SimilarInteraction>"
    )

    pdf.sub_title("Contextual Prompt Injection")
    pdf.bullet("Similar past interactions retrieved via vector similarity search")
    pdf.bullet("User preferences injected as section in prompt payload")
    pdf.bullet("Environment context (room layouts, frequent objects) included")
    pdf.bullet("All queries execute off-main-thread via Dispatchers.IO")

    # -- 9. Performance Optimizations --
    pdf.add_page()
    pdf.section_title("9. Performance Optimizations")

    optimizations = [
        ("Image Preprocessing", "Center-crop to 1:1 square + scale to 256x256 (4x fewer pixels than 512x512)"),
        ("Greedy Decoding", "maxTokens=35, temperature=0.1, topK=1 for fastest token generation"),
        ("Minimal Prompts", "CONTINUOUS_MODE_RULES: 72 chars vs 289 chars (75% fewer prefill tokens)"),
        ("First-Chunk Flush", "1 word / 3 chars triggers first TTS audio (~200ms after first tokens)"),
        ("GPU Warm-up", "Dummy 1x1 image pre-compiles OpenCL/Vulkan kernels on app launch"),
        ("Zero Disk I/O", "In-memory Bitmap buffer from ImageAnalysis (no takePicture + JPEG decode)"),
        ("Frame Counter", "Guarantees fresh frame on every voice query (no stale frame reuse)"),
        ("Speech Rate", "1.15x TTS playback speed reduces total utterance duration"),
        ("Barge-In", "Instant TTS stop on touch/speech input (200ms settle before mic restart)"),
        ("Engine Interrupt", "latch.countDown() releases blocking await without destroying engine"),
        ("Session Isolation", "UUID-based activeSessionId prevents stale callback leakage"),
        ("Center-Crop", "Preserves spatial alignment (left/right positioning) for VLM input"),
    ]
    for title, desc in optimizations:
        pdf.bullet(f"{title}: {desc}")

    # -- 10. Safety & Stability --
    pdf.section_title("10. Safety & Stability")

    pdf.sub_title("State Machine")
    pdf.code_block(
        "IDLE -> (tap/speech) -> ANALYZING -> (VLM complete) -> SPEAKING -> (TTS done) -> IDLE\n"
        "IDLE -> (barge-in) -> ANALYZING (cancels previous + stops TTS)\n"
        "ANALYZING -> (15s timeout) -> IDLE + error speech"
    )

    pdf.sub_title("Crash Prevention")
    pdf.bullet("15-second watchdog timer forces ANALYZING -> IDLE if inference hangs")
    pdf.bullet("Bitmap.isRecycled + getPixel(0,0) corruption check before VLM inference")
    pdf.bullet("Session ID gating on all callbacks (onTokenGenerated, onComplete, onError)")
    pdf.bullet("Coroutine Job cancellation with isActive checks at 3 points in inference")
    pdf.bullet("Non-destructive interrupt: engine stays alive for next query")
    pdf.bullet("FrameLock prevents bitmap recycling during snapshot extraction")
    pdf.bullet("isCapturing AtomicBoolean prevents concurrent frame extractions")
    pdf.bullet("1-second debounce on trigger events prevents double-fire")

    pdf.sub_title("Memory Management")
    pdf.bullet("Bitmap.recycle() in finally blocks for both original and scaled bitmaps")
    pdf.bullet("CameraSetupDelegate recycles old frames after frameLock acquisition")
    pdf.bullet("Error log pruning: 7-day retention window on cold start")
    pdf.bullet("Conversation lifecycle: manual close() (not use{}) to prevent SIGSEGV")

    # -- 11. Tech Stack --
    pdf.add_page()
    pdf.section_title("11. Technology Stack")

    pdf.sub_title("Languages & Frameworks")
    pdf.kv_row("Language:", "Kotlin 2.3.0")
    pdf.kv_row("Build System:", "Gradle 8.14.3 + Kotlin DSL")
    pdf.kv_row("Min SDK:", "26 (Android 8.0)")
    pdf.kv_row("Target SDK:", "34 (Android 14)")
    pdf.kv_row("Architecture:", "ARM64-v8a only (64-bit GPU delegates)")

    pdf.sub_title("AI & ML")
    pdf.kv_row("VLM Model:", "Gemma 3n E2B int4 (3.66 GB)")
    pdf.kv_row("Inference:", "LiteRT-LM 0.16.1 (Google)")
    pdf.kv_row("GPU Backend:", "OpenCL/Vulkan via Backend.GPU()")
    pdf.kv_row("Embeddings:", "MediaPipe Vision Embedder")

    pdf.sub_title("Android Libraries")
    pdf.kv_row("Camera:", "CameraX 1.3.1")
    pdf.kv_row("Database:", "Room 2.7.0")
    pdf.kv_row("Navigation:", "Navigation 2.7.7")
    pdf.kv_row("Coroutines:", "kotlinx-coroutines 1.10.1")
    pdf.kv_row("Lifecycle:", "Lifecycle 2.7.0")
    pdf.kv_row("UI:", "Material 1.11.0 + ConstraintLayout 2.1.4")

    pdf.sub_title("Build & Release")
    pdf.kv_row("Release Build:", "R8 minification + resource shrinking")
    pdf.kv_row("JNI Packaging:", "useLegacyPackaging = true (uncompressed .so)")
    pdf.kv_row("Model Assets:", "noCompress += \"litertlm\" (uncompressed in APK)")

    # -- 12. File Structure --
    pdf.section_title("12. Key File Structure")

    files = [
        ("VyzeApplication.kt", "App singleton, dependency injection, global error handler"),
        ("MainActivity.kt", "Speech recognition, TTS integration, lifecycle management"),
        ("VyzeCoreController.kt", "Pipeline orchestrator, session management, sentence buffering"),
        ("VlmEngineManager.kt", "LiteRT-LM wrapper, Gemma 3n E2B engine lifecycle"),
        ("CameraSetupDelegate.kt", "CameraX setup, frame buffer, bitmap extraction"),
        ("CameraFragment.kt", "Camera UI, voice triggers, continuous mode, state machine"),
        ("DynamicPromptBuilder.kt", "Prompt assembly with navigation/direct/continuous rules"),
        ("TTSManager.kt", "Neural TTS, voice selection, utterance tracking, audio focus"),
        ("MemoryRepository.kt", "Adaptive memory, vector search, interaction records"),
        ("VyzeDatabase.kt", "Room database, DAOs, entities"),
    ]
    for fname, desc in files:
        pdf.set_font("Courier", "", 9)
        pdf.set_text_color(30, 80, 160)
        pdf.cell(60, 5.5, fname)
        pdf.set_font("Helvetica", "", 10)
        pdf.set_text_color(60, 60, 60)
        pdf.cell(0, 5.5, desc)
        pdf.ln(6)

    # -- Save --
    output_path = os.path.join(os.path.dirname(os.path.abspath(__file__)), "Vyze_App_Summary.pdf")
    pdf.output(output_path)
    print(f"PDF generated: {output_path}")


if __name__ == "__main__":
    main()
