package ca.fuwafuwa.gaku

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
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
import androidx.appcompat.app.AppCompatActivity


class MainActivity : AppCompatActivity()
{
    // Per activity instance: each system screen opens once of its own accord. After a refusal the start
    // screen offers a button instead, so nothing loops back into the same dialog
    private var mOverlayRequested = false
    private var mScreenCaptureRequested = false
    private var mConsentDeclined = false
    // When the service was last asked to start, so the start screen can say "starting" meanwhile
    private var mServiceStartedAt = 0L

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
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray)
    {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == REQUEST_NOTIFICATIONS)
        {
            // Granted or not, carry on: the start screen says what's missing without them
            requestScreenCaptureConsent()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?)
    {
        super.onActivityResult(requestCode, resultCode, data)

        when (requestCode)
        {
            REQUEST_DRAW_ON_TOP ->
            {
                // The settings screen always returns RESULT_CANCELED; onStart() checks the permission itself
                Log.d(TAG, "Back from the overlay permission screen")
            }
            REQUEST_SCREENSHOT ->
            {
                mScreenCaptureRequested = false

                if (resultCode != Activity.RESULT_OK || data == null)
                {
                    // Declined: the start screen shows Gaku as off, with a button to ask again
                    mConsentDeclined = true
                    return
                }

                // Start the service now, while Gaku is in the foreground. When the user shares a single app,
                // Android switches to that app as soon as this returns, and a foreground service can't be
                // started from the background. Each consent can only be used once.
                mServiceStartedAt = System.currentTimeMillis()
                startGakuService(this, Intent(this, MainService::class.java)
                        .putExtra(EXTRA_PROJECTION_RESULT_CODE, resultCode)
                        .putExtra(EXTRA_PROJECTION_RESULT_INTENT, data))
            }
        }
    }

    /** Whether Gaku may draw over other apps. Without it, the start screen offers [openOverlaySettings]. */
    fun canDrawOverlays(): Boolean
    {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)
    }

    /** Whether the service's notification, which holds Gaku's controls, can be shown. */
    fun canShowNotifications(): Boolean
    {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    }

    /** Whether Gaku has asked for notifications yet; until then, their being off isn't worth a hint. */
    fun notificationsAsked(): Boolean
    {
        return mPrefs.getBoolean(GAKU_PREF_NOTIFICATIONS_ASKED, false)
    }

    /**
     * Whether Gaku is about to run: the consent dialog is up, or the service was just asked to start.
     * The time limit covers a service that failed to start, which this activity isn't told about.
     */
    fun isStarting(): Boolean
    {
        return mScreenCaptureRequested || System.currentTimeMillis() - mServiceStartedAt < 5000
    }

    /** The start screen's Start button: asks for screen capture (again). */
    fun startGaku()
    {
        mConsentDeclined = false
        requestScreenCaptureConsent()
    }

    fun openOverlaySettings()
    {
        Log.d(TAG, "Sending ACTION_MANAGE_OVERLAY_PERMISSION Intent")
        startActivityForResult(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")), REQUEST_DRAW_ON_TOP)
    }

    /**
     * @return true if Gaku can draw over other apps. Otherwise, the first time, explains and offers the
     * settings screen; after that the start screen's button does.
     */
    private fun checkDrawOnTopPermissions(): Boolean
    {
        if (canDrawOverlays()) return true

        if (!mOverlayRequested)
        {
            mOverlayRequested = true
            AlertDialog.Builder(this)
                    .setTitle("Display over other apps")
                    .setMessage("Gaku draws its capture box over other apps. On the next screen, turn on \"Display over other apps\" for Gaku, then come back.")
                    .setPositiveButton("Open settings") { _, _ -> openOverlaySettings() }
                    .setNegativeButton("Not now", null)
                    .show()
        }
        return false
    }

    /**
     * Android 13+ hides the service's notification, which holds the Show/Hide and Shutdown controls,
     * unless this is granted. Gaku still works without it, so it's only ever asked once.
     * @return true if the request is in flight; onRequestPermissionsResult carries on from there
     */
    private fun requestNotificationPermission(): Boolean
    {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || canShowNotifications() ||
                mPrefs.getBoolean(GAKU_PREF_NOTIFICATIONS_ASKED, false))
        {
            return false
        }

        mPrefs.edit().putBoolean(GAKU_PREF_NOTIFICATIONS_ASKED, true).apply()
        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
        return true
    }

    private fun requestScreenCaptureConsent()
    {
        // The running service already holds a projection, and onStart also runs when coming back from
        // another activity (e.g. the single-app picker), so only ask when no request is pending. After
        // a refusal, only the Start button asks again.
        if (MainService.IsRunning() || mScreenCaptureRequested || mConsentDeclined)
        {
            return
        }

        Log.d(TAG, "Sending REQUEST_SCREENSHOT Intent")
        mScreenCaptureRequested = true
        val mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(mediaProjectionManager.createScreenCaptureIntent(), REQUEST_SCREENSHOT)
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
