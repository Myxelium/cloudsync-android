package dev.cloudsync.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        AccountEntity::class,
        FolderEntity::class,
        TrackedFileEntity::class,
        FileVersionEntity::class,
        VersionBlobEntity::class,
        ConflictEntity::class,
        MergeSessionEntity::class,
        TransferEntity::class,
        SyncJobEntity::class,
        DiagnosticEventEntity::class,
        SettingEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
abstract class CloudSyncDatabase : RoomDatabase() {
    abstract fun accounts(): AccountDao

    abstract fun folders(): FolderDao

    abstract fun trackedFiles(): TrackedFileDao

    abstract fun conflicts(): ConflictDao

    abstract fun merges(): MergeSessionDao

    abstract fun transfers(): TransferDao

    abstract fun jobs(): SyncJobDao

    abstract fun versions(): VersionDao

    abstract fun diagnostics(): DiagnosticDao

    abstract fun settings(): SettingDao

    companion object {
        const val CREATE_APP_SETTINGS =
            "CREATE TABLE IF NOT EXISTS `app_settings` (`key` TEXT NOT NULL, `value` TEXT NOT NULL, PRIMARY KEY(`key`))"

        /**
         * Schema 1 is the sync tables. Schema 2 adds notification settings.
         * Fresh installs are created at version 2. This migration covers an upgrade.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(CREATE_APP_SETTINGS)
            }
        }
    }
}
