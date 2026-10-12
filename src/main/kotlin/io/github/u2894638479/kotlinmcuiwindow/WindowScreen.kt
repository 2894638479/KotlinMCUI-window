package io.github.u2894638479.kotlinmcuiwindow

import io.github.u2894638479.kotlinmcui.container.DslChild
import io.github.u2894638479.kotlinmcui.container.DslDataStore
import io.github.u2894638479.kotlinmcui.container.TopComponent
import io.github.u2894638479.kotlinmcui.dsl.DslFunction
import io.github.u2894638479.kotlinmcui.logger.dslLogger
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseMotionAdapter
import javax.swing.JFrame
import javax.swing.JPanel
import javax.swing.SwingUtilities
import javax.swing.Timer
import javax.swing.WindowConstants

/**
 * One window: a [JFrame] holding the Swing surface the backend paints on, plus the framework screen
 * that surface renders.
 *
 * The frame is built in [show] rather than in the constructor, because the framework asks its backend
 * for a screen whenever it wants a `DslScreen` object - the overlay layer does this on the first
 * frame - and an unshown screen must not put a window on the desktop.
 *
 * Nothing here touches Minecraft: the window is an ordinary Swing window that happens to live inside
 * the game's process, and its lifetime is its own.
 */
class WindowScreen internal constructor(
    private val backend: WindowBackend,
    val title: String,
    private val width: Int,
    private val height: Int,
    dslFunction: DslFunction,
) {
    val dataStore = DslDataStore(backend, title, { close() }, dslFunction)
    val dslScreen = dataStore.dslScreen

    /** The window this screen is showing, or `null` before [show] and after [close]. */
    var frame: JFrame? = null
        private set

    private var panel: WindowPanel? = null
    private var timer: Timer? = null
    private var child: DslChild? = null
    private var closing = false

    fun show() {
        onEdt {
            val existing = frame
            if (existing != null) {
                existing.toFront()
                return@onEdt
            }
            val surface = WindowPanel(backend, width, height)
            val window = JFrame(title)
            window.defaultCloseOperation = WindowConstants.DISPOSE_ON_CLOSE
            window.contentPane.add(surface)
            window.preferredSize = Dimension(width, height)
            window.pack()
            window.setLocationRelativeTo(null)
            window.addWindowListener(object : java.awt.event.WindowAdapter() {
                override fun windowClosed(event: java.awt.event.WindowEvent) = close()
            })
            // A panel that cannot take focus never sees a key event, so the surface asks for it as
            // soon as it is on screen and again on every click.
            surface.isFocusable = true
            window.isVisible = true
            surface.requestFocusInWindow()
            child = TopComponent.children.collect(dslScreen)
            panel = surface
            frame = window
            backend.register(this)
            // A Swing Timer fires on the event dispatch thread, which is the thread every DSL context
            // in this process is bound to, and the repaint is what drives the frame: the framework is
            // therefore never built or painted off that thread.
            timer = Timer(FRAME_MILLIS) { surface.repaint() }.also { it.start() }
        }
    }

    fun close() {
        onEdt {
            if (closing) return@onEdt
            closing = true
            timer?.stop()
            timer = null
            child?.let { TopComponent.children.remove(it) }
            child = null
            val window = frame
            frame = null
            panel = null
            backend.unregister(this)
            if (window != null && window.isDisplayable) window.dispose()
            // Releases the screen's properties and runs the page's close action, which is this method
            // again; the `closing` flag above is what stops that from looping.
            dslScreen.close()
        }
    }

    /** The window's own surface: it turns AWT events into framework events and paints the tree. */
    private class WindowPanel(
        private val backend: WindowBackend,
        private val width: Int,
        private val height: Int,
    ) : JPanel() {
        private var failureLogged = false

        init {
            isOpaque = true
            background = java.awt.Color.BLACK
            isDoubleBuffered = true
            isFocusable = true
            addMouseListener(object : MouseAdapter() {
                override fun mousePressed(event: MouseEvent) {
                    requestFocusInWindow()
                    WindowInput.mouseMoved(event.x, event.y)
                    WindowInput.mousePressed(event)
                }

                override fun mouseReleased(event: MouseEvent) {
                    WindowInput.mouseReleased(event)
                }
            })
            addMouseMotionListener(object : MouseMotionAdapter() {
                override fun mouseMoved(event: MouseEvent) = WindowInput.mouseMoved(event.x, event.y)
                override fun mouseDragged(event: MouseEvent) = WindowInput.mouseMoved(event.x, event.y)
            })
            addMouseWheelListener { event -> WindowInput.mouseWheel(event) }
            addKeyListener(object : KeyAdapter() {
                override fun keyPressed(event: KeyEvent) {
                    WindowInput.keyPressed(event)
                }

                override fun keyReleased(event: KeyEvent) {
                    WindowInput.keyReleased(event)
                }

                override fun keyTyped(event: KeyEvent) {
                    WindowInput.keyTyped(event)
                }
            })
            addFocusListener(object : FocusAdapter() {
                // A window that loses focus never sees the matching key-up, so nothing may stay
                // reported as held down.
                override fun focusLost(event: FocusEvent) = WindowInput.releaseAll()
            })
        }

        override fun paintComponent(g: Graphics) {
            super.paintComponent(g)
            val copy = g.create() as Graphics2D
            try {
                copy.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                copy.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
                copy.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
                backend.paint(copy, width, height)
            } catch (e: Throwable) {
                // Swallowing it keeps the window alive for the next frame - a page that throws once
                // may well paint the frame after - while `TopComponent.renderFailure`, which the
                // framework sets before rethrowing, still tells a preview session the run failed.
                if (!failureLogged) {
                    failureLogged = true
                    dslLogger.error("window: the page threw while rendering; further failures are not logged", e)
                }
            } finally {
                copy.dispose()
            }
        }
    }

    private fun onEdt(block: () -> Unit) {
        if (SwingUtilities.isEventDispatchThread()) block() else SwingUtilities.invokeLater(block)
    }

    private companion object {
        /** ~60 frames per second, the same cadence the game's own screen loop runs at. */
        const val FRAME_MILLIS = 16
    }
}
