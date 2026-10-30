package ca.fuwafuwa.gaku

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.Spinner
import android.widget.TextView
import androidx.fragment.app.Fragment
import ca.fuwafuwa.gaku.Anki.AnkiDroid
import ca.fuwafuwa.gaku.Llm.OllamaClient

class MainStartFragment : Fragment()
{
    private lateinit var mainActivity : MainActivity
    private lateinit var rootView : View

    private lateinit var tutorialText : TextView
    private lateinit var githubText : TextView
    private lateinit var stateText : TextView
    private lateinit var hintText : TextView
    private lateinit var startButton : Button
    private lateinit var showHideButton : Button
    private lateinit var stopButton : Button

    private lateinit var progressBar : ProgressBar

    // lan build only
    private lateinit var llmServerText : EditText
    private lateinit var llmApiKeyText : EditText
    private lateinit var llmModelSpinner : Spinner
    private lateinit var llmStatusText : TextView
    private var llmCall : OllamaClient.Call? = null
    // Set by Connect: the server is outside the local network and reached over plain http
    private var llmUnencrypted = false

    private lateinit var ankiDeckSpinner : Spinner
    private lateinit var ankiConnectButton : Button
    private lateinit var ankiStatusText : TextView
    // The deck ids in ankiDeckSpinner, by position; null is a new deck called AnkiDroid.DECK_NAME
    private var ankiDeckIds = listOf<Long?>()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?,
                              savedInstanceState: Bundle?): View?
    {
        mainActivity = activity as MainActivity

        rootView = inflater.inflate(R.layout.fragment_start, container, false)

        tutorialText = rootView.findViewById(R.id.gaku_tutorial)
        githubText = rootView.findViewById(R.id.gaku_github)
        stateText = rootView.findViewById(R.id.gaku_state)
        hintText = rootView.findViewById(R.id.gaku_hint)
        startButton = rootView.findViewById(R.id.gaku_start)
        showHideButton = rootView.findViewById(R.id.gaku_show_hide)
        stopButton = rootView.findViewById(R.id.gaku_stop)

        progressBar = rootView.findViewById(R.id.progress_bar)

        tutorialText.setOnClickListener {
            startActivity(Intent(mainActivity, TutorialActivity::class.java))
        }

        githubText.setOnClickListener {
            openUrl(mainActivity, "https://github.com/EnergizedElephant/gaku-local")
        }

        startButton.setOnClickListener {
            if (mainActivity.canDrawOverlays()) mainActivity.startGaku() else mainActivity.openOverlaySettings()
            refreshStateSoon(300)
        }

        showHideButton.setOnClickListener {
            // The same explicit broadcast as the notification's button and the Quick Settings tile
            mainActivity.sendBroadcast(Intent(mainActivity, MainService.ToggleShowHideMainService::class.java))
            refreshStateSoon(300)
        }

        stopButton.setOnClickListener {
            mainActivity.stopService(Intent(mainActivity, MainService::class.java))
            refreshStateSoon(300)
        }

        if (BuildConfig.LLM) setupLlm()
        setupAnki()

        return rootView
    }

    override fun onDestroyView()
    {
        llmCall?.cancel()
        super.onDestroyView()
    }

    /**
     * The lan build's Ollama settings. Connect lists the server's models and saves the server and its
     * API key; the model chosen is saved too. Clearing the server and tapping Connect turns the LLM off
     * again.
     */
    private fun setupLlm()
    {
        rootView.findViewById<View>(R.id.llm_settings).visibility = View.VISIBLE
        llmServerText = rootView.findViewById(R.id.llm_server)
        llmApiKeyText = rootView.findViewById(R.id.llm_api_key)
        llmModelSpinner = rootView.findViewById(R.id.llm_model)
        llmStatusText = rootView.findViewById(R.id.llm_status)

        val prefs = getPrefs(mainActivity)
        llmServerText.setText(prefs.llmServer)
        llmApiKeyText.setText(prefs.llmApiKey)
        // Until Connect lists the rest, only the saved model
        showModels(listOf(prefs.llmModel).filter { it.isNotEmpty() }, prefs.llmModel)

        rootView.findViewById<Button>(R.id.llm_connect).setOnClickListener { connectLlm() }
        val connectOnDone = TextView.OnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) connectLlm()
            false
        }
        llmServerText.setOnEditorActionListener(connectOnDone)
        llmApiKeyText.setOnEditorActionListener(connectOnDone)

        llmModelSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener
        {
            override fun onItemSelected(parent: AdapterView<*>, view: View?, position: Int, id: Long)
            {
                useModel(parent.getItemAtPosition(position) as String)
            }

            override fun onNothingSelected(parent: AdapterView<*>) {}
        }
    }

    private fun connectLlm()
    {
        val server = llmServerText.text.toString().trim()
        val apiKey = llmApiKeyText.text.toString().trim()
        llmCall?.cancel()
        llmUnencrypted = false

        if (server.isEmpty())
        {
            sharedPrefs().edit().remove(GAKU_PREF_LLM_SERVER).remove(GAKU_PREF_LLM_API_KEY).remove(GAKU_PREF_LLM_MODEL).apply()
            llmApiKeyText.setText("")
            showModels(emptyList(), "")
            return
        }

        llmStatusText.setText(R.string.llm_status_connecting)
        llmCall = OllamaClient(server, apiKey).listModels(object : OllamaClient.ModelsListener
        {
            override fun onModels(models: List<String>, unencrypted: Boolean)
            {
                llmUnencrypted = unencrypted
                sharedPrefs().edit().putString(GAKU_PREF_LLM_SERVER, server).putString(GAKU_PREF_LLM_API_KEY, apiKey).apply()
                val saved = getPrefs(mainActivity).llmModel
                showModels(models, if (saved in models) saved else models.firstOrNull() ?: "")
            }

            override fun onError(message: String)
            {
                llmStatusText.text = message
            }
        })
    }

    /** Fills the model list with [models], with [selected] chosen and saved. */
    private fun showModels(models: List<String>, selected: String)
    {
        // The spinner's own context has its light theme
        val adapter = ArrayAdapter(llmModelSpinner.context, android.R.layout.simple_spinner_item, models)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        llmModelSpinner.adapter = adapter
        llmModelSpinner.visibility = if (models.isEmpty()) View.GONE else View.VISIBLE
        if (selected in models) llmModelSpinner.setSelection(models.indexOf(selected))
        useModel(selected)
    }

    private fun useModel(model: String)
    {
        if (model.isEmpty()) sharedPrefs().edit().remove(GAKU_PREF_LLM_MODEL).apply()
        else sharedPrefs().edit().putString(GAKU_PREF_LLM_MODEL, model).apply()

        val prefs = getPrefs(mainActivity)
        val status = when
        {
            prefs.llmConfigured -> getString(R.string.llm_status_using, model)
            prefs.llmServer.isNotEmpty() -> getString(R.string.llm_status_no_models)
            else -> getString(R.string.llm_status_not_set)
        }
        llmStatusText.text = if (llmUnencrypted && prefs.llmServer.isNotEmpty()) "$status ${getString(R.string.llm_status_unencrypted)}" else status
    }

    private fun sharedPrefs() = mainActivity.getSharedPreferences(GAKU_PREF_FILE, Context.MODE_PRIVATE)

    /**
     * The AnkiDroid settings: what's needed next (AnkiDroid installed, its permission), then the deck the
     * results window's Anki button adds to.
     */
    private fun setupAnki()
    {
        ankiDeckSpinner = rootView.findViewById(R.id.anki_deck)
        ankiConnectButton = rootView.findViewById(R.id.anki_connect)
        ankiStatusText = rootView.findViewById(R.id.anki_status)

        ankiConnectButton.setOnClickListener {
            if (mainActivity.ankiPermissionBlocked()) mainActivity.openAppSettings() else mainActivity.requestAnkiPermission()
        }

        ankiDeckSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener
        {
            override fun onItemSelected(parent: AdapterView<*>, view: View?, position: Int, id: Long)
            {
                useDeck(position)
            }

            override fun onNothingSelected(parent: AdapterView<*>) {}
        }
    }

    private fun refreshAnki()
    {
        if (!AnkiDroid.isInstalled(mainActivity))
        {
            showAnkiStatus(getString(R.string.anki_status_not_installed), null)
        }
        else if (!AnkiDroid.hasPermission(mainActivity))
        {
            when
            {
                mainActivity.ankiPermissionBlocked() -> showAnkiStatus(getString(R.string.anki_status_blocked), R.string.anki_button_settings)
                mainActivity.ankiAsked() -> showAnkiStatus(getString(R.string.anki_status_declined), R.string.anki_button_try_again)
                else -> showAnkiStatus(getString(R.string.anki_status_connect), R.string.anki_button_connect)
            }
        }
        else
        {
            if (ankiDeckSpinner.visibility != View.VISIBLE) showAnkiStatus(getString(R.string.anki_status_loading), null)
            loadDecks()
        }
    }

    /** [button] is the connect button's label, or null to hide it; the deck list is hidden either way. */
    private fun showAnkiStatus(status: String, button: Int?)
    {
        ankiStatusText.text = status
        ankiDeckSpinner.visibility = View.GONE
        ankiConnectButton.visibility = if (button == null) View.GONE else View.VISIBLE
        button?.let { ankiConnectButton.setText(it) }
    }

    /** Lists AnkiDroid's decks, after making sure Gaku's note type is there. */
    private fun loadDecks()
    {
        val context = mainActivity.applicationContext
        Thread {
            val decks = try
            {
                AnkiDroid.gakuModelId(context, context.contentResolver)?.let { AnkiDroid.decks(context.contentResolver) }
            }
            catch (e: Exception)
            {
                null
            }
            rootView.post { if (isAdded) showDecks(decks) }
        }.start()
    }

    /** Fills the deck list: the saved deck selected, else the one called Gaku, new if need be. */
    private fun showDecks(decks: Map<Long, String>?)
    {
        if (decks == null)
        {
            showAnkiStatus(getString(R.string.anki_status_error), null)
            return
        }

        val ids = mutableListOf<Long?>()
        val names = mutableListOf<String>()
        if (AnkiDroid.DECK_NAME !in decks.values)
        {
            ids.add(null)
            names.add(getString(R.string.anki_new_deck, AnkiDroid.DECK_NAME))
        }
        for ((id, name) in decks)
        {
            ids.add(id)
            names.add(name)
        }
        ankiDeckIds = ids

        val saved = ids.indexOf(getPrefs(mainActivity).ankiDeck)
        val selected = if (saved >= 0) saved else ids.indexOfFirst { it == null || decks[it] == AnkiDroid.DECK_NAME }.coerceAtLeast(0)

        // The spinner's own context has its light theme
        val adapter = ArrayAdapter(ankiDeckSpinner.context, android.R.layout.simple_spinner_item, names)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        ankiDeckSpinner.adapter = adapter
        ankiDeckSpinner.setSelection(selected)
        ankiDeckSpinner.visibility = View.VISIBLE
        ankiConnectButton.visibility = View.GONE
        ankiStatusText.text = getString(R.string.anki_status_ready, names[selected])
    }

    /** Saves the deck at [position], making the new Gaku deck first if that's the one chosen. */
    private fun useDeck(position: Int)
    {
        // A new adapter can report its first item before the selection set with it, and that must not
        // make the Gaku deck when another one is saved
        if (position !in ankiDeckIds.indices || position != ankiDeckSpinner.selectedItemPosition) return
        val id = ankiDeckIds[position]
        if (id != null)
        {
            sharedPrefs().edit().putLong(GAKU_PREF_ANKI_DECK, id).apply()
            ankiStatusText.text = getString(R.string.anki_status_ready, ankiDeckSpinner.getItemAtPosition(position) as String)
            return
        }

        val context = mainActivity.applicationContext
        Thread {
            val newId = try { AnkiDroid.addDeck(context.contentResolver, AnkiDroid.DECK_NAME) } catch (e: Exception) { null }
            if (newId != null) context.getSharedPreferences(GAKU_PREF_FILE, Context.MODE_PRIVATE).edit().putLong(GAKU_PREF_ANKI_DECK, newId).apply()
            rootView.post { if (isAdded) loadDecks() }
        }.start()
    }

    override fun onResume()
    {
        super.onResume()

        GakuState.listener = { refreshState() }
        refreshState()
        // AnkiDroid may have been installed, or its permission answered, meanwhile
        refreshAnki()
    }

    override fun onPause()
    {
        GakuState.listener = null
        super.onPause()
    }

    /**
     * Shows what Gaku is doing, with the buttons that apply. The service calls back through GakuState
     * when that changes; the buttons also check again shortly after, since the service answers in its
     * own time.
     */
    private fun refreshState()
    {
        val running = MainService.IsRunning()
        val starting = !running && mainActivity.isStarting()
        val shown = running && getPrefs(mainActivity).showHideSetting

        when
        {
            !mainActivity.canDrawOverlays() ->
            {
                stateText.setText(R.string.gaku_state_needs_overlay)
                startButton.setText(R.string.gaku_button_open_settings)
                showButtons(start = true, showHide = false, stop = false)
            }
            running ->
            {
                stateText.setText(if (shown) R.string.gaku_state_running else R.string.gaku_state_hidden)
                showHideButton.setText(if (shown) R.string.gaku_button_hide else R.string.gaku_button_show)
                showButtons(start = false, showHide = true, stop = true)
            }
            starting ->
            {
                stateText.setText(R.string.gaku_state_starting)
                showButtons(start = false, showHide = false, stop = false)
                // In case the service failed to start, which MainActivity isn't told about
                refreshStateSoon(5000)
            }
            else ->
            {
                stateText.setText(R.string.gaku_state_off)
                startButton.setText(R.string.gaku_button_start)
                showButtons(start = true, showHide = false, stop = false)
            }
        }
        progressBar.visibility = if (starting) View.VISIBLE else View.INVISIBLE

        val hints = ArrayList<String>()
        if (!mainActivity.canShowNotifications() && mainActivity.notificationsAsked()) hints.add(getString(R.string.gaku_hint_notifications))
        if (!running && Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) hints.add(getString(R.string.gaku_hint_entire_screen))
        hintText.text = hints.joinToString("\n\n")
        hintText.visibility = if (hints.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun showButtons(start: Boolean, showHide: Boolean, stop: Boolean)
    {
        startButton.visibility = if (start) View.VISIBLE else View.GONE
        showHideButton.visibility = if (showHide) View.VISIBLE else View.GONE
        stopButton.visibility = if (stop) View.VISIBLE else View.GONE
    }

    private fun refreshStateSoon(delayMillis: Long)
    {
        rootView.postDelayed({ if (isResumed) refreshState() }, delayMillis)
    }
}




