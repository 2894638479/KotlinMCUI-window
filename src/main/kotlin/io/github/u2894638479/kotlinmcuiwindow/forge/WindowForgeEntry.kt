package io.github.u2894638479.kotlinmcuiwindow.forge

import io.github.u2894638479.kotlinmcui.backend.DslRuntime
import io.github.u2894638479.kotlinmcui.container.ViewerLifetime
import io.github.u2894638479.kotlinmcui.logger.dslLogger
import io.github.u2894638479.kotlinmcuiwindow.WindowPlatform
import io.github.u2894638479.kotlinmcuiwindow.WindowRootProvider
import io.github.u2894638479.kotlinmcuiwindow.WindowUtils
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
 * Only the call that publishes may put a window up: if another backend was published first, the
 * framework in this process is that backend's, so this one stays down and says so.
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
        val utils = WindowUtils()
        val platform = WindowPlatform(utils)
        val provider = WindowRootProvider(platform, utils)
        // Losing the platform slot is not the same as being unusable: the slot belongs to the
        // process, and this window still renders a root of its own in a process another backend
        // platform owns.
        if (!DslRuntime.platformReady { platform }) {
            dslLogger.warn("window: another platform owns this process; this window renders its own root")
        }
        // The owner root is built from the first registered provider, so it is this one's only when
        // nothing registered before it; otherwise this window gets a root of its own.
        val ownsMainRoot = DslRuntime.registeredProviders.isEmpty()
        DslRuntime.registerProvider(provider)
        provider.open(if (ownsMainRoot) DslRuntime.ownerRoot else DslRuntime.createRoot(provider, ViewerLifetime.PROCESS))
    }
}
