package ca.fuwafuwa.gaku.Anki

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import ca.fuwafuwa.gaku.GAKU_PREF_ANKI_DECK
import ca.fuwafuwa.gaku.GAKU_PREF_ANKI_MODEL
import ca.fuwafuwa.gaku.GAKU_PREF_FILE
import ca.fuwafuwa.gaku.getPrefs
import java.security.MessageDigest

/**
 * Adds notes to AnkiDroid on this device through its content provider (the API documented as
 * FlashCardsContract), behind the permission AnkiDroid defines, which the user can refuse. No network:
 * AnkiDroid's own sync is what takes cards further.
 *
 * Gaku talks to the provider directly rather than through AnkiDroid's API library, which is LGPL and
 * only published on JitPack. The calls mirror that library's AddContentApi.
 *
 * Everything but [isInstalled], [hasPermission] and [isReady] reads or writes AnkiDroid's collection, so
 * call it off the main thread.
 */
object AnkiDroid
{
    const val AUTHORITY = "com.ichi2.anki.flashcards"
    const val PERMISSION = "com.ichi2.anki.permission.READ_WRITE_DATABASE"

    const val MODEL_NAME = "Gaku"
    const val DECK_NAME = "Gaku"
    private const val TAG = "gaku"

    private val NOTES = Uri.parse("content://$AUTHORITY/notes")
    private val NOTES_V2 = Uri.parse("content://$AUTHORITY/notes_v2")
    private val MODELS = Uri.parse("content://$AUTHORITY/models")
    private val DECKS = Uri.parse("content://$AUTHORITY/decks")

    private const val FIELD_SEPARATOR = "\u001f"

    private const val QUESTION = "<div class=\"word\">{{Word}}</div>"
    private const val ANSWER = "{{FrontSide}}<hr id=answer><div class=\"reading\">{{Reading}}</div>" +
            "<div class=\"meaning\">{{Meaning}}</div><div class=\"sentence\">{{Sentence}}</div>" +
            "{{#Translation}}<div class=\"translation\">{{Translation}}</div>{{/Translation}}"
    private const val CSS = ".card { font-family: sans-serif; font-size: 20px; text-align: center; }\n" +
            ".word { font-size: 40px; }\n" +
            ".reading { margin-top: 8px; }\n" +
            ".meaning, .sentence, .translation { text-align: left; margin-top: 16px; }\n" +
            ".translation { color: #666; }\n"

    /** Reports back on the main thread. */
    fun interface Listener
    {
        fun onDone(message: String)
    }

    fun isInstalled(context: Context): Boolean
    {
        val packageManager = context.packageManager
        val provider = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
        {
            packageManager.resolveContentProvider(AUTHORITY, PackageManager.ComponentInfoFlags.of(0))
        }
        else
        {
            @Suppress("DEPRECATION")
            packageManager.resolveContentProvider(AUTHORITY, 0)
        }
        return provider != null
    }

    fun hasPermission(context: Context): Boolean
    {
        return ContextCompat.checkSelfPermission(context, PERMISSION) == PackageManager.PERMISSION_GRANTED
    }

    /** Whether the results window's Anki button can add a note: connected on the start screen, deck chosen. */
    @JvmStatic
    fun isReady(context: Context): Boolean
    {
        return getPrefs(context).ankiDeck != 0L && isInstalled(context) && hasPermission(context)
    }

    /** AnkiDroid's decks, by id, in name order. */
    fun decks(resolver: ContentResolver): Map<Long, String>
    {
        val decks = mutableMapOf<Long, String>()
        resolver.query(DECKS, arrayOf("deck_id", "deck_name"), null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) decks[cursor.getLong(0)] = cursor.getString(1)
        }
        return decks.entries.sortedBy { it.value.lowercase() }.associate { it.key to it.value }
    }

    /** Adds a deck called [name]; its id, or null if AnkiDroid refused. */
    fun addDeck(resolver: ContentResolver, name: String): Long?
    {
        val values = ContentValues()
        values.put("deck_name", name)
        return resolver.insert(DECKS, values)?.lastPathSegment?.toLongOrNull()
    }

    /**
     * The id of Gaku's note type, saved in the prefs: the saved one if it's still there, else one called
     * [MODEL_NAME], else a new one with [AnkiCard.FIELDS] and a single card. Null if AnkiDroid refused.
     */
    fun gakuModelId(context: Context, resolver: ContentResolver): Long?
    {
        val saved = getPrefs(context).ankiModel
        var modelId: Long? = null
        resolver.query(MODELS, arrayOf("_id", "name"), null, null, null)?.use { cursor ->
            while (cursor.moveToNext())
            {
                val id = cursor.getLong(0)
                if (id == saved) { modelId = id; break }
                if (modelId == null && cursor.getString(1) == MODEL_NAME) modelId = id
            }
        }

        if (modelId == null) modelId = addModel(resolver)
        modelId?.let { context.getSharedPreferences(GAKU_PREF_FILE, Context.MODE_PRIVATE).edit().putLong(GAKU_PREF_ANKI_MODEL, it).apply() }
        return modelId
    }

    private fun addModel(resolver: ContentResolver): Long?
    {
        val values = ContentValues()
        values.put("name", MODEL_NAME)
        values.put("field_names", AnkiCard.FIELDS.joinToString(FIELD_SEPARATOR))
        values.put("num_cards", 1)
        values.put("css", CSS)
        values.put("sort_field_index", 0)
        val modelUri = resolver.insert(MODELS, values) ?: return null

        val template = ContentValues()
        template.put("card_template_name", "Recognition")
        template.put("question_format", QUESTION)
        template.put("answer_format", ANSWER)
        resolver.update(Uri.withAppendedPath(modelUri, "templates/0"), template, null, null)

        return modelUri.lastPathSegment?.toLongOrNull()
    }

    private fun fieldNames(resolver: ContentResolver, modelId: Long): List<String>
    {
        resolver.query(Uri.withAppendedPath(MODELS, modelId.toString()), arrayOf("field_names"), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) return cursor.getString(0).split(FIELD_SEPARATOR)
        }
        return emptyList()
    }

    /** Whether a note of [modelId] already has [firstField] as its first field, the way Anki spots duplicates. */
    private fun isDuplicate(resolver: ContentResolver, modelId: Long, firstField: String): Boolean
    {
        val selection = "mid=$modelId and csum=${fieldChecksum(firstField)}"
        resolver.query(NOTES_V2, arrayOf("_id", "flds"), selection, null, null)?.use { cursor ->
            while (cursor.moveToNext())
            {
                // The checksum can collide
                if (cursor.getString(1).substringBefore(FIELD_SEPARATOR) == firstField) return true
            }
        }
        return false
    }

    /** Adds a note of [modelId] with [fields] and the [TAG] tag, with its cards in [deckId]. */
    private fun addNote(resolver: ContentResolver, modelId: Long, deckId: Long, fields: Array<String>): Boolean
    {
        val values = ContentValues()
        values.put("mid", modelId)
        values.put("flds", fields.joinToString(FIELD_SEPARATOR))
        values.put("tags", TAG)
        val noteUri = resolver.insert(NOTES, values) ?: return false

        // A new note's cards go to the note type's last deck, so move them
        val cardsUri = Uri.withAppendedPath(noteUri, "cards")
        val deck = ContentValues()
        deck.put("deck_id", deckId)
        resolver.query(cardsUri, arrayOf("ord"), null, null, null)?.use { cursor ->
            while (cursor.moveToNext())
            {
                resolver.update(Uri.withAppendedPath(cardsUri, cursor.getString(0)), deck, null, null)
            }
        }
        return true
    }

    /**
     * Adds [card] to the deck chosen on the start screen, unless its word is already there, on a thread of
     * its own; [listener] hears what happened, in words for a toast.
     */
    @JvmStatic
    fun add(context: Context, card: AnkiCard, listener: Listener)
    {
        val appContext = context.applicationContext
        val handler = Handler(Looper.getMainLooper())
        Thread {
            val message = try
            {
                addNow(appContext, card)
            }
            catch (e: SecurityException)
            {
                "Gaku isn't allowed to use AnkiDroid any more. Connect it again on Gaku's start screen."
            }
            catch (e: Exception)
            {
                "AnkiDroid didn't take the card: ${e.message ?: e.javaClass.simpleName}"
            }
            handler.post { listener.onDone(message) }
        }.start()
    }

    private fun addNow(context: Context, card: AnkiCard): String
    {
        val resolver = context.contentResolver
        val deckId = getPrefs(context).ankiDeck
        val deckName = decks(resolver)[deckId] ?: return "The deck chosen for Gaku is gone. Choose another on Gaku's start screen."
        val modelId = gakuModelId(context, resolver) ?: return "AnkiDroid didn't add Gaku's note type. Open AnkiDroid once, then try again."

        val fields = card.fieldsFor(fieldNames(resolver, modelId))
        if (fields.isEmpty() || fields[0].isEmpty()) return "The $MODEL_NAME note type's first field isn't ${AnkiCard.FIELD_WORD} any more"
        if (isDuplicate(resolver, modelId, fields[0])) return "${card.word} is already in Anki"

        return if (addNote(resolver, modelId, deckId, fields)) "Added ${card.word} to $deckName" else "AnkiDroid didn't add the card"
    }

    /** Anki's checksum of a first field: the first 8 hex digits of its SHA-1. Gaku's fields hold no media. */
    internal fun fieldChecksum(field: String): Long
    {
        val plain = field.replace(Regex("<[^>]*>"), "").replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")
        val digest = MessageDigest.getInstance("SHA-1").digest(plain.toByteArray(Charsets.UTF_8))
        return digest.take(4).fold(0L) { acc, byte -> (acc shl 8) or (byte.toLong() and 0xff) }
    }
}
