# Feature — The name we ask for and never use: make it earn its place, then make it editable

- **Status:** Awaiting approval
- **Date:** 2026-08-17
- **Branch (suggested):** `feature/editable-profile`
- **Original framing (user's words):** *"Onboarding pide el nombre y luego no se usa en ninguna parte,
  y no hay forma de cambiarlo."*
- **Type:** Behaviour + UI change on `app/` only. **No** backend change, **no** API contract change,
  **no** Room schema change or migration (this is DataStore only), **no** new dependency, **no** new
  permission, **no** new screen or navigation destination.
- **Tutorial:** **No (solo producto)** — answered by the user via `AskUserQuestion`. Rationale: every
  technique this feature uses is already taught. Lesson **02** walks the onboarding name field, its
  `ViewModel` state and validation; lesson **07** walks the `user_prefs` DataStore, the
  `UserPreferencesRepository` interface and its in-memory fake. Adding one preference key consumer, one
  `AlertDialog` and one `StateFlow` teaches nothing new, so no lesson is written and no tutorial number
  is reserved. `/doc-check` must therefore **not** flag a missing `tutorial/NN-*.md` for this feature.

---

## Overview

Onboarding (feature 02) asks the user for their name on first launch and persists it through
`UserPreferencesRepository.saveOnboarding(name)` into the `user_prefs` DataStore (feature 07). Its
only consumer was ever a greeting on the Home screen, and **feature 18 deleted the Home screen** when
bottom navigation replaced the hub. The result today, verified in the code:

- `UserPreferences.name` is written on first launch and **read by nothing**. The only read is
  `AppNavHost`'s use of the *sibling* key, `onboarded`, to pick a start destination.
- There is **no way to change the name** short of clearing app data or reinstalling.

So the app currently asks a personal question, stores the answer forever, and never once uses it.
That is worse than not asking: it spends a full blocking screen of a new user's attention — an
ADD/ADHD user's attention, which this product exists to respect — and returns nothing.

This feature is therefore **not** "add an edit field to Settings". Adding an editor for a value
nothing displays would be building a control panel for a dead wire. The feature has two halves, in
this order:

1. **Decide where the name becomes genuinely useful** — including the honest option of deleting the
   onboarding question entirely — and then surface it there.
2. **Make it editable from Settings**, without re-running onboarding, reflected immediately
   everywhere it appears.

The decisions in the next section are the substance of this spec; the acceptance criteria only
enforce them.

---

## Decisions

### D1 — Keep the question; make the name earn its place on two surfaces

**Decision:** keep the onboarding question, and give the name two real consumers:

| Surface | What it shows | Why here |
|---|---|---|
| **Tasks empty state** (`TasksUiState.Empty`, via `ui/components/MessageState`) | `Nada pendiente, Aritz.` instead of `No tienes tareas todavía.` | The one moment in the app that is pure tone rather than data. "Nothing pending" is a small win, and for this audience a small win addressed by name lands differently from a system notice. It is also the exact place a first-run user arrives **immediately after typing their name**, which closes the loop that feature 18 broke. |
| **Settings → Account card** | A `Nombre` row showing the current value, next to the session button | The canonical home of a profile field. It makes the stored value *visible* — today a user cannot even confirm what the app knows about them — and it is where the edit affordance must live anyway. |

**Rejected: a greeting in the Tasks top app bar.** Feature 20 shipped branded top app bars
(`brandedTopAppBarColors()`) as a tracked mockup slice, and the Tasks bar's title slot currently holds
`tasks_title` ("Mis tareas") with a stats `IconButton` in `actions`. Replacing that title with
"Hola, Aritz" would (a) remove the screen's identity label — the bar would no longer say which tab you
are on, which matters precisely because feature 18 made these peer tabs; (b) push the greeting into
the chrome slot most at risk from long names and large font scales; and (c) require a two-line
`TopAppBar` variant that no other screen uses, fragmenting chrome that feature 20 deliberately
unified. The constraint that a greeting "must not be the only thing occupying that header" is
satisfied here in the strongest way available: **there is no header greeting at all.**

**Rejected: personalizing `TasksUiState.NoResults`.** "No coincidencias" is a filter miss — a
mechanical outcome the user just caused. Warmth there reads as the app being chummy about a failure.
Only `Empty` is personalized.

### D1-alt — Removing the onboarding question (seriously considered, rejected)

This was evaluated as a real candidate, not a foil. The case *for* removal is strong: a blocking
first-run screen is an attention tax; the app functions identically without a name; and "we ask
because we already ask" is the kind of inertia this project's specs are supposed to catch. If the
only payoff were a word in a rarely-seen empty state, removal would win on the merits.

It is rejected for three reasons, in descending weight:

1. **It strands a shipped tutorial lesson.** `tutorial/02-*` walks `OnboardingScreen`,
   `OnboardingViewModel` and its validation line by line. `CLAUDE.md` forbids renumbering shipped
   lessons precisely because they are welded to the code, and deleting the code a lesson teaches is a
   harder break than renumbering it. Retiring onboarding is a legitimate product move, but it is a
   **tutorial-track decision** that must be taken deliberately and separately, not smuggled in as a
   side effect of "make the name editable".
2. **The `onboarded` flag is load-bearing beyond the name.** `AppNavHost` reads it to pick a start
   destination and to guarantee that a widget deep link can never skip onboarding for a fresh user
   (see `AppNavHost` around the `!preferences.onboarded -> Routes.ONBOARDING` arm). Removing the
   question means either keeping the flag with no screen behind it — a flag set by nothing — or
   reworking the auth/onboarding gate. That is a navigation-graph change, i.e. a different feature
   with different risks.
3. **The payoff is no longer marginal once D1 lands.** With two surfaces (empty state + a visible,
   editable Settings row), the stored value is something the user can see, verify and change. The
   original complaint — "asked and never used" — is answered by *using it*, which is the cheaper and
   less destructive of the two available answers.

**If the user disagrees and wants removal**, say so at approval: this spec is then withdrawn and
replaced by a `feature/retire-onboarding` spec covering the nav gate, the `onboarded` key's fate, the
four `onboarding_*` string pairs, and how lesson 02 is annotated as historical. Do not attempt both.

### D2 — Editing lives in the existing **Account** card, via a dialog

**Decision:** extend the existing `SettingsSectionCard(title = settings_account_section, icon =
Icons.Filled.AccountCircle)` in `SettingsScreen.kt` — no new section, no new screen. The card gains a
**name row** above the existing session button:

```
┌─ 👤 Cuenta ────────────────────────────┐
│ Nombre                                 │
│ Aritz                        [Cambiar] │
│ ────────────────────────────────────── │
│ [Cerrar sesión]                        │
└────────────────────────────────────────┘
```

Tapping **Cambiar** opens an `AlertDialog` containing a single `OutlinedTextField`, with
**Guardar** / **Cancelar** buttons.

**Why a dialog rather than an always-editable inline field:** an inline `OutlinedTextField` inside a
scrolling settings list creates a "when did this save?" ambiguity that no other control on this screen
has (every other control there commits on the spot — a radio tap, a switch flip), and it puts a
keyboard-focusable field in the path of a scroll. The dialog gives an explicit commit point, a natural
home for the validation message, and reuses a shape this screen already ships twice
(`LogoutConfirmDialog` here, `DeleteTaskDialog` in `TasksScreen.kt`): title + text + confirm/dismiss
`TextButton`s.

**Why not reuse the onboarding screen:** re-entering onboarding would re-run a first-run flow and, via
`saveOnboarding`, rewrite the `onboarded` flag — see D4. Renaming is not onboarding.

### D3 — Validation is exactly onboarding's rule, not a new one

**Decision:** reuse `OnboardingViewModel`'s existing criteria verbatim:

- **Enabled/valid iff `newName.isNotBlank()`** — whitespace-only counts as blank, so no separate trim
  is needed for the check.
- **The value written to disk is `name.trim()`** — already done inside
  `DataStoreUserPreferencesRepository.saveOnboarding`, and `saveName` (D4) must trim identically.

No maximum length, no character restrictions, no uniqueness — the field is free personal text.
**Rejected: a length cap** (e.g. 40 chars) as a defence against pathological names. A cap would be a
*different* validation rule from onboarding's, so the same name could be accepted at first launch and
rejected on rename — an incoherence the user would meet exactly once, at the worst moment. Long names
are handled as a **display** concern instead (truncation, see D6), which is where the problem actually
lives.

One addition over onboarding, and it is a11y, not validation: the dialog shows a localized
**supporting-text error** while the field is blank *and* has been edited, in addition to disabling
**Guardar**. A disabled button with no explanation is a known accessibility failure — the user gets no
answer to "why is nothing happening?". Onboarding's own behaviour is unchanged by this feature.

### D4 — Add `saveName(name)`; do **not** reuse or split `saveOnboarding`

`saveOnboarding(name)` writes **two** keys inside one atomic `edit {}`:

```kotlin
override suspend fun saveOnboarding(name: String) {
    context.userPrefsDataStore.edit { preferences ->
        preferences[Keys.NAME] = name.trim()
        preferences[Keys.ONBOARDED] = true
    }
}
```

**Decision: add a separate method to the interface.**

```kotlin
/** Persists the (trimmed) display name on its own, leaving the `onboarded` flag untouched. */
suspend fun saveName(name: String)
```

**Why not reuse `saveOnboarding` from Settings:** it is currently harmless (the user is already
onboarded, so re-writing `onboarded = true` is a no-op) — and that is precisely the trap. Calling it
would silently redefine the method's contract from *"complete the onboarding step"* to *"write the
name, and also assert onboarding"*. The next person to change what onboarding completion means (a
tour, a permission prompt, an analytics event) would break renaming from a file they never opened.
A method's name is a promise about **when** it is called, not only about what it writes.

**Why not split `saveOnboarding` into `saveName` + `markOnboarded`:** that would turn one atomic
DataStore transaction into two, opening a window where `onboarded = true` is durable but the name is
not (or vice versa) if the process dies between them — and the failure mode is a user landing on Tasks
with an empty name and no way back to the screen that asks for it. The atomicity is the point of the
method; it is kept.

**Expected fallout — three test doubles/tests must be updated. This is expected, not a defect:**
adding a method to an interface breaks every implementation of it, and these fakes exist precisely so
that ViewModels can be tested without DataStore.

| File | What changes |
|---|---|
| `app/src/test/java/com/neverlate/data/sync/SyncTestDoubles.kt` | `FakeUserPreferencesRepository` gains `override suspend fun saveName(name: String)` (no-op or state-updating, matching its existing style). Also used by `SyncEngineTest`, `GuestAdoptionTest`, `OutboxTaskRepositoryTest`, `AuthRepositoryTest`. |
| `app/src/test/java/com/neverlate/ui/settings/SettingsViewModelTest.kt` | Its private `FakeUserPreferencesRepository` gains the override, updating the emitted `UserPreferences` so the new rename test can assert the round trip. |
| `app/src/test/java/com/neverlate/ui/onboarding/OnboardingViewModelTest.kt` | Its private `FakeUserPreferencesRepository` gains the override; onboarding's own assertions are unchanged (it must still call `saveOnboarding`, never `saveName` — worth an explicit assertion). |

`DataStoreUserPreferencesRepositoryTest` gains a case proving `saveName` writes the name **and leaves
`onboarded` untouched** (both when it was `true` and when it was `false`).

### D5 — Local-only, and what that means for guest mode and logout

**Decision: the name stays device-local.** It is not synced, not sent to the backend, and
`docs/api/contract.md` is **not** touched. Justification:

- The contract has no profile resource at all; adding one means an endpoint, a DTO, ownership checks,
  a migration path for existing accounts, and a conflict rule for a field two devices can both edit.
  That is a backend feature, and the constraint for this one is explicitly *no backend change*.
- The name is **display personalization, not account identity**. Account identity is the email the
  user registers with — already owned by the server, already out of scope here (see Out of Scope).
- Guest mode exists and is a first-class state. A profile field that only worked once you had an
  account would be a worse product than one that always works.

**Guest mode:** identical behaviour in every respect. The name is asked at onboarding, shown on both
surfaces, and editable from Settings whether the user is `Guest` or `LoggedIn`. The name row is
rendered **outside** the `if (uiState.authState is AuthState.LoggedIn)` branch in the Account card, so
it is present in both arms; only the button below it (Cerrar sesión / Iniciar sesión) still switches.

**Logout:** the name **persists**, and the user is **not** returned to onboarding. Verified against
`AuthRepository.clearLocalSession()`, which wipes the session tokens, the `task`/`task_outbox` tables
and resets `syncCursor` to `0` — it does not touch `Keys.NAME` or `Keys.ONBOARDED`, and this feature
deliberately does not add it there. Rationale: those wipes exist because that data is **backend-owned
and account-scoped**; the name is neither. Clearing it would drop the user into a nameless empty state
after a perfectly ordinary sign-out, or — if `onboarded` were cleared too — re-run first-run
onboarding on someone who has used the app for months.

**Known consequence, accepted (see Risks R1):** if a second person signs in on the same device, the
first person's name is still shown. The mitigation is this feature itself — the name is now visible in
Settings and changeable in two taps, which it was not before. A device is treated as belonging to one
person, consistent with "multiple profiles" being out of scope.

### D6 — Long names truncate; they never reflow the chrome

Handled in full in **Visual & UX Design** below. Summary: single-line + ellipsis in the Settings value
row; the empty state's personalized sentence is body copy inside `MessageState` and may wrap, but is
capped at two lines with ellipsis so it can never push the action button off-screen.

### D7 — Blank-name fallback on every surface

The name can be blank in practice: a pre-existing install whose `NAME` key was never written, a
corrupted/absent key (the repository already reads `?: ""`), or a future path that sets `onboarded`
without a name. Every consumer therefore falls back:

- Tasks empty state → the existing `tasks_empty` string, unchanged.
- Settings name row value → a localized `settings_name_not_set` placeholder ("Sin definir" / "Not
  set"); the **Cambiar** action is still available (that is how you fix it).

---

## User Stories

### US-1 — I can see that the app remembers my name
*As a person using Never Late Again, I want to see the name I gave at first launch, so that the
question I answered visibly meant something.*

**Acceptance criteria**
- With a non-blank stored name, the Tasks screen's **empty** state reads `Nada pendiente, <nombre>.`
  (ES) / `Nothing pending, <name>.` (EN) instead of the generic message.
- The empty state keeps its icon and its "Añadir tarea" action, unchanged.
- The `NoResults` state is **not** personalized.
- With a blank stored name, the empty state falls back to the existing `tasks_empty` string, with no
  stray comma, space or placeholder artefact.
- Settings → Cuenta shows a `Nombre` row with the stored value, or `Sin definir` when blank.

### US-2 — I can change my name without reinstalling
*As a person whose name was typed wrong, changed, or entered by someone else, I want to edit it from
Settings, so that I don't have to clear app data to fix one word.*

**Acceptance criteria**
- The `Nombre` row in the existing **Cuenta** card offers a **Cambiar** action; no new settings
  section and no new screen is introduced.
- Tapping it opens a dialog pre-filled with the current name, with the cursor able to edit it.
- **Guardar** persists the **trimmed** name and closes the dialog.
- **Cancelar**, a back press, or a tap outside dismisses the dialog and persists **nothing**.
- A whitespace-only or empty field disables **Guardar** and shows a localized error as supporting
  text on the field.
- Editing never re-runs onboarding and never changes the `onboarded` flag: after renaming, killing
  and relaunching the app lands on Tasks, not on the onboarding screen.

### US-3 — The new name appears immediately
*As a person who just corrected my name, I want the app to use it right away, so that I don't wonder
whether it saved.*

**Acceptance criteria**
- On **Guardar**, the Settings `Nombre` row updates without leaving or reloading the screen.
- Navigating to Tasks (with no tasks) shows the **new** name in the empty state, with no app restart
  and no process death required.
- Both surfaces observe `UserPreferencesRepository.userPreferences` reactively — neither reads the
  name once at construction (the existing `SettingsViewModel.init` collector pattern).

### US-4 — My name survives signing out
*As a guest, or as someone who signs out, I want my name to stay, so that a routine sign-out doesn't
make the app forget who I am or ask me to onboard again.*

**Acceptance criteria**
- Renaming works identically while `AuthState.Guest` and while `AuthState.LoggedIn`; the row is
  rendered in both arms of the Account card.
- After `logout()`, the stored name is unchanged, the empty state is still personalized, and the app
  does not return to onboarding.
- The name is never included in any HTTP request; `docs/api/contract.md` is untouched by this branch.

---

## Acceptance Criteria (consolidated, behavioural)

**Data layer**
1. `UserPreferencesRepository` gains `suspend fun saveName(name: String)`; `saveOnboarding` is
   **unchanged** in signature, body and semantics.
2. `DataStoreUserPreferencesRepository.saveName` writes `Keys.NAME = name.trim()` in a single
   `edit {}` and touches **no other key** — proven by a test that asserts `onboarded` is unchanged in
   both its `true` and `false` starting states.
3. No new DataStore file and no new key: this reuses `user_prefs` and `Keys.NAME`, per the existing
   "same store, no second DataStore" convention.
4. `OnboardingViewModel.save` still calls `saveOnboarding` (asserted), not `saveName`.

**Settings**
5. `SettingsUiState` gains `name: String = ""`, fed by the existing `repository.userPreferences`
   collector in `SettingsViewModel.init` via `copy(name = preferences.name)` — no second collector,
   no second flow.
6. `SettingsViewModel` gains `fun onNameChanged(name: String)` which launches on `viewModelScope` and
   calls `repository.saveName(name)`; it ignores a blank argument defensively (mirroring
   `OnboardingViewModel.save`'s `if (name.isBlank()) return`).
7. Dialog visibility and the in-progress draft text are **local** `remember` state in
   `SettingsScreen`, following the existing `showLogoutConfirm` precedent — they never need to
   outlive the composition.
8. The name row and its action live inside the existing `SettingsSectionCard` for
   `settings_account_section`, above the session button, separated by the same
   `HorizontalDivider(Modifier.padding(vertical = 12.dp))` idiom used elsewhere on this screen.

**Tasks**
9. `TasksViewModel` exposes the name as its own `StateFlow<String>` derived from
   `UserPreferencesRepository.userPreferences` (mapped, `stateIn`), collected by `TasksRoute` and
   passed to `TasksScreen` as a new `userName: String = ""` parameter.
10. `TasksUiState` is **not** modified: the name is not task-list state, and folding it into `Empty`
    would rebuild the sealed state on every unrelated preference emission.
11. `TasksScreen`'s `Empty` arm picks the personalized or generic string purely from
    `userName.isNotBlank()`.

**Cross-cutting (Definition of Done)**
12. Every new string exists in **both** `values/strings.xml` (Spanish base) and
    `values-en/strings.xml`; the personalized empty-state string uses a positional `%1$s`.
13. No Room migration, no schema version bump, no `app/schemas/` change — this branch touches
    DataStore only.
14. `docs/api/contract.md` unchanged (no wire change), and no new dependency in
    `gradle/libs.versions.toml`.
15. `./gradlew :app:testDebugUnitTest` is green, including the three updated fakes (D4).
16. New unit tests cover: `saveName` key isolation; `SettingsViewModel.onNameChanged` round trip;
    blank input rejected; `SettingsUiState.name` reflecting a repository emission. A Compose UI test
    covers: dialog opens pre-filled, **Guardar** disabled while blank, **Cancelar** persists nothing.

---

## Visual & UX Design

### Mockup slice

**Slice claimed: none — `—` (net-new, not a mockup element).** The master mockup
[`docs/mockups/rediseno-ux-ui.html`](../mockups/rediseno-ux-ui.html) has **no greeting, no profile
concept and no name anywhere**: its Settings frame shows icon+title cards (already ✅ as feature 15's
slice) and its Tasks frame shows a populated list, never an empty state. This feature therefore adds
UI *inside* two already-✅ slices without restyling either.

**Deferred, explicitly:** no avatar, no profile header block, no account card redesign — all
deliberately out of scope (below), not oversights. Nothing is deferred to a future mockup row.

**Tracking-table row to add during the Design review step** (the table is edited then, not now), in
the established format of the `—` rows:

> \| Profile name display + rename \| `editable-profile` \| — \| **Not in the master mockup** (no
> greeting/profile concept anywhere in it); net-new text inside two shipped slices, with **no
> restyle**. Tasks `Empty` state gains a personalized `MessageState` message (same icon, same action,
> falls back to the generic string when the name is blank). Settings' existing **Cuenta**
> `SettingsSectionCard` gains a label/value/action row above the session button, plus an
> `AlertDialog` + `OutlinedTextField` editor reusing the `LogoutConfirmDialog` shape. Single-line
> ellipsis on the value row, ≥48dp targets via `TextButton`/`minimumInteractiveComponentSize()`. No
> mockup slice claimed. \|

### Tokens and components reused (extend, don't duplicate)

- `ui/components/MessageState` — the empty state's **message string** changes; icon, action, layout,
  colors do not.
- `SettingsSectionCard` (feature 15) and its `HorizontalDivider` sub-block idiom — the Account card
  is **extended**, never replaced.
- `LogoutConfirmDialog`'s `AlertDialog` shape (title / content / confirm `TextButton` / dismiss
  `TextButton`) — the rename dialog is the same shape with an `OutlinedTextField` in the content slot.
- `brandedTopAppBarColors()` — untouched; no chrome change on any screen.
- Type scale: `bodyLarge` for the row label (matching the existing `settings_reminders_enabled_label`
  row), `bodyMedium`/`onSurfaceVariant` for the value, `bodySmall`/`error` for the dialog's supporting
  error text. No hardcoded colors, no one-off `sp`/`dp` typography.
- No new icon asset: the Account card's existing `Icons.Filled.AccountCircle` header already carries
  the section's meaning.

### Visual acceptance criteria

**V1 — Truncation, never chrome reflow.** The Settings value row renders the name with `maxLines = 1`
and `TextOverflow.Ellipsis`. A 200-character name must not wrap the row, must not push the **Cambiar**
action off the right edge (the value `Text` takes `Modifier.weight(1f)`, the action is measured
first), and must not change the card's height.

**V2 — Empty state stays whole.** The personalized message wraps naturally as body copy but is capped
at `maxLines = 2` with ellipsis, so the `MessageState` icon, message and "Añadir tarea" action all
remain visible on the smallest supported screen at the largest font scale.

**V3 — No greeting in any app bar.** Verified by inspection: `TasksScreen`'s `TopAppBar` still shows
`tasks_title` and the stats action, with `brandedTopAppBarColors()` unchanged. Feature 20's chrome is
untouched on every screen (D1).

**V4 — Touch targets ≥ 48dp.** The **Cambiar** action is a `TextButton` (already ≥48dp) or carries
`Modifier.minimumInteractiveComponentSize()`; the dialog's **Guardar**/**Cancelar** are `TextButton`s,
matching `LogoutConfirmDialog`. Verified at the smallest and largest font scales.

**V5 — Largest font scale reflows cleanly.** At the system's maximum font size: the Settings name row
keeps label and value legible with the action still reachable (the row may become two lines — label
above, value+action below — but must not clip or overlap); the rename dialog's title, field, error
text and both buttons remain reachable, scrolling within the dialog if necessary; the Tasks empty
state satisfies V2.

**V6 — Localized labels and error.** The field label, dialog title, buttons, the `Sin definir`
placeholder and the blank-name error are all `stringResource`s present in **both** `values/` and
`values-en/`. No English literal appears in the Spanish build and vice versa.

**V7 — Both themes and Material You.** The row and dialog are verified in light, dark, and with
dynamic color on (Android 12+). Because every color is a role token (`onSurface`,
`onSurfaceVariant`, `error`, plus `AlertDialog` defaults), this must require no per-theme code.

**V8 — Accessible state, not just disabled.** While the field is blank and touched, the localized
error is shown as supporting text **and** `Guardar` is disabled — a screen reader announcing the
button must therefore also encounter the reason. The field carries its localized label (announced),
and the value row's `Text` needs no extra `contentDescription` (the label `Text` beside it already
conveys meaning, matching the decorative-icon reasoning in `SettingsSectionCard`).

**V9 — Immediate reflection is visible, not just persisted.** After **Guardar**, the row's value
changes in place with no navigation, no spinner and no flicker of the placeholder.

### New string resources (both locales)

| Key | `values/` (ES, base) | `values-en/` (EN) |
|---|---|---|
| `tasks_empty_personalized` | `Nada pendiente, %1$s.` | `Nothing pending, %1$s.` |
| `settings_name_label` | `Nombre` | `Name` |
| `settings_name_not_set` | `Sin definir` | `Not set` |
| `settings_name_edit_button` | `Cambiar` | `Change` |
| `settings_name_dialog_title` | `Tu nombre` | `Your name` |
| `settings_name_field_label` | `Tu nombre` | `Your name` |
| `settings_name_error_empty` | `El nombre no puede estar vacío.` | `The name can't be empty.` |
| `settings_name_save_button` | `Guardar` | `Save` |
| `settings_name_cancel_button` | `Cancelar` | `Cancel` |

**Note on not reusing `onboarding_name_label` / `settings_logout_cancel_button`:** the text coincides
today, but sharing a string couples two screens' copy forever — a future tweak to onboarding's prompt
would silently rewrite a Settings dialog. String reuse is only safe when the *meaning*, not just the
current wording, is shared. New keys are added instead; the existing `onboarding_*` strings are
untouched.

---

## Technical Approach

Files this branch is expected to touch (`app/` only):

| File | Change |
|---|---|
| `app/src/main/java/com/neverlate/data/UserPreferencesRepository.kt` | Add `saveName(name: String)` to the interface + a `DataStoreUserPreferencesRepository` implementation writing only `Keys.NAME` (trimmed). KDoc must state why it exists alongside `saveOnboarding` (D4). |
| `app/src/main/java/com/neverlate/ui/settings/SettingsViewModel.kt` | `SettingsUiState.name`; fed from the existing preferences collector; `onNameChanged(name)`. |
| `app/src/main/java/com/neverlate/ui/settings/SettingsScreen.kt` | Name row inside the existing Account `SettingsSectionCard` (rendered in both auth arms), plus a private `EditNameDialog` composable modelled on `LogoutConfirmDialog`. New `onNameChanged: (String) -> Unit` parameter, wired in `SettingsRoute`. |
| `app/src/main/java/com/neverlate/ui/tasks/TasksViewModel.kt` | Inject `UserPreferencesRepository`; expose `userName: StateFlow<String>` (`map { it.name }.stateIn(...)`). Note: this ViewModel currently injects `TaskRepository` + `MotionSettings` — Hilt resolves the addition from `StorageModule` with no wiring change. |
| `app/src/main/java/com/neverlate/ui/tasks/TasksScreen.kt` | New `userName: String = ""` parameter; the `Empty` arm chooses the personalized vs. generic string. `TasksRoute` collects and forwards it. |
| `app/src/main/res/values/strings.xml`, `values-en/strings.xml` | The nine string pairs above. |
| `app/src/main/java/com/neverlate/ui/onboarding/*` | **Unchanged.** Onboarding keeps calling `saveOnboarding`. |
| Tests (D4 table) + new cases | Three fakes updated; new repository/ViewModel/Compose tests per AC-16. |

---

## Out of Scope

- **Multiple profiles / accounts on one device.** One device, one name (D5, R1).
- **Avatar, photo, initials chip or any image.** No Coil dependency is introduced.
- **Anything backend-account-shaped:** changing the registration email, changing/resetting the
  password, deleting the account. That is **auth surface**, not profile, and belongs to a separate
  spec with a contract change.
- **Syncing the name** to the backend or across devices (D5). No `/profile` endpoint, no `TaskDto`-like
  `ProfileDto`, no `docs/api/contract.md` edit.
- **Removing or restyling the onboarding screen** (D1-alt). If the user wants removal, this spec is
  replaced rather than extended.
- **A greeting in any top app bar** or a new profile header block (D1).
- **Personalizing notifications, the widget, or the stats screen** with the name. Those are separate
  surfaces with their own truncation and localization concerns; if wanted, they follow as their own
  feature once the name has a proven home.
- **Time-of-day greetings** ("Buenos días, Aritz") — a separate, locale- and clock-sensitive problem.

---

## Dependencies

- **Nothing must be built first.** Everything this feature needs already exists and was verified in
  the current tree: the `user_prefs` DataStore and its repository interface (feature 07), the
  `SettingsSectionCard` Account section (feature 15), `MessageState` (feature 17), the
  `AlertDialog` confirm/dismiss idiom (feature 18), and Hilt injection into both ViewModels
  (feature 13d).
- **No backend, no emulator-only dependency, no new Gradle dependency, no new permission.**
- **Approval of D1** (keep vs. remove the onboarding question) is the one true blocker — the rest of
  the spec is downstream of it.

---

## Risks

**R1 — A second person signs in on a shared device and sees the first person's name.**
*Likelihood: low. Impact: mild but personal.* Accepted per D5: the name is device-local
personalization, and logout deliberately does not clear it. Mitigation is the feature itself — the
name is now visible in Settings and editable in two taps. Revisit only if multiple profiles ever
enter scope.

**R2 — The empty state is a thin payoff for a blocking first-run question.**
*Likelihood: medium. Impact: the original complaint partially survives.* This is the strongest
argument for D1-alt and is not hand-waved: a user with tasks may go weeks without seeing the empty
state. The Settings row is what makes the value continuously inspectable, and the honest fallback is
recorded — if, after shipping, the name still feels vestigial, the correct next move is the
`feature/retire-onboarding` spec, **not** bolting the name into more chrome.

**R3 — Adding an interface method breaks three test doubles.**
*Likelihood: certain. Impact: compile errors only.* Enumerated in D4 with the exact files. Called out
here so the failure is recognized as planned work, not a regression.

**R4 — Pathological name lengths.**
*Likelihood: low. Impact: layout damage if unhandled.* Mitigated by display-side truncation (V1/V2)
rather than by a validation rule that would diverge from onboarding's (D3).

**R5 — Blank name on an existing install.**
*Likelihood: low. Impact: a stray "Nada pendiente, ." if unhandled.* Mitigated by D7's fallback on
every surface, with an explicit AC and test.

**R6 — Scope creep into auth.** "Edit my profile" invites "…and my email, and my password".
*Mitigation:* the Out of Scope section names those explicitly; they are backend-owned and require a
contract change this branch is forbidden to make.

---

## Approval

Please review and approve — approval covers **behaviour, look and the tutorial decision** together:

1. **D1** — keep the onboarding question and surface the name on the Tasks empty state + the Settings
   Account card, with **no** greeting in any app bar. (Or reject in favour of **D1-alt**, retiring
   onboarding, which replaces this spec.)
2. **D2** — the editor is a dialog inside the existing Account card, not a new section or screen.
3. **D4** — a new `saveName(name)` method, with three test doubles updated as expected fallout.
4. **D5** — local-only: no sync, no contract change; the name survives logout and works identically
   in guest mode.
5. **Tutorial: No (solo producto)** — no Spanish lesson, no tutorial number reserved.

Implementation must not begin before explicit approval.
