package ca.fuwafuwa.gaku.Ocr

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Message
import android.util.Log
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import ca.fuwafuwa.gaku.*
import ca.fuwafuwa.gaku.Database.JmDictDatabase.JmDatabaseHelper
import ca.fuwafuwa.gaku.Database.JmDictDatabase.Models.EntryOptimized
import ca.fuwafuwa.gaku.Interfaces.Stoppable
import ca.fuwafuwa.gaku.MainService
import ca.fuwafuwa.gaku.Windows.CaptureWindow
import ca.fuwafuwa.gaku.Windows.Data.ChoiceCertainty
import ca.fuwafuwa.gaku.Windows.Data.DisplayDataOcr
import ca.fuwafuwa.gaku.Windows.Data.SquareCharOcr

class OcrRunnable(context: Context, private var mCaptureWindow: CaptureWindow?) : Runnable, Stoppable {
    private val mContext: MainService = context as MainService
    private val mOcrLock = java.lang.Object()
    private val mSimilarChars = loadSimilarChars()
    private val mCommonMistakes = loadCommonMistakes()
    private var mTextRecognizer: com.google.mlkit.vision.text.TextRecognizer? = null
    private var mThreadRunning = true
    private var mOcrParams: OcrParams? = null
    private var mIsReady = false

    val isReadyForOcr: Boolean
        get() = mOcrParams == null

    init {
        mOcrParams = null
    }

    fun warmUp() {
        if (mIsReady) return

        Log.d(TAG, "Warming up OCR engine.")

        // safeInitClient returns true if successful, false if it crashed
        if (!safeInitClient()) {
            sendToastToContext("Failed to initialize MLKit (Check Logs)")
            return
        }

        // ML Kit rejects images smaller than 32x32 without loading its model
        val dummyBitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        val image = InputImage.fromBitmap(dummyBitmap, 0)

        // Force non-null assertion (!!) is safe here because safeInitClient returned true
        mTextRecognizer!!.process(image)
            .addOnSuccessListener {
                Log.d(TAG, "OCR engine is ready.")
                mIsReady = true
                sendModelReadyBroadcast()
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "OCR engine warm-up failed.", e)
                mIsReady = true // Mark ready anyway to unblock UI
                sendModelReadyBroadcast()
            }
    }

    private fun safeInitClient(): Boolean {
        if (mTextRecognizer != null) return true

        return try {
            val options = JapaneseTextRecognizerOptions.Builder().build()
            mTextRecognizer = TextRecognition.getClient(options)
            true
        } catch (e: Throwable) {
            // This catch block is CRITICAL. It catches UnsatisfiedLinkError (Native crash)
            // and standard Exceptions, printing them to Logcat so you can finally see them.
            Log.e(TAG, "CRITICAL ERROR: Could not create TextRecognition client", e)
            e.printStackTrace()
            false
        }
    }

    private fun sendModelReadyBroadcast() {
        val intent = Intent(ACTION_MODEL_READY)
        LocalBroadcastManager.getInstance(mContext).sendBroadcast(intent)
    }

    override fun run() {
        // 1. Initialize safely on the background thread.
        // If this fails, we exit the thread to prevent a crash loop.
        if (!safeInitClient()) {
            Log.e(TAG, "Stopping OcrRunnable because OCR Client failed to initialize.")
            return
        }

        while (mThreadRunning) {
            try {
                synchronized(mOcrLock) {
                    if (mOcrParams == null) {
                        mOcrLock.wait()
                    }

                    if (!mThreadRunning) return

                    // Local capture to ensure thread safety
                    val ocrParams = mOcrParams
                    // Safely unwrap the recognizer (we know it's not null due to safeInitClient)
                    val recognizer = mTextRecognizer

                    if (ocrParams != null && recognizer != null) {
                        Log.d(TAG, "Processing OCR with params $ocrParams")
                        val startTime = System.currentTimeMillis()
                        mCaptureWindow?.showLoadingAnimation()

                        try {
                            // 2. Prepare the InputImage
                            val image = InputImage.fromBitmap(ocrParams.bitmap, 0)

                            // 3. Process blocking (Tasks.await is safe here in background thread)
                            val result = Tasks.await(recognizer.process(image), 10, TimeUnit.SECONDS)

                            val displayData = getDisplayData(ocrParams, result)
                            processDisplayData(displayData)

                            if (displayData.text.isNotEmpty()) {
                                val ocrTime = System.currentTimeMillis() - startTime
                                sendOcrResultToContext(OcrResult(displayData, ocrTime))
                            } else {
                                sendToastToContext("No Characters Recognized.")
                            }
                        } catch (e: Exception) {
                            when (e) {
                                is ExecutionException, is InterruptedException, is TimeoutException -> {
                                    Log.e(TAG, "OCR failed", e)
                                    sendToastToContext("OCR Failed: ${e.message}")
                                }
                                else -> {
                                    Log.e(TAG, "Unexpected OCR Error", e)
                                    // Don't throw; just log so the thread keeps living
                                }
                            }
                        } finally {
                            mCaptureWindow?.stopLoadingAnimation(ocrParams.instantMode)
                            mOcrParams = null
                        }
                    }
                }
            } catch (e: InterruptedException) {
                Log.d(TAG, "OcrRunnable interrupted, shutting down.")
                mThreadRunning = false
            } catch (e: Exception) {
                // Catch global exceptions to keep the thread alive
                Log.e(TAG, "General Error in OcrRunnable Loop", e)
                e.printStackTrace()
            }
        }

        Log.d(TAG, "THREAD STOPPED")
        mTextRecognizer?.close()
    }

    fun runTess(ocrParams: OcrParams) {
        synchronized(mOcrLock) {
            if (!mThreadRunning) return
            mOcrParams = ocrParams
            mOcrLock.notify()
            Log.d(TAG, "NOTIFIED")
        }
    }

    fun cancel() {
        Log.d(TAG, "CANCELED (Not implemented for ML Kit)")
    }

    override fun stop() {
        synchronized(mOcrLock) {
            mThreadRunning = false
            mCaptureWindow = null
            mOcrParams = null
            mOcrLock.notify()
        }
    }

    private fun processDisplayData(displayData: DisplayDataOcr) {
        val squareChars = displayData.squareChars.filterIsInstance<SquareCharOcr>()
        for (squareChar in squareChars) {
            mSimilarChars[squareChar.char]?.forEach {
                squareChar.addChoice(it, ChoiceCertainty.UNCERTAIN)
            }
        }

        for (squareChar in squareChars) {
            correctCommonMistake(squareChar, "く")
            correctCommonMistake(squareChar, "し")
            correctCommonMistake(squareChar, "じ")
            correctCommonMistake(squareChar, "え")
            correctCommonMistake(squareChar, "、")
            correctCommonMistake(squareChar, "。")
            correctKanjiOne(squareChar)
            correctKatakanaDash(squareChar)
        }
    }

    private fun correctCommonMistake(squareChar: SquareCharOcr, char: String) {
        if (mCommonMistakes[squareChar.char] == char) {
            val prev = squareChar.prev
            val next = squareChar.next
            if (prev?.char?.let { LangUtils.IsJapaneseChar(it[0]) } == true ||
                next?.char?.let { LangUtils.IsJapaneseChar(it[0]) } == true) {
                squareChar.addChoice(char, ChoiceCertainty.CERTAIN)
            }
        }
    }

    private fun correctKatakanaDash(squareChar: SquareCharOcr) {
        if (mCommonMistakes[squareChar.char] != null) {
            val prev = squareChar.prev
            if (prev?.char?.let { LangUtils.IsKatakana(it[0]) } == true) {
                squareChar.addChoice("ー", ChoiceCertainty.CERTAIN)
            }
        }
    }

    private fun correctKanjiOne(squareChar: SquareCharOcr) {
        if (mCommonMistakes[squareChar.char] != null) {
            val next = squareChar.next
            if (next?.char?.let { LangUtils.IsKanji(it[0]) || LangUtils.IsHiragana(it[0]) } == true) {
                squareChar.addChoice("一", ChoiceCertainty.CERTAIN)
            }
        }
    }

    private fun getDisplayData(ocrParams: OcrParams, visionText: Text): DisplayDataOcr {
        val bitmap = ocrParams.originalBitmap
        val boxParams = ocrParams.box
        val ocrChars = ArrayList<SquareCharOcr>()
        val displayData = DisplayDataOcr(bitmap, boxParams, ocrParams.instantMode, ocrChars)

        // 1. Sort the BLOCKS, not the individual symbols.
        // This preserves the internal Left-to-Right order of horizontal text,
        // while still ordering the blocks themselves in Japanese/Manga order (Right-to-Left, Top-to-Bottom).
        val sortedBlocks = visionText.textBlocks.sortedWith(Comparator { a, b ->
            val rectA = a.boundingBox ?: return@Comparator 0
            val rectB = b.boundingBox ?: return@Comparator 0

            // If blocks are vertically aligned (similar X coordinates), sort Top-to-Bottom.
            // If they are horizontally separated, sort Right-to-Left (Manga style).
            val threshold = 20 

            if (Math.abs(rectA.left - rectB.left) > threshold) {
                // Right to Left (Manga Columns)
                rectB.left - rectA.left
            } else {
                // Top to Bottom (Standard Rows)
                rectA.top - rectB.top
            }
        })

        // 2. Iterate through hierarchy to collect symbols in the correct order
        // ML Kit reads furigana as narrow lines (usually blocks of their own) beside the text they
        // annotate, and sorting puts them in among it. Leave them out of the recognised text.
        // Copy each block's lines once: ML Kit's lists hand out a new Line object on every access, and
        // lines are matched by identity below
        val lineBox = { line: Text.Line -> line.boundingBox?.let { intArrayOf(it.left, it.top, it.right, it.bottom) } }
        val blockLines = sortedBlocks.map { it.lines.toList() }
        val allLines = blockLines.flatten()
        // (furigana, the line it annotates); a line can have several
        val furiganaPairs = findFurigana(allLines, lineBox).map { (f, m) -> allLines[f] to allLines[m] }
        val furigana = Collections.newSetFromMap(IdentityHashMap<Text.Line, Boolean>())
        furiganaPairs.forEach { furigana.add(it.first) }
        // The characters recognised from each line, to match furigana to below
        val lineChars = IdentityHashMap<Text.Line, MutableList<SquareCharOcr>>()

        for (linesOfBlock in blockLines) {
            val lines = inReadingOrder(linesOfBlock.filterNot { it in furigana }, lineBox)
            for (line in lines) {
                val chars = lineChars.getOrPut(line) { ArrayList() }
                for (element in line.elements) {
                    for (symbol in element.symbols) {

                        val choices = ArrayList<Pair<String, Double>>()
                        choices.add(Pair(symbol.text, symbol.confidence.toDouble()))

                        val pos = symbol.boundingBox ?: android.graphics.Rect()

                        // Calculate relative position to the original full-screen bitmap
                        val char = SquareCharOcr(
                            displayData,
                            choices,
                            intArrayOf(
                                pos.left + ocrParams.offsetX,
                                pos.top + ocrParams.offsetY,
                                pos.right + ocrParams.offsetX,
                                pos.bottom + ocrParams.offsetY
                            )
                        )
                        ocrChars.add(char)
                        chars.add(char)
                    }
                }
            }
        }

        correctFromFurigana(furiganaPairs, lineChars, lineBox, ocrParams)
        displayData.assignIndicies()
        return displayData
    }

    /**
     * Uses furigana to fix the characters beside it. Looks up its reading (plus okurigana) in the
     * dictionary; where one spelling clearly fits what was recognised, swaps it in, keeping the
     * recognised character as an alternative. Otherwise the spellings that fit become alternatives.
     * E.g. 今目 with きょう becomes 今日, and 菜しい with むずか becomes 難しい.
     */
    private fun correctFromFurigana(furiganaPairs: List<Pair<Text.Line, Text.Line>>,
                                    lineChars: IdentityHashMap<Text.Line, MutableList<SquareCharOcr>>,
                                    lineBox: (Text.Line) -> IntArray?, ocrParams: OcrParams) {
        class Annotated(val reading: String, val chars: List<SquareCharOcr>, val following: List<SquareCharOcr>)

        val annotated = furiganaPairs.mapNotNull { (furigana, line) ->
            val chars = lineChars[line] ?: return@mapNotNull null
            val f = lineBox(furigana) ?: return@mapNotNull null
            val l = lineBox(line) ?: return@mapNotNull null
            val reading = LangUtils.ConvertKanatanaToHiragana(
                    furigana.text.filter { LangUtils.IsHiragana(it) || LangUtils.IsKatakana(it) })
            if (reading.isEmpty()) return@mapNotNull null

            // The characters whose centres lie alongside the furigana, give or take a quarter character
            val vertical = l[3] - l[1] > l[2] - l[0]
            val (start, end) = if (vertical) f[1] + ocrParams.offsetY to f[3] + ocrParams.offsetY
                               else f[0] + ocrParams.offsetX to f[2] + ocrParams.offsetX
            val slack = (if (vertical) l[2] - l[0] else l[3] - l[1]) / 4
            val beside = chars.indices.filter { i ->
                val p = chars[i].bitmapPos
                val centre = if (vertical) (p[1] + p[3]) / 2 else (p[0] + p[2]) / 2
                centre in (start - slack)..(end + slack)
            }
            if (beside.isEmpty()) return@mapNotNull null

            Annotated(reading, chars.subList(beside.first(), beside.last() + 1), chars.subList(beside.last() + 1, chars.size))
        }
        if (annotated.isEmpty()) return

        val words = lookUpWordsByReading(annotated.map { it.reading }.distinct())
        for (a in annotated) {
            val result = spellingsForFurigana(a.reading, a.chars.map { it.char }, a.following.map { it.char }, words)
            for ((n, spelling) in result.spellings.withIndex()) {
                val certainty = if (n == 0 && result.certain) ChoiceCertainty.CERTAIN else ChoiceCertainty.UNCERTAIN
                for ((i, c) in spelling.withIndex()) {
                    if (c != a.chars[i].char) a.chars[i].addChoice(c, certainty)
                }
            }
        }
    }

    /**
     * Dictionary words with a reading containing one of [readings]. One scan of the table for all
     * of them; spellingsForFurigana() picks out the exact readings.
     */
    private fun lookUpWordsByReading(readings: List<String>): List<FuriganaWord> {
        return try {
            val startTime = System.currentTimeMillis()
            val dao = JmDatabaseHelper.instance(mContext).getDbDao<EntryOptimized>(EntryOptimized::class.java)
            // Readings are stored as "きょう, こんにち, ..."; match any that starts with one of ours
            val sql = "SELECT kanji, readings, priorities FROM entryoptimized WHERE dictionary = ? AND (" +
                    readings.joinToString(" OR ") { "readings LIKE ? OR readings LIKE ?" } + ")"
            val args = arrayOf(DB_JMDICT_NAME) + readings.flatMap { listOf("$it%", "%, $it%") }
            dao.queryRaw(sql, *args).use { results ->
                results.map { FuriganaWord(it[0], it[1].split(", "), !it[2].isNullOrEmpty()) }
            }.also { Log.d(TAG, "Furigana lookup: ${readings.size} readings, ${it.size} words, ${System.currentTimeMillis() - startTime} ms") }
        } catch (e: Exception) {
            Log.e(TAG, "Furigana lookup failed", e)
            emptyList()
        }
    }

    private fun loadSimilarChars(): Map<String, List<String>> {
        val similarChars = HashMap<String, MutableList<String>>()
        for (list in OcrCorrection.CommonLookalikes) {
            for ((index, kana) in list.withIndex()) {
                if (list.size <= 1) continue

                val others = list.toMutableList().apply { removeAt(index) }
                val existing = similarChars.getOrPut(kana) { mutableListOf() }
                others.forEach {
                    if (!existing.contains(it)) {
                        existing.add(it)
                    }
                }
            }
        }

        // Then visually similar kanji, most similar first (see tools/similar_kanji.py)
        mContext.assets.open(SIMILAR_KANJI_ASSET).bufferedReader().useLines { lines ->
            for (line in lines) {
                if (line.startsWith("#")) continue
                val existing = similarChars.getOrPut(line.substringBefore(' ')) { mutableListOf() }
                for (kanji in line.substringAfter(' ')) {
                    if (!existing.contains(kanji.toString())) {
                        existing.add(kanji.toString())
                    }
                }
            }
        }
        return similarChars
    }

    private fun loadCommonMistakes(): HashMap<String, String> {
        val commonMistakes = HashMap<String, String>()
        for (pair in OcrCorrection.CommonMistakes) {
            for (c in pair.first) {
                commonMistakes[c] = pair.second
            }
        }
        return commonMistakes
    }

    private fun sendOcrResultToContext(result: OcrResult) {
        Message.obtain(mContext.handler, 0, result).sendToTarget()
    }

    private fun sendToastToContext(message: String) {
        Message.obtain(mContext.handler, 0, message).sendToTarget()
    }

    companion object {
        private val TAG = OcrRunnable::class.java.name
        const val ACTION_MODEL_READY = "ca.fuwafuwa.gaku.MODEL_READY"
        private const val SIMILAR_KANJI_ASSET = "similar_kanji.txt"

        /**
         * ML Kit returns each column of vertical text as a line, ordered left to right, but manga
         * columns read right to left. A block counts as vertical when its lines are clearly taller
         * than they are wide in total; otherwise (horizontal text, or too few characters to tell)
         * ML Kit's order is kept.
         * @param box a line's bounding box as left, top, right, bottom
         */
        internal fun <T> inReadingOrder(lines: List<T>, box: (T) -> IntArray?): List<T> {
            val boxes = lines.map(box)
            val totalHeight = boxes.sumOf { if (it == null) 0 else it[3] - it[1] }
            val totalWidth = boxes.sumOf { if (it == null) 0 else it[2] - it[0] }
            if (totalHeight * 2 <= totalWidth * 3) return lines

            // Right to left by horizontal centre
            return lines.indices.sortedByDescending { i -> boxes[i]?.let { it[0] + it[2] } ?: 0 }.map { lines[it] }
        }

        /**
         * The lines that look like furigana, each mapped to the line it annotates (by index in [lines]):
         * a line at most [FURIGANA_MAX_RATIO] as thick as a neighbouring line of text, just beside it
         * (right of a vertical column, above a horizontal line) and within its length.
         * @param box a line's bounding box as left, top, right, bottom
         */
        internal fun <T> findFurigana(lines: List<T>, box: (T) -> IntArray?): Map<Int, Int>
        {
            val boxes = lines.map(box)
            val furigana = HashMap<Int, Int>()

            for ((i, small) in boxes.withIndex())
            {
                if (small == null) continue
                for ((j, main) in boxes.withIndex())
                {
                    if (i == j || main == null) continue
                    if (isVerticalFurigana(small, main) || isHorizontalFurigana(small, main))
                    {
                        furigana[i] = j
                        break
                    }
                }
            }

            return furigana
        }

        private const val FURIGANA_MAX_RATIO = 0.65

        /** A dictionary word that may be spelled under some furigana. */
        internal class FuriganaWord(val kanji: String, val readings: List<String>, val common: Boolean)

        /**
         * Spellings for the characters furigana is written beside, from the dictionary words whose
         * reading is the furigana plus any okurigana (the hiragana right after them), best first:
         * most characters in common with what was recognised, then common words. [certain] is set when
         * one spelling clearly wins.
         */
        internal class FuriganaSpellings(val spellings: List<List<String>>, val certain: Boolean)

        internal fun spellingsForFurigana(reading: String, span: List<String>, following: List<String>,
                                         words: List<FuriganaWord>): FuriganaSpellings
        {
            val okurigana = following.takeWhile { it.length == 1 && LangUtils.IsHiragana(it[0]) }.take(4)

            // Longest okurigana first: 菜しい with むずか is 難しい (むずかしい), not a word read むずか
            for (k in okurigana.size downTo 0)
            {
                val oku = okurigana.take(k).joinToString("")
                val fits = words.filter { word ->
                    if ((reading + oku) !in word.readings || !word.kanji.endsWith(oku)) return@filter false
                    val chars = splitTextByChar(word.kanji)
                    chars.size == span.size + k && chars.take(span.size).all { it.length == 1 && LangUtils.IsKanji(it[0]) }
                }
                if (fits.isEmpty()) continue

                // (spelling, common word, characters in common with what was recognised)
                val ranked = fits
                        .map { word ->
                            val spelling = splitTextByChar(word.kanji).take(span.size)
                            Triple(spelling, word.common, spelling.indices.count { spelling[it] == span[it] })
                        }
                        .sortedWith(compareByDescending<Triple<List<String>, Boolean, Int>> { it.third }.thenByDescending { it.second })
                        .distinctBy { it.first }

                val best = ranked[0]
                val certain = ranked.size == 1 || best.third > ranked[1].third ||
                        (best.third == ranked[1].third && best.second && !ranked[1].second)
                return FuriganaSpellings(ranked.map { it.first }, certain)
            }

            return FuriganaSpellings(emptyList(), false)
        }

        private fun isVerticalFurigana(small: IntArray, main: IntArray): Boolean
        {
            val (sl, st, sr, sb) = small
            val (ml, mt, mr, mb) = main
            val mainWidth = mr - ml
            // A column, read top to bottom, and the furigana far thinner than it
            if (mb - mt <= mainWidth || sr - sl > mainWidth * FURIGANA_MAX_RATIO) return false

            // Starts at most a third of the column's width inside its right edge, and no further out
            // than one column width
            val tolerance = mainWidth / 3
            return sl >= mr - tolerance && sl - mr < mainWidth &&
                    st >= mt - tolerance && sb <= mb + tolerance
        }

        private fun isHorizontalFurigana(small: IntArray, main: IntArray): Boolean
        {
            val (sl, st, sr, sb) = small
            val (ml, mt, mr, mb) = main
            val mainHeight = mb - mt
            // A line, read left to right, and the furigana far shorter than it
            if (mr - ml <= mainHeight || sb - st > mainHeight * FURIGANA_MAX_RATIO) return false

            // Ends at most a third of the line's height below its top edge, and no further out than
            // one line height
            val tolerance = mainHeight / 3
            return sb <= mt + tolerance && mt - sb < mainHeight &&
                    sl >= ml - tolerance && sr <= mr + tolerance
        }
    }
}




