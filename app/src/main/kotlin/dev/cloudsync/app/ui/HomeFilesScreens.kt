package dev.cloudsync.app.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.cloudsync.app.shareCache
import dev.cloudsync.core.model.TransferStatus
import dev.cloudsync.core.path.SyncPaths
import dev.cloudsync.core.text.TextFiles
import dev.cloudsync.ui.EmptyPane
import dev.cloudsync.ui.ErrorPane
import dev.cloudsync.ui.SectionCard
import java.io.File
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(onAddAccount: () -> Unit, onTransfers: () -> Unit, onConflicts: () -> Unit, viewModel: HomeViewModel = hiltViewModel()) {
    val accounts by viewModel.accounts.collectAsStateWithLifecycle()
    val folders by viewModel.folders.collectAsStateWithLifecycle()
    val transfers by viewModel.transfers.collectAsStateWithLifecycle()
    val conflicts by viewModel.conflicts.collectAsStateWithLifecycle()
    val network by viewModel.networkSnapshot.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.refreshNetwork() }
    val active = transfers.count { it.status == TransferStatus.RUNNING || it.status == TransferStatus.QUEUED }
    val pendingUploads = transfers.count {
        it.direction.name == "UPLOAD" && it.status != TransferStatus.COMPLETED &&
            it.status != TransferStatus.CANCELLED
    }
    val pendingDownloads = transfers.count {
        it.direction.name == "DOWNLOAD" && it.status != TransferStatus.COMPLETED &&
            it.status != TransferStatus.CANCELLED
    }
    val lastSuccess = folders.mapNotNull { it.lastSuccessAt }.maxOrNull()
    Scaffold(topBar = { TopAppBar(title = { Text("CloudSync") }) }) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                SectionCard(
                    "Network",
                    when {
                        !network.validated -> "Waiting for a validated connection."
                        network.wifi && !network.metered -> "Unmetered Wi-Fi is available."
                        network.cellular -> "Mobile data is the active network."
                        else -> "A network is connected."
                    } + if (network.charging) " The device is charging." else " The device is not charging.",
                )
            }
            item {
                SectionCard(
                    "Synchronization",
                    "$active active or queued transfers. $pendingUploads uploads and $pendingDownloads downloads are not finished. " +
                        if (lastSuccess == null) {
                            "No folder has completed a sync yet."
                        } else {
                            "Last success ${DateFormat.getDateTimeInstance().format(Date(lastSuccess))}."
                        },
                )
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = viewModel::syncAll, enabled = folders.any { !it.paused }) { Text("Sync all") }
                    TextButton(onClick = onTransfers) { Text("Transfers") }
                    TextButton(onClick = onConflicts) { Text("Conflicts ${conflicts.size}") }
                }
            }
            if (accounts.isEmpty()) {
                item {
                    EmptyPane(
                        "No Nextcloud account yet",
                        "Connect a server with Login Flow v2 or an app password. CloudSync stores the app password in Android Keystore and never sends it anywhere else.",
                        "Add account",
                        onAddAccount,
                    )
                }
            }
            items(accounts, key = { it.id }) { account ->
                SectionCard(
                    account.displayName,
                    "${account.serverUrl}\n${account.loginName} · ${account.status.name.replace('_', ' ').lowercase()}",
                )
            }
            item { TextButton(onClick = onAddAccount) { Text("Add another account") } }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilesScreen(onEdit: (String, String) -> Unit, onVersions: (String) -> Unit, viewModel: FilesViewModel = hiltViewModel()) {
    val accounts by viewModel.accountsState.collectAsStateWithLifecycle()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    var create by remember { mutableStateOf(false) }
    var rename by remember { mutableStateOf<String?>(null) }
    var destination by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    var query by remember { mutableStateOf("") }
    LaunchedEffect(accounts) {
        if (viewModel.accountId == null && accounts.isNotEmpty()) viewModel.selectAccount(accounts.first().id)
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.upload(uri, uri.lastPathSegment?.substringAfterLast('/') ?: "upload")
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(state.path) },
                actions = {
                    TextButton(onClick = { menu = true }) { Text("Sort") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        FileSort.entries.forEach { sort ->
                            DropdownMenuItem(text = { Text(sort.name.lowercase()) }, onClick = {
                                menu = false
                                viewModel.sort(sort)
                            })
                        }
                    }
                },
            )
        },
        floatingActionButton = { FloatingActionButton(onClick = { picker.launch(arrayOf("*/*")) }) { Text("Up") } },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (accounts.size > 1) {
                Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    accounts.forEach { account ->
                        TextButton(onClick = { viewModel.selectAccount(account.id) }) { Text(account.displayName) }
                    }
                }
            }
            OutlinedTextField(
                query,
                { query = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                label = { Text("Search this server") },
                singleLine = true,
                trailingIcon = { TextButton(onClick = { viewModel.search(query) }) { Text("Go") } },
            )
            Row(Modifier.padding(horizontal = 8.dp)) {
                TextButton(onClick = { viewModel.open(parent(state.path)) }, enabled = state.path != "/") { Text("Up") }
                TextButton(onClick = { create = true }) { Text("New folder") }
                TextButton(onClick = viewModel::syncHere) { Text("Sync folder") }
                if (state.selected.isNotEmpty()) TextButton(onClick = viewModel::deleteSelected) { Text("Delete ${state.selected.size}") }
            }
            when {
                state.loading -> LinearProgressIndicator(Modifier.fillMaxWidth())
                state.error != null -> ErrorPane(state.error ?: "", onRetry = { viewModel.open(state.path) })
                accounts.isEmpty() -> EmptyPane(
                    "Connect an account first",
                    "The file browser talks to your Nextcloud server over WebDAV.",
                    null,
                    null,
                )
                state.entries.isEmpty() -> EmptyPane(
                    "This folder is empty",
                    "Upload a file or create a folder. Files that live only on the server stay marked that way until you synchronize the folder.",
                    null,
                    null,
                )
                else -> LazyColumn {
                    items(state.entries, key = { it.path }) { node ->
                        val name = SyncPaths.name(node.path)
                        ListItem(
                            headlineContent = { Text(name) },
                            supportingContent = {
                                Text(
                                    buildString {
                                        append(if (node.directory) "Folder" else node.contentType ?: "File")
                                        append(" · ")
                                        append(if (node.directory) "directory" else "${node.size} bytes")
                                        if (node.etag != null) append(" · etag ${node.etag}")
                                        append(" · server only until synced")
                                    },
                                )
                            },
                            leadingContent = {
                                Row {
                                    Checkbox(checked = node.path in state.selected, onCheckedChange = { viewModel.toggle(node.path) })
                                    Icon(
                                        if (node.directory) Icons.Outlined.Folder else Icons.Outlined.Description,
                                        contentDescription = null,
                                    )
                                }
                            },
                            modifier = Modifier.clickable {
                                if (node.directory) viewModel.open(node.path) else viewModel.details(node)
                            },
                        )
                    }
                }
            }
            state.message?.let { Text(it, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.secondary) }
        }
    }
    if (create) {
        NameDialog("New folder", "Create") { name ->
            create = false
            if (name != null) viewModel.createFolder(name)
        }
    }
    rename?.let { path ->
        NameDialog("Rename", "Rename") { name ->
            rename = null
            if (name != null) viewModel.rename(path, name)
        }
    }
    destination?.let { (path, copy) ->
        NameDialog(if (copy) "Copy into folder" else "Move into folder", "Continue") { folder ->
            destination = null
            if (folder != null) if (copy) viewModel.copy(path, folder) else viewModel.move(path, folder)
        }
    }
    state.details?.let { node ->
        val account = viewModel.accountId
        AlertDialog(
            onDismissRequest = { viewModel.details(null) },
            title = { Text(SyncPaths.name(node.path)) },
            text = {
                Text(
                    "Path ${node.path}\nType ${node.contentType ?: "unknown"}\nSize ${node.size} bytes\n" +
                        "Modified ${node.modifiedAtEpochMillis?.let { DateFormat.getDateTimeInstance().format(Date(it)) } ?: "unknown"}\n" +
                        "ETag ${node.etag ?: "none"}\nFile id ${node.fileId ?: "none"}\nStored on the server. Local availability appears after this folder is synchronized.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val id = account ?: return@TextButton
                    val path = node.path
                    val mime = node.contentType
                    val text = TextFiles.kindFor(SyncPaths.name(path), mime) != null
                    viewModel.details(null)
                    if (text) {
                        onEdit(id, path)
                    } else {
                        viewModel.prepareExternal(path) { file, type ->
                            shareCache(
                                context,
                                file,
                                type ?: mime ?: "application/octet-stream",
                            )
                        }
                    }
                }) { Text(if (TextFiles.kindFor(SyncPaths.name(node.path), node.contentType) != null) "Edit" else "Open") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        rename = node.path
                        viewModel.details(null)
                    }) { Text("Rename") }
                    TextButton(onClick = {
                        destination = node.path to false
                        viewModel.details(null)
                    }) { Text("Move") }
                    TextButton(onClick = {
                        destination = node.path to true
                        viewModel.details(null)
                    }) { Text("Copy") }
                    TextButton(onClick = {
                        val id = account ?: return@TextButton
                        onVersions("$id|${node.path}")
                        viewModel.details(null)
                    }) { Text("Versions") }
                }
            },
        )
    }
}

@Composable
private fun NameDialog(title: String, confirm: String, onResult: (String?) -> Unit) {
    var value by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = { onResult(null) },
        title = { Text(title) },
        text = { OutlinedTextField(value, { value = it }, label = { Text("Name") }) },
        confirmButton = { TextButton(onClick = { onResult(value.trim().ifEmpty { null }) }) { Text(confirm) } },
        dismissButton = { TextButton(onClick = { onResult(null) }) { Text("Cancel") } },
    )
}

private fun parent(path: String): String {
    val trimmed = path.trimEnd('/')
    val index = trimmed.lastIndexOf('/')
    return if (index <= 0) "/" else trimmed.substring(0, index)
}
