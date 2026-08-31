#!/usr/bin/env python3
"""Generate a comprehensive pitch deck PDF for Vyze."""

from fpdf import FPDF
import os


class PitchDeck(FPDF):
    def header(self):
        if self.page_no() > 1:
            self.set_font("Helvetica", "B", 9)
            self.set_text_color(100, 100, 100)
            self.cell(0, 8, "Vyze  |  Offline AI Vision Assistant", align="L")
            self.ln(10)

    def footer(self):
        self.set_y(-15)
        self.set_font("Helvetica", "I", 8)
        self.set_text_color(128, 128, 128)
        self.cell(0, 10, f"{self.page_no()}", align="C")

    def cover_page(self, title, subtitle, tagline):
        self.add_page()
        self.set_font("Helvetica", "B", 36)
        self.set_text_color(25, 80, 180)
        self.ln(50)
        self.cell(0, 18, title, align="C")
        self.ln(22)
        self.set_font("Helvetica", "", 16)
        self.set_text_color(60, 60, 60)
        self.cell(0, 10, subtitle, align="C")
        self.ln(16)
        self.set_font("Helvetica", "I", 12)
        self.set_text_color(120, 120, 120)
        self.cell(0, 8, tagline, align="C")
        self.ln(30)
        # Blue accent line
        self.set_draw_color(25, 80, 180)
        self.set_line_width(0.8)
        self.line(60, self.get_y(), 150, self.get_y())
        self.ln(10)
        self.set_font("Helvetica", "", 10)
        self.set_text_color(150, 150, 150)
        self.cell(0, 6, "Confidential  |  2025", align="C")

    def slide_title(self, number, title):
        self.set_font("Helvetica", "B", 10)
        self.set_text_color(25, 80, 180)
        self.cell(10, 8, f"{number}")
        self.set_font("Helvetica", "B", 14)
        self.set_text_color(30, 30, 30)
        self.cell(0, 8, title)
        self.ln(10)
        self.set_draw_color(25, 80, 180)
        self.set_line_width(0.4)
        self.line(self.l_margin, self.get_y(), 200, self.get_y())
        self.ln(6)

    def big_stat(self, number, label):
        self.set_font("Helvetica", "B", 28)
        self.set_text_color(25, 80, 180)
        self.cell(0, 14, number, align="C")
        self.ln(14)
        self.set_font("Helvetica", "", 10)
        self.set_text_color(80, 80, 80)
        self.cell(0, 6, label, align="C")
        self.ln(10)

    def stat_row(self, stat, description):
        self.set_font("Helvetica", "B", 11)
        self.set_text_color(25, 80, 180)
        self.cell(35, 7, stat)
        self.set_font("Helvetica", "", 10)
        self.set_text_color(60, 60, 60)
        self.multi_cell(0, 7, description)
        self.ln(1)

    def body(self, text):
        self.set_font("Helvetica", "", 10)
        self.set_text_color(50, 50, 50)
        self.multi_cell(0, 5.5, text)
        self.ln(2)

    def bullet(self, text):
        self.set_font("Helvetica", "", 10)
        self.set_text_color(50, 50, 50)
        self.cell(6, 5.5, chr(149))
        self.multi_cell(0, 5.5, text)
        self.ln(1)

    def bold_bullet(self, bold_part, normal_part):
        x = self.get_x()
        self.set_font("Helvetica", "", 10)
        self.set_text_color(50, 50, 50)
        self.cell(6, 5.5, chr(149))
        self.set_font("Helvetica", "B", 10)
        w_bold = self.get_string_width(bold_part)
        self.cell(w_bold, 5.5, bold_part)
        self.set_font("Helvetica", "", 10)
        self.multi_cell(0, 5.5, normal_part)
        self.ln(1)

    def quote(self, text, source=""):
        self.set_font("Helvetica", "I", 11)
        self.set_text_color(80, 80, 80)
        self.set_x(25)
        self.multi_cell(160, 6, f'"{text}"')
        if source:
            self.set_font("Helvetica", "", 9)
            self.set_text_color(140, 140, 140)
            self.set_x(25)
            self.cell(160, 5, f"- {source}", align="R")
        self.ln(6)

    def section_divider(self, title):
        self.add_page()
        self.set_fill_color(25, 80, 180)
        self.rect(0, 0, 210, 297, "F")
        self.set_font("Helvetica", "B", 28)
        self.set_text_color(255, 255, 255)
        self.ln(90)
        self.cell(0, 15, title, align="C")
        self.ln(20)
        self.set_draw_color(255, 255, 255)
        self.set_line_width(0.6)
        self.line(70, self.get_y(), 140, self.get_y())


def main():
    pdf = PitchDeck()
    pdf.set_auto_page_break(auto=True, margin=20)

    # ═══════════════════════════════════════════════
    # COVER
    # ═══════════════════════════════════════════════
    pdf.cover_page(
        "Vyze",
        "Offline AI Vision Assistant",
        "See for those who cannot. Everywhere. Instantly. Privately."
    )

    # ═══════════════════════════════════════════════
    # THE PROBLEM
    # ═══════════════════════════════════════════════
    pdf.section_divider("THE PROBLEM")

    pdf.add_page()
    pdf.slide_title("01", "A World Designed for the Sighted")

    pdf.body(
        "Over 2.2 billion people worldwide live with vision impairment "
        "(WHO, 2026). Of these, at least 1 billion could have been "
        "prevented or remain untreated. By 2050, this number will "
        "increase by 55% -- adding 600 million more people."
    )
    pdf.ln(2)

    pdf.quote(
        "Every 5 seconds, someone in the world goes blind.",
        "International Agency for the Prevention of Blindness"
    )

    pdf.body(
        "For the 61,000+ registered visually impaired individuals "
        "in Malaysia alone, daily tasks that sighted people take for "
        "granted -- reading a label, crossing a street, identifying "
        "currency, recognizing a friend -- remain constant challenges."
    )

    # ═══════════════════════════════════════════════
    # PAGE: WHY EXISTING SOLUTIONS FAIL
    # ═══════════════════════════════════════════════
    pdf.slide_title("02", "Why Existing Solutions Fall Short")

    pdf.ln(4)

    # Table
    pdf.set_font("Helvetica", "B", 10)
    pdf.set_fill_color(25, 80, 180)
    pdf.set_text_color(255, 255, 255)
    pdf.cell(50, 8, "  Limitation", fill=True)
    pdf.cell(0, 8, "  Impact on Daily Life", fill=True)
    pdf.ln()

    rows = [
        ("Requires Internet", "Fails underground, in rural areas, on planes, in developing regions"),
        ("Cloud Latency", "1-3 second delay per query makes real-time navigation impossible"),
        ("Privacy Risk", "Camera images of home, medication, documents uploaded to third-party servers"),
        ("Subscription Cost", "Monthly fees create financial barriers for disabled users"),
        ("No Memory", "App never learns user's environment, routes, or preferences"),
        ("English Only", "Excludes 80% of the global visually impaired population"),
    ]

    pdf.set_font("Helvetica", "", 9)
    pdf.set_text_color(50, 50, 50)
    for i, (limitation, impact) in enumerate(rows):
        if i % 2 == 0:
            pdf.set_fill_color(240, 245, 255)
        else:
            pdf.set_fill_color(255, 255, 255)
        pdf.set_font("Helvetica", "B", 9)
        pdf.cell(50, 7, f"  {limitation}", fill=True)
        pdf.set_font("Helvetica", "", 9)
        pdf.cell(0, 7, f"  {impact}", fill=True)
        pdf.ln()

    pdf.ln(6)
    pdf.body(
        "The result: visually impaired users are forced to choose "
        "between connectivity-dependent tools that fail when they "
        "need them most, and basic assistive devices that offer "
        "no intelligence at all."
    )

    # ═══════════════════════════════════════════════
    # THE SOLUTION
    # ═══════════════════════════════════════════════
    pdf.section_divider("THE SOLUTION")

    pdf.add_page()
    pdf.slide_title("03", "Vyze: AI That Sees for You -- Offline")

    pdf.ln(2)
    pdf.body(
        "Vyze is a fully offline AI vision assistant that speaks "
        "what the camera sees -- instantly, privately, and without "
        "ever connecting to the internet."
    )
    pdf.ln(2)

    # Key value props in boxes
    props = [
        ("100% Offline", "No internet needed. Works underground, on planes, in rural areas. Forever free."),
        ("Instant Response", "First audio in under 1 second. Sentence-streamed TTS for real-time interaction."),
        ("Learns Over Time", "Adaptive memory remembers your home, office, routes, and preferences."),
        ("Speaks Your Language", "Auto-detects spoken language and responds in it. No manual switching."),
    ]

    for title, desc in props:
        pdf.set_font("Helvetica", "B", 11)
        pdf.set_text_color(25, 80, 180)
        pdf.cell(0, 7, title)
        pdf.ln(7)
        pdf.set_font("Helvetica", "", 10)
        pdf.set_text_color(60, 60, 60)
        pdf.multi_cell(0, 5.5, desc)
        pdf.ln(3)

    # ═══════════════════════════════════════════════
    # PAGE: HOW IT WORKS
    # ═══════════════════════════════════════════════
    pdf.slide_title("04", "How It Works")

    pdf.ln(2)

    steps = [
        ("SPEAK", "User asks a question naturally: \"What medicine is this?\" or \"What's in front of me?\""),
        ("CAPTURE", "Camera captures a fresh frame from the live preview -- zero disk I/O, all in memory."),
        ("READ", "ML Kit OCR extracts text in 80-150ms. Gemma 3n E2B interprets with full context."),
        ("RESPOND", "Sentence-buffered TTS speaks the answer aloud as it generates -- no waiting."),
        ("REMEMBER", "Interaction stored in local database. Next time, Vyze provides smarter context."),
    ]

    for i, (step, desc) in enumerate(steps):
        # Step number circle
        pdf.set_fill_color(25, 80, 180)
        pdf.set_text_color(255, 255, 255)
        pdf.set_font("Helvetica", "B", 11)
        x = pdf.get_x()
        y = pdf.get_y()
        pdf.circle(x + 5, y + 3, 5, "F")
        pdf.set_xy(x + 2, y + 0.5)
        pdf.cell(6, 5, str(i + 1), align="C")
        pdf.set_xy(x + 15, y)

        pdf.set_font("Helvetica", "B", 11)
        pdf.set_text_color(25, 80, 180)
        pdf.cell(20, 7, step)
        pdf.set_font("Helvetica", "", 10)
        pdf.set_text_color(60, 60, 60)
        pdf.multi_cell(0, 5.5, desc)
        pdf.ln(3)

    # ═══════════════════════════════════════════════
    # PAGE: DEMO SCENARIO
    # ═══════════════════════════════════════════════
    pdf.slide_title("05", "User Experience: A Day with Vyze")

    pdf.ln(2)

    scenarios = [
        ("Morning -- Medicine",
         "\"What is this?\" Vyze reads the prescription label: \"This is Diclac Retard, "
         "a pain relief medication containing diclofenac sodium. Take one tablet daily "
         "after meals.\" Response time: 400ms."),
        ("Commute -- Navigation",
         "Continuous mode auto-describes surroundings: \"Bus stop ahead on your left, "
         "about 10 steps. Two people waiting. Sign reads RapidKL Route 300.\""),
        ("Office -- Document",
         "\"Read this.\" Vyze extracts text from a meeting agenda via ML Kit OCR in "
         "150ms, then reads it aloud with contextual formatting."),
        ("Evening -- Shopping",
         "\"How much is this?\" Vyze identifies the Malaysian ringgit banknote: "
         "\"This is a 20 ringgit note.\" Instant, private, no internet."),
    ]

    for title, desc in scenarios:
        pdf.set_font("Helvetica", "B", 10)
        pdf.set_text_color(25, 80, 180)
        pdf.cell(0, 7, title)
        pdf.ln(7)
        pdf.set_font("Helvetica", "", 10)
        pdf.set_text_color(60, 60, 60)
        pdf.multi_cell(0, 5.5, desc)
        pdf.ln(3)

    # ═══════════════════════════════════════════════
    # TECHNOLOGY
    # ═══════════════════════════════════════════════
    pdf.section_divider("TECHNOLOGY")

    pdf.add_page()
    pdf.slide_title("06", "What Powers Vyze")

    pdf.ln(2)

    tech = [
        ("Gemma 3n E2B", "Google's 2-billion parameter multimodal vision-language model, "
         "int4 quantized to 3.66 GB. Runs entirely on-device via LiteRT-LM with "
         "NPU/GPU acceleration."),
        ("ML Kit OCR", "Google's on-device text recognition supporting Latin (English, Malay, "
         "Indonesian, Vietnamese) and Chinese scripts. 80-150ms extraction time."),
        ("Adaptive Memory", "Room database with vector similarity search. Learns user's "
         "environment, routes, and preferences over time."),
        ("Language Mirroring", "Auto-detects spoken language via Android SpeechRecognizer, "
         "mirrors to both AI output and TTS voice. No hardcoded language lists."),
        ("Sentence Streaming", "Tokens stream directly to TTS as they generate. First audio "
         "fires after just 3 characters -- no waiting for full response."),
    ]

    for title, desc in tech:
        pdf.set_font("Helvetica", "B", 11)
        pdf.set_text_color(25, 80, 180)
        pdf.cell(0, 7, title)
        pdf.ln(7)
        pdf.set_font("Helvetica", "", 10)
        pdf.set_text_color(60, 60, 60)
        pdf.multi_cell(0, 5.5, desc)
        pdf.ln(3)

    # ═══════════════════════════════════════════════
    # PAGE: PERFORMANCE
    # ═══════════════════════════════════════════════
    pdf.slide_title("07", "Performance Benchmarks")

    pdf.ln(2)

    benchmarks = [
        ("Text Query (OCR)", "300-500ms", "ML Kit + Gemma interpretation"),
        ("Scene Description", "1.5-2s", "Gemma 3n E2B direct inference"),
        ("First Audio", "<1s", "Sentence-buffered TTS streaming"),
        ("Prefill Tokens", "~80", "Aggressively trimmed prompts"),
        ("Image Size", "256x256", "Center-cropped, spatially aligned"),
        ("Voice Detection", "300ms", "Aggressive silence endpoints"),
        ("Model Loading", "2-4s", "GPU warm-up on first launch"),
    ]

    # Header
    pdf.set_font("Helvetica", "B", 10)
    pdf.set_fill_color(25, 80, 180)
    pdf.set_text_color(255, 255, 255)
    pdf.cell(55, 8, "  Metric", fill=True)
    pdf.cell(35, 8, "  Result", fill=True)
    pdf.cell(0, 8, "  How", fill=True)
    pdf.ln()

    pdf.set_font("Helvetica", "", 9)
    pdf.set_text_color(50, 50, 50)
    for i, (metric, result, how) in enumerate(benchmarks):
        if i % 2 == 0:
            pdf.set_fill_color(240, 245, 255)
        else:
            pdf.set_fill_color(255, 255, 255)
        pdf.set_font("Helvetica", "B", 9)
        pdf.cell(55, 7, f"  {metric}", fill=True)
        pdf.set_font("Helvetica", "", 9)
        pdf.cell(35, 7, f"  {result}", fill=True)
        pdf.cell(0, 7, f"  {how}", fill=True)
        pdf.ln()

    # ═══════════════════════════════════════════════
    # MARKET
    # ═══════════════════════════════════════════════
    pdf.section_divider("MARKET OPPORTUNITY")

    pdf.add_page()
    pdf.slide_title("08", "The Market is Massive and Underserved")

    pdf.ln(4)

    # Big numbers
    pdf.set_font("Helvetica", "B", 32)
    pdf.set_text_color(25, 80, 180)
    pdf.cell(0, 15, "2.2 Billion", align="C")
    pdf.ln(14)
    pdf.set_font("Helvetica", "", 11)
    pdf.set_text_color(80, 80, 80)
    pdf.cell(0, 7, "People worldwide with vision impairment (WHO, 2026)", align="C")
    pdf.ln(12)

    pdf.set_font("Helvetica", "B", 32)
    pdf.set_text_color(25, 80, 180)
    pdf.cell(0, 15, "$410.7B", align="C")
    pdf.ln(14)
    pdf.set_font("Helvetica", "", 11)
    pdf.set_text_color(80, 80, 80)
    pdf.cell(0, 7, "Annual global productivity loss from sight loss (IAPB, 2024)", align="C")
    pdf.ln(12)

    pdf.set_font("Helvetica", "B", 32)
    pdf.set_text_color(25, 80, 180)
    pdf.cell(0, 15, "61,000+", align="C")
    pdf.ln(14)
    pdf.set_font("Helvetica", "", 11)
    pdf.set_text_color(80, 80, 80)
    pdf.cell(0, 7, "Registered visually impaired in Malaysia (DOSM, 2024)", align="C")
    pdf.ln(12)

    # ═══════════════════════════════════════════════
    # PAGE: MARKET SEGMENTS
    # ═══════════════════════════════════════════════
    pdf.slide_title("09", "Target Segments")

    pdf.ln(2)

    segments = [
        ("Blind & Low-Vision Users", "Primary users. Need real-time scene understanding, "
         "text reading, and navigation assistance. 2.2 billion globally, 61,000+ in Malaysia."),
        ("Elderly Population", "Age-related vision decline affects 27.8% of Malaysians over 50. "
         "Simple voice interface requires zero technical literacy."),
        ("Developing Regions", "Areas with limited internet connectivity where cloud-based "
         "assistive tools simply don't work."),
        ("Multilingual Communities", "Southeast Asia's diverse linguistic landscape needs "
         "tools that adapt to the user's language, not the other way around."),
    ]

    for title, desc in segments:
        pdf.set_font("Helvetica", "B", 11)
        pdf.set_text_color(25, 80, 180)
        pdf.cell(0, 7, title)
        pdf.ln(7)
        pdf.set_font("Helvetica", "", 10)
        pdf.set_text_color(60, 60, 60)
        pdf.multi_cell(0, 5.5, desc)
        pdf.ln(3)

    # ═══════════════════════════════════════════════
    # COMPETITIVE ADVANTAGE
    # ═══════════════════════════════════════════════
    pdf.section_divider("COMPETITIVE EDGE")

    pdf.add_page()
    pdf.slide_title("10", "Vyze vs. Existing Solutions")

    pdf.ln(2)

    # Comparison table
    pdf.set_font("Helvetica", "B", 8)
    pdf.set_fill_color(25, 80, 180)
    pdf.set_text_color(255, 255, 255)
    pdf.cell(40, 7, "  Feature", fill=True)
    pdf.cell(28, 7, "  Vyze", fill=True)
    pdf.cell(28, 7, "  Be My AI", fill=True)
    pdf.cell(28, 7, "  Seeing AI", fill=True)
    pdf.cell(28, 7, "  Envision", fill=True)
    pdf.cell(0, 7, "  Lazarillo", fill=True)
    pdf.ln()

    comparisons = [
        ("Fully Offline", "Yes", "No", "Partial", "No", "Partial"),
        ("VLM on Device", "Yes", "No", "No", "No", "No"),
        ("Adaptive Memory", "Yes", "No", "No", "No", "No"),
        ("Multi-Language", "Yes", "No", "Yes", "Yes", "Yes"),
        ("Language Mirroring", "Yes", "No", "No", "No", "No"),
        ("OCR Speed", "300ms", "2-3s", "1s", "1.5s", "N/A"),
        ("First Audio", "<1s", "3-5s", "2-3s", "2-3s", "1-2s"),
        ("Cost", "Free", "Free*", "Free*", "Sub", "Free"),
        ("Privacy", "Full", "Cloud", "Cloud", "Cloud", "Cloud"),
    ]

    pdf.set_font("Helvetica", "", 8)
    pdf.set_text_color(50, 50, 50)
    for i, row in enumerate(comparisons):
        if i % 2 == 0:
            pdf.set_fill_color(240, 245, 255)
        else:
            pdf.set_fill_color(255, 255, 255)
        pdf.set_font("Helvetica", "B", 8)
        pdf.cell(40, 6, f"  {row[0]}", fill=True)
        pdf.set_font("Helvetica", "", 8)
        # Highlight Vyze column
        pdf.set_text_color(25, 120, 50)
        pdf.cell(28, 6, f"  {row[1]}", fill=True)
        pdf.set_text_color(50, 50, 50)
        pdf.cell(28, 6, f"  {row[2]}", fill=True)
        pdf.cell(28, 6, f"  {row[3]}", fill=True)
        pdf.cell(28, 6, f"  {row[4]}", fill=True)
        pdf.cell(0, 6, f"  {row[5]}", fill=True)
        pdf.ln()

    pdf.ln(4)
    pdf.set_font("Helvetica", "I", 9)
    pdf.set_text_color(120, 120, 120)
    pdf.cell(0, 5, "* Requires internet connection for core functionality")
    pdf.ln(10)

    pdf.body(
        "Vyze is the only solution that combines fully offline VLM inference, "
        "adaptive memory, language mirroring, and sentence-streamed TTS -- "
        "all without any cloud dependency or subscription cost."
    )

    # ═══════════════════════════════════════════════
    # PAGE: UNIQUE MOAT
    # ═══════════════════════════════════════════════
    pdf.slide_title("11", "What Makes Vyze Defensible")

    pdf.ln(2)

    moats = [
        ("Offline-First Architecture",
         "While competitors race to add cloud features, Vyze's constraint "
         "is its strength. It works where nothing else does -- underground, "
         "in rural areas, on planes, in developing regions."),
        ("Adaptive Intelligence Loop",
         "Every interaction makes Vyze smarter. The local memory system "
         "creates personalized spatial awareness that cloud competitors "
         "cannot replicate without violating privacy."),
        ("Hybrid OCR + VLM Pipeline",
         "ML Kit for speed, Gemma for intelligence. This dual-engine "
         "approach delivers 10-30x faster text extraction than competitors."),
        ("Zero Recurring Costs",
         "No API fees, no subscriptions, no data plans. The model runs "
         "on hardware the user already owns. This is the most sustainable "
         "business model for accessibility."),
    ]

    for title, desc in moats:
        pdf.set_font("Helvetica", "B", 11)
        pdf.set_text_color(25, 80, 180)
        pdf.cell(0, 7, title)
        pdf.ln(7)
        pdf.set_font("Helvetica", "", 10)
        pdf.set_text_color(60, 60, 60)
        pdf.multi_cell(0, 5.5, desc)
        pdf.ln(3)

    # ═══════════════════════════════════════════════
    # ROADMAP
    # ═══════════════════════════════════════════════
    pdf.section_divider("ROADMAP")

    pdf.add_page()
    pdf.slide_title("12", "Product Roadmap")

    pdf.ln(2)

    pdf.set_font("Helvetica", "B", 12)
    pdf.set_text_color(25, 80, 180)
    pdf.cell(0, 8, "Phase 1: Core Experience")
    pdf.ln(10)

    phase1 = [
        "Currency detection (Malaysian ringgit banknotes)",
        "Offline OpenStreetMap navigation with GPS",
        "Multi-language OCR expansion (Hindi, Arabic, Thai)",
        "Medication reminder system with prescription OCR",
        "Scene history and visual diary",
    ]
    for item in phase1:
        pdf.bullet(item)
    pdf.ln(4)

    pdf.set_font("Helvetica", "B", 12)
    pdf.set_text_color(25, 80, 180)
    pdf.cell(0, 8, "Phase 2: Intelligence Layer")
    pdf.ln(10)

    phase2 = [
        "Smart glasses integration (Ray-Ban Meta, Envision Glasses)",
        "Real-time object tracking across frames",
        "Public transport announcements (offline GTFS feeds)",
        "Contact/face recognition for personal identification",
        "Indoor navigation with user-recorded floor plans",
    ]
    for item in phase2:
        pdf.bullet(item)
    pdf.ln(4)

    pdf.set_font("Helvetica", "B", 12)
    pdf.set_text_color(25, 80, 180)
    pdf.cell(0, 8, "Phase 3: Scale & Impact")
    pdf.ln(10)

    phase3 = [
        "Smaller model routing (fast model for simple queries)",
        "Federated learning for privacy-preserving pattern sharing",
        "Haptic navigation via smartwatch integration",
        "Emergency detection and auto-alert system",
        "Social media photo description sharing",
    ]
    for item in phase3:
        pdf.bullet(item)

    # ═══════════════════════════════════════════════
    # PAGE: BUSINESS MODEL
    # ═══════════════════════════════════════════════
    pdf.slide_title("13", "Business Model")

    pdf.ln(2)

    pdf.body(
        "Vyze's core product will always be free and open-source. "
        "Sustainability comes from complementary revenue streams "
        "that don't compromise the accessibility mission."
    )
    pdf.ln(2)

    models = [
        ("Hardware Partnerships", "Co-develop smart glasses and wearable accessories. "
         "Revenue from hardware sales, not software subscriptions."),
        ("Enterprise Licensing", "Hospitals, rehabilitation centers, and care facilities "
         "deploy Vyze for patient assistance. Per-device enterprise licenses."),
        ("Government & NGO Grants", "Disability rights organizations, WHO, and national "
         "health departments fund distribution to underserved communities."),
        ("Premium Features", "Advanced features like multi-device sync, custom model "
         "training, and priority support for power users."),
    ]

    for title, desc in models:
        pdf.set_font("Helvetica", "B", 10)
        pdf.set_text_color(25, 80, 180)
        pdf.cell(0, 7, title)
        pdf.ln(7)
        pdf.set_font("Helvetica", "", 10)
        pdf.set_text_color(60, 60, 60)
        pdf.multi_cell(0, 5.5, desc)
        pdf.ln(2)

    # ═══════════════════════════════════════════════
    # PAGE: IMPACT
    # ═══════════════════════════════════════════════
    pdf.section_divider("IMPACT")

    pdf.add_page()
    pdf.slide_title("14", "Why This Matters")

    pdf.ln(4)

    pdf.quote(
        "The real problem is not whether machines think, "
        "but whether men do.",
        "B.F. Skinner"
    )

    pdf.ln(2)
    pdf.body(
        "Vyze is not just an app. It is a statement that "
        "artificial intelligence should serve everyone -- "
        "not just those with internet access, not just "
        "English speakers, not just those who can afford "
        "monthly subscriptions."
    )
    pdf.ln(2)
    pdf.body(
        "When a blind person in rural Malaysia can point "
        "their phone at a medicine label and instantly "
        "hear what it says -- in their language, "
        "privately, for free -- that is technology "
        "fulfilling its highest purpose."
    )
    pdf.ln(2)
    pdf.body(
        "Vyze makes the invisible visible. "
        "Offline. Instantly. For everyone."
    )

    # ═══════════════════════════════════════════════
    # PAGE: CLOSING
    # ═══════════════════════════════════════════════
    pdf.add_page()
    pdf.ln(60)
    pdf.set_font("Helvetica", "B", 32)
    pdf.set_text_color(25, 80, 180)
    pdf.cell(0, 15, "Vyze", align="C")
    pdf.ln(20)
    pdf.set_font("Helvetica", "", 14)
    pdf.set_text_color(80, 80, 80)
    pdf.cell(0, 8, "See for those who cannot.", align="C")
    pdf.ln(12)
    pdf.set_font("Helvetica", "I", 11)
    pdf.set_text_color(120, 120, 120)
    pdf.cell(0, 7, "Everywhere. Instantly. Privately.", align="C")
    pdf.ln(30)

    # Blue accent line
    pdf.set_draw_color(25, 80, 180)
    pdf.set_line_width(0.8)
    pdf.line(60, pdf.get_y(), 150, pdf.get_y())
    pdf.ln(15)

    pdf.set_font("Helvetica", "", 10)
    pdf.set_text_color(100, 100, 100)
    pdf.cell(0, 6, "github.com/moreese84/Vyze", align="C")
    pdf.ln(6)
    pdf.cell(0, 6, "Built for accessibility. Designed for independence.", align="C")

    # -- Save --
    output_path = os.path.join(
        os.path.dirname(os.path.abspath(__file__)),
        "Vyze_Pitch_Deck.pdf"
    )
    pdf.output(output_path)
    print(f"Pitch deck generated: {output_path}")


if __name__ == "__main__":
    main()
