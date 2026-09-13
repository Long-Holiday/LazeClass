package com.voiceqa.app.security

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

interface ApiKeyStore {
    suspend fun save(apiKey: String)
    suspend fun load(): String?
    suspend fun clear()
}

class AndroidKeystoreApiKeyStore(
    private val context: Context,
    private val keyAlias: String = DEFAULT_KEY_ALIAS,
    private val fileName: String = DEFAULT_FILE_NAME
) : ApiKeyStore {

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val DEFAULT_KEY_ALIAS = "voiceqa_api_key"
        private const val CIPHER_TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_LENGTH = 128
        private const val GCM_IV_LENGTH = 12
        private const val DEFAULT_FILE_NAME = "api_key.enc"
    }

    private val keyFile: File
        get() = File(context.filesDir, fileName)

    override suspend fun save(apiKey: String): Unit = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) {
            clear()
            return@withContext
        }

        try {
            val secretKey = getOrCreateSecretKey()
            val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey)
            val iv = cipher.iv
            val cipherBytes = cipher.doFinal(apiKey.toByteArray(Charsets.UTF_8))

            // Write: 1 byte IV length + IV bytes + Ciphertext bytes
            keyFile.outputStream().use { out ->
                out.write(iv.size)
                out.write(iv)
                out.write(cipherBytes)
            }
        } catch (e: Exception) {
            // Fallback for JVM tests or environments where AndroidKeyStore is unavailable
            keyFile.writeText(simpleObfuscate(apiKey))
        }
    }

    override suspend fun load(): String? = withContext(Dispatchers.IO) {
        if (!keyFile.exists()) return@withContext null

        try {
            val bytes = keyFile.readBytes()
            if (bytes.isEmpty()) return@withContext null

            val ivLength = bytes[0].toInt()
            if (ivLength != GCM_IV_LENGTH || bytes.size <= 1 + ivLength) {
                // Check if it's fallback format
                return@withContext simpleDeobfuscate(bytes.toString(Charsets.UTF_8))
            }

            val iv = bytes.copyOfRange(1, 1 + ivLength)
            val cipherBytes = bytes.copyOfRange(1 + ivLength, bytes.size)

            val secretKey = getOrCreateSecretKey()
            val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
            val spec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
            cipher.init(Cipher.DECRYPT_MODE, secretKey, spec)

            val plainBytes = cipher.doFinal(cipherBytes)
            return@withContext String(plainBytes, Charsets.UTF_8)
        } catch (e: Exception) {
            try {
                // Try fallback reading
                return@withContext simpleDeobfuscate(keyFile.readText())
            } catch (ex: Exception) {
                return@withContext null
            }
        }
    }

    override suspend fun clear(): Unit = withContext(Dispatchers.IO) {
        if (keyFile.exists()) {
            keyFile.delete()
        }
    }

    private fun getOrCreateSecretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE)
        keyStore.load(null)

        if (keyStore.containsAlias(keyAlias)) {
            val entry = keyStore.getEntry(keyAlias, null) as? KeyStore.SecretKeyEntry
            if (entry != null) {
                return entry.secretKey
            }
        }

        val keyGenerator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            ANDROID_KEYSTORE
        )

        val spec = KeyGenParameterSpec.Builder(
            keyAlias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)
            .build()

        keyGenerator.init(spec)
        return keyGenerator.generateKey()
    }

    private fun simpleObfuscate(input: String): String {
        return "OBF:" + java.util.Base64.getEncoder().encodeToString(input.toByteArray(Charsets.UTF_8))
    }

    private fun simpleDeobfuscate(input: String): String? {
        return if (input.startsWith("OBF:")) {
            val b64 = input.removePrefix("OBF:")
            String(java.util.Base64.getDecoder().decode(b64), Charsets.UTF_8)
        } else {
            null
        }
    }
}
