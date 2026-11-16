package dev.cloudsync.sync

import dev.cloudsync.core.model.ScheduleMode
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SyncScheduleTest {
    @Test
    fun immediateUsesTheWorkManagerFloorAndManualIsNotScheduled() {
        assertEquals(15, SyncSchedule.intervalMinutes(ScheduleMode.IMMEDIATE))
        assertEquals(1440, SyncSchedule.intervalMinutes(ScheduleMode.DAILY))
        assertNull(SyncSchedule.intervalMinutes(ScheduleMode.MANUAL))
    }
}
