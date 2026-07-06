# Lección 11 — Datos en una BBDD remota: backend, auth y sincronización offline-first

> Objetivo: que las tareas dejen de vivir **solo** en el dispositivo y pasen a ser **propiedad de un
> backend** con su propia base de datos, de modo que la misma cuenta vea sus tareas en varios
> dispositivos. Es, con diferencia, la feature más grande del tutorial: aparece por primera vez un
> **servidor** (Kotlin + Ktor + Postgres), un **contrato de API** como fuente única de verdad,
> **autenticación** con **token JWT**, **almacenamiento seguro** de ese token, y —el corazón de todo—
> la **sincronización offline-first** con Room como caché local, una **cola de salida (outbox)** y
> **resolución de conflictos**. Reutilizamos casi todo lo anterior: el *seam* `TaskRepository` (04),
> Room y sus `@Entity`/`@Dao`/`@Database` (04), Retrofit + OkHttp + `kotlinx.serialization` y la idea
> de **DTO ≠ modelo de dominio** (10), WorkManager (09), y la lógica **pura y testeable** al estilo de
> `ReminderPlanning.kt` (09).

## Conceptos que aprendes aquí

Partiendo de las lecciones 04 (Room, el *seam* de repositorio tras una interfaz), 09 (WorkManager,
corrutinas en segundo plano, lógica pura extraída) y 10 (Retrofit/OkHttp, DTO de red vs. modelo de
dominio, Room como única fuente de verdad):

- **Qué es un backend y un contrato de API.** Por qué el contrato (`docs/api/contract.md`) es la
  **fuente única de verdad** que desacopla cliente y servidor, y cómo cada lado se construye contra él.
- **Autenticación con tokens (JWT).** Registro/login, qué es un token, cómo se adjunta a cada petición
  (`Authorization: Bearer …`) con un **interceptor de OkHttp**, y por qué es el **servidor** —nunca el
  cliente— quien verifica la identidad.
- **Almacenamiento seguro de credenciales.** Por qué un token **no** puede vivir en un DataStore /
  `SharedPreferences` en claro, y cómo guardarlo con **`EncryptedSharedPreferences`** respaldado por el
  **Android Keystore**.
- **Sync offline-first.** Room como fuente de verdad extendida a las **escrituras**; la **outbox**;
  **push/pull**; **ids locales vs. ids de servidor** + `clientRef`; **tombstones** para borrados;
  **last-write-wins** y su función de merge **pura**.
- **Postura de seguridad.** La propiedad de los datos, la validación y la autorización viven en el
  **backend**; el cliente es no confiable.

---

## 1. El backend y el contrato de API

Hasta la lección 10 la app solo **leía** de la red (los artículos). Ahora aparece un **servidor
propio** que es **dueño** de las tareas: valida, autoriza y decide. Vive en un sub-proyecto hermano,
`backend/`, escrito también en **Kotlin** (con **Ktor**) para no tener que aprender un lenguaje nuevo
además de todos los conceptos nuevos. Su base de datos es **Postgres**, y todo arranca en local con
`docker compose up`.

Antes de escribir una sola línea de cliente o de servidor, escribimos el **contrato**:
[`docs/api/contract.md`](../docs/api/contract.md). Es un documento que fija:

- Los **endpoints** (`POST /auth/register`, `POST /auth/login`, `GET /tasks?since=`, `POST /tasks`,
  `PATCH /tasks/{id}`, `DELETE /tasks/{id}`).
- La **forma de los datos en el cable** (el `TaskDto`), los **códigos de estado** y una **forma de
  error** común (`{ "error": { "code", "message" } }`).
- Cómo funciona la **auth** (token Bearer) y la semántica de **sync**.

¿Por qué tanto ceremonial? Porque el contrato es la **fuente única de verdad**: cliente y servidor se
construyen contra él, no el uno contra el otro. Si mañana el servidor lo escribe otra persona en otro
lenguaje, mientras respete el contrato, el cliente ni se entera. Cuando algo cambia, **se cambia el
contrato primero** y luego los dos lados se ajustan. Por eso `CLAUDE.md` recupera una sección **"API
Contract"** que apunta a ese fichero, y el `README` del backend **no** vuelve a listar los endpoints:
remite al contrato para no tener dos fuentes de verdad que se contradigan.

> **Idea clave:** un contrato de API es a cliente/servidor lo que una **interfaz** (`TaskRepository`)
> es a UI/implementación. Es un límite documentado que deja evolucionar los dos lados por separado.

### El servidor, en una frase por pieza

En `backend/src/main/kotlin/com/neverlate/backend/` verás, sin entrar al detalle de Ktor:

- `auth/` — `AuthRoutes` (los endpoints), `AuthService` (la lógica), `PasswordHasher` (bcrypt),
  `Jwt` (firma/verificación del token), `UserRepository` (Postgres).
- `tasks/` — `TaskRoutes`, `TaskService`, `TaskRepository` (Postgres), acotando **cada** consulta al
  usuario autenticado.
- `plugins/Security.kt` — enchufa el plugin `Authentication` + `jwt` de Ktor, que hace que las rutas de
  tareas exijan un token válido.

Lo importante para el cliente no es *cómo* está hecho, sino *qué promete*: eso es el contrato.

---

## 2. Autenticación con tokens

### 2.1 El flujo, de lejos

1. El usuario se **registra** o **inicia sesión** con email + contraseña (`POST /auth/register` o
   `/auth/login`).
2. El servidor comprueba las credenciales (la contraseña se guarda **hasheada** con bcrypt, nunca en
   claro) y devuelve un **token JWT**.
3. El cliente **guarda** ese token de forma segura (sección 3) y lo **adjunta** a cada llamada de tareas
   como cabecera `Authorization: Bearer <token>`.
4. El servidor, en cada llamada, **verifica** el token, saca de él **quién** eres, y acota todo a tus
   datos.

Un **JWT** (JSON Web Token) es una cadena firmada por el servidor que contiene el id de usuario. Como
está **firmada**, el servidor puede confiar en ella sin guardar sesiones en memoria: es *stateless*.
En v1 **no** hay *refresh token* (se difiere a una feature futura): cuando el token caduca, el servidor
responde `401` y el cliente simplemente vuelve a pedir login.

### 2.2 El interceptor que adjunta el token

En la lección 10 OkHttp ya nos dejó **interceptores** (los usamos para el logging en debug). Aquí
usamos uno para adjuntar el token a cada petición de tareas y para detectar el `401`:

```kotlin
class AuthInterceptor(
    private val tokenStorage: TokenStorage,
    private val onUnauthorized: () -> Unit,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val token = tokenStorage.getToken()
        val request = if (token != null) {
            chain.request().newBuilder()
                .addHeader("Authorization", "Bearer $token")
                .build()
        } else {
            chain.request()
        }
        val response = chain.proceed(request)
        if (response.code == 401) {
            onUnauthorized()   // token caducado/ inválido -> cerrar sesión y volver a login
        }
        return response
    }
}
```

Dos detalles didácticos:

- El interceptor **no** sabe navegar ni cerrar sesión; recibe un *callback* `onUnauthorized`. Así no
  acopla la capa de red con la de UI.
- Corre en un **hilo de OkHttp**, no dentro de una corrutina. Por eso `onUnauthorized` es una función
  normal, y quien la implementa (`AuthRepositoryImpl.notifyUnauthorized`) lanza su **propia** corrutina
  si necesita suspender.

### 2.3 El `AuthRepository`

Igual que `TaskRepository` es el *seam* de las tareas, `AuthRepository` es el *seam* de la cuenta: una
**interfaz** de la que dependen las pantallas de login/registro y el botón de "cerrar sesión" en
Ajustes, sin tocar Retrofit ni el almacenamiento directamente.

```kotlin
interface AuthRepository {
    val authState: StateFlow<AuthState>          // LoggedOut | LoggedIn(userId, email)
    suspend fun register(email: String, password: String): AuthResult
    suspend fun login(email: String, password: String): AuthResult
    suspend fun logout()
}
```

`register`/`login` devuelven un `AuthResult` (`Success` o `Error(type)`), y mapean los códigos HTTP a
errores **legibles** —sin crashes— con una función pura:

```kotlin
// 401 -> credenciales inválidas, 409 -> email ya usado, 400 -> validación...
AuthResult.Error(mapAuthErrorHttpCode(error.code()))
```

El `authState` es un `StateFlow`: `MainActivity` observa ese flujo y **condiciona la navegación** —si
estás `LoggedOut`, muestras la puerta de auth (login/registro) **antes** de las tareas; si estás
`LoggedIn`, vas directo a la lista. Como el token persiste (sección 3), al reabrir la app el estado
inicial ya es `LoggedIn` y no hay que reintroducir la contraseña.

---

## 3. Almacenamiento seguro del token

Aquí hay una decisión de seguridad que conviene entender bien. Ya tenemos un sitio para guardar
preferencias: el **DataStore** `user_prefs` (tema, ajustes de recordatorios). ¿Por qué no meter ahí el
token y ya está?

Porque un token **no es una preferencia, es una credencial**. Cualquiera que lo lea puede actuar como
tú contra el backend. El DataStore es un fichero en claro; en un dispositivo con root, o mediante un
exploit de backup, otro proceso podría leerlo. Un tema en claro no importa; un token sí.

La solución es **`EncryptedSharedPreferences`** (Jetpack Security): un fichero de preferencias cuyas
**claves y valores están cifrados** con una clave que **nunca sale del Android Keystore** (un almacén
de claves respaldado por hardware en dispositivos modernos).

```kotlin
class EncryptedTokenStorage(context: Context) : TokenStorage {
    private val prefs by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context, "auth_secure_prefs", masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }
    override fun getToken(): String? = prefs.getString(KEY_TOKEN, null)
    override fun saveSession(token: String, userId: Long, email: String) { /* ...putString... */ }
    override fun clearSession() { prefs.edit().clear().apply() }
    // ...
}
```

Otra vez usamos una **interfaz** (`TokenStorage`): así el `AuthRepository` se puede testear contra un
*fake* en memoria, y ni el `SyncEngine` ni el `AuthInterceptor` necesitan saber cómo se guarda el
token, solo "¿hay token y cuál es?".

> **Regla que enseña esta lección:** preferencias no sensibles → DataStore en claro; **secretos** →
> almacenamiento cifrado respaldado por Keystore. La distinción es el punto didáctico, no la API
> concreta.

La dependencia (`androidx.security:security-crypto`) se añade al catálogo de versiones
(`gradle/libs.versions.toml`), nunca hardcodeada en el `build.gradle.kts`.

---

## 4. Sync offline-first (el corazón de la feature)

El principio rector es **offline-first**: la red es una **mejora**, nunca un requisito. La UI **siempre**
lee de Room; escribir una tarea sin conexión funciona igual de bien; la sincronización pasa en segundo
plano y reconcilia. Es la misma invariante "Room es la única fuente de verdad" de la lección 10, ahora
extendida también a las **escrituras**.

Para lograrlo necesitamos cuatro piezas: **metadatos de sync** en la tarea, una **outbox**, un
**`SyncEngine`** (push/pull) y una **función de merge pura**.

### 4.1 Metadatos de sync y `SyncState`

La `Task` de Room gana columnas (esto sube `NeverLateDatabase` de la **versión 2 a la 3**):

```kotlin
data class Task(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,   // id LOCAL, identidad en la app
    val title: String,
    // ...campos durables y de timer...
    val serverId: Long? = null,        // id que asigna el backend; null hasta el primer push
    val updatedAt: Long = 0,           // última modificación; clave de last-write-wins
    val syncState: SyncState = SyncState.SYNCED,
    val deleted: Boolean = false,      // tombstone (borrado lógico)
)
```

`SyncState` es un `enum` que dice en qué punto está la fila respecto al servidor:

```kotlin
enum class SyncState { SYNCED, PENDING_CREATE, PENDING_UPDATE, PENDING_DELETE }
```

Room no sabe guardar un `enum` por sí solo, así que aparece el **primer `@TypeConverter`** del proyecto
(`Converters.kt`): le dice a Room que almacene el `enum` como su nombre en texto.

**Ids locales vs. de servidor** — un punto que suele confundir: la app **sigue** usando su `id` local
autoincremental como identidad estable (el widget de la 05, los recordatorios de la 09 y los argumentos
de navegación dependen de él, y `saveTask()` devuelve ese `Long`). El servidor asigna **su propio** id,
que guardamos aparte como `serverId`. Nunca cruzan el cable el mismo: en el `TaskDto`, `id` es el del
**servidor**.

### 4.2 La outbox: registrar la intención de sincronizar

Cada mutación local (crear/editar/borrar) escribe la fila de tarea **y** una fila en una tabla nueva,
`task_outbox`, que recuerda el cambio pendiente:

```kotlin
@Entity(tableName = "task_outbox")
data class OutboxEntity(
    @PrimaryKey val taskLocalId: Long,   // una fila por tarea (no autoincrement)
    val operation: OutboxOperation,      // CREATE | UPDATE | DELETE
    val clientRef: String,               // token de idempotencia (ver 4.4)
    val retryCount: Int = 0,
    val enqueuedAt: Long,
)
```

Decisión de diseño (simplificación deliberada sobre la spec): la outbox guarda **como mucho una fila por
tarea**, con su **última** intención. Si editas una tarea dos veces antes de que el primer cambio llegue
a sincronizarse, no hace falta reproducir dos `PATCH` en orden: solo importa el estado final, así que la
segunda escritura **reemplaza** la fila anterior (`OnConflictStrategy.REPLACE`). Es más simple de razonar
y de testear que un log append-only completo.

Lo crítico —y lo que hace esto **offline-first de verdad**— es que la fila de tarea y la de outbox se
escriben en la **misma transacción** de Room. Esto lo hace el decorador `OutboxTaskRepository`, que
envuelve al `RoomTaskRepository` real (exactamente el mismo patrón de decorador que
`ReminderSchedulingRepository` en la 09):

```kotlin
override suspend fun saveTask(task: Task): Long {
    val id = database.withTransaction {                 // <- tarea + outbox, atómico
        val isCreate = task.id == 0L
        val stamped = task.copy(
            updatedAt = now(),
            syncState = if (isCreate) SyncState.PENDING_CREATE else SyncState.PENDING_UPDATE,
        )
        val savedId = if (isCreate) taskDao.insert(stamped) else { taskDao.update(stamped); stamped.id }
        outboxDao.enqueue(OutboxEntity(
            taskLocalId = savedId,
            operation = if (isCreate) OutboxOperation.CREATE else OutboxOperation.UPDATE,
            // reutiliza el clientRef de una fila aún pendiente, para que sea estable entre reintentos
            clientRef = outboxDao.getForTask(savedId)?.clientRef ?: UUID.randomUUID().toString(),
            enqueuedAt = now(),
        ))
        savedId
    }
    syncEngine.schedulePush()   // empujón best-effort: si hay red, sincroniza ya
    return id
}
```

Como la escritura es **atómica**, un crash entre "guardé la tarea" y "encolé el cambio" es imposible: o
pasan las dos cosas o ninguna. La intención de sincronizar **nunca** se pierde.

Fíjate en que `saveTask` sigue devolviendo el **id local** (los recordatorios lo necesitan) y en que
`startTimer`/`pauseTimer` se **reenvían tal cual** al delegado: el estado del temporizador es local y
**no se sincroniza** (contarlo entre dispositivos es sutil y queda fuera de v1).

**Borrado** = tombstone. Borrar una tarea que **nunca** llegó al servidor (`serverId == null`) solo la
quita en local. Pero borrar una que sí existe en el servidor **no** la borra en duro: la marca
`deleted = true`, `PENDING_DELETE`, y encola un `DELETE`. Así el borrado se puede **propagar** de forma
fiable a los demás dispositivos.

### 4.3 El `SyncEngine`: push y pull

`SyncEngine` es la única clase que habla con la red de tareas. Ofrece un `syncNow()` que hace
**push y luego pull**, protegido por un `Mutex` para que dos sincronizaciones (un pull-to-refresh y el
job de WorkManager, por ejemplo) no se pisen. Nunca lanza excepciones al llamante: un fallo de red es un
resultado **normal** en offline-first, así que se convierte en un `SyncStatus` (`Idle` / `Syncing` /
`UpToDate` / `Offline` / `Error`):

```kotlin
suspend fun syncNow(): SyncStatus = mutex.withLock {
    if (tokenStorage.getToken() == null) return@withLock SyncStatus.Idle   // sin sesión, nada que hacer
    _status.value = SyncStatus.Syncing
    val result = try {
        push(); pull(); SyncStatus.UpToDate
    } catch (e: IOException) { SyncStatus.Offline }     // sin red
      catch (e: HttpException) { SyncStatus.Error }
    _status.value = result
    result
}
```

**Push** recorre la outbox de la más antigua a la más nueva y reproduce el verbo correspondiente. Al
tener éxito un `CREATE`, casa la respuesta del servidor con la fila local, **rellena `serverId`**, la
marca `SYNCED` y borra la fila de outbox —todo en una transacción. Si la red falla (`IOException`), **para**
de empujar (para preservar el orden) y deja la cola intacta para reintentarla luego. Si el servidor
rechaza una fila repetidamente (un `HttpException` "envenenado", p. ej. una validación que nunca pasará),
tras `MAX_PUSH_RETRIES` intentos la descarta, para no bloquear el resto de la cola para siempre.

**Pull** llama a `GET /tasks?since=<cursor>`, aplica cada `TaskDto` devuelto y **avanza el cursor** al
`serverTime` que reporta el servidor (así el siguiente pull es incremental). El `since` se guarda en el
DataStore `user_prefs` —el cursor no es un secreto, así que ahí sí vale.

### 4.4 Idempotencia: `clientRef`

Imagina: el cliente hace `POST /tasks`, el servidor la crea, pero la red se cae **antes** de que llegue
la respuesta (el *ack*). El cliente reintenta el `POST`. ¿Se crean **dos** tareas? Sería el clásico
doble-insert.

Se evita con un **`clientRef`**: un identificador único que **genera el cliente** y manda en el create.
El servidor **deduplica** por `clientRef`: si ya vio ese `clientRef`, devuelve la tarea **ya creada** en
vez de crear otra. Por eso el `clientRef` debe permanecer **estable** entre reintentos (mira arriba cómo
`saveTask` reutiliza el de la fila pendiente en vez de generar uno nuevo en cada edición). Resultado: el
push es **idempotente** y tolerante a reintentos.

### 4.5 Resolución de conflictos: last-write-wins, y una función pura

¿Qué pasa si la **misma** tarea se editó en dos dispositivos estando ambos offline y luego los dos
sincronizan? Necesitamos una regla **determinista** para que ambos converjan al mismo resultado. Elegimos
**Last-Write-Wins (LWW)** por `updatedAt`: gana la versión con el timestamp **más reciente**.

- *Por qué LWW:* es **simple, determinista y convergente** — la complejidad justa para un tutorial. Un
  merge campo a campo o CRDTs serían mucho más de lo que toca enseñar.
- *Trade-off (hay que ser honestos):* LWW puede **descartar en silencio** la edición perdedora. Para una
  app de tareas personal es aceptable; se documenta y punto.
- *Borrar vs. editar:* **gana el borrado**. Un tombstone vence a una edición concurrente, para que una
  tarea borrada no "resucite".

Y aquí volvemos al patrón favorito del proyecto (lección 09, `ReminderPlanning.kt`): la decisión se
extrae a una **función pura, sin Android**, en `domain/sync/SyncMerge.kt`, para poder testearla con un
test de JVM normal, sin emulador:

```kotlin
fun reconcilePulledTask(local: Task?, dto: TaskDto): PulledTaskAction {
    if (local == null) {
        // un tombstone de algo que nunca tuvimos: no hay nada que borrar
        return if (dto.deleted) PulledTaskAction.Ignore else PulledTaskAction.Upsert(dto.toNewLocalTask())
    }
    val hasPendingDelete = local.syncState == SyncState.PENDING_DELETE
    val hasPendingEdit = local.syncState == SyncState.PENDING_CREATE ||
                         local.syncState == SyncState.PENDING_UPDATE
    return when {
        dto.deleted        -> PulledTaskAction.Delete(local.id)   // el borrado del servidor gana
        hasPendingDelete   -> PulledTaskAction.Ignore             // nuestro borrado pendiente gana
        hasPendingEdit     -> if (dto.updatedAt > local.updatedAt)  // LWW: solo si el servidor es más nuevo
                                  PulledTaskAction.Upsert(dto.toExistingLocalTask(local.id))
                              else PulledTaskAction.Ignore
        else               -> PulledTaskAction.Upsert(dto.toExistingLocalTask(local.id)) // sin cambios locales
    }
}
```

`PulledTaskAction` es un `sealed interface` (`Upsert` / `Delete` / `Ignore`): la función **decide**, y el
`SyncEngine.pull()` **ejecuta** la decisión sobre Room. Separar "decidir" de "hacer" es justo lo que hace
la lógica testeable.

### 4.6 El `TaskDto`: otra vez DTO ≠ dominio

Como en la lección 10, la forma en el cable es **deliberadamente distinta** del modelo de dominio:

```kotlin
@Serializable
data class TaskDto(
    val id: Long,            // id del SERVIDOR (-> Task.serverId)
    val clientRef: String,
    val title: String,
    val estimatedDurationMillis: Long? = null,
    val deadline: Long? = null,
    val deleted: Boolean = false,   // tombstone
    val updatedAt: Long,
    val createdAt: Long = 0L,
)
```

No lleva el `id` local, ni el `syncState`, ni el estado del timer. El mapeo `TaskDto ↔ Task` vive en
funciones puras (`TaskMapping.kt`), reutilizables tanto por el merge como por el engine.

### 4.7 Cuándo se dispara el sync

- Al **abrir** la app (un `LaunchedEffect` en el nav host).
- En **pull-to-refresh** manual.
- **Justo después** de cada mutación (el `schedulePush()` best-effort de arriba).
- Un job **periódico de WorkManager** (`SyncWorker`) con restricción `NetworkType.CONNECTED`, para que la
  outbox se vacíe **aunque cerraras la app estando sin conexión**. Reutilizamos WorkManager de la
  lección 09.

La UI de estado es **mínima** (una decisión de la spec): el spinner de pull-to-refresh y una pista sutil
de "sin conexión". Nada de insignias de sync por tarea.

---

## 5. Postura de seguridad: la lógica sensible vive en el backend

Este es el cambio de mentalidad más importante de la feature. Hasta ahora la app era local-only y "la
seguridad" se reducía a no filtrar datos del dispositivo. Con un backend, **el cliente pasa a ser no
confiable**:

- **La validación existe en el servidor**, no solo en el formulario. El cliente puede tener bugs, ser
  modificado, o mandar peticiones a mano; el servidor **siempre** revalida (título no vacío, al menos una
  de duración/deadline...).
- **La autorización se aplica en el servidor.** Cada consulta de tareas se **acota al id del usuario
  autenticado**. El usuario A no puede leer ni tocar las tareas del usuario B: intentarlo da `404`. Sin
  token, `401`. Esto se **verifica con tests de backend**, no se da por hecho.
- **Los secretos viven en variables de entorno**, nunca commiteados: la clave de firma del JWT y las
  credenciales de Postgres se inyectan por entorno (hay un `.env.example` con valores de ejemplo; el
  `.env` real está en `.gitignore`).
- **Las contraseñas se hashean** (bcrypt) y **nunca** se guardan ni se loguean en claro; viajan por HTTPS.

En una frase: **el cliente pide, el servidor decide.** Esa es la línea que separa una app local de una
app con backend.

---

## 6. Qué respetamos del pasado (el *seam* intacto)

Un objetivo explícito de la feature: la UI y los ViewModels de tareas **apenas** cambian. Siguen
dependiendo de `TaskRepository`; toda la maquinaria de sync entra **por detrás** de esa interfaz, como un
decorador. En `MainActivity` la composición queda así (de dentro afuera):

```
RoomTaskRepository            // lee/escribe Room (lección 04)
  └─ OutboxTaskRepository     // + estampa metadatos de sync y encola outbox (feature 11)
       └─ ReminderSchedulingRepository   // + (re)programa recordatorios (lección 09)
            └─ TaskSurfacesRefreshingRepository  // + refresca widget/notificación (05/06)
```

Cada capa añade **una** responsabilidad y reenvía el resto. Room sigue siendo la **única fuente de verdad
local** de la que lee la UI. Y como `TaskRepository` ganó dos métodos nuevos con cuerpo por defecto
(`refreshFromServer()` y `observeSyncStatus()`), hubo que dar a cada decorador un override de dos líneas
que reenvíe al delegado: si no, un decorador que hereda el cuerpo por defecto se **tragaría** en silencio
la sincronización. (Un buen recordatorio de que los métodos con cuerpo por defecto en una interfaz son
cómodos, pero un decorador debe reenviarlos explícitamente.)

---

## 7. Cómo probarlo en local

```bash
# 1) Levantar el backend + Postgres
cd backend && docker compose up --build      # el backend queda en el puerto 8080

# 2) El emulador Android llega al backend del host en http://10.0.2.2:8080
#    (10.0.2.2 es el loopback del host visto desde el emulador; ver docs/api/contract.md)
```

Secuencia de humo (registrar → crear tarea → listar → login) documentada en el contrato (§6) y en
`backend/README.md`.

---

## 8. Resumen

- Aparece el **primer backend** del proyecto (Kotlin + Ktor + Postgres) y un **contrato de API** como
  fuente única de verdad.
- La **auth** con **JWT** y un **interceptor** que adjunta el token; el token guardado con
  **`EncryptedSharedPreferences`** respaldado por Keystore (¡no en un DataStore en claro!).
- **Sync offline-first**: Room como fuente de verdad + **outbox** transaccional + **push/pull** +
  **`clientRef`** para idempotencia + **tombstones** + **last-write-wins** con una **función de merge
  pura y testeable**.
- La **seguridad vive en el backend**: validación, autorización por usuario, secretos por entorno,
  contraseñas hasheadas.
- Todo entra **por detrás del *seam* `TaskRepository`**, sin reescribir la UI.

En la próxima lección (diferida a una feature futura) tocará el **modo invitado** (usar la app sin cuenta
y fusionar las tareas locales al registrarse) y el **refresh token** (renovar la sesión sin volver a
teclear la contraseña), los dos trozos que dejamos deliberadamente fuera de v1.
