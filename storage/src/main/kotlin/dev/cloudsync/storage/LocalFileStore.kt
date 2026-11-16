package dev.cloudsync.storage

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import dev.cloudsync.core.error.InvalidNameException
import dev.cloudsync.core.hash.ContentHash
import dev.cloudsync.core.path.SyncPaths
import dev.cloudsync.core.remote.LocalFiles
import dev.cloudsync.core.remote.LocalNode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class DelegatingLocalFiles(private val context: Context) : LocalFiles {
    private val files = FileSystemLocalFiles()
    private val saf = SafLocalFiles(context)

    private fun saf(root: String) = root.startsWith("content://")

    override suspend fun list(root: String) = if (saf(root)) saf.list(root) else files.list(root)

    override suspend fun read(root: String, relativePath: String) = if (saf(root)) saf.read(root, relativePath) else files.read(root, relativePath)

    override suspend fun writeAtomically(root: String, relativePath: String, bytes: ByteArray) {
        if (saf(root)) saf.writeAtomically(root, relativePath, bytes) else files.writeAtomically(root, relativePath, bytes)
    }

    override suspend fun moveToRecovery(root: String, relativePath: String, recoveryRoot: String) = if (saf(root)) saf.moveToRecovery(root, relativePath, recoveryRoot) else files.moveToRecovery(root, relativePath, recoveryRoot)

    override suspend fun delete(root: String, relativePath: String) {
        if (saf(root)) saf.delete(root, relativePath) else files.delete(root, relativePath)
    }

    override suspend fun exists(root: String, relativePath: String) = if (saf(root)) saf.exists(root, relativePath) else files.exists(root, relativePath)

    override suspend fun move(root: String, from: String, to: String) {
        if (saf(root)) saf.move(root, from, to) else files.move(root, from, to)
    }

    override suspend fun ensureDirectory(root: String, relativePath: String) {
        if (saf(root)) saf.ensureDirectory(root, relativePath) else files.ensureDirectory(root, relativePath)
    }

    override suspend fun sha256(root: String, relativePath: String) = if (saf(root)) saf.sha256(root, relativePath) else files.sha256(root, relativePath)

    override suspend fun size(root: String, relativePath: String) = if (saf(root)) saf.size(root, relativePath) else files.size(root, relativePath)

    override suspend fun modifiedAt(root: String, relativePath: String) = if (saf(root)) saf.modifiedAt(root, relativePath) else files.modifiedAt(root, relativePath)

    fun defaultRoot(accountId: String, folderId: String): String {
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        val dir = File(base, "sync/$accountId/$folderId")
        dir.mkdirs()
        return dir.absolutePath
    }
}

class FileSystemLocalFiles : LocalFiles {
    override suspend fun list(root: String): List<LocalNode> = withContext(Dispatchers.IO) {
        val base = File(root)
        if (!base.exists()) return@withContext emptyList()
        base.walkTopDown().filter { it != base }.map { file ->
            LocalNode(
                relativePath = SyncPaths.normalizeRelative(file.relativeTo(base).path),
                directory = file.isDirectory,
                size = if (file.isDirectory) 0 else file.length(),
                modifiedAt = file.lastModified(),
            )
        }.toList()
    }

    override suspend fun read(root: String, relativePath: String): ByteArray = withContext(Dispatchers.IO) {
        resolve(root, relativePath).readBytes()
    }

    override suspend fun writeAtomically(root: String, relativePath: String, bytes: ByteArray) {
        withContext(Dispatchers.IO) {
            val target = resolve(root, relativePath)
            target.parentFile?.mkdirs()
            val temporary = File(target.parentFile, target.name + ".cloudsync-tmp")
            temporary.writeBytes(bytes)
            if (!temporary.renameTo(target)) {
                temporary.copyTo(target, overwrite = true)
                temporary.delete()
            }
        }
    }

    override suspend fun moveToRecovery(root: String, relativePath: String, recoveryRoot: String): String = withContext(Dispatchers.IO) {
        val source = resolve(root, relativePath)
        val destination = resolve(recoveryRoot, relativePath)
        destination.parentFile?.mkdirs()
        if (destination.exists()) destination.delete()
        if (!source.renameTo(destination)) {
            source.copyTo(destination, overwrite = true)
            source.delete()
        }
        destination.absolutePath
    }

    override suspend fun delete(root: String, relativePath: String) {
        withContext(Dispatchers.IO) { resolve(root, relativePath).deleteRecursively() }
    }

    override suspend fun exists(root: String, relativePath: String): Boolean = withContext(Dispatchers.IO) {
        resolve(root, relativePath).exists()
    }

    override suspend fun move(root: String, from: String, to: String) {
        withContext(Dispatchers.IO) {
            val source = resolve(root, from)
            val destination = resolve(root, to)
            destination.parentFile?.mkdirs()
            if (!source.renameTo(destination)) {
                source.copyTo(destination, overwrite = true)
                source.deleteRecursively()
            }
        }
    }

    override suspend fun ensureDirectory(root: String, relativePath: String) {
        withContext(Dispatchers.IO) {
            val dir = if (relativePath.isEmpty()) File(root) else resolve(root, relativePath)
            if (!dir.mkdirs() && !dir.isDirectory) throw InvalidNameException("Could not create $relativePath")
        }
    }

    override suspend fun sha256(root: String, relativePath: String): String = withContext(Dispatchers.IO) {
        resolve(root, relativePath).inputStream().use { ContentHash.sha256(it) }
    }

    override suspend fun size(root: String, relativePath: String): Long = withContext(Dispatchers.IO) {
        resolve(root, relativePath).length()
    }

    override suspend fun modifiedAt(root: String, relativePath: String): Long = withContext(Dispatchers.IO) {
        resolve(root, relativePath).lastModified()
    }

    private fun resolve(root: String, relativePath: String): File {
        val base = File(root).canonicalFile
        val relative = SyncPaths.normalizeRelative(relativePath)
        val target = if (relative.isEmpty()) base else File(base, relative).canonicalFile
        if (target != base && !target.path.startsWith(base.path + File.separator)) {
            throw InvalidNameException("Path escapes the sync folder.")
        }
        return target
    }
}

class SafLocalFiles(private val context: Context) : LocalFiles {
    override suspend fun list(root: String): List<LocalNode> = withContext(Dispatchers.IO) {
        val tree = tree(root) ?: return@withContext emptyList()
        val found = mutableListOf<LocalNode>()
        walk(tree, "", found)
        found
    }

    override suspend fun read(root: String, relativePath: String): ByteArray = withContext(Dispatchers.IO) {
        val document = requireFile(root, relativePath)
        context.contentResolver.openInputStream(document.uri)?.use { it.readBytes() }
            ?: throw InvalidNameException("Could not read $relativePath")
    }

    override suspend fun writeAtomically(root: String, relativePath: String, bytes: ByteArray) {
        withContext(Dispatchers.IO) {
            val relative = SyncPaths.normalizeRelative(relativePath)
            val parent = ensureParent(root, SyncPaths.parent(relative))
            val name = SyncPaths.name(relative)
            val temporaryName = "$name.cloudsync-tmp"
            parent.findFile(temporaryName)?.delete()
            val temporary = parent.createFile("application/octet-stream", temporaryName)
                ?: throw InvalidNameException("Could not create a temporary file for $name")
            context.contentResolver.openOutputStream(temporary.uri)?.use { it.write(bytes) }
                ?: throw InvalidNameException("Could not write $name")
            val existing = parent.findFile(name)
            existing?.delete()
            if (!temporary.renameTo(name)) {
                val created = parent.createFile("application/octet-stream", name)
                    ?: throw InvalidNameException("Could not replace $name")
                context.contentResolver.openOutputStream(created.uri)?.use { it.write(bytes) }
                temporary.delete()
            }
        }
    }

    override suspend fun moveToRecovery(root: String, relativePath: String, recoveryRoot: String): String {
        val bytes = read(root, relativePath)
        writeAtomically(recoveryRoot, relativePath, bytes)
        delete(root, relativePath)
        return SyncPaths.child(recoveryRoot, relativePath)
    }

    override suspend fun delete(root: String, relativePath: String) {
        withContext(Dispatchers.IO) { find(root, relativePath)?.delete() }
    }

    override suspend fun exists(root: String, relativePath: String): Boolean = withContext(Dispatchers.IO) {
        find(root, relativePath) != null
    }

    override suspend fun move(root: String, from: String, to: String) {
        val bytes = read(root, from)
        writeAtomically(root, to, bytes)
        delete(root, from)
    }

    override suspend fun ensureDirectory(root: String, relativePath: String) {
        withContext(Dispatchers.IO) { ensureParent(root, SyncPaths.normalizeRelative(relativePath)) }
    }

    override suspend fun sha256(root: String, relativePath: String): String = ContentHash.sha256(read(root, relativePath))

    override suspend fun size(root: String, relativePath: String): Long = withContext(Dispatchers.IO) {
        find(root, relativePath)?.length() ?: 0
    }

    override suspend fun modifiedAt(root: String, relativePath: String): Long = withContext(Dispatchers.IO) {
        find(root, relativePath)?.lastModified() ?: 0
    }

    private fun walk(directory: DocumentFile, prefix: String, into: MutableList<LocalNode>) {
        for (child in directory.listFiles()) {
            val name = child.name ?: continue
            val relative = if (prefix.isEmpty()) name else "$prefix/$name"
            into += LocalNode(relative, child.isDirectory, if (child.isDirectory) 0 else child.length(), child.lastModified())
            if (child.isDirectory) walk(child, relative, into)
        }
    }

    private fun tree(root: String): DocumentFile? = DocumentFile.fromTreeUri(context, Uri.parse(root))

    private fun find(root: String, relativePath: String): DocumentFile? {
        val relative = SyncPaths.normalizeRelative(relativePath)
        var current = tree(root) ?: return null
        if (relative.isEmpty()) return current
        for (part in relative.split('/')) {
            current = current.findFile(part) ?: return null
        }
        return current
    }

    private fun requireFile(root: String, relativePath: String): DocumentFile = find(root, relativePath) ?: throw InvalidNameException("Missing $relativePath")

    private fun ensureParent(root: String, relativePath: String): DocumentFile {
        var current = tree(root) ?: throw InvalidNameException("The selected folder is no longer available.")
        if (relativePath.isEmpty()) return current
        for (part in SyncPaths.normalizeRelative(relativePath).split('/')) {
            val next = current.findFile(part)
            current = when {
                next == null -> current.createDirectory(part) ?: throw InvalidNameException("Could not create $part")
                next.isDirectory -> next
                else -> throw InvalidNameException("$part is a file.")
            }
        }
        return current
    }
}
