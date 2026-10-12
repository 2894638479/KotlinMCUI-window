package io.github.u2894638479.kotlinmcuiwindow.forge

import io.github.u2894638479.kotlinmcui.backend.DslRuntime
import io.github.u2894638479.kotlinmcuiwindow.WindowBackend
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
 */
@Mod(MOD_ID)
@Suppress("DEPRECATION")
class WindowForgeEntry {
    init {
        FMLJavaModLoadingContext.get().modEventBus.addListener { _: FMLClientSetupEvent -> clientSetup() }
    }

    private fun clientSetup() {
        DslRuntime.platformReady { WindowBackend }
    }
}
