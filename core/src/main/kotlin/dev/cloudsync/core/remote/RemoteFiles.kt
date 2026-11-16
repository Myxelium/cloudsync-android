package dev.cloudsync.core.remote

import dev.cloudsync.core.model.AccountEndpoint
import java.io.InputStream

data class RemoteNode(
    val path: String,
    val directory: Boolean,
    val etag: String?,
    val size: Long,
    val modifiedAtEpochMillis: Long?,
    val contentType: String?,
    val fileId: String?,
    val permissions: String?,
)

data class DownloadResult(val bytes: ByteArray, val etag: String?, val contentType: String?, val notModified: Boolean, val totalSize: Long)

data class UploadResult(val etag: String?, val fileId: String?)

data class RemoteVersion(val href: String, val versionId: String, val size: Long, val modifiedAtEpochMillis: Long?, val etag: String?)

fun interface ContentSource {
    fun open(): InputStream
}

interface CredentialsProvider {
    suspend fun secret(accountId: String): String?
}

interface RemoteFiles {
    suspend fun list(account: AccountEndpoint, directoryPath: String): List<RemoteNode>

    suspend fun stat(account: AccountEndpoint, path: String): RemoteNode?

    suspend fun download(account: AccountEndpoint, path: String, ifNoneMatch: String? = null): DownloadResult

    suspend fun upload(
        account: AccountEndpoint,
        path: String,
        source: ContentSource,
        size: Long,
        contentType: String,
        ifMatch: String?,
        uploadId: String? = null,
        resumeFromChunk: Int = 1,
    ): UploadResult

    suspend fun delete(account: AccountEndpoint, path: String, ifMatch: String?)

    suspend fun createDirectory(account: AccountEndpoint, path: String)

    suspend fun move(account: AccountEndpoint, from: String, to: String, overwrite: Boolean)

    suspend fun copy(account: AccountEndpoint, from: String, to: String, overwrite: Boolean)

    suspend fun search(account: AccountEndpoint, term: String): List<RemoteNode>

    suspend fun listVersions(account: AccountEndpoint, fileId: String): List<RemoteVersion>

    suspend fun downloadVersion(account: AccountEndpoint, href: String): ByteArray

    suspend fun restoreVersion(account: AccountEndpoint, href: String, destinationPath: String)
}

interface LocalFiles {
    suspend fun list(root: String): List<LocalNode>

    suspend fun read(root: String, relativePath: String): ByteArray

    suspend fun writeAtomically(root: String, relativePath: String, bytes: ByteArray)

    suspend fun moveToRecovery(root: String, relativePath: String, recoveryRoot: String): String

    suspend fun delete(root: String, relativePath: String)

    suspend fun exists(root: String, relativePath: String): Boolean

    suspend fun move(root: String, from: String, to: String)

    suspend fun ensureDirectory(root: String, relativePath: String)

    suspend fun sha256(root: String, relativePath: String): String

    suspend fun size(root: String, relativePath: String): Long

    suspend fun modifiedAt(root: String, relativePath: String): Long
}

data class LocalNode(val relativePath: String, val directory: Boolean, val size: Long, val modifiedAt: Long)
