package com.jarvis.app.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Keeps your Claude API key encrypted on the phone.
 *
 * The encryption key lives inside Android Keystore (secure hardware on most
 * phones) and can never be copied out, so even a copy of the app's files is
 * useless without this exact phone.
 */
class SecretStore(context: Context) {
    private val prefs = context.getSharedPreferences("jarvis_secrets", Context.MODE_PRIVATE)

    @Volatile
    private var cachedKey: String? = null

    @Volatile
    private var loaded = false

    suspend fun claudeKey(): String? {
        if (loaded) return cachedKey
        return withContext(Dispatchers.IO) {
            val stored = prefs.getString(PREF_CLAUDE, null)
            cachedKey = stored?.let { runCatching { decrypt(it) }.getOrNull() }
            loaded = true
            cachedKey
        }
    }

    suspend fun setClaudeKey(value: String?) = withContext(Dispatchers.IO) {
        val clean = value?.trim()?.takeIf { it.isNotEmpty() }
        prefs.edit().apply {
            if (clean == null) remove(PREF_CLAUDE) else putString(PREF_CLAUDE, encrypt(clean))
        }.apply()
        cachedKey = clean
        loaded = true
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val iv = Base64.encodeToString(cipher.iv, Base64.NO_WRAP)
        val data = Base64.encodeToString(cipher.doFinal(plain.toByteArray()), Base64.NO_WRAP)
        return "$iv:$data"
    }

    private fun decrypt(stored: String): String {
        val (iv, data) = stored.split(":", limit = 2)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
        return String(cipher.doFinal(Base64.decode(data, Base64.NO_WRAP)))
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "jarvis_secrets_v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val PREF_CLAUDE = "claude_api_key"
    }
}
