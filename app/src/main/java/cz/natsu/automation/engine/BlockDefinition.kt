package cz.natsu.automation.engine

import androidx.compose.ui.graphics.vector.ImageVector
import cz.natsu.automation.data.Block
import cz.natsu.automation.data.ParamValue
import cz.natsu.automation.data.VarRef
import cz.natsu.automation.data.richText
import cz.natsu.automation.data.richVar

enum class BlockKind { Normal, IfStart, Else, End, WhileStart }

enum class BlockCategory(val title: String) {
    Text("Text"),
    Clipboard("Schránka"),
    Share("Sdílení a web"),
    Data("API, JSON a seznamy"),
    Dialogs("Dialogy"),
    Variables("Proměnné"),
    Control("Podmínky a řízení"),
    Device("Zařízení"),
    Other("Ostatní"),
}

/** Something the user has to grant in system settings before a block can work. */
enum class Requirement(val title: String, val description: String) {
    NotificationPolicy(
        "Přístup k režimu Nerušit",
        "Bez něj nejde přepnout zvonění na tichý režim ani měnit hlasitost během Nerušit."
    ),
    PostNotifications(
        "Oznámení",
        "Aplikace musí smět zobrazovat oznámení."
    ),
    Overlay(
        "Zobrazení přes jiné aplikace",
        "Bez něj nejde z událostí na pozadí otevřít okno akce (dialogy, schránka, hlasitost na Androidu 17)."
    ),
}

/** [short] is used in the one-line summary of a collapsed block. */
data class ChoiceOption(val value: String, val label: String, val short: String = label)

sealed class ParamDef(val key: String, val label: String) {
    abstract fun defaultValue(): ParamValue

    /** When it returns true for the block's current values, the parameter is not shown. */
    var hiddenWhen: ((Map<String, ParamValue>) -> Boolean)? = null

    fun isHidden(values: Map<String, ParamValue>): Boolean = hiddenWhen?.invoke(values) == true

    /** Left out of the collapsed summary (but still editable) when it returns true. */
    var hiddenInSummaryWhen: ((Map<String, ParamValue>) -> Boolean)? = null

    /**
     * Wrapping text field that can contain variable chips.
     * [plain] disables chips (e.g. for a variable name).
     */
    class Text(
        key: String,
        label: String,
        val default: String = "",
        val defaultRef: VarRef? = null,
        val placeholder: String = "",
        val plain: Boolean = false,
    ) : ParamDef(key, label) {
        override fun defaultValue(): ParamValue =
            if (defaultRef != null) richVar(defaultRef) else richText(default)
    }

    class Choice(
        key: String,
        label: String,
        val options: List<ChoiceOption>,
        val default: String = options.first().value,
    ) : ParamDef(key, label) {
        override fun defaultValue(): ParamValue = ParamValue.Choice(default)
        fun labelOf(value: String) = options.firstOrNull { it.value == value }?.label ?: value
        fun shortOf(value: String) = options.firstOrNull { it.value == value }?.short ?: value
    }

    class Number(
        key: String,
        label: String,
        val min: Float,
        val max: Float,
        val default: Float,
        val step: Float = 1f,
        val unit: String = "",
    ) : ParamDef(key, label) {
        override fun defaultValue(): ParamValue = ParamValue.Number(default)
    }

    /** Another automation, chosen from a list; stored as its id ("" = none). */
    class Automation(key: String, label: String) : ParamDef(key, label) {
        override fun defaultValue(): ParamValue = ParamValue.Choice("")
    }

    /** [summaryWhenOn] is appended to the collapsed summary when the toggle is on. */
    class Toggle(
        key: String,
        label: String,
        val default: Boolean = false,
        val summaryWhenOn: String? = null,
    ) : ParamDef(key, label) {
        override fun defaultValue(): ParamValue = ParamValue.Bool(default)
    }
}

sealed interface BlockResult {
    data object None : BlockResult
    data class Output(val value: String) : BlockResult
    /** [output] = value of the step (e.g. the current item of a loop); shown in the log. */
    data class Condition(val passed: Boolean, val output: String? = null) : BlockResult
    data class Stop(val output: String?) : BlockResult
    /** Inside a loop: [exit] ends the loop, otherwise jumps to its next round. */
    data class LoopJump(val exit: Boolean) : BlockResult
}

class BlockException(message: String, val requirement: Requirement? = null) : Exception(message)

class BlockDef(
    val type: String,
    val title: String,
    val description: String,
    val category: BlockCategory,
    val icon: ImageVector,
    val params: List<ParamDef> = emptyList(),
    val kind: BlockKind = BlockKind.Normal,
    /** Name of the value the block produces, or null when it produces nothing. */
    val outputName: String? = null,
    val requirement: Requirement? = null,
    val showInLibrary: Boolean = true,
    /**
     * One-line description of a collapsed block; `{key}` is replaced by the
     * highlighted value of that parameter.
     */
    val summary: String = title,
    val execute: suspend RunContext.(Block) -> BlockResult = { BlockResult.None },
) {
    fun param(key: String): ParamDef? = params.firstOrNull { it.key == key }

    fun newBlock(groupId: String? = null): Block =
        Block(type = type, params = params.associate { it.key to it.defaultValue() }, groupId = groupId)
}
