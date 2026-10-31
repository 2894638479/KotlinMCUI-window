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
import java.awt.Paint
import java.awt.RenderingHints
import java.awt.Shape
import java.awt.geom.AffineTransform
import java.awt.geom.Area
import java.awt.geom.Path2D
import java.awt.geom.Rectangle2D
import java.awt.image.BufferedImage
import kotlin.math.ceil
import kotlin.math.floor

/**
 * Java2D implementation of the rendering contract.
 *
 * Primitives carry a colour field, not a flat colour: a quad's four corners define one bilinear
 * field over its original local rectangle, and a triangle list defines one barycentric field per
 * triangle. [WindowFieldPaint] answers a quad's fill per device pixel from the inverse of the full
 * local-to-device matrix, so an affine or positive-`w` projective transform still samples the
 * draw's original local coordinates. A triangle list is rasterised jointly instead, because
 * adjacent triangles share edges: the top-left half-open rule gives each sample one owner, which
 * removes the internal seam, while a 4x4 subpixel mask keeps anti-aliased outer edges and real
 * overlapping areas still composite in supplied order.
 *
 * Java2D cannot carry a projective third row in `AffineTransform`, so a projective scope is kept in
 * the render parameter and composed with the graphics state; an affine scope nested inside one is
 * folded into the pending matrix so the composition order is preserved. Text and everything that is
 * not a quad or a triangle stays affine under a perspective transform.
 *
 * Minecraft's textures are never read: the widget backgrounds and the item stand-in are drawn from
 * this backend's own palette, because a window that is not a game window has no resource pack to
 * read them from. The primitive overloads take typed input: colour-only quads have no image or UV,
 * textured quads require both, and triangle lists are either `x, y` or `x, y, u, v` per vertex.
 * Their structure and numeric validation happens here, before any texture lookup. A texture that is
 * still loading draws nothing; one whose source is confirmed unusable is drawn as the backend's
 * missing texture through the same geometry, UV and tint, never as a flat colour or untextured draw.
 */
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
        paintQuad(bounds, lt, rt, lb, rb, null, 0.0, 0.0, 0.0, 0.0)
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
        val texX0 = u0.toDouble() * uScale
        val texY0 = v0.toDouble() * vScale
        val texX1 = u1.toDouble() * uScale
        val texY1 = v1.toDouble() * vScale
        if (!texX0.isFinite() || !texY0.isFinite() || !texX1.isFinite() || !texY1.isFinite()) return
        paintQuad(bounds, lt, rt, lb, rb, sampled, texX0, texY0, texX1, texY1)
    }

    /**
     * Paint a quad's one bilinear field over [bounds], sampling [texture] when one is given.
     *
     * The field lives in the original, untransformed rectangle: [QuadFieldPaint] maps every device
     * pixel back through the inverse of the full local-to-device matrix, so a rotation, a shear or a
     * projective transform all evaluate the same `u`, `v`. The shape Java2D fills is the projected
     * rectangle in user space, which keeps its point-in-shape test, clip and outer anti-aliasing; the
     * paint only supplies the sample. One fill means no shared diagonal, no doubled coverage and no
     * internal seam.
     */
    context(renderParam: WindowRenderParam)
    private fun paintQuad(
        bounds: FloatArray,
        lt: Color,
        rt: Color,
        lb: Color,
        rb: Color,
        texture: BufferedImage?,
        texX0: Double,
        texY0: Double,
        texX1: Double,
        texY1: Double,
    ) {
        val (l, t, r, b) = bounds
        val graphics = renderParam.graphics
        // A constant field is just the colour: one source-over fill is byte-for-byte what the paint
        // would produce per pixel, so the common widget plate (and the whole-window background) does
        // not need a raster at all. Under a perspective the geometry still has to be projected, so
        // the general path keeps it.
        if (texture == null && renderParam.perspective == null && lt == rt && rt == lb && lb == rb) {
            fillWithPaint(graphics, Rectangle2D.Float(l, t, r - l, b - t), lt.awt())
            return
        }
        val perspective = renderParam.perspective?.let { matrixOf(it) }
        val inverse = invert(deviceMatrix(graphics.transform, perspective)) ?: return
        val width = (r - l).toDouble()
        val height = (b - t).toDouble()
        val corners = FloatArray(16)
        premultiply(lt, corners, 0)
        premultiply(rt, corners, 4)
        premultiply(lb, corners, 8)
        premultiply(rb, corners, 12)
        val shape = projectedPolygon(
            perspective,
            doubleArrayOf(l.toDouble(), r.toDouble(), r.toDouble(), l.toDouble()),
            doubleArrayOf(t.toDouble(), t.toDouble(), b.toDouble(), b.toDouble()),
        ) ?: return
        fillWithPaint(
            graphics,
            shape,
            QuadFieldPaint(
                inverse,
                l.toDouble(), t.toDouble(),
                1.0 / width, 1.0 / height,
                corners, texture, texX0, texY0, texX1, texY1,
            ),
        )
    }

    /** A straight corner colour as the premultiplied floats the field is evaluated in. */
    private fun premultiply(color: Color, out: FloatArray, offset: Int) {
        val alpha = color.aInt / 255f
        out[offset] = color.rInt / 255f * alpha
        out[offset + 1] = color.gInt / 255f * alpha
        out[offset + 2] = color.bInt / 255f * alpha
        out[offset + 3] = alpha
    }

    /** A straight framework colour as Java2D's own paint, for the exactly equivalent uniform fill. */
    private fun Color.awt() = java.awt.Color(rInt, gInt, bInt, aInt)

    /** The local-to-device matrix: the affine graphics state composed with the pending perspective. */
    private fun deviceMatrix(affine: AffineTransform, perspective: DoubleArray?): DoubleArray {
        val matrix = doubleArrayOf(
            affine.scaleX, affine.shearX, affine.translateX,
            affine.shearY, affine.scaleY, affine.translateY,
            0.0, 0.0, 1.0,
        )
        return perspective?.let { multiply(matrix, it) } ?: matrix
    }

    private fun matrixOf(perspective: WindowPerspective): DoubleArray =
        DoubleArray(9).also { perspective.writeMatrix(it) }

    private fun multiply(a: DoubleArray, b: DoubleArray): DoubleArray {
        val out = DoubleArray(9)
        for (row in 0 until 3) {
            for (column in 0 until 3) {
                out[row * 3 + column] =
                    a[row * 3] * b[column] +
                        a[row * 3 + 1] * b[3 + column] +
                        a[row * 3 + 2] * b[6 + column]
            }
        }
        return out
    }

    /**
     * The inverse of a 3x3 matrix, or `null` when it has none.
     *
     * The determinant alone is not the whole test: a near-singular transform inverts to infinite
     * entries, which would leave a partially evaluated draw, so those are rejected too. A caller
     * that gets `null` draws nothing, which is the backend's safe answer for a matrix it cannot
     * carry.
     */
    private fun invert(matrix: DoubleArray): DoubleArray? {
        val c00 = matrix[4] * matrix[8] - matrix[5] * matrix[7]
        val c01 = matrix[5] * matrix[6] - matrix[3] * matrix[8]
        val c02 = matrix[3] * matrix[7] - matrix[4] * matrix[6]
        val determinant = matrix[0] * c00 + matrix[1] * c01 + matrix[2] * c02
        if (determinant == 0.0 || !determinant.isFinite()) return null
        val scale = 1.0 / determinant
        val inverse = doubleArrayOf(
            c00 * scale, (matrix[2] * matrix[7] - matrix[1] * matrix[8]) * scale,
            (matrix[1] * matrix[5] - matrix[2] * matrix[4]) * scale,
            c01 * scale, (matrix[0] * matrix[8] - matrix[2] * matrix[6]) * scale,
            (matrix[2] * matrix[3] - matrix[0] * matrix[5]) * scale,
            c02 * scale, (matrix[1] * matrix[6] - matrix[0] * matrix[7]) * scale,
            (matrix[0] * matrix[4] - matrix[1] * matrix[3]) * scale,
        )
        for (value in inverse) if (!value.isFinite()) return null
        return inverse
    }

    /**
     * The polygon the fill covers, in user space, or `null` when the projection is not usable.
     *
     * Without a perspective the local points are already user space. With one, each is projected by
     * hand and the positive-`w` guard rejects a transform that reaches or crosses the eye plane: a
     * draw that cannot be projected is skipped whole instead of half-painted.
     */
    private fun projectedPolygon(perspective: DoubleArray?, xs: DoubleArray, ys: DoubleArray): Path2D.Double? {
        val path = Path2D.Double()
        for (index in xs.indices) {
            val localX = xs[index]
            val localY = ys[index]
            var x = localX
            var y = localY
            if (perspective != null) {
                val w = perspective[6] * localX + perspective[7] * localY + perspective[8]
                if (!(w > MIN_PROJECTIVE_W) || !w.isFinite()) return null
                x = (perspective[0] * localX + perspective[1] * localY + perspective[2]) / w
                y = (perspective[3] * localX + perspective[4] * localY + perspective[5]) / w
            }
            if (!x.isFinite() || !y.isFinite()) return null
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.closePath()
        return path
    }

    /**
     * One fill, one source-over.
     *
     * The paint is installed only around the fill and the composite is forced to source-over, which
     * is the contract for a primitive landing on the UI. Both are restored on every exit, including
     * an exception out of the paint. Nothing else in the graphics state is touched: the transform
     * and the clip are the ones the caller set.
     */
    private fun fillWithPaint(graphics: Graphics2D, shape: Shape, paint: Paint) {
        val savedPaint = graphics.paint
        val savedComposite = graphics.composite
        try {
            graphics.composite = AlphaComposite.SrcOver
            graphics.paint = paint
            graphics.fill(shape)
        } finally {
            graphics.paint = savedPaint
            graphics.composite = savedComposite
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
     * A list of triangles is one surface, not a stack of independent fills: giving each triangle its
     * own anti-aliased fill composites a shared edge twice and leaves an alpha seam. The list is
     * therefore rasterised jointly in device space. The top-left half-open rule gives each pixel
     * centre exactly one owner on an internal edge, while a centre strictly inside two triangles is
     * a real overlap and both are composited, in the supplied order. Coverage comes from a 4x4
     * subpixel mask, so outer edges keep their anti-aliasing. [texture] is `null` only for the
     * untextured overload; [uScale]/[vScale] are the UV-to-pixel scale of the image actually
     * sampled. A triangle whose projection or scaled UV cannot be carried is skipped whole rather
     * than drawn half; a matrix unusable for the call as a whole draws nothing. The finished pixels
     * are blitted in bounded tiles with one source-over per pixel, so destination colour and alpha
     * follow the same contract as a quad.
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
        val perspective = renderParam.perspective?.let { matrixOf(it) }
        val device = deviceMatrix(graphics.transform, perspective)
        val inverse = invert(device) ?: return
        val triangleCount = colors.size / 3
        if (triangleCount == 0) return

        // Everything the pixel loop needs, filled once: local vertices for the barycentric field and
        // the top-left test, device vertices for the bounds, premultiplied tints and texture UVs.
        val local = DoubleArray(triangleCount * 6)
        val projected = DoubleArray(triangleCount * 6)
        val tints = FloatArray(triangleCount * 12)
        val texels = DoubleArray(triangleCount * 6)
        val counterClockwise = BooleanArray(triangleCount)
        val valid = BooleanArray(triangleCount)
        var left = Double.POSITIVE_INFINITY
        var top = Double.POSITIVE_INFINITY
        var right = Double.NEGATIVE_INFINITY
        var bottom = Double.NEGATIVE_INFINITY
        var vertex = 0
        var color = 0
        for (index in 0 until triangleCount) {
            val ax = vertices[vertex].drawFloat().toDouble()
            val ay = vertices[vertex + 1].drawFloat().toDouble()
            val bx = vertices[vertex + stride].drawFloat().toDouble()
            val by = vertices[vertex + stride + 1].drawFloat().toDouble()
            val cx = vertices[vertex + stride * 2].drawFloat().toDouble()
            val cy = vertices[vertex + stride * 2 + 1].drawFloat().toDouble()
            val base = index * 6
            local[base] = ax
            local[base + 1] = ay
            local[base + 2] = bx
            local[base + 3] = by
            local[base + 4] = cx
            local[base + 5] = cy
            premultiply(colors[color], tints, index * 12)
            premultiply(colors[color + 1], tints, index * 12 + 4)
            premultiply(colors[color + 2], tints, index * 12 + 8)
            val area = (bx - ax) * (cy - ay) - (cx - ax) * (by - ay)
            var drawable = area != 0.0 && area.isFinite()
            if (drawable && !(projectPoint(device, ax, ay, projected, base) &&
                    projectPoint(device, bx, by, projected, base + 2) &&
                    projectPoint(device, cx, cy, projected, base + 4))) return
            if (drawable && texture != null) {
                texels[base] = vertices[vertex + 2].drawFloat().toDouble() * uScale
                texels[base + 1] = vertices[vertex + 3].drawFloat().toDouble() * vScale
                texels[base + 2] = vertices[vertex + stride + 2].drawFloat().toDouble() * uScale
                texels[base + 3] = vertices[vertex + stride + 3].drawFloat().toDouble() * vScale
                texels[base + 4] = vertices[vertex + stride * 2 + 2].drawFloat().toDouble() * uScale
                texels[base + 5] = vertices[vertex + stride * 2 + 3].drawFloat().toDouble() * vScale
                for (offset in 0 until 6) if (!texels[base + offset].isFinite()) drawable = false
            }
            vertex += stride * 3
            color += 3
            if (!drawable) continue
            valid[index] = true
            counterClockwise[index] = area > 0.0
            left = minOf(left, projected[base], projected[base + 2], projected[base + 4])
            right = maxOf(right, projected[base], projected[base + 2], projected[base + 4])
            top = minOf(top, projected[base + 1], projected[base + 3], projected[base + 5])
            bottom = maxOf(bottom, projected[base + 1], projected[base + 3], projected[base + 5])
        }
        if (!left.isFinite() || !top.isFinite() || !right.isFinite() || !bottom.isFinite()) return

        val clip = graphics.clip
        val deviceClip = clip?.let { graphics.transform.createTransformedShape(it) }
        if (deviceClip != null) {
            val bounds = deviceClip.bounds2D
            left = maxOf(left, bounds.minX)
            top = maxOf(top, bounds.minY)
            right = minOf(right, bounds.maxX)
            bottom = minOf(bottom, bounds.maxY)
        }
        if (renderParam.width > 0 && renderParam.height > 0) {
            left = maxOf(left, 0.0)
            top = maxOf(top, 0.0)
            right = minOf(right, renderParam.width.toDouble())
            bottom = minOf(bottom, renderParam.height.toDouble())
        }
        // Bounded raster memory: a shape larger than this in device pixels cannot be a UI primitive,
        // and converting an unbounded projection to int would overflow the tile walk as well.
        if (!(right > left) || !(bottom > top)) return
        if (right - left > MAX_TRIANGLE_EXTENT || bottom - top > MAX_TRIANGLE_EXTENT) return
        val x0 = floor(left).toInt()
        val y0 = floor(top).toInt()
        val x1 = ceil(right).toInt()
        val y1 = ceil(bottom).toInt()
        if (x1 <= x0 || y1 <= y0) return

        val patch = trianglePatchBuffer()
        val row = triangleRowBuffer()
        val owners = IntArray(triangleCount)
        val weights = FloatArray(triangleCount * 3)
        val samples = IntArray(triangleCount)
        val sample = FloatArray(4)
        val accumulated = FloatArray(4)

        val savedTransform = graphics.transform
        val savedClip = clip
        val savedComposite = graphics.composite
        try {
            graphics.composite = AlphaComposite.SrcOver
            graphics.transform = AffineTransform()
            graphics.clip = deviceClip
            var tileTop = y0
            while (tileTop < y1) {
                val height = minOf(TILE, y1 - tileTop)
                var tileLeft = x0
                while (tileLeft < x1) {
                    val width = minOf(TILE, x1 - tileLeft)
                    for (py in 0 until height) {
                        val deviceY = tileTop + py + 0.5
                        for (px in 0 until width) {
                            val deviceX = tileLeft + px + 0.5
                            val homogeneous = inverse[6] * deviceX + inverse[7] * deviceY + inverse[8]
                            if (homogeneous == 0.0 || !homogeneous.isFinite()) {
                                row[px] = 0
                                continue
                            }
                            val localX = (inverse[0] * deviceX + inverse[1] * deviceY + inverse[2]) / homogeneous
                            val localY = (inverse[3] * deviceX + inverse[4] * deviceY + inverse[5]) / homogeneous
                            var ownerCount = 0
                            for (triangle in 0 until triangleCount) {
                                if (!valid[triangle]) continue
                                val base = triangle * 6
                                if (!covers(local, base, counterClockwise[triangle], localX, localY)) continue
                                owners[ownerCount] = triangle
                                weightsAt(local, base, localX, localY, weights, ownerCount * 3)
                                ownerCount++
                            }

                            var coverage = 0
                            var fringeOwner = -1
                            val fullCoverage = ownerCount > 0 && cornersCovered(
                                local, counterClockwise, valid, triangleCount,
                                inverse, tileLeft + px, tileTop + py,
                            )
                            if (fullCoverage) {
                                coverage = SUBPIXELS
                            } else {
                                for (triangle in 0 until triangleCount) samples[triangle] = 0
                                var union = 0
                                for (subY in 0 until SUBDIVISION) {
                                    for (subX in 0 until SUBDIVISION) {
                                        val sampleX = tileLeft + px + (subX + 0.5) / SUBDIVISION
                                        val sampleY = tileTop + py + (subY + 0.5) / SUBDIVISION
                                        val w = inverse[6] * sampleX + inverse[7] * sampleY + inverse[8]
                                        if (w == 0.0 || !w.isFinite()) continue
                                        val sx = (inverse[0] * sampleX + inverse[1] * sampleY + inverse[2]) / w
                                        val sy = (inverse[3] * sampleX + inverse[4] * sampleY + inverse[5]) / w
                                        for (triangle in 0 until triangleCount) {
                                            if (!valid[triangle]) continue
                                            val base = triangle * 6
                                            if (!covers(local, base, counterClockwise[triangle], sx, sy)) continue
                                            samples[triangle]++
                                            union++
                                            break
                                        }
                                    }
                                }
                                coverage = union
                                if (ownerCount == 0) {
                                    var best = 0
                                    for (triangle in 0 until triangleCount) {
                                        if (samples[triangle] > best) {
                                            best = samples[triangle]
                                            fringeOwner = triangle
                                        }
                                    }
                                    if (best == 0) {
                                        row[px] = 0
                                        continue
                                    }
                                }
                            }
                            // A centre owned by a razor-thin triangle still deserves its sample.
                            if (coverage == 0 && ownerCount > 0) coverage = 1
                            if (coverage == 0) {
                                row[px] = 0
                                continue
                            }

                            accumulated[0] = 0f
                            accumulated[1] = 0f
                            accumulated[2] = 0f
                            accumulated[3] = 0f
                            val factor = coverage.toFloat() / SUBPIXELS
                            if (ownerCount > 0) {
                                for (owner in 0 until ownerCount) {
                                    if (!normalise(weights, owner * 3)) continue
                                    sampleTriangle(tints, owners[owner] * 12, weights, owner * 3, texture,
                                        owners[owner] * 6, texels, sample)
                                    sourceOver(sample, factor, accumulated)
                                }
                            } else {
                                weightsAt(local, fringeOwner * 6, localX, localY, weights, 0)
                                if (normalise(weights, 0)) {
                                    sampleTriangle(tints, fringeOwner * 12, weights, 0, texture,
                                        fringeOwner * 6, texels, sample)
                                    sourceOver(sample, factor, accumulated)
                                }
                            }
                            row[px] = premultipliedToArgb(
                                accumulated[0], accumulated[1], accumulated[2], accumulated[3],
                            )
                        }
                        patch.setRGB(0, py, width, 1, row, 0, width)
                    }
                    graphics.drawImage(
                        patch,
                        tileLeft, tileTop, tileLeft + width, tileTop + height,
                        0, 0, width, height,
                        null,
                    )
                    tileLeft += width
                }
                tileTop += height
            }
        } finally {
            graphics.transform = savedTransform
            graphics.clip = savedClip
            graphics.composite = savedComposite
        }
    }

    /** Project one local point through [matrix], rejecting a non-positive or non-finite `w`. */
    private fun projectPoint(matrix: DoubleArray, x: Double, y: Double, out: DoubleArray, offset: Int): Boolean {
        val w = matrix[6] * x + matrix[7] * y + matrix[8]
        if (!(w > MIN_PROJECTIVE_W) || !w.isFinite()) return false
        val deviceX = (matrix[0] * x + matrix[1] * y + matrix[2]) / w
        val deviceY = (matrix[3] * x + matrix[4] * y + matrix[5]) / w
        if (!deviceX.isFinite() || !deviceY.isFinite()) return false
        out[offset] = deviceX
        out[offset + 1] = deviceY
        return true
    }

    /**
     * Whether a local point is owned by a triangle under the top-left half-open rule.
     *
     * The rule is what makes an edge shared by two triangles belong to exactly one of them: an
     * edge sample is accepted only when the edge runs up or, if horizontal, to the right. The
     * orientation is normalised first, so reversing a mesh's vertex order cannot change ownership.
     */
    private fun covers(local: DoubleArray, base: Int, counterClockwise: Boolean, x: Double, y: Double): Boolean {
        val ax = local[base]
        val ay = local[base + 1]
        val bx = local[base + 2]
        val by = local[base + 3]
        val cx = local[base + 4]
        val cy = local[base + 5]
        return if (counterClockwise) {
            side(ax, ay, bx, by, x, y) && side(bx, by, cx, cy, x, y) && side(cx, cy, ax, ay, x, y)
        } else {
            side(bx, by, ax, ay, x, y) && side(cx, cy, bx, by, x, y) && side(ax, ay, cx, cy, x, y)
        }
    }

    private fun side(ax: Double, ay: Double, bx: Double, by: Double, x: Double, y: Double): Boolean {
        val edge = (bx - ax) * (y - ay) - (by - ay) * (x - ax)
        return when {
            edge > EDGE_EPSILON -> true
            edge < -EDGE_EPSILON -> false
            else -> by < ay || (by == ay && bx > ax)
        }
    }

    /** Barycentric weights of a local point in the triangle at [base], in its original vertex order. */
    private fun weightsAt(local: DoubleArray, base: Int, x: Double, y: Double, out: FloatArray, offset: Int) {
        val ax = local[base]
        val ay = local[base + 1]
        val bx = local[base + 2]
        val by = local[base + 3]
        val cx = local[base + 4]
        val cy = local[base + 5]
        val inverseArea = 1.0 / ((bx - ax) * (cy - ay) - (cx - ax) * (by - ay))
        val weightB = ((cx - x) * (ay - y) - (ax - x) * (cy - y)) * inverseArea
        val weightC = ((ax - x) * (by - y) - (bx - x) * (ay - y)) * inverseArea
        out[offset] = (1.0 - weightB - weightC).toFloat()
        out[offset + 1] = weightB.toFloat()
        out[offset + 2] = weightC.toFloat()
    }

    /** Clamp a weight triple to the triangle and renormalise it; false when nothing remains. */
    private fun normalise(weights: FloatArray, offset: Int): Boolean {
        var a = weights[offset].coerceAtLeast(0f)
        var b = weights[offset + 1].coerceAtLeast(0f)
        var c = weights[offset + 2].coerceAtLeast(0f)
        val sum = a + b + c
        if (!(sum > 0f) || !sum.isFinite()) return false
        a /= sum
        b /= sum
        c /= sum
        weights[offset] = a
        weights[offset + 1] = b
        weights[offset + 2] = c
        return true
    }

    /** Whether all four pixel corners are covered by the list, in which case coverage is full. */
    private fun cornersCovered(
        local: DoubleArray,
        counterClockwise: BooleanArray,
        valid: BooleanArray,
        triangleCount: Int,
        inverse: DoubleArray,
        pixelX: Int,
        pixelY: Int,
    ): Boolean {
        for (corner in 0 until 4) {
            val deviceX = pixelX + if (corner and 1 == 0) 0.125 else 0.875
            val deviceY = pixelY + if (corner and 2 == 0) 0.125 else 0.875
            val w = inverse[6] * deviceX + inverse[7] * deviceY + inverse[8]
            if (w == 0.0 || !w.isFinite()) return false
            val x = (inverse[0] * deviceX + inverse[1] * deviceY + inverse[2]) / w
            val y = (inverse[3] * deviceX + inverse[4] * deviceY + inverse[5]) / w
            var covered = false
            for (triangle in 0 until triangleCount) {
                if (valid[triangle] && covers(local, triangle * 6, counterClockwise[triangle], x, y)) {
                    covered = true
                    break
                }
            }
            if (!covered) return false
        }
        return true
    }

    /** Evaluate one triangle's premultiplied field at the given barycentric weights, including its texel. */
    private fun sampleTriangle(
        tints: FloatArray,
        tintBase: Int,
        weights: FloatArray,
        weightBase: Int,
        texture: BufferedImage?,
        texelBase: Int,
        texels: DoubleArray,
        out: FloatArray,
    ) {
        val a = weights[weightBase]
        val b = weights[weightBase + 1]
        val c = weights[weightBase + 2]
        var red = a * tints[tintBase] + b * tints[tintBase + 4] + c * tints[tintBase + 8]
        var green = a * tints[tintBase + 1] + b * tints[tintBase + 5] + c * tints[tintBase + 9]
        var blue = a * tints[tintBase + 2] + b * tints[tintBase + 6] + c * tints[tintBase + 10]
        var alpha = a * tints[tintBase + 3] + b * tints[tintBase + 7] + c * tints[tintBase + 11]
        if (texture != null) {
            val u = a * texels[texelBase] + b * texels[texelBase + 2] + c * texels[texelBase + 4]
            val v = a * texels[texelBase + 1] + b * texels[texelBase + 3] + c * texels[texelBase + 5]
            if (!u.isFinite() || !v.isFinite()) {
                out[0] = 0f
                out[1] = 0f
                out[2] = 0f
                out[3] = 0f
                return
            }
            val argb = texture.getRGB(
                Math.floorMod(floor(u).toInt(), texture.width),
                Math.floorMod(floor(v).toInt(), texture.height),
            )
            val texelAlpha = (argb ushr 24 and 0xFF) * (1f / 255f)
            red *= (argb shr 16 and 0xFF) * (1f / 255f) * texelAlpha
            green *= (argb shr 8 and 0xFF) * (1f / 255f) * texelAlpha
            blue *= (argb and 0xFF) * (1f / 255f) * texelAlpha
            alpha *= texelAlpha
        }
        out[0] = red
        out[1] = green
        out[2] = blue
        out[3] = alpha
    }

    /** Source-over one covered source into the premultiplied pixel accumulator. */
    private fun sourceOver(source: FloatArray, coverage: Float, accumulated: FloatArray) {
        val alpha = source[3] * coverage
        if (!(alpha > 0f)) return
        val keep = 1f - alpha
        accumulated[0] = source[0] * coverage + accumulated[0] * keep
        accumulated[1] = source[1] * coverage + accumulated[1] * keep
        accumulated[2] = source[2] * coverage + accumulated[2] * keep
        accumulated[3] = alpha + accumulated[3] * keep
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
        val graphics = renderParam.graphics
        val savedClip = graphics.clip
        val savedOutput = renderParam.outputClip
        try {
            // Snapshot every edge before the block: a caller's Rect may be backed by mutable state,
            // and the scope must not read it again after entry. Snapshotting also keeps a finite
            // positive extent from changing while the block runs.
            val bounds = rect.scissorBounds()
            val active = bounds?.let { effectiveClip(graphics, it) }
            renderParam.outputClip = active ?: Area()
            if (active == null) {
                // Invalid geometry, a singular local-to-output matrix, an eye-plane crossing or an
                // empty intersection all fail closed. The block still runs exactly once, under an
                // empty native clip, so its side effects and control flow are not silently dropped.
                graphics.clip = Path2D.Double()
            } else {
                setNativeClip(graphics, active)
            }
            block()
        } finally {
            graphics.setClip(savedClip)
            renderParam.outputClip = savedOutput
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
        // An affine scope inside an active projective one must not land in `graphics`: the graphics
        // matrix is applied *after* the pending perspective, so A there would compose as A*P for a
        // primitive whose local space is P*A. The inner transform belongs before the perspective, so
        // it is folded into the pending matrix and the outer graphics state stays exactly as it was.
        val active = renderParam.perspective
        if (active != null) {
            renderParam.perspective = active.concat(next)
            try {
                block()
            } finally {
                renderParam.perspective = active
            }
            return
        }
        val graphics = renderParam.graphics
        val savedTransform = graphics.transform
        // Reinstall the frozen output region through the new inverse: setClip accepts user-space
        // geometry under the current affine. No scissor means no clip work at all.
        val frozenClip = renderParam.outputClip
        val savedClip = if (frozenClip != null) graphics.clip else null
        try {
            graphics.transform(
                AffineTransform(
                    transform.m00.toDouble(), transform.m10.toDouble(),
                    transform.m01.toDouble(), transform.m11.toDouble(),
                    transform.m02.toDouble(), transform.m12.toDouble(),
                )
            )
            if (frozenClip != null) setNativeClip(graphics, frozenClip)
            block()
        } finally {
            graphics.transform = savedTransform
            if (frozenClip != null) graphics.setClip(savedClip)
        }
    }

    /**
     * A scissor's four edges, each read exactly once before the block and before any validation.
     *
     * Reading once is what makes a mutable/borrowed `Rect` harmless: neither the caller nor a
     * getter's side effect can change an edge between the snapshot and its use.
     */
    private fun Rect.scissorBounds(): FloatArray? {
        val left = left
        val top = top
        val right = right
        val bottom = bottom
        if (!left.isDrawable() || !top.isDrawable() || !right.isDrawable() || !bottom.isDrawable()) return null
        val bounds = floatArrayOf(left.drawFloat(), top.drawFloat(), right.drawFloat(), bottom.drawFloat())
        val width = bounds[2] - bounds[0]
        val height = bounds[3] - bounds[1]
        if (!width.isFinite() || !height.isFinite()) return null
        return if (width > 0f && height > 0f) bounds else null
    }

    /**
     * The exact output-space intersection of the parent clip and this scissor's projected
     * quadrilateral.
     *
     * The full local-to-output matrix is the graphics affine composed with the pending projective
     * row. The four corners are projected individually, not as an axis-aligned bound; a quadrilateral
     * whose edge cuts through its own bounding box would otherwise paint outside the scissor. The
     * parent is the frozen output clip when one is active, or the graphics clip at entry otherwise,
     * so nested scissors intersect exactly and a later transform cannot move an earlier one.
     */
    context(renderParam: WindowRenderParam)
    private fun effectiveClip(graphics: Graphics2D, local: FloatArray): Area? {
        val perspective = renderParam.perspective?.let { matrixOf(it) }
        val device = deviceMatrix(graphics.transform, perspective)
        // A singular or non-finite composition has no meaningful projected scissor: fail closed
        // rather than letting Java2D pick a half-defined clip from it.
        if (invert(device) == null) return null
        val xs = doubleArrayOf(local[0].toDouble(), local[2].toDouble(), local[2].toDouble(), local[0].toDouble())
        val ys = doubleArrayOf(local[1].toDouble(), local[1].toDouble(), local[3].toDouble(), local[3].toDouble())
        val projected = DoubleArray(8)
        for (corner in 0 until 4) {
            val localX = xs[corner]
            val localY = ys[corner]
            val w = device[6] * localX + device[7] * localY + device[8]
            // The scissor contract is w > 0, not the draw path's larger epsilon: a small but
            // positive homogeneous weight is still a point on the visible side of the eye plane.
            if (!(w > 0.0) || !w.isFinite()) return null
            val x = (device[0] * localX + device[1] * localY + device[2]) / w
            val y = (device[3] * localX + device[4] * localY + device[5]) / w
            if (!x.isFinite() || !y.isFinite()) return null
            projected[corner * 2] = x
            projected[corner * 2 + 1] = y
        }
        val path = Path2D.Double()
        path.moveTo(projected[0], projected[1])
        path.lineTo(projected[2], projected[3])
        path.lineTo(projected[4], projected[5])
        path.lineTo(projected[6], projected[7])
        path.closePath()
        val area = Area(path)
        val parent = renderParam.outputClip ?: graphics.clip?.let { clip ->
            runCatching { Area(graphics.transform.createTransformedShape(clip)) }.getOrNull()
        }
        if (parent != null) area.intersect(parent)
        return area
    }

    /**
     * Install [output] as the native clip by mapping it back through the current affine.
     *
     * Graphics2D exposes its clip in the current user space. The frozen region lives
     * in output space, so its inverse image makes the resulting device clip equal to [output].
     * A transform that cannot be inverted fails closed instead of leaving an unbounded clip.
     */
    private fun setNativeClip(graphics: Graphics2D, output: Area) {
        if (output.isEmpty) {
            graphics.clip = Path2D.Double()
            return
        }
        val user = runCatching { graphics.transform.createInverse().createTransformedShape(output) }.getOrNull()
        val bounds = user?.bounds2D
        if (user == null || bounds == null ||
            !bounds.minX.isFinite() || !bounds.minY.isFinite() ||
            !bounds.maxX.isFinite() || !bounds.maxY.isFinite()
        ) {
            graphics.clip = Path2D.Double()
            return
        }
        graphics.clip = user
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

    context(renderParam: WindowRenderParam)
    private fun outline(rect: Rect, color: Color) {
        val bounds = rect.bounds() ?: return
        val (l, t, r, b) = bounds
        fillRect(Rect(l.px, t.px, r.px, t.px + BORDER_WIDTH), color)
        fillRect(Rect(l.px, b.px - BORDER_WIDTH, r.px, b.px), color)
        fillRect(Rect(l.px, t.px, l.px + BORDER_WIDTH, b.px), color)
        fillRect(Rect(r.px - BORDER_WIDTH, t.px, r.px, b.px), color)
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

    /** Measures per vertex: `x, y` alone, or `x, y, u, v` when textured. */
    private val XY_STRIDE = 2
    private val XYUV_STRIDE = 4

    /** The smallest homogeneous weight a projected corner may have and still be drawn. */
    private val MIN_PROJECTIVE_W = 1e-6

    /** Device pixels per raster tile: a triangle list's only working surface is `TILE` squared. */
    private val TILE = 256

    /** Subpixel resolution of a triangle's anti-aliased outer edge. */
    private val SUBDIVISION = 4
    private val SUBPIXELS = SUBDIVISION * SUBDIVISION

    /** Local-space tolerance for treating a sample as lying exactly on an edge. */
    private val EDGE_EPSILON = 1e-8

    /** A primitive larger than this many device pixels is rejected rather than streamed forever. */
    private val MAX_TRIANGLE_EXTENT = 1.0e6

    /** Scratch surface and row reused by the triangle rasteriser; both are bounded by [TILE]. */
    private var trianglePatch: BufferedImage? = null
    private var triangleRow = IntArray(0)

    private fun trianglePatchBuffer(): BufferedImage {
        val current = trianglePatch
        if (current != null) return current
        return BufferedImage(TILE, TILE, BufferedImage.TYPE_INT_ARGB).also { trianglePatch = it }
    }

    private fun triangleRowBuffer(): IntArray {
        if (triangleRow.size < TILE) triangleRow = IntArray(TILE)
        return triangleRow
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
