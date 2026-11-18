package cz.natsu.automation.ui.editor

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.EmojiSymbols
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilledIconToggleButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import cz.natsu.automation.data.ParamValue
import cz.natsu.automation.data.Segment
import cz.natsu.automation.data.richText
import cz.natsu.automation.engine.EmbeddedImage
import cz.natsu.automation.engine.ParamDef
import cz.natsu.automation.i18n.tr
import cz.natsu.automation.ui.common.IconCatalog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The data:image text of a value that is just an image stored in the automation, else null. */
fun embeddedImageOf(value: ParamValue?): String? {
    val segments = (value as? ParamValue.Rich)?.segments ?: return null
    val text = segments.singleOrNull() as? Segment.Text ?: return null
    return text.text.takeIf { EmbeddedImage.isEmbedded(it) }
}

/** An image stored in the automation: a preview instead of the long base64 text. */
@Composable
fun EmbeddedImageField(param: ParamDef, data: String, onRemove: () -> Unit) {
    val bitmap = remember(data) { EmbeddedImage.bitmap(data)?.asImageBitmap() }
    Column {
        Text(
            tr(param.label),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp, bottom = 4.dp)
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(64.dp)
                    .clip(RoundedCornerShape(12.dp))
                    // A checker-like grey so a white icon is visible.
                    .background(Color(0xFF5F6368))
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center,
            ) {
                if (bitmap != null) Image(bitmap, contentDescription = null, modifier = Modifier.size(56.dp))
                else Icon(Icons.Rounded.Image, null)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(tr("Obrázek uložený v automatizaci"), style = MaterialTheme.typography.bodyMedium)
                Text(
                    (bitmap?.let { "${it.width} × ${it.height} px · " } ?: "") + "${data.length / 1024 + 1} kB",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onRemove) {
                Icon(Icons.Rounded.Close, null, Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text(tr("Odebrat"))
            }
        }
    }
}

/** Chips under an image field: put a picture from the gallery or an icon into the automation. */
@Composable
fun ImagePickerRow(onPick: (ParamValue) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var choosingIcon by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val data = withContext(Dispatchers.IO) {
                runCatching {
                    val bitmap = context.contentResolver.openInputStream(uri)?.use { input ->
                        BitmapFactory.decodeStream(input, null, BitmapFactory.Options().apply { inSampleSize = 1 })
                    }
                    bitmap?.let { EmbeddedImage.encode(it) }
                }.getOrNull()
            }
            failed = data == null
            if (data != null) onPick(richText(data))
        }
    }
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.horizontalScroll(rememberScrollState()),
    ) {
        AssistChip(
            onClick = { gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
            label = { Text(tr("Vložit obrázek")) },
            leadingIcon = { Icon(Icons.Rounded.Image, null, Modifier.size(18.dp)) },
        )
        AssistChip(
            onClick = { choosingIcon = true },
            label = { Text(tr("Vložit ikonu")) },
            leadingIcon = { Icon(Icons.Rounded.EmojiSymbols, null, Modifier.size(18.dp)) },
        )
    }
    if (failed) {
        Text(
            tr("Obrázek nejde načíst."),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
    if (choosingIcon) {
        IconImageDialog(
            onDismiss = { choosingIcon = false },
            onPick = { bitmap ->
                choosingIcon = false
                onPick(richText(EmbeddedImage.encode(bitmap, EmbeddedImage.ICON_SIZE)))
            },
        )
    }
}

private val iconColors = listOf(
    0xFFFFFFFF, 0xFF000000, 0xFFE53935, 0xFFFB8C00, 0xFFFDD835, 0xFF43A047, 0xFF1E88E5, 0xFF8E24AA,
)

/** Picks an icon of the catalog and its colour; it is drawn into a picture. */
@Composable
private fun IconImageDialog(onDismiss: () -> Unit, onPick: (Bitmap) -> Unit) {
    var query by remember { mutableStateOf("") }
    var color by remember { mutableIntStateOf(0xFFFFFFFF.toInt()) }
    val found = remember(query) { IconCatalog.search(query) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("Ikona do obrázku")) },
        text = {
            Column {
                Text(
                    tr("Bílou ikonu můžeš později obarvit blokem Upravit obrázek (Obarvit) podle stavu."),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.padding(vertical = 8.dp).horizontalScroll(rememberScrollState()),
                ) {
                    for (option in iconColors) {
                        val argb = option.toInt()
                        Box(
                            Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(Color(argb))
                                .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
                                .clickable { color = argb },
                            contentAlignment = Alignment.Center,
                        ) {
                            if (color == argb) {
                                Icon(
                                    Icons.Rounded.Check, null,
                                    tint = if (argb == 0xFFFFFFFF.toInt() || argb == 0xFFFDD835.toInt()) Color.Black else Color.White,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                    }
                }
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text(tr("např. dům, hudba, wifi")) },
                    leadingIcon = { Icon(Icons.Rounded.Search, null) },
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (found.isEmpty()) Text(tr("Nic nenalezeno."), Modifier.padding(top = 16.dp))
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(52.dp),
                    modifier = Modifier.fillMaxWidth().height(280.dp).padding(top = 8.dp),
                ) {
                    items(found, key = { it.key }) { entry ->
                        FilledIconToggleButton(
                            checked = false,
                            onCheckedChange = { onPick(EmbeddedImage.fromIcon(entry.icon, color)) },
                            shape = CircleShape,
                            modifier = Modifier.padding(2.dp),
                        ) { Icon(entry.icon, contentDescription = entry.key) }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(tr("Zavřít")) } },
    )
}
