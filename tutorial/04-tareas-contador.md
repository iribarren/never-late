# Lección 04 — Tareas con base de datos (Room) y contador de cuenta atrás

> Objetivo: construir el **núcleo de la app**. La persona usuaria puede **crear, listar, editar y
> borrar tareas** (con un título y una duración estimada y/o una fecha límite), y para cada tarea
> lanzar un **contador de cuenta atrás** que muestra el tiempo restante y se puede **iniciar y
> pausar**. Todo se guarda en el dispositivo con **Room** (una base de datos SQLite), así que las
> tareas sobreviven a cerrar la app. Por el camino aprendemos nuestra primera **base de datos**, el
> **CRUD** completo detrás de un repositorio, y a mover un **temporizador** a corrutinas y `Flow`.

## Conceptos que aprendes aquí

Partiendo de las lecciones 02 (`ViewModel` + `StateFlow`, DI manual) y 03 (`LazyColumn`, navegación
con argumentos, repositorio tras interfaz, `sealed interface` para el estado):

- **[Room (SQLite)](https://developer.android.com/training/data-storage/room):** `@Entity` (una
  tabla), `@Dao` (las consultas), `@Database` (la base de datos), y
  **[KSP](https://developer.android.com/build/migrate-to-ksp)** como generador de código en tiempo
  de compilación.
- **Consultas que devuelven `Flow`:** lecturas **reactivas** que vuelven a emitir cada vez que cambian
  los datos, en lugar de una lectura de una vez.
- **CRUD completo** (crear / leer / actualizar / borrar) desde el DAO → repositorio → `ViewModel`.
- **[Corrutinas](https://developer.android.com/kotlin/coroutines) y
  [`Flow`](https://kotlinlang.org/docs/flow.html) para un temporizador:** una cuenta atrás con
  `delay`, mantenida **fuera de la UI**.
- **Estado más complejo en Compose:** una lista donde cada fila tiene su propio tiempo restante que se
  refresca cada segundo, con `collectAsStateWithLifecycle`.
- **Diseño para el reloj de pared:** derivar el tiempo restante de la hora del sistema para que
  sobreviva a que Android mate y recree el proceso.
- **Formato de tiempo y fechas** con `SimpleDateFormat` y aritmética de milisegundos.

---

## 1. La entidad: una tabla con `@Entity`

Hasta ahora un modelo de dominio era una simple `data class` (`Article` en la lección 03). Para
guardarla en una base de datos Room, la anotamos como **`@Entity`**: eso le dice a Room que cree una
**tabla** con una columna por cada propiedad del constructor
([Task.kt](../app/src/main/java/com/neverlate/data/tasks/Task.kt)):

```kotlin
@Entity(tableName = "tasks")
data class Task(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val estimatedDurationMillis: Long? = null,
    val deadline: Long? = null,
    val timerEndsAt: Long? = null,
    val remainingMillis: Long? = null,
) {
    val isRunning: Boolean
        get() = timerEndsAt != null
}
```

Puntos clave:

- **`@PrimaryKey(autoGenerate = true)`**: cada fila necesita una clave única. Con `autoGenerate`,
  SQLite asigna el `id` automáticamente cuando insertamos una `Task` con el valor por defecto
  `id = 0`. Por eso el `id` es un `Long` y arranca en `0` ("todavía sin guardar").
- **Tipos que SQLite entiende:** guardamos duración y fecha límite como `Long?` (milisegundos y
  *epoch millis*), no como objetos de fecha. Room mapea directamente `Long`, `String`, `Int`,
  `Boolean`… a columnas.
- **`isRunning` es una propiedad con `get()`**, sin campo de respaldo. Room **no** la trata como
  columna: es solo una comodidad derivada de `timerEndsAt`.
- **La regla "al menos duración o fecha límite"** no se puede expresar en el esquema (SQLite no sabe
  de "al menos una de dos columnas nullable"), así que vive en la **validación del formulario**
  (sección 7), no aquí.

### ¿Por qué guardar el estado del contador en la tarea?

Fíjate en `timerEndsAt` y `remainingMillis`: son el estado del **temporizador**, persistido en la
propia fila. La idea (clave para esta feature y para el widget y la pantalla de bloqueo que vienen
después) es **derivar el tiempo restante del reloj de pared** en vez de tener un contador vivo en
memoria:

- **Corriendo:** `timerEndsAt` guarda el instante (epoch millis) en que la cuenta atrás llegará a
  cero; `remainingMillis` es `null`.
- **Pausado (o nunca iniciado):** `timerEndsAt` es `null` y `remainingMillis` congela cuánto quedaba
  al pausar.

Así, si Android mata el proceso y lo recrea, el tiempo restante sigue siendo correcto: se recalcula
como `timerEndsAt - ahora`. Un contador en memoria se habría perdido.

---

## 2. El DAO: consultas con `@Dao`

Un **DAO** (*Data Access Object*) es una interfaz donde declaramos las operaciones sobre la tabla.
No la implementamos nosotros: **KSP genera la implementación** en tiempo de compilación a partir de
las firmas anotadas ([TaskDao.kt](../app/src/main/java/com/neverlate/data/tasks/TaskDao.kt)):

```kotlin
@Dao
interface TaskDao {
    @Insert
    suspend fun insert(task: Task): Long

    @Update
    suspend fun update(task: Task)

    @Query("DELETE FROM tasks WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT * FROM tasks ORDER BY id DESC")
    fun observeTasks(): Flow<List<Task>>

    @Query("SELECT * FROM tasks WHERE id = :id")
    fun observeTask(id: Long): Flow<Task?>
}
```

Aquí está el **CRUD** completo:

- **`@Insert` / `@Update`** son operaciones estándar; Room genera el SQL. **`@Query`** lleva SQL a
  mano (para borrar por id y para las lecturas). `:id` es un parámetro enlazado (evita inyección de
  SQL).
- **Escrituras `suspend`:** insertar/actualizar/borrar son `suspend`, así que Room las ejecuta
  automáticamente **fuera del hilo principal**. Se llaman desde una corrutina.
- **Lecturas que devuelven `Flow`:** `observeTasks()` y `observeTask(id)` **no** son `suspend`;
  devuelven un `Flow`. Esto es lo nuevo importante: un `Flow` de Room **vuelve a emitir
  automáticamente** cada vez que la tabla cambia. Al insertar una tarea, todos los que estén
  observando `observeTasks()` reciben la lista nueva sin pedir nada. Es lo que hará que la lista de
  la UI se actualice sola.

---

## 3. La base de datos: `@Database` como singleton

La clase **`@Database`** une la lista de entidades con sus DAOs y genera (vía KSP) la implementación
SQLite real ([NeverLateDatabase.kt](../app/src/main/java/com/neverlate/data/tasks/NeverLateDatabase.kt)):

```kotlin
@Database(entities = [Task::class], version = 1, exportSchema = false)
abstract class NeverLateDatabase : RoomDatabase() {
    abstract fun taskDao(): TaskDao

    companion object {
        @Volatile
        private var instance: NeverLateDatabase? = null

        fun getInstance(context: Context): NeverLateDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    NeverLateDatabase::class.java,
                    "never-late.db",
                )
                    .fallbackToDestructiveMigration(dropAllTables = true)
                    .build()
                    .also { instance = it }
            }
    }
}
```

- **`version = 1`:** una base de datos se versiona desde el principio. Cuando el esquema cambie (el
  widget, la pantalla de bloqueo o los recordatorios ampliarán la tabla), habrá que subir la versión
  y escribir una **migración**.
- **`fallbackToDestructiveMigration`:** mientras la app sea pre-release, en vez de escribir una
  migración, Room puede **borrar y recrear** la base de datos ante un cambio de esquema. Es un atajo
  aceptable ahora; deja de serlo en cuanto haya usuarios con datos reales.
- **Singleton con *double-checked locking*:** crear una base de datos Room es caro y debe haber **una
  sola** por proceso. El patrón `instance ?: synchronized(this) { instance ?: ... }` garantiza que,
  aunque dos hilos lleguen a la vez, solo se construye una. **`@Volatile`** hace que la escritura de
  `instance` sea visible de inmediato a todos los hilos, lo que hace correcto ese doble chequeo.

---

## 4. El repositorio: misma interfaz de siempre, ahora sobre Room

Igual que en la lección 03, el acceso a datos vive detrás de una **interfaz**. La diferencia es que
ahora las lecturas devuelven `Flow`
([TaskRepository.kt](../app/src/main/java/com/neverlate/data/tasks/TaskRepository.kt)):

```kotlin
interface TaskRepository {
    fun observeTasks(): Flow<List<Task>>
    fun observeTask(id: Long): Flow<Task?>
    suspend fun saveTask(task: Task)
    suspend fun deleteTask(id: Long)
    suspend fun startTimer(id: Long)
    suspend fun pauseTimer(id: Long)
}
```

La implementación delega en el DAO
([RoomTaskRepository.kt](../app/src/main/java/com/neverlate/data/tasks/RoomTaskRepository.kt)):

```kotlin
class RoomTaskRepository(private val dao: TaskDao) : TaskRepository {

    override fun observeTasks(): Flow<List<Task>> = dao.observeTasks()
    override fun observeTask(id: Long): Flow<Task?> = dao.observeTask(id)

    override suspend fun saveTask(task: Task) {
        // id 0 = tarea nueva → insertar; cualquier otro id = ya existe → actualizar.
        if (task.id == 0L) dao.insert(task) else dao.update(task)
    }

    override suspend fun deleteTask(id: Long) = dao.deleteById(id)

    override suspend fun startTimer(id: Long) {
        val task = dao.observeTask(id).first() ?: return
        val now = System.currentTimeMillis()
        val remaining = computeRemainingMillis(task, now)
        dao.update(task.copy(timerEndsAt = now + remaining, remainingMillis = null))
    }

    override suspend fun pauseTimer(id: Long) {
        val task = dao.observeTask(id).first() ?: return
        val now = System.currentTimeMillis()
        val remaining = computeRemainingMillis(task, now)
        dao.update(task.copy(timerEndsAt = null, remainingMillis = remaining))
    }
}
```

- **`saveTask` decide insertar o actualizar** según el `id`: es el patrón "guardar" único que usa el
  formulario tanto para crear como para editar.
- **`startTimer` / `pauseTimer` traducen a estado persistido.** Al iniciar, calculamos cuánto queda y
  fijamos `timerEndsAt = ahora + restante`. Al pausar, congelamos `remainingMillis` y ponemos
  `timerEndsAt = null`. La cuenta atrás es, literalmente, una resta contra el reloj.
- **`.first()`** lee un único valor del `Flow` y deja de observar: es lo correcto para un "lee la fila
  actual" puntual, frente a una suscripción continua.

### ¿Por qué la interfaz, otra vez?

Porque **el widget (feature 05) y la pantalla de bloqueo (feature 06) van a reutilizar este mismo
repositorio** para leer las tareas y su tiempo restante. Al depender solo de `TaskRepository` (con
lecturas en `Flow`), esas features observarán los cambios sin tocar ni una línea de la UI de esta
lección. Es la restricción de diseño más importante de la feature.

---

## 5. La lógica del contador: funciones puras + reloj de pared

El corazón del temporizador es una **función pura**
([TaskTiming.kt](../app/src/main/java/com/neverlate/data/tasks/TaskTiming.kt)):

```kotlin
fun computeRemainingMillis(task: Task, now: Long): Long {
    val raw = when {
        task.timerEndsAt != null      -> task.timerEndsAt - now          // corriendo
        task.remainingMillis != null  -> task.remainingMillis            // pausado
        task.deadline != null         -> task.deadline - now             // sin iniciar, hay fecha
        else                          -> task.estimatedDurationMillis ?: 0L  // sin iniciar, solo duración
    }
    return raw.coerceAtLeast(0L)   // nunca negativo: cero = "tiempo agotado"
}
```

Dos ideas didácticas:

- **Es pura:** no lee el reloj ni la base de datos por su cuenta; recibe `now` como parámetro. Eso la
  hace **trivial de testear** pasándole distintas combinaciones de `Task` y `now`, sin relojes falsos
  ni bases de datos.
- **La regla de la cuenta atrás (decisión aprobada, US-5):** si la tarea tiene **fecha límite**, la
  cuenta atrás va **hacia ella** (la duración es solo informativa); si solo tiene **duración**, cuenta
  esa duración. Y **`coerceAtLeast(0L)`** garantiza que nunca se muestren números negativos.

El formateo también son funciones puras:

```kotlin
fun formatRemaining(millis: Long): String { /* mm:ss, o h:mm:ss al pasar de una hora */ }
fun formatDurationLabel(millis: Long): String { /* "1 h 30 min", "45 min" */ }
fun formatDeadline(epochMillis: Long): String  // "24/12/2026 20:30"
fun parseDeadline(text: String): Long?          // el inverso; null si el texto no encaja
```

> **Nota sobre fechas:** usamos `SimpleDateFormat` (de `java.text`) en vez de la API moderna
> `java.time`, porque esta necesita API 26+ o *desugaring*, y el proyecto tiene `minSdk = 24`.
> `parseDeadline` pone `isLenient = false` para **rechazar** fechas imposibles (`32/13/2026`) en vez
> de "arreglarlas" en silencio.

---

## 6. El tic del temporizador: un `Flow` con `delay`

Las funciones puras calculan el tiempo restante, pero algo tiene que **refrescar la pantalla cada
segundo**. Ese "algo" es un `Flow` mínimo que emite un latido periódico
([CountdownTicker.kt](../app/src/main/java/com/neverlate/ui/tasks/CountdownTicker.kt)):

```kotlin
fun countdownTicker(intervalMillis: Long = 1_000L): Flow<Unit> = flow {
    while (true) {
        emit(Unit)
        delay(intervalMillis)
    }
}
```

- **`flow { ... }`** construye un `Flow` frío: el bucle no arranca hasta que alguien lo recolecta, y
  se cancela solo cuando se cancela la corrutina que lo recoge.
- **`delay`** es la versión de corrutinas de "espera": suspende sin bloquear el hilo.
- El tic **no lleva datos de tiempo**: solo dice "es momento de refrescar". Quien lo recibe siempre
  recalcula el restante con `computeRemainingMillis(task, System.currentTimeMillis())`. Así, un tic
  que llegue tarde (p. ej. con la app en segundo plano) produce como mucho un refresco tardío, nunca
  un valor incorrecto.

---

## 7. El `ViewModel` de la lista: `Flow`, `flatMapLatest` y auto-pausa

Aquí se junta todo. A diferencia de `ArticlesViewModel` (una carga única), `TasksViewModel`
**observa** la lista de forma continua y, mientras haya alguna tarea corriendo, la refresca cada
segundo ([TasksViewModel.kt](../app/src/main/java/com/neverlate/ui/tasks/TasksViewModel.kt)):

```kotlin
init {
    viewModelScope.launch {
        repository.observeTasks()
            .flatMapLatest { tasks ->
                if (tasks.any { it.isRunning }) countdownTicker().map { tasks } else flowOf(tasks)
            }
            .collect { tasks -> onTasksTick(tasks) }
    }
}
```

- **`flatMapLatest`** cambia el `Flow` de aguas arriba cada vez que la lista de tareas cambia:
  - Si **alguna tarea corre**, conmuta al `countdownTicker()` (que reemite la lista una vez por
    segundo) → la UI se refresca cada segundo.
  - Si **ninguna corre**, conmuta a un `flowOf(tasks)` que emite una vez y calla → **el tic se detiene
    solo**, y con él el gasto de batería.
- Como iniciar/pausar/agotar una tarea pasa por el repositorio (que actualiza filas que
  `observeTasks()` observa), este cambio ocurre **sin que el ViewModel tenga que llevar la cuenta**
  de "¿hay algo corriendo?".

Cada tic recalcula el restante y detecta el fin:

```kotlin
private fun onTasksTick(tasks: List<Task>) {
    val now = System.currentTimeMillis()
    val uiTasks = tasks.map { task ->
        val remaining = computeRemainingMillis(task, now)
        TaskUiModel(task, remaining, isTimedOut = remaining == 0L)
    }
    _uiState.value = if (uiTasks.isEmpty()) TasksUiState.Empty else TasksUiState.Content(uiTasks)

    // Una tarea que acaba de llegar a cero se auto-pausa: congela remainingMillis a 0,
    // lo que evita números negativos (US-5) y hace que flatMapLatest suelte el tic inútil.
    uiTasks.filter { it.task.isRunning && it.remainingMillis == 0L }
        .forEach { pauseTimer(it.task.id) }
}
```

El estado se modela con `sealed interface` (`Loading` / `Content` / `Empty`), como en la lección 03,
y cada fila lleva su `TaskUiModel` (la tarea + su tiempo restante + si se agotó).

### El `ViewModel` del formulario

`TaskEditViewModel` ([TaskEditViewModel.kt](../app/src/main/java/com/neverlate/ui/tasks/TaskEditViewModel.kt))
sigue el patrón "argumento de navegación entra, repositorio recarga" de `ArticleDetailViewModel`, con
un matiz: aquí `taskId` **nullable tiene significado** — `null` significa "crear tarea nueva";
cualquier otro id significa "editar esa tarea". Al guardar, delega la validación en la función pura
`validateTaskForm` y solo persiste si el resultado es `Valid`:

```kotlin
when (val result = validateTaskForm(state.title, state.estimatedDurationMinutes, state.deadlineText)) {
    is TaskFormResult.Invalid -> _uiState.value = state.copy(validationError = result.error)
    is TaskFormResult.Valid   -> { /* construir Task y repository.saveTask(...) */ }
}
```

La validación ([TaskValidation.kt](../app/src/main/java/com/neverlate/data/tasks/TaskValidation.kt))
exige **título no vacío** y **al menos** una duración o una fecha válidas, devolviendo un
`sealed interface TaskFormResult` (`Valid` con los valores ya parseados, o `Invalid` con el error).
La pantalla traduce cada `TaskValidationError` a un mensaje de `strings.xml` (el código no lleva texto
visible).

---

## 8. La UI: `collectAsStateWithLifecycle` y estado hoisteado

Las pantallas son **stateless** y observan el estado del ViewModel de forma consciente del ciclo de
vida. El `TasksRoute` construye el ViewModel con el `AppViewModelFactory` y recoge el estado:

```kotlin
val uiState by viewModel.uiState.collectAsStateWithLifecycle()
```

**`collectAsStateWithLifecycle`** (ya visto en lecciones anteriores) deja de recolectar cuando la
pantalla no está visible: nada de refrescar un contador que no se ve. La `TasksScreen` pinta un
`LazyColumn` con el estado vacío, y cada fila muestra título, duración/fecha formateadas, el tiempo
restante y un botón de **play/pause** que llama a `onStart` / `onPause` (state hoisting: la fila
**avisa**, el ViewModel actúa). Borrar pide confirmación con un `AlertDialog`. El
`TaskEditScreen` es el formulario (título, duración en minutos, fecha límite como texto
`dd/MM/yyyy HH:mm`) con el mensaje de validación en línea.

La navegación añade tres rutas al `AppNavHost`: `tasks` (lista), `taskEdit` (crear) y
`taskEdit/{taskId}` (editar, con `NavType.LongType`). Como siempre, **por la ruta solo viaja el id**,
nunca el objeto `Task`. Y la Home gana una entrada "Tareas" que ahora navega de verdad.

---

## 9. Dependencias nuevas: Room, KSP y corrutinas

Todo al **catálogo de versiones** (`gradle/libs.versions.toml`), nada hardcodeado:

- **KSP** (`com.google.devtools.ksp`, versión `2.1.0-1.0.29`, alineada con Kotlin `2.1.0`): el
  procesador de anotaciones que **genera** el código de Room en compilación. Se declara como plugin
  en el catálogo, `apply false` en el `build.gradle.kts` raíz, y se aplica en el del módulo.
- **Room** (`2.7.1`): `room-runtime` y `room-ktx` (soporte de corrutinas/`Flow`) como
  `implementation`, y `room-compiler` con la configuración **`ksp(...)`** (no `implementation`).
- **`kotlinx-coroutines-android`** (`1.9.0`): el runtime de corrutinas para `delay`, `viewModelScope`,
  etc. (`kotlinx-coroutines-test`, ya en el catálogo, es solo para tests).
- **`material-icons-extended`**: para el icono de `Pause` del botón del contador (el set básico no lo
  trae). Sigue el BOM de Compose, como `material3`.

---

## 10. Tests

- **Tests unitarios JVM** (`app/src/test/.../data/tasks/` y `.../ui/tasks/`), sin emulador:
  - `TaskTimingTest` — `computeRemainingMillis` en todos sus casos (corriendo / pausado / sin iniciar,
    solo duración, solo fecha, **y el caso con ambas: manda la fecha**, y el recorte a cero);
    `formatRemaining` (frontera de la hora), `formatDurationLabel`, `parseDeadline`/`formatDeadline`
    (ida y vuelta + entradas inválidas).
  - `TaskValidationTest` — `validateTaskForm`: título vacío, sin duración ni fecha, duración
    inválida, fecha mal formada, combinaciones válidas.
  - `TasksViewModelTest` / `TaskEditViewModelTest` — con un **fake en memoria** de `TaskRepository` y
    `kotlinx-coroutines-test`: lista desde el `Flow`, iniciar/pausar, **auto-pausa al llegar a cero**,
    precarga en edición, guardar y borrar, y bloqueo de guardado por validación.
- **Tests instrumentados** (`app/src/androidTest/.../`, necesitan emulador):
  - `TaskDaoTest` — con `Room.inMemoryDatabaseBuilder`: insertar/actualizar/borrar reflejado en
    `observeTasks()` / `observeTask(id)`.
  - `TasksScreenTest` — test de UI ligero (estado vacío + fila + click).

```bash
# Tests unitarios (JVM, sin emulador)
./gradlew :app:testDebugUnitTest

# Tests instrumentados (Room DAO + UI): necesitan un emulador/dispositivo en marcha
./gradlew :app:connectedDebugAndroidTest
```

> El `TasksViewModel` deriva el restante de `System.currentTimeMillis()` real (por diseño), así que
> el tiempo virtual del `TestDispatcher` no puede "acelerar" el reloj del ViewModel; por eso el
> avance del tiempo se testea a nivel de función pura (`TaskTimingTest`) y el ViewModel se comprueba
> con asertos de estado (corriendo/pausado/agotado). Además, `countdownTicker()` es un `Flow`
> infinito: en esos tests se usa `runCurrent()`, no `advanceUntilIdle()` (que colgaría).

---

## 11. Probar la app

```bash
./gradlew :app:installDebug
adb shell am start -n com.neverlate/.MainActivity
```

- Desde **Home**, pulsa **Tareas**: se abre la **lista** (vacía la primera vez, con su mensaje).
- Pulsa **+**, crea una tarea con título y una duración (minutos) y/o una fecha límite. Aparece en la
  lista al instante (gracias al `Flow` de Room).
- Pulsa **play** en una tarea: el tiempo restante empieza a **contar atrás** cada segundo. **Pausa** y
  observa que se congela; reanuda y sigue donde estaba.
- **Cierra la app por completo y reábrela:** tus tareas siguen ahí (Room, en disco). Si dejaste un
  contador corriendo, el restante es el correcto (reloj de pared).
- Deja que una cuenta atrás llegue a **cero**: se detiene sola en `00:00`, sin números negativos.
- Todo funciona en **modo avión**: es local.

---

## Documentación oficial

- **Room** — [Save data in a local database using Room](https://developer.android.com/training/data-storage/room)
  · [Referencia de consultas con `@Query`](https://developer.android.com/training/data-storage/room/accessing-data)
- **KSP** — [Migrate from kapt to KSP](https://developer.android.com/build/migrate-to-ksp)
- **Corrutinas en Android** — [Kotlin coroutines on Android](https://developer.android.com/kotlin/coroutines)
  · [Coroutines (Kotlin)](https://kotlinlang.org/docs/coroutines-overview.html)
- **`Flow`** — [Asynchronous Flow (Kotlin)](https://kotlinlang.org/docs/flow.html)
  · [Flows en Android](https://developer.android.com/kotlin/flow)
- **Room + Flow (lecturas reactivas)** — [Read data using observable queries](https://developer.android.com/training/data-storage/room/async-queries)
- **Fechas y horas** — [`SimpleDateFormat`](https://developer.android.com/reference/java/text/SimpleDateFormat)

---

## 12. Siguiente paso

Ya tenemos una **base de datos** propia y un repositorio de tareas reactivo tras una interfaz. Sobre
esa base:

- La **feature 05** (`docs/prompts/05-widget-tareas.md`) añadirá un **widget** de pantalla de inicio
  (Glance) que lee las tareas pendientes y su tiempo restante **desde este mismo repositorio**.
- La **feature 06** (`docs/prompts/06-tareas-lockscreen.md`) mostrará la tarea activa en la **pantalla
  de bloqueo** (notificación / foreground service), reutilizando también el repositorio.

Gracias a que las lecturas son `Flow` y a que todo depende de la interfaz `TaskRepository`, ambas
podrán observar los cambios de las tareas sin tocar la UI que hemos construido aquí.
