package io.github.u2894638479.kotlinmcuiwindow

import io.github.u2894638479.kotlinmcui.logger.dslLogger
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.Locale

/**
 * The framework's own language files, read off this classpath.
 *
 * They travel in the core library's jar as `assets/kotlinmcui/lang/<locale>.json`, so a window shows
 * the same words a game backend shows instead of the raw key. Nothing else is read: another mod's
 * keys live in another mod's files, and a window has no resource pack to ask.
 *
 * A key no file knows answers `null` - the framework's own "show the key" case, which is what a game
 * does with a missing translation.
 */
internal object WindowTranslations {
    /**
     * The file for this machine's locale, or `null` when none of them could be read. Resolved once:
     * the locale of a running process does not change, and this is reached on the render thread.
     */
    private val strings: Map<String, String>? by lazy { load() }

    /** The localized [key], or `null` when no file carries it. */
    fun get(key: String, args: Array<out Any?>): String? {
        val text = strings?.get(key) ?: return null
        if (args.isEmpty()) return text
        // The framework's own files use `%s`, `%d` and `%f`, which is Java's format syntax; a file
        // whose placeholders do not fit the arguments must not take the frame down with it.
        return runCatching { String.format(Locale.getDefault(), text, *args) }.getOrElse { text }
    }

    /**
     * Read the first file that exists, the most specific locale first.
     *
     * A file that cannot be read or parsed is logged and skipped rather than thrown: a window that
     * shows keys because its language file is broken is still a window.
     */
    private fun load(): Map<String, String>? {
        for (candidate in candidates()) {
            val path = "assets/kotlinmcui/lang/$candidate.json"
            val text = runCatching {
                WindowTranslations::class.java.classLoader
                    ?.getResourceAsStream(path)
                    ?.use { it.readBytes().decodeToString() }
            }.getOrNull() ?: continue
            val parsed = runCatching {
                Json.parseToJsonElement(text).jsonObject.mapValues { (_, value) -> value.jsonPrimitive.content }
            }.onFailure {
                dslLogger.warn("window: $path could not be read, so its keys are shown as they are", it)
            }.getOrNull() ?: continue
            return parsed
        }
        return null
    }

    /** `<language>_<country>`, then `<language>`, then the framework's own fallback. */
    private fun candidates(): List<String> {
        val locale = Locale.getDefault()
        val language = locale.language.lowercase(Locale.ROOT)
        val country = locale.country.lowercase(Locale.ROOT)
        return listOfNotNull(
            "${language}_$country".takeIf { language.isNotEmpty() && country.isNotEmpty() },
            language.takeIf { it.isNotEmpty() },
            FALLBACK,
        )
    }

    private const val FALLBACK = "en_us"
}
