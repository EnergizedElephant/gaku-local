package ca.fuwafuwa.gaku

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Window
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import ca.fuwafuwa.gaku.Windows.InformationWindow
import ca.fuwafuwa.gaku.Windows.WindowCoordinator

/**
 * Looks up text another app sends ("Gaku" in a text selection menu, or Share) in the results window.
 * It's exported, so whatever arrives is untrusted: it's capped, and nothing is drawn without the overlay
 * permission.
 */
class PassthroughActivity : AppCompatActivity()
{
    override fun onCreate(savedInstanceState: Bundle?)
    {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)

        val text = when (intent?.action)
        {
            // getStringExtra() would be null for styled text
            Intent.ACTION_PROCESS_TEXT -> intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)
            Intent.ACTION_SEND -> if ("text/plain" == intent.type) intent.getCharSequenceExtra(Intent.EXTRA_TEXT) else null
            else -> null
        }?.toString()?.let { takeChars(it, MAX_CHARS) }

        if (text.isNullOrBlank())
        {
            finish()
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this))
        {
            // Adding the overlay would throw; MainActivity's start screen leads to the setting
            Toast.makeText(this, "Open Gaku once and allow it to display over other apps", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        setupGakuDatabasesAndFiles(this)

        val windowCoordinator = WindowCoordinator(applicationContext)
        val infoWindow = windowCoordinator.getWindow(WINDOW_INFO) as InformationWindow

        infoWindow.setResult(text)
        infoWindow.show()

        finish()
    }

    /** The first [n] chars of [text], never splitting a surrogate pair. */
    private fun takeChars(text: String, n: Int): String
    {
        if (text.length <= n) return text
        val cut = if (Character.isHighSurrogate(text[n - 1])) n - 1 else n
        return text.substring(0, cut)
    }

    companion object
    {
        // Each character becomes a view in the results window
        private const val MAX_CHARS = 2000
    }
}
