package cz.natsu.automation.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Secret values (API keys, tokens, passwords) used in automations by name.
 *
 * Values are encrypted with an AES key that lives in the Android Keystore and
 * never leaves it; the preferences file only holds the ciphertext. Automations
 * reference a secret by name ([VarRef.Secret]), so exporting / sharing an
 * automation never includes the value.
 */
object SecretStore {
    private const val PREFS = "secrets"
    private const val KEY_ALIAS = "automation_secrets"
    private const val PREFIX = "s:"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    /** Names of all stored secrets, sorted. */
    fun names(context: Context): List<String> =
        prefs(context).all.keys.filter { it.startsWith(PREFIX) }.map { it.removePrefix(PREFIX) }.sorted()

    fun contains(context: Context, name: String): Boolean = prefs(context).contains(PREFIX + name)

    fun set(context: Context, name: String, value: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val encrypted = cipher.doFinal(value.toByteArray())
        val stored = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" +
                Base64.encodeToString(encrypted, Base64.NO_WRAP)
        prefs(context).edit().putString(PREFIX + name, stored).apply()
    }

    /** The decrypted value, or null when there is no such secret (or it can't be decrypted any more). */
    fun get(context: Context, name: String): String? {
        val stored = prefs(context).getString(PREFIX + name, null) ?: return null
        return try {
            val (iv, data) = stored.split(":", limit = 2)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
            }
            String(cipher.doFinal(Base64.decode(data, Base64.NO_WRAP)))
        } catch (e: Exception) {
            null
        }
    }

    fun remove(context: Context, name: String) {
        prefs(context).edit().remove(PREFIX + name).apply()
    }

    /** Names of the secrets the steps refer to, in the order they first appear. */
    fun usedIn(blocks: List<Block>): List<String> = blocks.asSequence()
        .flatMap { it.params.values.asSequence() }
        .filterIsInstance<ParamValue.Rich>()
        .flatMap { it.segments.asSequence() }
        .mapNotNull { ((it as? Segment.Var)?.ref as? VarRef.Secret)?.name }
        .distinct().toList()

    /** The secrets the steps refer to that are not stored on this phone (an imported automation, a deleted secret). */
    fun missingIn(context: Context, blocks: List<Block>): List<String> = usedIn(blocks).filterNot { contains(context, it) }
}

/**
 * Values automations remember between runs ("Uložit hodnotu" / "Načíst hodnotu"),
 * e.g. the id of the last item seen in an API, so the next run knows what is new.
 * By default a value belongs to the automation that saved it, so two automations
 * can both use "last_state" without overwriting each other; a value can also be
 * shared by all automations. Kept on the device only; not part of an export.
 */
object ValueStore {
    private const val PREFS = "stored_values"
    /** Prefix of values that belong to one automation: "#<id>:<name>". Shared values have no prefix. */
    private val OWN = Regex("^#(\\d+):(.*)$", RegexOption.DOT_MATCHES_ALL)

    /** One stored value; [automationId] is null for a value shared by all automations. */
    data class Entry(val storageKey: String, val automationId: Long?, val name: String, val value: String)

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Where [name] is kept: per automation by default, or shared ([automationId] null). */
    fun keyOf(automationId: Long?, name: String): String = if (automationId == null) name else "#$automationId:$name"

    fun get(context: Context, automationId: Long?, name: String): String? =
        prefs(context).getString(keyOf(automationId, name), null)

    fun set(context: Context, automationId: Long?, name: String, value: String) {
        prefs(context).edit().putString(keyOf(automationId, name), value).apply()
    }

    fun remove(context: Context, automationId: Long?, name: String) {
        prefs(context).edit().remove(keyOf(automationId, name)).apply()
    }

    fun removeByKey(context: Context, storageKey: String) {
        prefs(context).edit().remove(storageKey).apply()
    }

    /** Drops the values of a deleted automation. */
    fun removeAutomation(context: Context, automationId: Long) {
        val prefix = "#$automationId:"
        val editor = prefs(context).edit()
        prefs(context).all.keys.filter { it.startsWith(prefix) }.forEach { editor.remove(it) }
        editor.apply()
    }

    fun all(context: Context): List<Entry> =
        prefs(context).all.mapNotNull { (key, value) ->
            val text = value as? String ?: return@mapNotNull null
            val own = OWN.matchEntire(key)
            if (own != null) Entry(key, own.groupValues[1].toLong(), own.groupValues[2], text)
            else Entry(key, null, key, text)
        }.sortedWith(compareBy<Entry>({ it.automationId ?: -1L }, { it.name }))

    fun clear(context: Context) {
        prefs(context).edit().clear().apply()
    }
}
