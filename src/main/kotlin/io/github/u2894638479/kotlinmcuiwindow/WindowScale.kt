package io.github.u2894638479.kotlinmcuiwindow

/**
 * The GUI scale at which [width] x [height] pixels hold the 320 x 240 interface that the framework's
 * `.scaled` sizes are written against, never below 1.0 so a small window still gets a usable one.
 *
 * The game derives its own scale from the same kind of numbers but rounds down to a whole number,
 * because it draws glyphs and box outlines without anti-aliasing and a fractional scale would leave
 * their edges uneven. Java2D anti-aliases both, so the fraction is kept here: the interface grows
 * with the window instead of jumping between whole-number steps, and it fills a window of any size
 * instead of leaving a margin that grows with it.
 */
internal fun guiScaleFor(width: Int, height: Int): Double =
    minOf(width / REFERENCE_WIDTH, height / REFERENCE_HEIGHT).coerceAtLeast(1.0)

/** The interface size in GUI units that a scale of 1.0 shows, as in the game's own calculation. */
private const val REFERENCE_WIDTH = 320.0
private const val REFERENCE_HEIGHT = 240.0
