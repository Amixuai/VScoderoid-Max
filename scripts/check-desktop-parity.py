#!/usr/bin/env python3
"""Hold Desktop Mode to the product rule: it is a PC, not an app with habits.

    check-desktop-parity.py

The owner's rule for Desktop Mode is strict PC parity. A student who later moves to
a laptop must not have learned anything that works only in this app. That cannot be
proved by a text check, but the ways it is most easily broken can be refused, and
this script refuses them:

  * no memory or heap flag is passed to the editor or to node (Master Blueprint
    section 2, Memory Throttling, is deliberately not implemented);
  * the Desktop Mode screen layout holds no button, toolbar or menu: the screen is
    a web view and a status line;
  * the Desktop Mode screen code adds no menu and no JavaScript bridge, which is how
    an app-only action would get into the desktop;
  * the Debian system gets no program of its own: nothing is written to
    /usr/local/bin or /usr/bin by the app's code or assets, and profile.d holds
    exactly the two files the brief names (aliases and paths);
  * the manifest gives Desktop Mode one launcher entry and no other way in.

A pass means none of those holds, not that the desktop behaves like a PC; the device
checklist (docs/DESKTOP_DEVICE_CHECKLIST.md, parity audit) covers the rest. Every run
first hands each refusal the input it exists to refuse, so a rule that stopped
matching anything fails the run instead of passing it.
"""

import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
MAIN = ROOT / "android/app/src/main"
DESKTOP_KT = MAIN / "kotlin/com/vscodroid/desktop"
ASSETS = MAIN / "assets/desktop"
LAYOUT = MAIN / "res/layout/activity_desktop.xml"
MANIFEST = MAIN / "AndroidManifest.xml"
BUILD = ROOT / "scripts/build-desktop-rootfs.sh"

HEAP_FLAG = re.compile(r"max-old-space-size|max_old_space|--js-flags|--max-semi-space|--expose-gc|NODE_OPTIONS=")
WIDGET = re.compile(r"<\s*(Button|ImageButton|MaterialButton|FloatingActionButton|ExtendedFloatingActionButton|"
                    r"Toolbar|MaterialToolbar|BottomNavigationView|NavigationView|Chip|Switch|CheckBox)\b|"
                    r"<\s*[\w.]*(Button|Toolbar)\b|\bandroid:onClick\b|<\s*menu\b")
APP_ONLY_CODE = re.compile(r"onCreateOptionsMenu|onPrepareOptionsMenu|setOnClickListener|addJavascriptInterface|"
                           r"setSupportActionBar|FloatingActionButton")
OWN_PROGRAM = re.compile(r"usr/local/s?bin/[A-Za-z0-9_.-]+['\"]?\s*(>|<<|\)|,)|>\s*['\"]?\$?[A-Za-z_{}]*/usr/(local/)?s?bin/|"
                         r"install\s+[^\n]*\s/usr/(local/)?s?bin/|cp\s+[^\n]*\s/usr/(local/)?s?bin/|"
                         r"ln\s+-s\w*\s+[^\n]*\s/usr/(local/)?s?bin/")
PROFILE_D = re.compile(r"profile\.d/([A-Za-z0-9_.-]+)")
ALLOWED_PROFILE_D = {"10-compat-aliases.sh", "20-dev-paths.sh"}


def strip_comments(text: str, line_prefixes=("//", "*", "/*", "#")) -> str:
    return "\n".join(l for l in text.splitlines() if not l.lstrip().startswith(line_prefixes))


def heap_flags(text: str) -> list[str]:
    return HEAP_FLAG.findall(strip_comments(text))


def widgets(xml: str) -> list[str]:
    return [m.group(0) for m in WIDGET.finditer(re.sub(r"<!--.*?-->", "", xml, flags=re.S))]


def app_only_code(text: str) -> list[str]:
    return APP_ONLY_CODE.findall(strip_comments(text, ("//", "*", "/*")))


def own_programs(text: str) -> list[str]:
    # policy-rc.d is apt's own hook that stops packages starting services, which cannot
    # run here; it is not a command anybody types.
    lines = [l for l in strip_comments(text, ("//", "*", "/*", "#")).splitlines() if "policy-rc.d" not in l]
    return [m.group(0) for m in OWN_PROGRAM.finditer("\n".join(lines))]


def profile_files(text: str) -> set[str]:
    return set(PROFILE_D.findall(text)) - ALLOWED_PROFILE_D


def launcher_entries(manifest: str) -> list[str]:
    found = []
    for m in re.finditer(r"<activity\b(.*?)</activity>", manifest, flags=re.S):
        block = m.group(1)
        if "android.intent.category.LAUNCHER" in block:
            name = re.search(r'android:name="([^"]+)"', block)
            found.append(name.group(1) if name else "?")
    return found


def self_test() -> list[str]:
    bad = []
    if not heap_flags('--js-flags=--max-old-space-size=2048'):
        bad.append("heap_flags no longer sees a heap flag")
    if heap_flags("// --max-old-space-size is not used here"):
        bad.append("heap_flags reads a comment")
    if not widgets('<Button android:id="@+id/x"/>') or not widgets("<com.google.android.material.button.MaterialButton/>"):
        bad.append("widgets no longer sees a button")
    if widgets("<!-- no Button here --><WebView/>"):
        bad.append("widgets reads a comment")
    if not app_only_code("webView.addJavascriptInterface(x, y)"):
        bad.append("app_only_code no longer sees a JS bridge")
    if not own_programs("cp tool /usr/local/bin/vscodroid-start") or not own_programs("printf x > /usr/local/bin/foo"):
        bad.append("own_programs no longer sees a program written into /usr/local/bin")
    if own_programs('printf x > "$ROOT/usr/sbin/policy-rc.d"'):
        bad.append("own_programs refuses apt's policy-rc.d")
    if own_programs("PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin"):
        bad.append("own_programs reads a PATH line")
    if profile_files("writeGuestFile(\"etc/profile.d/vscodroid.sh\")") != {"vscodroid.sh"}:
        bad.append("profile_files no longer sees an extra profile.d file")
    if launcher_entries('<activity android:name=".A"><category android:name="android.intent.category.LAUNCHER"/></activity>') != [".A"]:
        bad.append("launcher_entries no longer sees a launcher")
    return bad


def main() -> int:
    failures = [f"self-test: {m}" for m in self_test()]
    ok = []

    kt = {p: p.read_text(encoding="utf-8") for p in sorted(DESKTOP_KT.glob("*.kt"))}
    sh = {p: p.read_text(encoding="utf-8") for p in sorted(ASSETS.glob("*.sh"))}
    if not kt or not sh:
        failures.append("no Desktop Mode sources found; this check would otherwise read nothing")

    for path, text in {**kt, **sh, BUILD: BUILD.read_text(encoding="utf-8")}.items():
        for flag in heap_flags(text):
            failures.append(f"{path.relative_to(ROOT)}: memory flag {flag!r} (Master section 2 is not implemented)")
        for prog in own_programs(text):
            failures.append(f"{path.relative_to(ROOT)}: writes a program into the system path: {prog.strip()!r}")
        for extra in profile_files(text):
            failures.append(f"{path.relative_to(ROOT)}: profile.d file {extra!r} (only the aliases and paths files are allowed)")
    ok.append("no memory flags, no own programs, profile.d limited to aliases and paths")

    for widget in widgets(LAYOUT.read_text(encoding="utf-8")):
        failures.append(f"{LAYOUT.relative_to(ROOT)}: {widget!r} (the screen has no controls of its own)")
    for path in (DESKTOP_KT / "DesktopActivity.kt", DESKTOP_KT / "DesktopService.kt"):
        for hit in app_only_code(kt[path]):
            failures.append(f"{path.relative_to(ROOT)}: {hit!r} (nothing app-only may enter the desktop)")
    ok.append("the screen is a web view and a status line")

    launchers = [n for n in launcher_entries(MANIFEST.read_text(encoding="utf-8")) if "desktop" in n.lower()]
    if launchers != [".desktop.DesktopActivity"]:
        failures.append(f"manifest: Desktop Mode launcher entries are {launchers}, expected exactly one")
    ok.append("one launcher entry")

    for line in ok:
        print(f"  ok     {line}")
    for line in failures:
        print(f"  FAIL   {line}")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
