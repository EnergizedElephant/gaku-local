package ca.fuwafuwa.gaku.Ocr

import org.junit.Assert.assertEquals
import org.junit.Test

class FuriganaTest
{
    private class Line(val text: String, vararg val box: Int)

    private fun furigana(vararg lines: Line) = OcrRunnable.findFurigana(lines.toList()) { it.box }.keys.map { lines[it].text }.toSet()

    // Line boxes as ML Kit reported them for the furigana test page

    @Test
    fun furiganaBesideVerticalColumns()
    {
        assertEquals(setOf("きょう", "ほんとう", "たの"), furigana(
                Line("きょう", 242, 106, 259, 162),
                Line("ほんとう", 241, 237, 261, 314),
                Line("今日は本当に", 190, 89, 240, 365),
                Line("たの", 148, 93, 167, 130),
                Line("楽しかったね", 98, 90, 145, 364)))
    }

    @Test
    fun furiganaOverlappingTheColumnOrOneCharacterLong()
    {
        assertEquals(setOf("べんきょう", "あ"), furigana(
                Line("べんきょう", 185, 165, 212, 258),
                Line("勉強は", 143, 168, 190, 293),
                Line("あ", 109, 158, 125, 175),
                Line("会おう!", 56, 145, 106, 312)))
    }

    @Test
    fun furiganaAboveHorizontalText()
    {
        assertEquals(setOf("とうきょう"), furigana(
                Line("とうきょう", 80, 100, 160, 120),
                Line("東京に行く", 75, 125, 330, 170)))
    }

    @Test
    fun columnsOfTheSameSizeAreText()
    {
        assertEquals(emptySet<String>(), furigana(
                Line("たい", 584, 819, 652, 955),
                Line("食べに行き", 668, 819, 736, 1159),
                Line("ラーメンを", 752, 819, 820, 1159)))
    }

    @Test
    fun smallTextElsewhereIsText()
    {
        // A smaller bubble two columns' width to the right
        assertEquals(emptySet<String>(), furigana(
                Line("小さい声", 400, 100, 420, 180),
                Line("大きい声で", 300, 100, 350, 350)))
    }

    @Test
    fun smallTextLeftOfAColumnIsText()
    {
        // Furigana sits to the right of vertical text; to the left is the next column
        assertEquals(emptySet<String>(), furigana(
                Line("次", 270, 100, 290, 140),
                Line("前の列", 300, 100, 350, 350)))
    }
}
