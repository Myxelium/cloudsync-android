package dev.cloudsync.database

import dev.cloudsync.core.model.Account
import dev.cloudsync.core.model.AccountStatus
import dev.cloudsync.core.model.Conflict
import dev.cloudsync.core.model.ConflictKind
import dev.cloudsync.core.model.ConflictState
import dev.cloudsync.core.model.DeletionPolicy
import dev.cloudsync.core.model.DiagnosticEvent
import dev.cloudsync.core.model.ExcludeRules
import dev.cloudsync.core.model.FileVersion
import dev.cloudsync.core.model.FolderStatus
import dev.cloudsync.core.model.MergeSession
import dev.cloudsync.core.model.MergeSessionState
import dev.cloudsync.core.model.NetworkMode
import dev.cloudsync.core.model.ScheduleMode
import dev.cloudsync.core.model.SyncDirection
import dev.cloudsync.core.model.SyncFolder
import dev.cloudsync.core.model.TrackedFile
import dev.cloudsync.core.model.TrackedState
import dev.cloudsync.core.model.Transfer
import dev.cloudsync.core.model.TransferDirection
import dev.cloudsync.core.model.TransferStatus
import dev.cloudsync.core.model.VersionOrigin

private const val LIST_SEPARATOR = "\u001f"

internal fun parentsToColumn(ids: List<String>): String = ids.joinToString(LIST_SEPARATOR)

internal fun parentsFromColumn(value: String): List<String> = if (value.isEmpty()) emptyList() else value.split(LIST_SEPARATOR)

internal fun patternsToColumn(patterns: List<String>): String = patterns.joinToString("\n")

internal fun patternsFromColumn(value: String): List<String> = value.lines().map { it.trim() }.filter { it.isNotEmpty() }

fun AccountEntity.toModel() = Account(id, serverUrl, loginName, userId, displayName, AccountStatus.valueOf(status), createdAt, lastValidatedAt)

fun Account.toEntity() = AccountEntity(id, serverUrl, loginName, userId, displayName, status.name, createdAt, lastValidatedAt)

fun FolderEntity.toModel() = SyncFolder(
    id = id,
    accountId = accountId,
    displayName = displayName,
    remotePath = remotePath,
    localRoot = localRoot,
    direction = SyncDirection.valueOf(direction),
    exclude = ExcludeRules(includeSubfolders, patternsFromColumn(excludePatterns)),
    paused = paused,
    networkMode = NetworkMode.valueOf(networkMode),
    allowUploadOnWifi = allowUploadOnWifi,
    allowUploadOnMobile = allowUploadOnMobile,
    allowDownloadOnWifi = allowDownloadOnWifi,
    allowDownloadOnMobile = allowDownloadOnMobile,
    requireCharging = requireCharging,
    minBatteryPercent = minBatteryPercent,
    schedule = ScheduleMode.valueOf(schedule),
    deletionPolicy = DeletionPolicy.valueOf(deletionPolicy),
    lastSuccessAt = lastSuccessAt,
    lastError = lastError,
    status = FolderStatus.valueOf(status),
)

fun SyncFolder.toEntity() = FolderEntity(
    id = id,
    accountId = accountId,
    displayName = displayName,
    remotePath = remotePath,
    localRoot = localRoot,
    direction = direction.name,
    includeSubfolders = exclude.includeSubfolders,
    excludePatterns = patternsToColumn(exclude.patterns),
    paused = paused,
    networkMode = networkMode.name,
    allowUploadOnWifi = allowUploadOnWifi,
    allowUploadOnMobile = allowUploadOnMobile,
    allowDownloadOnWifi = allowDownloadOnWifi,
    allowDownloadOnMobile = allowDownloadOnMobile,
    requireCharging = requireCharging,
    minBatteryPercent = minBatteryPercent,
    schedule = schedule.name,
    deletionPolicy = deletionPolicy.name,
    lastSuccessAt = lastSuccessAt,
    lastError = lastError,
    status = status.name,
)

fun TrackedFileEntity.toModel() = TrackedFile(
    id, folderId, accountId, fileKey, localRelativePath, remoteRelativePath, remoteFileId,
    localModifiedAt, remoteModifiedAt, localSize, remoteSize, localHash, remoteEtag,
    baseVersionId, baseHash, baseEtag, lastSyncedAt, TrackedState.valueOf(state), pendingOperation, conflictId,
)

fun TrackedFile.toEntity() = TrackedFileEntity(
    id, folderId, accountId, fileKey, localRelativePath, remoteRelativePath, remoteFileId,
    localModifiedAt, remoteModifiedAt, localSize, remoteSize, localHash, remoteEtag,
    baseVersionId, baseHash, baseEtag, lastSyncedAt, state.name, pendingOperation, conflictId,
)

fun FileVersionEntity.toModel() = FileVersion(
    id, fileKey, parentsFromColumn(parentIds), createdAt, sha256, size, VersionOrigin.valueOf(origin),
    conflictId, description, remoteEtag, mergeMetadata, hasSnapshot,
)

fun FileVersion.toEntity() = FileVersionEntity(
    id, fileKey, parentsToColumn(parentIds), createdAt, sha256, size, origin.name,
    conflictId, description, remoteEtag, mergeMetadata, hasSnapshot,
)

fun ConflictEntity.toModel() = Conflict(
    id, fileKey, folderId, accountId, remotePath, ConflictKind.valueOf(kind), ConflictState.valueOf(state),
    createdAt, resolvedAt, baseVersionId, localVersionId, remoteVersionId, summary,
)

fun Conflict.toEntity() = ConflictEntity(
    id, fileKey, folderId, accountId, remotePath, kind.name, state.name,
    createdAt, resolvedAt, baseVersionId, localVersionId, remoteVersionId, summary,
)

fun MergeSessionEntity.toModel() = MergeSession(
    id, fileKey, folderId, conflictId, baseVersionId, localVersionId, remoteVersionId, resultVersionId,
    MergeSessionState.valueOf(state), remoteEtagAtStart, createdAt, updatedAt, error,
)

fun MergeSession.toEntity() = MergeSessionEntity(
    id, fileKey, folderId, conflictId, baseVersionId, localVersionId, remoteVersionId, resultVersionId,
    state.name, remoteEtagAtStart, createdAt, updatedAt, error,
)

fun TransferEntity.toModel() = Transfer(
    id, accountId, folderId, fileKey, TransferDirection.valueOf(direction), remotePath, localPath,
    bytesTransferred, totalBytes, TransferStatus.valueOf(status), error, retryCount, nextRetryAt,
    createdAt, updatedAt, uploadId, nextChunk,
)

fun Transfer.toEntity() = TransferEntity(
    id, accountId, folderId, fileKey, direction.name, remotePath, localPath,
    bytesTransferred, totalBytes, status.name, error, retryCount, nextRetryAt,
    createdAt, updatedAt, uploadId, nextChunk,
)

fun DiagnosticEventEntity.toModel() = DiagnosticEvent(id, timestamp, level, category, message, accountId, folderId)

fun DiagnosticEvent.toEntity() = DiagnosticEventEntity(id, timestamp, level, category, message, accountId, folderId)
