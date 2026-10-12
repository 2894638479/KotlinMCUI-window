package io.github.u2894638479.kotlinmcuiwindow

import io.github.u2894638479.kotlinmcui.math.rect.Rect
import java.awt.Graphics2D
import java.awt.geom.AffineTransform

/**
 * The render context every drawing call on this backend receives.
 *
 * A frame gets exactly one of these and every quad of the tree is painted through it, so it is a
 * thin view onto the frame's [Graphics2D] rather than a second copy of its state: the transform the
 * framework pushes with `withTransform` is the one Swing already holds, and [transform] reads it
 * back instead of tracking it twice.
 *
 * [clip] is the one thing Swing cannot be asked for: the framework clips in its own coordinates,
 * and `Graphics2D.getClip` answers in device space and loses the rectangle it came from.
 */
class WindowRenderParam(
    /** The surface the current frame is being painted on. */
    val graphics: Graphics2D,
) {
    /** The transform currently in effect, which is the one on [graphics]. */
    val transform: AffineTransform get() = graphics.transform

    /** The rectangle the framework last clipped to, or `null` while nothing is clipped. */
    var clip: Rect? = null
        internal set
}
