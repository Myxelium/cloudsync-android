package dev.cloudsync.network

import dev.cloudsync.core.error.AuthenticationException
import dev.cloudsync.core.error.CloudSyncException
import dev.cloudsync.core.error.ConflictResponseException
import dev.cloudsync.core.error.InsufficientStorageException
import dev.cloudsync.core.error.NetworkException
import dev.cloudsync.core.error.NotFoundException
import dev.cloudsync.core.error.PermissionException
import dev.cloudsync.core.error.RemoteChangedException
import dev.cloudsync.core.error.ServerUnavailableException
import dev.cloudsync.core.etag.Etags
import dev.cloudsync.core.model.AccountEndpoint
import dev.cloudsync.core.remote.ContentSource
import dev.cloudsync.core.remote.CredentialsProvider
import dev.cloudsync.core.remote.DownloadResult
import dev.cloudsync.core.remote.RemoteFiles
import dev.cloudsync.core.remote.RemoteNode
import dev.cloudsync.core.remote.RemoteVersion
import dev.cloudsync.core.remote.UploadProgress
import dev.cloudsync.core.remote.UploadResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.net.URLEncoder
import java.util.UUID

class WebDavClient(
    http: OkHttpClient,
    private val credentials: CredentialsProvider,
    private val chunkSize: Int = DEFAULT_CHUNK,
) : RemoteFiles {
    // 301 and 302 otherwise become GET for every method except PROPFIND, which drops the MOVE/PUT/MKCOL body.
    private val direct = http.newBuilder().followRedirects(false).followSslRedirects(false).build()

    override suspend fun list(account: AccountEndpoint, directoryPath: String): List<RemoteNode> {
        val url = filesUrl(account, directoryPath, directory = true)
        val nodes = propfind(account, url, "1")
        val root = normalizePath(directoryPath)
        return nodes.filter { it.path.trimEnd('/') != root.trimEnd('/') }
    }

    override suspend fun stat(account: AccountEndpoint, path: String): RemoteNode? {
        val url = filesUrl(account, path)
        return try {
            val nodes = propfind(account, url, "0")
            nodes.firstOrNull { it.path.trimEnd('/') == normalizePath(path).trimEnd('/') } ?: nodes.firstOrNull()
        } catch (_: NotFoundException) {
            null
        }
    }

    override suspend fun download(account: AccountEndpoint, path: String, ifNoneMatch: String?): DownloadResult {
        val request = base(account, filesUrl(account, path)).get()
        if (ifNoneMatch != null) request.header("If-None-Match", Etags.headerValue(ifNoneMatch))
        return exchange(request.build()) { response ->
            if (response.code == 304) {
                DownloadResult(ByteArray(0), ifNoneMatch, null, true, 0)
            } else {
                ensureSuccess(response)
                val type = response.body.contentType()?.toString()
                val bytes = response.body.bytes()
                DownloadResult(
                    bytes = bytes,
                    etag = Etags.normalize(response.header("ETag")),
                    contentType = type,
                    notModified = false,
                    totalSize = bytes.size.toLong(),
                )
            }
        }
    }

    override suspend fun upload(
        account: AccountEndpoint,
        path: String,
        source: ContentSource,
        size: Long,
        contentType: String,
        ifMatch: String?,
        uploadId: String?,
        resumeFromChunk: Int,
        onProgress: (suspend (UploadProgress) -> Unit)?,
    ): UploadResult {
        if (size > chunkSize) {
            return uploadChunked(account, path, source, size, ifMatch, uploadId, resumeFromChunk, onProgress)
        }
        val bytes = withContext(Dispatchers.IO) { source.open().use { it.readBytes() } }
        val builder = base(account, filesUrl(account, path))
            .put(bytes.toRequestBody(contentType.toMediaType()))
        if (ifMatch != null) builder.header("If-Match", Etags.headerValue(ifMatch))
        return exchange(builder.build()) { response ->
            ensureSuccess(response)
            UploadResult(
                Etags.normalize(response.header("ETag")) ?: Etags.normalize(response.header("OC-ETag")),
                response.header("OC-FileId"),
            )
        }
    }

    override suspend fun delete(account: AccountEndpoint, path: String, ifMatch: String?) {
        val builder = base(account, filesUrl(account, path)).delete()
        if (ifMatch != null) builder.header("If-Match", Etags.headerValue(ifMatch))
        exchange(builder.build()) { ensureSuccess(it) }
    }

    override suspend fun createDirectory(account: AccountEndpoint, path: String) {
        val request = base(account, filesUrl(account, path, directory = true)).method("MKCOL", ByteArray(0).toRequestBody(null)).build()
        exchange(request) { response ->
            if (response.code != 405) ensureSuccess(response)
        }
    }

    override suspend fun move(account: AccountEndpoint, from: String, to: String, overwrite: Boolean) {
        mutate(account, from, to, overwrite, "MOVE")
    }

    override suspend fun copy(account: AccountEndpoint, from: String, to: String, overwrite: Boolean) {
        mutate(account, from, to, overwrite, "COPY")
    }

    override suspend fun search(account: AccountEndpoint, term: String): List<RemoteNode> {
        val url = account.serverUrl.trimEnd('/') +
            "/ocs/v2.php/search/providers/files/search?term=" + encode(term)
        val request = base(account, url).get().header("OCS-APIRequest", "true").header("Accept", "application/json").build()
        return exchange(request) { response ->
            ensureSuccess(response)
            parseSearch(account, response.body.string())
        }
    }

    override suspend fun listVersions(account: AccountEndpoint, fileId: String): List<RemoteVersion> {
        val url = versionsCollection(account, fileId)
        val request = base(account, url).method("PROPFIND", PROPFIND_BODY.toRequestBody(XML)).header("Depth", "1").build()
        return exchange(request) { response ->
            if (response.code == 404) {
                emptyList()
            } else {
                ensureSuccess(response)
                PropfindParser.parse(response.body.string(), url).map { node ->
                    RemoteVersion(
                        href = versionsCollection(account, fileId).trimEnd('/') + "/" + node.path.trim('/').substringAfterLast('/'),
                        versionId = node.path.trim('/').substringAfterLast('/'),
                        size = node.size,
                        modifiedAtEpochMillis = node.modifiedAtEpochMillis,
                        etag = node.etag,
                    )
                }.filter { it.versionId.isNotEmpty() && it.versionId != fileId }
            }
        }
    }

    override suspend fun downloadVersion(account: AccountEndpoint, href: String): ByteArray = exchange(base(account, href).get().build()) { response ->
        ensureSuccess(response)
        response.body.bytes()
    }

    override suspend fun restoreVersion(account: AccountEndpoint, href: String, destinationPath: String) {
        val request = base(account, href)
            .method("MOVE", ByteArray(0).toRequestBody(null))
            .header("Destination", filesUrl(account, destinationPath))
            .header("Overwrite", "T")
            .build()
        exchange(request) { ensureSuccess(it) }
    }

    private suspend fun uploadChunked(
        account: AccountEndpoint,
        path: String,
        source: ContentSource,
        size: Long,
        ifMatch: String?,
        existingUploadId: String?,
        resumeFromChunk: Int,
        onProgress: (suspend (UploadProgress) -> Unit)?,
    ): UploadResult = withContext(Dispatchers.IO) {
        val uploadId = existingUploadId ?: "cloudsync-${UUID.randomUUID()}"
        val destination = filesUrl(account, path)
        val collection = uploadsUrl(account, uploadId)
        val startChunk = resumeFromChunk.coerceAtLeast(1)
        var bytesSent = 0L
        suspend fun report(nextChunk: Int) {
            onProgress?.invoke(UploadProgress(uploadId, nextChunk, bytesSent.coerceAtMost(size)))
        }
        // Persist the id before any network call so a dropped connection can resume this collection.
        report(startChunk)
        if (startChunk <= 1) {
            val create = base(account, collection)
                .method("MKCOL", ByteArray(0).toRequestBody(null))
                .header("Destination", destination)
                .build()
            exchange(create) { response ->
                if (response.code != 405) ensureSuccess(response)
            }
        }
        var nextChunk = startChunk
        source.open().use { input ->
            val buffer = ByteArray(chunkSize)
            var chunk = 1
            while (true) {
                val read = readChunk(input, buffer)
                if (read <= 0) break
                if (chunk >= startChunk) {
                    val name = chunk.toString().padStart(5, '0')
                    val put = base(account, "$collection/$name")
                        .put(buffer.copyOf(read).toRequestBody(OCTET))
                        .header("Destination", destination)
                        .header("OC-Total-Length", size.toString())
                        .build()
                    exchange(put) { ensureSuccess(it) }
                    bytesSent += read
                    nextChunk = chunk + 1
                    report(nextChunk)
                } else {
                    bytesSent += read
                }
                chunk++
            }
            nextChunk = chunk
        }
        // Chunks are on the server. A retry from here only repeats the assembly MOVE.
        report(nextChunk)
        val assemble = base(account, "$collection/.file")
            .method("MOVE", ByteArray(0).toRequestBody(null))
            .header("Destination", destination)
            .header("OC-Total-Length", size.toString())
            .header("Overwrite", "T")
        if (ifMatch != null) assemble.header("If-Match", Etags.headerValue(ifMatch))
        exchange(assemble.build()) { response ->
            ensureSuccess(response)
            UploadResult(
                Etags.normalize(response.header("ETag")) ?: Etags.normalize(response.header("OC-ETag")),
                response.header("OC-FileId"),
            )
        }
    }

    private fun readChunk(input: java.io.InputStream, buffer: ByteArray): Int {
        var offset = 0
        while (offset < buffer.size) {
            val read = input.read(buffer, offset, buffer.size - offset)
            if (read < 0) break
            offset += read
        }
        return offset
    }

    private suspend fun propfind(account: AccountEndpoint, url: String, depth: String): List<RemoteNode> {
        val request = base(account, url).method("PROPFIND", PROPFIND_BODY.toRequestBody(XML)).header("Depth", depth).build()
        return exchange(request) { response ->
            ensureSuccess(response)
            PropfindParser.parse(response.body.string(), filesUrl(account, "/"))
        }
    }

    private suspend fun mutate(account: AccountEndpoint, from: String, to: String, overwrite: Boolean, method: String) {
        val request = base(account, filesUrl(account, from))
            .method(method, ByteArray(0).toRequestBody(null))
            .header("Destination", filesUrl(account, to))
            .header("Overwrite", if (overwrite) "T" else "F")
            .build()
        exchange(request) { ensureSuccess(it) }
    }

    private suspend fun base(account: AccountEndpoint, url: String): Request.Builder {
        val secret = credentials.secret(account.accountId)
            ?: throw AuthenticationException("The account is not signed in.")
        return Request.Builder()
            .url(url)
            .header("Authorization", Credentials.basic(account.loginName, secret, Charsets.UTF_8))
            .header("User-Agent", HttpClients.USER_AGENT)
    }

    /**
     * Reads the response off the main thread. A blocking call there throws NetworkOnMainThreadException,
     * and that exception has no message, so the files screen can only say the folder could not be opened.
     * Redirects are repeated with the same method: Nextcloud answers a folder or front-controller move with 301/302.
     */
    private suspend fun <T> exchange(request: Request, block: (Response) -> T): T = withContext(Dispatchers.IO) {
        var current = request
        repeat(MAX_REDIRECTS) {
            val response = try {
                direct.newCall(current).execute()
            } catch (error: IOException) {
                throw NetworkException("The network request failed.", error)
            }
            response.use {
                if (it.code == 301 || it.code == 302 || it.code == 307 || it.code == 308) {
                    current = redirected(current, it)
                    return@use
                }
                return@withContext block(it)
            }
        }
        throw CloudSyncException("Nextcloud redirected this request too many times.")
    }

    private fun redirected(request: Request, response: Response): Request {
        val location = response.header("Location")?.takeIf { it.isNotBlank() }
            ?: throw CloudSyncException("Nextcloud redirected this request without a destination.")
        val next = response.request.url.resolve(location)
            ?: throw CloudSyncException("Nextcloud redirected this request to an invalid address.")
        if (!next.host.equals(request.url.host, ignoreCase = true) || next.port != request.url.port) {
            throw CloudSyncException("Nextcloud redirected this request to another server.")
        }
        if (request.url.scheme == "https" && next.scheme != "https") {
            throw CloudSyncException("Nextcloud redirected this request away from HTTPS.")
        }
        if (next == request.url) throw CloudSyncException("Nextcloud redirected this request to itself.")
        return request.newBuilder().url(next).build()
    }

    private fun ensureSuccess(response: Response) {
        if (response.isSuccessful || response.code == 201 || response.code == 204 || response.code == 207) return
        val detail = response.message.ifBlank { "HTTP ${response.code}" }
        throw when (response.code) {
            401 -> AuthenticationException("Nextcloud rejected the saved login. Sign in again.")
            403 -> PermissionException("Nextcloud refused access to this file.")
            404 -> NotFoundException("The file is no longer on the server.")
            409 -> ConflictResponseException("The server reported a conflict while changing the file.")
            412 -> RemoteChangedException("The file changed on the server before the upload finished.")
            413, 507 -> InsufficientStorageException("There is not enough space on the server.")
            else -> if (response.code == 408 || response.code == 423 || response.code == 429 || response.code >= 500) {
                ServerUnavailableException("Nextcloud is unavailable ($detail).")
            } else {
                CloudSyncException("Nextcloud returned $detail.")
            }
        }
    }

    private fun parseSearch(account: AccountEndpoint, body: String): List<RemoteNode> {
        val root = Json.parseToJsonElement(body).jsonObject
        val entries = root["ocs"]?.jsonObject?.get("data")?.jsonObject?.get("entries")?.jsonArray ?: return emptyList()
        return entries.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val attributes = obj["attributes"] as? JsonObject
            val attributePath = attributes?.stringOrNull("path")
            val path = when {
                !attributePath.isNullOrBlank() -> normalizePath(attributePath)
                else -> pathFromDavUrl(account, obj["resourceUrl"]?.jsonPrimitive?.contentOrNull)
            } ?: return@mapNotNull null
            val icon = obj["icon"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val directory = icon == "icon-folder" || icon.endsWith("/folder.svg") || path.endsWith("/")
            RemoteNode(
                path = if (directory) path.trimEnd('/').ifEmpty { "/" } else path,
                directory = directory,
                etag = null,
                size = attributes?.stringOrNull("size")?.toLongOrNull() ?: 0,
                modifiedAtEpochMillis = null,
                contentType = null,
                fileId = attributes?.stringOrNull("fileId"),
                permissions = null,
            )
        }
    }

    private fun pathFromDavUrl(account: AccountEndpoint, resource: String?): String? {
        if (resource.isNullOrBlank()) return null
        val marker = "/remote.php/dav/files/"
        val decoded = PropfindParser.decodeHref(resource.substringBefore('?'))
        val index = decoded.indexOf(marker)
        if (index < 0) return null
        val remainder = decoded.substring(index + marker.length).trim('/')
        val user = remainder.substringBefore('/')
        if (user != account.userId) return null
        val afterUser = remainder.substringAfter('/', "")
        return if (afterUser.isEmpty()) "/" else "/${afterUser.trim('/')}"
    }

    private fun JsonObject.stringOrNull(name: String): String? {
        val value = this[name] as? kotlinx.serialization.json.JsonPrimitive ?: return null
        return value.contentOrNull
    }

    private fun filesUrl(account: AccountEndpoint, path: String, directory: Boolean = false): String {
        val root = account.serverUrl.trimEnd('/') + "/remote.php/dav/files/" + encode(account.userId)
        val relative = path.trim('/')
        if (relative.isEmpty()) return "$root/"
        val encoded = root + "/" + relative.split('/').joinToString("/") { encode(it) }
        return if (directory) "$encoded/" else encoded
    }

    private fun uploadsUrl(account: AccountEndpoint, uploadId: String): String = account.serverUrl.trimEnd('/') + "/remote.php/dav/uploads/" + encode(account.userId) + "/" + encode(uploadId)

    private fun versionsCollection(account: AccountEndpoint, fileId: String): String = account.serverUrl.trimEnd('/') + "/remote.php/dav/versions/" + encode(account.userId) + "/versions/" + encode(fileId)

    private fun normalizePath(path: String): String {
        val trimmed = path.trim()
        if (trimmed.isEmpty() || trimmed == "/") return "/"
        return "/" + trimmed.trim('/')
    }

    private fun encode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8).replace("+", "%20")

    private companion object {
        const val DEFAULT_CHUNK = 5 * 1024 * 1024
        val XML = "application/xml; charset=utf-8".toMediaType()
        val OCTET = "application/octet-stream".toMediaType()
        const val MAX_REDIRECTS = 5
        val PROPFIND_BODY = """
            <?xml version="1.0" encoding="UTF-8"?>
            <d:propfind xmlns:d="DAV:" xmlns:oc="http://owncloud.org/ns" xmlns:nc="http://nextcloud.org/ns">
              <d:prop>
                <d:getlastmodified/>
                <d:getcontentlength/>
                <d:getcontenttype/>
                <d:getetag/>
                <d:resourcetype/>
                <oc:fileid/>
                <oc:permissions/>
                <oc:size/>
              </d:prop>
            </d:propfind>
        """.trimIndent()
    }
}
