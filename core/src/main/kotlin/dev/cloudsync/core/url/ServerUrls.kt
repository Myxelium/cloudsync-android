package dev.cloudsync.core.url

import dev.cloudsync.core.error.ValidationException
import java.net.IDN
import java.net.URI

object ServerUrls {
    fun normalize(input: String): String {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) throw ValidationException("Enter the Nextcloud server address.")
        if (trimmed.contains(" ")) throw ValidationException("The server address cannot contain spaces.")
        val withScheme = if ("://" in trimmed) trimmed else "https://$trimmed"
        val uri = try {
            URI(withScheme)
        } catch (error: IllegalArgumentException) {
            throw ValidationException("That server address is not a valid URL.")
        }
        val scheme = uri.scheme?.lowercase()
        if (scheme == "http") {
            throw ValidationException("CloudSync only connects over HTTPS. Certificate checks are not disabled.")
        }
        if (scheme != "https") {
            throw ValidationException("Use an https:// address for the Nextcloud server.")
        }
        if (!uri.userInfo.isNullOrBlank()) {
            throw ValidationException("Do not put a username or password in the server address.")
        }
        val host = uri.host ?: throw ValidationException("The server address needs a host name.")
        if (host.equals("localhost", ignoreCase = true) || host == "127.0.0.1" || host == "::1") {
            throw ValidationException("Localhost is not a Nextcloud server address.")
        }
        val asciiHost = try {
            IDN.toASCII(host)
        } catch (error: IllegalArgumentException) {
            throw ValidationException("The server host name is not valid.")
        }
        val port = if (uri.port == -1) "" else ":${uri.port}"
        return "https://$asciiHost$port"
    }
}
