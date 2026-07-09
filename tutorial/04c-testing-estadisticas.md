# Lección 04c — Estadísticas testeables (introducción al testing)

El proyecto lleva desde la lección 02 con dos carpetas de tests (`app/src/test` para tests **JVM** y
`app/src/androidTest` para tests **instrumentados**), y varias features han escrito tests por el camino.
Pero **ninguna lección ha enseñado a escribir tests como tema propio**. Esta lo hace explícito.

Como en la 04b, **la pantalla es la excusa; el tema es otro**. Aquí construimos una **pantalla de
estadísticas** —"tareas completadas esta semana", "% a tiempo", "por vencer"— y, para que esos números
sean *honestos*, añadimos un campo de **completado real** (`Task.completedAt`) que se sincroniza con el
backend. Pero el peso de la lección es **cómo se testea cada capa**: una función pura con tests JVM, un
`ViewModel` con tests de corrutinas, y una pantalla `@Composable` con tests de UI.

## Conceptos que aprendes aquí

Partiendo de la 02 (primeros tests JVM), la 04 (lógica pura de tiempo en `TaskTiming`) y la 04b
(corrutinas y `Flow`):

- **Diseñar para testear:** por qué extraer una **función pura** con el tiempo **inyectado** (`now`,
  `zone`, `Clock`) convierte un test en algo trivial y determinista — sin *mock* de reloj, sin emulador.
- **Tests unitarios JVM con JUnit:** la estructura *arrange / act / assert*, las aserciones
  (`assertEquals`, `assertNull`, `assertTrue`) y cómo se cubren los **casos límite** (fronteras, empates,
  entradas vacías).
- **Test doubles / fakes:** sustituir una dependencia real (`TaskRepository`) por una **falsa** que tú
  controlas, para aislar lo que estás probando.
- **Probar corrutinas y `Flow`:** `runTest`, `StandardTestDispatcher`, `Dispatchers.setMain`, y el
  **control del tiempo virtual** (`runCurrent`, `advanceUntilIdle`).
- **Tests de UI de Compose:** `createComposeRule`, `setContent`, la **semántica** como superficie de test
  (`onNodeWithText`, `onNodeWithContentDescription`, `performClick`).

Al final hay un repaso corto del resto de la feature (completado sincronizado + la **primera migración de
Room real** del proyecto), que es el *código bajo prueba*.

---

## 1. La idea central: diseñar para testear

Un test es fácil o difícil **antes** de escribirlo: lo decide el diseño del código que pruebas. Compara
dos formas de calcular "tareas completadas esta semana":

- **Difícil de testear:** el cálculo vive dentro del `@Composable`, lee `System.currentTimeMillis()` y la
  zona horaria del dispositivo. Para probarlo necesitas un emulador, y el resultado **cambia según el día
  en que ejecutes el test** (esta semana no es la misma la semana que viene). Un test así es frágil o
  directamente imposible.
- **Fácil de testear:** el cálculo es una **función pura** que recibe la lista de tareas, el instante
  `now` y la zona `zone` **como parámetros**, y devuelve un valor. No lee ningún reloj, no toca la base de
  datos, no sabe que Android existe. El test le pasa un `now` fijo y comprueba el número exacto que sale.

La segunda es la que aplicamos. Es el mismo patrón que ya usan `ReminderPlanning.kt` (lección 09) y
`TaskListShaping.kt` (lección 03b): **la decisión vive en Kotlin puro; la capa de Android es una cáscara
fina alrededor.** La función se llama `weeklyStatsFor` y vive en
[`domain/tasks/TaskStats.kt`](../app/src/main/java/com/neverlate/domain/tasks/TaskStats.kt):

```kotlin
data class WeeklyTaskStats(
    val completedThisWeek: Int,
    val onTimePercent: Int?,   // null = no hay nada con fecha que medir esta semana
    val dueSoon: Int,
)

fun weeklyStatsFor(tasks: List<Task>, now: Long, zone: ZoneId): WeeklyTaskStats { … }
```

Fíjate en lo que **no** hay en esa firma: ni `Context`, ni `Clock`, ni acceso a Room. Todo lo que la
función necesita para decidir **entra como parámetro**. Ese es el detalle que hace que el test de la
sección siguiente sea trivial.

> **`Int?` en `onTimePercent`.** El `?` (null-safety, lección 03b) codifica un caso real: si esta semana
> no completaste ninguna tarea *con fecha límite*, no hay ratio que calcular. Devolvemos `null` —no `0`—
> porque un `0%` se leería como "siempre tarde", que es mentira. La UI traduce ese `null` a un guion
> ("—"). Diseñar el tipo para que el caso "no aplica" sea imposible de confundir con "cero" es, en sí
> mismo, diseñar para testear.

---

## 2. Tests unitarios JVM con JUnit: *arrange / act / assert*

Un test unitario JVM es una función anotada con `@Test` dentro de una clase en `app/src/test/`. Corre en
la JVM de tu máquina (rápido, sin emulador). Casi todos siguen la misma estructura de tres pasos, el
patrón **AAA**:

1. **Arrange** (preparar): construye las entradas.
2. **Act** (actuar): llama a la función bajo prueba.
3. **Assert** (comprobar): verifica el resultado con una *aserción*.

Mira un test de
[`TaskStatsTest.kt`](../app/src/test/java/com/neverlate/domain/tasks/TaskStatsTest.kt):

```kotlin
@Test
fun `completed on time and completed late both count toward completedThisWeek, but only on-time toward the ratio`() {
    // Arrange
    val onTime = task(completedAt = weekStart + 1_000L, deadline = weekStart + 2_000L) // antes de su fecha
    val late = task(completedAt = weekStart + 3_000L, deadline = weekStart + 2_000L)   // después de su fecha

    // Act
    val stats = weeklyStatsFor(listOf(onTime, late), now, zone)

    // Assert
    assertEquals(2, stats.completedThisWeek)
    assertEquals(50, stats.onTimePercent)
}
```

Varias cosas que aprender de aquí:

- **El nombre del test es una frase.** Kotlin permite nombres de función entre backticks (`` ` ``) con
  espacios. Un buen nombre describe el **comportamiento**, no la implementación: cuando falla, el informe
  te dice qué regla se rompió sin abrir el código.
- **Aserciones.** `assertEquals(esperado, real)` es la más común (ojo al orden: primero lo esperado). Hay
  más: `assertNull`, `assertTrue`, `assertFalse`. Vienen de `org.junit.Assert`.
- **`now` y `zone` son fijos.** Se preparan una sola vez para toda la clase:

  ```kotlin
  private val zone: ZoneId = ZoneId.of("America/New_York")
  private val now = at(2024, 1, 10, 15, 30)   // miércoles fijo
  ```

  Como son constantes, el test da el **mismo resultado hoy, mañana y en la máquina de integración
  continua**. Esto es lo que significa *determinista*, y es imposible de conseguir si la función leyera el
  reloj por su cuenta.

### 2.1. Cubrir los casos límite

El valor de una batería de tests no está en probar el caso feliz una vez, sino en fijar los **bordes**,
que es donde viven los bugs. `TaskStatsTest` prueba, entre otros:

- **entrada vacía** → todo a cero, `onTimePercent == null`;
- una **tarea borrada** (`deleted = true`) que se excluye de las tres cuentas;
- completada **a tiempo** vs. **tarde** (el `50%` de arriba);
- completada **sin fecha límite** → cuenta como completada, pero se excluye del ratio;
- las **fronteras de la semana**: un `completedAt` exactamente en `weekStart` (dentro) y exactamente en
  `weekEnd` (fuera, ya es la semana siguiente);
- las **fronteras de "por vencer"**: una fecha exactamente en `now` (fuera), justo después (dentro),
  exactamente en `now + 24h` (dentro), y justo después (fuera);
- el **redondeo** de `onTimePercent` (1 de 3 a tiempo → `33`).

Estos tests son también **documentación ejecutable**: la ambigüedad "¿el borde de la semana es inclusivo o
exclusivo?" no se responde con un comentario que puede mentir, sino con un test que, si alguien cambia la
comparación de `<` a `<=`, se pone rojo al instante.

---

## 3. Test doubles: un `TaskRepository` falso

La función pura no necesitaba dependencias. El `ViewModel` sí: observa un `TaskRepository`. Para probar el
`ViewModel` **sin** una base de datos real, le pasamos un **doble de test** (*test double*): una
implementación falsa de la interfaz que nosotros controlamos. En
[`StatsViewModelTest.kt`](../app/src/test/java/com/neverlate/ui/stats/StatsViewModelTest.kt):

```kotlin
private class FakeTaskRepository(initialTasks: List<Task> = emptyList()) : TaskRepository {
    private val tasksFlow = MutableStateFlow(initialTasks)

    override fun observeTasks(): Flow<List<Task>> = tasksFlow

    // El StatsViewModel solo llama a observeTasks(); el resto son stubs mínimos.
    override suspend fun saveTask(task: Task): Long = task.id
    override suspend fun deleteTask(id: Long) {}
    // …

    /** Empuja una lista nueva, como haría el repositorio real tras un cambio. */
    fun setTasks(tasks: List<Task>) { tasksFlow.value = tasks }
}
```

Ideas clave:

- **Implementa la interfaz, no la clase real.** Que `TaskRepository` sea una interfaz (una "costura",
  *seam*) es lo que permite enchufar un doble. Por eso las lecciones anteriores insistieron en programar
  contra interfaces.
- **Un fake, no un mock.** Aquí escribimos la clase a mano (un `MutableStateFlow` que emite lo que le
  digas). No usamos librerías de *mocking*: para este proyecto, un fake explícito es más legible y más
  didáctico.
- **Solo lo que se usa.** El `ViewModel` únicamente llama a `observeTasks()`, así que el resto de métodos
  son *stubs* vacíos. Un doble no simula toda la realidad, solo la parte que tu test toca.
- **Controlable.** `setTasks(...)` nos deja simular que "la base de datos cambió" a mitad de un test, para
  comprobar que el `ViewModel` reacciona.

---

## 4. Probar corrutinas y `Flow`: tiempo virtual

El `ViewModel` expone su estado como un `StateFlow` derivado con `stateIn` (lección 04b):

```kotlin
val uiState: StateFlow<StatsUiState> = repository.observeTasks()
    .map { tasks ->
        if (tasks.isEmpty()) StatsUiState.Empty
        else StatsUiState.Content(weeklyStatsFor(tasks, clock.millis(), clock.zone))
    }
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StatsUiState.Loading)
```

Dos cosas lo hacen "difícil" de testear con un test normal, y las dos tienen solución en
`kotlinx-coroutines-test` (ya en el catálogo de versiones):

**(a) Lee un reloj real.** `clock.millis()` y `clock.zone`. La función pura no lo hacía; el `ViewModel`
sí, porque *alguien* tiene que leer la hora de verdad — y ese alguien es el único punto impuro de toda la
cadena. En el test le inyectamos un **reloj fijo**:

```kotlin
private val now = ZonedDateTime.of(2024, 1, 10, 15, 30, 0, 0, zone).toInstant()
private val clock: Clock = Clock.fixed(now, zone)   // no avanza nunca
```

Que el `ViewModel` acepte un `Clock` en el constructor (con un valor por defecto real para producción) es
otra vez **diseñar para testear**: el mismo truco que la costura del `TaskRepository`, aplicado al tiempo.

**(b) Es asíncrono.** El pipeline `map`/`stateIn` no produce su valor de forma síncrona; corre en
corrutinas sobre `Dispatchers.Main`. El test lo controla así:

```kotlin
@OptIn(ExperimentalCoroutinesApi::class)
class StatsViewModelTest {
    private val testDispatcher = StandardTestDispatcher()

    @Before fun setUp() { Dispatchers.setMain(testDispatcher) }   // Main → dispatcher de test
    @After  fun tearDown() { Dispatchers.resetMain() }

    @Test
    fun `repository with tasks produces Content with the weeklyStatsFor result for the fixed clock`() =
        runTest(testDispatcher) {
            val viewModel = StatsViewModel(FakeTaskRepository(listOf(onTime, dueSoon)), clock)
            collectUiState(viewModel)   // stateIn(WhileSubscribed) no hace nada sin colector

            runCurrent()                // ejecuta el trabajo pendiente en el tiempo virtual

            val state = viewModel.uiState.value
            assertTrue(state is StatsUiState.Content)
            assertEquals(
                WeeklyTaskStats(completedThisWeek = 1, onTimePercent = 100, dueSoon = 1),
                (state as StatsUiState.Content).stats,
            )
        }
}
```

Lo que hay que entender:

- **`runTest`** ejecuta el cuerpo en un *scheduler* de **tiempo virtual**: no hay esperas reales, el reloj
  de las corrutinas lo controlas tú. Un `delay(5_000)` dentro de `runTest` no tarda 5 segundos, salta al
  instante cuando tú lo ordenas.
- **`StandardTestDispatcher`** encola el trabajo sin ejecutarlo hasta que lo pides — por eso hace falta
  **`runCurrent()`** (ejecuta lo pendiente ahora) o `advanceUntilIdle()` (ejecuta hasta que no queda
  nada). Esto hace el *cuándo* explícito.
- **`Dispatchers.setMain(...)`** sustituye el `Dispatchers.Main` real (que no existe fuera de Android) por
  el dispatcher de test; `resetMain()` lo restaura. Sin esto, `viewModelScope` (que usa `Main`) fallaría.
- **`WhileSubscribed` necesita un colector.** Hay un test que lo demuestra al revés: sin nadie colectando,
  `advanceUntilIdle()` no cambia nada y `uiState` se queda en su semilla `Loading`. Y `setTasks(...)`
  sobre el fake prueba que el estado **se recalcula** cuando cambian los datos aguas arriba, con el reloj
  siempre quieto.

---

## 5. Tests de UI de Compose: la semántica como superficie

Los tests de la pantalla viven en `app/src/androidTest/` (son **instrumentados**: necesitan un
emulador/dispositivo para correr). La herramienta central es `createComposeRule`, que monta composables de
forma aislada, **sin lanzar la app entera**. En
[`StatsScreenTest.kt`](../app/src/androidTest/java/com/neverlate/ui/stats/StatsScreenTest.kt):

```kotlin
class StatsScreenTest {
    @get:Rule val composeTestRule = createComposeRule()

    @Test
    fun content_showsAllThreeStatNumbersAndLabels() {
        val stats = WeeklyTaskStats(completedThisWeek = 5, onTimePercent = 80, dueSoon = 2)

        composeTestRule.setContent {
            NeverLateTheme {
                StatsScreen(uiState = StatsUiState.Content(stats), onBack = {})
            }
        }

        composeTestRule.onNodeWithText("5").assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.stats_completed_label)).assertIsDisplayed()
    }
}
```

Por qué esto es tan directo:

- **La pantalla es *stateless*.** `StatsScreen` recibe un `StatsUiState` ya cocinado y un callback
  `onBack`; no crea `ViewModel` ni toca repositorios. El test le pasa el estado a mano (`Content`,
  `Empty`) y comprueba lo que se dibuja. Es el mismo *hoisting* de estado que predican todas las lecciones
  de Compose: además de limpio, es **testeable**.
- **La semántica es la superficie de test.** No inspeccionamos píxeles ni tipos internos: buscamos nodos
  por el **texto** (`onNodeWithText`) o por su **`contentDescription`** (`onNodeWithContentDescription`).
  Ese árbol de semántica es el mismo que consume TalkBack, así que **testear por semántica prueba también
  la accesibilidad** (lección 18).
- **Se pueden simular gestos.** El test del botón de volver hace `performClick()` sobre el nodo con el
  `contentDescription` de la flecha y comprueba que `onBack` se llamó una vez:

  ```kotlin
  composeTestRule.onNodeWithContentDescription(string(R.string.stats_back_content_description))
      .performClick()
  assert(backClicks == 1)
  ```

- **Casos que importan al producto.** Hay un test para el estado vacío (`StatsUiState.Empty` muestra el
  `MessageState` compartido, no una columna de ceros) y otro para el placeholder "—" cuando
  `onTimePercent` es `null`. La lógica de "qué se ve cuando no hay datos" también se prueba.

También ampliamos
[`TasksScreenTest.kt`](../app/src/androidTest/java/com/neverlate/ui/tasks/TasksScreenTest.kt) para el
nuevo `Checkbox` de marcar-como-hecha: al pulsarlo, invoca `onToggleComplete` con la **tarea entera** (no
solo su id — el `ViewModel` necesita el `completedAt` actual para decidir si marca o desmarca).

---

## 6. El código bajo prueba: completado real y sincronizado

Todo lo anterior prueba código que existe por esta feature. Un repaso breve de ese código (el "qué", ya
que el "cómo se testea" era el tema):

- **Campo real de completado.** `Task` gana `completedAt: Long?` (`null` = pendiente; si no, el instante
  en epoch-millis en que se marcó). Un `Checkbox` en cada fila de la lista lo pone/quita a través del
  camino de guardado normal (`TaskRepository.saveTask`), que ya escribe la fila **y** su registro de
  *outbox* en la misma transacción (feature 11). Una tarea completada **se queda** en la lista, tachada,
  ordenada al final y sin cuenta atrás ni color de urgencia.
- **Se sincroniza de punta a punta.** Siguiendo la regla del proyecto de *contrato primero*, `completedAt`
  se añadió a `TaskDto` en [`docs/api/contract.md`](../docs/api/contract.md), a la columna
  `tasks.completed_at` de Postgres, y al PATCH (con el mecanismo `PatchValue` que distingue "campo
  omitido" de "campo presente a `null`", es decir, *desmarcar*). No hay endpoint nuevo: reutiliza el CRUD
  de `/tasks` y viaja bajo la reconciliación *last-write-wins* por `updatedAt` que ya existía, **sin
  cambiar la lógica de fusión** (`SyncMerge.kt`).
- **La primera migración de Room *real* del proyecto.** Añadir la columna sube la versión de la base de
  datos de **3 a 4**. Hasta ahora, cada cambio de esquema usaba `fallbackToDestructiveMigration`, que
  **borra los datos**. Eso era aceptable pre-release, y "doblemente" en la feature 11 (las tareas pasaron
  a ser propiedad del backend, así que la caché local se repoblaba al iniciar sesión). Pero la **feature
  13 (modo invitado)** rompió esa excusa: las tareas de un invitado viven **solo** en el dispositivo. Una
  migración destructiva se las llevaría por delante. Así que escribimos una `Migration(3, 4)` de verdad:

  ```kotlin
  val MIGRATION_3_4 = object : Migration(3, 4) {
      override fun migrate(db: SupportSQLiteDatabase) {
          db.execSQL("ALTER TABLE tasks ADD COLUMN completedAt INTEGER")
      }
  }
  ```

  Es el caso más simple de migración (una columna nueva y anulable no tiene datos que reconciliar), pero
  ilustra el concepto que la lección **13b** profundizará (`TypeConverter`, `AutoMigration`, tests de
  migración con `exportSchema`).

---

## 7. Ejecutar los tests

```bash
# Tests unitarios JVM (rápidos, sin emulador): la función pura, el ViewModel, la reconciliación.
./gradlew :app:testDebugUnitTest

# Tests instrumentados de Compose (necesitan un emulador/dispositivo en marcha).
./gradlew :app:connectedDebugAndroidTest

# Tests del backend (round-trip de completedAt por create/patch/pull; hermético, sin Docker).
cd backend && ./gradlew test
```

Si solo quieres una clase, `--tests`:

```bash
./gradlew :app:testDebugUnitTest --tests "com.neverlate.domain.tasks.TaskStatsTest"
```

El informe HTML queda en `app/build/reports/tests/testDebugUnitTest/index.html`.

---

## Resumen

- **El diseño decide la testeabilidad.** Extraer una **función pura** (`weeklyStatsFor`) con el tiempo
  **inyectado** hace que el test sea determinista y no necesite ni reloj falso ni emulador.
- **Tests JVM con JUnit:** estructura *arrange / act / assert*, nombres que describen comportamiento,
  aserciones, y una batería que fija los **casos límite** (fronteras, empates, vacíos) como documentación
  ejecutable.
- **Test doubles:** un `FakeTaskRepository` que implementa la interfaz aísla al `ViewModel` de la base de
  datos real.
- **Probar corrutinas/`Flow`:** `runTest` + `StandardTestDispatcher` + `Dispatchers.setMain` dan
  **control del tiempo virtual**; un `Clock.fixed(...)` inyectado sustituye al reloj real.
- **Tests de UI de Compose:** `createComposeRule` sobre una pantalla *stateless*, con la **semántica**
  (`onNodeWithText` / `onNodeWithContentDescription`) como superficie — que de paso prueba la
  accesibilidad.
- Y de fondo, la app ganó **completado real sincronizado** y su **primera migración de Room de verdad**,
  que son justo el código que estos tests protegen.

En la lección siguiente (05) la app sale de su pantalla por primera vez: un **widget** de pantalla de
inicio con Glance y trabajo en background con WorkManager.
