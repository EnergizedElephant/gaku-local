package ca.fuwafuwa.gaku.Search

import ca.fuwafuwa.gaku.Database.JmDictDatabase.Models.EntryOptimized
import ca.fuwafuwa.gaku.Deinflictor.DeinflectionInfo

/**
 * [entry], found from [word], the text matched. [byReading] if it was found by reading rather than
 * written form (よけい → 余計), and [converted] if that took turning katakana into hiragana.
 */
data class JmSearchResult(
        val entry: EntryOptimized,
        val deinfInfo: DeinflectionInfo,
        val word: String,
        val byReading: Boolean = false,
        val converted: Boolean = false
)
