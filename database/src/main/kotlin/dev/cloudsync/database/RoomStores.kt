package dev.cloudsync.database

import androidx.room.withTransaction
import dev.cloudsync.core.hash.ContentHash
import dev.cloudsync.core.model.Conflict
import dev.cloudsync.core.model.FileVersion
import dev.cloudsync.core.model.MergeSession
import dev.cloudsync.core.model.TrackedFile
import dev.cloudsync.core.model.Transfer
import dev.cloudsync.core.model.VersionOrigin
import dev.cloudsync.core.sync.SyncStateStore
import dev.cloudsync.versioning.VersionStore

class RoomSyncStateStore(private val database: CloudSyncDatabase) : SyncStateStore {
    override suspend fun tracked(folderId: String): List<TrackedFile> = database.trackedFiles().forFolder(folderId).map { it.toModel() }

    override suspend fun upsert(file: TrackedFile) {
        database.trackedFiles().upsert(file.toEntity())
    }

    override suspend fun deleteTracked(id: String) {
        database.trackedFiles().delete(id)
    }

    override suspend fun conflicts(folderId: String): List<Conflict> = database.conflicts().forFolder(folderId).map { it.toModel() }

    override suspend fun saveConflict(conflict: Conflict) {
        database.conflicts().upsert(conflict.toEntity())
    }

    override suspend fun mergeSessions(folderId: String): List<MergeSession> = database.merges().forFolder(folderId).map { it.toModel() }

    override suspend fun saveMerge(session: MergeSession) {
        database.merges().upsert(session.toEntity())
    }

    override suspend fun saveTransfer(transfer: Transfer) {
        database.transfers().upsert(transfer.toEntity())
    }

    override suspend fun transfers(folderId: String): List<Transfer> = database.transfers().forFolder(folderId).map { it.toModel() }
}

class RoomVersionStore(private val database: CloudSyncDatabase) : VersionStore {
    override suspend fun record(
        fileKey: String,
        origin: VersionOrigin,
        bytes: ByteArray?,
        parentIds: List<String>,
        createdAt: Long,
        remoteEtag: String?,
        conflictId: String?,
        description: String?,
        mergeMetadata: String?,
        id: String,
    ): FileVersion {
        val version = FileVersion(
            id = id,
            fileKey = fileKey,
            parentIds = parentIds,
            createdAt = createdAt,
            sha256 = bytes?.let(ContentHash::sha256) ?: "",
            size = bytes?.size?.toLong() ?: 0,
            origin = origin,
            conflictId = conflictId,
            description = description,
            remoteEtag = remoteEtag,
            mergeMetadata = mergeMetadata,
            hasSnapshot = bytes != null,
        )
        database.withTransaction {
            database.versions().insert(version.toEntity())
            if (bytes != null) database.versions().insertBlob(VersionBlobEntity(id, bytes.copyOf()))
        }
        return version
    }

    override suspend fun get(id: String): FileVersion? = database.versions().get(id)?.toModel()

    override suspend fun content(id: String): ByteArray? = database.versions().content(id)?.copyOf()

    override suspend fun history(fileKey: String): List<FileVersion> = database.versions().history(fileKey).map { it.toModel() }

    override suspend fun delete(id: String) {
        database.withTransaction {
            database.versions().deleteBlob(id)
            database.versions().delete(id)
        }
    }
}
