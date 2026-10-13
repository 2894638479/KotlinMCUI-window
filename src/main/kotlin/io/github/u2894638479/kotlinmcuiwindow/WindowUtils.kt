package io.github.u2894638479.kotlinmcuiwindow

import io.github.u2894638479.kotlinmcui.backend.DslBackendUtils
import io.github.u2894638479.kotlinmcui.image.ImageHolder
import io.github.u2894638479.kotlinmcui.logger.dslLogger
import io.github.u2894638479.kotlinmcui.math.px
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.awt.Desktop
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.image.BufferedImage
import java.io.File
import java.net.URI
import javax.imageio.ImageIO
import javax.swing.SwingUtilities
import kotlin.coroutines.CoroutineContext
import kotlin.system.exitProcess

/**
 * Everything the framework asks of a platform that is not drawing.
 *
 * The one that differs from a game backend is [quit]: it ends the process instead of stopping a
 * game, because there is no game to ask. [translate] answers from the framework's own language
 * files, which travel in the core library's jar and are therefore on this classpath already.
 */
internal object WindowUtils : DslBackendUtils {
    override var clipboard: String
        get() = runCatching {
            Toolkit.getDefaultToolkit().systemClipboard.getData(DataFlavor.stringFlavor) as? String
        }.getOrNull() ?: ""
        set(value) {
            // The window shares the desktop's clipboard with every other application, which is what a
            // user expects from copy and paste in a real window.
            runCatching { Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(value), null) }
        }

    override fun narrate(string: String) {
        // There is no accessibility bridge from a plain Swing frame to a screen reader here, and
        // Swing's accessible context is not populated by this backend, so there is nothing to say.
    }

    override fun translate(key: String, vararg args: Any?): String? = WindowTranslations.get(key, args)

    override val isInWorld: Boolean get() = false

    override fun loadLocalImage(file: File): ImageHolder = holders.getOrPut(file) { holderFor(file) }

    override fun forceLoadLocalImage(file: File): ImageHolder = holderFor(file).also { holders[file] = it }

    override fun playButtonSound() {
        // A window has no UI sound set. The only sound the platform can make is a system beep, which
        // is not a button click and would be worse than silence.
    }

    override fun openUri(uri: String) {
        runCatching {
            val desktop = if (Desktop.isDesktopSupported()) Desktop.getDesktop() else null
            desktop?.takeIf { it.isSupported(Desktop.Action.BROWSE) }?.browse(URI(uri))
        }
    }

    override val mainDispatcher: CoroutineDispatcher get() = EdtDispatcher

    override suspend fun capture(fileName: String): File? = withContext(mainDispatcher) { captureNow(fileName) }

    override fun quit(exitCode: Int) {
        WindowBackend.closeAll()
        // Nothing here can ask a hosting game to stop - this backend deliberately holds no loader
        // reference - so the process is ended directly. `quit` is only ever reached from a preview
        // session, whose whole purpose is to run unattended and exit.
        exitProcess(exitCode)
    }

    private val holders = HashMap<File, ImageHolder>()

    /**
     * A local image needs no upload step here, so it is read on the spot and the framework gets a
     * usable holder straight away instead of an empty one that fills in later. The file is small
     * enough that reading it on the event dispatch thread is not worth a second thread.
     */
    private fun holderFor(file: File): ImageHolder {
        if (!file.isFile) return MISSING
        val size = runCatching { ImageIO.read(file) }.getOrNull() ?: return MISSING
        return ImageHolder(file.path, size.width.px, size.height.px)
    }

    private val MISSING = ImageHolder("missing", 16.px, 16.px)

    /**
     * Photographs the window this backend has on screen.
     *
     * It renders the window's own content instead of grabbing the desktop region it occupies. A
     * desktop grab needs the window to actually be on top, which the operating system's foreground
     * lock can refuse after `toFront()`, and it then writes whatever else was in that rectangle - a
     * screenshot that silently belongs to another application is worse than no screenshot. Rendering
     * the content is deterministic and is still this backend's own pixels, which is what a preview
     * gates on.
     *
     * The content is the page at the top of the stack: the pages below it are not in the rendered
     * tree, so photographing the surface photographs the page the user is on.
     */
    private fun captureNow(fileName: String): File? {
        if (WindowFrame.top() == null) return null
        val surface = WindowFrame.surface() ?: return null
        val image = BufferedImage(maxOf(surface.width, 1), maxOf(surface.height, 1), BufferedImage.TYPE_INT_ARGB)
        val graphics = image.createGraphics()
        return try {
            surface.printAll(graphics)
            val file = File(WindowMetadata.gameDir.toFile(), "screenshots/$fileName")
            file.parentFile?.mkdirs()
            if (runCatching { ImageIO.write(image, "png", file) }.getOrDefault(false)) file else null
        } finally {
            graphics.dispose()
        }
    }
}

/**
 * The main thread of a Swing application, as a coroutine dispatcher.
 *
 * Everything the framework builds or paints has to happen on the event dispatch thread, because the
 * DSL context captures the thread it was created on and refuses to be read from another one; a
 * dispatch that is already on that thread is run inline rather than queued, so `withContext` from
 * inside a paint does not deadlock.
 */
internal object EdtDispatcher : CoroutineDispatcher() {
    override fun dispatch(context: CoroutineContext, block: Runnable) {
        if (SwingUtilities.isEventDispatchThread()) block.run() else SwingUtilities.invokeLater(block)
    }

    override fun isDispatchNeeded(context: CoroutineContext) = !SwingUtilities.isEventDispatchThread()
}
