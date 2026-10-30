package ca.fuwafuwa.gaku.Anki

import ca.fuwafuwa.gaku.Database.JmDictDatabase.Models.EntryOptimized
import ca.fuwafuwa.gaku.DB_JMDICT_NAME
import ca.fuwafuwa.gaku.LangUtils

/**
 * The fields of a Gaku note in AnkiDroid, as HTML: the word looked up, its reading and meaning from the
 * dictionary, the text it was found in with the word in bold, and the LLM's translation of that text
 * (lan build), if there is one.
 */
class AnkiCard(val word: String, val reading: String, val meaning: String, val sentence: String, val translation: String)
{
    /** The values for [fieldNames], the note type's fields in order; fields Gaku doesn't know stay empty. */
    fun fieldsFor(fieldNames: List<String>): Array<String>
    {
        val values = mapOf(FIELD_WORD to word, FIELD_READING to reading, FIELD_MEANING to meaning,
                FIELD_SENTENCE to sentence, FIELD_TRANSLATION to translation)
        return fieldNames.map { values[it] ?: "" }.toTypedArray()
    }

    companion object
    {
        const val FIELD_WORD = "Word"
        const val FIELD_READING = "Reading"
        const val FIELD_MEANING = "Meaning"
        const val FIELD_SENTENCE = "Sentence"
        const val FIELD_TRANSLATION = "Translation"
        val FIELDS = listOf(FIELD_WORD, FIELD_READING, FIELD_MEANING, FIELD_SENTENCE, FIELD_TRANSLATION)

        /**
         * [matched] is the text the dictionary matched, starting at [offset] (in chars) in [text]; it may
         * be an inflected form of the entry's word.
         */
        @JvmStatic
        fun from(entry: EntryOptimized, matched: String, text: String, offset: Int, translation: String?): AnkiCard
        {
            return AnkiCard(escape(entry.kanji),
                    escape(entry.readings),
                    meaning(entry),
                    sentence(text, matched, offset),
                    escape(translation?.trim() ?: "").replace("\n", "<br>"))
        }

        /** Numbered like the results window, one sense per line, with the part of speech for JMdict. */
        private fun meaning(entry: EntryOptimized): String
        {
            val meanings = entry.meanings.split("\ufffc")
            val pos = entry.pos.split("\ufffc")
            val jmdict = DB_JMDICT_NAME == entry.dictionary

            return meanings.mapIndexed { i, meaning ->
                val partOfSpeech = if (jmdict) pos.getOrNull(i)?.takeIf { it.isNotEmpty() }?.let { "($it) " } ?: "" else ""
                "${LangUtils.ConvertIntToCircledNum(i + 1)} ${escape(partOfSpeech + meaning)}"
            }.joinToString("<br>")
        }

        /** [text] with [matched] in bold where it was found; without bold if it isn't there any more. */
        internal fun sentence(text: String, matched: String, offset: Int): String
        {
            if (matched.isEmpty() || offset < 0 || !text.regionMatches(offset, matched, 0, matched.length))
            {
                return escape(text)
            }

            return escape(text.substring(0, offset)) + "<b>" + escape(matched) + "</b>" + escape(text.substring(offset + matched.length))
        }

        internal fun escape(text: String): String
        {
            return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        }
    }
}
