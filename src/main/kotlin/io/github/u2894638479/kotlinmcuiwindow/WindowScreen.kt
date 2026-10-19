package io.github.u2894638479.kotlinmcuiwindow

import io.github.u2894638479.kotlinmcui.backend.DslPlatformUtils
import io.github.u2894638479.kotlinmcui.container.DslChild
import io.github.u2894638479.kotlinmcui.container.DslDataStore
import io.github.u2894638479.kotlinmcui.container.DslRoot
import io.github.u2894638479.kotlinmcui.dsl.DslFunction

/**
 * One page of the single window: the framework screen and the state that belongs to it, and nothing
 * about the window itself.
 *
 * A page owns two things the framework reads - [dataStore] and [dslScreen] - and the tree slot it
 * occupies in the root it was built for. Creating one puts nothing on screen and nothing on the
 * desktop: the window belongs to [WindowFrame], and where a page sits in the window's stack is
 * decided by the window when the page is shown.
 *
 * ### Mounting, unmounting and releasing
 * The three states are the page stack itself. [mount] makes the page the tree its root renders,
 * [unmount] takes it out again, and [release] ends it. Only the last one closes the framework screen.
 *
 * Unmounting deliberately does not close it. Everything a page declared with `stable` lives in its
 * [DslDataStore] and is released by `DslScreen.close`, so a page that is merely hidden keeps what it
 * had - which is the same promise the game backend makes when a screen is left and entered again.
 * The counterpart is that a hidden page is invisible, not unbuilt: the root's children list is what
 * the frame renders, and leaving it is what stops the page from being drawn.
 *
 * Nothing here touches Minecraft: a page is an ordinary framework screen that happens to be rendered
 * into a Swing window inside the game's process.
 */
class WindowScreen internal constructor(
    private val utils: DslPlatformUtils,
    private val root: DslRoot,
    private val frame: WindowFrame,
    val width: Int,
    val height: Int,
    val title: String,
    dslFunction: DslFunction,
) {
    private var child: DslChild? = null
    private var released = false

    /**
     * The page's own close action is [WindowFrame.pop]: closing the screen is leaving the stack,
     * which is what the game backend's action does when it hands the platform the screen below.
     */
    val dataStore = DslDataStore(utils, root, title, { frame.pop() }, dslFunction)
    val dslScreen = dataStore.dslScreen

    /**
     * Make this page the rendered tree of the root it was built for.
     *
     * The root's child list is appended to rather than rebuilt: the framework's overlay layer is
     * collected into it once, when the list is first touched, and dropping it would take the
     * overlays down with it.
     */
    internal fun mount() {
        check(!released) { "page '$title' was released and cannot be shown again" }
        if (child != null) return
        child = root.children.collect(dslScreen)
    }

    /** Take this page out of the rendered tree, leaving everything it declared in place. */
    internal fun unmount() {
        val mounted = child ?: return
        child = null
        root.children.remove(mounted)
    }

    /**
     * End this page: it leaves the tree and its framework screen is closed.
     *
     * `DslScreen.close` runs the page's close action, which is [WindowFrame.pop] - the page is
     * already off the stack by the time this runs, so the pop it asks for is refused instead of
     * taking the page below down with it. After it returns the page is spent and must not be shown
     * again.
     */
    internal fun release() {
        if (released) return
        released = true
        unmount()
        dslScreen.close()
    }
}
