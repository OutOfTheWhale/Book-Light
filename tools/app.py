#!/usr/bin/env python3
"""Book Light, as a window.

    python app.py

Pick some books, choose where the converted files go, press Convert. Optionally
send them straight to a plugged-in phone.

Tkinter comes with Python, so this needs nothing installed - the same rule the
converter follows. PDF still wants PyMuPDF and MOBI still wants Calibre; the
window says so plainly when a book needs one of them.
"""

from __future__ import annotations

import json
import queue
import sys
import threading
import tkinter as tk
from pathlib import Path
from tkinter import filedialog, font as tkfont, messagebox, ttk

sys.path.insert(0, str(Path(__file__).parent))

from convert import READERS, ConversionError, convert  # noqa: E402
from push import PushError, push  # noqa: E402

SETTINGS = Path.home() / ".booklight.json"

OPEN_TYPES = [
    ("Books", " ".join(f"*{suffix}" for suffix in sorted(READERS))),
    ("EPUB", "*.epub"),
    ("PDF", "*.pdf"),
    ("Text", "*.txt *.md *.markdown"),
    ("All files", "*.*"),
]


class App(ttk.Frame):

    def __init__(self, root: tk.Tk) -> None:
        super().__init__(root, padding=16)
        self.root = root
        self.files: list[Path] = []
        # Work happens on a thread so a 1 MB novel does not freeze the window;
        # it reports back through a queue, because only the main thread may
        # touch Tk widgets.
        self.messages: queue.Queue[tuple[str, str]] = queue.Queue()
        self.working = False

        settings = self._load_settings()
        self.out_dir = tk.StringVar(value=settings.get("out", str(Path.home() / "Books")))
        self.send = tk.BooleanVar(value=settings.get("send", False))

        self._build()
        self.root.after(100, self._drain)

    # -- layout ------------------------------------------------------------

    def _build(self) -> None:
        self.grid(sticky="nsew")
        self.root.columnconfigure(0, weight=1)
        self.root.rowconfigure(0, weight=1)
        self.columnconfigure(0, weight=1)
        self.rowconfigure(1, weight=3)
        self.rowconfigure(4, weight=2)

        heading = ttk.Label(self, text="Book Light", font=self._font(16, "bold"))
        heading.grid(row=0, column=0, sticky="w")
        ttk.Label(
            self,
            text="Turn books into .book files the phone can read.",
            foreground="#666666",
        ).grid(row=0, column=0, sticky="e")

        # Books
        books = ttk.LabelFrame(self, text="Books", padding=8)
        books.grid(row=1, column=0, sticky="nsew", pady=(12, 0))
        books.columnconfigure(0, weight=1)
        books.rowconfigure(0, weight=1)

        self.listbox = tk.Listbox(books, selectmode=tk.EXTENDED, activestyle="none")
        self.listbox.grid(row=0, column=0, sticky="nsew")
        scroll = ttk.Scrollbar(books, orient="vertical", command=self.listbox.yview)
        scroll.grid(row=0, column=1, sticky="ns")
        self.listbox.configure(yscrollcommand=scroll.set)

        buttons = ttk.Frame(books)
        buttons.grid(row=1, column=0, columnspan=2, sticky="w", pady=(8, 0))
        ttk.Button(buttons, text="Add books…", command=self.add).grid(row=0, column=0)
        ttk.Button(buttons, text="Remove", command=self.remove).grid(row=0, column=1, padx=6)
        ttk.Button(buttons, text="Clear", command=self.clear).grid(row=0, column=2)

        # Where they go
        where = ttk.LabelFrame(self, text="Save to", padding=8)
        where.grid(row=2, column=0, sticky="ew", pady=(12, 0))
        where.columnconfigure(0, weight=1)
        ttk.Entry(where, textvariable=self.out_dir).grid(row=0, column=0, sticky="ew")
        ttk.Button(where, text="Choose…", command=self.choose_out).grid(row=0, column=1, padx=(6, 0))
        ttk.Checkbutton(
            where,
            text="Also send to a plugged-in phone",
            variable=self.send,
        ).grid(row=1, column=0, sticky="w", pady=(8, 0))

        # Go
        actions = ttk.Frame(self)
        actions.grid(row=3, column=0, sticky="ew", pady=(12, 0))
        actions.columnconfigure(1, weight=1)
        self.convert_button = ttk.Button(actions, text="Convert", command=self.start)
        self.convert_button.grid(row=0, column=0)
        self.progress = ttk.Progressbar(actions, mode="determinate")
        self.progress.grid(row=0, column=1, sticky="ew", padx=(12, 0))

        # What happened
        log_frame = ttk.LabelFrame(self, text="Result", padding=8)
        log_frame.grid(row=4, column=0, sticky="nsew", pady=(12, 0))
        log_frame.columnconfigure(0, weight=1)
        log_frame.rowconfigure(0, weight=1)
        # tk.Text defaults to a fixed-width font, which reads as a console
        # rather than as part of the window.
        self.log = tk.Text(
            log_frame,
            height=8,
            wrap="word",
            state="disabled",
            font=tkfont.nametofont("TkDefaultFont"),
        )
        self.log.grid(row=0, column=0, sticky="nsew")
        log_scroll = ttk.Scrollbar(log_frame, orient="vertical", command=self.log.yview)
        log_scroll.grid(row=0, column=1, sticky="ns")
        self.log.configure(yscrollcommand=log_scroll.set)
        self.log.tag_configure("bad", foreground="#b00020")
        self.log.tag_configure("dim", foreground="#666666")

        self._say("Add a book to begin.", "dim")

    def _font(self, size: int, weight: str = "normal") -> tkfont.Font:
        family = tkfont.nametofont("TkDefaultFont").cget("family")
        return tkfont.Font(family=family, size=size, weight=weight)

    # -- the list ----------------------------------------------------------

    def add(self) -> None:
        chosen = filedialog.askopenfilenames(title="Choose books", filetypes=OPEN_TYPES)
        added = 0
        for name in chosen:
            path = Path(name)
            if path not in self.files:
                self.files.append(path)
                self.listbox.insert(tk.END, path.name)
                added += 1
        if added:
            self._say(f"Added {added} book{'s' if added != 1 else ''}.", "dim")

    def remove(self) -> None:
        # Back to front, so removing one does not shift the next index.
        for index in sorted(self.listbox.curselection(), reverse=True):
            self.listbox.delete(index)
            del self.files[index]

    def clear(self) -> None:
        self.listbox.delete(0, tk.END)
        self.files.clear()

    def choose_out(self) -> None:
        chosen = filedialog.askdirectory(title="Save .book files to", mustexist=False)
        if chosen:
            self.out_dir.set(chosen)

    # -- converting --------------------------------------------------------

    def start(self) -> None:
        if self.working:
            return
        if not self.files:
            messagebox.showinfo("Book Light", "Add a book first.")
            return

        out = Path(self.out_dir.get()).expanduser()
        try:
            out.mkdir(parents=True, exist_ok=True)
        except OSError as error:
            messagebox.showerror("Book Light", f"Cannot write to that folder.\n\n{error}")
            return

        self._save_settings()
        self.working = True
        self.convert_button.state(["disabled"])
        self.progress.configure(maximum=len(self.files), value=0)
        self._clear_log()

        worker = threading.Thread(
            target=self._work,
            args=(list(self.files), out, self.send.get()),
            daemon=True,
        )
        worker.start()

    def _work(self, files: list[Path], out: Path, send: bool) -> None:
        """Runs off the main thread. Talks back only through the queue."""
        written: list[Path] = []
        for path in files:
            try:
                destination = convert(path, out)
                written.append(destination)
                self.messages.put(("ok", f"{path.name}  →  {destination.name}"))
            except ConversionError as error:
                # One book that will not convert must not stop the rest.
                self.messages.put(("bad", f"{path.name}: {error}"))
            except Exception as error:                      # noqa: BLE001
                self.messages.put(("bad", f"{path.name}: {type(error).__name__}: {error}"))
            self.messages.put(("step", ""))

        if send and written:
            try:
                push(written)
                self.messages.put(("ok", f"Sent {len(written)} to the phone."))
            except PushError as error:
                self.messages.put(("bad", f"Could not send to the phone.\n{error}"))
            except Exception as error:                      # noqa: BLE001
                self.messages.put(("bad", f"Could not send to the phone.\n{error}"))

        self.messages.put(("done", str(out)))

    def _drain(self) -> None:
        """Move worker messages onto the screen. Main thread only."""
        try:
            while True:
                kind, text = self.messages.get_nowait()
                if kind == "step":
                    self.progress.step(1)
                elif kind == "done":
                    self.working = False
                    self.convert_button.state(["!disabled"])
                    self._say(f"Finished. Files are in {text}", "dim")
                else:
                    self._say(text, "bad" if kind == "bad" else "")
        except queue.Empty:
            pass
        self.root.after(100, self._drain)

    # -- odds and ends -----------------------------------------------------

    def _say(self, text: str, tag: str = "") -> None:
        self.log.configure(state="normal")
        self.log.insert(tk.END, text + "\n", tag)
        self.log.see(tk.END)
        self.log.configure(state="disabled")

    def _clear_log(self) -> None:
        self.log.configure(state="normal")
        self.log.delete("1.0", tk.END)
        self.log.configure(state="disabled")

    def _load_settings(self) -> dict:
        # Settings are a convenience. A missing or broken file just means
        # defaults, never a window that will not open.
        try:
            return json.loads(SETTINGS.read_text(encoding="utf-8"))
        except (OSError, ValueError):
            return {}

    def _save_settings(self) -> None:
        try:
            SETTINGS.write_text(
                json.dumps({"out": self.out_dir.get(), "send": self.send.get()}),
                encoding="utf-8",
            )
        except OSError:
            pass


def main() -> None:
    root = tk.Tk()
    root.title("Book Light")
    root.minsize(560, 620)
    App(root)
    root.mainloop()


if __name__ == "__main__":
    main()
