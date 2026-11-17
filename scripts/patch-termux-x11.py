#!/usr/bin/env python3
"""Adapts the pinned Termux:X11 source to run embedded in this app.

Termux:X11 is built to be embedded (its lorie module is a library whose host app
supplies the package name), but three things assume a host with the fixed id
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
