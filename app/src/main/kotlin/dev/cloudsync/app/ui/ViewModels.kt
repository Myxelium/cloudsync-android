package dev.cloudsync.app.ui

import android.content.Context
import android.net.Uri
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.cloudsync.app.data.AccountRepository
import dev.cloudsync.app.data.BrowserRepository
import dev.cloudsync.app.data.FolderRepository
import dev.cloudsync.app.data.SettingKeys
import dev.cloudsync.app.data.mimeFor
import dev.cloudsync.app.sync.CloudSyncTransferService
import dev.cloudsync.app.sync.NetworkMonitor
import dev.cloudsync.app.sync.SyncCoordinator
import dev.cloudsync.core.error.NetworkException
import dev.cloudsync.core.model.Account
import dev.cloudsync.core.model.Conflict
import dev.cloudsync.core.model.DeletionPolicy
import dev.cloudsync.core.model.DiagnosticEvent
import dev.cloudsync.core.model.ExcludeRules
import dev.cloudsync.core.model.FileVersion
import dev.cloudsync.core.model.NetworkMode
import dev.cloudsync.core.model.NetworkSnapshot
import dev.cloudsync.core.model.ScheduleMode
import dev.cloudsync.core.model.SyncDirection
import dev.cloudsync.core.model.SyncFolder
import dev.cloudsync.core.model.Transfer
import dev.cloudsync.core.model.endpoint
import dev.cloudsync.core.path.SyncPaths
import dev.cloudsync.core.remote.RemoteNode
import dev.cloudsync.core.remote.RemoteVersion
import dev.cloudsync.core.text.TextFiles
import dev.cloudsync.database.CloudSyncDatabase
import dev.cloudsync.database.toModel
import dev.cloudsync.merge.MergeCoordinator
import dev.cloudsync.merge.MergeResult
import dev.cloudsync.merge.TextDiff
import dev.cloudsync.nextcloud.LoginChallenge
import dev.cloudsync.nextcloud.LoginPoll
import dev.cloudsync.sync.SyncSchedule
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import javax.inject.Inject

enum class FileSort { NAME, DATE, SIZE, TYPE }

data class BrowserState(
    val loading: Boolean = true,
    val path: String = "/",
    val entries: List<RemoteNode> = emptyList(),
    val error: String? = null,
    val selected: Set<String> = emptySet(),
    val sort: FileSort = FileSort.NAME,
    val details: RemoteNode? = null,
    val message: String? = null,
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    accounts: AccountRepository,
    folders: FolderRepository,
    database: CloudSyncDatabase,
    private val network: NetworkMonitor,
    @ApplicationContext private val context: Context,
) : ViewModel() {
    val accounts = accounts.observe().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val folders = folders.observe().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val transfers = database.transfers().observe().map { rows -> rows.map { it.toModel() } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val conflicts = database.conflicts().observeOpen().map { rows -> rows.map { it.toModel() } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val networkSnapshot = MutableStateFlow(network.snapshot())

    fun refreshNetwork() {
        networkSnapshot.value = network.snapshot()
    }

    fun syncAll() {
        CloudSyncTransferService.start(context, null)
    }
}

@HiltViewModel
class FilesViewModel @Inject constructor(
    private val accounts: AccountRepository,
    private val browser: BrowserRepository,
    private val folders: FolderRepository,
    @ApplicationContext private val context: Context,
) : ViewModel() {
    val accountsState = accounts.observe().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    private val _state = MutableStateFlow(BrowserState())
    val state = _state.asStateFlow()
    var accountId: String? = null
        private set

    fun selectAccount(id: String) {
        accountId = id
        open("/")
    }

    fun open(path: String) {
        val account = accountId ?: return
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, error = null, path = path, selected = emptySet(), details = null)
            _state.value = try {
                val entries = sort(browser.list(account, path), _state.value.sort)
                _state.value.copy(loading = false, entries = entries, path = path)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _state.value.copy(loading = false, error = error.message?.takeIf { it.isNotBlank() } ?: "Could not open this folder.")
            }
        }
    }

    fun sort(sort: FileSort) {
        _state.value = _state.value.copy(sort = sort, entries = sort(_state.value.entries, sort))
    }

    fun search(term: String) {
        val account = accountId ?: return
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, error = null)
            _state.value = try {
                val entries = if (term.isBlank()) browser.list(account, _state.value.path) else browser.search(account, term)
                _state.value.copy(loading = false, entries = sort(entries, _state.value.sort))
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _state.value.copy(loading = false, error = error.message?.takeIf { it.isNotBlank() } ?: "Search failed.")
            }
        }
    }

    fun toggle(path: String) {
        val selected = _state.value.selected.toMutableSet()
        if (!selected.add(path)) selected.remove(path)
        _state.value = _state.value.copy(selected = selected)
    }

    fun details(node: RemoteNode?) {
        _state.value = _state.value.copy(details = node)
    }

    fun createFolder(name: String) = mutate { browser.createFolder(it, child(name)) }

    fun deleteSelected() {
        val paths = _state.value.selected.toList()
        mutate {
            paths.forEach { path -> browser.delete(it, path) }
        }
    }

    fun rename(path: String, name: String) = mutate { browser.rename(it, path, name) }

    fun move(path: String, destination: String) = mutate { browser.move(it, path, destination) }

    fun copy(path: String, destination: String) = mutate { browser.copy(it, path, destination) }

    fun upload(uri: Uri, displayName: String) {
        val account = accountId ?: return
        viewModelScope.launch {
            try {
                val bytes = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                } ?: error("Could not read the selected file.")
                browser.upload(account, _state.value.path, displayName, bytes)
                open(_state.value.path)
            } catch (error: Exception) {
                _state.value = _state.value.copy(error = error.message ?: "Upload failed.")
            }
        }
    }

    fun prepareExternal(path: String, onReady: (java.io.File, String?) -> Unit) {
        val account = accountId ?: return
        viewModelScope.launch {
            try {
                val result = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    val download = browser.download(account, path)
                    val file = java.io.File(context.cacheDir, "open/" + SyncPaths.name(path))
                    file.parentFile?.mkdirs()
                    file.writeBytes(download.bytes)
                    file to download.contentType
                }
                onReady(result.first, result.second)
            } catch (error: Exception) {
                _state.value = _state.value.copy(error = error.message ?: "Could not download the file.")
            }
        }
    }

    fun syncHere() {
        val account = accountId ?: return
        val path = _state.value.path
        viewModelScope.launch {
            try {
                val name = if (path == "/") "Files" else SyncPaths.name(path)
                folders.create(account, path, name)
                _state.value = _state.value.copy(message = "“$name” will synchronize on this device.")
            } catch (error: Exception) {
                _state.value = _state.value.copy(error = error.message ?: "Could not add this folder.")
            }
        }
    }

    private fun child(name: String): String {
        val parent = _state.value.path.trimEnd('/')
        return if (parent.isEmpty()) "/$name" else "$parent/$name"
    }

    private fun mutate(block: suspend (String) -> Unit) {
        val account = accountId ?: return
        viewModelScope.launch {
            try {
                block(account)
                open(_state.value.path)
            } catch (error: Exception) {
                _state.value = _state.value.copy(error = error.message ?: "The server rejected that change.")
            }
        }
    }

    private fun sort(entries: List<RemoteNode>, sort: FileSort): List<RemoteNode> {
        val comparator = when (sort) {
            FileSort.NAME -> compareBy<RemoteNode> { !it.directory }.thenBy { SyncPaths.name(it.path).lowercase() }
            FileSort.DATE -> compareByDescending { it.modifiedAtEpochMillis ?: 0 }
            FileSort.SIZE -> compareByDescending { it.size }
            FileSort.TYPE -> compareBy<RemoteNode> { !it.directory }.thenBy { SyncPaths.name(it.path).substringAfterLast('.', "") }
        }
        return entries.sortedWith(comparator)
    }
}

@HiltViewModel
class SyncViewModel @Inject constructor(
    private val folders: FolderRepository,
    accounts: AccountRepository,
    @ApplicationContext private val context: Context,
) : ViewModel() {
    val foldersState = folders.observe().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val accounts = accounts.observe().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val explanation = SyncSchedule.EXPLANATION

    fun save(folder: SyncFolder) {
        viewModelScope.launch { folders.save(folder) }
    }

    fun remove(id: String) {
        viewModelScope.launch { folders.remove(id) }
    }

    fun sync(id: String) {
        CloudSyncTransferService.start(context, id)
    }

    fun useTree(folder: SyncFolder, uri: String) {
        viewModelScope.launch { folders.useTree(folder, uri) }
    }
}

@HiltViewModel
class ConflictViewModel @Inject constructor(
    private val database: CloudSyncDatabase,
    private val coordinator: SyncCoordinator,
    savedState: SavedStateHandle,
) : ViewModel() {
    val conflictId: String? = savedState.get<String>("id")
    val open = database.conflicts().observeOpen().map { rows -> rows.map { it.toModel() } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    private val _result = MutableStateFlow<MergeResult?>(null)
    val result = _result.asStateFlow()
    private val _preview = MutableStateFlow("")
    val preview = _preview.asStateFlow()
    private val _message = MutableStateFlow<String?>(null)
    val message = _message.asStateFlow()
    private var loaded: Conflict? = null
    private var localBytes: ByteArray? = null
    private var remoteBytes: ByteArray? = null

    fun load(id: String) {
        viewModelScope.launch {
            val conflict = database.conflicts().get(id)?.toModel() ?: return@launch
            loaded = conflict
            val base = conflict.baseVersionId?.let { database.versions().content(it) } ?: ByteArray(0)
            localBytes = conflict.localVersionId?.let { database.versions().content(it) }
            remoteBytes = conflict.remoteVersionId?.let { database.versions().content(it) }
            val local = localBytes ?: ByteArray(0)
            val remote = remoteBytes ?: ByteArray(0)
            val merged = MergeCoordinator().merge(SyncPaths.name(conflict.remotePath), null, base, local, remote)
            _result.value = merged
            _preview.value = merged.mergedText.orEmpty()
        }
    }

    fun choose(choices: List<String>) {
        val merged = _result.value ?: return
        if (merged.binary) return
        _preview.value = runCatching { merged.withChoices(choices) }.getOrElse { _preview.value }
    }

    fun edit(text: String) {
        _preview.value = text
    }

    fun saveMerged() {
        val conflict = loaded ?: return
        viewModelScope.launch {
            val report = coordinator.resolve(conflict.folderId, conflict.id, _preview.value.encodeToByteArray(), "Manual merge")
            _message.value = report.errors.firstOrNull() ?: report.waitingReason ?: "The merged copy was saved."
        }
    }

    fun keep(local: Boolean) {
        val conflict = loaded ?: return
        val bytes = if (local) localBytes else remoteBytes
        if (bytes == null) {
            _message.value = "That copy has no saved snapshot, so it was left untouched."
            return
        }
        viewModelScope.launch {
            val report = coordinator.resolve(conflict.folderId, conflict.id, bytes, if (local) "Kept the device copy" else "Kept the server copy")
            _message.value = report.errors.firstOrNull() ?: "The selected copy is now current. The other copy stays in history."
        }
    }
}

@HiltViewModel
class VersionViewModel @Inject constructor(
    private val database: CloudSyncDatabase,
    private val coordinator: SyncCoordinator,
    private val folders: FolderRepository,
    savedState: SavedStateHandle,
) : ViewModel() {
    val fileKey: String = savedState.get<String>("fileKey").orEmpty()
    val history = database.versions().observeHistory(fileKey).map { rows -> rows.map { it.toModel() } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    private val _diff = MutableStateFlow<String?>(null)
    val diff = _diff.asStateFlow()
    private val _remoteVersions = MutableStateFlow<List<RemoteVersion>>(emptyList())
    val remoteVersions = _remoteVersions.asStateFlow()
    private val _message = MutableStateFlow<String?>(null)
    val message = _message.asStateFlow()

    fun compare(left: FileVersion, right: FileVersion) {
        viewModelScope.launch {
            val a = database.versions().content(left.id)?.let { TextFiles.decodeUtf8(it) }
            val b = database.versions().content(right.id)?.let { TextFiles.decodeUtf8(it) }
            _diff.value = if (a == null || b == null) {
                "These snapshots are not comparable as text. Both copies are still stored."
            } else {
                TextDiff.unified(a, b)
            }
        }
    }

    fun restore(version: FileVersion) {
        viewModelScope.launch {
            val bytes = database.versions().content(version.id)
            if (bytes == null) {
                _message.value = "This version has no stored snapshot."
                return@launch
            }
            val folder = folders.all().firstOrNull { version.fileKey.startsWith(it.accountId + "|") && version.fileKey.substringAfter('|').startsWith(it.remotePath.trimEnd('/')) }
            if (folder == null) {
                _message.value = "Add the folder to synchronization before restoring into it."
                return@launch
            }
            val relative = version.fileKey.substringAfter('|').removePrefix(folder.remotePath).trim('/')
            val report = coordinator.restore(folder.id, relative, bytes)
            _message.value = report.errors.firstOrNull() ?: report.waitingReason ?: "Restored ${SyncPaths.name(version.fileKey.substringAfter('|'))}."
        }
    }

    fun loadRemote(accountId: String, fileId: String, remote: dev.cloudsync.core.remote.RemoteFiles) {
        viewModelScope.launch {
            _remoteVersions.value = runCatching {
                val account = database.accounts().get(accountId)?.toModel() ?: return@runCatching emptyList()
                remote.listVersions(account.endpoint(), fileId)
            }.getOrDefault(emptyList())
        }
    }
}

@HiltViewModel
class TransferViewModel @Inject constructor(database: CloudSyncDatabase) : ViewModel() {
    val transfers = database.transfers().observe().map { rows -> rows.map { it.toModel() } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settings: DataStore<Preferences>,
    database: CloudSyncDatabase,
    private val accounts: AccountRepository,
) : ViewModel() {
    val preferences = settings.data.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val logs = database.diagnostics().observe().map { rows -> rows.map { it.toModel() } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val accountsState = accounts.observe().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun set(key: Preferences.Key<Boolean>, value: Boolean) {
        viewModelScope.launch { settings.edit { it[key] = value } }
    }

    fun disconnect(id: String) {
        viewModelScope.launch { accounts.disconnect(id) }
    }
}

data class LoginState(val busy: Boolean = false, val message: String? = null, val done: Boolean = false, val url: String? = null)

@HiltViewModel
class LoginViewModel @Inject constructor(
    private val accounts: AccountRepository,
    private val savedState: SavedStateHandle,
) : ViewModel() {
    private val _state = MutableStateFlow(LoginState())
    val state = _state.asStateFlow()
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private var session: Job? = null

    init {
        if (savedState.get<Boolean>(LOGIN_DONE) == true) {
            _state.value = LoginState(done = true, message = "Connected.")
        } else {
            val endpoint = savedState.get<String>(LOGIN_ENDPOINT)
            val token = savedState.get<String>(LOGIN_TOKEN)
            if (!endpoint.isNullOrBlank() && !token.isNullOrBlank()) {
                val challenge = LoginChallenge(token, endpoint, savedState.get<String>(LOGIN_URL).orEmpty())
                session = viewModelScope.launch { awaitCredentials(challenge, openBrowser = false) }
            }
        }
    }

    fun onResume() {
        wake.trySend(Unit)
    }

    fun start(server: String) {
        session?.cancel()
        savedState[LOGIN_DONE] = false
        session = viewModelScope.launch {
            _state.value = LoginState(busy = true, message = "Contacting the Nextcloud server…")
            try {
                val challenge = accounts.beginLogin(server)
                savedState[LOGIN_ENDPOINT] = challenge.endpoint
                savedState[LOGIN_TOKEN] = challenge.token
                savedState[LOGIN_URL] = challenge.loginUrl
                awaitCredentials(challenge, openBrowser = true)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _state.value = LoginState(message = error.message ?: "Could not start sign-in.")
            }
        }
    }

    private suspend fun awaitCredentials(challenge: LoginChallenge, openBrowser: Boolean) {
        _state.value = LoginState(
            busy = true,
            url = challenge.loginUrl.takeIf { openBrowser },
            message = "Approve CloudSync in the browser. Sign-in finishes here after Nextcloud grants access, including when you come back.",
        )
        val deadline = System.currentTimeMillis() + LOGIN_WINDOW_MILLIS
        while (System.currentTimeMillis() < deadline) {
            val outcome = try {
                accounts.poll(challenge)
            } catch (error: CancellationException) {
                throw error
            } catch (_: NetworkException) {
                LoginPoll.Pending
            } catch (error: Exception) {
                forgetLogin()
                _state.value = LoginState(message = error.message ?: "Could not finish sign-in.")
                return
            }
            when (outcome) {
                LoginPoll.Pending -> waitForPoll(2_000)
                LoginPoll.Throttled -> waitForPoll(8_000)
                is LoginPoll.Success -> {
                    try {
                        accounts.completeLogin(outcome)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        forgetLogin()
                        _state.value = LoginState(message = error.message ?: "Nextcloud approved the login, but the app could not store it.")
                        return
                    }
                    forgetLogin()
                    savedState[LOGIN_DONE] = true
                    _state.value = LoginState(done = true, message = "Connected.")
                    return
                }
            }
        }
        forgetLogin()
        _state.value = LoginState(message = "The sign-in window expired before Nextcloud returned an app password.")
    }

    private suspend fun waitForPoll(millis: Long) {
        withTimeoutOrNull(millis) { wake.receive() }
    }

    private fun forgetLogin() {
        savedState[LOGIN_ENDPOINT] = null
        savedState[LOGIN_TOKEN] = null
        savedState[LOGIN_URL] = null
    }

    fun appPassword(server: String, username: String, secret: String) {
        viewModelScope.launch {
            _state.value = LoginState(busy = true)
            try {
                accounts.addAppPassword(server, username, secret)
                _state.value = LoginState(done = true, message = "Connected with an app password.")
            } catch (error: Exception) {
                _state.value = LoginState(message = error.message ?: "Nextcloud rejected that login.")
            }
        }
    }

    fun consumeUrl() {
        _state.value = _state.value.copy(url = null)
    }

    private companion object {
        const val LOGIN_ENDPOINT = "loginEndpoint"
        const val LOGIN_TOKEN = "loginToken"
        const val LOGIN_URL = "loginUrl"
        const val LOGIN_DONE = "loginDone"
        const val LOGIN_WINDOW_MILLIS = 20 * 60 * 1000L
    }
}

data class EditorState(
    val loading: Boolean = true,
    val text: String = "",
    val etag: String? = null,
    val message: String? = null,
    val editable: Boolean = true,
)

@HiltViewModel
class EditorViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val browser: BrowserRepository,
    private val folders: FolderRepository,
    private val database: CloudSyncDatabase,
    private val coordinator: SyncCoordinator,
    @ApplicationContext private val context: Context,
) : ViewModel() {
    val accountId: String = savedState.get<String>("accountId").orEmpty()
    val path: String = savedState.get<String>("path").orEmpty()
    private val _state = MutableStateFlow(EditorState())
    val state = _state.asStateFlow()
    private var openedEtag: String? = null

    init {
        viewModelScope.launch {
            try {
                val (text, etag) = browser.readText(accountId, path)
                openedEtag = etag
                _state.value = EditorState(loading = false, text = text, etag = etag, editable = TextFiles.isEditable(SyncPaths.name(path), mimeFor(path), text.encodeToByteArray()))
            } catch (error: Exception) {
                _state.value = EditorState(loading = false, editable = false, message = error.message ?: "This file cannot be edited here.")
            }
        }
    }

    fun edit(value: String) {
        _state.value = _state.value.copy(text = value)
    }

    fun save() {
        viewModelScope.launch {
            try {
                val current = browser.stat(accountId, path)
                if (openedEtag != null && current?.etag != null && !dev.cloudsync.core.etag.Etags.same(openedEtag, current.etag)) {
                    _state.value = _state.value.copy(message = "The server copy changed while you were editing. Your text is still in the editor and was not uploaded.")
                    return@launch
                }
                val folder = folders.all().firstOrNull { folder ->
                    val root = folder.remotePath.trimEnd('/')
                    folder.accountId == accountId && (path == root || path.startsWith("$root/"))
                }
                if (folder != null) {
                    val relative = path.removePrefix(folder.remotePath).trim('/')
                    if (folder.direction == SyncDirection.DOWNLOAD_ONLY) {
                        _state.value = _state.value.copy(message = "This folder is download-only, so the edit stays in the editor and is not uploaded.")
                        return@launch
                    }
                    saveLocalAndSync(folder, relative)
                } else {
                    browser.uploadText(accountId, path, _state.value.text, openedEtag)
                    _state.value = _state.value.copy(message = "Uploaded.")
                }
            } catch (error: Exception) {
                _state.value = _state.value.copy(message = error.message ?: "Could not save.")
            }
        }
    }

    private suspend fun saveLocalAndSync(folder: SyncFolder, relative: String) {
        val bytes = _state.value.text.encodeToByteArray()
        val files = dev.cloudsync.storage.FileSystemLocalFiles()
        if (files.exists(folder.localRoot, relative)) {
            val existing = files.read(folder.localRoot, relative)
            val id = java.util.UUID.randomUUID().toString()
            database.versions().insert(
                dev.cloudsync.database.FileVersionEntity(
                    id = id,
                    fileKey = dev.cloudsync.core.model.fileKey(accountId, path),
                    parentIds = "",
                    createdAt = System.currentTimeMillis(),
                    sha256 = dev.cloudsync.core.hash.ContentHash.sha256(existing),
                    size = existing.size.toLong(),
                    origin = dev.cloudsync.core.model.VersionOrigin.LOCAL.name,
                    conflictId = null,
                    description = "Snapshot before edit",
                    remoteEtag = openedEtag,
                    mergeMetadata = null,
                    hasSnapshot = true,
                ),
            )
            database.versions().insertBlob(dev.cloudsync.database.VersionBlobEntity(id, existing))
        }
        if (!folder.localRoot.startsWith("content://")) {
            files.writeAtomically(folder.localRoot, relative, bytes)
            CloudSyncTransferService.start(context, folder.id)
            _state.value = _state.value.copy(message = "Saved on this device. Synchronization will upload it when the folder rules allow.")
        } else {
            browser.uploadText(accountId, path, _state.value.text, openedEtag)
            _state.value = _state.value.copy(message = "Uploaded. The folder uses a selected device location, so the server copy was updated directly.")
        }
    }

    fun cacheForExternal(): File? = null
}
