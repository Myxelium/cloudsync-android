package dev.cloudsync.versioning

import dev.cloudsync.core.model.FileVersion
import dev.cloudsync.core.model.RetentionPolicy
import dev.cloudsync.core.model.VersionOrigin
import dev.cloudsync.core.time.Clock
import dev.cloudsync.core.time.IdGenerator
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VersionRetentionTest {
    @Test
    fun pruneDropsOldestUnprotectedVersions() {
        val versions = (1..5).map { index ->
            version(id = index.toString(), createdAt = index.toLong(), size = 10)
        }
        val drop = VersionRetention.idsToPrune(
            versions,
            protectedIds = setOf("5"),
            policy = RetentionPolicy(maxVersionsPerFile = 3, maxTotalBytes = 10_000),
        )
        assertEquals(listOf("1", "2"), drop)
    }

    @Test
    fun byteBudgetDoesNotDeleteProtectedSnapshots() {
        val versions = listOf(
            version("base", 1, 100),
            version("old", 2, 80),
            version("new", 3, 80),
        )
        val drop = VersionRetention.idsToPrune(
            versions,
            protectedIds = setOf("base", "new"),
            policy = RetentionPolicy(maxVersionsPerFile = 10, maxTotalBytes = 150),
        )
        assertEquals(listOf("old"), drop)
    }

    @Test
    fun restoreReadsTheSavedBytes() = runBlocking {
        val store = InMemoryVersionStore()
        var tick = 10L
        val recorder = VersionRecorder(store, IdGenerator { "v$tick" }, Clock { tick })
        val saved = recorder.snapshot("file", VersionOrigin.LOCAL, "Monday".encodeToByteArray())
        tick = 11
        recorder.snapshot("file", VersionOrigin.LOCAL, "Tuesday".encodeToByteArray(), parents = listOf(saved.id))
        assertEquals("Monday", store.content(saved.id)!!.decodeToString())
        assertEquals(2, store.history("file").size)
        assertTrue(store.history("file").last().parentIds.contains(saved.id))
    }

    private fun version(id: String, createdAt: Long, size: Long) = FileVersion(
        id = id,
        fileKey = "file",
        parentIds = emptyList(),
        createdAt = createdAt,
        sha256 = id,
        size = size,
        origin = VersionOrigin.LOCAL,
        conflictId = null,
        description = null,
        remoteEtag = null,
        mergeMetadata = null,
        hasSnapshot = true,
    )
}
