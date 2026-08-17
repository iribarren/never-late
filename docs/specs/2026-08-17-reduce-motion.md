# Feature — Respect "reduce motion": what the platform already gives us, and the gap it leaves

- **Status:** Awaiting approval
- **Date:** 2026-08-17
- **Branch (suggested):** `feature/reduce-motion`
- **Prompt origen:** [`docs/prompts/reducir-movimiento.md`](../prompts/reducir-movimiento.md)
- **Original title (user's words):** *"Respetar «reducir movimiento»: lo que la plataforma ya da y lo
  que falta"*
- **Type:** Behaviour change on `app/` only. **No** backend change, **no** API contract change,
  **no** Room schema change or migration, **no** new dependency, **no** new permission (reading
  `Settings.Global` requires none), **no** new screen, **no** new user-facing string resource.
- **Tutorial:** **Sí** — Spanish lesson, confirmed by the user via `AskUserQuestion`
  ("Sí, con lección"). It is **not written now**: per `CLAUDE.md` → *Tutorial Track (optional)*, the
  lesson is authored **after implementation, before committing**. Suggested number **`20c`** —
  interleaved right behind `tutorial/20b-tiempo-restante-localizable.md`, because this feature
  **derogates a decision that lesson published** (see [D2](#d2--derogating-the-countdownticker-kdoc-instruction)),
  and a reader must meet 20b's "the 1 s tick survives" argument *before* meeting the case that ends
  it. **No shipped lesson is ever renumbered**; the exact number is re-confirmed against
  `tutorial/README.md` + `docs/conceptos-pendientes.md` at writing time. What the lesson must cover
  is listed in [Tutorial lesson — what `20c` must teach](#tutorial-lesson--what-20c-must-teach).

---

## Overview

Android has a system-wide accessibility setting — **Ajustes → Accesibilidad → Quitar animaciones**
(*Settings → Accessibility → Remove animations*) — that writes `Settings.Global.animator_duration_scale = 0`.
People turn it on for vestibular disorders, motion sensitivity, attention reasons, or simply because
motion is distracting. That last one matters unusually much here: **Never Late Again's users have
ADD/ADHD**, and "the screen keeps moving" is not a cosmetic complaint for them.

**Most of this already works, for free, and the spec says so up front rather than taking credit for
it.** Jetpack Compose's window `Recomposer` installs a `MotionDurationScale` — a
`CoroutineContext.Element` — into the composition's coroutine context, sourced from that exact
`Settings.Global` key and kept live via a `ContentObserver`. Every animation that runs inside that
context reads it and collapses to an instant snap when the scale is `0`. Verified against the
artifacts this project actually resolves (see [Verified inventory](#verified-inventory--what-is-already-free)):
the task list's `Modifier.animateItem()`, the grouped task list's, the article list's, the task
card's `animateFloatAsState` progress bar, and all of `ArticlesListDetailPane`'s `AnimatedPane`s
**already honour the setting with zero app code**.

So this feature is **not** "add reduce-motion support". A spec that claimed that would be selling
smoke. The real work is the **narrow gap the platform cannot cover**, plus writing down what is
already covered so nobody "fixes" it again in a year:

1. **`CountdownTicker`'s 1-second cadence.** It recomposes the whole Tasks screen once a second, for
   the sole purpose of letting the card's progress bar drain smoothly. A **periodic recomposition is
   not an animation** — no `MotionDurationScale` slows it down, because there is no animation for it
   to scale. Under reduced motion the smooth drain it pays for no longer exists, so the app is
   burning a recomposition per second (and the battery behind it) to buy nothing at all. Worse, from
   the user's point of view it is exactly the thing they asked to stop: a screen that will not sit
   still.
2. **A single, reusable "should we reduce motion?" criterion**, read once from one place, instead of
   each screen inventing its own `Settings.Global` lookup.
3. **The four `AnimatedPane` call sites** — verified case by case rather than assumed.

The visible outcome is deliberately quiet: under reduced motion the Tasks screen stops twitching,
the progress bar steps instead of gliding, and **nothing freezes, disappears, or becomes less
correct**. Under normal motion, absolutely nothing changes.

---

## Verified inventory — what is already free

Verified empirically against the **resolved** runtime classpath of this project (not from memory, and
not from the version catalog's BOM string — see [Technical notes](#technical-notes) for how and why
those differ).

| Animation site | File | Mechanism | Honours the system setting? |
|---|---|---|---|
| Task list row placement/insert/remove | `ui/tasks/TasksScreen.kt` (`TaskList`) | `Modifier.animateItem()` | ✅ **Yes, already** |
| Grouped task list row placement | `ui/tasks/TasksScreen.kt` (`ShapedTaskListView`) | `Modifier.animateItem()` | ✅ **Yes, already** |
| Article list row placement | `ui/articles/ArticlesScreen.kt` | `Modifier.animateItem()` | ✅ **Yes, already** |
| Task-card progress bar drain | `ui/tasks/TasksScreen.kt` (`TaskRow`) | `animateFloatAsState` | ✅ **Yes, already** |
| Articles two-pane list/detail panes (2 live + 2 in a `@Preview`) | `ui/articles/ArticlesListDetailPane.kt` | `AnimatedPane` (`material3-adaptive` 1.0.0) | ✅ **Yes, already** |
| Navigation transitions | `ui/navigation/` | `navigation-compose` defaults (nothing declared) | ✅ **Yes, already** (same animation-core path) |
| **Countdown refresh cadence** | **`ui/tasks/CountdownTicker.kt`** | **`delay(1_000)` loop — not an animation** | ❌ **No. This is the gap.** |

There is **no `AnimatedVisibility` and no `Crossfade` anywhere in the app** — confirmed, so there is
no fourth category hiding.

**How the "yes, already" column was established** (evidence, so a future reader can re-check it
rather than trust this table):

- `androidx.compose.ui:ui-android:1.10.0` contains `androidx/compose/ui/MotionDurationScale`,
  `androidx/compose/ui/platform/MotionDurationScaleImpl`, and
  `WindowRecomposer_androidKt$getAnimationScaleFlowFor$…` — i.e. the window recomposer observes
  `animator_duration_scale` and installs the scale element into the composition's context.
- `androidx.compose.animation:animation-core-android:1.10.0` reads it in
  `SuspendAnimationKt` (which backs `Animatable.animateTo`, and therefore `animateFloatAsState`) and
  in `Transition` / `SeekableTransitionState` / `InfiniteTransition`.
- `androidx.compose.foundation:foundation-android:1.10.0`'s `LazyLayoutItemAnimation` — the engine
  behind `Modifier.animateItem()` — drives its animations through `Animatable`/`animateTo`, i.e.
  through that same `SuspendAnimationKt` path.
- `material3-adaptive`'s `AnimatedPane` builds its `animateFraction` on `Transition`, same path.

**A deliberate platform exception worth writing down so nobody "fixes" it:** Compose Foundation
*pins* a fixed motion scale for a couple of interactions — `FixedMotionDurationScale` is used by
`MarqueeModifierNode`, and `ScrollableKt` installs its own `DefaultScrollMotionDurationScale`. That
is why **scrolling and fling stay normal even with animations removed**: a scroll that snapped
instantly would be unusable, not accessible. The app inherits this and must not override it.

---

## Goals

1. Someone who has turned on *Quitar animaciones* sees the Tasks screen **stop recomposing every
   second**, without losing any information the screen was showing.
2. The app has **one** answer to "should motion be reduced?", in one place, injectable and testable —
   not an ad-hoc `Settings.Global` read per screen.
3. The already-free behaviour is **documented in the repo**, so the next person to touch
   `TasksScreen`/`ArticlesScreen` does not re-implement what the framework provides.
4. `CountdownTicker`'s KDoc no longer contains an instruction the code violates — the derogation is
   written down **in the same change**, with its reasoning.
5. Nothing about the normal-motion experience changes. Zero visual diff with the setting off.

### Non-goals

Stated as goals-not-taken so approval is unambiguous: this feature does **not** improve any
animation, add any animation, add any screen, or close the accessibility backlog. See
[Out of Scope](#out-of-scope).

---

## Decisions

The prompt's own words: *"Hay argumentos para las dos; lo que no vale es no elegir."* Each decision
below is made, not surfaced as an open question.

### D1 — Product decision: obey the **system setting only**. No in-app toggle. ✅ Decided

**The question:** should Settings also carry a *"Reducir movimiento"* switch (persisted in the
existing `user_prefs` DataStore, following `UserPreferencesRepository`'s pattern), on top of obeying
the system?

**The case for an in-app toggle**
- Someone may want less motion **only here** and not across their whole phone — the system switch is
  all-or-nothing across every app.
- It is a *visible* accessibility affordance: a user who never found the Android setting can still
  find ours. Discoverability is real value.
- The plumbing is cheap and already exists: one `booleanPreferencesKey`, one `SettingsSectionCard`
  row, one string pair. The project has done this four times already (theme, reminders, lead time,
  dynamic color).

**The case for system-only**
- The system setting is the **platform-blessed, per-user, cross-app** answer. Every other app the
  person uses already respects it; duplicating it locally makes *this* app the odd one that needs a
  second opt-in.
- It creates a **second source of truth that can disagree**. "System says reduce, app switch says
  don't" has no good answer — and, critically, **the app cannot honour that combination even if it
  wanted to.** Once the framework installs a zero `MotionDurationScale`, `animateItem` and
  `animateFloatAsState` are *already* snapping; an app-level "allow motion" cannot bring them back
  without wrapping every animation in a custom scale override. So an in-app switch could only ever
  be **additive** (reduce more, never less) — a control that is silently inert in exactly the case a
  user would reach for it. That is a worse control than no control.
- The DoD's "extend, don't duplicate" rule points the same way: this is duplicating a platform
  control, not extending our own.

**Decision: obey the system setting only.** No Settings row, no DataStore key, no new string
resources in this feature. The asymmetry argument is the deciding one — an in-app switch that cannot
turn motion *back on* is a broken affordance, and shipping it would mean shipping a lie.

**But build for the reversal.** The shared criterion (D3) is a single `Flow<Boolean>` behind an
interface. If we later decide the *discoverability* argument wins, adding an app-level override is a
one-line change inside that one implementation (`systemReduced || prefs.reduceMotion`) plus a
Settings row — no call site moves. This deferral is recorded in
[`docs/diferidos.md`](../diferidos.md) as part of this feature, so it is a written-down option rather
than a forgotten one.

### D2 — Derogating the `CountdownTicker` KDoc instruction ✅ Decided

The project wrote down an explicit instruction, and this feature breaks it. Per the prompt, that is
done **out loud**. The instruction, quoted verbatim from
[`app/src/main/java/com/neverlate/ui/tasks/CountdownTicker.kt`](../../app/src/main/java/com/neverlate/ui/tasks/CountdownTicker.kt):

> *"Feature 20b (compact remaining time): the countdown **text** dropped seconds and now only changes
> once a minute (`2h 38m`), so this 1 s cadence is no longer justified by the text. It is kept anyway
> — **do not lower it or decouple it from the text refresh** — because feature 19's task-card progress
> bar (`deadlineProgressFor` → `animateFloatAsState`) still consumes this same tick to drain
> **smoothly**; a minute-cadence tick would make the bar visibly lurch."*

And its lesson-side twin, from `tutorial/20b-tiempo-restante-localizable.md` §7:

> *"La decisión —documentada en el KDoc del `CountdownTicker`— es **mantener el tick de 1 s**, cuya
> justificación simplemente se traslada del texto a la barra."*

**Why it stops applying in this one mode — in our own words.** That instruction is not wrong; it is
*conditional*, and its condition is stated inside it. The 1 s cadence is justified **only** by the
smooth drain of the progress bar. Under reduced motion there **is no smooth drain**: the framework
has already collapsed `animateFloatAsState` to an instant snap, so the bar jumps to its new value
the moment the value changes, whatever cadence feeds it. The cost the instruction was defending
against — *"a minute-cadence tick would make the bar visibly lurch"* — has already been paid, by the
user's own explicit request. What remains is only the cost: 60 recompositions a minute of the entire
Tasks screen, buying a smoothness that no longer exists, on a screen whose user has asked for less
movement.

So the rule is not being overturned; it is being **bounded**. Restated: *the 1 s cadence is kept
whenever the progress bar can actually animate — which is always, except when the system has
switched animation off.*

**The KDoc is updated in the same change**, not edited behind the rule's back: the existing paragraph
stays (it is still the reason for the default), and gains an explicit exception naming this feature
and this spec. A reader who arrives via `git blame` finds the rule, the exception, and the reason,
in one place.

### D3 — One criterion, one owner ✅ Decided

**What is read:** `Settings.Global.getFloat(contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f`.
The real setting, never an invented heuristic.

**What is explicitly *not* used:** `LocalAccessibilityManager`. Compose's `AccessibilityManager`
interface exposes only `calculateRecommendedTimeoutMillis` — it has no animation-scale accessor at
all. This is recorded here (and in the lesson) because it is the obvious-looking wrong turn that
costs half an hour.

**Where it lives.** The prompt suggested `ui/components/` or `ui/theme/`. The spec deviates, for a
reason worth approving explicitly: **the primary consumer is `TasksViewModel`, not a Composable**,
and a ViewModel must not depend on `ui/`. So:

- **`data/settings/MotionSettings.kt`** — an interface `MotionSettings { val reduceMotion: Flow<Boolean> }`
  plus `SystemMotionSettings`, which wraps the `contentResolver` read in a `callbackFlow` registering
  a `ContentObserver` on the `ANIMATOR_DURATION_SCALE` URI (so a mid-session toggle takes effect
  without an app restart) and emits the current value first. Declared as an **interface** for the
  same stated reason `UserPreferencesRepository` is: a JVM test drives a fake, with no Android
  runtime. Bound in Hilt alongside the other storage-shaped providers.
- **`ui/theme/ReduceMotion.kt`** — a thin `@Composable fun rememberReduceMotion(): Boolean` (and/or a
  `LocalReduceMotion` provided once in `NeverLateTheme`) **delegating to the same `MotionSettings`**,
  for any future UI-side read.

One criterion, two doorways, and a hard rule for reviewers: **no other file in the app calls
`Settings.Global` for this.** Today only the ViewModel doorway is actually consumed; the Compose one
ships because `NeverLateTheme` is where a future animation-level opt-out would naturally read it, and
adding it later would mean touching the theme again.

### D4 — Reduced cadence: one minute, clamped to the next expiry ✅ Decided

**Base cadence under reduced motion: 60 000 ms.** Not "stop the ticker" — a frozen countdown would
violate this feature's own central visual criterion. One minute is the natural floor because it is
exactly the granularity of the countdown *text* (feature 20b: `2h 38m` changes once a minute), so the
text and the bar step together and can never look like they disagree.

Implemented as two named constants in `CountdownTicker.kt` (`TICK_INTERVAL_MILLIS = 1_000L`,
`REDUCED_MOTION_TICK_INTERVAL_MILLIS = 60_000L`) rather than magic numbers at the call site.

**The clamp, and why it is not over-engineering.** `TasksViewModel` does more with the tick than
render: `autoPauseTimedOut` reacts to a running task hitting zero by **writing to the database**
(pausing the timer). At a flat 60 s cadence that write — and the "Tiempo agotado" state on screen —
could land up to 59 seconds late. That would make reduced motion silently degrade something
**functional**, not merely visual, which is precisely what an accessibility mode must never do.

So the reduced interval is `min(REDUCED_MOTION_TICK_INTERVAL_MILLIS, millis until the soonest running
task's expiry)`, never below `TICK_INTERVAL_MILLIS`. The screen sits still for a minute at a time,
then wakes exactly at the moment a task actually runs out. The computation is a pure function of
(the task list, `now`), so it is JVM-unit-testable with no device.

Note for completeness: the `times-up-alert` feature's `ReminderKind.TIME_UP` alarm fires on time via
`AlarmManager` regardless of any of this — the notification is never late. The clamp exists so the
**on-screen** state and the auto-pause write agree with that notification instead of trailing it.

### D5 — `AnimatedPane`: verified, no change needed ✅ Decided

All four `AnimatedPane` usages in `ArticlesListDetailPane.kt` were checked individually rather than
assumed as a group:

| # | Call site | Live at runtime? | Verdict |
|---|---|---|---|
| 1 | `listPane` of `ArticlesListDetailPane` | Yes (expanded width only) | Honours the setting |
| 2 | `detailPane` of `ArticlesListDetailPane` | Yes (expanded width only) | Honours the setting |
| 3 | `listPane` of `ArticlesListDetailPanePlaceholderPreview` | **No** — `@Preview` only | N/A |
| 4 | `detailPane` of `ArticlesListDetailPanePlaceholderPreview` | **No** — `@Preview` only | N/A |

`AnimatedPane` (adaptive-layout 1.0.0) animates via `Transition`, which reads `MotionDurationScale`
from the composition's coroutine context — the same mechanism as everything else in the table above.
**No code change.** The result is recorded as a KDoc line in `ArticlesListDetailPane.kt` so the
question is answered permanently instead of re-opened.

---

## User Stories

### US-1 — The Tasks screen stops twitching

> **As** someone who turned on *Quitar animaciones* because moving screens break my focus,
> **I want** the task list to sit still between meaningful updates,
> **so that** looking at my tasks does not cost me the attention I opened the app to protect.

**Acceptance criteria**
1. With `animator_duration_scale = 0`, `TasksScreen` recomposes on a **one-minute** cadence, not a
   one-second one, while at least one timer is running.
2. With the setting at its default (`1`), the cadence is **unchanged at 1 s** — byte-for-byte the
   pre-feature behaviour.
3. Toggling the system setting while the app is in the foreground changes the cadence **without an
   app restart** (the `ContentObserver` path in D3).
4. The existing `flatMapLatest` "no task running → no ticker at all" optimisation still holds in both
   modes; reduced motion never *starts* a ticker that would otherwise be stopped.

### US-2 — Nothing is lost in exchange

> **As** the same person,
> **I want** the countdown, the progress bar, and the list to keep telling me the truth,
> **so that** asking for less motion never costs me information or correctness.

**Acceptance criteria**
1. The countdown text remains accurate: it is always derived from the wall clock at read time
   (`computeRemainingMillis`), never accumulated from ticks, so a coarser cadence can only make a
   refresh *late*, never *wrong*.
2. A running task that reaches zero is auto-paused and shown as "Tiempo agotado" **within one second
   of actually reaching zero**, not up to a minute later (D4's clamp).
3. Adding, completing, deleting or reordering a task updates the list **immediately** — those come
   from Room's `Flow`, not from the ticker, and are unaffected by cadence in either mode.
4. The progress bar still reflects elapsed time; it advances in visible steps rather than a glide.

### US-3 — One place to ask the question

> **As** a developer adding the next screen,
> **I want** a single documented way to ask "should motion be reduced?",
> **so that** I neither re-implement the framework's job nor scatter `Settings.Global` reads.

**Acceptance criteria**
1. Exactly one file in `app/src/main/` references `Settings.Global.ANIMATOR_DURATION_SCALE`
   (`data/settings/MotionSettings.kt`). Grep-checkable.
2. `MotionSettings` is an interface with an injectable Hilt binding and a test fake, following
   `UserPreferencesRepository`'s stated rationale.
3. `ui/theme/ReduceMotion.kt` exposes the Compose-side accessor and delegates to the same source —
   it does **not** perform its own read.

### US-4 — The knowledge survives

> **As** the developer who touches `TasksScreen` in six months,
> **I want** the repo to tell me what the platform already handles,
> **so that** I do not "add reduce-motion support" that already exists, or delete the ticker
> exception as dead weight.

**Acceptance criteria**
1. `CountdownTicker.kt`'s KDoc carries both the original 20b rule **and** its bounded exception,
   naming this spec. The original wording is not deleted.
2. `ArticlesListDetailPane.kt` states the `AnimatedPane` verdict (D5).
3. `docs/arquitectura.md` gains this feature's decision entry — specifically the "a periodic
   recomposition is not an animation" distinction, which is the non-obvious part.
4. `docs/mockups/README.md`'s 🟡 accessibility row reflects what this feature delivered and what it
   left open.

---

## Acceptance Criteria (consolidated)

### Behavioural

- [ ] `animator_duration_scale = 0` → ticker interval is 60 000 ms (clamped per D4); default scale →
      1 000 ms.
- [ ] The clamp fires: with a task expiring in 12 s under reduced motion, the next tick lands at
      ~12 s, not ~60 s.
- [ ] The clamped interval never drops below 1 000 ms, whatever the task list.
- [ ] Changing the system setting with the app foregrounded takes effect without a restart.
- [ ] No task running → no ticker at all, in both modes (unchanged).
- [ ] Countdown values are wall-clock-derived, so a late tick is late, never incorrect.
- [ ] Auto-pause at zero happens within 1 s of zero in both modes.
- [ ] Room-driven list changes (add/complete/delete/reorder/filter/sort/group) are immediate in both
      modes.
- [ ] With the setting off, there is **no observable change of any kind** vs. `master`.

### Visual (see [Visual & UX Design](#visual--ux-design))

- [ ] Under reduced motion, rows still **appear, disappear and reorder** — by cut, not by slide.
      Nothing vanishes silently and nothing is left rendered after deletion.
- [ ] Under reduced motion, the progress bar still **fills as time passes** — in discrete steps.
      A bar at 40 % must not sit at 40 % while the countdown says 10 minutes have gone by.
- [ ] Nothing on the Tasks or Articles screens **freezes**: every element that changed before still
      changes, only less continuously.
- [ ] The two-pane Articles layout still switches panes correctly with animations off (cut, not
      slide) — no blank pane, no stuck pane.
- [ ] No new visual element, color, spacing or component is introduced by this feature.
- [ ] Touch targets ≥ 48dp and largest-font-scale reflow are unchanged (nothing in this feature
      touches layout) — re-verified, not assumed.

### Definition of Done items this feature touches

Folded in per `CLAUDE.md` → *Definition of Done*:

- [ ] **Tests pass.** New JVM unit tests for the cadence rule (`./gradlew :app:testDebugUnitTest`
      green). See [Testing](#testing).
- [ ] **Migrations** — N/A, no Room schema change. Stated so the reviewer does not look for one.
- [ ] **Contract** — N/A, no wire change. Same.
- [ ] **Security** — no new permission; `Settings.Global` reads need none; no secret involved.
- [ ] **Accessibility & i18n** — this feature *is* the accessibility item. **No new user-facing
      string** is added (D1 removed the only candidate: the Settings row), so `values/` and
      `values-en/` stay untouched and in sync by construction. Verified explicitly rather than
      skipped.
- [ ] **Every state designed** — no new state is introduced; loading/empty/error on Tasks and
      Articles are unchanged and re-verified under reduced motion.
- [ ] **Visual ACs pass** and `docs/mockups/README.md` is updated.
- [ ] **Docs match reality** — KDocs (D2, D5), `docs/arquitectura.md`, `docs/diferidos.md` (D1's
      deferred toggle), `docs/mockups/README.md`.

---

## Visual & UX Design

### Mockup slice

This feature touches exactly one row of [`docs/mockups/README.md`](../mockups/README.md):

> | Accessibility pass (content descriptions, ≥48dp, dynamic font) | 18 · navegación y accesibilidad | 🟡 | … |

— the table's **only** partial row. This feature **narrows it; it does not close it.** The row stays
🟡. Its note gains: *"Feature `reduce-motion` (2026-08-17): motion sensitivity covered — the app now
respects Ajustes → Accesibilidad → Quitar animaciones end to end (framework-provided for
`animateItem`/`animateFloatAsState`/`AnimatedPane`; app-provided for the countdown recomposition
cadence). Still open: the per-screen sweep of content descriptions, ≥48dp targets and largest-font
reflow on the screens 18/20 did not reach."*

There is **no new mockup slice claimed**: the master mockup
([`docs/mockups/rediseno-ux-ui.html`](../mockups/rediseno-ux-ui.html)) is a static visual document —
it has no motion layer at all, and therefore nothing to say about reduced motion. This feature adds
**zero new visible chrome**: no color, no component, no spacing, no icon, no string. With the setting
off it is a no-op; with it on, the change is *the absence of movement*.

### Deferred visual polish, and to where

Named explicitly, because deferring silently is what `CLAUDE.md` forbids:

- **The remaining accessibility sweep** stays in the 🟡 row above — the per-screen review of content
  descriptions, touch targets and font-scale reflow on Stats, Task Edit, Login/Register, Onboarding
  and the Articles detail. Not this feature.
- **A reduced-motion *alternative* treatment** (e.g. a cross-fade where a slide used to be, or a
  motion-free "just changed" highlight so a cut is still noticeable) is **out of scope** — it means
  *designing new motion*, which this feature explicitly does not do. If it is ever wanted, it starts
  as its own spec; recorded in [`docs/diferidos.md`](../diferidos.md).
- **An in-app Settings toggle** — deferred by [D1](#d1--product-decision-obey-the-system-setting-only-no-in-app-toggle-decided),
  recorded in `docs/diferidos.md` with the asymmetry argument attached so a future revisit starts
  from the reasoning, not from scratch.

### Theme tokens and components

Nothing new is styled, so the "extend, don't duplicate" rule shows up here as a **prohibition rather
than a reuse list**: this feature must not introduce a single new color, dimension, typography style
or component. Existing `NeverLateExtras` urgency colors, `MessageState`, `ReadableWidthContainer`,
`BrandIconChip` and `brandedTopAppBarColors()` are all untouched. The only file that gains anything
in `ui/theme/` is the new `ReduceMotion.kt` accessor (D3), which carries no visual tokens.

### Visual acceptance criteria

Concrete and checkable, verified with *Quitar animaciones* **on**, on a real screen:

1. **Rows still come and go.** Deleting a task removes its row instantly (cut). Creating one inserts
   it instantly. Marking one done moves it to the bottom instantly. In no case does a row linger,
   double-render, or leave a gap.
2. **The bar still moves.** With a task whose window is ~10 minutes, the progress bar is visibly
   further along after two minutes than it was at the start. Stepping is expected; standing still is
   a failure.
3. **The countdown still counts.** The `2h 38m` text still decrements at its own minute granularity,
   identically to normal mode.
4. **Nothing freezes.** After leaving the Tasks screen open for three minutes under reduced motion,
   every time-dependent element (countdown, bar, urgency color, section grouping) reflects the
   current time — the screen is quiet, not stalled.
5. **The screen is measurably quieter.** With Layout Inspector / recomposition counts (or a temporary
   log in a debug build), `TaskRow` recomposition count over a 60-second window drops from ~60 to ~1
   per row.
6. **Two panes still switch.** On an expanded-width device, tapping an article swaps the detail pane
   content immediately, with no blank or stuck pane.
7. **No regression with the setting off.** Side-by-side against `master`: identical smooth drain,
   identical list animations, identical everything.
8. **Unchanged a11y baseline.** Touch targets ≥ 48dp and layout reflow at the largest font scale are
   re-verified on Tasks and Articles (this feature does not touch layout, but the claim is checked,
   not assumed).

---

## Technical Approach

### Compose version — the number that matters

The version catalog pins `composeBom = 2024.12.01`, which by itself would mean Compose **1.7.6**.
**That is not what compiles.** Resolved from
`./gradlew :app:dependencies --configuration debugRuntimeClasspath`, every relevant Compose artifact
is upgraded past the BOM by transitive constraints:

| Artifact | Resolved version |
|---|---|
| `androidx.compose.ui:ui` / `ui-android` | **1.10.0** |
| `androidx.compose.foundation:foundation` / `foundation-android` | **1.10.0** |
| `androidx.compose.animation:animation` / `animation-core` | **1.10.0** |
| `androidx.compose.runtime:runtime` | **1.10.0** |
| `androidx.compose.material3:material3` | 1.3.1 |
| `androidx.compose.material3.adaptive:adaptive*` | 1.0.0 |

(The dependency report shows explicit `1.7.6 -> 1.10.0` upgrade arrows, so this is resolution, not a
coincidence of declaration.)

**Why the spec insists on the real number:** `MotionDurationScale` was introduced in **Compose UI
1.2**. At the resolved **1.10.0** the mechanism is present and mature — verified by inspecting the
actual resolved `.aar`s, not by version arithmetic. Citing "2024.12.01" would have been citing a
string that does not describe the classes on the classpath, and the claim would age badly the moment
someone checked.

**Note for the implementer:** the BOM-vs-resolved gap is a pre-existing condition of this project,
not something this feature creates or should fix. Do not "align" the catalog as a side effect —
that is its own change, with its own risk.

### Implementation sketch

Non-binding — the `mobile-engineer` agent owns the details — but the shape is fixed by the decisions
above.

1. **`data/settings/MotionSettings.kt`** (new)
   - `interface MotionSettings { val reduceMotion: Flow<Boolean> }`
   - `class SystemMotionSettings(context: Context) : MotionSettings` — `callbackFlow` +
     `ContentObserver` on `Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE)`;
     emits current value on collection; `distinctUntilChanged()`; unregisters in `awaitClose`.
   - Hilt binding beside the other storage-shaped providers (`StorageModule`, or a sibling —
     implementer's call, following the existing module map).

2. **`ui/tasks/CountdownTicker.kt`** (modified)
   - Add `TICK_INTERVAL_MILLIS` / `REDUCED_MOTION_TICK_INTERVAL_MILLIS` constants.
   - Add the pure cadence function — e.g. `tickIntervalFor(reduceMotion: Boolean, tasks: List<Task>, now: Long): Long`
     implementing D4's clamp. **Pure, no Android dependency, JVM-testable** — it is the whole reason
     the rule is expressible as a unit test.
   - **Update the KDoc per D2**: keep the 20b paragraph, append the bounded exception naming this
     spec.

3. **`ui/tasks/TasksViewModel.kt`** (modified)
   - Inject `MotionSettings`.
   - `combine` the reduce-motion flag into the existing `flatMapLatest` that already chooses between
     `countdownTicker()` and `flowOf(tasks)`, so the interval is recomputed whenever either the task
     list or the flag changes. **The existing "nothing running → no ticker" branch is preserved
     exactly.**

4. **`ui/theme/ReduceMotion.kt`** (new) — `rememberReduceMotion()` / `LocalReduceMotion`, delegating
   to `MotionSettings`. No independent read.

5. **`ui/articles/ArticlesListDetailPane.kt`** (modified — **KDoc only**) — record D5's verdict.

6. **`ui/tasks/TasksScreen.kt`** — expected to need **no change**. Listed in the prompt's file list as
   a candidate; if it turns out untouched, that is the correct outcome, not an oversight.

7. **Docs** — `docs/arquitectura.md` (decision entry), `docs/mockups/README.md` (🟡 row),
   `docs/diferidos.md` (D1's toggle + the reduced-motion alternative treatment).

### Testing

Owned by `qa-engineer`.

**JVM unit tests** (`app/src/test/`) — the point of keeping the cadence rule pure:
- `reduceMotion = false` → 1 000 ms, regardless of the task list.
- `reduceMotion = true`, no imminent expiry → 60 000 ms.
- `reduceMotion = true`, soonest expiry in 12 s → ~12 000 ms (the clamp).
- `reduceMotion = true`, expiry already passed / 0 → never below 1 000 ms (no busy loop, no negative
  delay).
- A `MotionSettings` fake flipping mid-stream → the ViewModel's emitted cadence follows, without
  restarting the Room subscription.

**Instrumented** — only what genuinely cannot be proven on the JVM: that `SystemMotionSettings`
actually observes a `Settings.Global` change (may require a test-only injected value if writing that
global is not permitted from an instrumented test — if it is not feasible, say so and cover it in
manual verification instead of faking a green test).

**Manual verification instructions** (for the Design review step — per this project's standing rule,
**the developer does not launch the emulator; the final in-app check is the user's**):
1. *Ajustes → Accesibilidad → Quitar animaciones* **on**.
2. Tasks screen, one running timer: confirm the screen is visually still between minute boundaries,
   the countdown still decrements, and the bar steps forward.
3. Delete / create / complete a task: rows cut in and out; nothing lingers.
4. Let a timer run to zero: "Tiempo agotado" appears at zero, not up to a minute later.
5. Expanded-width device: tap articles, panes swap cleanly.
6. Turn the setting **off** without leaving the app: smooth drain returns immediately.

---

## Out of Scope

Deliberately excluded. Each is a "no" someone might otherwise assume is a "yes".

- **Redesigning or adding any animation.** No new transitions, no reduced-motion *alternative*
  treatment (cross-fade, highlight, etc.). This feature only changes how often a screen recomposes.
- **The rest of the accessibility review.** The 🟡 row covers far more ground than motion —
  per-screen content descriptions, ≥48dp sweeps, largest-font reflow on Stats/Task Edit/auth/
  onboarding. Untouched; the row stays 🟡.
- **An in-app "reduce motion" toggle** — decided against in [D1](#d1--product-decision-obey-the-system-setting-only-no-in-app-toggle-decided).
- **The widget and the notification.** Neither is driven by `CountdownTicker` (they refresh via
  WorkManager/writes and are already minute-granular), and neither animates. Nothing to do.
- **Other accessibility system settings** — high-contrast text, bold text, colour inversion, the
  `TRANSITION_ANIMATION_SCALE` / `WINDOW_ANIMATION_SCALE` globals. Only `ANIMATOR_DURATION_SCALE` is
  in scope; the other two govern window/activity transitions the app does not declare.
- **Aligning the Compose BOM with the resolved versions.** Real, pre-existing, and its own change.
- **Backend, API contract, Room schema/migration, new dependency, new permission, new screen, new
  string resource** — none of these are touched. Restated here so the reviewer does not go looking.

---

## Dependencies

Everything required already exists; nothing blocks a start.

- **Compose UI ≥ 1.2** for `MotionDurationScale` — satisfied at the resolved **1.10.0** (verified).
- **Hilt** — already wired (`di/`, feature 13d); `MotionSettings` follows the existing binding
  pattern.
- **`UserPreferencesRepository`'s interface + fake convention** — the model `MotionSettings` copies,
  even though (per D1) it stores nothing.
- **Features 17, 19, 20b** — the animations and the cadence rule this feature reasons about; all
  shipped.
- **Feature 18b's `ArticlesListDetailPane`** — shipped; needed for D5's verification.
- **`times-up-alert`** — shipped; its `AlarmManager` alert is what keeps the on-time guarantee
  independent of D4's clamp.
- **Tutorial:** `tutorial/20b-tiempo-restante-localizable.md` must exist (it does) — lesson `20c`
  builds directly on its §7 and derogates it.
- **No human/team dependency**: no design asset, no backend deploy, no third-party account.

---

## Risks

| # | Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|---|
| **R1** | **Overselling.** The spec/lesson/commit message claims "the app now respects reduce motion", when the framework already did most of it. | High if unguarded — it is the natural way to write it | Erodes trust in this repo's docs; a future reader mis-attributes the mechanism and mis-debugs it | The [Verified inventory](#verified-inventory--what-is-already-free) is a required section of the deliverable, the commit message is scoped to the ticker, and lesson `20c` opens with "most of this was already free". |
| **R2** | **A coarser tick delays something functional**, not just visual (auto-pause at zero, timed-out styling). | Certain without a clamp | A user under reduced motion sees a stale "still running" task for up to 59 s — an accessibility mode making the app *less correct* | [D4](#d4--reduced-cadence-one-minute-clamped-to-the-next-expiry-decided)'s clamp, with a dedicated unit test. Independently, `TIME_UP` notifications fire on time via `AlarmManager`. |
| **R3** | **The clamp introduces a busy loop.** A task at or past expiry yields an interval of 0 or negative. | Medium (classic edge) | Battery drain / ANR — strictly worse than the problem being solved | Floor at `TICK_INTERVAL_MILLIS`; explicit unit tests for expiry `= 0` and expiry in the past. |
| **R4** | **The `ContentObserver` leaks or misses.** `callbackFlow` not unregistering, or the observer not firing for this key on some OEM ROM. | Low–Medium (OEM variance is real) | Leak, or the setting only taking effect after a restart | `awaitClose { unregister }`; emit the current value on collection so a missed callback degrades to "correct at screen entry" rather than "wrong forever"; manual verification step 6 explicitly toggles the setting *without leaving the app*. |
| **R5** | **The derogation is read as permission to lower the default cadence.** A future reader sees "we lowered the tick" and drops the 1 s default. | Medium | Feature 19's progress bar lurches for *every* user | D2's KDoc keeps the original rule verbatim and frames the change as a **bounded exception**, not a repeal. Lesson `20c` teaches the bounding, not the breaking. |
| **R6** | **Compose changes the mechanism.** A future Compose version alters how `MotionDurationScale` is installed, silently invalidating the inventory table. | Low | The "already free" claims quietly become false | The table records **how** each claim was verified (which artifact, which class), so it is re-checkable in minutes rather than re-researched. Any Compose upgrade PR should re-run that check. |
| **R7** | **Unmeasurable win.** "The screen stops recomposing" is hard to see; the feature could be waved through without evidence. | Medium | We ship a change nobody confirmed worked | Visual AC 5 requires an actual recomposition-count observation, not a vibe. |
| **R8** | **D1 revisited by inertia.** Someone later adds the Settings toggle without meeting the asymmetry argument. | Medium | A permanently-inert control shipped as an accessibility feature | The reasoning is written into D1 *and* `docs/diferidos.md`, so a revisit starts from the argument. |

---

## Tutorial lesson — what `20c` must teach

Bullets to guide the eventual `tutorial/20c-*.md`. **The lesson file is not written by this spec** —
it is authored after implementation, before committing, per `CLAUDE.md`.

- **`MotionDurationScale` as a `CoroutineContext.Element`.** What it means for an *accessibility
  setting* to travel through a coroutine context rather than through a `CompositionLocal` or a
  parameter — and why that is a genuinely elegant design: any animation, at any depth, running in
  that context, obeys, with no plumbing.
- **How the window's `Recomposer` installs it.** `WindowRecomposer` reads
  `Settings.Global.animator_duration_scale`, observes it, and folds a `MotionDurationScale` into the
  composition's context. Trace it concretely from `MainActivity`'s `setContent` down to
  `animateFloatAsState`, so the "magic" becomes a chain the reader can follow.
- **Animate vs. refresh — the central idea.** A **periodic recomposition is not an animation**, so no
  duration scale will ever slow it down. There is nothing being *interpolated*; there is a `delay` in
  a loop. This distinction, in its purest form, is why this feature exists at all.
- **`LocalAccessibilityManager` does not help here** — it only exposes
  `calculateRecommendedTimeoutMillis`. Teaching the *dead end* is part of the lesson: knowing where
  not to look is knowledge.
- **Investigating before implementing.** The first deliverable of this feature was the discovery that
  it was almost unnecessary. Show the inventory work — including *how* it was verified (inspecting
  the resolved `.aar`s, not trusting a version number) — as the professional habit it is.
- **Derogating an explicit previous instruction, out loud.** Quote 20b's own "keep the 1 s tick",
  explain when a written rule stops applying, and show the KDoc being **bounded rather than
  overwritten**. The transferable moral: a rule with its reason attached can be safely bounded; a
  rule without one can only be obeyed or broken.
- **Bonus, if it fits without bloating:** Compose Foundation deliberately *pins* the motion scale for
  scrolling and marquee (`FixedMotionDurationScale`) — a nice illustration that "respect the setting"
  is not the same as "apply it everywhere".

---

## Review request

Per `CLAUDE.md` → *Mandatory Workflow* step 3, **implementation does not begin until this spec is
explicitly approved.** Approval covers **behaviour, look, and the tutorial decision** — all three.

Please confirm in particular:

1. **[D1](#d1--product-decision-obey-the-system-setting-only-no-in-app-toggle-decided)** — obey the
   system setting only, **no** in-app Settings toggle (deferred with its reasoning to
   `docs/diferidos.md`). This is the product call the spec was asked not to dodge.
2. **[D4](#d4--reduced-cadence-one-minute-clamped-to-the-next-expiry-decided)** — one-minute cadence,
   **clamped** to the next expiry so auto-pause never runs late.
3. **[D3](#d3--one-criterion-one-owner-decided)** — the shared criterion lives in
   `data/settings/MotionSettings.kt` (not `ui/components/`, as the prompt suggested) because
   `TasksViewModel` is its primary consumer and must not depend on `ui/`; `ui/theme/ReduceMotion.kt`
   is a thin delegate.
4. **Tutorial number `20c`**, reading directly after the lesson it derogates.
