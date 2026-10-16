package io.github.u2894638479.kotlinmcuiwindow

import io.github.u2894638479.kotlinmcui.input.Key
import io.github.u2894638479.kotlinmcui.input.MouseButton
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import javax.swing.JPanel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The AWT-to-framework input mapping.
 *
 * This is the only hand-written platform mapping in the backend - every other part either computes
 * or draws - and it is the part a wrong answer is hardest to notice: a key that answers for another
 * one looks like a page that ignored the press. The events are built directly rather than delivered
 * to a window, so none of this needs a display.
 *
 * `WindowInput` holds the pressed sets, so every test that cares about them starts and ends at
 * [WindowInput.releaseAll].
 */
class WindowInputTest {
    /** Any component will do as an event source; nothing here asks it to be displayable. */
    private val source = JPanel()

    private fun keyEvent(
        keyCode: Int,
        modifiers: Int = 0,
        keyLocation: Int = KeyEvent.KEY_LOCATION_STANDARD,
    ) = KeyEvent(source, KeyEvent.KEY_PRESSED, 0L, modifiers, keyCode, KeyEvent.CHAR_UNDEFINED, keyLocation)

    private fun mouseEvent(button: Int, modifiers: Int = 0) =
        MouseEvent(source, MouseEvent.MOUSE_PRESSED, 0L, modifiers, 4, 4, 1, false, button)

    @Test
    fun `the named keys map to the framework's own constants`() {
        assertEquals(Key.ENTER, keyOf(keyEvent(KeyEvent.VK_ENTER)))
        assertEquals(Key.ESCAPE, keyOf(keyEvent(KeyEvent.VK_ESCAPE)))
        assertEquals(Key.BACKSPACE, keyOf(keyEvent(KeyEvent.VK_BACK_SPACE)))
        assertEquals(Key.DELETE, keyOf(keyEvent(KeyEvent.VK_DELETE)))
        assertEquals(Key.TAB, keyOf(keyEvent(KeyEvent.VK_TAB)))
        assertEquals(Key.LEFT, keyOf(keyEvent(KeyEvent.VK_LEFT)))
        assertEquals(Key.RIGHT, keyOf(keyEvent(KeyEvent.VK_RIGHT)))
        assertEquals(Key.UP, keyOf(keyEvent(KeyEvent.VK_UP)))
        assertEquals(Key.DOWN, keyOf(keyEvent(KeyEvent.VK_DOWN)))
        assertEquals(Key.HOME, keyOf(keyEvent(KeyEvent.VK_HOME)))
        assertEquals(Key.END, keyOf(keyEvent(KeyEvent.VK_END)))
        assertEquals(Key.PAGE_UP, keyOf(keyEvent(KeyEvent.VK_PAGE_UP)))
        assertEquals(Key.PAGE_DOWN, keyOf(keyEvent(KeyEvent.VK_PAGE_DOWN)))
        assertEquals(Key.INSERT, keyOf(keyEvent(KeyEvent.VK_INSERT)))
    }

    @Test
    fun `letters and digits are ordered the way the framework orders them`() {
        // AWT and the framework both count upwards, but from different origins, so the whole span has
        // to be shifted rather than the two ends special cased.
        assertEquals(Key.A, keyOf(keyEvent(KeyEvent.VK_A)))
        assertEquals(Key.M, keyOf(keyEvent(KeyEvent.VK_M)))
        assertEquals(Key.Z, keyOf(keyEvent(KeyEvent.VK_Z)))
        assertEquals(Key.N0, keyOf(keyEvent(KeyEvent.VK_0)))
        assertEquals(Key.N5, keyOf(keyEvent(KeyEvent.VK_5)))
        assertEquals(Key.N9, keyOf(keyEvent(KeyEvent.VK_9)))
    }

    @Test
    fun `function keys cover the whole f1 to f12 span`() {
        assertEquals(Key.F1, keyOf(keyEvent(KeyEvent.VK_F1)))
        assertEquals(Key.F6, keyOf(keyEvent(KeyEvent.VK_F6)))
        assertEquals(Key.F12, keyOf(keyEvent(KeyEvent.VK_F12)))
    }

    @Test
    fun `modifier keys are told apart by their location`() {
        assertEquals(Key.LEFT_SHIFT, keyOf(keyEvent(KeyEvent.VK_SHIFT, keyLocation = KeyEvent.KEY_LOCATION_LEFT)))
        assertEquals(Key.RIGHT_SHIFT, keyOf(keyEvent(KeyEvent.VK_SHIFT, keyLocation = KeyEvent.KEY_LOCATION_RIGHT)))
        assertEquals(Key.LEFT_CTRL, keyOf(keyEvent(KeyEvent.VK_CONTROL, keyLocation = KeyEvent.KEY_LOCATION_LEFT)))
        assertEquals(Key.RIGHT_CTRL, keyOf(keyEvent(KeyEvent.VK_CONTROL, keyLocation = KeyEvent.KEY_LOCATION_RIGHT)))
        assertEquals(Key.LEFT_ALT, keyOf(keyEvent(KeyEvent.VK_ALT, keyLocation = KeyEvent.KEY_LOCATION_LEFT)))
        assertEquals(Key.RIGHT_ALT, keyOf(keyEvent(KeyEvent.VK_ALT, keyLocation = KeyEvent.KEY_LOCATION_RIGHT)))
        assertEquals(Key.LEFT_SUPER, keyOf(keyEvent(KeyEvent.VK_META, keyLocation = KeyEvent.KEY_LOCATION_LEFT)))
        assertEquals(Key.RIGHT_SUPER, keyOf(keyEvent(KeyEvent.VK_META, keyLocation = KeyEvent.KEY_LOCATION_RIGHT)))
    }

    @Test
    fun `a locationless modifier counts as the left one`() {
        // AWT leaves the location unknown for events it did not get from a real keyboard, and a page
        // that cannot tell the two apart is better served by an answer than by none.
        assertEquals(Key.LEFT_SHIFT, keyOf(keyEvent(KeyEvent.VK_SHIFT, keyLocation = KeyEvent.KEY_LOCATION_UNKNOWN)))
    }

    @Test
    fun `a key the framework does not name goes into the extension range`() {
        // AWT's virtual key codes are small: VK_MINUS is 45, which is the framework's Key.P. Passing an
        // unknown code through would answer for a key nobody pressed.
        val minus = keyOf(keyEvent(KeyEvent.VK_MINUS))
        assertEquals(Key.custom(Key.CUSTOM_RANGE_START + KeyEvent.VK_MINUS), minus)
        assertNotEquals(Key.P, minus)
        assertEquals(Key.P.code, KeyEvent.VK_MINUS, "this is the collision the extension range exists to avoid")

        // A function key past F12 is unknown for the same reason, and takes the same road.
        val f13 = keyOf(keyEvent(KeyEvent.VK_F13))
        assertEquals(Key.custom(Key.CUSTOM_RANGE_START + KeyEvent.VK_F13), f13)
        assertTrue(f13.code >= Key.CUSTOM_RANGE_START)
    }

    @Test
    fun `mouse buttons follow the shared indices rather than awt's numbers`() {
        // GLFW and SDL count left, right, middle; AWT numbers them left, middle, right.
        assertEquals(MouseButton.LEFT, buttonOf(mouseEvent(MouseEvent.BUTTON1)))
        assertEquals(MouseButton.RIGHT, buttonOf(mouseEvent(MouseEvent.BUTTON3)))
        assertEquals(MouseButton.MIDDLE, buttonOf(mouseEvent(MouseEvent.BUTTON2)))
        assertEquals(0, buttonOf(mouseEvent(MouseEvent.BUTTON1))?.ordinal)
        assertEquals(1, buttonOf(mouseEvent(MouseEvent.BUTTON3))?.ordinal)
        assertEquals(2, buttonOf(mouseEvent(MouseEvent.BUTTON2))?.ordinal)
    }

    @Test
    fun `a button past the three awt names keeps counting upwards`() {
        // AWT has no constants for the side buttons: it reports them as numbers, and the index they
        // carry is the one the framework wants.
        assertEquals(MouseButton.BUTTON4, buttonOf(mouseEvent(4)))
        assertEquals(MouseButton.BUTTON5, buttonOf(mouseEvent(5)))
    }

    @Test
    fun `an event with no button has no framework button`() {
        assertNull(buttonOf(mouseEvent(MouseEvent.NOBUTTON)))
    }

    @Test
    fun `the four modifiers come from the event's mask`() {
        val all = modifiersOf(
            keyEvent(
                KeyEvent.VK_A,
                modifiers = InputEvent.SHIFT_DOWN_MASK or InputEvent.CTRL_DOWN_MASK or
                    InputEvent.ALT_DOWN_MASK or InputEvent.META_DOWN_MASK,
            )
        )
        assertTrue(all.shift)
        assertTrue(all.ctrl)
        assertTrue(all.alt)
        assertTrue(all.superKey)

        val none = modifiersOf(keyEvent(KeyEvent.VK_A))
        assertFalse(none.shift)
        assertFalse(none.ctrl)
        assertFalse(none.alt)
        assertFalse(none.superKey)
    }

    @Test
    fun `a held key and button are reported down until focus is lost`() {
        WindowInput.releaseAll()
        assertFalse(WindowInput.isKeyDown(Key.A))
        assertFalse(WindowInput.isMouseDown(MouseButton.LEFT.ordinal))

        // The pressed set is updated before the framework dispatch, which has no page to reach here,
        // so this records the state even though the dispatch itself cannot land.
        runCatching { WindowInput.keyPressed(keyEvent(KeyEvent.VK_A)) }
        runCatching { WindowInput.mousePressed(mouseEvent(MouseEvent.BUTTON1)) }
        assertTrue(WindowInput.isKeyDown(Key.A))
        assertTrue(WindowInput.isMouseDown(MouseButton.LEFT.ordinal))

        // A window that loses the focus never sees the matching release, so it drops everything it held.
        WindowInput.releaseAll()
        assertFalse(WindowInput.isKeyDown(Key.A))
        assertFalse(WindowInput.isMouseDown(MouseButton.LEFT.ordinal))
    }

    @Test
    fun `a released key stops being reported down`() {
        WindowInput.releaseAll()
        runCatching { WindowInput.keyPressed(keyEvent(KeyEvent.VK_A)) }
        assertTrue(WindowInput.isKeyDown(Key.A))

        runCatching {
            WindowInput.keyReleased(
                KeyEvent(source, KeyEvent.KEY_RELEASED, 0L, 0, KeyEvent.VK_A, KeyEvent.CHAR_UNDEFINED)
            )
        }
        assertFalse(WindowInput.isKeyDown(Key.A))
    }

    @Test
    fun `the cursor position follows the window's own coordinates`() {
        // AWT delivers cursor coordinates in the component's space, which is the space Rect uses.
        runCatching { WindowInput.mouseMoved(7, 11) }
        assertEquals(7.0, WindowInput.mouse.x.raw, 0.0)
        assertEquals(11.0, WindowInput.mouse.y.raw, 0.0)
    }
}
