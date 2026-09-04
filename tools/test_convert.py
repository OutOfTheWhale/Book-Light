"""Tests for the converter.

Everything here is a rule that was got wrong first and fixed against a real
book. They are cheap to keep and expensive to rediscover.
"""

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))

from convert import (  # noqa: E402
    cap_chapters,
    heading_title,
    html_to_blocks,
    split_at_headings,
    strip_gutenberg,
)


# -- whitespace -------------------------------------------------------------

def test_source_line_wrapping_becomes_a_space():
    # HTML wraps its own lines wherever it likes. Honouring those newlines put
    # a hard break in the middle of 575 paragraphs of Pride and Prejudice.
    blocks = html_to_blocks("<p>Netherfield is taken\nby a young man</p>")
    assert blocks[0]["s"] == "Netherfield is taken by a young man"


def test_br_is_a_real_line_break():
    blocks = html_to_blocks("<p>PRIDE.<br/>and<br/>PREJUDICE</p>")
    assert blocks[0]["s"] == "PRIDE.\nand\nPREJUDICE"


def test_runs_of_space_collapse_to_one():
    blocks = html_to_blocks("<p>a  \t  b</p>")
    assert blocks[0]["s"] == "a b"


def test_inline_tags_do_not_glue_words_together():
    blocks = html_to_blocks("<p>a <em>b</em> c</p>")
    assert blocks[0]["s"] == "a b c"


# -- emphasis ---------------------------------------------------------------

def test_emphasis_becomes_a_span_over_the_same_text():
    block = html_to_blocks("<p>Call me <em>Ishmael</em> today</p>")[0]
    span = block["spans"][0]
    assert block["s"][span["i"]:span["i"] + span["n"]] == "Ishmael"
    assert span["style"] == "ITALIC"


def test_strong_is_bold_not_italic():
    block = html_to_blocks("<p>a <strong>b</strong></p>")[0]
    assert block["spans"][0]["style"] == "BOLD"


def test_span_offsets_survive_a_stripped_leading_space():
    block = html_to_blocks("<p>   <em>Ishmael</em> spoke</p>")[0]
    span = block["spans"][0]
    assert block["s"] == "Ishmael spoke"
    assert block["s"][span["i"]:span["i"] + span["n"]] == "Ishmael"


def test_an_unclosed_em_styles_to_the_end_rather_than_vanishing():
    block = html_to_blocks("<p>a <em>b c</p>")[0]
    span = block["spans"][0]
    assert block["s"][span["i"]:span["i"] + span["n"]] == "b c"


def test_nested_emphasis_yields_both_spans():
    block = html_to_blocks("<p><em>a <strong>b</strong></em></p>")[0]
    assert {s["style"] for s in block["spans"]} == {"ITALIC", "BOLD"}


# -- what is not text -------------------------------------------------------

def test_head_and_title_contribute_nothing():
    # Gutenberg writes the illustration caption into <title>. Letting it
    # through put "I hope Mr. Bingley will like it." in the body.
    blocks = html_to_blocks(
        "<html><head><title>caption noise</title></head><body><p>real</p></body></html>"
    )
    assert [b["s"] for b in blocks] == ["real"]


def test_script_and_style_contribute_nothing():
    blocks = html_to_blocks("<body><script>var x=1</script><p>real</p></body>")
    assert [b["s"] for b in blocks] == ["real"]


def test_an_empty_paragraph_is_dropped():
    assert html_to_blocks("<p></p><p>  </p><p>text</p>") == [{"t": "P", "s": "text"}]


def test_hr_survives_as_a_break():
    assert [b["t"] for b in html_to_blocks("<p>a</p><hr/><p>b</p>")] == ["P", "RULE", "P"]


# -- chapter names ----------------------------------------------------------

def test_heading_title_takes_the_line_that_announces_a_chapter():
    # Illustrated editions hang the picture's caption inside the heading.
    assert heading_title("I hope Mr. Bingley will like it.\n\nCHAPTER II.") == "CHAPTER II."


def test_heading_title_keeps_a_whole_title_page():
    # No line announces a chapter, so trimming to the last line would leave
    # the book called "PREJUDICE".
    assert heading_title("PRIDE.\nand\nPREJUDICE") == "PRIDE. and PREJUDICE"


def test_heading_title_leaves_a_plain_chapter_heading_alone():
    assert heading_title("CHAPTER 1. Loomings.") == "CHAPTER 1. Loomings."


# -- chapter splitting ------------------------------------------------------

def _p(text):
    return {"t": "P", "s": text}


def _h(level, text):
    return {"t": level, "s": text}


def test_one_file_splits_into_every_chapter_it_holds():
    # A spine file is not a chapter: Gutenberg packs dozens into one file, and
    # splitting per file gave Moby Dick 27 chapters instead of 135.
    chapters = split_at_headings(
        [_h("H2", "CHAPTER I."), _p("a"), _h("H2", "CHAPTER II."), _p("b")], None
    )
    assert [c["title"] for c in chapters] == ["CHAPTER I.", "CHAPTER II."]


def test_only_the_outermost_heading_level_splits():
    # One chapter with two sections inside it, not three chapters.
    chapters = split_at_headings(
        [_h("H1", "CHAPTER I."), _h("H2", "A section"), _p("a"), _h("H2", "Another")], None
    )
    assert len(chapters) == 1
    assert chapters[0]["title"] == "CHAPTER I."


def test_a_document_without_headings_falls_back_to_the_toc_name():
    chapters = split_at_headings([_p("a")], "From the nav")
    assert [c["title"] for c in chapters] == ["From the nav"]


def test_a_document_with_headings_ignores_the_toc_name():
    # Gutenberg's nav labels carry the same caption noise, and one of them is
    # literally "II.,". A chunk with no heading is better left unnamed.
    chapters = split_at_headings([_p("front"), _h("H2", "CHAPTER II."), _p("a")], "II.,")
    assert [c["title"] for c in chapters] == [None, "CHAPTER II."]


def test_the_heading_block_is_redrawn_without_its_caption():
    chapters = split_at_headings([_h("H2", "A caption.\n\nCHAPTER II."), _p("a")], None)
    assert chapters[0]["blocks"][0]["s"] == "CHAPTER II."


def test_no_text_is_lost_when_a_heading_opens_the_document():
    chapters = split_at_headings([_h("H2", "CHAPTER I."), _p("a"), _p("b")], None)
    assert [b["s"] for b in chapters[0]["blocks"]] == ["CHAPTER I.", "a", "b"]


# -- chapter length ---------------------------------------------------------

def test_a_short_chapter_is_left_alone():
    chapters = [{"title": "One", "blocks": [_p("short")]}]
    assert cap_chapters(chapters, limit=100) == chapters


def test_an_overlong_chapter_is_split():
    # A PDF with no outline is one chapter of a million characters, and the
    # reader measures a whole chapter to paginate it.
    chapters = [{"title": "One", "blocks": [_p("x" * 60), _p("y" * 60), _p("z" * 60)]}]
    out = cap_chapters(chapters, limit=100)
    assert len(out) == 3


def test_only_the_first_part_keeps_the_chapter_name():
    # Otherwise the contents lists the same chapter several times over.
    chapters = [{"title": "One", "blocks": [_p("x" * 60), _p("y" * 60)]}]
    assert [c["title"] for c in cap_chapters(chapters, limit=100)] == ["One", None]


def test_splitting_never_cuts_a_paragraph_in_half():
    chapters = [{"title": None, "blocks": [_p("x" * 60), _p("y" * 60)]}]
    out = cap_chapters(chapters, limit=100)
    assert [b["s"] for c in out for b in c["blocks"]] == ["x" * 60, "y" * 60]


def test_a_single_paragraph_longer_than_the_limit_still_becomes_one_chapter():
    # It cannot be split on a block boundary, so it must not be dropped either.
    chapters = [{"title": None, "blocks": [_p("x" * 500)]}]
    out = cap_chapters(chapters, limit=100)
    assert len(out) == 1
    assert out[0]["blocks"][0]["s"] == "x" * 500


def test_no_text_is_lost_when_a_chapter_is_split():
    blocks = [_p("a" * 40), _p("b" * 40), _p("c" * 40), _p("d" * 40)]
    out = cap_chapters([{"title": None, "blocks": blocks}], limit=100)
    assert [b["s"] for c in out for b in c["blocks"]] == [b["s"] for b in blocks]


# -- Gutenberg wrapping -----------------------------------------------------

def test_the_licence_before_and_after_the_book_is_dropped():
    chapters = [
        {"title": "eBook of Moby Dick", "blocks": [_p("*** START OF THE PROJECT GUTENBERG EBOOK ***")]},
        {"title": "CHAPTER 1.", "blocks": [_p("Call me Ishmael.")]},
        {"title": None, "blocks": [_p("*** END OF THE PROJECT GUTENBERG EBOOK ***"), _p("Licence.")]},
    ]
    out = strip_gutenberg(chapters)
    assert [c["title"] for c in out] == ["CHAPTER 1."]


def test_a_book_that_is_not_from_gutenberg_is_untouched():
    # The markers are exact, so this never guesses at someone else's front matter.
    chapters = [{"title": "One", "blocks": [_p("Call me Ishmael.")]}]
    assert strip_gutenberg(chapters) is chapters


def test_the_marker_blocks_themselves_go_too():
    chapters = [{
        "title": None,
        "blocks": [
            _p("*** START OF THE PROJECT GUTENBERG EBOOK MOBY DICK ***"),
            _p("Call me Ishmael."),
            _p("*** END OF THE PROJECT GUTENBERG EBOOK MOBY DICK ***"),
        ],
    }]
    out = strip_gutenberg(chapters)
    assert [b["s"] for c in out for b in c["blocks"]] == ["Call me Ishmael."]


def test_a_start_marker_with_no_end_still_drops_the_header():
    chapters = [
        {"title": None, "blocks": [_p("*** START OF THE PROJECT GUTENBERG EBOOK ***")]},
        {"title": "One", "blocks": [_p("Call me Ishmael.")]},
    ]
    assert [c["title"] for c in strip_gutenberg(chapters)] == ["One"]


# Runs under pytest, and under plain `python test_convert.py` for anyone who
# has not got it - the converter itself needs no third-party package either.
if __name__ == "__main__":
    failed = 0
    for name, test in sorted(globals().items()):
        if not name.startswith("test_") or not callable(test):
            continue
        try:
            test()
        except AssertionError as error:
            failed += 1
            print(f"FAIL  {name}\n      {error}")
    total = sum(1 for n, t in globals().items() if n.startswith("test_") and callable(t))
    print(f"{total - failed}/{total} passed")
    sys.exit(1 if failed else 0)
