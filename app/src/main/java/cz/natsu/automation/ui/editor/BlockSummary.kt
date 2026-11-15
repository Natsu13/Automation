package cz.natsu.automation.ui.editor

import cz.natsu.automation.i18n.tr

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cz.natsu.automation.data.Block
import cz.natsu.automation.data.ParamValue
import cz.natsu.automation.data.Segment
import cz.natsu.automation.data.VarRef
import cz.natsu.automation.engine.BlockDef
import cz.natsu.automation.engine.ParamDef
import cz.natsu.automation.engine.formatNumber

// Narrow no-break space: pads the highlight and never gets separated from the value.
private const val PAD = " "
private const val MAX_TEXT = 48
private val PLACEHOLDER = Regex("\\{(\\w+)\\}")

/**
 * One sentence describing what a collapsed block does, e.g.
 * "Nahradit [utm_…] za [ ] v [Vstup]", with the configured values highlighted.
 */
@Composable
fun BlockSummary(
    block: Block,
    def: BlockDef,
    chipStyle: (VarRef) -> ChipStyle,
    outputLabel: String?,
    outputStyle: ChipStyle?,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val valueStyle = ChipStyle("", scheme.surfaceContainerHighest, scheme.primary)
    val emptyStyle = ChipStyle("", scheme.surfaceContainerHighest, scheme.onSurfaceVariant)

    val ranges = mutableListOf<Pair<TextRange, Color>>()
    val text = buildSummary(block, def, chipStyle, valueStyle, emptyStyle, outputLabel, outputStyle, ranges)

    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val radius = with(LocalDensity.current) { 6.dp.toPx() }
    val inset = with(LocalDensity.current) { 1.dp.toPx() }

    Text(
        text = text,
        style = MaterialTheme.typography.bodyLarge.copy(lineHeight = MaterialTheme.typography.bodyLarge.fontSize * 1.6f),
        color = scheme.onSurface,
        onTextLayout = { layout = it },
        modifier = modifier.drawBehind {
            val result = layout ?: return@drawBehind
            if (result.layoutInput.text.text != text.text) return@drawBehind
            for ((range, color) in ranges) drawChip(result, range, color, radius, inset)
        }
    )
}

private fun buildSummary(
    block: Block,
    def: BlockDef,
    chipStyle: (VarRef) -> ChipStyle,
    valueStyle: ChipStyle,
    emptyStyle: ChipStyle,
    outputLabel: String?,
    outputStyle: ChipStyle?,
    ranges: MutableList<Pair<TextRange, Color>>,
): AnnotatedString {
    val builder = AnnotatedString.Builder()

    fun pill(label: String, style: ChipStyle, keepTogether: Boolean, italic: Boolean = false) {
        val start = builder.length
        builder.pushStyle(
            SpanStyle(
                color = style.content,
                fontWeight = FontWeight.Medium,
                fontStyle = if (italic) FontStyle.Italic else null
            )
        )
        builder.append(PAD)
        builder.append(if (keepTogether) label.replace(' ', ' ') else label)
        builder.append(PAD)
        builder.pop()
        ranges += TextRange(start, builder.length) to style.container
    }

    fun appendValue(param: ParamDef, value: ParamValue) {
        when (value) {
            is ParamValue.Rich -> {
                val meaningful = value.segments.filter { it !is Segment.Text || it.text.isNotEmpty() }
                if (meaningful.isEmpty()) {
                    pill(tr("prázdné"), emptyStyle, keepTogether = true, italic = true)
                    return
                }
                var budget = MAX_TEXT
                for ((index, segment) in meaningful.withIndex()) {
                    if (index > 0) builder.append("​")
                    when (segment) {
                        is Segment.Var -> {
                            val style = chipStyle(segment.ref)
                            pill(style.label, style, keepTogether = true)
                        }
                        is Segment.Text -> {
                            if (budget <= 0) continue
                            var shown = segment.text.replace(Regex("\\s*\\R\\s*"), " ⏎ ")
                            if (shown.length > budget) shown = shown.take(budget).trimEnd() + "…"
                            budget -= shown.length
                            pill(shown, valueStyle, keepTogether = shown.length <= 16)
                        }
                    }
                }
            }
            is ParamValue.Choice -> {
                val label = (param as? ParamDef.Choice)?.shortOf(value.value)?.let { tr(it) } ?: value.value
                pill(label, valueStyle, keepTogether = true)
            }
            is ParamValue.Number -> {
                val unit = (param as? ParamDef.Number)?.unit?.takeIf { it.isNotEmpty() }?.let { " $it" } ?: ""
                pill(formatNumber(value.value.toDouble()) + unit, valueStyle, keepTogether = true)
            }
            is ParamValue.Bool -> {
                val label = (param as? ParamDef.Toggle)?.summaryWhenOn
                if (value.value && label != null) builder.append(tr(label))
            }
        }
    }

    val values = def.params.associate { it.key to (block.params[it.key] ?: it.defaultValue()) }
    val summaryText = tr(def.summary)
    var last = 0
    for (match in PLACEHOLDER.findAll(summaryText)) {
        builder.append(summaryText.substring(last, match.range.first))
        last = match.range.last + 1
        val param = def.param(match.groupValues[1]) ?: continue
        if (param.isHidden(values) || param.hiddenInSummaryWhen?.invoke(values) == true) continue
        appendValue(param, values.getValue(param.key))
    }
    builder.append(summaryText.substring(last).trimEnd())

    if (outputLabel != null && outputStyle != null) {
        builder.append("  → ")
        pill(outputLabel, outputStyle, keepTogether = true)
    }

    return builder.toAnnotatedString()
}
