package cz.natsu.automation.engine

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Fingerprint
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.VerifiedUser
import cz.natsu.automation.data.VarRef
import cz.natsu.automation.i18n.tr
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Values locked by a key in the phone's keystore that only a fingerprint (or face) unlocks.
 * The key never leaves the phone's secure hardware; a locked value is `bio1:<lock>:<iv + data>`
 * and is useless anywhere else. Adding a new fingerprint makes the key unusable (Android rule),
 * so locked values have to be locked again then.
 */
object BiometricLock {
    const val PREFIX = "bio1:"
    private const val STORE = "AndroidKeyStore"
    private val nameRule = Regex("[A-Za-z0-9_-]{1,40}")

    private fun alias(name: String) = "automation.bio.$name"

    private fun keyStore() = KeyStore.getInstance(STORE).apply { load(null) }

    fun checkName(name: String): String {
        val n = name.trim().ifEmpty { "hlavni" }
        if (!nameRule.matches(n)) throw BlockException(tr("název zámku smí mít jen písmena bez diakritiky, číslice, - a _"))
        return n
    }

    private fun create(name: String): SecretKey {
        val spec = KeyGenParameterSpec.Builder(alias(name), KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setUserAuthenticationRequired(true)
            .setInvalidatedByBiometricEnrollment(true)
            .apply {
                // Every use needs the finger again (no "unlocked for 30 s").
                if (Build.VERSION.SDK_INT >= 30) setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
            }
            .build()
        return try {
            KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, STORE).run {
                init(spec)
                generateKey()
            }
        } catch (e: Exception) {
            throw BlockException(tr("v telefonu není nastavený otisk prstu (nebo ho telefon nemá)"))
        }
    }

    /** Deletes the key of lock [name]; returns whether there was one. Values locked by it can never be unlocked again. */
    fun forget(name: String): Boolean {
        val store = keyStore()
        if (!store.containsAlias(alias(name))) return false
        store.deleteEntry(alias(name))
        return true
    }

    /** A cipher for locking (iv = null) or unlocking; it works only after the finger. */
    fun cipher(name: String, iv: ByteArray?): Cipher {
        val store = keyStore()
        val key = store.getKey(alias(name), null) as? SecretKey
            ?: if (iv == null) create(name)
            else throw BlockException(tr("zámek \"%s\" v telefonu není (smazaný, nebo s daty aplikace) – zamkni hodnotu znovu", name))
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        try {
            if (iv == null) cipher.init(Cipher.ENCRYPT_MODE, key) else cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
        } catch (e: KeyPermanentlyInvalidatedException) {
            store.deleteEntry(alias(name))
            throw BlockException(tr("otisky prstů v telefonu se změnily – zamčené hodnoty už nejdou odemknout, zamkni je znovu"))
        }
        return cipher
    }

    fun pack(name: String, cipher: Cipher, data: ByteArray): String =
        PREFIX + name + ":" + Base64.getEncoder().encodeToString(cipher.iv + data)

    /** The lock name, IV and data of a locked value. */
    fun unpack(text: String): Triple<String, ByteArray, ByteArray> {
        val t = text.trim()
        val parts = t.removePrefix(PREFIX).split(":")
        if (!t.startsWith(PREFIX) || parts.size != 2) throw BlockException(tr("tohle není hodnota zamčená otiskem (má začínat bio1:)"))
        val raw = try {
            Base64.getDecoder().decode(parts[1])
        } catch (e: IllegalArgumentException) {
            throw BlockException(tr("zamčená hodnota je poškozená"))
        }
        if (raw.size <= 12) throw BlockException(tr("zamčená hodnota je poškozená"))
        return Triple(checkName(parts[0]), raw.copyOfRange(0, 12), raw.copyOfRange(12, raw.size))
    }

    /** What the window answered, as a cipher ready to use, or an error the block ends with. */
    fun unlocked(result: AuthResult): Cipher? = when (result) {
        is AuthResult.Ok -> result.cipher
        AuthResult.Cancelled -> throw BlockException(tr("ověření otiskem zrušeno"))
        is AuthResult.Error -> throw BlockException(result.message)
    }
}

/** Fingerprint: lock a value, unlock it, or just check it is the owner. Each a block of its own. */
object BiometricBlocks {
    const val LOCK = "bio_lock"
    const val UNLOCK = "bio_unlock"
    const val VERIFY = "bio_verify"
    const val FORGET = "bio_forget"

    private fun titleParam(default: String) =
        ParamDef.Text("title", "Text v okně otisku", default = default).apply { hiddenInSummaryWhen = { true } }

    val all = listOf(
        BlockDef(
            type = LOCK,
            needsWindow = true,
            summary = "Zamknout otiskem {text}",
            title = "Zamknout otiskem prstu",
            description = "Zamkne hodnotu klíčem, který je jen v tomto telefonu a otevře ho jen tvůj otisk. " +
                    "Výsledek (bio1:…) si ulož blokem Uložit hodnotu – příště ho blok Odemknout otiskem prstu vrátí. " +
                    "Hodí se na klíč z hesla, ať heslo nemusíš psát pokaždé.",
            category = BlockCategory.Security,
            icon = Icons.Rounded.Fingerprint,
            params = listOf(
                ParamDef.Text("text", "Hodnota", defaultRef = VarRef.Input),
                ParamDef.Text("name", "Název zámku", default = "hlavni", plain = true).apply { hiddenInSummaryWhen = { true } },
                titleParam("Zamknout otiskem prstu"),
            ),
            outputName = "Zamčená hodnota",
        ) { block ->
            val value = text(block, "text")
            val name = BiometricLock.checkName(text(block, "name"))
            val ui = ui ?: throw BlockException(tr("otisk prstu jde ověřit jen v okně"))
            val cipher = BiometricLock.cipher(name, null)
            val ready = BiometricLock.unlocked(ui.authenticate(text(block, "title"), "", cipher, false)) ?: cipher
            BlockResult.Output(BiometricLock.pack(name, ready, ready.doFinal(value.toByteArray(Charsets.UTF_8))))
        },

        BlockDef(
            type = UNLOCK,
            needsWindow = true,
            summary = "Odemknout otiskem {text}",
            title = "Odemknout otiskem prstu",
            description = "Po přiložení prstu vrátí hodnotu zamčenou blokem Zamknout otiskem prstu. Zrušení nebo " +
                    "změněné otisky v telefonu jsou chyba – blokem Zkusit kolem se pak můžeš zeptat na heslo.",
            category = BlockCategory.Security,
            icon = Icons.Rounded.LockOpen,
            params = listOf(
                ParamDef.Text("text", "Zamčená hodnota", defaultRef = VarRef.Input),
                titleParam("Odemknout otiskem prstu"),
            ),
            outputName = "Odemčená hodnota",
        ) { block ->
            val (name, iv, data) = BiometricLock.unpack(text(block, "text"))
            val ui = ui ?: throw BlockException(tr("otisk prstu jde ověřit jen v okně"))
            val cipher = BiometricLock.cipher(name, iv)
            val ready = BiometricLock.unlocked(ui.authenticate(text(block, "title"), "", cipher, false)) ?: cipher
            val value = try {
                String(ready.doFinal(data), Charsets.UTF_8)
            } catch (e: javax.crypto.AEADBadTagException) {
                throw BlockException(tr("zamčená hodnota je poškozená"))
            }
            // Usually a key or a password: kept out of the log.
            markSecret(value)
            BlockResult.Output(value)
        },

        BlockDef(
            type = VERIFY,
            needsWindow = true,
            summary = "Ověřit otiskem prstu",
            title = "Ověřit otiskem prstu",
            description = "Zeptá se na otisk prstu (nebo obličej) a pokračuje, jen když sedí. Zrušení je chyba, " +
                    "kterou zachytí Zkusit. Třeba před tím, než automatizace otevře dveře nebo pošle peníze.",
            category = BlockCategory.Security,
            icon = Icons.Rounded.VerifiedUser,
            params = listOf(
                ParamDef.Text("title", "Text v okně otisku", default = "Potvrď, že jsi to ty"),
                ParamDef.Toggle("pin", "Povolit i PIN / gesto telefonu", summaryWhenOn = "nebo PIN"),
            ),
        ) { block ->
            val ui = ui ?: throw BlockException(tr("otisk prstu jde ověřit jen v okně"))
            BiometricLock.unlocked(ui.authenticate(text(block, "title"), "", null, bool(block, "pin")))
            BlockResult.None
        },

        BlockDef(
            type = FORGET,
            summary = "Smazat zámek otisku {name}",
            title = "Smazat zámek otisku",
            description = "Smaže z telefonu klíč zámku (podle názvu). Hodnoty jím zamčené už pak nepůjdou odemknout " +
                    "nikdy – smaž i jejich uložený text. Výstup je true, když zámek byl, jinak false.",
            category = BlockCategory.Security,
            icon = Icons.Rounded.DeleteForever,
            params = listOf(
                ParamDef.Text("name", "Název zámku", default = "hlavni", plain = true),
            ),
            outputName = "Zámek byl smazán",
        ) { block ->
            BlockResult.Output(BiometricLock.forget(BiometricLock.checkName(text(block, "name"))).toString())
        },
    )
}
