package ca.fuwafuwa.gaku.Search

import ca.fuwafuwa.gaku.Deinflictor.DeinflectionInfo
import ca.fuwafuwa.gaku.LangUtils

/**
 * Looking text in kana up by reading, as Yomitan does, so that よけい finds 余計: JmTask's main search
 * only matches written forms. The parts that need no database, kept here so they can be tested.
 */
object ReadingLookup
{
    /** The most kana looked up by reading from the tapped character; few words in kana are longer */
    const val MAX_KANA_RUN = 10

    /** At most this many entries found only by reading, for each length of text matched */
    const val MAX_PER_LENGTH = 5

    /**
     * A [reading] to look up, from the [source] text it was made from: the source as written, or
     * [converted] from katakana to hiragana, and maybe deinflected ([deinfInfo]).
     */
    class Candidate(val reading: String, val source: String, val deinfInfo: DeinflectionInfo, val converted: Boolean)

    fun isKana(char: Char): Boolean
    {
        return LangUtils.IsHiragana(char) || LangUtils.IsKatakana(char)
    }

    /** The kana from [offset] on, at most [MAX_KANA_RUN] of them; empty if the text there isn't kana */
    fun kanaRun(text: String, offset: Int): String
    {
        var end = offset
        while (end < text.length && end - offset < MAX_KANA_RUN && isKana(text[end]))
        {
            end++
        }
        return text.substring(offset, end)
    }

    /**
     * Every start of [run] two or more characters long, as written and in hiragana, with the
     * deinflections [deinflect] finds for each (the first being the text itself). A single kana is
     * left out: alone it's a particle or a word written in kana (は, の, よ) far more often than a
     * word read that way (歯, 野, 世), and the dozens of those would bury it.
     */
    fun candidates(run: String, deinflect: (String) -> List<DeinflectionInfo>): List<Candidate>
    {
        val candidates = ArrayList<Candidate>()
        for (length in run.length downTo 2)
        {
            val source = run.substring(0, length)
            val hiragana = LangUtils.ConvertKanatanaToHiragana(source)
            for (deinfInfo in deinflect(source))
            {
                candidates.add(Candidate(deinfInfo.word, source, deinfInfo, false))
            }
            if (hiragana != source)
            {
                for (deinfInfo in deinflect(hiragana))
                {
                    candidates.add(Candidate(deinfInfo.word, source, deinfInfo, true))
                }
            }
        }
        return candidates
    }

    /** How many deinflections lead to [deinfInfo]: the deinflector writes its reason as "< a < b" */
    fun deinflectionSteps(deinfInfo: DeinflectionInfo): Int
    {
        return deinfInfo.reason.count { it == '<' }
    }

    /** Whether a word with the parts of speech [pos] can be the dictionary form of a deinflection of [type] */
    fun fitsPartOfSpeech(pos: String, type: Int): Boolean
    {
        return (type and 1 != 0) && pos.contains("v1") ||
               (type and 2 != 0) && pos.contains("v5") ||
               (type and 4 != 0) && pos.contains("adj-i") ||
               (type and 8 != 0) && pos.contains("vk") ||
               (type and 16 != 0) && pos.contains("vs-")
    }
}
