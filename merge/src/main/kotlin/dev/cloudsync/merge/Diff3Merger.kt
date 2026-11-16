package dev.cloudsync.merge

import com.github.difflib.DiffUtils

/**
 * Three-way line merge. Region diffs come from java-diff-utils' Myers implementation.
 * Overlapping regions that do not produce the same text stay as conflicts; nothing is dropped.
 */
object Diff3Merger {
    fun merge(baseText: String, localText: String, remoteText: String): MergeResult {
        val base = splitBody(baseText)
        val local = splitBody(localText)
        val remote = splitBody(remoteText)
        val trailing = base.trailingNewline || local.trailingNewline || remote.trailingNewline
        val segments = mergeLines(base.lines, local.lines, remote.lines)
        return resultFrom(segments, trailing, strategy = "diff3")
    }

    internal fun mergeLines(base: List<String>, local: List<String>, remote: List<String>): List<MergeSegment> {
        val left = edits(base, local)
        val right = edits(base, remote)
        val output = mutableListOf<MergeSegment>()
        var baseIndex = 0
        var leftIndex = 0
        var rightIndex = 0
        while (leftIndex < left.size || rightIndex < right.size) {
            val leftEdit = left.getOrNull(leftIndex)
            val rightEdit = right.getOrNull(rightIndex)
            if (leftEdit != null && rightEdit != null && overlaps(leftEdit, rightEdit)) {
                val cluster = expandCluster(left, right, leftIndex, rightIndex)
                if (baseIndex < cluster.start) {
                    output += MergeSegment.Unchanged(base.subList(baseIndex, cluster.start))
                }
                val localLines = project(base, left.subList(leftIndex, cluster.leftEnd + 1), cluster.start, cluster.end)
                val remoteLines = project(base, right.subList(rightIndex, cluster.rightEnd + 1), cluster.start, cluster.end)
                output += if (localLines == remoteLines) {
                    MergeSegment.Merged(localLines, SegmentOrigin.BOTH)
                } else {
                    val baseEnd = cluster.end.coerceAtMost(base.size)
                    MergeSegment.Conflict(
                        base = if (cluster.start < baseEnd) base.subList(cluster.start, baseEnd) else emptyList(),
                        local = localLines,
                        remote = remoteLines,
                    )
                }
                baseIndex = cluster.end
                leftIndex = cluster.leftEnd + 1
                rightIndex = cluster.rightEnd + 1
            } else if (leftEdit != null && (rightEdit == null || leftEdit.begin <= rightEdit.begin)) {
                if (baseIndex < leftEdit.begin) {
                    output += MergeSegment.Unchanged(base.subList(baseIndex, leftEdit.begin))
                }
                output += MergeSegment.Merged(leftEdit.lines, SegmentOrigin.LOCAL)
                baseIndex = leftEdit.end
                leftIndex++
            } else if (rightEdit != null) {
                if (baseIndex < rightEdit.begin) {
                    output += MergeSegment.Unchanged(base.subList(baseIndex, rightEdit.begin))
                }
                output += MergeSegment.Merged(rightEdit.lines, SegmentOrigin.REMOTE)
                baseIndex = rightEdit.end
                rightIndex++
            }
        }
        if (baseIndex < base.size) {
            output += MergeSegment.Unchanged(base.subList(baseIndex, base.size))
        }
        return output
    }

    private data class Edit(val begin: Int, val end: Int, val lines: List<String>)

    private data class Cluster(val start: Int, val end: Int, val leftEnd: Int, val rightEnd: Int)

    private fun edits(base: List<String>, revised: List<String>): List<Edit> {
        val patch = DiffUtils.diff(base, revised)
        return patch.deltas.map { delta ->
            Edit(
                begin = delta.source.position,
                end = delta.source.position + delta.source.lines.size,
                lines = delta.target.lines.toList(),
            )
        }
    }

    private fun overlaps(left: Edit, right: Edit): Boolean {
        if (left.begin == left.end && right.begin == right.end) return left.begin == right.begin
        if (left.begin == left.end) return left.begin >= right.begin && left.begin < right.end
        if (right.begin == right.end) return right.begin >= left.begin && right.begin < left.end
        return left.begin < right.end && right.begin < left.end
    }

    private fun expandCluster(left: List<Edit>, right: List<Edit>, leftIndex: Int, rightIndex: Int): Cluster {
        var leftEnd = leftIndex
        var rightEnd = rightIndex
        var start = minOf(left[leftIndex].begin, right[rightIndex].begin)
        var end = maxOf(left[leftIndex].end, right[rightIndex].end)
        var grew = true
        while (grew) {
            grew = false
            while (leftEnd + 1 < left.size && left[leftEnd + 1].begin < end) {
                leftEnd++
                end = maxOf(end, left[leftEnd].end)
                start = minOf(start, left[leftEnd].begin)
                grew = true
            }
            while (rightEnd + 1 < right.size && right[rightEnd + 1].begin < end) {
                rightEnd++
                end = maxOf(end, right[rightEnd].end)
                start = minOf(start, right[rightEnd].begin)
                grew = true
            }
        }
        return Cluster(start, end, leftEnd, rightEnd)
    }

    private fun project(base: List<String>, edits: List<Edit>, start: Int, end: Int): List<String> {
        val lines = mutableListOf<String>()
        var cursor = start
        for (edit in edits) {
            if (edit.begin > cursor) lines += base.subList(cursor, edit.begin)
            lines += edit.lines
            cursor = maxOf(cursor, edit.end)
        }
        if (cursor < end) lines += base.subList(cursor, end.coerceAtMost(base.size))
        return lines
    }
}
