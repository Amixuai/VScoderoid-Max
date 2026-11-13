#!/usr/bin/env python3
"""Check the pinned Debian download for Desktop Mode is well formed.

    check-desktop-pins.py [--require-pinned]

`android/app/src/main/assets/desktop/pins.json` names the root file system the
app downloads on first use of Desktop Mode: its URL, its SHA-256 and its size.
The app refuses to download anything that does not match, so a typo here is a
feature that fails on every phone, after the APK has shipped.

Always checked:

  * the file parses and has the keys the app reads (the app's parser,
    DesktopPins.parse, throws on a missing one);
  * the suite, resolver addresses and mirrors are plain values: they are written
    into /etc files inside the guest, so a newline in one would add lines there;
  * a URL, when given, is https (the app also refuses anything else);
  * a SHA-256, when given, is 64 lower-case hex characters, and is not the
    digest of the 14-byte body "404: Not Found" that raw.githubusercontent.com
    returns for a path that does not exist, which is exactly what pinning a wrong
    URL produces;
  * a size, when given, is not that 14-byte body either.

The URL, digest and size are either all present or all empty. A half-filled pin
is the one state that is neither safe to ship nor honestly "not done yet".

With --require-pinned, an empty pin fails too. release.yml passes it: a debug
build may carry an empty pin (the app then says Desktop Mode is not available),
a signed release must not.

This checks the SHAPE. It cannot say the URL serves the file the digest names;
only downloading it can, and CI does not fetch 50 MB of Debian on every run.
"""

import json
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
PINS = ROOT / "android/app/src/main/assets/desktop/pins.json"

SHA256 = re.compile(r"^[0-9a-f]{64}$")
SUITE = re.compile(r"^[a-z][a-z0-9-]{1,31}$")
ADDRESS = re.compile(r"^[0-9a-fA-F:.]{2,45}$")
MIRROR = re.compile(r"^https?://[A-Za-z0-9.-]+(:[0-9]+)?(/[A-Za-z0-9._~/-]*)?$")

# sha256("404: Not Found"), which is what a wrong raw.githubusercontent.com path
# hands back. Measured on a real phone while choosing this pin.
NOT_FOUND_SHA256 = "d5558cd419c8d46bdc958064cb97f963d1ea793866414c025906ec15033512ed"
NOT_FOUND_BYTES = 14


def main() -> int:
    require_pinned = "--require-pinned" in sys.argv[1:]
    failures = []

    try:
        data = json.loads(PINS.read_text(encoding="utf-8"))
    except (OSError, ValueError) as e:
        print(f"  FAIL   {PINS.relative_to(ROOT)} cannot be read: {e}")
        return 1

    def need(obj, key, kind, where):
        value = obj.get(key) if isinstance(obj, dict) else None
        if not isinstance(value, kind) or isinstance(value, bool):
            failures.append(f"{where}{key} is missing or of the wrong type")
            return None
        return value

    if data.get("schema") != 1:
        failures.append("schema must be 1")
    suite = need(data, "suite", str, "")
    mirror = need(data, "mirror", str, "")
    security = need(data, "security_mirror", str, "")
    dns = need(data, "dns", list, "")
    rootfs = need(data, "rootfs", dict, "")

    if suite is not None and not SUITE.match(suite):
        failures.append("suite is not a plain lower-case name")
    for label, value in (("mirror", mirror), ("security_mirror", security)):
        if value is not None and not MIRROR.match(value):
            failures.append(f"{label} is not a plain http(s) URL")
    if dns is not None and (not dns or not all(isinstance(a, str) and ADDRESS.match(a) for a in dns)):
        failures.append("dns must be a non-empty list of addresses")

    url = sha = size = est = None
    if rootfs is not None:
        url = need(rootfs, "url", str, "rootfs.")
        sha = need(rootfs, "sha256", str, "rootfs.")
        size = need(rootfs, "bytes", int, "rootfs.")
        est = need(rootfs, "unpacked_bytes_estimate", int, "rootfs.")
        strip = rootfs.get("strip_components", 0)
        if not isinstance(strip, int) or isinstance(strip, bool) or not 0 <= strip <= 2:
            failures.append("rootfs.strip_components must be an integer from 0 to 2")

    if est is not None and est <= 0:
        failures.append("rootfs.unpacked_bytes_estimate must be positive")

    pinned = False
    if None not in (url, sha, size):
        filled = [bool(url), bool(sha), size > 0]
        if any(filled) and not all(filled):
            failures.append("rootfs url, sha256 and bytes must be all filled or all empty")
        elif all(filled):
            pinned = True
            if not url.startswith("https://"):
                failures.append("rootfs.url must be https")
            if not SHA256.match(sha):
                failures.append("rootfs.sha256 must be 64 lower-case hex characters")
            if sha == NOT_FOUND_SHA256 or size == NOT_FOUND_BYTES:
                failures.append("rootfs pin is the body of a 404 page: the URL does not exist")

    if failures:
        for f in failures:
            print(f"  FAIL   {f}")
        return 1
    if not pinned:
        if require_pinned:
            print("  FAIL   no root file system is pinned, and --require-pinned was given")
            return 1
        print("  note   no root file system pinned yet; Desktop Mode will report itself unavailable")
        return 0
    print(f"  ok     rootfs pinned for {suite}: {size} bytes")
    return 0


if __name__ == "__main__":
    sys.exit(main())
