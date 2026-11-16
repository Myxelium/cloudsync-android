package dev.cloudsync.testing

import dev.cloudsync.core.error.NetworkException
import dev.cloudsync.core.error.RemoteChangedException
import dev.cloudsync.core.etag.Etags
import dev.cloudsync.core.hash.ContentHash
import dev.cloudsync.core.model.AccountEndpoint
import dev.cloudsync.core.model.Conflict
import dev.cloudsync.core.model.MergeSession
import dev.cloudsync.core.model.TrackedFile
import dev.cloudsync.core.model.Transfer
import dev.cloudsync.core.remote.ContentSource
import dev.cloudsync.core.remote.DownloadResult
import dev.cloudsync.core.remote.LocalFiles
import dev.cloudsync.core.remote.LocalNode
import dev.cloudsync.core.remote.RemoteFiles
import dev.cloudsync.core.remote.RemoteNode
import dev.cloudsync.core.remote.RemoteVersion
import dev.cloudsync.core.remote.UploadProgress
import dev.cloudsync.core.remote.UploadResult
import dev.cloudsync.core.sync.SyncStateStore

class MemorySyncState : SyncStateStore {
    val files = mutableListOf<TrackedFile>()
    val conflictList = mutableListOf<Conflict>()
    val mergeList = mutableListOf<MergeSession>()
    val transferList = mutableListOf<Transfer>()

    override suspend fun tracked(folderId: String): List<TrackedFile> = files.filter { it.folderId == folderId }
    override suspend fun upsert(file: TrackedFile) {
        files.removeAll { it.id == file.id }
        files += file
    }
    override suspend fun deleteTracked(id: String) {
        files.removeAll { it.id == id }
    }
    override suspend fun conflicts(folderId: String): List<Conflict> = conflictList.filter { it.folderId == folderId }
    override suspend fun saveConflict(conflict: Conflict) {
        conflictList.removeAll { it.id == conflict.id }
        conflictList += conflict
    }
    override suspend fun mergeSessions(folderId: String): List<MergeSession> = mergeList.filter { it.folderId == folderId }
    override suspend fun saveMerge(session: MergeSession) {
        mergeList.removeAll { it.id == session.id }
        mergeList += session
    }
    override suspend fun saveTransfer(transfer: Transfer) {
        transferList.removeAll { it.id == transfer.id }
        transferList += transfer
    }
    override suspend fun transfers(folderId: String): List<Transfer> = transferList.filter { it.folderId == folderId }
}

class FakeRemoteFiles : RemoteFiles {
    val files = linkedMapOf<String, Stored>()
    val calls = mutableListOf<String>()
    var etagCounter = 1
    var failNextMatchedUpload = false
    var failNextUpload = false
    var resumedUploadId: String? = null
    var resumedChunk: Int = -1

    data class Stored(var bytes: ByteArray, var etag: String, var fileId: String, var directory: Boolean = false)

    override suspend fun list(account: AccountEndpoint, directoryPath: String): List<RemoteNode> {
        calls += "LIST $directoryPath"
        val prefix = directoryPath.trimEnd('/') + "/"
        return files.filter { (path, _) -> path.startsWith(prefix) && path.removePrefix(prefix).isNotEmpty() }
            .map { (path, stored) -> node(path, stored) }
    }

    override suspend fun stat(account: AccountEndpoint, path: String): RemoteNode? = files[path]?.let { node(path, it) }

    override suspend fun download(account: AccountEndpoint, path: String, ifNoneMatch: String?): DownloadResult {
        calls += "DOWNLOAD $path"
        val stored = files[path] ?: error("missing $path")
        if (ifNoneMatch != null && Etags.same(ifNoneMatch, stored.etag)) {
            return DownloadResult(ByteArray(0), stored.etag, null, true, stored.bytes.size.toLong())
        }
        return DownloadResult(stored.bytes.copyOf(), stored.etag, "text/plain", false, stored.bytes.size.toLong())
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
        calls += "UPLOAD $path"
        resumedUploadId = uploadId
        resumedChunk = resumeFromChunk
        val id = uploadId ?: "upload-resume"
        val nextChunk = if (resumeFromChunk <= 1) 3 else resumeFromChunk
        onProgress?.invoke(UploadProgress(id, nextChunk, 8))
        if (failNextUpload) {
            failNextUpload = false
            throw NetworkException("The connection dropped before the upload was assembled.")
        }
        val existing = files[path]
        if (ifMatch != null && (existing == null || !Etags.same(ifMatch, existing.etag))) {
            throw RemoteChangedException("remote changed")
        }
        if (failNextMatchedUpload && ifMatch != null) {
            failNextMatchedUpload = false
            existing?.etag = "changed-${etagCounter++}"
            throw RemoteChangedException("remote changed during upload")
        }
        val bytes = source.open().use { it.readBytes() }
        val etag = "e${etagCounter++}"
        files[path] = Stored(bytes, etag, existing?.fileId ?: "id-${path.trim('/')}")
        return UploadResult(etag, files[path]!!.fileId)
    }

    override suspend fun delete(account: AccountEndpoint, path: String, ifMatch: String?) {
        calls += "DELETE $path"
        val existing = files[path]
        if (ifMatch != null && (existing == null || !Etags.same(ifMatch, existing.etag))) {
            throw RemoteChangedException("remote changed")
        }
        files.remove(path)
    }

    override suspend fun createDirectory(account: AccountEndpoint, path: String) {
        calls += "MKCOL $path"
        files.putIfAbsent(path, Stored(ByteArray(0), "dir", "dir-$path", true))
    }

    override suspend fun move(account: AccountEndpoint, from: String, to: String, overwrite: Boolean) {
        calls += "MOVE $from $to"
        val stored = files.remove(from) ?: error("missing $from")
        files[to] = stored
    }

    override suspend fun copy(account: AccountEndpoint, from: String, to: String, overwrite: Boolean) {
        calls += "COPY $from $to"
        files[to] = files[from] ?: error("missing $from")
    }

    override suspend fun search(account: AccountEndpoint, term: String): List<RemoteNode> = files.filter { it.key.contains(term, ignoreCase = true) }.map { node(it.key, it.value) }

    override suspend fun listVersions(account: AccountEndpoint, fileId: String): List<RemoteVersion> = emptyList()
    override suspend fun downloadVersion(account: AccountEndpoint, href: String): ByteArray = ByteArray(0)
    override suspend fun restoreVersion(account: AccountEndpoint, href: String, destinationPath: String) = Unit

    private fun node(path: String, stored: Stored) = RemoteNode(
        path = path,
        directory = stored.directory,
        etag = stored.etag,
        size = stored.bytes.size.toLong(),
        modifiedAtEpochMillis = 0,
        contentType = "text/plain",
        fileId = stored.fileId,
        permissions = "RGDNVW",
    )
}

class FakeLocalFiles : LocalFiles {
    val files = linkedMapOf<String, ByteArray>()
    val recovered = mutableListOf<String>()
    private val modified = linkedMapOf<String, Long>()
    private var tick = 1L

    override suspend fun list(root: String): List<LocalNode> = files.map { (path, bytes) ->
        LocalNode(path, false, bytes.size.toLong(), modified[path] ?: 1)
    }

    override suspend fun read(root: String, relativePath: String): ByteArray = files[relativePath]?.copyOf() ?: error("missing $relativePath")
    override suspend fun writeAtomically(root: String, relativePath: String, bytes: ByteArray) {
        files[relativePath] = bytes.copyOf()
        modified[relativePath] = ++tick
    }
    override suspend fun moveToRecovery(root: String, relativePath: String, recoveryRoot: String): String {
        recovered += relativePath
        files.remove(relativePath)
        return "$recoveryRoot/$relativePath"
    }
    override suspend fun delete(root: String, relativePath: String) {
        files.remove(relativePath)
    }
    override suspend fun exists(root: String, relativePath: String): Boolean = relativePath in files
    override suspend fun move(root: String, from: String, to: String) {
        files[to] = files.remove(from) ?: error("missing $from")
    }
    override suspend fun ensureDirectory(root: String, relativePath: String) = Unit
    override suspend fun sha256(root: String, relativePath: String): String = ContentHash.sha256(read(root, relativePath))
    override suspend fun size(root: String, relativePath: String): Long = read(root, relativePath).size.toLong()
    override suspend fun modifiedAt(root: String, relativePath: String): Long = modified[relativePath] ?: 1

    fun put(path: String, text: String) {
        files[path] = text.encodeToByteArray()
        modified[path] = ++tick
    }
}
