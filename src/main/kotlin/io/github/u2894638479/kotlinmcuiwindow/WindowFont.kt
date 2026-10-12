package io.github.u2894638479.kotlinmcuiwindow

import io.github.u2894638479.kotlinmcui.math.Color
import io.github.u2894638479.kotlinmcui.math.Measure
import io.github.u2894638479.kotlinmcui.math.px
import io.github.u2894638479.kotlinmcui.text.DslCharStyle
import io.github.u2894638479.kotlinmcui.text.DslFont
import io.github.u2894638479.kotlinmcui.text.DslGlyph
import io.github.u2894638479.kotlinmcui.text.DslRenderableChar
import java.awt.Font
import java.awt.Graphics2D
import java.awt.font.FontRenderContext
import java.awt.geom.Rectangle2D
import java.util.Random
import kotlin.math.roundToInt

/**
 * The backend's font: a real desktop font, measured and drawn by Java2D.
 *
 * The framework measures every glyph against [DslFont.lineHeight] and scales it by
 * `char.size / lineHeight`, so the metrics below are taken at [BASE_SIZE] - the same 9 pixels the
 * framework's own text components default to - and that scaling then works out unchanged instead of
 * being applied twice.
 *
 * Two deliberate differences from the Minecraft backends, both a consequence of having a real font
 * rather than a bitmap one:
 * - bold is AWT's bold face, not the base face drawn twice at [DslGlyph.boldOffset];
 * - the advance comes from the font's own glyph metrics, so text is a little wider than the
 *   Minecraft font's, and a page laid out against that font will wrap slightly earlier here.
 */
internal class WindowFont(private val family: String? = null) : DslFont<WindowRenderParam> {
    private val base = Font(family ?: DEFAULT_FAMILY, Font.PLAIN, BASE_SIZE)
    private val renderContext = FontRenderContext(null, true, true)
    private val glyphs = HashMap<Int, DslGlyph>()
    private val random = Random()

    override val lineHeight: Measure = BASE_SIZE.px

    override fun glyph(code: Int): DslGlyph = glyphs.getOrPut(code) { GlyphMetrics(advanceOf(code) + GLYPH_SPACING) }

    /** One advance is all the framework asks for, and it is the font's own. */
    private class GlyphMetrics(advance: Float) : DslGlyph {
        override val normalAdvance = advance.px
        override val boldOffset = 1.px
        override val shadowOffset = 1.px
    }

    private fun advanceOf(code: Int): Float {
        val text = textOf(code, obfuscated = false)
        if (text.isEmpty()) return 0f
        val glyph = base.createGlyphVector(renderContext, text).getGlyphMetrics(0)
        // A code point the font has no glyph for advances by nothing, which would stack the rest of
        // the line on top of it; one cell is the honest answer for a character that cannot be drawn.
        return if (glyph.advanceX.isFinite() && glyph.advanceX > 0f) glyph.advanceX else MISSING_ADVANCE
    }

    context(renderParam: WindowRenderParam)
    override fun renderChar(char: DslRenderableChar, x: Measure, y: Measure, effectLeft: Measure, effectRight: Measure) {
        val left = x.pixelsOrElse<Float> { return }
        val top = y.pixelsOrElse<Float> { return }
        val scale = (char.size / lineHeight).takeIf { it.isFinite() && it > 0.0 } ?: 1.0
        val text = textOf(char.code, char.style.isObfuscated)
        if (text.isEmpty()) return
        val graphics = renderParam.graphics
        val savedFont = graphics.font
        val savedPaint = graphics.paint
        val font = base.deriveFont(styleOf(char.style), (BASE_SIZE * scale).roundToInt().coerceAtLeast(1).toFloat())
        graphics.font = font
        try {
            val ascent = font.getLineMetrics(SAMPLE, renderContext).ascent
            val advance = charAdvance(char).pixelsOrElse { 0.0 }.toFloat()
            if (char.style.isShadowed) {
                draw(graphics, text, left + SHADOW_OFFSET, top + SHADOW_OFFSET, ascent, advance, shadowOf(char.color), char.style, scale)
            }
            draw(graphics, text, left, top, ascent, advance, char.color, char.style, scale)
        } finally {
            graphics.font = savedFont
            graphics.paint = savedPaint
        }
    }

    private fun draw(
        graphics: Graphics2D,
        text: String,
        left: Float,
        top: Float,
        ascent: Float,
        advance: Float,
        color: Color,
        style: DslCharStyle,
        scale: Double,
    ) {
        graphics.color = java.awt.Color(color.rInt, color.gInt, color.bInt, color.aInt)
        graphics.drawString(text, left, top + ascent)
        val thickness = 1f
        val width = advance + 1f
        if (style.isUnderlined) {
            graphics.fill(Rectangle2D.Float(left - 1f, top + (8.0 * scale).toFloat(), width, thickness))
        }
        if (style.isStrikeThrough) {
            graphics.fill(Rectangle2D.Float(left - 1f, top + (3.5 * scale).toFloat(), width, thickness))
        }
    }

    private fun styleOf(style: DslCharStyle): Int =
        (if (style.isBold) Font.BOLD else 0) or (if (style.isItalic) Font.ITALIC else 0)

    private fun textOf(code: Int, obfuscated: Boolean): String {
        if (obfuscated) return OBFUSCATION[random.nextInt(OBFUSCATION.length)].toString()
        if (code < 0 || code > MAX_CODE_POINT) return ""
        return runCatching { String(Character.toChars(code)) }.getOrDefault("")
    }

    private fun shadowOf(color: Color) =
        color.change(r = color.rInt / 4, g = color.gInt / 4, b = color.bInt / 4)

    private companion object {
        const val BASE_SIZE = 9
        const val DEFAULT_FAMILY = "SansSerif"
        const val GLYPH_SPACING = 1f
        const val MISSING_ADVANCE = 6f
        const val SHADOW_OFFSET = 1f
        const val MAX_CODE_POINT = 0x10FFFF
        const val SAMPLE = "Ag"
        const val OBFUSCATION = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
    }
}
