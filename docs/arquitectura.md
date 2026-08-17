# Arquitectura — registro de decisiones por feature

Este documento es el **historial de decisiones de arquitectura** de Never Late, feature a feature.
Vivía dentro de `CLAUDE.md` (sección *Structure*), pero creció hasta ocupar la mitad del fichero que
se carga en cada sesión, así que se movió aquí íntegro.

- **`CLAUDE.md`** describe el **estado actual** del proyecto: cómo está montado hoy y qué reglas
  aplican al trabajar en él.
- **Este fichero** describe **cómo se llegó hasta aquí**: qué introdujo cada feature, qué se decidió
  y por qué, incluidas las restricciones que siguen vigentes (pins de versiones, migraciones, etc.).
- **`tutorial/`** contiene el detalle didáctico en español de las features que llevaron lección.

Las rutas entre comillas son relativas a la raíz del repositorio. Las entradas están ordenadas por
número de feature; los sufijos de letra (`13b`, `13c`…) son features intercaladas posteriormente.

---

## Feature 04c — Task completion + statistics

**Task completion + statistics** (feature 04c, the *testing* lesson): `Task` gains a real
`completedAt: Long?` (epoch millis; `null` = pending) that **syncs** end-to-end — added to the `TaskDto`
wire shape in `docs/api/contract.md` (no new endpoint; reuses `/tasks` CRUD + `?since=` pull, PATCH via the
existing `PatchValue` omitted-vs-null mechanism) and the Postgres `tasks.completed_at` column, and rides the
existing last-write-wins-by-`updatedAt` reconcile with no logic change. A per-row `Checkbox` on the Tasks
list marks a task done (strikethrough, sorts last, no countdown/urgency) through the normal outbox/transaction
save path. This bumps `NeverLateDatabase` **3 → 4** via the project's **first real additive `Migration(3,4)`**
(`ALTER TABLE tasks ADD COLUMN completedAt INTEGER`, data-preserving — the destructive fallback would wipe
guest-mode tasks that live only on-device). No new permission or dependency.

## Feature 05b — Widget visual refresh (theming Glance)

**The widget adopts the app's identity** (feature 05b, revisiting the feature-05 widget): the four
hardcoded light-mode hex values left over from the Android Studio purple template are deleted and the
widget now resolves every color from the app's **own** `LightColorScheme`/`DarkColorScheme`. The central
decision is *why that needs a bridge at all*: `MaterialTheme.colorScheme` and `NeverLateExtras.colors`
are read off a `CompositionLocal` that only exists inside a Material 3 composition — the widget's is a
`GlanceTheme` composition instead, a different tree that Glance's `RemoteViews` translator understands —
so `colorForUrgency` and `Priority.indicatorColor()` are **not callable** from `provideGlance`. Same
color, two worlds. The chosen bridge is **`androidx.glance:glance-material3`** (version catalog, riding
the existing `glance` version ref — no new pin) with
`GlanceTheme(colors = ColorProviders(light = LightColorScheme, dark = DarkColorScheme))`; `Theme.kt`'s
two schemes go `private` → `internal` for that single second consumer. The alternative (a local file of
hand-written `ColorProvider(day, night)` pairs) was rejected because it avoids duplicate *hex values*
but still duplicates the *role→color mapping* `Theme.kt` already owns. Both mechanisms end up used, each
where it belongs: the bridge for everything Material 3 has a role for, and hand-written day/night pairs
in `ui/widget/WidgetColors.kt` for the three roles it doesn't carry across — the extended urgency colors
`calm`/`soon` (which are not in `ColorScheme` at all, by design) and `outlineVariant` (a real M3 role
that Glance's `ColorProviders` simply doesn't expose — it has only `outline`). `WidgetColors.kt` holds
the two mapping twins (`urgencyColorProvider`, `Priority.glanceIndicatorColor()`) with cross-referencing
KDoc, since a mapping duplicated across two worlds can silently drift. **Material You is deliberately
not used** in the widget (`GlanceTheme()` with no arguments would enable it): the widget always uses the
brand palette, matching the app's `dynamicColor = false` default, and following the in-app `ThemeMode`
preference would mean reading `user_prefs` from `provideGlance` — behaviour, deferred. **Rounded corners
without an API branch:** `GlanceModifier.cornerRadius` is API 31+ while `minSdk` is 24, so the shape comes
from a drawable (`widget_background.xml`, `widget_header_background.xml`, `<solid>` white) and the color
from the theme via `background(ImageProvider, colorFilter = ColorFilter.tint(...))` — one code path on
every level, with `values-v31` swapping the 16dp radius for the platform's own
`system_app_widget_background_radius`. An `SDK_INT >= 31` branch was rejected precisely because it would
leave the square rectangle on API 24–30, the devices the fix exists for. **Documented convention
exception:** `previewLayout` requires a real XML layout (`res/layout/widget_preview.xml`), which the
project otherwise bans — it is launcher metadata inflated by the system's widget picker and cannot be a
composable by construction; `previewImage` (a hand-authored vector) covers API 24–30 and tooling.
`PendingTaskRow` gains `priority` (default `Priority.NONE`, so no existing caller or test changes); the
lock-screen notification shares that type and simply **ignores** the field — its `InboxStyle` lines are
already system-truncated, and the row's rule (`pendingRowsFor`) owns *what is pending*, not what each
surface renders. Deliberately **not** in this feature: migrating the widget to Hilt (it still builds its
repository by hand in `provideGlance` — an architecture change, not a visual one), a per-row progress bar
(no vertical budget at `minHeight = 110dp` with 5 rows, and no 1s ticker in a widget that refreshes every
~15 min, so a stale bar would misinform more than stale text), and `SizeMode.Responsive`. No backend,
contract, DB-version or permission change; the only manifest-side change is the two preview attributes in
`res/xml/pending_tasks_widget_info.xml`.

## Feature 08 — Localization

**Localization** (feature 08, i18n): all user-facing text lives in string resources.
`res/values/strings.xml` is the Spanish base/fallback; `res/values-en/strings.xml` is the English
variant (selected by device language). Counts use `<plurals>` (`getQuantityString` /
`pluralStringResource`); numbers/dates are formatted per device `Locale` via `NumberFormat` /
`java.time` `DateTimeFormatter` (see `data/tasks/TaskTiming.kt`, where display formatting is
locale-aware and the deadline input round-trip is pinned to `Locale.ROOT`). `java.time` on
`minSdk = 24` is enabled by **core library desugaring** (`isCoreLibraryDesugaringEnabled` +
`coreLibraryDesugaring(libs.desugar.jdk.libs)`).

## Feature 09 — Reminders

**Reminders** (feature 09): schedules a one-shot *alerting* local notification a configurable lead
time before a task's `deadline`, firing even with the app closed, and reschedules after reboot. Pure
scheduling logic (`reminderTimeFor`, `isReminderInFuture`, `remindersToSchedule`) lives in
`domain/tasks/ReminderPlanning.kt`; the Android shells live in `ui/notification`:
`AlarmManagerReminderScheduler` (exact `setExactAndAllowWhileIdle`, graceful fallback to inexact when
`canScheduleExactAlarms()` is false), `ReminderReceiver` (posts the notification, `exported=false`),
`ReminderNotificationHelper` (a **second**, alerting `IMPORTANCE_HIGH` channel `task_reminders`,
distinct from feature 06's silent `tasks_pending`), `BootReceiver` + `BootRescheduleWorker`
(reschedule on `BOOT_COMPLETED`, delegated to WorkManager), and `ReminderSchedulingRepository` (a
second `TaskRepository` decorator, composed with `TaskSurfacesRefreshingRepository` in `MainActivity`,
that (re)schedules/cancels a task's alarm on save/delete). Reminder prefs (`remindersEnabled`,
`reminderLeadMinutes`) are stored in the same `user_prefs` DataStore.

## Feature 10 — Articles from a remote API

**Articles from a remote API** (feature 10): replaces feature 03's bundled `assets/articles.json`
with a real network fetch. `data/articles/` gains `ArticlesApi` + `ArticlesNetwork` (Retrofit +
OkHttp, `HttpLoggingInterceptor` gated to debug builds via `BuildConfig.DEBUG`, deserializing with
the existing `kotlinx.serialization` through a Retrofit converter), `ArticleDto` (the wire shape,
deliberately different from the `Article` domain model — `article_id`/`content`, no `summary` —
mapped by `ArticleDto.toDomain()`), and `ArticleEntity` + `ArticleDao` (the Room cache). The remote
source is a static JSON file at `docs/articles-api/articles.json`, served over HTTPS via GitHub
raw once pushed to `master`. `CachingArticleRepository` implements `ArticleRepository` with Room as
the **single source of truth** (`getArticles()`/`getArticleById()` always read the cache) and adds
an additive `refresh(): RefreshResult` that the ViewModels use for a *stale-while-revalidate*
strategy (show the cache immediately, then update it from the network) plus pull-to-refresh and a
retry action on failure. `ArticleEntity` lives in the same `NeverLateDatabase` as `Task`, which
bumps `version` 1 → 2 — per that database's existing `fallbackToDestructiveMigration` policy, this
wipes tasks on devices that already have data, accepted pre-release the same way earlier schema
changes were.

> Superado en parte por la feature 13c: el catálogo se sirve ahora desde el backend y la carga
> completa (`getArticles()`/`refresh()`) se sustituyó por Paging 3.

## Feature 11 — Remote DB + offline-first sync

**Remote DB + offline-first sync** (feature 11): the app gains a real backend (`backend/`, Kotlin +
Ktor + Postgres) that owns accounts and tasks; the Android app becomes an **offline-first client**.
Basic email/password **auth** issues a stateless **JWT** (no refresh in v1 — **superseded by feature
12** below), attached to every task call via an OkHttp interceptor and stored in **Keystore-backed
encrypted storage** (not the plaintext `user_prefs` DataStore). Room stays the **local single source of truth**; each mutation writes the
task row **and** a `task_outbox` change row in the same transaction. A sync engine does **push** (replay
the outbox — idempotent creates keyed by `clientRef`, tombstones for deletes) and **pull**
(`GET /tasks?since=`), reconciling with **last-write-wins by `updatedAt`** (delete wins over edit); the
pure reconciliation lives in `domain/sync/` (JVM-testable, like `ReminderPlanning.kt`). Tasks gain sync
metadata (`serverId`, `updatedAt`, `syncState`, `deleted`), bumping `NeverLateDatabase` **2 → 3**
(destructive migration per project precedent — the cache repopulates from the backend after login). The
`TaskRepository` seam is preserved: sync/auth enter behind it. See **API Contract** in `CLAUDE.md`.

The local dev backend is plaintext HTTP (`http://10.0.2.2:8080`), which `targetSdk 36` blocks by
default; a **debug-build-only** network security config (`app/src/debug/res/xml/network_security_config.xml`,
wired via `app/src/debug/AndroidManifest.xml`) scopes the cleartext exception to `10.0.2.2`/`localhost`
only — release builds get no exception at all. **Before any real deployment, the backend must be served
over HTTPS** and this debug-only exception must not be widened or copied into the release manifest.
This warning applies **doubly** after feature 12, since a refresh token is a longer-lived, higher-value
credential crossing the wire.

## Feature 12 — Refresh token + silent session renewal

**Refresh token + silent session renewal** (feature 12): the single long-lived JWT of feature 11 is
split into a **short-lived access token** (stateless JWT, ~15 min) + a **long-lived refresh token**
(~30 days, opaque, server-stateful). On a `401` the client now **renews first** and only falls back to
login if renewal fails. Client shells live in `data/sync`/`data/auth`: a new OkHttp **`Authenticator`**
(`TokenAuthenticator`, distinct from feature 11's Bearer `AuthInterceptor`) intercepts the `401`,
performs a **single-flight** refresh (a `synchronized` guard so a burst of concurrent `401`s triggers
exactly one `POST /auth/refresh`), atomically swaps both tokens in `EncryptedTokenStorage`
(`saveTokens`), and retries the original request; the refresh call goes through a **bare** OkHttp client
(no authenticator/interceptor) to avoid recursion. Both tokens live in the same Keystore-backed store;
logout best-effort-revokes the refresh server-side then clears local state unconditionally. The backend
gains **auth state** (its first): a hashed-at-rest `refresh_tokens` table (`RefreshTokenRepository` +
Postgres/InMemory impls, `RefreshTokenCrypto` for `SecureRandom` token + SHA-256 hash) with **rotation**
on every use, **revocation** on logout, and **reuse detection** scoped to a token **family/lineage**
(`revokeFamily`), closing a TOCTOU race via an atomic `markConsumedIfUnconsumed`. Access/refresh
lifetimes are env-configurable (`ACCESS_TOKEN_EXPIRY_MINUTES`, `REFRESH_TOKEN_EXPIRY_DAYS` in `Config`).
No new permissions, modules, or dependencies. See **API Contract** in `CLAUDE.md`.

## Feature 13 — Guest mode + merge on sign-in

**Guest mode + merge on sign-in** (feature 13): removes feature 11's **mandatory** auth gate — the app
is fully usable with **no account** (local-only CRUD against Room, sync inactive). `AuthState` gains a
third face, **`Guest`** (alongside `LoggedOut`/`LoggedIn`): `Guest` is the cold-start default when no
token is stored (`readInitialAuthState`), while `LoggedOut` is now **reserved for the involuntary case**
(a failed feature-12 refresh → the login gate). A guest task is already the sync engine's "orphan" shape
(`serverId == null`, a `CREATE` outbox row keyed by a stable `clientRef`), and `SyncEngine.syncNow()`
already early-returns while tokenless — so **adoption on sign-in is simply the deferred `push` finally
running**: it reuses the outbox + `clientRef` idempotency + last-write-wins from feature 11 to merge
guest tasks into the account with no loss and no duplicates, then a full pull brings the account's other
tasks down. The adoption trigger is **doubly wired**: `AppNavHost` keeps `Guest`/`LoggedIn` as
**separate `when` arms** (both render `MainAppNavHost`) so a `Guest → LoggedIn` transition recomposes a
fresh `LaunchedEffect { refreshFromServer() }` — *do not merge those arms* — **plus** an explicit
`AuthRepositoryImpl.onAuthenticated` hook (wired in `MainActivity` to `refreshFromServer`). `logout()`
(user-initiated, from Settings) now **wipes** tasks/outbox/cursor and lands in `Guest`; it shares its
`clearLocalSession()` internals with `notifyUnauthorized()`, which lands in `LoggedOut` — the wipe is
mandatory either way to avoid re-adopting/duplicating tasks or leaking them across accounts on the next
sign-in. Login/register are reachable from **Settings** while `Guest` (an optional entry, not a gate).
Product decisions (in the spec): **silent** merge, **wipe-on-logout**, single write path, **no** content
de-duplication. **No backend, contract, DB-version, permission, or dependency change** — adoption uses
the existing idempotent `POST /tasks`.

## Feature 13b — Task priority + real Room migration

**Task priority + real Room migration** (feature 13b, the *migrations* lesson): `Task` gains a
`priority: Priority` **enum** (`NONE`/`LOW`/`MEDIUM`/`HIGH`, `data/tasks/Priority.kt`) — the first column of a
type Room can't store natively, so it's persisted through a **`@TypeConverter`** added to the existing
`Converters` (stores `Enum.name` as TEXT, tolerant fallback to `NONE`). It **syncs** end-to-end like
`completedAt` (added to `TaskDto`/`docs/api/contract.md` + the Postgres `tasks.priority` column, riding the
existing LWW reconcile; client/server both coerce absent/unknown → `NONE`). This bumps `NeverLateDatabase`
**4 → 5** via a hand-written **`MIGRATION_4_5`** (`ALTER TABLE tasks ADD COLUMN priority TEXT NOT NULL DEFAULT
'NONE'`) — the project's first `NOT NULL … DEFAULT` migration (`AutoMigration` is taught by contrast, not
shipped). The feature flips `exportSchema = true` + adds the `room.schemaLocation` KSP arg, commits the
exported `app/schemas/…/{4,5}.json` (the `4.json` baseline was generated one-off since v4 shipped with export
off), and adds the **`androidx.room:room-testing`** dependency for a `MigrationTestHelper` test that proves
4 → 5 data survival (`app/src/androidTest/.../MigrationTest.kt`). UI: a `FilterChip` priority selector on the
Task Edit screen + a small token-colored dot on the task card (`ui/tasks/PriorityUi.kt`, no mockup slice —
net-new). No new permission.

## Feature 13c — Paginated articles with Paging 3

**Paginated articles with Paging 3** (feature 13c, the *pagination* lesson): the Articles list stops
fetching the whole catalog at once and loads it **page by page on scroll** via **Jetpack Paging 3**,
Room as the cache/single-source-of-truth behind a **`RemoteMediator`**. Articles also move from the
feature-10 static GitHub-raw JSON to a **real backend endpoint** — `GET /articles?page=&size=`, the
backend's **first and only public (unauthenticated) route** (guest mode, feature 13, requires articles
with no account; registered **outside** the `authenticate("auth-jwt")` block, serving a global
read-only catalog seeded at startup from `backend/src/main/resources/seed/articles.json`). Client shells
extend `data/articles/`: `ArticlesApi` gains `@Query` `page`/`size` returning `ArticlesPageDto`
(`items`/`page`/`size`/`total`, contract §7); `ArticleDao.pagingSource()` (Room-generated
`PagingSource`); a new `article_remote_keys` table (`ArticleRemoteKeys` + DAO) and an
`ArticleEntity.remoteOrder` column for stable cross-page ordering; `ArticlesRemoteMediator`
(REFRESH/APPEND/PREPEND, single-transaction writes, `endOfPaginationReached = items.size < size`,
reusing `ArticleDto.toDomain()`); and `CachingArticleRepository.articlesPager(): Flow<PagingData<Article>>`.
The old whole-list `getArticles()`/`refresh()`/`RefreshResult` + `ArticlesUiState` SWR loop is
**removed** — `ArticlesScreen` now uses `collectAsLazyPagingItems()` + `loadState` (pull-to-refresh
spinner, bottom append spinner, inline append-retry, full-screen `MessageState` for refresh-error/empty),
and `getArticleById` is kept for Article Detail. This bumps `NeverLateDatabase` **5 → 6** via an additive
`MIGRATION_5_6` (new table + `remoteOrder INTEGER NOT NULL DEFAULT 0`; additive because the shared DB
holds guest-only tasks), with a committed `6.json` and a `MigrationTestHelper` test. New dependency:
`androidx.paging` (runtime + compose) + `androidx.room:room-paging` in the version catalog. No new
permission.

## Feature 13d — Dependency injection with Hilt

**Dependency injection with Hilt** (feature 13d, a behaviour-preserving refactor, not a product
feature): retires the manual DI used since feature 02 — the `ui/navigation/AppViewModelFactory`
`ViewModelProvider.Factory` (deleted) and the object-construction block that used to fill
`MainActivity.onCreate` (building `NeverLateDatabase`, the token storage, the network clients,
`SyncEngine`, and the `TaskRepository` decorator chain by hand) — with **Hilt**. `NeverLateApplication`
(`@HiltAndroidApp`) is the new `Application` class, registered via `android:name` in the manifest;
`MainActivity` is `@AndroidEntryPoint` and now only `@Inject`s the three things it still touches
directly (`UserPreferencesRepository`, the assembled `TaskRepository`, and the concrete
`AuthRepositoryImpl`, for the guest-mode `onAuthenticated` hook) — every imperative startup side
effect (notification channel, the two periodic `WorkManager` jobs, the lock-screen notification
refresh) still runs there unchanged, only the *construction* moved out. The new `di/` package holds
`SingletonComponent`-scoped modules: `DatabaseModule` (`NeverLateDatabase` + DAOs), `NetworkModule`
(the three Retrofit factories + `SyncEngine`), `StorageModule` (`TokenStorage`, `UserPreferencesRepository`),
and `RepositoryModule` — the crux of the migration — which provides `AuthRepository`, `ArticleRepository`,
`ReminderScheduler`, and, disambiguated with the qualifiers in `di/Qualifiers.kt` (`@RoomRepo`/
`@OutboxRepo`/`@ReminderRepo`), the exact same **four-layer `TaskRepository` decorator chain** in the
exact same order as before: `TaskSurfacesRefreshingRepository` (unqualified, what the app injects) ->
`ReminderSchedulingRepository` -> `OutboxTaskRepository` -> `RoomTaskRepository`. All nine `ViewModel`s
are now `@HiltViewModel` with an `@Inject constructor`, obtained via `hiltViewModel()` in every
`*Route` composable; `ArticleDetailViewModel`/`TaskEditViewModel` read their `articleId`/`taskId`
navigation argument from an injected `SavedStateHandle` instead of a factory parameter (a missing
`articleId` still throws — a missing `taskId` is still a valid "create new task" signal). New
dependencies, both in the version catalog: `com.google.dagger:hilt-android`/`hilt-compiler` (the
latter via **KSP**, alongside Room's, no `kapt`) and `androidx.hilt:hilt-navigation-compose` — both
pinned below their newest release (Hilt `2.58`, not `2.59+`; `hilt-navigation-compose` `1.2.0`, not
`1.3.0+`) because newer releases of each require **AGP 9**, and this project is still on AGP 8.13.2;
revisit both pins whenever the project upgrades AGP. No backend, contract, DB-version, permission,
or UI/behavioural change of any kind.

## Feature 18 — Bottom navigation bar + accessibility review

**Bottom navigation bar + accessibility review** (feature 18): retires the `HomeScreen` hub —
**Tasks/Articles/Settings** are now peer top-level destinations reached via a persistent Material 3
`NavigationBar`, not rows on a Home screen. **Tasks is the landing destination** (onboarded users and
the widget's `openTasksOnStart` both resolve there; Home's route/strings/`HomeViewModel` are removed).
The `Scaffold`/`NavigationBar` live **inside `MainAppNavHost`** (`ui/navigation/AppNavHost.kt`), never in
`AuthGateNavHost`, so the bar never shows on the login gate; feature 13's separate `Guest`/`LoggedIn`
`when` arms are untouched. The bar's **visibility is route-gated**: shown only while the current
destination is one of the three top-level routes, hidden on Article Detail/Task Edit/Login/Register-
from-Settings, which render full-height instead. The selected tab is derived reactively from
`navController.currentBackStackEntryAsState()`/`destination.hierarchy` (never a separate `remember`ed
index), and tab taps use the standard `popUpTo(graph.findStartDestination().id) { saveState = true }` +
`launchSingleTop` + `restoreState` idiom. `TasksScreen`/`ArticlesScreen`/`SettingsScreen` (and their
`*Route` wrappers) now take a **nullable** `onBack: (() -> Unit)? = null` — `null` (no back arrow) when
reached as a bottom-bar tab, a real callback when reached as a secondary screen. Settings' logout
button now opens an `AlertDialog` confirmation (mirroring `TasksScreen`'s `DeleteTaskDialog`) before
calling `SettingsViewModel.logout()`, since feature 13 made logout wipe local tasks. The feature also
folds in a cross-cutting **accessibility pass** (`docs/conceptos-pendientes.md` §7): coherent
`contentDescription`s on the new bar's icons, and `Modifier.minimumInteractiveComponentSize()` on
`ui/components/MessageState`'s action `Button` (Material 3's default 40dp button height is below the
48dp touch-target guideline). **No backend, contract, DB-version, permission, or dependency change.**

## Feature 18b — Adaptive layouts for large screens

**Adaptive layouts for large screens** (feature 18b, the *adaptive* half feature 18 left out — closes
`docs/conceptos-pendientes.md` §7's tablet/adaptive item): makes the app **width-aware** via
**`WindowSizeClass`** (compact/medium/expanded), as a presentation layer **over the unchanged feature-18
graph** — a phone user sees no change. `MainActivity` computes `calculateWindowSizeClass(this)` once and
threads `widthSizeClass` down through `AppNavHost` → `MainAppNavHost` (the auth gate ignores it). Inside
`MainAppNavHost` (never `AppNavHost` — the guest-mode `Guest`/`LoggedIn` `when` arms stay untouched) the
layout branches: **compact** keeps the bottom `NavigationBar`; **medium/expanded** swap to a leading-edge
`NavigationRail` (`MainNavigationRail`, reusing the same `bottomNavItems`, the same reactive back-stack
selected-tab derivation, and the same tab-switch idiom — now factored into a shared
`NavHostController.navigateToTopLevelRoute` extension). The nav graph itself is extracted into one private
`MainNavGraph` shared by both branches (the core teaching point: an adaptive layer above **one** graph,
never a duplicated graph); chrome stays route-gated by `TOP_LEVEL_ROUTES`. **Articles** gets a two-pane
**list-detail** at expanded width via **`ListDetailPaneScaffold`** (`ui/articles/ArticlesListDetailPane`):
list left (the unchanged `ArticlesRoute` + full Paging 3 pipeline), detail right, tap updates the right
pane in place, `MessageState` placeholder when nothing selected — compact/medium keep the single-pane push
`ArticleDetail` route. The right pane reuses the **rendering** (`ArticleDetailBody`, extracted from
`ArticleDetailScreen`) but loads via `articleRepository.getArticleById` directly rather than
`ArticleDetailViewModel`/`hiltViewModel()` (no back-stack `SavedStateHandle` for an in-pane selection);
`MainActivity` therefore also `@Inject`s `ArticleRepository`, threaded through to the pane. Tasks/Settings
get only the rail + a max-640dp `ReadableWidthContainer` (two-pane for them is deferred). All feature-18
a11y is preserved (≥48dp targets, content descriptions, large-font reflow). New dependencies in the
version catalog: `androidx.compose.material3:material3-window-size-class` (BOM-managed, no explicit
version; aliased `material3-windowsizeclass` since Gradle rejects an alias ending in `class`) and
`androidx.compose.material3.adaptive:{adaptive,adaptive-layout,adaptive-navigation}` pinned to **`1.0.0`**
(not the newer line) for the same AGP-8.13.2 reason as the Hilt pins — revisit on a Compose BOM upgrade.
No backend, contract, DB-version, or permission change.

---

## Feature `widget-hilt-color-token` — Widget joins Hilt, urgency/priority → color mapping unified

**Closes two debts feature 05b wrote down in its own code** (`docs/specs/2026-08-17-widget-hilt-color-token.md`):
the widget built its own `TaskRepository` by hand, and the urgency/priority → color decision was
duplicated across Compose and Glance. Behaviour-preserving refactor, **zero visual change** — see the
updated 05b row in `docs/mockups/README.md`.

**D1/D2 — how and what the widget injects.** `PendingTasksWidget.provideGlance` can't get an
`@Inject` field (a `GlanceAppWidget` is never constructed by Hilt — it's built directly by
`PendingTasksWidgetReceiver`, `TaskSurfacesRefreshingRepository`, and `TaskSurfacesRefreshWorker`, none
of which Hilt intercepts), so `app/src/main/java/com/neverlate/di/WidgetEntryPoint.kt` is a new
`@EntryPoint @InstallIn(SingletonComponent::class)` interface, resolved from inside `provideGlance` via
`EntryPointAccessors.fromApplication(context.applicationContext, ...)`. That resolution point matters:
because it happens *inside* the widget's own composition function rather than at construction time, all
three call sites keep constructing `PendingTasksWidget()` exactly as before. The rejected alternative was
`@HiltWorker`/`HiltWorkerFactory` — it would add `androidx.hilt:hilt-work` to the catalog, make
`NeverLateApplication` a `Configuration.Provider`, and disable WorkManager's default initializer, all to
fix the *workers*, not the widget, which is the actual problem here.

The entry point deliberately exposes the **`@ReminderRepo`** layer of the `TaskRepository` decorator
chain (`RepositoryModule`'s `RoomRepo -> OutboxRepo -> ReminderRepo -> unqualified`), not the unqualified
binding. The unqualified binding *is* `TaskSurfacesRefreshingRepository`, whose `refreshSurfaces()` calls
`PendingTasksWidget().updateAll(context)` after every write — reading through it today is harmless
(`observeTasks()` passes through unchanged), but a future write from the widget (row actions,
`widget-adaptable-progreso.md`) would reenter itself: write -> `refreshSurfaces()` -> `updateAll` ->
`provideGlance` -> write -> ... `@ReminderRepo` is the outermost layer that does not loop back into the
widget, so it is a structural lock against that cascade, not a convention someone has to remember. Full
reasoning lives in `WidgetEntryPoint`'s KDoc; the entry point is intentionally reusable (not yet reused)
by the other five hand-wired consumers (`TaskSurfacesRefreshWorker`, `BootRescheduleWorker`, `SyncWorker`,
`TasksNotificationService`, `ReminderReceiver`) — migrating them is out of scope here.

**D3 — the shared mapping is a role name, never a `Color`/`ColorProvider`.** Feature 05b's boundary
(Compose reads `MaterialTheme.colorScheme`/`NeverLateExtras`; Glance reads `GlanceTheme.colors` + its own
hand-written day/night pairs, because a Material 3 `CompositionLocal` doesn't exist inside a Glance
composition) was correct and had to survive. What was duplicated wasn't the color, it was the *decision*
of which role an `UrgencyLevel`/`Priority` maps to — kept in sync only by a KDoc warning in
`WidgetColors.kt`. `domain/tasks/ColorRole.kt` extracts that decision once: `enum class ColorRole { Calm,
Soon, Error, Primary, Secondary, Tertiary }` plus two pure, JVM-testable functions,
`urgencyColorRole(UrgencyLevel): ColorRole` (four levels onto **three** roles — `Urgent`/`Overdue` share
`Error`, unchanged from before) and `priorityColorRole(Priority): ColorRole?` (`null` for `NONE`, no
marker). The four call sites (`colorForUrgency`, `Priority.indicatorColor()`, `urgencyColorProvider`,
`Priority.glanceIndicatorColor()`) become thin resolvers that only translate role -> color in their own
world; none of them repeats the `when`. The hand-written day/night pairs in `WidgetColors.kt`
(`CalmColor`/`SoonColor`/`dividerColor`) are **not** part of this duplication and are untouched — they
exist because `glance-material3`'s bridge simply doesn't carry `calm`/`soon`/`outlineVariant`. Placed in
`domain/tasks/` (next to `Urgency.kt`), not `ui/theme/` or `ui/widget/`: the decision is tasks-domain
vocabulary, and the widget is the minor of the two consumers.

No backend, contract, DB-version, or permission change; no new Gradle dependency.

## Feature `times-up-alert` — Real alert when a task's time runs out

**Closes feature 09's biggest remaining gap** (`docs/specs/2026-08-17-times-up-alert.md`): a task's
countdown reaching zero produced no sound, no vibration, and no notification unless the Tasks screen
happened to be composed — and a duration-only task (no `deadline`) got no alarm, ever. This feature adds
a second one-shot alarm per task, `ReminderKind.TIME_UP`, fired at the wall-clock instant a task actually
runs out of time, reusing feature 09's alerting `task_reminders` channel and `AlarmManager`/
`ReminderReceiver`/`ReminderNotificationHelper`/`BootRescheduleWorker` machinery as-is.

**D1 — the `PendingIntent` identity bug, fixed before anything else could be built on it.** Feature 09's
`requestCodeFor(taskId) = taskId.toInt()` was shared, unmodified, by the alarm `PendingIntent` and the
notification id — one task, one `Int`. A second alarm kind built the same way (same request code, same
absent `Intent` action) would be `filterEquals`-identical to the first, so `AlarmManager`'s
`FLAG_UPDATE_CURRENT` would silently *replace* the lead-time reminder with the time-up one instead of
adding a second, independent alarm — no crash, no log, one alarm quietly gone. Fixed two independent ways
at once (belt and braces): a new `ui/notification/ReminderKind.kt` enum (`LEAD_TIME`/`TIME_UP`, each
carrying a `slot` and a distinct `Intent.action`), `requestCodeFor(taskId, kind) = taskId.toInt() * 2 +
kind.slot` (the single-arg overload is **deleted**, not defaulted, so the compiler forces every call site
to choose), and `intent.action = kind.action` set on the alarm `Intent` itself. The scheme threads through
all five places that must agree on one task+kind's identity: `AlarmManagerReminderScheduler`
(`schedule`/`cancel` now take a `ReminderKind`), `ReminderNotificationHelper.notificationIdFor` (base
offset `10_000 + requestCodeFor(...)`, closing a latent collision with `TASKS_NOTIFICATION_ID` = 1001),
`ReminderSchedulingRepository`, `SettingsViewModel`'s cancel-all-reminders loop, and
`BootRescheduleWorker`. Missing any one of them is exactly the failure mode US-5's tests target: alarms
that work until a reboot, or that survive turning reminders off.

**D3 — one alarm slot per task, not two.** A task can have both a running timer and a deadline, so two
"time is up" instants can exist; the decision is `min(timerEndsAt, deadline)` over whichever is non-null
(`domain/tasks/TimeUpPlanning.kt`'s `timeUpInstantFor`). This is usually a no-op — `startTimer` sets
`timerEndsAt = now + computeRemainingMillis(...)`, which for a never-started deadline task *is* the
deadline — and only diverges when a deadline task was paused and resumed, pushing `timerEndsAt` past the
deadline; the deadline is the earlier, truer commitment there (the same rule `TaskTiming.kt`'s countdown
already applies).

**D9 — `startTimer`/`pauseTimer` stop being pass-throughs, and this also fixes an existing bug.**
`ReminderSchedulingRepository` previously forwarded these two methods to its delegate with no reminder
consequence at all — the "easy-to-forget part of the feature: it has no UI surface", per the spec. Both
now re-read the task after the delegate's write and reschedule `TIME_UP` (cancel first, then schedule only
if warranted). The same "cancel first, then maybe schedule" rule was extended, for **both** kinds, to
require `completedAt == null && !deleted` — which fixes a pre-existing bug where a completed-but-not-yet-
due task still received its lead-time reminder.

**D8 — a delivery-time guard, not just a scheduling-time one.** `ReminderReceiver` re-reads the task fresh
and drops the notification if it is gone, deleted, completed, or (for `TIME_UP` only) stale —
`computeRemainingMillis(task, now) > 60_000`, meaning the alarm belongs to a superseded plan (timer
restarted, deadline pushed back). This is the safety net for the window between scheduling and firing that
cancellation at write time cannot close (process killed before the cancel runs, etc.).

**D10 — the reschedule worker also runs on cold start.** `BootRescheduleWorker` previously only followed
`BOOT_COMPLETED`; installing this feature's update would otherwise arm no `TIME_UP` alarms until each task
was next edited or the phone rebooted. `NeverLateApplication.onCreate` now enqueues the same worker
unconditionally (idempotent via `FLAG_UPDATE_CURRENT` + stable request codes; past-due tasks are dropped
by the never-retroactive future-check, same as boot). The class is **not** renamed — `BootRescheduleWorker`
is named in the shipped `tutorial/09-*.md` lesson — only its KDoc changed to state boot and cold-start are
both triggers. One caveat this enqueue call had to account for: plenty of JVM/Robolectric unit tests
instantiate `NeverLateApplication` (via the manifest's `android:name`) without WorkManager's own
ContentProvider ever having run, so `WorkManager.getInstance()` throws `IllegalStateException` in that
environment; the enqueue call is wrapped in a narrow `try/catch` for exactly that one failure mode rather
than making every unrelated test carry WorkManager test-init boilerplate.

No backend, contract, DB-version, or permission change; no new Gradle dependency; no new notification
channel (D11) or string resource — reuses feature 09's `task_reminders` channel and `tasks_time_up`.

## Feature `priority-sorting` — Priority sorts, filters, groups, and reaches every surface

**Closes what 13b left decorative** (`docs/specs/2026-08-17-priority-sorting.md`): priority could be
picked in the edit form and painted as a dot on the task card, and nothing else — it didn't sort,
filter, or group the list, and neither the notification nor the stats screen knew it existed. Full
decision set is D1–D8 in the spec; this entry records the three worth a second opinion before coding
(D3, D4, D7) plus D1's ordering fix, matching this file's "record the *why*" convention.

**D1 — `Priority` gains an explicit `rank`, and the "nothing relies on ordinal" KDoc stays true.**
`Priority`'s own KDoc has always promised that comparisons never rely on `Enum.ordinal` — the
`@TypeConverter` persists `name`, so reordering/inserting a constant can never corrupt a stored row.
Sorting by priority would have made that promise false the moment it compared `ordinal` for
convenience. The fix is a constructor property, `enum class Priority(val rank: Int) { NONE(0), LOW(1),
MEDIUM(2), HIGH(3) }` — a plain Kotlin constructor parameter, invisible to both kotlinx.serialization
(still encodes by `name`) and the Room converter (still stores `name`), so this is a no-op for
persistence and the wire format: no contract change, no migration, no schema version bump.

**D3 — the sort precedence stack is written down as four ordered steps, and priority is never an
implicit secondary key.** Sorting by the selected field now always resolves through the same four
steps: (1) completed-last (unchanged from 04c), (2) the selected field in the selected direction, (3)
deadline ascending nulls-last, (4) title A→Z case-insensitive — the last two guarantee a **total
order** so identical data never reshuffles between renders (`Modifier.animateItem()` depends on that
stability). `Priority`'s own branch sorts by `rank` but **inverts** the numeric direction: ascending
means "most important first" (`HIGH` → `NONE`), chosen for consistency of *meaning* — ascending already
means "soonest deadline"/"A→Z" for the other two fields, i.e. "what to act on first is on top";
`NONE`-first-when-ascending would be numerically tidy and behaviourally backwards. Deliberately **not**
decided the other way: priority never boosts order when `Deadline`/`Title` is the selected field — a
user who asks for chronological order gets chronological order, with no silent "…but important ones
first" making the sort control lie about what it does.

**D4 — `ShapedTaskList.Grouped` is generalized to a `List<TaskSection>` keyed by a sealed
`TaskGroupKey`, not duplicated per axis.** Adding a second grouping axis (priority, alongside 03b's
urgency) could have been a second `Grouped`-shaped variant (`GroupedByUrgency`/`GroupedByPriority`).
That was rejected: the renderer's `when` over `ShapedTaskList` only ever asks "does this list have
section headers?" — one question, two answers, unchanged by a third axis — so splitting the variant
per axis would duplicate the entire grouped `LazyColumn` body (headers, `items(key = ...)`, row wiring,
`animateItem()`) once per axis. Instead, `sealed interface TaskGroupKey { ByUrgency(level); ByPriority
(priority) }` plus `data class TaskSection(val key: TaskGroupKey, val tasks: List<TaskUiModel>)` push
the *only* thing that actually differs per axis — the header label — down into
`SectionHeader(key: TaskGroupKey)`'s own exhaustive `when`, which still fails to compile for an
unhandled axis. `List` over `Map`: display order (`URGENCY_DISPLAY_ORDER`/`PRIORITY_DISPLAY_ORDER`) is
already a decision this file makes explicitly, so a `List` makes "these are in display order" a
property of the type rather than of how a `Map` happened to be built. Stated tradeoff: the
*screen-level* `when` no longer forces acknowledgement of a new axis, only the header-level one — judged
acceptable because every axis in view differs only in its label, against the alternative of duplicating
the list body wholesale. A completed task's *urgency* section stays forced to `Calm` (unchanged from
04c — a done task has no meaningful countdown), but its *priority* section is its **real** priority
(D6) — priority stays true after completion, so a finished HIGH task belongs in Alta, sunk below
pending HIGH tasks by the completed-last sort key rather than needing a second "completed" bucket.

**D7 — the notification gets a trailing priority marker, partially rebutting 05b's own D2.** 05b
declined to render priority on the lock screen: `InboxStyle` lines are already truncated by the system,
and a third token risks pushing *which task* and *how long is left* toward the ellipsis. That reasoning
is correct about the mechanism and wrong about the conclusion once position is accounted for —
truncation eats the **tail** of a line, so a marker placed *after* the remaining-time label cannot
displace either fact; it is itself the first thing sacrificed on a line that doesn't fit. 05b's premise
also changed: it argued priority "restates importance in a second, weaker channel" while the
notification is already most-urgent-first — true while priority was decorative everywhere, false now
that it sorts/filters/groups the app the user actually interacts with. Implementation is one new format
string, `notification_row_format_priority = "%1$s — %2$s %3$s"`, used **only** for a non-`NONE`-priority
row; a `NONE` row keeps the untouched `notification_row_format`, so every existing byte-identical-line
test stays a valid regression guard. What 05b's D2 got right and this feature preserves: compact marker
only (never the word "Alta"), and row ordering (`pendingRowsFor`, most-urgent-first) is untouched —
priority informs a passive surface, it does not get to reorder one.

**D8 — one marker mapping, app-wide.** `widget_priority_marker_low/medium/high` (05b, widget-only
naming) is renamed to `priority_marker_low/medium/high` in both locales, and
`Priority.markerRes(): Int?` (`ui/tasks/PriorityUi.kt`, `null` for `NONE`) becomes the single source
three call sites resolve against — the task card (replacing, not supplementing, its chromatic-only dot;
US-6), the widget (`ui/widget/PendingTasksWidget.kt`'s local `when` deleted in favor of the shared
function), and the notification (D7 above) — the same "one shared non-`Color` decision, thin resolvers
per world" shape D3 of `widget-hilt-color-token` already established for the color mapping.

No backend, contract, DB-version, or permission change (priority already crosses the wire and persists
since 13b/`MIGRATION_4_5`); no new Gradle dependency.

---

## Feature `reduce-motion` — Respecting "reduce motion"

**Most of this was already free** (`docs/specs/2026-08-17-reduce-motion.md`): Compose's window
`Recomposer` installs `MotionDurationScale` — a `CoroutineContext.Element` sourced from
`Settings.Global.animator_duration_scale` and kept live via a `ContentObserver` — into the
composition's coroutine context, and every animation running inside that context (`animateItem()`,
`animateFloatAsState`, `AnimatedPane`'s `Transition`) already collapses to an instant snap when the
scale is `0`. Verified against the *resolved* runtime classpath (Compose UI 1.10.0, not the BOM
string), not assumed. The feature's real work is the one gap the platform cannot cover, plus writing
the "already free" inventory down so nobody re-implements it.

**D — a periodic recomposition is not an animation, and that distinction is the whole feature.**
`CountdownTicker`'s `delay(1_000)` loop drives the Tasks screen's once-a-second refresh so the
task-card progress bar (`animateFloatAsState`) can drain smoothly. No `MotionDurationScale` slows
that loop down, because there is nothing being *interpolated* for it to scale — it is a plain
coroutine `delay`, not an animation. So under reduced motion the 1 s cadence keeps firing at full
cost while the smooth drain it exists to protect no longer exists (the bar already snaps instantly):
a screen burning a recomposition a second, and the battery behind it, to buy nothing, on exactly the
screen whose user asked for less movement. The fix is `tickIntervalFor` (`ui/tasks/CountdownTicker.kt`,
pure and JVM-testable): 1 000 ms under normal motion, 60 000 ms under reduced motion, **clamped**
down to the soonest running task's expiry so the functional side of the tick — `autoPauseTimedOut`'s
database write at zero — never lands up to 59 s late. `CountdownTicker`'s existing KDoc (feature 20b's
"keep the 1 s tick" instruction) is **bounded, not overwritten**: the original paragraph stays
verbatim, with an appended exception naming this spec — a rule with its reason attached can be
safely bounded; one without a reason attached can only be obeyed or broken.

**One criterion, one owner.** `data/settings/MotionSettings.kt` is the single file allowed to read
`Settings.Global.ANIMATOR_DURATION_SCALE` — an interface (same rationale as
`UserPreferencesRepository`: a JVM test drives a fake) plus `SystemMotionSettings`, a `callbackFlow`
+ `ContentObserver` wrapper, bound in Hilt alongside the other storage-shaped providers in
`StorageModule`. `TasksViewModel` (the primary, in fact only-today, consumer) injects it directly;
`ui/theme/ReduceMotion.kt`'s `rememberReduceMotion()` is a thin Compose-side doorway onto the same
binding, reached via a `MotionSettingsEntryPoint` (`di/`) the same way `WidgetEntryPoint` reaches Hilt
from `PendingTasksWidget` — a Composable has no constructor for `@Inject` to hook into. Deliberately
unconsumed by any screen today; it exists so a future Compose-level motion decision has one place to
read from instead of a second `Settings.Global` call being added ad hoc.

**Decided against: an in-app "reduce motion" toggle.** The system setting is the platform-blessed,
per-user, cross-app answer, and — the deciding argument — an app-level "allow motion" could never
override a `0` system scale (the framework has already snapped every animation by the time app code
would run), so an in-app switch could only ever be *additive*: a control that is silently inert in
exactly the case a user would reach for it. Recorded in `docs/diferidos.md` with the full reasoning
so a future revisit starts from the argument, not from scratch.

No backend, contract, DB-version, or permission change (`Settings.Global` reads need none); no new
Gradle dependency; no new string resource (the only candidate — a Settings row — was the toggle
decided against above).

---

## Transversal — Permisos y manifest

**Permissions** (declared in `AndroidManifest.xml`): `POST_NOTIFICATIONS` (feature 06; runtime
permission on Android 13+, requested from Compose), plus `FOREGROUND_SERVICE` /
`FOREGROUND_SERVICE_SPECIAL_USE` for the notification's foreground service
(`TasksNotificationService`, `foregroundServiceType="specialUse"`). Feature 09 adds
`SCHEDULE_EXACT_ALARM` (exact alarms on API 31+, checked at runtime via `canScheduleExactAlarms()`
with graceful fallback to inexact; `USE_EXACT_ALARM` is deliberately **not** declared) and
`RECEIVE_BOOT_COMPLETED` (reschedule reminders after reboot), plus two `<receiver>`s: `ReminderReceiver`
(`exported="false"`) and `BootReceiver` (`exported="true"`, `BOOT_COMPLETED` filter). Feature 10 adds
`INTERNET` (a normal permission, no runtime request) for the articles API. Feature 11 adds
`ACCESS_NETWORK_STATE` (also a normal permission, no runtime request) for the sync engine's
connectivity-aware WorkManager job. A later bugfix adds Auto Backup rules
(`android:dataExtractionRules="@xml/data_extraction_rules"` + `android:fullBackupContent="@xml/backup_rules"`)
that **exclude the Keystore-sealed `auth_secure_prefs` file** from cloud backup/device transfer: its
hardware-bound key can't follow it, so a restored copy only throws `AEADBadTagException` on launch —
`EncryptedTokenStorage` now also recovers from that by clearing and recreating the store (see
`tutorial/12b-keystore-recuperacion.md`).
