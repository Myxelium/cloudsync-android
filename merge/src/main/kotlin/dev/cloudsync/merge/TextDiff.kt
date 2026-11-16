package dev.cloudsync.merge

import com.github.difflib.DiffUtils
import com.github.difflib.patch.DeltaType

object TextDiff {
    fun unified(before: String, after: String): String {
        val left = splitBody(before).lines
        val right = splitBody(after).lines
        val patch = DiffUtils.diff(left, right)
        if (patch.deltas.isEmpty()) return "No textual differences."
        return buildString {
            for (delta in patch.deltas) {
                val kind = when (delta.type) {
                    DeltaType.INSERT -> "added"
                    DeltaType.DELETE -> "removed"
                    DeltaType.CHANGE -> "changed"
                    else -> "equal"
                }
                append(kind).append(" at line ").append(delta.source.position + 1).append('\n')
                delta.source.lines.forEach { append("- ").append(it).append('\n') }
                delta.target.lines.forEach { append("+ ").append(it).append('\n') }
            }
        }
    }
}
