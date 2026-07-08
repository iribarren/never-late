# Feature 18 — Bottom navigation bar + accessibility review

- **Status:** Awaiting final approval — three open decisions resolved (see Resolved Decisions)
- **Date:** 2026-07-08
- **Type:** Larger architectural feature (navigation restructure) + cross-cutting a11y pass
- **Tutorial lesson (separate deliverable):** `tutorial/18-navegacion-accesibilidad.md` (Spanish)

## Overview

Today the app's main sections are reached from a **hub `HomeScreen`**: an onboarded user lands on
Home and taps a list row (Tasks, Articles) or a Settings icon in the top bar to drill in, then uses
the system back button to return. Each section is a separate leaf of a single `NavHost` inside
`MainAppNavHost` (`AppNavHost.kt`).

This feature replaces that hub-and-spoke flow with a **persistent bottom `NavigationBar`** — the
standard Material 3 pattern for switching between an app's few top-level destinations — exposing
**Tasks / Articles / Settings** as always-visible tabs. Selecting a tab is a lateral switch, not a
push, so the sections become peers rather than children of a Home screen.

The change is deliberately scoped as an **architectural** lesson: it restructures navigation
(introducing a `Scaffold` `bottomBar`, tab selection synced to the back-stack, and the
`saveState`/`restoreState`/`launchSingleTop` idiom) **without** touching the backend, the API
contract, the database schema, permissions, or dependencies. Because navigation and a11y are
naturally coupled (a nav bar is a prime accessibility surface — labels, selected-state semantics,
touch targets, large-font layouts), the feature also folds in a **cross-cutting accessibility
review** of the existing screens, mapping to the pending-concepts backlog entry
[`docs/conceptos-pendientes.md`](../conceptos-pendientes.md) §7 ("Accesibilidad a fondo").

Finally, it hardens one destructive action that predates a confirmation habit: **logout** (a plain
`TextButton` in Settings) gains an `AlertDialog` confirmation, reusing the existing `DeleteTaskDialog`
pattern from `TasksScreen.kt`.

## Goals

- The three main sections (Tasks, Articles, Settings) are reachable with a **single tap** from
  anywhere in the main app via a persistent bottom `NavigationBar`.
- The selected tab always reflects the **current destination**, and re-selecting a tab is a no-op /
  returns to that section's root rather than stacking duplicates.
- The **three-faced auth gate** (`LoggedOut` / `Guest` / `LoggedIn`) and the **conditional startup
  routing** (onboarding-first, widget `openTasksOnStart`) are fully preserved. The bottom bar is
  present **only inside `MainAppNavHost`** (Guest + LoggedIn), never on the login gate.
- Feature 13's requirement that `Guest` and `LoggedIn` remain **separate `when` arms** in
  `AppNavHost` is preserved — the restructure does **not** merge them.
- Every interactive element on the reviewed screens has a coherent `contentDescription` /
  `semantics`, a touch target ≥ 48dp, and a layout that survives the system's largest font scale
  without clipping or losing controls.
- Logging out requires explicit confirmation.
- The feature teaches at least one genuinely new Android/Compose concept (top-level tab navigation)
  and consolidates the a11y concepts the app has been using ad hoc.

## User Stories

### US-1 — Bottom navigation bar for the main sections

**As a** user of the app,
**I want** a bottom bar that always shows Tasks, Articles and Settings,
**so that** I can jump between the app's main areas in one tap instead of returning to a hub screen.

**Acceptance Criteria**

- A Material 3 `NavigationBar` is rendered as the `bottomBar` of a `Scaffold` that wraps the
  main-app `NavHost`, with exactly three `NavigationBarItem`s: **Tasks/Tareas**, **Articles/Artículos**,
  **Settings/Ajustes**, each with an icon + label.
- Each item's icon has an appropriate `contentDescription` (or the item is labelled such that the
  screen reader announces the section name and its selected state).
- Tapping an item navigates to that section's root destination.
- The bar is visible while on any of the three top-level sections. It is **not** shown on the login
  gate (`AuthGateNavHost`), i.e. while `AuthState.LoggedOut`.
- Labels come from string resources (Spanish base + English variant), never hardcoded.

### US-2 — Selected tab stays in sync with the current route

**As a** user,
**I want** the bottom bar to highlight the section I'm currently in,
**so that** I always know where I am, and re-tapping my current tab doesn't pile up screens.

**Acceptance Criteria**

- The selected `NavigationBarItem` is derived from the **current `NavController` back-stack entry's
  route** (observed reactively), not from local state that could drift.
- Tab navigation uses `launchSingleTop = true` (no duplicate destination on re-selection) plus
  `saveState = true` / `restoreState = true` so each section's scroll position / transient UI state
  is preserved when switching away and back.
- Switching tabs pops back to the start destination of the graph (`popUpTo(startDestination) { saveState = true }`)
  so the back stack does not grow unbounded as the user hops between tabs.
- System **back** from a top-level section behaves predictably (returns to the start destination /
  exits the app at the root) and does not leave the user on an orphaned screen with a mismatched
  selected tab.

### US-3 — Startup routing and auth gate preserved

**As a** returning or first-time user (guest or signed-in),
**I want** the app to still open on the right screen and behave correctly whether or not I have an
account,
**so that** adding the bottom bar doesn't regress onboarding, the widget shortcut, or guest mode.

**Acceptance Criteria**

- Not-yet-onboarded users still land on **Onboarding** (no bottom bar until they finish), and
  Onboarding still cannot be skipped by the widget shortcut.
- `openTasksOnStart` (widget tap, feature 05) still opens an onboarded user directly on **Tasks**,
  now with the **Tasks** tab shown as selected.
- The bottom bar and its `Scaffold` live **inside `MainAppNavHost`** and appear for both
  `AuthState.Guest` and `AuthState.LoggedIn`; they never appear for `AuthState.LoggedOut`.
- `AuthState.Guest` and `AuthState.LoggedIn` remain **separate `when` arms** in `AppNavHost`; the
  guest→signed-in adoption drain (`LaunchedEffect` re-firing on the branch switch) still works. The
  feature must **not** merge those arms.
- Detail/edit destinations that are logically "inside" a section (Article Detail, Task Edit,
  Login/Register-from-Settings) still function; per **Resolved Decision #2** the bar is **hidden** on
  those secondary screens (route-gated) and shown only on the three top-level sections.

### US-4 — Confirm logout with a dialog

**As a** signed-in user,
**I want** a confirmation prompt before I log out,
**so that** I don't accidentally end my session (and, per feature 13, wipe local tasks) with a single
mis-tap.

**Acceptance Criteria**

- Tapping "Log out" in Settings opens an `AlertDialog` with a title, an explanatory message, a
  confirm action and a dismiss action — following the existing `DeleteTaskDialog` pattern
  (`TasksScreen.kt`).
- Confirm triggers the existing `SettingsViewModel.logout()`; dismiss / outside-tap / back closes the
  dialog with no side effect.
- All dialog strings are localized resources.
- The dialog only applies to the destructive **logout** action; the guest "Sign in / Create account"
  entry is unaffected.

### US-5 — Accessibility review of existing screens

**As a** user who relies on TalkBack, large fonts, or has limited motor precision,
**I want** the app's controls to be properly labelled, comfortably tappable, and to reflow at large
text sizes,
**so that** I can use every screen without hitting unlabeled buttons, tiny targets, or clipped layouts.

**Acceptance Criteria**

- Every interactive element on Tasks, Articles (list + detail), Settings and the
  new bottom bar has a meaningful `contentDescription` or text label; purely decorative icons are
  explicitly `contentDescription = null` (as `SettingsSectionCard` already does).
- All tappable targets are **≥ 48dp** in their minimum touch dimension (using `minimumInteractiveComponentSize`
  / sizing modifiers where a visual is smaller).
- Each reviewed screen is verified against the **largest system font scale** and does not clip text,
  hide controls, or overflow horizontally; scrollable containers are used where content can exceed
  the viewport (Settings already does this — apply the same discipline elsewhere as needed).
- Selected state on the bottom bar is announced by accessibility services (Compose's
  `NavigationBarItem` provides `Role`/selected semantics — verify it is not overridden away).
- Findings and the touch-ups made are captured in the tutorial lesson so the review is reproducible.

> Note: US-5 is a **review + targeted touch-ups** pass, not a promise to redesign layouts. Concrete
> fixes are limited to labels, semantics, touch-target sizing, and small reflow corrections. Anything
> larger that surfaces is logged as a follow-up rather than expanded in-scope.

## Technical Approach

All changes are on the **Android app** (`app/`); no backend, contract, DB, permission, or dependency
change.

1. **Introduce a `Scaffold` with `bottomBar` inside `MainAppNavHost`** (`ui/navigation/AppNavHost.kt`).
   The existing `NavController`, `object Routes`, and the async startup routing (`userPreferences`
   collection → `LoadingIndicator` → `startDestination` selection) are **extended, not duplicated**:
   the current `NavHost(...)` block is wrapped by a `Scaffold`, and the bar reads the live
   destination from the same `navController`.
   - The bottom bar's three items map to `Routes.TASKS`, `Routes.ARTICLES`, and `Routes.SETTINGS`.
   - Current destination is observed via `navController.currentBackStackEntryAsState()` and compared
     against each item's route (typically on `destination.hierarchy`) to compute `selected`.
   - Tab clicks navigate with the standard idiom:
     `navigate(route) { popUpTo(navController.graph.findStartDestination().id) { saveState = true }; launchSingleTop = true; restoreState = true }`.
   - `Scaffold`'s `innerPadding` is threaded into the `NavHost` so section content is not drawn under
     the bar.
2. **Preserve the auth/startup structure.** `AppNavHost`'s `when(authState)` keeps its three arms
   unchanged; only `MainAppNavHost`'s internals gain the `Scaffold`. `AuthGateNavHost` is untouched,
   so no bar renders on the login gate. The `openTasksOnStart`/onboarding `startDestination` logic is
   preserved verbatim and simply feeds the wrapped `NavHost`.
3. **Bar visibility on secondary screens** (Article Detail, Task Edit, Login/Register-from-Settings):
   per **Resolved Decision #2**, the bar is **hidden** on non-top-level routes. Compute visibility
   from the current route (e.g. show the `bottomBar` only when the current destination is one of
   `Routes.TASKS`/`ARTICLES`/`SETTINGS`) so detail/edit screens render full-height.
4. **Home hub retired** (**Resolved Decision #1**): `HomeScreen` is removed and **Tasks** becomes the
   top-level landing destination. Startup routing still sends onboarded users and the widget
   (`openTasksOnStart`) path to Tasks; any Home route/strings that become dead are removed.
5. **Logout confirmation** (`ui/settings/SettingsScreen.kt`): replace the direct `onLogoutClick`
   `TextButton` with a button that flips a local `remember { mutableStateOf(false) }`, rendering a new
   `AlertDialog` (mirroring `DeleteTaskDialog`) whose confirm calls `onLogoutClick`. Add the dialog's
   title/message/confirm/cancel strings to `values` + `values-en`.
6. **Accessibility pass**: audit `ui/tasks`, `ui/articles`, `ui/settings` and the
   new bar for `contentDescription`/`semantics`, apply `Modifier.minimumInteractiveComponentSize()` /
   sizing where targets are < 48dp, and verify large-font reflow (Compose Preview `fontScale` +
   device font-size setting). Add any missing strings.

**Testing** (via `qa-engineer`): Compose UI tests asserting (a) the bar shows the three items and the
selected item matches the active route, (b) tapping a tab navigates and updates selection, (c) the bar
is absent on the login gate, (d) the logout dialog appears and confirm invokes logout while dismiss
does not, and (e) key controls expose their content descriptions. Reuse the existing test infra
(`createComposeRule`, semantics matchers). No pure-domain logic is added, so this is UI-test-only.

## New Tutorial Concepts (for `tutorial/18-navegacion-accesibilidad.md`, Spanish)

Progressive, building on lessons already shipped (Navigation Compose was introduced earlier;
`Scaffold`/`TopAppBar` and `AlertDialog` are already used across the app):

- **Top-level / lateral navigation** with `NavigationBar` + `NavigationBarItem` — how it differs from
  the push-style `navigate()` used so far.
- **`Scaffold` `bottomBar` slot** and threading `innerPadding` into a `NavHost`.
- **Observing the current destination reactively** with `currentBackStackEntryAsState()` and
  `destination.hierarchy` to drive selected state (state derived from navigation, not duplicated).
- **The tab-switch navigation idiom:** `launchSingleTop`, `saveState`/`restoreState`, and
  `popUpTo(startDestination)` — what each one prevents (duplicate destinations, lost state, unbounded
  back stack).
- **Accessibility fundamentals** (`conceptos-pendientes.md` §7): `contentDescription` vs decorative
  `null`, `Modifier.semantics`, the 48dp minimum touch target
  (`minimumInteractiveComponentSize`), selected-state semantics, and testing layouts against large
  font scales.
- **Reusing a dialog pattern** to guard a destructive action (logout), reinforcing the
  `DeleteTaskDialog` approach already in the codebase.

## Resolved Decisions (confirmed by product owner, 2026-07-08)

1. **Home hub — RETIRE.** `HomeScreen` is removed; with a persistent bottom bar it is redundant, and
   three tabs (Tasks / Articles / Settings) is the cleaner Material pattern. Onboarded and widget
   (`openTasksOnStart`) startup routing now targets **Tasks** as the landing destination. Any
   Home-specific routing/strings are cleaned up as part of the change.
2. **Bar on secondary screens — HIDE.** The bar is shown only on the three top-level sections; on
   Article Detail, Task Edit, and Login/Register-from-Settings the bar is hidden so those
   detail/edit screens own the full height (common Material behaviour). Visibility is gated on the
   current route.
3. **Default selected tab / start destination — TASKS.** Tasks is the landing tab for onboarded users
   and the target of the widget path; its tab shows as selected on that startup.

## Out of Scope

- Any **backend**, **API contract**, **database schema/version**, **permission**, or **new
  dependency** change (hard constraint).
- A **navigation-drawer**, **navigation-rail**, or **tablet/foldable adaptive** layout
  (`NavigationRail`, `WindowSizeClass`). Adaptive layouts remain a separate pending-concepts item;
  this feature is phone bottom-bar only.
- A **full visual redesign** of any screen. `docs/mockups/rediseno-ux-ui.html` is a **direction
  reference only**; this feature does not implement that mockup.
- A **complete WCAG audit / certification**. US-5 is a focused review + targeted touch-ups, not a
  formal accessibility certification.
- New **badges / counts** on nav items, animated tab transitions, or per-tab nested back stacks
  beyond the standard `saveState`/`restoreState` behaviour.
- Changes to **auth, sync, reminders, or task/article logic** beyond the logout-confirmation dialog.
- Reworking the **login gate**'s navigation (`AuthGateNavHost` stays as-is).

## Dependencies

- **Navigation Compose** (already in the project) — `NavigationBar`/`NavigationBarItem` come from
  Material 3, `currentBackStackEntryAsState()` from Navigation Compose. No new artifact.
- **Existing structures that must be extended, not replaced:** `MainAppNavHost`'s `NavController`,
  `object Routes`, the `userPreferences`-driven `startDestination` logic, and `openTasksOnStart`.
- **Feature 13 invariant:** `Guest`/`LoggedIn` stay as separate `when` arms in `AppNavHost` (see its
  KDoc) — the adoption drain depends on the branch switch; do not merge.
- **`DeleteTaskDialog` pattern** (`TasksScreen.kt`) as the template for the logout `AlertDialog`.
- **String resources** infrastructure (`values` Spanish base + `values-en`) for all new labels/dialog
  copy.
- **Localization:** section labels and dialog copy must exist in both locales before merge.
- **Resolved Decisions above are settled** (Home retired → Tasks landing, bar hidden on secondary
  screens, default tab Tasks) — no longer blocking.

## Risks

- **Regressing the guest→signed-in adoption drain.** If the `Scaffold` refactor accidentally merges
  the `Guest`/`LoggedIn` arms or hoists a single `NavController` above the `when`, the
  `LaunchedEffect` that drains the outbox on sign-in may stop re-firing. *Mitigation:* keep the
  `Scaffold` strictly **inside** `MainAppNavHost`; do not touch `AppNavHost`'s `when` arms; add a test
  covering a Guest→LoggedIn transition still triggering a refresh.
- **Selected-state / back-stack drift.** Getting `popUpTo`/`saveState`/`restoreState`/`launchSingleTop`
  wrong causes duplicated destinations, a growing back stack, or a highlighted tab that doesn't match
  the screen. *Mitigation:* use the canonical idiom against `graph.findStartDestination()`; cover with
  a UI test.
- **Startup routing regression.** Onboarding-first and `openTasksOnStart` must survive the wrap.
  *Mitigation:* leave the `startDestination` computation untouched and feed it into the wrapped
  `NavHost`; test both cold-start paths.
- **Bar appearing on the wrong surface.** The bar must never show on the login gate; and per Open
  Decision #2, possibly not on detail/edit screens. *Mitigation:* the bar lives only in
  `MainAppNavHost`; if hiding on secondary routes, gate on the current route explicitly and test it.
- **Large-font / a11y touch-ups uncovering deeper layout issues.** A screen might not reflow cleanly
  at max font scale. *Mitigation:* scope US-5 to labels/targets/small reflow fixes; log anything
  structural as a follow-up rather than growing this feature.
- **Scope creep from the mockup.** The visual reference invites redesign. *Mitigation:* explicitly
  out of scope; direction-only.

---

**Please give final approval before implementation begins.** The three previously-open decisions are
now resolved (Home retired → Tasks landing, bar hidden on secondary screens, default tab Tasks); see
**Resolved Decisions**. No other blockers remain.
