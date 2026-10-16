package ca.fuwafuwa.gaku;

import android.os.Handler;
import android.os.Message;
import android.util.Log;
import android.widget.Toast;

import ca.fuwafuwa.gaku.Ocr.OcrResult;
import ca.fuwafuwa.gaku.Windows.InformationWindow;
import ca.fuwafuwa.gaku.Windows.InstantKanjiWindow;
import ca.fuwafuwa.gaku.Windows.WindowCoordinator;

/**
 * Created by 0xbad1d3a5 on 4/15/2016.
 */
public class MainServiceHandler extends Handler {

    private static final String TAG = MainServiceHandler.class.getName();

    private MainService mGakuService;
    private WindowCoordinator mWindowCoordinator;

    public MainServiceHandler(MainService mainService, WindowCoordinator windowCoordinator) {
        mGakuService = mainService;
        mWindowCoordinator = windowCoordinator;
    }

    @Override
    public void handleMessage(Message message) {
        if (message.obj instanceof String) {
            Toast.makeText(mGakuService, message.obj.toString(), Toast.LENGTH_SHORT).show();
        } else if (message.obj instanceof OcrResult) {
            OcrResult result = (OcrResult) message.obj;

            // A read that finished after Gaku was hidden or shut down (neither waits for it): showing it
            // would bring back a window the user just put away
            if (!mWindowCoordinator.hasWindow(Constants.WINDOW_CAPTURE)) {
                Log.d(TAG, "Dropping a result that arrived after the capture window closed");
                return;
            }

            // Timing only: never log the recognised text
            Log.d(TAG, String.format("%s, %d chars, instant: %b", result.getMessage(), result.getText().length(),
                    result.getDisplayData().getInstantMode()));

            if (result.getDisplayData().getInstantMode()) {
                InstantKanjiWindow instantKanjiWindow = mWindowCoordinator
                        .getWindowOfType(Constants.WINDOW_INSTANT_KANJI);
                instantKanjiWindow.setResult(result.getDisplayData());
                instantKanjiWindow.show();
            } else {
                InformationWindow infoWindow = mWindowCoordinator.getWindowOfType(Constants.WINDOW_INFO);
                infoWindow.setResult(result.getDisplayData());
                infoWindow.show();
            }
        } else {
            Toast.makeText(mGakuService, String.format("Unable to handle type: %s", message.obj.getClass().getName()),
                    Toast.LENGTH_SHORT).show();
        }
    }
}
