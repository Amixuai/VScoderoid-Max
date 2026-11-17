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
        "showAdditionalKbd" to false,
        "displayResolutionMode" to "native",
    )
}
