package cz.natsu.automation.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Redo
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.automirrored.rounded.WrapText
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.DataObject
import androidx.compose.material.icons.rounded.FormatAlignLeft
import androidx.compose.material.icons.rounded.OpenInFull
import androidx.compose.material.icons.rounded.TextDecrease
import androidx.compose.material.icons.rounded.TextIncrease
import androidx.compose.material.icons.rounded.TaskAlt
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import cz.natsu.automation.data.Segment
import cz.natsu.automation.data.VarRef
import cz.natsu.automation.i18n.tr

/** Colours of the kinds of code, readable in the light and the dark theme. */
@Composable
private fun codeStyles(): (CodeKind) -> SpanStyle {
    val dark = MaterialTheme.colorScheme.surface.let { (it.red + it.green + it.blue) / 3 < 0.5f }
    val palette = if (dark) {
        mapOf(
            CodeKind.Keyword to Color(0xFFC792EA), CodeKind.Str to Color(0xFFC3E88D), CodeKind.Comment to Color(0xFF8A8FA3),
            CodeKind.Number to Color(0xFFF78C6C), CodeKind.Tag to Color(0xFF82AAFF), CodeKind.Attribute to Color(0xFFFFCB6B),
            CodeKind.Function to Color(0xFF89DDFF), CodeKind.Punctuation to Color(0xFFBFC7D5),
            CodeKind.Action to Color(0xFFFFB4A9),
        )
    } else {
        mapOf(
            CodeKind.Keyword to Color(0xFF7B1FA2), CodeKind.Str to Color(0xFF2E7D32), CodeKind.Comment to Color(0xFF7A7F8C),
            CodeKind.Number to Color(0xFFD84315), CodeKind.Tag to Color(0xFF1565C0), CodeKind.Attribute to Color(0xFF9A6700),
            CodeKind.Function to Color(0xFF00838F), CodeKind.Punctuation to Color(0xFF546E7A),
            CodeKind.Action to Color(0xFFC62828),
        )
    }
    // Tappable parts of a widget stand out: bold on a tinted background.
    val actionBackground = if (dark) Color(0x33FF8A80) else Color(0x22E53935)
    return { kind ->
        SpanStyle(
            color = palette.getValue(kind),
            fontStyle = if (kind == CodeKind.Comment) FontStyle.Italic else null,
            fontWeight = if (kind == CodeKind.Keyword) FontWeight.SemiBold else if (kind == CodeKind.Action) FontWeight.Bold else null,
            background = if (kind == CodeKind.Action) actionBackground else Color.Unspecified,
        )
    }
}

/**
 * A code parameter in the block card: a short coloured preview of the code, and a full screen
 * editor after a tap, so a long code does not stretch the whole automation.
 */
@Composable
fun CodeField(
    segments: List<Segment>,
    onChange: (List<Segment>) -> Unit,
    styleOf: (VarRef) -> ChipStyle,
    title: String,
    placeholder: String,
    language: CodeLanguage,
    allowVariables: Boolean,
    onRequestVariable: ((RichTextFieldController) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    var open by remember { mutableStateOf(false) }
    val styles = codeStyles()
    val codec = remember { ChipCodec() }
    val text = remember(segments) { codec.encode(segments) }
    val shown = remember(text, styles) {
        // Chips as their labels on a tinted background, the code coloured.
        var chips: List<Pair<TextRange, ChipStyle>> = emptyList()
        val annotated = ChipTransformation(
            codec, styleOf, onChips = { chips = it },
            highlight = { source -> CodeTools.tokens(source, language).map { it.first to styles(it.second) } },
        ).filter(AnnotatedString(text)).text
        AnnotatedString.Builder(annotated).apply {
            for ((range, style) in chips) addStyle(SpanStyle(background = style.container), range.start, range.end)
        }.toAnnotatedString()
    }
    val lines = if (text.isEmpty()) 0 else text.count { it == '\n' } + 1
    val codeStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 17.sp)

    Surface(
        onClick = { open = true },
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(start = 12.dp, end = 6.dp, top = 8.dp, bottom = 4.dp)) {
            if (text.isEmpty()) {
                Text(
                    placeholder.ifEmpty { tr("Klepni a napiš kód") },
                    style = codeStyle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )
            } else {
                Text(shown, style = codeStyle, color = MaterialTheme.colorScheme.onSurface, maxLines = 7, overflow = TextOverflow.Ellipsis)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Code, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(6.dp))
                Text(
                    if (lines == 0) tr("Prázdné") else tr("%s ř. · %s znaků", lines, text.length),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { open = true }) {
                    Icon(Icons.Rounded.OpenInFull, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(tr("Upravit"))
                }
            }
        }
    }

    if (open) {
        CodeEditorDialog(
            segments, onChange, styleOf, title, language, allowVariables, onRequestVariable,
            onClose = { open = false },
        )
    }
}

/** Keys that are far away on a phone keyboard. */
private val symbols = listOf("<", ">", "/", "=", "\"", "'", "{", "}", "(", ")", "[", "]", ";", ":", ".", ",", "#", "&", "|", "!", "+", "-", "*", "_")

@Composable
private fun CodeEditorDialog(
    segments: List<Segment>,
    onChange: (List<Segment>) -> Unit,
    styleOf: (VarRef) -> ChipStyle,
    title: String,
    language: CodeLanguage,
    allowVariables: Boolean,
    onRequestVariable: ((RichTextFieldController) -> Unit)?,
    onClose: () -> Unit,
) {
    val codec = remember { ChipCodec() }
    var value by remember { mutableStateOf(TextFieldValue(codec.encode(segments))) }
    val undo = remember { mutableStateListOf<TextFieldValue>() }
    val redo = remember { mutableStateListOf<TextFieldValue>() }
    var lastEdit by remember { mutableStateOf(0L) }
    var wrap by remember { mutableStateOf(false) }
    var fontSize by remember { mutableFloatStateOf(13f) }
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    var mapping by remember { mutableStateOf<OffsetMapping?>(null) }
    var chips by remember { mutableStateOf<List<Pair<TextRange, ChipStyle>>>(emptyList()) }
    var message by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    val styles = codeStyles()
    val transformation = remember(styleOf, styles) {
        ChipTransformation(
            codec, styleOf, onChips = { chips = it },
            highlight = { source -> CodeTools.tokens(source, language).map { it.first to styles(it.second) } },
            onMapping = { mapping = it },
        )
    }
    val focus = remember { FocusRequester() }

    /** A change of the text: remembered for Undo (typing within a second is one step) and saved. */
    fun change(next: TextFieldValue, step: Boolean = false) {
        if (next.text == value.text) { value = next; return }
        val now = System.currentTimeMillis()
        if (step || now - lastEdit > 1000 || undo.isEmpty()) {
            undo.add(value)
            if (undo.size > 100) undo.removeAt(0)
        }
        lastEdit = if (step) 0L else now
        redo.clear()
        value = next
        message = null
        onChange(codec.decode(next.text))
    }

    fun insert(piece: String) {
        val s = value.selection
        val text = value.text.replaceRange(s.min, s.max, piece)
        change(TextFieldValue(text, TextRange(s.min + piece.length)), step = piece.length > 1)
    }

    val controller = remember { RichTextFieldController() }
    controller.insert = { ref -> insert(codec.charFor(ref).toString()) }

    LaunchedEffect(Unit) { focus.requestFocus() }

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
                // Top bar: back, title, Undo / Redo and the tools.
                Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, tr("Hotovo")) }
                    Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    IconButton(onClick = {
                        redo.add(value); value = undo.removeAt(undo.lastIndex); onChange(codec.decode(value.text))
                    }, enabled = undo.isNotEmpty()) { Icon(Icons.AutoMirrored.Rounded.Undo, tr("Zpět")) }
                    IconButton(onClick = {
                        undo.add(value); value = redo.removeAt(redo.lastIndex); onChange(codec.decode(value.text))
                    }, enabled = redo.isNotEmpty()) { Icon(Icons.AutoMirrored.Rounded.Redo, tr("Znovu")) }
                }
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    TextButton(onClick = {
                        val formatted = CodeTools.format(value.text, language)
                        if (formatted != value.text) change(TextFieldValue(formatted, TextRange(0)), step = true)
                        message = tr("Naformátováno") to false
                    }) {
                        Icon(Icons.Rounded.FormatAlignLeft, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(tr("Formátovat"))
                    }
                    if (language == CodeLanguage.Script) {
                        TextButton(onClick = {
                            // Variables count as names, the rest has to be correct script.
                            val code = value.text.map { if (ChipCodec.isChipChar(it)) 'x' else it }.joinToString("")
                            message = try {
                                cz.natsu.automation.engine.Script.compile(code)
                                tr("Skript je v pořádku") to false
                            } catch (e: Exception) {
                                (e.message ?: e.javaClass.simpleName) to true
                            }
                        }) {
                            Icon(Icons.Rounded.TaskAlt, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(tr("Zkontrolovat"))
                        }
                    }
                    IconToggleButton(checked = wrap, onCheckedChange = { wrap = it }) {
                        Icon(Icons.AutoMirrored.Rounded.WrapText, tr("Zalamovat řádky"), tint = if (wrap) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = { fontSize = (fontSize - 1).coerceAtLeast(9f) }) { Icon(Icons.Rounded.TextDecrease, tr("Menší písmo")) }
                    IconButton(onClick = { fontSize = (fontSize + 1).coerceAtMost(22f) }) { Icon(Icons.Rounded.TextIncrease, tr("Větší písmo")) }
                }
                message?.let { (text, error) ->
                    Text(
                        text,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                    )
                }
                HorizontalDivider()

                // The code with line numbers.
                val textStyle = TextStyle(
                    fontFamily = FontFamily.Monospace, fontSize = fontSize.sp, lineHeight = (fontSize * 1.45f).sp,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                val measurer = rememberTextMeasurer()
                val gutterColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                val density = LocalDensity.current
                val chipRadius = with(density) { 6.dp.toPx() }
                val chipInset = with(density) { 1.dp.toPx() }
                val lineCount = value.text.count { it == '\n' } + 1
                val gutterWidth = with(density) { (measurer.measure("0".repeat(lineCount.toString().length), textStyle).size.width).toDp() } + 16.dp
                Row(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
                    Box(
                        Modifier.width(gutterWidth).height(with(density) { (layout?.size?.height ?: 0).toDp() } + 16.dp)
                            .background(MaterialTheme.colorScheme.surfaceContainer)
                            .drawBehind {
                                val result = layout ?: return@drawBehind
                                val map = mapping ?: return@drawBehind
                                var start = 0
                                var number = 1
                                val text = value.text
                                while (true) {
                                    val offset = map.originalToTransformed(start).coerceIn(0, result.layoutInput.text.length)
                                    val line = result.getLineForOffset(offset)
                                    val label = measurer.measure(number.toString(), textStyle.copy(color = gutterColor))
                                    drawText(label, topLeft = androidx.compose.ui.geometry.Offset(size.width - label.size.width - 8.dp.toPx(), result.getLineTop(line) + 8.dp.toPx()))
                                    val next = text.indexOf('\n', start)
                                    if (next < 0) break
                                    start = next + 1
                                    number++
                                }
                            }
                    )
                    val fieldModifier = Modifier.padding(start = 8.dp, top = 8.dp, end = 12.dp, bottom = 8.dp)
                    Box(if (wrap) Modifier.weight(1f) else Modifier.weight(1f).horizontalScroll(rememberScrollState())) {
                        BasicTextField(
                            value = value,
                            onValueChange = { next ->
                                // Enter keeps the indentation of the line (and indents after an opening line).
                                val inserted = next.text.length == value.text.length + 1 && next.selection.collapsed &&
                                        next.selection.start > 0 && next.text[next.selection.start - 1] == '\n'
                                if (inserted) {
                                    val at = next.selection.start
                                    val indent = CodeTools.indentAfter(next.text, at - 1, language)
                                    change(TextFieldValue(next.text.substring(0, at) + indent + next.text.substring(at), TextRange(at + indent.length)))
                                } else change(next)
                            },
                            modifier = fieldModifier.then(if (wrap) Modifier.fillMaxWidth() else Modifier.widthIn(min = 300.dp))
                                .focusRequester(focus)
                                .drawBehind {
                                    val result = layout ?: return@drawBehind
                                    for ((range, style) in chips) drawChip(result, range, style.container, chipRadius, chipInset)
                                },
                            textStyle = textStyle,
                            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false),
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                            visualTransformation = transformation,
                            onTextLayout = { layout = it },
                        )
                    }
                }

                // Symbols that are hard to reach on a phone keyboard, and the variable chips.
                HorizontalDivider()
                Row(
                    Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer)
                        .horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (allowVariables && onRequestVariable != null) {
                        IconButton(onClick = { onRequestVariable(controller) }) {
                            Icon(Icons.Rounded.DataObject, tr("Vložit proměnnou"), tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                    SymbolKey("⇥") { insert(language.indent) }
                    for (symbol in symbols) SymbolKey(symbol) { insert(symbol) }
                }
            }
        }
    }
}

@Composable
private fun SymbolKey(label: String, onClick: () -> Unit) {
    Box(
        Modifier.padding(2.dp).size(width = 38.dp, height = 40.dp)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Monospace))
    }
}
