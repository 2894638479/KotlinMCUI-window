package io.github.u2894638479.kotlinmcuiwindow

import io.github.u2894638479.kotlinmcui.text.DslFont

/**
 * The caches that belong to the process rather than to one window.
 *
 * A font is the same object whoever asks for it: it carries no window, page stack or cursor. One
 * window asking must not build a copy that the next window then builds again, so fonts live here,
 * while everything that *is* per window - the surface, the input state, the page stack - lives on
 * the backend instance. Local images are process-wide too, but they age and load off-thread, so they
 * have their own cache in [WindowImages].
 */
internal object WindowCaches {
    val fonts = HashMap<String?, DslFont<WindowRenderParam>>()
}
