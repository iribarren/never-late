package com.neverlate.di

import android.content.Context
import com.neverlate.data.UserPreferencesRepository
import com.neverlate.data.articles.ArticleRepository
import com.neverlate.data.articles.ArticlesApi
import com.neverlate.data.articles.CachingArticleRepository
import com.neverlate.data.auth.AuthApi
import com.neverlate.data.auth.AuthRepository
import com.neverlate.data.auth.AuthRepositoryImpl
import com.neverlate.data.auth.TokenStorage
import com.neverlate.data.sync.OutboxTaskRepository
import com.neverlate.data.sync.SyncEngine
import com.neverlate.data.tasks.NeverLateDatabase
import com.neverlate.data.tasks.RoomTaskRepository
import com.neverlate.data.tasks.TaskDao
import com.neverlate.data.tasks.TaskRepository
import com.neverlate.ui.notification.AlarmManagerReminderScheduler
import com.neverlate.ui.notification.ReminderScheduler
import com.neverlate.ui.notification.ReminderSchedulingRepository
import com.neverlate.ui.widget.TaskSurfacesRefreshingRepository
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.time.Clock
import javax.inject.Singleton

/**
 * The crux of this feature (US-3 of the spec): provides [TaskRepository] as the same **three-layer
 * decorator chain** `MainActivity.onCreate` used to compose by hand, outermost -> innermost:
 * [TaskSurfacesRefreshingRepository] -> [ReminderSchedulingRepository] -> [OutboxTaskRepository] ->
 * [RoomTaskRepository]. Hilt sees **four** different providers that all return [TaskRepository],
 * which on its own is ambiguous — [com.neverlate.di.RoomRepo]/[com.neverlate.di.OutboxRepo]/
 * [com.neverlate.di.ReminderRepo] (see `Qualifiers.kt`) disambiguate every *inner* layer, so each
 * `@Provides` function below receives precisely the instance one level further in, never
 * accidentally the wrong one. [provideTaskRepository] at the bottom is deliberately **unqualified**
 * — that is the one binding the rest of the app (every ViewModel, [com.neverlate.MainActivity])
 * actually injects. Reading the four functions top to bottom mirrors the nested constructor calls
 * `MainActivity` used to write by hand, just one qualifier per nesting level instead of one line of
 * indentation per level.
 *
 * Also binds [AuthRepository] (to [AuthRepositoryImpl]), [ArticleRepository] (to
 * [CachingArticleRepository]), and [ReminderScheduler] (to [AlarmManagerReminderScheduler]) — three
 * ordinary one-interface-one-implementation seams, same shape as `StorageModule`'s.
 *
 * [AuthRepositoryImpl] is provided as **both** [AuthRepository] (`bindAuthRepository`, what every
 * ViewModel depends on) **and** reachable as its own concrete type (`provideAuthRepositoryImpl`,
 * what [com.neverlate.di.NetworkModule]'s `provideTasksApi` and [com.neverlate.MainActivity]'s
 * `onAuthenticated` wiring both need) — `@Singleton` on the `@Provides` function means both
 * injection points resolve to the exact same instance, never two separate `AuthRepositoryImpl`s.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds
    @Singleton
    abstract fun bindAuthRepository(impl: AuthRepositoryImpl): AuthRepository

    @Binds
    @Singleton
    abstract fun bindArticleRepository(impl: CachingArticleRepository): ArticleRepository

    @Binds
    @Singleton
    abstract fun bindReminderScheduler(impl: AlarmManagerReminderScheduler): ReminderScheduler

    companion object {

        @Provides
        @Singleton
        fun provideAuthRepositoryImpl(
            api: AuthApi,
            tokenStorage: TokenStorage,
            database: NeverLateDatabase,
            userPreferencesRepository: UserPreferencesRepository,
        ): AuthRepositoryImpl = AuthRepositoryImpl(api, tokenStorage, database, userPreferencesRepository)

        @Provides
        @Singleton
        fun provideCachingArticleRepository(
            api: ArticlesApi,
            database: NeverLateDatabase,
        ): CachingArticleRepository = CachingArticleRepository(api, database)

        @Provides
        @Singleton
        fun provideAlarmManagerReminderScheduler(
            @ApplicationContext context: Context,
        ): AlarmManagerReminderScheduler = AlarmManagerReminderScheduler(context)

        /**
         * [com.neverlate.ui.stats.StatsViewModel]'s `clock` constructor parameter keeps a Kotlin
         * default value (`Clock.systemDefaultZone()`) so a test can still construct it directly
         * with a fixed [Clock] and no other caller has to pass one — see that class's KDoc.
         * Dagger/Hilt, however, does **not** see that default: it generates a call to the
         * `@Inject` constructor that always passes every parameter explicitly, so an
         * Hilt-provided `StatsViewModel` still needs an actual `@Provides` binding for [Clock],
         * or the build fails with a missing-binding error despite the source-level default. This
         * is the real system clock — the exact same value the default expression would have
         * produced.
         */
        @Provides
        fun provideClock(): Clock = Clock.systemDefaultZone()

        // --- TaskRepository decorator chain, innermost -> outermost (see this module's KDoc) ---

        /** Feature 11: the real, Room-backed repository — the innermost layer of the chain. */
        @Provides
        @Singleton
        @RoomRepo
        fun provideRoomTaskRepository(taskDao: TaskDao): TaskRepository = RoomTaskRepository(taskDao)

        /** Feature 11: stamps sync metadata + enqueues an outbox row on every write, wrapping [RoomRepo]. */
        @Provides
        @Singleton
        @OutboxRepo
        fun provideOutboxTaskRepository(
            database: NeverLateDatabase,
            @RoomRepo delegate: TaskRepository,
            syncEngine: SyncEngine,
        ): TaskRepository = OutboxTaskRepository(database, delegate, syncEngine)

        /** Feature 09: (re)schedules a task's reminder alarm on every write, wrapping [OutboxRepo]. */
        @Provides
        @Singleton
        @ReminderRepo
        fun provideReminderSchedulingRepository(
            @OutboxRepo delegate: TaskRepository,
            reminderScheduler: ReminderScheduler,
            preferences: UserPreferencesRepository,
        ): TaskRepository = ReminderSchedulingRepository(delegate, reminderScheduler, preferences)

        /**
         * Features 05/06: refreshes the widget + lock-screen notification on every write, wrapping
         * [ReminderRepo] — the **outermost** layer, and the only unqualified [TaskRepository]
         * binding: this is what every ViewModel and [com.neverlate.MainActivity] actually inject.
         */
        @Provides
        @Singleton
        fun provideTaskRepository(
            @ReminderRepo delegate: TaskRepository,
            @ApplicationContext context: Context,
        ): TaskRepository = TaskSurfacesRefreshingRepository(delegate, context)
    }
}
