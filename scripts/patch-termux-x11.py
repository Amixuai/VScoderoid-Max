#!/usr/bin/env python3
"""Adapts the pinned Termux:X11 source to run embedded in this app.

Termux:X11 is built to be embedded (its lorie module is a library whose host app
supplies the package name), but some things assume a host with the fixed id
com.termux.x11 or an APK that keeps native libraries uncompressed. Each edit below
is exact and asserted: if upstream text changed, this fails loudly instead of
building something different. Usage: patch-termux-x11.py <termux-x11 dir>
"""
import pathlib
import sys

root = pathlib.Path(sys.argv[1])


def edit(rel, old, new):
    path = root / rel
    text = path.read_text(encoding="utf-8")
    if text.count(old) != 1:
        sys.exit(f"patch-termux-x11: expected exactly one match in {rel}: {old!r} (found {text.count(old)})")
    path.write_text(text.replace(old, new), encoding="utf-8")


entry = "lorie/src/main/java/com/termux/x11/CmdEntryPoint.java"

# 1. The X server process is started by this app, whose package name differs between
#    debug (.debug suffix) and release, so it is passed in rather than compiled in.
edit(
    entry,
    "intent.setPackage(BuildConfig.APPLICATION_ID);",
    'intent.setPackage(System.getenv("VSCODROID_X11_HOST") != null ? System.getenv("VSCODROID_X11_HOST") : BuildConfig.APPLICATION_ID);',
)

# 2. This app packages native libraries extracted (useLegacyPackaging), so the server
#    loads libXlorie.so from the app's native library directory the app names.
edit(
    entry,
    '        String path = "lib/" + (android.os.Process.is64Bit()',
    '        String nativeDir = System.getenv("VSCODROID_X11_NATIVE_DIR");\n'
    "        if (nativeDir != null) {\n"
    "            try {\n"
    '                System.load(nativeDir + "/libXlorie.so");\n'
    "                return;\n"
    "            } catch (Throwable e) {\n"
    '                Log.e("CmdEntryPoint", "Failed to dlopen from " + nativeDir, e);\n'
    '                System.err.println("Failed to load native library from " + nativeDir);\n'
    "                System.exit(134);\n"
    "            }\n"
    "        }\n"
    '        String path = "lib/" + (android.os.Process.is64Bit()',
)

# 3. The application class runs inside this app's own package, so the sharedUid package
#    context path (which would look up com.termux.x11) is never taken.
edit(
    "lorie/src/main/java/com/termux/x11/LorieApp.java",
    "if (!BuildConfig.APPLICATION_ID.equals(getPackageName())) {",
    "if (false) {",
)

# 4. The stub module (hidden-API signatures used at compile time) is included by this
#    app under a name of its own, because the shell-loader module around it is not built.
edit("lorie/build.gradle", "compileOnly project(':shell-loader:stub')", "compileOnly project(':x11-stub')")
print("patch-termux-x11: 4 edits applied")

# 4. Back button: one more choice for what Android's Back button does, "send escape", which
#    sends the X11 Escape key (closes popups and cancels dialogs). Back then never pops the
#    keyboard up. Desktop Mode selects it by default (DesktopViewer).
edit(
    "lorie/src/main/res/values/arrays.xml",
    "<item>no action</item>\n        <item>toggle soft keyboard</item>",
    "<item>no action</item>\n        <item>send escape</item>\n        <item>toggle soft keyboard</item>",
)
edit(
    "lorie/src/main/java/com/termux/x11/input/TouchInputHandler.java",
    'case "send volume up": return (key, down) -> mActivity.getLorieView().sendKeyEvent(0, KEYCODE_VOLUME_UP, down);',
    'case "send escape": return (key, down) -> mActivity.getLorieView().sendKeyEvent(0, android.view.KeyEvent.KEYCODE_ESCAPE, down);\n'
    '            case "send volume up": return (key, down) -> mActivity.getLorieView().sendKeyEvent(0, KEYCODE_VOLUME_UP, down);',
)

# 5. Two bridges to the Debian side, both plain files in the guest's /tmp/.X11-unix (the X server's
#    own folder, which the app can read as <files>/debian_rootfs/tmp/.X11-unix; the name of
#    that folder is DesktopRuntime.ROOTFS_NAME, pinned by a unit test):
#    - vsc-ime: "1 <n>" when an editable field takes focus (or is tapped again) and "0 <n>" when
#      anything else does; the keyboard is shown or hidden to match.
#    - vsc-pick-req: a request from the editor's Open Folder / Open File / Save dialog; the
#      Android system picker is opened for it (DesktopPickerActivity), and its answer goes back
#      through vsc-pick-res.
#    Both are read by polling: the files are tiny and the folder may not exist yet. What is
#    already in a file when this screen starts is old news and is not acted on.
main = "lorie/src/main/java/com/termux/x11/MainActivity.java"
edit(
    main,
    "    @Override\n    public void onResume() {\n        super.onResume();\n        if (redirected)\n            return;\n",
    """    // VSCodroid: keyboard follows focus and file dialogs use the Android picker (see wrapper.sh).
    private volatile boolean vscImeRun;
    private Thread vscImeThread;

    private static String vscRead(java.io.File file) {
        try {
            long size = file.length();
            if (!file.isFile() || size <= 0 || size > 2048)
                return "";
            byte[] buf = new byte[(int) size];
            try (java.io.FileInputStream in = new java.io.FileInputStream(file)) {
                int n = in.read(buf);
                return n > 0 ? new String(buf, 0, n, java.nio.charset.StandardCharsets.UTF_8).trim() : "";
            }
        } catch (Throwable e) {
            return "";
        }
    }

    private void vscStartPicker(String request) {
        try {
            startActivity(new android.content.Intent()
                    .setClassName(getPackageName(), "com.vscodroid.desktop.DesktopPickerActivity")
                    .putExtra("request", request));
        } catch (Throwable ignored) {
        }
    }

    private void vscStartImeBridge() {
        if (vscImeThread != null)
            return;
        vscImeRun = true;
        final java.io.File dir = new java.io.File(getFilesDir(), "debian_rootfs/tmp/.X11-unix");
        final java.io.File imeFile = new java.io.File(dir, "vsc-ime");
        final java.io.File pickFile = new java.io.File(dir, "vsc-pick-req");
        vscImeThread = new Thread(() -> {
            String lastIme = vscRead(imeFile);
            String lastPick = vscRead(pickFile);
            while (vscImeRun) {
                try {
                    String ime = vscRead(imeFile);
                    if (!ime.isEmpty() && !ime.equals(lastIme)) {
                        lastIme = ime;
                        final boolean show = ime.startsWith("1");
                        runOnUiThread(() -> getLorieView().setKeyboardVisible(show));
                    }
                    String pick = vscRead(pickFile);
                    if (!pick.isEmpty() && !pick.equals(lastPick)) {
                        lastPick = pick;
                        final String request = pick;
                        runOnUiThread(() -> vscStartPicker(request));
                    }
                    Thread.sleep(120);
                } catch (InterruptedException e) {
                    return;
                } catch (Throwable ignored) {
                    try { Thread.sleep(500); } catch (InterruptedException e) { return; }
                }
            }
        }, "vsc-ime-bridge");
        vscImeThread.setDaemon(true);
        vscImeThread.start();
    }

    private void vscStopImeBridge() {
        vscImeRun = false;
        if (vscImeThread != null)
            vscImeThread.interrupt();
        vscImeThread = null;
    }

    @Override
    public void onResume() {
        super.onResume();
        if (redirected)
            return;
        vscStartImeBridge();
""",
)
edit(
    main,
    "        getLorieView().setKeyboardVisible(false);\n\n        handler.removeCallbacks(serverWatchdog);",
    "        vscStopImeBridge();\n        getLorieView().setKeyboardVisible(false);\n\n        handler.removeCallbacks(serverWatchdog);",
)

# 6. The screen resizes for the keyboard instead of being covered by it.
manifest = root / "lorie/src/main/AndroidManifest.xml"
text = manifest.read_text(encoding="utf-8")
if text.count("stateHidden|adjustNothing") != 2:
    sys.exit("patch-termux-x11: expected two windowSoftInputMode entries in the lorie manifest")
manifest.write_text(text.replace("stateHidden|adjustNothing", "stateHidden|adjustResize"), encoding="utf-8")
