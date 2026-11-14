#!/usr/bin/env bash
set -euo pipefail

# Builds the Debian system that Desktop Mode ships INSIDE the APK
# (docs: "VSCodroid Desktop Mode: Engineering Brief" WP2; "Master Blueprint" sections 3 and 5).
#
#   sudo ./scripts/build-desktop-rootfs.sh            full build
#   ./scripts/build-desktop-rootfs.sh --self-check    only the steps that need no root and no emulator
#
# Output, git-ignored build products the APK packages as assets:
#   android/app/src/main/assets/desktop/rootfs.tar.xz        the system, xz level 6 (8 MiB window: a phone decodes it)
#   android/app/src/main/assets/desktop/rootfs.meta.json     digest, sizes, package list, editor version
#
# WHAT IT DOES
#   1. Downloads the pinned arm64 Debian base image (pins.json) and checks its SHA-256.
#   2. Unpacks it as root and enters it with chroot under qemu-user (an x86-64 CI host runs the
#      arm64 programs through the binfmt handler; on CI `docker run --privileged --rm
#      tonistiigi/binfmt --install arm64` registers it).
#   3. apt-get installs scripts/desktop-packages.txt: the screen stack, the compilers, everyday tools.
#   4. Installs the VSCodium arm64 .deb (the Electron editor), after checking the SHA-256 that
#      VSCodium publishes beside it, then lays out /engines/electron_vscode (Master Blueprint, item 3).
#   5. Removes what must not ship (the emulator binary, apt caches, logs), packs the tree, writes the metadata.
#
# VSCODIUM_VERSION  (environment, or the VSCODIUM_VERSION file at the repository root) pins the editor
#                   release, for example 1.99.32562; when empty the newest release is used and recorded
#                   in rootfs.meta.json. Release builds should pin it.
# DESKTOP_MIRROR    (environment) overrides the Debian mirror in pins.json.
#
# STATUS: the steps up to unpacking were run; the chroot half (steps 2-4) could not be run where this
# was written (no qemu-user, no root namespace) and is UNTESTED. The first CI run is its test.
#
# Compatible with bash 3.2+ for --self-check; the full build needs Linux and root.

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(dirname "$SCRIPT_DIR")"
ASSETS_DIR="$ROOT_DIR/android/app/src/main/assets/desktop"
WORK_DIR="${DESKTOP_WORK_DIR:-$ROOT_DIR/toolchains/desktop-rootfs}"
PINS="$ASSETS_DIR/pins.json"
PACKAGES_FILE="$SCRIPT_DIR/desktop-packages.txt"

SELF_CHECK=0
[ "${1:-}" = "--self-check" ] && SELF_CHECK=1

pin() { python3 -I -c 'import json,sys; d=json.load(open(sys.argv[1])); v=d
for k in sys.argv[2].split("."): v=v[k]
print(v)' "$PINS" "$1"; }

BASE_URL="$(pin rootfs.url)"
BASE_SHA="$(pin rootfs.sha256)"
BASE_STRIP="$(pin rootfs.strip_components)"
MIRROR="${DESKTOP_MIRROR:-$(pin mirror)}"
SUITE="$(pin suite)"

if [ -z "$BASE_URL" ] || [ -z "$BASE_SHA" ]; then
    echo "ERROR: $PINS has no pinned base image." >&2
    exit 1
fi

packages() { sed -e 's/#.*//' -e 's/[[:space:]]*$//' "$PACKAGES_FILE" | grep -v '^$'; }

echo "=== Desktop Mode system ==="
echo "  base:     $BASE_URL"
echo "  suite:    $SUITE   mirror: $MIRROR"
echo "  packages: $(packages | wc -l | tr -d ' ')"

mkdir -p "$WORK_DIR"
BASE_FILE="$WORK_DIR/base.tar.xz"

sha_of() { if command -v sha256sum >/dev/null 2>&1; then sha256sum "$1" | cut -d' ' -f1; else shasum -a 256 "$1" | cut -d' ' -f1; fi; }

if [ ! -f "$BASE_FILE" ] || [ "$(sha_of "$BASE_FILE")" != "$BASE_SHA" ]; then
    echo "Downloading the base image..."
    curl -fL --retry 5 --retry-delay 5 -o "$BASE_FILE.part" "$BASE_URL"
    mv "$BASE_FILE.part" "$BASE_FILE"
fi
ACTUAL="$(sha_of "$BASE_FILE")"
if [ "$ACTUAL" != "$BASE_SHA" ]; then
    echo "ERROR: base image digest $ACTUAL does not match the pin $BASE_SHA" >&2
    exit 1
fi
echo "  base image digest verified"

if [ "$SELF_CHECK" = 1 ]; then
    xz -t "$BASE_FILE"
    echo "  base image decompresses"
    echo "  self-check passed (the chroot steps were not run)"
    exit 0
fi

if [ "$(id -u)" != 0 ]; then
    echo "ERROR: the full build needs root (chroot, mount): run it with sudo." >&2
    exit 1
fi
if [ ! -e /proc/sys/fs/binfmt_misc/qemu-aarch64 ] && [ "$(uname -m)" != "aarch64" ]; then
    echo "ERROR: no qemu-aarch64 binfmt handler. On the CI host: docker run --privileged --rm tonistiigi/binfmt --install arm64 (or, on Debian, apt-get install qemu-user-static binfmt-support)" >&2
    exit 1
fi

TREE="$WORK_DIR/tree"
MOUNTS=()
cleanup() {
    set +e
    for ((i=${#MOUNTS[@]}-1; i>=0; i--)); do umount -l "${MOUNTS[$i]}" 2>/dev/null; done
}
trap cleanup EXIT

rm -rf "$TREE"
mkdir -p "$TREE"
echo "Unpacking the base image..."
tar -xpf "$BASE_FILE" -C "$TREE" --strip-components="$BASE_STRIP" --numeric-owner

if [ "$(uname -m)" != "aarch64" ]; then
    QEMU="$(command -v qemu-aarch64-static || true)"
    if [ -n "$QEMU" ]; then
        cp "$QEMU" "$TREE/usr/bin/qemu-aarch64-static"
    elif grep -q '^flags:.*F' /proc/sys/fs/binfmt_misc/qemu-aarch64; then
        # Registered with the fix-binary flag (tonistiigi/binfmt does this): the kernel already
        # holds the emulator open, so nothing has to be copied into the tree.
        echo "  emulator is registered with the F flag; not copied into the tree"
    else
        echo "ERROR: qemu-aarch64-static not found and the binfmt handler lacks the F flag" >&2
        exit 1
    fi
fi

mount -t proc proc "$TREE/proc";            MOUNTS+=("$TREE/proc")
mount --bind /dev "$TREE/dev";              MOUNTS+=("$TREE/dev")
mount -t devpts devpts "$TREE/dev/pts" 2>/dev/null && MOUNTS+=("$TREE/dev/pts") || true

in_tree() { chroot "$TREE" /usr/bin/env -i HOME=/root LANG=C.UTF-8 TERM=dumb DEBIAN_FRONTEND=noninteractive \
    PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin "$@"; }

# A network for apt, and the repositories this build uses (the app rewrites the same files on first use).
cp /etc/resolv.conf "$TREE/etc/resolv.conf"
mkdir -p "$TREE/etc/apt/sources.list.d"
: > "$TREE/etc/apt/sources.list"
cat > "$TREE/etc/apt/sources.list.d/debian.sources" <<EOF
Types: deb
URIs: $MIRROR
Suites: $SUITE $SUITE-updates
Components: main
Signed-By: /usr/share/keyrings/debian-archive-keyring.gpg

Types: deb
URIs: $(pin security_mirror)
Suites: $SUITE-security
Components: main
Signed-By: /usr/share/keyrings/debian-archive-keyring.gpg
EOF
# No service can start in a build tree; a policy of 101 tells maintainer scripts so.
printf '#!/bin/sh\nexit 101\n' > "$TREE/usr/sbin/policy-rc.d"; chmod 755 "$TREE/usr/sbin/policy-rc.d"

echo "Installing packages..."
in_tree apt-get update
# shellcheck disable=SC2046
in_tree apt-get install -y --no-install-recommends $(packages | tr '\n' ' ')

echo "Installing the editor..."
VER="${VSCODIUM_VERSION:-}"
if [ -z "$VER" ] && [ -f "$ROOT_DIR/VSCODIUM_VERSION" ]; then
    # grep exits 1 when the file holds only comments (the "newest release" setting); under
    # pipefail that used to end the whole build here without a word.
    VER="$( (grep -v '^#' "$ROOT_DIR/VSCODIUM_VERSION" || true) | head -1 | tr -d '[:space:]')"
fi
if [ -z "$VER" ]; then
    # Newest release tag, read from the redirect of the public "latest" page: no API call, so
    # no anonymous rate limit. The tag is the last path segment of the final URL.
    VER="$(curl -fsSL --retry 3 -o /dev/null -w '%{url_effective}' \
        https://github.com/VSCodium/vscodium/releases/latest 2>/dev/null | sed 's#.*/##' || true)"
fi
if [ -z "$VER" ] || [ "$VER" = "latest" ]; then
    VER="$(curl -fsSL --retry 3 ${GITHUB_TOKEN:+-H "Authorization: Bearer $GITHUB_TOKEN"} \
        https://api.github.com/repos/VSCodium/vscodium/releases/latest 2>/dev/null \
        | python3 -I -c 'import json,sys; print(json.load(sys.stdin)["tag_name"])' || true)"
fi
if [ -z "$VER" ] || [ "$VER" = "latest" ]; then
    echo "ERROR: could not work out which VSCodium release to install. Put a version (for example 1.99.32562) in the VSCODIUM_VERSION file." >&2
    exit 1
fi
echo "  VSCodium release: $VER"
DEB="codium_${VER}_arm64.deb"
DEB_URL="https://github.com/VSCodium/vscodium/releases/download/${VER}/${DEB}"
curl -fSL --retry 5 --retry-delay 5 -o "$WORK_DIR/$DEB" "$DEB_URL" || { echo "ERROR: could not download $DEB_URL" >&2; exit 1; }
curl -fSL --retry 5 -o "$WORK_DIR/$DEB.sha256" "$DEB_URL.sha256" || { echo "ERROR: could not download $DEB_URL.sha256" >&2; exit 1; }
EXPECTED="$(cut -d' ' -f1 "$WORK_DIR/$DEB.sha256" | head -1)"
if [ "$(sha_of "$WORK_DIR/$DEB")" != "$EXPECTED" ]; then
    echo "ERROR: $DEB does not match its published SHA-256" >&2
    exit 1
fi
cp "$WORK_DIR/$DEB" "$TREE/tmp/codium.deb"
in_tree apt-get install -y --no-install-recommends /tmp/codium.deb
in_tree test -x /usr/share/codium/codium

# Master Blueprint item 3: the editor's own folder, with its user data kept beside it.
mkdir -p "$TREE/engines/electron_vscode/user_data"
echo "$VER" > "$TREE/engines/electron_vscode/VERSION"
ln -sfn /usr/share/codium "$TREE/engines/electron_vscode/app"

echo "Cleaning..."
in_tree apt-get clean
rm -rf "$TREE"/var/lib/apt/lists/* "$TREE"/var/cache/apt/* "$TREE"/var/log/* "$TREE"/tmp/* \
       "$TREE/usr/bin/qemu-aarch64-static" "$TREE/etc/resolv.conf" "$TREE/usr/sbin/policy-rc.d"
cleanup
MOUNTS=()

echo "Packing..."
mkdir -p "$ASSETS_DIR"
OUT="$ASSETS_DIR/rootfs.tar.xz"
UNPACKED="$(du -sb "$TREE" | cut -f1)"
tar --numeric-owner -C "$TREE" --exclude='./proc/*' --exclude='./sys/*' --exclude='./dev/*' -cf - . | xz -T0 -6 > "$OUT"
BYTES="$(stat -c %s "$OUT")"
SHA="$(sha_of "$OUT")"

python3 -I - "$ASSETS_DIR/rootfs.meta.json" "$SHA" "$BYTES" "$UNPACKED" "$VER" "$SUITE" "$PACKAGES_FILE" <<'PY'
import json, sys
out, sha, nbytes, unpacked, ver, suite, pkgs = sys.argv[1:8]
names = [l.split("#")[0].strip() for l in open(pkgs)]
json.dump({
    "schema": 1,
    "sha256": sha,
    "bytes": int(nbytes),
    "unpacked_bytes": int(unpacked),
    "suite": suite,
    "vscodium": ver,
    "packages": [n for n in names if n],
}, open(out, "w"), indent=2)
PY
echo "=== Done: rootfs.tar.xz $((BYTES / 1048576)) MiB, unpacks to $((UNPACKED / 1048576)) MiB, editor $VER ==="
