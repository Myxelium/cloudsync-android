package dev.cloudsync.merge

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlList
import com.charleskorn.kaml.YamlMap
import com.charleskorn.kaml.YamlNode
import com.charleskorn.kaml.YamlNull
import com.charleskorn.kaml.YamlScalar
import com.charleskorn.kaml.YamlTaggedNode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.w3c.dom.Element
import org.xml.sax.InputSource
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

internal object StructuredMergers {
    private val json = Json { prettyPrint = true }

    fun mergeJson(base: String, local: String, remote: String): MergeResult? {
        val baseNode = parseJson(base)
        val localNode = parseJson(local) ?: return null
        val remoteNode = parseJson(remote) ?: return null
        val merged = NodeMerger.merge(baseNode, localNode, remoteNode) ?: return null
        val text = json.encodeToString(JsonElement.serializer(), merged.toJson()) + "\n"
        return resultFrom(listOf(MergeSegment.Merged(splitBody(text).lines, SegmentOrigin.BOTH)), true, "json")
    }

    fun mergeYaml(base: String, local: String, remote: String): MergeResult? {
        val baseNode = parseYaml(base)
        val localNode = parseYaml(local) ?: return null
        val remoteNode = parseYaml(remote) ?: return null
        val merged = NodeMerger.merge(baseNode, localNode, remoteNode) ?: return null
        val text = emitYaml(merged)
        val body = splitBody(text)
        return resultFrom(listOf(MergeSegment.Merged(body.lines, SegmentOrigin.BOTH)), body.trailingNewline, "yaml")
    }

    fun validateJson(text: String): String? = if (parseJson(text) == null && text.isNotBlank()) "Merged JSON is not valid." else null

    fun validateYaml(text: String): String? = if (text.isNotBlank() && parseYaml(text) == null) "Merged YAML is not valid." else null

    fun validateXml(text: String): String? = if (text.isNotBlank() && parseXml(text) == null) "Merged XML is not well formed." else null

    fun mergeXml(base: String, local: String, remote: String): MergeResult? {
        val baseNode = if (base.isBlank()) null else parseXml(base)?.documentElement?.toXmlNode() ?: return null
        val localNode = parseXml(local)?.documentElement?.toXmlNode() ?: return null
        val remoteNode = parseXml(remote)?.documentElement?.toXmlNode() ?: return null
        val merged = mergeXmlNode(baseNode, localNode, remoteNode) ?: return null
        val text = emitXml(merged)
        if (parseXml(text) == null) return null
        val body = splitBody(text)
        return resultFrom(listOf(MergeSegment.Merged(body.lines, SegmentOrigin.BOTH)), body.trailingNewline, "xml")
    }

    fun mergeCsv(base: String, local: String, remote: String): MergeResult? {
        val baseTable = parseCsv(base)
        val localTable = parseCsv(local) ?: return null
        val remoteTable = parseCsv(remote) ?: return null
        if (localTable.header != remoteTable.header) return null
        if (baseTable != null && baseTable.header != localTable.header) return null
        val key = 0
        if (!stable(baseTable?.rows.orEmpty(), key) || !stable(localTable.rows, key) || !stable(remoteTable.rows, key)) {
            return null
        }
        val baseRows = baseTable?.rows?.associateBy { it[key] }.orEmpty()
        val localRows = localTable.rows.associateBy { it[key] }
        val remoteRows = remoteTable.rows.associateBy { it[key] }
        val ids = LinkedHashSet<String>()
        baseTable?.rows?.forEach { ids += it[key] }
        localTable.rows.forEach { ids += it[key] }
        remoteTable.rows.forEach { ids += it[key] }
        val rows = mutableListOf<List<String>>()
        for (id in ids) {
            val baseRow = baseRows[id]
            val localRow = localRows[id]
            val remoteRow = remoteRows[id]
            when {
                localRow == null && remoteRow == null -> Unit
                localRow == null && baseRow == remoteRow -> Unit
                remoteRow == null && baseRow == localRow -> Unit
                localRow == null || remoteRow == null -> return null
                else -> rows += mergeRow(baseRow, localRow, remoteRow) ?: return null
            }
        }
        val text = emitCsv(localTable.header, rows)
        val body = splitBody(text)
        return resultFrom(listOf(MergeSegment.Merged(body.lines, SegmentOrigin.BOTH)), true, "csv")
    }

    fun validateCsv(text: String): String? = if (text.isNotBlank() && parseCsv(text) == null) "Merged CSV is not valid." else null

    private fun mergeRow(base: List<String>?, local: List<String>, remote: List<String>): List<String>? {
        if (local.size != remote.size) return null
        if (base != null && base.size != local.size) return null
        val width = local.size
        return List(width) { index ->
            val baseCell = base?.getOrNull(index)
            val localCell = local[index]
            val remoteCell = remote[index]
            when {
                localCell == remoteCell -> localCell
                baseCell == localCell -> remoteCell
                baseCell == remoteCell -> localCell
                else -> return null
            }
        }
    }

    private data class Csv(val header: List<String>, val rows: List<List<String>>)

    private fun stable(rows: List<List<String>>, column: Int): Boolean {
        val ids = rows.map { it.getOrNull(column).orEmpty() }
        return ids.all { it.isNotEmpty() } && ids.distinct().size == ids.size
    }

    private fun parseCsv(text: String): Csv? {
        if (text.isBlank()) return Csv(emptyList(), emptyList())
        val rows = CsvCodec.parse(text)
        if (rows.isEmpty()) return null
        val width = rows.first().size
        if (width == 0 || rows.any { it.size != width }) return null
        return Csv(rows.first(), rows.drop(1))
    }

    private fun emitCsv(header: List<String>, rows: List<List<String>>): String {
        val all = listOf(header) + rows
        return all.joinToString("\n") { row -> row.joinToString(",") { CsvCodec.escape(it) } } + "\n"
    }

    private fun parseJson(text: String): Node? = try {
        if (text.isBlank()) null else Json.parseToJsonElement(text).toNode()
    } catch (_: Exception) {
        null
    }

    private fun JsonElement.toNode(): Node = when (this) {
        is JsonObject -> Node.Obj(entries.map { (key, value) -> key to value.toNode() })
        is JsonArray -> Node.Arr(map { it.toNode() })
        is JsonNull -> Node.Nil
        is JsonPrimitive -> when {
            isString -> Node.Str(content)
            contentOrNull == "true" -> Node.Bool(true)
            contentOrNull == "false" -> Node.Bool(false)
            contentOrNull == "null" -> Node.Nil
            else -> Node.Num(content)
        }
        else -> Node.Nil
    }

    private fun Node.toJson(): JsonElement = when (this) {
        is Node.Obj -> JsonObject(entries.associate { it.first to it.second.toJson() })
        is Node.Arr -> JsonArray(items.map { it.toJson() })
        is Node.Str -> JsonPrimitive(value)
        is Node.Num -> Json.parseToJsonElement(raw)
        is Node.Bool -> JsonPrimitive(value)
        Node.Nil -> JsonNull
    }

    private fun parseYaml(text: String): Node? = try {
        if (text.isBlank()) null else Yaml.default.parseToYamlNode(text).toNode()
    } catch (_: Exception) {
        null
    }

    private fun YamlNode.toNode(): Node = when (this) {
        is YamlMap -> Node.Obj(entries.map { (key, value) -> key.content to value.toNode() })
        is YamlList -> Node.Arr(items.map { it.toNode() })
        is YamlNull -> Node.Nil
        is YamlScalar -> scalarToNode(content)
        is YamlTaggedNode -> throw IllegalArgumentException("YAML tags are not merged")
        else -> throw IllegalArgumentException("Unsupported YAML node")
    }

    private fun scalarToNode(content: String): Node = when (content) {
        "null", "~", "" -> Node.Nil
        "true", "True", "TRUE" -> Node.Bool(true)
        "false", "False", "FALSE" -> Node.Bool(false)
        else -> {
            val number = content.toDoubleOrNull()
            if (number != null && !content.any { it.isLetter() }) Node.Num(content) else Node.Str(content)
        }
    }

    private fun emitYaml(node: Node, indent: Int = 0): String {
        val pad = "  ".repeat(indent)
        val body = when (node) {
            is Node.Obj -> if (node.entries.isEmpty()) {
                "{}"
            } else {
                node.entries.joinToString("\n") { (key, value) ->
                    if (value is Node.Obj || value is Node.Arr) {
                        "$pad${yamlKey(key)}:\n${emitYaml(value, indent + 1)}"
                    } else {
                        "$pad${yamlKey(key)}: ${emitYaml(value, 0)}"
                    }
                }
            }
            is Node.Arr -> if (node.items.isEmpty()) {
                "[]"
            } else {
                node.items.joinToString("\n") { item ->
                    if (item is Node.Obj || item is Node.Arr) {
                        "$pad-\n${emitYaml(item, indent + 1)}"
                    } else {
                        "$pad- ${emitYaml(item, 0)}"
                    }
                }
            }
            is Node.Str -> yamlString(node.value)
            is Node.Num -> node.raw
            is Node.Bool -> node.value.toString()
            Node.Nil -> "null"
        }
        return if (indent == 0) "$body\n" else body
    }

    private fun yamlKey(key: String): String = if (key.all { it.isLetterOrDigit() || it == '_' }) key else yamlString(key)

    private fun yamlString(value: String): String = "'" + value.replace("'", "''") + "'"

    private fun parseXml(text: String): org.w3c.dom.Document? = try {
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = true
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false)
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        factory.newDocumentBuilder().parse(InputSource(text.reader()))
    } catch (_: Exception) {
        null
    }

    private data class XmlNode(val name: String, val attributes: List<Pair<String, String>>, val children: List<XmlNode>, val text: String)

    private fun Element.toXmlNode(): XmlNode {
        val attributes = buildList {
            for (index in 0 until this@toXmlNode.attributes.length) {
                val item = this@toXmlNode.attributes.item(index)
                add(item.nodeName to item.nodeValue)
            }
        }
        val children = buildList {
            val nodes = this@toXmlNode.childNodes
            for (index in 0 until nodes.length) {
                val child = nodes.item(index)
                if (child is Element) add(child.toXmlNode())
            }
        }
        return XmlNode(tagName, attributes, children, textContentFor(this))
    }

    private fun textContentFor(element: Element): String {
        val nodes = element.childNodes
        val text = StringBuilder()
        for (index in 0 until nodes.length) {
            val child = nodes.item(index)
            if (child.nodeType == org.w3c.dom.Node.TEXT_NODE || child.nodeType == org.w3c.dom.Node.CDATA_SECTION_NODE) {
                text.append(child.nodeValue)
            }
        }
        return text.toString()
    }

    private fun mergeXmlNode(base: XmlNode?, local: XmlNode, remote: XmlNode): XmlNode? {
        if (local.name != remote.name) return null
        if (base != null && base.name != local.name) return null
        if (local == remote) return local
        if (base != null && base == local) return remote
        if (base != null && base == remote) return local
        val attributes = mergeAttributePairs(base?.attributes.orEmpty(), local.attributes, remote.attributes) ?: return null
        val text = when {
            local.text == remote.text -> local.text
            base?.text == local.text -> remote.text
            base?.text == remote.text -> local.text
            else -> return null
        }
        val children = mergeChildren(base?.children.orEmpty(), local.children, remote.children) ?: return null
        return XmlNode(local.name, attributes, children, text)
    }

    private fun mergeAttributePairs(
        base: List<Pair<String, String>>,
        local: List<Pair<String, String>>,
        remote: List<Pair<String, String>>,
    ): List<Pair<String, String>>? {
        val baseMap = base.toMap()
        val localMap = local.toMap()
        val remoteMap = remote.toMap()
        val names = LinkedHashSet<String>()
        names += baseMap.keys
        names += localMap.keys
        names += remoteMap.keys
        val merged = mutableListOf<Pair<String, String>>()
        for (name in names) {
            val baseValue = baseMap[name]
            val localValue = localMap[name]
            val remoteValue = remoteMap[name]
            val value = when {
                localValue == remoteValue -> localValue
                baseValue == localValue -> remoteValue
                baseValue == remoteValue -> localValue
                else -> return null
            }
            if (value != null) merged += name to value
        }
        return merged
    }

    private fun mergeElement(base: Element?, local: Element, remote: Element): XmlNode? {
        if (local.tagName != remote.tagName) return null
        if (base != null && base.tagName != local.tagName) return null
        val attributes = mergeAttributes(base, local, remote) ?: return null
        val localNode = local.toXmlNode()
        val remoteNode = remote.toXmlNode()
        val baseNode = base?.toXmlNode()
        val text = when {
            localNode.text == remoteNode.text -> localNode.text
            baseNode?.text == localNode.text -> remoteNode.text
            baseNode?.text == remoteNode.text || baseNode == null -> localNode.text
            else -> return null
        }
        val children = mergeChildren(baseNode?.children.orEmpty(), localNode.children, remoteNode.children) ?: return null
        return XmlNode(local.tagName, attributes, children, text)
    }

    private fun mergeAttributes(base: Element?, local: Element, remote: Element): List<Pair<String, String>>? {
        fun mapOf(element: Element?): Map<String, String> {
            if (element == null) return emptyMap()
            return buildMap {
                for (index in 0 until element.attributes.length) {
                    val item = element.attributes.item(index)
                    put(item.nodeName, item.nodeValue)
                }
            }
        }
        val baseMap = mapOf(base)
        val localMap = mapOf(local)
        val remoteMap = mapOf(remote)
        val names = LinkedHashSet<String>()
        names += baseMap.keys
        names += localMap.keys
        names += remoteMap.keys
        val merged = mutableListOf<Pair<String, String>>()
        for (name in names) {
            val baseValue = baseMap[name]
            val localValue = localMap[name]
            val remoteValue = remoteMap[name]
            val value = when {
                localValue == remoteValue -> localValue
                baseValue == localValue -> remoteValue
                baseValue == remoteValue || baseValue == null -> localValue
                localValue == null && baseValue == remoteValue -> null
                remoteValue == null && baseValue == localValue -> null
                else -> return null
            }
            if (value != null) merged += name to value
        }
        return merged
    }

    private fun mergeChildren(base: List<XmlNode>, local: List<XmlNode>, remote: List<XmlNode>): List<XmlNode>? {
        val localIds = ids(local)
        val remoteIds = ids(remote)
        val baseIds = ids(base)
        if (localIds != null && remoteIds != null && (base.isEmpty() || baseIds != null)) {
            return mergeByKey(base, local, remote, baseIds.orEmpty(), localIds, remoteIds)
        }
        if (uniqueTags(base) && uniqueTags(local) && uniqueTags(remote)) {
            return mergeByKey(
                base,
                local,
                remote,
                base.associateBy { it.name },
                local.associateBy { it.name },
                remote.associateBy { it.name },
            )
        }
        return if (local == remote || base == local) {
            remote
        } else if (base == remote) {
            local
        } else {
            null
        }
    }

    private fun mergeByKey(
        base: List<XmlNode>,
        local: List<XmlNode>,
        remote: List<XmlNode>,
        baseMap: Map<String, XmlNode>,
        localMap: Map<String, XmlNode>,
        remoteMap: Map<String, XmlNode>,
    ): List<XmlNode>? {
        val keys = LinkedHashSet<String>()
        base.forEach { keys += key(it, baseMap) }
        local.forEach { keys += key(it, localMap) }
        remote.forEach { keys += key(it, remoteMap) }
        val merged = mutableListOf<XmlNode>()
        for (id in keys) {
            val baseNode = baseMap[id]
            val localNode = localMap[id]
            val remoteNode = remoteMap[id]
            when {
                localNode == null && remoteNode == null -> Unit
                localNode == null && baseNode == remoteNode -> Unit
                remoteNode == null && baseNode == localNode -> Unit
                localNode == null || remoteNode == null -> return null
                localNode == remoteNode -> merged += localNode
                baseNode == localNode -> merged += remoteNode
                baseNode == remoteNode -> merged += localNode
                else -> merged += mergeXmlNode(baseNode, localNode, remoteNode) ?: return null
            }
        }
        return merged
    }

    private fun key(node: XmlNode, map: Map<String, XmlNode>): String = map.entries.first { it.value == node }.key

    private fun ids(nodes: List<XmlNode>): Map<String, XmlNode>? {
        if (nodes.isEmpty()) return emptyMap()
        val keys = nodes.map { it.attributes.firstOrNull { attr -> attr.first == "id" }?.second ?: return null }
        if (keys.any { it.isEmpty() } || keys.distinct().size != keys.size) return null
        return nodes.associateBy { node -> node.attributes.first { it.first == "id" }.second }
    }

    private fun uniqueTags(nodes: List<XmlNode>): Boolean = nodes.map { it.name }.distinct().size == nodes.size

    private fun emitXml(node: XmlNode): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        append(writeElement(node))
        append('\n')
    }

    private fun writeElement(node: XmlNode): String = buildString {
        append('<').append(node.name)
        node.attributes.forEach { (name, value) ->
            append(' ').append(name).append("=\"").append(escapeXml(value)).append('"')
        }
        if (node.children.isEmpty() && node.text.isEmpty()) {
            append("/>")
        } else {
            append('>')
            append(escapeXml(node.text))
            node.children.forEach { append(writeElement(it)) }
            append("</").append(node.name).append('>')
        }
    }

    private fun escapeXml(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
}

internal object CsvCodec {
    fun parse(text: String): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        val row = mutableListOf<String>()
        val cell = StringBuilder()
        var quoted = false
        var index = 0
        val source = text.replace("\r\n", "\n").replace('\r', '\n')
        while (index < source.length) {
            val char = source[index]
            when {
                quoted && char == '"' && index + 1 < source.length && source[index + 1] == '"' -> {
                    cell.append('"')
                    index += 2
                }
                char == '"' -> {
                    quoted = !quoted
                    index++
                }
                char == ',' && !quoted -> {
                    row += cell.toString()
                    cell.clear()
                    index++
                }
                char == '\n' && !quoted -> {
                    row += cell.toString()
                    cell.clear()
                    rows += row.toList()
                    row.clear()
                    index++
                }
                else -> {
                    cell.append(char)
                    index++
                }
            }
        }
        if (cell.isNotEmpty() || row.isNotEmpty()) {
            row += cell.toString()
            rows += row.toList()
        }
        return rows.dropLastWhile { it.size == 1 && it[0].isEmpty() }
    }

    fun escape(value: String): String {
        val needsQuotes = value.any { it == ',' || it == '"' || it == '\n' }
        val escaped = value.replace("\"", "\"\"")
        return if (needsQuotes) "\"$escaped\"" else escaped
    }
}
