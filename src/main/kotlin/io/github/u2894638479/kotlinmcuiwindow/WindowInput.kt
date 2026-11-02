package io.github.u2894638479.kotlinmcuiwindow

import io.github.u2894638479.kotlinmcui.backend.DslRootInput
import io.github.u2894638479.kotlinmcui.container.DslRoot
import io.github.u2894638479.kotlinmcui.input.Key
import io.github.u2894638479.kotlinmcui.input.KeyModifiers
import io.github.u2894638479.kotlinmcui.input.MouseButton
import io.github.u2894638479.kotlinmcui.logger.dslLogger
import io.github.u2894638479.kotlinmcui.math.Position
import io.github.u2894638479.kotlinmcui.math.px
import io.github.u2894638479.kotlinmcui.math.rect.Rect
import java.awt.Toolkit
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import java.awt.event.MouseWheelEvent

/**
 * AWT input state and the bridge into the framework's event entry points.
 *
 * AWT delivers every event on the event dispatch thread, which is the same thread the frame loop
 * paints on, so the framework's per-context thread check is satisfied without any hand-off. The
 * `held` sets exist because AWT has no "is this key down right now" query: the platform's own
 * `isKeyDown` is not available here, and the alternative - polling a global - would need a native
 * hook a mod jar cannot rely on.
 *
 * The translations themselves are the file-level functions below rather than members: they are pure
 * functions of one event and share nothing with the state this object keeps, so a second window
 * needs its own state without needing its own copy of the table.
 */
internal class WindowInput : DslRootInput {
    /**
     * The root this window renders, attached by its provider before the window is shown.
     *
     * Input must reach *this* root. Reading the process owner root here instead would send the
     * cursor and the keyboard to another backend's tree in a process where that backend registered
     * its provider first - silently, because a root with no focusable child under the cursor looks
     * exactly like input that was handled and ignored.
     */
    var root: DslRoot? = null

    /**
     * The cursor in the platform's own coordinate space, as every AWT event reports it.
     *
     * It is kept raw and mapped when read, so a window that was resized or moved to a display with a
     * different scale answers from the current mapping instead of from the coordinates of the last
     * paint. AWT delivers the cursor in the component's logical pixels; the framework's root, its
     * rect and all untransformed drawing are in the output target's pixels.
     */
    private var cursorX = 0.0
    private var cursorY = 0.0

    /**
     * Actual output pixels per logical pixel on each axis, updated by the frame that paints.
     *
     * Kept fractional: it is the graphics transform's scale, not the rounded bitmap extent divided
     * by the logical extent. A pointer must land on the output pixel that drawing actually painted.
     */
    private var outputScaleX = 1.0
    private var outputScaleY = 1.0

    override val mouse: Position
        get() = Position((cursorX * outputScaleX).px, (cursorY * outputScaleY).px)

    private val heldKeys = HashSet<Int>()
    private val heldButtons = HashSet<Int>()

    /**
     * Adopt the actual per-axis scale of one painted frame.
     *
     * These are the graphics transform's own ratios, the same ones drawing uses after the platform
     * scale is removed. They are not the rounded bitmap extent divided by the logical range: 101
     * logical pixels at 1.25 keep 1.25 here even though their bitmap is 127 pixels wide, so logical
     * 80 stays output 100 instead of drifting to 100.594. A non-finite or non-positive scale falls
     * back to one logical pixel per output pixel, matching a window that has no target yet.
     */
    internal fun mapOutput(scaleX: Double, scaleY: Double) {
        outputScaleX = if (scaleX.isFinite() && scaleX > 0.0) scaleX else 1.0
        outputScaleY = if (scaleY.isFinite() && scaleY > 0.0) scaleY else 1.0
    }

    override fun isKeyDown(key: Key) = heldKeys.contains(key.code)

    override fun isMouseDown(mouse: Int) = heldButtons.contains(mouse)

    override fun setPreeditArea(rect: Rect) {
        // [rect] is in output pixels, like the root and every untransformed drawing. Swing exposes no
        // IME candidate-window placement API: the candidate window is positioned by the input method
        // on every platform this backend runs on. A toolkit that had one would position it in logical
        // panel coordinates, so it would have to divide back through the same per-axis ratios above.
    }

    /**
     * Run the framework's handling of one AWT event and report whether it consumed the event.
     *
     * Events already arrive on the event dispatch thread, where the tree is built and painted, so
     * nothing is handed across threads here. What needs protecting is the caller: AWT catches
     * whatever escapes a listener, so without this a failure in the framework leaves a stack in the
     * log while the event itself goes unhandled. A failure is logged and reported as "not consumed",
     * which leaves the window's own handling - Escape, above all - in charge.
     */
    private inline fun <T> guarded(what: String, fallback: T, block: () -> T): T =
        try {
            block()
        } catch(e: Exception) {
            dslLogger.error("kotlinmcui failed to handle $what", e)
            fallback
        }

    fun mouseMoved(x: Int, y: Int) {
        cursorX = x.toDouble()
        cursorY = y.toDouble()
        val root = root ?: return
        guarded("a cursor move", Unit) { context(mouse) { root.component.mouseMove() } }
    }

    fun mousePressed(event: MouseEvent): Boolean {
        val button = buttonOf(event) ?: return false
        heldButtons.add(button.ordinal)
        val root = root ?: return false
        return guarded("a mouse press", false) { context(mouse, modifiersOf(event)) { root.component.mouseDown(button) } }
    }

    fun mouseReleased(event: MouseEvent): Boolean {
        val button = buttonOf(event) ?: return false
        heldButtons.remove(button.ordinal)
        val root = root ?: return false
        return guarded("a mouse release", false) { context(mouse, modifiersOf(event)) { root.component.mouseUp(button) } }
    }

    fun mouseWheel(event: MouseWheelEvent) {
        // AWT counts wheel notches downwards and the framework counts them upwards, which is the
        // direction GLFW reports in.
        val root = root ?: return
        val amount = -event.preciseWheelRotation
        guarded("a scroll", Unit) {
            context(mouse) {
                if (event.isShiftDown) root.component.mouseScrollHorizontal(amount)
                else root.component.mouseScrollVertical(amount)
            }
        }
    }

    fun keyPressed(event: KeyEvent): Boolean {
        val key = keyOf(event)
        heldKeys.add(key.code)
        val root = root ?: return false
        // The framework reads a repeat as another press, which is what GLFW's GLFW_REPEAT path does,
        // so a repeated AWT event is passed straight through.
        return guarded("a key press", false) { context(modifiersOf(event)) { root.component.keyDown(key, event.keyCode) } }
    }

    fun keyReleased(event: KeyEvent): Boolean {
        val key = keyOf(event)
        heldKeys.remove(key.code)
        val root = root ?: return false
        return guarded("a key release", false) { context(modifiersOf(event)) { root.component.keyUp(key, event.keyCode) } }
    }

    fun keyTyped(event: KeyEvent): Boolean {
        val root = root ?: return false
        val typed = event.keyChar
        if (typed == KeyEvent.CHAR_UNDEFINED) return false
        return guarded("a typed character", false) { context(modifiersOf(event)) { root.component.charTyped(typed) } }
    }

    /** A window that loses focus never sees the key-up, so nothing may stay stuck down. */
    fun releaseAll() {
        heldKeys.clear()
        heldButtons.clear()
    }
}

/** Translate an AWT key event to the framework's platform-neutral [Key]. */
internal fun keyOf(event: KeyEvent): Key = when (val code = event.keyCode) {
    KeyEvent.VK_ENTER -> Key.ENTER
    KeyEvent.VK_ESCAPE -> Key.ESCAPE
    KeyEvent.VK_BACK_SPACE -> Key.BACKSPACE
    KeyEvent.VK_DELETE -> Key.DELETE
    KeyEvent.VK_TAB -> Key.TAB
    KeyEvent.VK_LEFT -> Key.LEFT
    KeyEvent.VK_RIGHT -> Key.RIGHT
    KeyEvent.VK_UP -> Key.UP
    KeyEvent.VK_DOWN -> Key.DOWN
    KeyEvent.VK_HOME -> Key.HOME
    KeyEvent.VK_END -> Key.END
    KeyEvent.VK_PAGE_UP -> Key.PAGE_UP
    KeyEvent.VK_PAGE_DOWN -> Key.PAGE_DOWN
    KeyEvent.VK_INSERT -> Key.INSERT
    KeyEvent.VK_SHIFT -> side(event, Key.LEFT_SHIFT, Key.RIGHT_SHIFT)
    KeyEvent.VK_CONTROL -> side(event, Key.LEFT_CTRL, Key.RIGHT_CTRL)
    KeyEvent.VK_ALT -> side(event, Key.LEFT_ALT, Key.RIGHT_ALT)
    KeyEvent.VK_META -> side(event, Key.LEFT_SUPER, Key.RIGHT_SUPER)
    in KeyEvent.VK_A..KeyEvent.VK_Z -> Key(Key.A.code + (code - KeyEvent.VK_A))
    in KeyEvent.VK_0..KeyEvent.VK_9 -> Key(Key.N0.code + (code - KeyEvent.VK_0))
    in KeyEvent.VK_F1..KeyEvent.VK_F12 -> Key(Key.F1.code + (code - KeyEvent.VK_F1))
    // Anything else goes into the framework's extension range. AWT's virtual key codes are small
    // - VK_MINUS is 45, which is the framework's Key.P - so passing an unknown code through
    // unchanged, which the GLFW backend's much larger codes allow, would silently answer for a
    // named key that was never pressed.
    else -> Key.custom(Key.CUSTOM_RANGE_START + code)
}

/** Translate an AWT mouse event to the framework's [MouseButton], or `null` for an unknown one. */
internal fun buttonOf(event: MouseEvent): MouseButton? {
    val index = when (event.button) {
        MouseEvent.BUTTON1 -> 0
        MouseEvent.BUTTON3 -> 1
        MouseEvent.BUTTON2 -> 2
        else -> event.button - 1
    }
    return MouseButton.entries.getOrNull(index)
}

internal fun modifiersOf(event: InputEvent) = KeyModifiers(
    shift = event.isShiftDown,
    ctrl = event.isControlDown,
    alt = event.isAltDown,
    superKey = event.isMetaDown,
    capsLock = lockState(KeyEvent.VK_CAPS_LOCK),
    numLock = lockState(KeyEvent.VK_NUM_LOCK),
)

/** `Toolkit.getLockingKeyState` is unsupported on some platforms and throws in a headless one. */
private fun lockState(key: Int) = runCatching { Toolkit.getDefaultToolkit().getLockingKeyState(key) }.getOrDefault(false)

private fun side(event: KeyEvent, left: Key, right: Key) =
    if (event.keyLocation == KeyEvent.KEY_LOCATION_RIGHT) right else left
