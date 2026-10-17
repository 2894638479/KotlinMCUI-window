package io.github.u2894638479.kotlinmcuiwindow

import io.github.u2894638479.kotlinmcui.dsl.DslFunction
import io.github.u2894638479.kotlinmcui.logger.dslLogger
import java.awt.Dimension
import java.awt.Frame
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
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import javax.swing.JFrame
import javax.swing.JPanel
import javax.swing.SwingUtilities
import javax.swing.Timer
import javax.swing.WindowConstants

/**
 * The Swing window one backend instance has, and the page stack inside it.
 *
 * A page is not a window: [pages] is the whole of what is on screen, the last element is the page
 * being rendered, and only that one is in the root's children. [push] makes a new page the top
 * - unmounting the page below it, which keeps everything that page declared - and [pop] ends the
 * top page and puts the one below it back, putting the window away when there is nothing below.
 * That is the stack the framework drives through `showScreen` and `DslScreen.close` alone.
 *
 * The window is opened once and stays: see [minimize] for why nothing the user can do takes it
 * down. [close] exists for the harness that owns the process, not for the page stack.
 *
 * The window is built on the first [push] rather than when this instance is first touched, because
 * the framework asks its backend for a screen whenever it wants one - the overlay layer does this
 * on the first frame - and a created page must not put a window on the desktop. [open] is the same
 * push with the root page, and exists so the entry points have one call to make.
 *
 * Nothing here touches Minecraft: the window is an ordinary Swing window that happens to live
 * inside the game's process, and its lifetime is its own.
 */
/**
 * Whether closing the page on screen ends it.
 *
 * A stack of one has no page below it: that page is the floor, and it is what the window comes back
 * to, so closing it puts the window away instead of ending the last thing in it.
 */
internal fun endsPage(stackSize: Int) = stackSize > 1

internal class WindowFrame(private val painter: WindowBackend, private val input: WindowInput) {
    private companion object {
        /** ~60 frames per second, the same cadence the game's own screen loop runs at. */
        const val FRAME_MILLIS = 16
    }

    private val pages = ArrayDeque<WindowScreen>()

    private var frame: JFrame? = null
    private var surface: WindowPanel? = null
    private var timer: Timer? = null

    /** Set while a page is moving through the stack, so a close action that reenters fails loudly. */
    private var updating = false

    /** Set while [pop] runs: the close action it triggers asks for a pop of its own. */
    private var popping = false
    private var closed = false

    /** The page being rendered, or `null` while no window is up. */
    internal fun top(): WindowScreen? = pages.lastOrNull()

    /** The window's surface, which is what a screenshot photographs. */
    internal fun surface(): JPanel? = surface

    /**
     * Open the window with [page] as the page at the bottom of the stack.
     *
     * The whole window belongs to this one call: a second one while it is up is a caller that has
     * not understood that this backend is a window and not a window manager.
     */
    internal fun open(page: WindowScreen) {
        onEdt {
            check(frame == null && pages.isEmpty()) { "the window is already open" }
            closed = false
            push(page)
        }
    }

    /** Make [page] the page on screen, keeping the page below it. */
    internal fun push(page: WindowScreen) {
        onEdt {
            check(!updating) { "a page was shown from inside another page's stack change" }
            updating = true
            try {
                val previous = pages.lastOrNull()
                if (previous != null) previous.unmount()
                pages.addLast(page)
                start(page)
                frame?.title = page.title
                page.mount()
                focusSurface()
            } finally {
                updating = false
            }
        }
    }

    /**
     * End the page on screen.
     *
     * The page below it takes its place. The page at the bottom of the stack is the floor - it is what
     * the window comes back to - so a close on it does not end it: the window is put away with the
     * stack untouched, and the taskbar entry brings that same page back (see [minimize]). That is also
     * what makes a close on the bottom page behave like the game backend's default: there is no
     * previous screen, so the platform is told the screen is over.
     *
     * Returns whether a page was ended.
     */
    internal fun pop(): Boolean = onEdtAndReturn {
        // A page's close action is this call, and releasing that page runs it: the page is already
        // off the stack, so letting it through would take the page below it down as well. The same
        // arrives from every page while the window itself is coming down.
        if (popping || updating) return@onEdtAndReturn false
        if (!endsPage(pages.size)) {
            minimize()
            return@onEdtAndReturn false
        }
        val page = pages.removeLast()
        popping = true
        try {
            // The page is off the stack before it is released: a close action that shows a page has
            // to see the stack as it will be, not as it was.
            page.release()
            val previous = pages.last()
            frame?.title = previous.title
            previous.mount()
            focusSurface()
        } finally {
            popping = false
        }
        true
    }

    /**
     * Put the window away without ending anything in it.
     *
     * This window is the framework's entire user interface here: it is opened once, and there is no
     * key binding, menu or second window that could ask for it back. So nothing the user can do
     * takes it down - the last Escape and the window's close button both minimise, the stack and
     * every page in it survive, and the taskbar entry is what brings that same stack back.
     */
    internal fun minimize() {
        onEdt {
            val window = frame ?: return@onEdt
            if (!window.isShowing) return@onEdt
            window.state = Frame.ICONIFIED
            dslLogger.info("window: put away with ${pages.size} page(s) still on the stack; the taskbar entry brings it back")
        }
    }

    /**
     * Take the window down and end every page in it.
     *
     * Idempotent, and safe to reach from a page that is being released: the stack is emptied first,
     * so the release that follows cannot see a page it still has to end. This is the way out for
     * whoever owns the process - a harness that is done with its screenshot, or the game shutting
     * down - and not something the window itself offers: see [minimize].
     */
    internal fun close() {
        onEdt {
            if (closed) return@onEdt
            closed = true
            updating = true
            try {
                while (pages.isNotEmpty()) pages.removeLast().release()
            } finally {
                updating = false
            }
            dispose()
        }
    }

    /** Give up the desktop resources. Pages must already be gone; a later [push] builds a new window. */
    private fun dispose() {
        timer?.stop()
        timer = null
        surface = null
        val window = frame
        frame = null
        if (window != null && window.isDisplayable) window.dispose()
    }

    /** Build the window and start the frame loop on the first page only. */
    private fun start(page: WindowScreen) {
        if (frame != null) return
        val panel = WindowPanel()
        val window = JFrame(page.title)
        window.defaultCloseOperation = WindowConstants.DO_NOTHING_ON_CLOSE
        window.contentPane.add(panel)
        // The opening page decides how big the window starts. It is a starting size and not a fixed
        // one: the surface reports the size it actually has to every frame, so a resized window
        // re-lays the page out instead of letterboxing it.
        panel.preferredSize = Dimension(page.width, page.height)
        window.addWindowListener(object : WindowAdapter() {
            // The close button puts the window away, exactly like the last Escape: the stack is the
            // only way to the pages in it, and a disposed frame cannot be shown again.
            override fun windowClosing(event: WindowEvent) = minimize()

            // Restoring from the taskbar gives the window the focus back, and the surface needs it
            // to see the next key.
            override fun windowDeiconified(event: WindowEvent) = focusSurface()
        })
        window.pack()
        window.setLocationRelativeTo(null)
        // A panel that cannot take focus never sees a key event, so the surface asks for it as soon
        // as it is on screen and again on every click.
        panel.isFocusable = true
        window.isVisible = true
        panel.requestFocusInWindow()
        frame = window
        surface = panel
        // A Swing Timer fires on the event dispatch thread, which is the thread every DSL context in
        // this process is bound to, and the repaint is what drives the frame: the framework is
        // therefore never built or painted off that thread.
        timer = Timer(FRAME_MILLIS) { panel.repaint() }.also { it.start() }
    }

    /**
     * Ask for the focus back.
     *
     * A panel only receives key events while it holds the focus, and the page under it is a
     * different one after every stack change, so the surface asks again once that change is done -
     * otherwise the key after a step back would go nowhere. The same call serves a window that has
     * just been restored from the taskbar.
     */
    private fun focusSurface() {
        surface?.requestFocusInWindow()
    }

    private fun onEdt(block: () -> Unit) {
        if (SwingUtilities.isEventDispatchThread()) block() else SwingUtilities.invokeLater(block)
    }

    private fun <T> onEdtAndReturn(block: () -> T): T {
        if (SwingUtilities.isEventDispatchThread()) return block()
        var result: T? = null
        SwingUtilities.invokeAndWait { result = block() }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    /** The window's own surface: it turns AWT events into framework events and paints the tree. */
    private inner class WindowPanel : JPanel() {
        private var failureLogged = false

        init {
            isOpaque = true
            background = java.awt.Color.BLACK
            isDoubleBuffered = true
            isFocusable = true
            addMouseListener(object : MouseAdapter() {
                override fun mousePressed(event: MouseEvent) {
                    requestFocusInWindow()
                    input.mouseMoved(event.x, event.y)
                    input.mousePressed(event)
                }

                override fun mouseReleased(event: MouseEvent) {
                    input.mouseReleased(event)
                }
            })
            addMouseMotionListener(object : MouseMotionAdapter() {
                override fun mouseMoved(event: MouseEvent) = input.mouseMoved(event.x, event.y)
                override fun mouseDragged(event: MouseEvent) = input.mouseMoved(event.x, event.y)
            })
            addMouseWheelListener { event -> input.mouseWheel(event) }
            addKeyListener(object : KeyAdapter() {
                override fun keyPressed(event: KeyEvent) {
                    val consumed = input.keyPressed(event)
                    // Escape leaves the page, but only a page that did not want it: a page that
                    // consumed the key - to close a dialog of its own, say - has already said what
                    // Escape means here, and the stack must not overrule that.
                    if (!consumed && event.keyCode == KeyEvent.VK_ESCAPE) this@WindowFrame.pop()
                }

                override fun keyReleased(event: KeyEvent) {
                    input.keyReleased(event)
                }

                override fun keyTyped(event: KeyEvent) {
                    input.keyTyped(event)
                }
            })
            addFocusListener(object : FocusAdapter() {
                // A window that loses focus never sees the matching key-up, so nothing may stay
                // reported as held down.
                override fun focusLost(event: FocusEvent) = input.releaseAll()
            })
        }

        override fun paintComponent(g: Graphics) {
            super.paintComponent(g)
            // A page can be released while a repaint it queued is still on its way here, and there
            // is nothing left to paint in that case.
            if (top() == null) return
            val copy = g.create() as Graphics2D
            try {
                copy.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                copy.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
                copy.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
                // `width` and `height` are the panel's own, so a resize is a new frame size: the
                // framework lays the page out into it on this very frame.
                painter.paint(copy, width, height)
            } catch (e: Throwable) {
                // Swallowing it keeps the window alive for the next frame - a page that throws once
                // may well paint the frame after - while `renderFailure`, which the
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
}
