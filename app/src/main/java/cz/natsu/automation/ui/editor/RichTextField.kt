package cz.natsu.automation.ui.editor

import cz.natsu.automation.i18n.tr

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.focus.onFocusChanged
import kotlinx.coroutines.delay
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DataObject
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import cz.natsu.automation.data.Segment
import cz.natsu.automation.data.VarRef

/** Label and color of a variable chip. */
data class ChipStyle(val label: String, val container: Color, val content: Color)

private const val FIRST_CHIP_CHAR = 0xE000
private const val LAST_CHIP_CHAR = 0xF8FF
private const val CHIP_PADDING = " "

/**
 * Keeps the mapping between variable chips and the private-use characters that
 * stand for them inside the edited string. Every chip gets its own character, so
 * deleting one chip never shifts the meaning of the others.
 */
private class ChipCodec {
    val refs = mutableMapOf<Char, VarRef>()
    private var next = FIRST_CHIP_CHAR

    fun charFor(ref: VarRef): Char {
        if (next > LAST_CHIP_CHAR) next = FIRST_CHIP_CHAR
        val c = next.toChar()
        next++
        refs[c] = ref
        return c
    }

    fun encode(segments: List<Segment>): String = buildString {
        for (segment in segments) {
            when (segment) {
                is Segment.Text -> append(segment.text.filterNot { isChipChar(it) })
                is Segment.Var -> append(charFor(segment.ref))
            }
        }
    }

    fun decode(text: String): List<Segment> {
        val result = mutableListOf<Segment>()
        val buffer = StringBuilder()
        for (c in text) {
            val ref = refs[c]
            when {
                ref != null -> {
                    if (buffer.isNotEmpty()) {
                        result += Segment.Text(buffer.toString())
                        buffer.clear()
                    }
                    result += Segment.Var(ref)
                }
                isChipChar(c) -> Unit // stray character (e.g. pasted) – drop it
                else -> buffer.append(c)
            }
        }
        if (buffer.isNotEmpty()) result += Segment.Text(buffer.toString())
        return result
    }

    companion object {
        fun isChipChar(c: Char) = c.code in FIRST_CHIP_CHAR..LAST_CHIP_CHAR
    }
}

/** Replaces each chip character with its label and remembers where the chips are. */
private class ChipTransformation(
    private val codec: ChipCodec,
    private val styleOf: (VarRef) -> ChipStyle,
    private val onChips: (List<Pair<TextRange, ChipStyle>>) -> Unit,
) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val source = text.text
        val builder = AnnotatedString.Builder()
        val originalToTransformed = IntArray(source.length + 1)
        val transformedToOriginal = ArrayList<Int>(source.length + 16)
        val chips = mutableListOf<Pair<TextRange, ChipStyle>>()

        for ((index, c) in source.withIndex()) {
            originalToTransformed[index] = builder.length
            val ref = codec.refs[c]
            if (ref == null) {
                transformedToOriginal += index
                builder.append(c)
                continue
            }

            val style = styleOf(ref)
            // Non-breaking spaces keep a chip on one line.
            val label = CHIP_PADDING + style.label.replace(' ', ' ') + CHIP_PADDING
            val start = builder.length
            builder.pushStyle(SpanStyle(color = style.content, fontWeight = FontWeight.Medium))
            builder.append(label)
            builder.pop()
            // Cursor positions inside the chip snap to its start or end.
            for (i in label.indices) {
                transformedToOriginal += if (i <= label.length / 2) index else index + 1
            }
            chips += TextRange(start, builder.length) to style
        }
        originalToTransformed[source.length] = builder.length
        transformedToOriginal += source.length

        onChips(chips)

        val mapping = object : OffsetMapping {
            override fun originalToTransformed(offset: Int): Int =
                originalToTransformed[offset.coerceIn(0, source.length)]

            override fun transformedToOriginal(offset: Int): Int =
                transformedToOriginal[offset.coerceIn(0, transformedToOriginal.size - 1)]
        }
        return TransformedText(builder.toAnnotatedString(), mapping)
    }

    override fun equals(other: Any?): Boolean = other === this
    override fun hashCode(): Int = System.identityHashCode(this)
}

/** Lets the variable picker insert a chip at the current cursor position. */
class RichTextFieldController {
    internal var insert: ((VarRef) -> Unit)? = null
    fun insertVariable(ref: VarRef) {
        insert?.invoke(ref)
    }
}

/**
 * Multi-line text field that wraps and can contain variables shown as chips.
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
fun RichTextField(
    segments: List<Segment>,
    onChange: (List<Segment>) -> Unit,
    styleOf: (VarRef) -> ChipStyle,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    allowVariables: Boolean = true,
    onRequestVariable: ((RichTextFieldController) -> Unit)? = null,
    /** Code: fixed-width font, several lines tall, no auto-correct or capital letters. */
    code: Boolean = false,
) {
    val codec = remember { ChipCodec() }
    var value by remember { mutableStateOf(TextFieldValue(codec.encode(segments))) }
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    var chips by remember { mutableStateOf<List<Pair<TextRange, ChipStyle>>>(emptyList()) }
    val transformation = remember(styleOf) { ChipTransformation(codec, styleOf) { chips = it } }

    // Take over a value changed from outside (undo / redo).
    LaunchedEffect(segments) {
        val outside = codec.encode(segments)
        if (codec.decode(outside) != codec.decode(value.text)) {
            value = TextFieldValue(outside, TextRange(outside.length))
        }
    }

    val controller = remember { RichTextFieldController() }
    controller.insert = { ref ->
        val c = codec.charFor(ref)
        val selection = value.selection
        val text = value.text.replaceRange(selection.min, selection.max, c.toString())
        value = TextFieldValue(text, TextRange(selection.min + 1))
        onChange(codec.decode(text))
    }

    // Keep the field visible above the keyboard: once it is focused and whenever the
    // keyboard finishes opening or the text grows, scroll the whole field into view.
    val bringIntoView = remember { BringIntoViewRequester() }
    var focused by remember { mutableStateOf(false) }
    val imeVisible = WindowInsets.isImeVisible
    LaunchedEffect(focused, imeVisible, layout?.size?.height) {
        if (focused) {
            delay(if (imeVisible) 50 else 300)
            bringIntoView.bringIntoView()
        }
    }

    val textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface).let {
        if (code) it.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, fontSize = it.fontSize * 0.9f) else it
    }
    val chipRadius = with(androidx.compose.ui.platform.LocalDensity.current) { 6.dp.toPx() }
    val chipInset = with(androidx.compose.ui.platform.LocalDensity.current) { 1.dp.toPx() }

    BasicTextField(
        value = value,
        onValueChange = { newValue ->
            val changed = newValue.text != value.text
            value = newValue
            if (changed) onChange(codec.decode(newValue.text))
        },
        modifier = modifier
            .fillMaxWidth()
            .bringIntoViewRequester(bringIntoView)
            .onFocusChanged { focused = it.isFocused },
        textStyle = textStyle,
        minLines = if (code) 6 else 1,
        keyboardOptions = if (code) androidx.compose.foundation.text.KeyboardOptions(
            capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.None,
            autoCorrectEnabled = false,
        ) else androidx.compose.foundation.text.KeyboardOptions.Default,
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        visualTransformation = if (allowVariables) transformation else VisualTransformation.None,
        onTextLayout = { layout = it },
        decorationBox = { innerTextField ->
            Row(
                modifier = Modifier
                    .background(
                        MaterialTheme.colorScheme.surfaceContainerHighest,
                        RoundedCornerShape(12.dp)
                    )
                    .padding(start = 12.dp, top = 4.dp, bottom = 4.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .padding(vertical = 8.dp)
                        .drawBehind {
                            val result = layout ?: return@drawBehind
                            for ((range, style) in chips) {
                                drawChip(result, range, style.container, chipRadius, chipInset)
                            }
                        }
                ) {
                    if (value.text.isEmpty() && placeholder.isNotEmpty()) {
                        Text(
                            placeholder,
                            style = textStyle,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                    }
                    innerTextField()
                }
                if (allowVariables && onRequestVariable != null) {
                    IconButton(onClick = { onRequestVariable(controller) }) {
                        Icon(
                            Icons.Rounded.DataObject,
                            contentDescription = tr("Vložit proměnnou"),
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }
    )
}

internal fun androidx.compose.ui.graphics.drawscope.DrawScope.drawChip(
    layout: TextLayoutResult,
    range: TextRange,
    color: Color,
    radius: Float,
    inset: Float,
) {
    val length = layout.layoutInput.text.length
    if (range.collapsed || range.end > length) return

    val firstLine = layout.getLineForOffset(range.start)
    val lastLine = layout.getLineForOffset(range.end - 1)
    for (line in firstLine..lastLine) {
        val left = if (line == firstLine) layout.getBoundingBox(range.start).left else layout.getLineLeft(line)
        val right = if (line == lastLine) layout.getBoundingBox(range.end - 1).right else layout.getLineRight(line)
        val top = layout.getLineTop(line) + inset
        val bottom = layout.getLineBottom(line) - inset
        drawRoundRect(
            color = color,
            topLeft = Offset(left, top),
            size = Size(right - left, bottom - top),
            cornerRadius = CornerRadius(radius, radius)
        )
    }
}
