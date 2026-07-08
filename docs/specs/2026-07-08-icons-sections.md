# Feature 15 (extra) — Iconografía y separación clara de secciones

- **Status:** Approved — 2026-07-08
- **Date:** 2026-07-08
- **Suggested branch:** `feature/icons-sections`
- **Scope:** Android app only (`app/`) — **UI polish**. `ui/home/HomeScreen.kt`,
  `ui/settings/SettingsScreen.kt`, string resources, and (if needed) `gradle/libs.versions.toml`.
- **Visual reference:** `docs/mockups/rediseno-ux-ui.html` (direction only — not code to copy).
- **Tutorial lesson:** `tutorial/15-iconos-secciones.md` (Spanish, numbered after lesson 14) —
  required before committing.

---

## Overview

This is a **UI-only polish** feature. The Home hub and the Settings screen currently render as flat
lists of text: the two Home options (Tareas / Artículos) are bare `ListItem`s showing only a label
(`headlineContent`), and the Settings screen stacks its three groups (Tema, Recordatorios, Cuenta)
as plain `Text` section titles separated only by top padding. There is no visual anchor telling the
eye where one section ends and the next begins, and nothing hints at what each option does before
tapping it.

Feature 15 gives each surface a **clear visual hierarchy** so every section is recognizable at a
glance:

1. **Home hub — icons + one-line descriptions.** Each Home option row gains a leading icon
   (`leadingContent`) and a short describing line (`supportingContent`), turning the bare rows into
   rich, self-explanatory list items. The Settings entry point keeps its existing top-bar icon.
2. **Settings — grouped cards with icon headers.** The three existing blocks (Tema, Recordatorios,
   Cuenta) are each wrapped in a Material 3 `Card` with an icon + title header, and internal
   separation uses `HorizontalDivider` instead of a lone `Text` leaning on padding. The existing
   blocks and their `SelectableRadioRow` are **reused as-is** — wrapped, not rewritten.

There is **no backend, API contract, DB-version, permission, or dependency change** (see the
Dependencies note about `material-icons-extended`, which is already on the classpath). All new
user-facing text lives in string resources, Spanish base + English variant.

This is also a **tutorial feature** (see Tutorial Methodology in `CLAUDE.md`): lesson
`tutorial/15-iconos-secciones.md` is a required deliverable.

## Goals

- Make each section on Home and in Settings **recognizable at a glance** via icons and grouping.
- Turn the two Home option rows into **rich `ListItem`s** (`leadingContent` icon + `supportingContent`
  description) without changing navigation behaviour.
- Group the three Settings blocks into Material 3 `Card`s with icon headers, replacing padding-only
  separation with intentional `HorizontalDivider`/spacing.
- **Extend, don't duplicate:** reuse the existing `HomeOptionCard` `ListItem` and the existing
  Settings blocks/`SelectableRadioRow`; wrap them, don't rewrite them.
- Keep every user-facing string in resources (Spanish base + English), including new supporting
  descriptions and card headers.
- Teach new Compose/Material 3 concepts (the full `ListItem`, icon semantics /
  `contentDescription`, `Card` vs `Surface` + `HorizontalDivider` grouping, and the version-catalog
  dependency workflow) in the Spanish lesson.

## User Stories

### US-1 — Home options are self-explanatory at a glance
**As a** person with ADHD opening the app,
**I want** each Home option to show an icon and a short description,
**so that** I understand what Tareas and Artículos do without tapping them.

**Acceptance Criteria**
- The Tareas row shows a leading icon (`leadingContent`), its existing label (`headlineContent`), and
  a new one-line description (`supportingContent`).
- The Artículos row shows the same three parts (leading icon, label, description).
- The leading icons come from the Material icon set and are wired through the existing
  `HomeOptionCard`/`ListItem` — the row is **extended**, not replaced.
- Tapping a row still navigates exactly as today (Tareas → tasks, Artículos → articles); no
  navigation behaviour changes.
- The Settings entry point (top-bar action) still shows its icon and remains reachable; its existing
  `contentDescription` is preserved.

### US-2 — Settings sections are visually grouped
**As a** user on the Settings screen,
**I want** Tema, Recordatorios and Cuenta each shown as a distinct grouped card with an icon header,
**so that** I can immediately tell the three areas apart and find the one I want.

**Acceptance Criteria**
- Each of the three sections (Tema, Recordatorios, Cuenta) is wrapped in its own Material 3 `Card`.
- Each card has a header combining an icon and the section title (the existing
  `settings_theme_section` / `settings_reminders_section` / `settings_account_section` strings).
- Within a card, sub-blocks are separated with `HorizontalDivider` (and/or deliberate spacing) rather
  than a bare `Text` relying on top padding.
- The existing controls inside each block are reused unchanged: the theme radio group, the reminders
  on/off `Switch` + lead-time radio list + `ExactAlarmPermissionNotice`, and the account
  `TextButton` (Log out / Sign in). `SelectableRadioRow` is **not** rewritten.
- All existing behaviour is preserved: theme selection, reminders toggle and lead-time selection, the
  exact-alarm notice on API 31+, and the auth-state-dependent account action (Log out when
  `LoggedIn`, Sign in otherwise).
- The screen remains fully scrollable (the existing `verticalScroll`), so a tall Recordatorios card
  never pushes the Cuenta card out of reach.

### US-3 — Icons are correctly labelled for accessibility
**As a** user relying on TalkBack,
**I want** icons announced only when they carry meaning and skipped when they are purely decorative,
**so that** the screen reader is informative, not noisy.

**Acceptance Criteria**
- Icons that convey information a sighted user gets from the icon alone have a `contentDescription`
  string resource.
- Icons that merely decorate a row/header whose meaning is already in adjacent text use
  `contentDescription = null` (explicitly decorative).
- The decision (described vs `null`) is applied consistently and explained in the tutorial lesson.

### US-4 — Text stays localized
**As a** user in Spanish or English,
**I want** the new descriptions and card headers shown in my language,
**so that** the polish doesn't introduce hardcoded or untranslated strings.

**Acceptance Criteria**
- Every new user-facing string (Home option descriptions, any icon `contentDescription`s, card
  headers if a new string is needed) is defined in `res/values/strings.xml` (Spanish base) **and**
  `res/values-en/strings.xml` (English).
- No literal user-facing text is introduced in Kotlin; all text is read via `stringResource(...)`.

## Technical Approach

High-level only — no code here.

### Where it changes
- **`ui/home/HomeScreen.kt`** — extend `HomeOptionCard` (and the `HomeOption` data model that feeds
  it) so each option carries an icon and a supporting description, rendered through the existing
  `ListItem` as `leadingContent` + `headlineContent` + `supportingContent`. The `Scaffold`,
  navigation callbacks, and the Settings top-bar `IconButton` are unchanged.
- **`ui/settings/SettingsScreen.kt`** — introduce a small reusable section-card wrapper (a `Card`
  with an icon+title header) and move each of the three existing blocks inside one, replacing the
  three plain `Text` section titles with card headers and swapping padding-only gaps for
  `HorizontalDivider`/spacing. The block internals (`themeOptions` radio group,
  `reminderLeadMinuteOptions` radio list, `ExactAlarmPermissionNotice`, the account `TextButton`) and
  `SelectableRadioRow` are lifted in **verbatim**.
- **String resources** — new `supportingContent` descriptions for the two Home options, plus any new
  icon `contentDescription`s. Reuse the existing section-title strings for the card headers; add a new
  string only where none exists. Both `values/` and `values-en/`.
- **`gradle/libs.versions.toml`** — see Dependencies: the `material-icons-extended` entry and the app
  dependency **already exist**; this feature adds a version-catalog entry **only if** a genuinely new
  library is required (it is not expected to be).

### Reuse contract (extend, don't duplicate)
- Home rows keep going through the single `HomeOptionCard` composable; it grows parameters (icon,
  description) rather than being duplicated per option.
- Settings blocks and `SelectableRadioRow` are wrapped, not rewritten — the diff should read as
  "same controls, now inside cards with headers and dividers".

### Icon semantics
- Choose per US-3: a leading icon that stands in for the row/section meaning gets a
  `contentDescription`; an icon that only decorates a header whose title text is already read gets
  `contentDescription = null`. Document the reasoning in the lesson.

### Visual direction
- `docs/mockups/rediseno-ux-ui.html` is directional only. Match the *intent* (clear grouping, icon
  anchors, calm separation) using standard Material 3 components and the app's theme; do not port
  markup or hardcode colors — respect light/dark via `NeverLateTheme`.

## Out of Scope

- Any backend, `docs/api/contract.md`, DB schema/version, permission, or new-dependency change (the
  icon library is already present).
- Changing navigation, the set of Home options, or any Settings control's **behaviour** (theme,
  reminders, exact-alarm notice, logout/sign-in logic all stay as-is).
- Turning the Settings top-bar action into a Home hub row, or otherwise restructuring navigation.
- Adding new settings, new Home destinations, or new content — this is purely visual grouping and
  labelling of what already exists.
- The broader UX/visual refresh implied by the full mockup (typography scale, color system, spacing
  tokens beyond what these two screens need).
- Reworking other screens (task list, task edit, articles, onboarding, login) — only Home and
  Settings are touched.
- Animation/motion polish beyond default Material 3 component behaviour.

## Dependencies

- **`androidx.compose.material:material-icons-extended`** — **already declared** in
  `gradle/libs.versions.toml` (alias `androidx-material-icons-extended`) **and already wired** into
  `app/build.gradle.kts` (`implementation(libs.androidx.material.icons.extended)`), added back in
  feature 05 for `Icons.Filled.Pause` and already used by feature 14 (`Icons.Filled.CalendarMonth`,
  `Icons.Filled.Clear`). **No catalog change is required for icons.** The tutorial still *teaches* the
  version-catalog dependency workflow using this existing entry (why the bundled
  `material-icons-core` set isn't enough, and why the version comes from the Compose BOM rather than a
  hardcoded number) — but the implementer must **not** add a duplicate entry.
- **Material 3 Compose** (`androidx.compose.material3`, already on the classpath) — provides
  `ListItem` (with `leadingContent`/`supportingContent`), `Card`, and `HorizontalDivider`.
- **Existing screens/components** — `HomeScreen.kt`'s `HomeOptionCard`/`HomeOption`,
  `SettingsScreen.kt`'s three blocks + `SelectableRadioRow` + `ExactAlarmPermissionNotice`, and the
  existing section-title / option-label string resources.
- **Process / project:** new branch `feature/icons-sections` off `master` (never commit to `master`);
  mandatory Spanish lesson `tutorial/15-iconos-secciones.md` before committing; run `/doc-check`
  before the commit.

## Risks

- **False "new dependency" step.** The prompt frames adding `material-icons-extended` to the catalog
  as a step, but it is already present and wired. *Mitigation:* the implementer confirms the existing
  entry and adds nothing; the lesson teaches the catalog workflow against the existing entry. Adding a
  duplicate alias would break the build.
- **Layout regression on the scrollable Settings screen.** Wrapping three blocks in cards adds
  padding/elevation; a tall Recordatorios card (lead-time list + exact-alarm notice) must still let
  the Cuenta card scroll into view. *Mitigation:* keep the existing `verticalScroll`, verify on device
  with reminders on and API 31+ notice showing.
- **Accessibility inconsistency.** Mislabelling decorative icons (or leaving meaningful ones
  unlabelled) makes TalkBack noisy or uninformative. *Mitigation:* apply the US-3 rule uniformly and
  document it in the lesson.
- **Compose Preview drift.** `HomeScreenPreview` / `SettingsScreenPreview` must keep compiling and
  should visibly reflect the new icons/cards so the previews stay truthful.
- **Over-styling vs. the theme.** Hardcoding colors/elevation from the mockup would break light/dark.
  *Mitigation:* use Material 3 defaults and `NeverLateTheme` tokens; `tonalElevation`/spacing over
  custom colors.

## New Concepts Taught (tutorial lesson 15 — `tutorial/15-iconos-secciones.md`, Spanish)

Building on lesson 02 (Home hub, `ListItem`, `Scaffold`) and lesson 07 (Settings screen, radio
groups, section layout):

1. **The full `ListItem`** — `leadingContent`, `headlineContent`, `supportingContent`, and
   `trailingContent` as the building blocks of a rich row, and how the existing Home row grows from a
   headline-only item into a described, icon-led one.
2. **Adding a dependency via the version catalog** — why the bundled `material-icons-core` set isn't
   enough for the broader icon library, how `material-icons-extended` is declared in
   `gradle/libs.versions.toml` and referenced as `libs.androidx.material.icons.extended` (never a
   hardcoded version — the Compose BOM pins it). Taught against the **already-present** entry, noting
   it was introduced earlier (feature 05) so no new line is added here.
3. **Icon semantics** — when an `Icon` needs a `contentDescription` (it conveys meaning) versus when
   it is purely decorative (`contentDescription = null`), and why that distinction matters for
   TalkBack.
4. **Grouping surfaces in Material 3** — `Card` vs `Surface`, `HorizontalDivider`, and using
   spacing / `tonalElevation` to separate sections without clutter; wrapping existing blocks in cards
   instead of rewriting them (extend-don't-duplicate).

---

## Approval

Please review this spec and confirm the scope, user stories, and acceptance criteria. Per the
Mandatory Workflow, implementation must not begin until you explicitly approve. Open points to
confirm at approval:

1. **Home icons** — is it acceptable to pick sensible Material icons for Tareas (e.g. a
   checklist/task icon) and Artículos (e.g. an article/menu-book icon), decided during
   implementation, or do you want specific icons named in the spec first?
2. **Card header strings** — reuse the existing section-title strings as the card headers (no new
   strings for titles), adding new strings only for the Home option descriptions and any icon
   `contentDescription`s. Confirm that's the intended split.
3. **`material-icons-extended`** — confirmed already present; this feature adds **no** catalog entry
   and the lesson teaches the workflow against the existing one. Confirm you're happy with that
   framing rather than a (redundant) fresh add.
