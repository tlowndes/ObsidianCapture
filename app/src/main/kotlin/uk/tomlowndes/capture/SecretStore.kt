package uk.tomlowndes.capture

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
 * The Claude API key, encrypted with a key that lives in the Android Keystore and never leaves the phone.
 * Only the encrypted bytes are kept in the app's preferences.
 */
class SecretStore(ctx: Context) {
    private val p = ctx.applicationContext.getSharedPreferences("secrets", Context.MODE_PRIVATE)

    var claudeKey: String?
        get() = p.getString(KEY, null)?.let { runCatching { decrypt(it) }.getOrNull() }
        set(v) {
            if (v.isNullOrBlank()) p.edit().remove(KEY).apply() else p.edit().putString(KEY, encrypt(v.trim())).apply()
        }

    /** "sk-ant-…a1b2" for showing in Settings. */
    fun maskedKey(): String? = claudeKey?.let { k -> if (k.length <= 10) "••••" else k.take(7) + "…" + k.takeLast(4) }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return gen.generateKey()
    }

    private fun encrypt(plain: String): String {
        val c = Cipher.getInstance(TRANSFORM).apply { init(Cipher.ENCRYPT_MODE, key()) }
        val out = c.iv + c.doFinal(plain.toByteArray())
        return Base64.encodeToString(out, Base64.NO_WRAP)
    }

    private fun decrypt(stored: String): String {
        val bytes = Base64.decode(stored, Base64.NO_WRAP)
        val c = Cipher.getInstance(TRANSFORM).apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes, 0, 12)) }
        return String(c.doFinal(bytes, 12, bytes.size - 12))
    }

    private companion object {
        const val KEY = "claude_key"
        const val ALIAS = "flight-deck-claude"
        const val TRANSFORM = "AES/GCM/NoPadding"
    }
}
