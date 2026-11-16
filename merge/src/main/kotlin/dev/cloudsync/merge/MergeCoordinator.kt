package dev.cloudsync.merge

import dev.cloudsync.core.text.TextFiles
import dev.cloudsync.core.text.TextKind

class MergeCoordinator {
    fun merge(fileName: String, mime: String?, base: ByteArray, local: ByteArray, remote: ByteArray): MergeResult {
        if (base.size > TextFiles.MAX_MERGE_BYTES || local.size > TextFiles.MAX_MERGE_BYTES || remote.size > TextFiles.MAX_MERGE_BYTES) {
            return binary("File is too large to merge as text. Both versions are kept.")
        }
        if (TextFiles.refusesTextMerge(fileName)) {
            return binary("Automatic merging is not available for this file type. Both versions are kept.")
        }
        val kind = TextFiles.kindFor(fileName, mime)
        if (kind == null && !TextFiles.isEditable(fileName, mime, local)) {
            return binary("Automatic merging is not available for this file type. Both versions are kept.")
        }
        val baseText = TextFiles.decodeUtf8(base)
        val localText = TextFiles.decodeUtf8(local)
        val remoteText = TextFiles.decodeUtf8(remote)
        if (baseText == null || localText == null || remoteText == null) {
            return binary("The file is not valid UTF-8, so it was not merged as text.")
        }
        val text = Diff3Merger.merge(baseText, localText, remoteText)
        if (text.clean) {
            val error = validate(kind, text.mergedText.orEmpty())
            if (error != null) {
                return text.copy(clean = false, mergedText = null, validationError = error)
            }
            return text
        }
        val structured = structural(kind, baseText, localText, remoteText)
        if (structured != null && structured.clean) {
            val error = validate(kind, structured.mergedText.orEmpty())
            if (error == null) return structured
        }
        return text
    }

    private fun structural(kind: TextKind?, base: String, local: String, remote: String): MergeResult? = when (kind) {
        TextKind.JSON -> StructuredMergers.mergeJson(base, local, remote)
        TextKind.YAML -> StructuredMergers.mergeYaml(base, local, remote)
        TextKind.XML -> StructuredMergers.mergeXml(base, local, remote)
        TextKind.CSV -> StructuredMergers.mergeCsv(base, local, remote)
        else -> null
    }

    private fun validate(kind: TextKind?, text: String): String? = when (kind) {
        TextKind.JSON -> StructuredMergers.validateJson(text)
        TextKind.YAML -> StructuredMergers.validateYaml(text)
        TextKind.XML -> StructuredMergers.validateXml(text)
        TextKind.CSV -> StructuredMergers.validateCsv(text)
        else -> null
    }

    private fun binary(reason: String) = MergeResult(
        segments = emptyList(),
        clean = false,
        mergedText = null,
        trailingNewline = false,
        strategy = "binary",
        validationError = reason,
        binary = true,
    )
}
