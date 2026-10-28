package ca.fuwafuwa.gaku.Llm

import org.junit.Assert.assertEquals
import org.junit.Test

class MarkdownTest
{
    private fun StyledText.boldText() = bold.map { text.substring(it) }
    private fun StyledText.italicText() = italic.map { text.substring(it) }

    @Test
    fun boldAndItalicLoseTheirMarkers()
    {
        val styled = parseMarkdown("The *ga* particle, **が (ga):** marks the subject")
        assertEquals("The ga particle, が (ga): marks the subject", styled.text)
        assertEquals(listOf("が (ga):"), styled.boldText())
        assertEquals(listOf("ga"), styled.italicText())
    }

    @Test
    fun listItemsGetBulletsAndKeepTheirIndent()
    {
        val styled = parseMarkdown("*   **明日 (ashita):** Tomorrow\n    *   **〜た (ta):** past\n- dash")
        assertEquals("• 明日 (ashita): Tomorrow\n    • 〜た (ta): past\n• dash", styled.text)
        assertEquals(listOf("明日 (ashita):", "〜た (ta):"), styled.boldText())
    }

    @Test
    fun headingsAreBold()
    {
        val styled = parseMarkdown("## Overall structure\nTopic + verb")
        assertEquals("Overall structure\nTopic + verb", styled.text)
        assertEquals(listOf("Overall structure"), styled.boldText())
    }

    @Test
    fun loneAsterisksAndUnclosedMarkersStay()
    {
        // As an answer streams in, ** may not be closed yet
        assertEquals("2 * 3 = 6, **still", parseMarkdown("2 * 3 = 6, **still").text)
        assertEquals("Use 持っていく here", parseMarkdown("Use `持っていく` here").text)
    }
}
