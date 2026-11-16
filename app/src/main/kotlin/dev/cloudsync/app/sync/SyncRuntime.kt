package dev.cloudsync.app.sync

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.AndroidEntryPoint
import dev.cloudsync.app.MainActivity
import dev.cloudsync.app.data.SettingKeys
import dev.cloudsync.core.error.AuthenticationException
import dev.cloudsync.core.error.isRetryable
import dev.cloudsync.core.log.DiagnosticLog
import dev.cloudsync.core.log.LogRedactor
import dev.cloudsync.core.model.AccountStatus
import dev.cloudsync.core.model.FolderStatus
import dev.cloudsync.core.model.NetworkMode
import dev.cloudsync.core.model.NetworkSnapshot
import dev.cloudsync.core.model.SyncFolder
import dev.cloudsync.core.model.SyncReport
import dev.cloudsync.core.model.endpoint
import dev.cloudsync.core.time.Clock
import dev.cloudsync.core.time.IdGenerator
import dev.cloudsync.database.CloudSyncDatabase
import dev.cloudsync.database.DiagnosticEventEntity
import dev.cloudsync.database.toEntity
import dev.cloudsync.database.toModel
import dev.cloudsync.sync.SyncEngine
import dev.cloudsync.sync.SyncRequest
import dev.cloudsync.sync.SyncSchedule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

class RoomDiagnosticLog(
    private val database: CloudSyncDatabase,
    private val ids: IdGenerator,
    private val clock: Clock,
    private val scope: CoroutineScope,
) : DiagnosticLog {
    override fun info(category: String, message: String, accountId: String?, folderId: String?) = write("INFO", category, message, accountId, folderId)

    override fun error(category: String, message: String, accountId: String?, folderId: String?) = write("ERROR", category, message, accountId, folderId)

    private fun write(level: String, category: String, message: String, accountId: String?, folderId: String?) {
        val event = DiagnosticEventEntity(ids.newId(), clock.nowMillis(), level, category, LogRedactor.redact(message), accountId, folderId)
        scope.launch { database.diagnostics().insert(event) }
    }
}

@Singleton
class NetworkMonitor @Inject constructor(@dagger.hilt.android.qualifiers.ApplicationContext private val context: Context) {
    fun snapshot(): NetworkSnapshot {
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val network = connectivity.activeNetwork
        val capabilities = network?.let { connectivity.getNetworkCapabilities(it) }
        val wifi = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true ||
            capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true
        val cellular = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true
        val validated = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
        val battery = context.getSystemService(BatteryManager::class.java)
        val percent = battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY).takeIf { it in 0..100 }
        return NetworkSnapshot(
            validated = validated,
            wifi = wifi,
            cellular = cellular,
            metered = connectivity.isActiveNetworkMetered,
            charging = battery.isCharging,
            batteryPercent = percent,
        )
    }
}

@Singleton
class SyncNotifications @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: Context,
    private val settings: DataStore<Preferences>,
) {
    fun ensureChannels() {
        val manager = context.getSystemService(NotificationManager::class.java)
        listOf(
            CHANNEL_TRANSFER to "Transfers",
            CHANNEL_SYNC to "Synchronization",
            CHANNEL_CONFLICT to "Conflicts",
            CHANNEL_AUTH to "Account",
        ).forEach { (id, name) ->
            manager.createNotificationChannel(NotificationChannel(id, name, NotificationManager.IMPORTANCE_DEFAULT))
        }
    }

    fun foreground(title: String, text: String): ForegroundInfo {
        val notification = base(CHANNEL_TRANSFER, title, text, ongoing = true).build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(TRANSFER_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(TRANSFER_ID, notification)
        }
    }

    fun serviceNotification(title: String, text: String): Notification = base(CHANNEL_TRANSFER, title, text, ongoing = true).build()

    suspend fun finished(folder: SyncFolder, report: SyncReport) {
        if (!enabled(SettingKeys.notifySync)) return
        val text = when {
            report.errors.isNotEmpty() -> report.errors.first()
            report.waitingReason != null -> report.waitingReason
            else -> "Uploaded ${report.uploaded}, downloaded ${report.downloaded}."
        }
        notify(CHANNEL_SYNC, folder.id.hashCode(), folder.displayName, text ?: "Synchronization finished.")
    }

    suspend fun conflicts(count: Int) {
        if (count <= 0 || !enabled(SettingKeys.notifyConflicts)) return
        notify(CHANNEL_CONFLICT, 42, "Conflicts need a decision", "$count file${if (count == 1) "" else "s"} changed in overlapping ways.")
    }

    suspend fun authentication(server: String) {
        if (!enabled(SettingKeys.notifyAuth)) return
        notify(CHANNEL_AUTH, server.hashCode(), "Nextcloud needs you to sign in again", server)
    }

    private suspend fun enabled(key: androidx.datastore.preferences.core.Preferences.Key<Boolean>): Boolean = settings.data.map { it[key] ?: true }.first()

    private fun notify(channel: String, id: Int, title: String, text: String) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.notify(id, base(channel, title, text, ongoing = false).build())
    }

    private fun base(channel: String, title: String, text: String, ongoing: Boolean): NotificationCompat.Builder {
        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(context, channel)
            .setSmallIcon(dev.cloudsync.app.R.drawable.ic_stat_sync)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setOnlyAlertOnce(true)
            .setOngoing(ongoing)
            .setAutoCancel(!ongoing)
    }

    companion object {
        const val CHANNEL_TRANSFER = "transfers"
        const val CHANNEL_SYNC = "sync"
        const val CHANNEL_CONFLICT = "conflicts"
        const val CHANNEL_AUTH = "auth"
        const val TRANSFER_ID = 7
    }
}

@Singleton
class SyncScheduler @Inject constructor(@dagger.hilt.android.qualifiers.ApplicationContext private val context: Context) {
    fun schedule(folder: SyncFolder) {
        val minutes = SyncSchedule.intervalMinutes(folder.schedule)
        val name = SyncSchedule.uniqueWorkName(folder.id)
        val manager = WorkManager.getInstance(context)
        if (folder.paused || minutes == null) {
            manager.cancelUniqueWork(name)
            return
        }
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(if (folder.networkMode == NetworkMode.WIFI_ONLY) NetworkType.UNMETERED else NetworkType.CONNECTED)
            .setRequiresCharging(folder.requireCharging)
            .build()
        val request = PeriodicWorkRequestBuilder<SyncWorker>(minutes, java.util.concurrent.TimeUnit.MINUTES)
            .setInputData(workDataOf(SyncWorker.KEY_FOLDER to folder.id))
            .setConstraints(constraints)
            .build()
        manager.enqueueUniquePeriodicWork(name, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    fun syncNow(folderId: String?) {
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setInputData(workDataOf(SyncWorker.KEY_FOLDER to folderId))
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()
        WorkManager.getInstance(context).enqueue(request)
    }

    fun cancel(folderId: String) {
        WorkManager.getInstance(context).cancelUniqueWork(SyncSchedule.uniqueWorkName(folderId))
    }
}

@Singleton
class SyncCoordinator @Inject constructor(
    private val database: CloudSyncDatabase,
    private val engine: SyncEngine,
    private val network: NetworkMonitor,
    private val notifications: SyncNotifications,
    private val clock: Clock,
    private val ids: IdGenerator,
) {
    private val locks = ConcurrentHashMap<String, Mutex>()
    private val cancelled = ConcurrentHashMap.newKeySet<String>()

    fun cancel(folderId: String) {
        cancelled += folderId
    }

    suspend fun synchronize(folderId: String): SyncReport = locks.getOrPut(folderId) { Mutex() }.withLock {
        cancelled -= folderId
        val folder =
            database.folders().get(folderId)?.toModel()
                ?: return SyncReport(folderId, FolderStatus.ERROR, 0, 0, 0, null, listOf("That folder is no longer synchronized."))
        val account = database.accounts().get(folder.accountId)?.toModel()
            ?: return SyncReport(folder.id, FolderStatus.ERROR, 0, 0, 0, null, listOf("The Nextcloud account is disconnected."))
        if (account.status == AccountStatus.AUTH_EXPIRED) {
            notifications.authentication(account.serverUrl)
            return SyncReport(folder.id, FolderStatus.ERROR, 0, 0, 0, null, listOf("Sign in to ${account.serverUrl} again."))
        }
        database.folders().upsert(folder.copy(status = FolderStatus.SYNCING).toEntity())
        database.jobs().upsert(
            dev.cloudsync.database.SyncJobEntity(ids.newId(), folder.id, "RUNNING", clock.nowMillis(), clock.nowMillis(), null),
        )
        val report = try {
            engine.synchronize(
                SyncRequest(
                    folder = folder,
                    endpoint = account.endpoint(),
                    network = network.snapshot(),
                    isCancelled = { folder.id in cancelled },
                ),
            )
        } catch (error: AuthenticationException) {
            database.accounts().upsert(account.copy(status = AccountStatus.AUTH_EXPIRED).toEntity())
            notifications.authentication(account.serverUrl)
            SyncReport(folder.id, FolderStatus.ERROR, 0, 0, 0, null, listOf(error.message ?: "Sign in again."))
        }
        val success = report.errors.isEmpty() && report.status != FolderStatus.ERROR
        database.folders().upsert(
            folder.copy(
                status = report.status,
                lastError = report.errors.firstOrNull() ?: report.waitingReason,
                lastSuccessAt = if (success && report.waitingReason == null) clock.nowMillis() else folder.lastSuccessAt,
            ).toEntity(),
        )
        database.jobs().upsert(
            dev.cloudsync.database.SyncJobEntity(
                ids.newId(),
                folder.id,
                report.status.name,
                clock.nowMillis(),
                clock.nowMillis(),
                report.errors.firstOrNull(),
            ),
        )
        notifications.finished(folder, report)
        if (report.conflicts > 0) notifications.conflicts(report.conflicts)
        report
    }

    suspend fun synchronizeAll() {
        database.folders().all().map { it.toModel() }.filter { !it.paused }.forEach { synchronize(it.id) }
    }

    suspend fun resolve(folderId: String, conflictId: String, bytes: ByteArray, description: String): SyncReport {
        val folder = database.folders().get(folderId)?.toModel() ?: error("Folder missing")
        val account = database.accounts().get(folder.accountId)?.toModel() ?: error("Account missing")
        val conflict = database.conflicts().get(conflictId)?.toModel() ?: error("Conflict missing")
        return engine.resolveBytes(
            SyncRequest(folder, account.endpoint(), network.snapshot()),
            conflict,
            bytes,
            description,
        )
    }

    suspend fun restore(folderId: String, relativePath: String, bytes: ByteArray): SyncReport {
        val folder = database.folders().get(folderId)?.toModel() ?: error("Folder missing")
        val account = database.accounts().get(folder.accountId)?.toModel() ?: error("Account missing")
        return engine.restore(SyncRequest(folder, account.endpoint(), network.snapshot()), relativePath, bytes)
    }
}

@HiltWorker
class SyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted parameters: WorkerParameters,
    private val coordinator: SyncCoordinator,
    private val notifications: SyncNotifications,
) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val folderId = inputData.getString(KEY_FOLDER)
        setForeground(notifications.foreground("CloudSync", "Synchronizing"))
        return try {
            withContext(Dispatchers.IO) {
                if (folderId.isNullOrBlank()) coordinator.synchronizeAll() else coordinator.synchronize(folderId)
            }
            Result.success()
        } catch (error: Exception) {
            when {
                error is AuthenticationException -> Result.failure()
                isRetryable(error) && runAttemptCount < 5 -> Result.retry()
                else -> Result.failure()
            }
        }
    }

    companion object {
        const val KEY_FOLDER = "folderId"
    }
}

@AndroidEntryPoint
class CloudSyncTransferService : Service() {
    @Inject lateinit var coordinator: SyncCoordinator

    @Inject lateinit var notifications: SyncNotifications

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onBind(intent: Intent?) = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val folderId = intent?.getStringExtra(EXTRA_FOLDER)
        val notification = notifications.serviceNotification("CloudSync", "Synchronizing")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(SyncNotifications.TRANSFER_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(SyncNotifications.TRANSFER_ID, notification)
        }
        scope.launch {
            try {
                if (folderId.isNullOrBlank()) coordinator.synchronizeAll() else coordinator.synchronize(folderId)
            } finally {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf(startId)
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_FOLDER = "folderId"

        fun start(context: Context, folderId: String?) {
            val intent = Intent(context, CloudSyncTransferService::class.java).putExtra(EXTRA_FOLDER, folderId)
            ContextCompat.startForegroundService(context, intent)
        }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val pending = goAsync()
        val entry = dagger.hilt.android.EntryPointAccessors.fromApplication(context.applicationContext, BootEntry::class.java)
        CoroutineScope(Dispatchers.IO).launch {
            try {
                entry.database().transfers().requeueRunning()
                entry.database().folders().all().map { it.toModel() }.forEach { entry.scheduler().schedule(it) }
            } finally {
                pending.finish()
            }
        }
    }
}

@dagger.hilt.EntryPoint
@dagger.hilt.InstallIn(dagger.hilt.components.SingletonComponent::class)
interface BootEntry {
    fun database(): CloudSyncDatabase

    fun scheduler(): SyncScheduler
}
