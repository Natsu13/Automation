package cz.natsu.automation.trigger

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import cz.natsu.automation.AutomationApplication
import cz.natsu.automation.data.TriggerType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Quick settings tile that runs the automations marked for the tile. */
class AutomationTileService : TileService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onStartListening() {
        super.onStartListening()
        scope.launch {
            val automations = withContext(Dispatchers.IO) {
                (application as AutomationApplication).repository.withTrigger(TriggerType.Tile)
            }
            val tile = qsTile ?: return@launch
            tile.state = if (automations.isEmpty()) Tile.STATE_INACTIVE else Tile.STATE_ACTIVE
            tile.label = automations.singleOrNull()?.name ?: "Automatizace"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                tile.subtitle = when (automations.size) {
                    0 -> "Nic nenastaveno"
                    1 -> "Spustit"
                    else -> "Vybrat z ${automations.size}"
                }
            }
            tile.updateTile()
        }
    }

    override fun onClick() {
        super.onClick()
        if (isLocked) {
            unlockAndRun { launchTrigger() }
        } else {
            launchTrigger()
        }
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun launchTrigger() {
        val intent = TriggerActivity.tileIntent(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pending = PendingIntent.getActivity(
                this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            startActivityAndCollapse(pending)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
