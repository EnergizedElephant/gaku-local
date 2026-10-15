package ca.fuwafuwa.gaku.Windows

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.media.Image
import android.os.Build
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.ViewTreeObserver
import android.view.WindowManager
import android.view.animation.Animation
import android.view.animation.AnimationUtils
import android.widget.ImageView
import android.widget.Toast
import androidx.core.content.ContextCompat
import ca.fuwafuwa.gaku.*
import ca.fuwafuwa.gaku.Ocr.BoxParams
import ca.fuwafuwa.gaku.Ocr.OcrParams
import ca.fuwafuwa.gaku.Ocr.OcrRunnable
import ca.fuwafuwa.gaku.Prefs
import ca.fuwafuwa.gaku.R
import ca.fuwafuwa.gaku.Windows.Interfaces.WindowListener
import ca.fuwafuwa.gaku.XmlParsers.CommonParser

/**
 * Created by 0xbad1d3a5 on 4/13/2016.
 */
class CaptureWindow(context: Context, windowCoordinator: WindowCoordinator) : Window(context, windowCoordinator, R.layout.window_capture), WindowListener
{
    private val mOcr: OcrRunnable
    private val mWindowBox: View
    private val mImageView: ImageView
    private val mFadeRepeat: Animation
    private val mBorderDefault: Drawable?
    private val mBorderReady: Drawable?

    private var mPrefs: Prefs?
    private var mLastDoubleTapTime: Long
    private val mLastDoubleTapIgnoreDelay: Long
    private var mInLongPress: Boolean = false
    private var mProcessingPreview: Boolean = false
    private var mProcessingOcr: Boolean = false
    // A double-tap arrived while the first tap's capture was still running
    private var mOcrAfterCapture: Boolean = false
    private var mScreenshotForOcr: ScreenshotForOcr? = null
    // Why the last capture failed, shown when OCR is asked for
    private var mCaptureProblem: String? = null

    private var mCommonParser: CommonParser? = null

    // Helper class to hold data
    private inner class ScreenshotForOcr(val crop: Bitmap?, val orig: Bitmap?, val params: BoxParams?)

    init
    {
        show()

        this.mCommonParser = CommonParser(context)

        mImageView = window.findViewById(R.id.capture_image)
        mFadeRepeat = AnimationUtils.loadAnimation(this.context, R.anim.fade_repeat)
        
        // FIX: Use ContextCompat for modern Android compatibility
        mBorderDefault = ContextCompat.getDrawable(this.context, R.drawable.bg_translucent_border_0_blue_blue)
        mBorderReady = ContextCompat.getDrawable(this.context, R.drawable.bg_transparent_border_0_nil_ready)

        mLastDoubleTapTime = System.currentTimeMillis()
        mLastDoubleTapIgnoreDelay = 500
        mInLongPress = false
        mProcessingPreview = false
        mProcessingOcr = false
        mScreenshotForOcr = null

        mPrefs = getPrefs(context)

        mOcr = OcrRunnable(this.context, this)
        val ocrThread = Thread(mOcr)
        ocrThread.name = String.format("OcrThread%d", System.nanoTime())
        ocrThread.isDaemon = true
        ocrThread.start()
        
        // FIX: Initialize MLKit immediately so it's ready when the user captures
        mOcr.warmUp()

        // Need to wait for the view to finish updating before we try to determine its location
        mWindowBox = window.findViewById(R.id.capture_box)
        mWindowBox.viewTreeObserver.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener
        {
            override fun onGlobalLayout()
            {
                (context as MainService).onCaptureWindowFinishedInitializing()
                mWindowBox.viewTreeObserver.removeOnGlobalLayoutListener(this)
            }
        })
    }

    override fun reInit(options: Window.ReinitOptions)
    {
        mPrefs = getPrefs(context)
        super.reInit(options)
    }

    override fun onDoubleTap(e: MotionEvent): Boolean
    {
        mLastDoubleTapTime = System.currentTimeMillis()
        if (mProcessingPreview)
        {
            mOcrAfterCapture = true
        }
        else
        {
            performOcr(false)
        }
        return true
    }

    override fun onTouch(e: MotionEvent): Boolean
    {
        hideInstantWindows()

        if (!mInLongPress && !mProcessingOcr)
        {
            mImageView.setImageResource(0)
            setBorderStyle(e)
        }

        when (e.action)
        {
            MotionEvent.ACTION_MOVE ->
            {
                if (System.currentTimeMillis() > mLastDoubleTapTime + mLastDoubleTapIgnoreDelay)
                {
                    mOcr.cancel()
                }
            }
        }

        return super.onTouch(e)
    }

    override fun onLongPress(e: MotionEvent)
    {
        Log.d(TAG, "onLongPress")
        mInLongPress = true
    }

    override fun onResize(e: MotionEvent): Boolean
    {
        hideInstantWindows()

        mOcr.cancel()
        mImageView.setImageResource(0)
        setBorderStyle(e)
        return super.onResize(e)
    }

    override fun onUp(e: MotionEvent): Boolean
    {
        Log.d(TAG, String.format("onUp - mInLongPress: %b | mProcessingPreview: %b | mProcessingOcr: %b", mInLongPress, mProcessingPreview, mProcessingOcr))

        if (!mInLongPress && !mProcessingPreview && !mProcessingOcr)
        {
            Log.d(TAG, "onUp - SetPreviewImage")
            setBorderStyle(e)
            mProcessingPreview = true
            setCroppedScreenshot()
        }

        mInLongPress = false

        return true
    }

    override fun stop()
    {
        mOcr.stop()
        super.stop()
    }

    fun showLoadingAnimation()
    {
        (context as MainService).handler.post {
            Log.d(TAG, "showLoadingAnimation")
            mWindowBox.background = mBorderDefault
            mImageView.imageAlpha = 0
            mWindowBox.animation = mFadeRepeat
            mWindowBox.startAnimation(mFadeRepeat)
        }
    }

    fun stopLoadingAnimation(instant: Boolean)
    {
        (context as MainService).handler.post {
            mProcessingOcr = false
            mWindowBox.background = mBorderReady
            mWindowBox.clearAnimation()
            Log.d(TAG, "stopLoadingAnimation - instant: $instant")
            if (instant)
            {
                mImageView.imageAlpha = 255
            } else
            {
                mImageView.imageAlpha = 255
                mImageView.setImageResource(0)
                mScreenshotForOcr = null
            }
        }
    }

    fun hideInstantWindows()
    {
        windowCoordinator.getWindow(WINDOW_INSTANT_KANJI).hide()
    }

    override fun getDefaultParams(): WindowManager.LayoutParams
    {
        val params = super.getDefaultParams()
        params.x = realDisplaySize.x / 2 - params.width / 2
        params.y = realDisplaySize.y / 4 - params.height / 2
        params.alpha = 0.8F
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
        {
            // Lay out against the whole display so params.x/y are screen coordinates (see getBoxOnScreen)
            params.fitInsetsTypes = 0
            params.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
        return params
    }

    /**
     * The capture box in screen (= full-screen capture) coordinates. getLocationOnScreen() can't be
     * trusted for a moved overlay: on Android 15 it kept reporting a stale x after the box was
     * dragged, so on R+ (where the window ignores insets) use the LayoutParams position instead.
     */
    private fun getBoxOnScreen(): BoxParams
    {
        val pos = IntArray(2)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
        {
            mWindowBox.getLocationInWindow(pos)
            pos[0] += params.x
            pos[1] += params.y
        }
        else
        {
            mWindowBox.getLocationOnScreen(pos)
        }
        return BoxParams(pos[0], pos[1], params.width, params.height)
    }

    /**
     * Captures what's under the box into mScreenshotForOcr, then runs OCR if it was asked for meanwhile
     * (or instant mode applies). Only frames drawn after the box's current state are used, so a
     * full-screen capture never contains the box's touch tint or an old position.
     */
    private fun setCroppedScreenshot()
    {
        // Views and the service are only safe to use on the UI thread
        val box = getBoxOnScreen()
        val service = context as MainService

        afterBoxIsDrawn { sinceNanos ->
            val problem = service.captureProblem
            Thread {
                val ocrScreenshot = if (problem == null) getOcrData(service, box, sinceNanos) else null

                service.handler.post {
                    // The window was stopped (e.g. hidden) while capturing
                    if (context == null) return@post

                    mScreenshotForOcr = ocrScreenshot
                    mCaptureProblem = problem
                    mProcessingPreview = false

                    if (ocrScreenshot != null)
                    {
                        mImageView.setImageBitmap(ocrScreenshot.crop)
                    }

                    if (mOcrAfterCapture)
                    {
                        mOcrAfterCapture = false
                        performOcr(false)
                    }
                    else if (ocrScreenshot != null && mPrefs!!.instantModeSetting && System.currentTimeMillis() > mLastDoubleTapTime + mLastDoubleTapIgnoreDelay)
                    {
                        val sizeForInstant = minSize * 3
                        if (sizeForInstant >= box.width || sizeForInstant >= box.height)
                        {
                            performOcr(true)
                        }
                    }
                }
            }.start()
        }
    }

    /**
     * Runs [action] on the UI thread once the box's current state has been submitted for display, with
     * the time that happened. Before Android 10, or if no frame gets drawn, it runs with the current time.
     */
    private fun afterBoxIsDrawn(action: (Long) -> Unit)
    {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q)
        {
            action(System.nanoTime())
            return
        }

        var done = false
        val runOnce = Runnable {
            if (!done)
            {
                done = true
                action(System.nanoTime())
            }
        }
        mWindowBox.viewTreeObserver.registerFrameCommitCallback(runOnce)
        mWindowBox.invalidate()
        (context as MainService).handler.postDelayed(runOnce, 200)
    }

    private fun setBorderStyle(e: MotionEvent)
    {
        when (e.action)
        {
            MotionEvent.ACTION_DOWN -> mWindowBox.background = mBorderDefault
            MotionEvent.ACTION_UP -> mWindowBox.background = mBorderReady
        }
    }

    private fun performOcr(instant: Boolean)
    {
        mProcessingOcr = true

        try
        {
            if (!instant)
            {
                while (!mOcr.isReadyForOcr)
                {
                    mOcr.cancel()
                    Thread.sleep(10)
                }
            }

            if (mScreenshotForOcr == null)
            {
                Toast.makeText(context, mCaptureProblem ?: "Couldn't capture the screen, try again", Toast.LENGTH_LONG).show()
                mProcessingOcr = false
                return
            }

            // Note: Changed name to runTess to runTess (user can rename method in OcrRunnable to match if desired, 
            // but for now relying on previous OcrRunnable code which had runTess)
            mOcr.runTess(OcrParams(mScreenshotForOcr!!.crop!!, mScreenshotForOcr!!.crop!!, mScreenshotForOcr!!.params!!, mScreenshotForOcr!!.params!!.x, mScreenshotForOcr!!.params!!.y, instant))
        } catch (e: Exception)
        {
            e.printStackTrace()
        }
    }

    /**
     * Runs on a background thread: crops the box out of a frame drawn at or after [sinceNanos].
     */
    private fun getOcrData(service: MainService, box: BoxParams, sinceNanos: Long): ScreenshotForOcr?
    {
        Log.d(TAG, String.format("X:%d Y:%d (%dx%d)", box.x, box.y, box.width, box.height))

        try
        {
            val screenshot = service.getScreenshotSince(sinceNanos) ?: return null

            // The box can briefly be outside a frame captured before a rotation
            if (box.x + box.width > screenshot.width || box.y + box.height > screenshot.height)
            {
                return null
            }

            return ScreenshotForOcr(getCroppedBitmap(screenshot, box), screenshot, box)
        } catch (e: Exception)
        {
            Log.e(TAG, "Capture failed", e)
        }
        return null
    }


    private fun getCroppedBitmap(screenshot: Bitmap, box: BoxParams): Bitmap
    {
        val borderSize = dpToPx(context, 1) + 1
        
        // Safety check for bounds
        val width = if (box.width - 2 * borderSize > 0) box.width - 2 * borderSize else 1
        val height = if (box.height - 2 * borderSize > 0) box.height - 2 * borderSize else 1
        
        return Bitmap.createBitmap(screenshot,
                box.x + borderSize,
                box.y + borderSize,
                width,
                height)
    }

    companion object
    {
        private val TAG = CaptureWindow::class.java.name
    }
}



