# Feature 18b — Layouts adaptables para pantallas grandes (tablet)

**Status:** Draft — awaiting approval
**Owning lesson:** `tutorial/18b-layouts-adaptables.md` (numbered **18b**, between 18 and 19)
**Backlog slot:** `docs/conceptos-pendientes.md` §7 (accesibilidad / adaptativo) — the "adaptive half" that
feature 18 (accessibility) deliberately left pending
**Suggested branch:** `feature/adaptive-layouts`
**Type:** UI/architecture layer on top of the existing single nav graph — **no** behaviour change on phones,
**no** backend/contract/DB/permission change

---

## Overview

Since feature 18 the app has three peer top-level sections (Tasks / Articles / Settings) reached through a
persistent bottom `NavigationBar`, over a single Navigation Compose graph (`MainAppNavHost`). That layout is
tuned for one width: a phone in portrait. On a tablet, a large phone in landscape, or a foldable, the same
layout stretches a single column across the whole screen — a bottom bar marooned under a very wide, mostly
empty content area, and article rows whose line length runs far past comfortable reading width.

Feature 18b makes the app **width-aware**. Using `WindowSizeClass` (compact / medium / expanded), it adds an
**adaptive layer above the unchanged feature-18 graph** that:

1. Swaps the primary navigation from a bottom `NavigationBar` (compact) to a side `NavigationRail`
   (medium/expanded), keeping the exact same three destinations, the same auth gate (guest mode, feature 13),
   and the same tab-switch idiom.
2. Presents **Articles as a two-pane list-detail** on expanded width (list on the left, article body on the
   right) while keeping the familiar list → push-detail navigation on compact width.
3. Reflows correctly on phone, tablet, and landscape, and at the largest font scale — with **no accessibility
   regression** from feature 18 (touch targets stay ≥ 48 dp; content descriptions preserved).

This is a **teaching feature** first: it continues the accessibility review of lesson 18 into responsive
design, and introduces adaptive layout as a first-class Android concept. It is explicitly **not** a product
capability change — a phone user sees no difference.

## Goals

- The same app is genuinely usable and looks intentional on a phone, a tablet (portrait and landscape), and a
  large phone in landscape — not a stretched phone layout.
- The primary navigation reads as a side rail on large widths and a bottom bar on compact widths, with
  selection and back-stack behaviour identical to feature 18.
- Articles uses the available width on a tablet: list and detail visible at once, no full-screen push just to
  read one article.
- Zero regressions: phone behaviour, the guest-mode auth gate, and every feature-18 accessibility guarantee
  are preserved.
- The lesson teaches `WindowSizeClass`, adaptive navigation (bar ↔ rail without duplicating the graph),
  list-detail, and how to preview/verify multiple sizes — reusing, not duplicating, the feature-18 code.

## Didactic objectives (new concepts for lesson 18b)

Each feature must teach something new (Tutorial Methodology). Lesson 18b introduces:

- **`WindowSizeClass`** (compact / medium / expanded) — what a size class is, why it is based on *available
  width* rather than raw device type, and how to obtain it in Compose from the Activity
  (`calculateWindowSizeClass(this)` in `MainActivity`, threaded down through the composition).
- **Adaptive navigation without a second graph** — deriving `NavigationBar` vs `NavigationRail` from the
  width size class while reusing the *same* `bottomNavItems` list, the *same* navigate idiom
  (`popUpTo(startDestination){ saveState } + launchSingleTop + restoreState`), and the *same* selected-tab
  derivation from the live back stack. The lesson's key point: the adaptive layout is a **presentation layer
  above one graph**, never a duplicated graph.
- **List-detail pattern** — when to split one destination into two panes, and how (`ListDetailPaneScaffold`,
  or a manual two-pane `Row` as the fallback — see Dependencies/Risks). Contrast with the compact single-pane
  push navigation that stays in place.
- **Testing/previewing sizes** — `@Preview(widthDp = …)` at compact/medium/expanded widths, and *why
  responsive design is a continuation of accessibility* (both are about the UI adapting to the user's context
  rather than assuming one fixed environment) — closing the loop opened by lesson 18.

## User Stories

### US-1 — Side rail on large screens
*As a tablet user, I want the primary navigation on the side instead of the bottom, so that navigation sits
where it is reachable and the wide content area is not wasted.*

**Acceptance criteria**
- On **expanded** width the bottom `NavigationBar` is replaced by a `NavigationRail` on the leading edge; the
  bottom bar is not shown.
- On **medium** width the primary navigation is also a `NavigationRail` (a tablet in portrait / large phone in
  landscape gets the rail, not the bottom bar).
- On **compact** width the layout is unchanged from feature 18: bottom `NavigationBar`, exactly as today.
- The rail exposes the **same three destinations** (Tasks / Articles / Settings) with the same icons and
  content descriptions as the bottom bar, driven by the same `bottomNavItems` list.
- Selecting a rail item uses the identical tab-switch semantics as feature 18 (single top, saved/restored
  state, no unbounded back stack); the selected item is derived reactively from the current back stack, never
  a `remember`ed index.
- Rotating the device or resizing a foldable/window across a breakpoint swaps bar ↔ rail without losing the
  current section or its scroll state.

### US-2 — Two-pane Articles on large screens
*As a tablet user, I want to see the article list and the selected article side by side, so that I can browse
and read without a full-screen jump for every article.*

**Acceptance criteria**
- On **expanded** width, Articles shows **two panes**: the paginated list (left) and the selected article's
  detail (right).
- Tapping a list item on expanded width updates the **right pane** in place (no full-screen navigation).
- On first entry to Articles at expanded width with nothing selected, the detail pane shows a neutral
  placeholder (e.g. a `MessageState` "select an article" prompt), not a blank void or a crash.
- On **compact** width, Articles behaves exactly as feature 18/13c: a single-pane list that pushes the
  `ArticleDetail` destination on tap, with the back arrow returning to the list.
- The list keeps Paging 3 behaviour unchanged (pull-to-refresh, append spinner, inline append-retry,
  full-screen empty/error `MessageState`); paging is not re-implemented for the two-pane case.
- `getArticleById` (kept since feature 13c for the detail) supplies the right-pane content; the article
  domain/data layer is unchanged.

### US-3 — No regression on phones and at the auth gate
*As a phone user (and as any signed-out/guest user), I want the app to look and behave exactly as before, so
that the tablet work costs me nothing.*

**Acceptance criteria**
- On a compact-width phone every screen is pixel-equivalent to feature 18 (bottom bar, single-pane Articles,
  same routing).
- The login/register **auth gate** (`AuthGateNavHost`, feature 12/13) shows **no** rail and **no** bottom bar
  at any width — nav chrome continues to live strictly inside `MainAppNavHost`, never on the gate.
- Guest mode (feature 13) is untouched: the separate `Guest` / `LoggedIn` `when` arms in `AppNavHost` are
  **not** merged (adoption-on-sign-in still fires), and login/register-from-Settings still render full-height.
- Onboarding, Task Edit, Article Detail (compact), Stats, and Login/Register-from-Settings remain full-height
  secondary screens with their back arrows — the rail/bar visibility is route-gated exactly as the bottom bar
  is today.

### US-4 — Correct reflow and preserved accessibility at any size and font scale
*As a user with a large system font or a non-standard screen, I want the adaptive layouts to reflow without
clipping or breaking touch targets, so that responsiveness never costs accessibility.*

**Acceptance criteria**
- At the **largest system font scale**, every adaptive layout (rail, two-pane Articles, and the single-pane
  screens on large width) reflows without clipped text, overlap, or horizontal overflow.
- All interactive elements — rail items, list rows, checkboxes, buttons — keep a touch target **≥ 48 dp** at
  every size class (feature 18's `minimumInteractiveComponentSize()` guarantees preserved).
- All rail icons carry the same `contentDescription`s as the bottom-bar icons; decorative elements stay
  decorative.
- Tasks and Settings on large width do not stretch a single column edge-to-edge illegibly (see Visual & UX
  Design for the constraint applied).

## Technical Approach

High level: obtain the width size class once at the top of the composition and pass it down as a plain
parameter; branch on it inside the **existing** graph host. No new graph, no changes to any `*ViewModel`, no
data-layer change.

- **Obtain the size class (`MainActivity`).** Call `calculateWindowSizeClass(this)` in `setContent` and pass
  its `widthSizeClass` into `AppNavHost(...)`. `AppNavHost` threads it through to `MainAppNavHost`. The auth
  gate branch ignores it (US-3).
- **Adaptive navigation (`ui/navigation/AppNavHost.kt`).** In `MainAppNavHost`, replace the single
  `Scaffold { bottomBar = MainBottomBar }` with a width branch:
  - **Compact:** the current `Scaffold` + `MainBottomBar` (unchanged).
  - **Medium / Expanded:** a `Row { NavigationRail(...) ; NavHost(...) }`, where the rail is a new
    `MainNavigationRail` composable that reuses the existing `bottomNavItems` list, the existing selected-tab
    derivation (`currentBackStackEntryAsState()` + `destination.hierarchy`), and the existing navigate idiom.
    Both bar and rail should share a single private helper for "navigate to top-level route" so the idiom is
    written once (extend, don't duplicate).
  - The rail/bar is still **route-gated** by `TOP_LEVEL_ROUTES`: hidden on Article Detail (compact), Task
    Edit, Stats, Onboarding, and Login/Register-from-Settings.
- **List-detail Articles.** Add a width-aware Articles entry that, on **expanded**, renders list + detail in
  two panes (selection held as local pane state, `getArticleById` feeding the right pane via the existing
  `ArticleDetailViewModel`/`hiltViewModel()` with the selected id), and on **compact/medium** delegates to the
  current single-pane `ArticlesScreen` + push `ArticleDetail` route. The **preferred** implementation is
  `ListDetailPaneScaffold` (teaches a named adaptive API and lets the scaffold decide pane count); a manual
  two-pane `Row(ArticleList, ArticleDetailScreen)` is the documented fallback if the adaptive dependency
  cannot be pinned compatibly on AGP 8.13.2 (see Risks). Either way the existing `ArticlesScreen`,
  `ArticleDetailScreen`, their `*Route` wrappers, and the Paging pipeline are **reused**, not rewritten. The
  compact `ArticleDetail` nav destination stays for the single-pane path.
- **Single-pane large-width screens (Tasks, Settings).** No two-pane split this feature; they keep their
  current content but get a **max content width** constraint so a wide tablet does not stretch one column
  edge-to-edge (see Visual & UX Design). This is a small layout tweak, not a restructure.
- **Nullable `onBack` unchanged.** `TasksScreen` / `ArticlesScreen` / `SettingsScreen` keep their
  `onBack: (() -> Unit)? = null` contract from feature 18; the adaptive layer decides bar-vs-rail, not the
  screens themselves.

## Visual & UX Design

### Master mockup slice

`docs/mockups/rediseno-ux-ui.html` is a **phone-only** north star — it has no tablet/large-screen frame, no
`NavigationRail`, and no two-pane layout (confirmed: the mockup contains no large-screen breakpoints). Therefore
**this feature claims no mockup slice**. Instead it *extends the intent* of two already-shipped phone slices to
large screens:

- The bottom-nav slice (feature 18, ✅) → its large-screen counterpart is the `NavigationRail`.
- The Articles list + Article Detail slices → their large-screen counterpart is the two-pane list-detail.

A new tracking row is added to `docs/mockups/README.md` marked **"— not a mockup slice (net-new large-screen
adaptation)"**, noting what large-screen polish is deferred. **Explicitly deferred (stated here, not silently):**
two-pane list-detail for **Tasks** (task list + Task Edit) and **Settings** (sections + detail), a
list-detail-aware article header image (Coil, lesson 10b), and any tablet-specific spacing/typography scale —
all future work, not this feature.

### Per-screen behaviour by size class

| Screen | Compact (phone portrait) | Medium (large phone landscape / tablet portrait) | Expanded (tablet landscape) |
|---|---|---|---|
| **Primary nav** | Bottom `NavigationBar` (feature 18, unchanged) | `NavigationRail`, leading edge | `NavigationRail`, leading edge |
| **Tasks** | Single pane, current layout | Single pane; content constrained to a max readable width, centered in the remaining area | Same as medium (two-pane deferred) |
| **Articles** | Single-pane list → push `ArticleDetail` on tap (feature 13c, unchanged) | Single-pane list → push detail (same as compact) | **Two panes**: paginated list (left) + article body (right); tap updates right pane in place; placeholder when nothing selected |
| **Settings** | Single pane, current layout | Single pane; content constrained to a max readable width, centered | Same as medium |
| **Article detail (as a pane)** | n/a (full-screen pushed route) | n/a | Right pane of the two-pane Articles; reuses `ArticleDetailScreen` body; no back arrow inside the pane |
| **Auth gate (Login/Register)** | Full-height, no chrome | Full-height, no chrome (no rail) | Full-height, no chrome (no rail) |

Rationale for the rail switch at **medium** (not only expanded): a tablet in portrait and a large phone in
landscape both have the vertical room and the width where a side rail reads better than a bottom bar; the
guaranteed, testable AC is pinned at expanded (US-1), with medium documented as the same treatment.

### Visual acceptance criteria — per size class

**Compact width**
- Bottom `NavigationBar` present; **no** `NavigationRail` anywhere.
- Articles is single-pane; tapping a row pushes the full-screen `ArticleDetail`.
- Every screen is visually equivalent to feature 18 (no restyle, same theme tokens).

**Medium width**
- `NavigationRail` on the leading edge replaces the bottom bar; the bottom bar is not shown.
- Rail items use the same icons/labels/`contentDescription`s as the bottom bar and highlight the current
  section reactively.
- Tasks and Settings content is constrained to a max readable width and centered in the remaining area (no
  single column stretched edge-to-edge).

**Expanded width**
- `NavigationRail` replaces the bottom bar (bottom bar absent).
- Articles shows **two panes**: list left, detail right; selecting a list item updates the right pane in place
  with **no** full-screen navigation; a neutral placeholder shows when nothing is selected.
- The two-pane split leaves both panes usably wide (list not squeezed to an unreadable sliver; detail readable).
- Tasks and Settings remain single-pane with the max-width constraint.

**All size classes (cross-cutting a11y — no regression from feature 18)**
- Interactive targets (rail items, rows, checkboxes, buttons) stay **≥ 48 dp**.
- Layout reflows at the **largest font scale** with no clipping, overlap, or horizontal overflow.
- The auth gate never shows a rail or bottom bar.
- Crossing a breakpoint (rotate / resize) preserves the current section and its scroll state.

### Theme & component reuse

- Use the app's theme tokens (`ui/theme/` — `NeverLateExtras`, the type scale, Material 3 color roles) and the
  branded chrome already in place (`brandedTopAppBarColors()`); the `NavigationRail` uses the **same** Material 3
  color roles as the bottom bar — no one-off styling.
- Reuse existing components: `bottomNavItems`, `MessageState` (list-detail empty/placeholder and error states),
  `ArticleList` / `ArticleRow`, `ArticleDetailScreen`'s body, `PullToRefreshBox`,
  `minimumInteractiveComponentSize()`. This mirrors the "extend, don't duplicate" rule the tutorial applies to
  logic.

## Out of Scope

- **Two-pane Tasks and two-pane Settings** — Articles is the list-detail vehicle this feature; Tasks/Settings
  get only the rail + a max-width constraint. (Deferred, tracked in `docs/mockups/README.md`.)
- **Any new product capability** — no new screens, data, endpoints, or user-visible feature; a phone user sees
  no change.
- **Backend, API contract, Room schema/DB version, permissions, or new domain logic** — none change.
- **A tablet-specific visual redesign** (custom tablet spacing/typography scale, tablet mockup, landscape
  photography/hero images, article header images via Coil — lesson 10b).
- **Foldable posture / hinge-aware (`WindowLayoutInfo`) layouts** — only width size classes are used; folding
  posture and hinge avoidance are out of scope.
- **Desktop / Chromebook window-drag resize polish beyond correct reflow** — reflow must be correct, but no
  bespoke desktop affordances.
- **Multi-window / split-screen–specific tuning** beyond what the width size class already yields.

## Dependencies

- **`androidx.compose.material3:material3-window-size-class`** — provides `WindowSizeClass` /
  `calculateWindowSizeClass`. Managed by the existing Compose BOM (`composeBom = 2024.12.01`), so it is added to
  `gradle/libs.versions.toml` **without an explicit version** (BOM-aligned), like the other Compose artifacts.
- **`androidx.compose.material3.adaptive:*` (`adaptive`, `adaptive-layout`, `adaptive-navigation`)** — needed
  **only if** `ListDetailPaneScaffold` is chosen. This artifact group is versioned **independently** of the
  material3 BOM, so it needs an explicit version pinned in the catalog. It **must** be pinned to a release
  compatible with the current Compose BOM and **AGP 8.13.2** — mirroring the feature-13d precedent where Hilt
  and `hilt-navigation-compose` were pinned below their newest releases to stay AGP-8-compatible. If no
  compatible pin exists, take the **manual two-pane `Row`** fallback, which needs **no** new dependency (see
  Risks).
- Prerequisite lessons/features already in place: 02–07 (Compose, Material 3), 13c (Articles + Paging +
  `getArticleById`), 13d (Hilt / `hiltViewModel()`), and 18 (single nav graph, nullable `onBack`, a11y idioms).
- Version catalog and (if the adaptive artifact is added) a documented pin comment are updated in the same
  branch, per the Documentation Update rules.

## Risks

- **`material3.adaptive` vs AGP 8.13.2.** The adaptive artifacts may require a newer Compose/AGP than this
  project pins. *Mitigation:* verify a compatible version at implementation start; if none, use the manual
  two-pane `Row(ArticleList, ArticleDetailScreen)` composition — it teaches the same list-detail concept with
  zero new dependencies. **Open decision to confirm at implementation:** `ListDetailPaneScaffold` (preferred,
  named API) vs manual `Row` (dependency-free fallback).
- **State loss across a breakpoint.** Rotating/resizing recomposes the layout; if not handled, the current
  section, scroll position, or article selection could reset. *Mitigation:* derive selection/nav state from the
  back stack and `rememberSaveable` where local (feature 18 already derives the selected tab from the back
  stack, not a remembered index — reuse that pattern).
- **Accidental guest-mode regression.** The adaptive branch lives inside `MainAppNavHost`; carelessly
  refactoring `AppNavHost` could merge the `Guest`/`LoggedIn` arms and silently break adoption-on-sign-in.
  *Mitigation:* the adaptive layer changes only what's *inside* `MainAppNavHost`; the `when (authState)` arms in
  `AppNavHost` are left exactly as they are (there is an explicit KDoc warning there).
- **Two-pane paging edge cases.** Pull-to-refresh, append spinner, and append-retry must keep working when the
  list is the left pane. *Mitigation:* reuse `ArticlesScreen`'s list content unchanged inside the pane rather
  than re-implementing the Paging UI.
- **Testing large sizes without a tablet.** Per the project's execution policy there may be no tablet
  emulator handy. *Mitigation:* rely on multi-`widthDp` `@Preview`s and, where practical, a resizable emulator;
  the QA step targets previews + the pure size-class branching, and the final on-device tablet check is the
  user's.

---

## Approval

Please review this specification. Approval covers **both behaviour and look** — the *Visual & UX Design*
section (per-size-class visual acceptance criteria) is part of what is signed off. Implementation will not begin
until you explicitly approve. One decision is flagged for confirmation at implementation time: **preferred**
`ListDetailPaneScaffold` vs the **dependency-free** manual two-pane `Row` fallback, gated on AGP-8.13.2
compatibility of the `material3.adaptive` artifacts.
