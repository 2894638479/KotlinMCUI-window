package io.github.u2894638479.kotlinmcuiwindow

import io.github.u2894638479.kotlinmcui.component.DslComponent
import io.github.u2894638479.kotlinmcui.dsl.ui.Spacer
import io.github.u2894638479.kotlinmcui.input.*
import io.github.u2894638479.kotlinmcui.math.Position
import io.github.u2894638479.kotlinmcui.math.px
import io.github.u2894638479.kotlinmcui.modifier.Modifier
import io.github.u2894638479.kotlinmcui.modifier.size
import java.awt.event.MouseEvent
import java.util.concurrent.CopyOnWriteArrayList
import javax.swing.JPanel
import kotlin.test.*

class WindowPointerTest {
    private class Surface {
        val h = WindowHarness()
        val events = CopyOnWriteArrayList<PointerEvent>()
        init {
            h.mount {
                Spacer(Modifier.size(100.px, 100.px), "pointer").change { inner -> object : DslComponent by inner {
                    override fun onPointer(event: PointerEvent): PointerResult {
                        events += event
                        return if (event is PointerEvent.Down) PointerResult.CAPTURE else PointerResult.IGNORED
                    }
                } }
            }
            h.paintInto(200, 200, 100, 100, 2.0, 2.0)
        }
        fun event(button: Int, x: Int = 50, y: Int = 50) = MouseEvent(JPanel(), MouseEvent.MOUSE_PRESSED, 0L, 0, x, y, 1, false, button)
    }
    @Test fun `Down and final Up map their own positions once and use fallback pressure`() {
        val s = Surface()
        try {
            assertTrue(s.h.input.mousePressed(s.event(MouseEvent.BUTTON1)))
            assertTrue(s.h.input.mouseReleased(s.event(MouseEvent.BUTTON1, 60, 70)))
            assertEquals(Position(100.px, 100.px), (s.events[0] as PointerEvent.Down).position)
            assertEquals(0.5f, (s.events[0] as PointerEvent.Down).pressure)
            assertEquals(Position(120.px, 140.px), (s.events[1] as PointerEvent.Up).position)
            assertEquals(0f, (s.events[1] as PointerEvent.Up).pressure)
        } finally { s.h.root.close() }
    }
    @Test fun `AWT button changes share one identity and partial Up keeps the owner`() {
        val s = Surface()
        try {
            s.h.input.mousePressed(s.event(MouseEvent.BUTTON1))
            s.h.input.mousePressed(s.event(MouseEvent.BUTTON3))
            s.h.input.mouseReleased(s.event(MouseEvent.BUTTON1))
            s.h.input.mouseMoved(80, 80)
            s.h.input.mouseReleased(s.event(MouseEvent.BUTTON3))
            assertEquals(1, s.events.map { it.id }.distinct().size)
            assertEquals(setOf(PointerButton.SECONDARY), (s.events[2] as PointerEvent.Up).buttons)
            assertTrue(s.events[3] is PointerEvent.Move)
            assertEquals(2, s.events.count { it is PointerEvent.Up })
        } finally { s.h.root.close() }
    }
    @Test fun `leaving the panel preserves a drag but losing focus cancels it`() {
        val s = Surface()
        try {
            s.h.input.mousePressed(s.event(MouseEvent.BUTTON1))
            s.h.input.mouseLeft()
            s.h.input.mouseMoved(80, 80)
            assertTrue(s.events[1] is PointerEvent.Leave)
            assertTrue(s.events[2] is PointerEvent.Move)
            s.h.input.releaseAll()
            assertEquals(1, s.events.count { it is PointerEvent.Cancel })
            assertFalse(s.h.input.mouseReleased(s.event(MouseEvent.BUTTON1)))
            assertEquals(0, s.events.count { it is PointerEvent.Up })
        } finally { s.h.root.close() }
    }
}
