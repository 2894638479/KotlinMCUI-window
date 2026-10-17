package io.github.u2894638479.kotlinmcuiwindow.fabric

import io.github.u2894638479.kotlinmcui.logger.dslLogger
import io.github.u2894638479.kotlinmcuiwindow.startWindow
import net.fabricmc.api.ClientModInitializer

/**
 * Fabric's client entry point.
 *
 * `ClientModInitializer` and not `ModInitializer`, on purpose: Fabric runs every `ModInitializer`
 * before any `ClientModInitializer`, so this is guaranteed to happen after the headless backend has
 * installed the framework - which is a precondition of `start` and cannot be relied on
 * between two mods otherwise.
 *
 * `start` is the whole class, and the window goes up right after it: the framework's entry
 * list is the page at the bottom of its stack, and the headless backend that owns the framework's
 * metadata and entry discovery has already installed it by the time this runs.
 *
 * The window goes up whether or not this call published the platform: a process whose platform
 * another backend owns still gets a window here, rendering a root of this backend's own.
 */
class WindowFabricEntry : ClientModInitializer {
    override fun onInitializeClient() {
        if (!startWindow().published) {
            dslLogger.warn("window: another platform owns this process; this window renders its own root")
        }
    }
}
