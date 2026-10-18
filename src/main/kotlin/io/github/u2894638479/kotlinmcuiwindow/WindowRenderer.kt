package io.github.u2894638479.kotlinmcuiwindow

import io.github.u2894638479.kotlinmcui.backend.DslRootRenderer
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
import java.awt.Graphics2D
import java.awt.LinearGradientPaint
import java.awt.Paint
import java.awt.RenderingHints
import java.awt.TexturePaint
import java.awt.geom.AffineTransform
import java.awt.geom.Path2D
import java.awt.geom.Point2D
import java.awt.geom.Rectangle2D
import java.awt.image.BufferedImage
import kotlin.math.roundToInt

/**
 * Java2D implementation of the rendering contract.
 *
 * Everything the framework draws is a quad, and a quad is what Java2D fills best, so this backend is
 * "one [renderQuad] plus an approximation for whatever Java2D has no equivalent of". The
 * approximations are named where they are made:
 *
 * - **four independent corner colours** are one bilinear fill: two triangles would share a diagonal,
 *   and anti-aliased fills that only half cover it leave a line where the surface shows through,
 * - **per-vertex colour interpolation** is done by hand: the field through three colours is affine,
 *   and a two-by-two image filtered bilinearly reproduces an affine field exactly,
 * - **a perspective is projected by hand**: `AffineTransform` has six numbers and a projective third
 *   row has nowhere to go in it, so the corners of a quad or triangle are projected and filled flat,
 *   and anything that is not a quad or a triangle - text, above all - stays affine under it,
 * - **Minecraft's textures are never read**: the widget backgrounds and the item stand-in below are
 *   drawn from this backend's own palette, because a window that is not a game window has no
 *   resource pack to read them from.
 */
/** The resolution of a Gouraud texture: an affine field needs no more than a few samples per axis. */
private const val GOURAUD_SIZE = 32

internal class WindowRenderer : DslRootRenderer<WindowRenderParam> {
    /**
     * The surface the scale is derived from, set by the window's frame loop before every frame so it is
     * always the window's current size. Zero until the first paint, which the formula floors to 1.0.
     */
    internal var surfaceWidth: Int = 0
    internal var surfaceHeight: Int = 0

    /** The scale that makes a page's `.scaled` sizes come out at the current window's proportions. */
    override val guiScale: Double get() = guiScaleFor(surfaceWidth, surfaceHeight)

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
        val perspective = renderParam.perspective
        if (perspective != null) {
            // Projected by hand and filled flat: see the class comment. The corners come out in
            // perimeter order, which is what makes one path out of them.
            val matrix = graphics.transform
            val xs = FloatArray(4)
            val ys = FloatArray(4)
            putProjected(matrix, perspective, 0, l, t, xs, ys)
            putProjected(matrix, perspective, 1, r, t, xs, ys)
            putProjected(matrix, perspective, 2, r, b, xs, ys)
            putProjected(matrix, perspective, 3, l, b, xs, ys)
            fillPolygon(xs, ys, averageOf(lt, rt, lb, rb).awt())
            return
        }
        val texture = if (image == null || image.id.isEmpty() || uv == null) null else WindowTextures.get(image)
        val source = if (texture == null) null else uv?.bounds()
        if (texture == null || source == null) {
            val paint = gradientPaint(l, t, r, b, lt, rt, lb, rb)
            if (paint != null) {
                graphics.paint = paint
                graphics.fill(area)
            } else {
                // Four unrelated corners - a two-dimensional palette, say. One fill over the whole
                // rectangle: two triangles would share the diagonal, and Java2D anti-aliases each
                // fill on its own, so the two would cover half of its pixels each and leave the
                // surface behind showing through as a line.
                fillBilinear(graphics, l, t, r, b, lt, rt, lb, rb)
            }
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
        val texture = image?.takeIf { it.id.isNotEmpty() }?.let { WindowTextures.get(it) }
        val stride = if (texture == null) 2 else 4
        // A malformed list is a caller bug; drawing nothing beats drawing garbage.
        if (colors.size * stride != vertices.size || colors.size % 3 != 0) return
        val graphics = renderParam.graphics
        val perspective = renderParam.perspective
        var vertex = 0
        var color = 0
        while (vertex < vertices.size) {
            val x0 = vertices[vertex]
            val y0 = vertices[vertex + 1]
            val x1 = vertices[vertex + stride]
            val y1 = vertices[vertex + stride + 1]
            val x2 = vertices[vertex + stride * 2]
            val y2 = vertices[vertex + stride * 2 + 1]
            if (perspective != null) {
                val matrix = graphics.transform
                val xs = FloatArray(3)
                val ys = FloatArray(3)
                putProjected(matrix, perspective, 0, x0, y0, xs, ys)
                putProjected(matrix, perspective, 1, x1, y1, xs, ys)
                putProjected(matrix, perspective, 2, x2, y2, xs, ys)
                fillPolygon(xs, ys, averageOf(colors[color], colors[color + 1], colors[color + 2]))
            } else if (texture == null) {
                fillGouraud(graphics, x0, y0, x1, y1, x2, y2, colors[color], colors[color + 1], colors[color + 2])
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
        // One plate per (highlighted, active) combination: a button that cannot be pressed still has
        // to show whether the cursor is on it, and the page that exercises this backend draws all
        // four side by side.
        val surface = when {
            !active && highlighted -> DISABLED_HOVER
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
        val bounds = rect.bounds() ?: return
        val (l, t, r, b) = bounds
        val icon = WindowItems.get(item)
        if (icon != null) {
            // A window next to the game reads the icon out of the client jar, and that is the item.
            drawIcon(icon, l, t, r, b)
        } else {
            // No game to read from: an item is stood in for by a plate and its first letter rather
            // than by a wrong-looking sprite.
            renderQuad(rect, ITEM, ITEM, ITEM, ITEM)
            outline(rect, ITEM_BORDER)
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
        }
        val durability = damage
        if (durability != null) {
            val filled = ((r - l - 2f) * (1.0 - durability)).toFloat().coerceIn(0f, r - l - 2f)
            fillRect(Rect((l + 1f).px, (b - 3f).px, (l + 1f + filled).px, (b - 1f).px), DAMAGE)
        }
        if (count > 1) {
            // Every digit, right to left: a stack of 64 drawn as its last digit reads as 4.
            val digits = count.toString()
            digits.forEachIndexed { index, digit ->
                getFont().renderChar(
                    DslRenderableChar(digit.code, DslCharStyle(), Color.WHITE, 9.px),
                    (r - 6f - (digits.length - 1 - index) * 6f).px,
                    (b - 10f).px,
                    0.px,
                    0.px,
                )
            }
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
        val next = transform.run { WindowPerspective(m00, m01, m02, m10, m11, m12, m20, m21, m22) }
        // `AffineTransform` carries six numbers, so a projective third row has nowhere to go in it:
        // such a transform stays out of the graphics state and the renderer projects the corners
        // itself. An affine transform - the overwhelming majority - is handed to Java2D as before.
        if (!next.isAffine) {
            val previous = renderParam.perspective
            renderParam.perspective = previous?.concat(next) ?: next
            try {
                block()
            } finally {
                renderParam.perspective = previous
            }
            return
        }
        val graphics = renderParam.graphics
        val saved = graphics.transform
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

    override fun getFont(name: String?): DslFont<WindowRenderParam> = WindowCaches.fonts.getOrPut(name) { WindowFont(name) }


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

    /**
     * One corner of a projected shape: the perspective first, then the affine state the graphics is
     * already in, which is what puts the corner in device space.
     */
    private fun putProjected(
        matrix: AffineTransform,
        perspective: WindowPerspective,
        index: Int,
        x: Float,
        y: Float,
        xs: FloatArray,
        ys: FloatArray,
    ) {
        val point = Point2D.Float(perspective.projectX(x, y), perspective.projectY(x, y))
        matrix.transform(point, point)
        xs[index] = point.x
        ys[index] = point.y
    }

    /** Fill a shape whose corners are already in device space, with one flat colour. */
    context(renderParam: WindowRenderParam)
    private fun fillPolygon(xs: FloatArray, ys: FloatArray, color: java.awt.Color) {
        val graphics = renderParam.graphics
        val savedTransform = graphics.transform
        val savedPaint = graphics.paint
        // Device space already: the fill must not be transformed a second time.
        graphics.transform = AffineTransform()
        graphics.paint = color
        try {
            val path = Path2D.Float()
            path.moveTo(xs[0], ys[0])
            for (index in 1 until xs.size) path.lineTo(xs[index], ys[index])
            path.closePath()
            graphics.fill(path)
        } finally {
            graphics.paint = savedPaint
            graphics.transform = savedTransform
        }
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

    /**
     * Fill a rectangle with the bilinear field that runs through its four corner colours.
     *
     * One shape rather than two triangles: Java2D anti-aliases each fill separately, so two fills
     * sharing a diagonal cover only half of the pixels along it and whatever is behind shows through
     * as a line. A single fill has no internal edge at all, and the crease a two-triangle split
     * leaves is gone with it.
     */
    private fun fillBilinear(graphics: Graphics2D, l: Float, t: Float, r: Float, b: Float, lt: Color, rt: Color, lb: Color, rb: Color) {
        val stepX = (r - l) / (GOURAUD_SIZE - 2f)
        val stepY = (b - t) / (GOURAUD_SIZE - 2f)
        val originX = l - stepX
        val originY = t - stepY
        val pixels = gouraudPixels
        for (y in 0 until GOURAUD_SIZE) {
            // The margin is one texel wide, so the field is evaluated slightly outside the rectangle.
            val v = (originY + (y + 0.5f) * stepY - t) / (b - t)
            for (x in 0 until GOURAUD_SIZE) {
                val u = (originX + (x + 0.5f) * stepX - l) / (r - l)
                pixels[y * GOURAUD_SIZE + x] = bilinear(lt, rt, lb, rb, u, v)
            }
        }
        gouraudImage.setRGB(0, 0, GOURAUD_SIZE, GOURAUD_SIZE, pixels, 0, GOURAUD_SIZE)
        val savedPaint = graphics.paint
        val savedHint = graphics.getRenderingHint(RenderingHints.KEY_INTERPOLATION)
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        graphics.paint = TexturePaint(
            gouraudImage,
            Rectangle2D.Float(originX, originY, stepX * GOURAUD_SIZE, stepY * GOURAUD_SIZE),
        )
        graphics.fill(Rectangle2D.Float(l, t, r - l, b - t))
        graphics.paint = savedPaint
        graphics.setRenderingHint(
            RenderingHints.KEY_INTERPOLATION,
            savedHint ?: RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR,
        )
    }

    /** The bilinear field through four corner colours at ([u],[v]) of the rectangle. */
    private fun bilinear(lt: Color, rt: Color, lb: Color, rb: Color, u: Float, v: Float): Int {
        fun channel(a: Int, b: Int, c: Int, d: Int): Int {
            val top = a + u * (b - a)
            val bottom = c + u * (d - c)
            return (top + v * (bottom - top)).roundToInt().coerceIn(0, 255)
        }
        return Color(
            channel(lt.rInt, rt.rInt, lb.rInt, rb.rInt),
            channel(lt.gInt, rt.gInt, lb.gInt, rb.gInt),
            channel(lt.bInt, rt.bInt, lb.bInt, rb.bInt),
            channel(lt.aInt, rt.aInt, lb.aInt, rb.aInt),
        ).argbInt
    }

    /** Draw a decoded icon into the given bounds, scaled with nearest-neighbour so it stays crisp. */
    context(renderParam: WindowRenderParam)
    private fun drawIcon(icon: BufferedImage, l: Float, t: Float, r: Float, b: Float) {
        val graphics = renderParam.graphics
        val saved = graphics.getRenderingHint(RenderingHints.KEY_INTERPOLATION)
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR)
        graphics.drawImage(icon, l.toInt(), t.toInt(), (r - l).toInt(), (b - t).toInt(), null)
        graphics.setRenderingHint(
            RenderingHints.KEY_INTERPOLATION,
            saved ?: RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR,
        )
    }

    /** The exact paint for the corner combinations Java2D has a primitive for, or `null`. */
    private fun gradientPaint(l: Float, t: Float, r: Float, b: Float, lt: Color, rt: Color, lb: Color, rb: Color): Paint? {
        if (lt == rt && rt == lb && lb == rb) return lt.awt()
        if (lt == lb && rt == rb) return linear(l, t, r, t, lt, rt)
        if (lt == rt && lb == rb) return linear(l, t, l, b, lt, lb)
        return null
    }

    /**
     * The image a Gouraud field is painted through, and the buffer it is evaluated into. One image is
     * reused: only one field is painted at a time, and painting only happens on the window's thread.
     */
    private val gouraudImage = BufferedImage(GOURAUD_SIZE, GOURAUD_SIZE, BufferedImage.TYPE_INT_ARGB)
    private val gouraudPixels = IntArray(GOURAUD_SIZE * GOURAUD_SIZE)

    /**
     * Fill a triangle with the affine colour field that runs through its three vertex colours.
     *
     * Java2D has no Gouraud shading, but the field through three colours is affine, and that is
     * exactly what a two-by-two image filtered bilinearly reproduces: its four texels are the field's
     * values at the corners of the triangle's bounding box, so everything between them interpolates
     * the same plane. A four-corner quad is two of these.
     */
    private fun fillGouraud(
        graphics: Graphics2D,
        x0: Float, y0: Float, x1: Float, y1: Float, x2: Float, y2: Float,
        c0: Int, c1: Int, c2: Int,
    ) {
        val dx1 = x1 - x0
        val dy1 = y1 - y0
        val dx2 = x2 - x0
        val dy2 = y2 - y0
        val area = dx1 * dy2 - dx2 * dy1
        val left = minOf(x0, x1, x2)
        val top = minOf(y0, y1, y2)
        val right = maxOf(x0, x1, x2)
        val bottom = maxOf(y0, y1, y2)
        if (area == 0f || !area.isFinite() || right <= left || bottom <= top) {
            graphics.color = averageOf(c0, c1, c2)
            graphics.fill(triangle(x0, y0, x1, y1, x2, y2))
            return
        }
        val color0 = Color(c0)
        val color1 = Color(c1)
        val color2 = Color(c2)
        // An affine field is fully described by a small image of it: bilinear filtering between the
        // samples of an affine function gives that function back. The image is anchored one texel
        // wider than the triangle on each side, because a texture tiles - without the margin the
        // filter would reach into the neighbouring copy at the edges.
        val stepX = (right - left) / (GOURAUD_SIZE - 2f)
        val stepY = (bottom - top) / (GOURAUD_SIZE - 2f)
        val originX = left - stepX
        val originY = top - stepY
        val pixels = gouraudPixels
        for (y in 0 until GOURAUD_SIZE) {
            val py = originY + (y + 0.5f) * stepY - y0
            for (x in 0 until GOURAUD_SIZE) {
                val px = originX + (x + 0.5f) * stepX - x0
                // The barycentric weights of the sample inside the vertex triangle: the colour field
                // is the same plane, so extrapolating with them is the field, not an approximation.
                val u1 = (px * dy2 - py * dx2) / area
                val u2 = (py * dx1 - px * dy1) / area
                pixels[y * GOURAUD_SIZE + x] = affine(color0, color1, color2, u1, u2)
            }
        }
        gouraudImage.setRGB(0, 0, GOURAUD_SIZE, GOURAUD_SIZE, pixels, 0, GOURAUD_SIZE)
        val savedPaint = graphics.paint
        val savedHint = graphics.getRenderingHint(RenderingHints.KEY_INTERPOLATION)
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        graphics.paint = TexturePaint(
            gouraudImage,
            Rectangle2D.Float(originX, originY, stepX * GOURAUD_SIZE, stepY * GOURAUD_SIZE),
        )
        graphics.fill(triangle(x0, y0, x1, y1, x2, y2))
        graphics.paint = savedPaint
        graphics.setRenderingHint(
            RenderingHints.KEY_INTERPOLATION,
            savedHint ?: RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR,
        )
    }

    /** The affine field through three colours, at the point with barycentric weights [u1] and [u2]. */
    private fun affine(c0: Color, c1: Color, c2: Color, u1: Float, u2: Float): Int {
        fun channel(v0: Int, v1: Int, v2: Int) = (v0 + u1 * (v1 - v0) + u2 * (v2 - v0)).roundToInt().coerceIn(0, 255)
        // ARGB, because the texels go into a Java2D image; the framework packs the other way round.
        return Color(
            channel(c0.rInt, c1.rInt, c2.rInt),
            channel(c0.gInt, c1.gInt, c2.gInt),
            channel(c0.bInt, c1.bInt, c2.bInt),
            channel(c0.aInt, c1.aInt, c2.aInt),
        ).argbInt
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
    private val DISABLED_HOVER = Color(130, 130, 130)
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
