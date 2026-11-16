package dev.cloudsync.network

import dev.cloudsync.core.etag.Etags
import dev.cloudsync.core.remote.RemoteNode
import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.StringReader
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

object PropfindParser {
    fun parse(xml: String, userRootHref: String): List<RemoteNode> {
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = true
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        val document = factory.newDocumentBuilder().parse(InputSource(StringReader(xml)))
        val root = document.documentElement ?: return emptyList()
        val nodes = mutableListOf<RemoteNode>()
        for (response in elements(root, "response")) {
            val href = text(response, "href") ?: continue
            val path = hrefToPath(href, userRootHref) ?: continue
            val collection = elements(response, "collection").isNotEmpty() ||
                text(response, "resourcetype")?.contains("collection") == true
            val etag = Etags.normalize(text(response, "getetag"))
            val length = text(response, "getcontentlength")?.toLongOrNull()
                ?: text(response, "size")?.toLongOrNull()
                ?: 0L
            nodes += RemoteNode(
                path = path,
                directory = collection,
                etag = etag,
                size = length,
                modifiedAtEpochMillis = text(response, "getlastmodified")?.let(::parseHttpDate),
                contentType = text(response, "getcontenttype"),
                fileId = text(response, "fileid"),
                permissions = text(response, "permissions"),
            )
        }
        return nodes
    }

    fun hrefToPath(href: String, userRootHref: String): String? {
        val node = davPath(href) ?: return null
        val root = davPath(userRootHref) ?: return null
        val nodeTrim = node.trimEnd('/')
        val rootTrim = root.trimEnd('/')
        if (nodeTrim == rootTrim) return "/"
        if (!nodeTrim.startsWith("$rootTrim/")) return null
        val relative = nodeTrim.removePrefix(rootTrim).trim('/')
        return if (relative.isEmpty()) "/" else "/$relative"
    }

    fun decodeHref(value: String): String = try {
        java.net.URLDecoder.decode(value.replace("+", "%2B"), Charsets.UTF_8)
    } catch (_: IllegalArgumentException) {
        value
    }

    fun davPath(href: String): String? {
        val raw = decodeHref(href.substringBefore('?')).trim()
        if (raw.isEmpty()) return null
        val path = if (raw.startsWith("http://") || raw.startsWith("https://")) {
            val scheme = raw.indexOf("://")
            val slash = raw.indexOf('/', startIndex = scheme + 3)
            if (slash < 0) "/" else raw.substring(slash)
        } else if (raw.startsWith("/")) {
            raw
        } else {
            "/$raw"
        }
        return path
    }

    private fun parseHttpDate(value: String): Long? = try {
        ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
    } catch (_: Exception) {
        null
    }

    private fun text(parent: Element, localName: String): String? = elements(parent, localName).firstNotNullOfOrNull { element ->
        if (inFailedPropstat(element)) return@firstNotNullOfOrNull null
        element.textContent?.trim()?.ifEmpty { null }
    }

    private fun inFailedPropstat(element: Element): Boolean {
        var current = element.parentNode
        while (current is Element) {
            if (localNameOf(current) == "propstat") {
                val status = elements(current, "status").firstOrNull()?.textContent.orEmpty()
                if (status.isBlank()) return false
                return !status.contains(" 2")
            }
            current = current.parentNode
        }
        return false
    }

    private fun elements(parent: Element, localName: String): List<Element> = buildList {
        val pending = ArrayDeque<Element>()
        val children = parent.childNodes
        for (index in 0 until children.length) {
            (children.item(index) as? Element)?.let(pending::add)
        }
        while (pending.isNotEmpty()) {
            val current = pending.removeFirst()
            if (localNameOf(current) == localName) add(current)
            val nested = current.childNodes
            for (index in 0 until nested.length) {
                (nested.item(index) as? Element)?.let(pending::add)
            }
        }
    }

    private fun localNameOf(element: Element): String {
        val local = element.localName
        if (!local.isNullOrEmpty()) return local
        return element.tagName.substringAfter(':')
    }
}
