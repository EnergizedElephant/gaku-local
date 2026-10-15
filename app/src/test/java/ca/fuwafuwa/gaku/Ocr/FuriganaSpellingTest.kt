package ca.fuwafuwa.gaku.Ocr

import ca.fuwafuwa.gaku.Ocr.OcrRunnable.Companion.FuriganaWord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FuriganaSpellingTest
{
    private fun word(kanji: String, readings: String, common: Boolean = true) = FuriganaWord(kanji, readings.split(", "), common)

    private fun chars(text: String) = text.map { it.toString() }

    private fun spell(reading: String, span: String, following: String, vararg words: FuriganaWord) =
            OcrRunnable.spellingsForFurigana(reading, chars(span), chars(following), words.toList())

    @Test
    fun fixesAMisreadKanji()
    {
        // 今日 read as 今目, with きょう beside it
        val result = spell("きょう", "今目", "は本当に",
                word("今日", "きょう, こんにち, こんち"), word("京", "きょう, けい"), word("教", "きょう"))
        assertEquals(listOf(chars("今日")), result.spellings)
        assertTrue(result.certain)
    }

    @Test
    fun usesOkurigana()
    {
        // 難しい read as 菜しい, with むずか beside 菜: the word is read むずかしい
        val result = spell("むずか", "菜", "しい",
                word("難しい", "むずかしい, むつかしい"), word("六借しい", "むずかしい, むつかしい"),
                word("六ヶ敷い", "むずかしい, むつかしい"), word("難", "なん"))
        assertEquals(listOf(chars("難")), result.spellings)
        assertTrue(result.certain)
    }

    @Test
    fun okuriganaDecidesTheWord()
    {
        // 食べる read as 良べる, with た beside it
        val result = spell("た", "良", "べる",
                word("食べる", "たべる"), word("田", "た"), word("他", "た"))
        assertEquals(listOf(chars("食")), result.spellings)
        assertTrue(result.certain)
    }

    @Test
    fun keepsWhatWasReadCorrectly()
    {
        val result = spell("せんせい", "先生", "が来る", word("先生", "せんせい, せんじょう"), word("専制", "せんせい"))
        assertEquals(chars("先生"), result.spellings[0])
        assertTrue(result.certain)
    }

    @Test
    fun homophonesAreOnlyAlternatives()
    {
        // Nothing in common with what was read, and all equally common
        val result = spell("かみ", "上", "",
                word("紙", "かみ"), word("神", "かみ"), word("髪", "かみ"))
        assertEquals(3, result.spellings.size)
        assertFalse(result.certain)
    }

    @Test
    fun matchesInflectedOkurigana()
    {
        // 会おう with あ beside 会: the volitional of 会う (あう), not one of the words read あ
        val deinflect = { reading: String -> if (reading == "あおう") listOf("あおう", "あう") else listOf(reading) }
        val result = OcrRunnable.spellingsForFurigana("あ", chars("会"), chars("おう！"), listOf(
                word("会う", "あう"), word("合う", "あう"), word("遭う", "あう"),
                word("彼", "あ"), word("我", "あ")), deinflect)
        assertEquals(chars("会"), result.spellings[0])
        assertTrue(result.certain)
    }

    @Test
    fun fixesAKanjiBeforeInflectedOkurigana()
    {
        // 食べた read as 良べた, with た beside it
        val deinflect = { reading: String -> if (reading == "たべた") listOf("たべた", "たべる") else listOf(reading) }
        val result = OcrRunnable.spellingsForFurigana("た", chars("良"), chars("べた"), listOf(
                word("食べる", "たべる"), word("田", "た"), word("他", "た")), deinflect)
        assertEquals(listOf(chars("食")), result.spellings)
        assertTrue(result.certain)
    }

    @Test
    fun noWordFits()
    {
        val result = spell("きょう", "今目は", "", word("今日", "きょう"))
        assertTrue(result.spellings.isEmpty())
    }
}
