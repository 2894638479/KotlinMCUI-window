package io.github.u2894638479.kotlinmcuiwindow

import kotlin.math.ceil

/**
 * The GUI scale at which [width] x [height] output pixels hold the 320 x 240 interface that the
 * framework's `.scaled` sizes are written against, never below 1.0 so a small window still gets a
 * usable one.
 *
 * Both numbers are the drawing target's own pixels - the framebuffer/canvas backing the window paints
 * into - not the panel's logical pixels. The game derives its own scale from the same kind of numbers
 * but rounds down to a whole number, because it draws glyphs and box outlines without anti-aliasing
 * and a fractional scale would leave their edges uneven. Java2D anti-aliases both, so the fraction is
 * kept here: the interface grows with the window instead of jumping between whole-number steps, and
 * it fills a window of any size instead of leaving a margin that grows with it.
 */
internal fun guiScaleFor(width: Int, height: Int): Double =
    minOf(width / REFERENCE_WIDTH, height / REFERENCE_HEIGHT).coerceAtLeast(1.0)

/**
 * How many output pixels a logical extent of [logical] spans when the target paints at [scale].
 *
 * The graphics transform is the actual display-to-target mapping, so this is the real backing size,
 * including a fractional or non-uniform one. A zero or invalid extent is an empty output rather than
 * a made-up default: a minimised window has no pixels to paint, and the scale that accompanies it
 * stays finite.
 */
internal fun outputExtent(logical: Int, scale: Double): Int {
    if (logical <= 0 || !scale.isFinite() || scale <= 0.0) return 0
    val extent = logical * scale
    if (!extent.isFinite() || extent > Int.MAX_VALUE) return 0
    // The epsilon keeps a value that is mathematically whole from gaining a pixel through rounding.
    return ceil(extent - EXTENT_EPSILON).toInt().coerceAtLeast(0)
}

/** The interface size in GUI units that a scale of 1.0 shows, as in the game's own calculation. */
private const val REFERENCE_WIDTH = 320.0
private const val REFERENCE_HEIGHT = 240.0

/** Below the smallest real target: absorbs binary floating point in `logical * scale`. */
private const val EXTENT_EPSILON = 1e-9
