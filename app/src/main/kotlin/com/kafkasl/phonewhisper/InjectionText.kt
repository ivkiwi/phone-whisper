package com.kafkasl.phonewhisper

/**
 * Decides what an editable field's *existing* text really is before we append dictation.
 *
 * Many fields report placeholder/hint text as their value (especially web editors in
 * Chrome/WebView), which would otherwise get prepended to the inserted transcript
 * (e.g. "Сообщение привет"). [resolveEditableText] returns "" for such fields so the
 * caller treats them as empty. Ported from SHAREN/phone-whisper.
 */
object InjectionText {
    data class ResolvedText(
        val text: String,
        val ignoredReason: String? = null
    )

    fun resolveEditableText(
        rawText: String,
        hintText: String,
        contentDescription: String,
        className: String,
        packageName: String,
        isFocused: Boolean,
        selectionStart: Int,
        selectionEnd: Int
    ): ResolvedText {
        val raw = normalize(rawText)
        if (raw.isBlank() || isEmptyHtmlSentinel(raw)) return ResolvedText("")

        val hint = normalize(hintText)
        if (hint.isNotBlank() && raw.equals(hint, ignoreCase = true)) {
            return ResolvedText("", "matches_hint")
        }

        val description = normalize(contentDescription)
        val caretAtStart = selectionStart <= 1 && selectionEnd <= 1
        val webLike = isWebLike(packageName, className)
        if (description.isNotBlank() &&
            raw.equals(description, ignoreCase = true) &&
            isFocused &&
            (caretAtStart || webLike)
        ) {
            return ResolvedText("", "matches_accessible_name")
        }

        if (webLike &&
            isFocused &&
            looksLikePlaceholder(raw, strict = !caretAtStart)
        ) {
            return ResolvedText("", "web_placeholder_like")
        }

        return ResolvedText(rawText)
    }

    private fun normalize(value: String): String =
        value
            .replace('\u00A0', ' ')  // non-breaking space
            .replace("\u200B", "")    // zero-width space
            .replace("\uFEFF", "")    // BOM / zero-width no-break space
            .trim()

    private fun isEmptyHtmlSentinel(value: String): Boolean {
        val compact = value.lowercase().replace("\\s+".toRegex(), "")
        return compact == "<br>" ||
            compact == "<br/>" ||
            compact == "<div><br></div>" ||
            compact == "<p><br></p>"
    }

    private fun isWebLike(packageName: String, className: String): Boolean {
        val pkg = packageName.lowercase()
        val cls = className.lowercase()
        return pkg.contains("chrome") ||
            pkg.contains("browser") ||
            pkg.contains("webview") ||
            cls.contains("webview") ||
            cls.contains("webkit")
    }

    private fun looksLikePlaceholder(value: String, strict: Boolean): Boolean {
        if (value.length > 140) return false
        val lowered = value.lowercase()
        if (strict) {
            return strongPlaceholderPrefixes.any { lowered.startsWith(it) } ||
                placeholderExact.any { lowered == it }
        }
        return placeholderPrefixes.any { lowered.startsWith(it) } ||
            placeholderExact.any { lowered == it }
    }

    private val strongPlaceholderPrefixes = listOf(
        "напишите, как вы будете", // Kwork offer description
        "напишите свое сообщение",
        "write your message",
        "enter your message"
    )

    private val placeholderPrefixes = listOf(
        "напишите",
        "введите",
        "укажите",
        "выберите",
        "опишите",
        "расскажите",
        "добавьте",
        "write ",
        "enter ",
        "type ",
        "search "
    )

    private val placeholderExact = listOf(
        "сообщение",
        "message"
    )
}
