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
    val ALL = listOf("bolt", "paste", "text", "translate", "link", "volume", "phone", "star", "code", "magic")
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
        when (type) {
            TriggerType.ProcessText -> it.triggerProcessText
            TriggerType.Share -> it.triggerShare
            TriggerType.Tile -> it.triggerTile
            TriggerType.Clipboard -> it.triggerClipboard
        }
    }

    /** Automations that react to [type], each with its matching event settings. */
    suspend fun withEvent(type: EventType): List<Pair<AutomationEntity, List<EventTrigger>>> =
        dao.getAll().mapNotNull { automation ->
            val events = automation.events.filter { it.type == type.key }
            if (events.isEmpty()) null else automation to events
        }

    /** Event types used by at least one automation. */
    suspend fun usedEventTypes(): Set<EventType> =
        dao.getAll().flatMap { it.events }.mapNotNull { EventType.of(it.type) }.toSet()

    suspend fun create(name: String, blocks: List<Block> = emptyList()): Long {
        val id = dao.insert(AutomationEntity(name = name, blocksJson = BlockJson.encode(blocks)))
        onChanged()
        return id
    }

    suspend fun save(entity: AutomationEntity) {
        dao.update(entity.copy(updatedAt = System.currentTimeMillis()))
        onChanged()
    }

    suspend fun delete(id: Long) {
        dao.delete(id)
        ValueStore.removeAutomation(context, id)
        onChanged()
    }

    suspend fun duplicate(id: Long): Long? {
        val original = dao.getById(id) ?: return null
        val copy = original.copy(id = 0, name = original.name + " (kopie)", updatedAt = System.currentTimeMillis())
        val newId = dao.insert(copy)
        onChanged()
        return newId
    }

    /** Stores imported automations as new ones; returns how many were added. */
    suspend fun importAll(automations: List<ExportedAutomation>): Int {
        for (imported in automations) {
            dao.insert(
                AutomationEntity(
                    name = imported.name.ifBlank { "Importovaná automatizace" },
                    icon = imported.icon.takeIf { it in AutomationIcons.ALL } ?: AutomationIcons.DEFAULT,
                    blocksJson = BlockJson.encode(imported.blocks),
                    triggerProcessText = imported.triggerProcessText,
                    triggerShare = imported.triggerShare,
                    triggerTile = imported.triggerTile,
                    triggerClipboard = imported.triggerClipboard,
                    conditionsJson = BlockJson.encode(imported.conditions),
                    conditionsAny = imported.conditionsAny,
                    eventsJson = EventJson.encode(imported.events),
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
        ShortcutPublisher.publish(context, all)
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
