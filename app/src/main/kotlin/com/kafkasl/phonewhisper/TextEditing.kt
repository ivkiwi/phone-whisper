package com.kafkasl.phonewhisper

/**
 * Pure text helpers used by the voice keyboard: smart punctuation, spacing and
 * capitalization of dictated phrases, word-wise cursor movement and word fixing.
 * No Android dependencies so everything here is unit-testable.
 */
object TextEditing {

    /** A text change relative to the cursor: delete [deleteBefore] chars, then insert [insert]. */
    data class Edit(val deleteBefore: Int, val insert: String)

    /** Dictated text split into what to insert and where the phrase itself starts inside it. */
    data class Dictation(val insert: String, val phraseOffset: Int, val phrase: String)

    private const val TRAILING_PUNCT = ".,?!…;:"
    private const val SENTENCE_END = ".?!…"
    private const val NO_SPACE_AFTER = "([{«„“‘\"'/@#"

    fun isWordChar(c: Char): Boolean =
        c.isLetterOrDigit() || Character.getType(c) == Character.NON_SPACING_MARK.toInt() ||
            Character.getType(c) == Character.COMBINING_SPACING_MARK.toInt()

    private fun isHorizontalSpace(c: Char) = c == ' ' || c == '\t' || c == ' '

    /** True if text typed after [before] starts a new sentence. */
    fun isSentenceStart(before: CharSequence): Boolean {
        var i = before.length
        while (i > 0 && isHorizontalSpace(before[i - 1])) i--
        if (i == 0) return true
        val c = before[i - 1]
        return c == '\n' || c in SENTENCE_END
    }

    /**
     * Prepare a transcript for insertion at the cursor:
     * - capitalize at a sentence start, otherwise lower-case the first letter that Whisper
     *   capitalizes for every segment (keeps acronyms and the English pronoun "I");
     * - add a separating space before / after when the neighbours are words.
     */
    fun prepareDictation(raw: String, before: CharSequence, after: CharSequence): Dictation {
        var body = raw.trim()
        if (body.isEmpty()) return Dictation("", 0, "")

        body = if (isSentenceStart(before)) capitalizeFirst(body) else decapitalizeFirst(body)

        val last = before.lastOrNull()
        val first = body.first()
        val lead = if (last != null && !last.isWhitespace() && last !in NO_SPACE_AFTER &&
            first !in TRAILING_PUNCT) " " else ""
        val next = after.firstOrNull()
        val trail = if (next != null && isWordChar(next)) " " else ""
        return Dictation(lead + body + trail, lead.length, body)
    }

    /**
     * Smart punctuation key. Replaces the punctuation the recognizer put at the end of the
     * phrase (e.g. "Ты придёшь." + "?" → "Ты придёшь? "), removes spaces before the mark and
     * adds one after it. With [append] (the previous key was also punctuation) marks stack
     * instead, so "?" then "!" gives "?!" and three "." give "...".
     */
    fun punctuate(before: CharSequence, mark: Char, after: CharSequence, append: Boolean): Edit {
        var i = before.length
        while (i > 0 && isHorizontalSpace(before[i - 1])) i--
        if (!append) {
            var j = i
            while (j > 0 && before[j - 1] in TRAILING_PUNCT) j--
            // Only replace punctuation that follows a word, never a whole line of dots.
            if (j > 0 && !before[j - 1].isWhitespace()) i = j
        }
        val nextIsSpace = after.firstOrNull()?.isWhitespace() == true
        val trail = if (nextIsSpace) "" else " "
        return Edit(before.length - i, "$mark$trail")
    }

    /** How many chars a "delete word" key removes: trailing spaces plus the word (or punctuation run). */
    fun deleteWordLength(before: CharSequence): Int {
        var i = before.length
        while (i > 0 && before[i - 1].isWhitespace()) i--
        if (i > 0 && isWordChar(before[i - 1])) {
            while (i > 0 && isWordChar(before[i - 1])) i--
        } else {
            while (i > 0 && !before[i - 1].isWhitespace() && !isWordChar(before[i - 1])) i--
        }
        return before.length - i
    }

    /** Distance to move the cursor left to the start of the previous word. */
    fun previousWordOffset(before: CharSequence): Int {
        var i = before.length
        while (i > 0 && !isWordChar(before[i - 1])) i--
        while (i > 0 && isWordChar(before[i - 1])) i--
        return before.length - i
    }

    /** Distance to move the cursor right to the end of the next word. */
    fun nextWordOffset(after: CharSequence): Int {
        var i = 0
        while (i < after.length && !isWordChar(after[i])) i++
        while (i < after.length && isWordChar(after[i])) i++
        return i
    }

    /**
     * Fit a re-dictated or suggested replacement to the word it replaces: drop the sentence
     * punctuation Whisper appends to single words and copy the original's capitalization.
     */
    fun fitReplacement(raw: String, original: String): String {
        val text = raw.trim().trimEnd { it in TRAILING_PUNCT || it.isWhitespace() }
            .trim('"', '«', '»', '“', '”')
        if (text.isEmpty()) return text
        val origFirst = original.firstOrNull { it.isLetter() } ?: return text
        return if (origFirst.isUpperCase()) capitalizeFirst(text) else decapitalizeFirst(text)
    }

    /** Flip the case of the first letter ("москва" ↔ "Москва"). */
    fun toggleFirstLetterCase(word: String): String {
        val idx = word.indexOfFirst { it.isLetter() }
        if (idx < 0) return word
        val c = word[idx]
        val flipped = if (c.isUpperCase()) c.lowercaseChar() else c.uppercaseChar()
        return word.substring(0, idx) + flipped + word.substring(idx + 1)
    }

    fun capitalizeFirst(text: String): String {
        val idx = text.indexOfFirst { it.isLetter() }
        if (idx < 0 || text[idx].isUpperCase()) return text
        return text.substring(0, idx) + text[idx].titlecaseChar() + text.substring(idx + 1)
    }

    /** Lower-case the first letter unless the first word is an acronym or the pronoun "I". */
    fun decapitalizeFirst(text: String): String {
        val idx = text.indexOfFirst { it.isLetter() }
        if (idx < 0 || !text[idx].isUpperCase()) return text
        var end = idx
        while (end < text.length && isWordChar(text[end])) end++
        val word = text.substring(idx, end)
        if (word == "I") return text
        if (word.length > 1 && word.all { !it.isLetter() || it.isUpperCase() }) return text
        return text.substring(0, idx) + text[idx].lowercaseChar() + text.substring(idx + 1)
    }

    /**
     * The word the cursor is inside: how many chars it extends back into [before] and
     * forward into [after]. Null unless there are word characters on both sides, so a
     * cursor at a word boundary (e.g. right after dictation) selects nothing.
     */
    fun wordAroundCursor(before: CharSequence, after: CharSequence): Pair<Int, Int>? {
        if (before.isEmpty() || after.isEmpty()) return null
        if (!isWordChar(before[before.length - 1]) || !isWordChar(after[0])) return null
        var back = 0
        while (back < before.length && isWordChar(before[before.length - 1 - back])) back++
        var fwd = 0
        while (fwd < after.length && isWordChar(after[fwd])) fwd++
        return back to fwd
    }

    /** True if a user selection looks like one word we can offer fixes for. */
    fun isSingleWord(text: CharSequence): Boolean =
        text.length in 1..40 && text.none { it.isWhitespace() } && text.any { it.isLetter() }

    /** Range (end exclusive) to remove when deleting [start, end) from [text], with one adjacent space. */
    fun deletionExtent(text: CharSequence, start: Int, end: Int): IntRange {
        var s = start
        var e = end
        if (s > 0 && text[s - 1] == ' ') s--
        else if (e < text.length && text[e] == ' ') e++
        return s until e
    }

    /** Chars to move right over one visible character (a surrogate pair counts as one). */
    fun charStepForward(after: CharSequence): Int = when {
        after.isEmpty() -> 0
        after.length >= 2 && Character.isHighSurrogate(after[0]) && Character.isLowSurrogate(after[1]) -> 2
        else -> 1
    }

    /** Chars to move left over one visible character (a surrogate pair counts as one). */
    fun charStepBackward(before: CharSequence): Int = when {
        before.isEmpty() -> 0
        before.length >= 2 && Character.isLowSurrogate(before[before.length - 1]) &&
            Character.isHighSurrogate(before[before.length - 2]) -> 2
        else -> 1
    }

    /** Index of the occurrence of [needle] in [haystack] closest to [hint], or null. */
    fun findNearest(haystack: String, needle: String, hint: Int): Int? {
        if (needle.isEmpty()) return null
        var best: Int? = null
        var idx = haystack.indexOf(needle)
        while (idx >= 0) {
            if (best == null || kotlin.math.abs(idx - hint) < kotlin.math.abs(best - hint)) best = idx
            idx = haystack.indexOf(needle, idx + 1)
        }
        return best
    }
}

/**
 * The last dictated phrase as committed into the editor, starting at absolute
 * position [start]. Used for the tappable word strip in the voice keyboard.
 */
data class DictatedPhrase(val text: String, val start: Int) {

    data class Word(val start: Int, val end: Int, val text: String)

    val words: List<Word> by lazy {
        WORD_REGEX.findAll(text).map { Word(it.range.first, it.range.last + 1, it.value) }.toList()
    }

    /** Text used to find the phrase again in the editor; trailing punctuation may have been edited. */
    val searchKey: String get() = text.trimEnd { !TextEditing.isWordChar(it) }

    fun withStart(newStart: Int) = copy(start = newStart)

    /** Replace word [index] with [replacement]; an empty replacement deletes the word. */
    fun replace(index: Int, replacement: String): DictatedPhrase {
        if (replacement.isEmpty()) return delete(index)
        val w = words[index]
        return copy(text = text.substring(0, w.start) + replacement + text.substring(w.end))
    }

    /** Range (phrase-relative, end exclusive) removed when deleting word [index], with one adjacent space. */
    fun deletionRange(index: Int): IntRange {
        val w = words[index]
        var s = w.start
        var e = w.end
        if (s > 0 && text[s - 1] == ' ') s--
        else if (e < text.length && text[e] == ' ') e++
        return s until e
    }

    fun delete(index: Int): DictatedPhrase {
        val r = deletionRange(index)
        return copy(text = text.removeRange(r))
    }

    companion object {
        private val WORD_REGEX = Regex("[\\p{L}\\p{N}\\p{M}]+(?:['’\\-][\\p{L}\\p{N}\\p{M}]+)*")
    }
}
