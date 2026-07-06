# Spec: Configurable backend URL for physical-device testing (dev-environment fix)

- **Date:** 2026-07-07
- **Type:** Development-environment configuration fix (framed as a **bugfix**)
- **Suggested branch:** `bugfix/backend-url-dispositivo-fisico`
- **Scope:** Android client only (`app/`). No backend code, no API contract change.
- **Build variant affected:** **debug only.** The lesson-11 rule stands — before any real deployment
  the backend must be served over HTTPS and no cleartext exception may reach the release manifest.

> Note: this is **not** a tutorial feature. It does not get its own `tutorial/NN-*.md` lesson; instead
> it appends a short section to the existing `tutorial/11-bbdd-remota.md` (see Documentation to update).

---

## Overview

Since feature 11 the Android app is an offline-first client of the local `backend/` (Ktor + Postgres,
`docker compose up` on the host, port 8080). Today the app can only reach that backend from the
**Android emulator**, because the base URL is hardcoded:

```kotlin
// app/src/main/java/com/neverlate/data/network/BackendNetwork.kt
const val DEFAULT_BACKEND_BASE_URL = "http://10.0.2.2:8080/"
```

`10.0.2.2` is a special alias that **only the emulator** uses to reach the host machine's `localhost`.
On a **physical phone** that address goes nowhere, so register/login fail with a "no connection" error.
Even if the URL were corrected by hand, the debug-only cleartext allowlist
(`app/src/debug/res/xml/network_security_config.xml`) currently permits only `10.0.2.2` and `localhost`,
so `targetSdk 36` would block any other host anyway.

This fix makes the backend base URL **configurable per developer, without editing Kotlin source or
committing anyone's local network details**, so the same debug build runs on both the emulator and a
physical device. The default stays `http://10.0.2.2:8080/`, so the current emulator flow keeps working
untouched.

## Goals

- A developer can point the debug build at their backend on a **physical device** without editing any
  `.kt` file and without committing their machine's IP/URL.
- The **emulator flow is unchanged**: with no configuration, the app still targets `http://10.0.2.2:8080/`.
- Two documented paths, with **USB / `adb reverse` as the recommended default** (no shared Wi-Fi, no
  firewall changes) and Wi-Fi / LAN IP as the alternative.
- The cleartext exception stays **debug-only and host-scoped**; nothing widens to release or to
  arbitrary hosts.

## User Stories

### US-1 — Test on a physical device over USB (recommended)
*As a developer, I want to run the debug app on my USB-connected phone and reach the host backend, so
that I can test auth and sync on real hardware without configuring my network.*

**Acceptance criteria:**
- With `adb reverse tcp:8080 tcp:8080` active and the backend base URL set to `http://localhost:8080/`,
  the phone can register, log in, and sync tasks against the host backend.
- `localhost` is already in the debug cleartext allowlist, so **no `network_security_config.xml` change
  is required** for this path.
- The steps (connect USB, run `adb reverse`, set the URL) are documented in `CLAUDE.md` → Development →
  Backend.

### US-2 — Test on a physical device over Wi-Fi (LAN IP)
*As a developer, I want to point the debug app at my PC's LAN IP over Wi-Fi, so that I can test on a
device that is not tethered by USB.*

**Acceptance criteria:**
- Setting the base URL to the host's LAN IP (e.g. `http://192.168.1.50:8080/`) lets the phone reach the
  backend, given the backend listens on `0.0.0.0` and port 8080 is open on the host firewall.
- The chosen LAN IP is added to the **debug-only** `network_security_config.xml` cleartext allowlist
  (documented as a per-developer edit), and `targetSdk 36` then permits it.
- Prerequisites (backend on `0.0.0.0`, firewall, same Wi-Fi network) are documented in `CLAUDE.md`.

### US-3 — Configure the URL without touching source or git
*As a developer, I want to set the backend base URL in an untracked local file, so that my machine's
network details never land in a `.kt` file or in a commit.*

**Acceptance criteria:**
- The base URL is exposed as a `BuildConfig` field on `app/` (e.g. `BuildConfig.BACKEND_BASE_URL`), fed
  from a Gradle property read from `local.properties` (untracked) — falling back to `gradle.properties`
  if present.
- When the property is absent, the field defaults to `http://10.0.2.2:8080/`.
- `BackendNetwork.DEFAULT_BACKEND_BASE_URL` reads from `BuildConfig` rather than hardcoding the literal.
- `local.properties` remains git-ignored; no developer's URL is committed. `gradle.properties` (tracked)
  is **not** used to store a personal IP.

### US-4 — Emulator flow unchanged (regression guard)
*As an existing emulator user, I want the app to keep working with no configuration, so that this change
costs me nothing.*

**Acceptance criteria:**
- With a clean checkout and no property set, a debug build targets `http://10.0.2.2:8080/` and the
  emulator smoke sequence (register → create task → list → login) still passes.
- No source edit is required to get the previous behaviour.

## Technical Approach

1. **Gradle → `BuildConfig` field (`mobile-engineer`).**
   In `app/build.gradle.kts`, read a Gradle property (e.g. `neverlate.backendBaseUrl`) from
   `local.properties` (loading that file into the build script; it is not auto-exposed) with a fallback
   default of `"http://10.0.2.2:8080/"`, and emit it via
   `buildConfigField("String", "BACKEND_BASE_URL", "\"$value\"")`. `buildConfig = true` is already
   enabled (used by feature 10's `BuildConfig.DEBUG`).

2. **Wire the field into `BackendNetwork` (`mobile-engineer`).**
   Change `DEFAULT_BACKEND_BASE_URL` to read `BuildConfig.BACKEND_BASE_URL`. The `create(baseUrl = …)`
   seam is preserved — tests keep overriding it with a `MockWebServer` URL exactly as today.

3. **Cleartext allowlist stays debug-only and host-scoped (`devops-security-engineer`).**
   `localhost` already covers the recommended USB path, so no change is needed for US-1. For US-2,
   document adding the specific LAN IP to `app/src/debug/res/xml/network_security_config.xml` as a
   per-developer edit. Verify the file stays under `src/debug/`, that release builds get no exception,
   and that no `usesCleartextTraffic="true"` or wildcard host is introduced.

4. **Docs (`mobile-engineer` / author).** Update `CLAUDE.md` Development → Backend with the physical-device
   steps, and append a short note to `tutorial/11-bbdd-remota.md` (§7 area) — no new lesson file.

## Concepts taught (appended to tutorial 11 — not a new lesson)

- **`BuildConfig` fields from Gradle.** Injecting build configuration (URLs, flags) at build time
  instead of hardcoding it in Kotlin, read from `local.properties` so it stays out of git — the same
  `BuildConfig` mechanism lesson 10 already used for `BuildConfig.DEBUG`.
- **`adb reverse` vs. `10.0.2.2`.** Why the emulator's host-loopback alias doesn't exist on real
  hardware, and how USB port forwarding (`adb reverse tcp:8080 tcp:8080`) makes the phone's `localhost`
  resolve to the host's backend.
- **Debug-only Network Security Config (reminder from lesson 11).** Why cleartext is restricted to
  named hosts, why the exception lives under `src/debug/`, and why it is **never** copied into the
  release manifest — HTTPS is mandatory before any real deployment.

## Out of Scope

- Any change to `docs/api/contract.md` — the network contract (endpoints, wire shapes, auth, status
  codes) is unchanged; only the destination host changes.
- HTTPS / TLS for the backend, certificates, or release cleartext posture (the lesson-11 warning stands).
- Cloud or remote hosting of the backend (still local `docker compose` per project scope).
- A new tutorial lesson file (`tutorial/NN-*.md`) — this appends to lesson 11 only.
- An in-app / runtime UI to switch backend URLs (this is a build-time developer setting, not a
  user-facing feature).
- Changing the emulator default or introducing product flavors / release-config variants.
- Backend changes to bind on `0.0.0.0` — noted as a documented prerequisite for US-2, not delivered here.

## Dependencies

- Feature 11 (remote DB + offline-first sync) is merged: `BackendNetwork`, the debug
  `network_security_config.xml`, and `docker compose` backend all exist.
- `buildConfig = true` already enabled in `app/build.gradle.kts` (feature 10).
- `local.properties` is git-ignored (standard Android project setup) — confirm before relying on it.
- For US-2 only: backend reachable on the LAN (listening on `0.0.0.0`), host firewall open on port 8080,
  phone and PC on the same Wi-Fi network.

## Risks

- **Emulator regression.** A wrong default or misread property could break the working emulator flow.
  Mitigate by defaulting to `http://10.0.2.2:8080/` and covering US-4 as an explicit regression check.
- **Cleartext scope creep.** Copy-pasting the LAN IP into the wrong manifest, using a wildcard, or
  setting `usesCleartextTraffic="true"` would leak the exception to release. Mitigate via the
  `devops-security-engineer` verification that the exception stays `src/debug/`-only and host-scoped.
- **`local.properties` not ignored on some setup.** If a developer's IP were committed it would leak
  their network. Mitigate by confirming the git-ignore and documenting `local.properties` (not
  `gradle.properties`) as the place for personal values.
- **Silent misconfiguration.** A typo'd URL fails as a generic "no connection", which is hard to
  diagnose. Mitigate with clear docs and the existing debug OkHttp body logging.

## Documentation to update

- **`CLAUDE.md`** → Development → Backend: add "Testing on a physical device" with the `adb reverse`
  (recommended) steps and the LAN-IP alternative, plus how to set `BuildConfig.BACKEND_BASE_URL` via
  `local.properties`.
- **`tutorial/11-bbdd-remota.md`**: append a short note (around §7 "Cómo probarlo en local") covering the
  three concepts above — `BuildConfig` from Gradle, `adb reverse` vs `10.0.2.2`, and the debug-only
  cleartext reminder. **No new lesson file.**
- No new dependency, so `gradle/libs.versions.toml` is untouched.

---

## Review & approval

Please review this spec. Implementation should not begin until you explicitly approve it. On approval,
the workflow is: create `bugfix/backend-url-dispositivo-fisico` from `master`, implement with
`mobile-engineer`, have `devops-security-engineer` verify the cleartext scope, update the docs above,
then commit on the bugfix branch.
