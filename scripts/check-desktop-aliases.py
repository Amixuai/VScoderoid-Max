#!/usr/bin/env python3
"""Check the Windows and macOS command aliases of Desktop Mode (brief, section 12).

    check-desktop-aliases.py

`android/app/src/main/assets/desktop/10-compat-aliases.sh` is installed inside the
Debian system and maps a short list of Windows and macOS command names to their
Linux equivalent with a plain shell alias. The rules this enforces:

  * the file defines exactly the names in EXPECTED below, each mapped to the
    command in the brief, nothing else: the app invents no command names;
  * no name is a bash builtin or keyword, and none is already a program, except the
    names in FORCED, which the Master Blueprint orders (`dir`);
  * no name is already a program on this machine's PATH, except the names in
    EXEMPT, each with its reason (this host is not Debian, so a name that only
    exists here is a false alarm; the rule is still checked on a device, see
    docs/DEVICE_TEST_CHECKLIST.md);
  * the file only defines aliases through `_vsc_alias`, which itself refuses to
    shadow anything at load time, and returns early for non-interactive shells;

What no text check can prove is that no document names a command that exists only
in this app; the closed list in EXPECTED is the guard for that.
"""

import pathlib
import re
import shutil
import subprocess
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
FILE = ROOT / "android/app/src/main/assets/desktop/10-compat-aliases.sh"

EXPECTED = {
    "cls": "clear",
    "copy": "cp",
    "move": "mv",
    "del": "rm",
    "erase": "rm",
    "md": "mkdir -p",
    "rd": "rmdir",
    "where": "which",
    "findstr": "grep",
    "tasklist": "ps aux",
    "ipconfig": "ip addr",
    "open": "xdg-open",
    "pbcopy": "xclip -selection clipboard",
    "pbpaste": "xclip -selection clipboard -o",
    "dir": "ls -l",
}

# Names the Master Blueprint orders to be aliased even though a program of that name exists.
# They are written with _vsc_force_alias; every other name must go through the guarded
# _vsc_alias. The list is closed: adding a name here needs the same document to name it.
FORCED = {"dir": "section 1 of the Master Blueprint names `dir` as its example"}

# Names that may exist as a program on a CI host and still be right to alias.
EXEMPT = {
    "open": "provided on Ubuntu hosts by the console tools; absent from Debian trixie's base system",
}

LINE = re.compile(r"^(_vsc_alias|_vsc_force_alias) (\S+) '([^']*)'$")


def shell_words(kind: str) -> set:
    out = subprocess.run(["bash", "-c", f"compgen -{kind}"], capture_output=True, text=True, check=True).stdout
    return set(out.split())


def main() -> int:
    failures = []
    if not FILE.is_file():
        print(f"FAIL  {FILE.relative_to(ROOT)} is missing")
        return 1
    text = FILE.read_text()
    found = {}
    for number, line in enumerate(text.splitlines(), 1):
        if line.startswith(("_vsc_alias ", "_vsc_force_alias ")):
            m = LINE.match(line)
            if not m:
                failures.append(f"line {number}: not in the form  _vsc_alias NAME 'command'")
                continue
            kind, name, command = m.groups()
            if (kind == "_vsc_force_alias") != (name in FORCED):
                failures.append(f"line {number}: {name} must use {'_vsc_force_alias' if name in FORCED else '_vsc_alias'}")
            if name in found:
                failures.append(f"line {number}: {name} is defined twice")
            found[name] = command
    for name in sorted(set(found) - set(EXPECTED)):
        failures.append(f"{name} is not in the brief's table: the app may not invent commands")
    for name in sorted(set(EXPECTED) - set(found)):
        failures.append(f"{name} is in the brief's table but missing from the file")
    for name, command in found.items():
        if name in EXPECTED and EXPECTED[name] != command:
            failures.append(f"{name} runs '{command}' but the brief says '{EXPECTED[name]}'")
    builtins = shell_words("b") | shell_words("k")
    for name in sorted(found):
        if name in FORCED:
            continue
        if name in builtins:
            failures.append(f"{name} is a bash builtin or keyword and must not be aliased")
        elif shutil.which(name) and name not in EXEMPT:
            failures.append(f"{name} is already a program here ({shutil.which(name)}) and must not be aliased")
    if "[ -n \"$(type -t \"$1\"" not in text:
        failures.append("_vsc_alias no longer checks `type -t` before aliasing")
    if "case $- in *i*)" not in text:
        failures.append("the file no longer returns early for non-interactive shells")
    for failure in failures:
        print(f"FAIL  {failure}")
    if not failures:
        print(f"  ok     {len(found)} aliases, none shadow a command")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
