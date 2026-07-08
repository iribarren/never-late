# Feature 20 (extra) — Branded chrome: top app bars, colored leading-icon chips, branded FAB (cromo de marca)

- **Status:** Approved (2026-07-09). Confirmed decision: bars + FAB use the **saturated `primary` / `onPrimary`** pairing (mockup-faithful, bold); the leading-icon chip uses `secondaryContainer` / `onSecondaryContainer`.
- **Date:** 2026-07-09
- **Type:** UI polish + tutorial feature (**no backend, no API contract change, no DB-version change, no new permission, no new dependency**)
- **Suggested branch:** `feature/branded-chrome`
- **Planned tutorial lesson:** `tutorial/20-cromo-marca.md` (Spanish, numbered after the current latest lesson `tutorial/19-barra-progreso-tareas.md`)
- **Maps to pending concepts:** `docs/conceptos-pendientes.md` §7 (*Recursos y UI* — theming: Material 3 color roles / Material You, and accessibility/contrast).
- **Mockup slices:** three rows in [`docs/mockups/README.md`](../mockups/README.md) — **"Brand-colored top app bars"** (⬜), **"Colored leading-icon chips"** (⬜), and **"Branded FAB styling"** (🟡). This feature closes all three (the three rows currently owned by "20 · cromo de marca *(planned)*").
- **Visual reference:** [`docs/mockups/rediseno-ux-ui.html`](../mockups/rediseno-ux-ui.html) — the `.appbar` (brand-filled top bar), `.leading` (rounded brand-container icon chip on each list row), and `.fab` (branded floating action) elements (direction only — colors/proportions to interpret through the app's real theme tokens, **not** code to copy).

---

## Overview

Feature 16 landed the brand identity — the palette, the type scale, `NeverLateExtras`, and the
Material You toggle — but it deliberately **left the app's chrome uncolored**: the top app bars still
use Material 3's default surface color, list rows have no leading icon, and the FAB uses the default
container. The master mockup ([`rediseno-ux-ui.html`](../mockups/rediseno-ux-ui.html)) shows the
opposite: a **brand-filled top bar** on every screen, a **rounded brand-container icon chip** at the
start of each task/article row, and a **branded FAB**. That gap has been sitting in the tracking
table as the three ⬜/🟡 rows this feature now closes.

Feature 20 is a **pure chrome pass**: it teaches every surface to ask `MaterialTheme.colorScheme` for
the right **color role** and to pass a `*Colors` object to its component, instead of accepting the
defaults. The screens already use `Scaffold` + `TopAppBar` + `FloatingActionButton`, and Articles
rows already use `ListItem` (which has a `leadingContent` slot) — so this is overwhelmingly a matter
of **passing a `colors =` argument and adding a small reusable chip**, not rewriting layouts.

Its second purpose is didactic. It is the tutorial's first dedicated pass over **Material 3 color
roles in depth** (`primary` vs `primaryContainer` vs `onPrimaryContainer`, `secondaryContainer`, …)
and the **"don't hand-paint, pass a colors object"** idiom (`TopAppBarDefaults.topAppBarColors(...)`,
FAB/`NavigationBar` colors). It reuses feature 15's icon+title "chip" language (`SettingsSectionCard`)
and extracts it into a reusable `ui/components` component in the same spirit as feature 17's
`MessageState`. Crucially, because everything is expressed as **roles** (not hex), the result works
in light, dark, **and** Material You (dynamicColor) with no extra code — that "for free" property is
the core lesson.

The surface area is deliberately small and low-risk: it extends existing `Scaffold`/`TopAppBar`/FAB
call sites and adds one new stateless component. No state shape, navigation, data, or contract
changes.

---

## Goals

Success looks like:

- Every main screen's **top app bar is filled with a brand color role** (a `primaryContainer` /
  `onPrimaryContainer` pairing) instead of the default surface, so the app reads as *branded* the
  moment it opens.
- **Task rows and article rows show a rounded leading-icon chip** in a brand-container role with its
  paired on-container content color — the same visual language as feature 15's `SettingsSectionCard`
  header, extracted into a reusable component.
- The **FAB is branded** (a coherent container color + on-container icon tint) so it belongs to the
  same chrome as the bars and chips rather than standing apart.
- All of the above is **coherent in light, dark, and Material You (dynamicColor)** because every color
  is asked for as a **role**, never hardcoded.
- **Color is never the sole carrier of meaning** (feature 18's accessibility pass continued): every
  `container` role is paired with its `on-container` for legible contrast, and no state that used to
  be told in text/urgency stops being told that way.
- Everything is implemented by **passing `*Colors`/`*Defaults` objects** to existing components and
  adding **one** reusable chip component — **no** new colors are defined, and **no** change to any
  `UiState`, ViewModel `StateFlow`, or the navigation graph.
- The tutorial gains `tutorial/20-cromo-marca.md` (Spanish), teaching the concepts below.
- The three mockup tracking rows move to ✅ in the Design review step.

Non-goals for "success": no new screen, no new stored data, no new colors, no restyling of controls
beyond the three named elements (see Out of Scope), and no measurable performance target (this is
static styling).

---

## User Stories

### US-1 — Brand-colored top app bars
**As a** user opening any main screen,
**I want** the top bar to be filled with the app's brand color,
**so that** the app feels like a designed product with a clear identity, not a default template.

**Acceptance criteria**
- The `TopAppBar` on **Tasks**, **Articles**, and **Settings** receives a
  `colors = TopAppBarDefaults.topAppBarColors(...)` argument instead of relying on the default surface
  colors.
- The bar's `containerColor` is a **brand container role** (`MaterialTheme.colorScheme.primaryContainer`)
  and its `titleContentColor` / `navigationIconContentColor` / `actionIconContentColor` are the
  **paired** `onPrimaryContainer`, so title and back arrow stay legible on the filled bar.
- The **same colored treatment** is applied to the secondary screens that have their own top bar
  (**Article Detail**, **Task Edit**, **Login/Register-from-Settings**), so chrome is consistent
  whether a screen is a tab or a pushed destination — this is done by reusing a **single shared
  colors definition**, not by copy-pasting the arguments per screen (see Technical Approach).
- The treatment is coherent in **light, dark, and Material You** (dynamicColor on): because the colors
  are roles, the correct tone is selected automatically in each theme — no per-theme branch is written.

### US-2 — Colored leading-icon chip on list rows
**As a** user scanning my task list or the articles list,
**I want** each row to start with a small colored icon chip,
**so that** rows have a clear visual anchor and the lists match the rest of the branded UI (as in the
mockup).

**Acceptance criteria**
- A **reusable** leading-icon chip composable exists in `ui/components` (proposed `BrandIconChip`): a
  rounded-corner container (~40dp) painted with a **brand-container role**
  (`secondaryContainer`) and its **paired** `onSecondaryContainer` content color, holding a
  centered `Icon`.
- Each **task row** (`TaskRow` in `TasksScreen.kt`) and each **article row** (`ArticleRow` in
  `ArticlesScreen.kt`) renders this chip as its **leading** element, echoing the mockup's `.leading`.
- The chip's `Icon` uses `contentDescription = null` when it merely **decorates** a row that already
  has a text label (the row's title carries the meaning) — the same rule feature 15's
  `SettingsSectionCard` header applies to its own icon.
- Article rows keep using `ListItem` and supply the chip via its existing `leadingContent` slot; task
  rows integrate the chip into their existing `Card`/`Row` layout without disturbing the countdown,
  action buttons, or feature 19's progress bar.
- Container + on-container pairing keeps the icon legible in **light, dark, and Material You**.

### US-3 — Branded FAB
**As a** user,
**I want** the "new task" FAB to share the app's brand color,
**so that** the primary action reads as part of one coherent chrome instead of a stray default button.

**Acceptance criteria**
- The Tasks `FloatingActionButton` receives an explicit **branded container color + paired content
  color** via its `containerColor` / `contentColor` parameters (a brand role such as
  `primaryContainer` / `onPrimaryContainer`, or `primary` / `onPrimary` — the exact pairing fixed in
  the Visual & UX Design section), rather than the Material 3 default.
- The FAB's **elevation** stays coherent with the rest of the chrome (Material 3 FAB default elevation
  is acceptable; no custom shadow is invented — see Deferred visual polish).
- The FAB is legible and coherent in **light, dark, and Material You**.
- The FAB's icon keeps a meaningful `contentDescription` (it is an **interactive control**, not
  decoration) — the existing `tasks_add_content_description` is preserved.

### US-4 — Accessible: contrast and color-never-alone (feature 18 continuation)
**As a** user with low vision or who does not perceive color well,
**I want** the new branded surfaces to stay legible and to never depend on color to convey state,
**so that** the visual refresh does not cost accessibility.

**Acceptance criteria**
- **Every `container` role is paired with its `on-container`** content color everywhere it is
  introduced (bars, chips, FAB) — no branded surface is left with a mismatched or default content
  color.
- **Color is never the sole carrier of meaning:** the chip is decorative (the row's title text carries
  meaning); the top bar's title is text; feature 17's urgency color and feature 19's overdue text are
  untouched and still carry the urgency signal. Nothing that was previously conveyed in text/shape is
  demoted to color-only by this feature.
- Text on the new branded surfaces stays legible in **both light and dark** themes (verified in the
  Design review step — Material 3's role tones are designed to meet contrast, but the pairing must be
  actually used, and dynamicColor combinations spot-checked).
- Interactive targets are unaffected (the FAB and back arrows keep their existing ≥48dp sizing; the
  chip is non-interactive decoration, so the ≥48dp target rule does not apply to it).

### US-5 — Tutorial lesson (mandatory per Tutorial Methodology)
**As a** learner following the tutorial,
**I want** a Spanish lesson explaining Material 3 color roles, coloring components via their `*Colors`
objects, extracting a reusable brand component, and color contrast/accessibility,
**so that** the codebase stays a coherent progressive tutorial.

**Acceptance criteria**
- `tutorial/20-cromo-marca.md` exists, in Spanish, numbered after lesson 19.
- It teaches the concepts in "New concepts taught" below, walking through the code actually written,
  and references the prior lessons it builds on: **16** (brand identity: palette, type scale,
  `NeverLateExtras`, dynamicColor), **15** (`SettingsSectionCard`'s icon+title chip language), and
  **17** (extracting a reusable component into `ui/components`, `MessageState`), per methodology.

---

## Acceptance Criteria (behavioural, consolidated)

1. Tasks, Articles, and Settings top bars are filled with `primaryContainer` and use
   `onPrimaryContainer` for title/nav/action content, via `TopAppBarDefaults.topAppBarColors(...)`.
2. Article Detail, Task Edit, and Login/Register-from-Settings top bars use the **same** shared
   branded colors (one definition, reused — not copy-pasted per screen).
3. A reusable `BrandIconChip` composable lives in `ui/components`, uses a `secondaryContainer` /
   `onSecondaryContainer` pairing, and is rendered as the leading element of both task rows and
   article rows.
4. The chip's `Icon` uses `contentDescription = null` (decorative, per feature 15's rule).
5. The Tasks FAB uses an explicit branded container + paired content color.
6. **No new color values** are added to `Color.kt`/`Theme.kt`; every color is an existing
   `MaterialTheme.colorScheme` role (or `NeverLateExtras`, unchanged).
7. Every `container` role introduced is paired with its `on-container`; nothing becomes color-only.
8. Light, dark, and Material You (dynamicColor) all remain coherent and legible.
9. No change to any `UiState`, ViewModel `StateFlow`, the navigation graph, or feature 17/19's urgency
   color + progress bar.

---

## Visual & UX Design

### Mockup slices
This feature implements **three** slices from [`docs/mockups/README.md`](../mockups/README.md), all
currently owned by "20 · cromo de marca *(planned)*":

- **"Brand-colored top app bars"** (⬜) — the mockup's `.appbar`: a solid brand-filled bar
  (`background: var(--brand); color: var(--brand-ink)`) with the title and leading icon on top.
- **"Colored leading-icon chips"** (⬜) — the mockup's `.leading`: a ~40dp rounded chip
  (`background: var(--brand-container); color: var(--on-container)`) holding an icon at the start of
  each task/article row.
- **"Branded FAB styling"** (🟡) — the mockup's `.fab`: a brand-colored floating action
  (`background: var(--brand); color: var(--brand-ink)`).

The mockup's literal CSS hex values (`--brand`, `--brand-container`, `--on-container`, `--brand-ink`)
are **illustrative direction only**. Translate the *intent* into Compose using the app's **real**
theme (`ui/theme/` — the Material 3 role tokens on `MaterialTheme.colorScheme`), **not** by copying
its HTML/CSS. The role mapping below is how the mockup's four brand variables become real roles.

### Role mapping (the core design decision)

| Mockup token | Compose role (container) | Paired content role | Where |
|---|---|---|---|
| `--brand` + `--brand-ink` (filled bar) | `colorScheme.primary` | `colorScheme.onPrimary` | Top app bars (all screens) |
| `--brand-container` + `--on-container` (icon chip) | `colorScheme.secondaryContainer` | `colorScheme.onSecondaryContainer` | Leading-icon chip on list rows |
| `--brand` + `--brand-ink` (FAB) | `colorScheme.primary` | `colorScheme.onPrimary` | Tasks FAB |

Rationale: the mockup uses the **saturated brand** for the bar/FAB and a **lighter brand tint** for
the chip. **Confirmed decision:** the bar/FAB map to the fully-saturated `primary` / `onPrimary` — the
mockup-faithful, bold reading of the brand; the chip's lighter tint maps to `secondaryContainer` /
`onSecondaryContainer`, distinguishing it from the bar so the two branded surfaces don't merge into
one block of color. Contrast is guaranteed by always pairing `primary` with `onPrimary` (and
`secondaryContainer` with `onSecondaryContainer`); the Design review spot-checks legibility in light,
dark, and Material You.

### Deferred visual polish
- **Task-card time-elapsed progress bar** is already shipped (feature 19) and is **not** re-touched
  here beyond leaving room for the new leading chip in the row layout.
- **Bottom `NavigationBar` recoloring** is **out of scope**: Material 3's default `NavigationBar`
  already uses `secondaryContainer` for the selected indicator on a `surface` bar, which is already
  coherent with this palette. If a future feature wants a fully brand-filled nav bar, that is a new
  pending row — deferring is fine, deferring *silently* is not.
- **FAB custom elevation/shadow**: ship Material 3's default FAB elevation; the mockup's soft colored
  shadow is not pixel-matched. Any residual gap is recorded as pending, not chased here.
- **Onboarding screen chrome**: only touched if it already has a `TopAppBar` on the branded path;
  otherwise left as-is (it is a first-run, full-bleed screen) and noted.

### Visual acceptance criteria (verified in the Design review step)
- **Top bars branded, paired:** all app bars use `primaryContainer` container + `onPrimaryContainer`
  content via `TopAppBarDefaults.topAppBarColors(...)`. Title and back arrow legible on the fill.
- **One shared bar-colors definition:** the same `topAppBarColors` value is reused across every screen
  (extend, don't duplicate) — no per-screen copy of the color arguments.
- **Leading chip present + branded:** task and article rows start with a rounded `secondaryContainer` /
  `onSecondaryContainer` icon chip (~40dp), decorative (`contentDescription = null`).
- **FAB branded:** the Tasks FAB uses the confirmed brand container + paired content color, default
  elevation, meaningful icon `contentDescription` kept.
- **No new colors:** nothing added to `Color.kt`/`Theme.kt`; only existing roles used.
- **Theme-safe incl. Material You:** all three elements coherent and legible in light, dark, and
  dynamicColor-on — spot-checked in the Design review.
- **Contrast pairing everywhere:** every `container` role goes with its `on-container`; no branded
  surface left with a default/mismatched content color.
- **Color never sole meaning:** chip is decorative; urgency (feature 17) and overdue text
  (feature 19) unchanged.

---

## Technical Approach

High-level strategy — **pass `*Colors` objects to existing components; add one reusable chip.** All
work is in the app UI layer (`app/`); nothing in domain/data/backend moves.

### 1. Shared branded top-bar colors
- Define the branded `TopAppBarColors` **once** and reuse it on every screen, rather than repeating
  `TopAppBarDefaults.topAppBarColors(...)` at each call site. Proposed home: a small
  `ui/components/AppBarDefaults.kt` (or a `@Composable`/`@Composable get()` helper next to the theme),
  exposing e.g. `brandedTopAppBarColors(): TopAppBarColors` reading `MaterialTheme.colorScheme`
  (`containerColor = primaryContainer`, `titleContentColor` / `navigationIconContentColor` /
  `actionIconContentColor = onPrimaryContainer`).
- Apply it as `colors = brandedTopAppBarColors()` on the `TopAppBar` in `TasksScreen.kt`,
  `ArticlesScreen.kt`, `SettingsScreen.kt`, plus the secondary screens with their own bar
  (Article Detail, Task Edit, Login/Register). Because it reads roles, no light/dark/dynamicColor
  branching is needed.

### 2. Reusable leading-icon chip (`ui/components`)
- Add a stateless `BrandIconChip` composable — proposed `ui/components/BrandIconChip.kt`, in the same
  spirit as `MessageState` (feature 17): a `Box`/`Surface` with a rounded shape (~40dp,
  `RoundedCornerShape`), `color = MaterialTheme.colorScheme.secondaryContainer`,
  `contentColor = onSecondaryContainer`, centering an `Icon`. Parameters: the `ImageVector` and an
  optional `contentDescription` defaulting to `null` (decorative-by-default, since it usually
  accompanies a text label — feature 15's rule).
- **Article rows** (`ArticleRow`): pass `BrandIconChip(...)` into `ListItem`'s existing
  `leadingContent` slot (a menu-book / article icon).
- **Task rows** (`TaskRow`): add the chip as the leading element of the existing title `Row`/`Column`,
  keeping the countdown, play/pause + delete `IconButton`s, and feature 19's `LinearProgressIndicator`
  exactly where they are (an assignment/checklist icon, matching the mockup's check glyph).

### 3. Branded FAB
- On the Tasks `FloatingActionButton`, set `containerColor` + `contentColor` to the confirmed brand
  pairing (recommended `primaryContainer` / `onPrimaryContainer`, to match the bar). Keep default
  elevation and the existing icon + `contentDescription`.

### 4. Strings & theme
- **No new colors.** No new string is strictly required (the chip is decorative). If any new
  copy is added (e.g. a chip `contentDescription` for a case where it is *not* redundant), it goes in
  **both** `res/values/strings.xml` (Spanish base) and `res/values-en/strings.xml` (feature 08).
- `Color.kt` / `Theme.kt` are **read**, not changed: this feature only consumes existing roles.

### Files in scope
- **New:** `app/src/main/java/com/neverlate/ui/components/BrandIconChip.kt` — the reusable chip.
- **New (or a small helper):** `app/src/main/java/com/neverlate/ui/components/AppBarDefaults.kt` —
  the single shared `brandedTopAppBarColors()`.
- `app/src/main/java/com/neverlate/ui/tasks/TasksScreen.kt` — bar colors, FAB colors, chip in `TaskRow`.
- `app/src/main/java/com/neverlate/ui/articles/ArticlesScreen.kt` — bar colors, chip in `ArticleRow`.
- `app/src/main/java/com/neverlate/ui/settings/SettingsScreen.kt` — bar colors.
- The detail/edit/auth screens' `TopAppBar`s (Article Detail, Task Edit, Login, Register) — bar colors.
- `app/src/main/res/values/strings.xml` + `app/src/main/res/values-en/strings.xml` — only if any new
  copy is introduced.
- **New:** `tutorial/20-cromo-marca.md` (Spanish lesson).
- **Design review:** update `docs/mockups/README.md` (move the three slice rows to ✅).

---

## New concepts taught (tutorial mandate)

To be developed in `tutorial/20-cromo-marca.md` (Spanish); listed here so the spec captures the
didactic intent, per the Tutorial Methodology in `CLAUDE.md`:

- **Material 3 color roles in depth.** `primary` vs `primaryContainer` vs `onPrimaryContainer`,
  `secondaryContainer`, `surface`… — asking `MaterialTheme.colorScheme` for a *role* rather than a
  fixed color, why roles come in `container` + `on-container` pairs, and why this is what makes
  light/dark **and** Material You "just work" (tying back to feature 16's palette/dynamicColor).
- **Coloring a component via its `*Colors`/`*Defaults` object.** The "don't hand-paint, pass a colors
  object" idiom: `TopAppBarDefaults.topAppBarColors(...)`, `FloatingActionButton`'s
  `containerColor`/`contentColor`, and (as context) `NavigationBar`/`NavigationBarItem` colors — and
  why passing a colors object beats setting a background modifier by hand.
- **Extracting a reusable brand component into `ui/components`.** Building `BrandIconChip` in the same
  spirit as feature 17's `MessageState` and feature 15's `SettingsSectionCard` header, with a
  decorative `contentDescription = null` default, and sharing one `brandedTopAppBarColors()` instead
  of repeating it per screen (extend, don't duplicate).
- **Color contrast & accessibility.** Why a `container` always travels with its `on-container`, how to
  reason about legibility in both themes, and why color must never be the sole signal (linking back to
  feature 18's accessibility pass and feature 17's text-plus-color urgency).

---

## Out of Scope

- Any **backend, API contract, DB schema/version, permission, module, or dependency** change — every
  API used (`TopAppBarDefaults`, `FloatingActionButton` colors, `Surface`/`RoundedCornerShape`) is in
  the Compose BOM already on the classpath.
- Adding or changing **any color value** in `Color.kt`/`Theme.kt` — this feature only *consumes*
  existing roles. `NeverLateExtras` (calm/soon) is untouched.
- Restyling controls **beyond** the three named elements (top bars, list-row leading chips, FAB):
  no recoloring of buttons, switches, radios, cards' surfaces, dialogs, text fields, or the pull-to-
  refresh spinner.
- **Bottom `NavigationBar` recoloring** (its default is already coherent — see Deferred visual polish).
- Feature 17's **urgency colors** and feature 19's **progress bar** — left exactly as they are (the
  chip must fit around the bar, not modify it).
- Custom FAB **elevation/shadow** or pixel-matching the mockup's colored shadow (default elevation
  ships; any gap tracked as pending).
- Any new **stored data, state shape, ViewModel `StateFlow`, or navigation** change.
- Per-row **different** icons based on task content/type (the chip icon is a single consistent glyph
  per list; content-derived iconography would be a separate feature).

---

## Dependencies

- **Feature 16 (visual identity)** — the palette, `MaterialTheme.colorScheme` roles, the type scale,
  and the `dynamicColor` toggle this feature builds directly on. Those must remain as they are.
- **Feature 15 (icon sections)** — `SettingsSectionCard`'s icon+title chip language and its decorative
  `contentDescription = null` rule, generalized here into `BrandIconChip`.
- **Feature 17 (states & animations)** — the `ui/components` pattern (`MessageState`) the new chip
  follows; and the urgency color mapping the chip must sit alongside without disturbing.
- **Feature 19 (task progress bar)** — the `TaskRow` layout the chip must integrate with, leaving the
  progress bar intact.
- **Feature 08 (i18n)** — any new copy needs Spanish + English string resources.
- **Feature 18 (bottom nav + accessibility)** — the accessibility pass this feature continues
  (container/on-container pairing, color-never-alone).
- **Compose Material 3 APIs** — already available via the existing Compose BOM; **confirm no
  version-catalog change is needed** (expected: none).
- **Resolution of the `primary` vs `primaryContainer` decision** for the bar/FAB before implementation
  (see Review).

---

## Risks / Open Questions

1. **`primary` vs `primaryContainer` for the bar/FAB — RESOLVED (2026-07-09):** the user chose the
   fully-saturated **`primary` / `onPrimary`** for both the bars and the FAB (mockup-faithful, bold),
   with `secondaryContainer` / `onSecondaryContainer` for the chip. Because `primary` is more
   contrast-sensitive on a full-width bar, the Design review must spot-check title/back-arrow legibility
   in light, dark, and Material You.
2. **Material You (dynamicColor) coherence.** Because everything is a role, dynamicColor should be
   coherent automatically — but the wallpaper-derived palette can pair colors differently than the
   brand palette. Mitigation: spot-check the branded bar/chip/FAB with dynamicColor **on** in both
   light and dark during the Design review; roles guarantee *contrast*, this checks *taste*.
3. **Contrast correctness.** Material 3's role tones are designed to meet contrast, but only if the
   container is actually paired with its on-container. QA/Design review verifies no branded surface is
   left with a default/mismatched content color, in both themes.
4. **Chip fit in an already-dense task row.** `TaskRow` already holds title, duration, deadline,
   countdown, two action buttons, and feature 19's progress bar. Adding a leading chip must not clip or
   crowd at the largest font scale. Design review checks the max font scale in both themes.
5. **Chip semantics.** The chip is decorative (`contentDescription = null`) because the row title
   carries meaning — verify with TalkBack that the chip is not announced as a redundant/no-op element
   and the row still reads cleanly.

---

## Review

Please review this specification and confirm — approval covers **both behaviour and the Visual & UX
Design section**:

- **Risk 1 — the brand role for the bar/FAB:** `primaryContainer` + `onPrimaryContainer` (recommended)
  vs the fully-saturated `primary` + `onPrimary`. This is the one design decision needed before
  implementation. The chip is proposed as `secondaryContainer` + `onSecondaryContainer` regardless.
- That **no new colors** are introduced (only existing `MaterialTheme.colorScheme` roles) — confirm
  that is desired.
- That the **bottom `NavigationBar` is left at its coherent default** (not recolored) in this feature.
- That shipping **default FAB elevation** (rather than pixel-matching the mockup's colored shadow) is
  acceptable, with any residual gap tracked as pending.
- That the leading chip is treated as **decorative** (`contentDescription = null`), the same rule
  feature 15 applies.

Once approved, implementation proceeds on `feature/branded-chrome` per the New Feature Workflow in
`CLAUDE.md` (spec → approval → branch → `mobile-engineer` implementation → `qa-engineer` tests →
Design review + mockup-tracking update → Spanish lesson `tutorial/20-cromo-marca.md` → commit).
