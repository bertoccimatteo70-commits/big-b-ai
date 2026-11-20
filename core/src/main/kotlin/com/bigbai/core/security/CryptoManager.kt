package com.bigbai.core.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Owns every long-lived key used by the app.
 *
 *  - AES-256-GCM keys live in Android Keystore and never leave it.
 *  - The database passphrase is random, wrapped by a Keystore key and stored in
 *    private preferences. It is never written in clear.
 *  - Backups use a user passphrase (PBKDF2-HMAC-SHA256) so they can be restored
 *    on a different device; Keystore keys are device-bound by design.
 */
class CryptoManager(private val context: Context) {

    private val keyStore: KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun key(alias: String, create: Boolean): SecretKey? {
        keyStore.getKey(alias, null)?.let { return it as SecretKey }
        if (!create) return null
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }

    fun encrypt(plain: String, alias: String = DEFAULT_ALIAS): String {
        val secretKey = key(alias, create = true) ?: error("Keystore key unavailable")
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey)
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(iv, Base64.NO_WRAP) + ":" +
            Base64.encodeToString(ciphertext, Base64.NO_WRAP)
    }

    fun decrypt(payload: String, alias: String = DEFAULT_ALIAS): String? = runCatching {
        val (ivPart, dataPart) = payload.split(":", limit = 2)
        val secretKey = key(alias, create = false) ?: return null
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            secretKey,
            GCMParameterSpec(128, Base64.decode(ivPart, Base64.NO_WRAP)),
        )
        String(cipher.doFinal(Base64.decode(dataPart, Base64.NO_WRAP)), Charsets.UTF_8)
    }.getOrNull()

    /** 32 random bytes used as the SQLCipher passphrase, wrapped at rest. */
    fun databasePassphrase(): ByteArray {
        val stored = prefs.getString(KEY_DB_PASSPHRASE, null)
        if (stored != null) {
            decrypt(stored, DB_KEY_ALIAS)?.let { return Base64.decode(it, Base64.NO_WRAP) }
        }
        val fresh = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val wrapped = encrypt(Base64.encodeToString(fresh, Base64.NO_WRAP), DB_KEY_ALIAS)
        prefs.edit().putString(KEY_DB_PASSPHRASE, wrapped).apply()
        return fresh
    }

    /** Key used for encrypted local exports and attachment blobs. */
    fun localSecret(): ByteArray = databasePassphrase()

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val DEFAULT_ALIAS = "bigbai.local.v1"
        private const val DB_KEY_ALIAS = "bigbai.db.v1"
        private const val PREFS = "bigbai_secure"
        private const val KEY_DB_PASSPHRASE = "db_passphrase_v1"
    }
}
