package io.github.u2894638479.kotlinmcuiwindow.fabric

import io.github.u2894638479.kotlinmcui.backend.DslRuntime
import io.github.u2894638479.kotlinmcuiwindow.WindowBackend
import net.fabricmc.api.ClientModInitializer

/**
 * Fabric's client entry point.
 *
 * `ClientModInitializer` and not `ModInitializer`, on purpose: Fabric runs every `ModInitializer`
 * before any `ClientModInitializer`, so this is guaranteed to happen after the headless backend has
 * installed the framework - which is a precondition of `platformReady` and cannot be relied on
 * between two mods otherwise.
 *
 * `platformReady` is the whole class. `install` belongs to the headless backend, which owns the
 * framework's metadata and entry discovery.
 */
class WindowFabricEntry : ClientModInitializer {
    override fun onInitializeClient() {
        DslRuntime.platformReady { WindowBackend }
    }
}
