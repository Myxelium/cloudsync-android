package dev.cloudsync.sync

import dev.cloudsync.core.etag.Etags
import dev.cloudsync.core.model.ConflictKind
import dev.cloudsync.core.model.DeletionPolicy
import dev.cloudsync.core.model.SyncDirection
import dev.cloudsync.core.model.SyncFolder
import dev.cloudsync.core.model.TrackedFile
import dev.cloudsync.core.model.TrackedState
import dev.cloudsync.core.text.TextFiles

data class LocalSnapshot(val relativePath: String, val directory: Boolean, val size: Long, val modifiedAt: Long, val hash: String?)

data class RemoteSnapshot(
    val relativePath: String,
    val directory: Boolean,
    val size: Long,
    val modifiedAt: Long?,
    val etag: String?,
    val fileId: String?,
    val contentType: String?,
)

sealed interface PlannedAction {
    val relativePath: String

    data class Upload(override val relativePath: String, val ifMatch: String?) : PlannedAction
    data class Download(override val relativePath: String, val etag: String?) : PlannedAction
    data class Merge(override val relativePath: String, val remoteEtag: String?) : PlannedAction
    data class Conflict(override val relativePath: String, val kind: ConflictKind, val summary: String) : PlannedAction
    data class DeleteRemote(override val relativePath: String, val ifMatch: String?) : PlannedAction
    data class RecoverLocal(override val relativePath: String) : PlannedAction
    data class MoveRemote(val from: String, val to: String, val ifMatch: String?) : PlannedAction {
        override val relativePath: String = to
    }
    data class MoveLocal(val from: String, val to: String) : PlannedAction {
        override val relativePath: String = to
    }
    data class EnsureRemoteDir(override val relativePath: String) : PlannedAction
    data class EnsureLocalDir(override val relativePath: String) : PlannedAction
    data class Remember(override val relativePath: String, val state: TrackedState, val summary: String) : PlannedAction
}

object SyncPlanner {
    fun plan(
        folder: SyncFolder,
        tracked: List<TrackedFile>,
        local: List<LocalSnapshot>,
        remote: List<RemoteSnapshot>,
    ): List<PlannedAction> {
        val actions = mutableListOf<PlannedAction>()
        val localFiles = local.filter { !it.directory }.associateBy { it.relativePath }
        val remoteFiles = remote.filter { !it.directory }.associateBy { it.relativePath }
        val handled = mutableSetOf<String>()

        planDirectories(folder, local, remote, actions)
        planRenames(folder, tracked, localFiles, remoteFiles, actions, handled)

        val paths = (localFiles.keys + remoteFiles.keys + tracked.map { it.remoteRelativePath })
            .filter { it.isNotEmpty() && it !in handled }
            .distinct()
            .sorted()
        for (path in paths) {
            actions += planFile(
                folder,
                tracked.firstOrNull { it.remoteRelativePath == path || it.localRelativePath == path },
                localFiles[path],
                remoteFiles[path],
                path,
            )
        }
        return actions
    }

    private fun planDirectories(
        folder: SyncFolder,
        local: List<LocalSnapshot>,
        remote: List<RemoteSnapshot>,
        actions: MutableList<PlannedAction>,
    ) {
        val localDirs = local.filter { it.directory }.map { it.relativePath }.toSet()
        val remoteDirs = remote.filter { it.directory }.map { it.relativePath }.toSet()
        if (folder.direction != SyncDirection.DOWNLOAD_ONLY) {
            localDirs.filter { it !in remoteDirs && it.isNotEmpty() }.sorted().forEach {
                actions += PlannedAction.EnsureRemoteDir(it)
            }
        }
        if (folder.direction != SyncDirection.UPLOAD_ONLY) {
            remoteDirs.filter { it !in localDirs && it.isNotEmpty() }.sorted().forEach {
                actions += PlannedAction.EnsureLocalDir(it)
            }
        }
    }

    private fun planRenames(
        folder: SyncFolder,
        tracked: List<TrackedFile>,
        localFiles: Map<String, LocalSnapshot>,
        remoteFiles: Map<String, RemoteSnapshot>,
        actions: MutableList<PlannedAction>,
        handled: MutableSet<String>,
    ) {
        val untrackedLocal = localFiles.filterKeys { path -> tracked.none { it.localRelativePath == path } }
        val remoteById = remoteFiles.values.mapNotNull { node -> node.fileId?.let { it to node } }.toMap()
        for (file in tracked) {
            val localGone = localFiles[file.localRelativePath] == null
            val remoteNode = remoteFiles[file.remoteRelativePath]
            val renamedRemote = file.remoteFileId?.let { remoteById[it] }?.takeIf { it.relativePath != file.remoteRelativePath }
            val renamedLocal = if (localGone && file.localHash != null) {
                untrackedLocal.values.filter { it.hash == file.localHash }
            } else {
                emptyList()
            }
            val localRename = renamedLocal.singleOrNull()
            if (localRename != null && renamedRemote != null && localRename.relativePath != renamedRemote.relativePath) {
                actions += PlannedAction.Conflict(
                    file.remoteRelativePath,
                    ConflictKind.RENAME,
                    "The file was renamed locally to ${localRename.relativePath} and remotely to ${renamedRemote.relativePath}.",
                )
                handled += file.remoteRelativePath
                handled += localRename.relativePath
                handled += renamedRemote.relativePath
                continue
            }
            if (localRename != null && remoteNode != null && Etags.same(remoteNode.etag, file.baseEtag)) {
                if (folder.direction != SyncDirection.DOWNLOAD_ONLY) {
                    actions += PlannedAction.MoveRemote(file.remoteRelativePath, localRename.relativePath, file.baseEtag)
                } else {
                    actions +=
                        PlannedAction.Remember(
                            file.remoteRelativePath,
                            TrackedState.LOCAL_DIRTY,
                            "Download-only folders do not upload renames.",
                        )
                }
                handled += file.remoteRelativePath
                handled += localRename.relativePath
            } else if (renamedRemote != null && !localGone && file.localHash != null && file.localHash == file.baseHash) {
                if (folder.direction != SyncDirection.UPLOAD_ONLY) {
                    actions += PlannedAction.MoveLocal(file.localRelativePath, renamedRemote.relativePath)
                } else {
                    actions +=
                        PlannedAction.Remember(
                            file.remoteRelativePath,
                            TrackedState.REMOTE_DIRTY,
                            "Upload-only folders do not follow remote renames.",
                        )
                }
                handled += file.remoteRelativePath
                handled += renamedRemote.relativePath
            }
        }
    }

    private fun planFile(
        folder: SyncFolder,
        tracked: TrackedFile?,
        local: LocalSnapshot?,
        remote: RemoteSnapshot?,
        path: String,
    ): PlannedAction {
        val uploads = folder.direction != SyncDirection.DOWNLOAD_ONLY
        val downloads = folder.direction != SyncDirection.UPLOAD_ONLY
        if (tracked == null && local == null && remote != null) {
            return if (downloads) {
                PlannedAction.Download(path, remote.etag)
            } else {
                PlannedAction.Remember(path, TrackedState.REMOTE_DIRTY, "Upload-only folders do not download new files.")
            }
        }
        if (tracked == null && local != null && remote == null) {
            return if (uploads) {
                PlannedAction.Upload(path, null)
            } else {
                PlannedAction.Remember(path, TrackedState.LOCAL_DIRTY, "Download-only folders do not upload new files.")
            }
        }
        if (tracked == null && local != null && remote != null) {
            return PlannedAction.Conflict(
                path,
                if (textLike(path, remote.contentType)) ConflictKind.CONTENT else ConflictKind.BINARY,
                "Both a local and a remote copy existed before CloudSync had a common base. Neither copy was overwritten.",
            )
        }
        if (tracked != null && local == null && remote == null) {
            return PlannedAction.Remember(path, TrackedState.IN_SYNC, "The file is gone on both sides.")
        }
        if (tracked != null && local == null && remote != null) {
            val remoteChanged = !Etags.same(remote.etag, tracked.baseEtag)
            if (remoteChanged) {
                return PlannedAction.Conflict(
                    path,
                    ConflictKind.DELETE_LOCAL_MODIFY_REMOTE,
                    "The local file was deleted, and the server copy also changed.",
                )
            }
            return if (folder.deletionPolicy == DeletionPolicy.PROPAGATE && uploads) {
                PlannedAction.DeleteRemote(path, tracked.baseEtag)
            } else {
                PlannedAction.Remember(path, TrackedState.LOCAL_MISSING, "Local file is missing. The server copy was kept.")
            }
        }
        if (tracked != null && local != null && remote == null) {
            val localChanged = local.hash != null && local.hash != tracked.baseHash
            if (localChanged) {
                return PlannedAction.Conflict(
                    path,
                    ConflictKind.DELETE_REMOTE_MODIFY_LOCAL,
                    "The server file was deleted, and the local copy also changed.",
                )
            }
            return if (folder.deletionPolicy == DeletionPolicy.PROPAGATE && downloads) {
                PlannedAction.RecoverLocal(path)
            } else {
                PlannedAction.Remember(path, TrackedState.REMOTE_MISSING, "The server file is missing. The local copy was kept.")
            }
        }
        if (local == null || remote == null || tracked == null) {
            return PlannedAction.Remember(path, TrackedState.ERROR, "The file could not be compared.")
        }
        val localChanged = local.hash != null && local.hash != tracked.baseHash
        val remoteChanged = !Etags.same(remote.etag, tracked.baseEtag)
        return when {
            !localChanged && !remoteChanged -> PlannedAction.Remember(path, TrackedState.IN_SYNC, "Up to date.")
            localChanged && !remoteChanged && uploads -> PlannedAction.Upload(path, tracked.baseEtag)
            localChanged && !remoteChanged -> PlannedAction.Remember(
                path,
                TrackedState.LOCAL_DIRTY,
                "Download-only folders do not upload local edits.",
            )
            !localChanged && remoteChanged && downloads -> PlannedAction.Download(path, remote.etag)
            !localChanged && remoteChanged -> PlannedAction.Remember(
                path,
                TrackedState.REMOTE_DIRTY,
                "Upload-only folders do not download remote edits.",
            )
            uploads && downloads && textLike(path, remote.contentType) -> PlannedAction.Merge(path, remote.etag)
            else -> PlannedAction.Conflict(
                path,
                if (textLike(path, remote.contentType)) ConflictKind.CONTENT else ConflictKind.BINARY,
                "Both copies changed. Automatic ${if (textLike(
                        path,
                        remote.contentType,
                    )
                ) {
                    "upload"
                } else {
                    "merging"
                }} is not allowed for this folder or file type.",
            )
        }
    }

    private fun textLike(path: String, mime: String?): Boolean = TextFiles.kindFor(path.substringAfterLast('/'), mime) != null
}
