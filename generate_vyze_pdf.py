#!/usr/bin/env python3
"""Generate a comprehensive PDF summary of the Vyze accessibility assistant."""

from fpdf import FPDF
import os

class VyzePDF(FPDF):
    def header(self):
        self.set_font("Helvetica", "B", 10)
        self.set_text_color(100, 100, 100)
        self.cell(0, 8, "Vyze - Offline AI Vision Assistant", align="R")
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
    pdf.cell(0, 10, "Offline AI Vision Assistant", align="C")
    pdf.ln(12)
    pdf.set_font("Helvetica", "", 11)
    pdf.cell(0, 8, "Technical Summary & Architecture Overview", align="C")
    pdf.ln(20)
    pdf.set_font("Helvetica", "", 10)
    pdf.set_text_color(120, 120, 120)
    pdf.cell(0, 6, "Version 1.0  |  Android (arm64-v8a)  |  minSdk 26  |  targetSdk 34", align="C")
    pdf.ln(6)
    pdf.cell(0, 6, "Kotlin 2.3.0  |  LiteRT-LM 0.16.1  |  Gemma 3n E2B int4  |  ML Kit OCR", align="C")
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
        "6. ML Kit OCR Pipeline",
        "7. Camera Pipeline",
        "8. Speech Recognition & TTS",
        "9. Adaptive Memory System",
        "10. Performance Optimizations",
        "11. Safety & Stability",
        "12. Tech Stack",
        "13. File Structure",
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
        "Vyze is a fully offline AI vision assistant for Android that helps blind and "
        "visually impaired users understand their surroundings in real time. Powered by "
        "Gemma 3n E2B (2B parameter multimodal model) running on-device via Google's "
        "LiteRT-LM framework with NPU/GPU acceleration."
    )
    pdf.body_text(
        "Users interact hands-free through continuous speech recognition: they speak a "
        "question like \"What is in front of me?\" or \"Read this label\", and Vyze captures "
        "a camera frame, runs VLM inference (or fast ML Kit OCR for text queries), and "
        "speaks the result aloud via sentence-buffered TTS streaming."
    )
    pdf.body_text(
        "Key differentiators: (1) 100% offline -- no data leaves the device, (2) real-time "
        "voice-first UX with barge-in interruption, (3) adaptive memory that learns from "
        "past interactions, (4) hybrid OCR pipeline (ML Kit + Gemma) for 300ms text "
        "extraction, (5) user-driven language mirroring (auto-detect + respond in user's "
        "language), and (6) aggressive latency optimizations achieving sub-2-second "
        "time-to-first-audio."
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
        "GPU memory constraints while providing high-quality scene understanding, text "
        "extraction, and multi-language support."
    )

    # -- 3. Solution Architecture --
    pdf.section_title("3. Solution Architecture")
    pdf.body_text("Vyze follows a modular architecture with clear separation of concerns:")

    pdf.bullet("Camera Layer: CameraX ImageAnalysis delivers frames to an in-memory buffer (zero disk I/O)")
    pdf.bullet("ML Kit OCR: Fast on-device text extraction (80-150ms) for text queries")
    pdf.bullet("VLM Engine: LiteRT-LM + Gemma 3n E2B with NPU/GPU fallback")
    pdf.bullet("Prompt Builder: Dynamic prompt system with navigation, direct-query, continuous-mode, and language mirror rules")
    pdf.bullet("TTS Manager: Google neural TTS with sentence-buffered streaming and language-aware voice switching")
    pdf.bullet("Speech Recognizer: Android SpeechRecognizer with aggressive silence endpoints + language detection")
    pdf.bullet("Adaptive Memory: Room database with vector similarity search for contextual recall")
    pdf.bullet("Core Controller: Orchestrates the full pipeline with session isolation and state management")

    pdf.add_page()
    pdf.sub_title("Data Flow")
    pdf.code_block(
        "User speaks \"What is this?\"\n"
        "  -> SpeechRecognizer.onResults() extracts text + detected language\n"
        "  -> CameraFragment updates TTS voice to match detected language\n"
        "  -> VyzeCoreController.setUserLocale() switches TTS + prompt language\n"
        "  -> CameraSetupDelegate.takeSnapshot() copies fresh Bitmap\n"
        "  -> VyzeCoreController.triggerSnapshot() routes through OCR or VLM:\n"
        "       TEXT QUERY: ML Kit OCR (80-150ms) -> Gemma interprets\n"
        "       SCENE QUERY: Gemma 3n E2B direct inference\n"
        "  -> onTokenGenerated() streams tokens into sentenceBuffer\n"
        "  -> flushSentenceBufferIfReady() sends clause-level chunks to TTS\n"
        "  -> TTS speaks in user's detected language with matching voice\n"
        "  -> onDone() resets to IDLE + restarts SpeechRecognizer"
    )

    # -- 4. Core Components --
    pdf.section_title("4. Core Components")

    pdf.sub_title("4.1 VyzeCoreController")
    pdf.body_text(
        "The central orchestrator that manages the full capture-to-speech pipeline. "
        "Routes text queries through ML Kit OCR first, then feeds clean text to Gemma "
        "for interpretation. Manages session isolation, sentence streaming, and language mirroring."
    )
    pdf.bullet("Session isolation via UUID-based activeSessionId (prevents stale response leakage)")
    pdf.bullet("OCR-first routing: ML Kit for text queries, Gemma for scene queries")
    pdf.bullet("Language mirroring: stores activeUserLocale, passes to prompt builder + TTS")
    pdf.bullet("Coroutine-based inference with Job cancellation on new queries")
    pdf.bullet("Sentence-buffered TTS streaming with clause-boundary flushing")
    pdf.bullet("Duplicate detection via lastDescribedObject cache with 4s debounce")
    pdf.bullet("15-second watchdog timer to prevent indefinite ANALYZING state")

    pdf.sub_title("4.2 VlmEngineManager")
    pdf.body_text(
        "Wrapper around Google's LiteRT-LM framework that manages the Gemma 3n E2B "
        "engine lifecycle. Provides NPU/GPU multi-backend fallback and the analyzeImage() API."
    )
    pdf.bullet("Multi-backend fallback: NPU (preferred) -> GPU (fallback) -> Error")
    pdf.bullet("Model resolution from /storage/emulated/0/Download/ or getExternalFilesDir(null)")
    pdf.bullet("Dynamic bitmap preprocessing: 256px (scene) or 384px (text) center-cropped squares")
    pdf.bullet("Gemma turn-format prompting with compact system directives")
    pdf.bullet("Greedy decoding: maxTokens=35, temperature=0.1, topK=1")
    pdf.bullet("JPEG quality 75 with pre-allocated 8KB ByteArrayOutputStream")
    pdf.bullet("GPU warm-up: dummy 1x1 image pre-compiles OpenCL/Vulkan kernels")
    pdf.bullet("Non-destructive interrupt: latch.countDown() without engine.close()")

    pdf.sub_title("4.3 OcrHelper")
    pdf.body_text(
        "ML Kit on-device OCR wrapper supporting Latin and Chinese scripts. "
        "Runs 10-30x faster than full VLM inference for text extraction."
    )
    pdf.bullet("Latin script: English, Malay, Indonesian, Vietnamese, European languages (~5MB)")
    pdf.bullet("Chinese script: Simplified + Traditional Chinese (~20MB)")
    pdf.bullet("Auto-detects script in image, no manual routing needed")
    pdf.bullet("Confidence scoring for fallback decisions")
    pdf.bullet("Falls back to Gemma for handwriting and complex layouts")

    pdf.sub_title("4.4 CameraSetupDelegate")
    pdf.body_text(
        "Manages CameraX use cases and provides zero-disk-I/O frame extraction."
    )
    pdf.bullet("ImageAnalysis.Analyzer decodes YUV_888 to ARGB_8888 at camera framerate")
    pdf.bullet("Atomic frame swap with frameLock prevents recycling during extraction")
    pdf.bullet("Frame counter guarantees fresh frames on voice-triggered snapshots")
    pdf.bullet("takeSnapshot() polls for post-query frame with 300ms timeout + fallback")

    # -- 5. VLM Engine --
    pdf.add_page()
    pdf.section_title("5. VLM Engine -- Gemma 3n E2B")
    pdf.body_text(
        "The vision-language model is Google's Gemma 3n E2B (Edge 2 Billion parameters), "
        "int4 quantized to 3.66 GB. It runs entirely on-device via the LiteRT-LM framework "
        "with NPU/GPU acceleration."
    )

    pdf.sub_title("Model Specifications")
    pdf.kv_row("Model:", "gemma-3n-E2B-it-int4.litertlm")
    pdf.kv_row("Size:", "3.66 GB (int4 quantized)")
    pdf.kv_row("Parameters:", "~2 billion")
    pdf.kv_row("Backend:", "NPU (preferred) -> GPU (fallback)")
    pdf.kv_row("Framework:", "LiteRT-LM 0.16.1")
    pdf.kv_row("Max Output Tokens:", "35 (greedy)")
    pdf.kv_row("Temperature:", "0.1")
    pdf.kv_row("Top-K:", "1")
    pdf.kv_row("Image Input:", "256x256 or 384x384 center-cropped square")
    pdf.kv_row("Prompt Format:", "Gemma turn system (<start_of_turn>)")

    pdf.sub_title("Inference Pipeline")
    pdf.code_block(
        "1. Detect query type (text vs scene)\n"
        "2. If text query: run ML Kit OCR first (80-150ms)\n"
        "3. Center-crop bitmap to 1:1 square (preserves spatial alignment)\n"
        "4. Scale to 256px (scene) or 384px (text extraction)\n"
        "5. JPEG encode at quality=75 (pre-allocated 8KB buffer)\n"
        "6. Build compact prompt with language mirror directive\n"
        "7. Inject OCR text if available (skips character-level reading)\n"
        "8. Create Conversation with SamplerConfig(topK=1, temp=0.1)\n"
        "9. Engine.sendMessageAsync(contents, callback)\n"
        "10. Stream tokens via onTokenGenerated callback\n"
        "11. Sentence-buffered flush to TTS at clause boundaries"
    )

    # -- 6. ML Kit OCR Pipeline --
    pdf.section_title("6. ML Kit OCR Pipeline")
    pdf.body_text(
        "Vyze uses a hybrid OCR approach: ML Kit for fast text extraction, Gemma for "
        "intelligent interpretation. This achieves 10-30x faster text recognition compared "
        "to using the VLM alone."
    )

    pdf.sub_title("How It Works")
    pdf.code_block(
        "Text query arrives (\"Read this label\")\n"
        "  -> isTextExtractionQuery() detects keyword\n"
        "  -> ML Kit OCR runs on bitmap (80-150ms)\n"
        "     Latin script first, then Chinese if nothing found\n"
        "  -> OCR text injected into prompt: \"OCR: Diclac Retard...\"\n"
        "  -> Gemma receives clean text + image\n"
        "  -> Interprets immediately (skips character-level reading)\n"
        "  -> TTS speaks result (~300-500ms total)"
    )

    pdf.sub_title("Supported Scripts")
    pdf.kv_row("Latin:", "English, Malay, Indonesian, Vietnamese, Turkish, European (~5MB)")
    pdf.kv_row("Chinese:", "Simplified + Traditional Chinese (~20MB)")
    pdf.kv_row("Total overhead:", "~25MB added to APK")

    pdf.sub_title("Fallback Behavior")
    pdf.bullet("ML Kit returns good text -> Gemma interprets with context")
    pdf.bullet("ML Kit returns nothing -> checks for handwriting -> falls back to Gemma")
    pdf.bullet("Gemma handles handwriting, complex layouts, spatial context")

    # -- 7. Camera Pipeline --
    pdf.section_title("7. Camera Pipeline")
    pdf.body_text(
        "Vyze uses CameraX with ImageAnalysis for zero-disk-I/O frame extraction. "
        "All processing happens in memory -- no takePicture, no JPEG decode from disk."
    )

    pdf.sub_title("Frame Extraction Flow")
    pdf.bullet("ImageAnalysis.Analyzer runs on single-thread ExecutorService")
    pdf.bullet("YUV_888 -> ARGB_8888 conversion with rotation correction")
    pdf.bullet("New frame swapped into AtomicReference via getAndSet()")
    pdf.bullet("Frame counter incremented to signal fresh frame availability")

    pdf.sub_title("Snapshot Capture")
    pdf.bullet("takeSnapshot() records frameCounter at query time")
    pdf.bullet("Polls for frameCounter > counterAtQuery (guarantees fresh frame)")
    pdf.bullet("300ms timeout with fallback to latestFrame.get()")
    pdf.bullet("Deep copy via Bitmap.copy(ARGB_8888, true) before VLM")

    # -- 8. Speech Recognition & TTS --
    pdf.add_page()
    pdf.section_title("8. Speech Recognition & TTS")

    pdf.sub_title("8.1 Speech Recognition")
    pdf.body_text(
        "Uses Android's SpeechRecognizer with aggressive silence endpoints and "
        "automatic language detection for fast, hands-free interaction."
    )
    pdf.bullet("COMPLETE_SILENCE_LENGTH: 400ms (was 1500ms)")
    pdf.bullet("POSSIBLY_COMPLETE_SILENCE_LENGTH: 300ms (was 1500ms)")
    pdf.bullet("MINIMUM_LENGTH: 1000ms")
    pdf.bullet("PARTIAL_RESULTS: true (enables early intent parsing)")
    pdf.bullet("Language detection: EXTRA_LANGUAGE from speech results")
    pdf.bullet("Auto-restart after TTS completes (continuous hands-free loop)")

    pdf.sub_title("8.2 Text-to-Speech")
    pdf.body_text(
        "Neural voice synthesis via Google TTS engine with language-aware voice switching "
        "and deterministic completion tracking."
    )
    pdf.bullet("Engine: Google TTS (com.google.android.tts) for neural voice quality")
    pdf.bullet("Voice selection: Highest QUALITY_HIGH/QUALITY_VERY_HIGH voice for locale")
    pdf.bullet("Language mirroring: Auto-switches TTS voice to match detected spoken language")
    pdf.bullet("Pitch: 0.96f (-2%) for warmer, non-metallic tone")
    pdf.bullet("Rate: 0.98f (-2%) for conversational cadence")
    pdf.bullet("Audio: USAGE_ASSISTANCE_ACCESSIBILITY (non-duckable)")
    pdf.bullet("Volume: KEY_PARAM_VOLUME = 1.0f (stable full volume)")
    pdf.bullet("Audio Focus: AUDIOFOCUS_GAIN_TRANSIENT")
    pdf.bullet("Cadence: Punctuation-aware spacing for natural pauses")

    pdf.sub_title("8.3 Language Mirroring")
    pdf.body_text(
        "Vyze automatically detects the user's spoken language and mirrors it to both "
        "Gemma's output and the TTS voice. No hardcoded language lists."
    )
    pdf.bullet("SpeechRecognizer extracts EXTRA_LANGUAGE from results Bundle")
    pdf.bullet("Locale.forLanguageTag() converts BCP-47 tag to java.util.Locale")
    pdf.bullet("DynamicPromptBuilder adds: \"Respond strictly in {language}.\"")
    pdf.bullet("TTSManager.switchToLocale() selects best neural voice for language")
    pdf.bullet("Falls back to Locale.US if no voice pack installed")

    pdf.sub_title("8.4 Sentence-Buffered Streaming")
    pdf.body_text(
        "Tokens from VLM inference are buffered and flushed at natural clause boundaries "
        "for minimal time-to-first-audio."
    )
    pdf.bullet("First chunk: flush at >=1 word or >=3 characters (instant feedback)")
    pdf.bullet("Subsequent chunks: flush at commas/colons (12+ chars) or terminators (10+ chars)")
    pdf.bullet("Final flush: force-flush all remaining text on onComplete")
    pdf.bullet("Utterance ID tracking via pendingUtteranceIds (deterministic, no polling)")
    pdf.bullet("400ms post-drain grace period for AudioTrack hardware buffer")

    # -- 9. Adaptive Memory --
    pdf.section_title("9. Adaptive Memory System")
    pdf.body_text(
        "Vyze maintains a local Room database that records interactions, user preferences, "
        "and environment context to provide increasingly personalized assistance."
    )

    pdf.sub_title("Data Schema")
    pdf.code_block(
        "VyzeMemoryEntity:\n"
        "  - category: String (preference/environment/interaction)\n"
        "  - key: String\n"
        "  - value: String\n"
        "  - metadata: String (query text for interactions)\n"
        "  - timestamp: Long\n"
        "\n"
        "InteractionRecord:\n"
        "  - id: Long (auto-generated)\n"
        "  - timestamp: Long\n"
        "  - imageEmbedding: FloatArray (vector similarity)\n"
        "  - rawPrompt: String\n"
        "  - generatedOutput: String\n"
        "  - userFeedback: String"
    )

    pdf.sub_title("Contextual Prompt Injection")
    pdf.bullet("Similar past interactions retrieved via vector similarity search")
    pdf.bullet("User preferences injected as compact section in prompt")
    pdf.bullet("Environment context included for spatial continuity")
    pdf.bullet("All queries execute off-main-thread via Dispatchers.IO")

    # -- 10. Performance Optimizations --
    pdf.add_page()
    pdf.section_title("10. Performance Optimizations")

    optimizations = [
        ("Prompt Trimming", "Compact system directives: ~80 tokens prefill (was ~875 tokens)"),
        ("ML Kit OCR Pre-pass", "Text queries: 300-500ms (was 4-6s with VLM only)"),
        ("Dynamic Resolution", "256px for scenes, 384px for text (auto-detected by keywords)"),
        ("Greedy Decoding", "maxTokens=35, temperature=0.1, topK=1 for fastest generation"),
        ("First-Chunk Flush", "1 word / 3 chars triggers first TTS audio (~200ms)"),
        ("GPU Warm-up", "Dummy 1x1 image pre-compiles OpenCL/Vulkan kernels on init"),
        ("JPEG Optimization", "Quality 75 + pre-allocated 8KB buffer (15-30ms faster)"),
        ("Zero Disk I/O", "In-memory Bitmap buffer from ImageAnalysis"),
        ("Frame Counter", "Guarantees fresh frame on every voice query"),
        ("Engine Interrupt", "latch.countDown() releases await without destroying engine"),
        ("Session Isolation", "UUID-based activeSessionId prevents stale callback leakage"),
        ("NPU/GPU Fallback", "Auto-tries NPU first, falls back to GPU"),
        ("Language Mirroring", "Auto-detect + respond in user's language"),
        ("Barge-In", "Instant TTS stop on touch/speech input"),
        ("Watchdog Timer", "15s timeout prevents indefinite ANALYZING state"),
    ]
    for title, desc in optimizations:
        pdf.bullet(f"{title}: {desc}")

    # -- 11. Safety & Stability --
    pdf.section_title("11. Safety & Stability")

    pdf.sub_title("State Machine")
    pdf.code_block(
        "IDLE -> (tap/speech) -> ANALYZING -> (VLM complete) -> SPEAKING -> (TTS done) -> IDLE\n"
        "IDLE -> (barge-in) -> ANALYZING (cancels previous + stops TTS)\n"
        "ANALYZING -> (15s timeout) -> IDLE + error speech"
    )

    pdf.sub_title("Crash Prevention")
    pdf.bullet("15-second watchdog timer forces ANALYZING -> IDLE if inference hangs")
    pdf.bullet("Bitmap.isRecycled + getPixel(0,0) corruption check before VLM")
    pdf.bullet("Session ID gating on all callbacks (onTokenGenerated, onComplete, onError)")
    pdf.bullet("Coroutine Job cancellation with isActive checks at 3 points")
    pdf.bullet("Non-destructive interrupt: engine stays alive for next query")
    pdf.bullet("1-second debounce on trigger events prevents double-fire")

    pdf.sub_title("Memory Management")
    pdf.bullet("Bitmap.recycle() in finally blocks for original and scaled bitmaps")
    pdf.bullet("Conversation lifecycle: manual close() (not use{}) to prevent SIGSEGV")
    pdf.bullet("Coroutines 1.10.1 (fixed SendChannel.close$default crash with Kotlin 2.3.0)")

    # -- 12. Tech Stack --
    pdf.add_page()
    pdf.section_title("12. Technology Stack")

    pdf.sub_title("Languages & Frameworks")
    pdf.kv_row("Language:", "Kotlin 2.3.0")
    pdf.kv_row("Build System:", "Gradle 8.14.3 + Kotlin DSL")
    pdf.kv_row("Min SDK:", "26 (Android 8.0)")
    pdf.kv_row("Target SDK:", "34 (Android 14)")
    pdf.kv_row("Architecture:", "ARM64-v8a only (64-bit GPU delegates)")

    pdf.sub_title("AI & ML")
    pdf.kv_row("VLM Model:", "Gemma 3n E2B int4 (3.66 GB)")
    pdf.kv_row("Inference:", "LiteRT-LM 0.16.1 (Google)")
    pdf.kv_row("Backend:", "NPU -> GPU fallback chain")
    pdf.kv_row("OCR:", "ML Kit Text Recognition (Latin + Chinese)")
    pdf.kv_row("OCR Size:", "~25MB total (5MB Latin + 20MB Chinese)")

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
    pdf.kv_row("NDK Filters:", "arm64-v8a only")

    # -- 13. File Structure --
    pdf.section_title("13. Key File Structure")

    files = [
        ("VyzeApplication.kt", "App singleton, dependency injection, global error handler"),
        ("MainActivity.kt", "Speech recognition, language detection, TTS orchestration"),
        ("VyzeCoreController.kt", "Pipeline orchestrator, OCR routing, session management"),
        ("VlmEngineManager.kt", "LiteRT-LM wrapper, NPU/GPU fallback, Gemma engine lifecycle"),
        ("DynamicPromptBuilder.kt", "Prompt assembly with language mirror + intent-based rules"),
        ("OcrHelper.kt", "ML Kit OCR wrapper (Latin + Chinese scripts)"),
        ("CameraSetupDelegate.kt", "CameraX setup, frame buffer, bitmap extraction"),
        ("CameraFragment.kt", "Camera UI, voice triggers, continuous mode, state machine"),
        ("TTSManager.kt", "Neural TTS, voice switching, utterance tracking, audio focus"),
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
