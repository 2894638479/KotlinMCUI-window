package io.github.u2894638479.kotlinmcuiwindow

import io.github.u2894638479.kotlinmcui.backend.DslBackendRenderer
import io.github.u2894638479.kotlinmcui.backend.fillRect
import io.github.u2894638479.kotlinmcui.context.DslScaleContext
import io.github.u2894638479.kotlinmcui.image.ImageHolder
import io.github.u2894638479.kotlinmcui.math.Color
import io.github.u2894638479.kotlinmcui.math.px
import io.github.u2894638479.kotlinmcui.math.rect.Rect
import io.github.u2894638479.kotlinmcui.math.rect.isEmpty
import io.github.u2894638479.kotlinmcui.math.transform.Transform
import io.github.u2894638479.kotlinmcui.text.DslCharStyle
import io.github.u2894638479.kotlinmcui.text.DslFont
import io.github.u2894638479.kotlinmcui.text.DslRenderableChar
import java.awt.AlphaComposite
import java.awt.LinearGradientPaint
import java.awt.Paint
import java.awt.TexturePaint
import java.awt.geom.AffineTransform
import java.awt.geom.Path2D
import java.awt.geom.Point2D
import java.awt.geom.Rectangle2D

/**
 * Java2D implementation of the rendering contract.
 *
 * Everything the framework draws is a quad, and a quad is what Java2D fills best, so this backend is
 * "one [renderQuad] plus an approximation for whatever Java2D has no equivalent of". The
 * approximations are named where they are made:
 *
 * - **four independent corner colours** have no Java2D primitive; an axis-aligned gradient is exact
 *   and anything else falls back to the diagonal,
 * - **per-vertex colour interpolation** does not exist in Java2D at all, so [renderTriangles] shades
 *   each triangle flat,
 * - **Minecraft's textures are never read**: the widget backgrounds and the item stand-in below are
 *   drawn from this backend's own palette, because a window that is not a game window has no
 *   resource pack to read them from.
 */
internal object WindowRenderer : DslBackendRenderer<WindowRenderParam> {
    /**
     * 1.0, deliberately: this backend paints in the window's own pixels and has no game GUI scale to
     * account for, so a page's `scaled` sizes land on real pixels one for one and a screenshot of
     * the window is a faithful picture of what the framework asked for.
     */
    override val guiScale: Double get() = 1.0

    context(renderParam: WindowRenderParam)
    override fun flush() {
        // Every draw call paints immediately, so there is no batch to commit. The method stays
        // because the framework calls it at every scissor and transform boundary, and a backend that
        // does batch has to be able to say "and now" there.
    }

    context(renderParam: WindowRenderParam)
    override fun renderQuad(
        rect: Rect,
        lt: Color,
        rt: Color,
        lb: Color,
        rb: Color,
        image: ImageHolder?,
        uv: Rect?,
    ) {
        val bounds = rect.bounds() ?: return
        val (l, t, r, b) = bounds
        if (r <= l || b <= t) return
        val graphics = renderParam.graphics
        val area = Rectangle2D.Float(l, t, r - l, b - t)
        val texture = if (image == null || image.isEmpty || uv == null) null else WindowTextures.get(image)
        val source = if (texture == null) null else uv?.bounds()
        if (texture == null || source == null) {
            graphics.paint = gradientPaint(l, t, r, b, lt, rt, lb, rb)
            graphics.fill(area)
            return
        }
        val (u0, v0, u1, v1) = source
        if (u1 <= u0 || v1 <= v0) return
        val tint = averageOf(lt, rt, lb, rb)
        val savedComposite = graphics.composite
        // Pure alpha - a tint whose colour is white, which is what `renderImage` passes by default -
        // is exact: reduce the texture's own alpha rather than painting something over it.
        val alphaOnly = tint == Color.WHITE.change(a = tint.aInt)
        if (alphaOnly) {
            graphics.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, tint.aFloat)
        }
        // The source rectangle is taken as a sub-image rather than handed to the paint: the two
        // argument `TexturePaint` maps a whole image onto the anchor, and the only four argument one
        // is `isOriginTop`, not a source rectangle. A sub-image shares the parent's raster, so this
        // allocates a view and not a copy.
        graphics.paint = TexturePaint(imageArea(texture, u0, v0, u1, v1), area)
        graphics.fill(area)
        graphics.composite = savedComposite
        // A coloured tint means "multiply". Java2D has no multiply compositing rule, so the texture
        // is blended towards the tint instead: exact for pure alpha, an approximation otherwise.
        if (!alphaOnly && tint.aInt != 0) {
            graphics.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, tint.aFloat)
            graphics.paint = tint.awt()
            graphics.fill(area)
            graphics.composite = savedComposite
        }
    }

    context(renderParam: WindowRenderParam)
    override fun renderTriangles(vertices: FloatArray, colors: IntArray, image: ImageHolder?) {
        val texture = image?.takeIf { !it.isEmpty }?.let { WindowTextures.get(it) }
        val stride = if (texture == null) 2 else 4
        // A malformed list is a caller bug; drawing nothing beats drawing garbage.
        if (colors.size * stride != vertices.size || colors.size % 3 != 0) return
        val graphics = renderParam.graphics
        var vertex = 0
        var color = 0
        while (vertex < vertices.size) {
            val x0 = vertices[vertex]
            val y0 = vertices[vertex + 1]
            val x1 = vertices[vertex + stride]
            val y1 = vertices[vertex + stride + 1]
            val x2 = vertices[vertex + stride * 2]
            val y2 = vertices[vertex + stride * 2 + 1]
            if (texture == null) {
                // Java2D's 2-D API has no Gouraud shading: a triangle is one flat colour. The mean of
                // the three vertex colours is the closest a single fill can get, and it keeps a
                // gradient across a triangle fan monotonic instead of snapping to the apex colour.
                graphics.color = averageOf(colors[color], colors[color + 1], colors[color + 2])
                graphics.fill(triangle(x0, y0, x1, y1, x2, y2))
            } else {
                val u0 = vertices[vertex + 2]
                val v0 = vertices[vertex + 3]
                val u1 = vertices[vertex + stride + 2]
                val v1 = vertices[vertex + stride + 3]
                val u2 = vertices[vertex + stride * 2 + 2]
                val v2 = vertices[vertex + stride * 2 + 3]
                val mapping = uvToUser(u0, v0, x0, y0, u1, v1, x1, y1, u2, v2, x2, y2)
                if (mapping == null) {
                    graphics.color = averageOf(colors[color], colors[color + 1], colors[color + 2])
                    graphics.fill(triangle(x0, y0, x1, y1, x2, y2))
                } else {
                    // The texture is anchored in image pixels and the triangle is given in the same
                    // space, so the transform does the mapping and the fill does the clipping.
                    val savedTransform = graphics.transform
                    val savedPaint = graphics.paint
                    graphics.transform(mapping)
                    graphics.paint = TexturePaint(
                        texture,
                        Rectangle2D.Float(0f, 0f, texture.width.toFloat(), texture.height.toFloat()),
                    )
                    graphics.fill(triangle(u0, v0, u1, v1, u2, v2))
                    graphics.paint = savedPaint
                    graphics.transform = savedTransform
                }
            }
            vertex += stride * 3
            color += 3
        }
    }

    context(renderParam: WindowRenderParam, ctx: DslScaleContext)
    override fun renderButton(rect: Rect, highlighted: Boolean, active: Boolean, color: Color) {
        if (rect.isEmpty) return
        val surface = when {
            !active -> DISABLED
            highlighted -> BUTTON_HOVER
            else -> BUTTON
        }.mixedWith(color)
        val shadow = surface.change(r = surface.rInt * 3 / 5, g = surface.gInt * 3 / 5, b = surface.bInt * 3 / 5)
        renderQuad(rect, surface, surface, shadow, shadow)
        outline(rect, if (active) BUTTON_BORDER.mixedWith(color) else DISABLED_BORDER)
    }

    context(renderParam: WindowRenderParam, ctx: DslScaleContext)
    override fun renderSlot(rect: Rect) {
        if (rect.isEmpty) return
        renderQuad(rect, SLOT_INNER, SLOT_INNER, SLOT_INNER, SLOT_INNER)
        outline(rect, SLOT_BORDER)
    }

    context(renderParam: WindowRenderParam, ctx: DslScaleContext)
    override fun renderContainer(rect: Rect) {
        if (rect.isEmpty) return
        renderQuad(rect, PANEL_TOP, PANEL_TOP, PANEL_BOTTOM, PANEL_BOTTOM)
        outline(rect, PANEL_BORDER)
    }

    context(renderParam: WindowRenderParam, ctx: DslScaleContext)
    override fun renderTooltip(rect: Rect) {
        if (rect.isEmpty) return
        renderQuad(rect, TOOLTIP, TOOLTIP, TOOLTIP, TOOLTIP)
        outline(rect, TOOLTIP_BORDER)
    }

    context(renderParam: WindowRenderParam, ctx: DslScaleContext)
    override fun renderItem(rect: Rect, item: String, count: Int, damage: Double?, enchanted: Boolean) {
        if (rect.isEmpty) return
        // No item textures: a real window has no resource pack, so an item is stood in for by a
        // plate, its first letter and its stack size rather than by a wrong-looking sprite.
        renderQuad(rect, ITEM, ITEM, ITEM, ITEM)
        outline(rect, ITEM_BORDER)
        val bounds = rect.bounds() ?: return
        val (l, t, r, b) = bounds
        val letter = item.substringAfterLast(':').firstOrNull()
        if (letter != null) {
            val size = minOf(b - t - 2f, 9f).coerceAtLeast(1f)
            getFont().renderChar(
                DslRenderableChar(letter.code, DslCharStyle(), Color.WHITE, size.px),
                (l + 2f).px,
                (t + 1f).px,
                0.px,
                0.px,
            )
        }
        val durability = damage
        if (durability != null) {
            val filled = ((r - l - 2f) * (1.0 - durability)).toFloat().coerceIn(0f, r - l - 2f)
            fillRect(Rect((l + 1f).px, (b - 3f).px, (l + 1f + filled).px, (b - 1f).px), DAMAGE)
        }
        if (count > 1) {
            getFont().renderChar(
                DslRenderableChar('0'.code + (count % 10), DslCharStyle(), Color.WHITE, 9.px),
                (r - 6f).px,
                (b - 10f).px,
                0.px,
                0.px,
            )
        }
        if (enchanted) {
            // The glint is a flat tint over the plate: there is no animated overlay texture to
            // sample here, and inventing one would be drawing pixels the backend cannot read.
            renderQuad(rect, ENCHANT_GLINT, ENCHANT_GLINT, ENCHANT_GLINT, ENCHANT_GLINT)
        }
    }

    context(ctx: DslScaleContext, renderParam: WindowRenderParam)
    override fun renderDefaultBackground(rect: Rect) {
        if (rect.isEmpty) return
        renderQuad(rect, BACKGROUND, BACKGROUND, BACKGROUND, BACKGROUND)
    }

    context(renderParam: WindowRenderParam)
    override fun withScissor(rect: Rect, block: () -> Unit) {
        val bounds = rect.bounds()
        if (bounds == null) {
            block()
            return
        }
        val (l, t, r, b) = bounds
        val graphics = renderParam.graphics
        val savedClip = graphics.clip
        val savedRect = renderParam.clip
        // `Graphics2D.clip(Shape)` intersects with the clip already in effect, so nesting works by
        // itself; restoring the saved Shape is what puts it back.
        graphics.clip(Rectangle2D.Float(l, t, r - l, b - t))
        renderParam.clip = rect
        try {
            block()
        } finally {
            graphics.setClip(savedClip)
            renderParam.clip = savedRect
        }
    }

    context(renderParam: WindowRenderParam)
    override fun withTransform(transform: Transform, block: () -> Unit) {
        val graphics = renderParam.graphics
        val saved = graphics.transform
        // The 3x3 homogeneous matrix maps onto AffineTransform directly. Its third row is a
        // perspective, which Java2D cannot represent at all - a software 2D renderer has no
        // projective stage - so that part is dropped, exactly as the batched Minecraft path drops it.
        graphics.transform(
            AffineTransform(
                transform.m00.toDouble(), transform.m10.toDouble(),
                transform.m01.toDouble(), transform.m11.toDouble(),
                transform.m02.toDouble(), transform.m12.toDouble(),
            )
        )
        try {
            block()
        } finally {
            graphics.transform = saved
        }
    }

    override fun getFont(name: String?): DslFont<WindowRenderParam> = fonts.getOrPut(name) { WindowFont(name) }

    private val fonts = HashMap<String?, DslFont<WindowRenderParam>>()

    /** The four edges of [rect] as floats, or `null` when any of them is not a plain number. */
    private fun Rect.bounds(): FloatArray? {
        val l = left.pixelsOrElse<Float> { return null }
        val t = top.pixelsOrElse<Float> { return null }
        val r = right.pixelsOrElse<Float> { return null }
        val b = bottom.pixelsOrElse<Float> { return null }
        return floatArrayOf(l, t, r, b)
    }

    /** A view of one rectangle of [texture], clamped to what the image actually holds. */
    private fun imageArea(texture: java.awt.image.BufferedImage, u0: Float, v0: Float, u1: Float, v1: Float): java.awt.image.BufferedImage {
        val x = u0.toInt().coerceIn(0, texture.width - 1)
        val y = v0.toInt().coerceIn(0, texture.height - 1)
        val width = (u1.toInt() - x).coerceIn(1, texture.width - x)
        val height = (v1.toInt() - y).coerceIn(1, texture.height - y)
        return texture.getSubimage(x, y, width, height)
    }

    private fun triangle(x0: Float, y0: Float, x1: Float, y1: Float, x2: Float, y2: Float): Path2D.Float =
        Path2D.Float().apply {
            moveTo(x0, y0)
            lineTo(x1, y1)
            lineTo(x2, y2)
            closePath()
        }

    context(renderParam: WindowRenderParam)
    private fun outline(rect: Rect, color: Color) {
        val bounds = rect.bounds() ?: return
        val (l, t, r, b) = bounds
        fillRect(Rect(l.px, t.px, r.px, t.px + BORDER_WIDTH), color)
        fillRect(Rect(l.px, b.px - BORDER_WIDTH, r.px, b.px), color)
        fillRect(Rect(l.px, t.px, l.px + BORDER_WIDTH, b.px), color)
        fillRect(Rect(r.px - BORDER_WIDTH, t.px, r.px, b.px), color)
    }

    private fun gradientPaint(l: Float, t: Float, r: Float, b: Float, lt: Color, rt: Color, lb: Color, rb: Color): Paint {
        if (lt == rt && rt == lb && lb == rb) return lt.awt()
        if (lt == lb && rt == rb) return linear(l, t, r, t, lt, rt)
        if (lt == rt && lb == rb) return linear(l, t, l, b, lt, lb)
        // Four unrelated corners - a two-dimensional palette, say. Java2D has no bilinear gradient,
        // so the diagonal is used: its two ends are the exact colours and its midpoint is the mean
        // of the two corners the diagonal does not pass through.
        return LinearGradientPaint(
            Point2D.Float(l, t),
            Point2D.Float(r, b),
            floatArrayOf(0f, 0.5f, 1f),
            arrayOf(lt.awt(), rt.averageWith(lb).change(a = maxOf(rt.aInt, lb.aInt)).let { it.awt() }, rb.awt()),
        )
    }

    private fun linear(x0: Float, y0: Float, x1: Float, y1: Float, from: Color, to: Color): Paint {
        if (from == to) return from.awt()
        if (x0 == x1 && y0 == y1) return from.awt()
        return LinearGradientPaint(
            Point2D.Float(x0, y0),
            Point2D.Float(x1, y1),
            floatArrayOf(0f, 1f),
            arrayOf(from.awt(), to.awt()),
        )
    }

    /** One packed RGBA vertex colour per entry: the buffer the framework hands to [renderTriangles]. */
    private fun averageOf(vararg colors: Int): java.awt.Color {
        var a = 0
        var r = 0
        var g = 0
        var b = 0
        for (packed in colors) {
            val color = Color(packed)
            r += color.rInt
            g += color.gInt
            b += color.bInt
            a += color.aInt
        }
        val count = colors.size
        return java.awt.Color(r / count, g / count, b / count, a / count)
    }

    private fun averageOf(lt: Color, rt: Color, lb: Color, rb: Color): Color =
        lt.averageWith(rt).averageWith(lb.averageWith(rb))

    private fun Color.awt() = java.awt.Color(rInt, gInt, bInt, aInt)

    /**
     * The affine map that sends the three UV points to the three vertices, or `null` when the UV
     * triangle is degenerate and no such map exists.
     */
    private fun uvToUser(
        u0: Float, v0: Float, x0: Float, y0: Float,
        u1: Float, v1: Float, x1: Float, y1: Float,
        u2: Float, v2: Float, x2: Float, y2: Float,
    ): AffineTransform? {
        val determinant = u0 * (v1 - v2) - v0 * (u1 - u2) + (u1 * v2 - u2 * v1)
        if (determinant == 0f || !determinant.isFinite()) return null
        fun onX(a0: Float, a1: Float, a2: Float) = (a0 * (v1 - v2) - v0 * (a1 - a2) + (a1 * v2 - a2 * v1)) / determinant
        fun onY(a0: Float, a1: Float, a2: Float) = (u0 * (a1 - a2) - a0 * (u1 - u2) + (u1 * a2 - u2 * a1)) / determinant
        fun origin(a0: Float, a1: Float, a2: Float) =
            (u0 * (v1 * a2 - a1 * v2) - v0 * (u1 * a2 - a1 * u2) + a0 * (u1 * v2 - v1 * u2)) / determinant
        return AffineTransform(
            onX(x0, x1, x2).toDouble(), onX(y0, y1, y2).toDouble(),
            onY(x0, x1, x2).toDouble(), onY(y0, y1, y2).toDouble(),
            origin(x0, x1, x2).toDouble(), origin(y0, y1, y2).toDouble(),
        )
    }

    private val BORDER_WIDTH = 1.px

    private val BACKGROUND = Color(24, 24, 28)
    private val PANEL_TOP = Color(198, 198, 198)
    private val PANEL_BOTTOM = Color(160, 160, 160)
    private val PANEL_BORDER = Color(85, 85, 85)
    private val BUTTON = Color(160, 160, 160)
    private val BUTTON_HOVER = Color(200, 200, 200)
    private val BUTTON_BORDER = Color(70, 70, 70)
    private val DISABLED = Color(90, 90, 90)
    private val DISABLED_BORDER = Color(50, 50, 50)
    private val SLOT_INNER = Color(139, 139, 139)
    private val SLOT_BORDER = Color(55, 55, 55)
    private val TOOLTIP = Color(16, 0, 16)
    private val TOOLTIP_BORDER = Color(80, 0, 255)
    private val ITEM = Color(70, 70, 78)
    private val ITEM_BORDER = Color(120, 120, 130)
    private val DAMAGE = Color(0, 220, 0)
    private val ENCHANT_GLINT = Color(120, 60, 200, 96)
}
