# Feature 12 (extra) — Refresh token y renovación silenciosa de sesión

**Status:** Draft — awaiting user approval.
**Date:** 2026-07-07
**Branch (suggested):** `feature/refresh-token`
**Tutorial lesson (planned):** `tutorial/12-refresh-token.md`
**Builds on:** Feature 11 (remote DB + offline-first sync), which introduced the backend, JWT auth,
the Bearer interceptor, `EncryptedTokenStorage`, and the `AuthRepository` seam. This feature
**extends** that machinery; it does **not** rebuild it.

---

## Overview

Feature 11 shipped stateless JWT auth. It deliberately deferred token renewal: the access token is
long-lived (currently `JWT_EXPIRY_HOURS`, default 24h) and, on any `401`, the client simply logs the
user out and routes back to login (`AuthInterceptor.onUnauthorized` → `AuthRepositoryImpl.notifyUnauthorized`
→ `logout()`). That is safe but blunt: it forces the user to re-enter their password every time the
token expires, and it pushes toward a *long* access-token lifetime (a stolen token stays valid for a
long time, and there is no way to revoke it early — see `Jwt` KDoc: "no way to revoke early in v1").

This feature introduces **silent session renewal** using the standard **access token + refresh
token** pair:

- The **access token** stays a short-lived, stateless JWT (minutes), sent on every `/tasks*` call
  exactly as today.
- A new **refresh token** is long-lived and used *only* to obtain a fresh access token via a new
  `POST /auth/refresh` endpoint. It is never sent on ordinary API calls.
- On a `401`, the client **renews first** (once, transparently, retrying the failed request) and only
  falls back to login if renewal *also* fails.

Because refresh tokens must be **rotated**, **revoked** (on logout), and checked for **reuse**
(theft detection), the server can no longer be purely stateless for the refresh side: it gains a
persisted **refresh-tokens table**. The access token remains stateless.

**Why now:** it is the natural next lesson after auth/sync — it turns a working-but-blunt auth flow
into a production-shaped one, and teaches token lifecycle, an OkHttp `Authenticator`, server-side
stateful tokens, and rotation/reuse-detection security concepts. It is also one of the two follow-ups
explicitly deferred from Feature 11.

---

## Goals

- The user is **never forced to re-enter their password** solely because the access token expired,
  as long as their refresh token is still valid and unrevoked.
- The access token can be made **short-lived** (minutes) without degrading UX, shrinking the window a
  leaked access token is useful.
- Refresh tokens are **rotated on every use** and **revoked on logout**, and a **reused (already-rotated)**
  refresh token is detected and treated as a security event.
- Renewal is **transparent and single-flight**: a burst of concurrent `401`s (typical during a sync
  push/pull) triggers **one** refresh, not one per in-flight request.
- Refresh and access tokens are both stored **only** in Keystore-backed `EncryptedTokenStorage`,
  never in plaintext.
- The **API contract is updated first** and remains the single source of truth for both sides.

---

## User Stories

### US-1 — Silent renewal (no surprise re-login)
**As a** logged-in user,
**I want** the app to renew my session automatically when it expires in the background,
**so that** I can keep viewing and editing tasks without being kicked to the login screen.

**Acceptance Criteria**
- Given a valid refresh token and an **expired** access token, when any `/tasks*` call returns `401`,
  then the client obtains a new access token via `POST /auth/refresh` and **retries the original
  request once**, and the user sees the result of that request — no login screen, no visible error.
- The retry reuses the *same* request (method, path, body); a create/update is not duplicated or lost
  by the renewal.
- Renewal happens on OkHttp's own threads (no coroutine), consistent with how `AuthInterceptor`
  already works; it must not require UI involvement.

### US-2 — Fallback to login only when renewal truly fails
**As a** user whose refresh token is expired, revoked, or rejected,
**I want** to be sent to login (and only then),
**so that** the app degrades exactly like Feature 11 when renewal is genuinely impossible.

**Acceptance Criteria**
- Given `POST /auth/refresh` returns `401` (refresh expired/invalid/revoked/reused), when the client
  cannot obtain a new access token, then it performs the existing logout path (clear session + wipe
  cached tasks/outbox + `AuthState.LoggedOut`) and routes to login — preserving Feature 11 behaviour
  as the fallback, not the first response.
- The original `401`ing request is **not** retried in a loop; a failed refresh short-circuits to
  logout.

### US-3 — Concurrent 401s trigger a single refresh
**As a** user whose token expires while a sync is running,
**I want** the many simultaneously-failing requests to share one renewal,
**so that** the app does not fire N refresh calls (which, with rotation, would invalidate each
other and cause a false theft alarm).

**Acceptance Criteria**
- Given several in-flight `/tasks*` requests all receiving `401` at once (e.g. push + pull during
  `SyncEngine`), when renewal runs, then **exactly one** `POST /auth/refresh` is issued; the other
  requests wait for that result and then retry with the newly-minted access token.
- The single-flight guard does not deadlock and releases correctly whether refresh succeeds or fails.
- A request that already carried the newest access token and *still* got `401` does not trigger a
  second refresh for that same token generation.

### US-4 — Rotation and revocation
**As a** security-conscious user,
**I want** each refresh to invalidate the previous refresh token and logout to invalidate my refresh
token server-side,
**so that** an old or post-logout refresh token cannot be used to mint new sessions.

**Acceptance Criteria**
- On successful `POST /auth/refresh`, the server issues a **new** access token **and a new refresh
  token**, and marks the presented refresh token as **rotated/consumed** so it cannot be used again.
- The client atomically replaces the stored access **and** refresh tokens with the new pair.
- On **logout**, the client calls the server to **revoke** the current refresh token, and the server
  marks it revoked; subsequent use of that token → `401`. Logout must still fully clear local state
  even if the revoke network call fails (best-effort revoke; local logout is unconditional).

### US-5 — Reuse (theft) detection
**As a** user whose refresh token was stolen,
**I want** the server to detect that a token was used twice,
**so that** a thief cannot silently keep a parallel session alive alongside mine.

**Acceptance Criteria**
- Given a refresh token that has already been rotated (consumed), when it is presented again to
  `POST /auth/refresh`, then the server treats it as a **reuse event**: it rejects the request with
  `401` and **invalidates the active refresh-token lineage/family for that user** (so the thief's
  freshly-minted token is also killed), forcing a fresh login.
- The reuse decision is logged server-side as a security event (without logging the token value).

### US-6 — Short-lived access token, secure storage
**As a** user,
**I want** my access token to be short-lived and both tokens stored encrypted,
**so that** a leaked access token expires quickly and neither token is readable at rest.

**Acceptance Criteria**
- The access-token lifetime is configurable and set **short** (target: minutes, e.g. 15) via existing
  env config; the refresh-token lifetime is configurable and **long** (target: days/weeks).
- Both the access token and the refresh token are persisted **only** through
  `EncryptedTokenStorage` (Keystore-backed `EncryptedSharedPreferences`); neither ever touches the
  plaintext `user_prefs` DataStore, and neither appears in any response log or crash report.

---

## Technical Approach

High-level only; the contract and the implementing agents own the details. Real seams to extend:

**0. Contract first (mandatory ordering — see Dependencies).**
Update `docs/api/contract.md` before any code: add `POST /auth/refresh`, change the register/login
success bodies from `{ token, user }` to a token-**pair** shape (e.g. `{ accessToken, refreshToken,
user }`), and document rotation/reuse/revocation semantics + the refresh error codes.

**1. Backend (`backend/`, Ktor + Postgres) — extend, don't fork.**
- `Jwt` stays the stateless access-token issuer; access-token expiry moves to minutes via `Config`
  (`jwtExpiryHours` → likely a minutes-based field or a second `accessTokenExpiryMinutes`).
- New **refresh-token store**: a persisted table (opaque random tokens, hashed at rest — never stored
  in clear, mirroring the password-hash discipline in `PasswordHasher`), with columns for user id,
  a **family/lineage id**, `rotatedAt`/`consumed`, `revoked`, `expiresAt`. New repository seam next to
  `UserRepository` (`RefreshTokenRepository` + `Postgres…`/`InMemory…` fakes, same pattern the whole
  backend uses so tests don't need Docker).
- `AuthService` gains issue-pair / refresh / revoke logic; `AuthRoutes` gains `POST /auth/refresh`
  and wires revoke into logout. Register/login now return a pair.

**2. Android client (`app/`) — extend the auth/network layer.**
- `AuthResponse`/`AuthModels` grow a `refreshToken` (and the register/login mapping in
  `AuthRepositoryImpl.authenticate` saves both).
- `TokenStorage`/`EncryptedTokenStorage` gain get/save for the refresh token and a combined
  save so access+refresh are replaced together.
- New **OkHttp `Authenticator`** (distinct from the Bearer `AuthInterceptor`): OkHttp calls an
  `Authenticator` specifically on a `401`, lets it return a new request to retry, and caps retries via
  `response.priorResponse` — the idiomatic place for renew-and-retry. It performs **single-flight**
  refresh (a lock/mutex + "did another thread already refresh this token generation?" check) so
  concurrent `401`s share one refresh (US-3). The `AuthInterceptor`'s current job narrows to attaching
  the Bearer header; the "on 401 → logout" behaviour moves behind the Authenticator's *fallback*
  (refresh failed → `notifyUnauthorized`).
- `AuthRepository` gains a `refresh()`-style capability and a logout that best-effort revokes the
  refresh token server-side before clearing local state.

**3. Keep the seams.** No new architectural layer: the Authenticator plugs into the same OkHttp
client `TasksNetwork` builds; the `AuthRepository`/`TokenStorage` interfaces are the extension points,
so `SyncEngine`, ViewModels, and tests stay decoupled from the concrete flow.

---

## Out of Scope

- **Server-side session UI / device management** (list active sessions, "log out other devices"). The
  refresh-token family model makes this possible later, but no endpoint/UI here.
- **Sliding/rolling re-authentication policies, MFA, OAuth/social login, password reset.**
- **Access-token revocation lists / introspection.** The access token stays stateless; early
  revocation is achieved indirectly by keeping it short-lived, not by a denylist.
- **Refresh-token binding to device fingerprint / DPoP / mTLS.** Rotation + reuse detection is the
  security bar for this tutorial feature.
- **Production TLS hosting.** Same as Feature 11: local dev stays plaintext HTTP behind the debug-only
  network-security-config; refresh tokens crossing the wire in clear is acceptable *only* in local
  dev, and the "must be HTTPS before real deployment" warning from Feature 11 now applies **doubly**
  (a refresh token is a longer-lived, higher-value credential).
- **Changing sync semantics.** Task sync, outbox, LWW, tombstones are untouched.
- **Migrating existing sessions.** Pre-feature-12 tokens can be treated as invalid on first `401`
  (fall back to login once); no data migration of stored tokens is required.

---

## Dependencies

- **CONTRACT FIRST (blocking, first implementation step):** `docs/api/contract.md` must be updated
  *before* any client or server code, per the project's API Contract policy. Specifically: add
  `POST /auth/refresh` (request = the refresh token; responses = new pair on `200`, `401` on
  expired/invalid/revoked/**reused**), change register/login success bodies to the token-pair shape,
  remove the "No `POST /auth/refresh` in v1" note, and document rotation/revocation/reuse semantics
  and the new error `code`s. Both sides then reconcile to it.
- **Feature 11 must be present and green** (it is): backend + JWT auth, `EncryptedTokenStorage`,
  `AuthInterceptor`, `AuthRepository`/`TokenStorage` seams, `SyncEngine`.
- **Postgres schema change:** a new refresh-tokens table (new migration). This is a *backend* schema
  change; unlike the Room `fallbackToDestructiveMigration` on the client, the backend owns real user
  data and should add the table additively (no destructive drop).
- **Config/env:** a short access-token lifetime and a long refresh-token lifetime must be settable via
  the existing env-var config (`Config`), documented in `backend/.env.example`.
- **Docs (mandatory before commit):** `tutorial/12-refresh-token.md` (Spanish), plus any
  `CLAUDE.md`/`docs/api/contract.md` updates and version-catalog additions if a new dependency is
  introduced. Run `/doc-check`.

---

## Risks

- **Retry loops / storms.** A bug where refresh returns a token that is *also* rejected could loop
  401→refresh→401. Mitigation: cap via the Authenticator's `priorResponse` chain and short-circuit to
  logout after one failed renewal (US-2).
- **Concurrency correctness.** The single-flight guard (US-3) is the subtlest part: getting the
  "another thread already refreshed this generation" check wrong causes either duplicate refreshes
  (false reuse alarms) or a deadlock. Needs focused tests around concurrent `401`s.
- **False reuse alarms.** Rotation + a race (e.g. a retried refresh after a lost response) can look
  like reuse and log the user out spuriously. The design must tolerate an idempotent-ish refresh or
  scope reuse detection carefully (e.g. grace on the immediately-previous token) so normal retries
  don't nuke a session.
- **Losing an in-flight mutation on retry.** Retrying a `POST /tasks` after renewal must not create a
  duplicate; the existing `clientRef` idempotency (contract §3) protects creates, but this interaction
  must be verified.
- **Storage atomicity.** Access and refresh tokens must be replaced together; a partial write (new
  access, stale refresh) would break the next renewal. Save as one operation.
- **Stateful drift from "stateless" teaching.** The lesson must clearly explain *why* the refresh side
  is stateful while the access side stays stateless, so the earlier "JWT is stateless" teaching isn't
  contradicted but *extended*.

---

## New concepts taught (tutorial)

`tutorial/12-refresh-token.md` (Spanish), building on lesson 11's auth/sync foundations:

- **Token lifecycle rationale.** Why split one token into a short-lived **access** token + a
  long-lived **refresh** token; the trade-off between UX (don't re-login constantly) and security
  (shrink the leaked-access-token window). Why the access token stays stateless and the refresh token
  becomes stateful.
- **OkHttp `Authenticator` + concurrent-refresh handling.** How an `Authenticator` differs from the
  Bearer `Interceptor` (OkHttp invokes it specifically on `401`, it returns the retry request, and
  `priorResponse` bounds retries), and how to make refresh **single-flight** so a burst of concurrent
  `401`s triggers exactly one renewal.
- **Server-side stateful refresh tokens.** Persisting refresh tokens (hashed at rest), **rotation** on
  each use, **revocation** on logout, and **reuse detection** via a token family/lineage — and why
  this is the first place the backend keeps auth *state*.
- **Secure storage, revisited.** Storing *both* tokens in Keystore-backed `EncryptedTokenStorage`,
  replacing them atomically, and never letting either touch plaintext storage or logs.

---

## Approval

Please review this specification. **Do not begin implementation until you explicitly approve it.**
Once approved, the first step is updating `docs/api/contract.md` (contract-first), then the
`feature/refresh-token` branch, backend + client changes, tests (`qa-engineer`), and the Spanish
tutorial lesson.

Resolved decisions (approved 2026-07-07):

1. **Access / refresh lifetimes** — access **15 min**, refresh **30 days**.
2. **Reuse-detection blast radius** — on a detected reuse, kill only the presented token's
   **family/lineage** (other sessions/devices survive).
3. **Register issues a refresh token too** — yes; register and login both return the token pair.
