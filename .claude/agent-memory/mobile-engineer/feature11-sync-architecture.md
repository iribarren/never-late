---
name: feature11-sync-architecture
description: Client-side architecture decisions for feature 11 (remote DB + offline-first sync) that aren't obvious from re-reading the code once more features land on top.
metadata:
  type: project
---

Feature 11 (`feature/remote-db-sync`, spec `docs/specs/2026-07-06-remote-db-sync.md`, contract
`docs/api/contract.md`) added auth + offline-first sync to the previously local-only Android app.
Key decisions worth knowing before touching this area again:

- **Outbox is one row per task, not an append-only log.** `OutboxEntity.taskLocalId` is the
  `@PrimaryKey` (not autoincrement); `OutboxDao.enqueue` uses `OnConflictStrategy.REPLACE`. A task
  edited twice before the first edit is ever pushed just replaces the queued intent — the
  `clientRef` is reused across replaces (looked up via `outboxDao.getForTask` before enqueueing) so
  a create's idempotency token stays stable. **Why:** far simpler to reason about/test than replaying
  a full change history the server never needed. **How to apply:** if a future feature needs to
  preserve intermediate edit history for sync, this assumption needs revisiting — right now only the
  latest state per task ever gets pushed.

- **`TaskRepository` interface gained two methods with default (no-op) implementations**:
  `suspend fun refreshFromServer()` and `fun observeSyncStatus(): Flow<SyncStatus>`. This is how
  sync/auth stayed "additive" per US-7 of the spec, keeping `TasksViewModel` dependent only on
  `TaskRepository`, never on `SyncEngine` directly. **Gotcha:** any *pass-through* decorator
  (`ReminderSchedulingRepository`, `TaskSurfacesRefreshingRepository`) MUST explicitly override both
  methods to forward to its `delegate` — otherwise it silently falls back to the interface's no-op
  default instead of reaching `OutboxTaskRepository` further down the chain, and sync/pull-to-refresh
  silently does nothing. If a new decorator is added to the `TaskRepository` chain in the future,
  remember this forwarding requirement.

- **Decorator composition order in `MainActivity`**: `TaskSurfacesRefreshingRepository` wraps
  `ReminderSchedulingRepository` wraps `OutboxTaskRepository` wraps `RoomTaskRepository`.
  `OutboxTaskRepository` is the one that talks to `SyncEngine`; it stamps `updatedAt`/`syncState`
  and writes the outbox row in the same Room transaction as the task row (`database.withTransaction`).

- **LWW + delete-wins merge logic is pure**, in `domain/sync/SyncMerge.kt`
  (`reconcilePulledTask`) — mirrors `domain/tasks/ReminderPlanning.kt`'s pattern. No Android
  imports, so it's the natural place for qa-engineer to add exhaustive unit tests (local==null,
  pending create/update/delete vs. a pulled tombstone/edit, LWW tie-break by `updatedAt`).

- **Auth gate lives in `AppNavHost`**: it observes `AuthRepository.authState` and switches between
  `AuthGateNavHost` (login/register) and `MainAppNavHost` (the pre-existing graph) — no ViewModel
  ever calls `navController.navigate()` on login/logout; they just flip `AuthRepository`'s
  `StateFlow` and the nav graph reacts. Same reasoning applies to `SettingsViewModel.logout()`.

- **Sync triggers**: (1) `LaunchedEffect(Unit)` in `MainAppNavHost` calls
  `taskRepository.refreshFromServer()` once per login/cold-start-while-logged-in (app open/foreground
  trigger); (2) pull-to-refresh in `TasksScreen` calls the same method; (3)
  `OutboxTaskRepository.saveTask`/`deleteTask` call `SyncEngine.schedulePush()` (fire-and-forget,
  its own `CoroutineScope`) after every mutation; (4) `SyncWorker` (WorkManager,
  `NetworkType.CONNECTED` constraint, 15 min period) is the backstop that drains the outbox even if
  the app was closed while offline.

- **`SyncWorker`/`BootRescheduleWorker`-style limitation**: `SyncWorker.doWork()` reconstructs its
  own `TasksApi`/`SyncEngine`/`EncryptedTokenStorage` from process-wide singletons (no custom
  `WorkerFactory` DI in this project). If it sees a 401, it can only clear `EncryptedTokenStorage`
  directly — there's no live `AuthRepository` instance there to flip `authState`. If the app process
  is still alive with its own in-memory `AuthRepositoryImpl`, that only catches up once the *live*
  `TasksApi` (the one built in `MainActivity`, shared with `OutboxTaskRepository`) also 401s. Accepted
  as a known MVP limitation (documented in `SyncWorker`'s KDoc) — a killed-and-relaunched process
  always starts logged out correctly either way.

- **Base URL for the emulator**: `http://10.0.2.2:8080/` (`data/network/BackendNetwork.kt`,
  `DEFAULT_BACKEND_BASE_URL`), per the API contract. Physical devices need the host's LAN IP instead
  (override the `baseUrl` param).

- **`androidx.security:security-crypto:1.1.0`** was added for `EncryptedSharedPreferences`
  (Keystore-backed JWT storage in `data/auth/TokenStorage.kt`). It compiles and resolves fine, but
  both `EncryptedSharedPreferences` and `MasterKey` are flagged `@Deprecated` in that version (the
  Gradle build prints deprecation warnings, not errors). If a future security review flags this,
  the eventual replacement is Google's newer Tink-based API — not yet migrated here.
