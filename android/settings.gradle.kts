pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

@Suppress("UnstableApiUsage")
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "VSCodroid"
include(":app")

// On-demand toolchain asset packs (Play Asset Delivery)
include(":toolchain_ruby")
include(":toolchain_java")
include(":toolchain_cpp")

// Desktop Mode display: Termux:X11 (GPL-3.0), built into the app from a pinned commit.
// The source is fetched into android/third_party on the first build (untracked), by
// scripts/fetch-termux-x11.sh, so no workflow or developer step is needed.
run {
    val x11 = file("third_party/termux-x11")
    if (!file("third_party/termux-x11/.vscodroid-fetched").exists()) {
        val fetch = ProcessBuilder("bash", file("../scripts/fetch-termux-x11.sh").absolutePath)
            .redirectErrorStream(true)
            .start()
        fetch.inputStream.bufferedReader().forEachLine { println("termux-x11: $it") }
        check(fetch.waitFor() == 0) { "scripts/fetch-termux-x11.sh failed, see the termux-x11 lines above" }
    }
    include(":lorie")
    project(":lorie").projectDir = File(x11, "lorie")
    include(":x11-stub")
    project(":x11-stub").projectDir = File(x11, "shell-loader/stub")
}
