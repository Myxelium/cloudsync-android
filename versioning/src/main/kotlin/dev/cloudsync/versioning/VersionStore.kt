package dev.cloudsync.versioning

import dev.cloudsync.core.hash.ContentHash
import dev.cloudsync.core.model.FileVersion
import dev.cloudsync.core.model.RetentionPolicy
import dev.cloudsync.core.model.VersionOrigin
import dev.cloudsync.core.time.Clock
import dev.cloudsync.core.time.IdGenerator

interface VersionStore {
    suspend fun record(
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
    ): FileVersion

    suspend fun get(id: String): FileVersion?

    suspend fun content(id: String): ByteArray?

    suspend fun history(fileKey: String): List<FileVersion>

    suspend fun delete(id: String)
}

class InMemoryVersionStore : VersionStore {
    private val versions = linkedMapOf<String, FileVersion>()
    private val blobs = linkedMapOf<String, ByteArray>()

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
        versions[id] = version
        if (bytes != null) blobs[id] = bytes.copyOf()
        return version
    }

    override suspend fun get(id: String): FileVersion? = versions[id]

    override suspend fun content(id: String): ByteArray? = blobs[id]?.copyOf()

    override suspend fun history(fileKey: String): List<FileVersion> = versions.values.filter { it.fileKey == fileKey }.sortedBy { it.createdAt }

    override suspend fun delete(id: String) {
        versions.remove(id)
        blobs.remove(id)
    }
}

object VersionRetention {
    fun idsToPrune(versions: List<FileVersion>, protectedIds: Set<String>, policy: RetentionPolicy): List<String> {
        val grouped = versions.groupBy { it.fileKey }
        val drop = mutableListOf<String>()
        grouped.forEach { (_, history) ->
            val ordered = history.sortedBy { it.createdAt }
            val overflow = (ordered.size - policy.maxVersionsPerFile).coerceAtLeast(0)
            val candidates = ordered.filter { it.id !in protectedIds }
            drop += candidates.take(overflow).map { it.id }
        }
        val surviving = versions.filter { it.id !in drop }
        var total = surviving.sumOf { if (it.hasSnapshot) it.size else 0L }
        if (total > policy.maxTotalBytes) {
            val extra = surviving
                .filter { it.id !in protectedIds && it.hasSnapshot }
                .sortedBy { it.createdAt }
            for (version in extra) {
                if (total <= policy.maxTotalBytes) break
                drop += version.id
                total -= version.size
            }
        }
        return drop.distinct()
    }
}

class VersionRecorder(private val store: VersionStore, private val ids: IdGenerator, private val clock: Clock) {
    suspend fun snapshot(
        fileKey: String,
        origin: VersionOrigin,
        bytes: ByteArray?,
        parents: List<String> = emptyList(),
        remoteEtag: String? = null,
        conflictId: String? = null,
        description: String? = null,
        mergeMetadata: String? = null,
        maxSnapshotBytes: Long = RetentionPolicy().maxSnapshotBytes,
    ): FileVersion {
        val payload = if (bytes != null && bytes.size.toLong() <= maxSnapshotBytes) bytes else null
        return store.record(
            fileKey = fileKey,
            origin = origin,
            bytes = payload,
            parentIds = parents,
            createdAt = clock.nowMillis(),
            remoteEtag = remoteEtag,
            conflictId = conflictId,
            description = description ?: if (payload == null && bytes != null) {
                "Snapshot omitted because the file is larger than the retention limit."
            } else {
                null
            },
            mergeMetadata = mergeMetadata,
            id = ids.newId(),
        )
    }
}
