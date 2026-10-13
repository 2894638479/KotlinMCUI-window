package io.github.u2894638479.kotlinmcuiwindow

/**
 * The homogeneous part of a `Transform`, enough to project a point and to nest transforms.
 *
 * `AffineTransform` carries six numbers, so a projective third row has nowhere to go in Java2D: it
 * is kept here and applied per corner by the renderer instead, which is what the Minecraft backends
 * that only have a batched quad path do with the same matrix.
 */
internal class WindowPerspective(
    private val m00: Float, private val m01: Float, private val m02: Float,
    private val m10: Float, private val m11: Float, private val m12: Float,
    private val m20: Float, private val m21: Float, private val m22: Float,
) {
    /** Whether `AffineTransform` can hold this one: there is no third row to speak of. */
    val isAffine get() = m20 == 0f && m21 == 0f && m22 == 1f

    /** [x] after the divide by `w`, in the space this transform maps into. */
    fun projectX(x: Float, y: Float): Float {
        val w = m20 * x + m21 * y + m22
        if (w == 0f) return x
        return (m00 * x + m01 * y + m02) / w
    }

    /** [y] after the divide by `w`, in the space this transform maps into. */
    fun projectY(x: Float, y: Float): Float {
        val w = m20 * x + m21 * y + m22
        if (w == 0f) return y
        return (m10 * x + m11 * y + m12) / w
    }

    /** The matrix that applies [other] first and then this one. */
    fun concat(other: WindowPerspective): WindowPerspective = WindowPerspective(
        m00 * other.m00 + m01 * other.m10 + m02 * other.m20,
        m00 * other.m01 + m01 * other.m11 + m02 * other.m21,
        m00 * other.m02 + m01 * other.m12 + m02 * other.m22,
        m10 * other.m00 + m11 * other.m10 + m12 * other.m20,
        m10 * other.m01 + m11 * other.m11 + m12 * other.m21,
        m10 * other.m02 + m11 * other.m12 + m12 * other.m22,
        m20 * other.m00 + m21 * other.m10 + m22 * other.m20,
        m20 * other.m01 + m21 * other.m11 + m22 * other.m21,
        m20 * other.m02 + m21 * other.m12 + m22 * other.m22,
    )
}
