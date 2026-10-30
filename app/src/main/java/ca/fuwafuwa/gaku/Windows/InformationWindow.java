package ca.fuwafuwa.gaku.Windows;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.os.Build;
import android.util.Log;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextSwitcher;
import android.widget.TextView;
import android.widget.Toast;

import org.jetbrains.annotations.NotNull;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import ca.fuwafuwa.gaku.Constants;
import ca.fuwafuwa.gaku.Database.JmDictDatabase.Models.EntryOptimized;
import ca.fuwafuwa.gaku.GakuTools;
import ca.fuwafuwa.gaku.LangUtils;
import ca.fuwafuwa.gaku.Llm.OllamaClient;
import ca.fuwafuwa.gaku.Llm.Prompts;
import ca.fuwafuwa.gaku.MainService;
import ca.fuwafuwa.gaku.Prefs;
import ca.fuwafuwa.gaku.R;
import ca.fuwafuwa.gaku.Search.JmSearchResult;
import ca.fuwafuwa.gaku.Search.SearchInfo;
import ca.fuwafuwa.gaku.Search.Searcher;
import ca.fuwafuwa.gaku.Windows.Data.DisplayData;
import ca.fuwafuwa.gaku.Windows.Data.DisplayDataOcr;
import ca.fuwafuwa.gaku.Windows.Data.ISquareChar;
import ca.fuwafuwa.gaku.Windows.Data.SquareChar;
import ca.fuwafuwa.gaku.Windows.Data.SquareCharOcr;
import ca.fuwafuwa.gaku.Windows.Interfaces.ICopyText;
import ca.fuwafuwa.gaku.Windows.Interfaces.IRecalculateKanjiViews;
import ca.fuwafuwa.gaku.Windows.Interfaces.ISearchPerformer;
import ca.fuwafuwa.gaku.Windows.Views.KanjiCharacterView;
import ca.fuwafuwa.gaku.Windows.Views.KanjiGridView;

/**
 * Created by 0xbad1d3a5 on 4/23/2016.
 */
public class InformationWindow extends Window implements Searcher.SearchDictDone, IRecalculateKanjiViews, ISearchPerformer, ICopyText
{

    private static final String TAG = InformationWindow.class.getName();
    private static final float FLICK_THRESHOLD = -0.05f;

    private static final String GOOGLE_TRANSLATE_PACKAGE = "com.google.android.apps.translate";

    private GestureDetector mGestureDetector;
    private float mMaxFlingVelocity;
    private LinearLayout mInfoWindow;
    private KanjiGridView mKanjiGrid;
    private TextSwitcher mDictResults;
    private View mPrevButton;
    private View mNextButton;
    private TextView mInstantButton;
    private View mCharActions;
    private TextView mCharText;
    private LinearLayout mCharChoices;
    private Searcher mSearcher;
    private boolean mTextOnlyLookup;
    private ArrayList<ISquareChar> mSearchedChars = new ArrayList<>();
    // The character last tapped in the grid, which the character buttons act on
    private ISquareChar mSelectedChar;
    // The text shown, and which of its characters show() looks up: the first for new results, or the
    // one that was selected when a character was swapped, edited or deleted
    private DisplayData mDisplayData;
    private int mSelectIndex;
    private TextView mReadAgainButton;
    // The OCR result shown, to read again with the other reader; null for text looked up from another app
    private DisplayDataOcr mOcrResult;
    // lan build: Google and Ask, shown when an Ollama server is set, and the LLM's translation
    private View mGoogleButton;
    private View mAskButton;
    private TextView mLlmTranslation;
    private OllamaClient.Call mLlmCall;
    // For the chat: the finished translation, and the word last looked up with its entry as shown
    private String mTranslation;
    private String mLookedUpWord;
    private String mLookedUpEntry;

    public InformationWindow(Context context, WindowCoordinator windowCoordinator)
    {
        super(context, windowCoordinator, R.layout.window_info);

        mMaxFlingVelocity = ViewConfiguration.get(this.context).getScaledMaximumFlingVelocity();
        mGestureDetector = new GestureDetector(this.context, this);
        mInfoWindow = window.findViewById(R.id.info_window);
        mKanjiGrid = window.findViewById(R.id.kanji_grid);
        mDictResults = window.findViewById(R.id.dict_results);
        mPrevButton = window.findViewById(R.id.info_prev);
        mNextButton = window.findViewById(R.id.info_next);
        mInstantButton = window.findViewById(R.id.info_instant);
        mCharActions = window.findViewById(R.id.info_char_actions);
        mCharText = window.findViewById(R.id.info_char);
        mCharChoices = window.findViewById(R.id.info_char_choices);

        mKanjiGrid.setDependencies(windowCoordinator, this);
        // The grid only knows how many characters fit once it has been laid out
        mKanjiGrid.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> updateScrollButtons());

        mPrevButton.setOnClickListener(v -> scroll(false));
        mNextButton.setOnClickListener(v -> scroll(true));
        window.findViewById(R.id.info_translate).setOnClickListener(v -> translate());
        mGoogleButton = window.findViewById(R.id.info_google);
        mGoogleButton.setOnClickListener(v -> sendToTranslate());
        mAskButton = window.findViewById(R.id.info_ask);
        mAskButton.setOnClickListener(v -> openChat());
        mLlmTranslation = window.findViewById(R.id.info_llm_translation);
        window.findViewById(R.id.info_copy).setOnClickListener(v -> copyText());
        mReadAgainButton = window.findViewById(R.id.info_read_again);
        mReadAgainButton.setOnClickListener(v -> {
            if (mOcrResult == null) return;
            CaptureWindow captureWindow = windowCoordinator.getWindowOfType(Constants.WINDOW_CAPTURE);
            captureWindow.readAgain(mOcrResult);
        });
        mInstantButton.setOnClickListener(v -> toggleInstantMode());
        window.findViewById(R.id.info_close).setOnClickListener(v -> hide());
        window.findViewById(R.id.info_char_edit).setOnClickListener(v -> {
            KanjiCharacterView view = getSelectedView();
            if (view != null) view.edit();
        });
        window.findViewById(R.id.info_char_delete).setOnClickListener(v -> {
            KanjiCharacterView view = getSelectedView();
            if (view != null) view.delete();
        });

        try {
            mSearcher = new Searcher(context);
            mSearcher.registerCallback(this);
        } catch (SQLException e) {
            e.printStackTrace();
        }

        show();
    }

    public void setResult(DisplayData displayData)
    {
        mSearchedChars = new ArrayList<>();
        clearLlm();
        mDisplayData = displayData;
        mSelectIndex = 0;

        mKanjiGrid.removeAllViews();
        mKanjiGrid.setText(displayData);

        mOcrResult = displayData instanceof DisplayDataOcr ? (DisplayDataOcr) displayData : null;
        mReadAgainButton.setVisibility(mOcrResult != null ? View.VISIBLE : View.GONE);
        mInstantButton.setVisibility(View.VISIBLE);
        if (mOcrResult != null)
        {
            mReadAgainButton.setText(mOcrResult.getReadWithMangaOcr() ? "Read with ML Kit" : "Read with manga-ocr");
        }
    }

    public void setResult(String textResult)
    {
        clearLlm();
        mOcrResult = null;
        mReadAgainButton.setVisibility(View.GONE);
        // Instant mode is about the capture box, and the service may not even be running
        mInstantButton.setVisibility(View.GONE);

        List<String> charList = GakuTools.splitTextByChar(textResult);
        List<ISquareChar> squareCharList = new ArrayList<>();
        DisplayData displayData = new DisplayData(squareCharList);
        for (String c : charList) squareCharList.add(new SquareChar(displayData, c));
        displayData.assignIndicies();

        mKanjiGrid.setText(displayData);
        // show() looks up the first character
        mDisplayData = displayData;
        mSelectIndex = 0;

        mTextOnlyLookup = true;
    }

    public void copyText()
    {
        ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        ClipData clip = ClipData.newPlainText(null, mKanjiGrid.getText());
        clipboard.setPrimaryClip(clip);
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU)
        {
            // Android 13+ shows its own confirmation
            Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show();
        }

        hide();
    }

    @Override
    public void performSearch(@NotNull ISquareChar squareChar)
    {
        mKanjiGrid.unhighlightAll(squareChar);
        mSearcher.search(new SearchInfo(squareChar));
        setSelectedChar(squareChar);
    }

    /**
     * With an Ollama server set (lan build), shows the LLM's translation above the dictionary results;
     * otherwise opens Google Translate.
     */
    private void translate()
    {
        Prefs prefs = GakuTools.getPrefs(context);
        if (!prefs.getLlmConfigured())
        {
            sendToTranslate();
            return;
        }

        clearLlm();
        mLlmTranslation.setVisibility(View.VISIBLE);
        mLlmTranslation.setText("Translating…");

        StringBuilder sb = new StringBuilder();
        mLlmCall = new OllamaClient(prefs.getLlmServer(), prefs.getLlmApiKey()).chat(prefs.getLlmModel(), Prompts.translationMessages(mKanjiGrid.getText()), new OllamaClient.ChatListener()
        {
            @Override
            public void onThinking()
            {
                mLlmTranslation.setText("Thinking…");
            }

            @Override
            public void onText(@NotNull String text)
            {
                sb.append(text);
                mLlmTranslation.setText(sb.toString().trim());
            }

            @Override
            public void onDone()
            {
                mTranslation = sb.toString().trim();
                if (mTranslation.isEmpty()) mLlmTranslation.setText("The model didn't answer");
            }

            @Override
            public void onError(@NotNull String message)
            {
                mLlmTranslation.setText(message);
            }
        });
    }

    /** Opens the chat window about the text, with the word last looked up and the translation, if any. */
    private void openChat()
    {
        LlmChatWindow chatWindow = windowCoordinator.getWindowOfType(Constants.WINDOW_LLM_CHAT);
        chatWindow.open(mKanjiGrid.getText(), mLookedUpWord, mLookedUpEntry, mTranslation);
    }

    /** Stops any translation in progress and hides the last one, which may be for other text. */
    private void clearLlm()
    {
        if (mLlmCall != null)
        {
            mLlmCall.cancel();
            mLlmCall = null;
        }
        mTranslation = null;
        mLlmTranslation.setText("");
        mLlmTranslation.setVisibility(View.GONE);
    }

    /**
     * Opens Google Translate's pop-up with the recognised text. Gaku itself never contacts Google;
     * Translate only gets the text when this button is pressed.
     */
    private void sendToTranslate()
    {
        String text = mKanjiGrid.getText();
        Intent[] intents = {
            // The pop-up Translate shows for "Translate" in a text selection menu
            new Intent(Intent.ACTION_PROCESS_TEXT)
                    .putExtra(Intent.EXTRA_PROCESS_TEXT, text)
                    .putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, true),
            // Older versions: the full app
            new Intent(Intent.ACTION_SEND)
                    .putExtra(Intent.EXTRA_TEXT, text)
        };

        for (Intent intent : intents)
        {
            intent.setType("text/plain")
                    .setPackage(GOOGLE_TRANSLATE_PACKAGE)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try
            {
                context.startActivity(intent);
                // This window covers the whole screen, so it would hide Translate
                hide();
                return;
            }
            catch (ActivityNotFoundException e)
            {
                Log.d(TAG, "Google Translate can't handle " + intent.getAction());
            }
        }

        Toast.makeText(context, "Google Translate isn't installed", Toast.LENGTH_LONG).show();
    }

    /**
     * Goes through the same receiver as the notification's button, which also updates the
     * notification and the capture window's copy of the setting.
     */
    private void toggleInstantMode()
    {
        boolean instant = !GakuTools.getPrefs(context).getInstantModeSetting();
        context.sendBroadcast(new Intent(context, MainService.ToggleInstantModeMainService.class));
        updateInstantButton(instant);
    }

    private void updateInstantButton(boolean instant)
    {
        mInstantButton.setText(instant ? "Instant: on" : "Instant: off");
    }

    private void scroll(boolean next)
    {
        if (next)
        {
            mKanjiGrid.scrollNext();
        }
        else
        {
            mKanjiGrid.scrollPrev();
        }
        // The selected character may have scrolled out of view
        setSelectedChar(null);
        updateScrollButtons();
    }

    private void updateScrollButtons()
    {
        mPrevButton.setVisibility(mKanjiGrid.getCanScrollPrev() ? View.VISIBLE : View.INVISIBLE);
        mNextButton.setVisibility(mKanjiGrid.getCanScrollNext() ? View.VISIBLE : View.INVISIBLE);
    }

    /**
     * Shows the buttons for [squareChar]: its lookalikes (if it came from OCR) to swap in, Edit and
     * Delete. Null hides them.
     */
    private void setSelectedChar(ISquareChar squareChar)
    {
        mSelectedChar = squareChar;
        mCharChoices.removeAllViews();

        if (squareChar == null)
        {
            mCharActions.setVisibility(View.GONE);
            return;
        }

        mCharText.setText(squareChar.getChar());

        if (squareChar instanceof SquareCharOcr)
        {
            for (kotlin.Pair<String, Double> choice : ((SquareCharOcr) squareChar).getAllChoices())
            {
                final String c = choice.getFirst();
                if (c.equals(squareChar.getChar())) continue;

                TextView button = (TextView) LayoutInflater.from(context).inflate(R.layout.view_info_button, mCharChoices, false);
                button.setText(c);
                button.setOnClickListener(v -> {
                    KanjiCharacterView view = getSelectedView();
                    if (view != null) view.swap(c);
                });
                mCharChoices.addView(button);
            }
        }

        mCharActions.setVisibility(View.VISIBLE);
    }

    private KanjiCharacterView getSelectedView()
    {
        for (KanjiCharacterView view : mKanjiGrid.getKanjiViewList())
        {
            if (view.getSquareChar() == mSelectedChar) return view;
        }
        return null;
    }

    /**
     * InformationWindow does not need to reInit layout as its getDefaultParams() are all relative. Re-initing will cause bugs.
     */
    @Override
    public void reInit(ReinitOptions options)
    {
        options.reinitViewLayout = false;
        super.reInit(options);
    }

    /**
     * Focusable, unlike the other overlays, so that Back closes the results (onBackKey) instead of the app
     * being read underneath. It covers the screen and takes every touch anyway.
     */
    @Override
    protected WindowManager.LayoutParams getDefaultParams()
    {
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
                Build.VERSION.SDK_INT > 25 ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT);
        params.x = 0;
        params.y = 0;
        params.gravity = Gravity.TOP | Gravity.CENTER;
        // Nothing here takes text, so never bring up the keyboard now that the window can take focus
        params.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING | WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN;
        return params;
    }

    @Override
    public boolean onBackKey(KeyEvent e)
    {
        // Close on release, as Back does elsewhere; the press is taken too so the app doesn't see half of it
        if (e.getAction() == KeyEvent.ACTION_UP && !e.isCanceled())
        {
            hide();
        }
        return true;
    }

    @Override
    public boolean onTouch(MotionEvent e)
    {
        mGestureDetector.onTouchEvent(e);

        if (e.getAction() == MotionEvent.ACTION_UP){
            params.y = 0;
            windowManager.updateViewLayout(window, params);
            return true;
        }

        return false;
    }

    @Override
    public void show()
    {
        mDictResults.setText("");
        // Shown again for new results, or after a character was swapped, edited or deleted
        setSelectedChar(null);
        clearLlm();
        mLookedUpWord = null;
        mLookedUpEntry = null;
        Prefs prefs = GakuTools.getPrefs(context);
        updateInstantButton(prefs.getInstantModeSetting());
        // The server can be set or cleared on the start screen while Gaku runs
        mGoogleButton.setVisibility(prefs.getLlmConfigured() ? View.VISIBLE : View.GONE);
        mAskButton.setVisibility(prefs.getLlmConfigured() ? View.VISIBLE : View.GONE);

        window.setVisibility(View.VISIBLE);
        params.y = 0; // onScroll changes this value
        super.show();
        window.setY(0); // translationY changes this value

        // Look a character up straight away, so its lookalikes, Edit and Delete are already in view
        if (mDisplayData != null && mDisplayData.getCount() > 0)
        {
            List<ISquareChar> chars = mDisplayData.getSquareChars();
            performSearch(chars.get(Math.min(mSelectIndex, chars.size() - 1)));
        }
    }

    @Override
    public void hide()
    {
        clearLlm();
        window.animate().translationY(-getRealDisplaySize().y).setListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation)
            {
                window.setVisibility(View.INVISIBLE);

                if (!mTextOnlyLookup)
                {
                    InformationWindow.super.hide();
                }
                else
                {
                    // hide() may have been called multiple times - this isn't thread-safe but whatever
                    if (windowCoordinator != null)
                    {
                        windowCoordinator.stopAllWindows();
                    }
                }
            }
        });
    }

    @Override
    public void stop()
    {
        clearLlm();
        mSearcher.unregisterCallback();
        super.stop();
    }

    @Override
    public boolean onResize(MotionEvent e)
    {
        return false;
    }

    @Override
    public boolean onDown(MotionEvent motionEvent)
    {
        return true;
    }

    @Override
    public boolean onDoubleTap(MotionEvent e)
    {
        if (mInfoWindow.getX() < e.getX() && e.getX() < (mInfoWindow.getX() + mInfoWindow.getWidth()) &&
            mInfoWindow.getY() < e.getY() && e.getY() < (mInfoWindow.getY() + mInfoWindow.getHeight()))
        {
            // Do nothing
        }
        else
        {
            hide();
        }

        return true;
    }

    @Override
    public boolean onSingleTapUp(MotionEvent e)
    {
        if (e.getY() < mKanjiGrid.getY() + mKanjiGrid.getHeight())
        {
            int triggerAreaSize = getViewWidth() / 8;
            if (e.getX() > getViewWidth() - triggerAreaSize)
            {
                scroll(true);
            }
            else if (e.getX() < triggerAreaSize)
            {
                scroll(false);
            }
        }
        else if (mInfoWindow.getX() < e.getX() && e.getX() < (mInfoWindow.getX() + mInfoWindow.getWidth()) &&
                 mInfoWindow.getY() < e.getY() && e.getY() < (mInfoWindow.getY() + mInfoWindow.getHeight()))
        {
            // Do nothing
        }
        else
        {
            hide();
        }

        return true;
    }

    @Override
    public boolean onScroll(MotionEvent motionEvent, MotionEvent motionEvent1, float v, float v1)
    {
        if (motionEvent == null || motionEvent1 == null){
            return false;
        }

        params.y = (int) (motionEvent1.getRawY() - motionEvent.getRawY());
        if (params.y > 0){
            params.y = 0;
        }
        windowManager.updateViewLayout(window, params);

        return true;
    }

    @Override
    public boolean onFling(MotionEvent motionEvent, MotionEvent motionEvent1, float v, float v1)
    {
        if (motionEvent == null || motionEvent1 == null){
            return false;
        }

        float distanceMoved = motionEvent.getRawY() - motionEvent1.getRawY();

        Log.d(TAG, String.format("Fling strength: %f", v1 / mMaxFlingVelocity));
        Log.d(TAG, String.format("Distance moved: %f", distanceMoved));

        if ((v1 / mMaxFlingVelocity) < FLICK_THRESHOLD)
        {
            hide();
            return true;
        }

        return false;
    }

    @Override
    public void jmResultsCallback(List<JmSearchResult> results, SearchInfo search)
    {
        windowCoordinator.getWindow(Constants.WINDOW_INSTANT_KANJI).hide();

        if (results.size() > 0)
        {
            displayResults(results);
            mLookedUpWord = results.get(0).getWord();

            if (search.getSquareChar().getUserTouched() && !mSearchedChars.contains(search.getSquareChar()))
            {
                //windowCoordinator.<HistoryWindow>getWindowOfType(Constants.WINDOW_HISTORY).addResult(search.getSquareChar(), results);
                mSearchedChars.add(search.getSquareChar());
            }
        }

        // Highlights words in the window as long as they match
        // The character may have scrolled out of the grid since the search was asked for
        List<KanjiCharacterView> views = mKanjiGrid.getKanjiViewList();
        int start = search.getIndex() - mKanjiGrid.getOffset();
        if (results.size() > 0){
            String kanji = results.get(0).getWord();
            for (int i = Math.max(start, 0); i < start + kanji.codePointCount(0, kanji.length()); i++){
                if (i >= views.size()){
                    break;
                }
                views.get(i).highlight();
            }
        }
        else if (start >= 0 && start < views.size()) {
            views.get(start).highlight();
        }
    }

    /**
     * Called when a character was swapped, edited or deleted, just before show(). Keeps the selection in
     * the same place: on the new character, or on the next one after a delete. With nothing selected
     * (after scrolling), it's the first character in view.
     */
    @Override
    public void recalculateKanjiViews()
    {
        mSelectIndex = mSelectedChar != null ? mSelectedChar.getIndex() : mKanjiGrid.getOffset();
        mKanjiGrid.recalculateKanjiViews();
    }

    private void displayResults(List<JmSearchResult> jmResults)
    {
        StringBuilder sb = new StringBuilder();

        for (JmSearchResult jmSearchResult : jmResults)
        {
            sb.append(jmSearchResult.getEntry().getKanji());

            if (!jmSearchResult.getEntry().getReadings().isEmpty()){
                if (Constants.DB_JMDICT_NAME.equals(jmSearchResult.getEntry().getDictionary()))
                {
                    sb.append(" (");
                }
                else {
                    sb.append(" ");
                }
                sb.append(jmSearchResult.getEntry().getReadings());
                if (Constants.DB_JMDICT_NAME.equals(jmSearchResult.getEntry().getDictionary())) sb.append(")");
            }

            String deinfReason = jmSearchResult.getDeinfInfo().getReason();
            if (deinfReason != null && !deinfReason.isEmpty()){
                sb.append(String.format(" %s", deinfReason));
            }

            sb.append("\n");
            sb.append(getMeaning(jmSearchResult.getEntry()));
            sb.append("\n\n");
        }

        if (sb.length() > 2)
        {
            sb.setLength(sb.length() - 2);
        }

        mDictResults.setText(sb.toString());
        mLookedUpEntry = sb.toString();
    }

    private String getMeaning(EntryOptimized entry)
    {
        String[] meanings = entry.getMeanings().split("\ufffc", -1);
        String[] pos = entry.getPos().split("\ufffc", -1);

        StringBuilder sb = new StringBuilder();

        for (int i = 0; i < meanings.length; i++){
            if (i != 0){
                sb.append(" ");
            }
            sb.append(LangUtils.Companion.ConvertIntToCircledNum(i + 1));
            sb.append(" ");
            if (Constants.DB_JMDICT_NAME.equals(entry.getDictionary()) && !pos[i].isEmpty()){
                sb.append(String.format("(%s) ", pos[i]));
            }
            sb.append(meanings[i]);
        }

        return sb.toString();
    }
}



