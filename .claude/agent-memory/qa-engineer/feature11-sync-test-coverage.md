---
name: feature11-sync-test-coverage
description: Why Robolectric was added (first time in this project) for feature 11's sync/auth tests, and a real bug it caught in OutboxTaskRepository.
metadata:
  type: project
---

Added Android client test coverage for feature 11 (remote DB + offline-first sync,
`feature/remote-db-sync`) on 2026-07-06: `domain/sync/SyncMergeTest` (13, pure JVM),
`data/sync/SyncEngineTest` (7), `data/sync/OutboxTaskRepositoryTest` (9),
`data/auth/AuthErrorsTest` (6, pure JVM), `data/auth/AuthRepositoryTest` (8). Full suite went from
134 to 177 passing tests.

**Robolectric (`org.robolectric:robolectric`) is now a `testImplementation` dependency — the
project's pre-11 convention was hand-written DAO fakes on plain JVM (see
`CachingArticleRepositoryTest`'s `FakeArticleDao`), never real Room.** That stopped being an option
for `OutboxTaskRepository`/`SyncEngine`/`AuthRepositoryImpl.logout`, which all call
`database.withTransaction { ... }` — a `RoomDatabase` extension function with no fakeable seam;
faking it would mean not actually testing the "same transaction" guarantee that is the whole point
(US-3 of the spec). Robolectric supplies a real shadowed SQLite connection on the JVM, no
emulator/Testcontainers needed. See `app/src/test/java/com/neverlate/data/sync/SyncTestDoubles.kt`
(`buildInMemoryTestDatabase`) for the helper, and `@RunWith(RobolectricTestRunner::class)` +
`@Config(sdk = [34])` on the three test classes that need it. Pure-logic tests
(`SyncMergeTest`/`AuthErrorsTest`) stay plain JUnit, no Robolectric.

**Found and fixed a real bug in `OutboxTaskRepository.saveTask`**: it decided both "insert vs.
update the local row" *and* "queue a CREATE vs. UPDATE outbox operation" from the same check
(`task.id == 0L`). A task saved once (gets a local id) and edited again *before its first push ever
completes* still has `serverId == null`, but the old code queued that second edit as an UPDATE.
`SyncEngine.pushOne`'s UPDATE branch finds `serverId == null` and silently drops the outbox row
(its own comment even says "should not happen") — so the task was **never pushed to the server at
all**, permanently. Fixed by keying the outbox-operation choice off `task.serverId == null`
(mirroring the check `deleteTask` already used correctly) instead of `task.id == 0L`. Regression
test: `OutboxTaskRepositoryTest`'s "a task edited a second time before its first push still queues
as CREATE" — reproduced the bug before the fix, passes after.

**A `notifyUnauthorized()` test was deliberately not written** (`AuthRepositoryImpl.notifyUnauthorized`
launches `logout()` on its own untracked `CoroutineScope(Dispatchers.IO)`, since it's called from a
synchronous OkHttp interceptor with no coroutine to suspend from). Polling for it in a test proved
flaky under Robolectric — the async job sometimes completed well past a 2s poll window, then
crashed a *later* test by touching its already-`close()`d in-memory database. `logout()`'s own
behavior is covered directly instead; the interceptor-triggers-callback wiring is covered by
`SyncEngineTest`'s 401 test. If a future feature needs to actually assert on this path, it would
need `AuthRepositoryImpl` to accept an injectable `CoroutineScope`/dispatcher for its fire-and-forget
work rather than hardcoding one.
