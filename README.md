# Book Light

A plain e-reader for the [Light Phone](https://www.thelightphone.com/). One page
at a time, centred, with the clock above and two chevrons below. It remembers
where you stopped in every book.

Built on the [Light SDK](https://github.com/lightphone/light-sdk).

## How it works

Books are converted on a computer and read on the phone.

```
your.epub  ──  python tools/convert.py  ──▶  Your Book.book  ──▶  phone
```

That split is deliberate. The Light SDK sandbox allows no third-party libraries,
so nothing that can parse an EPUB or a PDF can run on the phone. Doing the work
on a computer, once, means the reader only ever opens one simple format — and it
means PDFs arrive as real reflowing text rather than as pictures of pages you
cannot read on a small screen.

## Getting a book onto the phone

Open the window:

```bash
python tools/app.py
```

Add some books, choose where the converted files should go, press Convert. Tick
"Also send to a plugged-in phone" and they go straight onto it. Tkinter comes
with Python, so this needs nothing installed. On Windows, `pythonw tools/app.py`
opens it without a console behind it.

Or from the command line:

```bash
python tools/convert.py "Moby Dick.epub"           # convert
python tools/convert.py "Moby Dick.epub" --push    # convert and send
```

Sending needs `adb` on PATH and USB debugging on. If that is not convenient,
copy the `.book` file across however you normally move files to the phone — the
reader takes in whatever it finds in its own folder, so any route works.

### What it can convert

| Format | Needs |
|---|---|
| EPUB, TXT, Markdown, HTML | nothing — plain Python |
| PDF | `pip install pymupdf` |
| MOBI, AZW3, DOCX, RTF, FB2 | [Calibre](https://calibre-ebook.com/download) on PATH |

A book bought from Amazon or another shop with DRM will not convert by any of
these routes. Public-domain books from [Project Gutenberg](https://www.gutenberg.org/)
work well and are what this was tested against.

## Reading

- **Tap left or right** to turn the page. So do the volume keys.
- **Tap the middle** for the contents.
- Every page turn is saved. Open the book again and it is where you left it.

The library lists whatever was read most recently first.

## Building it

```bash
./gradlew :tool:assembleDebug
```

The APK lands in `tool/build/outputs/apk/debug/`. To run it in the LightOS
emulator, leave `serverPackage` in [`tool/lighttool.toml`](tool/lighttool.toml)
alone; to run it on a Light Phone III, change it to `com.lightos`.

Tests:

```bash
./gradlew :tool:testDebugUnitTest     # the reader
python tools/test_convert.py          # the converter
```

## How position is stored

A saved place is a chapter and a **character offset** — never a page number.
Pages depend on the type size, the margins and the screen, and none of those are
properties of the book. Storing an offset means the same words are found again
whatever the page turns out to be, including on a different phone.

## Light Phone 2

Not built yet. The plan is a second, ordinary Android app that shares this
project's core and draws its own screens for the smaller e-ink display.
Everything below the screen layer — the book format, pagination, saved progress
— is already free of the SDK and ready to be shared.

## Licence

MIT, as the SDK it is built on.
