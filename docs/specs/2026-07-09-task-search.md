# Feature 04b (extra) — Buscador de tareas (corrutinas y `Flow` a fondo)

- **Fecha:** 2026-07-09
- **Lección tutorial:** `tutorial/04b-buscador-tareas.md` (Español) — hueco reservado 🚧 en `tutorial/README.md`
- **Área del backlog:** `docs/conceptos-pendientes.md` §2 (Corrutinas y `Flow` a fondo) → lección **04b**
- **Rama prevista:** `feature/task-search`
- **Estado:** ✅ aprobado (2026-07-09)

### Decisiones aprobadas (2026-07-09)

1. **Estado de la consulta:** `query` sale de `TaskListCriteria` a su **propio `StateFlow`** (opción (a));
   el `debounce` aplica **solo** al texto, orden/agrupación siguen inmediatos.
2. **Debounce:** **~300 ms**.
3. **Botón "limpiar" (✕):** **SÍ** se incluye como `trailingIcon` del campo (visible solo con texto), con su
   `contentDescription` localizado (ES base + EN). Esto **anula** la recomendación "no incluirlo" de las
   secciones *Visual & UX Design* / *Out of Scope* más abajo: el botón **entra** en el alcance de esta feature.

---

## Overview

Esta es una **feature-lección de corrutinas y `Flow`**, no una feature de infraestructura. Su objetivo
pedagógico es **ponerle nombre a la maquinaria reactiva que la app ya usa sin explicar del todo** y llevarla
un paso más allá: concurrencia estructurada (`CoroutineScope`, `viewModelScope`, dispatchers, cancelación) y
los **operadores de `Flow`** que convierten una cadena de eventos de teclado en una lista filtrada en
caliente: `debounce`, `combine`, `map`, `distinctUntilChanged` y `stateIn` (cold `Flow` → hot `StateFlow`
con valor inicial), más `StateFlow` vs `SharedFlow`.

Funcionalmente, la feature toma el **campo de búsqueda que la 03b ya dejó** en la pantalla de Tareas y
**reescribe su cableado interno** para que sea un buscador **reactivo y cancelable**: el texto se refleja al
instante en el campo, pero el **filtrado efectivo espera** a que el usuario deje de teclear (`debounce`) y se
**combina** (`combine`) con la `Flow<List<Task>>` de Room que ya alimenta la lista, exponiendo el resultado
como estado de UI vía `stateIn`. No se añade una segunda fuente de datos: se **añade un operador sobre el
stream existente**, no un `Flow` nuevo hacia Room.

Es el complemento directo de sus lecciones vecinas: la **04** introdujo corrutinas, `Flow`, Room y
`viewModelScope` de forma pragmática; la **03b** (que se lee justo antes de la 04) añadió el filtro/orden en
memoria como vehículo de **fundamentos de Kotlin**, pero con un filtrado **síncrono** y explícitamente dejó
el buscador reactivo "diferido a la 04b" (ver su spec, sección *Polish diferido*). Esta **04b** cobra esa
deuda: mismo campo, misma lógica de filtrado puro, pero ahora impulsado por una **cadena de operadores de
`Flow`** que es el tema central de la lección.

### Objetivo pedagógico (conceptos nuevos que enseña la lección 04b)

La lección `tutorial/04b-buscador-tareas.md` debe explicar, usando el código de esta feature:

1. **Concurrencia estructurada:** qué es un `CoroutineScope`, por qué `viewModelScope` ata el trabajo al
   ciclo de vida del `ViewModel`, qué es un dispatcher, y **por qué el trabajo se cancela con su scope**
   (al destruirse el `ViewModel` no queda ninguna corrutina huérfana).
2. **Operadores de `Flow`:** `map`, `combine` (re-emite cuando **cualquiera** de las fuentes cambia),
   `debounce` (espera una pausa de escritura), `distinctUntilChanged` (ignora emisiones repetidas), y
   **`stateIn`** — el operador que convierte un `Flow` **frío** (que solo trabaja mientras alguien lo
   colecta) en un `StateFlow` **caliente** con **valor inicial** y una **política de compartición**
   (`SharingStarted.WhileSubscribed`).
3. **`StateFlow` vs `SharedFlow`:** qué distingue a cada uno (valor actual + *conflation* + distinct vs.
   stream de eventos sin estado) y **por qué el estado de una búsqueda encaja en `StateFlow`**, no en
   `SharedFlow`.
4. **Cancelación y `async`/`await` (mención breve):** cómo una nueva búsqueda cancela el filtrado anterior en
   vuelo, y un apunte sobre `async`/`await` para trabajo en **paralelo** (contrastando con el
   `combine`/secuencial de esta pantalla) — sin introducirlo en el código de producción.

---

## Relación con la feature 03b (reconciliación — leer antes que las User Stories)

La 03b **ya entregó** un `OutlinedTextField` de búsqueda en la pantalla de Tareas (`TaskListControls` en
`TasksScreen.kt`), con filtrado por subcadena *case-insensitive* sobre el título, y un estado `NoResults`
(vía `MessageState` + `Icons.Filled.SearchOff`). Esta feature **no duplica** eso: lo **reescribe por dentro**.

| Aspecto | Feature 03b (ya en `master`) | Feature 04b (esta) |
|---|---|---|
| Tema didáctico | Fundamentos de **Kotlin** (null-safety, `when`, colecciones, alcance, extensiones) | Corrutinas y **`Flow`** a fondo (`debounce`, `combine`, `stateIn`, cancelación) |
| Filtrado | **Síncrono**, en cada tecla, dentro de un `combine` + `collect` imperativo | **Debounced** y **cancelable**, cadena declarativa de operadores |
| `uiState` | `MutableStateFlow` asignado imperativamente dentro de un `collect` | `StateFlow` derivado con **`stateIn`** (valor inicial `Loading`) |
| Lógica de *shaping* | `TaskListShaping.kt` (puro) | **Reutilizada tal cual** (extender, no duplicar) |
| Estado "sin resultados" | `MessageState` + `NoResults` | **Reutilizado tal cual** |

**Cambio observable para el usuario:** hoy (03b) el filtro se recalcula en **cada pulsación**; tras la 04b el
texto sigue apareciendo al instante en el campo, pero el **filtrado espera ~300 ms** a que el usuario deje de
teclear. Es un cambio de *timing*, no de aspecto: la UI visible es prácticamente idéntica (ver *Visual & UX
Design*). Todo el peso de la feature es **pedagógico y de plomería reactiva**.

---

## User Stories

> Las historias US-1..US-3 describen el **comportamiento nuevo** de la 04b. El filtrado por subcadena, el
> orden, la agrupación y el estado "sin resultados" **se preservan sin regresión** desde la 03b (ver
> *Acceptance Criteria* §7 y *Visual & UX Design*).

### US-1 — Buscar con escritura fluida (debounce)
**Como** persona con muchas tareas, **quiero** escribir en el buscador y ver el texto aparecer al instante
mientras el filtrado espera a que termine de teclear, **para** no notar tirones ni un refiltrado por cada
tecla.

**Criterios de aceptación:**
- El **texto del campo** se actualiza **inmediatamente** en cada pulsación (el campo nunca "va lento").
- El **filtrado efectivo** se aplica **tras una pausa** de escritura (`debounce`, ~300 ms), no en cada tecla.
- Teclear "presentacion" letra a letra produce **un único** filtrado estabilizado, no uno por letra.
- Vaciar el campo restaura "todas las tareas" tras el mismo *debounce*.
- Dos consultas consecutivas **idénticas** (p. ej. escribir y borrar una letra rápido volviendo al mismo
  texto) **no** vuelven a disparar el pipeline (`distinctUntilChanged`).

### US-2 — Buscar sin fugar trabajo (cancelación / concurrencia estructurada)
**Como** usuario, **quiero** que al cambiar la búsqueda el filtrado anterior deje de importar, **para** que
en pantalla solo llegue el resultado de lo último que escribí y no se acumule trabajo en segundo plano.

**Criterios de aceptación:**
- Una **nueva** consulta hace que la anterior en vuelo deje de emitir (el *debounce* descarta la pendiente;
  el operador de conmutación cancela el trabajo previo): **gana siempre el último texto tecleado**.
- Todo el trabajo del buscador vive en **`viewModelScope`**: al destruirse el `ViewModel` (p. ej. salir de la
  pantalla) el pipeline se **cancela** con su scope — no queda ninguna corrutina huérfana.
- No hay condiciones de carrera: no puede quedar en pantalla el resultado de una consulta **vieja** por
  encima del de una **más nueva**.

### US-3 — Estado de UI reactivo (cold `Flow` → hot `StateFlow`)
**Como** desarrollador que lee este código como material didáctico, **quiero** que el estado de la pantalla se
**derive declarativamente** de combinar la lista de Room con la consulta, expuesto como `StateFlow` con valor
inicial, **para** que la UI tenga **siempre** un estado válido y ninguna emisión se pierda antes de que la
pantalla empiece a observar.

**Criterios de aceptación:**
- `uiState` es un `StateFlow<TasksUiState>` con **valor inicial** `Loading`, construido con **`stateIn`**
  (no asignado imperativamente dentro de un `collect`).
- La `Flow<List<Task>>` **existente** de Room (`TaskRepository.observeTasks()`, ya con su `countdownTicker`)
  se **combina** (`combine`) con la consulta *debounced* y con los criterios de orden/agrupación de la 03b.
- La política de compartición es **`SharingStarted.WhileSubscribed(5_000)`** (el flujo trabaja mientras hay
  observadores y se suspende ~5 s después de que el último se vaya) — la lección explica por qué, frente a
  `Eagerly`/`Lazily`.
- El efecto secundario existente de **auto-pausar** una tarea cuyo temporizador llega a cero (hoy dentro del
  `collect` imperativo) se **preserva**, movido a su **propio colector** (`onEach`), separado de la derivación
  pura del estado.

---

## Acceptance Criteria (comportamiento, resumen verificable)

1. La consulta de texto se expone como un `StateFlow<String>` que el campo lee y actualiza; el campo refleja
   cada pulsación al instante.
2. El **filtrado** pasa por `debounce` (~300 ms) + `distinctUntilChanged` antes de aplicarse; no se refiltra
   en cada tecla.
3. `uiState` se construye con `combine(observeTasks-pipeline, queryDebounced, criteria)` → `map` a
   `TasksUiState` → **`stateIn(viewModelScope, WhileSubscribed(5_000), Loading)`**.
4. La lógica pura de *shaping* (`TaskListShaping.kt`: `filteredBy`/`sortedBy`/`groupedByUrgency`/`shapedBy`)
   se **reutiliza sin cambios de comportamiento**; el buscador solo cambia **cómo llega** la consulta a ella.
5. Ninguna interacción de búsqueda dispara una **nueva consulta a Room** ni red: se opera sobre la lista ya
   emitida por `observeTasks()` (un operador **sobre** el stream existente, no un segundo stream).
6. Una nueva consulta cancela el trabajo anterior en vuelo; todo el pipeline se cancela al destruirse el
   `ViewModel` (concurrencia estructurada vía `viewModelScope`).
7. **Sin regresión de la 03b:** filtrado por subcadena *case-insensitive*; orden {plazo, título} × {asc, desc}
   con `deadline == null` al final; agrupación por urgencia (toggle); estados `Loading`/`Empty`/`NoResults`/
   `Content` con `when` exhaustivo. El auto-pause al llegar a cero sigue funcionando.
8. **Tests JVM con `runTest` + `TestDispatcher`** (tiempo virtual) cubren: el *debounce* (avanzar el tiempo
   virtual dispara un único filtrado), el `combine` (cambiar lista **o** consulta re-emite), `NoResults`,
   `distinctUntilChanged` y la cancelación (la consulta vieja no gana). Ver *Technical Approach* §5.
9. La lección `tutorial/04b-buscador-tareas.md` está escrita (Español) y explica los 4 bloques de conceptos
   referenciando el código real; se marca 04b como ✅ en `docs/conceptos-pendientes.md` y `tutorial/README.md`.

---

## Visual & UX Design

### Slice del mockup maestro

El north star [`docs/mockups/rediseno-ux-ui.html`](../mockups/rediseno-ux-ui.html) **no incluye una pantalla
de búsqueda** (verificado: no hay ningún *search bar*/campo de filtro en el maquetado), igual que se dejó
constancia para la 03b en [`docs/mockups/README.md`](../mockups/README.md). Esta feature **no reclama ninguna
fila del backlog visual**: el campo ya existe desde la 03b y aquí se **reescribe su comportamiento**, no su
aspecto. **No se añade cromo visible nuevo.**

La regla "extender, no duplicar" aplica también a la UI: se **reutilizan** el `OutlinedTextField` y su icono
de la 03b, y el `MessageState` compartido para "sin resultados" — no se introduce estilo nuevo ni un
`SearchBar` de Material 3.

### Criterios de aceptación visual

- **Sin regresión visual de la 03b:** el campo de búsqueda, los `FilterChip`s de orden y el toggle de
  dirección se ven y colocan **igual** que antes de esta feature.
- El campo y sus controles conservan **área de toque ≥ 48dp** (los chips ya usan
  `Modifier.minimumInteractiveComponentSize()`; no romperlo).
- La fila de controles sigue **reflowing** correctamente a la **mayor escala de fuente** del sistema (el
  `FlowRow` de la 03b se mantiene; los chips envuelven a la siguiente línea, no se recortan).
- El icono de búsqueda sigue siendo **decorativo** (`contentDescription = null`) porque el `label` del campo
  ya lo describe; el toggle de dirección sigue **anunciando su estado** (ascendente/descendente). Cualquier
  `contentDescription` nuevo (p. ej. un botón "limpiar" opcional, ver abajo) debe ser coherente y localizado.
- El *debounce* **no** debe percibirse como "la app se ha colgado": el texto aparece al instante (US-1); solo
  el filtrado espera. **No** se añade *spinner* de carga durante el *debounce* (la lista simplemente se
  actualiza cuando el filtro resuelve).
- Los colores de selección siguen saliendo de **roles del tema** (`MaterialTheme.colorScheme` /
  `NeverLateExtras`); claro/oscuro/Material You siguen funcionando sin trabajo extra.
- La reescritura del pipeline **no** rompe las animaciones de fila (`Modifier.animateItem`) ni la barra de
  progreso por tarjeta (feature 19).

### Polish diferido explícito

- **Botón "limpiar" (icono ✕) como `trailingIcon` del campo:** *opcional*; el estado `NoResults` de la 03b ya
  ofrece "limpiar filtro". Si se incluye, es un cambio mínimo con su `contentDescription` localizado; si no,
  queda como posible fila futura. **Recomendación: no incluirlo** para mantener el foco de la lección en `Flow`.
- **`SearchBar` de Material 3 con historial/sugerencias:** fuera de alcance (sería una feature de diseño
  aparte).
- **Indicador de "buscando…"** durante el *debounce*: fuera de alcance; la búsqueda en memoria es instantánea
  una vez resuelto el *debounce*.
- **Buscar también en Artículos:** fuera de alcance; el patrón podría reutilizarse más adelante.

---

## Technical Approach

Todo el trabajo es de **cliente Android**, concentrado en `ui/tasks/`. Sin backend, sin contrato, sin cambio
de versión de Room, sin permisos, sin dependencias nuevas. La lógica pura de la 03b (`domain/tasks/TaskListShaping.kt`)
se **reutiliza sin cambios de comportamiento**.

### 1. ViewModel (reescribir el cableado reactivo) — `ui/tasks/TasksViewModel.kt`

Punto de partida (hoy): `_criteria` (query + orden + agrupación) es un `MutableStateFlow`; en el `init` se
hace `uiTasksFlow.combine(_criteria) { ... }.collect { onTasksTick(...) }`, y `onTasksTick` **asigna
imperativamente** `_uiState` (un `MutableStateFlow`) y, de paso, auto-pausa las tareas que llegan a cero.

Reescritura propuesta (el reviewer/`mobile-engineer` afina la forma exacta; el **qué** es lo firmado):

- **Separar la consulta de texto** del resto de criterios, para que el *debounce* aplique **solo** al texto y
  el orden/agrupación sigan siendo **inmediatos**:
  - `private val _query = MutableStateFlow("")` → `val query: StateFlow<String>` (lo que **lee el campo**,
    responde al instante).
  - `private val _criteria = MutableStateFlow(TaskListCriteria())` para orden/agrupación (inmediato).
    (Se puede sacar `query` de `TaskListCriteria` o mantenerlo y derivar; ver *nota* abajo.)
- **Cadena de la consulta debounced** (el corazón de la lección):
  ```
  val debouncedQuery = _query
      .debounce(300)              // espera una pausa de escritura
      .distinctUntilChanged()     // ignora repeticiones
  ```
- **Derivar `uiState` con `combine` + `stateIn`** (cold → hot), reemplazando el `collect` imperativo:
  ```
  val uiState: StateFlow<TasksUiState> =
      combine(uiTasksFlow, debouncedQuery, _criteria) { uiTasks, query, criteria ->
          shapeToUiState(uiTasks, query, criteria)   // reusa shapedBy(...) de 03b → Empty/NoResults/Content
      }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TasksUiState.Loading)
  ```
  donde `uiTasksFlow` es el **mismo** `observeTasks().flatMapLatest { ticker }.map { toUiModels() }` de hoy
  (no se toca la fuente de Room ni el `countdownTicker`).
- **Mover el efecto secundario** (auto-pausar una tarea cuyo temporizador llega a 0) a su **propio colector**,
  separado de la derivación pura del estado — un punto didáctico clave (estado ≠ efectos):
  ```
  init { uiTasksFlow.onEach { autoPauseTimedOut(it) }.launchIn(viewModelScope) }
  ```
- **Intención del usuario:** `onQueryChange(q)` → `_query.value = q` (inmediato); `onSortFieldChange` /
  `onToggleSortDirection` / `onToggleGrouping` → `.copy(...)` sobre `_criteria`, como hoy.

> **Nota sobre `TaskListCriteria.query`:** hoy `query` vive dentro de `TaskListCriteria`. Dos opciones válidas:
> (a) sacar `query` a su propio `StateFlow` y que `TaskListCriteria` quede con orden/agrupación; (b) mantener
> `query` en `TaskListCriteria` y derivar `debouncedQuery = _criteria.map { it.query }.debounce(...).distinctUntilChanged()`.
> La (a) es más **clara pedagógicamente** (el *debounce* aplica a una entrada única y evidente) y es la
> recomendada; la (b) toca menos la firma. Se decide en implementación; la lección debe justificar la elegida.

### 2. UI (cambio mínimo) — `ui/tasks/TasksScreen.kt`

- El `OutlinedTextField` de la 03b sigue igual; solo cambia **de dónde** sale su `value`: del `query`
  `StateFlow` (inmediato) en vez de `criteria.query`. `TasksRoute` colecta `query` además de `uiState`,
  `syncStatus` y `criteria`.
- Nada más cambia en la UI (mismos chips, mismo `NoResults`, mismas animaciones). Es esperable un diff de UI
  muy pequeño.

### 3. Dominio puro — `domain/tasks/TaskListShaping.kt`

- **Sin cambios de comportamiento.** Si se saca `query` de `TaskListCriteria`, `shapedBy` puede recibir el
  `query` como parámetro aparte o seguir tomándolo del criteria reconstruido; en cualquier caso `filteredBy`
  / `sortedBy` / `groupedByUrgency` quedan intactas. La lección 04b **no** re-explica esta lógica (es de la
  03b); la usa como "la parte pura que el `Flow` alimenta".

### 4. Strings — `res/values/strings.xml` (+ `values-en/`)

- Probablemente **ninguno nuevo** (el label del buscador ya existe de la 03b). **Solo** si se añade el botón
  "limpiar" opcional se añade su `contentDescription` (ES base + EN). Se respeta la i18n de la feature 08.

### 5. Tests (JVM) — `app/src/test/.../ui/tasks/TasksViewModelTest.kt` (extender)

Reutiliza el harness ya existente (`FakeTaskRepository`, `StandardTestDispatcher` + `Dispatchers.setMain`).
La clave de la lección de testing aplicada: **tiempo virtual**.

- **Debounce:** emitir varias `onQueryChange` seguidas, comprobar que **antes** de avanzar el tiempo virtual
  no hay filtrado nuevo, y que tras `advanceTimeBy(300)` / `advanceUntilIdle()` hay **un único** resultado
  filtrado. (Colectar `uiState` con Turbine **no** está disponible salvo que ya sea dependencia; usar
  `advanceUntilIdle()` + leer `uiState.value` bajo un colector activo — ver nota de dependencias.)
- **`combine`:** cambiar la lista de tareas del `FakeTaskRepository` **o** la consulta re-emite el estado.
- **`NoResults` vs `Empty`:** con tareas presentes y una consulta sin coincidencias → `NoResults`; con lista
  vacía → `Empty`.
- **`distinctUntilChanged`:** volver al mismo texto no produce un segundo filtrado.
- **Cancelación / último-gana:** teclear rápido A→AB→A y avanzar el tiempo → el estado final corresponde a la
  **última** consulta, no a una intermedia.
- **No regresión:** los tests de orden/agrupación/auto-pause existentes siguen pasando.

> **Nota sobre observar un `StateFlow` con `WhileSubscribed`:** al pasar `uiState` a `WhileSubscribed`, el
> pipeline **solo trabaja mientras hay un colector**. Los tests deben **lanzar un colector** (p. ej.
> `backgroundScope.launch { viewModel.uiState.collect {} }`) antes de avanzar el tiempo, o el `combine`/
> `debounce` no se activará. Es, además, un punto didáctico excelente para la lección (por qué el *cold* flow
> no hace nada sin suscriptores).

### 6. Lección + tracking

- `tutorial/04b-buscador-tareas.md` (rellenar el placeholder 🚧), recorriendo el código y explicando los 4
  bloques del *Objetivo pedagógico*.
- Flip de estado **04b** 🚧/⬜ → ✅ en `docs/conceptos-pendientes.md` y `tutorial/README.md`.
- Nota en `docs/mockups/README.md` (Design review): esta feature **no** reclama slice (refactor de
  comportamiento; el campo ya existía de la 03b).

### Archivos que se espera cambiar

| Archivo | Cambio |
|---|---|
| `app/src/main/java/com/neverlate/ui/tasks/TasksViewModel.kt` | **Núcleo** — `query` `StateFlow` + `debounce`/`distinctUntilChanged` + `combine` + `stateIn`; auto-pause a su propio `onEach` colector |
| `app/src/main/java/com/neverlate/ui/tasks/TasksScreen.kt` | Mínimo — el campo lee `query` (inmediato); `TasksRoute` colecta `query` |
| `app/src/main/java/com/neverlate/domain/tasks/TaskListShaping.kt` | Sin cambio de comportamiento (a lo sumo, mover `query` fuera de `TaskListCriteria`) |
| `app/src/main/res/values/strings.xml` · `values-en/strings.xml` | Solo si se añade el botón "limpiar" opcional (probablemente **ninguno**) |
| `app/src/test/.../ui/tasks/TasksViewModelTest.kt` | Extender — debounce, combine, NoResults, distinct, cancelación (tiempo virtual) |
| `tutorial/04b-buscador-tareas.md` | Rellenar la lección (Español) |
| `docs/conceptos-pendientes.md` · `tutorial/README.md` | Flip 04b 🚧/⬜ → ✅ |
| `docs/mockups/README.md` | Nota en Design review (sin slice; refactor de comportamiento) |

---

## Out of Scope

- **Filtrado en SQL / consultas a Room nuevas** (`WHERE`/`ORDER BY`, índices) — todo sigue **en memoria**
  sobre la lista ya emitida; el buscador es un **operador sobre el stream existente**, no una segunda fuente.
- **Re-enseñar la lógica de filtro/orden/agrupación** — es de la 03b; la 04b la reutiliza tal cual.
- **Persistir la consulta entre sesiones** (DataStore) — vive solo mientras la pantalla está en memoria.
- **`SearchBar` de Material 3, historial, sugerencias, resaltado de coincidencias** — no.
- **Buscar en Artículos** u otras pantallas — no (posible reutilización futura del patrón).
- **Indicador visual de "buscando…"**, *spinner* de *debounce*, o botón "limpiar" (este último, opcional y
  desaconsejado; ver *Visual & UX Design*).
- **Introducir `async`/`await` en producción** — solo se **menciona** en la lección para contrastar
  paralelo vs. `combine`.
- Cambios de **backend, contrato de API, versión de Room, permisos o dependencias** — **ninguno**.

---

## Dependencies

- **Requiere existente (verificado en código):**
  - Feature **03b**: el campo de búsqueda + `TaskListCriteria`/`TaskListShaping.kt` (puro), el estado
    `NoResults` y `MessageState` (`ui/components/`). La 04b **construye sobre** esto.
  - Feature **04**: `TaskRepository.observeTasks(): Flow<List<Task>>`, `viewModelScope`, el `countdownTicker`
    y `TaskUiModel` (`ui/tasks/TasksViewModel.kt`).
  - El harness de test ya presente: `FakeTaskRepository`, `StandardTestDispatcher` + `Dispatchers.setMain` en
    `TasksViewModelTest.kt`.
- **Prerrequisito pedagógico:** lección **04** (corrutinas, `Flow`, Room). La 04b se lee **entre** la 04 y la
  05; la 03b (que se lee antes) ya dejó el campo y la lógica pura.
- **Dependencias externas:** **ninguna nueva.** `debounce`/`combine`/`stateIn`/`distinctUntilChanged` son de
  `kotlinx-coroutines-core` (ya presente); `kotlinx-coroutines-test` (para `runTest`/`TestDispatcher`) **ya
  está** en el catálogo de versiones (`gradle/libs.versions.toml`, `kotlinxCoroutinesTest = "1.9.0"`) y se usa
  ya en `TasksViewModelTest.kt`. **Turbine no está**: si se quisiera para colectar `Flow` en tests sería una
  dependencia nueva → **evitarla**; usar `backgroundScope` + `advanceUntilIdle()` + `uiState.value`.
- **Sin** cambios en `AndroidManifest.xml`, `docs/api/contract.md`, ni en la versión de `NeverLateDatabase`.

---

## Risks

1. **`WhileSubscribed` deja de trabajar sin suscriptores** — un test que avance el tiempo sin un colector
   activo verá el `debounce`/`combine` inertes y "fallará por nada". *Mitigación:* documentarlo (es un punto
   de la lección); los tests lanzan un colector en `backgroundScope` antes de avanzar el tiempo virtual.
2. **El efecto secundario de auto-pause se pierde en la reescritura** — hoy vive dentro del `collect`
   imperativo; al pasar a `stateIn` hay que **moverlo** a su propio `onEach`, no borrarlo. *Mitigación:* un
   test de no-regresión (tarea que llega a 0 se pausa) lo fija; es un AC explícito (US-3).
3. **El `debounce` retrasa el filtrado y podría percibirse como *lag*** si el usuario no ve el texto avanzar.
   *Mitigación:* el **texto del campo** sale del `StateFlow` inmediato, **no** del debounced (US-1); solo el
   filtrado espera. Elegir ~300 ms (ni tan corto que no ahorre trabajo, ni tan largo que se note).
4. **Elegir mal `debounce` en el sitio equivocado** (debouncing también el orden/agrupación, que deben ser
   inmediatos). *Mitigación:* separar la consulta de texto de `TaskListCriteria` (opción (a) recomendada), de
   modo que el `debounce` aplique **solo** al texto.
5. **Ámbito percibido como "otra vez lo mismo que 03b"** — la feature toca el mismo campo. *Mitigación:* la
   sección *Relación con 03b* deja claro que es un **cambio de plomería reactiva** con un cambio observable
   real (debounce) y un objetivo didáctico distinto (corrutinas/`Flow`); el diff de dominio/UI es pequeño **a
   propósito**, el diff conceptual es grande.
6. **Test de `debounce`/cancelación *flaky*** si se usa tiempo real en vez de virtual. *Mitigación:*
   `StandardTestDispatcher` + `advanceTimeBy`/`advanceUntilIdle` (tiempo virtual, determinista); nunca
   `Thread.sleep` ni tiempo de pared.

---

## Deliverable de tutorial

- **`tutorial/04b-buscador-tareas.md`** (Español), rellenando el placeholder 🚧. Debe recorrer el código
  escrito y explicar los 4 bloques del *Objetivo pedagógico* (concurrencia estructurada; operadores de `Flow`
  incl. `stateIn`; `StateFlow` vs `SharedFlow`; cancelación + mención de `async`/`await`), reutilizando lo de
  la 04 y la 03b sin re-explicarlo. Al terminar, marcar la fila **04b** como ✅ en
  `docs/conceptos-pendientes.md` y `tutorial/README.md` (regla de no-renumerar: la lección **es** la 04b,
  entre la 04 y la 05).

---

> **Aprobación requerida.** Este spec cubre **comportamiento y aspecto visual** (la sección *Visual & UX
> Design* forma parte de lo que se firma). Por favor revísalo y **apruébalo explícitamente** antes de crear la
> rama `feature/task-search` e implementar. Puntos abiertos donde tu preferencia cambia el plan: **(1)** sacar
> `query` de `TaskListCriteria` (opción (a), recomendada) vs. derivar el debounce del criteria (opción (b));
> **(2)** valor del `debounce` (~300 ms propuesto); **(3)** incluir o no el botón "limpiar" opcional
> (desaconsejado). Dilo y ajusto el spec antes de empezar.
