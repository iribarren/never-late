package com.neverlate.di

import android.content.Context
import com.neverlate.data.articles.ArticleDao
import com.neverlate.data.articles.ArticleRemoteKeysDao
import com.neverlate.data.sync.OutboxDao
import com.neverlate.data.tasks.NeverLateDatabase
import com.neverlate.data.tasks.TaskDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Feature 13d: provides [NeverLateDatabase] and its DAOs. [NeverLateDatabase.getInstance] already
 * guarantees a single, process-wide instance on its own (its own `@Volatile` double-checked-locking
 * singleton, see its KDoc) — `@Singleton` here means Hilt calls it exactly once and hands every
 * injection point the same result, rather than each one separately running that check.
 *
 * A plain `@Module object` (not an `abstract class`) is enough here: every function below
 * *constructs or reads* something (`getInstance`, a DAO accessor) rather than aliasing one
 * interface to its one implementation, which is what `@Binds` is for instead (see
 * [com.neverlate.di.StorageModule]/[com.neverlate.di.RepositoryModule]).
 */
@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): NeverLateDatabase =
        NeverLateDatabase.getInstance(context)

    @Provides
    fun provideTaskDao(database: NeverLateDatabase): TaskDao = database.taskDao()

    @Provides
    fun provideArticleDao(database: NeverLateDatabase): ArticleDao = database.articleDao()

    @Provides
    fun provideArticleRemoteKeysDao(database: NeverLateDatabase): ArticleRemoteKeysDao =
        database.articleRemoteKeysDao()

    @Provides
    fun provideOutboxDao(database: NeverLateDatabase): OutboxDao = database.outboxDao()
}
