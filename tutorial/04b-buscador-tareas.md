# Lección 04b — Buscador de tareas (corrutinas y `Flow` a fondo)

La lección 04 introdujo corrutinas y `Flow` de forma **pragmática**: un DAO que devuelve
`Flow<List<Task>>`, un temporizador con `delay`, `flatMapLatest` para encender/apagar el tic. Funcionó,
pero dejó sin nombrar la maquinaria que había debajo. Esta lección la desmonta con un caso real: un
**buscador reactivo** sobre la lista de tareas.

La feature 03b (que se lee justo antes de la 04) ya dejó un campo de búsqueda que filtra la lista *en
cada pulsación*, dentro de un `collect` imperativo. Aquí **no añadimos un campo nuevo**: reescribimos su
cableado interno para que el filtrado pase por `debounce` (esperar a que dejes de teclear) y `combine`
(mezclar consulta + tareas + criterios), y para que el estado de la pantalla se **derive** de forma
declarativa con `stateIn`. El diff de UI es minúsculo a propósito; el peso de la lección es conceptual.

## Conceptos que aprendes aquí

Partiendo de la 04 (Room, `Flow`, `viewModelScope`) y la 03b (colecciones, `TaskListShaping.kt` puro):

- **Concurrencia estructurada:** qué es un `CoroutineScope`, por qué `viewModelScope` ata el trabajo al
  ciclo de vida del `ViewModel`, qué es un *dispatcher*, y por qué **el trabajo se cancela con su scope**.
- **Operadores de [`Flow`](https://kotlinlang.org/docs/flow.html):** `debounce`, `combine`, `map`,
  `distinctUntilChanged`, y `stateIn` — el operador que convierte un `Flow` **frío** en un `StateFlow`
  **caliente** con valor inicial y una política de compartición.
- **`StateFlow` vs `SharedFlow`:** qué distingue a cada uno y por qué el estado de una pantalla encaja en
  `StateFlow`.
- **Cancelación y `async`/`await` (mención):** cómo una consulta nueva descarta la anterior, y un apunte
  sobre paralelismo real.

---

## 1. Concurrencia estructurada: `CoroutineScope` y `viewModelScope`

Una **corrutina** es trabajo que puede *suspenderse* (pausarse sin bloquear el hilo) y reanudarse más
tarde. Una corrutina no vive en el aire: siempre pertenece a un **`CoroutineScope`**, que es a la vez su
"dueño" y su interruptor de apagado. Esta es la idea de **concurrencia estructurada**: ninguna corrutina
es huérfana; cada una tiene un scope padre, y **cuando el padre se cancela, todos sus hijos se cancelan
con él**. No hay corrutinas sueltas ejecutándose después de que su dueño desaparezca.

En un `ViewModel`, ese scope nos lo regala Android: **`viewModelScope`**. Está atado al ciclo de vida del
`ViewModel`, así que cuando el usuario sale de la pantalla y el `ViewModel` se destruye
(`onCleared()`), `viewModelScope` se cancela y **todo** el trabajo que lanzamos en él —el temporizador,
la observación de Room, el pipeline del buscador— se detiene solo. No hay que acordarse de cancelar nada
a mano; esa es justo la fuga de recursos que la concurrencia estructurada previene por diseño.

Un **dispatcher** decide *en qué hilo* corre una corrutina (`Dispatchers.Main` para tocar la UI,
`Dispatchers.IO` para disco/red, `Dispatchers.Default` para CPU). En este `ViewModel` no elegimos
dispatcher explícito: los operadores de `Flow` que veremos corren en el contexto de quien colecta
(la UI, en el hilo principal) y solo *suspenden* —no bloquean— mientras esperan, así que no hace falta
saltar de hilo. Lo importante para la lección: el dispatcher es *dónde* corre; el scope es *cuánto tiempo
puede correr*.

---

## 2. El punto de partida: un `collect` imperativo (lo que teníamos)

Antes de la 04b, el `ViewModel` observaba las tareas y **asignaba a mano** un `MutableStateFlow` desde
dentro de un `collect`, mezclando en el mismo sitio dos cosas muy distintas: *calcular qué mostrar* y
*ejecutar un efecto* (auto-pausar una tarea cuyo contador llega a cero). Simplificado:

```kotlin
// Estilo pre-04b (imperativo): un collect que hace de todo.
init {
    viewModelScope.launch {
        uiTasksFlow.combine(_criteria) { tasks, criteria -> tasks to criteria }
            .collect { (tasks, criteria) ->
                autoPauseTimedOut(tasks)                       // efecto secundario
                _uiState.value = shapeToUiState(tasks, criteria) // estado, asignado a mano
            }
    }
}
```

Funciona, pero tiene dos problemas que la 04b arregla:

1. **Estado y efecto viven enredados** en el mismo `collect`. El estado debería ser una *función pura* de
   las entradas; el efecto debería ir aparte.
2. **Filtra en cada tecla.** Cada pulsación cambiaba el criterio y disparaba un `shapeToUiState`
   completo. Con pocas tareas no se nota, pero es justo el patrón que `debounce` existe para evitar.

---

## 3. `debounce` + `distinctUntilChanged`: filtrar cuando dejas de teclear

La consulta de texto ahora vive en su **propio** `StateFlow`, separado de los criterios de orden/agrupación
([TasksViewModel.kt](../app/src/main/java/com/neverlate/ui/tasks/TasksViewModel.kt)):

```kotlin
private val _query = MutableStateFlow("")
val query: StateFlow<String> = _query.asStateFlow()   // el campo lo lee: reacciona al instante
```

¿Por qué separarlo de los criterios? Porque queremos **debouncear solo el texto**. Tocar un chip de orden
debe reordenar la lista de inmediato; escribir en el buscador, en cambio, debe esperar a que hagas una
pausa. Dos `StateFlow` distintos nos dejan aplicar un ritmo distinto a cada uno:

```kotlin
private val debouncedQuery: Flow<String> = _query
    .debounce(300)              // re-emite solo tras 300 ms sin cambios
    .distinctUntilChanged()     // ignora una re-emisión igual a la anterior
```

- **`debounce(300)`** arranca un temporizador interno cada vez que llega un valor nuevo. Si antes de esos
  300 ms llega **otro** valor, el temporizador anterior se **cancela** y se reinicia. Solo cuando pasan
  300 ms *sin cambios* deja pasar el último valor. Escribir "presentacion" letra a letra produce **una
  sola** emisión aguas abajo, no once. (Esa cancelación del temporizador pendiente es concurrencia
  estructurada otra vez, ahora dentro de un operador: el "espera 300 ms" viejo se abandona en cuanto
  aparece una tecla más nueva, así que **siempre gana el último texto**.)
- **`distinctUntilChanged()`** descarta un valor idéntico al que ya salió. Si escribes una letra y la
  borras dentro de la ventana de *debounce*, vuelves al mismo texto: no tiene sentido re-filtrar, y este
  operador lo evita.

> **Nota:** `debounce` es aún API marcada `@FlowPreview`, por eso el `ViewModel` lleva
> `@OptIn(FlowPreview::class)`. Es estable en la práctica y de uso muy extendido.

---

## 4. `combine`: la lista visible como función de tres entradas

`combine` teje varios `Flow` en uno solo que **re-emite cada vez que *cualquiera* de sus fuentes cambia**,
emparejando siempre el valor *más reciente* de cada una:

```kotlin
val uiState: StateFlow<TasksUiState> =
    combine(uiTasksFlow, debouncedQuery, _criteria) { uiTasks, settledQuery, criteria ->
        shapeToUiState(uiTasks, settledQuery, criteria)   // reutiliza shapedBy(...) de la 03b
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TasksUiState.Loading)
```

Se lee casi como una frase: *"la lista visible es `shapeToUiState` aplicado al último tic de tareas, la
última consulta ya asentada y los últimos criterios"*. Si Room emite una tarea nueva → re-emite. Si el
*debounce* asienta una consulta → re-emite. Si tocas un chip de orden → re-emite. Nunca mezcla una versión
vieja de una fuente con una nueva de otra: `combine` guarda el último valor de cada una.

Fíjate en que `shapeToUiState` es **pura** (mismas entradas → misma salida, sin efectos) y **reutiliza la
lógica de la 03b sin tocarla** (`shapedBy` filtra/ordena/agrupa; `TaskListShaping.kt` no cambia). El
buscador **no abre una segunda consulta a Room**: `uiTasksFlow` sigue siendo el mismo stream de la 04
(Room → `flatMapLatest` al `countdownTicker` → `map` a `TaskUiModel`). Añadimos operadores **encima** del
flujo existente; extender, no duplicar.

---

## 5. `stateIn`: de `Flow` frío a `StateFlow` caliente

El `Flow` que devuelve `combine` es **frío**: como cualquier `Flow`, no hace *nada* hasta que alguien lo
colecta, y si dos observadores lo coleccionaran, cada uno arrancaría su propia copia del cálculo desde
cero. Eso no sirve para el estado de una pantalla, que necesita (a) tener **siempre** un valor actual que
leer, y (b) que ese cálculo se haga **una sola vez** y se comparta. Ese es el trabajo de **`stateIn`**,
que toma tres argumentos:

- **`viewModelScope`** — el scope donde vive el trabajo compartido. Aquí vuelve a entrar la concurrencia
  estructurada: al destruirse el `ViewModel`, este `StateFlow` deja de trabajar con su scope.
- **`SharingStarted.WhileSubscribed(5_000)`** — la **política de compartición**: *cuándo* corre el
  cálculo. `WhileSubscribed` lo mantiene vivo solo mientras hay al menos un colector (la pantalla, vía
  `collectAsStateWithLifecycle`), y lo detiene 5 s después de que se vaya el último. Esos 5 s son
  suficientes para sobrevivir al hueco brevísimo de un cambio de configuración (rotar la pantalla) sin
  reiniciar todo el pipeline, y cortos para apagar el `combine`/ticker poco después de salir de verdad.
  Las alternativas: **`Eagerly`** correría siempre, aunque nadie mire (gasta batería con el ticker);
  **`Lazily`** arrancaría al primer colector y **no pararía nunca**.
- **`TasksUiState.Loading`** — el **valor inicial**. La garantía de `StateFlow` ("siempre hay un valor
  actual") necesita una semilla para el instante anterior a la primera emisión de `combine`.

### `StateFlow` vs `SharedFlow`

- Un **`StateFlow`** es un `SharedFlow` especializado: tiene **siempre un valor actual** (`.value`),
  *conflación* (si te suscribes tarde ves el último valor, no el historial) y `distinctUntilChanged`
  incorporado. Es un **estado**: "cómo están las cosas *ahora*".
- Un **`SharedFlow`** es un stream de **eventos** sin valor actual obligatorio ni inicial: sirve para
  "ha pasado algo una vez" (un *snackbar*, navegar a otra pantalla) donde no tiene sentido un "valor
  actual" ni reproducir el último a quien llega tarde.

El estado del buscador —"esta es la lista que se ve"— es claramente lo primero: siempre hay una lista
actual, y quien se suscriba debe verla de inmediato. Por eso `stateIn` (que produce un `StateFlow`) es la
herramienta correcta, no un `SharedFlow`.

---

## 6. El efecto secundario, en su propio colector

Recuerda que el `collect` viejo mezclaba estado y efecto. Al pasar el estado a `stateIn` (puro,
declarativo), el efecto de **auto-pausar** una tarea que llega a cero se muda a su **propio** colector:

```kotlin
init {
    // Efecto separado de la derivación de uiState: uiState responde "qué mostrar";
    // esto responde "qué hacer como consecuencia". Mezclarlos volvería impuro uiState.
    uiTasksFlow.onEach { uiTasks -> autoPauseTimedOut(uiTasks) }.launchIn(viewModelScope)
    // ...
}
```

`onEach { ... }.launchIn(viewModelScope)` es la forma idiomática de **enganchar un efecto a un `Flow`
existente** sin volver a coleccionarlo a mano. Es un punto de diseño clave de la lección: **estado ≠
efectos**. `uiState` calcula qué se ve; este colector ejecuta consecuencias. Manteniéndolos separados,
`uiState` sigue siendo una función pura fácil de testear por sí sola.

---

## 7. El botón "limpiar" (✕) y el campo

El cambio de UI es diminuto ([TasksScreen.kt](../app/src/main/java/com/neverlate/ui/tasks/TasksScreen.kt)):
el `value` del `OutlinedTextField` sale ahora del `query` inmediato (no del *debounced*), así que el texto
aparece al instante mientras escribes; solo el *filtrado* espera. Y añadimos un `trailingIcon` para vaciar:

```kotlin
trailingIcon = {
    if (query.isNotEmpty()) {          // sin texto, sin icono
        IconButton(onClick = { onQueryChange("") }) {
            Icon(
                imageVector = Icons.Filled.Close,
                contentDescription = stringResource(R.string.tasks_filter_clear_content_description),
            )
        }
    }
}
```

Detalles que vienen de lecciones anteriores: el icono de lupa (`leadingIcon`) es decorativo
(`contentDescription = null`, porque el `label` ya describe el campo — accesibilidad, feature 18), pero
el botón "limpiar" **sí** anuncia su acción con un texto localizado (i18n, feature 08: la cadena
`tasks_filter_clear_content_description` está en `values/strings.xml` y `values-en/strings.xml`). Limpiar
pasa por el **mismo** `onQueryChange("")` que ya usa el campo y la acción "limpiar filtro" del estado
`NoResults`: un único camino para cambiar la consulta.

---

## 8. `async`/`await`: el paralelismo que *no* usamos aquí (mención)

`combine` es esencialmente **secuencial-reactivo**: reacciona a valores según llegan. Cuando lo que
quieres es lanzar **varias tareas a la vez** y esperar a todas, la herramienta es `async`/`await`:

```kotlin
// Ilustrativo — NO está en el código de producción de esta feature.
val resultado = viewModelScope.launch {
    val a = async { cargarDeRed() }      // arranca ya, en paralelo
    val b = async { cargarDeDisco() }    // arranca ya, en paralelo
    combinar(a.await(), b.await())       // espera a ambos
}
```

`async` devuelve un `Deferred<T>` (una promesa de valor) y arranca el trabajo de inmediato; `await()`
suspende hasta que ese valor está listo. Y sigue siendo concurrencia estructurada: si el `launch` padre se
cancela, los dos `async` hijos se cancelan con él. El buscador no necesita paralelismo —su trabajo es
filtrar en memoria una lista ya emitida— pero es útil saber dónde encaja `async`/`await` frente al
`combine` reactivo de esta pantalla.

---

## 9. Tests: tiempo virtual con `runTest` y `TestDispatcher`

Probar `debounce` con tiempo real sería lento y *flaky* (`Thread.sleep(300)` en cada test). En su lugar,
`kotlinx-coroutines-test` nos da **tiempo virtual**: `runTest` corre sobre un `TestScheduler` cuyo reloj
**avanzamos a mano** con `advanceTimeBy(...)` / `advanceUntilIdle()`, de forma **determinista** e
instantánea. Es el mismo harness que ya usaba `TasksViewModelTest` (con `StandardTestDispatcher` +
`Dispatchers.setMain`).

Hay una sutileza que la propia lección enseña: como `uiState` usa `WhileSubscribed`, **el pipeline no hace
nada sin un colector**. Todo test que lea `uiState.value` debe primero *lanzar* un colector, o el
`combine`/`debounce` se queda inerte y el estado nunca sale de `Loading`:

```kotlin
// Enciende el pipeline: WhileSubscribed necesita al menos un suscriptor.
backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect {} }
```

Los casos que cubre [`TasksViewModelTest`](../app/src/test/java/com/neverlate/ui/tasks/TasksViewModelTest.kt):

- **Debounce:** varias `onQueryChange` seguidas no filtran hasta avanzar el tiempo virtual; tras
  `advanceTimeBy(300)` hay **un solo** resultado asentado.
- **`combine`:** cambiar la lista de tareas **o** la consulta re-emite el estado.
- **`NoResults` vs `Empty`:** con tareas y una consulta sin coincidencias → `NoResults`; con lista vacía →
  `Empty`.
- **`distinctUntilChanged`:** volver al mismo texto no produce una segunda emisión.
- **Cancelación / último-gana:** teclear rápido A → AB → A y avanzar el tiempo termina en el resultado de
  **A**, no en uno intermedio.
- **Inercia de `WhileSubscribed`:** sin colector, `uiState` se queda en su semilla `Loading`.

```bash
./gradlew :app:testDebugUnitTest
```

---

## Resumen

- **Concurrencia estructurada:** todo el trabajo vive en `viewModelScope` y se cancela con él; ninguna
  corrutina queda huérfana. El dispatcher es *dónde* corre; el scope, *cuánto*.
- **`debounce` + `distinctUntilChanged`** convierten una ráfaga de teclas en una única consulta asentada,
  cancelando el trabajo pendiente cuando llega una tecla más nueva.
- **`combine`** expresa la lista visible como función declarativa de tres entradas (tareas, consulta,
  criterios), siempre con el último valor de cada una.
- **`stateIn`** convierte ese `Flow` frío en un `StateFlow` caliente, con valor inicial y
  `WhileSubscribed` como política de compartición; y **`StateFlow` (estado) vs `SharedFlow` (eventos)**
  explica por qué la UI vive en el primero.
- **Estado ≠ efectos:** `uiState` es puro; el auto-pause se mudó a su propio `onEach`/`launchIn`.
- Todo se prueba con **tiempo virtual** (`runTest` + `advanceTimeBy`), lanzando un colector para
  despertar a `WhileSubscribed`.

Extendimos la 03b (mismo campo, misma lógica pura) y la 04 (mismo `Flow` de Room) sin duplicar nada: solo
cambió la **plomería reactiva** que las une.

## Documentación oficial

- [Kotlin Flow](https://kotlinlang.org/docs/flow.html) · [StateFlow y SharedFlow](https://developer.android.com/kotlin/flow/stateflow-and-sharedflow)
- [Operadores intermedios de Flow](https://kotlinlang.org/docs/flow.html#intermediate-flow-operators) ·
  [`debounce`](https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines.flow/debounce.html) ·
  [`combine`](https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines.flow/combine.html) ·
  [`stateIn`](https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines.flow/state-in.html)
- [Concurrencia estructurada](https://kotlinlang.org/docs/coroutines-basics.html#structured-concurrency) ·
  [Probar corrutinas](https://developer.android.com/kotlin/coroutines/test)

## Siguiente paso

La lección [04c — Testing (JVM + Compose UI)](04c-testing-estadisticas.md) profundiza en las pruebas que
aquí usamos de pasada, y la [05 — Widget de pantalla de inicio](05-widget.md) lleva las tareas fuera de la
app.
