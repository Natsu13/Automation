package cz.natsu.automation.engine

import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.Tag
import android.nfc.TagLostException
import android.nfc.tech.Ndef
import android.nfc.tech.NdefFormatable
import cz.natsu.automation.i18n.tr

/** What "Zapsat na NFC štítek" puts on a tag and how it gets there. */
object NfcTags {
    /** "https://…", "tel:…", "geo:…": stored as a link, anything else as a text. */
    private val link = Regex("[a-zA-Z][a-zA-Z0-9+.-]*:\\S+")

    /**
     * The record with [content] goes first; [appPackage] (an Android Application Record) after it
     * makes the phone hand the tag straight to that app instead of asking or opening a browser.
     */
    fun message(content: String, openHere: Boolean, appPackage: String): NdefMessage {
        val records = mutableListOf(
            if (link.matches(content)) NdefRecord.createUri(content) else NdefRecord.createTextRecord(null, content)
        )
        if (openHere) records += NdefRecord.createApplicationRecord(appPackage)
        return NdefMessage(records.toTypedArray())
    }

    /** Writes [message] on [tag] (formats an empty one first); returns the tag's ID. */
    fun write(tag: Tag, message: NdefMessage): String {
        val ndef = Ndef.get(tag)
        if (ndef != null) {
            ndef.use {
                it.connect()
                if (!it.isWritable) throw IllegalStateException(tr("štítek je jen pro čtení"))
                val size = message.toByteArray().size
                if (it.maxSize < size) {
                    throw IllegalStateException(tr("na štítek se to nevejde (%s z %s bajtů)", size, it.maxSize))
                }
                it.writeNdefMessage(message)
            }
        } else {
            val empty = NdefFormatable.get(tag) ?: throw IllegalStateException(tr("na tenhle druh štítku zapsat nejde"))
            empty.use {
                it.connect()
                it.format(message)
            }
        }
        return tag.id?.joinToString(":") { "%02X".format(it) }.orEmpty()
    }

    fun reason(e: Exception): String = when (e) {
        is IllegalStateException -> e.message ?: tr("zápis selhal")
        is TagLostException -> tr("štítek se vzdálil dřív, než se zápis dokončil")
        else -> tr("zápis selhal") + (e.message?.let { " ($it)" } ?: "")
    }
}
