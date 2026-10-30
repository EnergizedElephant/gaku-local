package ca.fuwafuwa.gaku.Dialogs

import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentActivity
import ca.fuwafuwa.gaku.BuildConfig
import ca.fuwafuwa.gaku.GAKU_PREF_FILE
import ca.fuwafuwa.gaku.GAKU_PREF_FIRST_LAUNCH
import ca.fuwafuwa.gaku.MainActivity

class GrantPermissionDialogFragment : DialogFragment()
{
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog
    {
        return activity?.let {

            val builder = AlertDialog.Builder(it)

            builder.setTitle("Before you start")
                    .setMessage(if (BuildConfig.LLM) LAN_MESSAGE else OFFLINE_MESSAGE)
                    .setPositiveButton("CONTINUE")
                    {
                        _, _ ->
                        run {
                            val prefs = context!!.getSharedPreferences(GAKU_PREF_FILE, Context.MODE_PRIVATE)
                            prefs.edit().putBoolean(GAKU_PREF_FIRST_LAUNCH, false).apply()

                            startActivity(Intent(activity, MainActivity::class.java))
                            (activity as FragmentActivity).finish()
                        }
                    }
                    .setNegativeButton("CANCEL")
                    {
                        _, _ ->
                        run {
                        }
                    }

            builder.create()

        } ?: throw IllegalStateException("Activity cannot be null")
    }

    companion object
    {
        private const val NEXT = "Next, Gaku asks to display over other apps, to show notifications, and to capture the screen."

        private const val OFFLINE_MESSAGE = "Gaku reads text by capturing your screen while it's running. Everything stays on your device: Gaku has no internet access.\n\nThe one exception is the Translate button, which hands the recognised text to Google Translate, and only when you tap it.\n\n$NEXT"

        // The lan build has the internet permission, for the Ollama server set on the start screen
        private const val LAN_MESSAGE = "Gaku reads text by capturing your screen while it's running. Reading and the dictionary stay on your device.\n\nText only leaves it when you tap a button: Translate and Ask send it to the Ollama server you set on the start screen, and until you set one, Translate hands it to Google Translate.\n\n$NEXT"
    }
}
