# Lección 13 — Modo invitado y fusión de datos al iniciar sesión

> Objetivo: que la app **se pueda usar sin cuenta** (modo invitado / local-only) —crear, editar,
> completar y borrar tareas contra Room, sin ninguna puerta de acceso obligatoria— y que, cuando ese
> invitado **se registra o inicia sesión**, sus tareas locales **se fusionen** con la cuenta del
> servidor sin perderse ni duplicarse. En la lección 11 dejamos esto **diferido a propósito**: allí la
> cuenta era obligatoria (la app abría en la puerta de login) justamente para que el motor de sync nunca
> tuviera que reconciliar *tareas locales preexistentes* contra una *cuenta recién estrenada*. Aquí
> levantamos esa restricción. La sorpresa agradable —y la lección de diseño más importante— es **lo poco
> que hay que escribir**: una tarea de invitado ya tiene exactamente la forma que el motor de la 11 llama
> "huérfana" (`serverId == null`), y la adopción resulta ser **el `push` que estaba aplazado, ejecutándose
> por fin**. Reutilizamos el *outbox*, el `clientRef` idempotente, el *last-write-wins*, el *seam*
> `AuthRepository` y el `SyncEngine` de la 11. **Extendemos, no duplicamos.**

## Conceptos que aprendes aquí

Partiendo de las lecciones 11 (sync offline-first: outbox, `serverId`, `clientRef`, tombstones,
last-write-wins) y 12 (par access/refresh token, `Authenticator`, estado de auth):

- **Datos sin dueño de servidor conviviendo con datos de cuenta.** Modelar una tarea de invitado como
  una fila "huérfana" (`serverId == null`) y por qué **ese único flag basta** para distinguir lo que
  nunca se ha sincronizado — sin columna nueva ni migración de base de datos.
- **Fusión (migración) de datos al hacer login.** Cómo la adopción **reutiliza** el outbox + la
  idempotencia por `clientRef` de la 11: las creaciones que el invitado dejó encoladas se empujan a la
  cuenta **sin pérdidas y sin duplicados**, y se reconcilian contra lo que ya hubiera en el servidor por
  last-write-wins. Y lo poco que cuesta cuando el motor de sync se diseñó con las *costuras* correctas.
- **Un estado de auth de tres caras.** Ampliar una [`sealed
  interface`](https://kotlinlang.org/docs/sealed-classes.html) (`LoggedOut` / `LoggedIn`) con un
  tercer caso, `Guest`, y cómo la [navegación
  reactiva](https://developer.android.com/develop/ui/compose/navigation) mapea cada estado a un
  grafo — más *por qué* mantenemos `Guest` (elegido, usable) y `LoggedOut` (involuntario, con puerta)
  **distintos**.
- **Decisiones de producto sobre la fusión.** Fusión silenciosa vs. preguntar; por qué el logout debe
  **borrar** en vez de conservar (evitar duplicados y fugas entre cuentas); y por qué **no** hay
  de-duplicación por contenido. Razonar sobre riesgos de pérdida y duplicación de datos, no solo escribir
  código.

---

## 1. El punto de partida: por qué la 11 obligaba a tener cuenta

En la lección 11 la app abría en una **puerta de acceso** (login/register). No podías ver tus tareas sin
iniciar sesión. Aquello fue una simplificación **deliberada**, y merece la pena entender por qué antes de
quitarla:

Si un dispositivo *desconectado* siempre tiene la caché vacía (porque nunca dejamos crear nada sin
cuenta), entonces al hacer login **no hay nada local que reconciliar**. El primer `pull` baja las tareas
de la cuenta y ya está. El motor de sync nunca se enfrenta a la pregunta espinosa: *"tengo 5 tareas
locales y la cuenta ya tiene 3 en el servidor — ¿qué hago?"*. La 11 evitó esa pregunta cerrando la
puerta.

El modo invitado la reabre. Ahora sí puede haber tareas locales preexistentes cuando llegas al login, y
hay que **fusionarlas**. Todo el reto de esta lección está en resolver esa fusión **sin reinventar** el
motor de sync — reutilizando piezas que la 11 ya dejó montadas.

---

## 2. La observación que lo cambia todo: una tarea de invitado ya es "huérfana"

Antes de escribir una sola línea, miremos qué forma tiene una tarea que un usuario logueado creó pero que
**aún no se ha subido** al servidor. En la 11, `OutboxTaskRepository` guarda una tarea nueva así:

- la fila `Task` en Room con `serverId == null` y `syncState = PENDING_CREATE`,
- una fila en el **outbox** con `operation = CREATE` y un **`clientRef`** estable (un UUID) para la
  idempotencia.

Y `SyncEngine.push()` recorre el outbox, hace `POST /tasks` por cada `CREATE`, y al recibir el `id` del
servidor rellena `serverId` y marca la fila como `SYNCED`.

Ahora la pregunta clave: **¿en qué se diferencia una tarea de invitado de esa tarea "pendiente de
crear"?** La respuesta es: **en nada, a nivel de fila.** Ambas son una tarea con `serverId == null` y una
fila `CREATE` en el outbox esperando a ser empujada. La única diferencia es que el invitado **todavía no
tiene token**, así que el `push` no puede correr aún.

Y aquí está la segunda pieza que la 11 ya nos regaló. `SyncEngine.syncNow()` empieza así:

```kotlin
suspend fun syncNow(): SyncStatus = mutex.withLock {
    if (tokenStorage.getAccessToken() == null) {
        // Sin sesión: no hay nada que sincronizar, y llamar a la API solo ganaría un 401.
        return@withLock SyncStatus.Idle
    }
    // … push() y pull() …
}
```

Es decir: **mientras no haya token, el sync es un no-op**. El invitado puede crear, editar y borrar
tareas todo lo que quiera; cada guardado dispara un `schedulePush()` que se topa con este `early-return` y
no toca la red. El outbox del invitado no es más que una **cola de creaciones aplazadas**.

> **La idea central de la lección, en una frase:** la fusión al iniciar sesión no es un subsistema
> nuevo. Es el `push` que llevaba aplazado desde que el invitado creó sus tareas, **ejecutándose por fin**
> en cuanto aparece un token.

Por eso esta feature **no necesita ninguna columna nueva ni subir la versión de la base de datos**. El
`Task` de la 11 ya lleva `serverId`, `updatedAt`, `syncState` y `deleted`; con `serverId == null` nos
basta para saber "esto es una huérfana que nunca se ha subido". Cero migración → cero riesgo de la
pérdida de datos destructiva que sí tuvimos en features anteriores.

---

## 3. Un estado de auth de tres caras

La 11 modeló la sesión con una `sealed interface` de **dos** casos. La 13 le añade un tercero. Este es el
corazón conceptual de la feature.

### 3.1 El tercer caso: `Guest`

En [`AuthRepository.kt`](../app/src/main/java/com/neverlate/data/auth/AuthRepository.kt):

```kotlin
sealed interface AuthState {
    data object LoggedOut : AuthState
    data object Guest : AuthState          // ← nuevo en la feature 13
    data class LoggedIn(val userId: Long, val email: String) : AuthState
}
```

Las tres caras y qué significa cada una:

| Estado | Significado | Disparador en arranque | Navegación |
|--------|-------------|------------------------|------------|
| `LoggedIn(userId, email)` | Sesión válida; sync activo. | Hay un access token guardado. | Grafo principal, sync **on**. |
| `Guest` | Sin cuenta, usando la app en local; sync inactivo. | **Por defecto** cuando no hay token. | Grafo principal, sync **off**; login accesible desde Ajustes. |
| `LoggedOut` | Una sesión terminó **involuntariamente** (un `401` cuyo refresh falló). | *(ya no es el defecto de arranque)* | Puerta de acceso (login/register). |

El cambio que **quita la puerta obligatoria** es una sola línea. En `readInitialAuthState()`, la rama
"no hay token" pasa de devolver `LoggedOut` a devolver `Guest`:

```kotlin
private fun readInitialAuthState(): AuthState {
    val token = tokenStorage.getAccessToken()
    // …
    return if (token != null && userId != null && email != null) {
        AuthState.LoggedIn(userId, email)
    } else {
        // Feature 13: no tener sesión ya no significa "muestra la puerta de login" — significa
        // que la app abre directa en un modo invitado usable y local (US-1). LoggedOut queda
        // reservado para el caso involuntario (ver notifyUnauthorized más abajo).
        AuthState.Guest
    }
}
```

### 3.2 ¿Por qué `Guest` y `LoggedOut` separados, si "ninguno" tiene sesión?

Es tentador colapsarlos: al fin y al cabo, en ambos falta la sesión. Pero significan cosas **distintas**
para el usuario, y esa diferencia decide a dónde lo llevas:

- **`Guest`** = *"no quiero cuenta ahora mismo"*. Es un modo **elegido y usable**. Lo mandas a la app.
- **`LoggedOut`** = *"tu sesión acaba de caducar"*. Sus datos están en el servidor y seguramente los
  quiere de vuelta. Lo mandas a la **puerta de login**.

Si los fusionáramos, o bien reinstauramos una puerta forzada para toda instalación nueva (matando la
feature), o bien tiramos a un usuario cuya sesión expiró de golpe a un modo invitado **vacío**, perdiendo
la vista de sus datos del servidor. Mantenerlos separados es una decisión de diseño, no un descuido.

### 3.3 Partir el "cerrar sesión" en dos caminos

En la 11, `logout()` hacía el trabajo de limpieza y terminaba en `LoggedOut`, y `notifyUnauthorized()`
(el *fallback* cuando el refresh de la 12 falla) simplemente llamaba a `logout()`. Ahora esos dos caminos
deben aterrizar en **estados distintos**, así que extraemos la limpieza a un ayudante privado y dejamos
que cada camino decida su estado final:

```kotlin
// Cierre de sesión iniciado por el usuario (desde Ajustes). Limpia y aterriza en Guest.
override suspend fun logout() = withContext(Dispatchers.IO) {
    clearLocalSession()
    _authState.value = AuthState.Guest
}

// Llamado por AuthInterceptor cuando un 401 llega y la renovación silenciosa ya falló (feature 12).
// Comparte la misma limpieza, pero aterriza en LoggedOut (fin de sesión involuntario).
fun notifyUnauthorized() {
    scope.launch {
        clearLocalSession()
        _authState.value = AuthState.LoggedOut
    }
}

// Las tripas compartidas: revoca el refresh best-effort y borra sesión + tareas + outbox + cursor.
// Deliberadamente NO decide en qué AuthState aterrizar: eso es cosa de cada llamante.
private suspend fun clearLocalSession() = withContext(Dispatchers.IO) {
    val refreshToken = tokenStorage.getRefreshToken()
    if (refreshToken != null) {
        try { api.logout(LogoutRequest(refreshToken)) } catch (error: IOException) { /* offline: da igual */ }
    }
    tokenStorage.clearSession()
    database.withTransaction {
        database.taskDao().clearAll()
        database.outboxDao().clearAll()
    }
    userPreferencesRepository.saveSyncCursor(0L)
}
```

Fíjate en el patrón: `clearLocalSession()` hace **exactamente lo mismo** que hacía el `logout()` de la 12
(revoca el refresh best-effort, limpia la sesión, borra la caché local propiedad del backend). Lo **único**
que cambia entre los dos llamantes es el `AuthState` final. Es un refactor de "extraer método" clásico,
motivado por la necesidad de dos aterrizajes distintos.

---

## 4. La fusión: la adopción es el `push` aplazado ejecutándose

Ya lo adelantamos en la § 2: adoptar las tareas del invitado no es código nuevo de sync. Es hacer que el
motor **corra** en cuanto haya token. Veamos las dos formas —redundantes a propósito— en que se dispara.

### 4.1 Disparador primario: la recomposición al cambiar de estado

En [`AppNavHost.kt`](../app/src/main/java/com/neverlate/ui/navigation/AppNavHost.kt), el `when(authState)`
gana una rama para `Guest`, y **tanto `Guest` como `LoggedIn` renderizan el mismo `MainAppNavHost`**:

```kotlin
when (authState) {
    is AuthState.LoggedOut -> AuthGateNavHost(authRepository = authRepository)

    is AuthState.Guest -> MainAppNavHost(/* … */)     // ← rama separada, a propósito

    is AuthState.LoggedIn -> MainAppNavHost(/* … */)
}
```

Y `MainAppNavHost` tiene, desde la 11, este disparador de sync "al abrir la app":

```kotlin
LaunchedEffect(Unit) { taskRepository.refreshFromServer() }
```

Aquí hay una sutileza de Compose que **hay que entender bien**, porque es fácil romperla sin querer.
Compose identifica el estado de una composición por su **sitio de llamada** (*call site*), no por qué
función se llama. Como `Guest` y `LoggedIn` están en **ramas distintas** del `when`, una transición
`Guest → LoggedIn` cambia qué rama está activa: Compose **desecha** la instancia vieja de `MainAppNavHost`
y compone una **nueva** en el sitio de la otra rama — con su `rememberNavController()` fresco y su
`LaunchedEffect(Unit)` fresco. Ese `LaunchedEffect` recién disparado es justo lo que **vacía el outbox del
invitado** al iniciar sesión.

> ⚠️ **La trampa:** si fusionaras las dos ramas en una sola (`is AuthState.Guest, is AuthState.LoggedIn ->
> MainAppNavHost(…)`), mantendrías **la misma composición viva** a través de la transición. El
> `LaunchedEffect(Unit)` **no** volvería a dispararse, y la adopción **no ocurriría** al iniciar sesión.
> Mantener las ramas separadas no es repetición ociosa: es lo que hace que el disparador funcione. Por eso
> lleva un comentario largo en el código.

### 4.2 Disparador secundario: `onAuthenticated` (cinturón y tirantes)

Depender de un detalle de recomposición para algo tan importante como "no perder las tareas del usuario"
da un poco de vértigo. Así que añadimos un segundo disparador **explícito** que no depende de Compose.
`AuthRepositoryImpl` expone una lambda:

```kotlin
var onAuthenticated: (suspend () -> Unit)? = null
```

que se invoca justo después de que un `register`/`login` con éxito guarde la sesión:

```kotlin
private suspend fun authenticate(call: suspend () -> AuthResponse): AuthResult = withContext(Dispatchers.IO) {
    try {
        val response = call()
        tokenStorage.saveSession(response.accessToken, response.refreshToken, response.user.id, response.user.email)
        _authState.value = AuthState.LoggedIn(response.user.id, response.user.email)
        // Adopción (feature 13, US-2/US-3): lo que el invitado encoló en el outbox estando sin token
        // ahora es empujable, porque el token ya está persistido arriba.
        onAuthenticated?.invoke()
        AuthResult.Success
    } // … catch …
}
```

Y [`MainActivity`](../app/src/main/java/com/neverlate/MainActivity.kt) la conecta al motor de sync **una
vez que `taskRepository` existe** (por eso se asigna aquí y no en el constructor de `AuthRepositoryImpl` —
evita un ciclo de orden de construcción):

```kotlin
authRepositoryImpl.onAuthenticated = { taskRepository.refreshFromServer() }
```

**¿No es peligroso disparar dos veces?** No. `SyncEngine.syncNow()` está protegido por un `Mutex`, y una
segunda llamada casi inmediata simplemente encuentra que ya no queda nada que empujar ni traer. Los dos
disparadores son **seguros y redundantes**: si uno fallara, el otro cubre.

### 4.3 Qué pasa exactamente en el `push` de adopción

Con el token ya presente, `push()` recorre el outbox del invitado y, por cada `CREATE`:

1. hace `POST /tasks` con el `clientRef` estable de esa tarea,
2. el servidor crea la tarea y devuelve su `id`,
3. reconciliamos la fila local: `serverId = id`, `syncState = SYNCED`, borramos su fila del outbox.

Luego `pull()` (`GET /tasks?since=0`) baja **el resto** de tareas de la cuenta (las que ya existían en el
servidor desde otro dispositivo). Como el `logout` reseteó el cursor a `0` y el invitado nunca lo avanzó,
el primer `pull` tras iniciar sesión es un **pull completo**. Resultado: las tareas del invitado y las de
la cuenta **conviven**, sin pérdidas.

### 4.4 Por qué no hay duplicados: la idempotencia por `clientRef` de la 11

El riesgo obvio de "subir tareas al iniciar sesión" es **duplicarlas** si el `push` se reintenta (por
ejemplo, un `ack` perdido por la red). Aquí no reinventamos nada: nos apoyamos en la garantía que la 11 ya
construyó en el servidor. Del contrato ([`docs/api/contract.md`](../docs/api/contract.md), § 3):

> El cliente genera un **`clientRef`** para la idempotencia: si se hace `POST` del mismo `clientRef` dos
> veces, el servidor devuelve la tarea **ya creada** en vez de crear un duplicado.

Como cada tarea de invitado ya nació con un `clientRef` estable (§ 2), empujarla dos veces es inofensivo:
la primera la crea, la segunda devuelve la misma. **La adopción hereda gratis la idempotencia del sync
normal.** No hay lógica de fusión especial que escribir.

---

## 5. Llegar al login desde el modo invitado

Un invitado tiene que poder iniciar sesión **sin** que se le fuerce una puerta. La solución reutiliza los
mismos `LoginRoute`/`RegisterRoute` de la 11, pero como destinos **dentro** del grafo principal,
alcanzables desde **Ajustes**:

```kotlin
// Feature 13: login/register alcanzables desde Ajustes mientras AuthState.Guest.
composable(Routes.LOGIN) {
    LoginRoute(
        authRepository = authRepository,
        onRegisterClick = { navController.navigate(Routes.REGISTER) },
        onBack = { navController.popBackStack() },   // ← vuelve a Ajustes si el invitado cancela
    )
}
composable(Routes.REGISTER) {
    RegisterRoute(authRepository = authRepository, onBack = { navController.popBackStack() })
}
```

Hay dos "copias" de las mismas pantallas, y la diferencia es intencionada:

- La copia dentro de `AuthGateNavHost` (la puerta obligatoria, para `LoggedOut`) **no** tiene flecha de
  volver — no hay a dónde volver, es una puerta.
- La copia dentro de `MainAppNavHost` (para el invitado) **sí** tiene `onBack`, que hace `popBackStack()`
  a Ajustes. Un invitado que se arrepiente de iniciar sesión vuelve a la app, no se queda atrapado.

Por eso `LoginRoute`/`LoginScreen` ganan un `onBack: (() -> Unit)?` **opcional**: `null` en la puerta (sin
flecha), y cableado en la copia de Ajustes. Un parámetro nullable modela limpiamente "esta pantalla a
veces tiene botón de volver y a veces no".

¿Y cómo navega el usuario a la app tras iniciar sesión con éxito? **No navega nadie explícitamente.** Al
tener éxito, `authState` pasa a `LoggedIn`, y `AppNavHost` reacciona componiendo un `MainAppNavHost` nuevo
(§ 4.1). Es el mismo patrón reactivo de la 11: la UI de auth **nunca navega**, solo cambia el estado y el
grafo se recompone solo.

### 5.1 Ajustes: "Cerrar sesión" o "Iniciar sesión", según el estado

`SettingsUiState` gana un campo `authState`, y la sección de cuenta muestra una acción u otra:

```kotlin
data class SettingsUiState(
    // … themeMode, remindersEnabled, reminderLeadMinutes …
    val authState: AuthState = AuthState.Guest,
)
```

El `SettingsViewModel` ahora colecciona **dos** flujos independientes (las preferencias y el `authState`),
y aquí hay un detalle sutil de Kotlin/coroutines que merece un comentario:

```kotlin
// Dos fuentes independientes alimentan un mismo uiState, así que cada colector debe FUSIONAR
// sobre el valor existente (copy), no reemplazarlo entero — si no, el último flujo en emitir
// borraría en silencio la aportación del otro.
viewModelScope.launch {
    repository.userPreferences.collect { prefs ->
        _uiState.update { it.copy(themeMode = prefs.themeMode, /* … */) }
    }
}
viewModelScope.launch {
    authRepository.authState.collect { authState ->
        _uiState.update { it.copy(authState = authState) }
    }
}
```

Usar `_uiState.value = SettingsUiState(...)` en cada colector (como hacía la 11 con una sola fuente) sería
un **bug**: el colector del `authState` machacaría el tema y los recordatorios con sus valores por defecto,
y viceversa. Con dos fuentes, `update { it.copy(...) }` es obligatorio.

---

## 6. Las decisiones de producto (y por qué importan tanto como el código)

Esta feature no es solo mecánica de sync: son varias decisiones de producto que la 11 se ahorró al cerrar
la puerta. Las cuatro se documentaron y resolvieron en el spec
([`docs/specs/2026-07-07-guest-mode.md`](../docs/specs/2026-07-07-guest-mode.md)) **antes** de programar.

### 6.1 Fusión silenciosa vs. preguntar

**Decisión: silenciosa siempre.** Al registrarte (cuenta vacía) no hay nada que reconciliar. Al iniciar
sesión en una cuenta que ya tenía tareas, tus tareas locales aparecen fusionadas sin preguntarte. Es lo
más simple y mantiene la lección centrada en la **mecánica** de la adopción idempotente, no en un diálogo
de confirmación. El compromiso aceptado: alguien que entra en una cuenta ajena con muchas tareas verá las
suyas mezcladas sin que se le pregunte — aceptable porque **nada se pierde ni se sobrescribe** (las de
invitado son creaciones nuevas; las de la cuenta llegan por `pull`), y encaja con el lema offline-first de
**nunca descartar los datos del usuario**.

### 6.2 Qué hace el logout con tareas ya adoptadas

**Decisión: borra (tareas + outbox + cursor) y aterriza en `Guest`.** Este es el riesgo central de la
feature, así que el razonamiento importa:

- **Por qué NO conservarlas:** si el logout dejara las tareas (antes sincronizadas) en Room como huérfanas
  (`serverId == null`), el siguiente inicio de sesión las **re-adoptaría como creaciones nuevas** —
  **duplicándolas** en el servidor. Y conservarlas *con* su `serverId` es peor: el dispositivo podría
  entrar luego en una cuenta **distinta** y filtrar/adoptar las tareas de la primera. Borrar al cerrar
  sesión es la **única** regla segura sin importar *qué* cuenta inicie sesión después.
- **Por qué es aceptable:** las tareas están a salvo en el servidor; la caché se repuebla en el siguiente
  login con el `pull` completo. Es exactamente el mismo borrado que la 11 ya hacía; lo **único** que
  cambió es el estado de aterrizaje (`LoggedOut` → `Guest`), para que la app siga siendo usable.

### 6.3 Un invitado que nunca crea cuenta

**Decisión: sus tareas se quedan en local para siempre, usables, nunca subidas, nunca caducadas.** Es el
comportamiento natural (Room persiste; el sync hace `early-return` sin token). No hace falta código extra
más allá de **no** borrar los datos del invitado al arrancar.

### 6.4 Sin de-duplicación por contenido

**Decisión: ninguna.** Si un invitado tiene "Comprar leche" y la cuenta ya tenía otra "Comprar leche",
**sobreviven las dos**. La de-duplicación por contenido (comparar título/fecha) es ambigua (¿son la misma
tarea dos "Comprar leche"?) y queda fuera del alcance didáctico. Se documenta como compromiso conocido.

---

## 7. Qué NO cambió (y por qué es una buena señal)

Una parte importante de la lección es lo que **no** tocamos:

- **El backend y el contrato de API: cero cambios.** La adopción usa el `POST /tasks` idempotente y el
  `GET /tasks?since=` que ya existían. El servidor **ni siquiera puede distinguir** una adopción de
  invitado de un dispositivo normal que estaba desconectado y se pone al día — y ése es justo el punto. La
  regla "el contrato primero" de la 11 **no se dispara** aquí.
- **`SyncEngine`, `OutboxTaskRepository`: sin cambios.** Su `early-return` sin token *es* el comportamiento
  del invitado; su distinción `CREATE`-vs-`UPDATE` por `serverId` es justo en lo que se apoya la adopción.
- **Base de datos: sin columna nueva, sin subir versión, sin migración** (§ 2).
- **Widget (05), notificación (06), recordatorios (09): funcionan igual en modo invitado.** Todos leen la
  caché de Room, que está poblada en cualquier estado de auth. Ninguno pide token ni sesión. Eran ya
  *auth-agnósticos*, y por eso el invitado no es una experiencia de segunda.

Que una feature "amplíe notablemente la superficie de sync" (como decía el prompt) y sin embargo toque tan
poco código es la recompensa de haber diseñado la 11 con las *costuras* correctas: el outbox, el
`clientRef`, el *seam* `TaskRepository`/`AuthRepository`. **Extender, no duplicar** no es un eslogan; es lo
que hace que la fusión quepa en un puñado de archivos.

---

## 8. Los tests

Los tests son JVM puros (`./gradlew :app:testDebugUnitTest`) y reutilizan los *dobles* de sync de la 11
(`SyncTestDoubles.kt`, con un `TasksApi` falso que **keyea las creaciones por `clientRef`** — justo lo que
necesitamos para probar la idempotencia).

- **Mapeo de estados en arranque** (`AuthRepositoryTest`): sin token → `Guest` (antes `LoggedOut`);
  `logout()` → `Guest`; y `notifyUnauthorized()` → `LoggedOut` (haciendo además el borrado local).
- **Ajustes** (`SettingsViewModelTest`): `uiState.authState` refleja `Guest` vs. `LoggedIn`.
- **Adopción sin duplicados** (`GuestAdoptionTest`, nuevo): un invitado crea N tareas huérfanas; tras
  iniciar sesión y sincronizar, las N se crean en el servidor **exactamente una vez** y las filas locales
  reconcilian a `serverId`/`SYNCED`. Reintentar el `push` **no** duplica (idempotencia por `clientRef`).
- **Fusión junto a tareas existentes** (`GuestAdoptionTest`): invitado con tareas + cuenta con tareas en el
  servidor → conviven, sin pérdidas y sin de-duplicación por contenido (§ 6.4).
- **El riesgo estrella — logout → re-login en otra cuenta** (`GuestAdoptionTest`, test de integración con
  `AuthRepositoryImpl` + `OutboxTaskRepository` + `SyncEngine` reales): invitado → login (adopta) → logout
  (borra, aterriza en `Guest`) → login como una cuenta **distinta** → las tareas de la primera **no**
  reaparecen ni se re-empujan. Cierra el riesgo de la § 6.2.

```bash
./gradlew :app:testDebugUnitTest    # app: 190 tests
```

---

## 9. Resumen

- Se puede **usar la app sin cuenta** (modo invitado): CRUD local completo contra Room, sin puerta
  obligatoria. Una tarea de invitado ya tiene la forma **huérfana** (`serverId == null`) que el motor de la
  11 espera — sin columna nueva ni migración.
- La **fusión al iniciar sesión no es un subsistema nuevo**: es el `push` del outbox que estaba aplazado
  (el sync hace `early-return` sin token) **ejecutándose por fin** cuando aparece el token. Hereda gratis
  la **idempotencia por `clientRef`** de la 11, así que no hay duplicados.
- Un **estado de auth de tres caras** (`Guest` / `LoggedOut` / `LoggedIn`): `Guest` es el nuevo defecto de
  arranque sin token; `LoggedOut` queda reservado para el fin de sesión **involuntario**. Se mantienen
  distintos a propósito, y `logout` vs. `notifyUnauthorized` comparten la misma limpieza pero aterrizan en
  estados distintos.
- El disparador de adopción es **doble y redundante**: la recomposición de `AppNavHost` al pasar de `Guest`
  a `LoggedIn` (¡ojo con fusionar las ramas del `when`!) **más** un `onAuthenticated` explícito. El `Mutex`
  del `SyncEngine` hace inofensivo dispararlo dos veces.
- Las **decisiones de producto** se razonaron antes de programar: fusión silenciosa, logout que borra (para
  evitar duplicados y fugas entre cuentas), invitado que nunca se registra conserva todo, y sin
  de-duplicación por contenido.
- **Cero cambios en backend, contrato, base de datos, dependencias o permisos.** Toda la feature entra por
  detrás de las *costuras* de la 11 — la recompensa de haberlas diseñado bien.

---

## Documentación oficial

- **`sealed interface` / `sealed class`** — [Sealed classes and interfaces (Kotlin)](https://kotlinlang.org/docs/sealed-classes.html)
- **Navegación reactiva en Compose** — [Navigation with Compose](https://developer.android.com/develop/ui/compose/navigation)
- **Efectos y `LaunchedEffect`** — [Side-effects in Compose](https://developer.android.com/develop/ui/compose/side-effects)
- **Idempotencia y `Mutex`** — [Shared mutable state and concurrency (Kotlin)](https://kotlinlang.org/docs/shared-mutable-state-and-concurrency.html)
- **Sincronizar con WorkManager** — [WorkManager](https://developer.android.com/topic/libraries/architecture/workmanager)
