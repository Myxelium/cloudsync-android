package dev.cloudsync.security

import org.junit.Test
import javax.crypto.KeyGenerator
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class AesGcmCipherTest {
    @Test
    fun roundTripDoesNotLeaveTheSecretInTheBlob() {
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val secret = "app-password-value"
        val blob = AesGcmCipher.encrypt(key, secret.toByteArray())
        assertFalse(String(blob, Charsets.ISO_8859_1).contains(secret))
        assertEquals(secret, String(AesGcmCipher.decrypt(key, blob), Charsets.UTF_8))
    }
}
