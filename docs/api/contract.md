# Never Late — API Contract (v1)

**Status:** authoritative. This document is the **single source of truth** for the HTTP contract
between the Android client (`app/`) and the backend (`backend/`). Client and server are built and
reviewed against this file; when the contract changes, this file changes **first** and both sides
follow. Introduced by **Feature 11** (remote DB + offline-first sync). See the spec at
`docs/specs/2026-07-06-remote-db-sync.md`.

- **Base URL (local dev):** `http://10.0.2.2:8080` from the Android emulator (`10.0.2.2` is the
  host loopback as seen from the emulator), `http://localhost:8080` from the host. Production TLS
  hosting is out of scope for v1 (see spec *Out of Scope*).
- **Content type:** `application/json` for all request and response bodies (UTF-8).
- **Auth scheme:** stateless **JWT** as a `Authorization: Bearer <token>` header. No refresh token
  in v1 — on `401` the client clears its session and returns to login.
- **Time:** all timestamps are **epoch milliseconds (UTC)** as JSON numbers (`Long`). The server is
  the authority on a task's `updatedAt` and `id`.

---

## 1. Conventions

### 1.1 Error shape

Every non-2xx response carries a consistent JSON error envelope:

```json
{ "error": { "code": "string_code", "message": "Human-readable, safe to show or log" } }
```

| HTTP | `code` examples | Meaning |
|------|-----------------|---------|
| 400  | `validation_error` | Malformed body / failed field validation |
| 401  | `invalid_credentials`, `unauthorized` | Missing/invalid/expired token, or wrong login |
| 403  | `forbidden` | Authenticated but not allowed (e.g. another user's task) |
| 404  | `not_found` | Resource does not exist **for this user** |
| 409  | `email_taken` | Unique-constraint conflict (registration) |
| 5xx  | `internal_error` | Unexpected server error (never leaks internals) |

Passwords and tokens are **never** included in any response body or server log.

### 1.2 Authentication

- All `/tasks*` endpoints require `Authorization: Bearer <token>`. A missing/invalid/expired token
  → `401 { error.code: "unauthorized" }`.
- Every task query is **scoped to the authenticated user's id** server-side. A client is untrusted:
  it can never read or write another user's tasks. Cross-user access → `404 not_found` (we prefer
  404 over 403 so the API does not confirm the existence of another user's resource).

---

## 2. Auth endpoints

### `POST /auth/register`

Create an account and return a session token.

**Request**
```json
{ "email": "user@example.com", "password": "at-least-8-chars" }
```
Validation: `email` must be a syntactically valid email; `password` min length 8. Password is
hashed server-side (bcrypt/argon2) — never stored or logged in clear.

**Responses**
- `201 Created`
  ```json
  { "token": "<jwt>", "user": { "id": 1, "email": "user@example.com" } }
  ```
- `409 Conflict` — `email_taken`
- `400 Bad Request` — `validation_error`

### `POST /auth/login`

**Request** — same body shape as register.

**Responses**
- `200 OK` — same body shape as register's `201`.
- `401 Unauthorized` — `invalid_credentials` (same code whether the email is unknown or the
  password is wrong, so the API does not reveal which emails are registered).
- `400 Bad Request` — `validation_error`.

> No `POST /auth/refresh` in v1. Token renewal is deferred to a future feature (see spec
> *Out of Scope*).

---

## 3. Task endpoints

All require auth (§1.2) and are scoped to the authenticated user.

### `GET /tasks?since=<epochMillis>`

The **pull** half of sync. Returns every task **changed at or after** `since`, **including
tombstones** (soft-deleted tasks, so other devices can learn about deletions). Omitting `since`
(or `since=0`) returns the user's full current set (used on first sync after login).

**Response** `200 OK`
```json
{
  "tasks": [ TaskDto, ... ],
  "serverTime": 1751800000000
}
```
`serverTime` is the server's clock at the moment the response was built; the client stores it as
the next `since` cursor. This makes pulls incremental and gives the client a server-authoritative
clock reference.

### `POST /tasks`

Create a task. The client generates a **`clientRef`** (a stable unique string, e.g. a UUID) for
idempotency: if the same `clientRef` is POSTed twice (e.g. a retried request after a lost ack), the
server returns the **already-created** task instead of creating a duplicate.

**Request**
```json
{
  "clientRef": "c0ffee-uuid",
  "title": "Buy milk",
  "estimatedDurationMillis": 1800000,
  "deadline": 1751900000000,
  "updatedAt": 1751800000000
}
```
`title` is required and non-blank. `estimatedDurationMillis` and `deadline` are each nullable, but
per the domain rule **at least one** must be present (validated server-side too, not only in the
client form). `updatedAt` is the client's last-modified time for the row.

**Responses**
- `201 Created` — the created `TaskDto` (with the server-assigned `id` and server `updatedAt`).
- `200 OK` — if `clientRef` was already seen: the existing `TaskDto` (idempotent replay).
- `400 Bad Request` — `validation_error`.

### `PATCH /tasks/{id}`

Update a task the user owns. `{id}` is the **server** id. Conflict resolution is
**last-write-wins by `updatedAt`**: if the incoming `updatedAt` is **older** than the stored one,
the server keeps its version and returns it (the client will reconcile on the next pull).

**Request** — any updatable subset; send `updatedAt`:
```json
{ "title": "Buy oat milk", "estimatedDurationMillis": 1800000, "deadline": null, "updatedAt": 1751805000000 }
```

**Responses**
- `200 OK` — the resulting `TaskDto` (either the applied update, or the retained newer server copy).
- `404 Not Found` — no such task for this user.
- `400 Bad Request` — `validation_error`.

### `DELETE /tasks/{id}`

**Soft delete** (tombstone). The row is not hard-deleted; it is marked `deleted: true` with a fresh
`updatedAt`, so other devices receive the deletion on their next pull. **Delete wins** over a
concurrent edit.

**Responses**
- `200 OK` — the tombstoned `TaskDto` (`deleted: true`).
- `404 Not Found` — no such task for this user.

> **OQ-2 resolved:** v1 uses **separate REST resources** (above), not a single batched
> `POST /sync`. A batched endpoint may be added later without breaking these.

---

## 4. `TaskDto` (wire shape)

Deliberately **different** from the client's Room `Task` entity (continuing the DTO-≠-entity
teaching from Feature 10). The DTO carries sync/server concerns the domain model does not, and
omits purely-local timer state.

```json
{
  "id": 42,
  "clientRef": "c0ffee-uuid",
  "title": "Buy milk",
  "estimatedDurationMillis": 1800000,
  "deadline": 1751900000000,
  "deleted": false,
  "updatedAt": 1751805000000,
  "createdAt": 1751800000000
}
```

| Field | Type | Notes |
|-------|------|-------|
| `id` | `Long` | **Server** id. Stable across devices. Maps to the client's `serverId`. |
| `clientRef` | `String` | Client-generated idempotency token; echoed back so the creating device can match the response to its local row. |
| `title` | `String` | Non-blank. |
| `estimatedDurationMillis` | `Long?` | Nullable. |
| `deadline` | `Long?` | Nullable, epoch millis. At least one of `estimatedDurationMillis`/`deadline` is present. |
| `deleted` | `Boolean` | Tombstone flag. `true` rows appear only in pulls so peers can delete locally. |
| `updatedAt` | `Long` | Server-authoritative last-modified time; the LWW conflict key. |
| `createdAt` | `Long` | Server creation time. |

**Not on the wire (local-only):** the client's autoincrement `id` (in-app identity for widget,
reminders, nav args), `serverId` (client's copy of the DTO `id`), `syncState`, and the timer fields
`timerEndsAt` / `remainingMillis` (live countdown state is **not** synced in v1 — see spec
*Data Model changes / Out of Scope*). The client maps `TaskDto ↔ Task (+ sync metadata)`.

---

## 5. Sync semantics (informative)

The authoritative sync design lives in the spec (*Sync Model*); summarised here so both sides agree
on behaviour the wire shape alone doesn't spell out:

- **Pull:** `GET /tasks?since=cursor` → upsert changed tasks by `id`, delete local rows whose DTO is
  a tombstone, advance the cursor to `serverTime`.
- **Push:** replay the client outbox oldest-first — `POST` (create, with `clientRef`), `PATCH`
  (update), `DELETE`. On success reconcile the local row (`serverId`, `syncState = synced`; a
  confirmed delete hard-deletes the local row). Creates are **idempotent** by `clientRef`.
- **Conflicts:** **last-write-wins by `updatedAt`**; **delete wins** over a concurrent edit. The
  server enforces LWW on `PATCH`; the client applies the same rule when merging a pull against
  pending local changes (in a pure, JVM-testable merge function).

---

## 6. Smoke sequence (must succeed against `docker compose up`)

```
POST /auth/register {email,password}         -> 201 {token,user}
POST /tasks (Bearer token) {clientRef,title,...} -> 201 {id,...}
GET  /tasks?since=0 (Bearer token)           -> 200 {tasks:[{id,title,...}], serverTime}
POST /auth/login {email,password}            -> 200 {token,user}
```
