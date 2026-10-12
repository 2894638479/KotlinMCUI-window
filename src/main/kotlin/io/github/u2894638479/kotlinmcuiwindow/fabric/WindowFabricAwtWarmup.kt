package io.github.u2894638479.kotlinmcuiwindow.fabric

import net.fabricmc.api.ModInitializer

/**
 * Clears `java.awt.headless` before anything in this process touches AWT.
 *
 * The game starts with `java.awt.headless=true`, and `GraphicsEnvironment` caches that value the first
 * time any AWT class reads it. A `JFrame` created later in the same process therefore throws
 * `HeadlessException` no matter what the property says by then - and because the throw happens on the
 * event dispatch thread, where Swing swallows it, the window simply never appears and nothing is logged.
 * `main` is early enough: it runs while the game is still loading and before any AWT class has been
 * loaded, so setting the property here is what makes a window possible at all.
 */
class WindowFabricAwtWarmup : ModInitializer {
    override fun onInitialize() {
        System.setProperty("java.awt.headless", "false")
        // Reading the toolkit here fixes the value while it is still ours to set, instead of leaving it
        // to whichever AWT class happens to initialise `GraphicsEnvironment` first.
        java.awt.Toolkit.getDefaultToolkit()
    }
}