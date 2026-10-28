@file:JvmName("Prompts")

package ca.fuwafuwa.gaku.Llm

private const val OCR_NOTE = "It was read by OCR from a manga, game or other screen, so it may contain misread characters."

fun translationMessages(text: String): List<LlmMessage>
{
    return listOf(
            LlmMessage("system", "Translate the user's Japanese text into natural English. $OCR_NOTE Translate what was most likely meant. Reply with only the translation."),
            LlmMessage("user", text))
}

/**
 * Starts a conversation about [sentence]. [word] and [entry] are the word last looked up and its
 * dictionary entry as the results window shows it, and [translation] the LLM's translation, if any.
 */
fun chatSystemMessage(sentence: String, word: String?, entry: String?, translation: String?): LlmMessage
{
    val sb = StringBuilder()
    sb.append("You are helping an English speaker who is learning Japanese to understand a sentence they are reading. ")
    sb.append(OCR_NOTE).append(" If part of it looks misread, say what was probably meant; otherwise don't mention it.\n\n")
    sb.append("The sentence:\n").append(sentence).append("\n")

    if (!word.isNullOrEmpty())
    {
        sb.append("\nThe reader looked up ").append(word)
        if (!entry.isNullOrEmpty()) sb.append(", which a dictionary gives as:\n").append(entry)
        sb.append("\n")
    }

    if (!translation.isNullOrEmpty())
    {
        sb.append("\nA machine translation of the sentence:\n").append(translation).append("\n")
    }

    // The chat window shows bold, italics, headings and lists (renderMarkdown), but not tables
    sb.append("\nAnswer clearly and concisely. Don't use tables.")
    return LlmMessage("system", sb.toString())
}

/** Questions the chat window offers as buttons: the label, then what is asked. */
val QUICK_QUESTIONS = listOf(
        "Explain the grammar" to "Explain the grammar of this sentence. Break it into parts, and explain each particle, conjugation and grammar pattern.",
        "Word by word" to "Go through the sentence word by word: each word's reading, dictionary form and meaning here.",
        "Nuance" to "What are the nuance and tone of this sentence? How formal is it, what kind of person would say it like this, and what does it imply?")
