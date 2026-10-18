package io.github.u2894638479.kotlinmcuiwindow.neoforge

import io.github.u2894638479.kotlinmcuiwindow.MOD_ID
import net.neoforged.fml.common.Mod

/**
 * The one class NeoForge insists on.
 *
 * It exists only because NeoForge may refuse a mod whose descriptor declares no `@Mod` class at all,
 * and nothing may be guessed about that: the failure would be a loader-side rejection that no test
 * here can see. It does nothing - no platform logic, no initialisation, no event handlers - because
 * startup still happens where it always does: `WindowEntry.launch()`, triggered by the framework's
 * own launch stage.
 *
 * Fabric needs no equivalent: its identity-only descriptor was verified on a real client.
 */
@Mod(MOD_ID)
class WindowNeoForgeMarker
