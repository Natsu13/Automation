package cz.natsu.automation.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.AutoFixHigh
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.TextFields
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import cz.natsu.automation.engine.BlockCategory
import cz.natsu.automation.ui.theme.Accent
import cz.natsu.automation.ui.theme.AccentHue

val BlockCategory.hue: AccentHue
    get() = when (this) {
        BlockCategory.Text -> AccentHue.Blue
        BlockCategory.Numbers -> AccentHue.Teal
        BlockCategory.Clipboard -> AccentHue.Teal
        BlockCategory.Share -> AccentHue.Teal
        BlockCategory.Data -> AccentHue.Blue
        BlockCategory.Images -> AccentHue.Pink
        BlockCategory.Dialogs -> AccentHue.Purple
        BlockCategory.Variables -> AccentHue.Purple
        BlockCategory.Control -> AccentHue.Orange
        BlockCategory.Device -> AccentHue.Green
        BlockCategory.Apps -> AccentHue.Green
        BlockCategory.Other -> AccentHue.Pink
    }

fun automationIcon(key: String): ImageVector = IconCatalog.icon(key)

fun automationHue(key: String): AccentHue = when (key) {
    "paste" -> AccentHue.Teal
    "text" -> AccentHue.Blue
    "translate" -> AccentHue.Purple
    "link" -> AccentHue.Blue
    "volume" -> AccentHue.Green
    "phone" -> AccentHue.Green
    "star" -> AccentHue.Orange
    "code" -> AccentHue.Purple
    "magic" -> AccentHue.Pink
    "bolt" -> AccentHue.Orange
    // The other icons get a colour of their own that stays the same.
    else -> AccentHue.entries.let { hues -> hues[(key.hashCode() and 0x7fffffff) % hues.size] }
}

@Composable
fun IconBadge(
    icon: ImageVector,
    accent: Accent,
    modifier: Modifier = Modifier,
    size: Dp = 36.dp,
) {
    Box(
        modifier = modifier
            .size(size)
            .background(accent.container, RoundedCornerShape(size * 0.3f)),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = null, tint = accent.content, modifier = Modifier.size(size * 0.55f))
    }
}
