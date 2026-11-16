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
import java.net.URLEncoder
import java.util.UUID

class WebDavClient(
    private val http: OkHttpClient,
    private val credentials: CredentialsProvider,
    private val chunkSize: Int = DEFAULT_CHUNK,
) : RemoteFiles {
    override suspend fun list(account: AccountEndpoint, directoryPath: String): List<RemoteNode> {
        val url = filesUrl(account, directoryPath)
        val nodes = propfind(account, url, "1")
        val root = normalizePath(directoryPath)
        return nodes.filter { it.path.trimEnd('/') != root.trimEnd('/') }
    }

    override suspend fun stat(account: AccountEndpoint, path: String): RemoteNode? {
        val url = filesUrl(account, path)
        return try {
            propfind(account, url, "0").firstOrNull { it.path.trimEnd('/') == normalizePath(path).trimEnd('/') }
                ?: propfind(account, url, "0").firstOrNull()
        } catch (_: NotFoundException) {
            null
        }
    }

    override suspend fun download(account: AccountEndpoint, path: String, ifNoneMatch: String?): DownloadResult {
        val request = base(account, filesUrl(account, path)).get()
        if (ifNoneMatch != null) request.header("If-None-Match", Etags.headerValue(ifNoneMatch))
        execute(request.build()).use { response ->
            if (response.code == 304) {
                return DownloadResult(ByteArray(0), ifNoneMatch, null, true, 0)
            }
            ensureSuccess(response)
            val bytes = response.body.bytes()
            return DownloadResult(
                bytes = bytes,
                etag = Etags.normalize(response.header("ETag")),
                contentType = response.body.contentType()?.toString(),
                notModified = false,
                totalSize = bytes.size.toLong(),
            )
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
        val bytes = source.open().use { it.readBytes() }
        val builder = base(account, filesUrl(account, path))
            .put(bytes.toRequestBody(contentType.toMediaType()))
        if (ifMatch != null) builder.header("If-Match", Etags.headerValue(ifMatch))
        execute(builder.build()).use { response ->
            ensureSuccess(response)
            return UploadResult(
                Etags.normalize(response.header("ETag")) ?: Etags.normalize(response.header("OC-ETag")),
                response.header("OC-FileId"),
            )
        }
    }

    override suspend fun delete(account: AccountEndpoint, path: String, ifMatch: String?) {
        val builder = base(account, filesUrl(account, path)).delete()
        if (ifMatch != null) builder.header("If-Match", Etags.headerValue(ifMatch))
        execute(builder.build()).use { ensureSuccess(it) }
    }

    override suspend fun createDirectory(account: AccountEndpoint, path: String) {
        val request = base(account, filesUrl(account, path)).method("MKCOL", ByteArray(0).toRequestBody(null)).build()
        execute(request).use { response ->
            if (response.code == 405) return
            ensureSuccess(response)
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
        execute(request).use { response ->
            ensureSuccess(response)
            return parseSearch(account, response.body.string())
        }
    }

    override suspend fun listVersions(account: AccountEndpoint, fileId: String): List<RemoteVersion> {
        val url = versionsCollection(account, fileId)
        val request = base(account, url).method("PROPFIND", PROPFIND_BODY.toRequestBody(XML)).header("Depth", "1").build()
        execute(request).use { response ->
            if (response.code == 404) return emptyList()
            ensureSuccess(response)
            return PropfindParser.parse(response.body.string(), url).map { node ->
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

    override suspend fun downloadVersion(account: AccountEndpoint, href: String): ByteArray {
        execute(base(account, href).get().build()).use { response ->
            ensureSuccess(response)
            return response.body.bytes()
        }
    }

    override suspend fun restoreVersion(account: AccountEndpoint, href: String, destinationPath: String) {
        val request = base(account, href)
            .method("MOVE", ByteArray(0).toRequestBody(null))
            .header("Destination", filesUrl(account, destinationPath))
            .header("Overwrite", "T")
            .build()
        execute(request).use { ensureSuccess(it) }
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
    ): UploadResult {
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
            execute(create).use { response ->
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
                    execute(put).use { ensureSuccess(it) }
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
        execute(assemble.build()).use { response ->
            ensureSuccess(response)
            return UploadResult(
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
        execute(request).use { response ->
            ensureSuccess(response)
            return PropfindParser.parse(response.body.string(), filesUrl(account, "/"))
        }
    }

    private suspend fun mutate(account: AccountEndpoint, from: String, to: String, overwrite: Boolean, method: String) {
        val request = base(account, filesUrl(account, from))
            .method(method, ByteArray(0).toRequestBody(null))
            .header("Destination", filesUrl(account, to))
            .header("Overwrite", if (overwrite) "T" else "F")
            .build()
        execute(request).use { ensureSuccess(it) }
    }

    private suspend fun base(account: AccountEndpoint, url: String): Request.Builder {
        val secret = credentials.secret(account.accountId)
            ?: throw AuthenticationException("The account is not signed in.")
        return Request.Builder()
            .url(url)
            .header("Authorization", Credentials.basic(account.loginName, secret, Charsets.UTF_8))
            .header("User-Agent", HttpClients.USER_AGENT)
    }

    private fun execute(request: Request): Response = try {
        http.newCall(request).execute()
    } catch (error: java.io.IOException) {
        throw NetworkException("The network request failed.", error)
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
            val resource = obj["resourceUrl"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val marker = "/remote.php/dav/files/${encode(account.userId)}"
            val index = resource.indexOf("/remote.php/dav/files/")
            if (index < 0) return@mapNotNull null
            val path = java.net.URLDecoder.decode(resource.substring(resource.indexOf("/remote.php/dav/files/")), Charsets.UTF_8)
            val relative = path.substringAfter("/remote.php/dav/files/${account.userId}", "")
            if (relative.isEmpty()) return@mapNotNull null
            RemoteNode(
                path = if (relative.startsWith("/")) relative else "/$relative",
                directory = false,
                etag = null,
                size = obj["size"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0,
                modifiedAtEpochMillis = null,
                contentType = null,
                fileId = null,
                permissions = null,
            ).let { node ->
                if (resource.contains(marker) || path.contains("/${account.userId}/") || path.endsWith("/${account.userId}")) node else node
            }
        }
    }

    private fun filesUrl(account: AccountEndpoint, path: String): String {
        val root = account.serverUrl.trimEnd('/') + "/remote.php/dav/files/" + encode(account.userId)
        val relative = path.trim('/')
        if (relative.isEmpty()) return "$root/"
        return root + "/" + relative.split('/').joinToString("/") { encode(it) }
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
        val PROPFIND_BODY = """
            <?xml version="1.0"?>
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
