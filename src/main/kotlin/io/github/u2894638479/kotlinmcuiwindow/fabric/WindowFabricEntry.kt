package io.github.u2894638479.kotlinmcuiwindow.fabric

import io.github.u2894638479.kotlinmcui.backend.DslRuntime
import io.github.u2894638479.kotlinmcui.container.ViewerLifetime
import io.github.u2894638479.kotlinmcui.logger.dslLogger
import io.github.u2894638479.kotlinmcuiwindow.WindowPlatform
import io.github.u2894638479.kotlinmcuiwindow.WindowRootProvider
import io.github.u2894638479.kotlinmcuiwindow.WindowUtils
import net.fabricmc.api.ClientModInitializer

/**
 * Fabric's client entry point.
 *
 * `ClientModInitializer` and not `ModInitializer`, on purpose: Fabric runs every `ModInitializer`
 * before any `ClientModInitializer`, so this is guaranteed to happen after the headless backend has
 * installed the framework - which is a precondition of `platformReady` and cannot be relied on
 * between two mods otherwise.
 *
 * `platformReady` is the whole class, and `open` is what puts the window up: the framework's entry
 * list is the page at the bottom of its stack, and the headless backend that owns the framework's
 * metadata and entry discovery has already installed it by the time this runs.
 *
 * Only the call that publishes may put a window up. If another backend was published first, the
 * framework in this process is that backend's, and a window opened here would draw the entry list
 * with a renderer that is not this one - so the window stays down and says so.
 */
class WindowFabricEntry : ClientModInitializer {
    override fun onInitializeClient() {
        val utils = WindowUtils()
        val platform = WindowPlatform(utils)
        val provider = WindowRootProvider(platform, utils)
        // Losing the platform slot is not the same as being unusable: the slot belongs to the
        // process, and this window still renders a root of its own in a process another backend
        // platform owns.
        if (!DslRuntime.platformReady { platform }) {
            dslLogger.warn("window: another platform owns this process; this window renders its own root")
        }
        DslRuntime.registerProvider(provider)
        provider.open(DslRuntime.rootFor(provider, ViewerLifetime.PROCESS))
    }
}
