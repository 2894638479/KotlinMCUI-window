package io.github.u2894638479.kotlinmcuiwindow.neoforge

import io.github.u2894638479.kotlinmcui.backend.DslRuntime
import io.github.u2894638479.kotlinmcui.container.ViewerLifetime
import io.github.u2894638479.kotlinmcui.logger.dslLogger
import io.github.u2894638479.kotlinmcuiwindow.WindowPlatform
import io.github.u2894638479.kotlinmcuiwindow.WindowRootProvider
import io.github.u2894638479.kotlinmcuiwindow.WindowUtils
import net.neoforged.bus.api.IEventBus
import net.neoforged.fml.common.Mod
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent

private const val MOD_ID = "kotlinmcuiwindow"

/**
 * NeoForge's entry point.
 *
 * The same split as the Forge one: the constructor subscribes to the mod bus that NeoForge injects,
 * and the publishing waits for `FMLClientSetupEvent`, which NeoForge fires after every mod object
 * exists - and therefore after the headless backend's `install`.
 *
 * The bus arrives as a constructor parameter here rather than through a static accessor, because
 * NeoForge injects `IEventBus` into a mod's constructor and has done so for every version this jar
 * targets. Nothing here touches a Minecraft class.
 *
 * Only the call that publishes may put a window up: if another backend was published first, the
 * framework in this process is that backend's, so this one stays down and says so.
 */
@Mod(MOD_ID)
class WindowNeoForgeEntry(modBus: IEventBus) {
    init {
        // The game runs with java.awt.headless=true and AWT caches that on first use, so
        // clear it before anything in this process loads an AWT class.
        System.setProperty("java.awt.headless", "false")
        modBus.addListener { _: FMLClientSetupEvent -> clientSetup() }
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
