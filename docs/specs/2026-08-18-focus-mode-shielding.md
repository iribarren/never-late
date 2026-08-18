# Feature — Modo Foco (blindaje): silenciar el teléfono durante la sesión

- **Status:** Approved (2026-08-18)
- **Date:** 2026-08-18
- **Branch (suggested):** `feature/focus-mode-shielding`
- **Prompt origen:** [`docs/prompts/modo-foco-blindaje.md`](../prompts/modo-foco-blindaje.md)
- **Original framing (user's words):** *"evaluar la viabilidad de que ciertas funciones del teléfono
  queden deshabilitadas"* durante el Modo Foco.
- **User-facing name:** the measures are named individually in the UI (**No molestar**, **Fijar la
  pantalla**, **Pantalla siempre encendida**); there is no user-facing word for the feature as a
  whole. In code: `FocusShield` / `focusShield`.
- **Type:** No new screen. Extends the shipped Modo Foco session with three optional device
  measures, one new **manifest permission**, one new `WorkManager` worker, two new `user_prefs`
  DataStore fields, and one **extracted shared component**. `app/` only. **No** backend change,
  **no** API contract change, **no** Room schema change or migration, **no** new Gradle dependency.
- **Tutorial:** **Sí, con lección** — answered by the user via `AskUserQuestion`. Slot:
  **`tutorial/20e-modo-foco-blindaje.md`** (next free in the series; `20d` is the Modo Foco núcleo
  and must never be renumbered). See *Tutorial scope* at the end.

> **This is the second half of the Modo Foco idea.** The first half —
> [`docs/specs/2026-08-18-focus-mode.md`](2026-08-18-focus-mode.md), the full-screen session and the
> exit ritual — **shipped** (PR #53, commits `f4f3511`/`d4e8120`). This feature adds no screen; it
> puts muscle on the session that one already defines. Everything here is **opt-in and optional**:
> Modo Foco must stay complete and useful with all three measures switched off.

---

## Overview

Modo Foco currently changes *the app*. It does not change *the phone*. A person who opens Modo Foco
and then gets a WhatsApp call has been helped by exactly nothing, and the núcleo spec said so in as
many words (its **D8**: "a commitment device, not a kiosk").

This feature makes the session actually reduce interruptions, using the three things a normal
Play-Store app can genuinely do — and refusing, out loud, to pretend it can do the fourth.

| Measure | What it really does | Cost |
|---|---|---|
| **No molestar** | Silences calls and notifications device-wide for the length of the session | A special access grant + a global state we must reliably undo |
| **Fijar la pantalla** | Puts the app in Android's *screen pinning* mode: leaving needs a deliberate long-press of back + overview | None, but it is **friction, not a lock**, and OEM behaviour varies |
| **Pantalla siempre encendida** | Keeps the screen awake and hides the system bars while the session is on screen | None; dies with the window |

**What this feature refuses to promise.** Blocking or hiding *other apps* is not achievable by a
normal app: it needs *device owner* (enterprise provisioning) or an `AccessibilityService`, and
using accessibility to block apps is Play-Store-policy minefield. It is **out of scope and the app's
copy must never imply it** — see *Out of Scope* for the full argument, written down so nobody later
reads its absence as an oversight.

**The one failure mode this whole spec is organised around:**

> The grave bug here is **leaving someone's phone silent forever**. Android kills app processes for
> memory routinely; a session ends "by the bad way" — process death, swipe from recents, reboot —
> far more often than it ends by the ritual. A design that only undoes Do Not Disturb on the happy
> path is not 95% correct; it is broken.

**D4**, **D5** and **D6** are the answer: a **write-ahead receipt** persisted before the effect is
applied, a **pure state machine** that decides what to undo, and **three independent triggers** that
run that same undo — the deliberate exit, every cold start, and a `WorkManager` backstop that fires
whether or not anyone ever opens the app again.

The second organising rule is the one the núcleo established and this feature must not weaken:

> Every measure is optional, and **a denied permission never blocks anything**. Modo Foco works
> without Do Not Disturb, without pinning, without any of it. No crash, no dead-end flow, no nagging
> dialog — the same criterion `ReminderReceiver` already applies when `POST_NOTIFICATIONS` is denied.

The substance is the twelve decisions below. The acceptance criteria only enforce them.

---

## Decisions

### D1 — Three measures, one line between them: **window-scoped** vs **device-scoped**

The three measures look like a list of three similar options. They are not, and the whole
architecture falls out of the difference:

| Measure | Scope | Survives our process dying? | Needs undo machinery? |
|---|---|---|---|
| Pantalla siempre encendida + inmersivo | The **Activity window** | ❌ dies with the window | **No** |
| Fijar la pantalla | The **system's task stack** | ✅ but the system always offers its own escape, and we can *query* the state | **No receipt** — only a query |
| No molestar | The **device**, globally | ✅ and nothing else will ever undo it | **Yes** — the receipt (**D4**) |

Everything expensive in this spec exists for exactly one of the three. Saying so explicitly is what
keeps a future contributor from "consistently" persisting all three and inventing two restoration
problems that do not exist.

**Consequence for the UI:** the three are offered as three independent switches, never a single
"blindaje" master toggle. A master toggle would imply they share a failure mode; they do not.

**Immersive and keep-screen-on are deliberately one option, not two.** Both are pure window flags,
both need zero permission, both revert identically on dispose, and both serve the same sentence
("no me distraigas la pantalla"). Splitting them would buy a second switch nobody has an opinion
about.

### D2 — Do Not Disturb uses `INTERRUPTION_FILTER_PRIORITY`, **never** `_NONE`, and we never touch the global `Policy`

Two sub-decisions, both safety-driven:

**We set `INTERRUPTION_FILTER_PRIORITY`, not `INTERRUPTION_FILTER_ALARMS` and not
`INTERRUPTION_FILTER_NONE`.**

- `_NONE` ("total silence") suppresses **alarms**. Somebody in a focus session can miss the alarm
  that gets them to a meeting, a medication reminder, or a flight. For an app whose entire purpose is
  people who struggle with time, shipping a mode that silences the clock app is not a bold choice,
  it is a defect.
- `_PRIORITY` respects whatever priority policy the person has already configured for themselves
  (favourite contacts, repeat callers), which is the setting they already reasoned about once. We
  are borrowing their DND, not redefining it.

**We call only `setInterruptionFilter`. We never call `setNotificationPolicy`.**

Changing the global `NotificationManager.Policy` would let us allow/deny categories precisely — and
would add a second, structurally richer piece of global state to snapshot and restore, one whose
partial restoration is far easier to get wrong. One `Int` in, one `Int` out (**D4**) is the entire
device-level footprint of this feature, on purpose.

### D3 — The app's own alerting notifications must **survive** the session's DND, and we say what happens where they can't

Deciding this by accident is the trap the prompt calls out. Decided on purpose:

| Our surface | Under session DND | Why |
|---|---|---|
| `tasks_pending` — the ongoing pending-tasks summary (feature 06) | **Left alone.** It is already a silent, `IMPORTANCE_DEFAULT`, no-sound-no-vibration ongoing notification. DND changes nothing audible about it. | It is a glanceable summary, not an interruption. |
| `task_reminders` — lead-time reminders **and** the time-up alert (features 09 + `times-up-alert`) | **Must still alert.** | The time-up alert fires the instant a task's timer/deadline runs out. During a focus session the roster tasks *are* what the person is working on, so this is the single most relevant signal that exists — silencing it would make the session actively worse than not using the feature. |

**Mechanism:** the alerting channel is created with `setBypassDnd(true)`, guarded by
`isNotificationPolicyAccessGranted()` (setting it without the access is refused by the platform, so
it must never be attempted unguarded). This is a change to `ReminderNotificationHelper.ensureChannel`,
which today builds the channel through `NotificationChannelCompat.Builder` — a builder that does not
expose `setBypassDnd`. On **API 26+** the channel is therefore built as a platform
`NotificationChannel` so the flag can be set; below 26 there are no channels at all.

**Two honest limits, both accepted and both documented in the app's own copy rather than hidden:**

1. **API 24–25 have no channels.** `_PRIORITY` there consults the global policy, in which our
   notifications are not a priority category — so on those two API levels the app's own reminders
   *are* silenced while DND is on. Accepted as a platform limit in exactly the same spirit as the
   widget's "API 24–30 progress-bar tint" row, not as a bug to chase.
2. **Android may ignore `bypassDnd` on a channel that already exists.** Channel settings are
   user-owned after first creation; an install that already created `task_reminders` before this
   update may keep `bypassDnd = false`, and the app is not allowed to force it. **We do not delete
   and recreate the channel** — that would silently reset every preference the person has set on it,
   and the `times-up-alert` spec already refused a second channel for good reasons (its D11).
   Instead: the entry dialog's Do-Not-Disturb copy states plainly that system alarms always get
   through and that the app's own reminders may not, and the honest fallback is that the person can
   flip "Anular No molestar" for that channel in system settings themselves.

**This is the highest-value item on the manual on-device checklist** (see *Test split*): whether the
flag takes on a pre-existing channel cannot be determined from documentation and must be observed.

**Rejected alternative — schedule an `AlarmManager` "session over" alarm instead, so at least the
session's own end is audible.** Modo Foco sessions have no timer (deferred by the núcleo), so there
is no session-end instant to alarm on. The relevant alert is the *task's* time-up alarm, which
already exists; the right fix is to keep it audible, not to invent a second one.

### D4 — The **write-ahead receipt**: exactly one persisted key, written *before* the effect

Do Not Disturb is the only thing this feature does that outlives our process and that nothing else
will ever undo (**D1**). It gets exactly one piece of persistence, in the existing `user_prefs`
DataStore:

```
focus_shield_prior_filter : Int      # the interruption filter that was in effect before we changed it
```

**Presence of the key *is* the receipt.** Absent ⇒ we have nothing to undo. There is no separate
"did we apply it" boolean, no timestamp, no serialized options blob — every extra field is another
way for the record to disagree with itself.

**Write-ahead ordering is the whole point, and it must not be reordered:**

```
start:   write receipt  →  enqueue 12h backstop  →  apply DND  →  persist session  →  navigate
end:     restore (per D5)  →  clear receipt  →  cancel backstop  →  end session  →  navigate
```

Every interruption of that sequence converges on a safe state:

| Process dies… | Resulting state | Next restore run does |
|---|---|---|
| after the receipt, before applying DND | receipt present, filter unchanged | current filter ≠ what we apply ⇒ **leave it alone**, clear receipt (**D5**) |
| after applying DND, before persisting the session | receipt present, no session | restores the prior filter |
| mid-session | receipt present, session active | nothing — a live session keeps its shield (**D5**) |
| after restoring, before ending the session | filter restored, session still active | nothing; the person returns to a session with no DND, which is the **safe** direction |

The rule to remember, and the one the lesson is built around: **persist the intent to change global
state before you change it, and clear it only after you have undone it.** Doing it the other way
round produces a window in which the phone is silent and nothing on disk knows why.

### D5 — The restoration decision is a **pure function**, and it never fights the user

All of the restore logic that is worth testing is a pure `(state) → action` function in
`domain/focus/FocusShieldRestore.kt` — no `NotificationManager`, no Android imports, JVM-testable in
milliseconds, exactly like `isFocusSessionActive` before it:

```kotlin
sealed interface ShieldRestoreAction {
    data object None : ShieldRestoreAction                                  // leave everything as-is
    data class RestoreFilter(val filter: Int) : ShieldRestoreAction         // set + clear the receipt
    data object ClearReceiptOnly : ShieldRestoreAction                      // forget it; touch nothing
}

fun shieldRestoreActionFor(
    sessionActive: Boolean,
    priorFilter: Int?,          // the receipt; null when absent
    currentFilter: Int,         // as the system reports it right now
    appliedFilter: Int,         // what we set when we applied the shield (INTERRUPTION_FILTER_PRIORITY)
    policyAccessGranted: Boolean,
): ShieldRestoreAction
```

The complete table — this is the state machine, and each row is one acceptance criterion:

| # | `sessionActive` | receipt | `currentFilter` | access | Action | Why |
|---|---|---|---|---|---|---|
| 1 | **true** | any | any | any | `None` | A running session keeps its shield. Restoring here would silently disarm a live session. |
| 2 | false | absent | any | any | `None` | Nothing was ever applied. |
| 3 | false | present | `== appliedFilter` | true | `RestoreFilter(prior)` | The normal case: we set it, we put it back. |
| 4 | false | present | `!= appliedFilter` | true | `ClearReceiptOnly` | **The person changed DND themselves mid-session.** Their choice wins; we forget our receipt rather than overwrite them. |
| 5 | false | present | any | **false** | `ClearReceiptOnly` | The special access was revoked. We cannot act, and we must not crash or nag — we drop the record. |
| 6 | false | present | `INTERRUPTION_FILTER_UNKNOWN` | true | `None` | The system could not tell us the current filter. Keep the receipt and try again later, rather than clearing a record we may still need. |

Row 4 is the decision most worth stating out loud: **restoration is not "put it back no matter
what".** An app that stomps a manual DND change the moment a session ends is a worse citizen than
one that occasionally leaves a receipt behind.

**Rejected alternative — restore unconditionally.** Two lines shorter, and it means a person who
deliberately turned DND *on* during a session (because a meeting started) has it silently turned off
when the session ends. Global state is borrowed, not owned.

### D6 — Three triggers, **one** restore function

The same function is the only place that consumes **D5**'s decision, called from exactly three
places:

1. **The deliberate exit** — either exit path in `FocusViewModel` (the ritual or "Abandonar sesión"),
   before `endFocusSession()`. The fast, normal path.
2. **Every cold start** — from `NeverLateApplication.onCreate`, alongside the existing
   `BootRescheduleWorker.enqueue(this)` call. Covers "killed from recents / by memory pressure, then
   reopened", which is the *common* case, not an edge case.
3. **A 12-hour `WorkManager` backstop** — `FocusShieldRestoreWorker`, enqueued as **unique** work
   (`ExistingWorkPolicy.REPLACE`) at session start with an initial delay equal to the session's own
   maximum age, cancelled on the deliberate exit. Covers the one case the other two cannot: **the
   person never opens the app again.**

**Why `WorkManager` and not a fourth `AlarmManager` alarm.** The núcleo's **D7** deliberately chose a
pure expiry predicate over an alarm, and that reasoning still holds *for the session*: a session
expiring needs no event, only to be false the next time somebody asks. Global device state is the
exact opposite — nobody is going to ask, so the undo genuinely needs a trigger. But it does **not**
need an *exact* one: "sometime in the next few hours after the 12-hour mark" is a perfectly good
time to un-silence a phone whose owner stopped using the app half a day ago. That makes it the same
deferrable background work `SyncWorker`, `TaskSurfacesRefreshWorker` and `BootRescheduleWorker`
already are, and it buys three things a new alarm would have cost us:

- `WorkManager` **persists its own queue and re-enqueues after `BOOT_COMPLETED`** — reboot handling
  is free, with no new receiver and no new manifest entry.
- **No `SCHEDULE_EXACT_ALARM` involvement**, no new `ReminderKind`, and — critically — no need to
  carve a non-task request code out of `requestCodeFor(taskId, kind)`'s per-task numbering, which is
  a collision analysis this feature has no reason to open.
- It follows the worker precedent already in the repo, including the "construct dependencies by
  hand from `applicationContext`" shape `BootRescheduleWorker` uses (no `@HiltWorker`, no new
  dependency).

**`BootRescheduleWorker` was considered as the host and rejected.** It is *about alarms*: it reads
`remindersEnabled` and returns early when reminders are off — which has nothing to do with whether a
phone is stuck in Do Not Disturb, and would silently skip the restore for every user who has
reminders disabled. Overloading it would couple two unrelated invariants inside one early return.
The **cold-start hook** it shares (`NeverLateApplication.onCreate`) is reused; the worker is not.

**The delay constant is the session's own.** `FOCUS_SESSION_MAX_AGE_MILLIS` (today `private` in
`domain/tasks/FocusSession.kt`) is promoted to `internal`/public and read by the worker, so the
backstop can never drift away from the expiry it backs. Two constants that must agree is one
constant.

### D7 — Screen pinning is friction, is presented as friction, and reports **verified** state

`Activity.startLockTask()` from a normal (non-device-owner) app enters Android's *screen pinning*
mode. The system shows its own dialog, and the person leaves by holding **back + overview**. That is
real friction and it is worth offering. It is **not** a lock, and the entry dialog's copy for this
option says so in one plain sentence.

Three rules:

- **Requested ≠ active.** After calling `startLockTask()`, the app reads
  `ActivityManager.getLockTaskModeState()` back. The in-session indicator (**Visual & UX Design**)
  shows only what is *verified active*, never what was requested. OEM behaviour here varies enough
  that a UI claiming "fijada" while the phone is not pinned is a straightforward lie.
- **No receipt, only a query** (**D1**). On cold start, if no session is active and
  `getLockTaskModeState() != LOCK_TASK_MODE_NONE`, the app calls `stopLockTask()`. Nothing needs to
  be persisted because the system already knows the answer.
- **`startLockTask()` can throw or be refused** (pinning disabled system-wide, an OEM that restricts
  it, an activity in an unsupported state). It is wrapped, the failure is swallowed, the session
  continues, and the indicator simply does not show pinning as active (**D10**).

**Interaction with the exit ritual — checked deliberately.** While pinned, the exit panel, the
slide-to-unlock and, crucially, **"Abandonar sesión" are all still on screen and still one tap
away**: pinning restricts leaving the *app*, not moving inside it. The núcleo's **D3** rule ("the
emergency exit is gated on nothing the person can forget, fail to perform, or wait out") is
untouched, and the system's own back+overview gesture is a further exit on top of ours. Pinning must
never be applied to a screen other than the Focus session, and it is released on either exit path
before navigation.

### D8 — Immersive and keep-screen-on are **composable-scoped effects** with no undo machinery

Both are applied by a single `DisposableEffect` in the Focus screen and reverted in its `onDispose`:

- `WindowInsetsControllerCompat(window, view).hide(WindowInsetsCompat.Type.systemBars())` with
  `systemBarsBehavior = BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE`, so a swipe always brings the bars
  back temporarily. Reverted with `show(...)`.
- `window.addFlags(FLAG_KEEP_SCREEN_ON)` / `clearFlags(...)` on dispose.

**Never at `Activity` level, never in `MainActivity.onCreate`.** `MainActivity` today does exactly
one window thing (`enableEdgeToEdge()`) and that stays true: a flag set in `onCreate` outlives the
session by definition and would keep the whole app's screen awake forever. Scoping them to the
composable means leaving the screen — by ritual, by abandon, by process death, by anything — reverts
them for free. This is precisely why they need no receipt, no worker and no state machine (**D1**),
and contrasting them against Do Not Disturb is the clearest teaching example the lesson has.

`BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE` is a hard requirement, not a default: an immersive mode the
person cannot escape by swiping is one more way to trap somebody.

**Accepted trade-off:** hiding the status bar hides the clock, in a time-management app. The session
top bar keeps showing the session progress, a swipe restores the bars, and the option is off by
default for anyone who dislikes it. Not deferred, not a debt — a decision.

### D9 — The special-access pattern is **extracted and shared**, not written twice — and it re-checks on resume

`ACCESS_NOTIFICATION_POLICY` is a **special access**, not a runtime permission: it is never granted
by a permission dialog. The idiom is *check → explain → send the person to a system Settings screen*,
and the repo already has exactly that in `SettingsScreen.kt`'s private `ExactAlarmPermissionNotice`
(feature 09's `SCHEDULE_EXACT_ALARM`).

**That composable is promoted, not copied**, into `ui/components/SpecialAccessNotice.kt`:

```kotlin
@Composable
fun SpecialAccessNotice(
    isGranted: () -> Boolean,       // re-evaluated on every ON_RESUME
    message: String,
    actionLabel: String,
    settingsIntent: () -> Intent,
    modifier: Modifier = Modifier,
)
```

- `SettingsScreen`'s exact-alarm notice becomes a thin caller — a **behaviour-preserving move**, and
  its existing tests must stay green untouched.
- The Focus entry dialog is the second caller, for
  `Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS`.

This is the same "one mapping, thin per-call-site resolvers" shape the widget refactor established
and the núcleo repeated when it promoted `colorForUrgency` out of `TasksScreen.kt`.

**The extraction also fixes a real defect it inherits.** The existing notice evaluates the grant once
per composition and its own KDoc admits this is only "good enough": returning from system settings
after granting leaves it stale until the screen is left and reopened. The shared component observes
`Lifecycle.Event.ON_RESUME` and re-reads, so the notice disappears the moment the person comes back
having granted it. That is a visible improvement to the **Settings** screen delivered as a side
effect of doing this properly — and one more reason the answer is "extract", not "duplicate".

### D10 — Graceful degradation is the **default path**, not the error path

A missing grant is an ordinary, expected state — not an error, not a failure, not something the app
gets to be unhappy about. Concretely, for every measure:

| Situation | Behaviour |
|---|---|
| DND access not granted at session start | The session starts. DND is simply not applied. The indicator does not show it. **No receipt is written.** |
| DND access revoked mid-session | Nothing happens during the session. At restore, row 5 of **D5** clears the receipt. No crash. |
| `setInterruptionFilter` throws (`SecurityException` on an OEM that disagrees) | Caught; the receipt is cleared; the session continues. |
| `startLockTask()` throws or does not take | Caught; the indicator does not claim pinning; the session continues. |
| The device has no `NotificationManager`/`ActivityManager` (test/edge contexts) | Null-guarded, same as `ExactAlarmPermissionNotice`'s `alarmManager == null` guard. |

**No blocking dialog, no repeated prompting, no "you must grant this to continue".** The person is
told once, in the entry dialog, next to the switch they just touched, with a one-tap route to the
system screen — and then left alone. This is the same criterion `ReminderReceiver` applies when
`POST_NOTIFICATIONS` is denied: check, return, show nothing, never crash.

### D11 — Defaults follow **D1**'s line: window-scoped on, device-scoped off

The three switches remember the person's last choice in `user_prefs` and pre-fill the entry dialog
with it. On a **fresh install**, with nothing stored:

| Measure | Default | Why |
|---|---|---|
| Pantalla siempre encendida + inmersivo | **On** | It cannot outlive the session, needs no permission, and changes nothing outside our own window. Nothing about it can surprise anyone tomorrow. |
| Fijar la pantalla | **Off** | It changes how the *system* behaves and shows a system dialog. Opting somebody into that silently is not acceptable. |
| No molestar | **Off** | It mutates a global device setting and requires a special access grant. Defaulting it on would be defaulting people into a silent phone. |

The rule, stated so it survives future edits: **a measure defaults on only if it cannot outlive the
session.** (Flagged in *Review* — the alternative "all three off, discoverability be damned" is a
defensible product call and deserves an explicit yes/no.)

### D12 — Minimal interface growth, and the **nine**-fake problem

`UserPreferencesRepository` grows by exactly **two** methods and `UserPreferences` by exactly **two**
fields:

```kotlin
val focusShieldOptions: FocusShieldOptions = FocusShieldOptions()   // 3 booleans, the remembered defaults (D11)
val focusShieldPriorFilter: Int? = null                             // the receipt (D4)

suspend fun saveFocusShieldOptions(options: FocusShieldOptions)
suspend fun saveFocusShieldPriorFilter(filter: Int?)                // null clears the receipt
```

Everything else — applying, verifying, restoring — goes through `FocusShieldController`, which is
*not* on this interface. Keeping the surface this small is a deliberate cost decision, because:

**The ripple is now nine files, not seven.** `grep -rln ": UserPreferencesRepository" app/src/test
app/src/androidTest` returns **nine** hand-written fakes (the núcleo added two of them):

```
app/src/test/java/com/neverlate/data/sync/SyncTestDoubles.kt
app/src/test/java/com/neverlate/ui/settings/SettingsViewModelTest.kt
app/src/test/java/com/neverlate/ui/tasks/TasksViewModelTest.kt
app/src/test/java/com/neverlate/ui/onboarding/OnboardingViewModelTest.kt
app/src/test/java/com/neverlate/ui/focus/FocusViewModelTest.kt
app/src/test/java/com/neverlate/ui/notification/ReminderSchedulingRepositoryTest.kt
app/src/androidTest/java/com/neverlate/ui/tasks/TasksEmptyStatePersonalizationTest.kt
app/src/androidTest/java/com/neverlate/ui/tasks/TasksRouteSnackbarTest.kt
app/src/androidTest/java/com/neverlate/ui/focus/FocusTestDoubles.kt
```

This is the **fourth consecutive feature** to pay this tax, and it is now growing. The recommendation
is to stop deferring it: **consolidate into one shared fake per source set — two files instead of
nine** (`app/src/test/.../data/FakeUserPreferencesRepository.kt` and its `androidTest` twin), as a
separate, mechanical first commit on this branch, before any behaviour change. Two files rather than
one because `test/` and `androidTest/` are separate source sets that cannot share code without a new
Gradle module or `testFixtures` wiring — which *is* out of scope here, and is why the "just
consolidate them" instinct has stalled three times. Flagged in *Review* for an explicit yes/no; if
the answer is no, all nine are updated identically (mutate the backing `MutableStateFlow`) and the
consolidation gets a row in `docs/diferidos.md`.

---

## User Stories

### US-1 — The session actually quiets my phone

> As someone who opens Modo Foco and then gets three WhatsApp calls, I want the session to silence
> the phone while it lasts, so that the mode does something to the device and not just to the app.

**Acceptance criteria**

- The entry dialog offers a "No molestar" switch alongside the exit code.
- Starting a session with it on sets the device's interruption filter to *priority*, and the person's
  own priority rules (favourite contacts, repeat callers) keep applying.
- System **alarms are never silenced** by this feature.
- Ending the session — by the ritual or by abandoning — puts the interruption filter back to whatever
  it was before the session started.
- The session's own screen shows which measures are actually in effect.

### US-2 — My phone is never left silent

> As someone whose phone kills apps to free memory, I want Do Not Disturb undone even when the
> session ends badly, so that a focus session can never cost me a missed call tomorrow.

**Acceptance criteria**

- Killing the app from recents mid-session and reopening it restores the previous interruption filter
  (once the session is no longer active).
- Rebooting the phone mid-session and reopening the app does the same.
- Never reopening the app at all still restores it: a background job runs at the session's 12-hour
  expiry and does the restore with no user action.
- If the person changed Do Not Disturb themselves during the session, the app leaves their change
  alone and forgets its own record.
- If the special access was revoked in the meantime, nothing crashes and the record is dropped.

### US-3 — Leaving the app takes a deliberate act

> As someone who reflexively swipes to another app, I want an extra step in the way, so that leaving
> is a decision rather than a twitch.

**Acceptance criteria**

- The entry dialog offers a "Fijar la pantalla" switch whose description says, in plain words, that
  it is extra friction and that the phone can still be unpinned by holding back + overview.
- Turning it on pins the app for the length of the session and unpins it on either exit.
- If pinning does not take (unsupported, refused, or the person unpins manually), the session
  continues normally and the in-session indicator does not claim it is active.
- Being pinned never blocks the exit panel, the slide, or "Abandonar sesión".

### US-4 — The screen stays with me

> As someone who loses the thread when the screen sleeps, I want the session to keep the screen awake
> and out of the way, so that looking away for a minute doesn't cost me the context.

**Acceptance criteria**

- The entry dialog offers a "Pantalla siempre encendida" switch (which also hides the system bars).
- While the session is on screen, the display does not sleep and the status/navigation bars are
  hidden; swiping brings them back temporarily.
- Leaving the session — by any route, including process death — restores normal screen behaviour and
  the system bars. Nothing about it persists into the rest of the app.

### US-5 — A denied permission never blocks me

> As someone who does not want to grant Do Not Disturb access, I want Modo Foco to work anyway, so
> that saying no costs me the option and nothing else.

**Acceptance criteria**

- With the access not granted, starting a session with the DND switch on still starts the session;
  DND is simply not applied and the indicator does not show it.
- The entry dialog explains, inline next to the switch, that the access is missing and offers one tap
  to the system screen that grants it.
- Returning from that system screen having granted it updates the notice **without** having to close
  and reopen the dialog or the screen.
- The app never blocks a flow, repeats a prompt, or crashes because a grant is missing.

### US-6 — The app tells me the truth about what it controls

> As a user deciding whether to trust this, I want to know that other apps are not being blocked, so
> that I am not surprised when WhatsApp still opens.

**Acceptance criteria**

- No copy anywhere claims the app blocks, disables, or hides other apps.
- The pinning option is described as friction, never as a lock.
- The Do-Not-Disturb option's copy states that system alarms always get through.
- The in-session indicator reflects **verified** state, never merely requested state.

---

## Acceptance Criteria (consolidated)

### Behavioural — the restoration state machine (pure, JVM-tested)

- **AC-1** — `shieldRestoreActionFor` returns `None` whenever `sessionActive` is `true`, for every
  combination of the other arguments (**D5** row 1).
- **AC-2** — It returns `None` when the receipt is absent (row 2).
- **AC-3** — It returns `RestoreFilter(prior)` when the session is inactive, the receipt is present,
  the current filter equals the applied filter, and access is granted (row 3).
- **AC-4** — It returns `ClearReceiptOnly` when the current filter differs from the applied filter —
  the person changed DND themselves and their change is not overwritten (row 4).
- **AC-5** — It returns `ClearReceiptOnly` when policy access is not granted (row 5).
- **AC-6** — It returns `None` when the current filter is `INTERRUPTION_FILTER_UNKNOWN`, keeping the
  receipt for a later attempt (row 6).
- **AC-7** — The function has no Android imports and lives in `domain/`, provably JVM-testable
  without Robolectric.

### Behavioural — Do Not Disturb

- **AC-8** — Starting a session with the measure on and access granted sets the interruption filter
  to `INTERRUPTION_FILTER_PRIORITY`. `INTERRUPTION_FILTER_NONE` is never set anywhere in the codebase
  (grep-checkable).
- **AC-9** — `setNotificationPolicy` is never called anywhere in the codebase (grep-checkable,
  **D2**).
- **AC-10** — The receipt (`focus_shield_prior_filter`) is written **before** `setInterruptionFilter`
  is called, and cleared only **after** a successful restore (**D4** ordering).
- **AC-11** — With access **not** granted, no receipt is written and no filter call is attempted.
- **AC-12** — The receipt lives in the existing `user_prefs` DataStore — never a second DataStore,
  never `EncryptedTokenStorage`, never Room.
- **AC-13** — An unreadable/absent `focus_shield_prior_filter` reads as `null` ("no receipt") and
  never as a crash — the same fail-open tolerance the núcleo's **D6** established for the session
  keys.
- **AC-14** — `ReminderNotificationHelper.ensureChannel` sets `bypassDnd = true` on the
  `task_reminders` channel on API 26+ **only when** `isNotificationPolicyAccessGranted()` is true,
  and never touches `tasks_pending` (**D3**).

### Behavioural — the three restore triggers

- **AC-15** — Both exit paths (the ritual and "Abandonar sesión") run the restore before
  `endFocusSession()`, and both cancel the backstop worker.
- **AC-16** — `NeverLateApplication.onCreate` runs the restore on every cold start, next to the
  existing `BootRescheduleWorker.enqueue` call, and is as tolerant of an uninitialised WorkManager as
  that call already is.
- **AC-17** — `FocusShieldRestoreWorker` is enqueued at session start as **unique** work with
  `ExistingWorkPolicy.REPLACE`, so a second session never stacks a second backstop.
- **AC-18** — The worker's initial delay is read from the **same constant** as the session's expiry
  (`FOCUS_SESSION_MAX_AGE_MILLIS`), asserted by a test so the two can never drift (**D6**).
- **AC-19** — The worker performs the identical `shieldRestoreActionFor`-driven restore as the other
  two triggers — one function, three callers, asserted against a fake controller.
- **AC-20** — Running the restore twice in a row is a no-op the second time (idempotent).

### Behavioural — screen pinning

- **AC-21** — Starting a session with the measure on calls `startLockTask()` and then reads
  `getLockTaskModeState()` back; the in-session indicator reflects the **read-back** value (**D7**).
- **AC-22** — Both exit paths call `stopLockTask()` before navigating away.
- **AC-23** — On cold start with **no** active session and `getLockTaskModeState() != LOCK_TASK_MODE_NONE`,
  the app calls `stopLockTask()`.
- **AC-24** — A throwing/refused `startLockTask()` is caught; the session starts normally and no
  error state is shown.
- **AC-25** — Nothing about pinning is persisted (no receipt) — the system's own state is the source
  of truth (**D1**).

### Behavioural — immersive and keep-screen-on

- **AC-26** — The window flag and the immersive call are applied by a `DisposableEffect` in the Focus
  screen and reverted in its `onDispose`; neither appears in `MainActivity.onCreate` or any other
  Activity-level location (grep-checkable).
- **AC-27** — `systemBarsBehavior` is `BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE`, so the bars are always
  recoverable by swipe (**D8**).
- **AC-28** — Navigating away from the Focus screen clears `FLAG_KEEP_SCREEN_ON` and shows the system
  bars, proven by an instrumented test that inspects the activity window flags before, during and
  after.

### Behavioural — options, defaults and degradation

- **AC-29** — The three switches persist to `user_prefs` and pre-fill the entry dialog on the next
  session.
- **AC-30** — On a fresh install the screen measure defaults **on** and the other two default **off**
  (**D11**), unit-tested against the `UserPreferences` defaults.
- **AC-31** — With any grant missing or any platform call failing, the session **still starts**, the
  ritual still works, and both exits still work — no crash, no blocked flow, no repeated prompt
  (**D10**).
- **AC-32** — The núcleo's **D3** guarantee is untouched: "Abandonar sesión" remains enabled from the
  first frame under every combination of measures, including while pinned and immersive. Regression
  asserted, not assumed.

### Accessibility and i18n

- **AC-33** — Every switch row in the entry dialog is a single ≥48dp target that toggles on tap of
  the whole row, with the label as its accessible name and the switch state as its state.
- **AC-34** — The entry dialog **scrolls** rather than clipping at the largest font scale, with the
  confirm/cancel actions always reachable.
- **AC-35** — The `SpecialAccessNotice` message and its action button are readable and fully
  reachable at the largest font scale.
- **AC-36** — The in-session indicator conveys each active measure with **text**, not icon or colour
  alone, and carries meaningful `contentDescription`s.
- **AC-37** — Every new user-facing string ships in **both** `values/` (Spanish base) and
  `values-en/`, with no hardcoded text in composables.

### Definition-of-Done items this feature touches

- **AC-38** — `AndroidManifest.xml` declares `<uses-permission android:name="android.permission.ACCESS_NOTIFICATION_POLICY" />`
  with a comment in the same style as the existing permission blocks, explaining that it is a
  **special access** (never a runtime request) and how it degrades.
- **AC-39** — The manifest change is reflected in **`CLAUDE.md`** (the `ui/focus/` module-map row and
  the permission narrative) and in **`docs/arquitectura.md`**'s *Transversal — Permisos y manifest*
  section, in this same branch.
- **AC-40** — `docs/arquitectura.md` also records the write-ahead-receipt decision (**D4**) and the
  window-scoped vs device-scoped line (**D1**) — the two things a future reader will otherwise
  re-derive wrongly.
- **AC-41** — **No Room migration**: the database version is untouched.
- **AC-42** — **No contract change**: `docs/api/contract.md` is not touched; nothing here leaves the
  device.
- **AC-43** — **No new dependency** in `gradle/libs.versions.toml`. `WorkManager`,
  `WindowInsetsControllerCompat` and `NotificationManagerCompat` are all already on the classpath.
- **AC-44** — Loading, empty and error states stay covered on every screen touched; this feature adds
  **no new error state** by design (**D10** turns every failure into an absence).
- **AC-45** — All nine `UserPreferencesRepository` fakes compile against the two new methods (or two
  consolidated fakes do, if *Review* approves **D12**'s consolidation).
- **AC-46** — `docs/mockups/README.md`'s **existing** "Focus Mode full-screen session + exit ritual"
  row is **extended** with an `**Update (feature focus-mode-shielding)**` paragraph. No second row is
  created.
- **AC-47** — The Spanish lesson `tutorial/20e-modo-foco-blindaje.md` is written and listed in
  `tutorial/README.md` (and in `docs/conceptos-pendientes.md` if it carries a row there). No existing
  lesson is renumbered.
- **AC-48** — `timeout 600 ./gradlew :app:testDebugUnitTest --console=plain` is green before
  committing.

### Visual

- **AC-V1** — The entry dialog keeps its existing shape (title, honest explanation, 4-digit code
  field, Empezar/Cancelar) and adds the three measures under one clearly labelled section — the code
  field remains the primary input, not something the person has to scroll past.
- **AC-V2** — Each measure row is label + one-line honest description + `Switch`, using the Material 3
  type scale (`bodyLarge` / `bodySmall` `onSurfaceVariant`) — no bespoke typography, no new colours.
- **AC-V3** — The Do-Not-Disturb row, when the access is missing, shows the shared
  `SpecialAccessNotice` **inline directly beneath it** (not at the top or bottom of the dialog), so
  the explanation is adjacent to the control it explains.
- **AC-V4** — The in-session indicator is a compact, **non-interactive** row directly under the top
  bar, listing only the verified-active measures with an icon **and** a short label each; it is
  visually subordinate to the session progress and can never be mistaken for the exit control.
- **AC-V5** — With no measure active, the indicator row is **absent** — not an empty placeholder.
- **AC-V6** — In immersive mode, the bottom "Salir del Modo Foco" action stays fully visible and
  ≥48dp, respecting `safeDrawing`/navigation-bar insets so it never sits under the gesture area.
  **No control needed to leave the session is ever hidden by the immersive state.**
- **AC-V7** — The whole entry dialog and the indicator reflow correctly at the largest font scale
  (AC-34) and read correctly in both light and dark themes.
- **AC-V8** — No new colour token, no focus-specific palette: the indicator uses
  `surfaceVariant`/`onSurfaceVariant` and the existing brand chrome.
- **AC-V9** — Verified in the real app (`/run`) at compact width, light and dark, default and largest
  font scale, once with each measure on and once with all three off.

---

## Visual & UX Design

### Mockup slice — **none claimed; the existing Focus row is extended**

The master mockup [`rediseno-ux-ui.html`](../mockups/rediseno-ux-ui.html) contains no Modo Foco
screen at all (the núcleo already established this), and certainly no permission notices, no
device-measure switches and no shield indicator. This feature therefore **claims no slice**.

Per the prompt's explicit instruction, it does **not** create a second row in
[`docs/mockups/README.md`](../mockups/README.md): it appends an
`**Update (feature focus-mode-shielding, 2026-08-18)**` paragraph to the existing
*"Focus Mode full-screen session + exit ritual"* row, in the same style as the widget row's
successive updates (AC-46).

The mockup remains the *direction*: the entry dialog and the indicator use the app's real theme
tokens and existing components only.

### Where the three measures are offered — the entry dialog, extended

The measures are chosen **at entry**, in the dialog that already exists, and not from Settings. That
is deliberate: they are properties of *this session*, decided at the moment of committing to it,
alongside the exit code. A Settings home would separate the choice from the commitment and would
imply the measures apply outside a session, which they never do.

```
┌──────────────────────────────────────────────┐
│ Modo Foco                                    │
│                                              │
│ Esta pantalla te muestra solo tus tareas.    │  ← existing honest copy (D8 of the núcleo),
│ No bloquea el teléfono ni otras apps.        │    unchanged
│                                              │
│  Código de salida  [ • • • • ]               │  ← existing 4-digit field, still primary
│                                              │
│  ── Durante la sesión ─────────────────────  │
│  Pantalla siempre encendida         [ ●— ]   │  ← default ON (D11)
│    La pantalla no se apaga y se ocultan      │
│    las barras del sistema. Desliza para      │
│    recuperarlas.                             │
│                                              │
│  No molestar                        [ —○ ]   │  ← default OFF
│    Silencia llamadas y notificaciones        │
│    mientras dura la sesión. Las alarmas      │
│    del sistema siempre suenan.               │
│    ⚠ Necesita permiso de No molestar.        │  ← SpecialAccessNotice, inline (AC-V3)
│      [ Conceder acceso ]                     │
│                                              │
│  Fijar la pantalla                  [ —○ ]   │  ← default OFF
│    Añade fricción para salir de la app.      │
│    No es un bloqueo: puedes soltarla         │
│    manteniendo pulsado Atrás + Recientes.    │
│                                              │
│              [ Cancelar ]  [ Empezar ]       │
└──────────────────────────────────────────────┘
```

- Each row is one ≥48dp tappable unit (label + description + switch), toggled by tapping anywhere on
  it, with the switch as its semantic state (AC-33).
- The descriptions are the **honesty budget** of this feature. Every one of them says what the
  measure does *not* do. None of them uses "bloquear" for anything Android will not actually block
  (US-6).
- The dialog scrolls (AC-34). At the largest font scale this content is tall, and clipped confirm
  buttons in a dialog that is the only way into the feature would be a hard failure.

### How active measures are shown during the session

A compact, non-interactive row under the top bar, listing **only what is verified active** (**D7**):

```
┌──────────────────────────────────────────────┐
│ ▓▓ Modo Foco                     3 de 7 ▓▓▓▓ │  branded top bar (unchanged)
├──────────────────────────────────────────────┤
│  🔕 No molestar   📌 Pantalla fijada          │  ← indicator: verified state only, text + icon
├──────────────────────────────────────────────┤
│  ☐  ▣  Preparar la presentación              │
│  ...                                          │
```

- Text **and** icon for each measure — never icon or colour alone (AC-36).
- Absent entirely when nothing is active (AC-V5): an empty strip is visual noise on a screen whose
  entire premise is the absence of noise.
- Non-interactive on purpose. Letting someone toggle DND mid-session would add a fourth way to reach
  global state and a fourth path through the receipt machinery, for a need nobody has expressed. If
  they want it off, the exit is one tap away.

### Tokens and components reused (extend, don't duplicate)

- **`ui/components/SpecialAccessNotice`** — extracted from `SettingsScreen.kt`'s
  `ExactAlarmPermissionNotice` (**D9**); Settings becomes its first caller, the entry dialog its
  second.
- The existing `FocusEntryDialog` `AlertDialog` shape, `OutlinedTextField` and button pair — grown,
  not replaced.
- Material 3 `Switch`, the standard type scale, `surfaceVariant`/`onSurfaceVariant`,
  `minimumInteractiveComponentSize()` — the same ≥48dp idiom every other screen uses.
- `brandedTopAppBarColors()` and the whole Focus screen chrome — **untouched**.

### Deferred, and to where

| Deferred | To where |
|---|---|
| Blocking, hiding or limiting **other apps** | **Never** — not deferred, refused. See *Out of Scope* for the argument. |
| Toggling the measures **mid-session** | [`docs/diferidos.md`](../diferidos.md) |
| Choosing the measures from **Settings** (as durable defaults edited outside the entry flow) | `docs/diferidos.md` |
| Blocking the **notification shade** while pinned | `docs/diferidos.md`; needs an approach we do not have |
| A per-session **timer** (and therefore an audible session-end alert of our own) | `docs/diferidos.md`, already deferred by the núcleo |
| **Session history** of which measures were used | Needs a Room table, hence a migration — excluded, as in the núcleo |
| Consolidating the nine `UserPreferencesRepository` fakes, if *Review* declines **D12** | `docs/diferidos.md` |

---

## Technical Approach

Nine files change, four are new, plus strings and test fakes. All in `app/`.

| File | What changes |
|---|---|
| **NEW** `domain/focus/FocusShieldRestore.kt` | `ShieldRestoreAction` + `shieldRestoreActionFor(...)` — the pure state machine (**D5**). No Android imports. Also the home of `FocusShieldOptions(keepScreenOn, doNotDisturb, screenPinning)` with **D11**'s defaults. |
| **NEW** `ui/focus/FocusShieldController.kt` | Interface (`applyDoNotDisturb()`, `restore(sessionActive: Boolean)`, `isPolicyAccessGranted()`, `currentInterruptionFilter()`) + `AndroidFocusShieldController` implementation over `NotificationManager` and `UserPreferencesRepository`. Context-scoped only — everything needing an `Activity` lives in composable effects or `MainActivity` (**D1**). All platform calls wrapped per **D10**. |
| **NEW** `ui/focus/FocusShieldRestoreWorker.kt` | `CoroutineWorker`, no `@HiltWorker` — constructs its dependencies from `applicationContext` exactly as `BootRescheduleWorker` does. `enqueue(context)` (unique, `REPLACE`, initial delay = `FOCUS_SESSION_MAX_AGE_MILLIS`) and `cancel(context)`. |
| **NEW** `ui/components/SpecialAccessNotice.kt` | The extracted shared notice (**D9**), with `ON_RESUME` re-evaluation. |
| `AndroidManifest.xml` | One `<uses-permission android:name="android.permission.ACCESS_NOTIFICATION_POLICY" />` with an explanatory comment matching the existing blocks' style. **No new receiver, no new service** — WorkManager brings its own. |
| `MainActivity.kt` | Nothing at window level (**D8**). One addition only: the cold-start pinning release (**AC-23**) — if no session is active and the task is in lock-task mode, `stopLockTask()`. `enableEdgeToEdge()` stays the only other window call. |
| `NeverLateApplication.kt` | Runs the cold-start restore (**AC-16**) next to the existing `BootRescheduleWorker.enqueue`, with the same tolerance for an uninitialised WorkManager. |
| `ui/focus/FocusScreen.kt` | A `DisposableEffect` applying/reverting immersive + `FLAG_KEEP_SCREEN_ON` (**D8**); the verified-state indicator row under the top bar. The exit panel, the slide bar and the abandon button are **not touched** — the núcleo's **D3**/**D9** guarantees must survive this feature byte-for-byte. |
| `ui/focus/FocusViewModel.kt` | Both exit paths call the shield restore + `stopLockTask` before `endFocusSession()`, and cancel the backstop worker (**AC-15**). Exposes the verified active-measure state for the indicator. |
| `ui/tasks/TasksScreen.kt` | `FocusEntryDialog` grows the three switch rows, the inline `SpecialAccessNotice` and a scroll container; its `onConfirm` now carries `(code, FocusShieldOptions)`. |
| `ui/navigation/AppNavHost.kt` | `onFocusClick`'s existing coroutine gains **D4**'s start sequence, in order: save options → write receipt → enqueue backstop → apply DND → `startLockTask` (Activity) → `startFocusSession` → navigate. |
| `ui/settings/SettingsScreen.kt` | `ExactAlarmPermissionNotice` deleted; its call site becomes a `SpecialAccessNotice(...)` — behaviour-preserving (**D9**), existing tests untouched and still green. |
| `ui/notification/ReminderNotificationHelper.kt` | `ensureChannel` sets `bypassDnd = true` on `task_reminders` on API 26+ when policy access is granted (**D3**/**AC-14**). |
| `data/UserPreferencesRepository.kt` | Two fields, two methods, two DataStore keys (**D12**), fail-open reads (**AC-13**). Same `user_prefs` file — never a second DataStore. |
| `domain/tasks/FocusSession.kt` | `FOCUS_SESSION_MAX_AGE_MILLIS` promoted from `private` so the worker shares it (**AC-18**). No other change; `FocusSession` itself gains **no field**. |
| `di/StorageModule.kt` | One `@Provides` for `FocusShieldController` (it needs `@ApplicationContext` + `UserPreferencesRepository`, both already provided here). **No new Hilt module.** |
| `res/values/strings.xml` + `res/values-en/strings.xml` | ~16 new strings, Spanish base first, English mirrored. |

### The two sequences, in full

**Start (write-ahead, D4):**

```
entry dialog confirm(code, options)
  │
  ├─ saveFocusShieldOptions(options)                    # D11 defaults for next time
  │
  ├─ if options.doNotDisturb && policyAccessGranted:
  │     ├─ saveFocusShieldPriorFilter(currentFilter)    # ① RECEIPT FIRST
  │     ├─ FocusShieldRestoreWorker.enqueue(ctx)        # ② backstop before the effect
  │     └─ setInterruptionFilter(PRIORITY)              # ③ the effect
  │
  ├─ if options.screenPinning: try { activity.startLockTask() } catch { }   # D7/D10
  ├─ startFocusSession(FocusSession(now, code, focusRosterFor(tasks)))
  └─ navigate(Routes.FOCUS)                             # immersive + keep-screen-on applied there (D8)
```

**End (either exit path, or any restore trigger):**

```
restore(sessionActive = false)
  │
  ├─ action = shieldRestoreActionFor(sessionActive, receipt, currentFilter, PRIORITY, accessGranted)
  ├─ when (action):
  │     None             -> return
  │     RestoreFilter(f) -> setInterruptionFilter(f); saveFocusShieldPriorFilter(null)
  │     ClearReceiptOnly -> saveFocusShieldPriorFilter(null)
  │
  ├─ if lockTaskModeState != NONE: activity.stopLockTask()       # Activity-scoped callers only
  ├─ FocusShieldRestoreWorker.cancel(ctx)
  ├─ endFocusSession()
  └─ navigate back to Tasks (+ the núcleo's outcome snackbar, unchanged)
```

### Test split — what can be proven where

The prompt asks for this to be explicit, because a large share of this feature is unprovable in
automation. Being honest about that up front is what keeps the manual checklist from being skipped.

**JVM unit tests (fast, the bulk of the real logic)**

- `shieldRestoreActionFor` — all six rows of **D5**'s table, plus the boundary between rows 3 and 4
  (AC-1..AC-6).
- `FocusShieldOptions` defaults on a fresh install (AC-30).
- The `user_prefs` round-trip and fail-open reads of both new fields (AC-12, AC-13, AC-29).
- `FocusShieldRestoreWorker`'s decision against a **fake** `FocusShieldController` — including
  idempotency (AC-19, AC-20) and the shared-constant assertion (AC-18).
- `FocusViewModel`: both exit paths call restore **before** `endFocusSession`, and cancel the
  backstop (AC-15); the abandon button stays enabled under every option combination (AC-32).
- The start sequence's **ordering** (receipt before effect) against a fake controller + fake
  repository, asserting call order, not just call presence (AC-10, AC-11).

**Instrumented tests (need a device/emulator)**

- The entry dialog renders three switch rows, each ≥48dp and toggleable by row tap (AC-33), and
  scrolls at the largest font scale with the confirm button reachable (AC-34).
- The in-session indicator shows only verified-active measures and is absent when none are
  (AC-V4, AC-V5).
- `FLAG_KEEP_SCREEN_ON` is **absent** before entering the Focus screen, **present** while it is
  displayed, and **absent again** after leaving — inspected on the activity's window (AC-26, AC-28).
- The exit action stays visible and hit-testable while immersive (AC-V6).
- Regression: the núcleo's accessibility-only exit (its AC-27) still passes with every measure on.

**Manual, on a real device only — no automation is honest here**

These go into the PR description as a checklist; none of them can be faked in CI:

1. Grant Do Not Disturb access from the dialog's notice; **return and confirm the notice disappears
   without reopening the dialog** (**D9**'s live re-check).
2. Start a session with DND on: a call and a notification are silenced; a **system alarm still
   rings**.
3. Confirm whether the app's own time-up alert still alerts — on a fresh install (channel created
   with `bypassDnd`) **and** on an upgrade over an install that already had the channel (**D3**'s
   open platform question, the single highest-value manual check here).
4. Kill the app from recents mid-session, reopen: DND restored.
5. Reboot mid-session, reopen: DND restored.
6. Change DND manually mid-session, then exit: **the manual change is left alone** (**D5** row 4).
7. Revoke the access mid-session, then exit: no crash, no dialog (**D10**).
8. Pinning: the system dialog appears; back+overview unpins; exiting the session unpins; the
   indicator matches reality on this OEM.
9. Pinned + immersive: the exit panel, the slide and "Abandonar sesión" are all reachable.
10. All three measures off: the session behaves exactly as it did before this feature.

---

## Out of Scope

- **Blocking, hiding, disabling or limiting other apps — refused, not deferred.** A normal Play-Store
  app cannot do it. The only two mechanisms are (a) being a **device owner**, which requires
  enterprise provisioning the user of this app will never have, and (b) an **`AccessibilityService`**,
  which would work technically but is Play-Store policy minefield: accessibility APIs are permitted
  for accessibility purposes, and using them to police app usage is a well-known route to review
  rejection and removal. **The app must never claim, imply, or partially implement this**, and this
  paragraph exists so the decision is documented rather than rediscovered.
- **`INTERRUPTION_FILTER_NONE`** (total silence) — silences system alarms; see **D2**.
- **Changing the global `NotificationManager.Policy`** — see **D2**.
- **Deleting and recreating the `task_reminders` channel** to force `bypassDnd` — see **D3**.
- **A new notification channel** of any kind.
- **Blocking the notification shade** while pinned.
- **Toggling measures mid-session**, or a Settings home for them.
- **Usage-stats, app-timer or launcher-level enforcement** of any kind.
- **Session history** of which measures were used (needs Room, hence a migration).
- **A per-session timer** and any alert of the session's own end (already deferred by the núcleo).
- **Any backend, contract, Room or dependency change.**
- **Re-opening any núcleo decision** — the frozen roster (its D1), the plaintext exit code (D2), the
  unconditional abandon exit (D3), the 12-hour expiry (D7). This feature builds on them and must not
  weaken any of them.

---

## Dependencies

- **Modo Foco (núcleo) — shipped.** `docs/specs/2026-08-18-focus-mode.md`, merged in PR #53
  (`d4e8120`). This feature depends on its `ui/focus/` package, the `FocusSession` model, the
  `user_prefs` session keys and the exit paths, all of which now exist on `master`.
- The existing `user_prefs` DataStore + `UserPreferencesRepository` pattern.
- `WorkManager`, already used by `SyncWorker`, `TaskSurfacesRefreshWorker` and `BootRescheduleWorker`
  — the last of which is also the precedent for a worker that builds its own dependencies from
  `applicationContext`.
- `ExactAlarmPermissionNotice` in `SettingsScreen.kt` — the pattern being extracted (**D9**).
- `ReminderNotificationHelper` and the two shipped notification channels (**D3**).
- `NeverLateApplication.onCreate`'s existing cold-start hook.
- **No new library.** `WindowInsetsControllerCompat`, `NotificationManagerCompat`, `WorkManager` and
  `ActivityManager` are all already available.
- **Blocking:** this spec must be approved before implementation — approval covers behaviour, look
  and the tutorial decision.

---

## Risks

- **R1 — Leaving the phone silent. The defining risk of this feature.** Every other risk here is a
  bug; this one is harm. *Mitigation:* the write-ahead receipt (**D4**), the pure state machine
  (**D5**), and three independent triggers (**D6**) of which one requires no user action at all.
  *Residual:* an aggressive OEM battery manager (Xiaomi, Huawei, Samsung's stricter modes) can delay
  or drop a `WorkManager` job for a force-stopped app. In that scenario the cold-start trigger still
  fixes it the next time the app is opened, and the person can always turn DND off themselves. This
  residual is **accepted and must be stated in the PR description**, not silently carried.
- **R2 — `bypassDnd` may be ignored on an already-created channel**, silencing the app's own time-up
  alert for existing users during a session — the exact case the prompt calls out. *Mitigation:*
  honest copy in the entry dialog, no channel deletion (**D3**), and manual verification on both a
  fresh install and an upgrade as an explicit release check. *If it turns out to be ignored:* it is a
  documented platform limit and a `docs/diferidos.md` row, not a reason to recreate the channel.
- **R3 — API 24–25 have no channels**, so the app's own reminders are silenced by DND there.
  *Mitigation:* accepted platform limit, documented in the same style as the widget's API 24–30
  progress-bar tint. `minSdk` is 24, so this is a real, if small, slice of users.
- **R4 — Screen pinning varies by OEM and Android version.** It may need pinning enabled in system
  settings, may show different dialogs, or may refuse. *Mitigation:* **D7**'s verified-state rule
  (never claim what did not happen) plus **D10**'s catch-and-continue. The measure is presented as
  optional friction, so its absence costs nothing.
- **R5 — Immersive hiding a control needed to exit.** This would turn the núcleo's carefully
  un-trappable mode into a trap. *Mitigation:* AC-V6 (the exit action respects `safeDrawing` insets
  and stays visible), AC-27 (bars always recoverable by swipe), and the pinned+immersive manual
  check. Treated as a hard failure, not a polish item.
- **R6 — A future contributor "simplifies" the write-ahead ordering.** Applying the effect before
  persisting the receipt looks identical in every test that does not kill the process. *Mitigation:*
  AC-10 asserts **call order** against fakes, **D4**'s table is reproduced in the
  `FocusShieldController` KDoc, and the lesson (20e) is built around exactly this point.
- **R7 — Restoration fighting the user.** Blindly restoring would undo a deliberate mid-session DND
  change. *Mitigation:* **D5** row 4 and AC-4.
- **R8 — Scope pressure toward "just block WhatsApp".** The most likely review comment on this
  feature is a request for the one thing it cannot do. *Mitigation:* the *Out of Scope* argument is
  written to be quotable; the answer is that paragraph, not code.
- **R9 — The `SpecialAccessNotice` extraction touching Settings.** Moving a working composable out of
  `SettingsScreen.kt` is behaviour-preserving in principle and a chance to change behaviour by
  accident in practice. *Mitigation:* pure move plus the `ON_RESUME` addition, existing Settings tests
  stay green untouched, and the exact-alarm copy is not reworded.
- **R10 — The nine-fake ripple.** Two new interface methods break nine hand-written fakes; one
  updated carelessly makes a test pass for the wrong reason. *Mitigation:* **D12** — consolidate to
  two shared fakes as a separate mechanical commit if *Review* approves; otherwise update all nine
  identically and file the consolidation.
- **R11 — The manual checklist being skipped.** Most of this feature's real behaviour is only
  observable on a device, and a green unit suite will look like proof it works. *Mitigation:* the
  ten-item checklist above goes into the PR description as checkboxes, and items 2, 3, 4 and 5 are
  release-blocking.

---

## Tutorial scope (`tutorial/20e-modo-foco-blindaje.md`)

The `Tutorial:` field says **sí**, so a Spanish lesson ships with this feature. Slot **20e** — after
`20d-modo-foco` (which it builds directly on) and before the reserved `21`. **No existing lesson is
renumbered.** Recommended contents, all genuinely exercised here for the first time in the repo:

1. **Accesos especiales frente a permisos runtime.** Why `ACCESS_NOTIFICATION_POLICY` is never
   granted by a dialog, the *comprobar → explicar → mandar a Ajustes* idiom, why feature 09's
   `SCHEDULE_EXACT_ALARM` is the same shape, and why the right move was to **extract** that pattern
   into a shared component rather than write it twice — including the `ON_RESUME` re-check the
   original was missing.
2. **Deshacer efectos globales cuando el proceso puede morir en cualquier momento.** The heart of the
   lesson: the *write-ahead receipt*, why the record is persisted **before** the effect, the table of
   "what if it dies here?", why one key beats a richer record, and why the undo must be idempotent.
   This is a general engineering idea (it is what a write-ahead log is) taught on a case small enough
   to hold in one hand.
3. **Elegir el disparador correcto: predicado, alarma o `WorkManager`.** The núcleo chose a pure
   predicate for session expiry precisely because nobody needed to be *told*; this feature needs the
   opposite. Then, within "needs a trigger": why deferrable `WorkManager` beat an exact
   `AlarmManager` alarm here (reboot handling for free, no new receiver, no request-code collision
   with the per-task alarm scheme), and why "sometime in the next few hours" is a perfectly good
   guarantee for this job.
4. **Efectos de ventana con `DisposableEffect`: `WindowInsetsControllerCompat` y `FLAG_KEEP_SCREEN_ON`.**
   Scope as a design tool: which effects need undo machinery and which get their undo for free, and
   why the same feature can correctly persist one measure and deliberately persist nothing for
   another. Includes `BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE` as an accessibility requirement rather
   than a nicety.
5. **Lock task mode y sus límites reales, y el hábito de "estado verificado".** What screen pinning
   actually is without device owner, why it is friction rather than a lock, and the wider habit of
   reading platform state **back** after asking for it instead of assuming the request succeeded —
   with degradation as the default path (**D10**), not the error path.

---

## Review

Please review and approve this spec — approval covers **behaviour, look and the tutorial decision** —
before implementation begins. The six points most worth an explicit yes/no:

1. **D2 + D3 — the Do Not Disturb contract.** `INTERRUPTION_FILTER_PRIORITY` (never `_NONE`, so
   system alarms always ring), the global `Policy` never touched, and the app's own `task_reminders`
   channel asking to bypass DND — with the honest, documented fallback if the platform refuses on an
   existing channel.
2. **D4 + D5 + D6 — the restoration design.** One write-ahead receipt key, a pure six-row state
   machine that **never overwrites a manual mid-session DND change**, and three triggers (exit, cold
   start, 12h `WorkManager` backstop) sharing one function. Explicitly: `BootRescheduleWorker` is
   **not** the host, and the reason is that its `remindersEnabled` early return would skip the
   restore.
3. **D7 — screen pinning ships as friction**, described as friction, with the indicator reporting
   **verified** state, no receipt, and catch-and-continue on refusal.
4. **D11 — the defaults.** "Pantalla siempre encendida" defaults **on**; "No molestar" and "Fijar la
   pantalla" default **off**. The rule: a measure defaults on only if it cannot outlive the session.
   The alternative (all three off, at the cost of discoverability) is defensible — please pick one.
5. **D12 — the nine fakes.** Recommendation: consolidate to **two** shared fakes (one per source set)
   as a separate mechanical first commit on this branch, before any behaviour change. The alternative
   is a fourth identical nine-file edit plus a `docs/diferidos.md` row.
6. **Out of Scope — blocking other apps is refused, not deferred**, and the app's copy will never
   imply otherwise. Please confirm this is the answer you want documented, since it is the one part
   of the original idea that does not get built.

*Agentes: `android-engineer` (implementación de las tres medidas, la máquina de restauración, el
componente `SpecialAccessNotice` extraído, y sus tests JVM + instrumentados en una sola pasada);
`devops-security-engineer` (revisión del nuevo permiso de manifest y de que la restauración de estado
global es a prueba de muerte de proceso — en particular el orden write-ahead de **D4** y las seis
filas de **D5**). La verificación en dispositivo de la lista manual de 10 puntos la hace el usuario.*
