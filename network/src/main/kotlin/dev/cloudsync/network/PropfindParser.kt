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
        val responses = document.getElementsByTagNameNS("*", "response")
        val nodes = mutableListOf<RemoteNode>()
        for (index in 0 until responses.length) {
            val response = responses.item(index) as? Element ?: continue
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
        val decoded = java.net.URLDecoder.decode(href.substringBefore('?'), Charsets.UTF_8)
        val root = java.net.URLDecoder.decode(userRootHref.substringBefore('?'), Charsets.UTF_8).trimEnd('/')
        val normalized = if (decoded.startsWith("http")) {
            val marker = "/remote.php/dav/files/"
            val index = decoded.indexOf(marker)
            if (index < 0) return null
            decoded.substring(index)
        } else {
            decoded
        }
        val rootPath = if (root.startsWith("http")) {
            val marker = "/remote.php/dav/files/"
            root.substring(root.indexOf(marker))
        } else {
            root
        }
        if (normalized.trimEnd('/') == rootPath) return "/"
        if (!normalized.startsWith(rootPath)) return null
        val relative = normalized.removePrefix(rootPath).trim('/')
        return if (relative.isEmpty()) "/" else "/$relative"
    }

    private fun parseHttpDate(value: String): Long? = try {
        ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
    } catch (_: Exception) {
        null
    }

    private fun text(parent: Element, localName: String): String? = elements(parent, localName).firstOrNull()?.textContent?.trim()?.ifEmpty { null }

    private fun elements(parent: Element, localName: String): List<Element> {
        val matches = parent.getElementsByTagNameNS("*", localName)
        return buildList {
            for (index in 0 until matches.length) {
                (matches.item(index) as? Element)?.let(::add)
            }
        }
    }
}
