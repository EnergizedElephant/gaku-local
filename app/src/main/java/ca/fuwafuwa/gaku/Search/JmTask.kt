package ca.fuwafuwa.gaku.Search

import android.content.Context
import android.os.AsyncTask
import android.util.Log
import ca.fuwafuwa.gaku.DB_JMDICT_NAME
import ca.fuwafuwa.gaku.DB_KANJIDICT_NAME
import ca.fuwafuwa.gaku.Database.JmDictDatabase.JmDatabaseHelper
import ca.fuwafuwa.gaku.Database.JmDictDatabase.Models.EntryOptimized
import ca.fuwafuwa.gaku.Database.JmDictDatabase.Models.EntryReading
import ca.fuwafuwa.gaku.Deinflictor.DeinflectionInfo
import ca.fuwafuwa.gaku.Deinflictor.Deinflector
import ca.fuwafuwa.gaku.LangUtils
import java.sql.SQLException
import java.util.ArrayList
import kotlin.collections.HashSet
import kotlin.collections.List
import kotlin.collections.filter
import kotlin.collections.sortedWith

/**
 * Created by 0xbad1d3a5 on 12/16/2016.
 */

class JmTask @Throws(SQLException::class)
constructor(private val mSearchInfo: SearchInfo, private val mSearchJmTaskDone: SearchJmTaskDone, context: Context) : AsyncTask<Void, Void, List<JmSearchResult>>()
{
    companion object
    {
        private val TAG = JmTask::class.java.getName()

        /**
         * Best first, in the order Yomitan sorts by: the most text matched (see [matchedLength]), then
         * not converted from katakana, then the fewest deinflections, then matched by written form
         * before reading (の the particle before 野), then, of those matched by reading, words usually
         * written in kana (行く before 幾); then the primary written form and JMdict's priority tags.
         * KANJIDIC's single kanji come after JMdict.
         */
        fun rank(results: List<JmSearchResult>) : List<JmSearchResult>
        {
            val longestWritten = results.filter { !it.byReading && it.entry.dictionary == DB_JMDICT_NAME }.maxOfOrNull { it.word.length } ?: 0

            return results.sortedWith(compareBy(
                    { getDictPriority(it) },
                    { 0 - matchedLength(it, longestWritten) },
                    { it.converted },
                    { ReadingLookup.deinflectionSteps(it.deinfInfo) },
                    { it.byReading },
                    { it.byReading && !isUsuallyKana(it.entry) },
                    { getEntryPriority(it) },
                    { getPriority(it) }))
        }

        /**
         * How much text [result] counts as matching. Matching a reading is weaker evidence than matching
         * the written form, since kana text can run across words: in もうだめだ, 猛打 (もうだ) spans もう
         * and だ, and in にいく, 二位 (にい) spans に and いく. So a word found by a reading other than its
         * first (新 by にい), or a rare one, counts for nothing against the written forms. Unless the word
         * is usually written in kana (駄目, 凄い), so kana is its normal spelling, a common one has to beat
         * a word written in kana of two or more characters ([longestWritten]) by two characters, not one.
         */
        private fun matchedLength(result: JmSearchResult, longestWritten: Int) : Int
        {
            val length = result.word.length
            return when
            {
                !result.byReading -> length
                !matchesFirstReading(result) -> 0
                isUsuallyKana(result.entry) -> length
                getPriority(result) == Int.MAX_VALUE -> 0
                longestWritten >= 2 -> length - 1
                else -> length
            }
        }

        /** Whether [result] was found by its entry's first, usual reading (よけい for 余計, not にい for 新) */
        private fun matchesFirstReading(result: JmSearchResult) : Boolean
        {
            val first = result.entry.readings.substringBefore(", ")
            return LangUtils.ConvertKanatanaToHiragana(first) == LangUtils.ConvertKanatanaToHiragana(result.deinfInfo.word)
        }

        /** Whether JMdict marks [entry] as usually written in kana: the generator keeps that as a "uk" tag */
        private fun isUsuallyKana(entry: EntryOptimized) : Boolean
        {
            return entry.priorities.split(",").contains("uk")
        }

        private fun getDictPriority(result: JmSearchResult) : Int
        {
            return when
            {
                result.entry.dictionary == DB_JMDICT_NAME -> Int.MAX_VALUE - 2
                result.entry.dictionary == DB_KANJIDICT_NAME -> Int.MAX_VALUE - 1
                else -> Int.MAX_VALUE
            }
        }

        fun getEntryPriority(result: JmSearchResult) : Int
        {
            return if (result.entry.isPrimaryEntry) 0 else 1
        }

        fun getPriority(result: JmSearchResult) : Int
        {
            val priorities = result.entry.priorities.split(",")
            var lowestPriority = Int.MAX_VALUE

            for (priority in priorities){

                var pri = Int.MAX_VALUE

                if (priority.contains("nf")){ // looks like the range is nf01-nf48
                    pri = priority.substring(2).toInt()
                }
                else if (priority == "news1"){
                    pri = 60
                }
                else if (priority == "news2"){
                    pri = 70
                }
                else if (priority == "ichi1"){
                    pri = 80
                }
                else if (priority == "ichi2"){
                    pri = 90
                }
                else if (priority == "spec1"){
                    pri = 100
                }
                else if (priority == "spec2"){
                    pri = 110
                }
                else if (priority == "gai1"){
                    pri = 120
                }
                else if (priority == "gai2"){
                    pri = 130
                }

                lowestPriority = if (pri < lowestPriority) pri else lowestPriority
            }

            return lowestPriority
        }
    }

    private val mJmDbHelper: JmDatabaseHelper = JmDatabaseHelper.instance(context)
    private val mDeinflector: Deinflector = Deinflector(context)

    interface SearchJmTaskDone
    {
        fun jmTaskCallback(results: List<JmSearchResult>, searchInfo: SearchInfo)
    }

    override fun doInBackground(vararg params: Void): List<JmSearchResult>
    {
        return try
        {
            lookUp()
        }
        catch (e: Exception)
        {
            // Thrown from an AsyncTask, this would take the whole process down
            Log.e(TAG, "Dictionary lookup failed", e)
            emptyList()
        }
    }

    private fun lookUp(): List<JmSearchResult>
    {
        val text = mSearchInfo.text
        val textOffset = mSearchInfo.textOffset
        val entryOptimizedDao = mJmDbHelper.getDbDao<EntryOptimized>(EntryOptimized::class.java)

        val startDictTime = System.currentTimeMillis()
        var character = String(intArrayOf(text.codePointAt(textOffset)), 0, 1)

        // What the flying fuck? Wasn't the entire point of using an ORM is so shit would be escaped for me?
        character = character.replace("%", "\\%")
        character = character.replace("_", "\\_")
        character = character.replace("'", "''")

        val entries: List<EntryOptimized> = entryOptimizedDao.queryBuilder().where().like("kanji", "$character%").query()
        val matchedEntries = rank(getMatchedEntries(text, textOffset, entries))
        Log.d(TAG, "Dict lookup time: ${System.currentTimeMillis() - startDictTime}")

        return matchedEntries
    }

    override fun onPostExecute(result: List<JmSearchResult>)
    {
        mSearchJmTaskDone.jmTaskCallback(result, mSearchInfo)
    }

    @Throws(SQLException::class)
    private fun getMatchedEntries(text: String, textOffset: Int, entries: List<EntryOptimized>): List<JmSearchResult>
    {
        val end = if (textOffset + 80 >= text.length) text.length else textOffset + 80
        var word = text.substring(textOffset, end)
        val seenEntries = HashSet<EntryOptimized>()
        val results = ArrayList<JmSearchResult>()

        while (word.isNotEmpty())
        {
            // Find deinflections and add them
            val deinfResultsList: List<DeinflectionInfo> = mDeinflector.getPotentialDeinflections(word)
            var count = 0
            for (deinfInfo in deinfResultsList)
            {
                val filteredEntry: List<EntryOptimized> = entries.filter { entry -> entry.kanji == deinfInfo.word }

                if (filteredEntry.isEmpty())
                {
                    continue
                }

                for (entry in filteredEntry){

                    if (seenEntries.contains(entry)){
                        continue
                    }

                    var valid = true

                    if (count > 0)
                    {
                        valid = ReadingLookup.fitsPartOfSpeech(entry.pos, deinfInfo.type)
                    }

                    if (valid){
                        results.add(JmSearchResult(entry, deinfInfo, word))
                        seenEntries.add(entry)
                    }

                    count++
                }
            }

            // Add all exact matches as well
            val filteredEntry: List<EntryOptimized> = entries.filter { entry -> entry.kanji == word }
            for (entry in filteredEntry)
            {
                if (seenEntries.contains(entry))
                {
                    continue
                }

                results.add(JmSearchResult(entry, DeinflectionInfo(word, 0, ""), word))
                seenEntries.add(entry)
            }

            word = word.substring(0, word.length - 1)
        }

        results.addAll(getReadingMatches(text, textOffset, results))

        return results
    }

    /**
     * Words written in kana that the dictionary has under kanji (よけい → 余計), found by reading, as
     * Yomitan does; [found] are the matches by written form, which aren't repeated. A failure here,
     * say a dictionary without the reading index, only loses these.
     */
    private fun getReadingMatches(text: String, textOffset: Int, found: List<JmSearchResult>): List<JmSearchResult>
    {
        val run = ReadingLookup.kanaRun(text, textOffset)
        if (run.length < 2) return emptyList()

        return try
        {
            val candidates = ReadingLookup.candidates(run) { mDeinflector.getPotentialDeinflections(it) }.groupBy { it.reading }

            // Batched: older SQLite takes at most 999 values in a query
            val readingDao = mJmDbHelper.getDbDao<EntryReading>(EntryReading::class.java)
            val rows = candidates.keys.chunked(500).flatMap { readingDao.queryBuilder().where().`in`("reading", it).query() }
            val entryDao = mJmDbHelper.getDbDao<EntryOptimized>(EntryOptimized::class.java)
            val entries = rows.map { it.entryId }.distinct().chunked(500)
                    .flatMap { entryDao.queryBuilder().where().`in`("id", it).query() }
                    .associateBy { it.id }

            // Every way an entry was reached, best first, then one result for each entry
            val seen = found.mapTo(HashSet()) { it.entry.id }
            val matches = rows.flatMap { row ->
                val entry = entries[row.entryId] ?: return@flatMap emptyList()
                candidates[row.reading].orEmpty()
                        .filter { it.deinfInfo.reason.isEmpty() || ReadingLookup.fitsPartOfSpeech(entry.pos, it.deinfInfo.type) }
                        .map { JmSearchResult(entry, it.deinfInfo, it.source, byReading = true, converted = it.converted) }
            }
            val best = rank(matches).filter { seen.add(it.entry.id) }

            // Short readings match many words (かわ: 川, 皮, 革, 側, 河...): keep the commonest few of each length
            best.groupBy { it.word.length }.values.flatMap { sameLength ->
                sameLength.sortedWith(compareBy({ getEntryPriority(it) }, { getPriority(it) })).take(ReadingLookup.MAX_PER_LENGTH)
            }
        }
        catch (e: Exception)
        {
            Log.e(TAG, "Lookup by reading failed", e)
            emptyList()
        }
    }
}




