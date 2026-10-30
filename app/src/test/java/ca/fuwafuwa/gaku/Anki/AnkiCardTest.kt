package ca.fuwafuwa.gaku.Anki

import ca.fuwafuwa.gaku.DB_JMDICT_NAME
import ca.fuwafuwa.gaku.Database.JmDictDatabase.Models.EntryOptimized
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class AnkiCardTest
{
    private fun entry(kanji: String, readings: String, meanings: List<String>, pos: List<String>, dictionary: String = DB_JMDICT_NAME): EntryOptimized
    {
        val entry = EntryOptimized()
        entry.kanji = kanji
        entry.readings = readings
        entry.meanings = meanings.joinToString("\ufffc")
        entry.pos = pos.joinToString("\ufffc")
        entry.dictionary = dictionary
        return entry
    }

    @Test
    fun anInflectedWordKeepsItsDictionaryFormAndIsBoldWhereItWasRead()
    {
        val card = AnkiCard.from(entry("食べる", "たべる", listOf("to eat"), listOf("v1,vt")), "食べた", "猫が魚を食べた", 4, null)

        assertEquals("食べる", card.word)
        assertEquals("たべる", card.reading)
        assertEquals("① (v1,vt) to eat", card.meaning)
        assertEquals("猫が魚を<b>食べた</b>", card.sentence)
        assertEquals("", card.translation)
    }

    @Test
    fun sensesGoOnTheirOwnLinesAndKanjidicHasNoPartOfSpeech()
    {
        val jmdict = AnkiCard.from(entry("明日", "あした, あす", listOf("tomorrow", "near future"), listOf("n-t", "")), "明日", "明日", 0, null)
        assertEquals("① (n-t) tomorrow<br>② near future", jmdict.meaning)

        val kanjidic = AnkiCard.from(entry("降", "コウ, ゴ", listOf("descend", "fall"), listOf("x", "y"), "KANJIDIC"), "降", "降", 0, null)
        assertEquals("① descend<br>② fall", kanjidic.meaning)
    }

    @Test
    fun theWordReadIsBoldNotAnEarlierCopy()
    {
        assertEquals("雨と<b>雨</b>", AnkiCard.sentence("雨と雨", "雨", 2))
    }

    @Test
    fun offsetsCountCharsSoSurrogatePairsComeOutRight()
    {
        // 𠮟 is two chars, so 雨 starts at 2
        assertEquals("𠮟<b>雨</b>", AnkiCard.sentence("𠮟雨", "雨", 2))
    }

    @Test
    fun aWordNoLongerAtItsOffsetIsLeftPlain()
    {
        assertEquals("猫が魚", AnkiCard.sentence("猫が魚", "犬", 0))
        assertEquals("猫が魚", AnkiCard.sentence("猫が魚", "魚", 7))
    }

    @Test
    fun everythingIsEscapedAndTheTranslationKeepsItsLines()
    {
        val card = AnkiCard.from(entry("a<b>", "r&r", listOf("x < y"), listOf("")), "<", "1 < 2", 2, "Line one\nLine <two>\n")
        assertEquals("a&lt;b&gt;", card.word)
        assertEquals("r&amp;r", card.reading)
        assertEquals("① x &lt; y", card.meaning)
        assertEquals("1 <b>&lt;</b> 2", card.sentence)
        assertEquals("Line one<br>Line &lt;two&gt;", card.translation)
    }

    @Test
    fun fieldsFollowTheNoteTypesOrderAndUnknownOnesStayEmpty()
    {
        val card = AnkiCard("w", "r", "m", "s", "t")
        assertArrayEquals(arrayOf("w", "r", "m", "s", "t"), card.fieldsFor(AnkiCard.FIELDS))
        assertArrayEquals(arrayOf("s", "", "w"), card.fieldsFor(listOf("Sentence", "Notes", "Word")))
    }

    @Test
    fun checksumsMatchAnkis()
    {
        // From Anki's own definition: int(sha1(text).hexdigest()[:8], 16)
        assertEquals(1522519019L, AnkiDroid.fieldChecksum("降る"))
        assertEquals(1982856378L, AnkiDroid.fieldChecksum("明日"))
        assertEquals(3775650288L, AnkiDroid.fieldChecksum("猫"))
        // HTML doesn't count
        assertEquals(AnkiDroid.fieldChecksum("猫"), AnkiDroid.fieldChecksum("<b>猫</b>"))
    }
}
