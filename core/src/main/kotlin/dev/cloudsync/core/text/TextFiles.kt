package dev.cloudsync.core.text

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

enum class TextKind {
    PLAIN,
    MARKDOWN,
    JSON,
    XML,
    YAML,
    CSV,
    SOURCE,
}

object TextFiles {
    private val sourceExtensions = setOf(
        "kt", "kts", "java", "gradle", "py", "js", "ts", "tsx", "jsx", "c", "h", "cpp", "hpp",
        "cs", "go", "rs", "rb", "php", "swift", "sql", "sh", "bash", "zsh", "properties", "toml",
        "ini", "cfg", "conf", "log", "html", "htm", "css", "scss", "sass", "svg",
    )
    private val officeExtensions = setOf(
        "doc", "docx", "docm", "xls", "xlsx", "xlsm", "ppt", "pptx", "pptm", "odt", "ods", "odp",
        "pdf", "rtf",
    )

    fun refusesTextMerge(fileName: String): Boolean = fileName.substringAfterLast('.', "").lowercase() in officeExtensions

    fun kindFor(fileName: String, mime: String?): TextKind? {
        val extension = fileName.substringAfterLast('.', "").lowercase()
        if (extension in officeExtensions) return null
        return when (extension) {
            "txt", "text", "log" -> TextKind.PLAIN
            "md", "markdown" -> TextKind.MARKDOWN
            "json" -> TextKind.JSON
            "xml" -> TextKind.XML
            "yaml", "yml" -> TextKind.YAML
            "csv", "tsv" -> TextKind.CSV
            in sourceExtensions -> TextKind.SOURCE
            else -> when {
                mime?.startsWith("text/") == true -> TextKind.PLAIN
                mime == "application/json" -> TextKind.JSON
                mime == "application/xml" || mime == "text/xml" -> TextKind.XML
                mime == "application/yaml" || mime == "text/yaml" -> TextKind.YAML
                mime == "text/csv" || mime == "application/csv" -> TextKind.CSV
                else -> null
            }
        }
    }

    fun decodeUtf8(bytes: ByteArray): String? {
        if (bytes.any { it == 0.toByte() }) return null
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return try {
            decoder.decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: CharacterCodingException) {
            null
        }
    }

    fun isEditable(fileName: String, mime: String?, bytes: ByteArray): Boolean {
        if (bytes.size > MAX_EDIT_BYTES) return false
        if (kindFor(fileName, mime) == null && !looksLikeText(bytes)) return false
        return decodeUtf8(bytes) != null
    }

    private fun looksLikeText(bytes: ByteArray): Boolean {
        if (bytes.isEmpty()) return true
        val sample = bytes.take(4096)
        if (sample.any { it == 0.toByte() }) return false
        val suspicious = sample.count { byte ->
            val value = byte.toInt() and 0xff
            value < 9 || (value in 14..31)
        }
        return suspicious.toDouble() / sample.size < 0.02
    }

    const val MAX_EDIT_BYTES = 2 * 1024 * 1024
    const val MAX_MERGE_BYTES = 8 * 1024 * 1024
}
