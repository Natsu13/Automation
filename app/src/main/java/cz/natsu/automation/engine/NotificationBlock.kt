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
import cz.natsu.automation.trigger.NotificationTimers
import androidx.compose.material.icons.rounded.HourglassBottom
import androidx.compose.material.icons.rounded.NotificationsOff
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

    private val tapOptions = listOf(
        ChoiceOption(Action.None.value, "Nic (jen se zavře)", "nic"),
        ChoiceOption(Action.Run.value, "Spustit automatizaci", "spustit automatizaci"),
        ChoiceOption(Action.Copy.value, "Kopírovat do schránky", "kopírovat"),
        ChoiceOption(Action.Url.value, "Otevřít odkaz", "otevřít odkaz"),
        ChoiceOption(Action.Share.value, "Sdílet text", "sdílet"),
    )

    private fun tapOf(values: Map<String, ParamValue>): Action =
        Action.of((values["tap_action"] as? ParamValue.Choice)?.value ?: Action.None.value)

    /** What a tap on the notification itself does – e.g. runs an automation that switches something. */
    private fun tapParams(): List<ParamDef> {
        fun hideUnless(vararg actions: Action): (Map<String, ParamValue>) -> Boolean = { tapOf(it) !in actions }
        return listOf(
            ParamDef.Choice("tap_action", "Po klepnutí na oznámení", tapOptions, default = Action.None.value).apply {
                hiddenInSummaryWhen = { true }
            },
            ParamDef.Automation("tap_automation", "Automatizace").apply {
                hiddenWhen = hideUnless(Action.Run)
                hiddenInSummaryWhen = { true }
            },
            ParamDef.Text("tap_input", "Vstup pro automatizaci", placeholder = "nic").apply {
                hiddenWhen = hideUnless(Action.Run)
                hiddenInSummaryWhen = { true }
            },
            ParamDef.Text("tap_text", "Text", defaultRef = VarRef.Input).apply {
                hiddenWhen = hideUnless(Action.Copy, Action.Share)
                hiddenInSummaryWhen = { true }
            },
            ParamDef.Text("tap_url", "Adresa", placeholder = "https://…").apply {
                hiddenWhen = hideUnless(Action.Url)
                hiddenInSummaryWhen = { true }
            },
        )
    }

    /** What happens for an action of a button or of the tap; keys are [prefix]_text, [prefix]_automation… */
    private suspend fun RunContext.pendingFor(
        block: cz.natsu.automation.data.Block, prefix: String, action: Action, id: Int, requestCode: Int, label: String,
    ): PendingIntent? = when (action) {
        Action.Copy, Action.Dismiss -> PendingIntent.getBroadcast(
            context, requestCode,
            NotificationButtonReceiver.intent(
                context, id, copy = if (action == Action.Copy) text(block, "${prefix}_text") else null
            ),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        Action.Run -> {
            val target = choice(block, "${prefix}_automation").toLongOrNull()
                ?: throw BlockException(tr("u tlačítka „%s“ není vybraná automatizace", label))
            activity(context, requestCode, TriggerActivity.notificationRunIntent(
                context, id, target, text(block, "${prefix}_input")
            ))
        }
        Action.Url -> {
            var url = text(block, "${prefix}_url").trim()
            if (url.isEmpty()) throw BlockException(tr("u tlačítka „%s“ chybí adresa", label))
            if (!url.contains(":")) url = "https://$url"
            activity(context, requestCode, TriggerActivity.notificationOpenIntent(context, id, url))
        }
        Action.Share -> activity(
            context, requestCode,
            TriggerActivity.notificationShareIntent(context, id, text(block, "${prefix}_text"))
        )
        Action.None -> null
    }

    /** At most 96 px: plenty for the status bar, and small to send to the system. */
    private fun statusBarIcon(bitmap: android.graphics.Bitmap): androidx.core.graphics.drawable.IconCompat {
        val scale = minOf(1f, 96f / maxOf(bitmap.width, bitmap.height))
        val small = if (scale < 1f) {
            android.graphics.Bitmap.createScaledBitmap(
                bitmap, (bitmap.width * scale).toInt().coerceAtLeast(1), (bitmap.height * scale).toInt().coerceAtLeast(1), true
            )
        } else bitmap
        return androidx.core.graphics.drawable.IconCompat.createWithBitmap(small)
    }

    private fun noCountdown(values: Map<String, ParamValue>): Boolean =
        (values["countdown"] as? ParamValue.Rich)?.segments.isNullOrEmpty()

    /** A named notification is the same one for every block; an unnamed one belongs to its block. */
    private suspend fun RunContext.idOf(block: cz.natsu.automation.data.Block): Int =
        text(block, "name").trim().let { if (it.isEmpty()) block.id.hashCode() else NotificationTimers.idOf(it) }

    private suspend fun RunContext.namedId(block: cz.natsu.automation.data.Block): Int =
        NotificationTimers.idOf(text(block, "name").trim().ifEmpty { throw BlockException(tr("chybí název oznámení")) })

    /** Small companions of the notification: closing it and asking how much of its countdown is left. */
    val more = listOf(
        BlockDef(
            type = "notification_cancel",
            summary = "Zrušit oznámení {name}",
            title = "Zrušit oznámení",
            description = "Zavře oznámení podle jeho názvu (pole Název oznámení v bloku Oznámení s tlačítky) " +
                    "a zruší jeho odpočet – co mělo po doběhnutí běžet, se nespustí.",
            category = BlockCategory.Other,
            icon = Icons.Rounded.NotificationsOff,
            params = listOf(ParamDef.Text("name", "Název oznámení")),
        ) { block ->
            val id = namedId(block)
            NotificationTimers.clear(context, id)
            NotificationManagerCompat.from(context).cancel(id)
            BlockResult.None
        },
        BlockDef(
            type = "notification_remaining",
            summary = "Zbývající čas odpočtu {name}",
            title = "Zbývající čas odpočtu",
            description = "Kolik sekund zbývá odpočtu v oznámení daného názvu (0, když žádný neběží). Hodí se k pozastavení: " +
                    "zapamatuj si zbytek, ukaž oznámení bez odpočtu a později ho se zbytkem pusť znovu.",
            category = BlockCategory.Other,
            icon = Icons.Rounded.HourglassBottom,
            params = listOf(ParamDef.Text("name", "Název oznámení")),
            outputName = "Zbývá sekund",
        ) { block ->
            BlockResult.Output(NotificationTimers.remaining(context, namedId(block)).toString())
        },
    )

    val def = BlockDef(
        type = TYPE,
        summary = "Oznámení {title} {text} s tlačítky {b1_label} {b2_label} {b3_label}",
        title = "Oznámení s tlačítky",
        description = "Ukáže oznámení až se třemi tlačítky: zkopírovat text, spustit jinou automatizaci, " +
            "otevřít odkaz nebo sdílet text. Texty se doplní při zobrazení, takže tlačítko zkopíruje to, " +
            "co automatizace spočítala. Může v něm běžet odpočet a po jeho doběhnutí se spustit automatizace. " +
            "Může mít i obrázek – vložený přímo do automatizace nebo vytvořený bloky Obrázky.",
        category = BlockCategory.Other,
        icon = Icons.Rounded.NotificationsActive,
        requirement = Requirement.PostNotifications,
        params = listOf(
            ParamDef.Text("title", "Nadpis", default = "Automatizace"),
            ParamDef.Text("text", "Text", defaultRef = VarRef.Input),
            ParamDef.Toggle("quiet", "Tiché (bez zvuku a vyskočení)", summaryWhenOn = " (tiché)"),
            ParamDef.Text(
                "image", "Obrázek", placeholder = "bez obrázku; vložený obrázek, soubor nebo https://", imagePicker = true,
            ).apply { hiddenInSummaryWhen = { true } },
            ParamDef.Choice(
                "small_icon", "Ikona ve stavovém řádku", listOf(
                    ChoiceOption("app", "Ikona aplikace"),
                    ChoiceOption("same", "Stejný obrázek jako v oznámení"),
                    ChoiceOption("other", "Jiný obrázek"),
                ),
            ).apply { hiddenInSummaryWhen = { true } },
            ParamDef.Text(
                "small_image", "Obrázek do stavového řádku",
                placeholder = "vložený obrázek, soubor nebo https:// (Android z něj kreslí jen tvar)", imagePicker = true,
            ).apply {
                hiddenWhen = { (it["small_icon"] as? ParamValue.Choice)?.value != "other" }
                hiddenInSummaryWhen = { true }
            },
            ParamDef.Text("name", "Název oznámení", placeholder = "nepovinný – stejný název oznámení nahradí").apply {
                hiddenInSummaryWhen = { true }
            },
            ParamDef.Text("countdown", "Odpočet", placeholder = "bez odpočtu; sekundy nebo 1:30").apply {
                hiddenInSummaryWhen = { true }
            },
            ParamDef.Automation("end_automation", "Po doběhnutí spustit").apply {
                hiddenWhen = ::noCountdown
                hiddenInSummaryWhen = { true }
            },
            ParamDef.Text("end_input", "Vstup pro automatizaci", placeholder = "nic").apply {
                hiddenWhen = ::noCountdown
                hiddenInSummaryWhen = { true }
            },
        ) + tapParams() + (1..BUTTONS).flatMap(::buttonParams),
    ) { block ->
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) {
            throw BlockException(tr("oznámení jsou pro aplikaci vypnutá"), Requirement.PostNotifications)
        }
        val quiet = bool(block, "quiet")
        ensureChannels(context)
        // One notification per block (or per name): showing it again replaces it.
        val id = idOf(block)
        val countdown = text(block, "countdown").trim().takeIf { it.isNotEmpty() }?.let {
            NotificationTimers.parse(it) ?: throw BlockException(tr("odpočet „%s“ není čas (sekundy nebo 1:30)", it))
        }?.takeIf { it > 0 }
        val end = countdown?.let { System.currentTimeMillis() + it * 1000 }
        val builder = NotificationCompat.Builder(context, if (quiet) CHANNEL_QUIET else CHANNEL_LOUD)
            .setSmallIcon(R.drawable.ic_tile)
            .setContentTitle(text(block, "title").ifBlank { "Automatizace" })
            .setAutoCancel(true)
            .setPriority(if (quiet) NotificationCompat.PRIORITY_LOW else NotificationCompat.PRIORITY_HIGH)
        if (end != null) {
            // Android draws the running time itself; nothing has to keep updating the notification.
            builder.setUsesChronometer(true).setChronometerCountDown(true).setWhen(end).setShowWhen(true)
                .setOnlyAlertOnce(true)
        }
        val body = text(block, "text")
        if (body.isNotBlank()) {
            builder.setContentText(body).setStyle(NotificationCompat.BigTextStyle().bigText(body))
        }

        val image = text(block, "image").trim()
        val large = if (image.isNotEmpty()) ImageTools.load(context, image).bitmap else null
        // In colour next to the text; the status bar icon is always one colour (Android draws only its shape).
        large?.let { builder.setLargeIcon(it) }
        when (choice(block, "small_icon")) {
            "same" -> large?.let { builder.setSmallIcon(statusBarIcon(it)) }
            "other" -> text(block, "small_image").trim().takeIf { it.isNotEmpty() }?.let {
                builder.setSmallIcon(statusBarIcon(ImageTools.load(context, it).bitmap))
            }
        }

        val tap = Action.of(choice(block, "tap_action"))
        pendingFor(block, "tap", tap, id, id * 4, tr("Po klepnutí na oznámení"))?.let { builder.setContentIntent(it) }

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
            val pending = pendingFor(block, "b$index", action, id, id * 4 + index, label) ?: continue
            builder.addAction(0, label, pending)
        }

        @Suppress("MissingPermission")
        NotificationManagerCompat.from(context).notify(id, builder.build())
        if (end != null) {
            NotificationTimers.set(
                context, id, end, choice(block, "end_automation").toLongOrNull(), text(block, "end_input")
            )
        } else {
            NotificationTimers.clear(context, id)
        }
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
