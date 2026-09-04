#!/usr/bin/env python3
"""Copy .book files onto a phone over USB.

    python push.py "Moby Dick.book"
    python push.py *.book

Needs `adb` on PATH and USB debugging turned on. If adb is not an option,
copy the .book files across however you normally move files to the phone and
drop them in Book Light's folder - the reader takes in anything it finds.
"""

from __future__ import annotations

import argparse
import shutil
import subprocess
import sys
from pathlib import Path

PACKAGE = "com.outofthewhale.booklight"
LP2_PACKAGE = "com.outofthewhale.booklight.lp2"

# Where each build keeps its books. The Light Phone 3 tool is sandboxed and its
# directory is private; the Light Phone 2 build is an ordinary app and its
# external directory can be written straight into.
LP3_BOOKS = "files/books"
LP2_BOOKS = f"/sdcard/Android/data/{LP2_PACKAGE}/files/books"

STAGING = "/data/local/tmp"


def adb(*args: str, check: bool = True) -> subprocess.CompletedProcess:
    tool = shutil.which("adb")
    if not tool:
        sys.exit(
            "adb is not on PATH.\n"
            "It comes with the Android platform-tools:\n"
            "    https://developer.android.com/tools/releases/platform-tools"
        )
    result = subprocess.run([tool, *args], capture_output=True, text=True)
    if check and result.returncode != 0:
        sys.exit(f"adb {' '.join(args)} failed:\n{result.stderr.strip()}")
    return result


def one_device() -> None:
    lines = adb("devices").stdout.strip().splitlines()[1:]
    devices = [line.split()[0] for line in lines if line.strip().endswith("device")]
    if not devices:
        sys.exit("No phone found. Plug it in and turn on USB debugging.")
    if len(devices) > 1:
        sys.exit(f"More than one device is connected: {', '.join(devices)}")


def installed(package: str) -> bool:
    result = adb("shell", "pm", "path", package, check=False)
    return result.returncode == 0 and "package:" in result.stdout


def push_lp3(files: list[Path]) -> None:
    """Copy into the tool's private directory, by way of run-as.

    adb cannot write an app's private directory itself, so each file goes to
    /data/local/tmp first and the app copies it in. `run-as` only works on a
    debuggable build - on a release build this fails, and the message says so
    rather than leaving the reader wondering where the book went.
    """
    adb("shell", "run-as", PACKAGE, "mkdir", "-p", LP3_BOOKS, check=False)
    probe = adb("shell", "run-as", PACKAGE, "ls", check=False)
    if probe.returncode != 0:
        sys.exit(
            f"This build of {PACKAGE} will not accept files over adb.\n"
            f"  {probe.stderr.strip() or probe.stdout.strip()}\n\n"
            "run-as needs a debuggable build. Install the debug APK\n"
            "(tool/build/outputs/apk/debug), or copy the .book files onto the\n"
            "phone another way - the reader takes in whatever it finds."
        )

    for path in files:
        staged = f"{STAGING}/{path.name}"
        adb("push", str(path), staged)
        adb("shell", "run-as", PACKAGE, "cp", staged, f"{LP3_BOOKS}/{path.name}")
        adb("shell", "rm", "-f", staged, check=False)
        print(f"{path.name}  ->  {PACKAGE}")


def push_lp2(files: list[Path]) -> None:
    adb("shell", "mkdir", "-p", LP2_BOOKS)
    for path in files:
        adb("push", str(path), f"{LP2_BOOKS}/{path.name}")
        print(f"{path.name}  ->  {LP2_PACKAGE}")


def push(files: list[Path]) -> None:
    if not files:
        return
    one_device()
    if installed(PACKAGE):
        push_lp3(files)
    elif installed(LP2_PACKAGE):
        push_lp2(files)
    else:
        sys.exit(
            "Book Light is not installed on this phone.\n"
            f"Looked for {PACKAGE} and {LP2_PACKAGE}."
        )


def main() -> None:
    parser = argparse.ArgumentParser(description="Copy .book files onto a phone.")
    parser.add_argument("files", nargs="+", type=Path)
    args = parser.parse_args()

    for path in args.files:
        if not path.is_file():
            sys.exit(f"No such file: {path}")
    push(args.files)


if __name__ == "__main__":
    main()
