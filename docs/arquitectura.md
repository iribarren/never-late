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
