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

    // The pointer is drawn only while a mouse is in use: a mouse event shows it, a finger or pen hides it.
    @Override
    public boolean dispatchTouchEvent(android.view.MotionEvent ev) {
        vscPointerFor(ev);
        return super.dispatchTouchEvent(ev);
    }

    @Override
    public boolean dispatchGenericMotionEvent(android.view.MotionEvent ev) {
        vscPointerFor(ev);
        return super.dispatchGenericMotionEvent(ev);
    }

    private void vscPointerFor(android.view.MotionEvent ev) {
        try {
            int tool = ev.getToolType(0);
            if (tool == android.view.MotionEvent.TOOL_TYPE_MOUSE)
                getLorieView().vscShowPointer(true);
            else if (tool == android.view.MotionEvent.TOOL_TYPE_FINGER || tool == android.view.MotionEvent.TOOL_TYPE_STYLUS)
                getLorieView().vscShowPointer(false);
        } catch (Throwable ignored) {
        }
    }

    /** Tells the Debian side that Back was pressed (it closes a window opened on top of the editor). */
    public void vscBack() {
        try {
            java.io.File dir = new java.io.File(getFilesDir(), "debian_rootfs/tmp/.X11-unix");
            java.io.File tmp = new java.io.File(dir, "vsc-back.tmp");
            try (java.io.FileOutputStream out = new java.io.FileOutputStream(tmp)) {
                out.write(Long.toString(System.nanoTime()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            tmp.renameTo(new java.io.File(dir, "vsc-back"));
        } catch (Throwable ignored) {
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

# 7. Back: "close top window" sends Escape (closes menus and dialogs inside the editor) and tells
#    the Debian side, which closes the focused window when it is a second one opened on top of
#    the editor (DevTools, a live preview). The editor window itself is never closed by Back.
edit(
    "lorie/src/main/res/values/arrays.xml",
    "<item>send escape</item>",
    "<item>send escape</item>\n        <item>close top window</item>",
)
edit(
    "lorie/src/main/java/com/termux/x11/input/TouchInputHandler.java",
    'case "send escape": return (key, down) -> mActivity.getLorieView().sendKeyEvent(0, android.view.KeyEvent.KEYCODE_ESCAPE, down);',
    'case "send escape": return (key, down) -> mActivity.getLorieView().sendKeyEvent(0, android.view.KeyEvent.KEYCODE_ESCAPE, down);\n'
    '            case "close top window": return (key, down) -> {\n'
    '                mActivity.getLorieView().sendKeyEvent(0, android.view.KeyEvent.KEYCODE_ESCAPE, down);\n'
    '                if (down) mActivity.vscBack();\n'
    '            };',
)

# 8. A swipeable extra-keys bar of its own: five pages of one row each (navigation and modifiers,
#    brackets, symbols, F1 to F10, and the rest of the function and editing keys), then
#    Termux:X11's text-entry page. It shows only while the soft keyboard is up, directly above it.
ek = "lorie/src/main/java/com/termux/x11/utils/TermuxX11ExtraKeys.java"
edit(ek, "    private ExtraKeysInfo mExtraKeysInfo;", "    private ExtraKeysInfo mExtraKeysInfo;\n    /** When set, this bar shows this layout and not the one in the preferences. */\n    public String vscConfig;")
edit(ek, "String extrakeys = mActivity.prefs.extra_keys_config.get();", "String extrakeys = vscConfig != null ? vscConfig : mActivity.prefs.extra_keys_config.get();")
pager = "lorie/src/main/java/com/termux/x11/utils/X11ToolbarViewPager.java"
edit(
    pager,
    "        final MainActivity mActivity;\n",
    "        final MainActivity mActivity;\n"
    "        static final String[] VSC_PAGES = {\n"
    "                \"[['ESC','TAB','CTRL','ALT','SHIFT','LEFT','DOWN','UP','RIGHT']]\",\n"
    "                \"[['{','}','(',')','[',']','<','>',';',':']]\",\n"
    "                \"[['/','|','~','`','-','_','=','+','*','&']]\",\n"
    "                \"[['F1','F2','F3','F4','F5','F6','F7','F8','F9','F10']]\",\n"
    "                \"[['F11','F12','HOME','END','PGUP','PGDN','DEL','INS','ENTER']]\",\n"
    "        };\n",
)
edit(pager, "            return 2;", "            return VSC_PAGES.length + 1;")
edit(
    pager,
    "            if (position == 0) {\n                layout = inflater.inflate(R.layout.lorie_view_terminal_toolbar_extra_keys, collection, false);",
    "            if (position < VSC_PAGES.length) {\n                layout = inflater.inflate(R.layout.lorie_view_terminal_toolbar_extra_keys, collection, false);",
)
edit(
    pager,
    "                mActivity.mExtraKeys = new TermuxX11ExtraKeys(mEventListener, mActivity, extraKeysView);",
    "                TermuxX11ExtraKeys keys = new TermuxX11ExtraKeys(mEventListener, mActivity, extraKeysView);\n"
    "                keys.vscConfig = VSC_PAGES[position];\n"
    "                if (position == 0)\n"
    "                    mActivity.mExtraKeys = keys;",
)
edit(
    pager,
    "            if (position == 0) {\n                act.getLorieView().requestFocus();",
    "            if (position < PageAdapter.VSC_PAGES.length) {\n                act.getLorieView().requestFocus();",
)
edit(
    main,
    "boolean showNow = !isInPictureInPictureMode && getLorieView().connected() && prefs.showAdditionalKbd.get() && prefs.additionalKbdVisible.get();",
    "boolean showNow = !isInPictureInPictureMode && getLorieView().connected() && prefs.showAdditionalKbd.get() && prefs.additionalKbdVisible.get() && imeHeight > 0;",
)
edit(main, "        imeHeight = height;\n        setTerminalToolbarViewLayout();", "        imeHeight = height;\n        setTerminalToolbarView();")
edit(
    main,
    "(extraKeysInfo == null ? 0 : extraKeysInfo.getMatrix().length)",
    "(extraKeysInfo == null ? 1 : extraKeysInfo.getMatrix().length)",
)

# 9. No white flash and no pointer flash when the screen comes back: the picture area and the
#    "not connected" cover are the editor's own dark colour (the window behind them is set by
#    the app's theme for this screen), and the pointer layer is shown only while a mouse is in
#    use (a finger hides it again).
layout = "lorie/src/main/res/layout/main_activity.xml"
dark = "#FF1E1E1E"
edit(layout, '    android:layout_height="match_parent"\n    android:background="@android:color/black">\n\n    <FrameLayout\n        android:id="@+id/frame"',
     f'    android:layout_height="match_parent"\n    android:background="{dark}">\n\n    <FrameLayout\n        android:id="@+id/frame"')
edit(layout, '        android:paddingBottom="0dp"\n        android:background="@android:color/black">',
     f'        android:paddingBottom="0dp"\n        android:background="{dark}">')
edit(layout, '            android:layout_height="match_parent"\n            android:background="@android:color/black"\n            android:gravity="center_horizontal|center_vertical"',
     f'            android:layout_height="match_parent"\n            android:background="{dark}"\n            android:gravity="center_horizontal|center_vertical"')
edit(
    "lorie/src/main/java/com/termux/x11/LorieView.java",
    "        addView(cursorView, new LayoutParams(32, 32));",
    "        addView(cursorView, new LayoutParams(32, 32));\n        cursorView.setVisibility(android.view.View.INVISIBLE);",
)
edit(
    "lorie/src/main/java/com/termux/x11/LorieView.java",
    "    @FastNative private native boolean sendKeyEvent(long ptr, int scanCode, int keyCode, boolean keyDown);",
    "    private boolean vscPointerShown;\n\n"
    "    /** The pointer layer is shown only while a mouse is in use (see MainActivity.vscPointerFor). */\n"
    "    public void vscShowPointer(boolean show) {\n"
    "        if (show == vscPointerShown)\n"
    "            return;\n"
    "        vscPointerShown = show;\n"
    "        cursorView.setVisibility(show ? android.view.View.VISIBLE : android.view.View.INVISIBLE);\n"
    "    }\n\n"
    "    @FastNative private native boolean sendKeyEvent(long ptr, int scanCode, int keyCode, boolean keyDown);",
)
print("patch-termux-x11: all edits applied")
