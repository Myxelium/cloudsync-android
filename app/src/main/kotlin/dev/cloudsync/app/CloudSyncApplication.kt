package dev.cloudsync.app

import android.app.Application
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import dev.cloudsync.app.sync.SyncNotifications
import dev.cloudsync.app.sync.SyncScheduler
import dev.cloudsync.database.CloudSyncDatabase
import dev.cloudsync.database.toModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class CloudSyncApplication :
    Application(),
    Configuration.Provider {
    @Inject lateinit var workerFactory: androidx.hilt.work.HiltWorkerFactory

    @Inject lateinit var notifications: SyncNotifications

    @Inject lateinit var database: CloudSyncDatabase

    @Inject lateinit var scheduler: SyncScheduler

    @Inject lateinit var scope: CoroutineScope

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    override fun onCreate() {
        super.onCreate()
        notifications.ensureChannels()
        scope.launch {
            database.transfers().requeueRunning()
            database.folders().all().map { it.toModel() }.forEach { scheduler.schedule(it) }
        }
    }
}
