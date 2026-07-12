package com.neverlate.di

import android.content.Context
import com.neverlate.data.DataStoreUserPreferencesRepository
import com.neverlate.data.UserPreferencesRepository
import com.neverlate.data.auth.EncryptedTokenStorage
import com.neverlate.data.auth.TokenStorage
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Feature 13d: provides the two on-device storage seams — [TokenStorage] (Keystore-backed, feature
 * 11/12) and [UserPreferencesRepository] (plain DataStore, feature 07). Both are the simplest DI
 * shape there is: **one interface, one real implementation, no decoration** — which is exactly what
 * `@Binds` is for (contrast with [com.neverlate.di.RepositoryModule]'s `TaskRepository` chain, where
 * `@Provides` composes several layers instead).
 *
 * `@Binds` methods must be `abstract` (there is no body to write — Hilt only needs to know "when
 * asked for the interface, hand out this implementation"), so this module is an `abstract class`
 * rather than an `object`. Kotlin doesn't allow an `abstract class`'s own body to hold the
 * `@Provides` functions that construct [EncryptedTokenStorage]/[DataStoreUserPreferencesRepository]
 * in the first place (only its `companion object` can), which is why those two live in the
 * `companion object` below — the standard Hilt idiom for mixing `@Binds` and `@Provides` in one
 * module.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class StorageModule {

    @Binds
    @Singleton
    abstract fun bindTokenStorage(impl: EncryptedTokenStorage): TokenStorage

    @Binds
    @Singleton
    abstract fun bindUserPreferencesRepository(impl: DataStoreUserPreferencesRepository): UserPreferencesRepository

    companion object {

        @Provides
        @Singleton
        fun provideEncryptedTokenStorage(@ApplicationContext context: Context): EncryptedTokenStorage =
            EncryptedTokenStorage(context)

        @Provides
        @Singleton
        fun provideDataStoreUserPreferencesRepository(
            @ApplicationContext context: Context,
        ): DataStoreUserPreferencesRepository = DataStoreUserPreferencesRepository(context)
    }
}
