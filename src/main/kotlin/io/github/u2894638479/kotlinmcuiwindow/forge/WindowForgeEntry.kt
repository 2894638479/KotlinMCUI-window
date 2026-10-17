package io.github.u2894638479.kotlinmcuiwindow.forge

import io.github.u2894638479.kotlinmcui.logger.dslLogger
import io.github.u2894638479.kotlinmcuiwindow.startWindow
import net.minecraftforge.fml.common.Mod
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext

private const val MOD_ID = "kotlinmcuiwindow"

/**
 * Forge's entry point.
 *
 * The constructor only subscribes; the publishing happens in [clientSetup]. That split is what makes
 * the ordering safe: Forge builds every mod object first and fires the mod bus afterwards, so
 * `FMLClientSetupEvent` is always later than the headless backend's `install`, while a constructor
 * has no such guarantee.
 *
 * `FMLJavaModLoadingContext.get().modEventBus` is used rather than an event-bus constructor
 * parameter or an `@EventBusSubscriber`: the constructor parameter form is Forge-version dependent,
 * and the annotation form needs a bus that is not there yet. This is the one subscription shape that
 * has compiled unchanged across the 1.20.x line. The accessor is marked deprecated inside the very
 * jar it is needed from, which is the reason for the suppression below: the replacement API the
 * warning points at does not exist in that version.
 *
 * Nothing here touches a Minecraft class, so the constructor cannot run before the game is ready.
 *
 * The window goes up whether or not this call published the platform: a process whose platform
 * another backend owns still gets a window here, rendering a root of this backend's own.
 */
@Mod(MOD_ID)
@Suppress("DEPRECATION")
class WindowForgeEntry {
    init {
        // The game runs with java.awt.headless=true and AWT caches that on first use, so
        // clear it before anything in this process loads an AWT class.
        System.setProperty("java.awt.headless", "false")
        FMLJavaModLoadingContext.get().modEventBus.addListener { _: FMLClientSetupEvent -> clientSetup() }
    }

    private fun clientSetup() {
        // The window goes up here rather than from the constructor: by this point the headless
        // backend has installed the framework, so the entry list it opens with has entries to list.
        if (!startWindow().published) {
            dslLogger.warn("window: another platform owns this process; this window renders its own root")
        }
    }
}
