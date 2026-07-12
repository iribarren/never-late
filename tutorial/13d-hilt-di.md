# Lección 13d — Inyección de dependencias con Hilt

Desde la lección 02, esta app construye sus propias dependencias **a mano**: un
`ViewModelProvider.Factory` escrito por nosotros (`AppViewModelFactory`) monta cada `ViewModel` con
el repositorio que necesita, y `MainActivity.onCreate` es quien construye, en orden, la base de
datos, el almacenamiento cifrado del token, los clientes Retrofit, el `SyncEngine` y — lo más
delicado — una cadena de **tres decoradores** envolviendo el `TaskRepository` real. Con el backend +
sync + auth de las features 11-13 ya construidas, ese bloque de `onCreate` se ha vuelto largo,
frágil (el orden de los decoradores importa) y tedioso de repetir cada vez que aparece un
`ViewModel` nuevo.

Esta lección es un **refactor "antes/después"**, no una feature de producto: sustituye ese cableado
manual por **Hilt**, el contenedor de inyección de dependencias recomendado para Android, sin tocar
**ni un bit** de comportamiento observable. Mismos seams, mismos decoradores, mismo orden, mismas
pantallas.

## Conceptos que aprendes aquí

Partiendo de la 02 (primer `ViewModelProvider.Factory` manual), la 09/11 (decoradores de
`TaskRepository` compuestos a mano) y la 13 (modo invitado y su gancho `onAuthenticated`):

- **Por qué un contenedor de DI:** el coste concreto de la DI manual en este proyecto — repetir la
  misma lista de dependencias en `AppViewModelFactory` y en `MainActivity.onCreate`, sin ayuda del
  compilador si te equivocas en el orden de los decoradores.
- **Hilt básico:** `@HiltAndroidApp` (genera el componente raíz), `@Inject` en un constructor,
  `@Module`, `@Provides` frente a `@Binds` (y cuándo usar cada uno), `@Singleton` y los
  *componentes/ámbitos* de Hilt (`SingletonComponent`, uno para toda la vida del proceso).
- **Hilt + Compose + Navigation:** `@HiltViewModel`, `hiltViewModel()`, y cómo un argumento de
  navegación (`articleId`, `taskId`) llega a un `ViewModel` vía `SavedStateHandle` en lugar de un
  parámetro de fábrica.
- **Proveer una cadena de decoradores con Hilt:** el punto central de la lección — cómo decirle a
  Hilt "estas cuatro cosas implementan la misma interfaz, pero son capas distintas" usando
  **qualifiers**, para que la composición final sea exactamente la de antes.

---

## 1. El problema: el coste de la DI manual en este proyecto

Antes de esta feature, `MainActivity.onCreate` tenía este aspecto (abreviado):

```kotlin
// Antes (02-13c): todo construido a mano, en orden, en MainActivity.
val repository = DataStoreUserPreferencesRepository(applicationContext)
val database = NeverLateDatabase.getInstance(applicationContext)
val articleRepository = CachingArticleRepository(ArticlesNetwork.create(), database)
val reminderScheduler = AlarmManagerReminderScheduler(applicationContext)

val tokenStorage = EncryptedTokenStorage(applicationContext)
val authRepositoryImpl = AuthRepositoryImpl(AuthNetwork.create(), tokenStorage, database, repository)

val tasksApi = TasksNetwork.create(tokenStorage, onUnauthorized = authRepositoryImpl::notifyUnauthorized)
val syncEngine = SyncEngine(tasksApi, database, repository, tokenStorage)

val taskRepository = TaskSurfacesRefreshingRepository(
    ReminderSchedulingRepository(
        OutboxTaskRepository(database, RoomTaskRepository(database.taskDao()), syncEngine),
        reminderScheduler,
        repository,
    ),
    applicationContext,
)
```

Y `AppViewModelFactory` repetía la misma lista de dependencias, como parámetros nulables con
`require*()` defensivos, para poder construir *cualquiera* de los nueve `ViewModel`s de la app:

```kotlin
// Antes: un factory con "un poco de todo", y un require* por cada dependencia.
class AppViewModelFactory(
    private val userPreferencesRepository: UserPreferencesRepository? = null,
    private val articleRepository: ArticleRepository? = null,
    private val articleId: String? = null,
    private val taskRepository: TaskRepository? = null,
    private val taskId: Long? = null,
    private val reminderScheduler: ReminderScheduler? = null,
    private val authRepository: AuthRepository? = null,
) : ViewModelProvider.Factory { /* ... un when por cada ViewModel ... */ }
```

Funciona, y de hecho es *exactamente* el tipo de DI manual que muchos tutoriales enseñan al
principio — pero el coste crece con cada feature: cada `ViewModel` nuevo exige tocar el factory,
cada repositorio nuevo exige tocar `MainActivity`, y **nada** avisa en tiempo de compilación si el
orden de los tres decoradores se rompe.

## 2. `@HiltAndroidApp` y `@AndroidEntryPoint`: el punto de entrada

Hilt genera, a partir de una anotación, un grafo de dependencias para toda la vida del proceso. El
punto de partida es la propia `Application`:

```kotlin
// app/src/main/java/com/neverlate/NeverLateApplication.kt
@HiltAndroidApp
class NeverLateApplication : Application()
```

`@HiltAndroidApp` dispara la generación de código: crea el componente raíz (`SingletonComponent`)
del que cuelgan todos los módulos. Se registra en el manifest exactamente como cualquier
`Application` personalizada:

```xml
<application android:name=".NeverLateApplication" ...>
```

Cualquier clase de Android que Hilt deba poder inyectar (aquí, solo `MainActivity`) se marca
`@AndroidEntryPoint`, y sus dependencias se piden con `@Inject` sobre propiedades `lateinit var`:

```kotlin
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var userPreferencesRepository: UserPreferencesRepository
    @Inject lateinit var taskRepository: TaskRepository
    @Inject lateinit var authRepositoryImpl: AuthRepositoryImpl
    // ...
}
```

Hilt rellena esos tres campos **antes** de que `onCreate` empiece a ejecutarse. Nada se construye ya
a mano aquí — `onCreate` solo hace dos cosas: cablear el gancho `onAuthenticated` del modo invitado
(ver §6) y disparar los efectos de arranque imperativos (canal de notificaciones, los dos
`WorkManager` periódicos, el refresco de la notificación de bloqueo) — exactamente los mismos que
antes, solo que ya no comparten función con la construcción de objetos.

## 3. `@Provides` frente a `@Binds`

Un `@Module` le enseña a Hilt cómo construir tipos que no puede adivinar por sí solo (una clase de
una librería externa, o una interfaz con más de una implementación posible). Hay dos anotaciones
para eso, y la diferencia es la pregunta clave de esta lección:

| Anotación | Cuándo | Ejemplo en este proyecto |
|---|---|---|
| `@Binds` | "Cuando pidan la interfaz `X`, dales esta implementación `Y`" — un alias, sin lógica. Requiere un método `abstract`. | `TokenStorage` -> `EncryptedTokenStorage`, `UserPreferencesRepository` -> `DataStoreUserPreferencesRepository` |
| `@Provides` | "Así es como se construye/obtiene este objeto" — hay una llamada real de por medio (un constructor con argumentos, una factoría, `getInstance()`). | `NeverLateDatabase.getInstance(context)`, cada capa del decorador de `TaskRepository` |

`@Binds` solo puede vivir en un método `abstract` (no hay cuerpo que escribir), así que los módulos
que lo usan son `abstract class`, no `object`. Kotlin no permite mezclar métodos `abstract` con
cuerpo en la misma clase, así que los `@Provides` de esos módulos viven en su `companion object` —
el patrón estándar de Hilt para combinar los dos:

```kotlin
// di/StorageModule.kt (abreviado)
@Module
@InstallIn(SingletonComponent::class)
abstract class StorageModule {

    @Binds
    @Singleton
    abstract fun bindTokenStorage(impl: EncryptedTokenStorage): TokenStorage

    companion object {
        @Provides
        @Singleton
        fun provideEncryptedTokenStorage(@ApplicationContext context: Context): EncryptedTokenStorage =
            EncryptedTokenStorage(context)
    }
}
```

`@Singleton` marca que Hilt construye el objeto **una sola vez** para todo el proceso — el mismo
efecto que antes lograba `NeverLateDatabase.getInstance`'s doble-check manual, o el simple hecho de
construir cada repositorio una vez en `MainActivity`. `@InstallIn(SingletonComponent::class)` dice
en qué *componente* (qué ámbito) vive ese binding; para esta app, con un solo proceso y sin
`Activity`/`Fragment` scoping más fino, todo cuelga del componente raíz.

## 4. La cadena de decoradores: el punto central de la lección

`TaskRepository` no es "una interfaz, una implementación" — es **cuatro** cosas que implementan la
misma interfaz, cada una envolviendo a la siguiente:

```
TaskSurfacesRefreshingRepository   (features 05/06 — refresca widget + notificación)
  └─ ReminderSchedulingRepository  (feature 09 — programa/cancela la alarma de cada tarea)
       └─ OutboxTaskRepository     (feature 11 — sella metadatos de sync + encola el outbox)
            └─ RoomTaskRepository  (real, respaldado por Room)
```

Si le pides a Hilt un `TaskRepository` sin más, no tiene forma de saber cuál de las cuatro quieres —
es **ambiguo**. La solución es un **qualifier**: una anotación propia, minúscula, que solo sirve
para distinguir "este `TaskRepository` en concreto" de los demás:

```kotlin
// di/Qualifiers.kt
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class RoomRepo

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class OutboxRepo

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ReminderRepo
```

Con eso, `di/RepositoryModule.kt` puede proveer las cuatro capas sin ambigüedad — y, lo importante,
**leyéndose de arriba abajo exactamente como las llamadas anidadas de antes**:

```kotlin
@Provides @Singleton @RoomRepo
fun provideRoomTaskRepository(taskDao: TaskDao): TaskRepository =
    RoomTaskRepository(taskDao)

@Provides @Singleton @OutboxRepo
fun provideOutboxTaskRepository(
    database: NeverLateDatabase,
    @RoomRepo delegate: TaskRepository,
    syncEngine: SyncEngine,
): TaskRepository = OutboxTaskRepository(database, delegate, syncEngine)

@Provides @Singleton @ReminderRepo
fun provideReminderSchedulingRepository(
    @OutboxRepo delegate: TaskRepository,
    reminderScheduler: ReminderScheduler,
    preferences: UserPreferencesRepository,
): TaskRepository = ReminderSchedulingRepository(delegate, reminderScheduler, preferences)

// Sin qualifier: esta es la que inyecta el resto de la app.
@Provides @Singleton
fun provideTaskRepository(
    @ReminderRepo delegate: TaskRepository,
    @ApplicationContext context: Context,
): TaskRepository = TaskSurfacesRefreshingRepository(delegate, context)
```

La capa más interna (`@RoomRepo`) no lleva qualifier en sus *dependencias* porque no envuelve a
nadie; la más externa (`provideTaskRepository`) no lleva qualifier en su *resultado* porque es la
única versión de `TaskRepository` que el resto de la app — cada `ViewModel`, `MainActivity` — puede
pedir sin ambigüedad. El orden es un **contrato de comportamiento**, no solo un detalle de
construcción: si `OutboxTaskRepository` no envolviera exactamente a `RoomTaskRepository`, o si
`ReminderSchedulingRepository` no fuera la envoltura inmediata de `OutboxTaskRepository`, una tarea
guardada dejaría de sincronizarse, o de programar su recordatorio, en silencio. Los qualifiers no
"arreglan" ese riesgo por sí solos — lo hacen **explícito y verificable leyendo el módulo**, en vez
de estar implícito en la indentación de un bloque de código en `MainActivity`.

## 5. `@HiltViewModel` + `hiltViewModel()`

Cada uno de los nueve `ViewModel`s de la app pasa por el mismo cambio mecánico: se anota
`@HiltViewModel`, su constructor se anota `@Inject`, y el `*Route` que lo usa cambia
`viewModel(factory = AppViewModelFactory(...))` por `hiltViewModel()`:

```kotlin
// Antes
class TasksViewModel(private val repository: TaskRepository) : ViewModel()

// Después
@HiltViewModel
class TasksViewModel @Inject constructor(private val repository: TaskRepository) : ViewModel()
```

```kotlin
// Antes
viewModel: TasksViewModel = viewModel(factory = AppViewModelFactory(taskRepository = taskRepository)),

// Después
viewModel: TasksViewModel = hiltViewModel(),
```

`hiltViewModel()` obtiene el `ViewModel` con el ciclo de vida correcto (ligado al
`NavBackStackEntry` de la ruta actual, igual que la función `viewModel()` de siempre), pero ya no
hace falta pasarle una fábrica: Hilt resuelve `repository` por su cuenta a partir de los módulos en
`di/`.

## 6. Argumentos de navegación vía `SavedStateHandle`

Dos `ViewModel`s necesitan algo más que repositorios: un argumento que viene de la ruta de
navegación. Antes, ese argumento era un parámetro más del factory (`articleId`, `taskId`); con
Hilt, se lee de un `SavedStateHandle` que Hilt inyecta automáticamente — siempre que la ruta declare
el `navArgument` correspondiente (algo que `AppNavHost` ya hacía y sigue haciendo sin cambios):

```kotlin
@HiltViewModel
class ArticleDetailViewModel @Inject constructor(
    private val repository: ArticleRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {
    // Un articleId ausente es un error de programación: cada pantalla de detalle necesita uno.
    private val articleId: String =
        requireNotNull(savedStateHandle.get<String>("articleId")) { "ArticleDetailViewModel requires an articleId" }
    // ...
}
```

`TaskEditViewModel` sigue el mismo patrón, pero con la semántica opuesta: un `taskId` ausente **no**
es un error, es la señal normal de "crear una tarea nueva" (la ruta `Routes.TASK_EDIT`, sin
`{taskId}`, simplemente no declara ese argumento — así que el `SavedStateHandle` no tiene esa
entrada, y `get<Long>("taskId")` devuelve `null` de forma natural):

```kotlin
@HiltViewModel
class TaskEditViewModel @Inject constructor(
    private val repository: TaskRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {
    private val taskId: Long? = savedStateHandle.get<Long>("taskId")
    // null -> crear; cualquier otro valor -> cargar y editar esa tarea
}
```

Los tests unitarios de estos dos `ViewModel`s ya no pasan `articleId`/`taskId` como parámetro con
nombre — construyen un `SavedStateHandle` con (o sin) esa entrada:

```kotlin
private fun savedStateHandleFor(articleId: String) = SavedStateHandle(mapOf("articleId" to articleId))

ArticleDetailViewModel(repository, savedStateHandleFor(pomodoro.id))
```

`@HiltViewModel`/`@Inject constructor` son solo anotaciones sobre un constructor normal — nada
impide seguir llamándolo directamente con `new`/`ViewModel(...)` en un test o en una vista previa,
sin ningún `HiltAndroidRule` ni aparato de test especial. Es lo que permite que
`TasksRouteSnackbarTest` (un test instrumentado de Compose) siga construyendo `TasksViewModel`
directamente con un `TaskRepository` falso, en vez de tener que arrancar un grafo de Hilt de mentira
solo para una prueba de UI.

## 7. Un caso especial: cuando el valor por defecto de Kotlin no basta

`StatsViewModel` tiene un parámetro con valor por defecto:

```kotlin
class StatsViewModel(
    private val repository: TaskRepository,
    private val clock: Clock = Clock.systemDefaultZone(),
) : ViewModel()
```

La intuición dice que Hilt, al no tener un binding para `Clock`, debería usar ese valor por
defecto — pero **no lo hace**. Dagger/Hilt genera código que llama al constructor `@Inject` pasando
**todos** los parámetros de forma explícita; el valor por defecto de Kotlin es una construcción del
propio compilador de Kotlin (un método `$default` sintético con una máscara de bits), invisible para
el generador de código de Hilt. El resultado, si no se hace nada más, es un error de compilación:

```
error: [Dagger/MissingBinding] java.time.Clock cannot be provided without an @Provides-annotated method.
```

La solución es añadir un `@Provides` explícito para `Clock` en `di/RepositoryModule.kt`:

```kotlin
@Provides
fun provideClock(): Clock = Clock.systemDefaultZone()
```

El valor por defecto de Kotlin **sigue ahí** y sigue siendo útil — `StatsViewModelTest` construye
`StatsViewModel(repository, fixedClock)` directamente, sin pasar por Hilt en absoluto — pero una
instancia obtenida vía `hiltViewModel()` en producción necesita este *binding* explícito. Es un buen
recordatorio de que "Hilt resuelve automáticamente" tiene un límite muy concreto: solo resuelve lo
que un `@Module` (o un `@Inject constructor`) le ha enseñado a resolver.

## 8. El gancho del modo invitado: lo único que sigue siendo manual

La feature 13 (modo invitado) dejó un cableado deliberadamente imperativo:
`authRepositoryImpl.onAuthenticated = { taskRepository.refreshFromServer() }`. Es un *seguro
adicional* para que, al iniciar sesión, se dispare el drenaje del outbox del invitado — ver la
lección 13 para el mecanismo principal (la recomposición de `MainAppNavHost`).

Ese gancho no se puede expresar como un grafo estático de Hilt: `taskRepository` tiene que **existir
ya** para poder asignarle su `onAuthenticated`, y ninguno de los dos puede depender del otro en su
propio constructor (crearía un ciclo). La solución sigue siendo la más simple: inyectar ambos por
separado en `MainActivity` y cablearlos a mano, en `onCreate` — el único sitio de todo el proyecto
donde una dependencia de Hilt se "conecta" imperativamente a otra:

```kotlin
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var taskRepository: TaskRepository
    @Inject lateinit var authRepositoryImpl: AuthRepositoryImpl

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        authRepositoryImpl.onAuthenticated = { taskRepository.refreshFromServer() }
        // ...
    }
}
```

`authRepositoryImpl` se inyecta como su **tipo concreto** (`AuthRepositoryImpl`), no como la
interfaz `AuthRepository` que ve el resto de la app — solo esta clase necesita el método
`notifyUnauthorized`/`onAuthenticated`, que no forma parte del seam público. `di/RepositoryModule.kt`
provee `AuthRepositoryImpl` como `@Singleton` (`provideAuthRepositoryImpl`) y además lo vincula a
`AuthRepository` (`bindAuthRepository`) — ambos puntos de inyección reciben **la misma instancia**,
nunca dos objetos distintos.

## 9. KSP, no kapt — y por qué Hilt/`hilt-navigation-compose` están fijados en versiones concretas

El procesador de anotaciones de Hilt corre a través de **KSP**, el mismo pipeline que ya usa Room —
un solo procesador de anotaciones en el proyecto, no dos (KSP + kapt) compitiendo por el mismo
código generado:

```kotlin
// app/build.gradle.kts
implementation(libs.hilt.android)
ksp(libs.hilt.compiler)
implementation(libs.androidx.hilt.navigation.compose)
```

Un detalle que solo aparece al intentar construir: las versiones **más recientes** de Hilt (2.59+) y
de `androidx.hilt:hilt-navigation-compose` (1.3.0+) exigen **AGP 9** — y este proyecto todavía usa
AGP 8.13.2 (ver `gradle/libs.versions.toml`). Por eso el catálogo fija Hilt en `2.58` y
`hilt-navigation-compose` en `1.2.0`: las últimas versiones de cada una que siguen siendo
compatibles con AGP 8.x. Cuando el proyecto suba de AGP, vale la pena revisar si ya se puede subir
también de Hilt.

---

## Repaso: ficheros de la feature

**Nuevo**
- [`NeverLateApplication.kt`](../app/src/main/java/com/neverlate/NeverLateApplication.kt) — `@HiltAndroidApp`.
- [`di/Qualifiers.kt`](../app/src/main/java/com/neverlate/di/Qualifiers.kt) — `@RoomRepo`/`@OutboxRepo`/`@ReminderRepo`.
- [`di/DatabaseModule.kt`](../app/src/main/java/com/neverlate/di/DatabaseModule.kt) — `NeverLateDatabase` + DAOs.
- [`di/NetworkModule.kt`](../app/src/main/java/com/neverlate/di/NetworkModule.kt) — los tres clientes Retrofit + `SyncEngine`.
- [`di/StorageModule.kt`](../app/src/main/java/com/neverlate/di/StorageModule.kt) — `TokenStorage`, `UserPreferencesRepository`.
- [`di/RepositoryModule.kt`](../app/src/main/java/com/neverlate/di/RepositoryModule.kt) — la cadena de `TaskRepository`, `AuthRepository`, `ArticleRepository`, `ReminderScheduler`, `Clock`.

**Retirado**
- `ui/navigation/AppViewModelFactory.kt` — borrado por completo.

**Modificados**
- [`MainActivity.kt`](../app/src/main/java/com/neverlate/MainActivity.kt) — `@AndroidEntryPoint`, tres `@Inject`, sin construcción.
- [`AndroidManifest.xml`](../app/src/main/AndroidManifest.xml) — `android:name=".NeverLateApplication"`.
- Los nueve `ViewModel`s (`OnboardingViewModel`, `ArticlesViewModel`, `ArticleDetailViewModel`,
  `TasksViewModel`, `TaskEditViewModel`, `StatsViewModel`, `SettingsViewModel`, `LoginViewModel`,
  `RegisterViewModel`) — `@HiltViewModel` + `@Inject constructor`.
- Cada `*Route` en `ui/<feature>/*Screen.kt` — `hiltViewModel()` en vez de `viewModel(factory = ...)`.
- [`AppNavHost.kt`](../app/src/main/java/com/neverlate/ui/navigation/AppNavHost.kt) — ya no enhebra repositorios hacia las rutas, solo lo que usa directamente (`authRepository`, `repository`, `taskRepository`).
- [`gradle/libs.versions.toml`](../gradle/libs.versions.toml) + [`app/build.gradle.kts`](../app/build.gradle.kts) + [`build.gradle.kts`](../build.gradle.kts) — plugin y dependencias de Hilt.

## Lo que te llevas

- Un contenedor de DI no es magia: **genera** el mismo código de construcción que ya escribías a
  mano, a partir de anotaciones — la diferencia es que el compilador comprueba que el grafo cuadra.
- `@Binds` es un alias interfaz -> implementación (un método `abstract`, sin cuerpo); `@Provides` es
  una construcción real con lógica. Cuando necesitas los dos en el mismo módulo, el patrón es una
  `abstract class` con un `companion object` para los `@Provides`.
- Cuando una misma interfaz tiene **varias** implementaciones simultáneas (una cadena de
  decoradores), un **qualifier** por capa es lo que elimina la ambigüedad — y lo que hace que el
  orden de la composición quede escrito, explícito, en el propio módulo.
- `hiltViewModel()` reemplaza a `viewModel(factory = ...)`; un argumento de navegación llega al
  `ViewModel` vía `SavedStateHandle`, no vía un parámetro de fábrica — y sigue siendo un
  constructor normal, invocable directamente desde un test.
- Un valor por defecto de Kotlin en un constructor `@Inject` **no** libra de proveer un binding: Hilt
  llama al constructor pasando siempre todos los parámetros de forma explícita.
- Las versiones de las librerías no son solo un número: `hilt-navigation-compose` más reciente
  arrastra un `androidx.lifecycle` que exige un AGP más nuevo del que este proyecto usa — fijar la
  versión adecuada, con el porqué escrito en el catálogo, evita esta sorpresa la próxima vez.
