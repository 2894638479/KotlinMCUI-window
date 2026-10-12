package io.github.u2894638479.kotlinmcuiwindow

import io.github.u2894638479.kotlinmcui.backend.DslRuntime
import io.github.u2894638479.kotlinmcui.logger.dslLogger
import io.github.u2894638479.kotlinmcui.test.TestColorPage
import kotlinx.coroutines.runBlocking
import java.awt.GraphicsEnvironment
import javax.swing.SwingUtilities

/**
 * Opens the window with no game behind it.
 *
 * This exists so the backend can be looked at on its own: everything under `src/main/kotlin` except
 * the three loader entry points is game-free, and this is the proof. It is also the only place that
 * calls `DslRuntime.install`, because there is no headless backend in this process to do it - inside
 * a game this file is never reached, and `install` is that backend's job there.
 *
 * `-Dkotlinmcuiwindow.capture=<file>` photographs the window into `screenshots/<file>` once the page
 * has painted, and leaves with 0; `-Dkotlinmcuiwindow.capture.delay=<ms>` waits that long first. The
 * framework's own `-Dkotlinmcui.preview=<page>` session works here too, and opens the page it names
 * in a second window next to the placeholder this always shows.
 */
fun main(args: Array<String>) {
    check(!GraphicsEnvironment.isHeadless()) {
        "kotlinmcuiwindow draws into a Swing window and cannot run headless"
    }
    val captureName = System.getProperty(CAPTURE_PROPERTY)?.takeIf { it.isNotBlank() }
    val preview = System.getProperty(PREVIEW_PROPERTY)?.takeIf { it.isNotBlank() }

    DslRuntime.install { WindowMetadata }
    DslRuntime.platformReady { WindowBackend }

    // A window always goes up, even for a preview session: the framework counts a frame only once
    // something has rendered one, and a preview waits for that count before it opens its own page.
    // For a preview the placeholder is a small window, so the page that follows is unmistakably the
    // one on top - and therefore the one a screenshot photographs.
    val placeholder = preview != null
    val holder = WindowBackend.create(
        if (placeholder) "KotlinMCUI-window (placeholder)" else "KotlinMCUI-window - TestColorPage",
        { TestColorPage() },
        if (placeholder) PLACEHOLDER_WIDTH else DEMO_WIDTH,
        if (placeholder) PLACEHOLDER_HEIGHT else DEMO_HEIGHT,
    )
    onEdtAndWait { holder.show() }
    dslLogger.info("standalone: window shown")
    onEdtAndWait {
        // The holder is what create returns; the window is what its screen put on the desktop.
        val frame = holder.screen.frame
        dslLogger.info(
            "standalone: frame bounds=${frame?.bounds} visible=${frame?.isVisible} showing=${frame?.isShowing}"
        )
    }

    // A preview session photographs and quits on its own; this path is for a plain standalone run.
    if (captureName != null && preview == null) captureAndExit(captureName)
}

private fun captureAndExit(fileName: String) {
    val delay = System.getProperty(CAPTURE_DELAY_PROPERTY)?.toLongOrNull() ?: DEFAULT_CAPTURE_DELAY_MILLIS
    // A daemon thread: the window's own event dispatch thread is what keeps the process alive, and
    // this one only has to wait for the page to have painted before photographing it.
    Thread {
        Thread.sleep(delay)
        val file = runBlocking { WindowUtils.capture(fileName) }
        if (file == null) {
            dslLogger.error("standalone: no screenshot was written for '$fileName'")
            WindowUtils.quit(1)
            return@Thread
        }
        dslLogger.info("standalone: wrote ${file.absolutePath}")
        WindowUtils.quit(0)
    }.apply {
        isDaemon = true
        name = "kotlinmcuiwindow-capture"
    }.start()
}

private fun onEdtAndWait(block: () -> Unit) {
    if (SwingUtilities.isEventDispatchThread()) block() else SwingUtilities.invokeAndWait(block)
}

private const val CAPTURE_PROPERTY = "kotlinmcuiwindow.capture"
private const val CAPTURE_DELAY_PROPERTY = "kotlinmcuiwindow.capture.delay"
private const val PREVIEW_PROPERTY = "kotlinmcui.preview"
private const val DEFAULT_CAPTURE_DELAY_MILLIS = 1_500L
private const val DEMO_WIDTH = 960
private const val DEMO_HEIGHT = 540
private const val PLACEHOLDER_WIDTH = 240
private const val PLACEHOLDER_HEIGHT = 160
