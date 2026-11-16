package dev.cloudsync.merge

enum class SegmentOrigin {
    LOCAL,
    REMOTE,
    BOTH,
}

sealed class MergeSegment {
    abstract val lines: List<String>

    data class Unchanged(override val lines: List<String>) : MergeSegment()

    data class Merged(override val lines: List<String>, val from: SegmentOrigin) : MergeSegment()

    data class Conflict(val base: List<String>, val local: List<String>, val remote: List<String>) : MergeSegment() {
        override val lines: List<String> = emptyList()
    }
}

data class MergeResult(
    val segments: List<MergeSegment>,
    val clean: Boolean,
    val mergedText: String?,
    val trailingNewline: Boolean,
    val strategy: String,
    val validationError: String? = null,
    val binary: Boolean = false,
) {
    val conflictCount: Int get() = segments.count { it is MergeSegment.Conflict }

    fun withChoices(choices: List<String>): String {
        var index = 0
        val lines = mutableListOf<String>()
        for (segment in segments) {
            when (segment) {
                is MergeSegment.Conflict -> {
                    val chosen = choices.getOrNull(index)
                        ?: error("A choice is required for conflict ${index + 1}.")
                    lines += splitBody(chosen).lines
                    index++
                }
                else -> lines += segment.lines
            }
        }
        if (index != choices.size) error("Too many resolution choices.")
        return joinLines(lines, trailingNewline)
    }
}

internal data class TextBody(val lines: List<String>, val trailingNewline: Boolean)

internal fun splitBody(text: String): TextBody {
    if (text.isEmpty()) return TextBody(emptyList(), false)
    val normalized = text.replace("\r\n", "\n").replace('\r', '\n')
    val trailing = normalized.endsWith("\n")
    val core = if (trailing) normalized.dropLast(1) else normalized
    if (core.isEmpty()) return TextBody(emptyList(), trailing)
    return TextBody(core.split('\n'), trailing)
}

internal fun joinLines(lines: List<String>, trailingNewline: Boolean): String {
    if (lines.isEmpty()) return if (trailingNewline) "\n" else ""
    val body = lines.joinToString("\n")
    return if (trailingNewline) "$body\n" else body
}

internal fun resultFrom(
    segments: List<MergeSegment>,
    trailingNewline: Boolean,
    strategy: String,
    validationError: String? = null,
    binary: Boolean = false,
): MergeResult {
    val clean = validationError == null && segments.none { it is MergeSegment.Conflict } && !binary
    val text = if (clean) {
        joinLines(segments.flatMap { it.lines }, trailingNewline)
    } else {
        null
    }
    return MergeResult(
        segments = coalesce(segments),
        clean = clean,
        mergedText = text,
        trailingNewline = trailingNewline,
        strategy = strategy,
        validationError = validationError,
        binary = binary,
    )
}

private fun coalesce(segments: List<MergeSegment>): List<MergeSegment> {
    val output = mutableListOf<MergeSegment>()
    for (segment in segments) {
        if (segment.lines.isEmpty() && segment !is MergeSegment.Conflict) continue
        val previous = output.lastOrNull()
        when {
            previous is MergeSegment.Unchanged && segment is MergeSegment.Unchanged ->
                output[output.lastIndex] = MergeSegment.Unchanged(previous.lines + segment.lines)
            previous is MergeSegment.Merged && segment is MergeSegment.Merged && previous.from == segment.from ->
                output[output.lastIndex] = MergeSegment.Merged(previous.lines + segment.lines, previous.from)
            else -> output += segment
        }
    }
    return output
}
