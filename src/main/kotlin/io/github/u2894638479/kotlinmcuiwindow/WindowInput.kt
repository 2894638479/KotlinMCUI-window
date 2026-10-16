package io.github.u2894638479.kotlinmcuiwindow

import io.github.u2894638479.kotlinmcui.backend.DslBackendInput
import io.github.u2894638479.kotlinmcui.container.TopComponent
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
internal class WindowInput : DslBackendInput {
    override var mouse = Position(0.px, 0.px)
        private set

    private val heldKeys = HashSet<Int>()
    private val heldButtons = HashSet<Int>()

    override fun isKeyDown(key: Key) = heldKeys.contains(key.code)

    override fun isMouseDown(mouse: Int) = heldButtons.contains(mouse)

    override fun setPreeditArea(rect: Rect) {
        // Swing exposes no IME candidate-window placement API: the candidate window is positioned by
        // the input method on every platform this backend runs on. A toolkit that has one would
        // position it here.
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
        mouse = Position(x.px, y.px)
        guarded("a cursor move", Unit) { context(mouse) { TopComponent.mouseMove() } }
    }

    fun mousePressed(event: MouseEvent): Boolean {
        val button = buttonOf(event) ?: return false
        heldButtons.add(button.ordinal)
        return guarded("a mouse press", false) { context(mouse, modifiersOf(event)) { TopComponent.mouseDown(button) } }
    }

    fun mouseReleased(event: MouseEvent): Boolean {
        val button = buttonOf(event) ?: return false
        heldButtons.remove(button.ordinal)
        return guarded("a mouse release", false) { context(mouse, modifiersOf(event)) { TopComponent.mouseUp(button) } }
    }

    fun mouseWheel(event: MouseWheelEvent) {
        // AWT counts wheel notches downwards and the framework counts them upwards, which is the
        // direction GLFW reports in.
        val amount = -event.preciseWheelRotation
        guarded("a scroll", Unit) {
            context(mouse) {
                if (event.isShiftDown) TopComponent.mouseScrollHorizontal(amount)
                else TopComponent.mouseScrollVertical(amount)
            }
        }
    }

    fun keyPressed(event: KeyEvent): Boolean {
        val key = keyOf(event)
        heldKeys.add(key.code)
        // The framework reads a repeat as another press, which is what GLFW's GLFW_REPEAT path does,
        // so a repeated AWT event is passed straight through.
        return guarded("a key press", false) { context(modifiersOf(event)) { TopComponent.keyDown(key, event.keyCode) } }
    }

    fun keyReleased(event: KeyEvent): Boolean {
        val key = keyOf(event)
        heldKeys.remove(key.code)
        return guarded("a key release", false) { context(modifiersOf(event)) { TopComponent.keyUp(key, event.keyCode) } }
    }

    fun keyTyped(event: KeyEvent): Boolean {
        val typed = event.keyChar
        if (typed == KeyEvent.CHAR_UNDEFINED) return false
        return guarded("a typed character", false) { context(modifiersOf(event)) { TopComponent.charTyped(typed) } }
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
