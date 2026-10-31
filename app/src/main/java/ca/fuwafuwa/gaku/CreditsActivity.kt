package ca.fuwafuwa.gaku

import android.graphics.Typeface
import android.os.Bundle
import android.text.method.LinkMovementMethod
import android.util.TypedValue
import android.widget.TextView
import androidx.annotation.RawRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.text.HtmlCompat
import ca.fuwafuwa.gaku.databinding.ActivityCreditsBinding

/**
 * Credits for the dictionary, data and libraries Gaku bundles (res/raw/credits.html), with their
 * licences. The EDRDG's licence for JMdict and KANJIDIC asks apps to credit them on a screen like
 * this, reached from a menu rather than a launch page; the other licences ask for their notices to
 * come with the app.
 */
class CreditsActivity : AppCompatActivity()
{
    private lateinit var mBinding: ActivityCreditsBinding

    override fun onCreate(savedInstanceState: Bundle?)
    {
        super.onCreate(savedInstanceState)
        mBinding = ActivityCreditsBinding.inflate(layoutInflater)
        setContentView(mBinding.root)

        mBinding.creditsText.text = HtmlCompat.fromHtml(readRaw(R.raw.credits), HtmlCompat.FROM_HTML_MODE_LEGACY)
        mBinding.creditsText.movementMethod = LinkMovementMethod.getInstance()

        for ((title, licence) in LICENCES)
        {
            addHeading(title)
            addText(unwrap(readRaw(licence)))
        }

        // Over 300 KB, so only laid out when asked for
        addHeading("ONNX Runtime's third-party notices")
        val notices = addText("Show them")
        notices.setTextColor(ContextCompat.getColor(this, R.color.blue_dark))
        notices.setOnClickListener {
            notices.setOnClickListener(null)
            notices.setTextColor(ContextCompat.getColor(this, R.color.PrimaryDark))
            notices.text = unwrap(readRaw(R.raw.onnxruntime_third_party_notices))
        }
    }

    private fun readRaw(@RawRes id: Int): String
    {
        return resources.openRawResource(id).bufferedReader().use { it.readText() }
    }

    /**
     * Licence texts come wrapped at about 75 characters, which a phone wraps again into ragged lines:
     * join each paragraph's lines, keeping the blank lines between paragraphs
     */
    private fun unwrap(text: String): String
    {
        return text.trim().split(Regex("\n[ \t]*\n+")).joinToString("\n\n") { it.trim().replace(Regex("\\s*\n\\s*"), " ") }
    }

    private fun addHeading(title: String)
    {
        val heading = TextView(this)
        heading.text = title
        heading.setTextColor(ContextCompat.getColor(this, R.color.PrimaryDark))
        heading.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        heading.setTypeface(heading.typeface, Typeface.BOLD)
        val dp = resources.displayMetrics.density
        heading.setPadding(0, (24 * dp).toInt(), 0, (8 * dp).toInt())
        mBinding.creditsContent.addView(heading)
    }

    private fun addText(text: String): TextView
    {
        val view = TextView(this)
        view.text = text
        view.setTextColor(ContextCompat.getColor(this, R.color.PrimaryDark))
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        mBinding.creditsContent.addView(view)
        return view
    }

    companion object
    {
        private val LICENCES = listOf(
                "Gaku: BSD 3-Clause licence" to R.raw.licence_gaku,
                "ONNX Runtime: MIT licence" to R.raw.licence_onnxruntime,
                "ORMLite: ISC licence" to R.raw.licence_ormlite,
                "Checker Framework qualifiers: MIT licence" to R.raw.licence_checker_qual,
                "Apache License 2.0" to R.raw.licence_apache_2_0,
                "GNU General Public License, version 2" to R.raw.licence_gpl_2_0)
    }
}
