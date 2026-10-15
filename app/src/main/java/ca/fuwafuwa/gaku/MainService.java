package ca.fuwafuwa.gaku;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import androidx.annotation.RequiresApi;
import androidx.core.app.NotificationCompat;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;
import android.view.WindowManager;
import android.widget.Toast;

import ca.fuwafuwa.gaku.Interfaces.Stoppable;
import ca.fuwafuwa.gaku.Windows.Window;
import ca.fuwafuwa.gaku.Windows.WindowCoordinator;

import static androidx.core.app.NotificationCompat.FLAG_FOREGROUND_SERVICE;
import static androidx.core.app.NotificationCompat.FLAG_ONGOING_EVENT;

/**
 * Created by 0xbad1d3a5 on 4/9/2016.
 */
public class MainService extends Service implements Stoppable {

    private static final String TAG = MainService.class.getName();

    public static class CloseMainService extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            Log.d(TAG, "GOT CLOSE");
            context.stopService(new Intent(context, MainService.class));
        }
    }

    public static class ToggleShowHideMainService extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            SharedPreferences prefs = context.getSharedPreferences(Constants.GAKU_PREF_FILE, Context.MODE_PRIVATE);
            boolean shown = prefs.getBoolean(Constants.GAKU_PREF_SHOW_HIDE, true);
            prefs.edit().putBoolean(Constants.GAKU_PREF_SHOW_HIDE, !shown).apply();

            GakuTools.startGakuService(context, new Intent(context, MainService.class));
        }
    }

    public static class ToggleInstantModeMainService extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            SharedPreferences prefs = context.getSharedPreferences(Constants.GAKU_PREF_FILE, Context.MODE_PRIVATE);
            boolean pageMode = prefs.getBoolean(Constants.GAKU_PREF_INSTANT_MODE, true);
            prefs.edit().putBoolean(Constants.GAKU_PREF_INSTANT_MODE, !pageMode).apply();

            GakuTools.startGakuService(context, new Intent(context, MainService.class));
        }
    }

    public static class ScreenOffReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            SharedPreferences prefs = context.getSharedPreferences(Constants.GAKU_PREF_FILE, Context.MODE_PRIVATE);
            prefs.edit().putBoolean(Constants.GAKU_PREF_SHOW_HIDE, false).apply();

            GakuTools.startGakuService(context, new Intent(context, MainService.class));
        }
    }

    private class ProjectionCallback extends MediaProjection.Callback {
        /**
         * Only called when something else stopped the projection (e.g. the status bar's "stop sharing"
         * chip): {@link #releaseProjection()} unregisters this before stopping it itself. A stopped
         * projection can't be restarted without asking for consent again, so shut Gaku down; relaunching
         * the app asks again.
         */
        @Override
        public void onStop() {
            Log.d(TAG, "Projection stopped externally");
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    if (ProjectionCallback.this == mProjectionCallback) {
                        releaseProjection();
                        Toast.makeText(MainService.this, "Screen capture stopped, closing Gaku", Toast.LENGTH_LONG).show();
                        stopSelf();
                    }
                }
            });
        }

        /**
         * Android 14+: the size of what's being captured, the whole screen or (in single-app mode) the
         * shared app's window.
         */
        @Override
        public void onCapturedContentResize(int width, int height) {
            Log.d(TAG, String.format("Captured content size: %dx%d", width, height));
            mCapturedContentSize = new Point(width, height);
        }

        /**
         * Android 14+: in single-app mode, whether the shared app is on screen. The whole screen always is.
         */
        @Override
        public void onCapturedContentVisibilityChanged(boolean isVisible) {
            Log.d(TAG, "Captured content visible: " + isVisible);
            mCapturedContentVisible = isVisible;
        }
    }

    private static boolean isGakuRunning = false;

    private static final int VIRTUAL_DISPLAY_FLAGS = DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY
            | DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC;
    private static final int NOTIFICATION_ID = 1;
    // How long to wait for a frame drawn after the caller's change before deciding the screen hasn't changed
    private static final long NO_NEW_FRAME_WAIT_NS = 250_000_000L;
    // After the first such frame, how long to keep taking newer ones, in case it was already being
    // composed when the change landed
    private static final long LATE_FRAME_WAIT_NS = 25_000_000L;

    private final Object mScreenshotLock = new Object();

    private IntentFilter mIntentFilter = new IntentFilter(Intent.ACTION_SCREEN_OFF);
    private ScreenOffReceiver mScreenOffReceiver = new ScreenOffReceiver();

    private Intent mProjectionResultIntent;
    private int mProjectionResultCode;

    private WindowManager mWindowManager;
    private MediaProjectionManager mMediaProjectionManager;
    private MediaProjection mMediaProjection;
    private ImageReader mImageReader;
    // The newest frame taken from mImageReader. Kept open because the virtual display only renders when
    // the captured content changes, so on a still screen this stays the current frame.
    private Image mLatestImage;
    private Display mDisplay;
    private VirtualDisplay mVirtualDisplay;
    private MainServiceHandler mHandler;

    private int mRotation;
    // The screen's size in its current orientation. Guarded by mScreenshotLock.
    private Point mRealDisplaySize = new Point();
    // Width and height of the square virtual display
    private int mVirtualDisplaySize;

    private ProjectionCallback mProjectionCallback;
    // Reported by ProjectionCallback on Android 14+; null until then
    private volatile Point mCapturedContentSize;
    private volatile boolean mCapturedContentVisible = true;
    private WindowCoordinator mWindowCoordinator = new WindowCoordinator(this);

    @Override
    public IBinder onBind(Intent intent) {
        // Not used
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();

        if (!isGakuRunning) {
            SharedPreferences prefs = getSharedPreferences(Constants.GAKU_PREF_FILE, Context.MODE_PRIVATE);
            prefs.edit().putBoolean(Constants.GAKU_PREF_SHOW_HIDE, true).apply();
        }

        Log.d(TAG, "CREATING MAINSERVICE: " + System.identityHashCode(this));
        Toast.makeText(this, "Starting capture window...", Toast.LENGTH_LONG).show();

        mMediaProjectionManager = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        mHandler = new MainServiceHandler(this, mWindowCoordinator);

        // Set preferences for ratings
        SharedPreferences prefs = getSharedPreferences(Constants.GAKU_PREF_FILE, Context.MODE_PRIVATE);
        int timesLaunched = prefs.getInt(Constants.GAKU_PREF_TIMES_LAUNCHED, 1);
        prefs.edit().putInt(Constants.GAKU_PREF_TIMES_LAUNCHED, timesLaunched + 1).apply();

        registerReceiver(mScreenOffReceiver, mIntentFilter);

        startForeground(NOTIFICATION_ID, getNotification());
        isGakuRunning = true;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "onStartCommand");

        if (intent.getExtras() != null &&
                intent.getExtras().containsKey(Constants.EXTRA_PROJECTION_RESULT_CODE) &&
                intent.getExtras().containsKey(Constants.EXTRA_PROJECTION_RESULT_INTENT)) {
            mProjectionResultIntent = (Intent) intent.getExtras().get(Constants.EXTRA_PROJECTION_RESULT_INTENT);
            mProjectionResultCode = intent.getExtras().getInt(Constants.EXTRA_PROJECTION_RESULT_CODE);
        }

        // Determine if we need to start/stop the capture service
        SharedPreferences prefs = getSharedPreferences(Constants.GAKU_PREF_FILE, Context.MODE_PRIVATE);
        Boolean shown = prefs.getBoolean(Constants.GAKU_PREF_SHOW_HIDE, true);
        if (shown) {
            // Re-init CaptureWindow as well as prefs may have changed (BroadcastReceiver go
            // to onStartCommand())
            mWindowCoordinator.getWindow(Constants.WINDOW_CAPTURE).reInit(new Window.ReinitOptions());
        } else {
            mWindowCoordinator.stopAllWindows();
            pauseCapture();
        }

        // Set notification text
        NotificationManager notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        notificationManager.notify(NOTIFICATION_ID, getNotification());
        ShowHideTileService.requestUpdate(this);

        return START_NOT_STICKY;
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);

        if (mWindowCoordinator.hasWindow(Constants.WINDOW_CAPTURE) && mMediaProjection != null) {
            final int rotation = mDisplay.getRotation();
            if (rotation != mRotation) {
                Log.d(TAG, "Orientation changed");
                mRotation = rotation;
                synchronized (mScreenshotLock) {
                    mDisplay.getRealSize(mRealDisplaySize);
                    // It shows the old orientation
                    closeLatestImage();
                }
                mWindowCoordinator.reinitAllWindows();
            }
        }
    }

    @Override
    public void onDestroy() {
        unregisterReceiver(mScreenOffReceiver);
        stopForeground(true);
        Log.d(TAG, "DESTORYING MAINSERVICE: " + System.identityHashCode(this));

        stop();
        mWindowCoordinator.stopAllWindows();
        mWindowCoordinator = null;
        isGakuRunning = false;
        ShowHideTileService.requestUpdate(this);

        Log.d(TAG, String.format("MAINSERVICE: %s DESTROYED", System.identityHashCode(this)));
        super.onDestroy();
    }

    @Override
    public void stop() {
        releaseProjection();
    }

    private void releaseProjection() {
        stopVirtualDisplay();
        if (mMediaProjection != null) {
            mMediaProjection.unregisterCallback(mProjectionCallback);
            mMediaProjection.stop();
            mMediaProjection = null;
        }
    }

    /**
     * While hidden, stop rendering frames but keep the projection: on Android 14+ a consent can only be
     * used once and a projection can only create one VirtualDisplay, so both must live as long as the
     * service. {@link #createVirtualDisplay()} attaches a new surface when the capture window is shown.
     */
    private void pauseCapture() {
        synchronized (mScreenshotLock) {
            if (mVirtualDisplay != null) {
                mVirtualDisplay.setSurface(null);
            }
            closeLatestImage();
            if (mImageReader != null) {
                mImageReader.close();
                mImageReader = null;
            }
        }
    }

    public static boolean IsRunning() {
        return isGakuRunning;
    }

    /**
     * This function is here as a bug fix against
     * {@link #onConfigurationChanged(Configuration)} not
     * triggering when the app is first started and immediately switches to another
     * orientation. In
     * such a case onConfigurationChanged will not trigger and
     * {@link Window#reInit(ca.fuwafuwa.gaku.Windows.Window.ReinitOptions)} will not
     * update the LayoutParams.
     */
    public void onCaptureWindowFinishedInitializing() {
        try {
            if (mMediaProjection == null) {
                Log.d(TAG, "mMediaProjection is null");
                if (mProjectionResultIntent == null) {
                    throw new SecurityException("No unused screen capture consent");
                }
                mMediaProjection = mMediaProjectionManager.getMediaProjection(mProjectionResultCode,
                        mProjectionResultIntent);
                // A consent can only be used once on Android 14+
                mProjectionResultIntent = null;
                if (mMediaProjection == null) {
                    throw new SecurityException("Screen capture consent was not granted");
                }
                mProjectionCallback = new ProjectionCallback();
                mMediaProjection.registerCallback(mProjectionCallback, mHandler);
            }
            createVirtualDisplay();
        } catch (SecurityException | IllegalStateException e) {
            Log.e(TAG, "Unable to start screen capture", e);
            Toast.makeText(this, "Screen capture unavailable, relaunch Gaku to allow it", Toast.LENGTH_LONG).show();
            stopSelf();
        }
    }

    public Handler getHandler() {
        return mHandler;
    }

    /**
     * {@code region} of the screen (in screen coordinates) as it was at or after {@code sinceNanos}
     * ({@link System#nanoTime()}), or null if nothing has been captured or the region is off screen.
     *
     * The virtual display only renders a frame when the captured content changes. So this waits briefly
     * for a frame rendered after {@code sinceNanos}; if none arrives, the screen hasn't changed and the
     * newest frame is still current. That's the usual case in single-app mode, where Gaku's own windows
     * aren't part of the captured content.
     */
    public Bitmap getScreenshotSince(long sinceNanos, Rect region) {
        synchronized (mScreenshotLock) {
            try {
                long deadline = System.nanoTime() + NO_NEW_FRAME_WAIT_NS;
                boolean gotNewFrame = false;
                while (mImageReader != null) {
                    Image image = mImageReader.acquireLatestImage();
                    if (image != null) {
                        closeLatestImage();
                        mLatestImage = image;
                        if (!gotNewFrame && image.getTimestamp() >= sinceNanos) {
                            gotNewFrame = true;
                            deadline = System.nanoTime() + LATE_FRAME_WAIT_NS;
                        }
                    }
                    if (System.nanoTime() >= deadline) {
                        break;
                    }
                    // Releases the lock, so pauseCapture() etc. can close the reader meanwhile
                    mScreenshotLock.wait(5);
                }

                if (mLatestImage == null) {
                    return null;
                }
                Log.d(TAG, String.format("Screenshot from %s frame, %.1f ms after the request",
                        gotNewFrame ? "a new" : "the last", (mLatestImage.getTimestamp() - sinceNanos) / 1e6));
                return cropFrame(mLatestImage, region);
            } catch (InterruptedException e) {
                return null;
            } catch (Exception e) {
                Log.e(TAG, "Error getting screenshot", e);
                return null;
            }
        }
    }

    /**
     * Why the captured frames can't show what's under the capture box right now, or null if they can.
     * Single-app capture records only the shared app's window, scaled into the virtual display, so screen
     * coordinates only line up while that app fills the screen.
     */
    public String getCaptureProblem() {
        if (!mCapturedContentVisible) {
            return "The app Gaku is sharing isn't on screen. Switch back to it, or relaunch Gaku to share this one";
        }
        Point size = mCapturedContentSize;
        if (size != null && (size.x != mRealDisplaySize.x || size.y != mRealDisplaySize.y)) {
            return "Gaku can only read a shared app while it's full screen";
        }
        return null;
    }

    private void closeLatestImage() {
        if (mLatestImage != null) {
            mLatestImage.close();
            mLatestImage = null;
        }
    }

    /**
     * Copies {@code screenRegion} (in screen coordinates) out of a frame, or returns null if it's off
     * screen. Android letterboxes the screen into the square virtual display, centred and unscaled.
     */
    private Bitmap cropFrame(Image image, Rect screenRegion) {
        Rect region = new Rect(screenRegion);
        region.offset((image.getWidth() - mRealDisplaySize.x) / 2, (image.getHeight() - mRealDisplaySize.y) / 2);
        if (region.isEmpty() || region.left < 0 || region.top < 0 ||
                region.right > image.getWidth() || region.bottom > image.getHeight()) {
            return null;
        }

        Image.Plane plane = image.getPlanes()[0];
        java.nio.ByteBuffer buffer = plane.getBuffer();
        int rowStride = plane.getRowStride();

        // copyPixelsFromBuffer() copies whole rows from the buffer's position, so copy the region's rows
        // (the same frame can be cropped more than once), then cut out its columns
        Bitmap rows = Bitmap.createBitmap(rowStride / plane.getPixelStride(), region.height(), Bitmap.Config.ARGB_8888);
        buffer.position(region.top * rowStride);
        rows.copyPixelsFromBuffer(buffer);
        return Bitmap.createBitmap(rows, region.left, 0, region.width(), region.height());
    }

    private Notification getNotification() {
        String channelId;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            channelId = createNotificationChannel();
        } else {
            channelId = "";
        }

        PendingIntent toggleShowHide = PendingIntent.getBroadcast(this, Constants.REQUEST_SERVICE_TOGGLE_SHOW_HIDE,
                new Intent(this, ToggleShowHideMainService.class), PendingIntent.FLAG_IMMUTABLE);
        PendingIntent toggleInstantMode = PendingIntent.getBroadcast(this,
                Constants.REQUEST_SERVICE_TOGGLE_INSTANT_MODE, new Intent(this, ToggleInstantModeMainService.class),
                PendingIntent.FLAG_IMMUTABLE);
        PendingIntent closeMainService = PendingIntent.getBroadcast(this, Constants.REQUEST_SERVICE_SHUTDOWN,
                new Intent(this, CloseMainService.class), PendingIntent.FLAG_IMMUTABLE);

        Prefs prefs = GakuTools.getPrefs(this);

        // Tapping the notification also shows/hides Gaku, but the buttons say so
        Notification n;
        if (prefs.getShowHideSetting()) {
            n = new NotificationCompat.Builder(this, channelId)
                    .setSmallIcon(R.drawable.gaku_notification_icon)
                    .setContentTitle("Gaku is running")
                    .setContentText(prefs.getInstantModeSetting()
                            ? "Instant mode on: small boxes are read as soon as you let go"
                            : "Instant mode off: double-tap the box to read it")
                    .setContentIntent(toggleShowHide)
                    .addAction(0, "Hide", toggleShowHide)
                    .addAction(0, prefs.getInstantModeSetting() ? "Turn instant off" : "Turn instant on", toggleInstantMode)
                    .addAction(0, "Shutdown", closeMainService)
                    .build();
        } else {
            n = new NotificationCompat.Builder(this, channelId)
                    .setSmallIcon(R.drawable.gaku_notification_icon)
                    .setContentTitle("Gaku is hidden and in power-saving mode")
                    .setContentIntent(toggleShowHide)
                    .addAction(0, "Show", toggleShowHide)
                    .addAction(0, "Shutdown", closeMainService)
                    .build();
        }

        n.flags = FLAG_ONGOING_EVENT | FLAG_FOREGROUND_SERVICE;

        return n;
    }

    private void createVirtualDisplay() {
        // display metrics
        mWindowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        DisplayMetrics metrics = getResources().getDisplayMetrics();
        int mDensity = metrics.densityDpi;
        mDisplay = mWindowManager.getDefaultDisplay();

        // One image is held as mLatestImage, and acquireLatestImage() needs two more to skip to the newest
        final int maxImages = 3;

        synchronized (mScreenshotLock) {
            mDisplay.getRealSize(mRealDisplaySize);

            if (mVirtualDisplay == null) {
                // Square, so the screen fits in either orientation and the display never needs resizing.
                // Resizing it on rotation sometimes left Android mirroring the rotated screen with the
                // old scaling (seen on Android 15: portrait frames shrunk to 62.5% and shifted right).
                mVirtualDisplaySize = Math.max(mRealDisplaySize.x, mRealDisplaySize.y);
                Log.d(TAG, String.format("Starting Projection: %dx%d", mVirtualDisplaySize, mVirtualDisplaySize));
                mImageReader = ImageReader.newInstance(mVirtualDisplaySize, mVirtualDisplaySize,
                        PixelFormat.RGBA_8888, maxImages);
                mVirtualDisplay = mMediaProjection.createVirtualDisplay(getClass().getName(), mVirtualDisplaySize,
                        mVirtualDisplaySize, mDensity, VIRTUAL_DISPLAY_FLAGS, mImageReader.getSurface(), null, mHandler);
            } else {
                // Shown again after being hidden
                ImageReader newImageReader = ImageReader.newInstance(mVirtualDisplaySize, mVirtualDisplaySize,
                        PixelFormat.RGBA_8888, maxImages);
                mVirtualDisplay.setSurface(newImageReader.getSurface());
                closeLatestImage();
                if (mImageReader != null) {
                    mImageReader.close();
                }
                mImageReader = newImageReader;
            }
        }
    }

    private void stopVirtualDisplay() {
        synchronized (mScreenshotLock) {
            if (mVirtualDisplay != null) {
                mVirtualDisplay.release();
                mVirtualDisplay = null;
            }
            closeLatestImage();
            if (mImageReader != null) {
                mImageReader.close();
                mImageReader = null;
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private String createNotificationChannel() {
        String channelId = Constants.GAKU_CHANNEL_ID;
        String channelName = Constants.GAKU_CHANNEL_NAME;

        NotificationChannel channel = new NotificationChannel(channelId, channelName,
                NotificationManager.IMPORTANCE_LOW);
        NotificationManager service = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        service.createNotificationChannel(channel);

        return channelId;
    }
}
