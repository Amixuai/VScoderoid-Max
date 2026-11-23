package com.vscodroid.desktop

/**
 * What the Desktop Mode screen asks of Termux:X11, as plain values so they can be tested.
 *
 * STATUS: unit-tested on a JVM only; never run against a device.
 */
object DesktopViewer {
    /** The Termux:X11 screen (library module), started once the X server is up. */
    const val X11_ACTIVITY = "com.termux.x11.MainActivity"

    /**
     * Termux:X11's own preferences, set once before its screen first opens. The desktop
     * is the whole screen with nothing of Android's added on top (product rule: PC
     * parity): fullscreen, no extra-keys bar, and the picture at the phone's own
     * resolution. Everything else stays at Termux:X11's defaults, and the person can
     * still change any of it in its settings, which are kept.
     */
    val SCREEN_PREFERENCES: Map<String, Any> = linkedMapOf(
        "fullscreen" to true,
        "hideCutout" to true,
        // The extra-keys bar (several pages: Tab, Esc, Ctrl, Alt, Shift, arrows; brackets; symbols;
        // F1 to F12 and editing keys). Termux:X11 shows it only while the soft keyboard is up.
        "showAdditionalKbd" to true,
        "additionalKbdVisible" to true,
        "displayResolutionMode" to "native",
        // Fill the whole screen: with the display at the phone's own size there is nothing to
        // stretch, and any left-over band is filled instead of staying black.
        "displayStretch" to true,
        // A finger touches what it touches (Termux:X11's value 3 is "Direct touch"; 1 is the
        // trackpad mode, which moves a pointer instead).
        "touchMode" to "3",
        // Android's Back sends the X11 Escape key (closes popups, cancels dialogs) and closes a
        // second window opened on top of the editor (DevTools, a live preview); it never opens
        // the keyboard. The keyboard follows focus instead (see wrapper.sh).
        "backButtonAction" to "close top window",
        // A three-finger swipe down would hide the bar for good.
        "swipeDownAction" to "no action",
        // A way to type that does not depend on the automatic keyboard: the volume-down key
        // opens and closes it (no button on the screen). Volume up still sets the volume.
        "volumeDownAction" to "toggle soft keyboard",
    )
}
