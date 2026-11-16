package dev.cloudsync.core.hash

import java.io.InputStream
import java.security.MessageDigest

object ContentHash {
    fun sha256(bytes: ByteArray): String = sha256(bytes.inputStream())

    fun sha256(input: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(DEFAULT_BUFFER)
        input.use { stream ->
            while (true) {
                val read = stream.read(buffer)
                if (read < 0) break
                if (read > 0) digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }

    private const val DEFAULT_BUFFER = 64 * 1024
}
