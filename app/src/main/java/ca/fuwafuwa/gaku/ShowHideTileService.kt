package ca.fuwafuwa.gaku

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.annotation.RequiresApi

/**
 * Quick Settings tile: shows or hides Gaku while it's running, and starts it (via MainActivity, which
 * asks for screen capture consent) when it isn't.
 */
@RequiresApi(Build.VERSION_CODES.N)
class ShowHideTileService : TileService()
{
    override fun onStartListening()
    {
        super.onStartListening()
        sListening = this
        updateTile()
    }

    override fun onStopListening()
    {
        if (sListening === this) sListening = null
        super.onStopListening()
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    override fun onClick()
    {
        super.onClick()

        if (MainService.IsRunning())
        {
            sendBroadcast(Intent(this, MainService.ToggleShowHideMainService::class.java))
            return
        }

        val intent = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
        {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE))
        }
        else
        {
            startActivityAndCollapse(intent)
        }
    }

    private fun updateTile()
    {
        val tile = qsTile ?: return
        val running = MainService.IsRunning()
        val shown = running && getPrefs(this).showHideSetting

        tile.state = if (shown) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
        {
            tile.subtitle = when
            {
                !running -> "Off"
                shown -> "Shown"
                else -> "Hidden"
            }
        }
        tile.updateTile()
    }

    companion object
    {
        // The tile while Quick Settings is open. It runs in Gaku's process, on the main thread like
        // MainService.
        private var sListening: ShowHideTileService? = null

        /**
         * Refreshes the tile after Gaku started, stopped, or was shown or hidden. Call on the main thread.
         */
        @JvmStatic
        fun requestUpdate(context: Context)
        {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return

            // Asking for a listening state does nothing while the tile is already listening
            sListening?.updateTile()
                ?: requestListeningState(context, ComponentName(context, ShowHideTileService::class.java))
        }
    }
}
