package ca.fuwafuwa.gaku

import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.*
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.LinearLayout
import android.widget.VideoView
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.Fragment
import ca.fuwafuwa.gaku.Dialogs.TutorialExplainDialogFragment

class TutorialFragment : Fragment()
{
    private lateinit var mRootView : View
    private lateinit var mVideoView : VideoView
    private lateinit var mButtonLayout : LinearLayout
    private lateinit var mExplainButton: Button
    private var mPos : Int = -1

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View?
    {
        mRootView = inflater.inflate(R.layout.fragment_tutorial, container, false)

        mVideoView = mRootView.findViewById(R.id.instruction_video_view) as VideoView
        mButtonLayout = mRootView.findViewById(R.id.tutorial_buttons) as LinearLayout
        mExplainButton = mRootView.findViewById(R.id.tutorial_button_explain)

        mPos = arguments?.getInt(ARG_SECTION_NUMBER)!!

        mExplainButton.setOnClickListener {
            getExplainDialogForFragment(mPos).show(fragmentManager!!, "ExplainDialog$mPos")
        }

        Log.d(TAG, "onCreateView $mPos")

        return mRootView
    }

    override fun onStart()
    {
        super.onStart()

        mButtonLayout.viewTreeObserver.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener
        {
            override fun onGlobalLayout()
            {
                val drawableHeight = mButtonLayout.y.toInt()

                val params = LinearLayout.LayoutParams(WRAP_CONTENT, drawableHeight - dpToPx(context!!, 20))
                params.gravity = Gravity.CENTER_HORIZONTAL
                params.setMargins(0, dpToPx(context!!, 20), 0, 0)
                mVideoView.layoutParams = params
                mVideoView.requestLayout()

                mButtonLayout.viewTreeObserver.removeOnGlobalLayoutListener(this)
            }
        })
    }

    override fun onResume()
    {
        super.onResume()

        mVideoView.setVideoURI(Uri.parse("android.resource://${requireContext().packageName}/${getVideoForSectionNumber(mPos)}"))
        mVideoView.setOnPreparedListener { it.isLooping = true }
        mVideoView.start()
    }

    private fun getExplainDialogForFragment(num: Int) : DialogFragment
    {
        return TutorialExplainDialogFragment.newInstance(getTitleTextForSectionNumber(num), getTextForSectionNumber(num))
    }

    private fun getVideoForSectionNumber(num: Int): Int
    {
        when (num){
            1 -> return R.raw.tut_reading
            2 -> return R.raw.tut_results
            3 -> return R.raw.tut_fixing
            4 -> return R.raw.tut_instant
            5 -> return R.raw.tut_copy_anki
            6 -> return R.raw.tut_show_hide
            7 -> return R.raw.tut_select
            8 -> return R.raw.tut_llm
        }

        return 0
    }

    private fun getTitleTextForSectionNumber(num: Int): String
    {
        when (num){
            1 -> return "READING TEXT"
            2 -> return "THE RESULTS"
            3 -> return "FIXING A MISREAD"
            4 -> return "INSTANT MODE"
            5 -> return "TRANSLATE, COPY AND ANKI"
            6 -> return "SHOWING, HIDING AND STOPPING"
            7 -> return "TEXT YOU CAN SELECT"
            8 -> return "YOUR OWN MODEL"
        }

        return ""
    }

    private fun getTextForSectionNumber(num: Int): String
    {
        when (num){
            1 -> return "Drag the box over Japanese text, and drag its bottom-right corner to resize it. Double-tap the box to read the text in it. The box stays where you leave it."
            2 -> return "The results open with the first word looked up. Tap any other character to look it up from there. Gaku reads with manga-ocr, made for manga; if the box covered several bubbles or unusual text, try Read with ML Kit. Close the results with ✕, Back, or a tap outside them."
            3 -> return "If Gaku misreads a character, tap it: lookalike characters appear below it, and tapping one swaps it in. Where the text has furigana, Gaku corrects the kanji beside it, and what it first read stays among the lookalikes. If the right character isn't there, tap Edit to type or handwrite it (handwriting needs a handwriting keyboard, such as Gboard's). Delete removes a character that shouldn't be there."
            4 -> return "With instant mode on, a small box is read as soon as you let go of it, with no double-tap: handy for single words. Turn it on or off with Instant in the results, or from Gaku's notification."
            5 -> return if (BuildConfig.LLM)
                "Translate shows your model's translation if you've set an Ollama server on the start screen; otherwise it opens Google Translate's pop-up, which Google does when a server is set. Copy copies the text. $ANKI Text only leaves Gaku when you tap one of these."
            else
                "Translate opens Google Translate's pop-up with the text, if Google Translate is installed. Copy copies the text. $ANKI Gaku itself has no internet access: text only leaves it when you tap one of these."
            6 -> return "Hide the box when you don't need it, and show it again, from Gaku's notification, its Quick Settings tile or the start screen. Turning the screen off hides it too. Stop on the start screen, or Shutdown in the notification, ends screen sharing; starting again asks for it again."
            7 -> return "If you can select the text, Gaku doesn't need to read it off the screen: select it, then choose Gaku from the menu (it may be under ⋮), or share it to Gaku."
            8 -> return "With an Ollama server set on the start screen, Translate shows your model's translation, and Ask opens a chat about the text. Its buttons ask for the grammar, a word-by-word breakdown or the nuance, and you can type your own questions. Gaku only contacts the server when you tap these."
        }

        return ""
    }

    companion object
    {
        private val TAG = TutorialFragment::class.java.name
        private val ARG_SECTION_NUMBER = "section_number"
        private const val ANKI = "Anki adds the word you looked up to an AnkiDroid deck, with the text it's in, once AnkiDroid is connected on the start screen."

        fun newInstance(sectionNumber: Int): TutorialFragment
        {
            val fragment = TutorialFragment()
            val args = Bundle()
            args.putInt(ARG_SECTION_NUMBER, sectionNumber)
            fragment.arguments = args
            return fragment
        }
    }
}



