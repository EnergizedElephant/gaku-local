package ca.fuwafuwa.gaku.Search

import ca.fuwafuwa.gaku.DB_JMDICT_NAME
import ca.fuwafuwa.gaku.Database.JmDictDatabase.Models.EntryOptimized
import ca.fuwafuwa.gaku.Deinflictor.DeinflectionInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadingLookupTest
{
    private fun entry(kanji: String, priorities: String = "", primary: Boolean = true): EntryOptimized
    {
        val entry = EntryOptimized()
        entry.kanji = kanji
        entry.priorities = priorities
        entry.dictionary = DB_JMDICT_NAME
        entry.setPrimaryEntry(primary)
        return entry
    }

    private fun exact(word: String) = DeinflectionInfo(word, 0xFF, "")

    /** Stands in for the deinflector: each text as itself only */
    private val asWritten: (String) -> List<DeinflectionInfo> = { listOf(exact(it)) }

    @Test
    fun theKanaRunStartsAtTheTappedCharacterAndStopsAtKanji()
    {
        assertEquals("よけいなことして", ReadingLookup.kanaRun("所でよけいなことして所", 2))
        assertEquals("", ReadingLookup.kanaRun("所でよけい", 0))
        assertEquals(ReadingLookup.MAX_KANA_RUN, ReadingLookup.kanaRun("あいうえおかきくけこさしすせそ", 0).length)
    }

    @Test
    fun everyStartOfTheRunIsACandidateButNotASingleKana()
    {
        val readings = ReadingLookup.candidates("よけいな", asWritten).map { it.reading }

        assertEquals(listOf("よけいな", "よけい", "よけ"), readings)
    }

    @Test
    fun katakanaIsAlsoLookedUpInHiraganaKeepingTheTextItCameFrom()
    {
        val candidates = ReadingLookup.candidates("スゴイ", asWritten).filter { it.source == "スゴイ" }

        assertEquals(listOf("スゴイ" to false, "すごい" to true), candidates.map { it.reading to it.converted })
    }

    @Test
    fun deinflectionsAreCountedFromTheirReason()
    {
        assertEquals(0, ReadingLookup.deinflectionSteps(exact("わかる")))
        assertEquals(1, ReadingLookup.deinflectionSteps(DeinflectionInfo("わかる", 2, "< negative")))
        assertEquals(2, ReadingLookup.deinflectionSteps(DeinflectionInfo("わかる", 2, "< past < negative")))
    }

    @Test
    fun aDeinflectionHasToFitThePartOfSpeech()
    {
        assertTrue(ReadingLookup.fitsPartOfSpeech("v5r,vi", 2))
        assertFalse(ReadingLookup.fitsPartOfSpeech("n", 2))
        assertTrue(ReadingLookup.fitsPartOfSpeech("adj-i", 4))
    }

    @Test
    fun aLongerMatchByReadingBeatsAShorterOneByWrittenForm()
    {
        val yo = JmSearchResult(entry("よ", "spec1"), exact("よ"), "よ")
        val yokei = JmSearchResult(entry("余計", "ichi1"), exact("よけい"), "よけい", byReading = true)

        assertEquals(listOf("余計", "よ"), JmTask.rank(listOf(yo, yokei)).map { it.entry.kanji })
    }

    @Test
    fun theWrittenFormBeatsAReadingOfTheSameLength()
    {
        // 網 is commoner than the particle here, but the text is written as もう
        val mou = JmSearchResult(entry("もう", "spec1"), exact("もう"), "もう")
        val ami = JmSearchResult(entry("網", "news1"), exact("もう"), "もう", byReading = true)

        assertEquals(listOf("もう", "網"), JmTask.rank(listOf(ami, mou)).map { it.entry.kanji })
    }

    @Test
    fun theMoreTextADeinflectionCoversTheBetter()
    {
        val yomi = JmSearchResult(entry("読み", "ichi1"), exact("読み"), "読み")
        val yomu = JmSearchResult(entry("読む"), DeinflectionInfo("読む", 2, "< polite"), "読みます")

        assertEquals(listOf("読む", "読み"), JmTask.rank(listOf(yomi, yomu)).map { it.entry.kanji })
    }

    @Test
    fun asWrittenBeatsConvertedAndFewerDeinflectionsBeatMore()
    {
        val converted = JmSearchResult(entry("凄い"), exact("すごい"), "スゴイ", byReading = true, converted = true)
        val asWritten = JmSearchResult(entry("スゴイ"), exact("スゴイ"), "スゴイ")
        assertEquals(listOf("スゴイ", "凄い"), JmTask.rank(listOf(converted, asWritten)).map { it.entry.kanji })

        val twoSteps = JmSearchResult(entry("甲"), DeinflectionInfo("甲", 2, "< a < b"), "かけた")
        val oneStep = JmSearchResult(entry("乙"), DeinflectionInfo("乙", 2, "< a"), "かけた")
        assertEquals(listOf("乙", "甲"), JmTask.rank(listOf(twoSteps, oneStep)).map { it.entry.kanji })
    }
}
