package dev.cloudsync.sync

import dev.cloudsync.core.error.RemoteChangedException
import dev.cloudsync.core.etag.Etags
import dev.cloudsync.core.hash.ContentHash
import dev.cloudsync.core.log.DiagnosticLog
import dev.cloudsync.core.log.NoopLog
import dev.cloudsync.core.model.AccountEndpoint
import dev.cloudsync.core.model.Conflict
import dev.cloudsync.core.model.ConflictKind
import dev.cloudsync.core.model.ConflictState
import dev.cloudsync.core.model.FolderStatus
import dev.cloudsync.core.model.MergeSession
import dev.cloudsync.core.model.MergeSessionState
import dev.cloudsync.core.model.NetworkSnapshot
import dev.cloudsync.core.model.SyncDirection
import dev.cloudsync.core.model.SyncFolder
import dev.cloudsync.core.model.SyncReport
import dev.cloudsync.core.model.TrackedFile
import dev.cloudsync.core.model.TrackedState
import dev.cloudsync.core.model.Transfer
import dev.cloudsync.core.model.TransferDirection
import dev.cloudsync.core.model.TransferStatus
import dev.cloudsync.core.model.VersionOrigin
import dev.cloudsync.core.model.fileKey
import dev.cloudsync.core.path.PathFilter
import dev.cloudsync.core.path.SyncPaths
import dev.cloudsync.core.remote.ContentSource
import dev.cloudsync.core.remote.LocalFiles
import dev.cloudsync.core.remote.RemoteFiles
import dev.cloudsync.core.sync.SyncStateStore
import dev.cloudsync.core.time.Clock
import dev.cloudsync.core.time.IdGenerator
import dev.cloudsync.merge.MergeCoordinator
import dev.cloudsync.versioning.VersionRecorder
import dev.cloudsync.versioning.VersionRetention
import dev.cloudsync.versioning.VersionStore

data class SyncRequest(
    val folder: SyncFolder,
    val endpoint: AccountEndpoint,
    val network: NetworkSnapshot,
    val retention: dev.cloudsync.core.model.RetentionPolicy = dev.cloudsync.core.model.RetentionPolicy(),
    val isCancelled: () -> Boolean = { false },
)

class SyncEngine(
    private val remote: RemoteFiles,
    private val local: LocalFiles,
    private val versions: VersionStore,
    private val state: SyncStateStore,
    private val merger: MergeCoordinator = MergeCoordinator(),
    private val ids: IdGenerator,
    private val clock: Clock,
    private val log: DiagnosticLog = NoopLog,
) {
    private val recorder = VersionRecorder(versions, ids, clock)

    suspend fun synchronize(request: SyncRequest): SyncReport {
        val folder = request.folder
        if (folder.paused) {
            return report(folder.id, FolderStatus.PAUSED, 0, 0, 0, "Synchronization is paused.", emptyList())
        }
        val blocking = NetworkGate.waitReason(folder, request.network, TransferDirection.DOWNLOAD)
            ?: NetworkGate.waitReason(folder, request.network, TransferDirection.UPLOAD)
        if (blocking != null && !request.network.validated) {
            return report(folder.id, FolderStatus.WAITING_NETWORK, 0, 0, 0, blocking, emptyList())
        }
        log.info("sync", "Starting synchronization for ${folder.displayName}.", folder.accountId, folder.id)
        retryReadyMerges(request)
        val tracked = state.tracked(folder.id).toMutableList()
        val remoteNodes = remote.list(request.endpoint, folder.remotePath)
        val localNodes = local.list(folder.localRoot)
        val remoteSnapshots = remoteNodes.mapNotNull { node ->
            val relative = SyncPaths.stripPrefix(node.path, folder.remotePath) ?: return@mapNotNull null
            if (relative.isEmpty() || PathFilter.ignored(relative, folder.exclude.includeSubfolders, folder.exclude.patterns)) return@mapNotNull null
            RemoteSnapshot(relative, node.directory, node.size, node.modifiedAtEpochMillis, node.etag, node.fileId, node.contentType)
        }
        val localSnapshots = localNodes.mapNotNull { node ->
            if (node.relativePath.isEmpty() || PathFilter.ignored(node.relativePath, folder.exclude.includeSubfolders, folder.exclude.patterns)) {
                return@mapNotNull null
            }
            val previous = tracked.firstOrNull { it.localRelativePath == node.relativePath }
            val hash = if (!node.directory && (previous?.localHash == null || previous.localSize != node.size || previous.localModifiedAt != node.modifiedAt)) {
                local.sha256(folder.localRoot, node.relativePath)
            } else {
                previous?.localHash
            }
            LocalSnapshot(node.relativePath, node.directory, node.size, node.modifiedAt, hash)
        }
        val actions = SyncPlanner.plan(folder, tracked, localSnapshots, remoteSnapshots)
        var uploaded = 0
        var downloaded = 0
        var conflicts = 0
        val errors = mutableListOf<String>()
        var waiting: String? = null
        for (action in actions) {
            if (request.isCancelled()) {
                waiting = "Cancelled."
                break
            }
            try {
                when (action) {
                    is PlannedAction.Upload -> if (gate(folder, request.network, TransferDirection.UPLOAD, action.relativePath)?.also { waiting = it } == null) {
                        upload(request, tracked, action)
                        uploaded++
                    }
                    is PlannedAction.Download -> if (gate(folder, request.network, TransferDirection.DOWNLOAD, action.relativePath)?.also { waiting = it } == null) {
                        download(request, tracked, action)
                        downloaded++
                    }
                    is PlannedAction.Merge -> if (gate(folder, request.network, TransferDirection.UPLOAD, action.relativePath)?.also { waiting = it } == null &&
                        gate(folder, request.network, TransferDirection.DOWNLOAD, action.relativePath)?.also { waiting = it } == null
                    ) {
                        if (merge(request, tracked, action)) conflicts++ else uploaded++
                    }
                    is PlannedAction.Conflict -> {
                        recordConflict(request, tracked, action.relativePath, action.kind, action.summary, null, null, null)
                        conflicts++
                    }
                    is PlannedAction.DeleteRemote -> if (gate(folder, request.network, TransferDirection.UPLOAD, action.relativePath)?.also { waiting = it } == null) {
                        deleteRemote(request, tracked, action)
                    }
                    is PlannedAction.RecoverLocal -> {
                        recoverLocal(request, tracked, action.relativePath)
                    }
                    is PlannedAction.MoveRemote -> if (gate(folder, request.network, TransferDirection.UPLOAD, action.to)?.also { waiting = it } == null) {
                        moveRemote(request, tracked, action)
                    }
                    is PlannedAction.MoveLocal -> {
                        local.move(folder.localRoot, action.from, action.to)
                        updatePath(tracked, action.from, action.to)
                    }
                    is PlannedAction.EnsureRemoteDir -> if (gate(folder, request.network, TransferDirection.UPLOAD, action.relativePath)?.also { waiting = it } == null) {
                        remote.createDirectory(request.endpoint, fullRemote(folder, action.relativePath))
                    }
                    is PlannedAction.EnsureLocalDir -> local.ensureDirectory(folder.localRoot, action.relativePath)
                    is PlannedAction.Remember -> remember(request, tracked, action)
                }
            } catch (error: Exception) {
                val message = error.message ?: error.javaClass.simpleName
                errors += "${action.relativePath}: $message"
                log.error("sync", message, folder.accountId, folder.id)
                remember(request, tracked, PlannedAction.Remember(action.relativePath, TrackedState.ERROR, message))
            }
        }
        prune(request, tracked)
        val status = when {
            errors.isNotEmpty() -> FolderStatus.ERROR
            waiting == "Cancelled." -> FolderStatus.IDLE
            waiting != null -> when {
                waiting!!.contains("charging") -> FolderStatus.WAITING_CHARGING
                waiting!!.contains("battery") -> FolderStatus.WAITING_BATTERY
                else -> FolderStatus.WAITING_NETWORK
            }
            conflicts > 0 || state.conflicts(folder.id).any { it.state == ConflictState.OPEN } -> FolderStatus.CONFLICTS
            else -> FolderStatus.IDLE
        }
        return report(folder.id, status, uploaded, downloaded, conflicts, waiting, errors)
    }

    suspend fun resolve(request: SyncRequest, conflict: Conflict, mergedText: String): SyncReport = resolveBytes(request, conflict, mergedText.encodeToByteArray(), "Manual merge")

    suspend fun resolveBytes(
        request: SyncRequest,
        conflict: Conflict,
        bytes: ByteArray,
        description: String,
    ): SyncReport {
        val localVersion = conflict.localVersionId?.let { versions.get(it) }
        val remoteVersion = conflict.remoteVersionId?.let { versions.get(it) }
        val merged = recorder.snapshot(
            fileKey = conflict.fileKey,
            origin = VersionOrigin.MERGE,
            bytes = bytes,
            parents = listOfNotNull(conflict.baseVersionId, conflict.localVersionId, conflict.remoteVersionId),
            conflictId = conflict.id,
            description = description,
        )
        val session = MergeSession(
            id = ids.newId(),
            fileKey = conflict.fileKey,
            folderId = conflict.folderId,
            conflictId = conflict.id,
            baseVersionId = conflict.baseVersionId,
            localVersionId = localVersion?.id ?: merged.id,
            remoteVersionId = remoteVersion?.id ?: merged.id,
            resultVersionId = merged.id,
            state = MergeSessionState.READY_TO_UPLOAD,
            remoteEtagAtStart = remoteVersion?.remoteEtag,
            createdAt = clock.nowMillis(),
            updatedAt = clock.nowMillis(),
            error = null,
        )
        state.saveMerge(session)
        val uploaded = uploadMerged(request, session, bytes)
        return if (uploaded) {
            state.saveConflict(conflict.copy(state = ConflictState.RESOLVED, resolvedAt = clock.nowMillis()))
            report(request.folder.id, FolderStatus.IDLE, 1, 0, 0, null, emptyList())
        } else {
            report(request.folder.id, FolderStatus.CONFLICTS, 0, 0, 1, null, listOf("The server copy changed again. The merge was kept locally in history and was not uploaded."))
        }
    }

    suspend fun restore(request: SyncRequest, relativePath: String, bytes: ByteArray): SyncReport {
        val relative = SyncPaths.normalizeRelative(relativePath)
        val tracked = state.tracked(request.folder.id).toMutableList()
        val existing = find(tracked, relative)
        if (local.exists(request.folder.localRoot, relative)) {
            val current = local.read(request.folder.localRoot, relative)
            recorder.snapshot(key(request, relative), VersionOrigin.LOCAL, current, description = "Snapshot before restore")
        }
        val restored = recorder.snapshot(key(request, relative), VersionOrigin.LOCAL, bytes, description = "Restored version")
        local.writeAtomically(request.folder.localRoot, relative, bytes)
        val hash = ContentHash.sha256(bytes)
        if (request.folder.direction == SyncDirection.DOWNLOAD_ONLY) {
            val kept = (existing ?: blankTracked(request, relative)).copy(
                localHash = hash,
                localSize = bytes.size.toLong(),
                state = TrackedState.LOCAL_DIRTY,
                pendingOperation = "Restored locally. Download-only folders do not upload.",
                conflictId = null,
            )
            state.upsert(kept)
            return report(request.folder.id, FolderStatus.IDLE, 0, 0, 0, kept.pendingOperation, emptyList())
        }
        val transfer = beginTransfer(request, relative, TransferDirection.UPLOAD, bytes.size.toLong())
        return try {
            val result = remote.upload(
                request.endpoint,
                fullRemote(request.folder, relative),
                ContentSource { bytes.inputStream() },
                bytes.size.toLong(),
                "application/octet-stream",
                existing?.baseEtag,
            )
            if (local.sha256(request.folder.localRoot, relative) != hash) {
                finishTransfer(transfer, TransferStatus.FAILED, "The local file changed during restore.")
                return report(request.folder.id, FolderStatus.ERROR, 0, 0, 0, null, listOf("The local file changed during restore. The server base was left unchanged."))
            }
            saveTracked(tracked, existing, request, relative, hash, result.etag, restored.id, TrackedState.IN_SYNC, bytes.size.toLong())
            finishTransfer(transfer, TransferStatus.COMPLETED, null)
            report(request.folder.id, FolderStatus.IDLE, 1, 0, 0, null, emptyList())
        } catch (error: RemoteChangedException) {
            finishTransfer(transfer, TransferStatus.FAILED, error.message)
            recordConflict(request, tracked, relative, ConflictKind.CONTENT, "The server copy changed during restore. The restored bytes stay on this device.", existing?.baseVersionId, restored.id, null)
            report(request.folder.id, FolderStatus.CONFLICTS, 0, 0, 1, null, listOf("The server copy changed. Nothing on the server was overwritten."))
        }
    }

    private suspend fun retryReadyMerges(request: SyncRequest) {
        state.mergeSessions(request.folder.id)
            .filter { it.state == MergeSessionState.READY_TO_UPLOAD || it.state == MergeSessionState.UPLOADING }
            .forEach { session ->
                val bytes = session.resultVersionId?.let { versions.content(it) } ?: return@forEach
                uploadMerged(request, session, bytes)
            }
    }

    private suspend fun uploadMerged(request: SyncRequest, session: MergeSession, bytes: ByteArray): Boolean {
        val relative = session.fileKey.substringAfter('|').removePrefix(request.folder.remotePath).trim('/')
        if (request.folder.direction == SyncDirection.DOWNLOAD_ONLY) {
            local.writeAtomically(request.folder.localRoot, relative, bytes)
            val tracked = state.tracked(request.folder.id).firstOrNull { it.fileKey == session.fileKey }
            if (tracked != null) {
                state.upsert(
                    tracked.copy(
                        localHash = ContentHash.sha256(bytes),
                        localSize = bytes.size.toLong(),
                        state = TrackedState.LOCAL_DIRTY,
                        conflictId = null,
                        pendingOperation = "Download-only folders keep the resolution locally.",
                    ),
                )
            }
            state.saveMerge(session.copy(state = MergeSessionState.COMMITTED, updatedAt = clock.nowMillis(), error = null))
            return true
        }
        state.saveMerge(session.copy(state = MergeSessionState.UPLOADING, updatedAt = clock.nowMillis()))
        return try {
            val result = remote.upload(
                request.endpoint,
                fullRemote(request.folder, relative),
                ContentSource { bytes.inputStream() },
                bytes.size.toLong(),
                "application/octet-stream",
                session.remoteEtagAtStart,
            )
            val currentHash = runCatching { local.sha256(request.folder.localRoot, relative) }.getOrNull()
            val localVersionHash = versions.get(session.localVersionId)?.sha256
            if (currentHash == null || currentHash == localVersionHash) {
                local.writeAtomically(request.folder.localRoot, relative, bytes)
            }
            val tracked = state.tracked(request.folder.id).firstOrNull { it.fileKey == session.fileKey }
            if (tracked != null && (currentHash == null || currentHash == localVersionHash)) {
                state.upsert(
                    tracked.copy(
                        localHash = ContentHash.sha256(bytes),
                        baseHash = ContentHash.sha256(bytes),
                        baseEtag = result.etag,
                        remoteEtag = result.etag,
                        baseVersionId = session.resultVersionId,
                        state = TrackedState.IN_SYNC,
                        conflictId = null,
                        lastSyncedAt = clock.nowMillis(),
                        pendingOperation = null,
                    ),
                )
            }
            state.saveMerge(session.copy(state = MergeSessionState.COMMITTED, updatedAt = clock.nowMillis(), error = null))
            true
        } catch (error: RemoteChangedException) {
            state.saveMerge(session.copy(state = MergeSessionState.ABORTED, updatedAt = clock.nowMillis(), error = error.message))
            log.error("merge", "Upload aborted because the remote file changed.", request.folder.accountId, request.folder.id)
            false
        }
    }

    private suspend fun upload(request: SyncRequest, tracked: MutableList<TrackedFile>, action: PlannedAction.Upload) {
        val bytes = local.read(request.folder.localRoot, action.relativePath)
        val hash = ContentHash.sha256(bytes)
        val existing = find(tracked, action.relativePath)
        if (existing?.baseHash == hash && Etags.same(existing.baseEtag, existing.remoteEtag)) return
        val transfer = beginTransfer(request, action.relativePath, TransferDirection.UPLOAD, bytes.size.toLong())
        try {
            val result = remote.upload(
                request.endpoint,
                fullRemote(request.folder, action.relativePath),
                ContentSource { bytes.inputStream() },
                bytes.size.toLong(),
                "application/octet-stream",
                action.ifMatch,
            )
            val version = recorder.snapshot(key(request, action.relativePath), VersionOrigin.UPLOAD, bytes, remoteEtag = result.etag)
            saveTracked(
                tracked,
                existing,
                request,
                action.relativePath,
                hash,
                result.etag,
                version.id,
                TrackedState.IN_SYNC,
                bytes.size.toLong(),
            )
            finishTransfer(transfer, TransferStatus.COMPLETED, result.etag)
        } catch (error: RemoteChangedException) {
            finishTransfer(transfer, TransferStatus.FAILED, error.message)
            recordConflict(request, tracked, action.relativePath, ConflictKind.CONTENT, "The server copy changed during the upload.", null, null, null)
        }
    }

    private suspend fun download(request: SyncRequest, tracked: MutableList<TrackedFile>, action: PlannedAction.Download) {
        val existing = find(tracked, action.relativePath)
        val before = if (local.exists(request.folder.localRoot, action.relativePath)) local.sha256(request.folder.localRoot, action.relativePath) else null
        val transfer = beginTransfer(request, action.relativePath, TransferDirection.DOWNLOAD, 0)
        val downloaded = remote.download(request.endpoint, fullRemote(request.folder, action.relativePath), null)
        val after = if (local.exists(request.folder.localRoot, action.relativePath)) local.sha256(request.folder.localRoot, action.relativePath) else null
        if (before != after) {
            finishTransfer(transfer, TransferStatus.FAILED, "Local file changed during download.")
            recordConflict(request, tracked, action.relativePath, ConflictKind.CONTENT, "The local file changed while it was being downloaded.", null, null, null)
            return
        }
        if (before != null) {
            recorder.snapshot(key(request, action.relativePath), VersionOrigin.LOCAL, local.read(request.folder.localRoot, action.relativePath))
        }
        local.writeAtomically(request.folder.localRoot, action.relativePath, downloaded.bytes)
        val hash = ContentHash.sha256(downloaded.bytes)
        val version = recorder.snapshot(key(request, action.relativePath), VersionOrigin.DOWNLOAD, downloaded.bytes, remoteEtag = downloaded.etag)
        saveTracked(tracked, existing, request, action.relativePath, hash, downloaded.etag, version.id, TrackedState.IN_SYNC, downloaded.bytes.size.toLong())
        finishTransfer(transfer.copy(totalBytes = downloaded.bytes.size.toLong(), bytesTransferred = downloaded.bytes.size.toLong()), TransferStatus.COMPLETED, null)
    }

    private suspend fun merge(request: SyncRequest, tracked: MutableList<TrackedFile>, action: PlannedAction.Merge): Boolean {
        val existing = find(tracked, action.relativePath) ?: return true
        val localBytes = local.read(request.folder.localRoot, action.relativePath)
        val localHash = ContentHash.sha256(localBytes)
        val downloaded = remote.download(request.endpoint, fullRemote(request.folder, action.relativePath), null)
        if (action.remoteEtag != null && downloaded.etag != null && !Etags.same(action.remoteEtag, downloaded.etag)) {
            recordConflict(request, tracked, action.relativePath, ConflictKind.CONTENT, "The server copy changed before the merge started.", null, null, null)
            return true
        }
        val baseBytes = existing.baseVersionId?.let { versions.content(it) }
        val localVersion = recorder.snapshot(existing.fileKey, VersionOrigin.LOCAL, localBytes)
        val remoteVersion = recorder.snapshot(existing.fileKey, VersionOrigin.REMOTE, downloaded.bytes, remoteEtag = downloaded.etag)
        if (baseBytes == null) {
            recordConflict(request, tracked, action.relativePath, ConflictKind.CONTENT, "There is no common base version to merge against.", existing.baseVersionId, localVersion.id, remoteVersion.id)
            return true
        }
        val merged = merger.merge(SyncPaths.name(action.relativePath), downloaded.contentType, baseBytes, localBytes, downloaded.bytes)
        val mergedText = merged.mergedText
        if (!merged.clean || mergedText == null) {
            val kind = when {
                merged.binary -> ConflictKind.BINARY
                merged.validationError?.contains("UTF-8") == true -> ConflictKind.ENCODING
                else -> ConflictKind.CONTENT
            }
            recordConflict(
                request,
                tracked,
                action.relativePath,
                kind,
                merged.validationError ?: "The copies changed in the same place.",
                existing.baseVersionId,
                localVersion.id,
                remoteVersion.id,
            )
            return true
        }
        val bytes = mergedText.encodeToByteArray()
        val result = recorder.snapshot(
            existing.fileKey,
            VersionOrigin.MERGE,
            bytes,
            parents = listOfNotNull(existing.baseVersionId, localVersion.id, remoteVersion.id),
            description = "Automatic three-way merge",
            mergeMetadata = merged.strategy,
        )
        if (local.sha256(request.folder.localRoot, action.relativePath) != localHash) {
            recordConflict(request, tracked, action.relativePath, ConflictKind.CONTENT, "The local file changed during the merge.", existing.baseVersionId, localVersion.id, remoteVersion.id)
            return true
        }
        val session = MergeSession(
            id = ids.newId(),
            fileKey = existing.fileKey,
            folderId = request.folder.id,
            conflictId = null,
            baseVersionId = existing.baseVersionId,
            localVersionId = localVersion.id,
            remoteVersionId = remoteVersion.id,
            resultVersionId = result.id,
            state = MergeSessionState.READY_TO_UPLOAD,
            remoteEtagAtStart = downloaded.etag,
            createdAt = clock.nowMillis(),
            updatedAt = clock.nowMillis(),
            error = null,
        )
        state.saveMerge(session)
        val uploaded = uploadMerged(request, session, bytes)
        if (!uploaded) {
            recordConflict(request, tracked, action.relativePath, ConflictKind.CONTENT, "The server copy changed during the merge upload. The original local file was left in place.", existing.baseVersionId, localVersion.id, remoteVersion.id)
            return true
        }
        return false
    }

    private suspend fun deleteRemote(request: SyncRequest, tracked: MutableList<TrackedFile>, action: PlannedAction.DeleteRemote) {
        remote.delete(request.endpoint, fullRemote(request.folder, action.relativePath), action.ifMatch)
        find(tracked, action.relativePath)?.let {
            state.deleteTracked(it.id)
            tracked.removeAll { file -> file.id == it.id }
        }
    }

    private suspend fun recoverLocal(request: SyncRequest, tracked: MutableList<TrackedFile>, relative: String) {
        val bytes = runCatching { local.read(request.folder.localRoot, relative) }.getOrNull()
        if (bytes != null) recorder.snapshot(key(request, relative), VersionOrigin.LOCAL, bytes, description = "Moved to recovery before a remote deletion was applied.")
        local.moveToRecovery(request.folder.localRoot, relative, request.folder.localRoot + "/.cloudsync/recovery")
        find(tracked, relative)?.let {
            state.deleteTracked(it.id)
            tracked.removeAll { file -> file.id == it.id }
        }
    }

    private suspend fun moveRemote(request: SyncRequest, tracked: MutableList<TrackedFile>, action: PlannedAction.MoveRemote) {
        remote.move(request.endpoint, fullRemote(request.folder, action.from), fullRemote(request.folder, action.to), overwrite = false)
        updatePath(tracked, action.from, action.to)
    }

    private suspend fun updatePath(tracked: MutableList<TrackedFile>, from: String, to: String) {
        val file = find(tracked, from) ?: return
        val updated = file.copy(localRelativePath = to, remoteRelativePath = to, fileKey = file.fileKey.substringBefore('|') + "|" + fullRemotePath(file, to))
        state.upsert(updated)
        tracked.removeAll { it.id == file.id }
        tracked += updated
    }

    private fun fullRemotePath(file: TrackedFile, relative: String): String {
        val root = file.fileKey.substringAfter('|').removeSuffix(file.remoteRelativePath).trimEnd('/')
        return if (root.isEmpty()) "/$relative" else "$root/$relative"
    }

    private suspend fun remember(request: SyncRequest, tracked: MutableList<TrackedFile>, action: PlannedAction.Remember) {
        val existing = find(tracked, action.relativePath)
        val file = (
            existing ?: TrackedFile(
                id = ids.newId(),
                folderId = request.folder.id,
                accountId = request.folder.accountId,
                fileKey = key(request, action.relativePath),
                localRelativePath = action.relativePath,
                remoteRelativePath = action.relativePath,
                remoteFileId = null,
                localModifiedAt = null,
                remoteModifiedAt = null,
                localSize = null,
                remoteSize = null,
                localHash = null,
                remoteEtag = null,
                baseVersionId = null,
                baseHash = null,
                baseEtag = null,
                lastSyncedAt = null,
                state = action.state,
                pendingOperation = action.summary,
                conflictId = null,
            )
            ).copy(state = action.state, pendingOperation = action.summary)
        state.upsert(file)
        tracked.removeAll { it.id == file.id }
        tracked += file
    }

    private suspend fun recordConflict(
        request: SyncRequest,
        tracked: MutableList<TrackedFile>,
        relative: String,
        kind: ConflictKind,
        summary: String,
        baseVersionId: String?,
        localVersionId: String?,
        remoteVersionId: String?,
    ) {
        val existing = find(tracked, relative)
        val conflict = Conflict(
            id = ids.newId(),
            fileKey = existing?.fileKey ?: key(request, relative),
            folderId = request.folder.id,
            accountId = request.folder.accountId,
            remotePath = fullRemote(request.folder, relative),
            kind = kind,
            state = ConflictState.OPEN,
            createdAt = clock.nowMillis(),
            resolvedAt = null,
            baseVersionId = baseVersionId ?: existing?.baseVersionId,
            localVersionId = localVersionId,
            remoteVersionId = remoteVersionId,
            summary = summary,
        )
        state.saveConflict(conflict)
        val file = (
            existing ?: TrackedFile(
                id = ids.newId(),
                folderId = request.folder.id,
                accountId = request.folder.accountId,
                fileKey = conflict.fileKey,
                localRelativePath = relative,
                remoteRelativePath = relative,
                remoteFileId = null,
                localModifiedAt = null,
                remoteModifiedAt = null,
                localSize = null,
                remoteSize = null,
                localHash = null,
                remoteEtag = null,
                baseVersionId = baseVersionId,
                baseHash = null,
                baseEtag = null,
                lastSyncedAt = null,
                state = TrackedState.CONFLICT,
                pendingOperation = summary,
                conflictId = conflict.id,
            )
            ).copy(state = TrackedState.CONFLICT, conflictId = conflict.id, pendingOperation = summary)
        state.upsert(file)
        tracked.removeAll { it.id == file.id }
        tracked += file
    }

    private suspend fun saveTracked(
        tracked: MutableList<TrackedFile>,
        existing: TrackedFile?,
        request: SyncRequest,
        relative: String,
        hash: String,
        etag: String?,
        versionId: String,
        state: TrackedState,
        size: Long,
    ) {
        val file = (
            existing ?: TrackedFile(
                id = ids.newId(),
                folderId = request.folder.id,
                accountId = request.folder.accountId,
                fileKey = key(request, relative),
                localRelativePath = relative,
                remoteRelativePath = relative,
                remoteFileId = null,
                localModifiedAt = clock.nowMillis(),
                remoteModifiedAt = clock.nowMillis(),
                localSize = size,
                remoteSize = size,
                localHash = hash,
                remoteEtag = etag,
                baseVersionId = versionId,
                baseHash = hash,
                baseEtag = etag,
                lastSyncedAt = clock.nowMillis(),
                state = state,
                pendingOperation = null,
                conflictId = null,
            )
            ).copy(
            localHash = hash,
            remoteEtag = etag,
            baseHash = hash,
            baseEtag = etag,
            baseVersionId = versionId,
            localSize = size,
            remoteSize = size,
            state = state,
            lastSyncedAt = clock.nowMillis(),
            pendingOperation = null,
            conflictId = null,
        )
        this.state.upsert(file)
        tracked.removeAll { it.id == file.id }
        tracked += file
    }

    private suspend fun beginTransfer(request: SyncRequest, relative: String, direction: TransferDirection, size: Long): Transfer {
        val transfer = Transfer(
            id = ids.newId(),
            accountId = request.folder.accountId,
            folderId = request.folder.id,
            fileKey = key(request, relative),
            direction = direction,
            remotePath = fullRemote(request.folder, relative),
            localPath = relative,
            bytesTransferred = 0,
            totalBytes = size,
            status = TransferStatus.RUNNING,
            error = null,
            retryCount = 0,
            nextRetryAt = null,
            createdAt = clock.nowMillis(),
            updatedAt = clock.nowMillis(),
            uploadId = null,
            nextChunk = 1,
        )
        state.saveTransfer(transfer)
        return transfer
    }

    private suspend fun finishTransfer(transfer: Transfer, status: TransferStatus, error: String?) {
        state.saveTransfer(transfer.copy(status = status, error = error, updatedAt = clock.nowMillis(), bytesTransferred = if (status == TransferStatus.COMPLETED) transfer.totalBytes else transfer.bytesTransferred))
    }

    private suspend fun prune(request: SyncRequest, tracked: List<TrackedFile>) {
        val protected = tracked.flatMap { listOfNotNull(it.baseVersionId, it.conflictId) }.toSet() +
            state.conflicts(request.folder.id).filter { it.state == ConflictState.OPEN }.flatMap { listOfNotNull(it.baseVersionId, it.localVersionId, it.remoteVersionId) } +
            state.mergeSessions(request.folder.id).filter { it.state != MergeSessionState.COMMITTED }.flatMap { listOfNotNull(it.baseVersionId, it.localVersionId, it.remoteVersionId, it.resultVersionId) }
        val versionsForFolder = tracked.flatMap { versions.history(it.fileKey) }
        VersionRetention.idsToPrune(versionsForFolder, protected, request.retention).forEach { versions.delete(it) }
    }

    private fun gate(folder: SyncFolder, network: NetworkSnapshot, direction: TransferDirection, path: String): String? = NetworkGate.waitReason(folder, network, direction)

    private fun blankTracked(request: SyncRequest, relative: String) = TrackedFile(
        id = ids.newId(),
        folderId = request.folder.id,
        accountId = request.folder.accountId,
        fileKey = key(request, relative),
        localRelativePath = relative,
        remoteRelativePath = relative,
        remoteFileId = null,
        localModifiedAt = clock.nowMillis(),
        remoteModifiedAt = null,
        localSize = null,
        remoteSize = null,
        localHash = null,
        remoteEtag = null,
        baseVersionId = null,
        baseHash = null,
        baseEtag = null,
        lastSyncedAt = null,
        state = TrackedState.LOCAL_DIRTY,
        pendingOperation = null,
        conflictId = null,
    )

    private fun find(tracked: List<TrackedFile>, relative: String): TrackedFile? = tracked.firstOrNull { it.remoteRelativePath == relative || it.localRelativePath == relative }

    private fun key(request: SyncRequest, relative: String): String = fileKey(request.folder.accountId, fullRemote(request.folder, relative))

    private fun fullRemote(folder: SyncFolder, relative: String): String = SyncPaths.child(folder.remotePath, relative).let { "/$it" }

    private fun report(folderId: String, status: FolderStatus, uploaded: Int, downloaded: Int, conflicts: Int, waiting: String?, errors: List<String>) = SyncReport(folderId, status, uploaded, downloaded, conflicts, waiting, errors)
}
