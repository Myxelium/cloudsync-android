package dev.cloudsync.core.path

import dev.cloudsync.core.error.InvalidNameException

object SyncPaths {
    fun normalizeRelative(path: String): String {
        val slash = path.replace('\\', '/').trim()
        val stripped = slash.trim('/')
        if (stripped.isEmpty()) return ""
        val parts = stripped.split('/').filter { it.isNotEmpty() && it != "." }
        if (parts.any { it == ".." }) throw InvalidNameException("Paths cannot contain '..'.")
        return parts.joinToString("/")
    }

    fun child(parent: String, name: String): String {
        val base = normalizeRelative(parent)
        val leaf = normalizeRelative(name)
        return if (base.isEmpty()) leaf else "$base/$leaf"
    }

    fun parent(path: String): String {
        val normalized = normalizeRelative(path)
        val index = normalized.lastIndexOf('/')
        return if (index < 0) "" else normalized.substring(0, index)
    }

    fun name(path: String): String {
        val normalized = normalizeRelative(path)
        val index = normalized.lastIndexOf('/')
        return if (index < 0) normalized else normalized.substring(index + 1)
    }

    fun stripPrefix(fullPath: String, root: String): String? {
        val full = normalizeRelative(fullPath)
        val base = normalizeRelative(root)
        if (base.isEmpty()) return full
        if (full == base) return ""
        val prefix = "$base/"
        if (!full.startsWith(prefix)) return null
        return full.removePrefix(prefix)
    }

    fun remoteUrlPath(userRootRelative: String): String = normalizeRelative(userRootRelative)

    fun validateName(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) throw InvalidNameException("The name cannot be empty.")
        if (trimmed == "." || trimmed == "..") throw InvalidNameException("That name is reserved.")
        if (trimmed.endsWith('.') || trimmed.endsWith(' ')) {
            throw InvalidNameException("Names cannot end with a space or a period.")
        }
        val forbidden = setOf('\\', '/', ':', '*', '?', '"', '<', '>', '|')
        val hit = trimmed.firstOrNull { it in forbidden || it.code < 32 }
        if (hit != null) throw InvalidNameException("The name contains a character Nextcloud does not allow.")
    }
}

object PathFilter {
    private val builtIn = listOf(
        ".cloudsync/**",
        "**/*.cloudsync-tmp",
        "**/*.cloudsync-partial",
        "**/.trashed-*",
    )

    fun ignored(relativePath: String, includeSubfolders: Boolean, userPatterns: List<String>): Boolean {
        val path = SyncPaths.normalizeRelative(relativePath)
        if (path.isEmpty()) return false
        if (!includeSubfolders && '/' in path) return true
        return (builtIn + userPatterns).any { pattern -> matches(path, pattern.trim()) }
    }

    fun matches(path: String, pattern: String): Boolean {
        if (pattern.isEmpty()) return false
        val regex = globToRegex(pattern)
        return regex.matches(path) || regex.matches(SyncPaths.name(path))
    }

    private fun globToRegex(pattern: String): Regex {
        val source = StringBuilder("^")
        var index = 0
        while (index < pattern.length) {
            when (val char = pattern[index]) {
                '*' -> {
                    val double = index + 1 < pattern.length && pattern[index + 1] == '*'
                    if (double) {
                        source.append(".*")
                        index += 2
                        if (index < pattern.length && pattern[index] == '/') index++
                    } else {
                        source.append("[^/]*")
                        index++
                    }
                }
                '?' -> {
                    source.append("[^/]")
                    index++
                }
                else -> {
                    if (char in REGEX_META) source.append('\\')
                    source.append(char)
                    index++
                }
            }
        }
        source.append('$')
        return Regex(source.toString())
    }

    private val REGEX_META = setOf('.', '(', ')', '+', '|', '^', '$', '{', '}', '[', ']', '\\')
}
