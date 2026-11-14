package cz.natsu.automation.ui.editor

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import cz.natsu.automation.AutomationApplication
import cz.natsu.automation.data.AutomationEntity
import cz.natsu.automation.data.Block
import cz.natsu.automation.data.BlockJson
import cz.natsu.automation.data.EventType
import cz.natsu.automation.data.EventValue
import cz.natsu.automation.data.ParamValue
import cz.natsu.automation.data.Segment
import cz.natsu.automation.data.VarRef
import cz.natsu.automation.engine.AutomationRunner
import cz.natsu.automation.engine.BlockDef
import cz.natsu.automation.engine.BlockKind
import cz.natsu.automation.engine.Blocks
import cz.natsu.automation.engine.RunResult
import cz.natsu.automation.engine.Structure
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.UUID

/** A value the user can insert into a text field, with the label shown on its chip. */
data class VariableOption(val ref: VarRef, val label: String, val description: String)

class EditorViewModel(application: Application, private val automationId: Long) : AndroidViewModel(application) {
    private val repository = (application as AutomationApplication).repository
    private var entity: AutomationEntity? = null
    private var saveJob: Job? = null

    var loaded by mutableStateOf(false)
        private set
    var missing by mutableStateOf(false)
        private set
    var name by mutableStateOf("")
        private set
    var icon by mutableStateOf("")
        private set
    var triggerProcessText by mutableStateOf(false)
        private set
    var triggerShare by mutableStateOf(false)
        private set
    var triggerTile by mutableStateOf(false)
        private set
    var triggerClipboard by mutableStateOf(false)
        private set

    val blocks = mutableStateListOf<Block>()

    /** System events that start the automation. */
    val events = mutableStateListOf<cz.natsu.automation.data.EventTrigger>()

    /** Launch conditions, checked before the automation is offered / started. */
    val conditions = mutableStateListOf<Block>()
    var conditionsAny by mutableStateOf(false)
        private set

    /** Ids of blocks shown with all their fields; the rest show a one-line summary. */
    val expandedIds = mutableStateMapOf<String, Boolean>()

    /** While an "if" is dragged, the steps of its condition are folded into it. */
    var foldedGroup by mutableStateOf<FoldedGroup?>(null)
        private set

    data class FoldedGroup(val ifId: String, val hidden: List<Block>, val before: List<Block>)

    var structure by mutableStateOf(Structure.analyze(emptyList()))
        private set

    var running by mutableStateOf(false)
        private set
    var activeBlockId by mutableStateOf<String?>(null)
        private set
    var lastResult by mutableStateOf<RunResult?>(null)
        private set

    /** Dialogs (ask / choose / show) of a test run started from the editor. */
    val runUi = cz.natsu.automation.ui.run.DialogRunUi()

    /** All automations, for the "Spustit automatizaci" button of a notification. */
    var automations by mutableStateOf<List<AutomationRef>>(emptyList())
        private set

    /** Some notification has a button that runs this automation. */
    var runFromNotification by mutableStateOf(false)
        private set

    init {
        viewModelScope.launch {
            repository.observeAll().collect { all ->
                automations = all.map { AutomationRef(it.id, it.name, it.id == automationId) }
                runFromNotification = all.any { other ->
                    other.blocks.any { block ->
                        block.type == cz.natsu.automation.engine.NotificationBlock.TYPE && block.params.any { (key, value) ->
                            key.endsWith("_automation") && (value as? ParamValue.Choice)?.value == automationId.toString()
                        }
                    }
                }
            }
        }
        viewModelScope.launch {
            val stored = repository.get(automationId)
            if (stored == null) {
                missing = true
            } else {
                entity = stored
                name = stored.name
                icon = stored.icon
                triggerProcessText = stored.triggerProcessText
                triggerShare = stored.triggerShare
                triggerTile = stored.triggerTile
                triggerClipboard = stored.triggerClipboard
                blocks.addAll(stored.blocks)
                conditions.addAll(stored.conditions)
                conditionsAny = stored.conditionsAny
                events.addAll(stored.events)
                structure = Structure.analyze(blocks)
                committed = capture()
            }
            loaded = true
        }
    }

    // ---------- saving ----------

    private fun currentEntity(): AutomationEntity? = entity?.copy(
        name = name.ifBlank { "Bez názvu" },
        icon = icon,
        // Never store a list with a folded (temporarily removed) condition.
        blocksJson = BlockJson.encode(foldedGroup?.before ?: blocks.toList()),
        triggerProcessText = triggerProcessText,
        triggerShare = triggerShare,
        triggerTile = triggerTile,
        triggerClipboard = triggerClipboard,
        conditionsJson = BlockJson.encode(conditions.toList()),
        conditionsAny = conditionsAny,
        eventsJson = cz.natsu.automation.data.EventJson.encode(events.toList()),
    )

    /**
     * Called after every edit: records it in the history and saves it.
     * Edits with the same [mergeKey] following quickly (typing into one field)
     * become a single history step.
     */
    private fun changed(mergeKey: String? = null) {
        record(mergeKey)
        persist()
    }

    private fun persist() {
        structure = Structure.analyze(blocks)
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            delay(400)
            currentEntity()?.let { repository.save(it) }
        }
    }

    // ---------- history (undo / redo) ----------

    /** Everything the user can change in the editor, as one history step. */
    private data class Snapshot(
        val name: String,
        val icon: String,
        val triggerProcessText: Boolean,
        val triggerShare: Boolean,
        val triggerTile: Boolean,
        val triggerClipboard: Boolean,
        val blocks: List<Block>,
        val conditions: List<Block>,
        val conditionsAny: Boolean,
        val events: List<cz.natsu.automation.data.EventTrigger>,
    )

    private val undoStack = ArrayDeque<Snapshot>()
    private val redoStack = ArrayDeque<Snapshot>()
    /** State after the last recorded edit. */
    private var committed: Snapshot? = null
    private var lastMergeKey: String? = null
    private var lastEditAt = 0L

    var canUndo by mutableStateOf(false)
        private set
    var canRedo by mutableStateOf(false)
        private set

    private fun capture() = Snapshot(
        name, icon, triggerProcessText, triggerShare, triggerTile, triggerClipboard,
        foldedGroup?.before ?: blocks.toList(), conditions.toList(), conditionsAny, events.toList(),
    )

    private fun record(mergeKey: String?) {
        val now = capture()
        val previous = committed
        committed = now
        if (previous == null || previous == now) return
        val time = android.os.SystemClock.uptimeMillis()
        val merge = mergeKey != null && mergeKey == lastMergeKey &&
            time - lastEditAt < MERGE_WINDOW_MS && undoStack.isNotEmpty()
        if (!merge) {
            undoStack.addLast(previous)
            if (undoStack.size > HISTORY_LIMIT) undoStack.removeFirst()
        }
        lastMergeKey = mergeKey
        lastEditAt = time
        redoStack.clear()
        updateHistoryFlags()
    }

    private fun updateHistoryFlags() {
        canUndo = undoStack.isNotEmpty()
        canRedo = redoStack.isNotEmpty()
    }

    fun undo() {
        if (foldedGroup != null) return
        val target = undoStack.removeLastOrNull() ?: return
        redoStack.addLast(capture())
        apply(target)
    }

    fun redo() {
        if (foldedGroup != null) return
        val target = redoStack.removeLastOrNull() ?: return
        undoStack.addLast(capture())
        apply(target)
    }

    private fun apply(snapshot: Snapshot) {
        name = snapshot.name
        icon = snapshot.icon
        triggerProcessText = snapshot.triggerProcessText
        triggerShare = snapshot.triggerShare
        triggerTile = snapshot.triggerTile
        triggerClipboard = snapshot.triggerClipboard
        blocks.clear()
        blocks.addAll(snapshot.blocks)
        conditions.clear()
        conditions.addAll(snapshot.conditions)
        conditionsAny = snapshot.conditionsAny
        events.clear()
        events.addAll(snapshot.events)
        committed = snapshot
        lastMergeKey = null
        updateHistoryFlags()
        persist()
    }

    /** The automation as currently edited, for export. */
    fun exportEntity(): AutomationEntity? = currentEntity()

    /** Imports an export file as new automations (the edited one stays as it is). */
    fun import(uri: android.net.Uri, onDone: (String?) -> Unit) {
        viewModelScope.launch {
            onDone(
                try {
                    val count = cz.natsu.automation.data.AutomationTransfer.import(getApplication(), repository, uri)
                    if (count == 1) "Automatizace importována – najdeš ji v seznamu" else "Importováno automatizací: $count"
                } catch (e: cz.natsu.automation.data.ImportException) {
                    e.message
                } catch (e: Exception) {
                    "Import se nepodařil: ${e.message}"
                }
            )
        }
    }

    fun saveNow() {
        saveJob?.cancel()
        currentEntity()?.let { repository.launchSave(it) }
    }

    override fun onCleared() {
        saveNow()
        super.onCleared()
    }

    // ---------- automation settings ----------

    fun rename(newName: String) {
        name = newName
        changed(mergeKey = "name")
    }

    fun changeIcon(key: String) {
        icon = key
        changed()
    }

    fun setTriggers(
        processText: Boolean = triggerProcessText,
        share: Boolean = triggerShare,
        tile: Boolean = triggerTile,
        clipboard: Boolean = triggerClipboard,
    ) {
        triggerProcessText = processText
        triggerShare = share
        triggerTile = tile
        triggerClipboard = clipboard
        changed()
    }

    // ---------- events ----------

    fun addEvent(type: cz.natsu.automation.data.EventType) {
        events.add(cz.natsu.automation.data.EventTrigger(type.key))
        changed()
    }

    fun removeEvent(id: String) {
        events.removeAll { it.id == id }
        changed()
    }

    fun updateEvent(id: String, key: String, value: String) {
        val index = events.indexOfFirst { it.id == id }
        if (index < 0) return
        events[index] = events[index].let { it.copy(params = it.params + (key to value)) }
        changed(mergeKey = "$id/$key")
    }

    fun hasEvent(type: cz.natsu.automation.data.EventType) = events.any { it.type == type.key }

    // ---------- value suggestions ("Porovnat s") ----------

    /**
     * What the left side of a condition is, as a key: a launch condition's source,
     * or the single variable in the "Hodnota" field ("Typ spuštění", a block output…).
     */
    private fun conditionSubject(block: Block): String? {
        Blocks.conditionSource(block.params)?.let { return it }
        val left = block.params["left"] as? ParamValue.Rich ?: return null
        val ref = left.segments.filter { it !is Segment.Text || it.text.isNotBlank() }
            .singleOrNull()?.let { (it as? Segment.Var)?.ref } ?: return null
        return when (ref) {
            VarRef.Input -> "input"
            VarRef.Trigger -> "trigger"
            is VarRef.Event -> ref.key
            is VarRef.BlockOutput -> {
                val source = blocks.firstOrNull { it.id == ref.blockId } ?: return null
                val info = (source.params["info"] as? ParamValue.Choice)?.value
                when (source.type) {
                    "network" -> when (info) {
                        "wifi_name" -> "wifi_name"
                        "connected", "metered" -> "bool"
                        else -> "network"
                    }
                    "battery" -> if (info == "charging") "bool" else "battery"
                    "orientation" -> "orientation"
                    cz.natsu.automation.engine.MoreBlocks.EVENT_VALUE ->
                        (source.params["value"] as? ParamValue.Choice)?.value ?: "wifi_name"
                    else -> null
                }
            }
            else -> null
        }
    }

    /** Values offered under "Porovnat s" for [block], or null when there is nothing to suggest. */
    fun suggestionsFor(block: Block): ValueSuggestions? {
        val subject = conditionSubject(block) ?: return null
        val context = getApplication<Application>()
        return when (subject) {
            "trigger" -> ValueSuggestions(buildList {
                add(cz.natsu.automation.engine.RunTrigger.Manual.label)
                if (triggerShare) add(cz.natsu.automation.engine.RunTrigger.Share.label)
                if (triggerProcessText) add(cz.natsu.automation.engine.RunTrigger.ProcessText.label)
                if (triggerClipboard) add(cz.natsu.automation.engine.RunTrigger.Clipboard.label)
                if (triggerTile) add(cz.natsu.automation.engine.RunTrigger.Tile.label)
                if (runFromNotification) add(cz.natsu.automation.engine.RunTrigger.NotificationButton.label)
                events.mapNotNullTo(this) { EventType.of(it.type)?.title }
            }.distinct(), hint = "Podle toho, jak se automatizace dá spustit (Spouštění a Události).")
            "network" -> ValueSuggestions(listOf("wifi", "mobile", "ethernet", "none"))
            "charging", "bool" -> ValueSuggestions(listOf("true", "false"))
            "dnd" -> ValueSuggestions(listOf("zapnuto", "vypnuto"))
            "orientation" -> ValueSuggestions(listOf("portrait", "landscape"))
            "battery" -> ValueSuggestions(listOf("20", "50", "80"), hint = "Pro čísla použij „je menší než“ / „je větší než“.")
            "wifi_name" -> ValueSuggestions(
                listOfNotNull(cz.natsu.automation.engine.DeviceState.wifiName(context).takeIf { it.isNotEmpty() }),
                hint = "Nabízí se Wi-Fi, ke které je telefon připojený teď."
            )
            "bt_device" -> ValueSuggestions(pairedBluetoothNames(context), hint = "Spárovaná zařízení.")
            "notif_app" -> ValueSuggestions(emptyList(), app = AppValue.Label)
            "notif_package" -> ValueSuggestions(emptyList(), app = AppValue.Package)
            else -> null
        }
    }

    private fun pairedBluetoothNames(context: Application): List<String> = try {
        val adapter = context.getSystemService(android.bluetooth.BluetoothManager::class.java)?.adapter
        @Suppress("MissingPermission")
        adapter?.bondedDevices?.mapNotNull { it.name }?.sorted().orEmpty()
    } catch (e: SecurityException) {
        emptyList()
    }

    // ---------- blocks ----------

    fun indexOf(blockId: String) = blocks.indexOfFirst { it.id == blockId }

    fun addBlock(def: BlockDef) {
        val block = if (def.kind == BlockKind.IfStart || def.kind == BlockKind.WhileStart) {
            val group = UUID.randomUUID().toString()
            def.newBlock(group).also {
                blocks.add(it)
                val end = if (def.kind == BlockKind.WhileStart) Blocks.END_WHILE else Blocks.END_IF
                blocks.add(Blocks.get(end)!!.newBlock(group))
            }
        } else {
            def.newBlock().also { blocks.add(it) }
        }
        // A freshly added step opens so it can be filled in right away.
        expandedIds[block.id] = true
        changed()
    }

    fun isExpanded(blockId: String) = expandedIds[blockId] == true

    fun toggleExpanded(blockId: String) {
        if (isExpanded(blockId)) expandedIds.remove(blockId) else expandedIds[blockId] = true
    }

    val allCollapsed: Boolean get() = expandedIds.isEmpty()

    fun setAllExpanded(expanded: Boolean) {
        expandedIds.clear()
        if (expanded) blocks.forEach { block ->
            if (Blocks.get(block.type)?.params?.isNotEmpty() == true) expandedIds[block.id] = true
        }
    }

    // ---------- dragging ----------

    /** Index range of an "if" together with its otherwise / inner steps / end. */
    private fun groupRange(index: Int): IntRange {
        val block = blocks[index]
        val kind = Blocks.get(block.type)?.kind
        if (kind != BlockKind.IfStart && kind != BlockKind.WhileStart) return index..index
        val end = blocks.indexOfLast { it.groupId == block.groupId }
        return index..maxOf(index, end)
    }

    /** Called when a drag starts. Dragging an "if" folds the whole condition into it. */
    fun startDrag(blockId: String) {
        val before = snapshot()
        val index = indexOf(blockId)
        if (index < 0) return
        val range = groupRange(index)
        val hidden = if (range.last > index) blocks.subList(index + 1, range.last + 1).toList() else emptyList()
        if (hidden.isNotEmpty()) {
            blocks.removeRange(index + 1, range.last + 1)
        }
        foldedGroup = FoldedGroup(blockId, hidden, before)
        structure = analyzeVisible()
    }

    /**
     * Structure of the list as shown. A folded "if" has no visible end, so for
     * indentation it is treated as an ordinary step.
     */
    private fun analyzeVisible(): Structure {
        val folded = foldedGroup ?: return Structure.analyze(blocks)
        return Structure.analyze(blocks.map { if (it.id == folded.ifId) it.copy(type = "text") else it })
    }

    /** Called when the drag ends; unfolds the condition. Returns false when the move was reverted. */
    fun endDrag(): Boolean {
        val folded = foldedGroup ?: return true
        foldedGroup = null
        val index = indexOf(folded.ifId)
        if (index >= 0 && folded.hidden.isNotEmpty()) {
            blocks.addAll(index + 1, folded.hidden)
        }
        return finishMove(folded.before)
    }

    fun foldedCount(blockId: String): Int =
        foldedGroup?.takeIf { it.ifId == blockId }?.hidden?.size ?: 0

    fun hasElse(ifBlock: Block): Boolean =
        blocks.any { it.groupId == ifBlock.groupId && it.type == Blocks.ELSE }

    fun addElse(ifBlock: Block) {
        if (hasElse(ifBlock)) return
        val endIndex = blocks.indexOfFirst { it.groupId == ifBlock.groupId && it.type == Blocks.END_IF }
        if (endIndex < 0) return
        blocks.add(endIndex, Blocks.get(Blocks.ELSE)!!.newBlock(ifBlock.groupId))
        changed()
    }

    // ---------- launch conditions ----------

    fun isCondition(blockId: String) = conditions.any { it.id == blockId }

    fun addCondition() {
        val block = Blocks.get(Blocks.LAUNCH_CONDITION)!!.newBlock().let {
            it.copy(params = it.params + ("source" to ParamValue.Choice("input")))
        }
        conditions.add(block)
        expandedIds[block.id] = true
        changed()
    }

    fun removeCondition(blockId: String) {
        conditions.removeAll { it.id == blockId }
        expandedIds.remove(blockId)
        changed()
    }

    fun duplicateCondition(blockId: String) {
        val index = conditions.indexOfFirst { it.id == blockId }
        if (index < 0) return
        conditions.add(index + 1, conditions[index].copy(id = UUID.randomUUID().toString()))
        changed()
    }

    fun moveCondition(blockId: String, delta: Int) {
        val index = conditions.indexOfFirst { it.id == blockId }
        val target = index + delta
        if (index < 0 || target !in conditions.indices) return
        conditions.add(target, conditions.removeAt(index))
        changed()
    }

    fun changeConditionsAny(any: Boolean) {
        conditionsAny = any
        changed()
    }

    /** Switches a step (or a launch condition) off or back on. */
    fun toggleDisabled(blockId: String) {
        val conditionIndex = conditions.indexOfFirst { it.id == blockId }
        if (conditionIndex >= 0) {
            conditions[conditionIndex] = conditions[conditionIndex].let { it.copy(disabled = !it.disabled) }
            changed()
            return
        }
        val index = indexOf(blockId)
        if (index < 0) return
        blocks[index] = blocks[index].let { it.copy(disabled = !it.disabled) }
        changed()
    }

    fun updateParam(blockId: String, key: String, value: ParamValue) {
        val conditionIndex = conditions.indexOfFirst { it.id == blockId }
        if (conditionIndex >= 0) {
            val condition = conditions[conditionIndex]
            conditions[conditionIndex] = condition.copy(params = condition.params + (key to value))
            changed(mergeKey = if (value is ParamValue.Rich) "$blockId/$key" else null)
            return
        }
        val index = indexOf(blockId)
        if (index < 0) return
        val block = blocks[index]
        blocks[index] = block.copy(params = block.params + (key to value))
        changed(mergeKey = if (value is ParamValue.Rich) "$blockId/$key" else null)
    }

    /**
     * Removes a block. Removing an "if" removes the whole condition: the steps
     * inside, its "otherwise" and its "end". Removing an "otherwise" removes the
     * steps of that branch too. Returns the list before the removal (for undo)
     * and how many steps went with it, or null when nothing was removed.
     */
    fun remove(blockId: String): Removal? {
        val index = indexOf(blockId)
        if (index < 0) return null
        val block = blocks[index]
        val before = snapshot()
        val range = when (Blocks.get(block.type)?.kind) {
            BlockKind.IfStart -> groupRange(index)
            BlockKind.WhileStart -> groupRange(index)
            BlockKind.Else -> {
                val end = blocks.indexOfFirst { it.groupId == block.groupId && it.type == Blocks.END_IF }
                if (end > index) index until end else index..index
            }
            BlockKind.End -> return null
            else -> index..index
        }
        val removed = blocks.subList(range.first, range.last + 1).toList()
        blocks.removeRange(range.first, range.last + 1)
        removed.forEach { expandedIds.remove(it.id) }
        changed()
        // Steps that were inside (control blocks of the removed condition itself don't count).
        val inner = if (removed.size == 1) 0 else removed.count {
            it.groupId != block.groupId && Blocks.get(it.type)?.kind.let { k -> k != BlockKind.Else && k != BlockKind.End }
        }
        return Removal(before, inner)
    }

    data class Removal(val before: List<Block>, val innerSteps: Int)

    fun restore(before: List<Block>) {
        blocks.clear()
        blocks.addAll(before)
        changed()
    }

    fun duplicate(blockId: String) {
        val index = indexOf(blockId)
        if (index < 0) return
        val block = blocks[index]
        var lastIndex = index
        val kind = Blocks.get(block.type)?.kind
        if (kind == BlockKind.IfStart || kind == BlockKind.WhileStart) {
            lastIndex = blocks.indexOfLast { it.groupId == block.groupId }.coerceAtLeast(index)
        }

        val range = blocks.subList(index, lastIndex + 1).toList()
        val newIds = range.associate { it.id to UUID.randomUUID().toString() }
        val newGroups = range.mapNotNull { it.groupId }.distinct().associateWith { UUID.randomUUID().toString() }
        val copies = range.map { original ->
            original.copy(
                id = newIds.getValue(original.id),
                groupId = original.groupId?.let { newGroups[it] },
                params = original.params.mapValues { (_, value) -> remapRefs(value, newIds) },
            )
        }
        blocks.addAll(lastIndex + 1, copies)
        changed()
    }

    private fun remapRefs(value: ParamValue, ids: Map<String, String>): ParamValue {
        if (value !is ParamValue.Rich) return value
        return ParamValue.Rich(value.segments.map { segment ->
            val ref = (segment as? Segment.Var)?.ref
            if (ref is VarRef.BlockOutput && ref.blockId in ids) {
                Segment.Var(VarRef.BlockOutput(ids.getValue(ref.blockId)))
            } else {
                segment
            }
        })
    }

    /** Moves a single block (used while dragging). Structure is validated in [finishMove]. */
    fun move(fromId: String, toId: String) {
        val from = indexOf(fromId)
        val to = indexOf(toId)
        if (from < 0 || to < 0 || from == to) return
        blocks.add(to, blocks.removeAt(from))
        structure = analyzeVisible()
    }

    fun snapshot(): List<Block> = blocks.toList()

    /** Returns false (and restores [before]) when the move broke if / otherwise / end nesting. */
    fun finishMove(before: List<Block>): Boolean {
        if (Structure.analyze(blocks).valid || !Structure.analyze(before).valid) {
            changed()
            return true
        }
        blocks.clear()
        blocks.addAll(before)
        changed()
        return false
    }

    /** Moves a block (an "if" together with its whole condition) one step up or down. */
    fun moveBy(blockId: String, delta: Int): Boolean {
        val before = snapshot()
        val index = indexOf(blockId)
        if (index < 0) return true
        val range = groupRange(index)
        val group = blocks.subList(range.first, range.last + 1).toList()
        if (delta < 0) {
            if (range.first == 0) return true
            val neighbour = blocks.removeAt(range.first - 1)
            blocks.add(range.last, neighbour)
        } else {
            if (range.last >= blocks.size - 1) return true
            val neighbour = blocks.removeAt(range.last + 1)
            blocks.add(range.first, neighbour)
        }
        check(group.isNotEmpty())
        return finishMove(before)
    }

    // ---------- variables ----------

    fun variablesFor(blockId: String): List<VariableOption> {
        val options = mutableListOf(
            VariableOption(VarRef.Input, "Vstup", "Text, se kterým byla akce spuštěna (označený, sdílený nebo zkopírovaný)"),
            VariableOption(VarRef.Trigger, "Typ spuštění", TRIGGER_DESCRIPTION),
            VariableOption(VarRef.Clipboard, "Schránka", "Aktuální obsah schránky"),
            VariableOption(VarRef.CurrentDate, "Aktuální datum", "Datum a čas spuštění"),
        )
        // Launch conditions run before any step; event values are picked in their
        // "Co kontrolovat" choice. In steps they come from blocks.
        if (isCondition(blockId)) return options
        val index = indexOf(blockId).let { if (it < 0) blocks.size else it }
        val names = linkedSetOf<String>()
        for (i in 0 until index) {
            val block = blocks[i]
            val def = Blocks.get(block.type) ?: continue
            if (def.outputName != null) {
                options += VariableOption(
                    VarRef.BlockOutput(block.id),
                    outputLabel(block),
                    "Výstup kroku ${i + 1}: ${def.title}"
                )
            }
            if (block.type == Blocks.SET_VARIABLE) {
                plainText(block.params["name"])?.takeIf { it.isNotBlank() }?.let { names += it.trim() }
            }
        }
        for (variable in names) {
            options += VariableOption(VarRef.Named(variable), variable, "Proměnná")
        }
        return options
    }

    /** Label of a block's output; numbered when several blocks produce the same kind of value. */
    fun outputLabel(block: Block): String {
        val def = Blocks.get(block.type) ?: return "?"
        val sameKind = blocks.filter { Blocks.get(it.type)?.outputName == def.outputName }
        val position = sameKind.indexOfFirst { it.id == block.id }
        return if (sameKind.size > 1) "${def.outputName} ${position + 1}" else def.outputName ?: def.title
    }

    fun labelOf(ref: VarRef): String? = when (ref) {
        VarRef.Input -> "Vstup"
        VarRef.Clipboard -> "Schránka"
        VarRef.CurrentDate -> "Aktuální datum"
        VarRef.Trigger -> "Typ spuštění"
        is VarRef.Event -> EventValue.of(ref.key)?.label
        is VarRef.Named -> ref.name
        is VarRef.BlockOutput -> blocks.firstOrNull { it.id == ref.blockId }?.let { outputLabel(it) }
    }

    fun usesInput(): Boolean = blocks.any { block ->
        block.params.values.any { value ->
            value is ParamValue.Rich && value.segments.any { it is Segment.Var && it.ref == VarRef.Input }
        }
    }

    // ---------- running ----------

    /** A step-by-step run is going on (or has just ended and its results are shown). */
    var stepMode by mutableStateOf(false)
        private set
    /** The step the run is paused after, waiting for "next". */
    var pausedAt by mutableStateOf<cz.natsu.automation.engine.LogEntry?>(null)
        private set
    /** Number of steps executed so far in the step-by-step run. */
    var stepCount by mutableStateOf(0)
        private set
    /** What each step did in the step-by-step run, shown on its card. */
    val stepResults = mutableStateMapOf<String, cz.natsu.automation.engine.LogEntry>()
    /** Named variables ("Nastavit proměnnou") as they are after the current step. */
    var stepVariables by mutableStateOf<Map<String, String>>(emptyMap())
        private set

    private var runJob: Job? = null
    private var stepGate: kotlinx.coroutines.CompletableDeferred<Unit>? = null
    /** "Run to the end" was pressed: stop pausing. */
    private var stepRunFree = false

    fun run(input: String?, stepByStep: Boolean = false) {
        if (running) return
        saveNow()
        running = true
        lastResult = null
        stepResults.clear()
        stepVariables = emptyMap()
        stepCount = 0
        pausedAt = null
        stepMode = stepByStep
        stepRunFree = false
        runJob = viewModelScope.launch {
            val afterStep: (suspend (cz.natsu.automation.engine.LogEntry, cz.natsu.automation.engine.RunContext) -> Unit)? =
                if (!stepByStep) null else { entry, ctx ->
                    val variables = ctx.variables.toMap()
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                        stepResults[entry.blockId] = entry
                        stepVariables = variables
                        if (entry.skipped) return@withContext
                        stepCount++
                        if (!stepRunFree) {
                            val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
                            stepGate = gate
                            pausedAt = entry
                            activeBlockId = entry.blockId
                            try {
                                gate.await()
                            } finally {
                                pausedAt = null
                                stepGate = null
                            }
                        }
                    }
                }
            try {
                lastResult = AutomationRunner.run(
                    getApplication(), blocks.toList(), input, runUi,
                    onStep = { id -> activeBlockId = id },
                    afterStep = afterStep,
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                lastResult = RunResult(null, stepResults.values.toList(), cancelled = true)
            } finally {
                activeBlockId = null
                running = false
                runJob = null
            }
        }
    }

    /** Step-by-step run: execute the next step. */
    fun nextStep() {
        stepGate?.complete(Unit)
    }

    /** Step-by-step run: run the rest without pausing. */
    fun runToEnd() {
        stepRunFree = true
        stepGate?.complete(Unit)
    }

    fun stopRun() {
        runJob?.cancel()
    }

    /** Leaves step mode and hides the step results. */
    fun closeSteps() {
        stopRun()
        stepMode = false
        stepResults.clear()
        stepVariables = emptyMap()
        lastResult = null
    }

    fun clearResult() {
        lastResult = null
    }

    companion object {
        private const val HISTORY_LIMIT = 100
        /** Typing into one field within this time counts as one history step. */
        private const val MERGE_WINDOW_MS = 1500L

        val TRIGGER_DESCRIPTION = "Jak byla automatizace spuštěna: " +
                cz.natsu.automation.engine.RunTrigger.entries.joinToString(", ") { it.label }

        fun plainText(value: ParamValue?): String? = (value as? ParamValue.Rich)?.segments
            ?.joinToString("") { (it as? Segment.Text)?.text ?: "" }
    }
}
