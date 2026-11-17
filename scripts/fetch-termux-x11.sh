#!/usr/bin/env bash
# Fetches Termux:X11 (the Desktop Mode display server and screen) at the commit pinned
# in TERMUX_X11_COMMIT, with its submodules, into android/third_party/termux-x11, and
# applies scripts/patch-termux-x11.py. android/settings.gradle.kts runs this when the
# folder is missing, so every Gradle build (local, CI, release) has the source it needs
# and no workflow has to know about it. The folder is untracked (.gitignore).
set -euo pipefail

root=$(cd "$(dirname "$0")/.." && pwd)
commit=$(tr -d '[:space:]' < "$root/TERMUX_X11_COMMIT")
dest="$root/android/third_party/termux-x11"
stamp="$dest/.vscodroid-fetched"

if [ -f "$stamp" ] && [ "$(cat "$stamp")" = "$commit" ]; then
    exit 0
fi

rm -rf "$dest"
mkdir -p "$dest"
git -C "$dest" init -q
git -C "$dest" remote add origin https://github.com/termux/termux-x11.git
git -C "$dest" fetch -q --depth 1 origin "$commit"
git -C "$dest" checkout -q FETCH_HEAD
git -C "$dest" submodule update --init --recursive --jobs 8 --depth 1
python3 -I "$root/scripts/patch-termux-x11.py" "$dest"
echo "$commit" > "$stamp"
echo "Termux:X11 $commit ready in $dest"
