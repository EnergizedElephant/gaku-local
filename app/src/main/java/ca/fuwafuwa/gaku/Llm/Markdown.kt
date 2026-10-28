@file:JvmName("Markdown")

package ca.fuwafuwa.gaku.Llm

import android.graphics.Typeface
import android.text.SpannableString
import android.text.Spanned
import android.text.style.StyleSpan

/** [text] with the character ranges to show in bold and in italics. */
internal class StyledText(val text: String, val bold: List<IntRange>, val italic: List<IntRange>)

private val HEADING = Regex("""#{1,6}\s+(.*)""")
private val BULLET = Regex("""(\s*)[*+-]\s+(.*)""")
// **bold**, *italics* (not a lone * between spaces) and `code`, whose backticks are just dropped
private val INLINE = Regex("""\*\*(.+?)\*\*|\*([^*\s](?:[^*]*[^*\s])?)\*|`([^`]+)`""")

/**
 * The Markdown models use in answers, as styled text: # headings and **bold** in bold, *italics*, and
 * bullets for * and - list items. Anything else, such as tables, is left as it is.
 */
fun renderMarkdown(markdown: String): CharSequence
{
    val styled = parseMarkdown(markdown)
    val spannable = SpannableString(styled.text)
    for (range in styled.bold) spannable.setSpan(StyleSpan(Typeface.BOLD), range.first, range.last + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    for (range in styled.italic) spannable.setSpan(StyleSpan(Typeface.ITALIC), range.first, range.last + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    return spannable
}

internal fun parseMarkdown(markdown: String): StyledText
{
    val sb = StringBuilder()
    val bold = ArrayList<IntRange>()
    val italic = ArrayList<IntRange>()

    markdown.lines().forEachIndexed { i, rawLine ->
        if (i > 0) sb.append('\n')

        var line = rawLine
        val heading = HEADING.matchEntire(line)
        if (heading != null) line = heading.groupValues[1]
        else BULLET.matchEntire(line)?.let { line = it.groupValues[1] + "• " + it.groupValues[2] }

        val lineStart = sb.length
        var last = 0
        for (match in INLINE.findAll(line))
        {
            sb.append(line, last, match.range.first)
            val (boldText, italicText, codeText) = match.destructured
            val start = sb.length
            when
            {
                match.groups[1] != null -> { sb.append(boldText); bold.add(start until sb.length) }
                match.groups[2] != null -> { sb.append(italicText); italic.add(start until sb.length) }
                else -> sb.append(codeText)
            }
            last = match.range.last + 1
        }
        sb.append(line, last, line.length)

        if (heading != null && sb.length > lineStart) bold.add(lineStart until sb.length)
    }

    return StyledText(sb.toString(), bold, italic)
}
