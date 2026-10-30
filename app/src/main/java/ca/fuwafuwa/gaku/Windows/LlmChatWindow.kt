package ca.fuwafuwa.gaku.Windows

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.util.TypedValue
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

import ca.fuwafuwa.gaku.Llm.LlmMessage
import ca.fuwafuwa.gaku.Llm.OllamaClient
import ca.fuwafuwa.gaku.Llm.QUICK_QUESTIONS
import ca.fuwafuwa.gaku.Llm.chatSystemMessage
import ca.fuwafuwa.gaku.Llm.renderMarkdown
import ca.fuwafuwa.gaku.R
import ca.fuwafuwa.gaku.dpToPx
import ca.fuwafuwa.gaku.getPrefs

/**
 * A conversation with the LLM about one sentence (lan build), opened by Ask in the results window,
 * which stays underneath. The conversation is only kept in memory, until another sentence is opened.
 */
class LlmChatWindow(context: Context, windowCoordinator: WindowCoordinator) : Window(context, windowCoordinator, R.layout.window_llm_chat)
{
    private val mSentenceText: TextView = window.findViewById(R.id.chat_sentence)
    private val mScroll: ScrollView = window.findViewById(R.id.chat_scroll)
    private val mMessageViews: LinearLayout = window.findViewById(R.id.chat_messages)
    private val mInput: EditText = window.findViewById(R.id.chat_input)
    private val mSendButton: TextView = window.findViewById(R.id.chat_send)

    private var mSentence: String? = null
    // What the model is sent each time: the system message about the sentence, then the conversation
    private val mMessages = ArrayList<LlmMessage>()
    private var mCall: OllamaClient.Call? = null
    private var mAnswer = StringBuilder()

    init
    {
        // Send sits in the corner the resize handle would take
        window.findViewById<View>(R.id.resize_view).visibility = View.GONE

        window.findViewById<View>(R.id.chat_close).setOnClickListener { hide() }
        mSendButton.setOnClickListener {
            if (mCall != null)
            {
                stopAnswer()
            }
            else
            {
                val question = mInput.text.toString().trim()
                if (question.isNotEmpty()) ask(question)
            }
        }

        // The keyboard takes the first Back; the next closes the chat
        mInput.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) hide()
            keyCode == KeyEvent.KEYCODE_BACK
        }

        val quickQuestions = window.findViewById<LinearLayout>(R.id.chat_quick)
        for ((label, question) in QUICK_QUESTIONS)
        {
            val button = LayoutInflater.from(context).inflate(R.layout.view_info_button, quickQuestions, false) as TextView
            button.text = label
            button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            button.setOnClickListener { if (mCall == null) ask(question) }
            quickQuestions.addView(button)
        }
    }

    /**
     * Shows the conversation about [sentence], starting a new one if it's a different sentence. [word],
     * [entry] and [translation] are what the model is told besides the sentence; see [chatSystemMessage].
     */
    fun open(sentence: String, word: String?, entry: String?, translation: String?)
    {
        val system = chatSystemMessage(sentence, word, entry, translation)
        if (sentence != mSentence)
        {
            stopAnswer()
            mSentence = sentence
            mMessages.clear()
            mMessages.add(system)
            mMessageViews.removeAllViews()
            mInput.setText("")
        }
        else
        {
            // The same sentence, with perhaps another word looked up since
            mMessages[0] = system
        }

        mSentenceText.text = sentence
        show()
    }

    private fun ask(question: String)
    {
        val prefs = getPrefs(context)
        mInput.setText("")
        addMessageView(question, true)

        if (!prefs.llmConfigured)
        {
            addMessageView("Set an Ollama server on Gaku's start screen first.", false)
            return
        }

        mMessages.add(LlmMessage("user", question))
        val answerView = addMessageView("…", false)
        mAnswer = StringBuilder()
        mSendButton.text = "Stop"

        mCall = OllamaClient(prefs.llmServer, prefs.llmApiKey).chat(prefs.llmModel, mMessages.toList(), object : OllamaClient.ChatListener
        {
            override fun onThinking()
            {
                answerView.text = "Thinking…"
            }

            override fun onText(text: String)
            {
                mAnswer.append(text)
                setFollowingScroll(answerView, renderMarkdown(mAnswer.toString().trim()))
            }

            override fun onDone()
            {
                if (mAnswer.isBlank()) answerView.text = "The model didn't answer"
                endAnswer()
            }

            override fun onError(message: String)
            {
                answerView.text = message
                endAnswer()
            }
        })
    }

    /** Stops the answer being written, keeping what has arrived. */
    private fun stopAnswer()
    {
        val call = mCall ?: return
        call.cancel()
        endAnswer()
    }

    /**
     * Adds the answer to the conversation. Without one (an error, or stopped before any text), the
     * question is taken back out too, so the model never sees a question without an answer.
     */
    private fun endAnswer()
    {
        mCall = null
        mSendButton.text = "Send"

        val answer = mAnswer.toString().trim()
        if (answer.isNotEmpty()) mMessages.add(LlmMessage("assistant", answer))
        else if (mMessages.last().role == "user") mMessages.removeAt(mMessages.size - 1)
        mAnswer = StringBuilder()
    }

    private fun addMessageView(text: String, fromUser: Boolean): TextView
    {
        val view = TextView(context)
        view.setTextColor(context.getColor(android.R.color.black))
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        val padding = dpToPx(context, 8)
        view.setPadding(padding, padding, padding, padding)
        // Questions are shaded, answers plain
        if (fromUser) view.setBackgroundColor(0xFFF0F0F0.toInt())

        val params = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        params.topMargin = padding
        mMessageViews.addView(view, params)
        setFollowingScroll(view, text, true)
        return view
    }

    /** Sets [view]'s text, keeping the conversation scrolled to the end if it was there (or [always]). */
    private fun setFollowingScroll(view: TextView, text: CharSequence, always: Boolean = false)
    {
        val atEnd = always || !mScroll.canScrollVertically(1)
        view.text = text
        if (atEnd) mScroll.post { mScroll.smoothScrollTo(0, mMessageViews.height) }
    }

    override fun hide()
    {
        stopAnswer()
        super.hide()
    }

    override fun stop()
    {
        mCall?.cancel()
        super.stop()
    }

    override fun onTouch(e: MotionEvent): Boolean
    {
        return false
    }

    override fun onDown(e: MotionEvent): Boolean
    {
        return false
    }

    override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean
    {
        return false
    }

    override fun onResize(e: MotionEvent): Boolean
    {
        return false
    }

    /**
     * LlmChatWindow does not need to reInit layout as its getDefaultParams() are all relative.
     */
    override fun reInit(options: Window.ReinitOptions)
    {
        options.reinitViewLayout = false
        super.reInit(options)
    }

    /**
     * Focusable for the keyboard, like [EditWindow], and resized to stay above it
     */
    override fun getDefaultParams(): WindowManager.LayoutParams
    {
        val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                if (Build.VERSION.SDK_INT > 25) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE,
                0,
                PixelFormat.TRANSLUCENT)
        params.x = 0
        params.y = 0
        params.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        return params
    }
}
