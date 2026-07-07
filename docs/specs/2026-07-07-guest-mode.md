# Feature 13 (extra) — Guest mode (local-only) and merge on sign-in

**Status:** Approved (2026-07-07) — product decisions resolved (see *Product Decisions*).
**Date:** 2026-07-07
**Branch (suggested):** `feature/guest-mode`
**Tutorial lesson (planned):** `tutorial/13-modo-invitado.md` (Spanish), numbered after lesson 12.
**Prompt origin:** `docs/prompts/13-modo-invitado.md`
**Builds on:** Feature 11 (remote DB + offline-first sync: the backend, `AuthRepository`, the outbox,
`serverId`/`clientRef`, last-write-wins, `SyncEngine`) and Feature 12 (access + refresh tokens). This
feature **extends** that machinery; it does **not** rebuild it. In fact, most of what "merge on
sign-in" needs already exists — the deliberate design goal is to add the smallest possible surface on
top of the sync engine Feature 11 shipped.

---

## Overview

Feature 11 made an **account mandatory**: the app opens on a login/register gate and you cannot reach
your tasks without signing in. That was a deliberate simplification — it meant the sync engine never
had to reconcile *pre-existing local tasks* into a *fresh account*, because there were no pre-existing
local tasks (a logged-out device has an empty cache). Feature 11 explicitly deferred **guest mode**
for exactly that reason (see its *Out of Scope*).

Feature 13 lifts that restriction. It lets a person **use the whole app without an account** — create,
edit, complete, and delete tasks against the local Room cache, with no sync and no gate — and then, if
and when they **register or log in**, **merges** the tasks they created as a guest into their account
with **no loss and no duplicates**, after which sync continues exactly as in Feature 11.

The pleasant surprise, on inspecting the existing code, is how little genuinely new sync logic this
requires. A guest's tasks are already the exact shape the sync engine calls an "orphan": a Room `Task`
row with `serverId == null` and `syncState = PENDING_CREATE`. `OutboxTaskRepository` already enqueues
an idempotent `CREATE` outbox row (keyed by a stable `clientRef`) for every such save, and
`SyncEngine.syncNow()` already **early-returns `Idle` when there is no access token** — so a guest's
changes simply queue up quietly. The moment sign-in supplies a token, the app's existing
"sync on app open" trigger (`MainAppNavHost`'s `LaunchedEffect`) drains that queue: the queued creates
are pushed to the account (idempotent by `clientRef`), then a pull brings the account's other tasks
down. **The merge is the deferred push finally running.** This feature is therefore mostly about
(a) a three-faced auth state so guests can *reach* the task UI, and (b) resolving the product
questions that mandatory-account mode let us dodge — chiefly what `logout()` should do now that wiping
everything can destroy a guest's work.

**Guiding principle (unchanged from Feature 11):** Room is the local single source of truth and the
network is an enhancement, never a prerequisite. Guest mode is just that principle taken to its limit —
the app is fully usable with *no* backend contact at all.

---

## Goals

Success means:

1. A person can **open the app and manage tasks immediately, without an account** — no forced
   login/register gate. Full local CRUD works against Room.
2. Registering or logging in **adopts** the guest's pre-existing local tasks into the account: every
   guest task ends up on the server, **none lost, none duplicated**, reconciled against whatever the
   account already holds by the existing last-write-wins rule.
3. After adoption, the device behaves exactly like a Feature 11 logged-in device: normal push/pull
   sync, tasks visible on the account's other devices.
4. The behaviour of a guest who **never** creates an account is well-defined: their tasks stay local,
   indefinitely, fully usable.
5. **Logout** has a defined, safe behaviour that does not silently destroy work or duplicate tasks on
   the next sign-in (see *Product Decisions*).
6. The existing surfaces that read tasks — home-screen widget (05), lock-screen notification (06),
   reminders (09) — keep working in guest mode, since they read Room regardless of auth.
7. **No backend or API-contract change** is required: adoption reuses the existing idempotent
   `POST /tasks` keyed by `clientRef`.
8. The seams are preserved and reused, not duplicated: `TaskRepository`, `AuthRepository` (extended
   with a `Guest` state), `SyncEngine`, the outbox, `clientRef`, LWW, `EncryptedTokenStorage`.
9. `tutorial/13-modo-invitado.md` (Spanish) explains orphan-vs-account data, merge-at-login via
   idempotent creates, the three-faced auth state, and the merge/logout product trade-offs.

---

## User Stories

### US-1 — Use the app without an account (no forced gate)
**As a** first-time user who just wants to try the app,
**I want** to start managing tasks immediately without registering,
**so that** I'm not blocked by a sign-up wall before I've seen any value.

**Acceptance Criteria**
- Given a fresh install (no session), when I open the app, then I land on the normal task surfaces
  (onboarding/home/tasks) — **not** the login gate — and can list/create/edit/complete/delete tasks.
- Every guest mutation is written to Room immediately and the UI reflects it optimistically, exactly as
  a logged-in offline mutation does today.
- No guest action shows a blocking error merely because there is no account or no connectivity; the
  network is never contacted for a guest.
- Sign-in and registration remain **reachable but optional** — from an explicit entry point in
  Settings — so a guest can create an account whenever they choose.

### US-2 — Adopt my local tasks when I register
**As a** guest who decides to create an account,
**I want** the tasks I already created to become part of my new account,
**so that** signing up doesn't cost me the work I did as a guest.

**Acceptance Criteria**
- Given local guest tasks (each `serverId == null`), when I register successfully, then each of them is
  created on the server (idempotently by `clientRef`) and its local row is reconciled with the assigned
  `serverId` and `syncState = SYNCED` — no task is dropped.
- Re-running sync (e.g. a retried push after a lost ack) does **not** create duplicates on the server:
  the `clientRef` idempotency from Feature 11 protects each create.
- After adoption, the same tasks appear on the account's other devices on their next pull.

### US-3 — Adopt my local tasks when I log into an existing account
**As a** guest who already has an account with tasks on another device,
**I want** my new local (guest) tasks merged in alongside the account's existing tasks,
**so that** logging in adds my guest work without wiping or duplicating what's already there.

**Acceptance Criteria**
- Given local guest tasks **and** an account that already has tasks server-side, when I log in, then a
  push adopts the guest tasks and a pull brings the account's existing tasks into the local cache; both
  sets coexist with **no loss**.
- Guest tasks are treated as genuinely new creates (new `clientRef`s the server has never seen), so
  they never collide with, overwrite, or get overwritten by the account's existing tasks. *(There is
  deliberately no content-based de-duplication — a guest task "Buy milk" and an identical pre-existing
  account task both survive as two tasks; see Product Decisions.)*
- If the same underlying task somehow exists on both sides with the same `serverId` (not possible for a
  genuine guest orphan, but stated for completeness), the existing LWW-by-`updatedAt` rule applies.

### US-4 — A guest who never signs up keeps their tasks
**As a** user who never wants an account,
**I want** my tasks to persist locally forever,
**so that** guest mode is a real, first-class way to use the app, not a nag screen.

**Acceptance Criteria**
- Given I only ever use the app as a guest, when I close and reopen it (including after process death),
  then my tasks are still there, read from Room.
- No guest data is ever expired, cleared, or uploaded without an explicit sign-in.

### US-5 — Logout is safe and predictable
**As a** user who signs out,
**I want** signing out to behave predictably without destroying work or corrupting my next sign-in,
**so that** I can trust the account boundary.

**Acceptance Criteria**
- Given I am logged in and my tasks are synced, when I log out, then I land back in a **usable
  (guest) app** rather than a dead login gate.
- Logout does **not** leave the local cache in a state that would cause the *same* tasks to be
  re-created (duplicated) on the server the next time someone logs in on this device (see Product
  Decisions for the chosen rule and its rationale).
- Logout still best-effort revokes the refresh token server-side (Feature 12, US-4) and always clears
  the session locally even if that revoke call fails.

### US-6 — Existing task surfaces keep working in guest mode
**As a** guest,
**I want** the widget, lock-screen notification, and reminders to work,
**so that** guest mode is not a degraded second-class experience.

**Acceptance Criteria**
- Given I am a guest with tasks, the pending-tasks widget (05), the lock-screen notification (06), and
  scheduled reminders (09) all reflect my local tasks, because they read the same Room cache the UI
  does.
- None of those surfaces requires an access token or a session to function.

---

## Technical Approach

High-level only; the implementing agents own the details. The overriding constraint is **extend, don't
reinvent** — reuse the Feature 11/12 seams.

### 1. Three-faced auth state (`AuthState`)
`data/auth/AuthRepository.kt` today has a sealed interface with two cases: `LoggedOut` and
`LoggedIn(userId, email)`. Add a third: **`Guest`** (a `data object`). Semantics:

| State | Meaning | Cold-start trigger | Navigation |
|-------|---------|--------------------|------------|
| `LoggedIn(userId, email)` | Valid session; sync active. | A stored access token exists. | Main app graph, sync on. |
| `Guest` | No account, using the app locally; sync inactive. | **Default** when no token exists. | Main app graph, sync off; sign-in reachable from Settings. |
| `LoggedOut` | A session ended **involuntarily** (a 401 whose refresh failed) or the user explicitly chose the sign-in gate. | *(not the cold-start default)* | Auth gate (login/register). |

- `readInitialAuthState()` changes its `else` branch: **no token → `Guest`** (instead of `LoggedOut`).
  This is the one line that removes the mandatory gate.
- `AppNavHost` (`ui/navigation/AppNavHost.kt`) gains a `Guest` branch that composes the existing
  `MainAppNavHost`. `LoggedIn` keeps composing `MainAppNavHost`; `LoggedOut` keeps composing the auth
  gate. The login/register screens also become reachable as ordinary destinations from **Settings**, so
  a guest can sign in without a state flip.
- The distinction between `Guest` and `LoggedOut` is deliberate and teachable: **`Guest` = a chosen,
  usable local-only mode; `LoggedOut` = "your session ended, here is the gate."** Keeping `LoggedOut`
  for the Feature 12 failed-refresh fallback means an involuntarily-expired user is routed to re-login
  (their data is on the server and they likely want it back) rather than silently dropped into an empty
  guest app.

### 2. Guest CRUD — reuse the single write path
Guest task writes go through the **same** `OutboxTaskRepository` that logged-in writes use. That
repository already:
- stamps `serverId == null` saves as `syncState = PENDING_CREATE` and enqueues a `CREATE` outbox row
  with a stable `clientRef`;
- handles a guest editing a still-orphan task (re-uses the existing `clientRef`, keeps it a `CREATE`);
- handles a guest deleting an orphan task (hard-deletes the local row and drops its outbox row — no
  tombstone needed, since the server never saw it).

`schedulePush()` still fires after each guest save/delete, but `SyncEngine.syncNow()` **already
early-returns `Idle` when `tokenStorage.getAccessToken() == null`**, so nothing hits the network. The
guest's outbox is simply a queue of *deferred* creates. **This means guest CRUD needs essentially no
new repository code** — the recommended approach keeps one write path for all states (see
*Product Decisions → PD-4* for the alternative and why we reject it).

### 3. Merge / adoption on sign-in — the deferred push runs
Adoption is not a new subsystem; it is the existing "sync on app open" trigger firing once a token
exists. Concretely, after a successful `register()`/`login()`:
- `authenticate()` saves the session and flips `AuthState` to `LoggedIn` **before** `MainAppNavHost` is
  (re)composed, so by the time that composable's `LaunchedEffect { taskRepository.refreshFromServer() }`
  runs, the access token is present.
- `refreshFromServer()` → `syncNow()` now passes the token check: **push** replays the guest outbox
  (each orphan created on the server, idempotent by `clientRef`, local row reconciled with `serverId`),
  then **pull** (`GET /tasks?since=0`) brings the account's other tasks down. `logout` resets the sync
  cursor to `0` and a guest never advances it, so the first post-sign-in pull is a full-set pull.
- The only decision to make explicit is **whether to gate this behind a confirmation prompt** (PD-1) —
  the mechanics are otherwise free.

### 4. Logout target
Change the logout landing state from `LoggedOut` to **`Guest`** (US-5), and keep the existing wipe of
synced tasks + outbox + cursor. Rationale in PD-2. `notifyUnauthorized()` (the Feature 12 failed-refresh
fallback) should route to **`LoggedOut`** instead, distinguishing an involuntary session end from a
deliberate sign-out — this likely means splitting the current single `logout()` into "user-initiated
sign-out → `Guest`" and "session-invalidated → `LoggedOut`", sharing the same local-clear internals.

---

## Product Decisions (resolve explicitly before implementing)

### PD-1 — Auto-merge silently vs. prompt to confirm? → **RESOLVED: always silent**
**Decision (user, 2026-07-07): adopt guest tasks automatically on both register and login, with no
confirmation prompt.** The merge is the deferred push simply running; there is no separate "merge UI"
to build. This is the simplest path and keeps the tutorial focused on the *mechanics* of idempotent
adoption rather than a consent dialog. The accepted trade-off: a guest who logs into an account that
already has tasks will see their local tasks appear merged in without being asked — acceptable because
nothing is lost or overwritten (guest tasks are new creates; account tasks arrive on pull), and it
matches the offline-first default of never discarding the user's data. *(The previously-recommended
one-time confirmation prompt is therefore **not** built; OQ-1 is closed.)*

### PD-2 — Logout after tasks have been adopted/synced
**Recommendation: keep the current wipe (tasks + outbox + cursor) and land in `Guest` with an empty
local set — do not retain the synced tasks as guest orphans.**
- **Why not retain them:** if logout left the (previously synced) tasks in Room as `serverId == null`
  orphans, the next sign-in would **re-adopt them as brand-new creates** (new `clientRef`s) —
  **duplicating** them on the server. Retaining them *with* their `serverId` is worse across accounts:
  a device could then sign into a *different* account and leak/adopt the first account's tasks. Wiping
  on logout is the only rule that is safe regardless of *which* account signs in next.
- **Why it's acceptable:** the tasks are safely server-owned; the cache repopulates on the next login
  via the full pull. This is unchanged from Feature 11's logout semantics — the *only* change is the
  landing state (`LoggedOut` → `Guest`) so the app stays usable.
- **Consequence to document in the lesson:** guest mode's local-only tasks and a logged-in account's
  tasks never coexist on-device *across* a session boundary — logout starts the guest with a clean
  slate.

### PD-3 — A guest who never creates an account
**Decision: their tasks stay local forever, fully usable, never uploaded, never expired.** This is
already the natural behaviour (Room persists; sync early-returns without a token). No work required
beyond *not* clearing guest data on app start. The guest's outbox mirrors their live task set (creates
minus deletes) and is harmless while it is never pushed.

### PD-4 — One write path (eager outbox) vs. adopt-at-login (lazy outbox) → **RESOLVED: single write path**
**Decision (user, 2026-07-07): keep the single write path — guests use `OutboxTaskRepository`, so
outbox rows accrue eagerly and adoption is just the deferred push.** This is the least code, reuses all
the Feature 11 machinery, and needs no repository swap at the auth boundary. The lesson still teaches
adoption explicitly by narrating *what happens at first sign-in* — the queue that was deferred finally
drains. *(The bare-Room-plus-explicit-adoption-pass alternative is **not** built; OQ-2 is closed.)*

### PD-5 — Content de-duplication on merge?
**Decision: none.** Guest tasks are pushed as new creates; if the account already contains a
lookalike, both survive. Content-based dedup (title/deadline matching) is out of scope — it is
ambiguous (are two "Buy milk" tasks the same?) and beyond this tutorial's teaching goals. Documented as
a known trade-off.

---

## Data Model / Migration Impact

**No new column and no `NeverLateDatabase` version bump are required.**

- A never-synced guest orphan is already fully identified by **`serverId == null`** (with
  `syncState = PENDING_CREATE`). This is the *same* shape a logged-in user's not-yet-pushed create has,
  and that is fine: the outbox/push machinery treats both identically, so there is no need to
  distinguish "guest orphan" from "logged-in pending create" at the row level.
- The `Task` entity already carries `serverId`, `updatedAt`, `syncState`, and `deleted` (added in
  Feature 11). Nothing new is needed on it.
- The database stays at its current version (Feature 11 bumped it to 3). Because we add no column, there
  is **no migration** and therefore none of the destructive-migration data loss the earlier features
  had to accept.

*(If future review decides an explicit "was created as guest" marker is wanted for analytics or UI, it
would be an additive nullable column and a version bump — but it is not needed for correctness and is
deliberately excluded here.)*

---

## Impact on Existing Surfaces

- **Widget (05), lock-screen notification (06), reminders (09):** all read the Room cache (directly or
  via `TaskRepository`/`domain/tasks`), which is populated in every auth state. They therefore work
  unchanged in guest mode. Reminder scheduling (`ReminderPlanning` + `AlarmManagerReminderScheduler`)
  is purely local and never touched sync, so a guest gets reminders exactly like a logged-in user.
- **Navigation (`AppNavHost`):** the only structural change — a `Guest` branch into the existing
  `MainAppNavHost`, plus login/register reachable from Settings. The Feature 11 auth-gate NavHost is
  retained for the `LoggedOut` (involuntary) case.
- **Settings:** gains a "Sign in / Create account" entry when `Guest`, and keeps the existing "Log out"
  when `LoggedIn`.
- **`SyncEngine`:** unchanged. Its existing no-token early-return *is* the guest behaviour.
- **`OutboxTaskRepository`:** unchanged (single write path). It already keys create-vs-update off
  `serverId`, which is exactly what guest adoption relies on.

---

## Backend / Contract Impact

**None — explicitly.** Adoption uses the existing `POST /tasks` (idempotent by `clientRef`) and the
existing `GET /tasks?since=` pull, both already specified in `docs/api/contract.md` (§3). No new
endpoint, no new field, no status-code change, no auth change. The server cannot even tell a guest
adoption apart from an ordinary offline device catching up — which is the point. The API-contract-first
rule therefore does **not** trigger for this feature; do not touch `backend/` or the contract. If review
uncovers an unavoidable server need, that would be a signal to stop and revisit this claim before
writing client code.

---

## Out of Scope

- **Any backend or API-contract change** (see above).
- **Content-based de-duplication / merge UI** for lookalike tasks (PD-5).
- **Cross-account transfer** of tasks (e.g. "move my tasks from account A to account B"). Adoption is
  guest→account only, once.
- **Retaining synced tasks locally through logout** (PD-2): logout wipes and lands in `Guest`.
- **Offline registration/login.** Creating or entering an account still requires connectivity
  (unchanged from Feature 11); only *task usage* is offline-capable.
- **A separate "guest" identity on the server** (anonymous accounts, device tokens). A guest is simply
  a device with no session; nothing represents them server-side until they register/log in.
- **Syncing timer state, articles, or preferences** — unchanged scope from Features 10/11.
- **Migrating a guest's reminders/notifications differently on sign-in** — they are local and already
  keyed by the stable local `id`, which adoption preserves.

---

## Dependencies

- **Feature 11 present and green** (it is): backend, `AuthRepository`/`AuthState`, `TaskRepository`,
  `OutboxTaskRepository`, `SyncEngine`, outbox + `clientRef` + LWW, `EncryptedTokenStorage`.
- **Feature 12 present and green** (it is): access/refresh tokens, `TokenAuthenticator`, and the
  `notifyUnauthorized` failed-refresh fallback that this feature re-points at `LoggedOut`.
- **No new dependencies, modules, or permissions.** Nothing to add to `gradle/libs.versions.toml` or the
  manifest.
- **Product decisions resolved** (PD-1, PD-2, PD-4 at minimum) before implementation, since they change
  observable behaviour and the tutorial narrative.
- **Docs (mandatory before commit):** `tutorial/13-modo-invitado.md` (Spanish), and a `CLAUDE.md` note
  that account is now optional (guest mode) — the "account mandatory" framing from Feature 11 and the
  auth-gate description need updating. Run `/doc-check`.

---

## Risks

- **Duplicate tasks on the server from a mishandled logout.** The central hazard (PD-2): any rule that
  keeps orphan-shaped rows across a session boundary risks re-adoption/duplication. *Mitigation:* wipe
  on logout and land in `Guest`; test "guest → login (adopt) → logout → login again" asserts no
  duplicates server-side.
- **Adoption not firing, or firing before the token is stored.** Adoption relies on the
  `authenticate()` → save-session → flip-state ordering and `MainAppNavHost`'s `LaunchedEffect`.
  *Mitigation:* verify the token is persisted before the state flip; a focused test that a successful
  login with a non-empty guest outbox results in a push. Consider triggering an explicit
  `refreshFromServer()` from the auth flow as a belt-and-braces trigger rather than relying solely on
  recomposition.
- **Cross-account contamination.** A device used by guest → account A → account B must never let A's or
  the guest's tasks leak into B. *Mitigation:* wipe-on-logout (PD-2) guarantees each sign-in starts from
  a clean local cache; test the A→B sequence.
- **Surprising silent merge (UX).** Auto-adopting into an existing account with many tasks can startle
  users. *Accepted trade-off* (PD-1, resolved always-silent): nothing is lost or overwritten — guest
  tasks are new creates and the account's tasks arrive on pull — so the surprise is additive only. The
  lesson calls this out so the behaviour is intentional, not a bug.
- **`Guest` vs `LoggedOut` confusion in navigation.** Getting the state→graph mapping wrong could either
  reinstate the forced gate (defeating the feature) or drop an expired user silently into guest mode
  (losing their server-data view). *Mitigation:* the explicit mapping table above, plus tests for
  cold-start-no-token → `Guest` and failed-refresh → `LoggedOut`.
- **Outbox growth for a long-lived guest.** Harmless (mirrors the live task set), but worth a lesson
  note so it isn't mistaken for a leak.

---

## New Concepts Taught (tutorial)

`tutorial/13-modo-invitado.md` (Spanish), building on lessons 11 (sync) and 12 (tokens):

- **Server-owner-less ("orphan") data coexisting with account data.** Modelling guest tasks as
  `serverId == null` rows and why that single flag is enough to distinguish never-synced data — no new
  schema needed.
- **Data migration / merge at login via idempotent creates.** How adoption reuses the outbox +
  `clientRef` idempotency from lesson 11: the guest's deferred creates are pushed into the account with
  no loss and no duplicates, then reconciled against existing account data by last-write-wins — and how
  little new code this takes when the sync engine is designed with the right seams.
- **A three-faced auth state.** Extending a sealed interface (`LoggedOut` / `LoggedIn`) with a third
  case (`Guest`), and how reactive navigation maps each state to a graph — plus *why* `Guest` (chosen,
  usable) and `LoggedOut` (involuntary, gated) are kept distinct.
- **Product trade-offs around merge.** Silent adoption vs. a confirmation prompt; why logout must wipe
  (duplicate/leak avoidance) rather than retain; and why there is no content de-duplication — reasoning
  about data-loss and duplication risks, not just writing code.

---

## Open Questions — all resolved (2026-07-07)

- **OQ-1 — Confirm-merge UX (PD-1). → CLOSED: always-silent adoption.** No confirmation prompt is built.
- **OQ-2 — Eager outbox vs. explicit adoption pass (PD-4). → CLOSED: single write path** (outbox accrues
  during guest mode; adoption is the deferred push).
- **Guest vs. LoggedOut states → CONFIRMED distinct** (chosen/usable vs. involuntary/gated), per the
  mapping table in *Technical Approach §1*.
- **Logout after adoption (PD-2) → CONFIRMED: wipe + land in `Guest`.**

---

## Approval

**Approved 2026-07-07.** Product decisions locked in: always-silent adoption (PD-1/OQ-1), single write
path with the eager outbox (PD-4/OQ-2), wipe-on-logout landing in `Guest` (PD-2), and distinct
`Guest`/`LoggedOut` states. The flow now follows CLAUDE.md's Mandatory Workflow: create
`feature/guest-mode`, implement with `mobile-engineer` (three-faced auth state, navigation, logout
target, adoption trigger), add tests with `qa-engineer` (adoption without duplicates, logout→re-login no
duplicates, cross-account isolation, cold-start → `Guest`, failed-refresh → `LoggedOut`), and write
`tutorial/13-modo-invitado.md` (Spanish) before committing. No backend or contract change is expected.
