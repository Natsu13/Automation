package cz.natsu.automation.engine

import androidx.compose.ui.graphics.vector.ImageVector
import cz.natsu.automation.data.Block
import cz.natsu.automation.data.ParamValue
import cz.natsu.automation.data.VarRef
import cz.natsu.automation.data.richText
import cz.natsu.automation.data.richVar

enum class BlockKind {
    Normal, IfStart, Else, End, WhileStart, TryStart,

    /** "Jinak pokud": another condition of an "if", tried when the ones before it failed. */
    ElseIf,

    /** "Souběžně": its branches run at the same time and the run goes on when all are done. */
    ParallelStart,

    /** Separator between two branches of "Souběžně". */
    Branch,

    /** A plain group: its steps run as usual, under a rule of the block (one at a time, a time limit). */
    Scope;

    /** Starts a group that ends with an "end" block (if, loop, try, parallel). */
    val isGroupStart: Boolean get() = this == IfStart || this == WhileStart || this == TryStart || this == ParallelStart || this == Scope
}

enum class BlockCategory(val title: String) {
    Text("Text"),
    Numbers("Čísla"),
    Clipboard("Schránka"),
    Share("Sdílení a web"),
    Data("API, JSON a seznamy"),
    Images("Obrázky"),
    Dialogs("Dialogy"),
    Variables("Proměnné"),
    Control("Podmínky a řízení"),
    Device("Zařízení"),
    Apps("Aplikace"),
    Security("Zabezpečení"),
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
    LocalNetwork(
        "Přístup k místní síti",
        "Android 17 bez něj nepustí aplikaci k zařízením v domácí síti (http://192.168…)."
    ),
    Overlay(
        "Zobrazení přes jiné aplikace",
        "Bez něj nejde z událostí na pozadí otevřít okno akce (dialogy, schránka, hlasitost na Androidu 17)."
    ),
    WriteSettings(
        "Změna nastavení systému",
        "Bez něj aplikace nesmí měnit jas displeje."
    ),
    Location(
        "Přístup k poloze",
        "Bez něj nejde zjistit, kde telefon je."
    ),
    InstallPackages(
        "Instalace neznámých aplikací",
        "Bez něj aplikace nesmí nabídnout instalaci APK (třeba své aktualizace)."
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
        /** Code: a tall field with a fixed-width font (the "Skript" block). */
        val code: Boolean = false,
        /** Offers a picker of installed apps that fills in the app's package name. */
        val appPicker: Boolean = false,
        /** Offers the screens, services and receivers of the app chosen in the block's "package" field. */
        val componentPicker: Boolean = false,
        /** Values offered as chips under the field. */
        val suggestions: List<String> = emptyList(),
        /**
         * Offers to put an image right into the automation (from the gallery or an icon),
         * stored as a data:image/…;base64 text; a variable or a file still works too.
         */
        val imagePicker: Boolean = false,
        /** A colour: shows it next to the field when it can be worked out, with a picker. */
        val colorPicker: Boolean = false,
        /** The key of an icon from the catalogue: shows the icon next to the field, with a picker. */
        val iconPicker: Boolean = false,
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
     * The block needs a window on screen (a dialog, reading the clipboard, opening
     * another app), so a run started by an event in the background opens one.
     */
    val needsWindow: Boolean = false,
    /** Like [needsWindow], for blocks where it depends on how the block is set up. */
    val needsWindowWhen: ((Block) -> Boolean)? = null,
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
