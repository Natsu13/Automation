package cz.natsu.automation.trigger

import cz.natsu.automation.i18n.tr

import android.content.Context
import android.util.Log
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import cz.natsu.automation.R
import cz.natsu.automation.data.AutomationEntity

/**
 * Publishes automations with the share trigger as sharing shortcuts, so they show up
 * directly in the Android share sheet (and in the launcher long-press menu).
 */
object ShortcutPublisher {
    /** Must match the category in res/xml/shortcuts.xml. */
    private const val SHARE_CATEGORY = "cz.natsu.automation.category.TEXT_SHARE_TARGET"
    private const val PREFIX = "automation_"

    fun automationId(shortcutId: String): Long? =
        shortcutId.removePrefix(PREFIX).takeIf { shortcutId.startsWith(PREFIX) }?.toLongOrNull()

    fun publish(context: Context, automations: List<AutomationEntity>) {
        try {
            val max = ShortcutManagerCompat.getMaxShortcutCountPerActivity(context).coerceAtLeast(1)
            val shortcuts = automations
                .filter { it.triggerShare }
                .take(max)
                .mapIndexed { rank, automation ->
                    ShortcutInfoCompat.Builder(context, PREFIX + automation.id)
                        .setShortLabel(automation.name.take(25).ifBlank { tr("Automatizace") })
                        .setLongLabel(automation.name.ifBlank { tr("Automatizace") })
                        .setIcon(IconCompat.createWithResource(context, R.drawable.ic_shortcut))
                        .setIntent(TriggerActivity.runIntent(context, automation.id))
                        .setCategories(setOf(SHARE_CATEGORY))
                        .setLongLived(true)
                        .setRank(rank)
                        .build()
                }
            ShortcutManagerCompat.setDynamicShortcuts(context, shortcuts)
        } catch (e: Exception) {
            Log.w("ShortcutPublisher", "Could not publish shortcuts", e)
        }
    }
}
