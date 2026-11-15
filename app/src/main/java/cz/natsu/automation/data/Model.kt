package cz.natsu.automation.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
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

    /**
     * Result of reading stored blocks. [unreadable] = the text is not a list of blocks
     * at all (nothing could be read, [blocks] is empty). [lost] = number of blocks,
     * parameters or variables that had to be left out, e.g. because they come from a
     * newer version of the app.
     */
    class Decoded(val blocks: List<Block>, val unreadable: Boolean = false, val lost: Int = 0) {
        val damaged: Boolean get() = unreadable || lost > 0
    }

    fun decode(text: String): List<Block> = decodeChecked(text).blocks

    /**
     * Reads as much as possible: one value the app does not understand must not
     * throw away the whole automation. The caller decides what to do when
     * something is [Decoded.damaged] (the editor then never overwrites silently).
     */
    fun decodeChecked(text: String): Decoded {
        try {
            return Decoded(json.decodeFromString(serializer, text))
        } catch (e: Exception) {
            // Fall through to the block-by-block reading.
        }
        val array = try {
            json.parseToJsonElement(text) as? JsonArray
        } catch (e: Exception) {
            null
        } ?: return Decoded(emptyList(), unreadable = true)

        var lost = 0
        val blocks = array.mapNotNull { element ->
            try {
                json.decodeFromJsonElement(Block.serializer(), element)
            } catch (e: Exception) {
                val (block, missing) = salvage(element)
                lost += missing
                block
            }
        }
        return Decoded(blocks, lost = lost)
    }

    /** A block with the parts that can still be read, and how many parts were left out. */
    private fun salvage(element: JsonElement): Pair<Block?, Int> {
        val obj = element as? JsonObject ?: return null to 1
        val type = (obj["type"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null to 1
        var lost = 0
        val params = mutableMapOf<String, ParamValue>()
        for ((key, value) in (obj["params"] as? JsonObject).orEmpty()) {
            try {
                params[key] = json.decodeFromJsonElement(ParamValue.serializer(), value)
            } catch (e: Exception) {
                val rich = salvageRich(value)
                if (rich == null) lost++ else {
                    params[key] = rich.first
                    lost += rich.second
                }
            }
        }
        fun string(key: String) = (obj[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
        val block = Block(
            id = string("id") ?: UUID.randomUUID().toString(),
            type = type,
            params = params,
            groupId = string("groupId"),
            disabled = (obj["disabled"] as? JsonPrimitive)?.booleanOrNull ?: false,
        )
        return block to lost
    }

    /** A text value without the segments (variables) that cannot be read. */
    private fun salvageRich(value: JsonElement): Pair<ParamValue.Rich, Int>? {
        val obj = value as? JsonObject ?: return null
        if ((obj["type"] as? JsonPrimitive)?.content != "rich") return null
        val segments = obj["segments"] as? JsonArray ?: return null
        var lost = 0
        val kept = segments.mapNotNull { segment ->
            try {
                json.decodeFromJsonElement(Segment.serializer(), segment)
            } catch (e: Exception) {
                lost++
                null
            }
        }
        return ParamValue.Rich(kept) to lost
    }
}
