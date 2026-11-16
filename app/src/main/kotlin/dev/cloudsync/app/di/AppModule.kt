package dev.cloudsync.app.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.cloudsync.app.data.settingsDataStore
import dev.cloudsync.core.log.DiagnosticLog
import dev.cloudsync.core.remote.LocalFiles
import dev.cloudsync.core.remote.RemoteFiles
import dev.cloudsync.core.sync.SyncStateStore
import dev.cloudsync.core.time.Clock
import dev.cloudsync.core.time.IdGenerator
import dev.cloudsync.core.time.SystemClock
import dev.cloudsync.core.time.UuidGenerator
import dev.cloudsync.database.CloudSyncDatabase
import dev.cloudsync.database.RoomSyncStateStore
import dev.cloudsync.database.RoomVersionStore
import dev.cloudsync.merge.MergeCoordinator
import dev.cloudsync.network.HttpClients
import dev.cloudsync.network.WebDavClient
import dev.cloudsync.nextcloud.LoginFlowClient
import dev.cloudsync.nextcloud.OcsClient
import dev.cloudsync.security.CredentialStore
import dev.cloudsync.security.KeystoreCredentialStore
import dev.cloudsync.storage.DelegatingLocalFiles
import dev.cloudsync.sync.SyncEngine
import dev.cloudsync.versioning.VersionStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides @Singleton
    fun http(): OkHttpClient = HttpClients.create()

    @Provides @Singleton
    fun database(@ApplicationContext context: Context): CloudSyncDatabase = Room.databaseBuilder(
        context,
        CloudSyncDatabase::class.java,
        "cloudsync.db",
    ).addMigrations(CloudSyncDatabase.MIGRATION_1_2).build()

    @Provides @Singleton
    fun credentials(@ApplicationContext context: Context): CredentialStore = KeystoreCredentialStore(context)

    @Provides @Singleton
    fun remote(http: OkHttpClient, credentials: CredentialStore): RemoteFiles = WebDavClient(http, credentials)

    @Provides @Singleton
    fun localFiles(@ApplicationContext context: Context): DelegatingLocalFiles = DelegatingLocalFiles(context)

    @Provides
    fun local(files: DelegatingLocalFiles): LocalFiles = files

    @Provides @Singleton
    fun state(database: CloudSyncDatabase): SyncStateStore = RoomSyncStateStore(database)

    @Provides @Singleton
    fun versions(database: CloudSyncDatabase): VersionStore = RoomVersionStore(database)

    @Provides @Singleton
    fun ids(): IdGenerator = UuidGenerator

    @Provides @Singleton
    fun clock(): Clock = SystemClock

    @Provides @Singleton
    fun scope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Provides @Singleton
    fun log(database: CloudSyncDatabase, ids: IdGenerator, clock: Clock, scope: CoroutineScope): DiagnosticLog = dev.cloudsync.app.sync.RoomDiagnosticLog(database, ids, clock, scope)

    @Provides @Singleton
    fun engine(
        remote: RemoteFiles,
        local: LocalFiles,
        versions: VersionStore,
        state: SyncStateStore,
        ids: IdGenerator,
        clock: Clock,
        log: DiagnosticLog,
    ): SyncEngine = SyncEngine(remote, local, versions, state, MergeCoordinator(), ids, clock, log)

    @Provides @Singleton
    fun login(http: OkHttpClient): LoginFlowClient = LoginFlowClient(http)

    @Provides @Singleton
    fun ocs(http: OkHttpClient): OcsClient = OcsClient(http)

    @Provides @Singleton
    fun settings(@ApplicationContext context: Context): DataStore<Preferences> = context.settingsDataStore
}
