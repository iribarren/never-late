# Feature — The list should remember how you left it (persisted sort & grouping)

- **Status:** Awaiting approval
- **Date:** 2026-08-17
- **Branch (suggested):** `feature/persisted-list-preferences`
- **Prompt origen:** [`docs/prompts/preferencias-lista-persistentes.md`](../prompts/preferencias-lista-persistentes.md)
- **Original framing (user's words):** *"Que el filtro, el orden y la búsqueda de la lista sobrevivan a
  cerrar la app."*
- **Type:** Behaviour change on `app/` only. **No** backend change, **no** API contract change, **no**
  Room schema change or migration (this is DataStore, not Room), **no** new dependency, **no** new
  permission, **no** new screen, **no** new control.
- **Tutorial:** **No** — answered by the user via `AskUserQuestion`. Rationale: this feature is the
  intersection of three lessons that already exist. Lesson **07** teaches the `user_prefs` DataStore,
  the `UserPreferencesRepository` interface, its in-memory fake and `ThemeMode.fromStorage`'s tolerant
  parsing; lesson **04b** teaches the reactive `debounce`/`combine`/`stateIn` pipeline in
  `TasksViewModel`; lesson **03b** teaches `TaskListCriteria` and in-memory filter/sort/group. Adding
  three preference keys and one loading branch teaches no new concept. The one genuinely didactic
  angle — *"the first frame: what do you show while the disk has not answered yet?"* — is a
  cross-cutting topic (it already recurs in `MainActivity` and `AppNavHost`), not this feature's, and
  is documented here in **D3** instead. `/doc-check` must therefore **not** flag a missing
  `tutorial/NN-*.md` for this feature.

---

## Overview

The Tasks list lets the user shape it four ways (features 03b, 04b, `priority-sorting`): a text
search, a sort field, a sort direction, a grouping axis, and a multi-select priority filter. **None of
it survives.** In
[`TasksViewModel`](../../app/src/main/java/com/neverlate/ui/tasks/TasksViewModel.kt) all of it lives
in two plain `MutableStateFlow`s:

```kotlin
private val _query = MutableStateFlow("")
private val _criteria = MutableStateFlow(TaskListCriteria())
```

That ViewModel has no `SavedStateHandle` either, so this is worse than "resets when you close the
app": it resets whenever the system reclaims the process while the user is in another app. A person
who sorts by priority — or groups by urgency — *because that is the only arrangement in which they can
actually see their list* has to rebuild it several times a day, silently, with no indication that
anything was lost. For the audience this product exists for, re-doing the same four taps daily is not
a papercut; it is the app quietly undoing the one accommodation the user made for themselves.

This feature makes the list's **arrangement** durable, restores it with **no visible intermediate
state**, and tolerates a stored value that a future version no longer recognises.

The substance of this spec is the four decisions below — in particular **D1**, which decides *what is
persisted and what deliberately is not*. The acceptance criteria only enforce them.

---

## Decisions

### D1 — Persist what **re-arranges**; never persist what **hides**

This is the product decision the prompt asks for, and it answers the search question and the
priority-filter question with one rule instead of two ad-hoc calls.

| Control | Kind | Persisted? |
|---|---|---|
| Sort field (Deadline / Title / Priority) | re-arranges | **Yes** |
| Sort direction (asc / desc) | re-arranges | **Yes** |
| Grouping axis (None / Urgency / Priority) | re-arranges | **Yes** |
| Text search (`query`) | **hides** | **No** |
| Priority filter (`priorityFilter`) | **hides** | **No** |

**Why.** Sorting and grouping are *lossless*: every task the user owns is still on screen, just in a
different order or under section headers. Restoring them can only ever match what the user set up on
purpose. Search and the priority filter are *lossy*: they remove rows from view. A restored filter
produces a list that is **missing tasks, with the reason scrolled off the top of the screen** — and
the app cannot distinguish "I meant that" from "I forgot I typed that three days ago". Opening the app
to a list that is quietly short of tasks is indistinguishable from data loss, and in an ADD/ADHD tool
"where did my task go?" is the single most expensive failure mode we can ship: it costs trust in the
one surface the whole product depends on.

The asymmetry is also behavioural, not just theoretical: a sort choice is *configuration* (set once,
kept for weeks); a search is a *momentary act* (typed to find one thing, then abandoned rather than
cleared — the field's ✕ is an affordance people use far less often than they simply navigate away).
Persisting a momentary act as if it were configuration is the actual bug.

**Rejected alternative — persist everything, and make the filter loud.** Restore the query/priority
filter but flag it prominently on first frame ("filtro activo — limpiar"). This was seriously
considered and rejected: it pays a permanent complexity and visual cost (a banner, a dismissal rule,
a "was this restored or just typed?" distinction in state) to make a restored filter *survivable*
rather than *useful*. Nobody has ever reopened an app hoping their old search was still running. If a
future feature wants durable filters, the right shape is **named saved views** the user creates
explicitly — not a filter that persists behind their back. Noted in *Out of Scope*.

**Consequence for the debounce trap.** Because the query is not persisted, the restoration path never
touches `debouncedQuery`, and the prompt's "a restored search must not wait 300ms" hazard **cannot
occur by construction** — the strongest possible way to handle it. If a future spec reverses D1, that
hazard comes back and must be handled there (see *Risks*, R3).

### D2 — DataStore is the single source of truth for the arrangement; no local echo state

`_criteria` is **removed**, not kept alongside the persisted value. The read path becomes
`userPreferencesRepository.userPreferences.map { it.taskListArrangement }`, and each setter
(`onSortFieldChange`, `onToggleSortDirection`, `onGroupAxisChange`) writes to the repository inside
`viewModelScope.launch { ... }`; the new value reaches the UI when DataStore re-emits.

**Why not keep a `MutableStateFlow` and mirror it into DataStore?** Two sources of truth for one value
is exactly the drift bug this codebase avoids elsewhere (`MainBottomBar` derives its selected tab from
the live back stack rather than a `remember`ed index, for the same reason). The round-trip is the same
one `SettingsViewModel` already uses for the theme switch — a DataStore `edit` plus a `Flow` emission,
single-digit milliseconds, already proven acceptable on a control the user taps and watches. A chip's
selected state is not more latency-sensitive than the app-wide theme.

**Note on the priority filter.** `priorityFilter` stays in-memory (D1), so it still needs a home. It
keeps a small `MutableStateFlow` of its own — `_priorityFilter: MutableStateFlow<Set<Priority>>` —
next to the existing `_query`, and is `combine`d back together with the persisted arrangement. This is
the same split feature 04b already made when it pulled `query` out of `_criteria`: what is debounced,
what is ephemeral and what is persisted each get their own stream, and `TaskListCriteria` is
reassembled at the point of use.

### D3 — Restoration uses the project's existing **explicit null-loading branch**, not an `init {}`

The prompt's option (a). While DataStore has not answered, the arrangement is `null`, and `null` means
`TasksUiState.Loading` — never a default-valued render.

```kotlin
// null until DataStore answers — the same "wait for a value before deciding" contract
// MainActivity uses for themeMode/dynamicColor and AppNavHost uses for the start destination.
private val arrangement: StateFlow<TaskListCriteria?> =
    userPreferencesRepository.userPreferences
        .map { it.taskListArrangement }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

val uiState: StateFlow<TasksUiState> =
    combine(uiTasksFlow, debouncedQuery, arrangement, _priorityFilter) { uiTasks, q, arr, filter ->
        if (arr == null) TasksUiState.Loading
        else shapeToUiState(uiTasks, q, arr.copy(priorityFilter = filter))
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TasksUiState.Loading)
```

**Why (a) and not (b) — the `init {}` seeding block.** Option (b) would reintroduce, by hand, exactly
the imperative collector feature 04b removed on purpose; that removal is not an incidental cleanup but
a documented decision, written into `TasksViewModel`'s KDoc and taught in
`tutorial/04b-buscador-tareas.md` ("instead of one imperative `collect` that assigns a
`MutableStateFlow` by hand, `uiState` is now a **declarative** chain"). It would also be *worse at the
job*: an `init {}` that seeds `_criteria` from disk still leaves a window in which the seed has not
landed, so the flicker it is meant to prevent becomes a race rather than a certainty — the very class
of bug that is invisible on a fast device and reported by users on a slow one. Option (a) makes the
"not loaded yet" state **unrepresentable as a render**: there is no default-valued frame to flash,
because `null` has no rendering.

**The invariant this creates, which the UI must honour:** `arrangement == null` ⇔ `uiState ==
Loading`. `TasksRoute` therefore passes `criteria: TaskListCriteria?` to `TasksScreen`, and the
sort/group/filter control row renders only when it is non-null — which is precisely when the list
itself renders. The chips and the list appear in the **same frame**, already in the restored
arrangement. (The control row is already hidden in `Loading` and `Empty` today, so this adds a guard,
not a new layout state.)

**Startup cost.** `debouncedQuery` already delays `uiState`'s first emission by ~300ms today (the
initial `""` passes through `debounce(300)`), so the DataStore read — which the app performs in
parallel and which typically completes well inside that window — adds no perceptible startup latency.
Tracked as R2 rather than assumed away.

### D4 — Storage shape: three `String` keys, tolerant parsing, no parallel DTO

`TaskListCriteria` stays the one object the controls configure; nothing new is invented alongside it.

**Repository surface** (`data/UserPreferencesRepository.kt`):

- `UserPreferences` gains `val taskListArrangement: TaskListCriteria = TaskListCriteria()`.
- The interface gains **one** method:
  `suspend fun saveTaskListArrangement(criteria: TaskListCriteria)`.

It takes the whole `TaskListCriteria` (no parallel DTO — the ViewModel passes the object it already
holds) but is named **arrangement**, not *criteria*, precisely because it deliberately writes only
`sortField`, `direction` and `groupAxis`, and ignores `priorityFilter` (D1). A method named
`saveTaskListCriteria` would be a lie the compiler could not catch; the name is the documentation of
the drop, backed by a KDoc note and a unit test. Reads always return `priorityFilter = emptySet()`.

**Keys** — three new `stringPreferencesKey`s in the same `user_prefs` DataStore (no second store, same
as every key added since feature 07):

| Key | Values | Missing/unknown falls back to |
|---|---|---|
| `task_sort_field` | `Deadline` \| `Title` \| `Priority` | `TaskSortField.Deadline` |
| `task_sort_direction` | `Ascending` \| `Descending` | `SortDirection.Ascending` |
| `task_group_axis` | `None` \| `Urgency` \| `Priority` | `TaskGroupAxis.None` |

**Tolerant parsing.** Each of the three enums in
[`TaskListShaping.kt`](../../app/src/main/java/com/neverlate/domain/tasks/TaskListShaping.kt) gains a
`companion object { fun fromStorage(value: String?): T = entries.firstOrNull { it.name == value } ?: DEFAULT }`,
copying `ThemeMode.fromStorage` exactly. Never `enum.ordinal` (reordering the enum would silently
repoint every user's preference at a different value) and never a bare `valueOf` (a renamed or removed
constant would throw on read, i.e. the Tasks screen would crash on launch for everyone who had that
value stored — the worst possible failure for a preference nobody's data depends on). Fallback is
**silent**: no error state, no toast; a preference that cannot be read is simply the default, and the
next control tap overwrites it.

The defaults above are the same defaults `TaskListCriteria()` already declares, so a fresh install and
an install upgrading from before this feature behave identically — no migration, no first-run write.

**Layering note.** `data.UserPreferences` will reference `domain.tasks.TaskListCriteria`. That is the
allowed direction (the pure domain layer is the innermost one) and the three enums carry no Android or
UI dependency.

---

## User Stories

### US-1 — My arrangement is still there when I come back

> As someone who can only work through my list when it is sorted by priority, I want that sort to
> still be applied when I reopen the app, so that I do not have to rebuild it every single time.

**Acceptance criteria**

- Selecting a sort field, flipping the direction, or selecting/clearing a grouping axis persists that
  choice immediately (no explicit "save").
- Fully closing the app (or having the process killed in the background) and reopening it shows the
  list in the last-chosen sort field, direction and grouping axis.
- The chips shown on reopen match the restored arrangement.
- Restoration also holds after the process is killed while backgrounded — not only after a clean exit.

### US-2 — Reopening never shows me a list that is wrong for a moment

> As someone whose attention is expensive, I want the app to open straight into my arrangement, so
> that I am not shown a wrong list first and made to re-read it after it rearranges itself.

**Acceptance criteria**

- At no point during startup is the task list rendered with default sort/grouping before switching to
  the stored one.
- The sort/group chips never render in a default-selected state and then change selection on their
  own.
- Until the stored arrangement is available, the screen shows its existing `Loading` state — no new
  visual state is introduced.
- The list and its control chips become visible in the same frame, already correct.

### US-3 — No tasks ever go missing because of something I did days ago

> As someone who panics when tasks appear to vanish, I want the app to always open showing all my
> tasks, so that an empty-looking list always means something real.

**Acceptance criteria**

- After a restart, the search field is empty and no priority filter chip is selected, regardless of
  what was active before (D1).
- After a restart the list never shows `NoResults`, since neither filter can be active at launch.
- Grouping is restored, and grouping never removes a task from view (all sections are shown).

### US-4 — A future app version cannot brick my list

> As a user upgrading the app, I want a preference the new version no longer understands to be ignored
> quietly, so that the app opens normally instead of crashing or showing an error.

**Acceptance criteria**

- An unknown stored value for any of the three keys resolves to that key's default with no crash and
  no user-visible error.
- An absent key (fresh install, or an install from before this feature) resolves to the same default.
- A stored value is parsed by name, never by ordinal.

---

## Acceptance Criteria (consolidated)

### Behavioural

- **AC-1** — `sortField`, `direction` and `groupAxis` round-trip through the `user_prefs` DataStore and
  are restored on next launch.
- **AC-2** — `query` and `priorityFilter` are **not** written to DataStore, and always start at `""` /
  `emptySet()` on launch (D1).
- **AC-3** — `saveTaskListArrangement(criteria)` writes exactly three keys; a criteria object carrying
  a non-empty `priorityFilter` leaves nothing filter-shaped on disk (unit-tested).
- **AC-4** — `TaskSortField.fromStorage` / `SortDirection.fromStorage` / `TaskGroupAxis.fromStorage`
  return the documented default for `null` and for any unrecognised string (unit-tested for both).
- **AC-5** — `uiState` is `Loading` while the stored arrangement has not yet been read, and its first
  non-`Loading` emission already carries the restored arrangement (unit-tested with a fake repository
  seeded with a non-default arrangement).
- **AC-6** — No `init {}` collector is added to `TasksViewModel`; the arrangement reaches `uiState`
  through the existing declarative `combine`/`stateIn` chain (D3).
- **AC-7** — Tapping a sort/group chip updates the visible list, and the change survives a
  `ViewModel` being recreated.
- **AC-8** — `onClearFilters` still clears the query and the priority filter and still leaves the
  persisted arrangement untouched.
- **AC-9** — Every existing `TasksViewModel` / list-shaping test still passes; no change to
  `shapedBy`'s behaviour.
- **AC-10** — All existing `UserPreferencesRepository` fakes implement the new method and the suite
  compiles (see *Dependencies*).

### Definition-of-Done items this feature touches

- **AC-11** — Unit tests cover the tolerant parsing (AC-4), the write filtering (AC-3) and the
  no-flicker restoration (AC-5); `./gradlew :app:testDebugUnitTest` is green before committing.
- **AC-12** — **No Room migration**: this is DataStore only, the database version is untouched.
- **AC-13** — **No contract change**: `docs/api/contract.md` is not touched; these preferences never
  leave the device.
- **AC-14** — **No new user-facing strings** (no new control, no new message). If any string is added
  it ships in both `values/` (Spanish base) and `values-en/`.
- **AC-15** — Loading / empty / error states on Tasks are unchanged and still covered; the new loading
  case reuses the existing `TasksUiState.Loading` rendering rather than introducing a state.
- **AC-16** — `docs/mockups/README.md`'s "Tasks filter / sort / group controls" row is updated to record
  this feature's behavioural change to that (non-mockup) slice.

### Visual

- **AC-V1** — On cold start, the task list is **never** painted with a default arrangement before the
  stored one applies — no self-reordering list in front of the user.
- **AC-V2** — The sort/group chips never appear with a default selection and then jump; their first
  painted state is the restored one.
- **AC-V3** — The chips and the list appear together, in the same frame (guaranteed by the D3
  invariant: `arrangement == null` ⇔ `Loading`).
- **AC-V4** — The `Loading` state on Tasks is visually unchanged from today (same indicator, same
  placement) — this feature adds no new loading chrome.
- **AC-V5** — On reopening, the search field is empty and shows **no** ✕ clear button, and no priority
  chip is selected — the visual proof of AC-2, and the reason the list is never mysteriously short.
- **AC-V6** — Touch targets on the (unchanged) chips remain ≥ 48dp via
  `minimumInteractiveComponentSize()`, and the `FlowRow` still reflows at the largest font scale.
- **AC-V7** — Verified on cold start **specifically looking for the chip jump**, in both light and dark
  themes, at default and largest font scale.

---

## Visual & UX Design

### Mockup slice

**None claimed.** The master mockup
([`docs/mockups/rediseno-ux-ui.html`](../mockups/rediseno-ux-ui.html)) has no search/filter/sort
surface at all, which is why the existing "Tasks filter / sort / group controls" row in
[`docs/mockups/README.md`](../mockups/README.md) is marked `—` (net-new UI, no slice). This feature
adds **no control, no chrome and no new pixel**: it changes *when the existing controls show their
correct value*. The tracking table's note for that row is extended (AC-16) to record that the
arrangement is now durable and that restoration is flicker-free by construction; the row's `—` status
is unchanged.

### The one visual requirement

There is no new component, so the entire visual contract of this feature is a **negative** one: on
launch, the user must never see a state that is wrong and then corrects itself. Concretely, the three
things that must never be observable are (a) the list in default order before rearranging itself, (b)
chips selected on the default values before jumping, and (c) the list appearing before the chips that
explain it. D3's `null`-until-loaded branch makes all three unrepresentable rather than merely
unlikely; AC-V1–V3 are how that is verified, and AC-V7 is the manual pass that looks for it on purpose.

### Tokens and components reused (extend, don't duplicate)

- The existing `TasksUiState.Loading` rendering — no new loading state, no new indicator.
- The existing `FilterChip` row inside the `FlowRow` of `TasksScreen` — unchanged styling,
  unchanged `minimumInteractiveComponentSize()` targets.
- `ui/components/MessageState` for `Empty`/`NoResults` — untouched.
- `ui/components/ReadableWidthContainer` around Tasks — untouched.
- `ui/theme/` tokens — untouched; this feature adds no color, no type style and no dimension.

### Deferred, and to where

- **Search-field restoration visuals** (restored text plus its ✕ visible from the first painted frame)
  — not applicable, because D1 does not persist the search. If a future spec reverses D1, that visual
  requirement travels with it and must be re-specified there, together with the debounce bypass (R3).
- **Named saved views** (an explicit, user-created arrangement + filter preset with a visible chip) —
  the honest home for durable *filters*, out of scope here; a candidate row for
  [`docs/diferidos.md`](../diferidos.md).
- **Scroll-position restoration** — out of scope (below).

---

## Technical Approach

Three files change in `app/`, plus test fakes.

| File | What changes |
|---|---|
| [`domain/tasks/TaskListShaping.kt`](../../app/src/main/java/com/neverlate/domain/tasks/TaskListShaping.kt) | Add a `companion object` with `fromStorage(value: String?)` to `TaskSortField`, `SortDirection` and `TaskGroupAxis`, mirroring `ThemeMode.fromStorage`. `TaskListCriteria` itself is unchanged. |
| [`data/UserPreferencesRepository.kt`](../../app/src/main/java/com/neverlate/data/UserPreferencesRepository.kt) | Add `UserPreferences.taskListArrangement: TaskListCriteria`, three `stringPreferencesKey`s in `Keys`, tolerant reads in the `map { }`, and `saveTaskListArrangement(criteria)` on both the interface and `DataStoreUserPreferencesRepository` (one `edit {}` writing exactly three keys). |
| [`ui/tasks/TasksViewModel.kt`](../../app/src/main/java/com/neverlate/ui/tasks/TasksViewModel.kt) | Replace `_criteria` with (i) the `arrangement: StateFlow<TaskListCriteria?>` derived from the repository and (ii) `_priorityFilter: MutableStateFlow<Set<Priority>>`; widen `combine` to four sources with the `null → Loading` branch; setters become `viewModelScope.launch { saveTaskListArrangement(...) }`. `_query`, `debouncedQuery`, `uiTasksFlow`, the auto-pause collector and every other method are untouched. |
| [`ui/tasks/TasksScreen.kt`](../../app/src/main/java/com/neverlate/ui/tasks/TasksScreen.kt) | `TasksRoute` exposes the criteria as nullable and `TasksScreen` renders the control row only when non-null (the D3 invariant). No styling change. |

`di/StorageModule` already provides `UserPreferencesRepository`, and `TasksViewModel` already injects
it (for `userName`) — **no new Hilt wiring**.

### Test impact (compile-breaking)

Adding a method to the `UserPreferencesRepository` interface breaks **every** implementation. The
prompt named three; the codebase actually has **seven** (`grep -rln ": UserPreferencesRepository"
app/src/test app/src/androidTest`), all of which must gain the new method:

- `app/src/test/java/com/neverlate/data/sync/SyncTestDoubles.kt`
- `app/src/test/java/com/neverlate/ui/settings/SettingsViewModelTest.kt`
- `app/src/test/java/com/neverlate/ui/onboarding/OnboardingViewModelTest.kt`
- `app/src/test/java/com/neverlate/ui/tasks/TasksViewModelTest.kt`
- `app/src/test/java/com/neverlate/ui/notification/ReminderSchedulingRepositoryTest.kt`
- `app/src/androidTest/java/com/neverlate/ui/tasks/TasksEmptyStatePersonalizationTest.kt`
- `app/src/androidTest/java/com/neverlate/ui/tasks/TasksRouteSnackbarTest.kt`

Each fake should implement it the way the existing fakes implement `saveName`/`saveRemindersEnabled`
— update the backing `MutableStateFlow` — so a test can seed a non-default arrangement and assert
AC-5. This is the second time in two features that one interface method has rippled across this many
fakes; consolidating them into one shared test double is **not** done here (out of scope) but is worth
a `docs/diferidos.md` row.

### New tests

- JVM: `fromStorage` for each enum — `null`, unknown string, every valid name (AC-4).
- JVM: `saveTaskListArrangement` ignores `priorityFilter` (AC-3), against a fake or Robolectric-backed
  DataStore consistent with existing practice.
- JVM: `TasksViewModel` seeded with a non-default stored arrangement emits `Loading` first and then a
  `Content` already in that arrangement — never a default-arranged `Content` (AC-5).
- JVM: a chip setter writes through the repository and the new value comes back via the flow (AC-7).
- JVM: after construction, `query` is `""` and no priority filter is active regardless of what the
  fake repository holds (AC-2).

---

## Out of Scope

- **Persisting the text search** and **persisting the priority filter** (D1 — an explicit decision, not
  an omission).
- **Named saved views / filter presets** — the correct future shape for durable filtering.
- **Persisting scroll position** in the task list.
- **Syncing these preferences across devices** — they stay on-device, so no `TaskDto`, no contract
  change, no backend work.
- **New filter or sort criteria** of any kind (that is `docs/prompts/prioridad-operativa.md`'s
  territory, largely shipped by the `priority-sorting` spec).
- **Persisting anything on other screens** (Articles list, Stats).
- **Introducing `SavedStateHandle` to `TasksViewModel`** — DataStore covers the durable half, and the
  ephemeral half (query, priority filter) is deliberately ephemeral per D1. Rotation already survives
  via `SharingStarted.WhileSubscribed(5_000)`.
- **Consolidating the seven `UserPreferencesRepository` test fakes** into one shared double.
- **Any visual restyle** of the Tasks controls.

---

## Dependencies

- Everything needed already exists: the `user_prefs` DataStore and `UserPreferencesRepository`
  (feature 07), `TaskListCriteria` (03b), the `combine`/`stateIn` pipeline (04b), Hilt provision of the
  repository into `TasksViewModel` (13d / `editable-profile`).
- **No new library** — nothing is added to `gradle/libs.versions.toml`.
- **No backend, no contract, no permission, no manifest change, no Room migration.**
- The seven test fakes listed above must be updated in the same change or the suite will not compile.

---

## Risks

- **R1 — The `null` branch leaks into a state the UI does not expect.** If `TasksScreen` renders the
  control row while criteria is `null`, the flicker returns in a new disguise. Mitigated by making the
  invariant explicit (D3), passing a nullable type so the compiler forces the guard, and asserting the
  first-emission ordering in a unit test (AC-5).
- **R2 — Startup latency.** Two async sources (`debounce(300)` on the initial query and the DataStore
  read) now gate the first render. They run in parallel and the DataStore read is expected to finish
  well inside the existing 300ms, so the observable startup should be unchanged — but this is an
  expectation, not a measurement. Verify on a cold start on a real device (AC-V7); if the disk read
  ever dominates, the fix is to shorten the *query* path (the debounce is what actually delays the
  first frame today), not to abandon D3.
- **R3 — A future reversal of D1 reintroduces the debounce trap.** If someone later decides to persist
  the query, feeding it through `_query` would make the restored text wait 300ms before the list
  reflects it, so a cold start would look sluggish for no reason. The restoration path would need to
  bypass `debounce` (e.g. seed the debounced stream directly, or `merge` a restore emission that skips
  it), and the search field would need its text and ✕ visible from the first painted frame. Written
  down here so the hazard is inherited rather than rediscovered.
- **R4 — Write amplification on the direction toggle.** Every chip tap is a DataStore `edit`. Taps are
  human-paced and each write is small, so this is not a real concern — but if a future control ever
  changes the arrangement programmatically or rapidly, it must not write per intermediate value.
- **R5 — The `saveTaskListArrangement` silent drop.** The method accepts a full `TaskListCriteria` and
  deliberately ignores `priorityFilter`. A future caller could reasonably expect it to persist
  everything. Mitigated by the name, a KDoc note and AC-3's test; accepted in exchange for not
  inventing a parallel DTO.
- **R6 — Fake drift across seven files.** Seven hand-written fakes is a maintenance smell; one updated
  incorrectly could make a test pass for the wrong reason. Mitigated by implementing them all
  identically (mutate the backing `MutableStateFlow`) and noting the consolidation as deferred work.

---

## Review

Please review and approve this spec — approval covers **behaviour, look and the tutorial decision** —
before implementation begins. The three points most worth an explicit yes/no:

1. **D1** — sort and grouping persist; the text search and the priority filter deliberately do not.
2. **D3** — restoration via the existing `null`-until-loaded branch, with no `init {}` collector.
3. **D4** — one new repository method named `saveTaskListArrangement`, three tolerant `String` keys.
