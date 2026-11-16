package dev.cloudsync.app.data

import android.content.Context
import android.net.Uri
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import dev.cloudsync.app.sync.SyncScheduler
import dev.cloudsync.core.error.AuthenticationException
import dev.cloudsync.core.model.Account
import dev.cloudsync.core.model.AccountStatus
import dev.cloudsync.core.model.DeletionPolicy
import dev.cloudsync.core.model.ExcludeRules
import dev.cloudsync.core.model.FolderStatus
import dev.cloudsync.core.model.NetworkMode
import dev.cloudsync.core.model.ScheduleMode
import dev.cloudsync.core.model.SyncDirection
import dev.cloudsync.core.model.SyncFolder
import dev.cloudsync.core.model.endpoint
import dev.cloudsync.core.remote.ContentSource
import dev.cloudsync.core.remote.RemoteFiles
import dev.cloudsync.core.remote.RemoteNode
import dev.cloudsync.core.text.TextFiles
import dev.cloudsync.core.time.Clock
import dev.cloudsync.core.time.IdGenerator
import dev.cloudsync.core.url.ServerUrls
import dev.cloudsync.database.CloudSyncDatabase
import dev.cloudsync.database.toEntity
import dev.cloudsync.database.toModel
import dev.cloudsync.nextcloud.LoginChallenge
import dev.cloudsync.nextcloud.LoginFlowClient
import dev.cloudsync.nextcloud.LoginPoll
import dev.cloudsync.nextcloud.OcsClient
import dev.cloudsync.security.CredentialStore
import dev.cloudsync.storage.DelegatingLocalFiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

val Context.settingsDataStore by preferencesDataStore(name = "cloudsync-settings")

object SettingKeys {
    val notifySync = booleanPreferencesKey("notify_sync")
    val notifyConflicts = booleanPreferencesKey("notify_conflicts")
    val notifyAuth = booleanPreferencesKey("notify_auth")
}

@Singleton
class AccountRepository @Inject constructor(
    private val database: CloudSyncDatabase,
    private val secrets: CredentialStore,
    private val login: LoginFlowClient,
    private val ocs: OcsClient,
    private val scheduler: SyncScheduler,
    private val ids: IdGenerator,
    private val clock: Clock,
) {
    fun observe() = database.accounts().observe().map { rows -> rows.map { it.toModel() } }

    suspend fun all(): List<Account> = database.accounts().all().map { it.toModel() }

    suspend fun get(id: String): Account? = database.accounts().get(id)?.toModel()

    suspend fun beginLogin(serverUrl: String): LoginChallenge = withContext(Dispatchers.IO) {
        val server = ServerUrls.normalize(serverUrl)
        ocs.probe(server)
        login.start(server)
    }

    suspend fun poll(challenge: LoginChallenge): LoginPoll = withContext(Dispatchers.IO) {
        login.poll(challenge.endpoint, challenge.token)
    }

    suspend fun completeLogin(success: LoginPoll.Success): Account = saveAccount(success.server, success.loginName, success.appPassword)

    suspend fun addAppPassword(serverUrl: String, username: String, secret: String): Account = saveAccount(serverUrl, username.trim(), secret)

    suspend fun disconnect(id: String) = withContext(Dispatchers.IO) {
        val account = database.accounts().get(id)?.toModel() ?: return@withContext
        val secret = secrets.secret(id)
        if (secret != null) ocs.deleteAppPassword(account.serverUrl, account.loginName, secret)
        database.folders().forAccount(id).forEach { scheduler.cancel(it.id) }
        secrets.delete(id)
        database.versions().deleteForAccount(id)
        database.accounts().delete(id)
    }

    suspend fun markExpired(id: String) {
        val account = database.accounts().get(id)?.toModel() ?: return
        database.accounts().upsert(account.copy(status = AccountStatus.AUTH_EXPIRED).toEntity())
    }

    suspend fun endpoint(id: String) = database.accounts().get(id)?.toModel()?.endpoint()
        ?: throw AuthenticationException("That Nextcloud account is no longer connected.")

    private suspend fun saveAccount(serverUrl: String, username: String, secret: String): Account = withContext(Dispatchers.IO) {
        val server = ServerUrls.normalize(serverUrl)
        val stored = ocs.preferAppPassword(server, username, secret)
        val user = ocs.currentUser(server, username, stored)
        val account = Account(
            id = ids.newId(),
            serverUrl = server,
            loginName = username,
            userId = user.userId,
            displayName = user.displayName,
            status = AccountStatus.ACTIVE,
            createdAt = clock.nowMillis(),
            lastValidatedAt = clock.nowMillis(),
        )
        secrets.put(account.id, stored)
        database.accounts().upsert(account.toEntity())
        account
    }
}

@Singleton
class FolderRepository @Inject constructor(
    private val database: CloudSyncDatabase,
    private val local: DelegatingLocalFiles,
    private val scheduler: SyncScheduler,
    private val ids: IdGenerator,
) {
    fun observe() = database.folders().observe().map { rows -> rows.map { it.toModel() } }

    suspend fun get(id: String): SyncFolder? = database.folders().get(id)?.toModel()

    suspend fun all(): List<SyncFolder> = database.folders().all().map { it.toModel() }

    suspend fun create(accountId: String, remotePath: String, displayName: String): SyncFolder {
        val id = ids.newId()
        val folder = SyncFolder(
            id = id,
            accountId = accountId,
            displayName = displayName,
            remotePath = if (remotePath.startsWith("/")) remotePath else "/$remotePath",
            localRoot = local.defaultRoot(accountId, id),
            direction = SyncDirection.TWO_WAY,
            exclude = ExcludeRules(),
            paused = false,
            networkMode = NetworkMode.WIFI_AND_MOBILE,
            allowUploadOnWifi = true,
            allowUploadOnMobile = true,
            allowDownloadOnWifi = true,
            allowDownloadOnMobile = true,
            requireCharging = false,
            minBatteryPercent = 0,
            schedule = ScheduleMode.IMMEDIATE,
            deletionPolicy = DeletionPolicy.SAFE_RECOVERY,
            lastSuccessAt = null,
            lastError = null,
            status = FolderStatus.IDLE,
        )
        save(folder)
        return folder
    }

    suspend fun save(folder: SyncFolder) {
        database.folders().upsert(folder.toEntity())
        scheduler.schedule(folder)
    }

    suspend fun remove(id: String) {
        scheduler.cancel(id)
        database.folders().delete(id)
    }

    suspend fun useTree(folder: SyncFolder, treeUri: String): SyncFolder {
        val updated = folder.copy(localRoot = treeUri)
        save(updated)
        return updated
    }
}

@Singleton
class BrowserRepository @Inject constructor(
    private val remote: RemoteFiles,
    private val accounts: AccountRepository,
    private val folders: FolderRepository,
) {
    suspend fun list(accountId: String, path: String): List<RemoteNode> = remote.list(accounts.endpoint(accountId), path)

    suspend fun search(accountId: String, term: String): List<RemoteNode> = remote.search(accounts.endpoint(accountId), term)

    suspend fun createFolder(accountId: String, path: String) {
        ensureUploadAllowed(accountId, path)
        remote.createDirectory(accounts.endpoint(accountId), path)
    }

    suspend fun delete(accountId: String, path: String) {
        ensureUploadAllowed(accountId, path)
        val node = remote.stat(accounts.endpoint(accountId), path)
        remote.delete(accounts.endpoint(accountId), path, node?.etag)
    }

    suspend fun rename(accountId: String, path: String, newName: String) {
        ensureUploadAllowed(accountId, path)
        val parent = path.trimEnd('/').substringBeforeLast('/', "")
        val destination = if (parent.isEmpty()) "/$newName" else "$parent/$newName"
        remote.move(accounts.endpoint(accountId), path, destination, overwrite = false)
    }

    suspend fun move(accountId: String, path: String, destinationDirectory: String) {
        ensureUploadAllowed(accountId, destinationDirectory)
        val name = path.trimEnd('/').substringAfterLast('/')
        remote.move(accounts.endpoint(accountId), path, destinationDirectory.trimEnd('/') + "/" + name, overwrite = false)
    }

    suspend fun copy(accountId: String, path: String, destinationDirectory: String) {
        ensureUploadAllowed(accountId, destinationDirectory)
        val name = path.trimEnd('/').substringAfterLast('/')
        remote.copy(accounts.endpoint(accountId), path, destinationDirectory.trimEnd('/') + "/" + name, overwrite = false)
    }

    suspend fun upload(accountId: String, directory: String, displayName: String, bytes: ByteArray) {
        ensureUploadAllowed(accountId, directory)
        val path = directory.trimEnd('/') + "/" + displayName
        remote.upload(
            accounts.endpoint(accountId),
            path,
            ContentSource { bytes.inputStream() },
            bytes.size.toLong(),
            mimeFor(displayName),
            null,
        )
    }

    suspend fun download(accountId: String, path: String) = remote.download(accounts.endpoint(accountId), path, null)

    suspend fun stat(accountId: String, path: String) = remote.stat(accounts.endpoint(accountId), path)

    suspend fun readText(accountId: String, path: String): Pair<String, String?> {
        val download = download(accountId, path)
        val text =
            TextFiles.decodeUtf8(download.bytes) ?: throw dev.cloudsync.core.error.ValidationException("This file is not UTF-8 text.")
        return text to download.etag
    }

    suspend fun uploadText(accountId: String, path: String, text: String, ifMatch: String?) {
        ensureUploadAllowed(accountId, path)
        val bytes = text.encodeToByteArray()
        remote.upload(
            accounts.endpoint(accountId),
            path,
            ContentSource { bytes.inputStream() },
            bytes.size.toLong(),
            mimeFor(path.substringAfterLast('/')),
            ifMatch,
        )
    }

    private suspend fun ensureUploadAllowed(accountId: String, path: String) {
        val target = path.trimEnd('/').ifEmpty { "/" }
        val blocked = folders.all().any { folder ->
            val root = folder.remotePath.trimEnd('/').ifEmpty { "/" }
            val inside = root == "/" || target == root || target.startsWith("$root/")
            folder.accountId == accountId && folder.direction == SyncDirection.DOWNLOAD_ONLY && inside
        }
        if (blocked) {
            throw dev.cloudsync.core.error.PermissionException("This folder is download-only. CloudSync will not upload changes into it.")
        }
    }
}

fun mimeFor(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
    "txt" -> "text/plain"
    "md", "markdown" -> "text/markdown"
    "json" -> "application/json"
    "xml" -> "application/xml"
    "yaml", "yml" -> "application/yaml"
    "csv" -> "text/csv"
    "html", "htm" -> "text/html"
    "png" -> "image/png"
    "jpg", "jpeg" -> "image/jpeg"
    "pdf" -> "application/pdf"
    else -> "application/octet-stream"
}

fun Uri.displayName(): String = lastPathSegment?.substringAfterLast('/') ?: "upload"
