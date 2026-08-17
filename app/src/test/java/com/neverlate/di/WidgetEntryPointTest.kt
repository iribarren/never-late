package com.neverlate.di

import com.neverlate.data.sync.FakeUserPreferencesRepository
import com.neverlate.ui.notification.FakeReminderScheduler
import com.neverlate.ui.notification.FakeTaskRepository
import com.neverlate.ui.notification.ReminderSchedulingRepository
import com.neverlate.ui.widget.TaskSurfacesRefreshingRepository
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AC-5 (spec `widget-adaptive-layout`, D4): fixes in writing that the concrete `TaskRepository`
 * Hilt hands out for [WidgetEntryPoint.taskRepository]'s [ReminderRepo] qualifier — the exact
 * `@Provides` function `RepositoryModule.provideReminderSchedulingRepository` — is
 * [ReminderSchedulingRepository], never [TaskSurfacesRefreshingRepository] (the unqualified
 * binding, `RepositoryModule.provideTaskRepository`, whose `refreshSurfaces()` calls back into
 * the widget — see [WidgetEntryPoint]'s KDoc for the reentrancy this qualifier avoids).
 *
 * This calls `RepositoryModule`'s `@Provides` function directly with hand-written fakes rather
 * than resolving it through a real Hilt [SingletonComponent]: `@Qualifier` annotations in this
 * project use `AnnotationRetention.BINARY` (see `Qualifiers.kt`), so they are erased before
 * runtime and cannot be observed by reflection — and this project has no JVM-level Hilt test
 * harness to stand up the generated component instead. Calling the provider function is the
 * next best thing: it is the exact code Hilt calls for this qualifier, so a regression that
 * changed `provideReminderSchedulingRepository` to build a `TaskSurfacesRefreshingRepository`
 * (or moved `@ReminderRepo` onto a different function) would fail this test.
 */
class WidgetEntryPointTest {

    @Test
    fun `the ReminderRepo provider is ReminderSchedulingRepository, not TaskSurfacesRefreshingRepository`() {
        val result = RepositoryModule.provideReminderSchedulingRepository(
            delegate = FakeTaskRepository(),
            reminderScheduler = FakeReminderScheduler(),
            preferences = FakeUserPreferencesRepository(),
        )

        assertTrue(result is ReminderSchedulingRepository)
        assertFalse(
            "the @ReminderRepo binding must never be the surface-refreshing decorator",
            result is TaskSurfacesRefreshingRepository,
        )
    }
}
