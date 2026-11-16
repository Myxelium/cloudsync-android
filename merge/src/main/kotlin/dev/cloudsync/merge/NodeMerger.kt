package dev.cloudsync.merge

internal sealed interface Node {
    data class Obj(val entries: List<Pair<String, Node>>) : Node
    data class Arr(val items: List<Node>) : Node
    data class Str(val value: String) : Node
    data class Num(val raw: String) : Node
    data class Bool(val value: Boolean) : Node
    data object Nil : Node
}

internal object NodeMerger {
    fun merge(base: Node?, local: Node, remote: Node): Node? = mergeValue(base, local, remote)

    private fun mergeValue(base: Node?, local: Node?, remote: Node?): Node? {
        if (local == null && remote == null) return null
        if (local == remote) return local
        if (base == local) return remote
        if (base == remote) return local
        if (local == null || remote == null) return null
        if (local is Node.Obj && remote is Node.Obj && (base == null || base is Node.Obj)) {
            return mergeObjects(base as Node.Obj?, local, remote)
        }
        if (local is Node.Arr && remote is Node.Arr && (base == null || base is Node.Arr)) {
            return mergeArrays(base as Node.Arr?, local, remote)
        }
        return null
    }

    private fun mergeObjects(base: Node.Obj?, local: Node.Obj, remote: Node.Obj): Node.Obj? {
        val baseMap = base?.entries?.toMap().orEmpty()
        val localMap = local.entries.toMap()
        val remoteMap = remote.entries.toMap()
        val keys = LinkedHashSet<String>()
        base?.entries?.forEach { keys += it.first }
        local.entries.forEach { keys += it.first }
        remote.entries.forEach { keys += it.first }
        val merged = mutableListOf<Pair<String, Node>>()
        for (key in keys) {
            val baseValue = baseMap[key]
            val localValue = localMap[key]
            val remoteValue = remoteMap[key]
            when {
                localValue == null && remoteValue == null -> Unit
                localValue == null && baseValue == remoteValue -> Unit
                remoteValue == null && baseValue == localValue -> Unit
                localValue == null || remoteValue == null -> return null
                else -> {
                    val child = mergeValue(baseValue, localValue, remoteValue) ?: return null
                    merged += key to child
                }
            }
        }
        return Node.Obj(merged)
    }

    private fun mergeArrays(base: Node.Arr?, local: Node.Arr, remote: Node.Arr): Node.Arr? {
        val byId = mergeById(base, local, remote)
        if (byId != null) return byId
        val baseLines = base?.items?.map { canonical(it) } ?: emptyList()
        val merged = Diff3Merger.mergeLines(baseLines, local.items.map { canonical(it) }, remote.items.map { canonical(it) })
        if (merged.any { it is MergeSegment.Conflict }) return null
        return Node.Arr(merged.flatMap { it.lines }.map { parseCanonical(it) })
    }

    private fun mergeById(base: Node.Arr?, local: Node.Arr, remote: Node.Arr): Node.Arr? {
        val baseItems = base?.items.orEmpty()
        if (!hasStableIds(baseItems) || !hasStableIds(local.items) || !hasStableIds(remote.items)) return null
        val baseMap = baseItems.associateBy { idOf(it) }
        val localMap = local.items.associateBy { idOf(it) }
        val remoteMap = remote.items.associateBy { idOf(it) }
        val ids = LinkedHashSet<String>()
        baseItems.forEach { ids += idOf(it) }
        local.items.forEach { ids += idOf(it) }
        remote.items.forEach { ids += idOf(it) }
        val merged = mutableListOf<Node>()
        for (id in ids) {
            val baseValue = baseMap[id]
            val localValue = localMap[id]
            val remoteValue = remoteMap[id]
            when {
                localValue == null && remoteValue == null -> Unit
                localValue == null && baseValue == remoteValue -> Unit
                remoteValue == null && baseValue == localValue -> Unit
                localValue == null || remoteValue == null -> return null
                else -> merged += mergeValue(baseValue, localValue, remoteValue) ?: return null
            }
        }
        return Node.Arr(merged)
    }

    private fun hasStableIds(items: List<Node>): Boolean {
        if (items.isEmpty()) return true
        val ids = items.map { node ->
            val obj = node as? Node.Obj ?: return false
            val id = obj.entries.firstOrNull { it.first == "id" }?.second as? Node.Str ?: return false
            id.value
        }
        return ids.all { it.isNotEmpty() } && ids.distinct().size == ids.size
    }

    private fun idOf(node: Node): String = ((node as Node.Obj).entries.first { it.first == "id" }.second as Node.Str).value

    private fun canonical(node: Node): String = when (node) {
        is Node.Str -> "s:" + node.value.replace("\n", "\\n")
        is Node.Num -> "n:" + node.raw
        is Node.Bool -> "b:" + node.value
        Node.Nil -> "z"
        is Node.Obj -> "o:" + node.entries.joinToString(",") { it.first + "=" + canonical(it.second) }
        is Node.Arr -> "a:" + node.items.joinToString(",") { canonical(it) }
    }

    private fun parseCanonical(text: String): Node = when {
        text.startsWith("s:") -> Node.Str(text.removePrefix("s:").replace("\\n", "\n"))
        text.startsWith("n:") -> Node.Num(text.removePrefix("n:"))
        text.startsWith("b:") -> Node.Bool(text.removePrefix("b:").toBoolean())
        text == "z" -> Node.Nil
        else -> Node.Str(text)
    }
}
