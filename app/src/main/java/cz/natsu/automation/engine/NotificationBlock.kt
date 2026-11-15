package cz.natsu.automation.engine

import cz.natsu.automation.i18n.tr

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import cz.natsu.automation.AutomationApplication
import cz.natsu.automation.R
import cz.natsu.automation.data.ParamValue
import cz.natsu.automation.data.VarRef
import cz.natsu.automation.trigger.NotificationButtonReceiver
import cz.natsu.automation.trigger.TriggerActivity

/**
 * "Oznámení s tlačítky": a notification with up to three buttons. Each button does
 * one thing when tapped – copies text, runs another automation, opens a link, shares
 * text or just closes the notification. Texts are filled in (variables resolved)
 * when the notification is shown, so a button copies what the automation computed.
 */
object NotificationBlock {
    const val TYPE = "notification"
    const val BUTTONS = 3

    private const val CHANNEL_LOUD = "automation_notifications"
    private const val CHANNEL_QUIET = "automation_notifications_quiet"

    enum class Action(val value: String, val label: String) {
        None("none", "Žádné tlačítko"),
        Copy("copy", "Kopírovat do schránky"),
        Run("run", "Spustit automatizaci"),
        Url("url", "Otevřít odkaz"),
        Share("share", "Sdílet text"),
        Dismiss("dismiss", "Jen zavřít oznámení");

        companion object {
            fun of(value: String) = entries.firstOrNull { it.value == value } ?: None
        }
    }

    private val actionOptions = Action.entries.map { ChoiceOption(it.value, it.label) }

    private fun actionOf(values: Map<String, ParamValue>, index: Int): Action =
        Action.of((values["b${index}_action"] as? ParamValue.Choice)?.value ?: if (index == 1) "copy" else "none")

    /** Button [index] is offered once the previous one is used. */
    private fun buttonHidden(values: Map<String, ParamValue>, index: Int): Boolean =
        (1 until index).any { actionOf(values, it) == Action.None }

    private fun buttonParams(index: Int): List<ParamDef> {
        val first = index == 1
        fun hideUnless(vararg actions: Action): (Map<String, ParamValue>) -> Boolean = { values ->
            buttonHidden(values, index) || actionOf(values, index) !in actions
        }
        return listOf(
            ParamDef.Choice(
                "b${index}_action", "$index. tlačítko", actionOptions,
                default = if (first) Action.Copy.value else Action.None.value,
            ).apply {
                hiddenWhen = { buttonHidden(it, index) }
                hiddenInSummaryWhen = { true }
            },
            ParamDef.Text(
                "b${index}_label", "Popisek tlačítka",
                default = if (first) "Kopírovat" else "", placeholder = "prázdné = podle akce",
            ).apply {
                hiddenWhen = { buttonHidden(it, index) || actionOf(it, index) == Action.None }
            },
            ParamDef.Text(
                "b${index}_text", "Text",
                default = "", defaultRef = if (first) VarRef.Input else null,
            ).apply { hiddenWhen = hideUnless(Action.Copy, Action.Share) },
            ParamDef.Automation("b${index}_automation", "Automatizace").apply {
                hiddenWhen = hideUnless(Action.Run)
            },
            ParamDef.Text("b${index}_input", "Vstup pro automatizaci", placeholder = "nic").apply {
                hiddenWhen = hideUnless(Action.Run)
            },
            ParamDef.Text("b${index}_url", "Adresa", placeholder = "https://…").apply {
                hiddenWhen = hideUnless(Action.Url)
            },
        )
    }

    val def = BlockDef(
        type = TYPE,
        summary = "Oznámení {title} {text} s tlačítky {b1_label} {b2_label} {b3_label}",
        title = "Oznámení s tlačítky",
        description = "Ukáže oznámení až se třemi tlačítky: zkopírovat text, spustit jinou automatizaci, " +
            "otevřít odkaz nebo sdílet text. Texty se doplní při zobrazení, takže tlačítko zkopíruje to, " +
            "co automatizace spočítala.",
        category = BlockCategory.Other,
        icon = Icons.Rounded.NotificationsActive,
        requirement = Requirement.PostNotifications,
        params = listOf(
            ParamDef.Text("title", "Nadpis", default = "Automatizace"),
            ParamDef.Text("text", "Text", defaultRef = VarRef.Input),
            ParamDef.Toggle("quiet", "Tiché (bez zvuku a vyskočení)", summaryWhenOn = " (tiché)"),
        ) + (1..BUTTONS).flatMap(::buttonParams),
    ) { block ->
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) {
            throw BlockException(tr("oznámení jsou pro aplikaci vypnutá"), Requirement.PostNotifications)
        }
        val quiet = bool(block, "quiet")
        ensureChannels(context)
        // One notification per block: running the automation again replaces it.
        val id = block.id.hashCode()
        val builder = NotificationCompat.Builder(context, if (quiet) CHANNEL_QUIET else CHANNEL_LOUD)
            .setSmallIcon(R.drawable.ic_tile)
            .setContentTitle(text(block, "title").ifBlank { "Automatizace" })
            .setAutoCancel(true)
            .setPriority(if (quiet) NotificationCompat.PRIORITY_LOW else NotificationCompat.PRIORITY_HIGH)
        val body = text(block, "text")
        if (body.isNotBlank()) {
            builder.setContentText(body).setStyle(NotificationCompat.BigTextStyle().bigText(body))
        }

        val values = Blocks.get(TYPE)!!.params.associate { it.key to (block.params[it.key] ?: it.defaultValue()) }
        for (index in 1..BUTTONS) {
            if (buttonHidden(values, index)) break
            val action = actionOf(values, index)
            if (action == Action.None) continue
            val label = text(block, "b${index}_label").ifBlank {
                // A run button without a label is named after the automation it runs.
                choice(block, "b${index}_automation").toLongOrNull()
                    ?.takeIf { action == Action.Run }
                    ?.let { (context.applicationContext as AutomationApplication).repository.get(it)?.name }
                    ?: action.label
            }
            val requestCode = id * 4 + index
            val pending = when (action) {
                Action.Copy, Action.Dismiss -> PendingIntent.getBroadcast(
                    context, requestCode,
                    NotificationButtonReceiver.intent(
                        context, id, copy = if (action == Action.Copy) text(block, "b${index}_text") else null
                    ),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
                Action.Run -> {
                    val target = choice(block, "b${index}_automation").toLongOrNull()
                        ?: throw BlockException(tr("u tlačítka „%s“ není vybraná automatizace", label))
                    activity(context, requestCode, TriggerActivity.notificationRunIntent(
                        context, id, target, text(block, "b${index}_input")
                    ))
                }
                Action.Url -> {
                    var url = text(block, "b${index}_url").trim()
                    if (url.isEmpty()) throw BlockException(tr("u tlačítka „%s“ chybí adresa", label))
                    if (!url.contains(":")) url = "https://$url"
                    activity(context, requestCode, TriggerActivity.notificationOpenIntent(context, id, url))
                }
                Action.Share -> activity(
                    context, requestCode,
                    TriggerActivity.notificationShareIntent(context, id, text(block, "b${index}_text"))
                )
                Action.None -> continue
            }
            builder.addAction(0, label, pending)
        }

        @Suppress("MissingPermission")
        NotificationManagerCompat.from(context).notify(id, builder.build())
        BlockResult.None
    }

    private fun activity(context: Context, requestCode: Int, intent: Intent): PendingIntent =
        PendingIntent.getActivity(
            context, requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun ensureChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_LOUD) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_LOUD, "Oznámení z automatizací", NotificationManager.IMPORTANCE_HIGH)
            )
        }
        if (manager.getNotificationChannel(CHANNEL_QUIET) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_QUIET, "Tichá oznámení z automatizací", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }
}
