package dev.cloudsync.app

import android.app.NotificationManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.cloudsync.app.data.settingsDataStore
import dev.cloudsync.app.sync.SyncNotifications
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NotificationChannelTest {
    @Test
    fun channelsExistAfterSetup() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferences = context.settingsDataStore
        val notifications = SyncNotifications(context, preferences)
        notifications.ensureChannels()
        val manager = context.getSystemService(NotificationManager::class.java)
        assertNotNull(manager.getNotificationChannel(SyncNotifications.CHANNEL_TRANSFER))
        assertNotNull(manager.getNotificationChannel(SyncNotifications.CHANNEL_SYNC))
        assertNotNull(manager.getNotificationChannel(SyncNotifications.CHANNEL_CONFLICT))
        assertNotNull(manager.getNotificationChannel(SyncNotifications.CHANNEL_AUTH))
    }
}
