# Feature 21 (extra) — Build de release: variants, R8 y firma (a producción)

Lee `CLAUDE.md` (**Tutorial Methodology** + **Mandatory Workflow** + **API Contract**) y las lecciones
previas (en especial la 11: backend local por HTTP cleartext y el aviso de **HTTPS pendiente**; la 12:
refresh token, credencial de más valor cruzando la red; y la 12b: keystore/backup). Implementa
**"preparar una build `release` firmada, minificada y sobre HTTPS"** siguiendo el flujo `/feature`.

> **Última lección del roadmap: llevar la app a producción.** La app solo construye `debug`. Aquí se
> cierra el pendiente de seguridad que arrastran las lecciones 11–12: la excepción de cleartext es
> **solo de debug** y **no** debe llegar a release.

## Qué construir

- Un **build type `release`** con `isMinifyEnabled = true` (R8), `isShrinkResources = true` y reglas
  `proguard-rules.pro` (`keep` para lo que la reflexión/serialización necesite).
- **Firma de release** con un keystore (referenciado por propiedades en `local.properties`/env, **no**
  commiteado), configurando `signingConfigs`.
- El `BACKEND_BASE_URL` de release apuntando a **HTTPS**, y confirmación de que el
  `network_security_config` de cleartext es **solo** de `src/debug` (no en release).
- Verificar que la build release **arranca, sincroniza y no rompe** (kotlinx.serialization, Room,
  Retrofit funcionan tras R8).

## Conceptos nuevos a enseñar (lección en español)

- **Build types y product flavors, `BuildConfig`:** debug vs release, campos por variante.
- **R8/ProGuard:** minificación, ofuscación, `shrinkResources`, y por qué hacen falta reglas `keep`
  (kotlinx.serialization, modelos de Room/Retrofit).
- **Firma de la app:** keystore, `signingConfigs`, APK vs AAB, y por qué el keystore nunca se
  commitea.
- **Cleartext y release:** por qué la excepción HTTP solo puede vivir en `src/debug`, y el paso a
  HTTPS del backend.

## Notas

- Rama sugerida: `feature/release-build`.
- **Actualiza el contrato/README si aplica:** deja claro en `backend/README.md`/`docs/api/contract.md`
  el requisito de HTTPS para despliegue real.
- **Extiende, no dupliques:** reutiliza el `BuildConfig.BACKEND_BASE_URL` ya cableado (11) y el
  `network_security_config` de debug (11); **no** copies la excepción cleartext a release.
- Mapea a `docs/conceptos-pendientes.md` §8 (Build y release). Sin nueva dependencia de app; sí config
  de Gradle.
- Ficheros: [`app/build.gradle.kts`](../../app/build.gradle.kts), `app/proguard-rules.pro` (nuevo),
  `app/src/debug/` (confirmar aislamiento del cleartext), `local.properties` (propiedades de firma,
  git-ignored), `backend/README.md`.
- Agentes: `devops-security-engineer` (protagonista: R8, firma, aislamiento cleartext, HTTPS),
  `mobile-engineer` (reglas `keep`, `BuildConfig`), `qa-engineer` (smoke de la build release). Lección
  en `tutorial/21-build-release.md` (español), numerada como **21** (tras la 20, última del roadmap).
