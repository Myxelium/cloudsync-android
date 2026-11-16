package dev.cloudsync.database

import dev.cloudsync.core.model.Account
import dev.cloudsync.core.model.AccountStatus
import dev.cloudsync.core.model.DeletionPolicy
import dev.cloudsync.core.model.ExcludeRules
import dev.cloudsync.core.model.FolderStatus
import dev.cloudsync.core.model.NetworkMode
import dev.cloudsync.core.model.ScheduleMode
import dev.cloudsync.core.model.SyncDirection
import dev.cloudsync.core.model.SyncFolder
import org.junit.Test
import java.sql.DriverManager
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DatabaseContractTest {
    @Test
    fun folderRoundTripKeepsIndependentRules() {
        val folder = SyncFolder(
            id = "f",
            accountId = "a",
            displayName = "Notes",
            remotePath = "/Notes",
            localRoot = "/local",
            direction = SyncDirection.UPLOAD_ONLY,
            exclude = ExcludeRules(includeSubfolders = false, patterns = listOf("*.tmp", "secret/**")),
            paused = true,
            networkMode = NetworkMode.WIFI_ONLY,
            allowUploadOnWifi = true,
            allowUploadOnMobile = false,
            allowDownloadOnWifi = false,
            allowDownloadOnMobile = false,
            requireCharging = true,
            minBatteryPercent = 40,
            schedule = ScheduleMode.HOURS_3,
            deletionPolicy = DeletionPolicy.SAFE_RECOVERY,
            lastSuccessAt = 10,
            lastError = null,
            status = FolderStatus.PAUSED,
        )
        assertEquals(folder, folder.toEntity().toModel())
    }

    @Test
    fun accountRoundTrip() {
        val account = Account("id", "https://cloud.example", "alice", "alice", "Alice", AccountStatus.ACTIVE, 1, null)
        assertEquals(account, account.toEntity().toModel())
    }

    @Test
    fun settingsMigrationCreatesTheTable() {
        DriverManager.getConnection("jdbc:sqlite::memory:").use { connection ->
            connection.createStatement().execute(CloudSyncDatabase.CREATE_APP_SETTINGS)
            val tables = connection.createStatement().executeQuery(
                "SELECT name FROM sqlite_master WHERE type = 'table' AND name = 'app_settings'",
            )
            assertTrue(tables.next())
            connection.createStatement().execute("INSERT INTO app_settings(key, value) VALUES ('notify.sync', 'true')")
            val value = connection.createStatement().executeQuery("SELECT value FROM app_settings WHERE key = 'notify.sync'")
            assertTrue(value.next())
            assertEquals("true", value.getString(1))
        }
    }
}
