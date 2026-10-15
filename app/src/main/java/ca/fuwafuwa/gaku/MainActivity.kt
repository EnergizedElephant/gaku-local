package ca.fuwafuwa.gaku

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import ca.fuwafuwa.gaku.Dialogs.StarRatingDialogFragment


class MainActivity : AppCompatActivity()
{
    private var mShownRating = false
    private var mNotificationPermissionAsked = false
    private var mScreenCaptureRequested = false

    private lateinit var mPrefs : SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?)
    {
        super.onCreate(savedInstanceState)

        mPrefs = getSharedPreferences(GAKU_PREF_FILE, Context.MODE_PRIVATE)

        if (isFirstLaunch())
        {
            startActivity(Intent(this, TutorialActivity::class.java))
            finish()
        }
        else {
            supportActionBar?.hide()
            setContentView(R.layout.activity_main)

            setupGakuDatabasesAndFiles(this)
        }
    }

    override fun onStart()
    {
        super.onStart()

        // One system dialog at a time: overlay permission, then notifications, then screen capture
        if (!checkDrawOnTopPermissions()) return
        if (requestNotificationPermission()) return
        requestScreenCaptureConsent()

        showRatingDialog()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray)
    {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == REQUEST_NOTIFICATIONS)
        {
            if (grantResults.firstOrNull() != PackageManager.PERMISSION_GRANTED)
            {
                Toast.makeText(this, "Without notifications, Gaku's Show/Hide and Shutdown controls aren't available", Toast.LENGTH_LONG).show()
            }
            requestScreenCaptureConsent()
        }
    }

    override fun onPause()
    {
        super.onPause()
        Log.d(TAG, "ACTIVITY INVISIBLE")
    }

    override fun onResume()
    {
        super.onResume()
        Log.d(TAG, "ACTIVITY VISIBLE")
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?)
    {
        Log.d(TAG, "onActivityResult")

        val relaunchAppText = "Relaunch Gaku after verifying permission"

        if (requestCode == REQUEST_DRAW_ON_TOP)
        {
            Log.d(TAG, "Recieved ACTION_MANAGE_OVERLAY_PERMISSION Intent")

            if (resultCode != Activity.RESULT_OK)
            {
                Toast.makeText(this, "Check Permission: Draw on Other Apps\n$relaunchAppText", Toast.LENGTH_LONG).show()
                finish()
            }

            return
        }

        if (requestCode == REQUEST_SCREENSHOT)
        {
            Log.d(TAG, "Recieved REQUEST_SCREENSHOT Intent")
            mScreenCaptureRequested = false

            if (resultCode != Activity.RESULT_OK)
            {
                Toast.makeText(this, "Check Permission: Record Screen\n$relaunchAppText", Toast.LENGTH_LONG).show()
                finish()
                return
            }

            // Start the service now, while Gaku is in the foreground. When the user shares a single app,
            // Android switches to that app as soon as this returns, and a foreground service can't be
            // started from the background. Each consent can only be used once.
            startGakuService(this, Intent(this, MainService::class.java)
                    .putExtra(EXTRA_PROJECTION_RESULT_CODE, resultCode)
                    .putExtra(EXTRA_PROJECTION_RESULT_INTENT, data))
            return
        }
    }

    /**
     * Called by the start screen a few seconds after it resumes, to replace its loading text once the
     * service is running.
     */
    fun refreshLoadingState(startFragment: MainStartFragment)
    {
        if (MainService.IsRunning())
        {
            startFragment.onGakuLoaded()
        }
    }

    /**
     * @return true if Gaku can draw over other apps; otherwise the settings screen has been opened
     */
    private fun checkDrawOnTopPermissions(): Boolean
    {
        var checkPermissions = "Check \"Draw on Top of Other Apps\" permission"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
        {
            if (!Settings.canDrawOverlays(this))
            {
                Log.d(TAG, "Sending ACTION_MANAGE_OVERLAY_PERMISSION Intent")
                startActivityForResult(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")), REQUEST_DRAW_ON_TOP)
                return false
            }
        }
        else
        {
            Toast.makeText(this, "Manually $checkPermissions\nGaku might not work on this device", Toast.LENGTH_LONG).show()
        }
        return true
    }

    /**
     * Android 13+ hides the service's notification, which holds the Show/Hide and Shutdown controls,
     * unless this is granted. Gaku still works without it.
     * @return true if the request is in flight; onRequestPermissionsResult carries on from there
     */
    private fun requestNotificationPermission(): Boolean
    {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || mNotificationPermissionAsked ||
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)
        {
            return false
        }

        mNotificationPermissionAsked = true
        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
        return true
    }

    private fun requestScreenCaptureConsent()
    {
        // The running service already holds a projection, and onStart also runs when coming back from
        // another activity (e.g. the single-app picker), so only ask when no request is pending
        if (MainService.IsRunning() || mScreenCaptureRequested)
        {
            return
        }

        Log.d(TAG, "Sending REQUEST_SCREENSHOT Intent")
        mScreenCaptureRequested = true
        val mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(mediaProjectionManager.createScreenCaptureIntent(), REQUEST_SCREENSHOT)
    }

    private fun showRatingDialog()
    {
        if (mShownRating)
        {
            return
        }

        mShownRating = true

        val timesLaunched = mPrefs.getInt(GAKU_PREF_TIMES_LAUNCHED, 1)
        val rated = mPrefs.getBoolean(GAKU_PREF_PLAY_STORE_RATED, false)

        if (timesLaunched % 20 == 0 && !rated)
        {
            StarRatingDialogFragment().show(supportFragmentManager, "StarRating")
        }
    }

    private fun isFirstLaunch() : Boolean
    {
        return mPrefs.getBoolean(GAKU_PREF_FIRST_LAUNCH, true)
    }

    companion object
    {
        private val TAG = MainActivity::class.java.name
    }
}




