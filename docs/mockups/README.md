# Design mockups — tracking

The master mockup [`rediseno-ux-ui.html`](rediseno-ux-ui.html) is the app's **visual north star**
(see *Design in the Workflow* in the root `CLAUDE.md`). It shows the aspirational look across many
features at once; no single feature builds all of it. This table tracks **which slices are shipped
vs. pending**, so the gap between the mockup and the app is always visible and owned instead of
silently accumulating.

Update this table in the **Design review** step of every feature that touches UI.

**Legend:** ✅ done · 🟡 partial · ⬜ pending

## Slice status

| Mockup element / screen | Owning feature | Status | Notes |
|---|---|---|---|
| Native date/time picker (replaces free-text deadline) | 14 · selector de fecha | ✅ | Shipped as the platform picker. |
| Settings as icon+title cards (theme / reminders / account) | 15 · iconos secciones | ✅ | `SettingsSectionCard` (icon + title + divider). |
| Brand palette + type scale + Material You toggle | 16 · identidad visual | ✅ | `ui/theme/` (`NeverLateExtras`, type scale, dynamic-color switch). |
| Urgency-colored countdown (calm / soon / late) | 17 · estados y animaciones | ✅ | Color-only cue via `urgencyLevelFor` + `NeverLateExtras`. |
| Reusable empty/error states + list animations | 17 · estados y animaciones | ✅ | `MessageState`, `Modifier.animateItem()`. |
| Bottom navigation bar (Tasks / Articles / Settings) | 18 · navegación y accesibilidad | ✅ | `MainBottomBar`, route-gated visibility. |
| Accessibility pass (content descriptions, ≥48dp, dynamic font) | 18 · navegación y accesibilidad | 🟡 | Bar + `MessageState` covered; ongoing per screen. |
| **Task-card time-elapsed progress bar** | 19 · barra de progreso *(planned)* | ⬜ | Deferred by 17 ("v1: no progress bar"). Prompt: `docs/prompts/19-barra-progreso-tareas.md`. |
| **Brand-colored top app bars** | 20 · cromo de marca *(planned)* | ⬜ | Mockup app bars use the brand container color; app uses default M3 `TopAppBar` surface. Prompt: `docs/prompts/20-cromo-marca.md`. |
| **Colored leading-icon chips** on task/list rows | 20 · cromo de marca *(planned)* | ⬜ | Mockup rows have a `brand-container` rounded icon chip; not yet in the app. Prompt: `docs/prompts/20-cromo-marca.md`. |
| Branded FAB styling | 20 · cromo de marca *(planned)* | 🟡 | FAB exists (feature 04); mockup's branded treatment not applied. Prompt: `docs/prompts/20-cromo-marca.md`. |

## How to use this table

- **Starting a feature?** Check the ⬜/🟡 rows for the screens you're touching — those are candidate
  slices to fold in, and the spec's *Visual & UX Design* section should say which you're taking and
  which you're leaving pending.
- **Finishing a feature?** Move the rows you delivered to ✅, add any newly-discovered pending polish,
  and keep the notes honest (a slice is only ✅ when it matches the mockup's *intent* in the real app,
  not just "something shipped").
- The ⬜ rows are the **visual backlog** — the answer to "why doesn't the app look like the mockup yet?"
