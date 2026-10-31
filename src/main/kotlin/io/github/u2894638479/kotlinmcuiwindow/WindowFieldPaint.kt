package io.github.u2894638479.kotlinmcuiwindow

import java.awt.Paint
import java.awt.PaintContext
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.Transparency
import java.awt.geom.AffineTransform
import java.awt.geom.Rectangle2D
import java.awt.image.BufferedImage
import java.awt.image.ColorModel
import java.awt.image.Raster
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * A [Paint] that evaluates one colour field at every device pixel, in premultiplied form.
 *
 * Java2D has no four-corner interpolation and cannot carry a projective transform, so the field is
 * handed to it the other way round: the shape is filled in user space as usual, and this paint
 * answers each device pixel by inverting the full local-to-device matrix. The inverse is affine for
 * an ordinary transform and a homography under a perspective, which is what keeps both cases on the
 * draw's *original local coordinates* instead of a projected bounding box or a screen-space triangle
 * field. Java2D still rasterises the shape, so clipping and the anti-aliased outer edge stay its job;
 * the paint only says what a covered sample contains.
 *
 * The corners arrive already premultiplied, and [evaluate] blends them in float; the texture, when
 * present, scales the premultiplied field by the sampled texel's straight RGB and its alpha, so both
 * alpha factors apply exactly once. The result leaves here as straight ARGB only because that is the
 * format Java2D composites; the premultiplied value is converted back in double precision, never
 * quantised on the way in.
 *
 * One [Context] exists per fill and reuses one row of packed pixels: the only buffers this adds are
 * the raster Java2D asks for and a row the width of the tile it asks for, never a screen-sized
 * intermediate.
 */
internal abstract class WindowFieldPaint(protected val texture: BufferedImage?) : Paint {

    /**
     * Fill [premult] with the field's premultiplied RGBA at device pixel centre ([deviceX], [deviceY]),
     * and [uv] with the texture position in texture pixels.
     */
    protected abstract fun evaluate(deviceX: Double, deviceY: Double, premult: FloatArray, uv: DoubleArray)

    override fun getTransparency(): Int = Transparency.TRANSLUCENT

    override fun createContext(
        cm: ColorModel,
        deviceBounds: Rectangle,
        userBounds: Rectangle2D,
        xform: AffineTransform,
        hints: RenderingHints,
    ): PaintContext = Context()

    private inner class Context : PaintContext {
        private var row = IntArray(0)
        private val premult = FloatArray(4)
        private val uv = DoubleArray(2)

        override fun dispose() {}

        /**
         * Straight ARGB is the canonical model Java2D accepts from any paint, whatever the
         * destination's own type is; the pipeline premultiplies it again for the source-over blend.
         */
        override fun getColorModel(): ColorModel = ColorModel.getRGBdefault()

        override fun getRaster(x: Int, y: Int, w: Int, h: Int): Raster {
            val model = ColorModel.getRGBdefault()
            val raster = model.createCompatibleWritableRaster(w, h)
            if (w <= 0 || h <= 0) return raster
            if (row.size < w) row = IntArray(w)
            for (py in 0 until h) {
                val deviceY = y + py + 0.5
                for (px in 0 until w) {
                    premult[0] = 0f
                    premult[1] = 0f
                    premult[2] = 0f
                    premult[3] = 0f
                    evaluate(x + px + 0.5, deviceY, premult, uv)
                    row[px] = pack(premult)
                }
                raster.setDataElements(0, py, w, 1, row)
            }
            return raster
        }

        /** The premultiplied sample as straight ARGB, with the texture's alpha and RGB applied once. */
        private fun pack(premult: FloatArray): Int {
            var r = premult[0]
            var g = premult[1]
            var b = premult[2]
            var a = premult[3]
            // A zero (or unusable) field contributes nothing, and asking the texture for a texel that
            // will be multiplied by zero is wasted work as much as it is a stale-coordinate risk.
            if (!(a > 0f) || !a.isFinite()) return 0
            if (texture != null) {
                if (!uv[0].isFinite() || !uv[1].isFinite()) return 0
                val argb = texture.getRGB(
                    Math.floorMod(floor(uv[0]).toInt(), texture.width),
                    Math.floorMod(floor(uv[1]).toInt(), texture.height),
                )
                val texAlpha = (argb ushr 24 and 0xFF) * (1f / 255f)
                if (texAlpha <= 0f) return 0
                r *= (argb shr 16 and 0xFF) * (1f / 255f) * texAlpha
                g *= (argb shr 8 and 0xFF) * (1f / 255f) * texAlpha
                b *= (argb and 0xFF) * (1f / 255f) * texAlpha
                a *= texAlpha
            }
            if (!(a > 0f)) return 0
            return premultipliedToArgb(r, g, b, a)
        }
    }
}

/** The straight ARGB Java2D accepts for a premultiplied sample, quantised only once, at the end. */
internal fun premultipliedToArgb(red: Float, green: Float, blue: Float, alpha: Float): Int {
    if (!(alpha > 0f) || !alpha.isFinite()) return 0
    val quantisedAlpha = (alpha * 255f).roundToInt().coerceIn(0, 255)
    if (quantisedAlpha == 0) return 0
    val scale = 1f / alpha
    val r = (red * scale * 255f).roundToInt().coerceIn(0, 255)
    val g = (green * scale * 255f).roundToInt().coerceIn(0, 255)
    val b = (blue * scale * 255f).roundToInt().coerceIn(0, 255)
    return (quantisedAlpha shl 24) or (r shl 16) or (g shl 8) or b
}

/**
 * The bilinear field through a quad's four premultiplied corner colours.
 *
 * [inverse] maps device coordinates back to the draw's own local space; [left]/[top] and the
 * reciprocals of the rectangle's extents turn that into the `u`, `v` of the *original* rectangle.
 * The texture range is carried separately, so repeating or cropping UVs never restarts the
 * gradient.
 */
internal class QuadFieldPaint(
    private val inverse: DoubleArray,
    private val left: Double,
    private val top: Double,
    private val uScale: Double,
    private val vScale: Double,
    private val corners: FloatArray,
    texture: BufferedImage?,
    private val texX0: Double,
    private val texY0: Double,
    private val texX1: Double,
    private val texY1: Double,
) : WindowFieldPaint(texture) {

    override fun evaluate(deviceX: Double, deviceY: Double, premult: FloatArray, uv: DoubleArray) {
        val w = inverse[6] * deviceX + inverse[7] * deviceY + inverse[8]
        if (w == 0.0) return
        val x = (inverse[0] * deviceX + inverse[1] * deviceY + inverse[2]) / w
        val y = (inverse[3] * deviceX + inverse[4] * deviceY + inverse[5]) / w
        val u = ((x - left) * uScale).toFloat()
        val v = ((y - top) * vScale).toFloat()
        for (channel in 0..3) {
            val topValue = corners[channel] + u * (corners[4 + channel] - corners[channel])
            val bottomValue = corners[8 + channel] + u * (corners[12 + channel] - corners[8 + channel])
            premult[channel] = topValue + v * (bottomValue - topValue)
        }
        uv[0] = texX0 + u * (texX1 - texX0)
        uv[1] = texY0 + v * (texY1 - texY0)
    }
}
