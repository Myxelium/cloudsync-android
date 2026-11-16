package dev.cloudsync.core.log

object LogRedactor {
    private val patterns = listOf(
        Regex("(?i)(authorization\\s*[:=]\\s*)(\\S.*)"),
        Regex("(?i)(apppassword\"?\\s*[:=]\\s*\"?)([^\"\\s,;}]+)"),
        Regex("(?i)(password\"?\\s*[:=]\\s*\"?)([^\"\\s,;}]+)"),
        Regex("(?i)(token\"?\\s*[:=]\\s*\"?)([^\"\\s,;}]+)"),
        Regex("(?i)(secret\"?\\s*[:=]\\s*\"?)([^\"\\s,;}]+)"),
    )

    fun redact(message: String): String {
        var result = message
        for (pattern in patterns) {
            result = pattern.replace(result) { match -> match.groupValues[1] + "[redacted]" }
        }
        return result
    }
}

interface DiagnosticLog {
    fun info(category: String, message: String, accountId: String? = null, folderId: String? = null)
    fun error(category: String, message: String, accountId: String? = null, folderId: String? = null)
}

object NoopLog : DiagnosticLog {
    override fun info(category: String, message: String, accountId: String?, folderId: String?) = Unit
    override fun error(category: String, message: String, accountId: String?, folderId: String?) = Unit
}
