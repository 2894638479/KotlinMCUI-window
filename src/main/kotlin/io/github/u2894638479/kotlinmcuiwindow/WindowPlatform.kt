package io.github.u2894638479.kotlinmcuiwindow

import io.github.u2894638479.kotlinmcui.backend.DslPlatformMetadata
import io.github.u2894638479.kotlinmcui.backend.DslPlatform
import io.github.u2894638479.kotlinmcui.backend.DslPlatformUtils
import kotlinx.coroutines.CoroutineDispatcher

/**
 * The process-level half of the window backend: what is true for the whole process, not for one output.
 *
 * It answers the three questions the core asks a platform - the metadata of the process, the helpers
 * that are not per-output, and the dispatcher every session is serialised on. For a window that is the
 * AWT event thread, which is why the same [EdtDispatcher] is the answer here and to
 * [io.github.u2894638479.kotlinmcui.backend.DslRootUtils.sessionDispatcher]: Swing has one UI thread
 * for the whole process, not one per window.
 */
internal class WindowPlatform(
    private val windowUtils: WindowUtils,
) : DslPlatform {
    override val metadata: DslPlatformMetadata get() = WindowMetadata

    override val utils: DslPlatformUtils get() = windowUtils

    override val dispatcher: CoroutineDispatcher get() = EdtDispatcher
}
