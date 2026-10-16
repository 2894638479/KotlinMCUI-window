package io.github.u2894638479.kotlinmcuiwindow.neoforge

import io.github.u2894638479.kotlinmcui.backend.DslRuntime
import io.github.u2894638479.kotlinmcui.logger.dslLogger
import io.github.u2894638479.kotlinmcuiwindow.WindowBackend
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
        if (!DslRuntime.platformReady { WindowBackend }) {
            dslLogger.warn("window: another backend was published first; this window stays down")
            return
        }
        WindowBackend.open()
    }
}
