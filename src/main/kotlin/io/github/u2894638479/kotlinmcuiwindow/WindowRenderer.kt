package io.github.u2894638479.kotlinmcuiwindow

import io.github.u2894638479.kotlinmcui.backend.DslRootRenderer
import io.github.u2894638479.kotlinmcui.backend.fillRect
import io.github.u2894638479.kotlinmcui.context.DslScaleContext
import io.github.u2894638479.kotlinmcui.image.ImageHolder
import io.github.u2894638479.kotlinmcui.math.Color
import io.github.u2894638479.kotlinmcui.math.ColorArray
import io.github.u2894638479.kotlinmcui.math.Measure
import io.github.u2894638479.kotlinmcui.math.MeasureArray
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
import java.awt.Shape
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
 *   and anything that is not a quad or a triangle - text, above all - stays affine under it. A
 *   textured shape under a perspective keeps the draw's own UV range, mapped affinely onto the
 *   projected geometry; its correspondence to the corners is approximate and not per-pixel, but it
 *   is still the requested texture, never a stretched whole-image replacement,
 * - **Minecraft's textures are never read**: the widget backgrounds and the item stand-in below are
 *   drawn from this backend's own palette, because a window that is not a game window has no
 *   resource pack to read them from.
 *
 * The primitive overloads take the current typed input: colour-only quads have no image or UV,
 * textured quads require both, and triangle lists are either `x, y` or `x, y, u, v` per vertex.
 * Their structure and numeric validation happens here, before any texture lookup. A texture that
 * is still loading draws nothing; one whose source is confirmed unusable is drawn as the backend's
 * missing texture through the same geometry, UV and tint, never as a flat colour or untextured draw.
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
    override fun renderQuad(rect: Rect, lt: Color, rt: Color, lb: Color, rb: Color) {
        val bounds = rect.drawableBounds() ?: return
        paintColoredQuad(bounds, lt, rt, lb, rb)
    }

    context(renderParam: WindowRenderParam)
    override fun renderQuad(
        rect: Rect,
        lt: Color,
        rt: Color,
        lb: Color,
        rb: Color,
        image: ImageHolder,
        uv: Rect,
    ) {
        val bounds = rect.drawableBounds() ?: return
        // An empty holder means "nothing yet": no draw, and no texture lookup or fallback format.
        if (image.source == null) return
        if (!image.hasDrawableSize()) return
        val source = uv.bounds() ?: return
        val (u0, v0, u1, v1) = source
        if (u1 <= u0 || v1 <= v0) return
        // Classified before the lookup: a failure confirmed on an earlier frame draws the missing
        // texture, while a read this call starts is still pending for this frame - even when a test
        // runs the read inline and it has already recorded its failure by the time the call returns.
        val missing = WindowTextures.isMissing(image)
        val texture = WindowTextures.ready(image)
        if (texture == null && !missing) return
        val sampled = texture ?: WindowTextures.placeholder
        // UV is in the holder's nominal pixels for a ready texture as much as for the placeholder:
        // a decoded 2-pixel image declared 16 wide fills the same 16-unit range instead of tiling.
        // Double keeps a tiny-but-valid nominal size from overflowing the scale to infinity.
        val uScale = sampled.width.toDouble() / image.width.raw
        val vScale = sampled.height.toDouble() / image.height.raw
        val (l, t, r, b) = bounds
        val graphics = renderParam.graphics
        val tint = averageOf(lt, rt, lb, rb)
        val perspective = renderParam.perspective
        if (perspective != null) {
            // Projected by hand: `AffineTransform` has nowhere to put a third row. The original UV
            // range is still mapped over the projected shape's bounds, so two draws that differ only
            // in UV sample different texture; the correspondence stays affine, not per-pixel.
            val matrix = graphics.transform
            val xs = FloatArray(4)
            val ys = FloatArray(4)
            putProjected(matrix, perspective, 0, l, t, xs, ys)
            putProjected(matrix, perspective, 1, r, t, xs, ys)
            putProjected(matrix, perspective, 2, r, b, xs, ys)
            putProjected(matrix, perspective, 3, l, b, xs, ys)
            paintProjectedTexture(graphics, xs, ys, u0, v0, u1, v1, sampled, uScale, vScale, tint)
            return
        }
        paintTexturedQuad(graphics, l, t, r, b, u0, v0, u1, v1, sampled, uScale, vScale, tint)
    }

    /**
     * Paint [texture] over the quad [l,t,r,b] with UV [u0,v0,u1,v1] scaled by [uScale]/[vScale].
     *
     * UV is mapped affinely onto the quad and the paint covers the full image across one scaled
     * image extent, so a negative or past-the-edge UV repeats the texture instead of being clamped
     * or silently replaced. The scale is double precision because the UV and the nominal size may
     * be tiny; the product still lands on image pixels instead of overflowing through `Float`.
     */
    private fun paintTexturedQuad(
        graphics: Graphics2D,
        l: Float, t: Float, r: Float, b: Float,
        u0: Float, v0: Float, u1: Float, v1: Float,
        texture: BufferedImage,
        uScale: Double,
        vScale: Double,
        tint: Color,
    ) {
        val x0 = u0.toDouble() * uScale
        val y0 = v0.toDouble() * vScale
        val x1 = u1.toDouble() * uScale
        val y1 = v1.toDouble() * vScale
        val width = x1 - x0
        val height = y1 - y0
        if (width <= 0.0 || height <= 0.0 || !width.isFinite() || !height.isFinite()) return
        val sx = (r - l).toDouble() / width
        val sy = (b - t).toDouble() / height
        if (!sx.isFinite() || !sy.isFinite()) return
        val mapping = AffineTransform(
            sx, 0.0, 0.0, sy,
            (l - x0 * sx), (t - y0 * sy),
        )
        val savedTransform = graphics.transform
        graphics.transform(mapping)
        try {
            paintTexture(
                graphics,
                Rectangle2D.Double(x0, y0, width, height),
                texture,
                Rectangle2D.Double(0.0, 0.0, texture.width.toDouble(), texture.height.toDouble()),
                tint,
            )
        } finally {
            graphics.transform = savedTransform
        }
    }

    /**
     * Paint [texture] across the already-projected corners [xs]/[ys], using the original [u0,v0,u1,v1].
     *
     * The shape is the exact projected geometry, but Java2D cannot carry a projective texture map,
     * so the UV range is mapped onto the shape's bounding box and clipped by the projected outline:
     * real texture pixels chosen by the draw's own UV, an approximate (affine, not per-pixel)
     * correspondence between them and the corners.
     */
    private fun paintProjectedTexture(
        graphics: Graphics2D,
        xs: FloatArray,
        ys: FloatArray,
        u0: Float, v0: Float, u1: Float, v1: Float,
        texture: BufferedImage,
        uScale: Double,
        vScale: Double,
        tint: Color,
    ) {
        val x0 = u0.toDouble() * uScale
        val y0 = v0.toDouble() * vScale
        val x1 = u1.toDouble() * uScale
        val y1 = v1.toDouble() * vScale
        val sourceWidth = x1 - x0
        val sourceHeight = y1 - y0
        if (sourceWidth <= 0.0 || sourceHeight <= 0.0 || !sourceWidth.isFinite() || !sourceHeight.isFinite()) return
        var minX = xs[0]
        var maxX = xs[0]
        var minY = ys[0]
        var maxY = ys[0]
        for (index in 1 until xs.size) {
            minX = minOf(minX, xs[index])
            maxX = maxOf(maxX, xs[index])
            minY = minOf(minY, ys[index])
            maxY = maxOf(maxY, ys[index])
        }
        if (maxX <= minX || maxY <= minY) return
        val sx = (maxX - minX).toDouble() / sourceWidth
        val sy = (maxY - minY).toDouble() / sourceHeight
        if (!sx.isFinite() || !sy.isFinite()) return
        val savedTransform = graphics.transform
        // Device space already: the fill must not be transformed a second time.
        graphics.transform = AffineTransform()
        try {
            paintTexture(
                graphics,
                polygon(xs, ys),
                texture,
                // Where image pixel (0,0) lands when the scaled UV range fills the projected bounds.
                Rectangle2D.Double(
                    minX - x0 * sx,
                    minY - y0 * sy,
                    texture.width * sx,
                    texture.height * sy,
                ),
                tint,
            )
        } finally {
            graphics.transform = savedTransform
        }
    }

    /**
     * Fill [shape] with [texture] tinted by [tint], anchored at [anchor].
     *
     * The tint's RGB multiplies the texture's RGB per texel, and only the texture's pixels are
     * touched: a transparent texel stays transparent instead of painting the tint over whatever was
     * already in the destination. The tint's alpha scales the whole texture once, through the
     * composite of the single `SRC_OVER` fill. A white tint needs no texel work at all, so it keeps
     * the direct `TexturePaint` fast path.
     */
    private fun paintTexture(graphics: Graphics2D, shape: Shape, image: BufferedImage, anchor: Rectangle2D, tint: Color) {
        // A fully transparent tint contributes nothing, and copying the texture for it would be
        // wasted work as well as a needless allocation.
        if (tint.aInt == 0) return
        val source = if (tint.isWhiteRgb) image else tintedTexture(image, tint)
        val savedComposite = graphics.composite
        val savedPaint = graphics.paint
        try {
            graphics.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, tint.aFloat)
            graphics.paint = TexturePaint(source, anchor)
            graphics.fill(shape)
        } finally {
            graphics.composite = savedComposite
            graphics.paint = savedPaint
        }
    }

    /** Whether [tint]'s colour is white, so multiplying by it leaves every RGB channel unchanged. */
    private val Color.isWhiteRgb: Boolean
        get() = rInt == 255 && gInt == 255 && bInt == 255

    /**
     * A copy of [image]'s RGB channels multiplied by [tint]'s, its alpha untouched.
     *
     * One entry only: the copy is reused while the same image is drawn with the same tint colour at
     * the same size. Tints alternate far less often than frames do, and a texture is usually drawn
     * many times before it changes, so a single entry removes the repeat work without turning into
     * an unbounded cache of tinted copies. Painting is confined to one thread, so the entry and its
     * pixel buffer need no locking.
     */
    private fun tintedTexture(image: BufferedImage, tint: Color): BufferedImage {
        val key = (tint.rInt shl 16) or (tint.gInt shl 8) or tint.bInt
        val cached = tintedImage
        if (cached != null && tintedSource === image && tintedKey == key &&
            cached.width == image.width && cached.height == image.height
        ) {
            return cached
        }
        val target = if (cached != null && cached.width == image.width && cached.height == image.height) {
            cached
        } else {
            BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_ARGB)
        }
        val count = image.width * image.height
        if (tintedPixels.size != count) tintedPixels = IntArray(count)
        val pixels = tintedPixels
        image.getRGB(0, 0, image.width, image.height, pixels, 0, image.width)
        val tintR = tint.rInt
        val tintG = tint.gInt
        val tintB = tint.bInt
        for (index in 0 until count) {
            val argb = pixels[index]
            val alpha = argb ushr 24 and 0xFF
            val r = ((argb shr 16 and 0xFF) * tintR) / 255
            val g = ((argb shr 8 and 0xFF) * tintG) / 255
            val b = ((argb and 0xFF) * tintB) / 255
            // Alpha is carried over untouched: only the composite scales it, exactly once.
            pixels[index] = (alpha shl 24) or (r shl 16) or (g shl 8) or b
        }
        target.setRGB(0, 0, image.width, image.height, pixels, 0, image.width)
        tintedImage = target
        tintedSource = image
        tintedKey = key
        return target
    }

    /** Paint the colour-only quad whose validated float bounds are [bounds]. */
    context(renderParam: WindowRenderParam)
    private fun paintColoredQuad(bounds: FloatArray, lt: Color, rt: Color, lb: Color, rb: Color) {
        val (l, t, r, b) = bounds
        val graphics = renderParam.graphics
        val perspective = renderParam.perspective
        if (perspective != null) {
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
        val paint = gradientPaint(l, t, r, b, lt, rt, lb, rb)
        if (paint != null) {
            graphics.paint = paint
            graphics.fill(Rectangle2D.Float(l, t, r - l, b - t))
        } else {
            // Four unrelated corners - a two-dimensional palette, say. One fill over the whole
            // rectangle: two triangles would share the diagonal, and Java2D anti-aliases each
            // fill on its own, so the two would cover half of its pixels each and leave the
            // surface behind showing through as a line.
            fillBilinear(graphics, l, t, r, b, lt, rt, lb, rb)
        }
    }

    context(renderParam: WindowRenderParam)
    override fun renderTriangles(vertices: MeasureArray, colors: ColorArray) {
        requireTriangleStructure(vertices.size, colors.size, XY_STRIDE)
        if (colors.size == 0) return
        if (!vertices.allDrawable()) return
        paintTriangles(vertices, colors, XY_STRIDE, null, 1.0, 1.0)
    }

    context(renderParam: WindowRenderParam)
    override fun renderTriangles(vertices: MeasureArray, colors: ColorArray, image: ImageHolder) {
        requireTriangleStructure(vertices.size, colors.size, XYUV_STRIDE)
        // Empty input is a valid no-op even when the image is empty; the structure check above is
        // the only thing that may throw.
        if (colors.size == 0) return
        // Numeric validation comes before the resource is examined, so a bad list never starts a
        // read or leaves a partially drawn list behind.
        if (!vertices.allDrawable()) return
        if (image.source == null) return
        if (!image.hasDrawableSize()) return
        // Readiness is classified before the lookup: the missing texture is sampled through the same
        // four-measure geometry, never replaced by a flat averaged colour, while a read this call
        // starts stays pending for this frame. A warm resolve returns the cached image directly.
        val missing = WindowTextures.isMissing(image)
        val texture = WindowTextures.ready(image)
        if (texture == null && !missing) return
        val sampled = texture ?: WindowTextures.placeholder
        // The holder's nominal size is the texture's full UV range whether it decoded or is the
        // placeholder; double precision keeps tiny nominal sizes from becoming a non-finite scale.
        val uScale = sampled.width.toDouble() / image.width.raw
        val vScale = sampled.height.toDouble() / image.height.raw
        paintTriangles(vertices, colors, XYUV_STRIDE, sampled, uScale, vScale)
    }

    /**
     * Paint a structurally and numerically validated triangle list.
     *
     * [texture] is `null` only for the untextured overload; the stride is already fixed by then, so
     * a missing or failed texture can never change the format. [uScale]/[vScale] are the UV-to-pixel
     * scale of the image that is actually sampled.
     */
    context(renderParam: WindowRenderParam)
    private fun paintTriangles(
        vertices: MeasureArray,
        colors: ColorArray,
        stride: Int,
        texture: BufferedImage?,
        uScale: Double,
        vScale: Double,
    ) {
        val graphics = renderParam.graphics
        val perspective = renderParam.perspective
        var vertex = 0
        var color = 0
        while (vertex < vertices.size) {
            val x0 = vertices[vertex].drawFloat()
            val y0 = vertices[vertex + 1].drawFloat()
            val x1 = vertices[vertex + stride].drawFloat()
            val y1 = vertices[vertex + stride + 1].drawFloat()
            val x2 = vertices[vertex + stride * 2].drawFloat()
            val y2 = vertices[vertex + stride * 2 + 1].drawFloat()
            val c0 = colors[color].rgbaInt
            val c1 = colors[color + 1].rgbaInt
            val c2 = colors[color + 2].rgbaInt
            if (texture == null) {
                if (perspective != null) {
                    val matrix = graphics.transform
                    val xs = FloatArray(3)
                    val ys = FloatArray(3)
                    putProjected(matrix, perspective, 0, x0, y0, xs, ys)
                    putProjected(matrix, perspective, 1, x1, y1, xs, ys)
                    putProjected(matrix, perspective, 2, x2, y2, xs, ys)
                    fillPolygon(xs, ys, averageOf(c0, c1, c2))
                } else {
                    fillGouraud(graphics, x0, y0, x1, y1, x2, y2, c0, c1, c2)
                }
            } else {
                val u0 = vertices[vertex + 2].drawFloat().toDouble() * uScale
                val v0 = vertices[vertex + 3].drawFloat().toDouble() * vScale
                val u1 = vertices[vertex + stride + 2].drawFloat().toDouble() * uScale
                val v1 = vertices[vertex + stride + 3].drawFloat().toDouble() * vScale
                val u2 = vertices[vertex + stride * 2 + 2].drawFloat().toDouble() * uScale
                val v2 = vertices[vertex + stride * 2 + 3].drawFloat().toDouble() * vScale
                val tint = average(Color(c0), Color(c1), Color(c2))
                if (perspective != null) {
                    val matrix = graphics.transform
                    val xs = FloatArray(3)
                    val ys = FloatArray(3)
                    putProjected(matrix, perspective, 0, x0, y0, xs, ys)
                    putProjected(matrix, perspective, 1, x1, y1, xs, ys)
                    putProjected(matrix, perspective, 2, x2, y2, xs, ys)
                    paintTexturedTriangle(
                        graphics, xs[0].toDouble(), ys[0].toDouble(), xs[1].toDouble(), ys[1].toDouble(),
                        xs[2].toDouble(), ys[2].toDouble(),
                        u0, v0, u1, v1, u2, v2, texture, tint, device = true,
                    )
                } else {
                    paintTexturedTriangle(
                        graphics, x0.toDouble(), y0.toDouble(), x1.toDouble(), y1.toDouble(),
                        x2.toDouble(), y2.toDouble(),
                        u0, v0, u1, v1, u2, v2, texture, tint, device = false,
                    )
                }
            }
            vertex += stride * 3
            color += 3
        }
    }

    /**
     * Paint [texture] across one triangle, with UV at its corners.
     *
     * [device] is `true` when the corners are already projected, in which case the affine map that
     * sends UV to them replaces the current transform rather than being concatenated onto it. The
     * map is affine over the projected triangle, so perspective correction stays deferred. A
     * degenerate UV triangle has no such map, so the texture is read at its first UV instead of
     * falling back to a palette colour.
     */
    private fun paintTexturedTriangle(
        graphics: Graphics2D,
        x0: Double, y0: Double, x1: Double, y1: Double, x2: Double, y2: Double,
        u0: Double, v0: Double, u1: Double, v1: Double, u2: Double, v2: Double,
        texture: BufferedImage,
        tint: Color,
        device: Boolean,
    ) {
        val mapping = uvToUser(u0, v0, x0, y0, u1, v1, x1, y1, u2, v2, x2, y2)
        if (mapping == null) {
            val savedTransform = graphics.transform
            // Device space already: filling through the current transform would place it twice.
            if (device) graphics.transform = AffineTransform()
            try {
                graphics.color = textureColor(texture, u0, v0, tint)
                graphics.fill(triangle(x0, y0, x1, y1, x2, y2))
            } finally {
                graphics.transform = savedTransform
            }
            return
        }
        val savedTransform = graphics.transform
        if (device) graphics.transform = mapping else graphics.transform(mapping)
        try {
            paintTexture(
                graphics,
                triangle(u0, v0, u1, v1, u2, v2),
                texture,
                Rectangle2D.Double(0.0, 0.0, texture.width.toDouble(), texture.height.toDouble()),
                tint,
            )
        } finally {
            graphics.transform = savedTransform
        }
    }

    /**
     * One texture pixel at [u], [v], tinted by [tint]; used where no affine map exists.
     *
     * Out-of-range UV wraps, and a negative coordinate is floored rather than truncated so `-0.5`
     * samples the same wrapped texel the texture tiling would. RGB is multiplied by the tint's RGB
     * and alpha scaled by the tint's alpha once, exactly like the textured fill path.
     */
    private fun textureColor(texture: BufferedImage, u: Double, v: Double, tint: Color): java.awt.Color {
        val argb = texture.getRGB(
            Math.floorMod(Math.floor(u).toInt(), texture.width),
            Math.floorMod(Math.floor(v).toInt(), texture.height),
        )
        val r = ((argb shr 16 and 0xFF) * tint.rInt) / 255
        val g = ((argb shr 8 and 0xFF) * tint.gInt) / 255
        val b = ((argb and 0xFF) * tint.bInt) / 255
        return java.awt.Color(r, g, b, ((argb ushr 24) and 0xFF) * tint.aInt / 255)
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


    /** The four edges of [rect] as floats, or `null` when any edge is unmeasured or overflows. */
    private fun Rect.bounds(): FloatArray? {
        // Primitive checks first: nothing is converted unless every component would survive it, so a
        // whole call stays atomic without a boxed value per component.
        if (!left.isDrawable() || !top.isDrawable() || !right.isDrawable() || !bottom.isDrawable()) return null
        return floatArrayOf(left.drawFloat(), top.drawFloat(), right.drawFloat(), bottom.drawFloat())
    }

    /** The bounds of a rectangle that is actually drawable: finite floats and a positive area. */
    private fun Rect.drawableBounds(): FloatArray? {
        val bounds = bounds() ?: return null
        // Finite edges are not enough: two opposite edges at `Float.MAX_VALUE` subtract to an
        // infinite extent, and the contract requires a finite positive destination size. Each
        // extent is computed once, at the width and height it will be filled at, so the check is
        // the same one the fill performs rather than an order comparison that can agree with it.
        val width = bounds[2] - bounds[0]
        val height = bounds[3] - bounds[1]
        if (!width.isFinite() || !height.isFinite()) return null
        return if (width > 0f && height > 0f) bounds else null
    }

    /** Whether a measure can be consumed as a backend float: measured, finite, and still finite as `Float`. */
    private fun Measure.isDrawable(): Boolean = isFinite && raw.toFloat().isFinite()

    /** Only called after [isDrawable] proved the value survives the read. */
    private fun Measure.drawFloat(): Float = raw.toFloat()

    /** Whether every measure in the list is a finite value that survives conversion to `Float`. */
    private fun MeasureArray.allDrawable(): Boolean {
        var index = 0
        while (index < size) {
            if (!this[index].isDrawable()) return false
            index++
        }
        return true
    }

    /** A texture needs a positive, finite nominal size even before its pixels are ready. */
    private fun ImageHolder.hasDrawableSize(): Boolean =
        width.isDrawable() && height.isDrawable() && width.drawFloat() > 0f && height.drawFloat() > 0f

    /**
     * Check the triangle list's shape by division and remainder, never by a multiplication that
     * could overflow. A malformed call is a caller bug, so it throws before any resource lookup.
     */
    private fun requireTriangleStructure(vertexCount: Int, colorCount: Int, stride: Int) {
        require(colorCount % 3 == 0) {
            "triangle colours must come in threes, got $colorCount"
        }
        require(vertexCount % stride == 0 && vertexCount / stride == colorCount) {
            "expected $colorCount vertices at stride $stride, got $vertexCount measures"
        }
    }

    private fun triangle(x0: Float, y0: Float, x1: Float, y1: Float, x2: Float, y2: Float): Path2D.Float =
        Path2D.Float().apply {
            moveTo(x0, y0)
            lineTo(x1, y1)
            lineTo(x2, y2)
            closePath()
        }

    /** The closed path through the points in [xs]/[ys], in the order given. */
    private fun polygon(xs: FloatArray, ys: FloatArray): Path2D.Float = Path2D.Float().apply {
        moveTo(xs[0], ys[0])
        for (index in 1 until xs.size) lineTo(xs[index], ys[index])
        closePath()
    }

    /** A triangle whose UV coordinates may exceed `Float` after scaling. */
    private fun triangle(x0: Double, y0: Double, x1: Double, y1: Double, x2: Double, y2: Double): Path2D.Double =
        Path2D.Double().apply {
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
     * The single tinted source image [paintTexture] reuses, the image and tint colour it was made
     * from, and the scratch buffer the tint is applied into. Bounded by construction: at most one
     * tinted copy exists at a time.
     */
    private var tintedImage: BufferedImage? = null
    private var tintedSource: BufferedImage? = null
    private var tintedKey: Int = 0
    private var tintedPixels: IntArray = IntArray(0)

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

    /** The mean of three vertex colours: the tint a textured triangle multiplies its texture by. */
    private fun average(c0: Color, c1: Color, c2: Color): Color = Color(
        (c0.rInt + c1.rInt + c2.rInt) / 3,
        (c0.gInt + c1.gInt + c2.gInt) / 3,
        (c0.bInt + c1.bInt + c2.bInt) / 3,
        (c0.aInt + c1.aInt + c2.aInt) / 3,
    )

    private fun Color.awt() = java.awt.Color(rInt, gInt, bInt, aInt)

    /**
     * The affine map that sends the three UV points to the three vertices, or `null` when the UV
     * triangle is degenerate and no such map exists.
     *
     * Double throughout: a scaled UV may be far outside `Float` even though the input measures were
     * finite, and the map itself only has to stay a valid affine transform.
     */
    private fun uvToUser(
        u0: Double, v0: Double, x0: Double, y0: Double,
        u1: Double, v1: Double, x1: Double, y1: Double,
        u2: Double, v2: Double, x2: Double, y2: Double,
    ): AffineTransform? {
        val determinant = u0 * (v1 - v2) - v0 * (u1 - u2) + (u1 * v2 - u2 * v1)
        if (determinant == 0.0 || !determinant.isFinite()) return null
        fun onX(a0: Double, a1: Double, a2: Double) =
            (a0 * (v1 - v2) - v0 * (a1 - a2) + (a1 * v2 - a2 * v1)) / determinant
        fun onY(a0: Double, a1: Double, a2: Double) =
            (u0 * (a1 - a2) - a0 * (u1 - u2) + (u1 * a2 - u2 * a1)) / determinant
        fun origin(a0: Double, a1: Double, a2: Double) =
            (u0 * (v1 * a2 - a1 * v2) - v0 * (u1 * a2 - a1 * u2) + a0 * (u1 * v2 - v1 * u2)) / determinant
        return AffineTransform(
            onX(x0, x1, x2), onX(y0, y1, y2),
            onY(x0, x1, x2), onY(y0, y1, y2),
            origin(x0, x1, x2), origin(y0, y1, y2),
        )
    }

    /** Measures per vertex: `x, y` alone, or `x, y, u, v` when textured. */
    private val XY_STRIDE = 2
    private val XYUV_STRIDE = 4

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
