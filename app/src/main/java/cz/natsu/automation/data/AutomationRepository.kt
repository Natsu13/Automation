package cz.natsu.automation.data

import android.content.ComponentName
import android.content.Context
import android.service.quicksettings.TileService
import cz.natsu.automation.trigger.AutomationTileService
import cz.natsu.automation.trigger.ShortcutPublisher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/** Keys of the icons an automation can use; mapped to vectors in the UI layer. */
object AutomationIcons {
    const val DEFAULT = "bolt"
}

enum class TriggerType { ProcessText, Share, Tile, Clipboard }

class AutomationRepository(
    private val context: Context,
    private val dao: AutomationDao,
) {
    /** Scope for writes that must finish even when the screen that started them is gone. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun observeAll(): Flow<List<AutomationEntity>> = dao.observeAll()

    suspend fun getAll(): List<AutomationEntity> = dao.getAll()

    suspend fun get(id: Long): AutomationEntity? = dao.getById(id)

    suspend fun withTrigger(type: TriggerType): List<AutomationEntity> = dao.getAll().filter {
        !it.needsReview && when (type) {
            TriggerType.ProcessText -> it.triggerProcessText
            TriggerType.Share -> it.triggerShare
            // The general tile offers only those without a tile of their own.
            TriggerType.Tile -> it.triggerTile && it.tileSlot == 0
            TriggerType.Clipboard -> it.triggerClipboard
        }
    }

    /** The automation that took the numbered tile [slot], if any. */
    suspend fun forTileSlot(slot: Int): AutomationEntity? =
        dao.getAll().firstOrNull { it.triggerTile && it.tileSlot == slot && !it.needsReview }

    /** Automations that react to [type], each with its matching event settings. */
    suspend fun withEvent(type: EventType): List<Pair<AutomationEntity, List<EventTrigger>>> =
        dao.getAll().mapNotNull { automation ->
            val events = automation.activeEvents.filter { it.type == type.key }
            if (events.isEmpty()) null else automation to events
        }

    /** Event types used by at least one automation. */
    suspend fun usedEventTypes(): Set<EventType> =
        dao.getAll().flatMap { it.activeEvents }.mapNotNull { EventType.of(it.type) }.toSet()

    suspend fun create(name: String, blocks: List<Block> = emptyList()): Long {
        val id = dao.insert(AutomationEntity(name = name, blocksJson = BlockJson.encode(blocks)))
        onChanged()
        return id
    }

    suspend fun save(entity: AutomationEntity) {
        // A numbered tile runs one automation: taking a slot frees it from whoever had it.
        if (entity.triggerTile && entity.tileSlot > 0) {
            for (other in dao.getAll()) {
                if (other.id != entity.id && other.tileSlot == entity.tileSlot) dao.update(other.copy(tileSlot = 0))
            }
        }
        dao.update(entity.copy(updatedAt = System.currentTimeMillis()))
        onChanged()
        // Widgets on the home screen show the changed automation right away.
        cz.natsu.automation.trigger.HomeWidgets.refreshAutomation(context, entity.id)
    }

    suspend fun setPinned(id: Long, pinned: Boolean) {
        dao.setPinned(id, pinned)
    }

    suspend fun delete(id: Long) {
        dao.delete(id)
        LastRun.forget(context, id)
        ValueStore.removeAutomation(context, id)
        onChanged()
        cz.natsu.automation.trigger.HomeWidgets.refreshAutomation(context, id)
    }

    suspend fun duplicate(id: Long): Long? {
        val original = dao.getById(id) ?: return null
        // A copy of an unreviewed import waits for review too (needsReview is copied).
        val copy = original.copy(
            id = 0, name = original.name + " (kopie)", updatedAt = System.currentTimeMillis(), tileSlot = 0, pinned = false
        )
        val newId = dao.insert(copy)
        onChanged()
        return newId
    }

    /**
     * Stores imported automations as new ones; returns how many were added.
     * They wait for the user's review ([AutomationEntity.needsReview]) before they can run.
     */
    suspend fun importAll(automations: List<ExportedAutomation>): Int {
        for (imported in automations) {
            dao.insert(
                AutomationEntity(
                    name = imported.name.ifBlank { "Importovaná automatizace" },
                    // Any icon key is fine; one this version does not know is drawn as the default icon.
                    icon = imported.icon.takeIf { Regex("[a-z0-9_]{1,40}").matches(it) } ?: AutomationIcons.DEFAULT,
                    blocksJson = BlockJson.encode(imported.blocks),
                    triggerProcessText = imported.triggerProcessText,
                    triggerShare = imported.triggerShare,
                    triggerTile = imported.triggerTile,
                    triggerClipboard = imported.triggerClipboard,
                    triggerWidget = imported.triggerWidget,
                    widgetDebug = imported.widgetDebug,
                    conditionsJson = BlockJson.encode(imported.conditions),
                    conditionsAny = imported.conditionsAny,
                    eventsJson = EventJson.encode(imported.events),
                    needsReview = true,
                )
            )
        }
        onChanged()
        return automations.size
    }

    /** Keeps sharing shortcuts and the quick settings tile in sync with the stored automations. */
    private suspend fun onChanged() {
        val all = dao.getAll()
        // Start / stop the background service, re-plan time events.
        cz.natsu.automation.trigger.BackgroundService.sync(context)
        cz.natsu.automation.trigger.EventAlarms.scheduleAll(context, all)
        cz.natsu.automation.trigger.NotificationEvents.sync(context, all)
        cz.natsu.automation.trigger.MoreEvents.sync(context, all)
        ShortcutPublisher.publish(context, all)
        cz.natsu.automation.trigger.TileSlots.sync(context, all)
        try {
            TileService.requestListeningState(
                context,
                ComponentName(context, AutomationTileService::class.java)
            )
        } catch (_: Exception) {
            // The tile may not be added yet; nothing to refresh then.
        }
    }

    fun launchSave(entity: AutomationEntity) {
        appScope.launch { save(entity) }
    }
}
