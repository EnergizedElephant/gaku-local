@file:JvmName("GakuTools")

package ca.fuwafuwa.gaku

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.DisplayMetrics
import android.util.Log
import android.widget.Toast

import com.google.gson.GsonBuilder
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.lang.Exception

import java.util.ArrayList

private const val TAG = "GakuTools"
private val gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().excludeFieldsWithoutExposeAnnotation().create()

data class Prefs(val instantModeSetting: Boolean,
                 val showHideSetting: Boolean,
                 // The Ollama server, its API key and model (lan build); empty until set on the start screen
                 val llmServer: String,
                 val llmApiKey: String,
                 val llmModel: String,
                 // AnkiDroid's ids for the deck and note type Gaku adds to; 0 until chosen
                 val ankiDeck: Long,
                 val ankiModel: Long)
{
    val llmConfigured: Boolean
        get() = BuildConfig.LLM && llmServer.isNotEmpty() && llmModel.isNotEmpty()
}

// NOTE: The defValue here should match the defValue of the BroadcastReceivers, otherwise
// they will be out of sync the first time.
fun getPrefs(context: Context): Prefs
{
    val prefs = context.getSharedPreferences(GAKU_PREF_FILE, Context.MODE_PRIVATE)

    return Prefs(
            prefs.getBoolean(GAKU_PREF_INSTANT_MODE, true),
            prefs.getBoolean(GAKU_PREF_SHOW_HIDE, true),
            prefs.getString(GAKU_PREF_LLM_SERVER, "")!!,
            prefs.getString(GAKU_PREF_LLM_API_KEY, "")!!,
            prefs.getString(GAKU_PREF_LLM_MODEL, "")!!,
            prefs.getLong(GAKU_PREF_ANKI_DECK, 0L),
            prefs.getLong(GAKU_PREF_ANKI_MODEL, 0L))
}

fun toJson(obj: Any): String
{
    return gson.toJson(obj)
}

fun dpToPx(context: Context, dp: Int): Int
{
    val displayMetrics = context.resources.displayMetrics
    return Math.round(dp * (displayMetrics.xdpi / DisplayMetrics.DENSITY_DEFAULT))
}

fun pxToDp(context: Context, px: Int): Int
{
    val displayMetrics = context.resources.displayMetrics
    return Math.round(px / (displayMetrics.xdpi / DisplayMetrics.DENSITY_DEFAULT))
}

/**
 * Splits `text` into individual unicode characters as a list of strings
 * @param text Text to split
 * @return List of strings with each string representing one unicode character
 */
fun splitTextByChar(text: String): List<String>
{
    val charList = ArrayList<String>()

    val length = text.length
    var offset = 0
    while (offset < length)
    {
        val curr = text.codePointAt(offset)
        val charz = String(intArrayOf(curr), 0, 1)
        charList.add(charz)
        offset += Character.charCount(curr)
    }

    return charList
}

fun startGakuService(context: Context, i: Intent)
{
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
    {
        context.startForegroundService(i)
    }
    else
    {
        context.startService(i)
    }
}

fun setupGakuDatabasesAndFiles(context: Context)
{
    try {
        // Left by earlier builds: saved screen captures, the Tesseract data Gaku never used, and
        // dictionaries since replaced
        deleteFilesFolder(context, SCREENSHOT_FOLDER_NAME)
        deleteFilesFolder(context, "tessdata")
        deleteOldDictionaries(context)

        val filesAndPaths = hashMapOf(
                JMDICT_DATABASE_NAME to context.filesDir.absolutePath)

        if (shouldResetData(filesAndPaths))
        {
            Log.d(TAG, "Resetting Data")
            for (fileAndPath in filesAndPaths){
                File("${fileAndPath.value}/${fileAndPath.key}").delete()
            }
        }

        copyFilesIfNotExists(context, filesAndPaths)
    }
    catch (e: Exception)
    {
        Log.e(TAG, "Unable to set up the dictionary", e)
        Toast.makeText(context, "Unable to setup Gaku database", Toast.LENGTH_LONG).show()
        return
    }
}

fun shouldResetData(filesAndPaths: Map<String, String>) : Boolean
{
    for (fileAndPath in filesAndPaths){
        if (!File("${fileAndPath.value}/${fileAndPath.key}").exists()) return true
    }
    return false
}

fun createDirIfNotExists(path: String)
{
    val dir = File(path)
    if (!dir.exists())
    {
        dir.mkdirs()
    }
}

fun copyFilesIfNotExists(context: Context, filesAndPaths: Map<String, String>)
{
    for (fileAndPath in filesAndPaths)
    {
        val path = fileAndPath.value
        val fileName = fileAndPath.key
        val filePath = "$path/$fileName"

        if (File(filePath).exists())
        {
            continue
        }

        createDirIfNotExists(path)

        // Copied under another name and renamed, so a copy cut short (say, the app killed during its
        // first launch) is never taken for the whole file next time
        val partFile = File("$filePath.part")
        context.assets.open(fileName).use { input ->
            FileOutputStream(partFile).use { output -> input.copyTo(output) }
        }
        if (!partFile.renameTo(File(filePath)))
        {
            throw IOException("Couldn't move $partFile into place")
        }

        Log.d(TAG, "Copied $filePath")
    }
}

/**
 * Captures are never written to disk any more, but earlier builds saved full-screen PNGs here
 * (and their cleanup never deleted anything), so purge whatever is left.
 */
private fun deleteFilesFolder(context: Context, name: String)
{
    // Only ever a plain folder name directly under filesDir
    require(name.isNotBlank() && !name.contains('/') && name != "." && name != "..") { "Bad folder name: $name" }
    val dir = File(context.filesDir, name)
    if (dir.exists() && !dir.deleteRecursively())
    {
        Log.w(TAG, "Unable to fully delete ${dir.absolutePath}")
    }
}

/**
 * Each dictionary refresh ships the database under a new name (tools/update_dictionary.sh), so the
 * copy an earlier version made in filesDir is left behind: delete it, with its SQLite side files.
 */
private fun deleteOldDictionaries(context: Context)
{
    val old = context.filesDir.listFiles { file ->
        (file.name.startsWith("dictionary-") || file.name.startsWith("DB_KakuDict-")) &&
                !file.name.startsWith(JMDICT_DATABASE_NAME)
    } ?: return
    for (file in old)
    {
        if (!file.delete())
        {
            Log.w(TAG, "Unable to delete ${file.absolutePath}")
        }
    }
}

/**
 * Opens [url] in the browser, or says there isn't one.
 */
fun openUrl(context: Context, url: String)
{
    try
    {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }
    catch (e: ActivityNotFoundException)
    {
        Toast.makeText(context, "No browser is installed", Toast.LENGTH_SHORT).show()
    }
}

/**
 * Lets the start screen follow the service: MainService calls [notifyChanged] (on the main thread) when
 * it starts, stops, or is shown or hidden, and the screen re-reads MainService.IsRunning() and the prefs.
 */
object GakuState
{
    @Volatile
    var listener: (() -> Unit)? = null

    @JvmStatic
    fun notifyChanged()
    {
        listener?.invoke()
    }
}
