package ca.fuwafuwa.gaku.Ocr

import org.junit.Assert.assertEquals
import org.junit.Test

class ReadingOrderTest
{
    private class Line(val text: String, vararg val box: Int)

    private fun order(vararg lines: Line) = OcrRunnable.inReadingOrder(lines.toList()) { it.box }.map { it.text }

    @Test
    fun verticalColumnsReadRightToLeft()
    {
        // As ML Kit returns them: leftmost column first
        assertEquals(listOf("ラーメンを", "食べに行き", "たい"), order(
                Line("たい", 584, 819, 652, 955),
                Line("食べに行き", 668, 819, 736, 1159),
                Line("ラーメンを", 752, 819, 820, 1159)))
    }

    @Test
    fun shortLastColumnStillVertical()
    {
        assertEquals(listOf("それは無理", "だよ"), order(
                Line("だよ", 210, 858, 270, 978),
                Line("それは無理", 290, 858, 350, 1158)))
    }

    @Test
    fun horizontalLinesKeepTheirOrder()
    {
        assertEquals(listOf("東京の天気は", "晴れです。"), order(
                Line("東京の天気は", 80, 134, 380, 182),
                Line("晴れです。", 80, 190, 330, 238)))
    }

    @Test
    fun singleCharactersKeepTheirOrder()
    {
        assertEquals(listOf("あ", "い"), order(
                Line("あ", 100, 100, 148, 150),
                Line("い", 200, 100, 248, 150)))
    }
}
