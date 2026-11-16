package dev.cloudsync.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface AccountDao {
    @Query("SELECT * FROM accounts ORDER BY createdAt")
    fun observe(): Flow<List<AccountEntity>>

    @Query("SELECT * FROM accounts ORDER BY createdAt")
    suspend fun all(): List<AccountEntity>

    @Query("SELECT * FROM accounts WHERE id = :id")
    suspend fun get(id: String): AccountEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: AccountEntity)

    @Query("DELETE FROM accounts WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface FolderDao {
    @Query("SELECT * FROM folders ORDER BY displayName")
    fun observe(): Flow<List<FolderEntity>>

    @Query("SELECT * FROM folders")
    suspend fun all(): List<FolderEntity>

    @Query("SELECT * FROM folders WHERE id = :id")
    suspend fun get(id: String): FolderEntity?

    @Query("SELECT * FROM folders WHERE accountId = :accountId")
    suspend fun forAccount(accountId: String): List<FolderEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: FolderEntity)

    @Query("DELETE FROM folders WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface TrackedFileDao {
    @Query("SELECT * FROM tracked_files WHERE folderId = :folderId")
    suspend fun forFolder(folderId: String): List<TrackedFileEntity>

    @Query("SELECT * FROM tracked_files WHERE fileKey = :fileKey")
    suspend fun byKey(fileKey: String): TrackedFileEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: TrackedFileEntity)

    @Query("DELETE FROM tracked_files WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface ConflictDao {
    @Query("SELECT * FROM conflicts WHERE folderId = :folderId ORDER BY createdAt DESC")
    suspend fun forFolder(folderId: String): List<ConflictEntity>

    @Query("SELECT * FROM conflicts WHERE state = 'OPEN' ORDER BY createdAt DESC")
    fun observeOpen(): Flow<List<ConflictEntity>>

    @Query("SELECT * FROM conflicts WHERE state = 'OPEN' ORDER BY createdAt DESC")
    suspend fun open(): List<ConflictEntity>

    @Query("SELECT * FROM conflicts WHERE id = :id")
    suspend fun get(id: String): ConflictEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: ConflictEntity)
}

@Dao
interface MergeSessionDao {
    @Query("SELECT * FROM merge_sessions WHERE folderId = :folderId")
    suspend fun forFolder(folderId: String): List<MergeSessionEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: MergeSessionEntity)
}

@Dao
interface TransferDao {
    @Query("SELECT * FROM transfers WHERE folderId = :folderId")
    suspend fun forFolder(folderId: String): List<TransferEntity>

    @Query("SELECT * FROM transfers ORDER BY updatedAt DESC")
    fun observe(): Flow<List<TransferEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: TransferEntity)

    @Query("UPDATE transfers SET status = 'QUEUED', error = 'Recovered after the process stopped.' WHERE status = 'RUNNING'")
    suspend fun requeueRunning(): Int
}

@Dao
interface SyncJobDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: SyncJobEntity)

    @Query("SELECT * FROM sync_jobs WHERE folderId = :folderId ORDER BY updatedAt DESC LIMIT 1")
    suspend fun latest(folderId: String): SyncJobEntity?
}

@Dao
interface VersionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: FileVersionEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBlob(entity: VersionBlobEntity)

    @Query("SELECT * FROM file_versions WHERE id = :id")
    suspend fun get(id: String): FileVersionEntity?

    @Query("SELECT bytes FROM version_blobs WHERE versionId = :id")
    suspend fun content(id: String): ByteArray?

    @Query("SELECT * FROM file_versions WHERE fileKey = :fileKey ORDER BY createdAt")
    suspend fun history(fileKey: String): List<FileVersionEntity>

    @Query("SELECT * FROM file_versions WHERE fileKey = :fileKey ORDER BY createdAt DESC")
    fun observeHistory(fileKey: String): Flow<List<FileVersionEntity>>

    @Query("DELETE FROM file_versions WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM version_blobs WHERE versionId = :id")
    suspend fun deleteBlob(id: String)

    @Query("DELETE FROM file_versions WHERE fileKey LIKE :prefix || '|%'")
    suspend fun deleteForAccount(prefix: String)
}

@Dao
interface DiagnosticDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: DiagnosticEventEntity)

    @Query("SELECT * FROM diagnostic_events ORDER BY timestamp DESC LIMIT :limit")
    fun observe(limit: Int = 200): Flow<List<DiagnosticEventEntity>>
}

@Dao
interface SettingDao {
    @Query("SELECT * FROM app_settings WHERE key = :key")
    suspend fun get(key: String): SettingEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: SettingEntity)

    @Query("SELECT * FROM app_settings")
    fun observe(): Flow<List<SettingEntity>>
}
