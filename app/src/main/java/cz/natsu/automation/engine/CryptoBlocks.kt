package cz.natsu.automation.engine

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.EnhancedEncryption
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.NoEncryption
import androidx.compose.material.icons.rounded.Password
import cz.natsu.automation.i18n.tr
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Encryption with a password: AES-256-GCM with a key made from the password by PBKDF2-HMAC-SHA256.
 *
 * - a key: `key1:<salt>:<rounds>:<key>` – made once ("Klíč z hesla"), then encrypting and
 *   decrypting with it is fast (making a key from a password is slow on purpose);
 * - an encrypted text: `enc1:<salt>:<rounds>:<iv + data>` – it names the salt and rounds of its
 *   key, so the same password makes the same key again from just the encrypted text.
 *
 * Plain Java (no Android), so the JVM tests check it.
 */
object Crypto {
    const val KEY_PREFIX = "key1:"
    const val DATA_PREFIX = "enc1:"
    const val DEFAULT_ROUNDS = 200_000
    private const val SALT_BYTES = 16
    private const val IV_BYTES = 12
    private const val KEY_BITS = 256

    private val random = SecureRandom()
    private val b64 = Base64.getEncoder()
    private val unb64 = Base64.getDecoder()

    class Key(val salt: ByteArray, val rounds: Int, val bytes: ByteArray) {
        val text: String get() = KEY_PREFIX + b64.encodeToString(salt) + ":" + rounds + ":" + b64.encodeToString(bytes)
        /** The part an encrypted text carries: `<salt>:<rounds>`. */
        val header: String get() = b64.encodeToString(salt) + ":" + rounds
    }

    fun newSalt(): ByteArray = ByteArray(SALT_BYTES).also(random::nextBytes)

    fun derive(password: String, salt: ByteArray, rounds: Int): Key {
        if (password.isEmpty()) throw BlockException(tr("heslo je prázdné"))
        val spec = PBEKeySpec(password.toCharArray(), salt, rounds, KEY_BITS)
        try {
            val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
            return Key(salt, rounds, bytes)
        } finally {
            spec.clearPassword()
        }
    }

    /** A key written by [Key.text]; null when [text] is not one. */
    fun parseKey(text: String): Key? {
        if (!text.startsWith(KEY_PREFIX)) return null
        val parts = text.removePrefix(KEY_PREFIX).trim().split(":")
        if (parts.size != 3) return null
        return try {
            Key(unb64.decode(parts[0]), parts[1].toInt(), unb64.decode(parts[2])).takeIf { it.bytes.size == KEY_BITS / 8 }
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    /**
     * Salt and rounds of a key or an encrypted text (`key1:…`, `enc1:…`), or of a bare salt
     * (Base64, rounds = [rounds]); null when [text] is none of them.
     */
    fun saltOf(text: String, rounds: Int = DEFAULT_ROUNDS): Pair<ByteArray, Int>? {
        val t = text.trim()
        val parts = when {
            t.startsWith(KEY_PREFIX) -> t.removePrefix(KEY_PREFIX).split(":")
            t.startsWith(DATA_PREFIX) -> t.removePrefix(DATA_PREFIX).split(":")
            else -> return try {
                unb64.decode(t).takeIf { it.size >= 8 }?.let { it to rounds }
            } catch (e: IllegalArgumentException) {
                null
            }
        }
        if (parts.size < 2) return null
        return try {
            unb64.decode(parts[0]) to parts[1].toInt()
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    fun encrypt(plain: String, key: Key): String {
        val iv = ByteArray(IV_BYTES).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key.bytes, "AES"), GCMParameterSpec(128, iv))
        val header = DATA_PREFIX + key.header
        // The header is checked too: changing the salt or rounds in the text breaks it like changing the data.
        cipher.updateAAD(header.toByteArray())
        val data = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return header + ":" + b64.encodeToString(iv + data)
    }

    fun decrypt(text: String, key: Key): String {
        val t = text.trim()
        if (!t.startsWith(DATA_PREFIX)) throw BlockException(tr("tohle není zašifrovaný text (má začínat enc1:)"))
        val cut = t.lastIndexOf(':')
        val header = t.substring(0, cut)
        val (salt, rounds) = saltOf(t) ?: throw BlockException(tr("zašifrovaný text je poškozený"))
        if (!salt.contentEquals(key.salt) || rounds != key.rounds) {
            throw BlockException(tr("text je zašifrovaný jiným klíčem (jiná sůl) – udělej klíč z hesla s tímto textem jako solí"))
        }
        val raw = try {
            unb64.decode(t.substring(cut + 1))
        } catch (e: IllegalArgumentException) {
            throw BlockException(tr("zašifrovaný text je poškozený"))
        }
        if (raw.size <= IV_BYTES) throw BlockException(tr("zašifrovaný text je poškozený"))
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key.bytes, "AES"), GCMParameterSpec(128, raw, 0, IV_BYTES))
        cipher.updateAAD(header.toByteArray())
        return try {
            String(cipher.doFinal(raw, IV_BYTES, raw.size - IV_BYTES), Charsets.UTF_8)
        } catch (e: AEADBadTagException) {
            throw BlockException(tr("špatné heslo nebo poškozená data"))
        }
    }

    /** [secret] is a key (`key1:…`) or a password; a password gets a new salt every time. */
    fun encryptWith(plain: String, secret: String): String =
        encrypt(plain, parseKey(secret) ?: derive(secret, newSalt(), DEFAULT_ROUNDS))

    /** [secret] is a key (`key1:…`) or a password; the password makes its key with the salt in [text]. */
    fun decryptWith(text: String, secret: String): String {
        val key = parseKey(secret) ?: run {
            val (salt, rounds) = saltOf(text.trim().takeIf { it.startsWith(DATA_PREFIX) } ?: "")
                ?: throw BlockException(tr("tohle není zašifrovaný text (má začínat enc1:)"))
            derive(secret, salt, rounds)
        }
        return decrypt(text, key)
    }

    const val LOWER = "abcdefghijkmnopqrstuvwxyz"
    const val UPPER = "ABCDEFGHJKLMNPQRSTUVWXYZ"
    const val DIGITS = "23456789"
    const val SYMBOLS = "!#$%&*+-=?@_~.,:;"

    /** A random password of [length] characters with at least one from every set used. */
    fun password(length: Int, sets: List<String>): String {
        val used = sets.filter { it.isNotEmpty() }
        if (used.isEmpty()) throw BlockException(tr("vyber aspoň jeden druh znaků"))
        if (length < used.size) throw BlockException(tr("heslo je moc krátké na všechny druhy znaků"))
        val all = used.joinToString("")
        val chars = MutableList(length) { all[random.nextInt(all.length)] }
        // One of every kind, on places picked at random.
        val places = (0 until length).shuffled(random).take(used.size)
        for ((set, place) in used.zip(places)) chars[place] = set[random.nextInt(set.length)]
        return chars.joinToString("")
    }
}

/** Password, key, encrypting, decrypting and a random password: each a block of its own. */
object CryptoBlocks {
    const val DERIVE_KEY = "derive_key"
    const val ENCRYPT = "encrypt"
    const val DECRYPT = "decrypt"
    const val PASSWORD = "random_password"

    val all = listOf(
        BlockDef(
            type = DERIVE_KEY,
            summary = "Klíč z hesla {password}",
            title = "Klíč z hesla",
            description = "Udělá z hesla klíč pro Zašifrovat a Dešifrovat. Je to schválně pomalé (kvůli hádání hesel), " +
                    "takže klíč udělej jednou a šifruj s ním všechno. Sůl: prázdná = nová; nebo dej dříve zašifrovaný " +
                    "text a vznikne z hesla stejný klíč jako tehdy.",
            category = BlockCategory.Security,
            icon = Icons.Rounded.Key,
            params = listOf(
                ParamDef.Text("password", "Heslo"),
                ParamDef.Text("salt", "Sůl nebo zašifrovaný text", placeholder = "prázdné = nová sůl").apply {
                    hiddenInSummaryWhen = { true }
                },
                ParamDef.Number("rounds", "Počet kol (síla)", 10_000f, 2_000_000f, Crypto.DEFAULT_ROUNDS.toFloat(), step = 10_000f).apply {
                    hiddenInSummaryWhen = { true }
                },
            ),
            outputName = "Klíč",
        ) { block ->
            val password = text(block, "password")
            markSecret(password)
            val from = text(block, "salt").trim()
            val rounds = number(block, "rounds").toInt().coerceAtLeast(10_000)
            val (salt, keyRounds) = if (from.isEmpty()) Crypto.newSalt() to rounds
            else Crypto.saltOf(from, rounds) ?: throw BlockException(tr("sůl ani zašifrovaný text to není"))
            val key = Crypto.derive(password, salt, keyRounds)
            markSecret(key.text)
            BlockResult.Output(key.text)
        },

        BlockDef(
            type = ENCRYPT,
            summary = "Zašifrovat {text}",
            title = "Zašifrovat",
            description = "Zašifruje text klíčem z bloku Klíč z hesla (nebo rovnou heslem – to je pomalejší). " +
                    "Výstup je text enc1:…, který jde uložit do souboru nebo poslat dál.",
            category = BlockCategory.Security,
            icon = Icons.Rounded.EnhancedEncryption,
            params = listOf(
                ParamDef.Text("text", "Text", defaultRef = cz.natsu.automation.data.VarRef.Input),
                ParamDef.Text("key", "Klíč nebo heslo").apply { hiddenInSummaryWhen = { true } },
            ),
            outputName = "Zašifrovaný text",
        ) { block ->
            val secret = text(block, "key")
            if (secret.isEmpty()) throw BlockException(tr("chybí klíč nebo heslo"))
            markSecret(secret)
            BlockResult.Output(Crypto.encryptWith(text(block, "text"), secret))
        },

        BlockDef(
            type = DECRYPT,
            summary = "Dešifrovat {text}",
            title = "Dešifrovat",
            description = "Vrátí původní text ze zašifrovaného (enc1:…). Se špatným heslem nebo klíčem skončí chybou – " +
                    "blokem Zkusit kolem tak zjistíš, jestli heslo sedí.",
            category = BlockCategory.Security,
            icon = Icons.Rounded.NoEncryption,
            params = listOf(
                ParamDef.Text("text", "Zašifrovaný text", defaultRef = cz.natsu.automation.data.VarRef.Input),
                ParamDef.Text("key", "Klíč nebo heslo").apply { hiddenInSummaryWhen = { true } },
            ),
            outputName = "Dešifrovaný text",
        ) { block ->
            val secret = text(block, "key")
            if (secret.isEmpty()) throw BlockException(tr("chybí klíč nebo heslo"))
            markSecret(secret)
            BlockResult.Output(Crypto.decryptWith(text(block, "text"), secret))
        },

        BlockDef(
            type = PASSWORD,
            summary = "Náhodné heslo, {length} znaků",
            title = "Náhodné heslo",
            description = "Vymyslí silné náhodné heslo. Znaky, které se pletou (l, I, O, 0, 1), vynechá.",
            category = BlockCategory.Security,
            icon = Icons.Rounded.Password,
            params = listOf(
                ParamDef.Number("length", "Délka", 4f, 128f, 20f, unit = "znaků"),
                ParamDef.Toggle("lower", "Malá písmena", default = true),
                ParamDef.Toggle("upper", "Velká písmena", default = true),
                ParamDef.Toggle("digits", "Číslice", default = true),
                ParamDef.Toggle("symbols", "Symboly", default = true),
            ),
            outputName = "Heslo",
        ) { block ->
            val sets = buildList {
                if (bool(block, "lower")) add(Crypto.LOWER)
                if (bool(block, "upper")) add(Crypto.UPPER)
                if (bool(block, "digits")) add(Crypto.DIGITS)
                if (bool(block, "symbols")) add(Crypto.SYMBOLS)
            }
            BlockResult.Output(Crypto.password(number(block, "length").toInt(), sets))
        },
    )
}
