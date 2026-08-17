# Feature — Modo Foco (núcleo): pantalla completa y ritual de salida costoso

- **Status:** Awaiting approval
- **Date:** 2026-08-18
- **Branch (suggested):** `feature/focus-mode`
- **Prompt origen:** [`docs/prompts/modo-foco-nucleo.md`](../prompts/modo-foco-nucleo.md)
- **Original framing (user's words):** *"Añadir un botón a la aplicación que permita entrar en un modo
  de concentracion […] una pantalla completa donde solo se puedan ver las tareas que tiene pendiente
  el usuario. Para poder salir de esta pantalla el usuario deberá marcar las tareas pendientes como
  echas en un check box, deslzar una barra de desbloqueo e introducir un código que se le habrá
  solicitado al pulsar el botón."*
- **User-facing name:** **"Modo Foco"** (English: *Focus Mode*). In code: `FocusMode` / `focus`,
  matching the rest of the codebase.
- **Type:** New screen + new pure domain logic + three new `user_prefs` DataStore keys, on `app/`
  only. **No** backend change, **no** API contract change, **no** Room schema change or migration,
  **no** new Gradle dependency, **no** new permission, **no** manifest change.
- **Tutorial:** **Sí, con lección** — answered by the user via `AskUserQuestion`. Suggested slot:
  **`tutorial/20d-modo-foco.md`** (after `20c-reducir-movimiento`, before the pending `21`). It is
  placed here rather than at `18c` — which would sit better thematically, next to navigation and
  accessibility — because the lesson necessarily reads `brandedTopAppBarColors()` (lesson 20) and the
  determinate progress bar (lesson 19), and a lesson must never depend on one that comes later in
  reading order. See *Tutorial scope* at the end of this spec for what the lesson should cover.

> **This is half of the product idea.** Device-level lockdown — screen pinning, Do Not Disturb,
> keep-screen-on — is a **separate, optional** future feature
> ([`docs/prompts/modo-foco-blindaje.md`](../prompts/modo-foco-blindaje.md)) and is explicitly out of
> scope here. Everything below must be useful and complete **without** any of it.

---

## Overview

The Tasks screen is honest but busy: search, sort chips, group chips, priority filters, a bottom
navigation bar offering Articles and Settings, a FAB, a stats button, per-row start/pause/delete
controls. That is the right design for *managing* a task list. It is the wrong design for *doing the
work*, and for the audience this product exists for the difference is not cosmetic — every affordance
on screen is an invitation to do something other than the thing.

**Modo Foco** is a deliberately impoverished screen: the tasks the person committed to, a checkbox
per task, and nothing else. No bottom bar, no rail, no route to another section, no way to add or
edit a task. Leaving it is not a tap — it is a **ritual** the person configured for themselves on the
way in: finish the list, slide a bar, type the code they chose.

The whole feature turns on one tension, and this spec exists mostly to resolve it rather than
restate it:

> A mode that demands a code to leave can **trap** the person. In an app for ADD/ADHD, forgetting
> that code is the likely case, not the edge case.

**D3** answers it: the ritual is the *dignified* way out, never the *only* way out. An unconditional
"Abandonar sesión" is on screen from the first frame, gated on nothing the person can forget or fail
to perform.

The second thing this spec refuses to do is **overpromise**. `BackHandler` intercepts the back
gesture *inside this app's window* and nothing else. Home, recents, the notification shade, quick
settings and the launcher all still work exactly as they always did. Modo Foco is a commitment
device, not a kiosk, and both the spec (**D8**) and the app's own copy say so in those words.

The substance is the ten decisions below. The acceptance criteria only enforce them.

---

## Decisions

### D1 — A session is a **frozen roster**, not a live "pending" filter

When a session starts, the app captures the **set of task ids that are pending at that instant** and
keeps it for the life of the session. The Focus screen renders exactly that set; the exit gate is
computed over exactly that set.

**Why frozen and not live.** A live "everything currently pending" list re-locks the person for
reasons they did not cause and cannot see:

- The sync engine pulls a task created on another device mid-session. With a live gate, a person one
  checkbox away from finishing is silently pushed back to "not done" by a task they have never read.
  That is the exact shape of the trap this spec is trying to avoid, arriving through the back door.
- A task arriving mid-session is *precisely the distraction* the mode exists to suppress. Showing it
  is the mode failing at its one job.

Frozen also gives the screen its spine: a session is a **commitment with a denominator**. "3 de 7"
is a real number that only moves when the person moves it — which is what makes the top-bar progress
worth looking at, and what lets the exit panel say something true about how far along they are.

**Consequences, all of which must hold:**

| Situation | Behaviour |
|---|---|
| A task is created (locally impossible here, or pulled by sync) mid-session | **Not shown**, not counted. It is waiting on the Tasks screen afterwards. |
| A roster task is completed **from another surface** (widget, another device) | Counted as done — the gate reads the live `Task` rows, only the *membership* is frozen. |
| A roster task is deleted elsewhere and no longer resolves | Counted as **satisfied** ("done or gone"), never as blocking. Fail open (**D6**). |
| The roster is empty at session start | Legal. The screen shows the "nothing pending" `MessageState` and the task gate is satisfied from the first frame. |

**Rejected alternative — disable the entry button when nothing is pending.** It costs a third
subscription to the task stream in `TasksViewModel` just to compute `hasPendingTasks` (the shaped
`uiState` cannot answer it — an active filter can empty the shaped list while pending tasks exist),
adds a disabled-control accessibility wrinkle, and buys nothing: an empty session is already a
perfectly coherent state, identical to the state a *completed* session reaches. No special case.

### D2 — The exit code is **friction, not a credential**

It is a 4-digit numeric code, chosen at entry, stored in **plaintext** in the existing `user_prefs`
DataStore, and shown back to the person on request (**D3**).

This is deliberate and must not be "hardened" by a later change:

- It does **not** go into the Keystore-backed `EncryptedTokenStorage`. That store exists for auth
  tokens — values an attacker could use against a *server*. This code protects nothing and grants
  nothing.
- It is **not hashed**. Hashing would make the "reveal the code" escape hatch impossible to build,
  which would trade a real safety property for a security property that has no threat to defend
  against.
- The attacker and the protected party **are the same person**. The only thing this code resists is
  the same person's own next impulse, thirty seconds from now. Anyone who can read `user_prefs`
  already holds the unlocked device and could simply press Home.

Anyone tempted to "fix" this should read this paragraph first: the plaintext is the feature. It is
also why this pattern must **never** be copied for anything that genuinely is a secret.

### D3 — Two exits: one dignified, one unconditional. The escape hatch is gated on **nothing**

The exit panel offers, always, both of these:

**1. The ritual (dignified exit).** Enabled only when all three of the product owner's pieces hold:
every roster task done or gone, the correct code entered, and the slide bar completed. Completing
the slide ends the session as **completed**.

**2. "Abandonar sesión" (emergency exit).** A plain text button, **always enabled**, from the very
first frame of the session. One tap opens a confirmation dialog naming the consequence
("la sesión se cerrará como abandonada"); confirming ends the session as **abandoned** and returns to
Tasks.

**The rule this encodes, and the line between friction and a trap:**

> The emergency exit must never be gated on anything the person can **forget** (a code), **fail to
> perform** (a gesture), or **wait out** (a timer).

A time-gated escape hatch is not an escape hatch during the exact minute someone needs it. Abandon is
therefore one tap plus one confirmation — no delay, no code, no gesture, no accessibility cliff.

**3. "No recuerdo el código" (the gentler middle path).** Also always visible on the exit panel.
Tapping it starts a visible **60-second countdown**, after which the code is displayed in plain text
and the ritual can be finished normally. This is the prompt's *"el código se vuelva visible tras N
minutos"*, tuned two ways:

- The countdown runs from **when the person asks**, not from when the session started. Anchoring it
  to session start would leave a forgotten-code person with only the abandon path during the first N
  minutes — a trap with a timer on it.
- 60 seconds, not five minutes, because the wait is **not a security control** (**D2**). It only has
  to be long enough not to be the default path.

This reveal is safe to time-gate at all *only because* abandon is not: the timed path is always the
**nicer** of two available exits, never the only one.

**Session outcome (completed / abandoned) is surfaced immediately and deliberately not stored.**
Returning to Tasks shows a snackbar — completed (with the count) or abandoned — reusing the
`SavedStateHandle` result idiom `TASK_CREATED_RESULT_KEY` already established for "a task was just
created" (see `TasksRoute`'s KDoc). A **session history** would need a Room table, hence a migration,
which this feature explicitly excludes — deferred (*Out of Scope*).

### D4 — Navigation: a secondary route, exactly like Stats

Follows the existing pattern in
[`AppNavHost.kt`](../../app/src/main/java/com/neverlate/ui/navigation/AppNavHost.kt) with no new
mechanism invented:

- A `FOCUS` constant in the `private object Routes`.
- A `composable(Routes.FOCUS)` inside `MainNavGraph`.
- **Deliberately absent from `TOP_LEVEL_ROUTES`.** That single omission is what hides the bottom bar
  (compact) and the rail (medium/expanded) — the route-gated `showTopLevelChrome` check already in
  `MainAppNavHost` does the rest. No new visibility flag, no `Scaffold` change.
- The entry point is an `IconButton` in the Tasks `TopAppBar` `actions` slot, wired **exactly like**
  the existing `onStatsClick` (feature 04c's canonical secondary-route precedent).

**One deliberate difference from every other destination:** the Focus composable is **not** wrapped in
`ReadableWidthContainer`. Every other screen constrains itself to 640dp on a wide window so prose and
forms stay readable; Modo Foco wants the whole window — a centred column with empty gutters on a
tablet reads as "an app with a screen in it", which is the opposite of what this screen is for.

**Startup routing.** `MainAppNavHost` already computes `startDestination` from the DataStore-loaded
preferences behind its `null → LoadingIndicator` branch. One arm is added:

```kotlin
val startDestination = when {
    !preferences.onboarded -> Routes.ONBOARDING   // onboarding still always wins
    preferences.focusSession != null -> Routes.FOCUS
    openTasksOnStart -> Routes.TASKS
    else -> Routes.TASKS
}
```

An active session therefore survives process death and cold start, and `openTasksOnStart` (the widget
tap) no longer wins over it — which is the point: opening the app any way at all, including from the
lock-screen notification, lands back in the session. Onboarding's precedence is untouched, and a
not-yet-onboarded user cannot have a session anyway.

### D5 — Session state lives in **three tiers**, and one of them is deliberately unused

This is the decision the tutorial lesson is built around.

| State | Home | Survives rotation | Survives process death | Survives reboot |
|---|---|---|---|---|
| Session exists, `startedAt`, exit code, frozen roster | **`user_prefs` DataStore** | ✅ | ✅ | ✅ (until expiry, **D7**) |
| Exit panel open/closed | `FocusViewModel` | ✅ | ❌ | ❌ |
| Digits typed into the code field | `FocusViewModel` | ✅ | ❌ | ❌ |
| Slide-bar drag progress | `FocusViewModel` | ✅ | ❌ | ❌ |
| "Reveal code" countdown | `FocusViewModel` | ✅ | ❌ | ❌ |

**`SavedStateHandle` is deliberately not used anywhere in this feature.** The middle tier exists for
state that is expensive or impossible to reproduce after a system-initiated process death. Everything
transient here is a few digits and a half-finished drag; losing it costs seconds, and *resetting the
exit panel to closed* after a process death is arguably the correct behaviour rather than a
regression — the person comes back to the session, not to a half-finished ritual.

Recording the tier we chose **not** to use, and why, is a better lesson than reaching for it because
it exists.

**What survives a process death is exactly what makes the mode credible.** If the session lived only
in the `ViewModel`, killing the app from recents would be a silent fourth exit — and the ritual would
be theatre. It survives; the honest exits are the two in **D3**.

### D6 — Fail **open**, always, on every unreadable value

Tolerant parsing here is not just crash-avoidance (as it is for `ThemeMode.fromStorage`); it decides
whether a corrupted preference can **lock someone out of their own phone's task app**. Every fallback
therefore resolves toward *easier to leave*, never toward *harder*:

| Stored value | If absent / unparseable | Resulting behaviour |
|---|---|---|
| `focus_session_started_at` | `0L` → **no session** | The app opens normally on Tasks. |
| `focus_exit_code` | blank | The **code step is treated as satisfied** and the field is not rendered — a session whose code was lost is not a session you cannot leave. |
| `focus_roster` | empty set | The **task gate is satisfied**; the screen shows the "nothing pending" state. Individual unparseable ids are dropped one by one, never invalidating the whole list. |

No error state, no toast, no crash. A preference that cannot be read is simply the lenient default,
and the session can be ended normally.

### D7 — A session **expires after 12 hours**, evaluated purely on read

`FocusSession` carries `startedAt`. A session whose `startedAt` is more than **12 hours** old is
treated as absent by a pure predicate, not by an alarm, a receiver or a `WorkManager` job:

```kotlin
fun isFocusSessionActive(session: FocusSession?, now: Long): Boolean
```

**Why an expiry at all.** DataStore survives reboots, so without one, a phone that is put down and
rebooted two days later opens into a focus session its owner has entirely forgotten agreeing to.
Every exit is still available in that state, but "the app ambushes you with a ritual you don't
remember" is not a good product even when it is escapable.

**Why 12 hours.** Longer than any plausible single focus session (so it can never cut a real one
short), shorter than "the next day" (so a stale session never ambushes anyone).

**Why a pure predicate and not a scheduled alarm.** The app already has an alarm-heavy surface
(`ui/notification/`, two `ReminderKind`s namespaced by request code) and every alarm added is another
thing to cancel, reschedule at boot and reason about. A session expiring is not an *event* anyone
needs to be notified of — it only needs to be *true the next time anyone asks*. A pure function over
`(session, now)` is JVM-testable in three lines and cannot leak.

### D8 — `BackHandler` intercepts the back gesture and **nothing else** — say so, in the app

`BackHandler(enabled = true)` on the Focus screen redirects the system back gesture to **open the
exit panel** rather than leaving. The repo's only precedent
([`ArticlesListDetailPane.kt`](../../app/src/main/java/com/neverlate/ui/articles/ArticlesListDetailPane.kt))
uses it the same way — intercept, don't leave.

**What it does not do, stated plainly because the spec must not promise a lockdown that does not
exist:** Home, recents/app-switching, the notification shade, quick settings, the power menu and the
launcher **all still work**. A person can leave this screen at any moment without touching the
ritual. Modo Foco is a commitment device, not a kiosk.

This is not merely a note for engineers. The **entry dialog's own copy** must describe the mode
without implying a lock, and must not use words like "bloquear" that promise something Android will
not deliver. Everything in this paragraph that *could* be delivered (screen pinning, DND) belongs to
[`modo-foco-blindaje.md`](../prompts/modo-foco-blindaje.md), and stays there.

### D9 — The slide bar gets a **custom accessibility action**, and it is not optional

A "slide to unlock" bar with no semantic alternative locks a TalkBack user inside the mode. That is
not a missing polish item; it is the feature being broken for a subset of users while every automated
test passes.

The bar therefore carries:

- `Modifier.semantics { customActions = listOf(CustomAccessibilityAction(<"Desbloquear">) { … }) }` —
  a single activation that performs the same completion the full drag performs.
- A `contentDescription` naming the control and a `stateDescription` reflecting whether it is
  currently enabled (and, when disabled, **why**: which of the two prerequisites is still missing).
- `Role.Button` semantics so switch access and keyboard users reach it at all.
- A thumb and a track height of **≥48dp**.

The custom action preserves the friction proportionally rather than deleting it: under TalkBack,
reaching a custom action is itself a deliberate two-step interaction, not a stray tap. And the
action is enabled under exactly the same conditions as the drag — a screen reader user gets the same
gate and the same escape hatches, never a shortcut and never a wall.

This is enforced by an **instrumented test** that completes the exit using only accessibility
actions, never a gesture (AC-27). If that test cannot be made to pass, the feature is not done.

### D10 — Reuse every helper; duplicate only the row, and say why

| Thing | Decision |
|---|---|
| Marking a task done | **Reuse `TasksViewModel.toggleComplete`'s exact call** — `repository.saveTask(task.copy(completedAt = …))`. That one line already flows through the four-layer decorator chain: widget + notification refresh, reminder cancellation, outbox enqueue, Room write. `FocusViewModel` calls `saveTask` the same way. **No second write path.** |
| Pending / roster / progress logic | **New pure functions** in `domain/tasks/FocusSession.kt`, JVM-tested — never a `filter {}` inside a composable. |
| `pendingRowsFor` | **Not reused.** It caps at `MAX_PENDING_ROWS = 5` and projects to the lossy `PendingTaskRow` shape built for *passive* surfaces (widget, notification) — and its own KDoc carries an explicit tripwire against growing it for one more consumer. Focus mode is an interactive, unbounded list of real `TaskUiModel`s. |
| Sorting the focus list | **Reuse `sortedBy(TaskSortField.Deadline, SortDirection.Ascending)`** from `TaskListShaping.kt`, which already sinks completed tasks last. Focus mode does **not** honour the persisted arrangement (`persisted-list-preferences`): a screen whose whole premise is "one thing matters now" has exactly one correct order — soonest first. |
| Urgency color | `colorForUrgency` is currently `private` in `TasksScreen.kt`. Promote it to a shared `ui/theme/UrgencyColors.kt` (unchanged behaviour, still resolving `domain/tasks/ColorRole.kt`'s `urgencyColorRole` against `NeverLateExtras`) so Focus is the second consumer rather than the second copy — the same "one mapping, thin per-world resolvers" shape the widget refactor established. |
| Remaining-time text | **Reuse `formatRemainingLabel(Context, Long)`** (`ui/components/RemainingTimeLabel.kt`) — the single home of compact countdown text for card, widget and notification. |
| Progress bar fraction | **Reuse `deadlineProgressFor`**. |
| Priority marker | **Reuse `Priority.markerRes()` / `Priority.indicatorColor()`** (`ui/tasks/PriorityUi.kt`). |
| Empty state | **Reuse `ui/components/MessageState`**. |
| Top bar colors, leading chip | **Reuse `brandedTopAppBarColors()` and `BrandIconChip`**. |
| The row itself | **A new `FocusTaskRow`, not `TaskRow`.** `TaskRow` carries start/pause/delete/tap-to-edit — four affordances that each navigate away from or complicate the one thing this screen is for. Duplicating the *row* while reusing every *token and helper* inside it is the correct trade: the difference between the two rows is the entire product point, not incidental styling. |

---

## User Stories

### US-1 — I can shut everything else out

> As someone who loses an hour to the app itself, I want a screen that shows only what I committed to
> and offers nothing else, so that the app stops being one of the things distracting me.

**Acceptance criteria**

- A focus entry action is available from the Tasks top bar, alongside the existing stats action.
- Tapping it opens a dialog that explains the mode, asks for a 4-digit exit code, and starts the
  session on confirm.
- The Focus screen shows **no** bottom navigation bar and **no** navigation rail, at every window
  width.
- The Focus screen offers no route to Articles, Settings, Stats, Task Edit or task creation.
- The Focus screen uses the **full window width** (no `ReadableWidthContainer`).
- The system back gesture opens the exit panel; it never leaves the screen.

### US-2 — I can tick things off without leaving

> As someone working through a list, I want to mark a task done right there, so that finishing the
> session and finishing the work are the same act.

**Acceptance criteria**

- Each task in the session shows a checkbox; tapping it marks the task done (and tapping again undoes
  it).
- Completing a task from Modo Foco updates the home-screen widget and the pending-tasks notification,
  cancels its reminders, and enqueues its outbox row — because it goes through the same `saveTask`
  path as the Tasks screen, with no focus-specific write code.
- Completing the last remaining task changes the screen to the "nothing pending left" state without
  leaving the mode.
- A task completed elsewhere (widget, another device via sync) is reflected on the Focus screen and
  counts toward the exit gate.

### US-3 — Leaving costs me something I chose

> As someone who breaks their own promises to themselves, I want leaving to require the ritual I set
> up on the way in, so that quitting is a decision rather than a reflex.

**Acceptance criteria**

- The exit panel lists three requirements with live status: tasks finished, code entered, slide
  completed.
- The slide bar is inert until every roster task is done or gone **and** the correct code is entered.
- Completing the slide ends the session and returns to the Tasks screen.
- Returning to Tasks shows a snackbar naming the outcome and the number of tasks completed.
- The session survives rotation, backgrounding, process death and a device reboot — reopening the app
  by any route (launcher, widget, lock-screen notification) returns to the session.

### US-4 — Forgetting the code cannot trap me

> As someone whose memory is exactly the thing this app compensates for, I want a way out that does
> not depend on remembering anything, so that a focus session can never become a problem I have to
> solve.

**Acceptance criteria**

- "Abandonar sesión" is visible and enabled from the first frame of the session, and at every moment
  after it.
- It requires exactly one confirmation, is never disabled, never delayed, and never asks for the code
  or the gesture.
- Confirming ends the session, returns to Tasks and shows the "abandoned" snackbar. No task is
  altered by abandoning.
- "No recuerdo el código" is always available; activating it starts a visible 60-second countdown
  after which the code is shown in plain text and the ritual can be finished normally.
- A session older than 12 hours is treated as ended: the app opens on Tasks with no session and no
  message.
- A corrupted or missing stored code, roster or session value never produces a state that is harder to
  leave (**D6**).

### US-5 — I can use this with a screen reader

> As a TalkBack user, I want the unlock gesture to have an equivalent accessible action, so that a
> mode designed to be hard to leave is not impossible for me to leave.

**Acceptance criteria**

- The slide bar exposes a custom accessibility action that performs the same unlock as the drag.
- The bar announces what it is, and when it is inert, which requirement is still missing.
- The code field has a label that a screen reader announces as its name.
- Every interactive target on the screen — checkbox, slide thumb, abandon button, reveal button, code
  field — is ≥48dp.
- The whole exit — ritual or abandon — is completable using only accessibility actions, proven by an
  instrumented test.

### US-6 — The app does not lie to me about what it controls

> As a user deciding whether to trust this mode, I want to know it does not actually block my phone,
> so that I am not surprised when pressing Home works.

**Acceptance criteria**

- The entry dialog describes the mode without claiming it blocks the device or other apps.
- Nothing in the UI copy uses lockdown language for behaviour Android does not provide here.
- Leaving via Home/recents and reopening returns to the session (it is not an exit), and this is the
  only sense in which the mode "holds".

---

## Acceptance Criteria (consolidated)

### Behavioural — session and data

- **AC-1** — `focusRosterFor(tasks)` returns the ids of tasks with `completedAt == null`, and nothing
  else (unit-tested, including the empty-list case).
- **AC-2** — `focusRowsFor(uiTasks, roster)` returns only roster members, ordered by
  `sortedBy(Deadline, Ascending)`, with completed ones last (unit-tested).
- **AC-3** — `focusProgressFor(uiTasks, roster)` counts a roster id as **done** when its task is
  completed **or** no longer present (**D1**), and yields `total`/`done` consistent with each other
  (unit-tested for both cases).
- **AC-4** — A task not in the roster is never rendered and never counted, even if it is pending
  (unit-tested via a task added after the roster was captured).
- **AC-5** — `isFocusSessionActive(session, now)` is `false` for `null`, `false` at
  `startedAt + 12h + 1ms`, and `true` at `startedAt + 12h - 1ms` (unit-tested at the boundary).
- **AC-6** — The session round-trips through the `user_prefs` DataStore: `startFocusSession` then a
  fresh read yields the same `startedAt`, code and roster.
- **AC-7** — `endFocusSession()` clears all three keys; a subsequent read yields `focusSession == null`.
- **AC-8** — Missing/unparseable values fail **open** per **D6**: no session, satisfied code step,
  empty roster — never a crash, never a harder-to-leave state (unit-tested for each key).
- **AC-9** — Individual unparseable ids in the stored roster are dropped without discarding the
  parseable ones (unit-tested).
- **AC-10** — Nothing focus-related is written to `EncryptedTokenStorage` or any second DataStore
  (**D2**); the three new keys live in the existing `user_prefs` file.

### Behavioural — navigation and lifecycle

- **AC-11** — `Routes.FOCUS` exists, is registered in `MainNavGraph`, and is **absent** from
  `TOP_LEVEL_ROUTES`; no bar and no rail render on it at compact, medium or expanded width.
- **AC-12** — The Focus composable is **not** wrapped in `ReadableWidthContainer`.
- **AC-13** — With an active session stored, `MainAppNavHost`'s `startDestination` is `Routes.FOCUS`,
  and `openTasksOnStart` does not override it.
- **AC-14** — A not-yet-onboarded user still lands on Onboarding regardless of any stored session.
- **AC-15** — The start destination is still decided behind the existing `null → LoadingIndicator`
  branch: no frame of Tasks is ever painted before the Focus session is restored.
- **AC-16** — System back on the Focus screen opens the exit panel and never pops the destination.
- **AC-17** — The session survives rotation, backgrounding and process death; the exit panel's
  transient state (typed code, drag progress, reveal countdown) resets on process death only (**D5**).

### Behavioural — the ritual

- **AC-18** — Entry requires a code of exactly 4 digits; the start action is disabled until it is
  valid, and only digits are accepted into the field.
- **AC-19** — The slide bar (and its custom accessibility action) is inert unless **both** the task
  gate and the code gate are satisfied; satisfying them enables it in the same frame.
- **AC-20** — An incorrect code never enables the slide and never produces a lockout, a penalty, an
  attempt counter or a delay.
- **AC-21** — Completing the slide ends the session, navigates back to Tasks, and shows the
  "completed" snackbar with the number of tasks completed (via `<plurals>`).
- **AC-22** — "Abandonar sesión" is enabled at every moment a session is active, including the first
  frame and including while the reveal countdown is running.
- **AC-23** — Abandoning takes exactly one confirmation, ends the session, returns to Tasks with the
  "abandoned" snackbar, and modifies **no** task.
- **AC-24** — "No recuerdo el código" starts a visible 60-second countdown; the code is shown in plain
  text when it reaches zero, and the ritual can then be completed normally.
- **AC-25** — Completing a task from Modo Foco goes through `TaskRepository.saveTask` and therefore
  refreshes the widget and notification, cancels reminders and enqueues the outbox row — asserted by
  a test that observes the repository chain, not by a focus-specific write.

### Accessibility (US-5, **D9**)

- **AC-26** — The slide bar exposes a `CustomAccessibilityAction` that completes the unlock, enabled
  under exactly the same conditions as the drag.
- **AC-27** — **Instrumented test:** a full exit is completed using only semantics actions (no drag
  gesture), and a second one abandons the session the same way.
- **AC-28** — The bar carries a `contentDescription`, and a `stateDescription` that names the missing
  prerequisite while it is inert.
- **AC-29** — The code field has a `label` that becomes its accessible name; the checkbox carries a
  `contentDescription` distinguishing "mark done" from "completed" (reusing the existing
  `tasks_mark_done_content_description` / `tasks_completed_content_description` strings).
- **AC-30** — Every interactive target is ≥48dp, via `minimumInteractiveComponentSize()` or an
  explicit height.

### Definition-of-Done items this feature touches

- **AC-31** — Pure logic in `domain/tasks/FocusSession.kt` has JVM unit tests (AC-1..AC-5, AC-9); the
  session state machine in `FocusViewModel` has JVM tests against a fake repository; the accessibility
  exit has an instrumented test (AC-27). `timeout 600 ./gradlew :app:testDebugUnitTest --console=plain`
  is green before committing.
- **AC-32** — **No Room migration**: the database version is untouched (this is DataStore only).
- **AC-33** — **No contract change**: `docs/api/contract.md` is not touched; the session never leaves
  the device.
- **AC-34** — Every new user-facing string ships in **both** `values/` (Spanish base) and
  `values-en/`; counts ("3 de 7 tareas", the completion snackbar) use `<plurals>`; the countdown uses
  `NumberFormat`-consistent formatting per `Locale`.
- **AC-35** — Loading, empty and error states are covered on the Focus screen: loading is the existing
  no-flash `Unit` branch used by Tasks/Stats, empty reuses `MessageState`, and the "unreadable
  session" case is not an error state at all by **D6** — it resolves to no session.
- **AC-36** — All **seven** `UserPreferencesRepository` fakes implement the two new methods and the
  suite compiles (see *Technical Approach → Test impact*).
- **AC-37** — `docs/mockups/README.md` gains the new **"Focus Mode full-screen session + exit ritual"**
  row (status `—`, net-new UI), and `docs/arquitectura.md` records the session-state-tiers decision
  (**D5**) and the fail-open rule (**D6**).
- **AC-38** — The Spanish lesson `tutorial/20d-modo-foco.md` is written and its status flipped in both
  `tutorial/README.md` and `docs/conceptos-pendientes.md` (the `Tutorial:` field says yes).
- **AC-39** — **No new dependency** in `gradle/libs.versions.toml`, **no** manifest or permission
  change.

### Visual

- **AC-V1** — The top bar uses `brandedTopAppBarColors()`, carries the session progress ("3 de 7"),
  and has **no** navigation icon and **no** action icons — nothing in the chrome invites the person
  elsewhere.
- **AC-V2** — The first (soonest) unfinished task is visually dominant: a larger type style and more
  vertical space than the rows below it, so "the thing to do now" is identifiable without reading.
- **AC-V3** — Rows below it are compact and uniform, and the list scrolls independently while the top
  bar's progress and the bottom exit action stay put — the screen stays legible with 30+ tasks.
- **AC-V4** — Countdown text and per-row progress bar use `colorForUrgency` over `NeverLateExtras`
  (the same mapping as the Tasks card and the widget), never a focus-specific palette.
- **AC-V5** — Each row leads with `BrandIconChip`, matching the Tasks and Articles rows.
- **AC-V6** — The "nothing pending left" state is `ui/components/MessageState` with a congratulatory
  message and the exit action as its `actionLabel`/`onAction` pair.
- **AC-V7** — Urgency colors, the slide bar's enabled/inert treatment and the revealed code all meet
  contrast in **both** light and dark themes; the inert slide bar is distinguishable from the enabled
  one **without relying on color alone** (also a lock/unlock icon and its label).
- **AC-V8** — Every interactive target is ≥48dp (AC-30), verified visually as well as in semantics.
- **AC-V9** — The layout reflows correctly at the largest font scale: the exit panel scrolls rather
  than clipping, the slide bar keeps its full travel, and no label is truncated below legibility.
- **AC-V10** — The screen fills the window at expanded width (no 640dp gutters), and the list does not
  become an unreadable full-width line of text — the *rows* stay within a comfortable measure while
  the *surface* spans the window.
- **AC-V11** — Verified in the real app (`/run`) at compact and expanded width, in light and dark, at
  default and largest font scale, and once with TalkBack on.

---

## Visual & UX Design

### Mockup slice — **none claimed; a new row is added**

The master mockup [`rediseno-ux-ui.html`](../mockups/rediseno-ux-ui.html) **has no Focus Mode
screen** — no full-screen session surface, no ritual, no slide bar anywhere in it. This feature
therefore claims no slice and **adds a new row** to
[`docs/mockups/README.md`](../mockups/README.md) with status `—` (net-new UI, tracked for context),
owned by this feature. It does **not** move or repurpose any existing row.

The mockup is still the **direction**: the brand top-bar treatment, the card/row rhythm, the
urgency-colored countdown and the progress bar all come from it, translated through the app's real
theme tokens (`ui/theme/`) exactly as feature 20 established. No HTML/CSS is copied.

### Hierarchy — one thing matters

The screen is a deliberate inversion of the Tasks screen's density:

```
┌──────────────────────────────────────────┐
│ ▓▓ Modo Foco              3 de 7 ▓▓▓▓▓▓▓ │  branded top bar, no nav icon, no actions
├──────────────────────────────────────────┤
│  ☐  ▣  Preparar la presentación          │  ← the current task: headlineSmall,
│           2h 38m           ▬▬▬▬▬▬▬▬░░░░  │    generous padding, own emphasis
├──────────────────────────────────────────┤
│  ☐  ▣  Responder a Marta        45m      │  ← the rest: compact, uniform, bodyLarge
│  ☑  ▣  ~~Reservar sala~~                 │  ← done: strikethrough, sunk to the bottom
│  ☐  ▣  Revisar el presupuesto   1d 4h    │
├──────────────────────────────────────────┤
│         [ Salir del Modo Foco ]          │  ← the only action in the chrome
└──────────────────────────────────────────┘
```

- **The top bar** carries the session's denominator and nothing else. No back arrow (there is nowhere
  to go), no overflow, no stats. Its emptiness is the design.
- **The current task** — the first unfinished row after `sortedBy(Deadline, Ascending)` — is rendered
  at a larger type style with more vertical space and its progress bar at full width. Everything else
  is visibly secondary. This is the "una sola cosa importa: la tarea de ahora" requirement made
  concrete and checkable (AC-V2).
- **The remaining rows** are a compact, uniform list, so the screen degrades gracefully rather than
  becoming a wall: with 30 pending tasks the current task still reads as the current task, and the
  list scrolls under a fixed top bar and above a fixed exit action (AC-V3).
- **Completed rows** keep the Tasks screen's existing treatment — strikethrough title in
  `onSurfaceVariant`, no countdown, no progress bar, sunk to the bottom by `sortedBy`'s
  completed-last key — so "done" looks identical in both places.

### The exit panel

A modal sheet over the session (not a separate destination — leaving it must never be a navigation
event that could pop the session):

1. **The three requirements**, as a live checklist with the same iconography as the checkbox rows:
   *Tareas terminadas (5 de 7)* · *Código introducido* · *Deslizar para salir*.
2. **The code field** — `OutlinedTextField`, numeric keyboard, a real `label` (its accessible name),
   4-digit cap. Never rendered at all when the stored code is blank (**D6**).
3. **"No recuerdo el código"** — a `TextButton` that turns into a visible countdown, then into the
   code itself, in place.
4. **The slide bar** — full width, ≥48dp track, a thumb with a lock icon that flips to unlocked past
   the completion threshold. Inert state is conveyed by **both** a dimmed treatment and a lock icon
   plus its `stateDescription` (AC-V7): color is never the only cue.
5. **"Abandonar sesión"** — a `TextButton` in `error`-adjacent emphasis, always enabled, opening the
   same `AlertDialog` shape `LogoutConfirmDialog` already uses.

### The "nothing pending left" state

`ui/components/MessageState`, no new component: a congratulatory message, and — via `MessageState`'s
existing `actionLabel`/`onAction` pair — the exit action right there, so finishing the work and
finishing the session are one continuous motion (AC-V6). The checklist's task requirement is already
satisfied at this point, so the ritual is down to the code and the slide.

### Tokens and components reused (extend, don't duplicate)

- `brandedTopAppBarColors()` — top bar (feature 20's chrome).
- `BrandIconChip` — every row's leading element (feature 20).
- `MessageState` — the empty state (feature 17).
- `colorForUrgency` over `NeverLateExtras.colors` — countdown + progress bar color, promoted to
  `ui/theme/UrgencyColors.kt` so this screen is a second **consumer**, not a second copy (**D10**).
- `formatRemainingLabel` — the compact countdown text (feature 20b).
- `deadlineProgressFor` + `LinearProgressIndicator` — the per-row bar (feature 19).
- `Priority.markerRes()` / `Priority.indicatorColor()` — the `!`/`!!`/`!!!` marker (`priority-sorting`).
- `minimumInteractiveComponentSize()` — the ≥48dp guarantee, made visible at each call site as the
  rest of the codebase does.
- Material 3 type scale and color roles throughout — **no** focus-specific palette, **no** bespoke
  dimensions beyond the slide bar's track/thumb sizing.

### Deferred, and to where

| Deferred | To where |
|---|---|
| Screen pinning, Do Not Disturb, keep-screen-on, blocking other apps | [`modo-foco-blindaje.md`](../prompts/modo-foco-blindaje.md) — the explicit second half of this idea |
| Starting/pausing a task's **timer** from the Focus screen | A follow-up; the strongest candidate, and a row for [`docs/diferidos.md`](../diferidos.md) |
| Session **history** and focus statistics (a Stats card, streaks) | Needs a Room table → a migration, excluded here. `docs/diferidos.md` |
| Sound, haptics or an ambient timer for the session | `docs/diferidos.md` |
| A **tablet-specific** Focus layout (two columns, current task in its own pane) | `docs/diferidos.md`; this feature only guarantees the full-width surface reads correctly (AC-V10) |
| Suppressing the app's own reminder notifications during a session | `docs/diferidos.md` — it interacts with `ui/notification/`'s alarm model and deserves its own decision |

Deferring is fine; deferring silently is not. Each row above is written down precisely so nobody
later reads its absence as an oversight.

---

## Technical Approach

Five files change, two are new, plus strings and test fakes. All in `app/`.

| File | What changes |
|---|---|
| **NEW** `domain/tasks/FocusSession.kt` | `data class FocusSession(startedAt, exitCode, roster: Set<Long>)`; `data class FocusProgress(total, done)` with `isComplete`; `focusRosterFor(tasks)`, `focusRowsFor(uiTasks, roster)`, `focusProgressFor(uiTasks, roster)`, `isFocusSessionActive(session, now)`. Pure Kotlin, no Android imports — same rules as `TaskListShaping.kt`, which it reuses (`sortedBy`) rather than re-implementing an order. |
| **NEW** `ui/focus/FocusScreen.kt` + `ui/focus/FocusViewModel.kt` | `FocusRoute` (stateful, `hiltViewModel()`) → `FocusScreen` (stateless), following the Route/Screen split every other screen uses. The ViewModel combines `repository.observeTasks()` with the stored session, exposes a sealed `FocusUiState`, owns the exit-panel state (**D5**), calls `saveTask` for completion (**D10**) and `endFocusSession()` on either exit. `FocusSlideToUnlock` and `FocusExitPanel` are private composables in the same package. |
| [`data/UserPreferencesRepository.kt`](../../app/src/main/java/com/neverlate/data/UserPreferencesRepository.kt) | `UserPreferences` gains `val focusSession: FocusSession? = null`; three keys in `Keys` (`focus_session_started_at: Long`, `focus_exit_code: String`, `focus_roster: String`); tolerant reads per **D6**; two interface methods: `suspend fun startFocusSession(session: FocusSession)` and `suspend fun endFocusSession()`. Same `user_prefs` file — **never a second DataStore**. |
| [`ui/navigation/AppNavHost.kt`](../../app/src/main/java/com/neverlate/ui/navigation/AppNavHost.kt) | `Routes.FOCUS`; a `composable(Routes.FOCUS)` in `MainNavGraph` (no `ReadableWidthContainer`); the extra `startDestination` arm (**D4**); `onFocusClick` threaded into `TasksRoute` exactly like `onStatsClick`. `TOP_LEVEL_ROUTES` is **not** touched — that is the point. |
| [`ui/tasks/TasksScreen.kt`](../../app/src/main/java/com/neverlate/ui/tasks/TasksScreen.kt) | One `IconButton` in the existing `actions` slot + `onFocusClick` on `TasksRoute`/`TasksScreen`; the entry `AlertDialog` (code + explanation), reusing the `LogoutConfirmDialog` shape; the exit-outcome snackbar via the existing `SavedStateHandle` result idiom. `colorForUrgency` moves out to `ui/theme/UrgencyColors.kt` (behaviour-preserving). |
| `res/values/strings.xml` + `res/values-en/strings.xml` | ~20 new strings, Spanish base first, English mirrored; `<plurals>` for the progress and the completion snackbar. |

`di/StorageModule` already provides `UserPreferencesRepository` and `RepositoryModule` the
`TaskRepository` chain — **no new Hilt wiring**, only a new `@HiltViewModel`.

### Session lifecycle, end to end

```
Tasks ─ tap focus icon ─▶ entry dialog (4-digit code)
                             │ confirm
                             ▼
            startFocusSession(FocusSession(now, code, focusRosterFor(tasks)))
                             │
                             ▼
                     navigate(Routes.FOCUS)
                             │
   ┌─────────────────────────┴──────────────────────────┐
   │  session persists across rotation / process death / │
   │  reboot; startDestination returns here (D4)         │
   └─────────────────────────┬──────────────────────────┘
                             │
        ┌────────────────────┼────────────────────┐
        ▼                    ▼                    ▼
   slide unlock         abandon (1 tap)     >12h elapsed (D7)
   (tasks + code)       + confirm           evaluated on read
        │                    │                    │
        └──── endFocusSession() ───┬──────────────┘
                                   ▼
                     popBackStack() → Tasks + snackbar
```

### Test impact (compile-breaking)

Adding two methods to the `UserPreferencesRepository` interface breaks **every** implementation. The
codebase currently has **seven** fakes (`grep -rln ": UserPreferencesRepository" app/src/test
app/src/androidTest`), all of which must gain both methods in the same change or the suite will not
compile:

- `app/src/test/java/com/neverlate/data/sync/SyncTestDoubles.kt`
- `app/src/test/java/com/neverlate/ui/settings/SettingsViewModelTest.kt`
- `app/src/test/java/com/neverlate/ui/onboarding/OnboardingViewModelTest.kt`
- `app/src/test/java/com/neverlate/ui/tasks/TasksViewModelTest.kt`
- `app/src/test/java/com/neverlate/ui/notification/ReminderSchedulingRepositoryTest.kt`
- `app/src/androidTest/java/com/neverlate/ui/tasks/TasksEmptyStatePersonalizationTest.kt`
- `app/src/androidTest/java/com/neverlate/ui/tasks/TasksRouteSnackbarTest.kt`

Implement them as the existing fakes implement `saveName`/`saveTaskListArrangement` — mutate the
backing `MutableStateFlow` — so a test can seed an active session. This is the **third** feature in a
row to ripple across all seven; consolidating them into one shared double stays out of scope here but
is now a strong candidate row for `docs/diferidos.md`.

### New tests

**JVM (`domain/tasks/FocusSessionTest.kt`)**
- `focusRosterFor` over mixed completed/pending lists, and over an empty list (AC-1).
- `focusRowsFor` filters to roster membership and orders deadline-ascending, completed last (AC-2, AC-4).
- `focusProgressFor` counts completed **and** vanished ids as done (AC-3).
- `isFocusSessionActive` at, just before and just after the 12h boundary, and for `null` (AC-5).

**JVM (`ui/focus/FocusViewModelTest.kt`)**
- The exit gate opens only when tasks **and** code are both satisfied, in either order (AC-19).
- A wrong code leaves the gate closed and produces no penalty state (AC-20).
- Abandon ends the session and modifies no task (AC-23).
- Completing a task calls `saveTask` on the injected repository with `completedAt` set (AC-25).
- A blank stored code satisfies the code step and hides the field (AC-8, **D6**).
- An expired stored session yields "no session" (AC-5).

**JVM (`data/UserPreferencesRepositoryTest`-style, consistent with existing practice)**
- Round-trip of session start/end (AC-6, AC-7).
- Fail-open parsing of each key, including partially-unparseable rosters (AC-8, AC-9).

**Instrumented**
- The full ritual exit performed with **semantics actions only** (AC-27).
- Abandon performed with semantics actions only (AC-27).
- The Focus destination renders with no `NavigationBar`/`NavigationRail` at compact and expanded
  width (AC-11).

---

## Out of Scope

- **Device lockdown of any kind** — screen pinning (`startLockTask`), Do Not Disturb, keep-screen-on,
  blocking or hiding other apps, usage-stats or accessibility-service based enforcement. All of it is
  [`modo-foco-blindaje.md`](../prompts/modo-foco-blindaje.md)'s subject, and any permission or manifest
  change belongs there, not here.
- **Session history, streaks or focus statistics** — needs a Room table, hence a migration, which this
  feature excludes. The outcome is surfaced in the moment (**D3**) and not stored.
- **Creating or editing tasks** from the Focus screen — the absence of those routes is the feature.
- **Starting or pausing a task's timer** from the Focus screen.
- **Honouring the persisted list arrangement** (sort/group from `persisted-list-preferences`) inside
  Modo Foco — the order is fixed at soonest-first by design (**D10**).
- **Search, filter or grouping** on the Focus screen.
- **Suppressing the app's own notifications** while a session is active.
- **Syncing the session** to the backend or across devices — no `TaskDto` change, no contract change,
  no backend work.
- **Multiple or scheduled sessions**, session goals, per-session time budgets.
- **A tablet-specific two-pane Focus layout.**
- **Treating the exit code as a secret** — hashing it, moving it to the Keystore, adding attempt
  limits or lockout timers. All four would work against **D2** and **D3**.
- **Consolidating the seven `UserPreferencesRepository` test fakes.**

---

## Dependencies

- Everything needed already exists and is shipped:
  - The `user_prefs` DataStore + `UserPreferencesRepository` pattern (feature 07, extended by 09, 11,
    16 and `persisted-list-preferences`).
  - `TaskRepository`'s four-layer decorator chain and `saveTask` (feature 11 / `architecture
    consolidation`) — the reason completion needs no new write code.
  - `toggleComplete`'s exact formulation in `TasksViewModel` (feature 04c).
  - The secondary-route pattern and `TOP_LEVEL_ROUTES` gating (features 18 / 18b), with Stats (04c) as
    the canonical precedent.
  - `BackHandler` precedent in `ArticlesListDetailPane` (18b).
  - `MessageState` (17), `brandedTopAppBarColors()` + `BrandIconChip` (20), `formatRemainingLabel`
    (20b), `deadlineProgressFor` (19), `urgencyColorRole`/`NeverLateExtras` (16/17 +
    `widget-hilt-color-token`), `PriorityUi` (`priority-sorting`).
  - Hilt provision of both repositories (13d) — no new module, no new binding.
- **No new library.** `androidx.activity.compose.BackHandler` and
  `androidx.compose.foundation.gestures.draggable` are already on the classpath.
- **No backend, no contract, no permission, no manifest change, no Room migration.**
- The seven test fakes above must be updated in the same change.
- **Blocking:** this spec must be approved before implementation, and `modo-foco-blindaje.md` must
  **not** be started before this ships — it builds directly on the session model decided here.

---

## Risks

- **R1 — "Modo Foco" sounds like a lockdown and is not one.** `BackHandler` covers the back gesture
  inside this app's window; Home, recents, the shade, quick settings and the launcher are all
  untouched (**D8**). A user who expects a kiosk will feel misled the first time Home works.
  *Mitigation:* the entry dialog's copy describes the mode honestly and avoids lockdown language
  (US-6); the spec refuses to claim otherwise; anything stronger is `modo-foco-blindaje.md`'s job.
- **R2 — The trap.** This is the feature's defining risk: a mode requiring a remembered code to leave,
  built for people whose memory is the thing being compensated for. *Mitigation:* **D3**'s rule — the
  emergency exit is gated on nothing forgettable, performable-or-not, or waitable. Abandon is one tap
  plus one confirmation, always. If a future change ever puts a condition, delay or gesture in front
  of "Abandonar sesión", it has broken this feature no matter what the tests say.
- **R3 — The TalkBack cliff.** A drag gesture with no semantic equivalent would leave a screen-reader
  user stranded (**D9**). *Mitigation:* the custom action, the `stateDescription`, and AC-27's
  instrumented test — which is the only AC in this spec whose failure means "do not ship", not
  "polish later".
- **R4 — The code is stored in plaintext.** True, deliberate and documented (**D2**). *Residual risk:*
  a future contributor "hardens" it by hashing it, which silently deletes the reveal escape hatch and
  converts R2 from mitigated to live. *Mitigation:* **D2**'s reasoning is repeated in the
  `UserPreferences` KDoc at the point of storage, and the reveal path has its own AC (AC-24).
- **R5 — Frozen-roster drift.** A roster id that no longer resolves (deleted elsewhere, purged
  tombstone) must count as satisfied, not as an eternal blocker. *Mitigation:* `focusProgressFor`
  treats "absent" as done (**D1**, AC-3), and every parse failure fails open (**D6**).
- **R6 — Resurrection surprise.** A session surviving reboot means the app can open into a ritual the
  person has forgotten agreeing to. *Mitigation:* the 12-hour expiry (**D7**) plus the fact that both
  exits are available on the very first painted frame. *Accepted residual:* within those 12 hours the
  session genuinely does come back — that is the feature working, not a bug.
- **R7 — Seven-fake ripple.** Two new interface methods break seven hand-written fakes; one updated
  carelessly could make a test pass for the wrong reason. *Mitigation:* implement all seven
  identically (mutate the backing `MutableStateFlow`), and note the consolidation as deferred work.
- **R8 — `colorForUrgency`'s promotion touches the Tasks screen.** Moving a `private` function out of
  `TasksScreen.kt` is a behaviour-preserving refactor in principle, and a chance to change a color by
  accident in practice. *Mitigation:* pure move, no logic edit, and the existing Tasks tests must stay
  green untouched.
- **R9 — The full-width mandate versus readability on a tablet.** Dropping `ReadableWidthContainer`
  (**D4**) is right for the *surface* and wrong for a *line of text*. *Mitigation:* AC-V10 separates
  the two — the surface spans the window, the rows keep a comfortable measure — and a proper tablet
  layout is explicitly deferred.
- **R10 — Scope pressure from the blindaje half.** Review feedback of the form "while we're here,
  could it at least keep the screen on?" is exactly how this feature stops shipping. *Mitigation:*
  every such item has a named destination in *Deferred, and to where*; the answer is the row, not the
  code.

---

## Tutorial scope (`tutorial/20d-modo-foco.md`)

The spec's `Tutorial:` field says **sí**, so a Spanish lesson ships with this feature. Recommended
contents, all of which this feature genuinely exercises for the first time in the repo:

1. **`BackHandler` y el alcance honesto de "interceptar la navegación".** What an app actually
   controls (its own back gesture) and what it does not (home, recents, the shade), why that defines
   the honest scope of any "blocked mode", and how the repo's only prior use
   (`ArticlesListDetailPane`) is the same idiom at a smaller scale.
2. **Gestos personalizados con semántica accesible.** Why a slide-to-unlock bar is the classic
   TalkBack trap, what `CustomAccessibilityAction` / `stateDescription` / `Role` actually do, and how
   to keep the friction *proportional* rather than deleting it for screen-reader users.
3. **Estado de sesión que sobrevive al ciclo de vida.** The three tiers — `ViewModel`,
   `SavedStateHandle`, DataStore — what each one actually survives, why the session had to be in the
   third, and why this feature **deliberately did not use the middle one** (**D5**). Naming a tool you
   chose not to reach for is the part most lessons skip.
4. **Diseñar fricción sin diseñar una trampa.** The product judgment in **D3**: why the escape hatch
   must not be gated on anything forgettable, performable-or-not, or waitable; why a *timed* reveal is
   only acceptable because an *untimed* abandon sits next to it; and why the exit code is plaintext on
   purpose (**D2**) — a case where the "obviously more secure" choice would have made the product
   worse and less safe.

---

## Review

Please review and approve this spec — approval covers **behaviour, look and the tutorial decision**
— before implementation begins. The five points most worth an explicit yes/no:

1. **D1** — the session is a **frozen roster** captured at start; tasks arriving mid-session are
   neither shown nor counted.
2. **D3** — **"Abandonar sesión" is always enabled, one tap plus one confirmation, gated on nothing**;
   "no recuerdo el código" reveals the code after a 60-second countdown; the outcome is shown in a
   snackbar and not stored.
3. **D2** — the exit code is **plaintext friction, not a credential**: no Keystore, no hashing, no
   attempt limits, ever.
4. **D7** — a session **expires after 12 hours**, evaluated as a pure function on read (no alarm).
5. **D8/AC-V1** — the app **never claims** to block the device; the top bar carries the progress and
   nothing else, and the screen goes **full width** (no `ReadableWidthContainer`).

*Agentes: `android-engineer` (implementación, ritual de salida, estado de sesión, accesibilidad del
gesto, y sus tests JVM + instrumentados en una sola pasada).*
