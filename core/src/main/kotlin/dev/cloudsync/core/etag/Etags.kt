package dev.cloudsync.core.etag

object Etags {
    fun normalize(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        var value = raw.trim()
        if (value.startsWith("W/", ignoreCase = true)) {
            value = value.substring(2).trim()
        }
        if (value.length >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            value = value.substring(1, value.length - 1)
        }
        return value.ifBlank { null }
    }

    fun same(left: String?, right: String?): Boolean {
        val a = normalize(left)
        val b = normalize(right)
        return a != null && a == b
    }

    fun headerValue(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.startsWith("W/") || trimmed.startsWith("\"")) return trimmed
        return "\"$trimmed\""
    }
}
