package com.vscodroid.desktop

import android.content.Context
import org.json.JSONObject

/**
 * What Desktop Mode downloads, pinned in `assets/desktop/pins.json`.
 *
 * One small file rather than constants in code, so that moving to a newer Debian
 * image is a data change that `scripts/check-desktop-pins.py` can check in CI.
 *
 * [pinned] is false while the URL or digest is missing. That is a legitimate
 * state for a development build and the provisioner refuses to download in it;
 * a release build is stopped earlier, by `check-desktop-pins.py --require-pinned`.
 *
 * Every value that ends up inside a file written into the guest (the suite, the
 * resolver addresses, the mirrors) is validated here, because a newline in any
 * of them would let a data file write extra lines into `resolv.conf` or the apt
 * sources.
 *
 * STATUS: UNTESTED. Written without an Android SDK; not compiled.
 */
data class DesktopPins(
    val suite: String,
    val rootfsUrl: String,
    val rootfsSha256: String,
    val rootfsBytes: Long,
    val rootfsStripComponents: Int,
    val unpackedBytesEstimate: Long,
    val dns: List<String>,
    val mirror: String,
    val securityMirror: String,
) {
    val pinned: Boolean
        get() = rootfsUrl.startsWith("https://") && SHA256.matches(rootfsSha256) && rootfsBytes > 0

    fun withRootfs(url: String, sha256: String, bytes: Long): DesktopPins =
        copy(rootfsUrl = url, rootfsSha256 = sha256.lowercase(), rootfsBytes = bytes)

    companion object {
        const val ASSET_PATH = "desktop/pins.json"
        private val SHA256 = Regex("^[0-9a-f]{64}$")
        private val SUITE = Regex("^[a-z][a-z0-9-]{1,31}$")
        private val ADDRESS = Regex("^[0-9a-fA-F:.]{2,45}$")
        private val MIRROR = Regex("^https?://[A-Za-z0-9.-]+(:[0-9]+)?(/[A-Za-z0-9._~/-]*)?$")

        fun load(context: Context): DesktopPins =
            parse(context.assets.open(ASSET_PATH).bufferedReader().use { it.readText() })

        fun parse(text: String): DesktopPins {
            val root = JSONObject(text)
            require(root.getInt("schema") == 1) { "unsupported pins schema" }
            val rootfs = root.getJSONObject("rootfs")
            val dnsArray = root.getJSONArray("dns")
            val dns = (0 until dnsArray.length()).map { dnsArray.getString(it) }
            val pins = DesktopPins(
                suite = root.getString("suite"),
                rootfsUrl = rootfs.getString("url"),
                rootfsSha256 = rootfs.getString("sha256"),
                rootfsBytes = rootfs.getLong("bytes"),
                rootfsStripComponents = rootfs.optInt("strip_components", 0),
                unpackedBytesEstimate = rootfs.getLong("unpacked_bytes_estimate"),
                dns = dns,
                mirror = root.getString("mirror"),
                securityMirror = root.getString("security_mirror"),
            )
            require(SUITE.matches(pins.suite)) { "suite is not a plain name" }
            require(pins.dns.isNotEmpty() && pins.dns.all { ADDRESS.matches(it) }) { "dns holds something that is not an address" }
            require(MIRROR.matches(pins.mirror) && MIRROR.matches(pins.securityMirror)) { "a mirror is not a plain URL" }
            require(pins.rootfsStripComponents in 0..2) { "strip_components out of range" }
            require(pins.rootfsBytes >= 0 && pins.unpackedBytesEstimate > 0) { "sizes must not be negative" }
            return pins
        }
    }
}
