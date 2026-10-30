package ca.fuwafuwa.gaku.Windows.Views;

import android.content.Context;
import androidx.core.view.GestureDetectorCompat;
import android.util.AttributeSet;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.RelativeLayout;

import ca.fuwafuwa.gaku.R;
import ca.fuwafuwa.gaku.Windows.Interfaces.WindowListener;

/**
 * Created by 0xbad1d3a5 on 4/13/2016.
 */
public class WindowView extends RelativeLayout
{
    private WindowListener mWindowListener;
    private GestureDetectorCompat mDetector;
    // The resize corner (window.xml), and its size there, which is also its largest
    private View mResizeView;
    private int mMaxResizeSize;

    public WindowView(Context context)
    {
        super(context);
    }

    public WindowView(Context context, AttributeSet attrs)
    {
        super(context, attrs);
    }

    public WindowView(Context context, AttributeSet attrs, int defStyleAttr)
    {
        super(context, attrs, defStyleAttr);
    }

    @Override
    protected void onFinishInflate()
    {
        super.onFinishInflate();
        mResizeView = findViewById(R.id.resize_view);
        if (mResizeView != null) mMaxResizeSize = mResizeView.getLayoutParams().width;
    }

    /**
     * Keeps the resize corner to a third of the window's shorter side. At its full 40dp it covered nearly
     * all of a small box (the kind instant mode reads), so dragging one resized it instead of moving it.
     */
    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh)
    {
        super.onSizeChanged(w, h, oldw, oldh);
        if (mResizeView == null) return;

        int size = Math.min(mMaxResizeSize, Math.min(w, h) / 3);
        ViewGroup.LayoutParams params = mResizeView.getLayoutParams();
        if (params.width == size && params.height == size) return;

        params.width = size;
        params.height = size;
        // Not during this layout pass
        post(() -> mResizeView.setLayoutParams(params));
    }

    public void setWindowListener(WindowListener windowListener)
    {
        mWindowListener = windowListener;
    }

    public void setDetector(GestureDetectorCompat detector)
    {
        mDetector = detector;
    }

    @Override
    public boolean onTouchEvent(MotionEvent e)
    {
        if (mDetector.onTouchEvent(e))
        {
            return true;
        }

        return mWindowListener.onTouch(e);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e)
    {
        if (e.getKeyCode() == KeyEvent.KEYCODE_BACK && mWindowListener != null && mWindowListener.onBackKey(e))
        {
            return true;
        }

        return super.dispatchKeyEvent(e);
    }
}




