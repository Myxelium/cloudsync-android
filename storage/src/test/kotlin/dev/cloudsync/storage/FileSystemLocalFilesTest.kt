package dev.cloudsync.storage

import dev.cloudsync.core.hash.ContentHash
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FileSystemLocalFilesTest {
    @Test
    fun atomicReplaceAndRecoveryKeepBytes() = runBlocking<Unit> {
        val root = Files.createTempDirectory("cloudsync").toFile()
        val files = FileSystemLocalFiles()
        files.writeAtomically(root.absolutePath, "notes/a.txt", "one".encodeToByteArray())
        files.writeAtomically(root.absolutePath, "notes/a.txt", "two".encodeToByteArray())
        assertEquals("two", files.read(root.absolutePath, "notes/a.txt").decodeToString())
        assertEquals(ContentHash.sha256("two".encodeToByteArray()), files.sha256(root.absolutePath, "notes/a.txt"))
        val recovery = File(root, ".cloudsync/recovery")
        files.moveToRecovery(root.absolutePath, "notes/a.txt", recovery.absolutePath)
        assertFalse(files.exists(root.absolutePath, "notes/a.txt"))
        assertTrue(files.exists(recovery.absolutePath, "notes/a.txt"))
        root.deleteRecursively()
        Unit
    }
}
