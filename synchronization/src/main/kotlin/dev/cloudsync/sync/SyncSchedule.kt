package dev.cloudsync.sync

import dev.cloudsync.core.model.ScheduleMode

object SyncSchedule {
    const val EXPLANATION =
        "Android runs periodic synchronization inexactly and may delay it. Fifteen minutes is the shortest interval WorkManager accepts. " +
            "Immediate mode uses that same floor while the app is in the background. A change made with the app open is synchronized as soon as the network rules allow."

    fun intervalMinutes(mode: ScheduleMode): Long? = mode.minutes

    fun uniqueWorkName(folderId: String): String = "cloudsync-sync-$folderId"
}
