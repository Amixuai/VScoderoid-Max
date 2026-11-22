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
#   DESKTOP_WORKSPACE   the guest path of the public workspace the editor opens
#   DESKTOP_DENSITY     the phone's screen density (for example 2.75), for the editor's scale
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
    desktop_proot_exec "READY_MARKER=$DESKTOP_READY_MARKER" "DENSITY=${DESKTOP_DENSITY:-1.00}" "WORKSPACE=${DESKTOP_WORKSPACE:-/storage/emulated/0/.VScoderoid-Pro}" -- \
        /bin/bash -c '
set -u
umask 077
mkdir -p "$WORKSPACE" /tmp/.X11-unix
export DISPLAY=:0
# Wait for the app'"'"'s X server (Termux:X11) to answer from in here, then say so.
i=0
until xdpyinfo >/dev/null 2>&1; do
    i=$((i + 1))
    [ $i -ge 60 ] && { echo "display :0 did not answer" >&2; exit 12; }
    sleep 0.5
done
touch "/tmp/$READY_MARKER"
# Every window opens maximized and without a frame, so the one editor window fills the whole
# Termux:X11 screen (which is the device screen) on any phone or tablet, with no empty bands.
mkdir -p /root/.config/openbox
cat > /root/.config/openbox/rc.xml <<XML
<?xml version="1.0" encoding="UTF-8"?>
<openbox_config xmlns="http://openbox.org/3.4/rc">
  <applications>
    <application class="*">
      <decor>no</decor>
      <maximized>yes</maximized>
    </application>
  </applications>
</openbox_config>
XML
# --- Bridges between the editor and Android. A private session bus and the accessibility bus
# (AT-SPI) run beside the editor, and both bridges talk to the app through small files in
# /tmp/.X11-unix (the one folder the app can read):
#   keyboard: a watcher listens for focus on the screen and writes "1 <n>" (an editable field,
#     show the keyboard) or "0 <n>" (anything else, hide it) into vsc-ime. Chromium builds its
#     accessibility tree only when asked, so the editor gets --force-renderer-accessibility below.
#   file dialogs: this script answers as the desktop file-chooser service, so the editor asks it
#     for Open Folder, Open File and Save; each request goes to the Android picker (vsc-pick-req
#     and vsc-pick-res).
# Nothing here is needed to run the editor: if a piece is missing it still starts, only without
# that bridge, and the logs below say why.
export DBUS_SESSION_BUS_ADDRESS=unix:path=/tmp/vsc-session-bus
rm -f /tmp/vsc-session-bus /tmp/.X11-unix/vsc-ime /tmp/.X11-unix/vsc-ime.tmp \
    /tmp/.X11-unix/vsc-pick-req /tmp/.X11-unix/vsc-pick-req.tmp \
    /tmp/.X11-unix/vsc-pick-res /tmp/.X11-unix/vsc-pick-res.tmp
mkdir -p /root/.cache
if command -v dbus-daemon >/dev/null 2>&1 && \
        dbus-daemon --session --fork --nopidfile --address="$DBUS_SESSION_BUS_ADDRESS" >/tmp/dbus.log 2>&1; then
    export ACCESSIBILITY_ENABLED=1 GNOME_ACCESSIBILITY=1
    cat > /root/.cache/vsc-portal.py <<\PYPORTAL
import os, sys, time, urllib.parse
try:
    import gi
    gi.require_version("Gio", "2.0")
    from gi.repository import Gio, GLib
except Exception as e:
    print("no Gio: " + str(e))
    sys.exit(0)

DIR = os.environ.get("VSC_PICK_DIR", "/tmp/.X11-unix")
REQ = os.path.join(DIR, "vsc-pick-req")
RES = os.path.join(DIR, "vsc-pick-res")
WAIT_SECONDS = 900
BASE = "/org/freedesktop/portal/desktop"

XML = """
<node>
  <interface name="org.freedesktop.portal.FileChooser">
    <method name="OpenFile">
      <arg type="s" name="parent_window" direction="in"/>
      <arg type="s" name="title" direction="in"/>
      <arg type="a{sv}" name="options" direction="in"/>
      <arg type="o" name="handle" direction="out"/>
    </method>
    <method name="SaveFile">
      <arg type="s" name="parent_window" direction="in"/>
      <arg type="s" name="title" direction="in"/>
      <arg type="a{sv}" name="options" direction="in"/>
      <arg type="o" name="handle" direction="out"/>
    </method>
    <property name="version" type="u" access="read"/>
  </interface>
</node>
"""

busy = [False]


def remove(path):
    try:
        os.remove(path)
    except OSError:
        pass


def write_request(rid, kind, name):
    tmp = REQ + ".tmp"
    with open(tmp, "w") as f:
        f.write(rid + "\n" + kind + "\n" + name.replace("\n", " ") + "\n")
    os.replace(tmp, REQ)


def read_answer(rid):
    try:
        with open(RES) as f:
            lines = f.read().split("\n")
    except OSError:
        return None
    if not lines or lines[0] != rid:
        return None
    return [l for l in lines[1:] if l]


def respond(conn, handle, paths):
    if paths:
        results = {"uris": GLib.Variant("as", ["file://" + urllib.parse.quote(p) for p in paths])}
        code = 0
    else:
        results = {}
        code = 1
    conn.emit_signal(None, handle, "org.freedesktop.portal.Request", "Response",
                     GLib.Variant("(ua{sv})", (code, results)))


def poll(conn, handle, rid, deadline):
    paths = read_answer(rid)
    if paths is None and time.time() < deadline:
        return True
    remove(REQ)
    remove(RES)
    busy[0] = False
    respond(conn, handle, paths or [])
    return False


def cancel_now(conn, handle):
    respond(conn, handle, [])
    return False


def on_call(conn, sender, path, iface, method, params, inv):
    try:
        parent, title, opts = params.unpack()
    except Exception:
        inv.return_error_literal(Gio.dbus_error_quark(), Gio.DBusError.INVALID_ARGS, "bad arguments")
        return
    token = str(opts.get("handle_token", "vsc"))
    handle = BASE + "/request/" + sender[1:].replace(".", "_") + "/" + token
    name = ""
    if method == "SaveFile":
        kind = "save"
        name = str(opts.get("current_name", ""))
    elif opts.get("directory"):
        kind = "dir"
    elif opts.get("multiple"):
        kind = "files"
    else:
        kind = "file"
    inv.return_value(GLib.Variant("(o)", (handle,)))
    if busy[0]:
        GLib.timeout_add(50, cancel_now, conn, handle)
        return
    busy[0] = True
    rid = str(int(time.time() * 1000))
    remove(RES)
    write_request(rid, kind, name)
    GLib.timeout_add(150, poll, conn, handle, rid, time.time() + WAIT_SECONDS)


def on_get(conn, sender, path, iface, prop):
    if prop == "version":
        return GLib.Variant("u", 4)
    return None


conn = Gio.bus_get_sync(Gio.BusType.SESSION, None)
info = Gio.DBusNodeInfo.new_for_xml(XML).interfaces[0]
conn.register_object(BASE, info, on_call, on_get, None)
Gio.bus_own_name_on_connection(conn, "org.freedesktop.portal.Desktop", Gio.BusNameOwnerFlags.NONE, None, None)
GLib.MainLoop().run()
PYPORTAL
    python3 /root/.cache/vsc-portal.py >/tmp/vsc-portal.log 2>&1 &
    for launcher in /usr/libexec/at-spi-bus-launcher /usr/lib/at-spi2-core/at-spi-bus-launcher; do
        if [ -x "$launcher" ]; then
            "$launcher" --launch-immediately >/tmp/atspi.log 2>&1 &
            break
        fi
    done
    for i in 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16 17 18 19 20 21 22 23 24 25; do
        dbus-send --session --print-reply --dest=org.a11y.Bus /org/a11y/bus org.a11y.Bus.GetAddress >/dev/null 2>&1 && break
        sleep 0.2
    done
    for prop in IsEnabled ScreenReaderEnabled; do
        dbus-send --session --dest=org.a11y.Bus --type=method_call /org/a11y/bus \
            org.freedesktop.DBus.Properties.Set string:org.a11y.Status string:$prop variant:boolean:true >/dev/null 2>&1
    done
    cat > /root/.cache/vsc-ime-bridge.py <<\PYBRIDGE
import os, sys, threading
try:
    import pyatspi
except Exception as e:
    print("no pyatspi: " + str(e))
    sys.exit(0)
OUT = "/tmp/.X11-unix/vsc-ime"
seq = [0]
ROLES = [getattr(pyatspi, n) for n in ("ROLE_ENTRY", "ROLE_PASSWORD_TEXT", "ROLE_TERMINAL") if hasattr(pyatspi, n)]
def emit(show):
    seq[0] += 1
    tmp = OUT + ".tmp"
    with open(tmp, "w") as f:
        f.write(("1" if show else "0") + " " + str(seq[0]))
    os.replace(tmp, OUT)
    print("keyboard " + ("show" if show else "hide"), flush=True)
def editable(acc):
    try:
        if acc.getState().contains(pyatspi.STATE_EDITABLE):
            return True
        return acc.getRole() in ROLES
    except Exception:
        return False
def on_focus(ev):
    if ev.detail1 == 1:
        emit(editable(ev.source))
def on_caret(ev):
    if editable(ev.source):
        emit(True)
pyatspi.Registry.registerEventListener(on_focus, "object:state-changed:focused")
pyatspi.Registry.registerEventListener(on_caret, "object:text-caret-moved")
print("watching focus", flush=True)
pyatspi.Registry.start()
PYBRIDGE
    python3 /root/.cache/vsc-ime-bridge.py >/tmp/vsc-ime.log 2>&1 &
else
    unset DBUS_SESSION_BUS_ADDRESS
    echo "no session bus: keyboard follow-focus and the Android picker are off" >/tmp/vsc-portal.log
fi
# The pointer is not drawn: the screen is touched directly.
if command -v unclutter >/dev/null 2>&1; then
    unclutter --timeout 1 >/tmp/unclutter.log 2>&1 &
fi
# Fallback only: if the editor does not use the Android picker, its own Linux file dialog still
# shows hidden folders (the workspace one is hidden) and has the workspace and the phone storage
# one tap away.
mkdir -p /root/.config/gtk-3.0 /root/.config/glib-2.0/settings
printf "file://%s Workspace\nfile:///storage/emulated/0 Phone storage\n" "$WORKSPACE" > /root/.config/gtk-3.0/bookmarks
printf "[org/gtk/settings/file-chooser]\nshow-hidden=true\n" > /root/.config/glib-2.0/settings/keyfile
openbox >/tmp/openbox.log 2>&1 &
# The editor (work package WP4). Plain VSCodium, started the way a PC desktop would
# start it, with the flags Electron needs inside proot and nothing else: no heap or
# memory flags (Master Blueprint section 2 is not implemented).
mkdir -p /root/.config/VSCodium/User /root/.vscode-oss/extensions
if [ ! -e /root/.config/VSCodium/User/settings.json ]; then
cat > /root/.config/VSCodium/User/settings.json <<JSON
{
  "editor.smoothScrolling": false,
  "workbench.list.smoothScrolling": false,
  "terminal.integrated.smoothScrolling": false,
  "workbench.reduceMotion": "on",
  "files.simpleDialog.enable": false,
  "window.dialogStyle": "native"
}
JSON
fi
if [ ! -e /root/.config/VSCodium/.vsc-dialog-v1 ]; then
    python3 - <<PYSET
import json, os
p = "/root/.config/VSCodium/User/settings.json"
try:
    d = json.load(open(p)) if os.path.exists(p) else {}
    d["files.simpleDialog.enable"] = False
    d["window.dialogStyle"] = "native"
    json.dump(d, open(p, "w"), indent=2)
except Exception:
    pass
PYSET
    touch /root/.config/VSCodium/.vsc-dialog-v1
fi
[ -x /usr/share/codium/codium ] || { echo "the editor is not installed" >&2; exit 13; }
sleep 1
# The editor draws at the phone density, as the device would on its own screen.
DENSITY=${DENSITY:-1.00}
export GIO_USE_VFS=local GSETTINGS_BACKEND=keyfile GTK_USE_PORTAL=0 ELECTRON_ENABLE_LOGGING=1 NO_AT_BRIDGE=0
COMMON="--no-sandbox --disable-setuid-sandbox --disable-dev-shm-usage --ozone-platform=x11 --password-store=basic --force-renderer-accessibility --force-device-scale-factor=$DENSITY"
# Electron inside proot can lose its renderer ("crashed", code 5) depending on how the graphics
# and process model are set up, which differs between devices. Three launch variants are tried
# in turn when the editor reports a crashed renderer; the one that works is remembered.
variant_flags() {
    case "$1" in
        1) echo "--disable-gpu" ;;
        2) echo "--disable-gpu --disable-gpu-compositing --in-process-gpu --disable-features=VizDisplayCompositor" ;;
        *) echo "--disable-gpu --disable-gpu-compositing --in-process-gpu --no-zygote --disable-features=VizDisplayCompositor" ;;
    esac
}
VARIANT_FILE=/root/.config/vscodroid-variant
v=1
[ -r "$VARIANT_FILE" ] && v=$(cat "$VARIANT_FILE" 2>/dev/null)
case "$v" in 1|2|3) ;; *) v=1 ;; esac
tries=0
while :; do
    rm -rf /root/.config/VSCodium/logs
    /usr/share/codium/codium $COMMON $(variant_flags "$v") \
        --user-data-dir=/root/.config/VSCodium --extensions-dir=/root/.vscode-oss/extensions \
        "$WORKSPACE" >/tmp/codium.log 2>&1 &
    cpid=$!
    echo "variant $v started" >> /tmp/codium.log
    crashed=0
    while kill -0 "$cpid" 2>/dev/null; do
        sleep 2
        if grep -rqsE "renderer process gone|render-process-gone|renderer process crashed" /root/.config/VSCodium/logs /tmp/codium.log 2>/dev/null; then
            crashed=1
            break
        fi
    done
    [ "$crashed" = 1 ] || break
    echo "variant $v crashed; trying the next" >> /tmp/codium.log.history
    pkill -f /usr/share/codium 2>/dev/null
    sleep 1
    tries=$((tries + 1))
    v=$(( v % 3 + 1 ))
    echo "$v" > "$VARIANT_FILE"
    [ "$tries" -ge 3 ] && break
done
# The person closed the editor, or it could not be kept running: the session ends here (the app
# closes the picture and goes back), instead of leaving a black desktop with nothing on it.
kill %1 2>/dev/null
exit 0
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
