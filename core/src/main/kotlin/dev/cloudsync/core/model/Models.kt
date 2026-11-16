package dev.cloudsync.core.model

enum class SyncDirection {
    TWO_WAY,
    UPLOAD_ONLY,
    DOWNLOAD_ONLY,
}

enum class NetworkMode {
    WIFI_ONLY,
    MOBILE_ONLY,
    WIFI_AND_MOBILE,
}

enum class ScheduleMode(val minutes: Long?) {
    IMMEDIATE(15),
    MINUTES_15(15),
    MINUTES_30(30),
    HOURS_1(60),
    HOURS_3(180),
    HOURS_6(360),
    HOURS_12(720),
    DAILY(1440),
    MANUAL(null),
}

enum class DeletionPolicy {
    /** Never delete the other side automatically. Local removals are recoverable. */
    SAFE_RECOVERY,

    /** Propagate a deletion only in the folder's allowed direction, after an unchanged check. */
    PROPAGATE,
}

enum class VersionOrigin {
    LOCAL,
    REMOTE,
    DOWNLOAD,
    UPLOAD,
    MERGE,
}

enum class TrackedState {
    IN_SYNC,
    LOCAL_DIRTY,
    REMOTE_DIRTY,
    CONFLICT,
    PENDING_UPLOAD,
    PENDING_DOWNLOAD,
    ERROR,
    LOCAL_MISSING,
    REMOTE_MISSING,
    WAITING_NETWORK,
    IGNORED,
}

enum class ConflictKind {
    CONTENT,
    BINARY,
    DELETE_LOCAL_MODIFY_REMOTE,
    DELETE_REMOTE_MODIFY_LOCAL,
    RENAME,
    ENCODING,
}

enum class ConflictState {
    OPEN,
    RESOLVED,
    ABORTED,
}

enum class TransferDirection {
    UPLOAD,
    DOWNLOAD,
}

enum class TransferStatus {
    QUEUED,
    RUNNING,
    PAUSED,
    COMPLETED,
    FAILED,
    CANCELLED,
    WAITING_NETWORK,
}

enum class AccountStatus {
    ACTIVE,
    AUTH_EXPIRED,
    DISCONNECTED,
}

enum class MergeSessionState {
    READY_TO_UPLOAD,
    UPLOADING,
    COMMITTED,
    ABORTED,
}

enum class FolderStatus {
    IDLE,
    SYNCING,
    WAITING_NETWORK,
    WAITING_CHARGING,
    WAITING_BATTERY,
    PAUSED,
    ERROR,
    CONFLICTS,
}

data class Account(
    val id: String,
    val serverUrl: String,
    val loginName: String,
    val userId: String,
    val displayName: String,
    val status: AccountStatus,
    val createdAt: Long,
    val lastValidatedAt: Long?,
)

data class AccountEndpoint(val accountId: String, val serverUrl: String, val loginName: String, val userId: String)

data class ExcludeRules(val includeSubfolders: Boolean = true, val patterns: List<String> = emptyList())

data class SyncFolder(
    val id: String,
    val accountId: String,
    val displayName: String,
    val remotePath: String,
    val localRoot: String,
    val direction: SyncDirection,
    val exclude: ExcludeRules,
    val paused: Boolean,
    val networkMode: NetworkMode,
    val allowUploadOnWifi: Boolean,
    val allowUploadOnMobile: Boolean,
    val allowDownloadOnWifi: Boolean,
    val allowDownloadOnMobile: Boolean,
    val requireCharging: Boolean,
    val minBatteryPercent: Int,
    val schedule: ScheduleMode,
    val deletionPolicy: DeletionPolicy,
    val lastSuccessAt: Long?,
    val lastError: String?,
    val status: FolderStatus,
)

data class TrackedFile(
    val id: String,
    val folderId: String,
    val accountId: String,
    val fileKey: String,
    val localRelativePath: String,
    val remoteRelativePath: String,
    val remoteFileId: String?,
    val localModifiedAt: Long?,
    val remoteModifiedAt: Long?,
    val localSize: Long?,
    val remoteSize: Long?,
    val localHash: String?,
    val remoteEtag: String?,
    val baseVersionId: String?,
    val baseHash: String?,
    val baseEtag: String?,
    val lastSyncedAt: Long?,
    val state: TrackedState,
    val pendingOperation: String?,
    val conflictId: String?,
)

data class FileVersion(
    val id: String,
    val fileKey: String,
    val parentIds: List<String>,
    val createdAt: Long,
    val sha256: String,
    val size: Long,
    val origin: VersionOrigin,
    val conflictId: String?,
    val description: String?,
    val remoteEtag: String?,
    val mergeMetadata: String?,
    val hasSnapshot: Boolean,
)

data class Conflict(
    val id: String,
    val fileKey: String,
    val folderId: String,
    val accountId: String,
    val remotePath: String,
    val kind: ConflictKind,
    val state: ConflictState,
    val createdAt: Long,
    val resolvedAt: Long?,
    val baseVersionId: String?,
    val localVersionId: String?,
    val remoteVersionId: String?,
    val summary: String,
)

data class MergeSession(
    val id: String,
    val fileKey: String,
    val folderId: String,
    val conflictId: String?,
    val baseVersionId: String?,
    val localVersionId: String,
    val remoteVersionId: String,
    val resultVersionId: String?,
    val state: MergeSessionState,
    val remoteEtagAtStart: String?,
    val createdAt: Long,
    val updatedAt: Long,
    val error: String?,
)

data class Transfer(
    val id: String,
    val accountId: String,
    val folderId: String?,
    val fileKey: String?,
    val direction: TransferDirection,
    val remotePath: String,
    val localPath: String,
    val bytesTransferred: Long,
    val totalBytes: Long,
    val status: TransferStatus,
    val error: String?,
    val retryCount: Int,
    val nextRetryAt: Long?,
    val createdAt: Long,
    val updatedAt: Long,
    val uploadId: String?,
    val nextChunk: Int,
)

data class DiagnosticEvent(
    val id: String,
    val timestamp: Long,
    val level: String,
    val category: String,
    val message: String,
    val accountId: String?,
    val folderId: String?,
)

data class NetworkSnapshot(
    val validated: Boolean,
    val wifi: Boolean,
    val cellular: Boolean,
    val metered: Boolean,
    val charging: Boolean,
    val batteryPercent: Int?,
)

data class SyncReport(
    val folderId: String,
    val status: FolderStatus,
    val uploaded: Int,
    val downloaded: Int,
    val conflicts: Int,
    val waitingReason: String?,
    val errors: List<String>,
)

data class RetentionPolicy(
    val maxVersionsPerFile: Int = 50,
    val maxTotalBytes: Long = 512L * 1024L * 1024L,
    val maxSnapshotBytes: Long = 32L * 1024L * 1024L,
)

fun Account.endpoint(): AccountEndpoint = AccountEndpoint(
    accountId = id,
    serverUrl = serverUrl,
    loginName = loginName,
    userId = userId,
)

fun fileKey(accountId: String, remotePath: String): String = "$accountId|$remotePath"
