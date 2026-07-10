# Feature 13c — Lista de artículos paginada con Paging 3

- **Feature number / lesson:** 13c (interleaved between feature 13 and 14 — the letter-suffix rule, no
  renumbering of shipped lessons). Tutorial lesson: `tutorial/13c-paginacion.md` (Spanish).
- **Roadmap mapping:** `docs/conceptos-pendientes.md` §6 *Datos: migraciones y paginación* — the
  "Paginación con Paging 3 (con Room y/o red)" slot, ⬜ pendiente. This spec fills it.
- **Suggested branch:** `feature/articles-paging`
- **Status:** DRAFT — awaiting user approval (behaviour **and** look). Do not implement before sign-off.
- **Author:** project-manager-docs · **Date:** 2026-07-10

---

## Overview

Today the Articles list is loaded **all at once**: `ArticlesApi.getArticles()` does a single
`@GET("articles.json")` that returns the **entire** `List<ArticleDto>` from a static JSON file
(`docs/articles-api/articles.json`) served over HTTPS via GitHub raw (feature 10).
`CachingArticleRepository` mirrors that whole list into a Room table (`articles`) as the single source
of truth, and `ArticlesViewModel` runs a bespoke *stale-while-revalidate* (SWR) loop
(`loadThenRefresh`) on top of it.

This feature replaces "fetch the whole list" with **incremental, page-by-page loading using Jetpack
Paging 3**, keeping Room as the cache / single source of truth via a **`RemoteMediator`**. As the user
scrolls, the next page is fetched from the backend, appended to the Room cache, and streamed to the UI
through a `Flow<PagingData<Article>>`. The list shows a **refresh** spinner on first load / pull-to-
refresh, an **append** spinner at the bottom while the next page loads, and an inline **retry** on
error — all driven by Paging's `LoadState`.

Two structural changes come with it, both called out for explicit sign-off below:

1. **A real paginated backend endpoint is introduced.** Articles are currently *not* served by the
   Ktor backend at all — they come from the GitHub-raw static file. This feature adds a **new public
   articles domain** to `backend/` exposing `GET /articles?page=&size=`, seeded from the same catalog
   content. The API contract (`docs/api/contract.md`) is updated **first**.
2. **The endpoint is PUBLIC (no auth).** Because of guest mode (feature 13), Articles must be usable
   with **no account**, exactly as the current unauthenticated GitHub-raw fetch is. So `/articles`
   must **not** require a Bearer token, unlike every `/tasks*` route.

### Teaching goal (Tutorial Methodology)

Lesson `13c-paginacion.md` introduces, building on the network (10/11) and Room (04/11/13b) already
taught:

- **`PagingSource` vs `RemoteMediator`** — who owns "next page from the DB" vs "next page from the
  network into the DB", and why a network+DB app needs both.
- **`Pager` / `PagingConfig`** — `pageSize`, `prefetchDistance`, `initialLoadSize`.
- **`PagingData` as a `Flow`** — `.cachedIn(viewModelScope)` and why paging state is a stream, not a
  one-shot `List`.
- **`collectAsLazyPagingItems()`** in Compose, with **`itemKey` / `itemContentType`**.
- **`loadState`** (refresh / append / prepend) for spinners + retry, contrasted with feature 10's
  hand-rolled `ArticlesUiState`.
- **Network + Room as SSOT in paging** — `RemoteMediator` writes pages into Room; the `PagingSource`
  reads only from Room. Offline-first falls out naturally.
- **The pagination contract** — page/size query params and the paged response shape, and how they map
  to `RemoteMediator.MediatorResult` / `endOfPaginationReached`.

---

## Goals

- Load articles **page by page** as the user scrolls, instead of the whole catalog in one request.
- Keep **Room as the single source of truth** and keep the app **usable offline** once at least one
  page has been cached (a guest with no account must still be able to read cached articles).
- Introduce a **real, public, paginated `/articles` endpoint** in the Ktor backend, defined in
  `docs/api/contract.md` first.
- Replace the bespoke SWR loop with Paging's own `LoadState`-driven **refresh / append / retry**,
  reusing the feature-17 `MessageState` component for the empty/error surfaces where it fits.
- Deliver a clean, teachable slice: extend the existing articles stack (`ArticlesApi`,
  `CachingArticleRepository`, `ArticleDao`, `ArticleEntity`, `ArticleDto.toDomain()`), do not fork it.

### Non-goals for this lesson

Paging *search/filter*, paged *tasks*, bi-directional (prepend) paging, and image loading (Coil is its
own lesson, 10b) — see *Out of Scope*.

---

## User Stories

### US-1 — Scroll loads more articles
**As a** reader of the Articles screen, **I want** more articles to load automatically as I scroll to
the bottom, **so that** I never wait for the full catalog up front and the list stays responsive.

**Acceptance criteria**
- Opening Articles loads and shows the **first page** without loading the whole catalog.
- Scrolling near the bottom triggers the **next page** load automatically (driven by
  `PagingConfig.prefetchDistance`), which appends below the existing rows without losing scroll
  position.
- When the **last page** has been reached, no further append is attempted (`endOfPaginationReached`),
  and no perpetual bottom spinner is shown.
- Article rows keep their existing look (Card + `ListItem` + `BrandIconChip`), tracked by a stable key
  (`itemKey = article.id`).

### US-2 — Read cached articles offline
**As a** user with no connectivity (including a **guest** with no account), **I want** to still see the
articles I have already loaded, **so that** the app is useful offline.

**Acceptance criteria**
- Pages already fetched are read from the **Room cache** (the `PagingSource`), with **no network call**,
  when reopening the screen offline.
- The first-ever load with an empty cache **and** no network shows the **error + retry** surface, not a
  blank screen (distinguishable from a genuinely empty catalog).
- No auth token is required to read articles at any point (guest mode).

### US-3 — Feedback while loading, and retry on failure
**As a** reader, **I want** clear feedback while a page is loading and an obvious way to retry a failed
load, **so that** transient network errors don't leave me stuck.

**Acceptance criteria**
- **Initial / refresh** load shows a loading state (reusing pull-to-refresh chrome), not a flash of the
  empty state.
- An **append** (load-more) in progress shows a small spinner **at the bottom** of the list.
- A **refresh** failure with no cache shows the shared `MessageState` error with a **Retry** action
  that calls `lazyPagingItems.refresh()`.
- An **append** failure shows a compact inline **Retry** affordance at the bottom of the list that
  calls `lazyPagingItems.retry()`, without discarding the pages already shown.
- A genuinely **empty** catalog (server returns zero items, load succeeded) shows the existing empty
  `MessageState`, distinct from the error state.

### US-4 — Pull to refresh re-syncs from page 1
**As a** reader, **I want** to pull down to refresh the list, **so that** I can pick up newly published
articles.

**Acceptance criteria**
- Pull-to-refresh triggers a **`RemoteMediator` REFRESH** (re-fetch page 1), which reconciles the Room
  cache and re-streams `PagingData` from the top.
- The refresh spinner reflects the Paging **refresh** `LoadState` (in flight → spinner; settled →
  hidden), preserving the current pull-to-refresh gesture.

### US-5 — Backend serves articles, paginated and public
**As a** client (app), **I want** a public paginated `/articles` endpoint, **so that** articles can be
fetched incrementally without an account.

**Acceptance criteria**
- `GET /articles?page=&size=` returns a **paged response** (shape in *Technical Approach* / contract),
  **without** an `Authorization` header (no `401` when unauthenticated).
- Query params are validated and clamped server-side (sane `size` bounds, `page` ≥ first page); invalid
  params return the standard `400 validation_error` envelope.
- The endpoint returns items in a **stable, deterministic order** across pages (so paging never skips
  or duplicates a row).
- The catalog is **seeded** on the backend from the existing article content (see *Seed data*).

---

## Acceptance Criteria (behavioural, consolidated)

1. First screen open fetches **page 1 only**; subsequent pages load on scroll via `prefetchDistance`.
2. The **`PagingSource` reads exclusively from Room**; only the **`RemoteMediator`** touches the
   network, writing fetched pages (and page-tracking keys) into Room **in a single transaction**.
3. `RemoteMediator` handles the three `LoadType`s: **REFRESH** (page 1, clears + repopulates cache and
   remote keys transactionally), **APPEND** (next page from the last remote key), **PREPEND**
   (immediately returns `endOfPaginationReached = true` — no upward paging in v1).
4. `endOfPaginationReached` is derived from the response (recommended: `items.size < size`, cross-checked
   against `total`) so the last page stops appends.
5. `LoadState.Refresh` drives the top/pull-to-refresh spinner; `LoadState.Append` drives the bottom
   spinner; `LoadState.Refresh is Error` (with empty list) drives the full-screen error+retry;
   `LoadState.Append is Error` drives the inline bottom retry.
6. `ArticleDto` stays on the wire unchanged (`article_id` / `content`) and is mapped with the **existing**
   `ArticleDto.toDomain()` before being written as `ArticleEntity`.
7. The **Article Detail** screen keeps working: it still reads a single cached article via
   `getArticleById(id)` from the same Room cache the mediator fills (no paging on the detail screen).
8. `GET /articles?page=&size=` requires **no auth** and returns the paged shape; app and backend agree
   with `docs/api/contract.md`, which is updated **first**.
9. Room schema version is bumped **5 → 6** with a **real, data-preserving migration** (see *Room
   migration impact*), a committed `6.json` schema, and a `MigrationTestHelper` test proving survival.
10. New dependency `androidx.paging` (runtime + compose) is added to `gradle/libs.versions.toml` (no
    hardcoded versions).

---

## Technical Approach

High-level only — this is a spec, not an implementation. Sub-projects touched: **`app/`** (client) and
**`backend/`** (server), plus the shared **contract** and **tutorial**.

### 1. Contract first (`docs/api/contract.md`)

Add a new section **"7. Articles endpoint (public)"** (informative for the existing task/auth sections;
they are unchanged). Define:

- **`GET /articles?page=<int>&size=<int>`** — **public, no `Authorization` header**. Explicitly note
  it is the **first unauthenticated resource** and *why* (guest mode / feature 13). Register it in
  Ktor **outside** the `authenticate("auth-jwt")` block.
- **Recommended response shape — page/offset with a total:**

  ```json
  {
    "items": [ { "article_id": "pomodoro", "title": "…", "content": "…" }, … ],
    "page": 0,
    "size": 20,
    "total": 42
  }
  ```

  - `items` are **`ArticleDto`** (unchanged wire shape — `article_id` / `content`, no `summary`).
  - `page` is the **zero-based** page index echoed back; `size` the page size actually used (after
    server clamping); `total` the full catalog count.
- **Recommendation & justification (page/offset over cursor):** for a **small, static, append-only
  catalog** a page/offset scheme is the right teaching choice. It maps cleanly onto a Paging 3
  `RemoteMediator`: the `RemoteKeys` row per article stores an integer `prevKey`/`nextKey`, `APPEND`
  loads `lastKey + 1`, and `endOfPaginationReached` is a trivial `items.size < size` (with `total`
  available as a cross-check for the lesson). A **cursor** (`nextCursor`) is the better tool for large,
  frequently-mutating feeds where offsets drift, but it adds opaque-cursor machinery that would distract
  from the core Paging concepts and buys nothing for a fixed catalog. **Decision needed:** confirm
  page/offset (recommended) vs cursor.
- **Ordering guarantee:** the endpoint must return a **stable, total order** (recommended: a server-side
  `position` / insertion order), so pages never overlap or gap. State this in the contract.
- **Errors:** reuse the existing envelope — `400 validation_error` for bad `page`/`size`.

### 2. Backend (`backend/`) — new public articles domain

Mirror the existing `tasks` package structure (Model / Repository interface + Postgres & InMemory impls
/ Service / Routes), so the lesson can point at a familiar shape:

- `articles/ArticleModels.kt` — `ArticleDto` (matching the wire shape) + the paged response DTO
  (`items/page/size/total`).
- `articles/ArticleRepository.kt` (+ `PostgresArticleRepository`, `InMemoryArticleRepository`) — a
  `page(offset, limit)` + `count()` read API over an `articles` table (columns incl. a `position` for
  stable ordering). InMemory impl for tests, matching the tasks precedent.
- `articles/ArticleService.kt` — clamps `size` to sane bounds, computes `total`, assembles the page.
- `articles/ArticleRoutes.kt` — `get("/articles")` registered in `Application.configureApp` **outside**
  `authenticate("auth-jwt")` (public).
- `db/` — add the `articles` table to `initSchema` and a **seed** step (idempotent insert-if-empty).

### 3. Seed data (backend)

Repurpose the existing catalog content as the backend's seed: on startup, if the `articles` table is
empty, insert the catalog (title/content/position). **Recommendation:** keep the article bodies in one
place — see *`docs/articles-api/` disposition* below — and have the backend seed from that content so
the app and backend never diverge on wording. The seed must assign a **stable `position`** so ordering
is deterministic.

### 4. Client (`app/`) — extend the articles stack

- **`ArticlesApi`** — add `@GET("articles")` returning the paged response DTO with `@Query("page")` /
  `@Query("size")`. The existing `getArticles()` (whole-list) is **removed** (nothing else uses it once
  paging lands). The client's Retrofit base URL for articles moves from the GitHub-raw host to the
  backend base URL (`BuildConfig.BACKEND_BASE_URL`) — see *Static file disposition*.
- **`ArticleEntity` / `ArticleDao`** — the DAO gains a `PagingSource<Int, ArticleEntity>` read
  (`@Query("SELECT * FROM articles ORDER BY <stable order> …")`) plus transactional insert/clear used by
  the mediator. `ArticleEntity` gains a **stable ordering column** (e.g. `remoteOrder: Int`) so the
  `PagingSource` returns pages in server order (SQLite has no inherent row order) — this is the crux of
  the migration.
- **`ArticleRemoteKeys` + `ArticleRemoteKeysDao`** — the new page-tracking table
  (`articleId` → `prevKey`/`nextKey`), standard Paging `RemoteMediator` bookkeeping.
- **`ArticlesRemoteMediator`** — `RemoteMediator<Int, ArticleEntity>`; on REFRESH clears articles +
  remote keys and loads page 1 (single Room transaction), on APPEND loads `lastKey + 1`, PREPEND ends
  immediately; maps `ArticleDto.toDomain().toEntity()` (reusing the existing mappers). `initialize()`
  can `LAUNCH_INITIAL_REFRESH` so opening online re-syncs page 1.
- **`CachingArticleRepository`** — provides the `Pager` (`Flow<PagingData<Article>>` via `pagingSource`
  + `remoteMediator`, `.map { it.toDomain() }`) and **keeps `getArticleById(id)`** for the detail
  screen. See *Old non-paged path* for what is removed.
- **`ArticlesViewModel`** — exposes `val articles: Flow<PagingData<Article>> = pager.flow
  .cachedIn(viewModelScope)`. The `ArticlesUiState` sealed hierarchy, `isRefreshing`, and
  `loadThenRefresh` are **removed** — Paging's `LoadState` replaces them.
- **`ArticlesScreen`** — `collectAsLazyPagingItems()`, `LazyColumn` with `items(count, key = itemKey,
  contentType = itemContentType)`, a bottom append-spinner/retry item, and `loadState`-driven refresh
  and error surfaces. **Pull-to-refresh** maps `isRefreshing = loadState.refresh is LoadState.Loading`
  and `onRefresh = { lazyPagingItems.refresh() }`.

### 5. Room migration impact (explicit)

Adding a `RemoteKeys` table **and** an ordering column to `articles` changes the schema, so
`NeverLateDatabase` bumps **`version = 5` → `6`**. Following the precedent set in **feature 13b**
(`exportSchema = true` is already on; `room-testing` is already a dependency; `4.json`/`5.json` are
committed):

- Add a hand-written **`MIGRATION_5_6`** registered in `addMigrations(...)` alongside `MIGRATION_3_4` /
  `MIGRATION_4_5`:
  - `CREATE TABLE article_remote_keys (...)` (the new page-tracking table).
  - `ALTER TABLE articles ADD COLUMN remoteOrder INTEGER NOT NULL DEFAULT 0` (the stable order column;
    a `NOT NULL … DEFAULT`, same style as `MIGRATION_4_5`).
- **Data-preservation nuance:** unlike tasks, the `articles` table is a **pure cache** that repopulates
  from the network on the next refresh. A destructive fallback would *technically* be tolerable for
  articles alone — **but** `articles` shares `NeverLateDatabase` with `tasks`/`task_outbox`, and since
  feature 13 (guest mode) those rows can live **only on-device**. A destructive migration on this
  shared DB would wipe guest tasks. So the migration **must be additive/data-preserving** (it only adds
  a table + a defaulted column; existing `articles` rows survive but will re-sort/refresh on next load).
  This is the same reasoning feature 04c used to ship the first real migration.
- Commit the exported **`6.json`** and add a `MigrationTestHelper` test (**`MigrationTest.kt`**, the file
  13b created) proving `5 → 6` survives existing rows — reusing the `room-testing` dependency already in
  the catalog. **Decision needed:** confirm additive migration (recommended) over destructive fallback.

### 6. New dependency

Add `androidx.paging` to `gradle/libs.versions.toml`:

- `androidx-paging-runtime` (`androidx.paging:paging-runtime`)
- `androidx-paging-compose` (`androidx.paging:paging-compose`)

Pin a single `paging` version in `[versions]` (propose the latest stable 3.3.x — the exact value to be
confirmed against the catalog at implementation time; **do not hardcode in `build.gradle.kts`**). Room's
Paging integration (`androidx.room:room-paging`) may also be needed for `PagingSource` DAO return types;
if so, add it under the existing `room` version ref.

### 7. `docs/articles-api/` disposition (decision needed)

Options, with a recommendation:

- **(A) Recommended — repurpose as backend seed, retire as the client's live source.** Keep
  `docs/articles-api/articles.json` in the repo as the **canonical catalog content**, and have the
  backend **seed** its `articles` table from it. The **client no longer fetches GitHub raw** — it talks
  to the backend `/articles`. Add a one-line note at the top of the file (or its folder README, if any)
  marking it as *backend seed data, no longer served to the app directly*. This keeps one source of
  wording and avoids drift.
- **(B) Remove it entirely** and inline the seed content in the backend. Simpler tree, but throws away
  the existing content file and its history.
- **(C) Keep serving it too** (leave the GitHub-raw fetch as a fallback). **Not recommended** — two live
  sources of the same data is exactly the drift the contract rule forbids.

**Recommendation: (A).** Confirm.

### 8. Old non-paged path — what is removed vs kept (decision needed)

- **`CachingArticleRepository.refresh()` + `RefreshResult`** and the ViewModel's `loadThenRefresh` SWR
  loop are **replaced** by Paging's own refresh: `RemoteMediator(LoadType.REFRESH)` re-fetches page 1,
  and `LoadState.Refresh` + `lazyPagingItems.refresh()` drive the UI. **Recommendation:** remove
  `refresh()`, `RefreshResult`, `ArticleRepository.refresh` from the interface, and the whole
  `ArticlesUiState`/`isRefreshing` machinery — Paging is now the single mechanism, so keeping both would
  be two refresh systems for one screen (the "extend, don't duplicate" rule cuts against it).
- **`getArticles(): List<Article>`** (whole-list read) and `ArticlesApi.getArticles()` are **removed** —
  the list is now a `PagingData` stream.
- **`getArticleById(id)` is KEPT** — the Article Detail screen still reads a single cached article from
  Room; the mediator fills that same cache, so detail keeps working offline.
- **`ArticleRow` / `BrandIconChip` styling is KEPT** unchanged — only the *loading mechanics* change,
  not the row look.

**Decision needed:** confirm removing the SWR/`RefreshResult` path in favour of Paging's refresh.

---

## Visual & UX Design

### Master-mockup slice

- **Slice:** the **Articles list** (`docs/mockups/rediseno-ux-ui.html` — the articles list rows). Per
  the tracking table (`docs/mockups/README.md`), the **row look is already ✅ shipped** across features
  10 / 17 / 20 (Card + `ListItem` + `BrandIconChip`, list `animateItem()`, branded top bar). This
  feature **does not restyle the rows** — it changes only how the list is *loaded* (pagination + load
  states). No new mockup element is claimed; the mockup has **no dedicated paging/loading element**, so
  this is a mechanics change under an already-✅ slice.
- **Reused surfaces (feature 17):** `ui/components/MessageState` for the full-screen **empty** and
  **refresh-error+retry** states, exactly as the current screen already uses it.
- **Deferred / not in scope (stated, not silent):**
  - No **skeleton/shimmer** placeholder rows for the initial load — a plain refresh spinner (existing
    pull-to-refresh chrome) is used. Shimmer can be a future polish row.
  - No **article header images** — that is Coil / lesson **10b**, explicitly separate.
  - The bottom **append spinner / inline append-retry** row is **net-new UI** (the mockup has no
    load-more element); it reuses theme tokens and a small `CircularProgressIndicator` + text
    button — no mockup slice claimed for it.

### Visual acceptance criteria

- **Rows unchanged:** article rows render identically to the current screen (Card + `ListItem` +
  `BrandIconChip`), using existing `ui/theme/` tokens — no restyle.
- **Refresh:** the pull-to-refresh spinner (existing `PullToRefreshBox`) shows while
  `LoadState.Refresh is Loading` and hides when settled; no flash of the empty state on first load.
- **Append:** while `LoadState.Append is Loading`, a centered `CircularProgressIndicator` shows as the
  **last item** of the list; it disappears at `endOfPaginationReached`.
- **Append error:** an inline row with a localized message + **Retry** text button
  (`minimumInteractiveComponentSize()`, **≥ 48dp** touch target) appears at the bottom and calls
  `lazyPagingItems.retry()`; existing rows stay visible.
- **Refresh error (empty cache):** full-screen `MessageState` (error icon + localized message + Retry
  action ≥ 48dp) calling `lazyPagingItems.refresh()`.
- **Empty (loaded, zero items):** full-screen `MessageState` with the existing empty icon/message,
  visually distinct from the error state.
- **Accessibility:** all spinners/retry controls carry coherent `contentDescription`s (or are marked
  decorative where a sibling text carries meaning); the **layout reflows without truncation/overlap at
  the largest font scale**; touch targets **≥ 48dp**.
- **Theme:** all states render correctly in light / dark / Material You, using role-based theme tokens
  only (no hardcoded colors).

### New/updated strings

All user-facing text via string resources (feature 08), Spanish base + English variant: an append-error
message and its Retry label (the empty/refresh-error strings already exist —
`articles_empty` / `articles_error` / `articles_retry`).

---

## Out of Scope

- **Paged search / filter / sort** of articles (no filter UI exists on Articles; Paging + filtering is
  its own topic).
- **Paged tasks** — tasks keep their live-`Flow` model; this lesson paginates articles only.
- **Prepend / bi-directional paging** — `PREPEND` returns `endOfPaginationReached` immediately.
- **Article header images / Coil** — reserved for lesson **10b**.
- **Server-side article authoring/admin** (create/update/delete articles) — the catalog is seed-only and
  read-only over HTTP in v1.
- **Auth on `/articles`** — deliberately public (guest mode). Any future per-user article features would
  be a separate, authenticated surface.
- **Cursor-based pagination** (unless chosen at sign-off) — page/offset is recommended.
- **Production HTTPS hosting** of the backend — still out of scope project-wide (see feature 11 spec);
  local `docker compose` + the debug-only cleartext allowlist stand, and `/articles` rides the same
  `10.0.2.2` / `localhost` exception.
- **Migrating articles off the shared `NeverLateDatabase`** into their own DB — stays in the one Room DB.

---

## Dependencies

**Must exist / be true before implementation:**

- Backend running locally via `docker compose` (feature 11), reachable at the configured
  `BuildConfig.BACKEND_BASE_URL` (`http://10.0.2.2:8080/` from the emulator).
- The debug-only cleartext network security config already allows `10.0.2.2` / `localhost` — `/articles`
  needs no new exception.
- Room `exportSchema = true` and the committed `4.json` / `5.json` baselines + `room-testing` dependency
  (all in place since feature 13b).
- `ArticleDto`, `ArticleDto.toDomain()`, `ArticleEntity`, `Article.toEntity()`, `ArticleDao` and
  `CachingArticleRepository` (feature 10) — all extended, not replaced.

**New / changed within this feature:**

- **Contract:** `docs/api/contract.md` gains the public `/articles` section (authored first).
- **Backend:** new `articles` package + `articles` table + seed in `initSchema`; route registered
  public.
- **Dependency:** `androidx.paging` (runtime + compose), and possibly `androidx.room:room-paging`, in
  `gradle/libs.versions.toml`.
- **Room:** version `5 → 6`, `MIGRATION_5_6`, committed `6.json`, migration test.
- **Tutorial:** `tutorial/13c-paginacion.md` filled in; `docs/conceptos-pendientes.md` §6 and
  `tutorial/README.md` row flipped ⬜ → ✅.

**Files in play (for orientation, not a checklist):** `app/.../data/articles/` (`ArticlesApi`,
`ArticleDao`, `ArticleEntity`, `ArticlesRemoteMediator`, `ArticleRemoteKeys`+DAO,
`CachingArticleRepository`, `ArticleRepository`), `app/.../data/tasks/NeverLateDatabase.kt`,
`app/.../ui/articles/ArticlesScreen.kt` + `ArticlesViewModel.kt`, `gradle/libs.versions.toml`,
`docs/api/contract.md`, `backend/src/.../articles/` + `backend/src/.../db/`, `docs/articles-api/`,
`tutorial/13c-paginacion.md`.

---

## Risks

- **`RemoteMediator` correctness is the hard part.** Off-by-one on `nextKey`, mishandling
  `endOfPaginationReached`, or a non-transactional REFRESH (clearing the cache then failing the network
  call) can leave the cache empty or duplicate/skip pages. Mitigation: REFRESH clears + repopulates in a
  **single Room transaction**; derive end-of-pagination from the response; add tests around the mediator
  logic where practical.
- **Stable ordering.** SQLite has no inherent row order; without a server-provided `position` /
  `remoteOrder` the `PagingSource` can re-order rows between loads, causing visible jumps or gaps.
  Mitigation: the ordering column + a deterministic server order are load-bearing, not optional.
- **Shared-DB migration blast radius.** The `articles` migration runs on the same DB as guest tasks, so
  a mistaken destructive fallback would lose guest data. Mitigation: additive migration + migration test
  (per 13b).
- **Public endpoint exposure.** `/articles` being unauthenticated is intentional but must be
  double-checked to sit **outside** `authenticate("auth-jwt")`; and it must not leak anything user-
  scoped (it serves a global catalog only). No rate limiting in v1 (acceptable for a local tutorial
  backend; note it).
- **Two refresh mechanisms if the old SWR path lingers.** If `RefreshResult`/`loadThenRefresh` are not
  fully removed, the screen would have Paging refresh *and* the old SWR loop — confusing and
  contradictory. Mitigation: the clean removal in *Old non-paged path* (pending sign-off).
- **Seed/content drift.** If the backend seed and the old GitHub-raw JSON both persist as live sources,
  wording can diverge. Mitigation: option (A) — one canonical content source, backend seeds from it.
- **Detail screen coupling.** Detail reads `getArticleById` from the cache; a page the user hasn't
  scrolled to isn't cached yet, so deep-linking to an unseen article id could miss. Low risk today
  (detail is only reachable by tapping a visible, already-cached row), but note it.

---

## Decisions needed at sign-off (summary)

1. **Response shape:** page/offset `{ items, page, size, total }` (**recommended**) vs cursor
   (`nextCursor`).
2. **Public endpoint:** confirm `/articles` is unauthenticated (guest mode) — **recommended / required**
   by feature 13.
3. **Room migration:** additive `MIGRATION_5_6` + `6.json` + migration test (**recommended**) vs
   destructive fallback (**rejected** — shared DB with guest tasks).
4. **Static GitHub-raw file:** repurpose `docs/articles-api/articles.json` as backend seed and stop
   serving it to the app (**option A, recommended**) vs remove entirely (B) vs keep serving too
   (C, rejected).
5. **Old refresh path:** remove `CachingArticleRepository.refresh` / `RefreshResult` / the SWR
   `ArticlesUiState` loop in favour of Paging's `LoadState` refresh (**recommended**).

---

> **Next step:** please review and **approve** this spec — approval covers **both behaviour and the
> Visual & UX Design** section. On approval I'll create branch `feature/articles-paging` and hand off to
> implementation (contract first, then backend + client, tests, design review, and the
> `tutorial/13c-paginacion.md` lesson). Do not implement before sign-off.
