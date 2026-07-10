# Design mockups — tracking

The master mockup [`rediseno-ux-ui.html`](rediseno-ux-ui.html) is the app's **visual north star**
(see *Design in the Workflow* in the root `CLAUDE.md`). It shows the aspirational look across many
features at once; no single feature builds all of it. This table tracks **which slices are shipped
vs. pending**, so the gap between the mockup and the app is always visible and owned instead of
silently accumulating.

Update this table in the **Design review** step of every feature that touches UI.

**Legend:** ✅ done · 🟡 partial · ⬜ pending · — not a mockup slice (net-new UI, tracked for context)

## Slice status

| Mockup element / screen | Owning feature | Status | Notes |
|---|---|---|---|
| Native date/time picker (replaces free-text deadline) | 14 · selector de fecha | ✅ | Shipped as the platform picker. |
| Settings as icon+title cards (theme / reminders / account) | 15 · iconos secciones | ✅ | `SettingsSectionCard` (icon + title + divider). |
| Brand palette + type scale + Material You toggle | 16 · identidad visual | ✅ | `ui/theme/` (`NeverLateExtras`, type scale, dynamic-color switch). |
| Urgency-colored countdown (calm / soon / late) | 17 · estados y animaciones | ✅ | Color-only cue via `urgencyLevelFor` + `NeverLateExtras`. |
| Reusable empty/error states + list animations | 17 · estados y animaciones | ✅ | `MessageState`, `Modifier.animateItem()`. |
| Bottom navigation bar (Tasks / Articles / Settings) | 18 · navegación y accesibilidad | ✅ | `MainBottomBar`, route-gated visibility. |
| Accessibility pass (content descriptions, ≥48dp, dynamic font) | 18 · navegación y accesibilidad | 🟡 | Bar + `MessageState` covered; feature 20 continued it (container/on-container pairing, decorative chip); ongoing per screen. |
| Task-card time-elapsed progress bar | 19 · barra de progreso | ✅ | Determinate `LinearProgressIndicator` per `TaskRow`, colored by `colorForUrgency` (reuses 17), `animateFloatAsState`; fraction from pure `deadlineProgressFor` over `estimatedDurationMillis` (no bar when absent). |
| Brand-colored top app bars | 20 · cromo de marca | ✅ | Shared `brandedTopAppBarColors()` (`primary` container + `onPrimary` content) on every `TopAppBar` (Tasks/Articles/Settings + Article Detail/Task Edit/Login/Register/Onboarding). Role-based → light/dark/Material You for free. |
| Colored leading-icon chips on task/list rows | 20 · cromo de marca | ✅ | `ui/components/BrandIconChip` (~40dp rounded `secondaryContainer`/`onSecondaryContainer`, decorative `contentDescription = null`) as the leading element of task and article rows. |
| Tasks filter / sort / group controls | 03b · filtro y orden | — | **Not in the master mockup** (which has no search/filter/sort screen); net-new UI. Reuses existing primitives — `OutlinedTextField` + `FilterChip`s in a reflowing `FlowRow`, ≥48dp touch targets via `minimumInteractiveComponentSize()`, `NoResults` via `ui/components/MessageState`. No mockup slice claimed. **Feature 04b** reworked only the *behaviour* of this same search field (reactive `debounce`/`combine`/`stateIn` pipeline) and added a decorative clear (✕) `trailingIcon` with a localized `contentDescription`; no new visible chrome, still no mockup slice claimed. |
| Branded FAB styling | 20 · cromo de marca | ✅ | Tasks FAB uses `primary`/`onPrimary` (default elevation; mockup's colored shadow not pixel-matched — acceptable per spec). |
| Weekly statistics screen (completed / on-time % / due soon) | 04c · testing y estadísticas | — | **Not in the master mockup** (no stats screen); net-new surface. Three stat `Card`s reusing theme tokens (`headlineMedium` number + `bodyMedium` label + `BrandIconChip`), branded `TopAppBar`, `MessageState` empty state, "—" when on-time % is undefined. Reached via a stats `IconButton` in the Tasks top app bar (secondary route, bottom bar hidden). No mockup slice claimed. |
| Task-card mark-done affordance (checkbox + strikethrough) | 04c · testing y estadísticas | — | Addition to the existing task-card slice, not a mockup element. Per-row `Checkbox` (≥48dp, `contentDescription`); completed rows show a strikethrough title via `onSurfaceVariant`, sort after pending, and drop the countdown/progress bar/urgency color. Reuses the card's tokens (no restyle). |
| Articles pagination load states (append spinner / inline retry) | 13c · paginación | — | **Not a mockup element** (the mockup has no load-more/paging UI); mechanics change under the already-✅ Articles list slice — the rows are **not** restyled (Card + `ListItem` + `BrandIconChip` unchanged). Net-new: a bottom append `CircularProgressIndicator` and an inline append-error **Retry** row (`minimumInteractiveComponentSize()`, ≥48dp). Reuses feature 17's `MessageState` for the full-screen empty/refresh-error states and `PullToRefreshBox` for refresh. Deferred (stated in spec): skeleton/shimmer placeholders and article header images (Coil, lesson 10b). No mockup slice claimed. |
| Task priority selector + list indicator | 13b · migraciones de Room | — | **Not in the master mockup** (which has no priority element); net-new UI. Edit screen: single-select `FilterChip`s in a reflowing `FlowRow` (reuses 03b's pattern, ≥48dp via `minimumInteractiveComponentSize()`). Task card: a small token-colored dot (`primary`/`tertiary`/`secondary` per level, `PriorityUi.indicatorColor`) leading the title for non-`NONE` priorities, suppressed on completed rows (done styling wins). Deferred (stated in spec): priority-based sort/filter/group, priority×urgency color blending, priority on widget/notification/stats. No mockup slice claimed. |

## How to use this table

- **Starting a feature?** Check the ⬜/🟡 rows for the screens you're touching — those are candidate
  slices to fold in, and the spec's *Visual & UX Design* section should say which you're taking and
  which you're leaving pending.
- **Finishing a feature?** Move the rows you delivered to ✅, add any newly-discovered pending polish,
  and keep the notes honest (a slice is only ✅ when it matches the mockup's *intent* in the real app,
  not just "something shipped").
- The ⬜ rows are the **visual backlog** — the answer to "why doesn't the app look like the mockup yet?"
