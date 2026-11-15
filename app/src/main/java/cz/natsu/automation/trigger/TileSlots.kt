package cz.natsu.automation.trigger

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import cz.natsu.automation.AutomationApplication
import cz.natsu.automation.data.AutomationEntity
import cz.natsu.automation.i18n.tr
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Quick settings tiles that each run one automation directly.
 *
 * Android has no "tile per automation": every tile is a service declared in the
 * manifest. So there is a fixed number of numbered slots, and an automation picks
 * one in its settings. A slot nobody uses is switched off, so it is not offered
 * among the tiles at all.
 */
object TileSlots {
    val classes: List<Class<out SlotTileService>> = listOf(
        TileSlot1::class.java, TileSlot2::class.java, TileSlot3::class.java, TileSlot4::class.java, TileSlot5::class.java,
    )
    val count: Int get() = classes.size

    fun component(context: Context, slot: Int) = ComponentName(context, classes[slot - 1])

    /** Switches every slot on or off to match [all] automations and refreshes the tiles. */
    fun sync(context: Context, all: List<AutomationEntity>) {
        val used = all.filter { it.triggerTile && !it.needsReview }.map { it.tileSlot }.toSet()
        for (slot in 1..count) {
            val component = component(context, slot)
            try {
                context.packageManager.setComponentEnabledSetting(
                    component,
                    if (slot in used) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                    else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP,
                )
                if (slot in used) TileService.requestListeningState(context, component)
            } catch (_: Exception) {
                // The tile is not added, or the system refuses right now; it refreshes when opened.
            }
        }
    }

    /** Opens the run window from a tile; [automationId] = run that one, null = offer the general ones. */
    @SuppressLint("StartActivityAndCollapseDeprecated")
    fun launch(service: TileService, automationId: Long?) {
        val intent = TriggerActivity.tileIntent(service, automationId)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pending = PendingIntent.getActivity(
                service, (automationId ?: 0L).toInt(), intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            service.startActivityAndCollapse(pending)
        } else {
            @Suppress("DEPRECATION")
            service.startActivityAndCollapse(intent)
        }
    }
}

/** One numbered tile: shows the name of the automation that took the slot and runs it on a tap. */
abstract class SlotTileService(private val slot: Int) : TileService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private suspend fun automation(): AutomationEntity? = withContext(Dispatchers.IO) {
        (application as AutomationApplication).repository.forTileSlot(slot)
    }

    override fun onStartListening() {
        super.onStartListening()
        scope.launch {
            val automation = automation()
            val tile = qsTile ?: return@launch
            tile.state = if (automation == null) Tile.STATE_UNAVAILABLE else Tile.STATE_ACTIVE
            tile.label = automation?.name ?: tr("Automatizace %s", slot)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                tile.subtitle = if (automation == null) tr("Nic nenastaveno") else tr("Spustit")
            }
            tile.updateTile()
        }
    }

    override fun onClick() {
        super.onClick()
        scope.launch {
            val id = automation()?.id ?: return@launch
            if (isLocked) unlockAndRun { TileSlots.launch(this@SlotTileService, id) }
            else TileSlots.launch(this@SlotTileService, id)
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}

class TileSlot1 : SlotTileService(1)
class TileSlot2 : SlotTileService(2)
class TileSlot3 : SlotTileService(3)
class TileSlot4 : SlotTileService(4)
class TileSlot5 : SlotTileService(5)
