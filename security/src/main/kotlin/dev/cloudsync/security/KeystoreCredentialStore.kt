package dev.cloudsync.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import dev.cloudsync.core.remote.CredentialsProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

interface CredentialStore : CredentialsProvider {
    suspend fun put(accountId: String, secret: String)

    suspend fun delete(accountId: String)
}

/**
 * Stores each account secret as an AES-GCM blob. The key never leaves Android Keystore.
 * Files are named by account id and contain no server URL or username.
 */
class KeystoreCredentialStore(context: Context) : CredentialStore {
    private val directory = File(context.filesDir, "credentials").apply { mkdirs() }

    override suspend fun secret(accountId: String): String? = withContext(Dispatchers.IO) {
        val file = fileFor(accountId)
        if (!file.exists()) return@withContext null
        val plain = AesGcmCipher.decrypt(key(), file.readBytes())
        String(plain, Charsets.UTF_8)
    }

    override suspend fun put(accountId: String, secret: String) {
        withContext(Dispatchers.IO) {
            val encrypted = AesGcmCipher.encrypt(key(), secret.toByteArray(Charsets.UTF_8))
            val destination = fileFor(accountId)
            val temporary = File(destination.parentFile, destination.name + ".tmp")
            temporary.writeBytes(encrypted)
            if (!temporary.renameTo(destination)) {
                destination.writeBytes(encrypted)
                temporary.delete()
            }
        }
    }

    override suspend fun delete(accountId: String) {
        withContext(Dispatchers.IO) {
            fileFor(accountId).delete()
        }
    }

    private fun fileFor(accountId: String): File {
        val safe = accountId.filter { it.isLetterOrDigit() || it == '-' }
        require(safe.isNotEmpty()) { "Account id is empty." }
        return File(directory, safe)
    }

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val existing = keyStore.getKey(ALIAS, null) as? SecretKey
        if (existing != null) return existing
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setRandomizedEncryptionRequired(true)
            .build()
        generator.init(spec)
        return generator.generateKey()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "cloudsync-credentials"
    }
}
