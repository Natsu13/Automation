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
}

/**
 * Values automations remember between runs ("Uložit hodnotu" / "Načíst hodnotu"),
 * e.g. the id of the last item seen in an API, so the next run knows what is new.
 * Kept on the device only; not part of an export.
 */
object ValueStore {
    private const val PREFS = "stored_values"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun get(context: Context, key: String): String? = prefs(context).getString(key, null)

    fun set(context: Context, key: String, value: String) {
        prefs(context).edit().putString(key, value).apply()
    }

    fun remove(context: Context, key: String) {
        prefs(context).edit().remove(key).apply()
    }

    fun all(context: Context): Map<String, String> =
        prefs(context).all.mapNotNull { (k, v) -> (v as? String)?.let { k to it } }.toMap().toSortedMap()

    fun clear(context: Context) {
        prefs(context).edit().clear().apply()
    }
}
