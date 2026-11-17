#!/bin/bash
# Desktop Mode launch layer (work package WP3): proot_layer/wrapper.sh of the Master
# Blueprint, section 5. Sourced by the app, never by a user:
# these are internal helper functions, not commands, and nothing here is put on
# PATH or into the Debian shell (brief, section 12, rule 3).
#
# The app (DesktopLauncher.kt) runs
#     bash -c '. wrapper.sh; desktop_start'
# with this environment:
#   PROOT               path of libproot.so
#   DESKTOP_ROOT        the Debian tree (filesDir/debian_rootfs)
#   DESKTOP_DIR         filesDir/proot_layer (session files, never inside the tree)
#   DESKTOP_BINDS       one host:guest bind per line (workspace, caches, project folders)
#   DESKTOP_NO_SECCOMP  1 to run proot with PROOT_NO_SECCOMP=1 (the fallback)
# PROOT_LOADER, PROOT_TMP_DIR and LD_LIBRARY_PATH come from the app's own
# environment (util/Environment.kt) and are passed through untouched.
#
# The display is Termux:X11 (built into the app). Its X server is NOT started here: the
# app starts it first, as a process of its own, with its socket in the tree's /tmp, so
# the guest sees /tmp/.X11-unix/X0 and just uses DISPLAY=:0. This script only runs the
# Debian side.
#
# STATUS: UNTESTED on a device. The proot command line is the brief's reference
# line with the adjustments the accepted WP1 device run showed were needed.

DESKTOP_READY_MARKER=.vsc-display-ready
DESKTOP_GUEST_PATH=/root/.npm-global/bin:/root/.local/bin:/root/.cargo/bin:/root/go/bin:/root/.config/composer/vendor/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin

# Runs one command inside the Debian tree under PRoot, in the foreground.
#   desktop_proot_exec [VAR=value ...] -- <command> [args...]
# Variables before `--` are added to the guest's (otherwise empty) environment.
desktop_proot_exec() {
    local -a guest_env=() cmd=()
    while [ $# -gt 0 ] && [ "$1" != "--" ]; do guest_env+=("$1"); shift; done
    [ "${1:-}" = "--" ] && shift
    mkdir -p "$DESKTOP_DIR/shm"
    cmd=("$PROOT" --link2symlink -0 --kill-on-exit -r "$DESKTOP_ROOT"
         -b /dev -b /proc -b /sys -b "$DESKTOP_DIR/shm:/dev/shm")
    local line
    if [ -n "${DESKTOP_BINDS:-}" ]; then
        while IFS= read -r line; do
            [ -n "$line" ] && cmd+=(-b "$line")
        done <<EOF
$DESKTOP_BINDS
EOF
    fi
    cmd+=(-w /root /usr/bin/env -i HOME=/root USER=root LANG=C.UTF-8 TERM=xterm-256color
          "PATH=$DESKTOP_GUEST_PATH" DEBIAN_FRONTEND=noninteractive)
    [ ${#guest_env[@]} -gt 0 ] && cmd+=("${guest_env[@]}")
    cmd+=("$@")
    (
        # proot must not run under the app's exec interceptor: it rewrites exec
        # paths to the Termux prefix, which is what stopped proot from starting
        # /usr/bin/env inside Debian in the first device run.
        unset LD_PRELOAD TERMUX_EXEC__PROC_SELF_EXE TERMUX_EXEC__LD_PRELOAD \
              TERMUX_EXEC__SYSTEM_LINKER_EXEC__MODE
        [ "${DESKTOP_NO_SECCOMP:-0}" = "1" ] && export PROOT_NO_SECCOMP=1
        exec "${cmd[@]}"
    )
}

# Removes the leftover of an earlier session that would stop a new one: the marker that
# says the display answered. (The X lock and socket belong to the app's X server, which
# the app has just started; they are not touched here.)
desktop_clean_locks() {
    rm -f "$DESKTOP_ROOT/tmp/$DESKTOP_READY_MARKER"
}

# Keeps the guest's own logs from growing without end: any log under the tree's /tmp
# over 1 MiB is cut to its last 256 KiB. Runs before the session starts, when nothing
# holds them open. No log ever contains the session secret.
desktop_rotate_logs() {
    local f size
    for f in "$DESKTOP_ROOT"/tmp/*.log; do
        [ -f "$f" ] || continue
        size=$(wc -c < "$f" 2>/dev/null) || continue
        if [ "${size:-0}" -gt 1048576 ]; then
            tail -c 262144 "$f" > "$f.tmp" && mv "$f.tmp" "$f"
        fi
    done
}

# Runs the Debian side of the session in ONE proot, so that the window manager and the
# editor end together when it ends. Does not return until the session is over; the app
# holds the process and stops it by signal. The app stops any earlier session first.
desktop_start() {
    desktop_clean_locks
    desktop_rotate_logs
    mkdir -p "$DESKTOP_ROOT/tmp"
    exec_session
}

exec_session() {
    desktop_proot_exec "READY_MARKER=$DESKTOP_READY_MARKER" -- \
        /bin/bash -c '
set -u
umask 077
mkdir -p /root/workspace /tmp/.X11-unix
export DISPLAY=:0
# Wait for the app'"'"'s X server (Termux:X11) to answer from in here, then say so.
i=0
until xdpyinfo >/dev/null 2>&1; do
    i=$((i + 1))
    [ $i -ge 60 ] && { echo "display :0 did not answer" >&2; exit 12; }
    sleep 0.5
done
touch "/tmp/$READY_MARKER"
openbox >/tmp/openbox.log 2>&1 &
# The editor (work package WP4). Plain VSCodium, started the way a PC desktop would
# start it, with the flags Electron needs inside proot and nothing else: no heap or
# memory flags (Master Blueprint section 2 is not implemented). It is the one
# window; closing it leaves the desktop, as on a PC.
mkdir -p /root/.config/VSCodium/User /root/.vscode-oss/extensions
if [ ! -e /root/.config/VSCodium/User/settings.json ]; then
cat > /root/.config/VSCodium/User/settings.json <<JSON
{
  "editor.smoothScrolling": false,
  "workbench.list.smoothScrolling": false,
  "terminal.integrated.smoothScrolling": false,
  "workbench.reduceMotion": "on"
}
JSON
fi
if [ -x /usr/share/codium/codium ]; then
    sleep 1
    /usr/share/codium/codium --no-sandbox --disable-gpu --disable-dev-shm-usage \
        --no-zygote --disable-setuid-sandbox --ozone-platform=x11 --password-store=basic \
        --user-data-dir=/root/.config/VSCodium --extensions-dir=/root/.vscode-oss/extensions \
        /root/workspace >/tmp/codium.log 2>&1 &
fi
wait
'
}

# Ends every process that belongs to a session of this Debian tree, whether or
# not the app still holds its parent: any process whose command line names the
# tree, and the app's X server (process name vscodroid-x11). TERM first, KILL for
# what is left. Prints how many it found.
desktop_stop() {
    local found=0 pid cl self=$$
    local -a pids=()
    for d in /proc/[0-9]*; do
        pid=${d#/proc/}
        [ "$pid" = "$self" ] && continue
        cl=$(tr '\0' ' ' < "$d/cmdline" 2>/dev/null) || continue
        case "$cl" in
            *"$DESKTOP_ROOT"*|*vscodroid-x11*) pids+=("$pid") ;;
        esac
    done
    found=${#pids[@]}
    if [ "$found" -gt 0 ]; then
        kill -TERM "${pids[@]}" 2>/dev/null
        sleep 1
        kill -KILL "${pids[@]}" 2>/dev/null
    fi
    echo "$found"
}

# Health of the display. Prints "x=up|down session=up|down"; exit 0 only if both are up.
desktop_status() {
    local x=down session=down
    [ -S "$DESKTOP_ROOT/tmp/.X11-unix/X0" ] && x=up
    [ -f "$DESKTOP_ROOT/tmp/$DESKTOP_READY_MARKER" ] && session=up
    echo "x=$x session=$session"
    [ "$x" = up ] && [ "$session" = up ]
}

# One-time: the packages the screen needs, inside the Debian tree. Idempotent.
# policy-rc.d keeps apt from trying to start services, which cannot run here.
desktop_install_stack() {
    mkdir -p "$DESKTOP_ROOT/usr/sbin"
    printf '#!/bin/sh\nexit 101\n' > "$DESKTOP_ROOT/usr/sbin/policy-rc.d"
    chmod 755 "$DESKTOP_ROOT/usr/sbin/policy-rc.d"
    desktop_proot_exec -- /bin/bash -c '
set -e
apt-get update
apt-get install -y --no-install-recommends \
    ca-certificates curl xauth x11-utils xterm xclip x11-xserver-utils \
    fonts-dejavu-core xfonts-base \
    xkb-data openbox
'
}
