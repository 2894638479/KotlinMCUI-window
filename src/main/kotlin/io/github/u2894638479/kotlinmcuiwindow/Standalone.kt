package io.github.u2894638479.kotlinmcuiwindow

import io.github.u2894638479.kotlinmcui.backend.DslRuntime
import io.github.u2894638479.kotlinmcui.container.ViewerLifetime
import io.github.u2894638479.kotlinmcui.logger.dslLogger
import kotlinx.coroutines.runBlocking
import java.awt.GraphicsEnvironment

/**
 * Opens the window with no game behind it.
 *
 * This exists so the backend can be looked at on its own: everything under `src/main/kotlin` except
 * the three loader entry points is game-free, and this is the proof. It is also the only place that
 * calls `DslRuntime.install`, because there is no headless backend in this process to do it - inside
 * a game this file is never reached, and `install` is that backend's job there.
 *
 * The window it opens is the same one the in-game entries open, with the framework's entry list at
 * the bottom of its stack: that page is the framework's own, and it is what a person expects to see
 * when this backend is started on its own.
 *
 * `-Dkotlinmcuiwindow.capture=<file>` photographs the window into `screenshots/<file>` once the page
 * has painted, and leaves with 0; `-Dkotlinmcuiwindow.capture.delay=<ms>` waits that long first. The
 * framework's own `-Dkotlinmcui.preview=<page>` session works here too, and pushes the page it names
 * on top of the entry list in the same window.
 */
fun main(args: Array<String>) {
    check(!GraphicsEnvironment.isHeadless()) {
        "kotlinmcuiwindow draws into a Swing window and cannot run headless"
    }
    val captureName = System.getProperty(CAPTURE_PROPERTY)?.takeIf { it.isNotBlank() }
    val preview = System.getProperty(PREVIEW_PROPERTY)?.takeIf { it.isNotBlank() }

    DslRuntime.install { WindowMetadata }
    val utils = WindowUtils()
    val platform = WindowPlatform(utils)
    val provider = WindowRootProvider(platform, utils)
    // The result is deliberately not checked. This process has exactly one platform, and the `install`
    // above would have thrown had another one installed the framework first, so this call cannot be
    // refused. Testing it here would read as if a game's own client platform could take the window
    // away from the standalone run, which cannot happen.
    DslRuntime.platformReady { platform }
    DslRuntime.registerProvider(provider)
    provider.open(DslRuntime.rootFor(provider, ViewerLifetime.PROCESS))
    dslLogger.info("standalone: window shown with the entry list at the bottom of its stack")

    // A preview session photographs and quits on its own; this path is for a plain standalone run.
    if (captureName != null && preview == null) captureAndExit(platform, provider, captureName)
}

private fun captureAndExit(platform: WindowPlatform, provider: WindowRootProvider, fileName: String) {
    val delay = System.getProperty(CAPTURE_DELAY_PROPERTY)?.toLongOrNull() ?: DEFAULT_CAPTURE_DELAY_MILLIS
    // A daemon thread: the window's own event dispatch thread is what keeps the process alive, and
    // this one only has to wait for the page to have painted before photographing it.
    Thread {
        Thread.sleep(delay)
        val file = runBlocking { provider.rootUtils.capture(fileName) }
        if (file == null) {
            dslLogger.error("standalone: no screenshot was written for '$fileName'")
            platform.utils.quit(1)
            return@Thread
        }
        dslLogger.info("standalone: wrote ${file.absolutePath}")
        platform.utils.quit(0)
    }.apply {
        isDaemon = true
        name = "kotlinmcuiwindow-capture"
    }.start()
}

private const val CAPTURE_PROPERTY = "kotlinmcuiwindow.capture"
private const val CAPTURE_DELAY_PROPERTY = "kotlinmcuiwindow.capture.delay"
private const val PREVIEW_PROPERTY = "kotlinmcui.preview"
private const val DEFAULT_CAPTURE_DELAY_MILLIS = 1_500L
