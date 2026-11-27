package com.kafkasl.phonewhisper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TextEditingTest {

    // --- prepareDictation ---

    @Test fun `capitalizes at the start of a field`() {
        val d = TextEditing.prepareDictation("привет мир.", "", "")
        assertEquals("Привет мир.", d.insert)
        assertEquals(0, d.phraseOffset)
    }

    @Test fun `adds a space and lower-cases mid-sentence`() {
        val d = TextEditing.prepareDictation("Как дела?", "Привет,", "")
        assertEquals(" как дела?", d.insert)
        assertEquals(1, d.phraseOffset)
        assertEquals("как дела?", d.phrase)
    }

    @Test fun `capitalizes after a sentence end even with trailing space`() {
        assertEquals("Как дела?", TextEditing.prepareDictation("как дела?", "Привет. ", "").insert)
        assertEquals("Как дела?", TextEditing.prepareDictation("как дела?", "Привет\n", "").insert)
    }

    @Test fun `keeps acronyms and the pronoun I mid-sentence`() {
        assertEquals(" NASA launched", TextEditing.prepareDictation("NASA launched", "Today", "").insert)
        assertEquals(" I think so", TextEditing.prepareDictation("I think so", "Well,", "").insert)
    }

    @Test fun `adds a trailing space before a following word`() {
        assertEquals(" big ", TextEditing.prepareDictation("Big", "a", "house").insert)
    }

    @Test fun `no leading space after an opening bracket`() {
        assertEquals("note", TextEditing.prepareDictation("Note", "see (", "").insert)
    }

    @Test fun `blank transcript inserts nothing`() {
        assertEquals("", TextEditing.prepareDictation("   ", "abc", "").insert)
    }

    // --- punctuate ---

    @Test fun `question mark replaces the recognizer's period`() {
        assertEquals(TextEditing.Edit(1, "? "), TextEditing.punctuate("Ты придёшь.", '?', "", append = false))
    }

    @Test fun `replaces punctuation followed by spaces`() {
        assertEquals(TextEditing.Edit(2, "! "), TextEditing.punctuate("Ура. ", '!', "", append = false))
    }

    @Test fun `adds a mark after a bare word`() {
        assertEquals(TextEditing.Edit(0, ", "), TextEditing.punctuate("Привет", ',', "", append = false))
        assertEquals(TextEditing.Edit(1, ", "), TextEditing.punctuate("Привет ", ',', "", append = false))
    }

    @Test fun `stacks marks when the previous key was punctuation`() {
        assertEquals(TextEditing.Edit(1, "! "), TextEditing.punctuate("Что? ", '!', "", append = true))
    }

    @Test fun `no trailing space when text after already starts with one`() {
        assertEquals(TextEditing.Edit(1, "?"), TextEditing.punctuate("Да.", '?', " потом", append = false))
    }

    @Test fun `does not eat a line made of dots`() {
        assertEquals(TextEditing.Edit(0, "? "), TextEditing.punctuate("...", '?', "", append = false))
    }

    // --- word navigation and deletion ---

    @Test fun `delete word removes trailing spaces and the word`() {
        assertEquals(4, TextEditing.deleteWordLength("один два "))
        assertEquals(3, TextEditing.deleteWordLength("один два"))
        assertEquals(2, TextEditing.deleteWordLength("один?!"))
        assertEquals(0, TextEditing.deleteWordLength(""))
    }

    @Test fun `word offsets skip punctuation`() {
        assertEquals(3, TextEditing.previousWordOffset("один два"))
        assertEquals(5, TextEditing.previousWordOffset("один два, "))
        assertEquals(4, TextEditing.nextWordOffset(" два, три"))
        assertEquals(0, TextEditing.nextWordOffset(""))
    }

    @Test fun `char steps treat emoji as one character`() {
        assertEquals(0, TextEditing.charStepForward(""))
        assertEquals(1, TextEditing.charStepForward("ab"))
        assertEquals(2, TextEditing.charStepForward("😀a"))
        assertEquals(0, TextEditing.charStepBackward(""))
        assertEquals(1, TextEditing.charStepBackward("ab"))
        assertEquals(2, TextEditing.charStepBackward("a😀"))
    }

    @Test fun `word around cursor only inside a word`() {
        assertEquals(3 to 3, TextEditing.wordAroundCursor("Привет мос", "ква, как"))
        assertEquals(null, TextEditing.wordAroundCursor("Привет ", "москва"))
        assertEquals(null, TextEditing.wordAroundCursor("москва", ", как"))
        assertEquals(null, TextEditing.wordAroundCursor("", "abc"))
    }

    @Test fun `single word selection`() {
        assertEquals(true, TextEditing.isSingleWord("Москва,"))
        assertEquals(false, TextEditing.isSingleWord("два слова"))
        assertEquals(false, TextEditing.isSingleWord("123"))
        assertEquals(false, TextEditing.isSingleWord(""))
    }

    @Test fun `deletion extent takes one adjacent space`() {
        assertEquals(6 until 10, TextEditing.deletionExtent("Привет мир, как", 7, 10))
        assertEquals(0 until 4, TextEditing.deletionExtent("мир как", 0, 3))
        assertEquals(0 until 3, TextEditing.deletionExtent("мир", 0, 3))
    }

    // --- replacements ---

    @Test fun `fit replacement strips whisper punctuation and copies case`() {
        assertEquals("москва", TextEditing.fitReplacement(" Москва. ", "моска"))
        assertEquals("Москва", TextEditing.fitReplacement("москва", "Моска"))
        assertEquals("в то же", TextEditing.fitReplacement("В то же!", "вотоже"))
    }

    @Test fun `toggle first letter case`() {
        assertEquals("Москва", TextEditing.toggleFirstLetterCase("москва"))
        assertEquals("москва", TextEditing.toggleFirstLetterCase("Москва"))
        assertEquals("123", TextEditing.toggleFirstLetterCase("123"))
    }

    @Test fun `find nearest occurrence`() {
        assertEquals(11, TextEditing.findNearest("abc xx abc abc", "abc", 10))
        assertEquals(0, TextEditing.findNearest("abc xx abc", "abc", -1))
        assertNull(TextEditing.findNearest("abc", "zzz", 0))
        assertNull(TextEditing.findNearest("abc", "", 0))
    }
}

class DictatedPhraseTest {

    @Test fun `splits words without punctuation`() {
        val p = DictatedPhrase("Привет, мир! Как-то так, don't worry.", 5)
        assertEquals(listOf("Привет", "мир", "Как-то", "так", "don't", "worry"), p.words.map { it.text })
        assertEquals(8, p.words[1].start)
        assertEquals(11, p.words[1].end)
    }

    @Test fun `replace keeps surrounding punctuation`() {
        val p = DictatedPhrase("Привет, мир!", 0)
        assertEquals("Привет, Москва!", p.replace(1, "Москва").text)
    }

    @Test fun `delete removes one adjacent space`() {
        assertEquals("Привет, как дела", DictatedPhrase("Привет мир, как дела", 0).delete(1).text)
        assertEquals("мир", DictatedPhrase("Привет мир", 0).delete(0).text)
        assertEquals("мир", DictatedPhrase("Привет мир", 0).replace(0, "").text)
    }

    @Test fun `search key ignores trailing punctuation`() {
        assertEquals("Ты придёшь", DictatedPhrase("Ты придёшь.", 0).searchKey)
    }
}

class WordAlternativesTest {

    @Test fun `parses a json array`() {
        assertEquals(listOf("Москва", "мост"), WordAlternatives.parse("[\"Москва\", \"мост\"]", "моска"))
    }

    @Test fun `parses fenced json and drops the original`() {
        val reply = "```json\n[\"моска\", \"маска\", \"Маска\", \"мошка\"]\n```"
        assertEquals(listOf("маска", "мошка"), WordAlternatives.parse(reply, "моска"))
    }

    @Test fun `falls back to lines with bullets`() {
        assertEquals(listOf("один", "два"), WordAlternatives.parse("1. один\n- два\n", "x"))
    }

    @Test fun `limits the number of suggestions`() {
        assertEquals(4, WordAlternatives.parse("[\"a\",\"b\",\"c\",\"d\",\"e\"]", "x").size)
    }
}

class PcmConverterTest {
    @Test fun `converts little-endian samples`() {
        val pcm = byteArrayOf(0x00, 0x40, 0x00, 0xC0.toByte(), 0x01, 0x00)
        val out = PcmConverter.toFloatSamples(pcm, 4)
        assertEquals(2, out.size)
        assertEquals(0.5f, out[0], 1e-6f)
        assertEquals(-0.5f, out[1], 1e-6f)
    }
}

class VoiceButtonsTest {
    @Test fun `buttons survive a save and load`() {
        val list = listOf(
            VoiceButton("tap", 0xFF2563EB.toInt(), pushToTalk = false),
            VoiceButton("b3", 0xFF7C3AED.toInt(), pushToTalk = true, model = "sherpa-onnx-sense-voice-zh"),
        )
        assertEquals(list, VoiceButtons.parse(VoiceButtons.serialize(list)))
    }

    @Test fun `broken or empty settings fall back`() {
        assertEquals(null, VoiceButtons.parse("not json"))
        assertEquals(null, VoiceButtons.parse("[]"))
    }

    @Test fun `new ids do not clash`() {
        assertEquals("b3", VoiceButtons.newId(VoiceButtons.defaults()))
        assertEquals("b4", VoiceButtons.newId(VoiceButtons.defaults() + VoiceButton("b3", 0, false)))
    }
}

class VadGateTest {
    private fun window(v: Float) = FloatArray(4) { v }

    /** Detector says "speech" for windows whose samples are 1. */
    private fun gate(preRoll: Int = 2) = VadGate(windowSize = 4, preRollWindows = preRoll) { it[0] == 1f }

    private fun run(g: VadGate, vararg windows: Float): List<String> {
        val out = mutableListOf<VadGate.Event>()
        windows.forEach { g.accept(window(it), out) }
        return out.map { if (it is VadGate.Event.Audio) "A${it.samples[0].toInt()}" else "END" }
    }

    @Test fun `silence never reaches the recognizer`() {
        assertEquals(emptyList<String>(), run(gate(), 0f, 0f, 0f, 0f))
    }

    @Test fun `speech is fed with pre-roll and closed by speech end`() {
        // windows: 0 0 0 1 1 0  → pre-roll keeps the last two silent windows
        assertEquals(listOf("A0", "A0", "A1", "A1", "END"), run(gate(), 0f, 0f, 0f, 1f, 1f, 0f))
    }

    @Test fun `chunks are re-cut into detector windows`() {
        val g = gate(preRoll = 0)
        val out = mutableListOf<VadGate.Event>()
        g.accept(FloatArray(6) { 1f }, out)
        assertEquals(1, out.size)
        g.accept(FloatArray(2) { 1f }, out)
        assertEquals(2, out.size)
        assertEquals(true, g.inSpeech)
    }
}
