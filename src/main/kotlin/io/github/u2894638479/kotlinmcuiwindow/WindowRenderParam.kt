package io.github.u2894638479.kotlinmcuiwindow

import io.github.u2894638479.kotlinmcui.math.rect.Rect
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
 * [clip] cannot describe an arbitrary clip: a scissor is a rectangle in the caller's local space,
 * but after rotation or a perspective it becomes a quadrilateral in output space, and a nested
 * scissor intersects two such quadrilaterals. [clip] is therefore only a snapshot of the last valid
 * rectangular request; it is never used to decide coverage. The effective region is kept in
 * [outputClip] and is always enforced by the native Java2D clip.
 */
class WindowRenderParam(
    /** The surface the current frame is being painted on. */
    val graphics: Graphics2D,
    /** Width of the surface this frame paints, which is what the root's scale is derived from. */
    val width: Int = 0,
    /** Height of the surface this frame paints. */
    val height: Int = 0,
) {
    /** The transform currently in effect, which is the one on [graphics]. */
    val transform: AffineTransform get() = graphics.transform

    /**
     * A snapshot of the last valid rectangle passed to a scissor, or `null` when that request was
     * not a finite positive rectangle.
     *
     * This is informational only: rotation, perspective and intersection with a parent clip are not
     * representable as one rectangle, so coverage always comes from [outputClip] and the native
     * clip. The snapshot also means a caller mutating its own `Rect` implementation cannot change a
     * scope that has already been entered.
     */
    var clip: Rect? = null
        internal set

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
     * Java2D only stores a user-space clip; every transform boundary re-maps this region back
     * through the then-current affine so the region itself does not move. It is `null` when no
     * scissor is active, and an empty area means every draw in the scope is suppressed.
     */
    internal var outputClip: Area? = null
}
