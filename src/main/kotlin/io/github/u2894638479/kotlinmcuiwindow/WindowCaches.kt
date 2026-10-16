package io.github.u2894638479.kotlinmcuiwindow

import io.github.u2894638479.kotlinmcui.image.ImageHolder
import io.github.u2894638479.kotlinmcui.text.DslFont
import java.io.File

/**
 * The caches that belong to the process rather than to one window.
 *
 * A font and a decoded local image are the same object whoever asks for them: neither carries a
 * window, a page stack or a cursor. One window asking must not build a copy that the next window
 * then builds again, so these live here, while everything that *is* per window - the surface, the
 * input state, the page stack - lives on the backend instance.
 */
internal object WindowCaches {
    val fonts = HashMap<String?, DslFont<WindowRenderParam>>()
    val holders = HashMap<File, ImageHolder>()
}