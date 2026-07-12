package com.neverlate.di

import com.neverlate.data.UserPreferencesRepository
import com.neverlate.data.articles.ArticlesApi
import com.neverlate.data.articles.ArticlesNetwork
import com.neverlate.data.auth.AuthApi
import com.neverlate.data.auth.AuthNetwork
import com.neverlate.data.auth.AuthRepositoryImpl
import com.neverlate.data.auth.TokenStorage
import com.neverlate.data.sync.SyncEngine
import com.neverlate.data.sync.TasksApi
import com.neverlate.data.sync.TasksNetwork
import com.neverlate.data.tasks.NeverLateDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Feature 13d: provides every network/sync object `MainActivity.onCreate` used to build inline —
 * the three tiny Retrofit factories ([ArticlesNetwork], [AuthNetwork], [TasksNetwork]) plus
 * [SyncEngine].
 *
 * [provideTasksApi] is the one interesting wiring here: [TasksNetwork.create] needs
 * [AuthRepositoryImpl.notifyUnauthorized] as its `onUnauthorized` callback (feature 12's silent
 * refresh giving up) — see that method's KDoc. That is why this function depends on the
 * **concrete** [AuthRepositoryImpl] rather than the [com.neverlate.data.auth.AuthRepository]
 * interface: `notifyUnauthorized` is not part of the public seam other callers (ViewModels) see,
 * only this one internal wiring needs it. Hilt resolves [AuthRepositoryImpl] to the exact same
 * `@Singleton` instance [com.neverlate.di.RepositoryModule] binds to `AuthRepository` elsewhere —
 * there are never two.
 */
@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideArticlesApi(): ArticlesApi = ArticlesNetwork.create()

    @Provides
    @Singleton
    fun provideAuthApi(): AuthApi = AuthNetwork.create()

    @Provides
    @Singleton
    fun provideTasksApi(tokenStorage: TokenStorage, authRepositoryImpl: AuthRepositoryImpl): TasksApi =
        TasksNetwork.create(tokenStorage = tokenStorage, onUnauthorized = authRepositoryImpl::notifyUnauthorized)

    @Provides
    @Singleton
    fun provideSyncEngine(
        tasksApi: TasksApi,
        database: NeverLateDatabase,
        preferences: UserPreferencesRepository,
        tokenStorage: TokenStorage,
    ): SyncEngine = SyncEngine(tasksApi, database, preferences, tokenStorage)
}
