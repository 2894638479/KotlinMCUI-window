package io.github.u2894638479.kotlinmcuiwindow

import java.awt.Graphics2D
import java.awt.geom.AffineTransform
import java.awt.geom.Area

/**
 * The render context every drawing call on this backend receives.
 *
 * A frame gets exactly one of these and every quad of the tree is painted through it, so it is a
 * thin view onto the frame's [Graphics2D] rather than a second copy of its state: the transform the
 * framework pushes with `withTransform` is the one Swing already holds, and [transform] reads it
 * back instead of tracking it twice.
 *
 * A scissor is a rectangle in the caller's local space, but after rotation or a perspective it
 * becomes a quadrilateral in output space, and a nested scissor intersects two such quadrilaterals.
 * The effective region is kept in [outputClip] and is always enforced by the native Java2D clip.
 */
class WindowRenderParam(
    /**
     * The surface the current frame is being painted on.
     *
     * Its user space is the drawing target's pixels: the platform's display scale has already been
     * removed at the window boundary, so a coordinate here is one pixel of the output target.
     */
    val graphics: Graphics2D,
    /** Width of the output target this frame paints, in its own pixels. */
    val width: Int = 0,
    /** Height of the output target this frame paints, in its own pixels. */
    val height: Int = 0,
) {
    /** The transform currently in effect, which is the one on [graphics]. */
    val transform: AffineTransform get() = graphics.transform

    /**
     * The perspective in effect, or `null` while every transform is affine.
     *
     * Java2D cannot hold a third row, so a projective transform stays out of [graphics] entirely and
     * the renderer projects the corners itself; [transform] therefore never reports it.
     */
    internal var perspective: WindowPerspective? = null

    /**
     * The active clip in output (device) space, frozen when its scissor was entered.
     *
     * [graphics] exposes its clip in current user space and `setClip` accepts geometry in that
     * same space; the renderer therefore maps this frozen region through the inverse of the current affine
     * when it installs the native clip, so the device-space region itself does not move at a
     * transform boundary. It is `null` when no scissor is active, and an empty area means every
     * draw in the scope is suppressed.
     */
    internal var outputClip: Area? = null
}
