#!/usr/bin/env python3
"""Turn a book into a .book file that Book Light can read.

    python convert.py "Moby Dick.epub"
    python convert.py *.epub -o out/

EPUB, TXT, Markdown and HTML need nothing but Python. PDF needs PyMuPDF
(`pip install pymupdf`). MOBI, AZW3, DOCX and FB2 are handed to Calibre's
`ebook-convert` if it is on PATH, which turns them into EPUB first.

All the messy work happens here, on a real computer with real libraries,
because the phone cannot do any of it: the Light SDK sandbox forbids every
dependency that could parse a book format. The phone reads only .book files.
"""

from __future__ import annotations

import argparse
import html
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import zipfile
from html.parser import HTMLParser
from pathlib import Path
from xml.etree import ElementTree

# Block types, matching BlockType in Models.kt.
P, H1, H2, H3, QUOTE, LI, RULE = "P", "H1", "H2", "H3", "QUOTE", "LI", "RULE"

BLOCK_TAGS = {
    "p": P, "div": P, "dd": P, "dt": P, "pre": P,
    "h1": H1, "h2": H2, "h3": H3, "h4": H3, "h5": H3, "h6": H3,
    "blockquote": QUOTE, "li": LI,
}
ITALIC_TAGS = {"em", "i", "cite", "dfn", "var"}
BOLD_TAGS = {"strong", "b"}
# Nothing inside these is text a reader wants.
SKIP_TAGS = {"script", "style", "head", "title", "svg", "figcaption"}

HEADINGS = {H1, H2, H3}


class ConversionError(Exception):
    """A book that cannot be converted, with a message worth showing.

    Raised rather than exited, so the window can report it and carry on with
    the next book. Only main() turns one into an exit code.
    """


# --------------------------------------------------------------------------
# HTML -> blocks
# --------------------------------------------------------------------------

class BlockBuilder(HTMLParser):
    """Collects an XHTML document into a flat list of blocks.

    Emphasis becomes an offset+length span rather than splitting the text, so a
    paragraph stays one string. The phone lays each block out as a single run;
    per-word layout would wreck prose wrapping.
    """

    def __init__(self) -> None:
        super().__init__(convert_charrefs=True)
        self.blocks: list[dict] = []
        self._type = P
        self._buf = ""
        self._spans: list[dict] = []
        self._open: list[tuple[str, int]] = []   # (style, start offset)
        self._skip = 0

    # -- text accumulation -------------------------------------------------

    def _append(self, text: str) -> None:
        """Append with HTML whitespace rules: every run of space collapses to one.

        A newline in the source is only whitespace - HTML wraps its own lines
        wherever it likes, and honouring those would put a hard break in the
        middle of every paragraph. `<br>` is the only real line break, and it
        writes its newline straight into the buffer.
        """
        text = re.sub(r"\s+", " ", text)
        if not self._buf or self._buf.endswith((" ", "\n")):
            text = text.lstrip(" ")
        self._buf += text

    def _flush(self) -> None:
        # Close anything still open, so an unclosed <em> styles to the end of the
        # block instead of being thrown away.
        for style, start in self._open:
            self._span(style, start, len(self._buf))
        self._open.clear()

        text = self._buf.strip()
        if text:
            lead = len(self._buf) - len(self._buf.lstrip())
            spans = []
            for span in self._spans:
                i = span["i"] - lead
                n = span["n"]
                if i < 0:
                    n += i
                    i = 0
                n = min(n, len(text) - i)
                if 0 <= i < len(text) and n > 0:
                    spans.append({"i": i, "n": n, "style": span["style"]})
            block = {"t": self._type, "s": text}
            if spans:
                block["spans"] = spans
            self.blocks.append(block)

        self._buf = ""
        self._spans = []
        self._type = P

    def _span(self, style: str, start: int, end: int) -> None:
        if end > start:
            self._spans.append({"i": start, "n": end - start, "style": style})

    # -- parser callbacks --------------------------------------------------

    def handle_starttag(self, tag: str, attrs) -> None:
        if self._skip:
            if tag in SKIP_TAGS:
                self._skip += 1
            return
        if tag in SKIP_TAGS:
            self._skip += 1
        elif tag == "br":
            self._buf += "\n"
        elif tag == "hr":
            self._flush()
            self.blocks.append({"t": RULE, "s": ""})
        elif tag in BLOCK_TAGS:
            self._flush()
            self._type = BLOCK_TAGS[tag]
        elif tag in ITALIC_TAGS:
            self._open.append(("ITALIC", len(self._buf)))
        elif tag in BOLD_TAGS:
            self._open.append(("BOLD", len(self._buf)))

    def handle_startendtag(self, tag: str, attrs) -> None:
        self.handle_starttag(tag, attrs)

    def handle_endtag(self, tag: str) -> None:
        if tag in SKIP_TAGS:
            self._skip = max(0, self._skip - 1)
            return
        if self._skip:
            return
        if tag in BLOCK_TAGS:
            self._flush()
        elif tag in ITALIC_TAGS or tag in BOLD_TAGS:
            style = "ITALIC" if tag in ITALIC_TAGS else "BOLD"
            for k in range(len(self._open) - 1, -1, -1):
                if self._open[k][0] == style:
                    self._span(style, self._open.pop(k)[1], len(self._buf))
                    break

    def handle_data(self, data: str) -> None:
        if not self._skip:
            self._append(data)

    def close(self) -> None:  # type: ignore[override]
        super().close()
        self._flush()


def html_to_blocks(markup: str) -> list[dict]:
    builder = BlockBuilder()
    builder.feed(markup)
    builder.close()
    return builder.blocks


# --------------------------------------------------------------------------
# EPUB
# --------------------------------------------------------------------------

def _local(tag: str) -> str:
    """Strip the XML namespace, so `{...}spine` reads as `spine`."""
    return tag.rsplit("}", 1)[-1]


def _find(node, name: str):
    for child in node.iter():
        if _local(child.tag) == name:
            return child
    return None


def _epub_toc_titles(zf: zipfile.ZipFile, names: list[str]) -> dict[str, str]:
    """Map document href -> title, from the EPUB3 nav or the EPUB2 NCX.

    Only a fallback: a chapter's own <h1> is a better title than the one the
    table of contents gives it, and most books have one.
    """
    titles: dict[str, str] = {}
    for name in names:
        if not name.lower().endswith((".ncx", ".xhtml", ".html")):
            continue
        try:
            root = ElementTree.fromstring(zf.read(name))
        except (ElementTree.ParseError, KeyError):
            continue
        base = os.path.dirname(name)
        for node in root.iter():
            local = _local(node.tag)
            href = label = None
            if local == "navPoint":                       # EPUB2 NCX
                content = _find(node, "content")
                text = _find(node, "text")
                href = content.get("src") if content is not None else None
                label = "".join(text.itertext()).strip() if text is not None else None
            elif local == "a":                            # EPUB3 nav
                href = node.get("href")
                label = "".join(node.itertext()).strip()
            if href and label:
                target = os.path.normpath(os.path.join(base, href.split("#")[0]))
                titles.setdefault(target.replace("\\", "/"), label)
    return titles


def from_epub(path: Path) -> dict:
    try:
        archive = zipfile.ZipFile(path)
    except zipfile.BadZipFile:
        raise ConversionError(
            f"{path.name} is not a readable EPUB - it is not a zip archive at all.\n"
            "A book bought from a shop is usually DRM-protected, and no converter "
            "can open it."
        ) from None

    with archive as zf:
        names = zf.namelist()

        try:
            container = ElementTree.fromstring(zf.read("META-INF/container.xml"))
            rootfile = _find(container, "rootfile")
            opf_path = rootfile.get("full-path")
        except (KeyError, AttributeError, ElementTree.ParseError):
            raise ConversionError(
                f"{path.name} is a zip file but not an EPUB - it has no readable "
                "table of contents."
            ) from None
        opf_dir = os.path.dirname(opf_path)

        opf = ElementTree.fromstring(zf.read(opf_path))
        title = author = None
        for node in opf.iter():
            local = _local(node.tag)
            if local == "title" and title is None:
                title = "".join(node.itertext()).strip()
            elif local == "creator" and author is None:
                author = "".join(node.itertext()).strip()

        hrefs = {}
        for node in opf.iter():
            if _local(node.tag) == "item":
                hrefs[node.get("id")] = node.get("href")

        spine = []
        for node in opf.iter():
            if _local(node.tag) == "itemref":
                href = hrefs.get(node.get("idref"))
                if href:
                    target = os.path.normpath(os.path.join(opf_dir, href))
                    spine.append(target.replace("\\", "/"))

        toc = _epub_toc_titles(zf, names)

        chapters = []
        for target in spine:
            try:
                markup = zf.read(target).decode("utf-8", "replace")
            except KeyError:
                continue
            blocks = html_to_blocks(markup)
            if not any(b["s"] for b in blocks):
                continue    # cover pages and the like carry no text
            chapters.extend(split_at_headings(blocks, toc.get(target)))

    return _book(title or path.stem, author, "epub", chapters)


def heading_title(text: str) -> str:
    """Pull the real chapter name out of a heading.

    Illustrated editions hang the picture's caption inside the heading, split
    off by <br>, so Pride and Prejudice's chapter two arrives as "I hope Mr.
    Bingley will like it. / / CHAPTER II." Only the line that announces a
    chapter is the name.

    When no line announces one, the whole heading is the name - a title page
    reading "PRIDE. / and / PREJUDICE" must not be trimmed to its last line.
    """
    lines = [" ".join(line.split()) for line in text.split("\n")]
    lines = [line for line in lines if line]
    for line in lines:
        if len(line) < 120 and CHAPTER_HEADING.match(line):
            return line
    return " ".join(lines)


def split_at_headings(blocks: list[dict], fallback: str | None) -> list[dict]:
    """Cut one document into chapters at its headings.

    A spine file is not a chapter. Gutenberg packs dozens of chapters into a
    handful of files, so splitting per file gave Moby Dick 27 chapters instead
    of 135 and made the table of contents useless.

    Only the outermost heading level present splits. A file with one <h1> and
    ten <h2> sections is one chapter, not eleven - the h2s are sections inside
    it, and cutting there would shred the book the other way.
    """
    levels = {b["t"] for b in blocks if b["t"] in HEADINGS and b["s"]}
    if not levels:
        return [{"title": fallback, "blocks": blocks}]
    cut_at = H1 if H1 in levels else H2 if H2 in levels else H3

    chapters: list[dict] = []
    # No fallback here: a document that names its own chapters gets to. The
    # EPUB nav is the worse source - Gutenberg builds its labels from <title>
    # elements that carry the same caption noise, and one of them is literally
    # "II.,". A chunk with no heading of its own is better left unnamed.
    title: str | None = None
    current: list[dict] = []
    for block in blocks:
        if block["t"] == cut_at and block["s"]:
            if any(b["s"] for b in current):
                chapters.append({"title": title, "blocks": current})
            elif current and chapters:
                # Stray matter before a heading with no chapter of its own -
                # a page break or a decoration. It belongs to what came before.
                chapters[-1]["blocks"].extend(current)
            title = heading_title(block["s"])
            # Redraw the heading as its own name, so the caption does not show
            # above the chapter. The picture it captions is not here anyway.
            current = [{"t": block["t"], "s": title}]
        else:
            current.append(block)
    if any(b["s"] for b in current):
        chapters.append({"title": title, "blocks": current})
    return chapters


# --------------------------------------------------------------------------
# Plain text and Markdown
# --------------------------------------------------------------------------

# Conservative on purpose: only split where a line really announces a chapter.
# Gutenberg plain texts are full of numbers that are not chapter numbers.
CHAPTER_HEADING = re.compile(
    r"^\s*(chapter|part|book|volume|canto|act|scene|letter)\s+([0-9]+|[ivxlcdm]+)\b.*$",
    re.IGNORECASE,
)


def from_text(path: Path) -> dict:
    raw = path.read_text(encoding="utf-8", errors="replace")
    chapters: list[dict] = []
    blocks: list[dict] = []
    title: str | None = None

    for para in re.split(r"\n\s*\n", raw):
        para = para.strip()
        if not para:
            continue
        if len(para) < 120 and CHAPTER_HEADING.match(para):
            if blocks:
                chapters.append({"title": title, "blocks": blocks})
            title, blocks = " ".join(para.split()), []
            continue
        blocks.append({"t": P, "s": " ".join(para.split())})

    if blocks:
        chapters.append({"title": title, "blocks": blocks})
    return _book(path.stem, None, "txt", chapters)


MD_HEADING = re.compile(r"^(#{1,6})\s+(.*)$")
MD_EMPHASIS = re.compile(r"(\*\*|__)(.+?)\1|(\*|_)(.+?)\3", re.DOTALL)


def from_markdown(path: Path) -> dict:
    raw = path.read_text(encoding="utf-8", errors="replace")
    chapters: list[dict] = []
    blocks: list[dict] = []
    title: str | None = None

    for para in re.split(r"\n\s*\n", raw):
        para = para.strip()
        if not para:
            continue
        heading = MD_HEADING.match(para)
        if heading:
            level = len(heading.group(1))
            text = heading.group(2).strip()
            if level <= 2:
                if blocks:
                    chapters.append({"title": title, "blocks": blocks})
                title, blocks = text, []
            blocks.append({
                "t": H1 if level == 1 else H2 if level == 2 else H3,
                "s": text,
            })
            continue
        blocks.append(_markdown_block(" ".join(para.split())))

    if blocks:
        chapters.append({"title": title, "blocks": blocks})
    return _book(path.stem, None, "markdown", chapters)


def _markdown_block(text: str) -> dict:
    """Strip * and _ emphasis markers, recording where the emphasis was."""
    spans: list[dict] = []
    out = ""
    pos = 0
    for match in MD_EMPHASIS.finditer(text):
        out += text[pos:match.start()]
        strong = match.group(2) is not None
        inner = match.group(2) if strong else match.group(4)
        spans.append({
            "i": len(out),
            "n": len(inner),
            "style": "BOLD" if strong else "ITALIC",
        })
        out += inner
        pos = match.end()
    out += text[pos:]
    block = {"t": P, "s": out}
    if spans:
        block["spans"] = spans
    return block


def from_html(path: Path) -> dict:
    markup = path.read_text(encoding="utf-8", errors="replace")
    blocks = html_to_blocks(markup)
    title = None
    match = re.search(r"<title[^>]*>(.*?)</title>", markup, re.IGNORECASE | re.DOTALL)
    if match:
        title = html.unescape(" ".join(match.group(1).split()))
    return _book(title or path.stem, None, "html", [{"title": None, "blocks": blocks}])


# --------------------------------------------------------------------------
# PDF
# --------------------------------------------------------------------------

# A running header or footer sits in the top or bottom twelfth of the page.
PDF_MARGIN = 0.12
# Page numbers on their own, arabic or roman, in any common decoration.
PAGE_NUMBER = re.compile(r"^[\[\(]?\s*(?:page\s+)?[0-9ivxlcdm]{1,7}\s*[\]\)]?$", re.I)


def _digits_blurred(text: str) -> str:
    """The same header with a different page number counts as the same header."""
    return re.sub(r"\d+", "#", text).strip()


def _in_margin(y0: float, y1: float, height: float) -> bool:
    """True only when the whole block sits in the top or bottom margin.

    It has to be the whole block. Testing where a block *starts* calls the
    first paragraph on the page a header, because it begins just below one -
    which threw away the entire body of the first PDF this met.
    """
    return y1 <= height * PDF_MARGIN or y0 >= height * (1 - PDF_MARGIN)


def running_text(pages: list[tuple[float, list[tuple[float, float, str]]]]) -> set[str]:
    """Find the headers and footers, by looking for what repeats.

    A PDF has no idea what a header is - the running title and the page number
    are just more text on the page, and left alone they land in the middle of
    the prose: "CHAPTER 1 A TEST BOOK 1 It was a bright cold day".

    Repetition is what gives them away. A chapter heading also sits at the top
    of a page, but it appears once; "A TEST BOOK" appears on every page. So
    only margin text that recurs is dropped, and a real heading survives.
    """
    counts: dict[str, int] = {}
    for height, blocks in pages:
        seen = set()
        for y0, y1, text in blocks:
            if _in_margin(y0, y1, height):
                # A chapter heading is never furniture, however often it
                # recurs. Blurring the numbers makes "CHAPTER 1" and
                # "CHAPTER 2" the same string, so without this a book whose
                # headings sit high on the page loses every one of them.
                if CHAPTER_HEADING.match(text):
                    continue
                key = _digits_blurred(text)
                if key and key not in seen:
                    seen.add(key)
                    counts[key] = counts.get(key, 0) + 1
    # Three occurrences, or a fifth of the book - whichever is more. Two is not
    # yet a pattern, and a short book must not have its headings mistaken.
    threshold = max(3, len(pages) // 5)
    return {key for key, count in counts.items() if count >= threshold}


def _page_blocks(page) -> list[tuple[float, float, str]]:
    """Text blocks with their vertical position, ordered down the page."""
    blocks = []
    for x0, y0, x1, y1, text, *_ in page.get_text("blocks"):
        text = " ".join(text.split())
        if text:
            blocks.append((y0, y1, text))
    blocks.sort(key=lambda block: block[0])
    return blocks


def from_pdf(path: Path) -> dict:
    try:
        import pymupdf
    except ImportError:
        try:
            import fitz as pymupdf          # PyMuPDF before it was renamed
        except ImportError:
            raise ConversionError(
                "PDF conversion needs PyMuPDF.\n    pip install pymupdf"
            ) from None

    doc = pymupdf.open(path)
    metadata = doc.metadata or {}
    title = metadata.get("title") or path.stem
    author = metadata.get("author") or None

    # Read every page first, so the headers can be found before anything is
    # kept - they can only be recognised by comparing pages to each other.
    pages = [(doc[n].rect.height, _page_blocks(doc[n])) for n in range(doc.page_count)]
    furniture = running_text(pages)

    # A PDF's own outline gives real chapter breaks when it has one. Plenty of
    # PDFs have none, and then the book is one long chapter - which still reads
    # fine, because position is a character offset, not a page.
    starts = {page: name for name, page in
              [(entry[1], entry[2]) for entry in doc.get_toc() if entry[2] > 0]}
    doc.close()

    chapters: list[dict] = []
    blocks: list[dict] = []
    chapter_title: str | None = None

    for number, (height, page_blocks) in enumerate(pages):
        if (number + 1) in starts:
            if blocks:
                chapters.append({"title": chapter_title, "blocks": blocks})
                blocks = []
            chapter_title = starts[number + 1]

        for y0, y1, text in page_blocks:
            if _in_margin(y0, y1, height):
                if _digits_blurred(text) in furniture or PAGE_NUMBER.match(text):
                    continue
            blocks.append({"t": P, "s": _mend_wrapping(text)})

    if blocks:
        chapters.append({"title": chapter_title, "blocks": blocks})
    return _book(title, author, "pdf", chapters)


def _mend_wrapping(text: str) -> str:
    """Undo the line breaks a PDF hard-wrapped its paragraphs at."""
    # A hyphen at a line end is almost always a word split across the wrap.
    text = re.sub(r"(\w)-\s*\n\s*(\w)", r"\1\2", text)
    return " ".join(text.split())


# --------------------------------------------------------------------------
# MOBI / AZW3 / DOCX / FB2, via Calibre
# --------------------------------------------------------------------------

def via_calibre(path: Path) -> dict:
    tool = shutil.which("ebook-convert")
    if not tool:
        raise ConversionError(
            f"{path.suffix} needs Calibre's ebook-convert on PATH.\n"
            "    https://calibre-ebook.com/download\n"
            "Or convert it to EPUB yourself first.\n"
            "(A book bought from Amazon is DRM-protected and converts by no route.)"
        )
    with tempfile.TemporaryDirectory() as tmp:
        epub = Path(tmp) / (path.stem + ".epub")
        result = subprocess.run([tool, str(path), str(epub)],
                                capture_output=True, text=True)
        if result.returncode != 0 or not epub.exists():
            raise ConversionError(f"ebook-convert could not read {path.name}:\n{result.stderr.strip()}")
        book = from_epub(epub)
    book["source"] = path.suffix.lstrip(".").lower()
    return book


# --------------------------------------------------------------------------

# Project Gutenberg wraps every book in a licence and a header. They are marked
# unmistakably, which is what makes dropping them safe to do automatically.
GUTENBERG_START = re.compile(r"\*\*\*\s*START OF (?:THE|THIS) PROJECT GUTENBERG", re.I)
GUTENBERG_END = re.compile(r"\*\*\*\s*END OF (?:THE|THIS) PROJECT GUTENBERG", re.I)


def strip_gutenberg(chapters: list[dict]) -> list[dict]:
    """Drop Gutenberg's front and back matter, keeping the book between them.

    Without this, opening Moby Dick lands on "The Project Gutenberg eBook of
    Moby Dick" followed by pages of licence, and the contents lists the licence
    as a chapter.

    The markers are exact strings Gutenberg puts in every book, so a book that
    has neither is left completely alone - this never guesses.
    """
    flat = [
        (chapter_index, block_index)
        for chapter_index, chapter in enumerate(chapters)
        for block_index in range(len(chapter["blocks"]))
    ]

    start = end = None
    for position, (chapter_index, block_index) in enumerate(flat):
        text = chapters[chapter_index]["blocks"][block_index]["s"]
        if start is None and GUTENBERG_START.search(text):
            start = position
        elif start is not None and GUTENBERG_END.search(text):
            end = position
            break

    if start is None and end is None:
        return chapters

    keep = set(flat[(start + 1 if start is not None else 0):(end if end is not None else len(flat))])
    out = []
    for chapter_index, chapter in enumerate(chapters):
        blocks = [
            block for block_index, block in enumerate(chapter["blocks"])
            if (chapter_index, block_index) in keep
        ]
        if blocks:
            out.append({"title": chapter["title"], "blocks": blocks})
    return out


# The reader lays out a whole chapter to work out where its pages fall, so a
# chapter has to stay small enough to measure quickly.
CHAPTER_LIMIT = 20_000


def cap_chapters(chapters: list[dict], limit: int = CHAPTER_LIMIT) -> list[dict]:
    """Split chapters too long to lay out in one go.

    A PDF with no outline arrives as a single chapter of a million characters,
    and measuring that on the phone would stall it. Splitting costs a reader
    nothing - pages run straight on across the join - and the continuation is
    left unnamed so the contents does not list the same chapter five times.

    Splits fall on block boundaries, so no paragraph is ever cut in half.
    """
    out: list[dict] = []
    for chapter in chapters:
        blocks = chapter["blocks"]
        if sum(len(b["s"]) for b in blocks) <= limit:
            out.append(chapter)
            continue

        part: list[dict] = []
        size = 0
        first = True
        for block in blocks:
            if part and size + len(block["s"]) > limit:
                out.append(_part(chapter, part, first))
                part, size, first = [], 0, False
            part.append(block)
            size += len(block["s"])
        if part:
            out.append(_part(chapter, part, first))
    return out


def _part(chapter: dict, blocks: list[dict], first: bool) -> dict:
    """One piece of a split chapter.

    A continuation is flagged rather than just left unnamed, because the two
    mean different things to the contents: a chapter the book never named still
    deserves a line, but a continuation is the same chapter and must not appear
    again.
    """
    if first:
        return {"title": chapter["title"], "blocks": blocks}
    return {"title": None, "continues": True, "blocks": blocks}


def _book(title, author, source: str, chapters: list[dict]) -> dict:
    return {
        "title": " ".join(str(title).split()) or "Untitled",
        "author": " ".join(author.split()) if author else None,
        "source": source,
        "chapters": cap_chapters(strip_gutenberg([c for c in chapters if c["blocks"]])),
    }


READERS = {
    ".epub": from_epub,
    ".txt": from_text,
    ".text": from_text,
    ".md": from_markdown,
    ".markdown": from_markdown,
    ".html": from_html,
    ".htm": from_html,
    ".xhtml": from_html,
    ".pdf": from_pdf,
    ".mobi": via_calibre,
    ".azw": via_calibre,
    ".azw3": via_calibre,
    ".docx": via_calibre,
    ".rtf": via_calibre,
    ".fb2": via_calibre,
}

UNSAFE = re.compile(r'[<>:"/\\|?*\x00-\x1f]')


def safe_filename(title: str) -> str:
    name = UNSAFE.sub("", title).strip().rstrip(".")
    return (name or "Untitled")[:120]


def convert(path: Path, out_dir: Path) -> Path:
    reader = READERS.get(path.suffix.lower())
    if not reader:
        raise ConversionError(
            f"Do not know how to read {path.suffix or path.name}.\n"
            f"Handled: {', '.join(sorted(READERS))}"
        )
    book = reader(path)
    if not book["chapters"]:
        raise ConversionError(f"{path.name} produced no text. A scanned PDF needs OCR first.")

    out_dir.mkdir(parents=True, exist_ok=True)
    destination = out_dir / (safe_filename(book["title"]) + ".book")
    destination.write_text(
        json.dumps(book, ensure_ascii=False, separators=(",", ":")),
        encoding="utf-8",
    )

    chars = sum(len(b["s"]) for c in book["chapters"] for b in c["blocks"])
    print(
        f"{destination.name}  -  {len(book['chapters'])} chapters, "
        f"{chars:,} characters, {destination.stat().st_size / 1024:.0f} KB"
    )
    return destination


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Convert books into .book files for Book Light.",
    )
    parser.add_argument("files", nargs="+", type=Path, help="books to convert")
    parser.add_argument(
        "-o", "--out", type=Path, default=Path("."),
        help="where to write the .book files (default: here)",
    )
    parser.add_argument(
        "-p", "--push", action="store_true",
        help="copy the converted books onto a plugged-in phone",
    )
    args = parser.parse_args()

    for path in args.files:
        if not path.is_file():
            sys.exit(f"No such file: {path}")

    try:
        written = [convert(path, args.out) for path in args.files]
    except ConversionError as error:
        sys.exit(str(error))

    if args.push:
        from push import push
        push(written)


if __name__ == "__main__":
    main()
