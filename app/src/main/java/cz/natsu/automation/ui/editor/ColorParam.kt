package cz.natsu.automation.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.PriorityHigh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import cz.natsu.automation.data.Segment
import cz.natsu.automation.engine.ImageTools
import cz.natsu.automation.i18n.tr

/** The colour a field holds, when it is plain text that is a colour. */
private sealed interface FieldColor {
    data object Empty : FieldColor
    /** Contains a variable: known only when the automation runs. */
    data object AtRun : FieldColor
    data object Invalid : FieldColor
    data class Known(val argb: Int) : FieldColor
}

private fun fieldColor(segments: List<Segment>): FieldColor {
    if (segments.any { it is Segment.Var }) return FieldColor.AtRun
    val text = segments.joinToString("") { (it as Segment.Text).text }
    if (text.isBlank()) return FieldColor.Empty
    return ImageTools.colorOrNull(text)?.let { FieldColor.Known(it) } ?: FieldColor.Invalid
}

private fun hex(argb: Int): String = "#%06X".format(argb and 0xFFFFFF)

/** A round preview of the colour next to the field; tapping it opens the picker. */
@Composable
fun ColorSwatchButton(segments: List<Segment>, onPick: (String) -> Unit) {
    val current = fieldColor(segments)
    var picking by remember { mutableStateOf(false) }
    val shape = CircleShape
    Box(
        Modifier
            .size(40.dp)
            .clip(shape)
            .background(
                when (current) {
                    is FieldColor.Known -> Color(current.argb)
                    else -> MaterialTheme.colorScheme.surfaceVariant
                }
            )
            .border(
                if (current == FieldColor.Invalid) 2.dp else 1.dp,
                if (current == FieldColor.Invalid) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline,
                shape,
            )
            .clickable { picking = true },
        contentAlignment = Alignment.Center,
    ) {
        when (current) {
            FieldColor.Invalid -> Icon(
                Icons.Rounded.PriorityHigh, tr("Není to barva"),
                tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp),
            )
            FieldColor.Empty, FieldColor.AtRun -> Icon(
                Icons.Rounded.Palette, tr("Vybrat barvu"),
                tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp),
            )
            is FieldColor.Known -> Unit
        }
    }
    if (picking) {
        ColorPickerDialog(
            initial = (current as? FieldColor.Known)?.argb ?: 0xFF43A047.toInt(),
            onDismiss = { picking = false },
            onPick = {
                picking = false
                onPick(hex(it))
            },
        )
    }
}

private val palette = listOf(
    0xFFFFFFFF, 0xFF9E9E9E, 0xFF000000, 0xFFE53935, 0xFFFB8C00, 0xFFFDD835,
    0xFF43A047, 0xFF00ACC1, 0xFF1E88E5, 0xFF3949AB, 0xFF8E24AA, 0xFFD81B60,
).map { it.toInt() }

@Composable
private fun ColorPickerDialog(initial: Int, onDismiss: () -> Unit, onPick: (Int) -> Unit) {
    val start = remember { FloatArray(3).also { android.graphics.Color.colorToHSV(initial, it) } }
    var hue by remember { mutableFloatStateOf(start[0]) }
    var saturation by remember { mutableFloatStateOf(start[1]) }
    var brightness by remember { mutableFloatStateOf(start[2]) }
    val argb = android.graphics.Color.HSVToColor(floatArrayOf(hue, saturation, brightness))
    var typed by remember { mutableStateOf(hex(initial)) }

    fun set(color: Int) {
        val hsv = FloatArray(3).also { android.graphics.Color.colorToHSV(color, it) }
        hue = hsv[0]; saturation = hsv[1]; brightness = hsv[2]
        typed = hex(color)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("Barva")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(56.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(argb))
                            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp))
                    )
                    Spacer(Modifier.width(12.dp))
                    OutlinedTextField(
                        value = typed,
                        onValueChange = { text ->
                            typed = text
                            ImageTools.colorOrNull(text)?.let { color ->
                                val hsv = FloatArray(3).also { android.graphics.Color.colorToHSV(color, it) }
                                hue = hsv[0]; saturation = hsv[1]; brightness = hsv[2]
                            }
                        },
                        singleLine = true,
                        isError = ImageTools.colorOrNull(typed) == null,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
                        modifier = Modifier.weight(1f),
                    )
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                ) {
                    for (option in palette) {
                        Box(
                            Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(Color(option))
                                .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
                                .clickable { set(option) },
                            contentAlignment = Alignment.Center,
                        ) {
                            if (hex(option) == hex(argb)) {
                                val light = Color(option).let { it.red * 0.3f + it.green * 0.59f + it.blue * 0.11f } > 0.6f
                                Icon(Icons.Rounded.Check, null, tint = if (light) Color.Black else Color.White, modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }
                ColorSlider(tr("Odstín"), hue, 0f..360f, Brush.horizontalGradient(
                    (0..6).map { Color(android.graphics.Color.HSVToColor(floatArrayOf(it * 60f, 1f, 1f))) }
                )) { hue = it; typed = hex(android.graphics.Color.HSVToColor(floatArrayOf(it, saturation, brightness))) }
                ColorSlider(tr("Sytost"), saturation, 0f..1f, Brush.horizontalGradient(
                    listOf(Color(android.graphics.Color.HSVToColor(floatArrayOf(hue, 0f, brightness))),
                        Color(android.graphics.Color.HSVToColor(floatArrayOf(hue, 1f, brightness))))
                )) { saturation = it; typed = hex(android.graphics.Color.HSVToColor(floatArrayOf(hue, it, brightness))) }
                ColorSlider(tr("Jas"), brightness, 0f..1f, Brush.horizontalGradient(
                    listOf(Color.Black, Color(android.graphics.Color.HSVToColor(floatArrayOf(hue, saturation, 1f))))
                )) { brightness = it; typed = hex(android.graphics.Color.HSVToColor(floatArrayOf(hue, saturation, it))) }
            }
        },
        confirmButton = { TextButton(onClick = { onPick(argb) }) { Text(tr("Použít")) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Zrušit")) } },
    )
}

@Composable
private fun ColorSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, track: Brush, onChange: (Float) -> Unit) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Box(contentAlignment = Alignment.Center) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp)
                    .height(10.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(track)
            )
            Slider(
                value = value,
                onValueChange = onChange,
                valueRange = range,
                colors = SliderDefaults.colors(
                    activeTrackColor = Color.Transparent,
                    inactiveTrackColor = Color.Transparent,
                    thumbColor = MaterialTheme.colorScheme.onSurface,
                ),
            )
        }
    }
}
