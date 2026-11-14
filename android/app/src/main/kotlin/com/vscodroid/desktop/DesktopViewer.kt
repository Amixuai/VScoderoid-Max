package com.vscodroid.desktop

/**
 * What the Desktop Mode screen asks for, as pure functions so they can be tested.
 *
 * STATUS: unit-tested on a JVM only; never run against a device.
 */
object DesktopViewer {
    private const val WIDTH = 1280
    private const val MIN_HEIGHT = 600
    private const val MAX_HEIGHT = 820

    /**
     * The virtual screen size for a phone of [widthPx] by [heightPx]. The desktop is
     * always landscape (the screen is locked to it), 1280 wide, with the height that
     * keeps the phone's own proportions, so the picture fills the display without
     * distortion once noVNC scales it. Both numbers are even.
     */
    fun geometry(widthPx: Int, heightPx: Int): String {
        val long = maxOf(widthPx, heightPx).coerceAtLeast(1)
        val short = minOf(widthPx, heightPx).coerceAtLeast(1)
        val height = (WIDTH.toLong() * short / long).toInt().coerceIn(MIN_HEIGHT, MAX_HEIGHT)
        return "${WIDTH}x${height and 1.inv()}"
    }

    /**
     * The noVNC address for this session. `resize=scale` keeps one fixed screen size
     * and scales the picture to the window, `reconnect` brings it back after a brief
     * drop, and `quality` and `compression` pick tight-encoding settings that suit a
     * phone's CPU and a local link (brief, WP7). The password is the per-session
     * secret and stays on loopback.
     */
    fun url(password: String): String =
        "http://127.0.0.1:${DesktopLauncher.WEB_PORT}/vnc.html" +
            "?autoconnect=1&resize=scale&reconnect=1&reconnect_delay=1000" +
            "&quality=5&compression=4&password=$password"

    /** True only for the one origin the screen may show. */
    fun isViewerOrigin(scheme: String?, host: String?, port: Int): Boolean =
        scheme == "http" && host == "127.0.0.1" && port == DesktopLauncher.WEB_PORT
}
