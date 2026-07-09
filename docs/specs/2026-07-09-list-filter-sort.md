# Feature 03b (extra) — Filtro y ordenación de la lista en memoria (fundamentos de Kotlin)

- **Fecha:** 2026-07-09
- **Lección tutorial:** `tutorial/03b-filtro-orden-memoria.md` (Español) — hueco reservado 🚧 en `tutorial/README.md`
- **Área del backlog:** `docs/conceptos-pendientes.md` §1 (Kotlin — fundamentos del lenguaje) → lección **03b**
- **Rama prevista:** `feature/list-filter-sort`
- **Estado:** borrador para aprobación

---

## Overview

Esta es una **feature-lección de fundamentos de Kotlin**, no una feature de infraestructura. Su objetivo
pedagógico es **ponerle nombre al Kotlin que la app ya usa sin explicar**: null-safety, `when` como
expresión exhaustiva, colecciones + funciones de orden superior, funciones de alcance y funciones de
extensión.

Funcionalmente añade a la pantalla de **Tareas** la capacidad de **filtrar por texto**, **ordenar** (por
plazo o por título, ascendente/descendente) y **agrupar por estado de urgencia** la lista de tareas. Todo
ocurre **en memoria**, sobre la lista **ya cargada** por el ViewModel existente: **no hay** nuevas consultas
a Room, **no hay** red, **no hay** una segunda fuente de datos. La lógica de transformación vive en
**funciones puras** en `domain/tasks/`, testeables en la JVM igual que `ReminderPlanning.kt`,
`urgencyLevelFor` o `deadlineProgressFor`.

Es el complemento didáctico de las lecciones vecinas: la **03** ya introdujo `data class` y `sealed`; esta
**03b** los explota para enseñar el lenguaje base **antes** de que la **04b** profundice en corrutinas/`Flow`
(`debounce`, `combine`, `stateIn`) sobre una idea de buscador parecida pero reactiva-a-Room. Aquí,
deliberadamente, **no** tocamos Room ni `Flow` avanzado: el filtrado es síncrono y en memoria para que el
foco quede en el **lenguaje**.

### Objetivo pedagógico (conceptos nuevos que enseña la lección 03b)

La lección `tutorial/03b-filtro-orden-memoria.md` debe explicar, usando el código de esta feature:

1. **Null-safety y smart casts:** `?` (tipos anulables), `?:` (Elvis), `?.let { }`, y **por qué evitar
   `!!`** — con vehículo natural en los campos anulables de `Task` (`deadline: Long?`,
   `estimatedDurationMillis: Long?`).
2. **`when` como expresión exhaustiva** sobre un `enum`/`sealed` (aquí `UrgencyLevel` y el `enum` de orden),
   y **desestructuración** (`val (level, tasks) = entry`, `for ((level, list) in grouped)`).
3. **Colecciones y funciones de orden superior:** `filter` / `map` / `sortedBy` / `sortedWith` / `groupBy`,
   `Comparator` (incl. `compareBy`, `nullsLast`), lambdas y **referencias a función** (`::`).
4. **Funciones de alcance:** `let` / `run` / `apply` / `also` / `with` — cuándo usar cada una.
5. **Funciones de extensión:** encapsular el filtro/orden/agrupación como extensiones legibles de
   `List<TaskUiModel>`.

---

## Decisión: pantalla vehículo

**Se elige la pantalla de Tareas (`ui/tasks/`), no la de Artículos.** Justificación:

| Criterio pedagógico | Tareas | Artículos |
|---|---|---|
| Campos **anulables** para enseñar null-safety (`?`, `?:`, `?.let`, evitar `!!`) | ✅ `deadline: Long?`, `estimatedDurationMillis: Long?` | ❌ `id`/`title`/`summary`/`body` todos no anulables |
| Un **estado/categoría** natural para `groupBy` + `when` exhaustivo | ✅ `UrgencyLevel` (`enum` **ya existente**) vía `urgencyLevelFor` puro | ❌ sin estado que agrupar |
| Una **fecha** por la que ordenar (y que obliga a `Comparator` con nulls) | ✅ `deadline` (anulable → `nullsLast`) | ❌ sin fecha |
| **Reutiliza** dominio puro existente (extender, no duplicar) | ✅ `urgencyLevelFor` / `UrgencyLevel` en `domain/tasks/` | ❌ nada que reutilizar |

Artículos es más simple pero **pedagógicamente más pobre**: no tiene campos anulables, ni fecha, ni un
estado por el que agrupar, así que no daría superficie para null-safety ni para `when` sobre un `enum`. La
pantalla de Tareas ofrece los tres conceptos "gratis" y **reutiliza** el `enum` de urgencia que la feature
17 ya dejó en `domain/tasks/`, cumpliendo la regla "extender, no duplicar" del `CLAUDE.md`.

**Riesgo asumido y su mitigación:** el `TasksViewModel` refresca la lista cada segundo mientras hay un
temporizador en marcha (el `countdownTicker`). El filtro/orden/agrupación se aplican como **función pura
sobre la lista ya emitida**, releídos cada vez que cambian la lista **o** los criterios; no se introduce
ninguna consulta ni `Flow` nuevo hacia Room (eso es, a propósito, el tema de la **04b**). Como la
agrupación por urgencia depende del tiempo restante, un cruce de umbral puede mover una tarea de sección;
es un comportamiento correcto y didáctico (ilustra que la agrupación es una **derivación** del estado, no
un dato almacenado).

### Definición concreta para Tareas

- **Filtro de texto:** subcadena, **ignorando mayúsculas/minúsculas**, sobre `task.title`. Campo vacío = sin
  filtro.
- **Orden (campo + dirección):**
  - **Por plazo** (`deadline`): más próximo primero / más lejano primero. Las tareas **sin plazo**
    (`deadline == null`) van **al final** en ambos sentidos (`nullsLast`) — el vehículo directo para
    enseñar `Comparator` con anulables.
  - **Por título:** A→Z / Z→A, **case-insensitive**.
  - Un control de **dirección** (ascendente/descendente) acompaña al campo elegido.
- **Agrupación (dimensión = estado de urgencia):** `groupBy` sobre `UrgencyLevel` (reutilizando
  `urgencyLevelFor(remainingMillis, isTimedOut)`), devolviendo un `Map<UrgencyLevel, List<TaskUiModel>>`.
  Se renderiza como **secciones con cabecera** en el orden del `enum`: **Vencidas → Urgentes → Pronto →
  Con calma**. El orden elegido (plazo/título) se aplica **dentro de cada sección**. La agrupación es un
  **toggle** (activada/desactivada); desactivada, la lista es plana y ordenada.

---

## User Stories

### US-1 — Filtrar por texto
**Como** persona con muchas tareas, **quiero** escribir en un campo de búsqueda para ver solo las tareas
cuyo título contenga ese texto, **para** encontrar rápido la que busco sin desplazarme por toda la lista.

**Criterios de aceptación:**
- Existe un campo de búsqueda visible sobre la lista de tareas.
- El filtro es por **subcadena** e **ignora mayúsculas/minúsculas** (`"pres"` encuentra "Preparar
  Presentación").
- Con el campo **vacío** se muestran **todas** las tareas (sin filtro).
- El filtrado ocurre **en memoria** sobre la lista ya cargada; no dispara consultas a Room ni red.
- Si el filtro no deja ninguna tarea, se muestra un estado **"sin resultados"** (ver US-4), distinto del
  estado "aún no hay tareas".
- Mientras hay un temporizador corriendo, el filtro se **mantiene** aplicado en cada tick de un segundo.

### US-2 — Ordenar la lista
**Como** usuario, **quiero** elegir el criterio de orden (por plazo o por título) y su dirección,
**para** ver primero lo que más me importa.

**Criterios de aceptación:**
- Puedo elegir ordenar **por plazo** o **por título**, y alternar **ascendente/descendente**.
- Por plazo, las tareas **sin plazo** aparecen **al final** en ambas direcciones.
- Por título, el orden es **case-insensitive**.
- El orden se aplica sobre la lista **ya filtrada** (US-1) y, si la agrupación está activa (US-3),
  **dentro de cada sección**.
- El criterio elegido **persiste** mientras la pantalla está viva (sobrevive a recomposiciones y al tick
  del contador); no es necesario persistirlo en disco entre sesiones (ver *Out of Scope*).

### US-3 — Agrupar por estado
**Como** usuario, **quiero** poder agrupar mis tareas por urgencia, **para** ver de un vistazo qué está
vencido o a punto de vencer frente a lo que puede esperar.

**Criterios de aceptación:**
- Un control (toggle) activa/desactiva la agrupación por **estado de urgencia**.
- Con la agrupación activa, la lista se divide en secciones con **cabecera** en el orden **Vencidas →
  Urgentes → Pronto → Con calma**; **no** se muestran cabeceras de secciones vacías.
- La agrupación **reutiliza** `urgencyLevelFor` (no se define un segundo cálculo de urgencia).
- Con la agrupación **desactivada**, la lista es plana y ordenada (US-2), como hasta ahora.
- La agrupación se combina correctamente con el filtro (US-1) y el orden (US-2).

### US-4 — Estado "sin resultados"
**Como** usuario que ha filtrado demasiado, **quiero** un mensaje claro cuando ninguna tarea coincide,
**para** entender que no es un error y que puedo ajustar el filtro.

**Criterios de aceptación:**
- Cuando hay tareas pero el filtro/criterios no dejan ninguna visible, se muestra el componente
  compartido `ui/components/MessageState` con un mensaje de "sin resultados".
- Este estado es **distinto** del estado `Empty` ("aún no tienes tareas", con acción de crear).
- El estado "sin resultados" **no** ofrece la acción "crear tarea" (o, si la ofrece, es "limpiar
  filtro"); no confunde ausencia-de-datos con ausencia-de-coincidencias.

---

## Acceptance Criteria (comportamiento, resumen verificable)

1. La lógica de filtro/orden/agrupación es **pura** y vive en `domain/tasks/` (sin imports de Android/Room),
   con **tests JVM** que la cubren.
2. Ninguna interacción de filtro/orden/agrupación provoca una nueva consulta a Room ni una llamada de red
   (se opera sobre la lista ya emitida por `TaskRepository.observeTasks()`).
3. Filtro: subcadena, case-insensitive, sobre el título; vacío = todo.
4. Orden: {plazo, título} × {asc, desc}; `deadline == null` siempre al final; título case-insensitive.
5. Agrupación: toggle; `Map<UrgencyLevel, List<TaskUiModel>>` reusando `urgencyLevelFor`; secciones no
   vacías en orden de `enum`; orden aplicado dentro de cada sección.
6. Estados: `Loading` (sin cambios), `Empty` (sin tareas), **`NoResults`** (hay tareas, 0 tras criterios),
   `Content` (plana o agrupada). El `when` que los renderiza es **exhaustivo**.
7. Los criterios sobreviven a recomposiciones y al tick de un segundo del contador.
8. La lección `tutorial/03b-filtro-orden-memoria.md` está escrita (Español) y explica los 5 bloques de
   conceptos, referenciando el código real.

---

## Visual & UX Design

### Slice del mockup maestro

El north star [`docs/mockups/rediseno-ux-ui.html`](../mockups/rediseno-ux-ui.html) **no incluye una pantalla
dedicada de búsqueda/filtro/orden** — no hay un "slice" propio que reclamar en la tabla de
[`docs/mockups/README.md`](../mockups/README.md). Por tanto esta feature **no** entrega una fila del backlog
visual; introduce controles nuevos reutilizando los **primitivos de control ya presentes** en el mockup y en
el tema real de la app:

- **Campo de búsqueda:** estilo del text-field del mockup (`.tf`: borde fino, esquinas redondeadas), traducido
  a un `OutlinedTextField` de Material 3 usando los tokens del tema (`ui/theme/`), **sin** copiar CSS.
- **Controles de orden/agrupación:** píldoras seleccionables al estilo `.chip` / `.tag` del mockup
  (`border-radius: 99px`), traducidas a `FilterChip` de Material 3 (color de selección = rol de marca del
  tema). El toggle de dirección puede ser un `IconButton` (flecha ↑/↓) o un `FilterChip`.
- **Cabeceras de sección** (agrupación activa): tipografía `MaterialTheme.typography` (p. ej. `titleSmall`
  con `onSurfaceVariant`), coherentes con el resto de la app; sin inventar un estilo nuevo.
- **Estado "sin resultados":** reutiliza `ui/components/MessageState` (mismo componente que Tareas/Artículos
  ya usan para vacío/error), con un icono adecuado (p. ej. `Icons.Filled.SearchOff`).

### Criterios de aceptación visual

- Todos los controles interactivos (campo, chips, toggle de dirección) tienen **área de toque ≥ 48dp**
  (usar `Modifier.minimumInteractiveComponentSize()` donde el default de Material 3 quede por debajo, como
  ya se hizo en la feature 18 para `MessageState`).
- La fila de controles **reflow** correctamente a la **mayor escala de fuente** del sistema (los chips
  envuelven a la siguiente línea, no se recortan ni empujan la lista fuera de pantalla). Usar unidades
  relativas / `FlowRow` si hace falta.
- El campo de búsqueda y los chips tienen **`contentDescription`/labels** coherentes (accesibilidad,
  continúa el repaso transversal de la feature 18): el icono de búsqueda es decorativo si el campo ya tiene
  label; el toggle de dirección anuncia su estado (ascendente/descendente).
- El estado "sin resultados" usa `MessageState` (no un `Text` suelto) y es **visualmente distinto** del
  estado `Empty`.
- Los colores de selección salen de **roles del tema** (`MaterialTheme.colorScheme` / `NeverLateExtras`),
  de modo que claro/oscuro/Material You funcionan sin trabajo extra.
- La agrupación **no** rompe las animaciones existentes de fila (`Modifier.animateItem`) ni la barra de
  progreso por tarjeta (feature 19).

### Polish diferido explícito

- **Buscador reactivo con `debounce`** (escritura fluida, `combine` con Room): **diferido a la lección 04b**
  (`04b-buscador-tareas`). Aquí el filtro es síncrono en memoria a propósito.
- **Persistir los criterios entre sesiones** (DataStore): fuera de alcance (ver abajo); los criterios solo
  viven mientras la pantalla está en memoria.
- **Chips de filtro por estado** (además del toggle de agrupación) y **búsqueda como `SearchBar` de Material
  3 con historial**: no en esta feature; si se desea, futura fila del backlog visual.

---

## Technical Approach

Todo el trabajo es de **cliente Android**, dentro de `ui/tasks/` y `domain/tasks/`. Sin backend, sin
contrato, sin cambio de versión de Room, sin permisos, sin dependencias nuevas.

1. **Dominio puro (nuevo archivo, testeable en JVM):** `app/src/main/java/com/neverlate/domain/tasks/TaskListShaping.kt`
   - Un `enum class TaskSortField { Deadline, Title }` y una `enum class SortDirection { Ascending, Descending }`
     (o un único `enum` con dirección aparte) + una `data class TaskListCriteria(query, sortField, direction, grouped)`.
   - **Funciones de extensión** sobre `List<TaskUiModel>`, encapsulando cada paso (para enseñar extensiones):
     - `fun List<TaskUiModel>.filteredBy(query: String): List<TaskUiModel>` — `filter` + `contains(ignoreCase = true)`.
     - `fun List<TaskUiModel>.sortedBy(field, direction): List<TaskUiModel>` — `sortedWith` + `Comparator`
       (`compareBy(nullsLast()) { it.task.deadline }` para plazo; `compareBy { it.task.title.lowercase() }`
       para título), invertido con `.reversed()`/`Comparator.reversed()` según `direction` (vehículo de
       `when` sobre el `enum`).
     - `fun List<TaskUiModel>.groupedByUrgency(): Map<UrgencyLevel, List<TaskUiModel>>` — `groupBy { urgencyLevelFor(it.remainingMillis, it.isTimedOut) }`.
   - Una función que compone el pipeline (`shape(criteria)`), usando **funciones de alcance** donde aporten
     legibilidad (`let`/`run`/`with`) — la lección comentará cuál y por qué.
   - **Null-safety** aparece de forma natural con `task.deadline` (`Long?`) y `estimatedDurationMillis`
     (`Long?`): Elvis, `?.let`, y el comentario de "por qué NO `!!`".

2. **ViewModel (extender el existente):** `app/src/main/java/com/neverlate/ui/tasks/TasksViewModel.kt`
   - Añadir un `MutableStateFlow<TaskListCriteria>` con los criterios actuales + métodos de intención
     (`onQueryChange`, `onSortChange`, `onToggleGrouping`).
   - Aplicar la **transformación pura** al construir el `uiState` (combinando criterios + lista ya emitida);
     **no** se añade una segunda fuente de datos ni consulta a Room. El `countdownTicker`/`observeTasks`
     siguen igual.
   - Extender `sealed interface TasksUiState`: `Content` pasa a poder representar lista plana **o** agrupada
     (p. ej. `Content(flat: List<TaskUiModel>)` + `Grouped(sections: Map<UrgencyLevel, List<TaskUiModel>>)`,
     o un `Content` con un campo opcional de secciones), y se añade **`NoResults`**.

3. **UI (extender `TasksScreen.kt`):** `app/src/main/java/com/neverlate/ui/tasks/TasksScreen.kt`
   - Fila de controles sobre la lista: `OutlinedTextField` de búsqueda + `FilterChip`s de orden + toggle de
     agrupación (estado **hoisted**: la pantalla es stateless, reporta intención por callbacks, como ya hace).
   - Render de secciones en el `LazyColumn` cuando la agrupación está activa (cabeceras como items; `when`
     exhaustivo sobre `UrgencyLevel` para el texto de cada cabecera).
   - `NoResults` → `MessageState`.

4. **Strings:** `res/values/strings.xml` (base, Español) + `res/values-en/strings.xml` (Inglés) — hint del
   buscador, etiquetas de orden/dirección, etiqueta de agrupar, cabeceras de sección, mensaje "sin
   resultados". (Se respeta la i18n de la feature 08: nada de texto concatenado en código.)

5. **Tests (JVM):** `app/src/test/.../domain/tasks/TaskListShapingTest.kt` — cubre filtro (case-insensitive,
   vacío), orden (ambos campos, ambas direcciones, `deadline` nulo al final), y agrupación (Map por urgencia,
   secciones esperadas). Toda la lógica es pura, así que no requiere emulador (lo idóneo para la lección 03b).

6. **Lección:** `tutorial/03b-filtro-orden-memoria.md` (rellenar el placeholder 🚧) + flip de estado a ✅ en
   `docs/conceptos-pendientes.md` y `tutorial/README.md`.

### Archivos que se espera cambiar

| Archivo | Cambio |
|---|---|
| `app/src/main/java/com/neverlate/domain/tasks/TaskListShaping.kt` | **Nuevo** — enums de criterio + extensiones puras (filtro/orden/agrupación) |
| `app/src/main/java/com/neverlate/ui/tasks/TasksViewModel.kt` | Añadir `StateFlow` de criterios + aplicar el pipeline puro; extender `TasksUiState` (`NoResults`, agrupado) |
| `app/src/main/java/com/neverlate/ui/tasks/TasksScreen.kt` | Controles de búsqueda/orden/agrupación, secciones, estado `NoResults` |
| `app/src/main/res/values/strings.xml` · `values-en/strings.xml` | Strings nuevos (ES base + EN) |
| `app/src/test/.../domain/tasks/TaskListShapingTest.kt` | **Nuevo** — tests JVM de la lógica pura |
| `tutorial/03b-filtro-orden-memoria.md` | Rellenar la lección (Español) |
| `docs/conceptos-pendientes.md` · `tutorial/README.md` | Flip 03b ⬜/🚧 → ✅ |
| `docs/mockups/README.md` | Nota en Design review (esta feature no reclama slice; controles reutilizan primitivos) |

---

## Out of Scope

- **Buscador reactivo** con `debounce` / `combine` / `stateIn` sobre `Flow` de Room → es la **lección 04b**.
- **Filtrar/ordenar en Artículos** (se elige Tareas como vehículo; Artículos podría reutilizar el patrón más
  adelante, pero no en esta feature).
- **Persistir los criterios entre sesiones** (DataStore) — solo viven en memoria mientras la pantalla está viva.
- **Nuevas consultas a Room, índices, `ORDER BY`/`WHERE` en SQL** — todo es en memoria por diseño pedagógico.
- **Filtros por rango de fechas, por duración, o multiselección** — solo texto + orden + agrupación por estado.
- **`SearchBar` de Material 3 con historial/sugerencias**, y **animaciones nuevas** de sección — se reutilizan
  las existentes.
- Cambios de **backend, contrato de API, versión de Room, permisos o dependencias** — **ninguno**.

---

## Dependencies

- **Requiere existente (verificado):** `TaskUiModel` (`ui/tasks/TasksViewModel.kt`), `Task` con `deadline`/
  `estimatedDurationMillis` anulables (`data/tasks/Task.kt`), `UrgencyLevel` + `urgencyLevelFor`
  (`domain/tasks/Urgency.kt`), `MessageState` (`ui/components/`), `TasksUiState` sealed, la separación
  Route/Screen stateless y el patrón dominio-puro-testeable (`ReminderPlanning.kt`).
- **Prerrequisito pedagógico:** lección 03 (`data class`, `sealed`) — ya publicada. La 03b se lee **entre** la
  03 y la 04.
- **Sin** dependencias externas nuevas: `filter`/`map`/`sortedWith`/`groupBy`/`Comparator` son de la stdlib de
  Kotlin; `FilterChip`/`OutlinedTextField`/`FlowRow` ya vienen con Material 3, ya en el catálogo de versiones.
- **Sin** cambios en `gradle/libs.versions.toml`, `AndroidManifest.xml`, `docs/api/contract.md` ni en la
  versión de `NeverLateDatabase`.

---

## Risks

1. **El tick de un segundo revuelve la agrupación por urgencia** (una tarea cruza un umbral y cambia de
   sección). *Mitigación:* es correcto y didáctico; documentarlo en la lección como "la sección es una
   derivación del estado, no un dato". El orden dentro de sección se mantiene estable por el `Comparator`.
2. **Re-aplicar el pipeline en cada tick** podría parecer costoso. *Mitigación:* la lista es pequeña
   (tareas de una persona) y las operaciones son O(n log n); es exactamente el tipo de trabajo en memoria que
   la lección quiere mostrar. Nada de esto toca Room.
3. **Alcance de la lección demasiado amplio** (5 bloques de conceptos). *Mitigación:* cada concepto tiene un
   vehículo de código muy concreto y pequeño; la lección los presenta en el orden filtro → orden →
   agrupación, reutilizando lo anterior.
4. **Confundir `NoResults` con `Empty`.** *Mitigación:* son ramas distintas del `sealed` con mensajes y
   (posible) acción distintos; un test de ViewModel/estado lo fija.
5. **Regresión visual con la barra de progreso/animaciones existentes** al meter secciones. *Mitigación:* la
   Design review verifica que `animateItem` y la barra por tarjeta (feature 19) siguen intactas.

---

## Deliverable de tutorial

- **`tutorial/03b-filtro-orden-memoria.md`** (Español), rellenando el placeholder 🚧. Debe recorrer el código
  escrito y explicar los 5 bloques del *Objetivo pedagógico*. Al terminar, marcar la fila **03b** como ✅ en
  `docs/conceptos-pendientes.md` y `tutorial/README.md` (regla de no-renumerar: la lección **es** la 03b,
  entre la 03 y la 04).

---

> **Aprobación requerida.** Este spec cubre **comportamiento y aspecto visual** (la sección *Visual & UX
> Design* forma parte de lo que se firma). Por favor revísalo y **apruébalo explícitamente** antes de crear la
> rama `feature/list-filter-sort` e implementar. Si prefieres Artículos como pantalla vehículo, u otras
> opciones de orden/agrupación, dilo y ajusto el spec antes de empezar.
