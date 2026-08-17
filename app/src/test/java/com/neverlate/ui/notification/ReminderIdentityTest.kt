package com.neverlate.ui.notification

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * US-5's "provable independence" acceptance criteria: [requestCodeFor] (alarm `PendingIntent`
 * identity) and [ReminderNotificationHelper.notificationIdFor] must never let a
 * [ReminderKind.LEAD_TIME] alarm/notification collide with its task's own [ReminderKind.TIME_UP]
 * one, and no reminder notification id may ever equal `TASKS_NOTIFICATION_ID` (1001, D2) — both
 * are plain functions with no Android dependency, so this stays a JVM unit test.
 */
class ReminderIdentityTest {

    /** A representative range of task ids: the low end (0, 1), an ordinary id, and large ids near
     *  the `Int` boundary R8 calls out (halved by the *2 multiplication in [requestCodeFor]). */
    private val representativeTaskIds = listOf(
        0L, 1L, 2L, 3L, 500L, 501L, 1001L, 1_000_000L,
        (Int.MAX_VALUE / 2).toLong() - 1,
        (Int.MAX_VALUE / 2).toLong(),
    )

    // requestCodeFor -----------------------------------------------------------------------------

    @Test
    fun `requestCodeFor differs between LEAD_TIME and TIME_UP for every representative task id`() {
        for (taskId in representativeTaskIds) {
            assertNotEquals(
                "task $taskId: LEAD_TIME and TIME_UP request codes must differ",
                requestCodeFor(taskId, ReminderKind.LEAD_TIME),
                requestCodeFor(taskId, ReminderKind.TIME_UP),
            )
        }
    }

    @Test
    fun `requestCodeFor never collides across any two (taskId, kind) pairs in a representative range`() {
        val allPairs = representativeTaskIds.flatMap { taskId ->
            ReminderKind.entries.map { kind -> taskId to kind }
        }

        val requestCodes = allPairs.map { (taskId, kind) -> requestCodeFor(taskId, kind) }

        assertEquals(
            "expected every (taskId, kind) pair to produce a distinct request code",
            allPairs.size,
            requestCodes.toSet().size,
        )
    }

    @Test
    fun `requestCodeFor is deterministic for the same taskId and kind`() {
        assertEquals(requestCodeFor(42L, ReminderKind.LEAD_TIME), requestCodeFor(42L, ReminderKind.LEAD_TIME))
        assertEquals(requestCodeFor(42L, ReminderKind.TIME_UP), requestCodeFor(42L, ReminderKind.TIME_UP))
    }

    @Test
    fun `requestCodeFor matches the documented taskId times two plus slot formula`() {
        assertEquals(0, requestCodeFor(0L, ReminderKind.LEAD_TIME))
        assertEquals(1, requestCodeFor(0L, ReminderKind.TIME_UP))
        assertEquals(2, requestCodeFor(1L, ReminderKind.LEAD_TIME))
        assertEquals(3, requestCodeFor(1L, ReminderKind.TIME_UP))
        assertEquals(1000, requestCodeFor(500L, ReminderKind.LEAD_TIME))
        assertEquals(1001, requestCodeFor(500L, ReminderKind.TIME_UP))
    }

    // notificationIdFor ----------------------------------------------------------------------------

    @Test
    fun `notificationIdFor never equals TASKS_NOTIFICATION_ID for any representative task id or kind`() {
        for (taskId in representativeTaskIds) {
            for (kind in ReminderKind.entries) {
                assertNotEquals(
                    "task $taskId, kind $kind: reminder notification id must never collide with the persistent notification",
                    TASKS_NOTIFICATION_ID,
                    ReminderNotificationHelper.notificationIdFor(taskId, kind),
                )
            }
        }
    }

    @Test
    fun `notificationIdFor differs between LEAD_TIME and TIME_UP for the same task`() {
        for (taskId in representativeTaskIds) {
            assertNotEquals(
                ReminderNotificationHelper.notificationIdFor(taskId, ReminderKind.LEAD_TIME),
                ReminderNotificationHelper.notificationIdFor(taskId, ReminderKind.TIME_UP),
            )
        }
    }

    @Test
    fun `notificationIdFor never collides across any two (taskId, kind) pairs in a representative range`() {
        val allPairs = representativeTaskIds.flatMap { taskId ->
            ReminderKind.entries.map { kind -> taskId to kind }
        }

        val notificationIds = allPairs.map { (taskId, kind) -> ReminderNotificationHelper.notificationIdFor(taskId, kind) }

        assertEquals(allPairs.size, notificationIds.toSet().size)
    }

    @Test
    fun `notificationIdFor is always at or above the 10000 base offset, well clear of TASKS_NOTIFICATION_ID`() {
        for (taskId in listOf(0L, 1L, 500L, 501L)) {
            for (kind in ReminderKind.entries) {
                assertTrue(ReminderNotificationHelper.notificationIdFor(taskId, kind) >= 10_000)
            }
        }
    }

    // ReminderKind actions ---------------------------------------------------------------------------

    @Test
    fun `ReminderKind actions are distinct strings, the belt-and-braces half of D1's fix`() {
        assertNotEquals(ReminderKind.LEAD_TIME.action, ReminderKind.TIME_UP.action)
    }
}
