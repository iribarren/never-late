# Feature — Really alert the user when a task's time runs out

- **Status:** Approved
- **Date:** 2026-08-17
- **Branch (suggested):** `feature/times-up-alert`
- **Original title (user's words):** *"Avisar de verdad cuando se agota el tiempo de una tarea"*
- **Type:** Behaviour change on `app/` only. **No** backend change, **no** API contract change,
  **no** Room schema change or migration, **no** new permission, **no** new dependency,
  **no** new screen, **no** new string resource.
- **Tutorial:** **Sí** — Spanish lesson, confirmed by the user via `AskUserQuestion`
  ("Sí, con lección"). It is **not written now**: per `CLAUDE.md` → *Tutorial Track (optional)*, the
  lesson is authored **after implementation, before committing**. Suggested number **`09b`**
  (interleaved right behind `tutorial/09-*.md`, which taught `AlarmManager`, `BroadcastReceiver` and
  the reminder channel — this feature is that same thread, one level harder). **No shipped lesson is
  ever renumbered**; the exact number is confirmed against `tutorial/README.md` +
  `docs/conceptos-pendientes.md` at writing time. What the lesson will cover is listed in
  [Tutorial lesson — what `09b` must teach](#tutorial-lesson--what-09b-must-teach).

---

## Overview

Today, when a task's countdown reaches zero, **nothing happens**. No sound, no vibration, no
notification. The only code that reacts is `TasksViewModel.autoPauseTimedOut`:

```kotlin
// app/src/main/java/com/neverlate/ui/tasks/TasksViewModel.kt:250
private fun autoPauseTimedOut(uiTasks: List<TaskUiModel>) {
    uiTasks.filter { it.task.isRunning && it.remainingMillis == 0L }
        .forEach { pauseTimer(it.task.id) }
}
```

That is a *housekeeping* reaction (freeze the countdown at zero so the UI never shows a negative
number), and it only runs **while the Tasks screen is composed** — i.e. only when the user is
already looking at the app, which is precisely the case where they need no alert.

Two consequences:

1. **A task whose deadline arrives while the app is closed passes in silence.** Feature 09 gives us
   a *lead-time* reminder ("in 10 minutes your deadline"), and then never speaks again. The moment
   that actually matters — zero — is unannounced.
2. **A duration-only task never gets any alarm at all.** `reminderTimeFor` derives from
   `task.deadline`:

   ```kotlin
   // app/src/main/java/com/neverlate/domain/tasks/ReminderPlanning.kt:27
   fun reminderTimeFor(task: Task, leadMillis: Long): Long? =
       task.deadline?.let { deadline -> deadline - leadMillis }
   ```

   A task with `estimatedDurationMillis` and no `deadline` returns `null` — no reminder, and no
   time-up alert either. "Focus on this for 25 minutes" is a first-class use of this app and it
   currently ends with silence.

For an app whose whole purpose is helping people with ADD/ADHD manage time, this is the most
expensive functional gap left: the product measures time carefully and then fails to tell anyone
when it is up.

This feature adds a **real alert at zero** — heads-up notification with sound and vibration on the
existing alerting channel (`task_reminders`, `IMPORTANCE_HIGH`) — for both paths by which a task
runs out of time (a `deadline` arriving, or a running timer's `timerEndsAt` arriving), surviving a
reboot, and cancelled the moment the task is completed, deleted or paused.

### Extend, don't duplicate

Almost all the machinery already exists and is reused **as is**:

| Existing piece | Reused for |
|---|---|
| `AlarmManagerReminderScheduler` (+ its `canScheduleExactAlarms()` degradation) | scheduling the time-up alarm |
| `ReminderReceiver` (+ its `goAsync()` recipe) | delivering it with the app process not even started |
| `ReminderNotificationHelper` | building the notification |
| Channel `task_reminders` (`IMPORTANCE_HIGH`) vs. silent `tasks_pending` | sound + vibration + heads-up |
| `BootReceiver` → `BootRescheduleWorker` | surviving a reboot |
| `computeRemainingMillis` (`data/tasks/TaskTiming.kt`) | the countdown semantics this feature must agree with |
| `R.string.tasks_time_up` (already in `values/` **and** `values-en/`) | the notification body |

The genuinely missing piece is small and pure: **a function that answers "at what wall-clock instant
does this task run out of time?"** — and two places that currently forget to ask it
(`startTimer`/`pauseTimer`). Plus one latent bug that must be fixed *before* any of this can work
safely: see [D1](#d1--pendingintent-identity-namespaced-request-codes--distinct-intent-actions).

---

## Goals

1. When a task's time runs out, the user is **told** — audibly — whether or not the app is open.
2. Duration-only tasks (no deadline) get an alert when their timer finishes. Today's silence ends.
3. The alert is **never wrong**: not retroactive, not about a completed task, not about a paused
   timer, not duplicated.
4. Adding a second alarm per task does not break the first one (the lead-time reminder from
   feature 09) — proven, not assumed.
5. All of the decision logic stays a pure JVM-testable function in `domain/tasks/`; the Android
   classes stay thin shells, as feature 09 established.

---

## Decisions (locked — do not re-litigate during implementation)

### D1 — PendingIntent identity: namespaced request codes **and** distinct Intent actions

**This is the bug that must be fixed first.** Today:

```kotlin
// app/src/main/java/com/neverlate/ui/notification/ReminderScheduler.kt:94
fun requestCodeFor(taskId: Long): Int = taskId.toInt()
```

and `ReminderNotificationHelper.notificationIdFor(taskId) = requestCodeFor(taskId)` — one task, one
`Int`, shared by the alarm's `PendingIntent` **and** the notification id. A `PendingIntent`'s
identity is its target component + request code + the `Intent`'s `filterEquals` fields (action, data,
type, categories, component). **Extras are explicitly not part of it.** So a second alarm for the
same task built the way the current code builds them — same request code, same action (none), same
extras-only difference — would be considered *the same* `PendingIntent`, and
`FLAG_UPDATE_CURRENT` would **silently overwrite the lead-time reminder** with the time-up one.
No crash, no log, one alarm quietly gone.

**Decision — change the scheme, in two independent ways at once (belt and braces):**

1. Introduce an alarm-kind enum in `ui/notification/`:

   ```kotlin
   enum class ReminderKind(val slot: Int, val action: String) {
       LEAD_TIME(0, "com.neverlate.action.LEAD_TIME_REMINDER"),
       TIME_UP(1, "com.neverlate.action.TIME_UP"),
   }
   ```

2. Namespace the request code by kind:

   ```kotlin
   fun requestCodeFor(taskId: Long, kind: ReminderKind): Int = taskId.toInt() * 2 + kind.slot
   ```

3. Also set `intent.action = kind.action` on the alarm `Intent`. This makes the two `PendingIntent`s
   distinct **even if** the arithmetic above were ever wrong, and gives `ReminderReceiver` a
   first-class way to branch (`intent.action`) instead of inferring intent from extras.

**The scheme applies consistently in all four places, or only half of it works:**

- `AlarmManagerReminderScheduler.schedule` / `cancel` → take a `ReminderKind`; the interface
  `ReminderScheduler` becomes `schedule(taskId, kind, triggerAtMillis)` / `cancel(taskId, kind)`.
- `ReminderNotificationHelper.notificationIdFor(taskId, kind)` → see D2.
- `ReminderSchedulingRepository` → passes the right kind on every write path.
- `BootRescheduleWorker` → reschedules **both** kinds (otherwise only half the alarms survive a
  reboot, which is exactly the kind of half-fix that looks fine in a demo).

The single-argument `requestCodeFor(taskId)` overload is **removed**, not kept as a default — a
default would let a call site silently land in the `LEAD_TIME` namespace. The compiler must force
every call site to state which alarm it means.

### D2 — Notification ids get a base offset (closes a latent collision)

`notificationIdFor` currently equals `requestCodeFor`, i.e. raw `taskId`. `TASKS_NOTIFICATION_ID` is
`1001`, so a task with id 1001 already collides today with the persistent pending-tasks
notification, and `id * 2 (+1)` would make task 500 and 501 collide with it too.

**Decision:**

```kotlin
private const val REMINDER_NOTIFICATION_ID_BASE = 10_000
fun notificationIdFor(taskId: Long, kind: ReminderKind): Int =
    REMINDER_NOTIFICATION_ID_BASE + requestCodeFor(taskId, kind)
```

Notification ids are ephemeral (they only identify a *currently posted* notification), so changing
them is safe with no migration. This is a deliberate small bonus fix, called out here so it is not
mistaken for accidental scope creep.

### D3 — One time-up alarm per task, at the **earliest** applicable instant

A task can have both a `deadline` and a running timer, so two "time is up" instants can exist. Two
notifications landing a second apart would be noise, and "which one won" would be undefined.

**Decision: exactly one `TIME_UP` alarm slot per task, fired at `min(timerEndsAt, deadline)` over
whichever of the two is non-null.**

Rationale — and note this is *usually a no-op*, because `RoomTaskRepository.startTimer` sets
`timerEndsAt = now + computeRemainingMillis(task, now)`, and for a never-started task with a
deadline that is exactly the deadline. The two instants only diverge when a task with a deadline was
paused and later resumed (its frozen `remainingMillis` can push `timerEndsAt` past the deadline). In
that case the **deadline is the earlier and the truer one** — it is the real commitment; the
approved countdown rule (2026-07-02, see `TaskTiming.kt`) already says a deadline dominates the
estimated duration. `min` gets that right for free.

### D4 — The alert respects the existing `remindersEnabled` switch; lead time never applies to it

**Decision:** the Settings "reminders" master switch (`UserPreferences.remindersEnabled`) governs
**both** kinds. Someone who turned reminders off does not want their phone to make noise.
`reminderLeadMinutes` is **only** for `LEAD_TIME` — a time-up alert fires *at* zero, by definition,
and no lead time is subtracted.

No new preference is added. A **separate** on/off switch for time-up alerts (so a user could keep
one and drop the other) is deferred — see [Out of Scope](#out-of-scope).

### D5 — Precision: exact is the target, and the tolerance here is seconds, not minutes

A lead-time reminder arriving 3 minutes late is still useful ("your deadline is soon"). A *time's up*
alert arriving 3 minutes late is a different, worse thing: the user has already been late for three
minutes and the app knew.

**Decision:**

- The time-up alarm always attempts `setExactAndAllowWhileIdle(RTC_WAKEUP, …)`, and degrades to
  `setAndAllowWhileIdle` when `canScheduleExactAlarms()` is false — i.e. **exactly** the existing
  `AlarmManagerReminderScheduler` behaviour from feature 09, unchanged and un-branched. No new
  permission, no new code path.
- **Accepted tolerance (verifiable AC):** on a device where exact alarms are permitted, the
  notification appears within **5 seconds** of the instant, including while the device is idle
  (that is what `AllowWhileIdle` buys). With exact alarms denied by the user or the system, delivery
  is best-effort and may be batched by the OS by minutes — a stated, accepted degradation, not a
  bug.
- **Lateness can never make the text wrong.** The body is `R.string.tasks_time_up`
  ("Tiempo agotado" / "Time's up"), which is timeless — unlike the lead-time reminder's
  "in N minutes" plurals, it cannot become false by arriving late. This is a deliberate reason to
  reuse that string rather than invent a countdown-flavoured one.
- A late alert is **still delivered** (the user needs to know), but a *stale* one is not — see D8.

### D6 — Duration-only, paused, and never-started tasks

`computeRemainingMillis` already defines what "remaining" means in each state. The time-up *instant*
is a stricter question: an instant only exists if something is actually counting down against the
wall clock.

| Task state | Time-up instant | Why |
|---|---|---|
| Running timer (`timerEndsAt != null`), no deadline | `timerEndsAt` | **This is the gap this feature closes.** |
| Running timer + deadline | `min(timerEndsAt, deadline)` | D3 |
| Paused / never started, has deadline | `deadline` | The deadline arrives whether or not a timer runs |
| Paused / never started, duration-only | **none** (`null`) | Nothing is counting down; there is no wall-clock instant to alarm on |
| Completed (`completedAt != null`) or `deleted` | **none** (`null`) | Never alert about something already handled |
| Resulting instant already `<= now` | **none** | D7 |

### D7 — Never retroactive

A task whose time was already up before this feature existed (or before the app update installed)
**must not** be alerted about. This falls out of reusing the existing purity rule
`isReminderInFuture(triggerAt, now)` (`ReminderPlanning.kt:36`, approved as OQ-6 in feature 09): a
non-future instant is simply never scheduled. Nothing fires at install time, nothing fires at boot
for a task that expired last week.

### D8 — Suppression at delivery, not only at scheduling

An alarm can outlive the reason it was set (the task got completed, deleted, paused or extended in
the window between scheduling and firing; or the process was killed before a cancel could run).
Cancellation at write time (D9) is the primary mechanism; a delivery-time guard is the safety net,
following the precedent already in `ReminderReceiver` (`if (task.deadline == null) return`).

**Decision — `ReminderReceiver` drops a `TIME_UP` broadcast when, re-reading the task fresh from
Room:**

- the task no longer exists, or `deleted == true`, or
- `completedAt != null`, or
- `computeRemainingMillis(task, now) > STALE_TOLERANCE_MILLIS` (**60 000 ms**) — i.e. the countdown
  has visibly more than a minute left, so this alarm belongs to a superseded plan (timer restarted,
  deadline pushed back).

The same "task gone / completed" guard is applied to `LEAD_TIME` too, since it is the same fix
(see D9) and skipping it would leave a known-wrong alert in place.

### D9 — Completion and pausing cancel the alert (this changes existing behaviour too)

`ReminderSchedulingRepository.reschedule` currently schedules a lead-time reminder regardless of
`completedAt`, so **today** a completed-but-not-yet-due task still gets reminded. That contradicts
"never alert about something already handled".

**Decision:** the rescheduling rule for *both* kinds becomes *cancel first, then schedule only if
still warranted*, where "warranted" now includes `completedAt == null && !deleted`. This fixes the
existing lead-time behaviour as part of this feature; it is stated here explicitly so approval covers
the change.

### D10 — The reschedule worker is also enqueued on cold start (name unchanged)

`BootRescheduleWorker` only runs after `BOOT_COMPLETED`. That leaves a real hole: **installing the
app update that ships this feature schedules no time-up alarms at all** until each task is next
edited or the phone reboots. The same hole exists after a force-stop or an app-standby bucket
demotion, both of which can clear alarms.

**Decision:** enqueue the very same one-shot worker on cold start (from `NeverLateApplication`).
It is idempotent — scheduling replaces a task's alarm rather than stacking one (that is what
`FLAG_UPDATE_CURRENT` plus a stable request code is for), and past-due tasks are dropped by D7.

**Considered and rejected:** renaming the class to `RescheduleAlarmsWorker`. `BootRescheduleWorker`
is named in the shipped Spanish lesson `tutorial/09-*.md`, and a rename would silently make a
published lesson wrong for no functional gain. Its KDoc is updated instead to state that boot is now
one of two triggers.

### D11 — No third notification channel

The time-up alert posts to the **existing** `task_reminders` channel. A channel's importance is
frozen by Android after first creation, and a third channel would fragment the user's control for no
benefit. **Accepted trade-off:** the user cannot silence lead-time reminders separately from time-up
alerts. Splitting them is deferred (see Out of Scope) and only makes sense together with D4's
separate switch.

---

## User Stories

### US-1 — I am told when my deadline arrives

> As someone with ADHD who loses track of time, I want my phone to make a noise the moment a task's
> deadline arrives, so that I notice *at* the moment it matters, not an hour later.

**Acceptance criteria**

- Given a pending task with a deadline in the future and reminders enabled, when the deadline
  instant arrives with the app **closed**, then a heads-up notification appears on the
  `task_reminders` channel with sound and vibration.
- The notification title is the task title; the body is `R.string.tasks_time_up`.
- This is **in addition to** the feature-09 lead-time reminder: with a 10-minute lead time, the user
  receives **two** notifications (T−10min and T), both still present and both distinguishable.
- Tapping the notification opens the app on the Tasks list; the notification then dismisses itself
  (`setAutoCancel(true)`).

### US-2 — My duration-only focus timer tells me when it is done

> As someone using the app as a focus timer, I want an alert when the 25 minutes I started are over,
> so that a duration-only task is as useful as one with a deadline.

**Acceptance criteria**

- Given a task with `estimatedDurationMillis` and **no** `deadline`, when the user presses play, then
  a `TIME_UP` alarm is scheduled for the resulting `timerEndsAt`.
- When that instant arrives with the app closed, the alert appears exactly as in US-1.
- Given the same task **never started** (or paused), then **no** `TIME_UP` alarm exists — nothing is
  counting down (D6).

### US-3 — Pausing, completing or deleting silences it

> As a user who finished early, I want the app to stop warning me about a task I already handled, so
> that I keep trusting its alerts.

**Acceptance criteria**

- Pressing pause on a running timer **cancels** that task's `TIME_UP` alarm.
- Pressing play again **reschedules** it against the new `timerEndsAt`.
- Marking a task complete cancels **both** its `TIME_UP` and its `LEAD_TIME` alarms (D9).
- Deleting a task cancels both.
- Turning the reminders switch off in Settings cancels both for **every** task.
- If a task is completed in the window between scheduling and firing, no notification is shown
  (D8's delivery-time guard).
- Un-completing a task (the existing undo path in `toggleComplete`) re-schedules whichever alarms are
  still in the future.

### US-4 — The alert survives a reboot and an app update

> As a user whose phone restarts overnight, I want tomorrow's alerts to still exist, so that a
> reboot is not a silent data-loss event.

**Acceptance criteria**

- After `BOOT_COMPLETED`, every still-future alarm of **both** kinds is rescheduled.
- After the app process is cold-started (including right after installing this update), the same
  reconciliation runs (D10).
- A task whose time-up instant already passed is **not** rescheduled and **not** alerted about
  (D7) — verified explicitly, since a retroactive burst of alerts on update would be the worst
  possible first impression of this feature.
- With reminders disabled, boot and cold start schedule nothing.

### US-5 — Adding a second alarm does not break the first

> As a maintainer, I want the two alarms per task to be provably independent, so that we do not ship
> a feature that silently deletes feature 09.

**Acceptance criteria**

- `requestCodeFor(taskId, LEAD_TIME) != requestCodeFor(taskId, TIME_UP)` for every task id, and no
  two `(taskId, kind)` pairs share a request code (unit-tested over a range of ids).
- The two alarm `Intent`s carry different `action`s (D1).
- No reminder notification id equals `TASKS_NOTIFICATION_ID` (1001) for any task id (D2).
- Scheduling a task's `TIME_UP` alarm leaves its `LEAD_TIME` alarm intact, and vice versa — asserted
  against the fake scheduler, which records `(taskId, kind)` pairs.

---

## Acceptance Criteria (consolidated)

**Behavioural**

1. A pure function in `domain/tasks/` answers "when does this task run out of time?" for all six
   states in D6's table, and is covered by JVM unit tests with **no** Android dependency.
2. One `TIME_UP` alarm per task, at `min(timerEndsAt, deadline)` over the non-null candidates (D3).
3. Duration-only + running timer produces an alert (the gap in the Overview is closed).
4. `startTimer` and `pauseTimer` in `ReminderSchedulingRepository` are no longer pass-throughs: play
   reschedules, pause cancels.
5. Alarm identity is namespaced by kind across scheduler, notification id, repository decorator,
   Settings cancel-all **and** `BootRescheduleWorker` (D1/D2).
6. No retroactive alert, ever (D7).
7. Delivery-time suppression for deleted / completed / stale alarms (D8).
8. Both kinds cancelled on completion and deletion (D9).
9. Boot **and** cold start reconcile alarms (D10).
10. `remindersEnabled == false` means total silence for both kinds (D4).
11. Exact alarms attempted, inexact fallback preserved; no crash when
    `canScheduleExactAlarms()` is false (D5).
12. `./gradlew :app:testDebugUnitTest` is green.

**Definition of Done items this feature touches**

13. **Migrations:** none needed — `timerEndsAt`, `remainingMillis`, `deadline` and `completedAt`
    already persist. The database stays at version 6. *(If implementation discovers a schema need,
    stop and revise this spec — do not improvise a migration.)*
14. **Contract:** `docs/api/contract.md` is **unchanged** — this is entirely local, wall-clock,
    on-device behaviour, and `timerEndsAt`/`remainingMillis` are explicitly not synced.
15. **i18n:** **no new string resource.** `tasks_time_up` already exists in `values/strings.xml:73`
    (Spanish base) and `values-en/strings.xml:78`. Both files stay in sync because neither changes.
16. **Security:** no new permission (`SCHEDULE_EXACT_ALARM`, `RECEIVE_BOOT_COMPLETED`,
    `POST_NOTIFICATIONS` already declared); no manifest change beyond none; `ReminderReceiver` stays
    `android:exported="false"`.
17. **Every state designed:** the notification's own states are covered — notifications denied
    (nothing posted, no crash, existing `areNotificationsEnabled()` guard), task vanished
    (nothing posted), exact alarms denied (degraded delivery).
18. **Docs:** `docs/mockups/README.md` gains the row in
    [Visual & UX Design](#visual--ux-design); `docs/arquitectura.md` records the alarm-identity
    decision (D1) as the durable "why"; `CLAUDE.md`'s module map note for `ui/notification/` is
    updated to mention the two alarm kinds.

**Visual** — see the next section.

---

## Visual & UX Design

There is **no new screen**, but there *is* a new visible surface: a notification. It is designed
here, not improvised.

### Mockup slice

**None.** The master mockup [`docs/mockups/rediseno-ux-ui.html`](../mockups/rediseno-ux-ui.html) is
phone-**app** only — it contains no notification shade, no lock screen and no system surface at all.
This feature therefore claims **no mockup slice** and defers **no** app-screen polish: it changes
nothing inside the app's UI. A row is added to
[`docs/mockups/README.md`](../mockups/README.md) marked **`—`** ("not a mockup slice — net-new
surface, tracked for context"), following the precedent of the widget and stats rows.

Proposed row (added in the Design review step):

| Mockup element / screen | Owning feature | Status | Notes |
|---|---|---|---|
| Time-up alert notification | *times-up-alert* | — | **Not in the master mockup** (phone app only — no notification shade or lock screen). Net-new system surface. Reuses the feature-09 `task_reminders` alerting channel and `ReminderNotificationHelper`'s existing treatment (small icon, task title, `PRIORITY_HIGH`, `VISIBILITY_PUBLIC`, `setAutoCancel`, tap → Tasks list); body is the existing `tasks_time_up` string. No new channel (D11), no notification actions (deferred). No mockup slice claimed. |

### Notification appearance

| Element | Value | Reused from |
|---|---|---|
| Channel | `task_reminders` (`IMPORTANCE_HIGH` → heads-up + sound + vibration) | feature 09, unchanged (D11) |
| Small icon | `R.drawable.ic_launcher` | `ReminderNotificationHelper` |
| Title | the task title | same as the lead-time reminder |
| Body | `R.string.tasks_time_up` — "Tiempo agotado" / "Time's up" | existing string, already localized |
| Pre-API-26 priority | `PRIORITY_HIGH` | `ReminderNotificationHelper` |
| Lock screen | `VISIBILITY_PUBLIC` (title visible — approved as OQ-5 in feature 09) | same |
| Category | `CATEGORY_REMINDER` | new but token-level: lets the OS classify it correctly under DND |
| Auto-cancel | `true` — a one-shot alert should vanish once seen | same |
| Tap target | `PendingIntent` → `MainActivity` with `EXTRA_OPEN_TASKS` | the one recipe shared by notification, widget and reminder |
| Actions | **none** (deferred, see Out of Scope) | — |

### Relationship to the persistent pending-tasks notification

The two must not collide, visually or technically:

- **Different channel:** `tasks_pending` is deliberately silent and ongoing (a status summary);
  `task_reminders` is a one-shot alert. Unchanged by this feature.
- **Different ids:** `TASKS_NOTIFICATION_ID` = 1001; time-up alerts live at
  `10 000 + taskId*2 + 1` (D2), which also removes the pre-existing collision risk.
- **They coexist on purpose:** the persistent summary keeps showing the pending list (a completed
  or timed-out task is already excluded from it, per the fix in commit `7a817c6`), while the alert
  sits above it as a separate, dismissible item. Neither cancels the other.
- **No new noise:** the persistent notification's `setOnlyAlertOnce(true)` behaviour is untouched;
  the only thing that ever makes a sound is the alerting channel.

### Visual acceptance criteria

- **VA-1** With the app closed and the screen off, the alert wakes the screen as a heads-up
  notification with the channel's default sound **and** vibration.
- **VA-2** The notification shows the task's **own title** (not a generic "Never Late" title), so a
  user with three timers knows which one ended.
- **VA-3** The body text is the localized `tasks_time_up` — Spanish on a Spanish device, English on
  an English one — verified on both locales.
- **VA-4** At the largest system font scale the notification renders without truncating the body
  (title may ellipsize, as Android does for any notification).
- **VA-5** Tapping it opens the Tasks list and the notification disappears; swiping it away leaves
  the persistent pending-tasks notification intact.
- **VA-6** With a 10-minute lead time, the lead-time reminder and the time-up alert appear as **two
  separate notifications**, both readable, neither replacing the other (this is D1's bug, made
  visually checkable).
- **VA-7** No visible change anywhere inside the app: Tasks list, card, countdown, progress bar,
  widget and Settings are pixel-identical before and after.

### Tokens and components reused

No new UI component and no new styling: the notification is built by the existing
`ReminderNotificationHelper` with the existing channel and the existing string resource. Notification
chrome is drawn by the system, so `ui/theme/` tokens do not apply — the deliberate consequence being
that there is nothing here to keep in sync with `NeverLateExtras`.

---

## Technical Approach

All changes are in `app/`. Ordered so that the identity fix (D1) lands before anything depends on it.

### 1. `ReminderKind` (new, `ui/notification/`)

The enum from D1: `slot` (request-code namespace) + `action` (Intent action). Small, and it is the
type that makes every other signature in this feature honest.

### 2. `ReminderScheduler` / `AlarmManagerReminderScheduler` — kind-aware

- `schedule(taskId: Long, kind: ReminderKind, triggerAtMillis: Long)`, `cancel(taskId, kind)`.
- `buildPendingIntent(taskId, kind)` sets `intent.action = kind.action` and uses
  `requestCodeFor(taskId, kind)`.
- `requestCodeFor(taskId)` (single-arg) **deleted** — the compiler must force each call site to
  choose (D1).
- The exact/inexact branch is untouched (D5).

### 3. `domain/tasks/` — the pure function

A new file `TimeUpPlanning.kt` next to `ReminderPlanning.kt` (kept separate so each file reads as one
idea, matching how `ColorRole.kt` and `RemainingTime.kt` are split):

```kotlin
/** The wall-clock instant at which [task] runs out of time, or null if none exists (see D6). */
fun timeUpInstantFor(task: Task): Long?

/** Every time-up alert to (re)schedule right now — the boot/cold-start counterpart. */
fun timeUpAlertsToSchedule(tasks: List<Task>, now: Long): List<ReminderPlan>
```

`ReminderPlan` and `isReminderInFuture` are **reused**, not duplicated. `timeUpInstantFor` takes no
clock (it is a property of the task) and the future-check stays the caller's, exactly as
`reminderTimeFor` + `isReminderInFuture` are already split.

`remindersToSchedule` gains the same `completedAt == null && !deleted` filter as D9 requires.

### 4. `ReminderSchedulingRepository` — the two forgotten methods

This is **the easy-to-forget part of the feature: it has no UI surface at all.** Nothing on screen
changes when it is wrong; the alert simply never fires, or fires about a paused task.

```kotlin
override suspend fun startTimer(id: Long) {
    delegate.startTimer(id)          // writes the new timerEndsAt
    rescheduleTimeUp(id)             // must re-read the task: the instant changed underneath us
}

override suspend fun pauseTimer(id: Long) {
    delegate.pauseTimer(id)
    rescheduleTimeUp(id)             // re-read yields timerEndsAt == null → cancel only
}
```

`rescheduleTimeUp` re-reads the task via `delegate.observeTask(id).first()` (the recipe the class
already uses for preferences), then: always `cancel(id, TIME_UP)` first, then schedule only if
reminders are enabled and `timeUpInstantFor(task)` is non-null and in the future. `saveTask` and
`deleteTask` do the same for **both** kinds. The "always cancel, then maybe schedule" shape that
feature 09 established is kept verbatim — it is what removes any create-vs-update branching.

**Why here and not in the ViewModel:** the repository decorator is the only place *every* write
passes through — the Tasks screen, the widget's future write paths, the sync engine's reconciliation,
a background worker. A `TasksViewModel` reschedule would cover exactly one caller (the one case where
the user is already looking at the app and needs the alert least) and would silently miss the rest.
This is also the reason `ui/widget/WidgetEntryPoint` deliberately exposes the `@ReminderRepo` layer:
the decorator chain's *order* is the design.

### 5. `ReminderReceiver` — branch on action, guard on delivery

- Read `intent.action` → `ReminderKind`; unknown/absent action → return (defensive; also keeps old
  in-flight alarms from a previous install harmless).
- Keep the existing `areNotificationsEnabled()` guard and the `goAsync()` structure untouched.
- Apply D8's guards, then post with `notificationIdFor(taskId, kind)`.

### 6. `ReminderNotificationHelper` — a second builder

`buildTimeUpNotification(context, task)` alongside the existing `buildNotification`. It needs no
`locale` and no `now`: its body is a fixed localized string (D5's point about timeless text). Shared
chrome (icon, priority, visibility, auto-cancel, content intent) is factored into one private
builder so the two notifications cannot drift apart.

### 7. `BootRescheduleWorker` + `NeverLateApplication`

- Schedule both kinds: `remindersToSchedule(...)` with `LEAD_TIME`, `timeUpAlertsToSchedule(...)`
  with `TIME_UP`.
- `NeverLateApplication.onCreate` enqueues the worker once (D10). Name unchanged; KDoc updated.

### 8. `SettingsViewModel.onRemindersEnabledChanged`

The cancel-all loop must cancel **both** kinds per task, or turning reminders off would leave every
time-up alarm armed — the most user-visible possible version of the half-fix D1 warns about.

---

## Out of Scope

Deliberately **not** in this feature (candidates for `docs/diferidos.md`):

- **A "Mark done" / "Snooze" notification action.** Genuinely valuable for this audience, and
  deliberately separate: it needs a new receiver, a repository *write* from a broadcast receiver
  (with the decorator-reentrancy care the `@ReminderRepo` qualifier exists for), new strings, and its
  own tests. Bundling it would double this feature and blur what D1's fix is being verified against.
- **A separate on/off switch (and/or channel) for time-up alerts**, distinct from lead-time
  reminders — see D4 and D11. Only worth doing together, and only if users ask.
- **Full-screen / alarm-clock-style alert** (`setFullScreenIntent`, `USE_FULL_SCREEN_INTENT`,
  looping alarm sound). A notification is the right first step; escalating is a separate product
  decision with a permission cost.
- **Prompting the user to grant exact alarms** (`ACTION_REQUEST_SCHEDULE_EXACT_ALARM` from Settings)
  when `canScheduleExactAlarms()` is false. This feature keeps feature 09's silent degradation; the
  Settings affordance is its own small feature.
- **Repeated / escalating reminders after zero** ("still not done, 5 minutes later").
- **Per-task reminder settings** (feature 09's OQ-4 decided one global lead time; unchanged).
- **Any backend or contract work.** Time-up alerts are local. `timerEndsAt`/`remainingMillis` remain
  unsynced by design, which is also why an alert cannot fire twice on two devices.
- **Any Room schema change**, and therefore any migration.
- **Any change to the persistent notification, the widget, or in-app UI.**

---

## Dependencies

Everything this feature needs already exists — there is **no blocking dependency**:

- Feature 09's alarm/notification stack (`ReminderScheduler`, `ReminderReceiver`,
  `ReminderNotificationHelper`, `BootReceiver`/`BootRescheduleWorker`, channel `task_reminders`).
- Feature 04's countdown persistence (`timerEndsAt`, `remainingMillis`) and `computeRemainingMillis`.
- Feature 04c's `completedAt` (needed by D9).
- Declared permissions: `SCHEDULE_EXACT_ALARM`, `RECEIVE_BOOT_COMPLETED`, `POST_NOTIFICATIONS`.
- `R.string.tasks_time_up` in both `values/` and `values-en/`.
- Hilt wiring (`RepositoryModule` binds `ReminderScheduler`); signature changes in D1 ripple into
  `SettingsViewModel` and the decorator, both already injected.

**Implementation notes for the agents that will do the work** (per `CLAUDE.md`'s New Feature
Workflow — not invoked from this spec):

- `mobile-engineer`: the pure instant function, the `ReminderKind` identity scheme applied to all
  five call sites, `startTimer`/`pauseTimer` rescheduling, boot + cold-start reconciliation.
- `qa-engineer`: exhaustive JVM tests of `timeUpInstantFor` across D6's six states and their
  boundaries (instant exactly `== now`, `timerEndsAt` before/after/equal to `deadline`, completed,
  deleted, both fields null); a request-code/notification-id distinctness test (US-5); decorator
  tests per write path. **Note:** `FakeReminderScheduler.Call` must grow a `kind` field, and
  `FakeTaskRepository.startTimer`/`pauseTimer` currently only *record* the call without mutating
  `timerEndsAt` — they must actually apply `computeRemainingMillis`, or the decorator's re-read will
  see a stale task and the tests will pass for the wrong reason. Manual verification is required for
  what unit tests cannot prove: the alert sounds with the app closed, it survives a reboot, and it
  does **not** arrive when the task is completed first.

---

## Risks

| # | Risk | Mitigation |
|---|---|---|
| R1 | **The identity fix is done by halves** — new scheme in the scheduler but not in `BootRescheduleWorker` or the Settings cancel-all. Symptom: alarms work until a reboot, or survive being switched off. | D1 lists all five call sites; deleting the single-arg `requestCodeFor` makes the compiler enforce it; AC-5 and US-5 test it. |
| R2 | **`startTimer`/`pauseTimer` are silently wrong** — no UI surface reveals it. | Explicitly called out (§4); decorator unit tests per path; the fake repository fix noted above, without which those tests are vacuous. |
| R3 | **Retroactive alert burst on update install** — worst possible first impression. | D7 + AC-6, tested with a past-due task at boot/cold start. |
| R4 | **Duplicate/near-simultaneous alerts** for a task with both a deadline and a running timer. | D3: one slot, `min(...)`. |
| R5 | **Exact alarms denied** → a late "time's up" (the tolerance in D5 cannot be met). | Existing graceful degradation; timeless body text (D5); a Settings prompt is deferred, not forgotten. |
| R6 | **OEM battery managers / force-stop clear alarms** and no boot broadcast follows. | D10's cold-start reconciliation covers a large share; full coverage is impossible on Android and is accepted, not hidden. |
| R7 | **Notification fatigue** — two notifications per task (T−10min and T) may feel like too much for some users. | Both are opt-out via the existing switch; the separate switch/channel (D4/D11) is the escape hatch if feedback asks for it. |
| R8 | **Room id → `Int` narrowing** in `requestCodeFor` now multiplied by 2, halving the safe id range. | Still ~1.07 billion task ids; the existing KDoc's "never collides within this app's practical lifetime" reasoning holds and is updated to state the new bound. |
| R9 | **Channel importance is frozen** for users who already have `task_reminders` — if it was ever created wrong, time-up alerts inherit that. | Not regressed by this feature (same channel, same code path); worth knowing when reading bug reports. |

---

## Tutorial lesson — what `09b` must teach

Written **after** implementation, in Spanish, per `CLAUDE.md`. Suggested number `09b`; confirmed at
writing time against `tutorial/README.md` + `docs/conceptos-pendientes.md`; **no shipped lesson is
renumbered**. The three concepts, in the order the code meets them:

1. **`PendingIntent` identity.** Why two alarms for the same task silently clobber each other when
   they share a `requestCode` under `FLAG_UPDATE_CURRENT`, and what *actually* distinguishes one
   `PendingIntent` from another: request code, target component, and the `Intent`'s `filterEquals`
   fields (action, data, type, categories) — **not** extras. The bug in D1 is the lesson's hook: the
   failure is invisible, which is exactly why the mental model has to be right.
2. **Alarms anchored to mutable state.** A deadline-relative reminder is scheduled once, because a
   deadline is fixed data. A `timerEndsAt`-anchored alarm must be **re-scheduled every time the user
   presses play or pause**, because its anchor moves. Fixed data vs. data that shifts under you —
   and why "schedule it once and forget" is a category error for the second kind.
3. **Where rescheduling belongs.** Why the repository decorator is the right home and the
   `ViewModel` is not (every write passes through the decorator; only one caller passes through the
   ViewModel), and what was missing in `startTimer`/`pauseTimer` — two methods that were pure
   pass-throughs and therefore looked finished.

---

## Approval

**Please review and approve this spec before any implementation begins.** Approval covers all three
of: the **behaviour**, the **look** (the notification design and its visual ACs), and the **tutorial
decision** (`Sí`, lesson `09b`, written after implementation).

Worth an explicit yes/no on:

- **D3** — one alert at the earliest instant, for a task with both a deadline and a running timer.
- **D9** — this feature also stops the **existing** lead-time reminder from firing for completed
  tasks (a behaviour change beyond the headline feature).
- **D10** — the reschedule worker also runs on cold start, so installing this update arms alarms
  without waiting for a reboot.
- **No notification actions** (no "Mark done") in this feature — deferred deliberately.
