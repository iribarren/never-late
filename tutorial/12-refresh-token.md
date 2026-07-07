# Lección 12 — Refresh token y renovación silenciosa de sesión

> Objetivo: que la sesión **se renueve sola** cuando el token de acceso caduca, sin obligar al usuario a
> volver a teclear su contraseña. En la lección 11 dejamos esto **diferido a propósito**: el token JWT
> vivía 24 h y, ante cualquier `401`, cerrábamos sesión y volvíamos al login. Aquí partimos ese único
> token en un par **access token (corto) + refresh token (largo)**, enseñamos el **`Authenticator` de
> OkHttp** (un punto de extensión distinto del interceptor de la 11) para renovar y reintentar de forma
> transparente, y descubrimos que el refresh token obliga al backend —hasta ahora *stateless*— a
> **guardar estado**: una tabla de tokens con **rotación**, **revocación** y **detección de reuso**.
> Reutilizamos casi todo de la 11: el contrato de API como fuente única de verdad, el interceptor
> Bearer, `EncryptedSharedPreferences`, el *seam* `AuthRepository`/`TokenStorage`, y el backend Ktor +
> Postgres. **Extendemos, no duplicamos.**

## Conceptos que aprendes aquí

Partiendo de la lección 11 (auth con JWT, interceptor Bearer, almacenamiento cifrado del token, el
*seam* de repositorio, el backend Ktor + Postgres):

- **Ciclo de vida de tokens.** Por qué un **access token de vida corta** + un **refresh token de vida
  larga** ([patrón OAuth 2.0](https://oauth.net/2/refresh-tokens/)) es más seguro que un único JWT
  eterno; qué se guarda dónde; por qué el access sigue siendo *stateless* y el refresh se vuelve
  *stateful*.
- **Renovación silenciosa en el cliente.** El
  **[`Authenticator`](https://square.github.io/okhttp/recipes/#handling-authentication-kt-java)** de
  OkHttp: cómo se diferencia del interceptor de la 11, cómo intercepta el `401`, pide **una sola vez**
  un token nuevo y **reintenta** la petición original de forma transparente; y cómo manejar
  **renovaciones concurrentes** (varias llamadas fallando a la vez sin disparar N refrescos) con
  [`synchronized`](https://kotlinlang.org/docs/shared-mutable-state-and-concurrency.html).
- **Estado en el servidor para tokens de refresco.** Una tabla de refresh tokens, **rotación** en cada
  uso, **revocación** en logout, y **detección de reuso** (posible robo) acotada por **familias** de
  tokens. Es el primer sitio donde el backend guarda *estado de auth*.
- **Seguridad, revisitada.** Guardar el refresh token también en Keystore, reemplazar ambos tokens de
  forma **atómica**, una **carrera TOCTOU** real en la rotación, y por qué **no** logueamos los cuerpos
  de las peticiones de auth.

---

## 1. Por qué dos tokens: el ciclo de vida

En la lección 11 teníamos **un** token: un JWT que el servidor firma en el login y que el cliente
adjunta en cada llamada (`Authorization: Bearer …`). El servidor lo verifica con pura matemática de
firma, sin tocar la base de datos: es **stateless** (sin estado). Elegante, pero tiene una tensión
incómoda:

- Si el token dura **poco** (por seguridad: si te lo roban, caduca pronto), el usuario tiene que hacer
  login **constantemente**. Mala experiencia.
- Si el token dura **mucho** (por comodidad), un token robado sirve durante **mucho tiempo**, y —al ser
  stateless— **no hay forma de revocarlo** antes de que caduque. En la 11 lo pusimos a 24 h justamente
  por este compromiso, y el KDoc de `Jwt` lo admitía: *"no way to revoke early in v1"*.

La solución estándar es partir el token en **dos**, cada uno con un trabajo distinto:

| | **Access token** | **Refresh token** |
|---|---|---|
| Qué es | un JWT firmado (estructurado) | una cadena **opaca** aleatoria |
| Vida | **corta** (15 min) | **larga** (30 días) |
| Se manda… | en **cada** llamada a `/tasks*` | **solo** a `POST /auth/refresh` |
| Lo verifica… | firma (stateless) | **estado en el servidor** (stateful) |
| Si se filtra… | caduca en minutos | se puede **revocar** al instante |

La idea clave: el token que viaja **todo el rato** (y por tanto tiene más superficie para filtrarse)
ahora vive minutos. El token de vida larga viaja **casi nunca** —solo para pedir un access nuevo— y,
como el servidor lo tiene fichado, se puede matar en cuanto huela mal.

En el cliente, **ambos** se guardan en el mismo sitio cifrado de la 11
(`EncryptedSharedPreferences`, § 5), nunca en claro.

---

## 2. El contrato primero

Como en la 11, **antes de tocar cliente o servidor** cambiamos el contrato
([`docs/api/contract.md`](../docs/api/contract.md)), que es la **fuente única de verdad**. Los cambios:

- `POST /auth/register` y `POST /auth/login` ahora devuelven un **par de tokens** en vez de uno:

  ```json
  { "accessToken": "<jwt>", "refreshToken": "<opaco>", "user": { "id": 1, "email": "…" } }
  ```

- Endpoint nuevo **`POST /auth/refresh`**: recibe `{ "refreshToken": "…" }` (¡**sin** cabecera
  `Authorization`, porque justo se usa cuando el access ha caducado!) y devuelve un **par nuevo**
  (`200`) o `401 invalid_refresh_token` si el refresh está caducado, revocado, es desconocido o ha sido
  **reusado**.

- Endpoint nuevo **`POST /auth/logout`**: recibe `{ "refreshToken": "…" }` y devuelve `204` **siempre**
  (idempotente: un token desconocido o ya revocado también da `204`, para no filtrar si un token es
  válido).

- Una sección **§ 2.1** nueva que fija la semántica *stateful* del refresh: **rotación**, **revocación**
  y **detección de reuso** por **familia**. Volveremos a ella en la § 3.

Regla de oro (política de la sección *API Contract* de `CLAUDE.md`): cualquier cambio de forma de
petición/respuesta, código de estado o auth se escribe **primero** aquí, y luego los dos lados se
ajustan. El `README` del backend **no** vuelve a listar endpoints: remite al contrato.

---

## 3. El servidor gana estado: rotación, revocación y reuso

Aquí está el cambio conceptual gordo. El access token sigue igual de *stateless* que en la 11 (solo
cambia que ahora vive **15 minutos**, configurable por entorno — ver `Config` más abajo). Pero el
refresh token **no** puede ser stateless: su validez depende de cosas que una firma no captura —¿ya se
usó?, ¿lo revocaron?, ¿caducó?—, así que el servidor necesita **guardar una fila por cada refresh
token**.

### 3.1 Generar y hashear el token (nunca en claro)

Un refresh token es simplemente una cadena aleatoria con **mucha entropía**. No hay nada que decodificar
(a diferencia del JWT): el servidor solo lo busca por su hash.

```kotlin
// backend/src/main/kotlin/com/neverlate/backend/auth/RefreshTokenCrypto.kt
object RefreshTokenGenerator {
    private const val TOKEN_BYTES = 32           // 256 bits
    private val secureRandom = SecureRandom()    // aleatoriedad criptográfica, no Random()

    fun generate(): String {
        val bytes = ByteArray(TOKEN_BYTES)
        secureRandom.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }
}
```

Y —igual que con las contraseñas en la 11— **jamás** guardamos el token en claro en la base de datos:
guardamos su **hash**. Si alguien roba un volcado de la BBDD, no obtiene tokens usables.

```kotlin
object RefreshTokenHasher {
    fun hash(rawToken: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(rawToken.toByteArray(UTF_8))
        return Base64.getEncoder().encodeToString(digest)
    }
}
```

> **Detalle que sí importa:** aquí usamos **SHA-256**, *no* el **bcrypt** de las contraseñas. Bcrypt es
> **lento a propósito** para defender un secreto de **poca** entropía (una contraseña que un humano
> eligió) contra fuerza bruta offline. Un refresh token ya tiene 256 bits de entropía de máquina: no
> hay nada que un KDF lento proteja. Además el refresh ocurre en un camino caliente (cada renovación) y
> un hash determinista permite una **búsqueda indexada** por igualdad en SQL. Elegir el hash correcto
> según *qué* estás protegiendo es la lección.

### 3.2 La tabla y el *seam* de repositorio

Mantenemos el mismo patrón de la 11: una **interfaz** `RefreshTokenRepository` (el *seam*), con una
implementación en memoria para tests (sin Docker) y una de Postgres para producción. La fila guarda:

```kotlin
// backend/src/main/kotlin/com/neverlate/backend/auth/RefreshTokenRepository.kt
data class RefreshToken(
    val id: Long,
    val userId: Long,
    val familyId: String,   // la "familia" / linaje — clave para el reuso (§ 3.4)
    val tokenHash: String,  // solo el hash llega a la BBDD
    val createdAt: Long,
    val expiresAt: Long,
    val consumedAt: Long?,  // != null  ->  ya se rotó una vez  ->  señal de reuso
    val revoked: Boolean,   // logout (este token) o kill de familia (reuso)
)
```

La tabla se crea de forma **aditiva** en `db/Database.kt` (`CREATE TABLE IF NOT EXISTS …`, con índices
por `token_hash` y `family_id`). Ojo al contraste con el cliente: en Room usábamos
`fallbackToDestructiveMigration` (borrar y recrear), aceptable porque Room es solo una **caché**. Aquí
el backend es **dueño de datos reales de usuarios**, así que **nunca** hacemos un drop destructivo:
añadimos la tabla sin tocar lo existente.

### 3.3 La máquina de estados de `refresh()`

El corazón del servidor. Dado un refresh token en crudo, `AuthService.refresh()` decide si emite un par
nuevo o lo rechaza:

```kotlin
// backend/src/main/kotlin/com/neverlate/backend/auth/AuthService.kt
fun refresh(rawRefreshToken: String): AuthResponse {
    if (rawRefreshToken.isBlank()) throw ValidationException("refreshToken is required")
    val stored = refreshTokens.findByTokenHash(RefreshTokenHasher.hash(rawRefreshToken))
        ?: throw InvalidRefreshTokenException()   // desconocido
    val now = System.currentTimeMillis()

    if (stored.revoked) throw InvalidRefreshTokenException()          // revocado (logout / kill)
    if (stored.consumedAt != null) {                                 // ¡REUSO! (§ 3.4)
        logger.warn("Refresh token reuse detected: userId=${stored.userId} familyId=${stored.familyId}")
        refreshTokens.revokeFamily(stored.familyId)                  // mata toda la familia
        throw InvalidRefreshTokenException()
    }
    if (stored.expiresAt < now) throw InvalidRefreshTokenException()  // caducado

    // Reclamar el token para rotación de forma ATÓMICA (§ 3.5)
    if (!refreshTokens.markConsumedIfUnconsumed(stored.id, now)) {
        refreshTokens.revokeFamily(stored.familyId)
        throw InvalidRefreshTokenException()
    }

    val user = users.findById(stored.userId) ?: throw InvalidRefreshTokenException()
    return issuePair(user, stored.familyId)   // par nuevo, MISMA familia
}
```

Y cuando todo va bien, **rotación**: se emite un par **completamente nuevo** (access + refresh), y el
refresh viejo queda `consumedAt != null`, es decir, **muerto**. Un cliente legítimo solo debería tener
el **último** token del linaje.

```kotlin
private fun issuePair(user: User, familyId: String): AuthResponse {
    val now = System.currentTimeMillis()
    val rawRefreshToken = RefreshTokenGenerator.generate()
    refreshTokens.create(
        userId = user.id,
        familyId = familyId,                     // se hereda al rotar; UUID nuevo al hacer login
        tokenHash = RefreshTokenHasher.hash(rawRefreshToken),
        createdAt = now,
        expiresAt = now + config.refreshTokenExpiryDays * 24 * 60 * 60 * 1000,
    )
    return AuthResponse(
        accessToken = Jwt.createToken(config, user.id),
        refreshToken = rawRefreshToken,
        user = user.toPublic(),
    )
}
```

`register` y `login` llaman a `issueNewSession`, que arranca un linaje **nuevo** (un `familyId` UUID
fresco). `refresh` reusa el `familyId` del token que rota. Esa es toda la diferencia.

### 3.4 Reuso = robo: matar la familia

¿Por qué `consumedAt != null` significa robo? Porque tras rotar, el cliente legítimo **tira** el token
viejo y usa el nuevo. Si alguien presenta un token **ya consumido**, es que ese token se filtró y lo
tiene **otra persona** (o el propio ladrón lo usó primero y ahora lo usa el legítimo). En cualquier
caso, no podemos distinguir quién es quién, así que la respuesta segura es **cerrar todo el linaje**:

- `familyId` enlaza todos los tokens descendientes de un mismo login/registro a través de todas las
  rotaciones.
- Al detectar reuso, `revokeFamily(familyId)` revoca **toda** la familia —incluido el token recién
  rotado que el ladrón acaba de recibir— forzando un login limpio.
- Otras familias del mismo usuario (p. ej. otro dispositivo que hizo login por separado) **no** se
  tocan. Ése fue nuestro **decision #2** del spec: acotar el radio de explosión a la familia, no a todo
  el usuario.

### 3.5 La carrera TOCTOU (lo que encontró la revisión de seguridad)

Un detalle sutil y **real** que salió en la revisión de seguridad. Mira otra vez la § 3.3: primero
comprobamos `consumedAt == null` y luego marcamos el token como consumido. Si eso fueran **dos pasos
separados** (leer, luego escribir), dos peticiones `/auth/refresh` concurrentes con el **mismo** token
válido podrían **ambas** leer "aún no consumido" antes de que ninguna escriba, **ambas** emitir un par
nuevo, y el reuso **nunca** se detectaría. Es una carrera **TOCTOU** (*time-of-check to time-of-use*).

La defensa es hacer el "reclamo" en **una sola** escritura condicional atómica:

```kotlin
// La interfaz lo exige explícitamente:
/**
 * Marca un token consumido SOLO SI no lo estaba ya, devolviendo si esta llamada ganó la carrera.
 * Debe ser una única escritura condicional (SQL `UPDATE … WHERE consumed_at IS NULL`), no un
 * read-then-write, para que dos /auth/refresh concurrentes con el mismo token nunca ganen ambos.
 */
fun markConsumedIfUnconsumed(id: Long, consumedAt: Long): Boolean
```

En Postgres es un `UPDATE … WHERE id = ? AND consumed_at IS NULL` (la base de datos serializa la fila);
en la implementación en memoria, `ConcurrentHashMap.computeIfPresent`. Exactamente **una** llamada
recibe `true` (y rota); la otra recibe `false` y se trata como **reuso** → familia muerta. Hay un test
de concurrencia real (dos hilos + `CyclicBarrier`) que fija este comportamiento (§ 7).

### 3.6 Revocación en logout

`POST /auth/logout` simplemente revoca el token presentado. Es **best-effort** e **idempotente**: un
token desconocido es un no-op silencioso, y siempre devuelve `204` para no filtrar validez.

```kotlin
fun revoke(rawRefreshToken: String) {
    if (rawRefreshToken.isBlank()) throw ValidationException("refreshToken is required")
    val stored = refreshTokens.findByTokenHash(RefreshTokenHasher.hash(rawRefreshToken)) ?: return
    refreshTokens.revokeById(stored.id)
}
```

### 3.7 La config de tiempos

Los dos tiempos de vida vienen por **variable de entorno** (mismo principio de la 11: secretos y config
sensible fuera del código), con valores por defecto seguros para desarrollo:

```kotlin
// backend/src/main/kotlin/com/neverlate/backend/Config.kt
accessTokenExpiryMinutes = System.getenv("ACCESS_TOKEN_EXPIRY_MINUTES")?.toLongOrNull() ?: 15L,
refreshTokenExpiryDays   = System.getenv("REFRESH_TOKEN_EXPIRY_DAYS")?.toLongOrNull()   ?: 30L,
```

(documentados en `backend/.env.example`). El access token pasó de 24 h (11) a **15 min**.

---

## 4. El cliente: renovación silenciosa con un `Authenticator`

Ahora la parte que hace toda la magia invisible. En la 11, un `401` iba directo a logout. Ahora
queremos: ante un `401`, **renovar primero** y solo caer al login si la renovación **también** falla.

### 4.1 `Authenticator` ≠ `Interceptor`

OkHttp tiene **dos** puntos de extensión, y usar el correcto es media lección:

- El **`Interceptor`** (el `AuthInterceptor` de la 11) se ejecuta **antes** de cada petición. Su trabajo
  ahora se reduce a **poner** la cabecera `Authorization: Bearer <access>`. Nada más.
- El **`Authenticator`** (nuevo) lo llama OkHttp **específicamente cuando una respuesta vuelve `401`**.
  Recibe la respuesta fallida y **reintenta automáticamente** con la petición que le devuelvas; si
  devuelves `null`, se rinde y el `401` llega al llamante original. Es el sitio idiomático para "renueva
  el token y reintenta".

```kotlin
// app/src/main/java/com/neverlate/data/sync/TokenAuthenticator.kt
class TokenAuthenticator(
    private val refreshApi: AuthApi,          // ¡un cliente SIN este Authenticator! (§ 4.3)
    private val tokenStorage: TokenStorage,
    private val onRefreshFailed: () -> Unit,  // el camino de logout de la 11, ahora como fallback
) : Authenticator {

    private val refreshLock = Any()           // single-flight (§ 4.2)

    override fun authenticate(route: Route?, response: Response): Request? {
        // Acotar los reintentos: si la petición YA reintentada vuelve a dar 401, rendirse.
        if (responseCount(response) > 1) {
            onRefreshFailed()
            return null
        }
        val failedAccessToken = bearerToken(response.request)

        synchronized(refreshLock) {
            val currentAccessToken = tokenStorage.getAccessToken()
            if (currentAccessToken != null && currentAccessToken != failedAccessToken) {
                // Otro hilo ya refrescó mientras esperábamos el lock: reintenta con el token nuevo,
                // sin refrescar otra vez.
                return requestWithBearer(response.request, currentAccessToken)
            }
            val refreshToken = tokenStorage.getRefreshToken()
                ?: run { onRefreshFailed(); return null }   // sin refresh -> no hay nada que renovar

            return try {
                val newTokens = runBlocking { refreshApi.refresh(RefreshRequest(refreshToken)) }
                tokenStorage.saveTokens(newTokens.accessToken, newTokens.refreshToken)  // swap atómico
                requestWithBearer(response.request, newTokens.accessToken)              // reintento
            } catch (error: HttpException) {   // 401 invalid_refresh_token -> renovar es imposible
                onRefreshFailed()
                null
            } catch (error: IOException) {     // sin red -> rendirse sin cerrar sesión
                null
            }
        }
    }
}
```

Fíjate en los tres fallbacks distintos: **refresh 401** → logout (US-2); **sin refresh token** →
logout; **sin red** → rendirse *sin* cerrar sesión (un corte de red transitorio no debería echarte).

### 4.2 Renovaciones concurrentes: *single-flight*

Este es el problema fino que el spec (US-3) obliga a resolver. Durante un sync, **varias** peticiones a
`/tasks*` (push y pull) pueden estar en vuelo a la vez y **todas** recibir `401` al mismo tiempo. Si
cada una dispara su propio `/auth/refresh`… la primera rota el token, y las demás presentan un refresh
**ya consumido** → ¡el servidor cree que es **robo** y mata la sesión! Un falso positivo autoinfligido.

La defensa: `synchronized(refreshLock)`. Solo un hilo entra a la vez. El primero refresca y guarda el
par nuevo. Los que esperaban, al entrar, ven que **el token guardado ya no es el que falló** (la
comprobación `currentAccessToken != failedAccessToken`) y simplemente **reintentan con el nuevo**, sin
refrescar. Resultado: **un** solo `/auth/refresh` para toda la ráfaga.

> **¿Por qué `synchronized` y no un `Mutex` de corrutinas?** Porque `authenticate()` es un *callback*
> **síncrono** de OkHttp: se ejecuta en uno de los hilos del dispatcher de OkHttp, **no** dentro de una
> corrutina. Por eso el `refreshApi.refresh(...)` va envuelto en `runBlocking`: es correcto bloquear
> aquí porque no hay corrutina que suspender. (La revisión de seguridad verificó que esto no puede dar
> *deadlock*: el `runBlocking` bloquea un hilo del cliente de `/tasks`, pero espera I/O de **otro**
> cliente distinto —el de refresh— sin ciclo de vuelta al mismo pool.)

### 4.3 Evitar la recursión

Sutil pero crítico: la llamada `POST /auth/refresh` **no** puede pasar por este mismo `Authenticator`
(ni por el interceptor Bearer), o un `401` del propio refresh se recuraría infinitamente. Por eso
`refreshApi` se construye con un cliente OkHttp **desnudo** (`AuthNetwork.create`), sin authenticator ni
interceptor propios. Además el refresh se autentica con el token **en el cuerpo**, no con la cabecera
`Authorization` — justo funciona cuando el access ha caducado.

### 4.4 Logout que revoca en el servidor

`AuthRepository.logout()` ahora llama a `POST /auth/logout` para revocar el refresh en el servidor
(**best-effort**) y luego **siempre** limpia el estado local, aunque la llamada de red falle:

```kotlin
override suspend fun logout() {
    val refreshToken = tokenStorage.getRefreshToken()
    if (refreshToken != null) {
        try {
            authApi.logout(LogoutRequest(refreshToken))   // best-effort
        } catch (e: IOException) {
            // sin red: da igual, limpiamos local igualmente
        }
    }
    tokenStorage.clearSession()   // incondicional
}
```

---

## 5. Almacenamiento seguro, revisitado: atomicidad

Los **dos** tokens se guardan en el mismo `EncryptedSharedPreferences` respaldado por Keystore de la 11
—nunca en el DataStore en claro, nunca en logs—. Lo **nuevo** aquí es la **atomicidad** al rotar:

```kotlin
// app/src/main/java/com/neverlate/data/auth/TokenStorage.kt
override fun saveTokens(accessToken: String, refreshToken: String) {
    // Un único Editor: apply() escribe AMBOS o (rarísimo, fallo de disco) ninguno.
    prefs.edit()
        .putString(KEY_ACCESS_TOKEN, accessToken)
        .putString(KEY_REFRESH_TOKEN, refreshToken)
        .apply()
}
```

¿Por qué importa? Cuando `saveTokens` se llama, el servidor **ya** rotó: el refresh que acabábamos de
presentar está **muerto**. Si aquí escribiéramos el access nuevo pero, por una muerte del proceso, **no**
el refresh nuevo, la próxima renovación presentaría un refresh que el servidor ya invalidó → lo trataría
como **reuso** → sesión muerta. Un `Editor` de `SharedPreferences` agrupa todos los `put()` en un único
`apply()` atómico: o entran los dos, o se queda el par viejo intacto. Nunca una mezcla.

---

## 6. Lo que encontró la revisión de seguridad

Esta feature pasó por el agente de seguridad, que encontró y arregló **tres** defectos reales — vale la
pena verlos como ejemplos de bugs sutiles de auth:

1. **Carrera TOCTOU en la rotación (§ 3.5).** El *check-then-set* del `consumedAt` permitía a dos
   refrescos concurrentes rotar el mismo token. Arreglado con `markConsumedIfUnconsumed` (escritura
   condicional atómica).
2. **Orden reuso-vs-caducidad.** Comprobábamos caducidad **antes** que reuso. Un ladrón podía esperar a
   que el token robado caducara y **luego** reusarlo: caía en la rama "caducado" (un `401` normal) sin
   disparar el kill de familia, dejando vivo el token hermano del usuario legítimo. Arreglado
   comprobando **reuso antes que caducidad** (fíjate en el orden de la § 3.3).
3. **Logging de cuerpos de auth.** El `HttpLoggingInterceptor` estaba a nivel `BODY` en debug, y
   `redactHeader` solo tapa cabeceras, no cuerpos: la contraseña (en register/login) y **ambos tokens**
   (en las respuestas) acababan en Logcat en claro. Arreglado bajando el cliente de auth a `BASIC`
   (método/URL/estado, sin cuerpos); el cliente de `/tasks` no lleva secretos en el cuerpo y sigue
   igual.

La lección: la auth es un campo de minas de detalles. El diseño puede ser correcto y aún así tener
carreras de concurrencia, órdenes de comprobación traicioneros y fugas por logging.

---

## 7. Cómo probarlo

Los tests cubren ambos lados sin necesidad de Docker (backend con repos en memoria) ni Room (cliente con
`MockWebServer`):

**Backend** (`backend/src/test/…/auth/`): `AuthRoutesTest` (nivel HTTP con Ktor `testApplication`) y
`AuthServiceTest` (nivel unitario). Cubren: register/login devuelven familias **distintas**; refresh
feliz (par nuevo, viejo rechazado); **reuso mata la familia** —incluido el hermano recién rotado— y las
familias independientes sobreviven; refresh caducado → `401`; logout revoca y es idempotente; y **el
test estrella**: dos hilos reales con `CyclicBarrier` llamando a `refresh()` con el mismo token → exacto
**uno** gana, el otro es reuso, la familia acaba muerta (fija el arreglo de la § 3.5).

**Cliente** (`app/src/test/…/sync/TokenAuthenticatorTest.kt`) con `MockWebServer`: **US-1** `401` →
refresh → reintento de la **misma** petición con el token nuevo; **US-2** refresh que da `401` → se
rinde y `onRefreshFailed` se dispara una vez, sin bucle; **US-3** 6 `401` concurrentes → **exactamente
un** `/auth/refresh`; y el swap atómico de tokens.

```bash
cd backend && ./gradlew test        # backend: 24 tests
./gradlew :app:testDebugUnitTest    # app: 182 tests
```

---

## 8. Resumen

- Un único JWT eterno se parte en **access token corto (15 min, stateless)** + **refresh token largo
  (30 días, stateful)**: el que viaja siempre caduca pronto; el de vida larga viaja casi nunca y se
  puede **revocar**.
- El **contrato primero**: `POST /auth/refresh`, `POST /auth/logout`, y el par de tokens en
  register/login.
- El backend gana **estado**: una tabla de refresh tokens (hasheados), **rotación** en cada uso,
  **revocación** en logout y **detección de reuso** acotada por **familia** — más una **carrera TOCTOU**
  cerrada con una escritura condicional atómica.
- El cliente renueva en silencio con un **`Authenticator`** de OkHttp (distinto del interceptor):
  intercepta el `401`, refresca **una sola vez** (*single-flight* con `synchronized`), reintenta la
  petición original, y solo cae al login si el refresh también falla. La llamada de refresh va por un
  cliente **desnudo** para no recurarse.
- Ambos tokens siguen en **Keystore** (`EncryptedSharedPreferences`), reemplazados de forma **atómica**.
- La **revisión de seguridad** cazó tres bugs reales (TOCTOU, orden reuso/caducidad, logging de
  cuerpos): recordatorio de que la auth vive en los detalles.
- Todo entra **por detrás de los *seams* de la 11** (`AuthRepository`, `TokenStorage`, el interceptor):
  `SyncEngine`, los ViewModels y la UI no se enteran.

---

## Documentación oficial

- **Refresh tokens (OAuth 2.0)** — [OAuth 2.0 refresh tokens](https://oauth.net/2/refresh-tokens/)
- **JWT** — [Introduction to JWT](https://jwt.io/introduction)
- **`Authenticator` de OkHttp** — [Handling authentication](https://square.github.io/okhttp/recipes/#handling-authentication-kt-java)
  · [`Authenticator` (referencia)](https://square.github.io/okhttp/4.x/okhttp/okhttp3/-authenticator/)
- **Concurrencia y estado compartido (Kotlin)** — [Shared mutable state and concurrency](https://kotlinlang.org/docs/shared-mutable-state-and-concurrency.html)
- **Android Keystore** — [Android Keystore system](https://developer.android.com/privacy-and-security/keystore)
