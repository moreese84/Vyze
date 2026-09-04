#!/usr/bin/env python3
"""
Build a styled PDF of WHITEPAPER.md using headless Chrome.

Usage: python whitepaper/build_pdf.py
Output: whitepaper/Vyze-Technical-Whitepaper.{html,pdf}

No external Python dependencies. Renders the repo WHITEPAPER.md into a
print-styled A4 document with a cover page, then prints it to PDF via
headless Chrome.
"""

import html as html_mod
import os
import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
MD_PATH = ROOT / "WHITEPAPER.md"
OUT_DIR = Path(__file__).resolve().parent
HTML_PATH = OUT_DIR / "Vyze-Technical-Whitepaper.html"
PDF_PATH = OUT_DIR / "Vyze-Technical-Whitepaper.pdf"

BRAND_RED = "#CE2028"
BRAND_YELLOW = "#FBD10A"

CSS = """
@page { size: A4; margin: 16mm 15mm 18mm 15mm; }

* { box-sizing: border-box; }
html, body { margin: 0; padding: 0; }

body {
  font-family: "Segoe UI", "Helvetica Neue", Arial, "Microsoft YaHei", sans-serif;
  font-size: 10pt;
  line-height: 1.55;
  color: #1b1b1f;
  -webkit-print-color-adjust: exact;
  print-color-adjust: exact;
}

/* ── Cover ─────────────────────────────────────────── */
.cover {
  height: 256mm;
  page-break-after: always;
  position: relative;
  display: flex;
  flex-direction: column;
}
.cover .dots { position: absolute; top: 0; left: 0; right: 0; height: 90mm; overflow: hidden; }
.cover .dot { position: absolute; border-radius: 50%; }
.cover .dot-red { background: @RED@; }
.cover .dot-yellow { background: @YELLOW@; }
.cover-main { flex: 1; display: flex; flex-direction: column; justify-content: center; }
.wordmark {
  font-size: 15pt; font-weight: 800; letter-spacing: 10px; color: #111;
  margin-bottom: 6mm; display: flex; align-items: center; gap: 2mm;
}
.wordmark .sw { width: 3.2mm; height: 3.2mm; border-radius: 50%; display: inline-block; }
.sw-red { background: @RED@; }
.sw-yellow { background: @YELLOW@; }
.cover h1 {
  font-size: 33pt; line-height: 1.08; margin: 0 0 7mm 0;
  letter-spacing: -0.5px; color: #0d0d0f; font-weight: 800;
}
.cover h1 .amp { color: @RED@; }
.cover .tagline {
  font-size: 13.5pt; color: #444; max-width: 150mm;
  line-height: 1.5; margin: 0 0 14mm 0; font-weight: 400;
}
.cover .rule { height: 1.1mm; width: 32mm; background: @RED@; margin-bottom: 10mm; }
.facts { width: 100%; border-collapse: collapse; }
.facts td {
  padding: 3.1mm 0; border-bottom: 0.35pt solid #e2e2e6;
  vertical-align: top; font-size: 10.5pt;
}
.facts td.k {
  width: 34mm; color: #777; text-transform: uppercase;
  font-size: 8pt; font-weight: 700; letter-spacing: 1.6px; padding-top: 3.6mm;
}
.facts tr:last-child td { border-bottom: none; }
.cover-foot { margin-top: 10mm; font-size: 9pt; color: #999; letter-spacing: 0.4px; }

/* ── Body ──────────────────────────────────────────── */
main { }
h2 {
  font-size: 15.5pt; color: #101014; margin: 9mm 0 3.5mm 0;
  padding-bottom: 1.8mm; border-bottom: 0.6pt solid #d8d8de;
  break-after: avoid; page-break-after: avoid;
}
h2 .no { color: @RED@; }
h3 {
  font-size: 12pt; color: #202024; margin: 6mm 0 2.2mm 0;
  break-after: avoid; page-break-after: avoid;
}
p { margin: 0 0 3mm 0; }
ul, ol { margin: 0 0 3mm 0; padding-left: 6.5mm; }
li { margin-bottom: 1.4mm; }
li > ul, li > ol { margin-top: 1.4mm; }
code {
  font-family: Consolas, "Cascadia Mono", monospace;
  font-size: 8.7pt; background: #f1f1f4; border-radius: 2pt;
  padding: 0.3pt 2.5pt; color: #111;
}
pre {
  background: #f6f6f8; border: 0.5pt solid #e3e3e8; border-left: 2.5pt solid #d8d8de;
  border-radius: 3pt; padding: 4mm 5mm; overflow: hidden;
  break-inside: avoid; page-break-inside: avoid;
  margin: 3.5mm 0;
}
pre code { background: none; padding: 0; font-size: 8.4pt; }
pre.art {
  font-family: Consolas, "Cascadia Mono", "MS Gothic", "Segoe UI Symbol", monospace;
  font-size: 7.8pt; line-height: 1.3; letter-spacing: 0.2px;
}
pre.art code { font-size: 7.8pt; }
table { width: 100%; border-collapse: collapse; margin: 3.5mm 0 4.5mm 0; font-size: 9.3pt; }
th {
  text-align: left; background: #f0f0f3; color: #0d0d0f;
  font-weight: 700; padding: 2.2mm 3mm; border-bottom: 0.9pt solid #c9c9d2;
  font-size: 8.6pt; text-transform: uppercase; letter-spacing: 0.8px;
}
td { padding: 2mm 3mm; border-bottom: 0.35pt solid #e0e0e5; vertical-align: top; }
tr { break-inside: avoid; page-break-inside: avoid; }
tbody tr:nth-child(even) { background: #fafafc; }
blockquote {
  margin: 3.5mm 0 4mm 0; padding: 2.5mm 5mm; background: #fbf4f4;
  border-left: 2.5pt solid @RED@; border-radius: 0 3pt 3pt 0;
  color: #333; font-style: italic; break-inside: avoid;
}
strong { color: #0d0d0f; }
"""


def esc(s: str) -> str:
    return html_mod.escape(s, quote=False)


def inline(s: str) -> str:
    t = esc(s)
    t = re.sub(r"`([^`]+)`", r"<code>\1</code>", t)
    t = re.sub(r"\*\*(.+?)\*\*", r"<strong>\1</strong>", t)
    t = re.sub(r"(?<!\*)\*([^*\n]+)\*(?!\*)", r"<em>\1</em>", t)
    return t


def split_cells(row: str):
    s = row.strip()
    if s.startswith("|"):
        s = s[1:]
    if s.endswith("|"):
        s = s[:-1]
    return [c.strip() for c in s.split("|")]


def is_separator(row: str) -> bool:
    s = row.strip().strip("|").strip()
    return bool(s) and bool(re.fullmatch(r"[\s:\-|]+", s)) and "-" in s


def parse(md: str):
    lines = md.splitlines()
    tokens = []
    i = 0
    n = len(lines)
    while i < n:
        line = lines[i]
        if not line.strip():
            i += 1
            continue
        stripped = line.strip()
        # fenced code block
        if stripped.startswith("```"):
            buf = []
            i += 1
            while i < n and not lines[i].strip().startswith("```"):
                buf.append(lines[i])
                i += 1
            i += 1  # skip closing fence
            tokens.append(("code", "\n".join(buf)))
            continue
        # thematic break
        if stripped == "---":
            tokens.append(("hr",))
            i += 1
            continue
        # headings
        m = re.match(r"^(#{1,6})\s+(.*)$", stripped)
        if m:
            tokens.append(("h", len(m.group(1)), m.group(2).strip()))
            i += 1
            continue
        # blockquote
        if stripped.startswith(">"):
            buf = []
            while i < n and lines[i].strip().startswith(">"):
                buf.append(re.sub(r"^\s*>\s?", "", lines[i]))
                i += 1
            tokens.append(("quote", "\n".join(buf)))
            continue
        # table
        if stripped.startswith("|"):
            rows = []
            while i < n and lines[i].strip().startswith("|"):
                rows.append(lines[i].strip())
                i += 1
            if len(rows) >= 2 and is_separator(rows[1]):
                header = split_cells(rows[0])
                body = [split_cells(r) for r in rows[2:]]
            else:
                header = None
                body = [split_cells(r) for r in rows]
            tokens.append(("table", header, body))
            continue
        # lists
        m = re.match(r"^\s*(?:[-*]|\d+\.)\s+(.*)$", line)
        if m:
            ordered = bool(re.match(r"^\s*\d+\.", line))
            items = []
            pattern = r"^\s*(?:\d+\.)\s+(.*)$" if ordered else r"^\s*[-*]\s+(.*)$"
            while i < n and re.match(r"^\s*(?:[-*]|\d+\.)\s+", lines[i]):
                mm = re.match(pattern, lines[i])
                if not mm:
                    break
                items.append(mm.group(1).strip())
                i += 1
            tokens.append(("ol", items) if ordered else ("ul", items))
            continue
        # paragraph
        buf = [line]
        i += 1
        while i < n:
            nxt = lines[i]
            ns = nxt.strip()
            if (
                not ns
                or ns == "---"
                or ns.startswith("```")
                or re.match(r"^#{1,6}\s", ns)
                or ns.startswith("|")
                or ns.startswith(">")
                or re.match(r"^\s*(?:[-*]|\d+\.)\s+", nxt)
            ):
                break
            buf.append(nxt)
            i += 1
        tokens.append(("p", " ".join(x.strip() for x in buf)))
    return tokens


def render_table(header, body):
    rows_html = []
    if header is not None:
        cells = "".join(f"<th>{inline(c)}</th>" for c in header)
        rows_html.append(f"<thead><tr>{cells}</tr></thead>")
    tbody = []
    for row in body:
        cells = "".join(f"<td>{inline(c)}</td>" for c in row)
        tbody.append(f"<tr>{cells}</tr>")
    rows_html.append(f"<tbody>{''.join(tbody)}</tbody>")
    return f"<table>{''.join(rows_html)}</table>"


def render_body(tokens):
    parts = []
    for tok in tokens:
        kind = tok[0]
        if kind == "hr":
            continue
        if kind == "h":
            level, text = tok[1], tok[2]
            if level == 1:
                parts.append(f"<h1>{inline(text)}</h1>")
            elif level == 2:
                m = re.match(r"^(\d+)\.\s*(.*)$", text)
                if m:
                    parts.append(
                        f'<h2><span class="no">{m.group(1)}.</span> {inline(m.group(2))}</h2>'
                    )
                else:
                    parts.append(f"<h2>{inline(text)}</h2>")
            else:
                parts.append(f"<h3>{inline(text)}</h3>")
        elif kind == "p":
            parts.append(f"<p>{inline(tok[1])}</p>")
        elif kind == "quote":
            parts.append(f"<blockquote>{inline(tok[1])}</blockquote>")
        elif kind == "ul":
            lis = "".join(f"<li>{inline(x)}</li>" for x in tok[1])
            parts.append(f"<ul>{lis}</ul>")
        elif kind == "ol":
            lis = "".join(f"<li>{inline(x)}</li>" for x in tok[1])
            parts.append(f"<ol>{lis}</ol>")
        elif kind == "code":
            parts.append(
                '<pre class="art"><code>' + esc(tok[1]) + "</code></pre>"
            )
        elif kind == "table":
            parts.append(render_table(tok[1], tok[2]))
    return "\n".join(parts)


def build_cover(title, tagline, meta_rows):
    dot1 = '<span class="sw sw-red"></span>'
    dot2 = '<span class="sw sw-yellow"></span>'
    facts_rows = []
    for row in meta_rows:
        if len(row) < 2:
            continue
        label = re.sub(r"[*`]", "", row[0]).strip()
        value = re.sub(r"[*`]", "", row[1]).strip()
        if not label:
            continue
        facts_rows.append(f'<tr><td class="k">{esc(label)}</td><td>{esc(value)}</td></tr>')
    facts = f'<table class="facts">{"".join(facts_rows)}</table>'
    return f"""
<section class="cover">
  <div class="dots">
    <div class="dot dot-red" style="width:14mm;height:14mm;top:8mm;right:30mm;"></div>
    <div class="dot dot-yellow" style="width:9mm;height:9mm;top:20mm;right:16mm;"></div>
    <div class="dot dot-yellow" style="width:6mm;height:6mm;bottom:60mm;left:26mm;"></div>
    <div class="dot dot-red" style="width:5mm;height:5mm;bottom:110mm;left:8mm;opacity:.5;"></div>
  </div>
  <div class="cover-main">
    <div class="wordmark">{dot1}&nbsp;{dot2}&nbsp;VYZE</div>
    <div class="rule"></div>
    <h1>Vyze<br><span class="amp">Technical</span> Whitepaper</h1>
    <p class="tagline">{esc(tagline)}</p>
    {facts}
  </div>
  <div class="cover-foot">On-device multimodal AI for visual accessibility &nbsp;·&nbsp; September 2026</div>
</section>
"""


def main():
    md = MD_PATH.read_text(encoding="utf-8")
    tokens = parse(md)

    # Cover material: first h1 + tagline paragraph + metadata table.
    title = "Vyze — Technical Whitepaper"
    tagline = ""
    meta_rows = []
    rest = tokens
    for idx, tok in enumerate(tokens):
        if tok[0] == "h" and tok[1] == 1:
            title = tok[2]
        elif tok[0] == "p" and not tagline:
            plain = re.sub(r"[*`]", "", tok[1]).strip()
            if plain:
                tagline = plain
        elif tok[0] == "table" and tok[2]:
            meta_rows = tok[2]
        if title and tagline and meta_rows:
            rest = tokens[idx + 1 :]
            break

    cover = build_cover(title, tagline, meta_rows)
    body = render_body([t for t in rest if t[0] != "hr"])

    css = CSS.replace("@RED@", BRAND_RED).replace("@YELLOW@", BRAND_YELLOW)

    html_doc = f"""<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<title>{esc(title)}</title>
<style>{css}</style>
</head>
<body>
{cover}
<main>{body}</main>
</body>
</html>
"""
    HTML_PATH.write_text(html_doc, encoding="utf-8")
    print(f"[1/2] Wrote {HTML_PATH.name} ({len(html_doc):,} bytes)")

    chrome = find_chrome()
    if not chrome:
        sys.exit("Chrome not found. Set CHROME_PATH to chrome.exe and rerun.")
    print(f"[2/2] Printing with {chrome}")

    url = HTML_PATH.resolve().as_uri()
    cmd = [
        chrome,
        "--headless=new",
        "--disable-gpu",
        "--no-pdf-header-footer",
        "--virtual-time-budget=8000",
        f"--print-to-pdf={PDF_PATH.resolve()}",
        url,
    ]
    res = subprocess.run(cmd, capture_output=True, text=True)
    if not PDF_PATH.exists() or PDF_PATH.stat().st_size < 10_000:
        print("Chrome stderr:", res.stderr[-2000:] if res.stderr else "(none)")
        sys.exit("PDF was not produced.")
    data = PDF_PATH.read_bytes()
    pages = data.count(b"/Type /Page") - data.count(b"/Type /Pages")
    print(f"OK -> {PDF_PATH}  ({PDF_PATH.stat().st_size / 1024:.0f} KB, ~{pages} pages)")


def find_chrome():
    env = os.environ.get("CHROME_PATH")
    if env and os.path.exists(env):
        return env
    roots = [
        os.environ.get("PROGRAMFILES", r"C:\Program Files"),
        os.environ.get("PROGRAMFILES(X86)", r"C:\Program Files (x86)"),
        os.environ.get("LOCALAPPDATA", ""),
    ]
    candidates = []
    for r in roots:
        if not r:
            continue
        candidates.append(str(Path(r) / "Google" / "Chrome" / "Application" / "chrome.exe"))
        candidates.append(str(Path(r) / "Chromium" / "Application" / "chrome.exe"))
    for c in candidates:
        if os.path.exists(c):
            return c
    return None


if __name__ == "__main__":
    main()
