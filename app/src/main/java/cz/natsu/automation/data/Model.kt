package cz.natsu.automation.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.util.UUID

/**
 * Reference to a value that can be inserted into a text field as a "chip".
 */
@Serializable
sealed interface VarRef {
    /** Text the automation was started with (selected / shared text). */
    @Serializable
    @SerialName("input")
    data object Input : VarRef

    /** Current clipboard content, read when the value is needed. */
    @Serializable
    @SerialName("clipboard")
    data object Clipboard : VarRef

    /** Current date and time. */
    @Serializable
    @SerialName("date")
    data object CurrentDate : VarRef

    /** How the automation was started (share, text selection, clipboard, ...). */
    @Serializable
    @SerialName("trigger")
    data object Trigger : VarRef

    /** Output of an earlier block. */
    @Serializable
    @SerialName("output")
    data class BlockOutput(val blockId: String) : VarRef

    /** Value provided by a system event (Wi-Fi name, battery, notification text, ...); see [EventValue]. */
    @Serializable
    @SerialName("event")
    data class Event(val key: String) : VarRef

    /** Named variable created by the "Set variable" block. */
    @Serializable
    @SerialName("variable")
    data class Named(val name: String) : VarRef

    /** Secret stored encrypted on the device (API key, token); see [SecretStore]. Never exported. */
    @Serializable
    @SerialName("secret")
    data class Secret(val name: String) : VarRef
}

@Serializable
sealed interface Segment {
    @Serializable
    @SerialName("text")
    data class Text(val text: String) : Segment

    @Serializable
    @SerialName("var")
    data class Var(val ref: VarRef) : Segment
}

@Serializable
sealed interface ParamValue {
    @Serializable
    @SerialName("rich")
    data class Rich(val segments: List<Segment>) : ParamValue

    @Serializable
    @SerialName("choice")
    data class Choice(val value: String) : ParamValue

    @Serializable
    @SerialName("number")
    data class Number(val value: Float) : ParamValue

    @Serializable
    @SerialName("bool")
    data class Bool(val value: Boolean) : ParamValue
}

fun richText(text: String): ParamValue.Rich =
    ParamValue.Rich(if (text.isEmpty()) emptyList() else listOf(Segment.Text(text)))

fun richVar(ref: VarRef): ParamValue.Rich = ParamValue.Rich(listOf(Segment.Var(ref)))

/**
 * One step of an automation. Control blocks (if / otherwise / end) that belong
 * together share the same [groupId].
 */
@Serializable
data class Block(
    val id: String = UUID.randomUUID().toString(),
    val type: String,
    val params: Map<String, ParamValue> = emptyMap(),
    val groupId: String? = null,
    /** A switched-off step is skipped when running (a switched-off "if" skips its whole condition). */
    val disabled: Boolean = false,
)

object BlockJson {
    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val serializer = ListSerializer(Block.serializer())

    fun encode(blocks: List<Block>): String = json.encodeToString(serializer, blocks)

    fun decode(text: String): List<Block> = try {
        json.decodeFromString(serializer, text)
    } catch (e: Exception) {
        emptyList()
    }
}
