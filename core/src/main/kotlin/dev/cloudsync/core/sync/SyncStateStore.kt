package dev.cloudsync.core.sync

import dev.cloudsync.core.model.Conflict
import dev.cloudsync.core.model.MergeSession
import dev.cloudsync.core.model.TrackedFile
import dev.cloudsync.core.model.Transfer

interface SyncStateStore {
    suspend fun tracked(folderId: String): List<TrackedFile>

    suspend fun upsert(file: TrackedFile)

    suspend fun deleteTracked(id: String)

    suspend fun conflicts(folderId: String): List<Conflict>

    suspend fun saveConflict(conflict: Conflict)

    suspend fun mergeSessions(folderId: String): List<MergeSession>

    suspend fun saveMerge(session: MergeSession)

    suspend fun saveTransfer(transfer: Transfer)

    suspend fun transfers(folderId: String): List<Transfer>
}
