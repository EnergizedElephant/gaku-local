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

        mVideoView.setVideoURI(Uri.parse("android.resource://ca.fuwafuwa.gaku/${getVideoForSectionNumber(mPos)}"))
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
            1 -> return R.raw.tut1
            2 -> return R.raw.tut2
            4 -> return R.raw.tut4
            5 -> return R.raw.tut5
            6 -> return R.raw.tut6
            7 -> return R.raw.tut7
            8 -> return R.raw.tut8
            9 -> return R.raw.tut9
        }

        return 0
    }

    private fun getTitleTextForSectionNumber(num: Int): String
    {
        when (num){
            1 -> return "BASIC USAGE"
            2 -> return "INSTANT MODE"
            4 -> return "FIXING A CHARACTER - SWAP"
            5 -> return "FIXING A CHARACTER - EDIT"
            6 -> return "FIXING A CHARACTER - DELETE"
            7 -> return "TRANSLATE AND COPY"
            8 -> return "SHOWING AND HIDING GAKU"
            9 -> return "SELECT TO LOOKUP"
        }

        return ""
    }

    private fun getTextForSectionNumber(num: Int): String
    {
        when (num){
            1 -> return "Drag the capture window to move it, and drag its bottom right corner to resize it (the resize area is inside the window). Double tap the window to read the text in it with manga-ocr, a reader trained on manga. If the box covers several bubbles or unusual text, try Read with ML Kit in the results. The results open with the first word looked up: tap any other character to look it up from there."
            2 -> return "With instant mode on, a small capture window is read as soon as you let go of it, with no double tap. It's meant for single words, not sentences. Turn it on or off with the Instant button in the results, or from Gaku's notification."
            4 -> return "If Gaku misreads a character, tap it in the results. Lookalike characters appear below it: tap one to swap it in. Where the text has furigana, Gaku corrects the kanji beside it from the dictionary, and what it first read stays among the lookalikes. A quick swipe down on the character also shows them."
            5 -> return "If the right character isn't among the lookalikes, tap the character, then Edit, and type or handwrite it. Handwriting needs a handwriting keyboard, for example Gboard with Japanese handwriting. A quick swipe up and to the left on the character does the same."
            6 -> return "To remove a character that shouldn't be there, tap it, then Delete. A quick swipe up and to the right does the same; in instant mode the swipe directions may be reversed when there isn't enough room."
            7 -> return "Translate opens Google Translate's pop-up with the recognised text. Copy copies it to the clipboard, as does a long press on any character. Gaku itself has no internet access: Translate only gets the text when you tap the button."
            8 -> return "Show, hide or shut down Gaku from its notification, which also turns instant mode on or off. Gaku's Quick Settings tile shows or hides it too, and starts it when it isn't running."
            9 -> return "In the case that you can select the text and don't need OCR, simply select the text and send it to Gaku to bring up the dictionary."
        }

        return ""
    }

    companion object
    {
        private val TAG = TutorialFragment::class.java.name
        private val ARG_SECTION_NUMBER = "section_number"

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



