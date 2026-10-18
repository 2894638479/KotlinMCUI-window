package io.github.u2894638479.kotlinmcuiwindow

import io.github.u2894638479.kotlinmcui.backend.Environment
import io.github.u2894638479.kotlinmcui.backend.dslMetadata
import io.github.u2894638479.kotlinmcui.entry.DslEntryService
import io.github.u2894638479.kotlinmcui.image.ImageHolder
import io.github.u2894638479.kotlinmcui.math.px
import java.awt.Toolkit

/**
 * The entry the framework's own discovery finds, now that this backend has no loader entry point.
 *
 * A jar with no descriptor never reaches the classpath, and one whose descriptor declares no entry
 * points does reach it without anything being executed - so the three descriptors carry identity
 * only, and this object is what `META-INF/kotlinmcui/entries` declares, for `client` and `standalone`
 * and no other environment. Its [launch] runs once the platform is published: in a game the headless
 * backend calls `DslRuntime.launch()`, in standalone `Standalone.main` does.
 */
object WindowEntry : DslEntryService {
    init {
        // A game client starts with java.awt.headless=true, and AWT reads that once and caches it. So
        // it is cleared before anything touches AWT - discovery instantiates this object long before
        // that - and the value is pinned immediately after, because setting it late is setting it
        // after the cache exists. The environment guard is what keeps a dedicated server from
        // loading AWT at all.
        if (dslMetadata.environment == Environment.CLIENT) {
            System.setProperty("java.awt.headless", "false")
            Toolkit.getDefaultToolkit()
        }
    }

    override val name: String get() = "KotlinMCUI Window"

    override val id: String get() = MOD_ID

    override val icon: ImageHolder get() = ImageHolder("kotlinmcuiwindow:icon.png", 16.px, 16.px)

    /**
     * The registry declares this entry for `client,standalone`, so a dedicated server never loads the
     * class at all - the filter runs before `Class.forName`. There is therefore nothing to guard here.
     */
    override fun launch() = enterWindow()
}
