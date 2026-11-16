package dev.cloudsync.database

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "accounts")
data class AccountEntity(
    @PrimaryKey val id: String,
    val serverUrl: String,
    val loginName: String,
    val userId: String,
    val displayName: String,
    val status: String,
    val createdAt: Long,
    val lastValidatedAt: Long?,
)

@Entity(
    tableName = "folders",
    foreignKeys = [
        ForeignKey(
            entity = AccountEntity::class,
            parentColumns = ["id"],
            childColumns = ["accountId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("accountId"), Index(value = ["accountId", "remotePath"], unique = true)],
)
data class FolderEntity(
    @PrimaryKey val id: String,
    val accountId: String,
    val displayName: String,
    val remotePath: String,
    val localRoot: String,
    val direction: String,
    val includeSubfolders: Boolean,
    val excludePatterns: String,
    val paused: Boolean,
    val networkMode: String,
    val allowUploadOnWifi: Boolean,
    val allowUploadOnMobile: Boolean,
    val allowDownloadOnWifi: Boolean,
    val allowDownloadOnMobile: Boolean,
    val requireCharging: Boolean,
    val minBatteryPercent: Int,
    val schedule: String,
    val deletionPolicy: String,
    val lastSuccessAt: Long?,
    val lastError: String?,
    val status: String,
)

@Entity(
    tableName = "tracked_files",
    foreignKeys = [
        ForeignKey(
            entity = FolderEntity::class,
            parentColumns = ["id"],
            childColumns = ["folderId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("folderId"), Index(value = ["fileKey"], unique = true)],
)
data class TrackedFileEntity(
    @PrimaryKey val id: String,
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
    val state: String,
    val pendingOperation: String?,
    val conflictId: String?,
)

@Entity(tableName = "file_versions", indices = [Index("fileKey")])
data class FileVersionEntity(
    @PrimaryKey val id: String,
    val fileKey: String,
    val parentIds: String,
    val createdAt: Long,
    val sha256: String,
    val size: Long,
    val origin: String,
    val conflictId: String?,
    val description: String?,
    val remoteEtag: String?,
    val mergeMetadata: String?,
    val hasSnapshot: Boolean,
)

@Entity(
    tableName = "version_blobs",
    foreignKeys = [
        ForeignKey(
            entity = FileVersionEntity::class,
            parentColumns = ["id"],
            childColumns = ["versionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class VersionBlobEntity(@PrimaryKey val versionId: String, val bytes: ByteArray)

@Entity(
    tableName = "conflicts",
    foreignKeys = [
        ForeignKey(
            entity = FolderEntity::class,
            parentColumns = ["id"],
            childColumns = ["folderId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("folderId"), Index("state")],
)
data class ConflictEntity(
    @PrimaryKey val id: String,
    val fileKey: String,
    val folderId: String,
    val accountId: String,
    val remotePath: String,
    val kind: String,
    val state: String,
    val createdAt: Long,
    val resolvedAt: Long?,
    val baseVersionId: String?,
    val localVersionId: String?,
    val remoteVersionId: String?,
    val summary: String,
)

@Entity(
    tableName = "merge_sessions",
    foreignKeys = [
        ForeignKey(
            entity = FolderEntity::class,
            parentColumns = ["id"],
            childColumns = ["folderId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("folderId")],
)
data class MergeSessionEntity(
    @PrimaryKey val id: String,
    val fileKey: String,
    val folderId: String,
    val conflictId: String?,
    val baseVersionId: String?,
    val localVersionId: String,
    val remoteVersionId: String,
    val resultVersionId: String?,
    val state: String,
    val remoteEtagAtStart: String?,
    val createdAt: Long,
    val updatedAt: Long,
    val error: String?,
)

@Entity(
    tableName = "transfers",
    foreignKeys = [
        ForeignKey(
            entity = AccountEntity::class,
            parentColumns = ["id"],
            childColumns = ["accountId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("accountId"), Index("folderId"), Index("status")],
)
data class TransferEntity(
    @PrimaryKey val id: String,
    val accountId: String,
    val folderId: String?,
    val fileKey: String?,
    val direction: String,
    val remotePath: String,
    val localPath: String,
    val bytesTransferred: Long,
    val totalBytes: Long,
    val status: String,
    val error: String?,
    val retryCount: Int,
    val nextRetryAt: Long?,
    val createdAt: Long,
    val updatedAt: Long,
    val uploadId: String?,
    val nextChunk: Int,
)

@Entity(
    tableName = "sync_jobs",
    foreignKeys = [
        ForeignKey(
            entity = FolderEntity::class,
            parentColumns = ["id"],
            childColumns = ["folderId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("folderId")],
)
data class SyncJobEntity(
    @PrimaryKey val id: String,
    val folderId: String,
    val state: String,
    val createdAt: Long,
    val updatedAt: Long,
    val error: String?,
)

@Entity(tableName = "diagnostic_events", indices = [Index("timestamp")])
data class DiagnosticEventEntity(
    @PrimaryKey val id: String,
    val timestamp: Long,
    val level: String,
    val category: String,
    val message: String,
    val accountId: String?,
    val folderId: String?,
)

@Entity(tableName = "app_settings")
data class SettingEntity(@PrimaryKey val key: String, val value: String)
