package com.neverlate.data.sync

/**
 * The outcome of the most recent [SyncEngine.syncNow] attempt — drives the minimal sync indicator
 * (OQ-1 of the feature spec: a pull-to-refresh spinner plus a subtle hint, not a rich per-task
 * badge). Modeled as a sealed hierarchy, same success/error-with-cause shape
 * [com.neverlate.data.articles.ArticlesRemoteMediator]'s `MediatorResult` already uses, so the UI
 * can render a distinct (localized) hint for each case instead of a single boolean.
 */
sealed interface SyncStatus {
    /** No sync has run yet this process (or the user is logged out — see [SyncEngine.syncNow]). */
    data object Idle : SyncStatus

    /** A push-then-pull cycle is currently in flight. */
    data object Syncing : SyncStatus

    /** The last cycle finished cleanly: the local cache matches the backend as of that moment. */
    data object UpToDate : SyncStatus

    /** The last cycle failed because there was no connectivity (an [java.io.IOException]). */
    data object Offline : SyncStatus

    /** The last cycle failed for a reason other than connectivity (e.g. an unexpected server error). */
    data object Error : SyncStatus
}
