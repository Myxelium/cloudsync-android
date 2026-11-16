package dev.cloudsync.app

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.net.toUri
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import dagger.hilt.android.AndroidEntryPoint
import dev.cloudsync.app.ui.ConflictListScreen
import dev.cloudsync.app.ui.ConflictResolverScreen
import dev.cloudsync.app.ui.EditorScreen
import dev.cloudsync.app.ui.FilesScreen
import dev.cloudsync.app.ui.HomeScreen
import dev.cloudsync.app.ui.LoginScreen
import dev.cloudsync.app.ui.SettingsScreen
import dev.cloudsync.app.ui.SyncScreen
import dev.cloudsync.app.ui.TransfersScreen
import dev.cloudsync.app.ui.VersionsScreen
import dev.cloudsync.ui.CloudSyncTheme

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val notifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= 33) notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        setContent {
            CloudSyncTheme {
                CloudSyncApp(onOpenUrl = { url ->
                    CustomTabsIntent.Builder().build().launchUrl(this, url.toUri())
                })
            }
        }
    }
}

private data class Destination(val route: String, val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector)

private val destinations = listOf(
    Destination("home", "Home", Icons.Outlined.Home),
    Destination("files", "Files", Icons.Outlined.Folder),
    Destination("sync", "Sync", Icons.Outlined.Cloud),
    Destination("conflicts", "Conflicts", Icons.Outlined.Warning),
    Destination("settings", "Settings", Icons.Outlined.Settings),
)

@Composable
private fun CloudSyncApp(onOpenUrl: (String) -> Unit) {
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route.orEmpty()
    val showBar = destinations.any { route.startsWith(it.route) }
    Scaffold(
        bottomBar = {
            if (showBar) {
                NavigationBar {
                    destinations.forEach { destination ->
                        NavigationBarItem(
                            selected = route.startsWith(destination.route),
                            onClick = { nav.navigate(destination.route) { launchSingleTop = true } },
                            icon = { Icon(destination.icon, contentDescription = destination.label) },
                            label = { Text(destination.label) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(nav, startDestination = "home", modifier = Modifier.padding(padding)) {
            composable("home") {
                HomeScreen(
                    onAddAccount = { nav.navigate("login") },
                    onTransfers = { nav.navigate("transfers") },
                    onConflicts = { nav.navigate("conflicts") },
                )
            }
            composable("files") {
                FilesScreen(
                    onEdit = { account, path -> nav.navigate("editor/$account/${android.net.Uri.encode(path)}") },
                    onVersions = { fileKey -> nav.navigate("versions/${android.net.Uri.encode(fileKey)}") },
                )
            }
            composable("sync") { SyncScreen() }
            composable("conflicts") { ConflictListScreen(onOpen = { nav.navigate("conflict/$it") }) }
            composable("settings") { SettingsScreen(onAddAccount = { nav.navigate("login") }) }
            composable("login") { LoginScreen(onOpenUrl = onOpenUrl, onDone = { nav.popBackStack() }) }
            composable("transfers") { TransfersScreen(onBack = { nav.popBackStack() }) }
            composable(
                "editor/{accountId}/{path}",
                arguments = listOf(navArgument("path") { type = NavType.StringType }),
            ) { EditorScreen(onBack = { nav.popBackStack() }) }
            composable(
                "versions/{fileKey}",
                arguments = listOf(navArgument("fileKey") { type = NavType.StringType }),
            ) { VersionsScreen(onBack = { nav.popBackStack() }) }
            composable("conflict/{id}") { ConflictResolverScreen(onBack = { nav.popBackStack() }) }
        }
    }
}

fun shareCache(context: android.content.Context, file: java.io.File, mime: String) {
    val uri = androidx.core.content.FileProvider.getUriForFile(context, context.packageName + ".files", file)
    val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(view, "Open with"))
}
