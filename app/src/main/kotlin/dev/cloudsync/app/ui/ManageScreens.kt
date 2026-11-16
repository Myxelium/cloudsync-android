@file:OptIn(ExperimentalMaterial3Api::class)

package dev.cloudsync.app.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.cloudsync.app.data.SettingKeys
import dev.cloudsync.core.model.DeletionPolicy
import dev.cloudsync.core.model.NetworkMode
import dev.cloudsync.core.model.ScheduleMode
import dev.cloudsync.core.model.SyncDirection
import dev.cloudsync.core.model.SyncFolder
import dev.cloudsync.core.model.TransferStatus
import dev.cloudsync.core.path.SyncPaths
import dev.cloudsync.merge.MergeSegment
import dev.cloudsync.ui.EmptyPane
import dev.cloudsync.ui.SectionCard
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncScreen(viewModel: SyncViewModel = hiltViewModel()) {
    val folders by viewModel.foldersState.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<SyncFolder?>(null) }
    val tree = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        val folder = editing
        if (uri != null && folder != null) viewModel.useTree(folder, uri.toString())
    }
    Scaffold(topBar = { TopAppBar(title = { Text("Synchronization") }) }) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { Text(viewModel.explanation, style = MaterialTheme.typography.bodyMedium) }
            if (folders.isEmpty()) {
                item {
                    EmptyPane(
                        "No folders selected",
                        "Open Files, go to a folder, and choose Sync folder. Removing a folder later leaves its files on the device and on the server.",
                        null,
                        null,
                    )
                }
            }
            items(folders, key = { it.id }) { folder ->
                SectionCard(
                    folder.displayName,
                    "${folder.direction.name.replace('_', ' ').lowercase()} · ${folder.networkMode.name.replace('_', ' ').lowercase()}\n" +
                        "Status ${folder.status.name.lowercase()} · ${folder.lastError ?: "No recent error"}\n" +
                        "Schedule ${folder.schedule.name.lowercase()} · charging only ${folder.requireCharging}",
                )
                Row {
                    TextButton(onClick = { viewModel.sync(folder.id) }) { Text("Sync now") }
                    TextButton(onClick = { editing = folder }) { Text("Settings") }
                    TextButton(onClick = {
                        viewModel.save(folder.copy(paused = !folder.paused))
                    }) { Text(if (folder.paused) "Resume" else "Pause") }
                    TextButton(onClick = { viewModel.remove(folder.id) }) { Text("Remove") }
                }
            }
        }
    }
    editing?.let { folder ->
        FolderEditor(
            folder = folder,
            onDismiss = { editing = null },
            onSave = {
                viewModel.save(it)
                editing = null
            },
            onPickTree = {
                editing = folder
                tree.launch(null)
            },
        )
    }
}

@Composable
private fun FolderEditor(folder: SyncFolder, onDismiss: () -> Unit, onSave: (SyncFolder) -> Unit, onPickTree: () -> Unit) {
    var draft by remember(folder.id) { mutableStateOf(folder) }
    var patterns by remember(folder.id) { mutableStateOf(folder.exclude.patterns.joinToString("\n")) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(folder.displayName) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Direction")
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SyncDirection.entries.forEach { direction ->
                        FilterChip(selected = draft.direction == direction, onClick = {
                            draft = draft.copy(direction = direction)
                        }, label = { Text(direction.name.lowercase()) })
                    }
                }
                Text("Network")
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    NetworkMode.entries.forEach { mode ->
                        FilterChip(selected = draft.networkMode == mode, onClick = {
                            draft = draft.copy(networkMode = mode)
                        }, label = { Text(mode.name.lowercase()) })
                    }
                }
                LabeledSwitch("Upload on Wi-Fi", draft.allowUploadOnWifi) { draft = draft.copy(allowUploadOnWifi = it) }
                LabeledSwitch("Upload on mobile", draft.allowUploadOnMobile) { draft = draft.copy(allowUploadOnMobile = it) }
                LabeledSwitch("Download on Wi-Fi", draft.allowDownloadOnWifi) { draft = draft.copy(allowDownloadOnWifi = it) }
                LabeledSwitch("Download on mobile", draft.allowDownloadOnMobile) { draft = draft.copy(allowDownloadOnMobile = it) }
                LabeledSwitch("Only while charging", draft.requireCharging) { draft = draft.copy(requireCharging = it) }
                Text("Minimum battery ${draft.minBatteryPercent}%")
                Slider(draft.minBatteryPercent / 100f, { draft = draft.copy(minBatteryPercent = (it * 100).toInt()) })
                Text("Schedule")
                ScheduleMode.entries.forEach { mode ->
                    FilterChip(selected = draft.schedule == mode, onClick = {
                        draft = draft.copy(schedule = mode)
                    }, label = { Text(mode.name.lowercase()) })
                }
                Text("Deletion")
                DeletionPolicy.entries.forEach { policy ->
                    FilterChip(selected = draft.deletionPolicy == policy, onClick = {
                        draft = draft.copy(deletionPolicy = policy)
                    }, label = { Text(policy.name.lowercase()) })
                }
                LabeledSwitch("Include subfolders", draft.exclude.includeSubfolders) {
                    draft =
                        draft.copy(exclude = draft.exclude.copy(includeSubfolders = it))
                }
                OutlinedTextField(patterns, { patterns = it }, label = { Text("Exclude patterns, one per line") })
                TextButton(onClick = onPickTree) { Text("Choose a device folder") }
                Text(draft.localRoot, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(draft.copy(exclude = draft.exclude.copy(patterns = patterns.lines().map { it.trim() }.filter { it.isNotEmpty() })))
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun LabeledSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label)
        Switch(checked, onChange)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConflictListScreen(onOpen: (String) -> Unit, viewModel: ConflictViewModel = hiltViewModel()) {
    val conflicts by viewModel.open.collectAsStateWithLifecycle()
    Scaffold(topBar = { TopAppBar(title = { Text("Conflicts") }) }) { padding ->
        if (conflicts.isEmpty()) {
            EmptyPane(
                "No open conflicts",
                "When the same part of a text file changes on both sides, it shows up here. Both copies stay until you decide.",
                modifier = Modifier.padding(padding),
            )
        } else {
            LazyColumn(Modifier.padding(padding)) {
                items(conflicts, key = { it.id }) { conflict ->
                    ListItem(
                        headlineContent = { Text(SyncPaths.name(conflict.remotePath)) },
                        supportingContent = { Text("${conflict.kind.name.lowercase()} · ${conflict.summary}") },
                        modifier = Modifier.fillMaxWidth().padding(0.dp),
                        trailingContent = { TextButton(onClick = { onOpen(conflict.id) }) { Text("Resolve") } },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConflictResolverScreen(onBack: () -> Unit, viewModel: ConflictViewModel = hiltViewModel()) {
    val result by viewModel.result.collectAsStateWithLifecycle()
    val preview by viewModel.preview.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val id = viewModel.conflictId
    LaunchedEffect(id) { if (id != null) viewModel.load(id) }
    var choices by remember { mutableStateOf(listOf<String>()) }
    Scaffold(topBar = {
        TopAppBar(title = { Text("Resolve") }, navigationIcon = { TextButton(onClick = onBack) { Text("Back") } })
    }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val merged = result
            if (merged == null) {
                Text("Loading the three copies…")
            } else if (merged.binary) {
                Text("Automatic merging is not available for this file. Both versions stay in history. Choose which one becomes current.")
                Button(onClick = { viewModel.keep(true) }) { Text("Use the copy on this device") }
                Button(onClick = { viewModel.keep(false) }) { Text("Use the server copy") }
            } else {
                Text(
                    merged.validationError
                        ?: "Independent edits are already combined. Choose a side only where both copies changed the same lines.",
                )
                var conflictOrdinal = 0
                merged.segments.forEach { segment ->
                    when (segment) {
                        is MergeSegment.Conflict -> {
                            val ordinal = conflictOrdinal
                            conflictOrdinal += 1
                            Text("Conflict ${ordinal + 1}", style = MaterialTheme.typography.titleSmall)
                            Text("Base:\n${segment.base.joinToString("\n")}")
                            Text("Device:\n${segment.local.joinToString("\n")}")
                            Text("Server:\n${segment.remote.joinToString("\n")}")
                            Row {
                                TextButton(onClick = {
                                    val updated = padChoices(choices, merged.conflictCount, ordinal, segment.local.joinToString("\n"))
                                    choices = updated
                                    if (updated.count { it.isNotEmpty() } == merged.conflictCount) viewModel.choose(updated)
                                }) { Text("Use device") }
                                TextButton(onClick = {
                                    val updated = padChoices(choices, merged.conflictCount, ordinal, segment.remote.joinToString("\n"))
                                    choices = updated
                                    if (updated.count { it.isNotEmpty() } == merged.conflictCount) viewModel.choose(updated)
                                }) { Text("Use server") }
                            }
                        }
                        else -> if (segment.lines.isNotEmpty()) {
                            Text(
                                segment.lines.joinToString("\n"),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
                OutlinedTextField(preview, viewModel::edit, modifier = Modifier.fillMaxWidth(), label = { Text("Result") }, minLines = 6)
                Button(onClick = viewModel::saveMerged) { Text("Save merged result") }
                Text("Cancel leaves every original version in place.")
            }
            message?.let { Text(it) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VersionsScreen(onBack: () -> Unit, viewModel: VersionViewModel = hiltViewModel()) {
    val history by viewModel.history.collectAsStateWithLifecycle()
    val diff by viewModel.diff.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    var first by remember { mutableStateOf<String?>(null) }
    Scaffold(topBar = {
        TopAppBar(title = { Text("Versions") }, navigationIcon = { TextButton(onClick = onBack) { Text("Back") } })
    }) { padding ->
        LazyColumn(Modifier.padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (history.isEmpty()) {
                item {
                    EmptyPane(
                        "No local history yet",
                        "Versions are recorded when a file is synchronized, edited, merged, or restored.",
                        null,
                        null,
                    )
                }
            }
            items(history, key = { it.id }) { version ->
                SectionCard(
                    "${version.origin.name.lowercase()} · ${DateFormat.getDateTimeInstance().format(Date(version.createdAt))}",
                    "${version.size} bytes · ${version.sha256.take(12)} · ${version.description ?: "No note"}" +
                        if (version.hasSnapshot) "" else "\nSnapshot omitted because the file is larger than the retention limit.",
                )
                Row {
                    TextButton(onClick = { viewModel.restore(version) }, enabled = version.hasSnapshot) { Text("Restore") }
                    TextButton(onClick = {
                        val selected = first
                        if (selected == null) {
                            first = version.id
                        } else {
                            val left = history.firstOrNull { it.id == selected }
                            if (left != null) viewModel.compare(left, version)
                            first = null
                        }
                    }) { Text(if (first == null) "Compare" else "Compare with selected") }
                }
            }
            diff?.let { item { Text(it) } }
            message?.let { item { Text(it) } }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransfersScreen(onBack: () -> Unit, viewModel: TransferViewModel = hiltViewModel()) {
    val transfers by viewModel.transfers.collectAsStateWithLifecycle()
    Scaffold(topBar = {
        TopAppBar(title = { Text("Transfers") }, navigationIcon = { TextButton(onClick = onBack) { Text("Back") } })
    }) { padding ->
        if (transfers.isEmpty()) {
            EmptyPane(
                "No transfers yet",
                "Uploads and downloads appear here with their size and status. Failed transfers stay listed so you can sync the folder again.",
                modifier = Modifier.padding(padding),
            )
        } else {
            LazyColumn(Modifier.padding(padding)) {
                items(transfers, key = { it.id }) { transfer ->
                    val progress = if (transfer.totalBytes <= 0) 0f else transfer.bytesTransferred.toFloat() / transfer.totalBytes
                    ListItem(
                        headlineContent = { Text(SyncPaths.name(transfer.remotePath)) },
                        supportingContent = {
                            Column {
                                Text(
                                    "${transfer.direction.name.lowercase()} · ${transfer.status.name.lowercase()} · ${transfer.bytesTransferred}/${transfer.totalBytes} bytes",
                                )
                                if (transfer.status ==
                                    TransferStatus.RUNNING
                                ) {
                                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                                }
                                transfer.error?.let { Text(it) }
                            }
                        },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onAddAccount: () -> Unit, viewModel: SettingsViewModel = hiltViewModel()) {
    val prefs by viewModel.preferences.collectAsStateWithLifecycle()
    val accounts by viewModel.accountsState.collectAsStateWithLifecycle()
    val logs by viewModel.logs.collectAsStateWithLifecycle()
    Scaffold(topBar = { TopAppBar(title = { Text("Settings") }) }) { padding ->
        LazyColumn(Modifier.padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item { Text("Notifications", style = MaterialTheme.typography.titleMedium) }
            item {
                LabeledSwitch("Synchronization finished or failed", prefs?.get(SettingKeys.notifySync) ?: true) {
                    viewModel.set(SettingKeys.notifySync, it)
                }
            }
            item {
                LabeledSwitch("Conflicts", prefs?.get(SettingKeys.notifyConflicts) ?: true) {
                    viewModel.set(SettingKeys.notifyConflicts, it)
                }
            }
            item {
                LabeledSwitch("Sign-in problems", prefs?.get(SettingKeys.notifyAuth) ?: true) { viewModel.set(SettingKeys.notifyAuth, it) }
            }
            item { Text("Accounts", style = MaterialTheme.typography.titleMedium) }
            items(accounts, key = { it.id }) { account ->
                SectionCard(account.displayName, account.serverUrl)
                TextButton(onClick = { viewModel.disconnect(account.id) }) { Text("Disconnect") }
            }
            item { Button(onClick = onAddAccount) { Text("Add account") } }
            item { Text("Recent log", style = MaterialTheme.typography.titleMedium) }
            item { Text("Passwords, tokens, and file contents are not written here.") }
            items(logs.take(40), key = { it.id }) { event ->
                Text("${event.level} ${event.category}: ${event.message}", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginScreen(onOpenUrl: (String) -> Unit, onDone: () -> Unit, viewModel: LoginViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current
    var server by remember { mutableStateOf("https://") }
    var username by remember { mutableStateOf("") }
    var secret by remember { mutableStateOf("") }
    DisposableEffect(lifecycleOwner, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.onResume()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(state.url) {
        val url = state.url ?: return@LaunchedEffect
        onOpenUrl(url)
        viewModel.consumeUrl()
    }
    LaunchedEffect(state.done) { if (state.done) onDone() }
    Scaffold(topBar = { TopAppBar(title = { Text("Add Nextcloud") }) }) { padding ->
        Column(
            Modifier.padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "HTTPS only. Certificate checks stay on. The account password you might type is exchanged for an app password when the server allows it, and only that app password is stored.",
            )
            OutlinedTextField(server, { server = it }, label = { Text("Server") }, modifier = Modifier.fillMaxWidth())
            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            Button(onClick = { viewModel.start(server) }, enabled = !state.busy) { Text("Sign in with browser") }
            Text("Or use an existing app password")
            OutlinedTextField(username, { username = it }, label = { Text("Username") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(secret, { secret = it }, label = { Text("App password") }, modifier = Modifier.fillMaxWidth())
            Button(onClick = { viewModel.appPassword(server, username, secret) }, enabled = !state.busy) { Text("Connect") }
            state.message?.let { Text(it) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(onBack: () -> Unit, viewModel: EditorViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(SyncPaths.name(viewModel.path)) },
                navigationIcon = { TextButton(onClick = onBack) { Text("Back") } },
                actions = { TextButton(onClick = viewModel::save, enabled = state.editable && !state.loading) { Text("Save") } },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (state.loading) {
                Text("Opening the file…")
            } else if (!state.editable) {
                Text(state.message ?: "This format opens in another app.")
            } else {
                OutlinedTextField(state.text, viewModel::edit, modifier = Modifier.fillMaxWidth().weight(1f, fill = false), minLines = 12)
                state.message?.let { Text(it) }
            }
        }
    }
}

private fun padChoices(current: List<String>, count: Int, index: Int, value: String): List<String> {
    val next = current.toMutableList()
    while (next.size < count) next.add("")
    next[index] = value
    return next
}
