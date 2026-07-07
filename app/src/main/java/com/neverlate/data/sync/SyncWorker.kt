package com.neverlate.data.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.neverlate.data.DataStoreUserPreferencesRepository
import com.neverlate.data.auth.EncryptedTokenStorage
import com.neverlate.data.tasks.NeverLateDatabase
import java.util.concurrent.TimeUnit

private const val UNIQUE_WORK_NAME = "task_sync"

/** Same "don't drain the battery" trade-off as [com.neverlate.ui.widget.TaskSurfacesRefreshWorker]:
 *  this is a *backstop* for the immediate best-effort push (see [SyncEngine.schedulePush]), not
 *  the only way sync ever happens. */
private const val SYNC_INTERVAL_MINUTES = 15L

/**
 * Drains the outbox (and pulls remote changes) once connectivity returns, even if nobody is
 * actively using the app — the WorkManager job the feature spec's *Sync Model* calls for,
 * guaranteeing that a change made entirely offline eventually reaches the backend (US-4).
 *
 * Like [com.neverlate.ui.notification.BootRescheduleWorker], this worker has no Activity-scoped
 * repositories to reuse — WorkManager instantiates it with no way to inject `MainActivity`'s
 * instances — so it reconstructs equivalent ones from the same process-wide singletons
 * ([NeverLateDatabase.getInstance]) and lightweight wrappers
 * ([EncryptedTokenStorage]/[DataStoreUserPreferencesRepository]). One known limitation from doing
 * so: if this worker's own `TasksApi` sees a 401, `TokenAuthenticator` tries a silent renewal
 * first (feature 12) same as everywhere else, but if that *also* fails it can only clear
 * [EncryptedTokenStorage] directly (there is no live `AuthRepository` instance here to flip its
 * `authState`) — see [TasksNetwork]'s `onUnauthorized` argument below. If the app process is still
 * alive with its own `AuthRepository` already in memory, that in-memory state only catches up
 * once the *live* `TasksApi` (the one `MainActivity` built, shared with [SyncEngine.schedulePush])
 * also fails to renew, which happens the next time any task mutation or foreground sync runs.
 * Acceptable for an MVP: the persisted token is correctly cleared either way, so a
 * killed-and-relaunched process always starts logged out correctly.
 */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val tokenStorage = EncryptedTokenStorage(applicationContext)
        val database = NeverLateDatabase.getInstance(applicationContext)
        val preferences = DataStoreUserPreferencesRepository(applicationContext)
        val api = TasksNetwork.create(tokenStorage = tokenStorage) { tokenStorage.clearSession() }
        val engine = SyncEngine(api, database, preferences, tokenStorage)

        return when (engine.syncNow()) {
            SyncStatus.Offline, SyncStatus.Error -> Result.retry()
            SyncStatus.Idle, SyncStatus.Syncing, SyncStatus.UpToDate -> Result.success()
        }
    }

    companion object {
        /**
         * Schedules the periodic drain, once, for the whole app process — same
         * `enqueueUniquePeriodicWork` + [ExistingPeriodicWorkPolicy.KEEP] idempotency as
         * [com.neverlate.ui.widget.TaskSurfacesRefreshWorker.enqueuePeriodic]. The
         * [NetworkType.CONNECTED] constraint is what makes this specifically an "once connectivity
         * returns" job rather than a plain periodic one — WorkManager itself defers/reschedules it
         * around connectivity changes, so this class needs no manual `ConnectivityManager` checks.
         */
        fun enqueuePeriodic(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = PeriodicWorkRequestBuilder<SyncWorker>(SYNC_INTERVAL_MINUTES, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(UNIQUE_WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
