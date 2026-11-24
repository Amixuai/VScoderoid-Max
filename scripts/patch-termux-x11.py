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
#      Android system picker is opened from this screen for it, and its answer goes back
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

    private static final int VSC_PICK = 7311;
    private String vscPickId = "";
    private String vscPickKind = "";

    // The editor's Open Folder / Open File / Save dialogs: Android's own picker is opened from
    // this screen, so the person comes back to this screen. Mirrored by PickerProtocol (tested).
    private void vscStartPicker(String request) {
        String[] lines = request.split("\\n", -1);
        vscPickId = lines[0].trim();
        vscPickKind = lines.length > 1 ? lines[1].trim() : "";
        String name = lines.length > 2 ? lines[2].trim() : "";
        if (vscPickId.isEmpty())
            return;
        try {
            android.content.Intent intent;
            if (vscPickKind.equals("dir")) {
                intent = new android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT_TREE);
            } else if (vscPickKind.equals("file") || vscPickKind.equals("files")) {
                intent = new android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT)
                        .addCategory(android.content.Intent.CATEGORY_OPENABLE).setType("*/*")
                        .putExtra(android.content.Intent.EXTRA_ALLOW_MULTIPLE, vscPickKind.equals("files"));
            } else if (vscPickKind.equals("save")) {
                intent = new android.content.Intent(android.content.Intent.ACTION_CREATE_DOCUMENT)
                        .addCategory(android.content.Intent.CATEGORY_OPENABLE).setType("application/octet-stream")
                        .putExtra(android.content.Intent.EXTRA_TITLE, name.isEmpty() ? "untitled" : name);
            } else {
                vscAnswerPick(new java.util.ArrayList<String>());
                return;
            }
            intent.putExtra(android.provider.DocumentsContract.EXTRA_INITIAL_URI,
                    android.provider.DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:.VScoderoid-Pro"));
            startActivityForResult(intent, VSC_PICK);
        } catch (Throwable e) {
            vscAnswerPick(new java.util.ArrayList<String>());
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, android.content.Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != VSC_PICK)
            return;
        java.util.ArrayList<String> paths = new java.util.ArrayList<>();
        if (resultCode == RESULT_OK && data != null) {
            java.util.ArrayList<android.net.Uri> uris = new java.util.ArrayList<>();
            if (data.getClipData() != null)
                for (int i = 0; i < data.getClipData().getItemCount(); i++)
                    uris.add(data.getClipData().getItemAt(i).getUri());
            if (data.getData() != null && !uris.contains(data.getData()))
                uris.add(data.getData());
            boolean folder = vscPickKind.equals("dir");
            for (android.net.Uri uri : uris) {
                try {
                    String docId = folder ? android.provider.DocumentsContract.getTreeDocumentId(uri)
                            : android.provider.DocumentsContract.getDocumentId(uri);
                    String path = vscPathFor(uri.getAuthority(), docId);
                    if (path == null)
                        continue;
                    java.io.File f = new java.io.File(path);
                    if (folder ? f.isDirectory() : f.exists())
                        paths.add(path);
                } catch (Throwable ignored) {
                }
            }
        }
        vscAnswerPick(paths);
    }

    private static String vscPathFor(String authority, String docId) {
        if ("com.android.externalstorage.documents".equals(authority)) {
            int colon = docId.indexOf(':');
            if (colon <= 0)
                return null;
            String volume = docId.substring(0, colon);
            String rel = docId.substring(colon + 1);
            for (String part : rel.split("/"))
                if (part.equals(".."))
                    return null;
            String base = volume.equals("primary") ? "/storage/emulated/0"
                    : volume.equals("home") ? "/storage/emulated/0/Documents" : "/storage/" + volume;
            return rel.isEmpty() ? base : base + "/" + rel;
        }
        if ("com.android.providers.downloads.documents".equals(authority) && docId.startsWith("raw:/")) {
            String path = docId.substring(4);
            for (String part : path.split("/"))
                if (part.equals(".."))
                    return null;
            return path;
        }
        return null;
    }

    private void vscAnswerPick(java.util.List<String> paths) {
        try {
            java.io.File dir = new java.io.File(getFilesDir(), "debian_rootfs/tmp/.X11-unix");
            StringBuilder text = new StringBuilder(vscPickId).append('\\n');
            for (String path : paths)
                text.append(path).append('\\n');
            java.io.File tmp = new java.io.File(dir, "vsc-pick-res.tmp");
            try (java.io.FileOutputStream out = new java.io.FileOutputStream(tmp)) {
                out.write(text.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            tmp.renameTo(new java.io.File(dir, "vsc-pick-res"));
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

# 10. Keyboard bar: taller, as comfortable to hit as the other editor's. The picture is covered by the
#     editor's dark colour for a moment while the keyboard changes the size of the screen, so the
#     white the editor paints while it redraws is never seen.
edit(main, "layoutParams.height = Math.round(37.5f * getResources().getDisplayMetrics().density *",
     "layoutParams.height = Math.round(72f * getResources().getDisplayMetrics().density *")
edit("lorie/src/main/res/layout/main_activity.xml", 'android:layout_height="37.5dp"', 'android:layout_height="72dp"')
edit(main, "        imeHeight = height;\n        setTerminalToolbarView();",
     "        imeHeight = height;\n        vscCoverBriefly();\n        setTerminalToolbarView();")
edit(
    main,
    "    /** Tells the Debian side that Back was pressed",
    """    private android.view.View vscCover;
    private int vscSyncSerial = 0;
    private final Runnable vscUncover = () -> {
        final android.view.View c = vscCover;
        if (c != null && c.getParent() instanceof android.view.ViewGroup)
            c.animate().alpha(0f).setDuration(150).withEndAction(() -> {
                if (c.getParent() instanceof android.view.ViewGroup)
                    ((android.view.ViewGroup) c.getParent()).removeView(c);
                c.setAlpha(1f);
            }).start();
    };
    // Asks the X server to answer once it has applied the new size; the answer means the picture
    // underneath is the new one.
    private final Runnable vscAskSync = () -> {
        try {
            final LorieView v = getLorieView();
            if (!v.connected())
                return;
            final int serial = ++vscSyncSerial;
            v.setSyncListener(reply -> {
                if (reply == serial)
                    runOnUiThread(this::vscSettled);
            });
            v.sendSync(serial);
        } catch (Throwable ignored) {
        }
    };

    private void vscSettled() {
        handler.removeCallbacks(vscUncover);
        android.view.Choreographer.getInstance().postFrameCallback(t1 ->
                android.view.Choreographer.getInstance().postFrameCallback(t2 ->
                        handler.postDelayed(vscUncover, 40)));
    }

    /** The editor's dark colour over the picture until the new size has been drawn (see patch 10). */
    private void vscCoverBriefly() {
        try {
            if (vscCover == null) {
                vscCover = new android.view.View(this);
                vscCover.setBackgroundColor(0xFF1E1E1E);
            }
            vscCover.animate().cancel();
            vscCover.setAlpha(1f);
            if (vscCover.getParent() == null)
                frm.addView(vscCover, frm.indexOfChild(getLorieView()) + 1,
                        new android.widget.FrameLayout.LayoutParams(-1, -1));
            getTerminalToolbarViewPager().bringToFront();
            handler.removeCallbacks(vscUncover);
            handler.removeCallbacks(vscAskSync);
            handler.postDelayed(vscAskSync, 80);
            handler.postDelayed(vscUncover, 900); // never kept longer than this, whatever the server says
        } catch (Throwable ignored) {
        }
    }

    /** Tells the Debian side that Back was pressed""",
)
# 11. One notification, not two: the separate "Termux:X11" one is not shown (the desktop has its own,
#     with its own Stop). And the raw "Not connected" screen is never shown: a loading screen sits on
#     top until the Debian side says the editor window is up (vsc-ready, written by vsc-back.py).
edit(
    "lorie/src/main/java/com/termux/x11/LorieApp.java",
    "    public void onActivityResumed(MainActivity activity) {\n        notificationManager.notify(NOTIFICATION_ID, buildNotification(activity));",
    "    public void onActivityResumed(MainActivity activity) {\n        notificationManager.cancel(NOTIFICATION_ID);",
)
edit(
    main,
    "        frm = findViewById(R.id.frame);\n",
    "        frm = findViewById(R.id.frame);\n        vscShowLoading();\n",
)
edit(
    main,
    "    /** Tells the Debian side that Back was pressed",
    """    private android.view.View vscLoading;
    private final Runnable vscHideLoading = () -> {
        final android.view.View c = vscLoading;
        vscLoading = null;
        if (c != null && c.getParent() instanceof android.view.ViewGroup)
            c.animate().alpha(0f).setDuration(200).withEndAction(() -> {
                if (c.getParent() instanceof android.view.ViewGroup)
                    ((android.view.ViewGroup) c.getParent()).removeView(c);
            }).start();
    };

    /** The "Starting the desktop..." screen, kept until the editor window is up or two minutes pass. */
    private void vscShowLoading() {
        try {
            java.io.File ready = new java.io.File(getFilesDir(), "debian_rootfs/tmp/.X11-unix/vsc-ready");
            if (ready.exists())
                return;
            android.widget.LinearLayout box = new android.widget.LinearLayout(this);
            box.setOrientation(android.widget.LinearLayout.VERTICAL);
            box.setGravity(android.view.Gravity.CENTER);
            box.setBackgroundColor(0xFF1E1E1E);
            box.setClickable(true);
            box.addView(new android.widget.ProgressBar(this));
            android.widget.TextView text = new android.widget.TextView(this);
            int id = getResources().getIdentifier("desktop_starting", "string", getPackageName());
            text.setText(id != 0 ? getString(id) : "");
            text.setTextColor(0xFFCCCCCC);
            text.setTextSize(16);
            text.setPadding(0, (int) (24 * getResources().getDisplayMetrics().density), 0, 0);
            box.addView(text);
            frm.addView(box, new android.widget.FrameLayout.LayoutParams(-1, -1));
            vscLoading = box;
            handler.postDelayed(vscHideLoading, 120000);
        } catch (Throwable ignored) {
        }
    }

    /** Tells the Debian side that Back was pressed""",
)
edit(
    main,
    '        final java.io.File pickFile = new java.io.File(dir, "vsc-pick-req");\n',
    '        final java.io.File pickFile = new java.io.File(dir, "vsc-pick-req");\n        final java.io.File readyFile = new java.io.File(dir, "vsc-ready");\n',
)
edit(
    main,
    "                    String pick = vscRead(pickFile);\n",
    "                    if (vscLoading != null && readyFile.exists())\n                        runOnUiThread(vscHideLoading);\n                    String pick = vscRead(pickFile);\n",
)

print("patch-termux-x11: all edits applied")
