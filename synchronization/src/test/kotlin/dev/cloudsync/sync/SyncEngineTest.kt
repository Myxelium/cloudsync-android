package dev.cloudsync.sync

import dev.cloudsync.core.hash.ContentHash
import dev.cloudsync.core.model.AccountEndpoint
import dev.cloudsync.core.model.ConflictKind
import dev.cloudsync.core.model.ConflictState
import dev.cloudsync.core.model.DeletionPolicy
import dev.cloudsync.core.model.ExcludeRules
import dev.cloudsync.core.model.FolderStatus
import dev.cloudsync.core.model.MergeSession
import dev.cloudsync.core.model.MergeSessionState
import dev.cloudsync.core.model.NetworkMode
import dev.cloudsync.core.model.NetworkSnapshot
import dev.cloudsync.core.model.ScheduleMode
import dev.cloudsync.core.model.SyncDirection
import dev.cloudsync.core.model.SyncFolder
import dev.cloudsync.core.model.TrackedFile
import dev.cloudsync.core.model.TrackedState
import dev.cloudsync.core.model.TransferStatus
import dev.cloudsync.core.model.VersionOrigin
import dev.cloudsync.core.time.Clock
import dev.cloudsync.core.time.IdGenerator
import dev.cloudsync.testing.FakeLocalFiles
import dev.cloudsync.testing.FakeRemoteFiles
import dev.cloudsync.testing.MemorySyncState
import dev.cloudsync.versioning.InMemoryVersionStore
import dev.cloudsync.versioning.VersionRecorder
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SyncEngineTest {
    private val endpoint = AccountEndpoint("acc", "https://cloud.example", "alice", "alice")

    @Test
    fun twoWayMergeKeepsIndependentEdits() = runBlocking<Unit> {
        val harness = harness()
        val base = "Meeting is on Monday.\nLocation is Stockholm.\n"
        harness.local.put("notes.txt", "Meeting is on Tuesday.\nLocation is Stockholm.\n")
        harness.remote.files["/Notes/notes.txt"] = FakeRemoteFiles.Stored(
            "Meeting is on Monday.\nLocation is Gothenburg.\n".encodeToByteArray(),
            "remote-etag",
            "file-1",
        )
        val baseVersion = harness.seedBase("notes.txt", base, "base-etag")
        harness.remember("notes.txt", base, "base-etag", baseVersion)

        val report = harness.engine.synchronize(harness.request())
        assertEquals(FolderStatus.IDLE, report.status)
        assertEquals(
            "Meeting is on Tuesday.\nLocation is Gothenburg.\n",
            harness.local.read("root", "notes.txt").decodeToString(),
        )
        assertEquals(
            "Meeting is on Tuesday.\nLocation is Gothenburg.\n",
            harness.remote.files["/Notes/notes.txt"]!!.bytes.decodeToString(),
        )
        assertTrue(harness.remote.calls.any { it.startsWith("UPLOAD") })
    }

    @Test
    fun uploadOnlyNeverDownloadsAndDownloadOnlyNeverUploads() = runBlocking<Unit> {
        val uploadOnly = harness(direction = SyncDirection.UPLOAD_ONLY)
        uploadOnly.local.put("local.txt", "local-edit")
        uploadOnly.remote.files["/Notes/local.txt"] = FakeRemoteFiles.Stored("local-base".encodeToByteArray(), "base", "id-l")
        uploadOnly.remote.files["/Notes/remote.txt"] = FakeRemoteFiles.Stored("remote".encodeToByteArray(), "e", "id-r")
        uploadOnly.seedAndTrack("local.txt", "local-base", "base")
        uploadOnly.engine.synchronize(uploadOnly.request())
        assertFalse(uploadOnly.remote.calls.any { it.startsWith("DOWNLOAD") })
        assertTrue(uploadOnly.remote.calls.any { it.startsWith("UPLOAD /Notes/local.txt") })
        assertEquals("remote", uploadOnly.remote.files["/Notes/remote.txt"]!!.bytes.decodeToString())

        val downloadOnly = harness(direction = SyncDirection.DOWNLOAD_ONLY)
        downloadOnly.local.put("local.txt", "dirty")
        downloadOnly.remote.files["/Notes/local.txt"] = FakeRemoteFiles.Stored("clean".encodeToByteArray(), "old", "id-local")
        downloadOnly.remote.files["/Notes/remote.txt"] = FakeRemoteFiles.Stored("from-server".encodeToByteArray(), "new", "id-2")
        downloadOnly.seedAndTrack("local.txt", "clean", "old")
        downloadOnly.engine.synchronize(downloadOnly.request())
        assertFalse(downloadOnly.remote.calls.any { it.startsWith("UPLOAD") })
        assertEquals("from-server", downloadOnly.local.read("root", "remote.txt").decodeToString())
        assertEquals("dirty", downloadOnly.local.read("root", "local.txt").decodeToString())
    }

    @Test
    fun sameLineConflictDoesNotOverwriteEitherCopy() = runBlocking<Unit> {
        val harness = harness()
        harness.local.put("notes.txt", "Tuesday\n")
        harness.remote.files["/Notes/notes.txt"] = FakeRemoteFiles.Stored("Wednesday\n".encodeToByteArray(), "remote", "id")
        val base = harness.seedBase("notes.txt", "Monday\n", "base")
        harness.remember("notes.txt", "Monday\n", "base", base)
        val report = harness.engine.synchronize(harness.request())
        assertEquals(FolderStatus.CONFLICTS, report.status)
        assertEquals("Tuesday\n", harness.local.read("root", "notes.txt").decodeToString())
        assertEquals("Wednesday\n", harness.remote.files["/Notes/notes.txt"]!!.bytes.decodeToString())
        assertEquals(ConflictKind.CONTENT, harness.state.conflictList.single().kind)
    }

    @Test
    fun remoteChangeDuringMergeAbortsAndKeepsTheLocalFile() = runBlocking<Unit> {
        val harness = harness()
        harness.local.put("notes.txt", "Meeting is on Tuesday.\nLocation is Stockholm.\n")
        harness.remote.files["/Notes/notes.txt"] = FakeRemoteFiles.Stored(
            "Meeting is on Monday.\nLocation is Gothenburg.\n".encodeToByteArray(),
            "remote",
            "id",
        )
        val base = harness.seedBase("notes.txt", "Meeting is on Monday.\nLocation is Stockholm.\n", "base")
        harness.remember("notes.txt", "Meeting is on Monday.\nLocation is Stockholm.\n", "base", base)
        harness.remote.failNextMatchedUpload = true
        harness.engine.synchronize(harness.request())
        assertEquals("Meeting is on Tuesday.\nLocation is Stockholm.\n", harness.local.read("root", "notes.txt").decodeToString())
        assertTrue(harness.state.mergeList.any { it.state == MergeSessionState.ABORTED })
        assertTrue(harness.state.conflictList.any { it.state == ConflictState.OPEN })
        assertEquals("base", harness.state.files.single().baseEtag)
    }

    @Test
    fun interruptedMergeRetriesTheUploadWithoutASecondCopy() = runBlocking<Unit> {
        val harness = harness()
        val original = "Meeting is on Tuesday.\nLocation is Stockholm.\n"
        val merged = "Meeting is on Tuesday.\nLocation is Gothenburg.\n"
        harness.local.put("notes.txt", original)
        harness.remote.files["/Notes/notes.txt"] = FakeRemoteFiles.Stored(original.encodeToByteArray(), "remote", "id")
        val localVersion = harness.recorder.snapshot("acc|/Notes/notes.txt", VersionOrigin.LOCAL, original.encodeToByteArray())
        val mergedVersion = harness.recorder.snapshot("acc|/Notes/notes.txt", VersionOrigin.MERGE, merged.encodeToByteArray())
        harness.remember("notes.txt", original, "remote", localVersion.id)
        harness.state.saveMerge(
            MergeSession(
                id = "session",
                fileKey = "acc|/Notes/notes.txt",
                folderId = "folder",
                conflictId = null,
                baseVersionId = null,
                localVersionId = localVersion.id,
                remoteVersionId = localVersion.id,
                resultVersionId = mergedVersion.id,
                state = MergeSessionState.READY_TO_UPLOAD,
                remoteEtagAtStart = "remote",
                createdAt = 1,
                updatedAt = 1,
                error = null,
            ),
        )
        harness.engine.synchronize(harness.request())
        assertEquals(merged, harness.remote.files["/Notes/notes.txt"]!!.bytes.decodeToString())
        assertEquals(merged, harness.local.read("root", "notes.txt").decodeToString())
        assertEquals(1, harness.remote.calls.count { it.startsWith("UPLOAD") })
        assertEquals(MergeSessionState.COMMITTED, harness.state.mergeList.single().state)
    }

    @Test
    fun safeDeletionDoesNotDestroyTheOtherCopy() = runBlocking<Unit> {
        val harness = harness(deletion = DeletionPolicy.SAFE_RECOVERY)
        harness.remote.files["/Notes/notes.txt"] = FakeRemoteFiles.Stored("keep".encodeToByteArray(), "same", "id")
        val version = harness.seedBase("notes.txt", "keep", "same")
        harness.remember("notes.txt", "keep", "same", version)
        harness.engine.synchronize(harness.request())
        assertTrue(harness.remote.files.containsKey("/Notes/notes.txt"))
        assertFalse(harness.remote.calls.any { it.startsWith("DELETE") })
        assertEquals(TrackedState.LOCAL_MISSING, harness.state.files.single().state)
    }

    @Test
    fun propagatedRemoteDeletionMovesTheLocalFileToRecovery() = runBlocking<Unit> {
        val harness = harness(deletion = DeletionPolicy.PROPAGATE)
        val version = harness.seedBase("notes.txt", "gone", "same")
        harness.local.put("notes.txt", "gone")
        harness.remember("notes.txt", "gone", "same", version)
        harness.engine.synchronize(harness.request())
        assertEquals(listOf("notes.txt"), harness.local.recovered)
        assertFalse(harness.local.exists("root", "notes.txt"))
    }

    @Test
    fun concurrentRenamesConflict() {
        val folder = sampleFolder()
        val baseHash = ContentHash.sha256("body".encodeToByteArray())
        val tracked = trackedFile("notes.txt", "body", "etag", "version", fileId = "id-1").copy(localHash = baseHash, baseHash = baseHash)
        val actions = SyncPlanner.plan(
            folder,
            listOf(tracked),
            listOf(LocalSnapshot("renamed-local.txt", false, 4, 2, baseHash)),
            listOf(RemoteSnapshot("renamed-remote.txt", false, 4, 2, "etag", "id-1", "text/plain")),
        )
        assertTrue(actions.any { it is PlannedAction.Conflict && it.kind == ConflictKind.RENAME })
        assertFalse(actions.any { it is PlannedAction.MoveRemote || it is PlannedAction.MoveLocal })
    }

    @Test
    fun networkRestrictionSkipsTransfers() = runBlocking<Unit> {
        val harness = harness(networkMode = NetworkMode.WIFI_ONLY)
        harness.local.put("notes.txt", "changed")
        harness.remote.files["/Notes/notes.txt"] = FakeRemoteFiles.Stored("original".encodeToByteArray(), "etag", "id")
        harness.seedAndTrack("notes.txt", "original", "etag")
        val report = harness.engine.synchronize(
            harness.request(NetworkSnapshot(true, wifi = false, cellular = true, metered = true, charging = true, batteryPercent = 90)),
        )
        assertEquals(FolderStatus.WAITING_NETWORK, report.status)
        assertFalse(harness.remote.calls.any { it.startsWith("UPLOAD") })
    }

    @Test
    fun droppedUploadResumesAndFinishes() = runBlocking<Unit> {
        val harness = harness()
        harness.local.put("notes.txt", "local-change")
        harness.remote.files["/Notes/notes.txt"] = FakeRemoteFiles.Stored("original".encodeToByteArray(), "etag", "id")
        harness.seedAndTrack("notes.txt", "original", "etag")
        harness.remote.failNextUpload = true

        val failed = harness.engine.synchronize(harness.request())
        assertEquals(FolderStatus.ERROR, failed.status)
        val queued = harness.state.transferList.single()
        assertEquals(TransferStatus.QUEUED, queued.status)
        assertEquals("upload-resume", queued.uploadId)
        assertEquals(3, queued.nextChunk)
        assertEquals(1, harness.remote.calls.count { it.startsWith("UPLOAD") })
        assertEquals("original", harness.remote.files["/Notes/notes.txt"]!!.bytes.decodeToString())

        val waiting = harness.engine.synchronize(harness.request())
        assertEquals(FolderStatus.WAITING_NETWORK, waiting.status)
        assertEquals(1, harness.remote.calls.count { it.startsWith("UPLOAD") })
        assertEquals(TransferStatus.QUEUED, harness.state.transferList.single().status)

        harness.now = queued.nextRetryAt!!
        val finished = harness.engine.synchronize(harness.request())
        assertEquals(FolderStatus.IDLE, finished.status)
        assertEquals("local-change", harness.remote.files["/Notes/notes.txt"]!!.bytes.decodeToString())
        assertEquals("upload-resume", harness.remote.resumedUploadId)
        assertEquals(3, harness.remote.resumedChunk)
        assertEquals(TransferStatus.COMPLETED, harness.state.transferList.single { it.status == TransferStatus.COMPLETED }.status)
        assertEquals(2, harness.remote.calls.count { it.startsWith("UPLOAD") })
    }

    @Test
    fun mobileCanDownloadWhileUploadsStayOnWifi() = runBlocking<Unit> {
        val harness = harness(allowUploadOnMobile = false)
        harness.local.put("dirty.txt", "local-change")
        harness.seedAndTrack("dirty.txt", "original", "etag")
        harness.remote.files["/Notes/fresh.txt"] = FakeRemoteFiles.Stored("fresh".encodeToByteArray(), "new", "id-fresh")
        harness.engine.synchronize(
            harness.request(NetworkSnapshot(true, wifi = false, cellular = true, metered = true, charging = true, batteryPercent = 90)),
        )
        assertEquals("fresh", harness.local.read("root", "fresh.txt").decodeToString())
        assertFalse(harness.remote.calls.any { it.startsWith("UPLOAD") })
        assertEquals("local-change", harness.local.read("root", "dirty.txt").decodeToString())
    }

    private fun harness(
        direction: SyncDirection = SyncDirection.TWO_WAY,
        deletion: DeletionPolicy = DeletionPolicy.SAFE_RECOVERY,
        networkMode: NetworkMode = NetworkMode.WIFI_AND_MOBILE,
        allowUploadOnMobile: Boolean = true,
    ): Harness {
        var next = 0
        val versions = InMemoryVersionStore()
        val clock = Clock { 1_000 }
        val ids = IdGenerator { "id-${next++}" }
        return Harness(
            local = FakeLocalFiles(),
            remote = FakeRemoteFiles(),
            state = MemorySyncState(),
            versions = versions,
            recorder = VersionRecorder(versions, ids, clock),
            engine = SyncEngine(
                remote = FakeRemoteFiles().also { },
                local = FakeLocalFiles(),
                versions = versions,
                state = MemorySyncState(),
                ids = ids,
                clock = clock,
            ),
            direction = direction,
            deletion = deletion,
            networkMode = networkMode,
            allowUploadOnMobile = allowUploadOnMobile,
        ).wired()
    }

    private class Harness(
        val local: FakeLocalFiles,
        val remote: FakeRemoteFiles,
        val state: MemorySyncState,
        val versions: InMemoryVersionStore,
        val recorder: VersionRecorder,
        var engine: SyncEngine,
        val direction: SyncDirection,
        val deletion: DeletionPolicy,
        val networkMode: NetworkMode,
        val allowUploadOnMobile: Boolean,
    ) {
        var now: Long = 1_000

        fun wired(): Harness {
            var next = 100
            engine = SyncEngine(
                remote = remote,
                local = local,
                versions = versions,
                state = state,
                ids = IdGenerator { "id-${next++}" },
                clock = Clock { now },
            )
            return this
        }

        fun request(network: NetworkSnapshot = online()) = SyncRequest(
            sampleFolder(direction, deletion, networkMode, allowUploadOnMobile),
            AccountEndpoint("acc", "https://cloud.example", "alice", "alice"),
            network,
        )

        suspend fun seedBase(path: String, text: String, etag: String): String = recorder.snapshot("acc|/Notes/$path", VersionOrigin.LOCAL, text.encodeToByteArray(), remoteEtag = etag).id

        suspend fun seedAndTrack(path: String, text: String, etag: String) {
            val id = seedBase(path, text, etag)
            remember(path, text, etag, id)
        }

        fun remember(path: String, text: String, etag: String, versionId: String) {
            state.files += trackedFile(path, text, etag, versionId)
        }
    }
}

private fun online() = NetworkSnapshot(true, wifi = true, cellular = false, metered = false, charging = true, batteryPercent = 80)

private fun sampleFolder(
    direction: SyncDirection = SyncDirection.TWO_WAY,
    deletion: DeletionPolicy = DeletionPolicy.SAFE_RECOVERY,
    networkMode: NetworkMode = NetworkMode.WIFI_AND_MOBILE,
    allowUploadOnMobile: Boolean = true,
) = SyncFolder(
    id = "folder",
    accountId = "acc",
    displayName = "Notes",
    remotePath = "/Notes",
    localRoot = "root",
    direction = direction,
    exclude = ExcludeRules(),
    paused = false,
    networkMode = networkMode,
    allowUploadOnWifi = true,
    allowUploadOnMobile = allowUploadOnMobile,
    allowDownloadOnWifi = true,
    allowDownloadOnMobile = true,
    requireCharging = false,
    minBatteryPercent = 0,
    schedule = ScheduleMode.MANUAL,
    deletionPolicy = deletion,
    lastSuccessAt = null,
    lastError = null,
    status = FolderStatus.IDLE,
)

private fun trackedFile(path: String, text: String, etag: String, versionId: String, fileId: String? = null) = TrackedFile(
    id = "tracked-$path",
    folderId = "folder",
    accountId = "acc",
    fileKey = "acc|/Notes/$path",
    localRelativePath = path,
    remoteRelativePath = path,
    remoteFileId = fileId,
    localModifiedAt = 1,
    remoteModifiedAt = 1,
    localSize = text.encodeToByteArray().size.toLong(),
    remoteSize = text.encodeToByteArray().size.toLong(),
    localHash = ContentHash.sha256(text.encodeToByteArray()),
    remoteEtag = etag,
    baseVersionId = versionId,
    baseHash = ContentHash.sha256(text.encodeToByteArray()),
    baseEtag = etag,
    lastSyncedAt = 1,
    state = TrackedState.IN_SYNC,
    pendingOperation = null,
    conflictId = null,
)
