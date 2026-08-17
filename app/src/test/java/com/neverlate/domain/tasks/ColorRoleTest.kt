package com.neverlate.domain.tasks

import com.neverlate.data.tasks.Priority
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pure JVM tests for [urgencyColorRole] and [priorityColorRole] — the shared mapping extracted by
 * `docs/specs/2026-08-17-widget-hilt-color-token.md` (D3, US-3) from the two hand-synced pairs of
 * resolvers (`ui/tasks/TasksScreen.kt`/`ui/tasks/PriorityUi.kt` for Compose,
 * `ui/widget/WidgetColors.kt` for Glance). Covers all four [UrgencyLevel] values and all four
 * [Priority] values against their expected [ColorRole] (AC-6).
 */
class ColorRoleTest {

    // urgencyColorRole ------------------------------------------------------------------------------

    @Test
    fun `Calm maps to ColorRole Calm`() {
        assertEquals(ColorRole.Calm, urgencyColorRole(UrgencyLevel.Calm))
    }

    @Test
    fun `Soon maps to ColorRole Soon`() {
        assertEquals(ColorRole.Soon, urgencyColorRole(UrgencyLevel.Soon))
    }

    @Test
    fun `Urgent maps to ColorRole Error`() {
        assertEquals(ColorRole.Error, urgencyColorRole(UrgencyLevel.Urgent))
    }

    @Test
    fun `Overdue maps to ColorRole Error, same as Urgent`() {
        assertEquals(ColorRole.Error, urgencyColorRole(UrgencyLevel.Overdue))
    }

    // priorityColorRole -------------------------------------------------------------------------------

    @Test
    fun `NONE maps to no role`() {
        assertNull(priorityColorRole(Priority.NONE))
    }

    @Test
    fun `LOW maps to ColorRole Secondary`() {
        assertEquals(ColorRole.Secondary, priorityColorRole(Priority.LOW))
    }

    @Test
    fun `MEDIUM maps to ColorRole Tertiary`() {
        assertEquals(ColorRole.Tertiary, priorityColorRole(Priority.MEDIUM))
    }

    @Test
    fun `HIGH maps to ColorRole Primary`() {
        assertEquals(ColorRole.Primary, priorityColorRole(Priority.HIGH))
    }
}
