# Lección 07b — Poner nombre al patrón: UDF, MVVM y capas

> Objetivo: **no escribir una feature nueva**, sino *nombrar, dibujar y consolidar* la arquitectura
> que la app usa desde la lección 02 sin haberla nombrado nunca. Desde el `OnboardingViewModel` de la
> 02 venimos repitiendo la misma forma —`ViewModel` + `StateFlow` + UI sin estado, funciones puras de
> dominio, repositorios tras una interfaz— por imitación. Esta lección le pone **vocabulario** (UDF,
> MVVM, capas UI/dominio/datos, el *seam*) y un **diagrama**, apoyándose en código que ya reconoces.
> Es una lección **transversal**: casi todo es leer lo que ya hay con otros ojos.

## Conceptos que aprendes aquí

Partiendo de las lecciones 02 (`ViewModel`/`StateFlow`, estado hoisteado), 04 (repositorios + funciones
puras de dominio, Room, `Flow`) y 07 (DataStore, preferencias reactivas):

- **UDF (flujo de datos unidireccional):** el estado **baja** (del `ViewModel` a la UI) y los eventos
  **suben** (de la UI al `ViewModel`). Por qué eso hace que *la UI sea una función del estado* y por
  qué eso la vuelve predecible y testeable.
- **MVVM en Android:** qué papel juega el `ViewModel` (sobrevive a los cambios de configuración, posee
  `viewModelScope`), por qué el `StateFlow` es la **única fuente** del estado de pantalla, y qué
  significa una **UI sin estado** (*state hoisting*).
- **Capas UI / dominio / datos:** qué vive en cada una y **por qué el dominio se mantiene puro** (sin
  Android, sin I/O) → determinista y barato de testear en la JVM.
- **El *seam* (costura) como concepto:** una **interfaz** (`TaskRepository`) que te deja
  **decorar/inyectar** comportamiento sin tocar la UI — exactamente lo que ya hacen los decoradores de
  recordatorios y de sync.

> **Nota sobre esta lección.** Es más didáctica que de producto. Al revisar el código para escribirla
> confirmamos que las capas y los *seams* ya eran coherentes, así que **no cambia comportamiento
> observable** ni toca código de producción: es una lección de *consolidación*. Al final ("Lo que NO
> cambió") explicamos por qué esa era la salida correcta y no una señal de pereza.

---

## 1. La idea: la UI es una función del estado

En una app de Compose no "actualizas widgets" a mano como en las Vistas clásicas (`textView.setText(...)`).
En su lugar **describes** cómo debe verse la pantalla **para un estado dado**, y cuando el estado cambia,
Compose vuelve a ejecutar esa descripción (recomposición). En pseudocódigo:

```
UI = f(estado)
```

Si la UI es una función del estado, entonces todo lo interesante está en **quién posee el estado** y
**cómo fluye**. La respuesta es el patrón que llevamos usando siete lecciones: **UDF**.

### Estado abajo, eventos arriba

**UDF (Unidirectional Data Flow)** significa que la información viaja en **un solo sentido** en cada
dirección:

- **El estado baja:** el `ViewModel` expone un `StateFlow` y la pantalla lo lee. La pantalla nunca
  inventa estado por su cuenta; solo *renderiza* el que le llega.
- **Los eventos suben:** cuando el usuario teclea o pulsa, la pantalla **no** modifica el estado
  directamente; llama a una función del `ViewModel` (`onQueryChange`, `save`, `toggleComplete`…). El
  `ViewModel` decide qué hacer y produce un **estado nuevo**, que vuelve a bajar.

El caso más limpio ya está comentado en el código, en `OnboardingViewModel` (lección 02):

```kotlin
// El estado sale hacia la UI vía [uiState]; la intención del usuario vuelve a entrar por
// [onNameChange] y [save] (flujo de datos unidireccional).
class OnboardingViewModel(private val repository: UserPreferencesRepository) : ViewModel() {
    private val _uiState = MutableStateFlow(OnboardingUiState())
    val uiState: StateFlow<OnboardingUiState> = _uiState.asStateFlow()

    fun onNameChange(newName: String) { /* produce un estado nuevo */ }
    fun save(onSaved: () -> Unit) { /* … */ }
}
```

Fíjate en el patrón `_uiState` privado (mutable) + `uiState` público (`StateFlow` de solo lectura vía
`asStateFlow()`). La pantalla **puede leer pero no puede escribir**: la única forma de cambiar el estado
es llamar a una función. Ese candado es UDF hecho tipo.

### Por qué nos importa

- **Predecible:** hay un único sitio donde el estado cambia. Si la pantalla muestra algo raro, el
  culpable está en el `ViewModel`, no repartido por veinte *callbacks* que mutan variables sueltas.
- **Testeable:** como la UI es `f(estado)`, para probar la lógica basta con probar cómo el `ViewModel`
  transforma eventos en estado — sin arrancar un emulador (lo vimos en la 04c).
- **Sobrevive a la rotación:** el estado vive en el `ViewModel`, no en la composición (más en §2).

---

## 2. MVVM: los tres papeles

**MVVM** (Model–View–ViewModel) es la forma concreta que toma UDF en Android. En nuestra app:

| Papel | Quién lo hace | Responsabilidad |
|-------|---------------|-----------------|
| **View** | Los `@Composable` (`OnboardingScreen`, `TasksScreen`, …) | Dibujar el estado y **emitir eventos** hacia arriba. Sin estado propio de negocio. |
| **ViewModel** | `OnboardingViewModel`, `TasksViewModel`, `StatsViewModel`, … | Poseer el `StateFlow` de estado de pantalla, orquestar corrutinas, hablar con los repos. |
| **Model** | Las capas **dominio** y **datos** (repos, Room, red, funciones puras) | La verdad de los datos y las reglas de negocio. |

### La UI sin estado (*state hoisting*)

Una `@Composable` "sin estado" no guarda el estado que muestra: lo **recibe** por parámetro y **sube**
los eventos por *callbacks*. El estado se *hoistea* (se eleva) al `ViewModel`. Por eso nuestras pantallas
tienen dos piezas: un `XxxRoute` que conecta el `ViewModel` y un `XxxScreen(state, onEvento)` que solo
pinta. La pantalla se vuelve trivial de previsualizar (`@Preview`) y de testear con `createComposeRule`
(lección 04c), porque le puedes pasar cualquier estado a mano.

El puente entre el `StateFlow` y la recomposición es siempre el mismo:

```kotlin
val uiState by viewModel.uiState.collectAsStateWithLifecycle()
```

`collectAsStateWithLifecycle` colecciona el `StateFlow` **respetando el ciclo de vida** (deja de
coleccionar cuando la pantalla no está visible) y devuelve un `State<T>` que dispara recomposición en
cada emisión. Esa línea es, literalmente, el `= f(estado)` de la §1.

### El `ViewModel` y su `viewModelScope`

El `ViewModel` **sobrevive a los cambios de configuración** (rotar la pantalla no lo recrea), a
diferencia de un `remember { mutableStateOf(...) }` dentro de una `@Composable`, que muere con la
composición. Ese matiz está comentado tal cual en `OnboardingViewModel`:

> Holds the Onboarding screen's state so it survives configuration changes (e.g. rotation) — something a
> plain `remember { mutableStateOf(...) }` inside a composable would NOT do.

Además el `ViewModel` trae un `viewModelScope`: un `CoroutineScope` atado a su vida que **cancela solo**
su trabajo cuando el `ViewModel` se limpia. Por eso todas nuestras corrutinas de pantalla se lanzan con
`viewModelScope.launch { … }` (o se comparten con `stateIn(viewModelScope, …)`): nadie fuga trabajo.

### Un estado, no diez campos sueltos

La convención del proyecto es exponer **un tipo de estado inmutable por pantalla**, no un puñado de
`StateFlow` sueltos. Toma dos formas según convenga:

- **`sealed interface`** cuando los estados son **excluyentes**: `TasksUiState` es `Loading` /
  `Content` / `Empty` / `NoResults`; `ArticlesUiState` añade `Error`. El `when` del renderizador está
  **obligado** a tratar cada caso, así que "cargando" no puede colarse como "vacío".
- **`data class`** cuando el estado es **un formulario con varios campos a la vez**: `OnboardingUiState`,
  `LoginUiState`, `TaskEditUiState`, `SettingsUiState`.

¿Y cuando de verdad hay **dos preguntas distintas**? Entonces sí conviven dos `StateFlow` — pero es una
decisión consciente y comentada, no un descuido. `ArticlesViewModel` mantiene `isRefreshing` **fuera**
de `ArticlesUiState` porque responde a otra pregunta ("¿hay una llamada de red en vuelo?") que el
`PullToRefreshBox` consume por separado. `TasksViewModel` mantiene `query` y `criteria` como
`StateFlow` propios *precisamente* para poder aplicar `debounce` a uno y no al otro (lección 04b). La
regla no es "siempre un único `StateFlow`", sino "**un estado por pregunta**, y que se note por qué".

---

## 3. Las tres capas: UI, dominio, datos

Aquí está el mapa completo de la app, con las flechas de UDF marcadas. El estado baja por la izquierda de
cada seam; los eventos suben por la derecha.

```
┌──────────────────────────── CAPA UI (Android, Compose) ────────────────────────────┐
│                                                                                     │
│   TasksScreen / StatsScreen / SettingsScreen …   ← @Composable sin estado           │
│        ▲ estado (StateFlow)        │ eventos (callbacks)                             │
│        │                           ▼                                                 │
│   TasksViewModel / StatsViewModel / …            ← poseen el StateFlow, orquestan    │
│                                                                                     │
└───────────────────────────────────┬─────────────────────────────────────────────────┘
                                     │  llama a funciones puras  +  usa el repo (seam)
                                     ▼
┌────────────────────── CAPA DOMINIO (Kotlin puro, sin Android) ──────────────────────┐
│                                                                                     │
│   urgencyLevelFor()   shapedBy()   weeklyStatsFor()   deadlineProgressFor()          │
│   ReminderPlanning (reminderTimeFor, remindersToSchedule)   domain/sync (SyncMerge)  │
│                                                                                     │
│   → funciones que reciben valores planos y devuelven valores planos. Sin I/O,       │
│     sin reloj propio, sin tipos de Android → deterministas y testeables en la JVM.  │
│                                                                                     │
└───────────────────────────────────┬─────────────────────────────────────────────────┘
                                     │  TaskRepository (INTERFAZ = el seam)
                                     ▼
┌───────────────────────────── CAPA DATOS (fuentes de verdad) ─────────────────────────┐
│                                                                                     │
│   RoomTaskRepository → Room (SQLite)          ← fuente de verdad local              │
│   CachingArticleRepository → Room + Retrofit  ← caché local + red                   │
│   DataStore (user_prefs)   ·   red backend (TasksApi/AuthApi + OkHttp)              │
│                                                                                     │
└─────────────────────────────────────────────────────────────────────────────────────┘
```

### Qué vive en cada capa (y por qué)

- **UI** (`ui/…`): todo lo que **es Android** de pantalla — `@Composable` de Material 3, `ViewModel`,
  navegación, temas (`ui/theme/`). Es la única capa que conoce Compose. Nótese que algunos "shells" de
  Android que *no* son pantalla también viven aquí por vecindad de feature: los receptores/servicios de
  recordatorios y notificaciones (`ui/notification/`) y el widget (`ui/widget/`).
- **Dominio** (`domain/…`): **Kotlin puro**, sin una sola importación de `android.*`. Son las reglas de
  negocio aisladas: `urgencyLevelFor` (nivel de urgencia de una cuenta atrás, lección 17), `shapedBy`
  (filtrar/ordenar/agrupar la lista, 03b), `weeklyStatsFor` (estadísticas, 04c), `deadlineProgressFor`
  (fracción de tiempo transcurrido, 19), `ReminderPlanning` (cuándo disparar un recordatorio, 09) y la
  reconciliación de sync en `domain/sync/` (`SyncMerge`, 11).
- **Datos** (`data/…`): repositorios, Room, DataStore, red (Retrofit/OkHttp), los DTO del contrato
  (`TaskDto`, `ArticleDto`, deliberadamente distintos de las entidades). Room es la **fuente de verdad
  local**; desde la 11 el backend es la fuente de verdad de lo *sincronizado*.

### Por qué el dominio es puro (y por qué merece la pena)

Fíjate en la cabecera de `ReminderPlanning.kt`:

> Todo aquí toma valores planos (un `Task`, un *lead time*, un instante) y devuelve un valor plano, así
> que `AlarmManagerReminderScheduler`, `ReminderReceiver` y `BootRescheduleWorker` **no** necesitan
> tests que cubran *esta* lógica — un test de JVM lo hace, sin emulador.

Ese es el trato: **empujamos la decisión a una función pura y dejamos que las clases de Android sean
cáscaras finas**. `urgencyLevelFor(remainingMillis, isTimedOut)` no lee ningún reloj: recibe los dos
números y devuelve un `enum`, así que un test le pasa combinaciones a mano. `weeklyStatsFor(tasks, now,
zone)` recibe el `now` en vez de llamar a `System.currentTimeMillis()`, así que `StatsViewModel` es el
**único** impuro de esa cadena (es quien lee el `Clock` inyectado) y la función queda trivial de probar.
Cuanta más lógica vive en el dominio, más barato y rápido es el testing (lección 04c).

---

## 4. El *seam*: una interfaz por la que colar comportamiento

Un **seam** ("costura") es un punto de una interfaz por el que puedes **insertar** o **envolver**
comportamiento **sin tocar** a quien la usa. En nuestra app el seam estrella es `TaskRepository`, una
**interfaz** (no una clase):

```kotlin
interface TaskRepository {
    fun observeTasks(): Flow<List<Task>>
    suspend fun saveTask(task: Task): Long
    suspend fun deleteTask(id: Long)
    // …
}
```

`TasksViewModel` y `TaskEditViewModel` dependen **solo de este contrato**, nunca de `RoomTaskRepository`
ni de ningún tipo de Room. Eso es lo que permite, sin que la UI se entere de nada, envolver la
implementación real en **decoradores**. Un decorador implementa la misma interfaz, delega en otro
`TaskRepository` y añade algo alrededor de cada escritura. En `MainActivity` los componemos como una
cebolla:

```kotlin
val taskRepository: TaskRepository = TaskSurfacesRefreshingRepository(   // refresca widget + notif.
    ReminderSchedulingRepository(                                        // (re)programa la alarma
        OutboxTaskRepository(                                            // encola cambio para el backend
            database, RoomTaskRepository(database.taskDao()), syncEngine // ← la verdad, en Room
        ),
        reminderScheduler, repository,
    ),
    applicationContext,
)
```

Cada `saveTask` recorre la cebolla de fuera hacia dentro: refresca las superficies pasivas
(widget/notificación, features 05–06), (re)programa el recordatorio (09), encola una fila de *outbox*
para el backend (11) y, por fin, escribe la fila en Room. **La pantalla de Tareas no sabe que nada de
esto existe**: sigue viendo un `TaskRepository` y ya. Añadimos recordatorios y sync sin tocar el
`ViewModel` de la 04. Eso es el valor del seam.

> El mismo patrón está en `ArticleRepository` y `UserPreferencesRepository`: interfaces primero,
> implementaciones detrás. No es casualidad; es la convención.

### El seam también es lo que hace posible el *fake* en los tests

Como el `ViewModel` depende de la interfaz, un test le inyecta un `TaskRepository` **falso** en memoria
—sin Room, sin red— y comprueba la transformación evento→estado en la JVM. El seam sirve dos amos a la
vez: **decorar** en producción e **inyectar** en tests.

### Todo esto está cableado a mano (DI manual)

¿Quién construye esa cebolla y se la pasa a cada `ViewModel`? Nosotros, a mano, en `MainActivity`
(*composition root*) y en `AppViewModelFactory`. A eso se le llama **inyección de dependencias manual**:
sin framework, perfecta para un proyecto de este tamaño. El coste crece con cada feature (mira el
constructor de `MainActivity`: auth, token storage, sync engine, tres decoradores…). Cuando ese cableado
manual se vuelve máximamente tedioso llega su relevo natural: **Hilt**, en la lección **13d**, que
automatiza exactamente este pegamento sin cambiar la arquitectura que acabamos de nombrar.

---

## 5. Lo que NO cambió (y por qué está bien)

Esta lección es de **consolidación**, no de refactor. Al revisar las tres capas contra una lista de
posibles incoherencias, **no encontramos ninguna que mereciera un cambio**:

- **Forma del estado de UI:** todas las pantallas ya exponen **un** tipo de estado inmutable
  (`sealed interface` o `data class`) por `StateFlow`. Los `StateFlow` extra (`isRefreshing`, `query`,
  `criteria`) están **justificados y comentados** uno a uno.
- **Cálculos en la capa equivocada:** los cálculos no triviales ya viven en `domain/` (`urgencyLevelFor`,
  `shapedBy`, `weeklyStatsFor`, `deadlineProgressFor`, `ReminderPlanning`, `SyncMerge`). Los `ViewModel`
  solo orquestan (leen relojes, lanzan corrutinas, hablan con repos) — que es justo lo *impuro* que les
  toca.
- **Cosas mal ubicadas:** dominio en `domain/`, datos en `data/`, UI en `ui/`. Sin fugas.
- **Estado que debería hoistearse:** el estado de pantalla ya vive en los `ViewModel`; hasta el
  *ticker* de la cuenta atrás es un `Flow` dentro de `TasksViewModel`, no un temporizador en la
  `@Composable`.

Por eso la salida correcta era **la lección sola, sin tocar código de producción**. En un proyecto que
lleva la coherencia como valor desde la 02, "no hay nada que alinear" no es pereza: es la **prueba** de
que el patrón ya estaba bien aplicado. Nombrarlo es justamente lo que te permite ahora *verlo* — y
mantenerlo — feature tras feature. Y como no tocamos código, **no cambia nada observable**, no hay
backend, contrato, versión de base de datos, permiso ni dependencia nuevos, y **los tests existentes
siguen verdes tal cual**.

---

## Resumen

La app usa **UDF** desde la 02: el estado **baja** por un `StateFlow` y los eventos **suben** por
*callbacks*, de modo que la UI es una **función del estado** (`= f(estado)`, vía
`collectAsStateWithLifecycle`). Su forma concreta en Android es **MVVM**: `@Composable` sin estado
(View) ↔ `ViewModel` dueño del `StateFlow` (ViewModel) ↔ dominio + datos (Model). Organizamos el código
en tres **capas** —UI (Compose, Android), dominio (Kotlin puro y testeable: `urgencyLevelFor`,
`shapedBy`, `weeklyStatsFor`, `ReminderPlanning`, `SyncMerge`) y datos (repos, Room, red)— y las cosemos
con **seams**: la interfaz `TaskRepository`, que dejamos **decorar** (recordatorios, sync, widget) e
**inyectar** (tests) sin que la UI se entere. Todo ese pegamento es **DI manual** hoy, y su relevo es
Hilt (13d). No escribimos una feature: le pusimos **nombre, diagrama y vocabulario** a lo que ya estaba
—y confirmamos que ya era coherente— sin cambiar comportamiento.

## Documentación oficial

- [Guide to app architecture](https://developer.android.com/topic/architecture) — la referencia de
  Google para capas UI/dominio/datos.
- [UI state and state holders](https://developer.android.com/topic/architecture/ui-layer/stateholders)
  — el papel del `ViewModel` y el estado de UI.
- [`StateFlow` y `SharedFlow`](https://developer.android.com/kotlin/flow/stateflow-and-sharedflow).
- [State hoisting en Compose](https://developer.android.com/develop/ui/compose/state-hoisting).
- [Unidirectional Data Flow](https://developer.android.com/develop/ui/compose/architecture#udf) en Compose.

## Siguiente lección

La [**08 — Internacionalización (i18n)**](08-i18n.md): sacamos todo el texto a recursos de cadena, con
`plurals` y fechas/números *locale-aware*. Y más adelante, la [**13d — Hilt**](13d-hilt-di.md) recogerá
el guante de la DI manual que acabamos de nombrar aquí.
