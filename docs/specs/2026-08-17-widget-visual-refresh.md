# Feature — Rediseño visual del widget de tareas pendientes ("que el widget se parezca a la app")

**Status:** Draft — awaiting approval
**Suggested branch:** `feature/widget-visual-refresh`
**Tutorial:** `Sí (lección 05b-widget-tema-y-glance)` — el usuario ya lo decidió. Enseña por qué el
tema de Compose **no cruza** a la composición de Glance (`MaterialTheme.colorScheme` y
`NeverLateExtras.colors` viven en un `CompositionLocal` que el widget nunca provee, y
`colorForUrgency`/`Priority.indicatorColor()` son `@Composable` de Material 3 no invocables desde
Glance: "el mismo color, dos mundos"), `ColorProvider(day =, night =)` como forma de resolver
claro/oscuro sin `CompositionLocal` frente a `GlanceTheme` + `glance-material3`, los **límites de
API dentro de un widget** (`GlanceModifier.cornerRadius` es API 31+ y el `minSdk` del proyecto es
24 — degradar con elegancia en lugar de romper), y por qué `previewImage`/`previewLayout` son parte
del producto y no un extra. La lección se numera **05b** (interleaved: revisita la lección 05 del
widget; **no se renumera ninguna lección publicada**). El número exacto se confirma al escribirla
contra `tutorial/README.md` + `docs/conceptos-pendientes.md`.
**Type:** UI-only feature on `app/`. **No** backend, **no** API contract change, **no** Room schema
change or migration. One shared domain type gains one field. One new dependency (see D1), declared
in the version catalog.

---

## Overview

The home-screen widget (feature 05) is the only surface of the app that never received the visual
identity work of features 16–20. While the app moved to the brand palette, branded top bars,
urgency-colored countdowns, progress bars and priority indicators, the widget stayed exactly as
feature 05 shipped it:

- **Four hardcoded light-mode hex values** in `PendingTasksWidget.kt`
  (`WidgetBackground = 0xFFEFE6FF`, `WidgetTitleColor`, `WidgetTextColor`, `WidgetTimedOutColor`).
  They are neither brand colors nor theme roles, and there is **no dark variant at all**: on a dark
  launcher the widget is still a pale lavender rectangle.
- **Square corners** — no `cornerRadius`, no background drawable — so it reads as a foreign block on
  every modern launcher, which rounds its own widget frames.
- **A binary urgency cue**: the countdown is red when `remainingMillis == 0L` and brand-ish purple
  otherwise. The app has had a four-level scale (`urgencyLevelFor` → calm / soon / urgent / overdue)
  since feature 17.
- **No priority indicator**, even though every task has carried a `Priority` since feature 13b and
  the task card shows it as a colored dot. `docs/mockups/README.md` explicitly lists
  "priority on widget/notification/stats" as deferred on the 13b row.
- **No launcher preview**: `res/xml/pending_tasks_widget_info.xml` declares neither `previewImage`
  nor `previewLayout`, so the widget picker shows a generic app-icon placeholder instead of the
  widget.
- **Flat typography**: title and countdown share one size and weight, rows have 2 dp of vertical
  padding and no separation, so five rows read as one block of text.

This feature closes that gap. The widget adopts the app's identity — rounded corners, brand palette,
a real light/dark variant, the app's four-level urgency scale, the priority indicator, and a
readable typographic hierarchy — and stops looking unfinished in the launcher picker.

The enabling refactor already landed: feature 20b (commit `b11dd68`) moved remaining-time formatting
out of the domain, so `PendingTaskRow` carries raw `remainingMillis: Long` and the widget composes
its own text via `formatRemainingLabel(context, millis)`. The widget is therefore free to restyle
that text without touching the domain — which is exactly what this feature does.

## Goals

- The widget looks like it belongs to Never Late: rounded corners, brand palette, and a **correct
  dark variant** on a dark launcher/system theme.
- The remaining time is colored by the **same four-level urgency scale** the task list uses
  (`urgencyLevelFor`), not a red/not-red binary — and never relies on color alone.
- Each row shows the task's **priority**, closing the widget half of 13b's deferred item.
- Remaining time is **easier to read**: typographic hierarchy (title vs countdown), real row
  separation, and single-line titles that truncate instead of wrapping into a wall of text.
- The launcher picker shows a **real preview** of the widget.
- **Zero duplicated color knowledge**: the widget resolves its colors from the same
  `ColorScheme`/`Color.kt` values the app uses. No new hex literals, no second palette to keep in
  sync.
- Every decision Glance forces (theming mechanism, API-31-only modifiers, progress rendering,
  preview mechanism) is **decided here**, not improvised in the branch.

---

## Decisions (locked — do not re-litigate during implementation)

### D1 — Theming: **`glance-material3` + `GlanceTheme(ColorProviders(light, dark))`** (option b)

**Decision: option (b).** Add `androidx.glance:glance-material3` (version `1.1.1`, the same version
ref as the already-present `androidx.glance:glance-appwidget`) to
`gradle/libs.versions.toml` — reusing the existing `glance = "1.1.1"` version ref, never a hardcoded
version in `app/build.gradle.kts` — and wrap the widget content in:

```kotlin
GlanceTheme(colors = ColorProviders(light = LightColorScheme, dark = DarkColorScheme)) { … }
```

`LightColorScheme`/`DarkColorScheme` in `ui/theme/Theme.kt` are currently `private`; they become
`internal` so the widget can read the *same* scheme objects the app composes with. That visibility
change is the whole point: the widget stops re-deciding "which role is my background" and inherits
the app's role→color mapping verbatim.

**Why (b) and not (a):**

1. **It removes the drift risk, not just the hex literals.** Option (a) (a local Glance color file
   wrapping `surfaceLight`/`surfaceDark`/… in `ColorProvider(day, night)`) avoids duplicate hex
   values, but it duplicates the *role→color mapping* that `Theme.kt` already owns — the same class
   of duplication this project has repeatedly refused (`pendingRowsFor`, `formatRemainingLabel`,
   `colorForUrgency` are all "single home" decisions). With (b) there is exactly one mapping.
2. **The cost is genuinely small.** `glance-material3` is an AndroidX artifact on the *same version
   train* as the Glance dependency already in the build (1.1.1), so it introduces no new version pin
   and no AGP-compatibility question of the kind that forced the `hilt`/`adaptive` pins.
3. **(b) does not remove the need to teach `ColorProvider(day =, night =)` — it sharpens it.** The
   urgency colors *calm* and *soon* are **not** part of `ColorScheme`: they live in
   `NeverLateExtendedColors` precisely because Material 3 has no such role. So this feature ends up
   using both mechanisms, each where it belongs: `GlanceTheme` for everything Material 3 knows
   about (background, surface, onSurface, error, primary/secondary/tertiary), and a hand-written
   `ColorProvider(day = urgencyCalmLight, night = urgencyCalmDark)` for the two roles it doesn't.
   That contrast — "the scheme crosses through an adapter; your *extended* colors you must carry by
   hand" — is a better lesson than either option alone.
4. **Priority comes for free.** `Priority.indicatorColor()` maps to `secondary`/`tertiary`/`primary`;
   with (b) the widget mirrors that mapping against `GlanceTheme.colors` with no new colors at all.

**Explicitly NOT adopted:** `GlanceTheme()` with no arguments (which uses Material You / dynamic
colors on API 31+). The widget always uses the **brand** palette, matching the app's default
(`dynamicColor = false`, see `NeverLateTheme`). Honoring the in-app Material You preference inside
the widget is **deferred** (see Out of Scope) — it would require reading the `user_prefs` DataStore
from `provideGlance`, which is behaviour, not visual polish.

### D2 — `PendingTaskRow` gains `priority`; the notification **ignores** it (for now)

`domain/tasks/PendingTaskRows.kt`:

```kotlin
data class PendingTaskRow(
    val title: String,
    val remainingMillis: Long,
    val priority: Priority = Priority.NONE,
)
```

`pendingRowsFor` fills it from `task.priority`. The pending/ordering/cap rule is **unchanged**
(priority does **not** affect ordering — see Out of Scope).

**The lock-screen notification does not render priority in this feature.** Justification:

- The notification's rows are plain `InboxStyle` text lines built from `notification_row_format`
  (`title` + remaining label). Rendering priority there means adding a **third text token per line**
  to a surface where lines are already truncated by the system, pushing the two facts that actually
  matter (which task, how long left) toward the ellipsis. The widget has layout; the notification
  has a text budget.
- Priority is a *sorting/importance* hint, and the notification's whole message is already
  "most urgent first". Adding a marker restates importance in a second, weaker channel.
- The shared type carrying a field one consumer ignores is **the normal, intended shape** of
  `pendingRowsFor`: it owns the *rule* (what is pending, in what order, how many), while each
  surface decides what to *render* from a row. A Kotlin `data class` read by name means the
  notification does not even change.
- It is cheap to add later if wanted (one new format resource pair), and this spec records the
  choice rather than silently omitting it. `docs/mockups/README.md`'s 13b note is updated to say
  the **widget** half is done and **notification + stats stay deferred** — so nothing is deferred
  silently.

### D3 — Per-row progress indicator: **OUT of scope**, deferred to a size-aware widget feature

Glance *can* do it: `androidx.glance.appwidget.LinearProgressIndicator(progress, color,
backgroundColor)` exists in `glance-appwidget` 1.1.1 and maps to a `RemoteViews` `ProgressBar`, so
the two-`Box` width-ratio trick is not needed. It is still the wrong call **for this feature**:

1. **Vertical budget.** The widget's `minHeight` is 110 dp and it renders up to 5 rows
   (`MAX_PENDING_ROWS`) plus a header. A bar plus its spacing costs roughly 10–14 dp per row; five
   of them do not fit the minimum size, so at the default 4×2 cell size rows would clip — replacing
   one readability problem with a worse one. This feature's stated goal is *better* readability of
   remaining time.
2. **`PendingTaskRow` would need a second new field** (the total window, i.e.
   `estimatedDurationMillis`) purely so one surface can draw a decoration — growing a type shared
   with the notification for a cue the notification will never use. D2 adds one field that closes a
   tracked deferral; adding two, one of them speculative, is scope creep.
3. **It restates what the row already says.** After this feature the countdown text is
   urgency-colored *and* bolded on urgent/overdue; the bar would be a third encoding of the same
   fact, in the surface with the least room for it.
4. **The card's bar is animated by the 1 s `CountdownTicker`; the widget has no ticker.** It
   refreshes on writes and every ~15 min (`TaskSurfacesRefreshWorker`, WorkManager's floor). A bar
   reads as a live gauge far more than a text label does, so a stale bar misinforms more than a
   stale label.

**Deferred to:** a future **"widget size-aware layout"** feature (`SizeMode.Responsive`): a tall
widget variant that has the vertical room for `deadlineProgressFor` bars, plus per-row tap targets.
This is recorded as a **pending note on the new widget row** in `docs/mockups/README.md`, so the
deferral is visible in the visual backlog, not buried in this spec.

### D4 — Rounded corners on API 24: **a shape drawable + tint, one code path** (no `cornerRadius`)

`GlanceModifier.cornerRadius(dp)` requires **API 31** (it is backed by
`RemoteViews.setViewOutlinePreferredRadius`); `minSdk` is **24**. Rather than branching in Kotlin
(`if (SDK_INT >= 31) cornerRadius(...) else …`), the widget uses **one path on every API level**:

- `res/drawable/widget_background.xml` — a `<shape>` with `<corners android:radius="@dimen/widget_corner_radius"/>`
  and a **white `<solid>`**, applied via
  `GlanceModifier.background(ImageProvider(R.drawable.widget_background), colorFilter = ColorFilter.tint(GlanceTheme.colors.background))`.
  The drawable carries only the **shape**; the **color** still comes from the theme (D1), so there
  is no hex value in resource XML and no light/dark duplication.
- `res/values/dimens.xml`: `widget_corner_radius = 16dp` (matching the mockup's 14–16 px card
  radius). `res/values-v31/dimens.xml`: `widget_corner_radius = @android:dimen/system_app_widget_background_radius`,
  so on Android 12+ the widget matches the launcher's own frame radius instead of guessing.
- The header band gets its own `res/drawable/widget_header_background.xml` (top corners rounded,
  bottom corners square), tinted with `GlanceTheme.colors.primaryContainer`.

**Implementation check (do this first, it is a five-minute check):** confirm the
`background(ImageProvider, colorFilter = …)` overload and `ColorFilter.tint(ColorProvider)` are
present in `glance-appwidget` **1.1.1**. **If they are not**, the locked fallback is: keep the same
drawables, drop the tint, and give each drawable's `<solid>` a `@color/widget_background` reference
defined in `res/values/colors.xml` + `res/values-night/colors.xml`, whose two values are copied
from `ui/theme/Color.kt` with a comment naming that file as the source of truth. Do **not** invent a
third mechanism, and do **not** fall back to square corners.

**Explicitly rejected:** `if (Build.VERSION.SDK_INT >= 31) cornerRadius(...)` — it means 20 % of the
supported device range (API 24–30) keeps the square rectangle this feature exists to remove, i.e.
the visual bug survives on exactly the older devices least likely to have a rounding launcher.

### D5 — Preview: **ship both `previewLayout` and `previewImage`**

- `android:previewLayout="@layout/widget_preview"` (honored on **API 31+**, ignored below): a
  static XML layout that mimics the real widget — rounded background, header band, two illustrative
  rows with a priority marker and an urgency-colored time. It is the accurate, theme-aware preview
  and costs one file.
- `android:previewImage="@drawable/widget_preview_image"` (used on **API 24–30**, and by tooling and
  store surfaces): a hand-authored **vector** drawable showing the same composition. A vector rather
  than a PNG screenshot because this repo has no emulator-in-CI to capture one, and a vector is a
  reviewable text file in git.

**Note on the "No XML layouts" convention.** `CLAUDE.md` bans XML layouts *for app UI* — the app's
screens are Compose, and the widget itself stays Glance. `previewLayout` is **launcher metadata**,
inflated by the system's widget picker, and cannot be a composable by construction. Shipping one
static preview layout is therefore a documented, bounded exception, recorded in `docs/arquitectura.md`
with this reasoning so the next reader does not read it as convention drift.

All user-facing text inside the preview (header + the two sample task titles) comes from
`strings.xml` in **both** locales — the preview is user-visible surface, so the no-hardcoded-text
rule applies to it too.

---

## User Stories

### US-1 — The widget looks like the app
*As someone who put the widget on my home screen, I want it to look like Never Late — rounded, in
the brand palette — so that it stops looking like a leftover placeholder next to my other widgets.*

**Acceptance criteria**
- The widget's background comes from the app's theme (`GlanceTheme.colors.background`), not from a
  hardcoded hex value; the four `Widget*Color` constants in `PendingTasksWidget.kt` are **deleted**.
- The widget has rounded corners on **every** supported API level (24–36), via D4's drawable path.
- The header shows the widget title on a **brand container band** (`primaryContainer` /
  `onPrimaryContainer`) — the same brand-chrome idiom feature 20 gave the app's top app bars.
- `grep` finds **no `Color(0xFF…)` literal** in `ui/widget/`.

### US-2 — A correct dark variant
*As someone whose phone is in dark mode, I want the widget to be dark too, so that it does not glow
on my home screen at night.*

**Acceptance criteria**
- With the system in dark mode the widget renders with the app's **dark** scheme (dark background,
  light text, dark-variant urgency colors); in light mode, the light scheme. Verified manually on a
  device/emulator by toggling system dark mode with the widget on the home screen.
- The light/dark choice is resolved by `GlanceTheme` + `ColorProviders` (D1) — the widget does not
  read `isSystemInDarkTheme()` and does not read the app's `ThemeMode` preference (see Out of Scope).
- Text/background contrast meets **WCAG AA for body text in both themes** (the app's scheme roles are
  already paired for this; the ACs are that no role is mixed across pairs, e.g. never `onSurface` on
  `primaryContainer`).

### US-3 — Urgency at a glance, on the same scale as the app
*As someone with ADD/ADHD scanning the widget, I want the same calm/soon/urgent/late color language
the task list uses, so that I learn one visual code instead of two.*

**Acceptance criteria**
- The remaining-time label is colored per `urgencyLevelFor(remainingMillis, remainingMillis == 0L)`:
  **Calm** → extended calm color, **Soon** → extended soon color, **Urgent**/**Overdue** →
  `GlanceTheme.colors.error` — the exact same mapping as `colorForUrgency` in `TasksScreen.kt`.
- The Calm/Soon colors come from `ColorProvider(day = urgencyCalmLight, night = urgencyCalmDark)`
  (and the `soon` pair) — the existing values in `ui/theme/Color.kt`, **not** new hex values.
- Urgency is **never carried by color alone**: the label text itself states the state
  (`2h 38m` / `Tiempo agotado` via the shared `formatRemainingLabel`), and **Urgent/Overdue rows are
  additionally bolded** while Calm/Soon are not — a weight (shape) channel on top of the color one.
- The timed-out row still reads `Tiempo agotado` / `Time's up` (feature 20b behaviour preserved).

### US-4 — Priority on the widget
*As someone who marked a task as high priority, I want to see that on the widget, so that the widget
tells me the same thing the task list does.*

**Acceptance criteria**
- Each row with a non-`NONE` priority shows a **priority marker** leading the title; `Priority.NONE`
  shows **nothing** (no visual noise for the default — same rule as the task card).
- The marker's color mirrors `Priority.indicatorColor()`: `LOW` → `secondary`, `MEDIUM` →
  `tertiary`, `HIGH` → `primary`, all read from `GlanceTheme.colors`.
- The marker is **not color-only**: it renders the localized rank glyph
  (`widget_priority_marker_low/medium/high` → `!` / `!!` / `!!!`), so rank is legible as *repetition
  count* without color perception, and the color reinforces it.
- The row carries a `contentDescription` via Glance `semantics` built from the existing
  `tasks_priority_content_description` + `priority_*` labels ("Prioridad: Alta"), so TalkBack on the
  home screen announces priority in words.
- `PendingTaskRow` carries `priority`; `pendingRowsFor` fills it; the ordering/cap rule is unchanged
  and its existing tests still pass unmodified.

### US-5 — Rows that are actually readable
*As someone glancing at five rows, I want to tell titles and times apart instantly, so that the
widget answers "what's next and how long" in one look.*

**Acceptance criteria**
- **Hierarchy:** the countdown is visually dominant (bold, ≥ the title's size); the title is regular
  weight in `onSurface`; the header is bold in `onPrimaryContainer`. Three distinct treatments, not
  one.
- **Separation:** rows are separated by a hairline divider (a 1 dp `Box` in
  `GlanceTheme.colors.outlineVariant`) or ≥ 8 dp of vertical spacing — the current 2 dp block-of-text
  look is gone.
- **Titles are single-line and truncate** (`maxLines = 1`); a long title never pushes the countdown
  off the row or wraps the row into three lines.
- The countdown column never wraps: with the longest label (`12d 6h 30m`) and the longest priority
  marker, a typical 4×2 widget still shows title + marker + countdown on one line.

### US-6 — A real preview in the widget picker
*As someone adding a widget, I want to see what it looks like before I place it, so that I do not
have to drop it on the home screen to find out.*

**Acceptance criteria**
- The widget picker shows a preview resembling the real widget (header band + 2 rows), not the
  generic app-icon placeholder — on API 31+ via `previewLayout`, on API 24–30 via `previewImage`.
- All text in the preview comes from string resources present in **both** `values/` and
  `values-en/`.

---

## Acceptance Criteria (consolidated)

### Behavioural
- Every user-story AC above holds.
- `PendingTaskRow` carries `priority: Priority`; `pendingRowsFor` populates it; **ordering and the
  5-row cap are unchanged** (proven by the existing tests passing untouched).
- The lock-screen notification's output is **byte-for-byte unchanged** (D2) — its existing tests pass
  without modification.
- The widget's data path is unchanged: `provideGlance` still snapshots `observeTasks().first()` and
  maps via `toWidgetModel`. **No** change to refresh triggers, WorkManager scheduling, the tap
  action (`MainActivity.EXTRA_OPEN_TASKS`), or `PendingTasksWidgetReceiver`.
- The empty state (`widget_pending_tasks_empty`) is still rendered — restyled with the new tokens,
  never a blank box.
- `./gradlew :app:testDebugUnitTest` is green; `./gradlew :app:assembleDebug` builds on `minSdk = 24`.

### Visual (verified in the Design review step)
- **Legibility in light and dark.** The widget is readable on both a light and a dark launcher;
  every text/background pairing uses matched scheme roles (`onX` on `X`), verified by inspection and
  by the manual light/dark check.
- **Color is never the sole carrier of information.** Urgency is also carried by the label's own text
  and by font weight (US-3); priority is also carried by the marker's repetition count and by the
  row's `contentDescription` (US-4).
- **Rounded corners on API 24 and on API 31+.** Verified on both an API-24-era and a modern
  emulator/device (or, at minimum, an API 24 emulator plus the current device).
- **Resize reflow.** Resized in the launcher — narrower, wider, shorter, taller — the widget reflows
  without clipped text, overlapping columns, or a countdown pushed off-screen; when the height only
  fits fewer rows, the remaining rows are cut cleanly at the bottom rather than squeezed.
- **Touch targets ≥ 48 dp.** The widget keeps a single click target (the whole widget opens the app);
  at its minimum size (250×110 dp) that is comfortably ≥ 48 dp. No sub-48 dp tap target is
  introduced (no per-row buttons — see Out of Scope).
- **Font scale.** At the largest system font scale the widget still renders its header and at least
  the first row without clipping; text is sized in `sp` so it scales (never `dp` for text).
- **The launcher picker preview** shows the widget's real composition (US-6).

### Definition-of-Done items this feature touches
- **Tests pass.** New JVM unit tests for `pendingRowsFor` carrying priority and for the widget's
  pure urgency mapping; existing widget/notification model tests still pass.
  `./gradlew :app:testDebugUnitTest` green before commit.
- **Accessibility & i18n hold.** New strings in **both** `values/` (Spanish base) and `values-en/`;
  priority announced in words via `semantics`; color never the sole information channel; `sp` text;
  ≥ 48 dp target.
- **Every state is designed.** The widget's two states (empty / content) are both restyled. There is
  no loading state by construction (`provideGlance` composes only after the data snapshot) and no
  error state (a local Room read) — stated here so the absence is a decision, not an oversight.
- **New dependency goes through the version catalog.** `glance-material3` is added to
  `gradle/libs.versions.toml` under the existing `glance` version ref, with an explanatory comment
  in the file's established style.
- **No migration, no contract change, no schema change** — asserted explicitly; `app/schemas/` and
  `docs/api/contract.md` are untouched, and that is correct, not a gap.
- **Visual ACs pass and the tracking table reflects reality** (see Visual & UX Design below).
- **The tutorial lesson ships** (`Tutorial: Sí`) before the commit, with its status flipped in
  `tutorial/README.md` and `docs/conceptos-pendientes.md`.

---

## Visual & UX Design

### Master mockup slice — **net-new surface, translated intent**

`docs/mockups/rediseno-ux-ui.html` is **phone-app only**: it contains no widget frame, no launcher
context, and no widget picker. This feature therefore claims **no existing mockup row**; it adds a
**new row** to `docs/mockups/README.md` marked `—` (net-new UI outside the mockup), in the same way
the stats screen, the adaptive layout and the priority indicator rows are tracked.

What it *does* translate is the **intent of the task-card slice** — the mockup's card composition
(`.card` + `.count ok|soon|late` + priority-style dot + rounded 14–16 px corners + brand-container
leading chip) — into what Glance can express:

| Mockup intent (task card) | Widget translation | Why it differs |
|---|---|---|
| Rounded card, 14–16 px radius | `widget_background.xml` at 16 dp / the platform widget radius on v31+ | Glance has no `Card`; `cornerRadius` is API 31+ (D4) |
| `--brand-container` leading chip | brand-container **header band** | A per-row 40 dp chip would eat the row's width budget in a 250 dp-wide surface |
| `.count` colored `ok/soon/late` | urgency-colored, weight-differentiated countdown | Same four-level scale (`urgencyLevelFor`), same source colors |
| Priority dot | priority **marker glyph**, role-colored | An 8 dp dot alone is color-only; Glance has no `semantics` on a bare `Box` shape the way the card does — the glyph carries rank without color |
| Elapsed-time progress bar | **deferred** (D3) | No vertical budget at the default widget size; deferred to a size-aware widget feature |
| Card elevation / shadow | not reproduced | `RemoteViews` has no elevation; a fake shadow drawable is worse than none |

The mockup is consulted as **direction, not code**: none of its HTML/CSS is copied, and every color
resolves through the app's real theme (D1), never a hex value lifted from the mockup.

### Layout (normative sketch)

```
┌────────────────────────────────────────────┐  ← widget_background (rounded, GlanceTheme background)
│ Tareas pendientes                          │  ← header band: primaryContainer / onPrimaryContainer,
├────────────────────────────────────────────┤     bold, top corners rounded
│ !!!  Enviar informe              4m        │  ← marker (primary) · title (onSurface, maxLines 1,
│ ──────────────────────────────────────────  │     defaultWeight) · countdown (error, bold)
│ !!   Preparar reunión            48m       │  ← marker (tertiary) · … · countdown (soon, regular)
│ ──────────────────────────────────────────  │  ← 1dp outlineVariant divider
│      Comprar regalos             14h 22m   │  ← Priority.NONE: no marker
└────────────────────────────────────────────┘
```

Empty state: the header band, then `widget_pending_tasks_empty` in `onSurfaceVariant`.

### Concrete visual acceptance criteria

Restated compactly for the Design review checklist (all also listed above):

1. Rounded corners visible on **API 24** and **API 31+**.
2. Correct **light and dark** rendering after a system dark-mode toggle, with matched `onX`/`X`
   role pairs and WCAG AA body-text contrast.
3. Countdown color follows the **four-level** urgency scale, identical to the task card's mapping.
4. Urgency and priority are **each carried by at least two channels** (color + text/weight/glyph).
5. Priority marker present for `LOW/MEDIUM/HIGH`, absent for `NONE`.
6. Three distinct type treatments (header / title / countdown) and visible row separation.
7. Titles truncate to one line; the countdown never wraps or is pushed off the row.
8. Reflows cleanly when resized in the launcher (narrower / wider / shorter / taller).
9. Header + first row legible at the **largest** system font scale.
10. Single click target ≥ 48 dp; no smaller tap targets introduced.
11. The widget picker shows a real preview.
12. No `Color(0xFF…)` literal remains in `ui/widget/`.

### Theme & component reuse

- Colors: `GlanceTheme` over the app's own `LightColorScheme`/`DarkColorScheme` (D1); Calm/Soon from
  `ui/theme/Color.kt`'s existing `urgency*Light`/`urgency*Dark` values via `ColorProvider(day, night)`.
- Text: the shared `formatRemainingLabel` (feature 20b) — unchanged, no second formatter.
- Rules: `urgencyLevelFor` and `pendingRowsFor` — the same pure domain functions the app screen uses.
- Priority labels: the existing `priority_*` and `tasks_priority_content_description` resources.
- Nothing new is invented that the app already has.

### Mockup tracking plan (`docs/mockups/README.md`)

Updated in the Design review step:

1. **New row** — `| Home-screen widget visual refresh | 05b · rediseño del widget | — | **Not in the
   master mockup** (which is phone-app only); net-new surface that translates the task-card slice's
   *intent* into Glance. Delivers: rounded corners (drawable + platform radius on v31+), theme-driven
   light/dark via `GlanceTheme` + `glance-material3`, four-level urgency color (`urgencyLevelFor`,
   same mapping as `colorForUrgency`) with a weight channel so color is never alone, priority marker
   (role-colored glyph + `semantics`), typographic hierarchy + row dividers + single-line titles,
   and `previewLayout`/`previewImage` in the picker. Deferred (stated in spec): per-row progress bar
   and per-row tap targets → a future size-aware (`SizeMode.Responsive`) widget feature; Material You
   / in-app `ThemeMode` inside the widget; notification + stats priority. No mockup slice claimed. |`
2. **Update the 13b row's note**: its deferred list currently reads "priority on widget/notification/stats";
   it becomes "priority on **notification/stats** (the **widget** half shipped in feature 05b)".

---

## Technical Approach

Sub-project: **`app/`** only. Packages touched: `ui/widget/`, `domain/tasks/`, `ui/theme/` (one
visibility change), plus resources.

### 1. Dependency (version catalog)
- `gradle/libs.versions.toml`: add
  `androidx-glance-material3 = { group = "androidx.glance", name = "glance-material3", version.ref = "glance" }`
  with a comment explaining *why* (bridges `androidx.compose.material3.ColorScheme` into Glance's
  `ColorProviders`, so the widget reuses the app's scheme instead of a second palette).
- `app/build.gradle.kts`: `implementation(libs.androidx.glance.material3)` — no hardcoded version.

### 2. Theme access
- `ui/theme/Theme.kt`: `LightColorScheme` / `DarkColorScheme` become `internal` (from `private`),
  with a one-line comment naming the widget as the second consumer and pointing at this spec.
  `NeverLateTheme` is otherwise untouched.

### 3. New: `ui/widget/WidgetColors.kt`
The widget's small "color adapter" layer, and the lesson's centerpiece:
- `@Composable fun urgencyColorProvider(level: UrgencyLevel): ColorProvider` — mirrors
  `colorForUrgency`: `Urgent`/`Overdue` → `GlanceTheme.colors.error`; `Calm`/`Soon` → the
  hand-written `ColorProvider(day = urgencyCalmLight, night = urgencyCalmDark)` /
  `(urgencySoonLight, urgencySoonDark)` pairs (declared here as private vals), because those two
  roles do not exist in `ColorScheme`.
- `@Composable fun Priority.glanceIndicatorColor(): ColorProvider?` — mirrors
  `PriorityUi.indicatorColor()` against `GlanceTheme.colors` (`NONE` → `null`).
- KDoc states plainly why `colorForUrgency`/`indicatorColor()` cannot simply be called here (they are
  Material 3 `@Composable`s reading a `CompositionLocal` the Glance composition never provides), and
  that these two functions must be changed **together with** their Compose twins.

### 4. `ui/widget/PendingTasksWidget.kt` (the bulk of the change)
- Delete the four `Widget*Color` constants and the `WidgetBackground` usage.
- Wrap the content in `GlanceTheme(colors = ColorProviders(light = LightColorScheme, dark = DarkColorScheme))`.
- Root `Column`: `background(ImageProvider(R.drawable.widget_background), colorFilter = ColorFilter.tint(GlanceTheme.colors.background))`
  + `fillMaxSize()` + the existing `clickable(openTasks)`. Padding moves inside so the rounded
  background is not padded away.
- Header `Row`: `widget_header_background` tinted `primaryContainer`, title `onPrimaryContainer`,
  bold, ~16 sp.
- `PendingTaskRowContent`: priority marker `Text` (glyph resource, `glanceIndicatorColor()`), title
  `Text` (`defaultWeight()`, `maxLines = 1`, `onSurface`), countdown `Text`
  (`formatRemainingLabel(context, row.remainingMillis)`, `urgencyColorProvider(level)`, bold only for
  `Urgent`/`Overdue`). Row-level `semantics { contentDescription = … }` including the priority words.
- A 1 dp `Box` divider (`outlineVariant`) between rows.
- Empty state restyled with `onSurfaceVariant`.
- `provideGlance` is otherwise unchanged (still hand-built repository — see Out of Scope).

### 5. `domain/tasks/PendingTaskRows.kt`
- `PendingTaskRow` gains `priority: Priority = Priority.NONE`; `pendingRowsFor` fills it from the
  task. Rule (pending definition, most-urgent-first order, `MAX_PENDING_ROWS`) untouched.
- Add a small pure helper so the widget's urgency decision is JVM-testable without a widget host,
  e.g. `fun PendingTaskRow.urgencyLevel(): UrgencyLevel = urgencyLevelFor(remainingMillis, remainingMillis == 0L)`.
  (This also documents, in one place, that a widget row's "timed out" is exactly `remainingMillis == 0L`
  — the invariant feature 20b established.)

### 6. Resources
- `res/drawable/widget_background.xml`, `res/drawable/widget_header_background.xml` (shapes only).
- `res/drawable/widget_preview_image.xml` (vector preview) and `res/layout/widget_preview.xml`
  (API 31+ preview layout).
- `res/values/dimens.xml` + `res/values-v31/dimens.xml` (`widget_corner_radius`).
- `res/xml/pending_tasks_widget_info.xml`: add `android:previewLayout` and `android:previewImage`;
  **also fix its stale comment**, which still names `WidgetRefreshingTaskRepository` /
  `WidgetRefreshWorker` — those are now `TaskSurfacesRefreshingRepository` /
  `TaskSurfacesRefreshWorker`. (Free correctness fix in a file this feature already edits.)
- `res/values/strings.xml` + `res/values-en/strings.xml`:

| Resource | ES (`values/`) | EN (`values-en/`) |
|---|---|---|
| `widget_priority_marker_low` | `!` | `!` |
| `widget_priority_marker_medium` | `!!` | `!!` |
| `widget_priority_marker_high` | `!!!` | `!!!` |
| `widget_preview_task_first` | `Enviar informe` | `Send report` |
| `widget_preview_task_second` | `Preparar reunión` | `Prepare meeting` |

  Reused unchanged: `widget_pending_tasks_title`, `widget_pending_tasks_empty`,
  `widget_pending_tasks_description`, `priority_low/medium/high`,
  `tasks_priority_content_description`, `tasks_remaining_*`, `tasks_time_up`.
  The marker glyphs are **string resources, not literals**, precisely so a locale that reads `!!!`
  differently can change them without touching Kotlin — the feature-20b lesson applied again.

### Files in scope
- `gradle/libs.versions.toml`, `app/build.gradle.kts` — new catalog entry + dependency.
- `app/src/main/java/com/neverlate/ui/theme/Theme.kt` — scheme visibility.
- `app/src/main/java/com/neverlate/ui/widget/WidgetColors.kt` — **new**.
- `app/src/main/java/com/neverlate/ui/widget/PendingTasksWidget.kt` — restyle.
- `app/src/main/java/com/neverlate/domain/tasks/PendingTaskRows.kt` — `priority` + urgency helper.
- `app/src/main/res/` — drawables, layout, dimens (+`values-v31`), strings (both locales),
  `xml/pending_tasks_widget_info.xml`.
- Tests: `app/src/test/java/com/neverlate/ui/widget/PendingTasksWidgetStateTest.kt`,
  `app/src/test/java/com/neverlate/domain/tasks/` (new priority/urgency cases).
- Docs: `docs/mockups/README.md`, `docs/arquitectura.md`, `CLAUDE.md` (module map line for
  `ui/widget/`), `tutorial/05b-*.md`, `tutorial/README.md`, `docs/conceptos-pendientes.md`.

---

## Out of Scope

- **Migrating the widget to Hilt.** `provideGlance` still builds its repository by hand
  (`NeverLateDatabase.getInstance(context)` + `RoomTaskRepository`); feature 13d never migrated it.
  That is an **architecture** change, not a visual one, and it must not ride along in this branch —
  it deserves its own spec (and its own thinking about `EntryPointAccessors` in a `GlanceAppWidget`).
- **Any backend, API contract, or `TaskDto` change**; **any Room schema change or migration.**
  `docs/api/contract.md` and `app/schemas/` are untouched.
- **Per-row progress indicator** (D3) → deferred to a size-aware widget feature.
- **Per-row tap targets / actions** (tap a row to open that task, complete from the widget). The
  widget keeps its single "open the app on tasks" action. Adding row actions means `actionRunCallback`
  and ≥ 48 dp per-row targets — behaviour, not visual polish.
- **`SizeMode.Responsive` / distinct layouts per widget size.** This feature makes the *current*
  single layout reflow correctly; multiple size buckets are the deferred feature.
- **Material You / the in-app `ThemeMode` preference inside the widget.** The widget always uses the
  brand palette (D1). Reading `user_prefs` from `provideGlance` (so the widget follows a user who
  forced light or dark in-app, or enabled dynamic color) is a separate, behavioural feature.
- **Priority on the lock-screen notification and on the stats screen** (D2) — those two thirds of
  13b's deferred item stay deferred, and the tracking note says so.
- **Priority-based ordering** in `pendingRowsFor`. Rows stay most-urgent-first; priority is displayed,
  not sorted on. (13b already deferred priority sort/filter/group.)
- **Filtering completed tasks out of the widget/notification.** `pendingRowsFor` today includes every
  non-deleted task, completed ones included — pre-existing behaviour, unchanged here, and flagged in
  Risks so it is not mistaken for something this feature introduced.
- **A widget configuration activity** (choose row count, choose what to show).
- **Changing refresh cadence or triggers** (`TaskSurfacesRefreshWorker`, `TaskSurfacesRefreshingRepository`).
- **Restyling the lock-screen notification.** Its look is untouched.

---

## Dependencies

- **Satisfied already — feature 20b (commit `b11dd68`), verified in the code:**
  `domain/tasks/PendingTaskRows.kt` declares `data class PendingTaskRow(val title: String, val
  remainingMillis: Long)` (raw millis, no pre-formatted string, no `isTimedOut` flag), and
  `ui/components/RemainingTimeLabel.kt`'s `formatRemainingLabel(context, remainingMillis)` is the
  single home of the compact label, already called by `PendingTasksWidget.PendingTaskRowContent`.
  The widget can therefore restyle its text freely with **no domain change** — this feature adds one
  field to that type and changes nothing else about it.
- **Existing code that must be reused, not re-implemented:** `urgencyLevelFor`
  (`domain/tasks/Urgency.kt`), `pendingRowsFor`, `formatRemainingLabel`, the `urgency*Light/Dark`
  and scheme colors in `ui/theme/Color.kt`, the `Priority` enum and its `priority_*` /
  `tasks_priority_content_description` resources.
- **New library:** `androidx.glance:glance-material3` at the existing `glance = 1.1.1` version ref —
  must resolve from the configured repositories at build time (it is not currently in the local
  Gradle cache, which holds only `glance`, `glance-appwidget` and its protobuf artifacts).
- **Verification needs a device or emulator.** Glance output is `RemoteViews` rendered by the
  launcher: light/dark, corner radius on API 24, resize reflow and the picker preview **cannot** be
  proven by unit tests. The Design review step requires a running launcher (per `CLAUDE.md`'s
  emulator instructions), ideally one API-24-era AVD plus a modern one.
- **No backend work, no new permission, no manifest change** beyond the two new attributes in
  `pending_tasks_widget_info.xml` (the receiver declaration is unchanged).

---

## Risks

- **`glance-material3` may not resolve, or the `background(ImageProvider, colorFilter)` overload may
  not exist in 1.1.1.** *Mitigation:* both are checked in the first hour of implementation; D4 carries
  a locked fallback (color resources with a `values-night` variant) and, if `glance-material3` itself
  cannot be added, D1's option (a) is the documented fallback — but that reversal changes an approved
  decision, so it comes **back to the user**, it is not taken silently.
- **`RemoteViews` is not Compose.** Glance supports a deliberately small subset: no arbitrary drawing,
  no `Modifier.clip`, no elevation, limited text styling. *Mitigation:* every visual decision in this
  spec is expressed in primitives Glance 1.1 actually has (`Text`, `Row`/`Column`/`Box`,
  `background(ImageProvider/ColorProvider)`, `semantics`, `defaultWeight`, `maxLines`); the mockup's
  card shadow and per-row chip are explicitly not reproduced.
- **Launcher fragmentation.** Some launchers (and some OEM skins) impose their own padding, corner
  masking or background tint on widgets, so the widget may not look pixel-identical everywhere.
  *Mitigation:* the design depends on **contrast and hierarchy**, not on exact geometry; the platform
  radius dimen on v31+ follows the launcher's own convention rather than fighting it.
- **Dark-mode resolution is the launcher's call.** `ColorProviders` resolve against the **host's**
  configuration; a launcher that runs in a different `uiMode` than the app can render the "wrong"
  variant. *Mitigation:* this is inherent to widgets and is precisely why the widget does **not** read
  the in-app `ThemeMode` (which would be a second, conflicting source of truth). Called out in the
  lesson.
- **Two color mappings must stay in sync.** `WidgetColors.kt` mirrors `colorForUrgency` and
  `Priority.indicatorColor()`. If someone changes one and not the other, card and widget drift.
  *Mitigation:* cross-referencing KDoc on both sides stating they are twins; the shared *inputs*
  (`UrgencyLevel`, `Priority`, `Color.kt` values) mean only the two tiny mapping functions can
  diverge, not the underlying palette. A stronger fix (a single pure `UrgencyLevel → color token`
  enum consumed by both worlds) is noted as future work rather than forced in here.
- **The `!`/`!!`/`!!!` marker is a new visual idiom** not present on the task card (which uses a dot).
  *Mitigation:* deliberate — a bare dot is a color-only cue, which the accessibility ACs forbid on a
  surface with no tooltip and no adjacent legend. It is localizable, and unifying the two surfaces on
  one priority idiom is a candidate follow-up for whenever the card's dot is revisited.
- **Widget staleness is unchanged and will be visible next to the new colors.** Rows refresh on writes
  and every ~15 min (WorkManager's floor), so an urgency color can lag reality by minutes. *Mitigation:*
  pre-existing and documented in `TaskSurfacesRefreshWorker`'s KDoc; it is also part of why the
  progress bar is deferred (D3). No cadence change here.
- **The widget shows completed tasks.** `pendingRowsFor` includes every non-deleted task, so a task
  completed in-app still occupies a widget row. Pre-existing (since feature 04c added completion) and
  **out of scope**; the visual refresh will make it more noticeable, which is why it is recorded here
  as a candidate bug-fix branch of its own.
- **`previewLayout` introduces one XML layout** in a Compose-only project. *Mitigation:* bounded,
  justified in D5, and recorded in `docs/arquitectura.md` so it is not read as convention drift.

---

## Testing Plan

**JVM unit tests** (`./gradlew :app:testDebugUnitTest` — must be green before commit):
- `pendingRowsFor` carries `priority` verbatim from the task, for all four `Priority` values, and
  `Priority.NONE` stays the default for a task that never set one.
- `pendingRowsFor`'s **existing** ordering / cap / "timed-out rows are included" tests still pass
  **unmodified** — proof that adding a field changed no rule.
- `PendingTaskRow.urgencyLevel()` (the new helper) returns `Overdue` at exactly `0L`, `Urgent` at
  ≤ 5 min, `Soon` at ≤ 60 min, `Calm` above — including the two threshold boundaries. (Complements,
  does not duplicate, the existing `UrgencyTest`.)
- `toWidgetModel` still returns `Empty` for no tasks and `Content` otherwise, with priority carried
  through.
- Notification model tests pass **untouched** (D2 regression guard).

**Not unit-testable — manual verification in the Design review step** (Glance renders `RemoteViews`
in the launcher's process; there is no unit-level assertion for any of it):
- Light theme and dark theme, toggled with the widget already placed.
- Rounded corners on an **API 24** emulator and on a modern device/emulator.
- Resize in the launcher: narrower, wider, shorter, taller — no clipping or overlap.
- Largest system font scale — header + first row legible.
- The widget picker preview on API 31+ (`previewLayout`) and on an older emulator (`previewImage`).
- TalkBack announces a row's priority in words.
- A task at each urgency level and each priority level, checked against the task card for
  color/wording agreement.

**Build check:** `./gradlew :app:assembleDebug` (confirms `glance-material3` resolves and that
nothing used is above `minSdk = 24` without a guard).

---

## Documentation Update checklist (before commit)

| Item | Applies? | Action |
|---|---|---|
| `docs/api/contract.md` | ❌ | No wire change. |
| Room schema / migration / `app/schemas/` | ❌ | No persisted field changes. |
| `docs/mockups/README.md` | ✅ | Add the new widget row; update the 13b row's deferred note (widget half done). |
| `gradle/libs.versions.toml` | ✅ | `glance-material3` under the existing `glance` ref, with a why-comment. |
| Manifest / permissions | 🟡 | Only the two new preview attributes in `pending_tasks_widget_info.xml`; note the preview-layout exception in `docs/arquitectura.md`. |
| `CLAUDE.md` (Structure / module map) | ✅ | `ui/widget/` line gains `WidgetColors.kt` + the widget's theming approach; `ui/theme/` line notes the schemes are now `internal` (second consumer: the widget). |
| `docs/arquitectura.md` | ✅ | New entry: why the Compose theme cannot cross into Glance, why `glance-material3` was chosen over hand-rolled `ColorProvider`s, the API-24 corner-radius strategy, and the `previewLayout` XML exception. |
| Tutorial lesson | ✅ | `tutorial/05b-widget-tema-y-glance.md` (Spanish) + status flipped in `tutorial/README.md` (Bloque 2, after 05) and a new `05b` row in `docs/conceptos-pendientes.md`. |
| Setup / commands / SDK | ❌ | Unchanged. |

---

## Approval

Please review this specification. Approval covers **behaviour, look, and the tutorial decision**
(`Sí — lección 05b`). In particular, the five locked decisions are part of what is being signed off:

- **D1** — `glance-material3` + `GlanceTheme(ColorProviders(light, dark))`, with the app's schemas
  made `internal`; the widget always uses the **brand** palette (no Material You).
- **D2** — `PendingTaskRow` gains `priority`; the **lock-screen notification ignores it** for now.
- **D3** — **no per-row progress bar**, deferred to a future size-aware widget feature.
- **D4** — rounded corners via a **shape drawable + theme tint** on all API levels (no
  `cornerRadius`, no API branch), with the platform radius on v31+.
- **D5** — **both** `previewLayout` (API 31+) and `previewImage`, with the single preview XML layout
  documented as a bounded exception to the Compose-only rule.

Implementation will not begin until you explicitly approve.
