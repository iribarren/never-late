# Feature — El widget entra en Hilt y el mapeo de color de urgencia deja de estar duplicado

- **Status:** Draft — awaiting approval
- **Date:** 2026-08-17
- **Branch (suggested):** `feature/widget-hilt-color-token`
- **Prompt origen:** [`docs/prompts/widget-hilt-y-token-color.md`](../prompts/widget-hilt-y-token-color.md)
  (indexado en [`docs/diferidos.md`](../diferidos.md), item 9)
- **Type:** Behaviour-preserving refactor on `app/` only. **No** backend change, **no** API contract
  change, **no** Room schema change or migration, **no** new permission, **no** new dependency,
  **no visual change whatsoever**.
- **Tutorial:** `Sí, con lección` — lección en español, a escribir **al final** de la
  implementación (no ahora). Número sugerido **`13e`** (interleaved, justo detrás de
  `13d-hilt-di.md`, que enseñó Hilt en el caso cómodo); alternativa razonable **`05c`** si se
  prefiere colgarla del hilo del widget. **El número exacto se confirma al escribirla** contra
  `tutorial/README.md` + `docs/conceptos-pendientes.md`, y **no se renumera ninguna lección
  publicada**. Lo que enseña:
  - **`@EntryPoint` / `EntryPointAccessors`:** cómo se saca algo del grafo desde una clase que **no
    puedes anotar** porque no la construyes tú — un `GlanceAppWidget` no puede tener campos
    `@Inject`. Entender *por qué* enseña qué hace realmente `@AndroidEntryPoint`.
  - **El grafo tiene forma:** la cadena de decoradores de `TaskRepository` significa que "inyecta el
    `TaskRepository`" no es una pregunta con una sola respuesta — inyectas **una capa concreta**, y
    elegir mal reentra en el propio widget (ver D2).
  - **Duplicar un valor frente a duplicar una decisión:** la 05b ya evitó duplicar el *color*; lo que
    quedó duplicado es el **mapeo** nivel→rol. Extraerlo obliga a inventar un tipo que no es un
    `Color` ni un `ColorProvider`, sino un **nombre de rol** — ese salto de abstracción es la lección.

---

## Overview

Feature 05b (`docs/specs/2026-08-17-widget-visual-refresh.md`, lección
`tutorial/05b-widget-tema-y-glance.md`) dejó **dos deudas anotadas por escrito en el propio código**.
Esta feature las cierra. **Van juntas porque tocan los mismos ficheros, no porque sean lo mismo:**
una es de **cableado** (quién construye el repositorio del widget) y otra de **modelado** (quién
decide qué color significa "urgente"). Se ha valorado partirlas en dos features y se decide
explícitamente mantenerlas juntas (ver D5).

### Deuda 1 — cableado: el widget vive fuera de Hilt

La app migró a Hilt en la feature 13d (`docs/specs/2026-07-12-hilt-di.md`), pero el widget **no**.
Hoy, `PendingTasksWidget.provideGlance` monta su propio grafo a mano:

```kotlin
// app/src/main/java/com/neverlate/ui/widget/PendingTasksWidget.kt:58-59
val database = NeverLateDatabase.getInstance(context)
val repository = RoomTaskRepository(database.taskDao())
```

Es el único consumidor de datos de tareas del proyecto que no pasa por `RepositoryModule`. El
comentario que lo acompaña explica correctamente *por qué* no puede reutilizar el repositorio de
`MainActivity` (el widget nunca ejecuta `MainActivity.onCreate`), pero la respuesta correcta a esa
pregunta ya no es "constrúyelo a mano": es "pídeselo al grafo".

### Deuda 2 — modelado: dos mapeos de color gemelos, sincronizados a mano

Hay **dos** pares de funciones que codifican la **misma decisión** (qué rol de color corresponde a
cada nivel de urgencia y a cada prioridad), resueltos en dos mundos distintos:

| Decisión | Mundo Compose | Mundo Glance |
|---|---|---|
| `UrgencyLevel` → color | `colorForUrgency` (`ui/tasks/TasksScreen.kt:801`) | `urgencyColorProvider` (`ui/widget/WidgetColors.kt:57`) |
| `Priority` → color | `Priority.indicatorColor()` (`ui/tasks/PriorityUi.kt:34`) | `Priority.glanceIndicatorColor()` (`ui/widget/WidgetColors.kt:69`) |

El KDoc de `WidgetColors.kt` **ya avisa** de que hay que cambiarlos a la vez:

> *"whoever changes `colorForUrgency` or `Priority.indicatorColor()` must change the matching
> function here too, or the task card and the widget will silently disagree on what a color means."*

Un comentario que pide disciplina humana es exactamente el tipo de deuda que se paga con un tipo.
La distinción que la 05b levantó sigue siendo **correcta y debe sobrevivir**: el *color* se resuelve
en cada mundo (Compose lee `MaterialTheme.colorScheme`/`NeverLateExtras`; Glance lee
`GlanceTheme.colors` y pares día/noche escritos a mano). Lo que **no** debe estar duplicado es el
*mapeo*. La solución es un **token de rol** — ni un `Color` ni un `ColorProvider` — más dos
resolutores finos, uno por mundo.

### El criterio de éxito es que no se note

Al terminar, la app y el widget se ven **exactamente igual** que antes, en claro y en oscuro, en los
cuatro niveles de urgencia y en los cuatro niveles de prioridad. Esta feature no entrega píxeles
nuevos; entrega que la próxima persona que cambie un color lo cambie en **un** sitio y que el widget
deje de ser el único cliente de datos fuera del grafo.

## Goals

- El widget obtiene su `TaskRepository` **por Hilt**, de una capa elegida y **documentada**, no por
  accidente.
- Se establece en el repo el precedente de `@EntryPoint`/`EntryPointAccessors` para clases que el
  framework construye — reutilizable después por los tres workers y por
  `TasksNotificationService`/`ReminderReceiver`, que repiten el mismo montaje manual.
- El mapeo urgencia→rol y prioridad→rol existe **una sola vez**, en una función pura testeable en
  JVM, con dos resolutores finos.
- **Cero cambio observable**: ni visual, ni de comportamiento, ni de rendimiento perceptible.
- Queda **probado** (no supuesto) que completar una tarea no dispara una cascada de refrescos.

---

## Decisions (locked — do not re-litigate during implementation)

### D1 — Cómo se inyecta: `EntryPointAccessors.fromApplication(...)`, sin dependencia nueva

**Decisión:** se crea `app/src/main/java/com/neverlate/di/WidgetEntryPoint.kt` con una interfaz
`@EntryPoint @InstallIn(SingletonComponent::class)` que expone el `TaskRepository` que el widget
necesita, y `provideGlance` la obtiene con `EntryPointAccessors.fromApplication(context, …)`.

**Por qué:**

- **No necesita ningún artefacto nuevo.** `@EntryPoint` y `EntryPointAccessors` viajan dentro de
  `hilt-android`, que ya está en el catálogo desde la 13d. Cero cambios en
  `gradle/libs.versions.toml`.
- **Es la única forma que sirve para los tres sitios donde se construye el widget.**
  `PendingTasksWidget()` se instancia directamente en `PendingTasksWidgetReceiver`,
  en `TaskSurfacesRefreshingRepository.refreshSurfaces()` y en `TaskSurfacesRefreshWorker.doWork()`.
  Ninguno de los tres puede pasarle un repositorio por constructor sin cambiar las tres firmas; con
  un entry point resuelto **dentro** de `provideGlance`, los tres siguen funcionando sin tocarlos.
- **Un `GlanceAppWidget` no puede llevar `@Inject`**: no lo construye Hilt, lo construye el receiver
  (y, en dos casos más, código nuestro). `@AndroidEntryPoint` tampoco aplica: no es una de las
  clases Android que Hilt sabe interceptar.

**Alternativa rechazada — `@HiltWorker` + `HiltWorkerFactory`:** exigiría añadir
`androidx.hilt:hilt-work` al catálogo, que `NeverLateApplication` implemente
`Configuration.Provider` y desactivar el inicializador por defecto de WorkManager en el manifest.
Es mucho más cambio, y **no resuelve el widget** — resuelve los workers, que aquí no son el
problema. Si se quiere, **es una feature aparte** (ver *Out of Scope*).

**Verificado en el repo (2026-08-17):** `androidx.hilt:hilt-work` no está en
`gradle/libs.versions.toml`; no hay ningún `@HiltWorker`, ningún `HiltWorkerFactory` ni ningún
`EntryPointAccessors` en todo el proyecto. Esta feature **establece** el precedente, así que la
elección tiene que quedar escrita, no solo hecha.

### D2 — Qué se inyecta: la capa `@ReminderRepo`, **no** la vinculación sin cualificar

**Decisión:** el widget recibe la capa **`@ReminderRepo`** (`ReminderSchedulingRepository`, la
tercera de la cadena), no el `TaskRepository` sin cualificar.

**Por qué (la trampa de reentrancia):** la vinculación **sin cualificar** de `TaskRepository` en
[`RepositoryModule.kt`](../../app/src/main/java/com/neverlate/di/RepositoryModule.kt) es
`TaskSurfacesRefreshingRepository`, y su `refreshSurfaces()` privado hace exactamente esto:

```kotlin
PendingTasksWidget().updateAll(context)
TasksNotificationService.refresh(context)
```

Leer desde esa capa dentro del widget es **inocuo** (`observeTasks()` se delega sin más). Pero si el
widget alguna vez **escribe** a través de ella —justo lo que pedirán las acciones por fila de
`widget-adaptable-progreso.md`— se reentra a sí mismo: escritura → `refreshSurfaces()` →
`updateAll` → `provideGlance` → escritura → … El cableado manual de hoy evita ese bucle **por
accidente, no por diseño**, y en cuanto entre por el grafo hay que elegir a conciencia.

Inyectar `@ReminderRepo` da:

- **Los mismos datos exactos** para lectura: la cadena delega `observeTasks()`/`observeTask()` sin
  transformarlos en ninguna capa.
- **Un candado estructural**, no una convención: aunque una feature futura escriba desde el widget,
  esa escritura sí pasa por outbox + Room + reprogramación de recordatorios (lo que debe pasar) pero
  **no** re-dispara el refresco de superficies, porque esa capa queda por fuera. La feature futura
  refrescará el widget explícitamente si lo necesita, que es lo correcto: quien escribe desde el
  widget sabe si quiere redibujarlo.
- **Menos capa que atravesar** para lo único que el widget hace hoy: un `.first()` de lectura.

**Alternativas rechazadas:**

- *La cadena completa (sin cualificar):* correcta hoy (solo se lee), pero deja armada la trampa para
  la siguiente feature, que es justo la que el prompt origen advierte que va detrás de esta.
- *Una guarda de reentrancia dentro de `TaskSurfacesRefreshingRepository`* (flag/`ThreadLocal`):
  añade estado y una condición de carrera a un decorador hoy trivial, para arreglar un problema que
  la elección de capa resuelve sin código.
- *`@RoomRepo` / `@OutboxRepo`:* también seguros para leer, pero saltarse capas sin motivo hace que
  una futura escritura desde el widget pierda outbox o recordatorios silenciosamente. `@ReminderRepo`
  es la capa **más externa que no reentra**, que es exactamente el criterio.

**Se exige verificación explícita, no confianza:** ver AC-8 y el *Testing Plan*.

### D3 — El token es un **nombre de rol**, nunca un `Color` ni un `ColorProvider`

**Decisión:** se introduce un enum de rol de color y **una función pura** por cada decisión
duplicada:

- un enum de rol de urgencia con los roles que el mapeo actual realmente usa —
  **`Calm` / `Soon` / `Error`** (nótese: **tres** roles para **cuatro** `UrgencyLevel`, porque
  `Urgent` y `Overdue` comparten el rol `error` en ambos mundos hoy, y eso **no cambia**);
- un enum (o reutilización del mismo tipo) para el rol de prioridad —
  **`Primary` / `Tertiary` / `Secondary` / ninguno** para `HIGH`/`MEDIUM`/`LOW`/`NONE`;
- funciones puras `UrgencyLevel → rol` y `Priority → rol?`, **sin `@Composable`, sin Android, sin
  Compose ni Glance en su firma** — testeables en JVM.

Sobre ellas, **dos resolutores finos** que no toman ninguna decisión propia, solo traducen rol→color
en su mundo:

- Compose: `@Composable` que lee `MaterialTheme.colorScheme.error` / `NeverLateExtras.colors.calm` /
  `.soon` y los roles de prioridad;
- Glance: `@Composable` que lee `GlanceTheme.colors.error` y los pares día/noche `CalmColor`/
  `SoonColor` ya existentes en `WidgetColors.kt`.

**Por qué:** un token que devolviera un `Color` volvería a romper la frontera que la 05b levantó (el
`CompositionLocal` de Material 3 **no existe** dentro de una composición de Glance). Unificar el
**mapeo** dejando la **resolución** en cada mundo es exactamente la distinción que el KDoc de
`WidgetColors.kt` ya explica — esta feature la convierte de comentario en tipo.

**Ubicación del enum + funciones puras:** `domain/tasks/` (junto a `Urgency.kt`), por coherencia con
el resto de reglas puras y JVM-testables del proyecto. Es admisible `ui/theme/` si al implementar se
ve que el nombre de rol es vocabulario de tema y no de dominio; **elegir uno y justificarlo en el
KDoc**, no dejar el tipo en `ui/widget/` (que lo ataría al widget, que es el consumidor menor).

### D4 — Los pares día/noche escritos a mano **se quedan**

`CalmColor`, `SoonColor` y `dividerColor` en `WidgetColors.kt` **no son un parche que arreglar**:
son los roles que el puente de `glance-material3` no expone (`calm`/`soon` no existen en Material 3;
`outlineVariant` existe en `ColorScheme` pero la `ColorProviders` de Glance no lo lleva). Siguen
haciendo falta y esta feature **no los toca** salvo para que los consuma el resolutor Glance.

### D5 — Las dos deudas van en una sola feature

Se mantienen juntas porque tocan los mismos ficheros (`PendingTasksWidget.kt`, `WidgetColors.kt`) y
comparten la verificación cara (comparación visual antes/después del widget en claro/oscuro y en los
cuatro niveles). Partirlas obligaría a hacer esa comparación dos veces sobre la misma superficie.
Son, eso sí, **dos entregas independientes dentro de la rama**: US-1/US-2 (cableado) y US-3
(modelado) no dependen la una de la otra y pueden implementarse y revisarse por separado.

---

## User Stories

### US-1 — El widget obtiene su repositorio del grafo

> **Como** desarrollador del proyecto, **quiero** que el widget pida su `TaskRepository` a Hilt
> **para que** exista una sola forma de construir el acceso a datos y el widget deje de ser la
> excepción que 13d no migró.

**Acceptance criteria**

- `PendingTasksWidget.provideGlance` **no** contiene `NeverLateDatabase.getInstance(...)` ni
  `RoomTaskRepository(...)`.
- Existe `app/src/main/java/com/neverlate/di/WidgetEntryPoint.kt` con una interfaz `@EntryPoint`
  `@InstallIn(SingletonComponent::class)` que expone el `TaskRepository` cualificado de D2.
- `provideGlance` obtiene el repositorio vía `EntryPointAccessors.fromApplication(context, …)`.
- El widget sigue dibujando exactamente las mismas filas que antes (mismo `pendingRowsFor`, misma
  toma instantánea con `.first()`, misma ausencia de suscripción continua).
- El widget funciona en sus **tres** rutas de construcción: colocado desde el picker
  (`PendingTasksWidgetReceiver`), refrescado tras una escritura
  (`TaskSurfacesRefreshingRepository`) y refrescado periódicamente (`TaskSurfacesRefreshWorker`).
- No se añade ninguna dependencia al catálogo de versiones.

### US-2 — La capa inyectada es una decisión escrita, no un accidente

> **Como** desarrollador que mañana añadirá acciones por fila al widget, **quiero** que la capa del
> repositorio que recibe el widget esté elegida y explicada **para que** una escritura desde el
> widget no se reentre a sí misma.

**Acceptance criteria**

- El `@EntryPoint` expone la capa **`@ReminderRepo`**, y su KDoc explica en inglés (convención del
  proyecto para código) por qué **no** la vinculación sin cualificar, nombrando el ciclo
  `refreshSurfaces() → updateAll → provideGlance`.
- El KDoc de `TaskSurfacesRefreshingRepository` (o el del entry point) deja constancia de que esa
  capa **no** debe ser el repositorio del widget.
- Completar (o crear/borrar/pausar) una tarea desde la app refresca el widget **exactamente una
  vez**: no hay cascada. Verificado según el *Testing Plan*.

### US-3 — Un solo sitio decide qué color significa "urgente"

> **Como** desarrollador, **quiero** cambiar el color de un nivel de urgencia o de una prioridad en
> **un** sitio **para que** la lista de tareas y el widget no puedan discrepar en silencio.

**Acceptance criteria**

- Existe un enum de rol de color y funciones **puras** `UrgencyLevel → rol` y `Priority → rol?`, sin
  `@Composable` y sin tipos de Compose/Glance en su firma.
- `colorForUrgency` (`TasksScreen.kt`), `urgencyColorProvider`, `Priority.indicatorColor()` y
  `Priority.glanceIndicatorColor()` pasan a ser **resolutores finos** que consultan esas funciones y
  solo traducen rol→color en su mundo; ninguno vuelve a repetir el `when` sobre `UrgencyLevel` o
  `Priority`.
- `Priority.NONE` sigue devolviendo `null` (sin marcador) en ambos mundos.
- `UrgencyLevel.Urgent` y `UrgencyLevel.Overdue` siguen compartiendo el rol de error en ambos
  mundos.
- El aviso "cámbialos a la vez" desaparece del KDoc de `WidgetColors.kt` **porque ya no es cierto**,
  y el KDoc pasa a explicar la separación mapeo (compartido) / resolución (por mundo).
- Hay un test JVM que cubre los **cuatro** `UrgencyLevel` y las **cuatro** `Priority` contra el rol
  esperado.

### US-4 — Nadie nota nada

> **Como** usuario, **quiero** que la app y el widget sigan viéndose y comportándose exactamente
> igual **para que** un cambio interno no me cueste ni un píxel.

**Acceptance criteria**

- Ver *Visual & UX Design* — todos los criterios visuales de esta feature son de **equivalencia
  antes/después**.

---

## Acceptance Criteria (consolidated)

| # | Criterio | Tipo |
|---|---|---|
| AC-1 | `provideGlance` no construye base de datos ni repositorio a mano; los obtiene de `WidgetEntryPoint` vía `EntryPointAccessors.fromApplication`. | Comportamiento |
| AC-2 | El entry point expone la capa `@ReminderRepo`, con el porqué documentado en su KDoc. | Comportamiento |
| AC-3 | El widget se dibuja correctamente en sus tres rutas de construcción (receiver, refresco por escritura, worker periódico). | Comportamiento |
| AC-4 | Existe una función pura `UrgencyLevel → rol` y otra `Priority → rol?`, sin `@Composable` ni tipos de Compose/Glance. | Comportamiento |
| AC-5 | Los cuatro resolutores de color consumen esas funciones; ninguno duplica el `when`. | Comportamiento |
| AC-6 | Test JVM verde cubriendo los 4 `UrgencyLevel` y las 4 `Priority`. | Comportamiento |
| AC-7 | `./gradlew :app:testDebugUnitTest` en verde; `./gradlew :app:assembleDebug` compila. | DoD |
| AC-8 | Completar una tarea provoca **un solo** `updateAll` del widget — sin cascada. Verificado con log/instrumentación temporal o test, no por inspección. | Comportamiento |
| AC-9 | Sin dependencia nueva, sin cambio en `docs/api/contract.md`, sin cambio de esquema Room ni migración, sin permiso nuevo. | DoD |
| AC-10 | El widget se ve **idéntico** antes y después, en tema claro y oscuro, en los 4 niveles de urgencia. | Visual |
| AC-11 | La lista de tareas se ve **idéntica** antes y después (color de cuenta atrás y punto de prioridad), en claro y oscuro. | Visual |
| AC-12 | El marcador de prioridad del widget (`!`/`!!`/`!!!`) conserva color y ausencia para `NONE`; el peso de fuente por urgencia (Bold en Urgent/Overdue, Medium en Calm/Soon) se conserva intacto. | Visual |
| AC-13 | Sin cambios en `strings.xml` ni en `values-en/` (esta feature no añade texto de usuario). | i18n |
| AC-14 | Accesibilidad intacta: mismos `contentDescription` de fila, mismos objetivos táctiles. | A11y |
| AC-15 | `docs/mockups/README.md`: se **anota la fila existente del widget (05b)** indicando que la deuda queda cerrada. **No se añade fila nueva.** | Docs |
| AC-16 | `docs/arquitectura.md` recoge las dos decisiones (entry point + capa elegida; token de rol). | Docs |
| AC-17 | `docs/diferidos.md` marca el item 9 como hecho y deja constancia de que el 1 (`widget-adaptable-progreso.md`) ya tiene su prerrequisito. | Docs |

---

## Visual & UX Design

### **No hay cambio visual. Ninguno.**

Esta es una feature de refactor puro: el objetivo declarado es que **no se note**. No implementa
ninguna porción nueva del mockup maestro
([`docs/mockups/rediseno-ux-ui.html`](../mockups/rediseno-ux-ui.html)) — el widget, además, **no
está en el mockup**, que es solo de la app de teléfono (así consta ya en la fila 05b de
[`docs/mockups/README.md`](../mockups/README.md)).

**Slice del mockup implementado:** *ninguno*. **Polish visual diferido:** *nada nuevo* — los
diferidos visuales del widget siguen siendo los que la 05b ya registró (barra de progreso por fila y
objetivos táctiles por fila → `widget-adaptable-progreso.md`; Material You / `ThemeMode` dentro del
widget; prioridad en notificación y estadísticas). Esta feature **no los toca ni los mueve**.

**Tracking:** se **anota la fila existente del widget (05b)** de `docs/mockups/README.md` para dejar
dicho que la deuda de cableado y la de mapeo de color quedaron cerradas. **No se crea fila nueva**,
porque no hay elemento visual que trackear.

### Tokens y componentes reutilizados

No se inventa estilo. Se sigue leyendo, sin excepción:

- `ui/theme/` — `MaterialTheme.colorScheme` (`error`, `primary`, `secondary`, `tertiary`) y
  `NeverLateExtras.colors` (`calm`, `soon`) para el mundo Compose;
- `GlanceTheme.colors` + los pares día/noche de `WidgetColors.kt` (`CalmColor`, `SoonColor`,
  `dividerColor`) para el mundo Glance, **que se conservan tal cual** (D4);
- `ui/components/formatRemainingLabel` y el resto de componentes compartidos, sin cambios.

### Criterios de aceptación visuales (todos de equivalencia antes/después)

Verificación por **comparación lado a lado** con capturas tomadas **antes** de tocar nada (en la
rama, en el primer commit) y **después** de la implementación:

| # | Criterio visual |
|---|---|
| V-1 | Widget en **tema claro**: idéntico antes/después — fondo, banda de cabecera de color `primaryContainer`, esquinas redondeadas, divisores hairline, tipografía. |
| V-2 | Widget en **tema oscuro**: idéntico antes/después, mismos elementos. |
| V-3 | Color de la cuenta atrás del widget idéntico en los **cuatro** niveles: `Calm`, `Soon`, `Urgent`, `Overdue` (Urgent y Overdue siguen compartiendo el rojo de error), en claro y en oscuro. |
| V-4 | Peso de fuente de la cuenta atrás idéntico: `Bold` en `Urgent`/`Overdue`, `Medium` en `Calm`/`Soon` — el canal redundante al color no se pierde. |
| V-5 | Marcador de prioridad del widget idéntico: `!`/`!!`/`!!!` con `secondary`/`tertiary`/`primary`, y **nada** para `NONE`. |
| V-6 | Lista de tareas: color de la cuenta atrás idéntico en los cuatro niveles, claro y oscuro. |
| V-7 | Lista de tareas: punto de prioridad idéntico en los cuatro valores (incluida la ausencia para `NONE`), claro y oscuro. |
| V-8 | Estado **vacío** del widget idéntico (texto sobre `onSurfaceVariant`). |
| V-9 | Objetivos táctiles y `contentDescription` sin cambios; el widget entero sigue siendo un solo `clickable` que abre la lista de tareas. |
| V-10 | La app reflows correctamente a la mayor escala de fuente, igual que antes (no hay motivo para que cambie; se comprueba porque el cambio toca color de texto). |

**Cómo se generan los cuatro niveles de urgencia para la comparación:** crear cuatro tareas con
deadlines escalonados (> 1 h → `Calm`; entre 5 min y 1 h → `Soon`; < 5 min → `Urgent`; ya vencida →
`Overdue`), según los umbrales de `domain/tasks/Urgency.kt`.

---

## Technical Approach

Sub-proyecto: **solo `app/`**. Ningún cambio en `backend/`.

### 1. `di/WidgetEntryPoint.kt` (nuevo)

Interfaz `@EntryPoint @InstallIn(SingletonComponent::class)` con un único accesor que devuelve el
`TaskRepository` **cualificado con `@ReminderRepo`** (los cualificadores ya existen en
`di/Qualifiers.kt`). KDoc en inglés explicando: (a) por qué existe un entry point en vez de
`@Inject` (un `GlanceAppWidget` no lo construye Hilt), y (b) por qué esta capa y no la sin
cualificar (el ciclo de reentrancia de D2).

`RepositoryModule` **no necesita cambios**: la vinculación `@ReminderRepo` ya existe y es
`@Singleton`.

### 2. `PendingTasksWidget.provideGlance`

Sustituir las dos líneas de montaje manual por la resolución del entry point desde
`context.applicationContext`. El resto de `provideGlance` (`.first()`, `toWidgetModel`,
`GlanceTheme(ColorProviders(light, dark))`) **no se toca**. Actualizar el comentario existente: hoy
explica por qué se monta a mano; pasa a explicar por qué se usa un entry point.

Los tres sitios que hacen `PendingTasksWidget()` (`PendingTasksWidgetReceiver`,
`TaskSurfacesRefreshingRepository`, `TaskSurfacesRefreshWorker`) **no cambian** — esa es justo la
ventaja de resolver dentro de `provideGlance`.

### 3. El token de rol (nuevo, en `domain/tasks/`)

Enum de rol + funciones puras (ver D3), con KDoc que explique la separación mapeo/resolución y que
apunte a los dos resolutores.

### 4. Adelgazar los cuatro resolutores

- `TasksScreen.kt::colorForUrgency` → `when (roleFor(level)) { … }` sobre
  `MaterialTheme.colorScheme`/`NeverLateExtras`.
- `PriorityUi.kt::Priority.indicatorColor()` → ídem sobre `MaterialTheme.colorScheme`.
- `WidgetColors.kt::urgencyColorProvider` → ídem sobre `GlanceTheme.colors` + `CalmColor`/`SoonColor`.
- `WidgetColors.kt::Priority.glanceIndicatorColor()` → ídem sobre `GlanceTheme.colors`.

Y reescribir el KDoc de cabecera de `WidgetColors.kt`: el aviso de sincronización manual se elimina
(deja de ser cierto) y se sustituye por la explicación de que el mapeo es compartido y solo la
resolución vive aquí.

### 5. La puerta que se deja abierta (sin cruzarla)

El `@EntryPoint` creado aquí es **deliberadamente reutilizable** por los otros cinco puntos que
repiten el mismo montaje manual — `TaskSurfacesRefreshWorker`, `BootRescheduleWorker`, `SyncWorker`,
`TasksNotificationService` y `ReminderReceiver` (todos hacen
`NeverLateDatabase.getInstance(...)` + `RoomTaskRepository(...)`). Migrarlos está **fuera de
alcance** aquí, pero el KDoc del entry point debe decirlo explícitamente, para que la próxima
persona **extienda este mecanismo en vez de inventar un tercero**. Ojo: cada uno de ellos podría
necesitar una capa distinta de la cadena (los que escriben, en particular), así que la reutilización
puede implicar **añadir un accesor** al entry point, no reutilizar ciegamente el del widget.

---

## Out of Scope

Explícitamente **fuera**, cada uno como feature propia si se quiere:

- **Migrar los tres `CoroutineWorker` a `@HiltWorker`** (`TaskSurfacesRefreshWorker`,
  `BootRescheduleWorker`, `SyncWorker`). Requeriría `androidx.hilt:hilt-work` en el catálogo,
  `NeverLateApplication` como `Configuration.Provider` y desactivar el inicializador por defecto de
  WorkManager en el manifest. **Feature separada** si se quiere.
- **Migrar `TasksNotificationService` y `ReminderReceiver`** al nuevo entry point. La puerta se deja
  abierta y documentada; no se cruza aquí.
- **Cualquier cambio visual o de diseño.** Si durante la implementación se ve una mejora visual, se
  anota en `docs/diferidos.md` y **no se hace en esta rama**.
- **Acciones por fila en el widget / escrituras desde el widget** (completar una tarea desde el
  launcher). Eso es [`docs/prompts/widget-adaptable-progreso.md`](../prompts/widget-adaptable-progreso.md),
  que **depende de que esta feature aterrice antes** (D2).
- **Widget consciente del tamaño (`SizeMode.Responsive`), barras de progreso por fila, Material You /
  `ThemeMode` dentro del widget.** Diferidos de la 05b, siguen diferidos.
- **Prioridad en la notificación y en estadísticas.** Diferido de la 13b, sigue diferido.
- **Backend, contrato de API, esquema de Room o migraciones, permisos nuevos.** Ninguno se toca.
- **Unificar los pares día/noche escritos a mano.** No son deuda (D4).
- **Escribir la lección de tutorial.** Se escribe al final de la implementación, antes del commit
  (paso 8 del *New Feature Workflow*).

---

## Dependencies

- **Feature 13d (Hilt)** — ya en `master`: `hilt-android`, `NeverLateApplication` con
  `@HiltAndroidApp`, `RepositoryModule` con la cadena cualificada y `di/Qualifiers.kt`
  (`@RoomRepo`/`@OutboxRepo`/`@ReminderRepo`). **Todo presente y verificado.**
- **Feature 05b (rediseño del widget)** — ya en `master` (`a98699a`): `WidgetColors.kt`,
  `GlanceTheme` + `glance-material3`, `PendingTaskRow.urgencyLevel()`. Esta feature cierra sus dos
  deudas anotadas.
- **Feature 17** — `NeverLateExtras` con `calm`/`soon`; **feature 13b** — `Priority` y su indicador.
- Un **emulador o dispositivo** para la comparación visual antes/después y para AC-8 (el único
  requisito operativo real de esta feature).
- Ninguna dependencia nueva de Gradle. Ningún trabajo de backend. Ningún bloqueo externo.

---

## Risks

| # | Riesgo | Mitigación |
|---|---|---|
| R-1 | **Regresión silenciosa de color**: adelgazar cuatro resolutores a la vez es justo donde se cuela un rol mal mapeado, y la única prueba real es visual. | Test JVM exhaustivo sobre los 8 casos (4 urgencias + 4 prioridades) + comparación antes/después obligatoria con capturas en claro y oscuro (V-1…V-7). Tomar las capturas *antes* de tocar código. |
| R-2 | **La cascada de refrescos no se manifiesta hoy** (el widget solo lee), así que AC-8 puede "pasar" sin probar nada. | Verificar con logging temporal en `provideGlance` + `refreshSurfaces()` contando invocaciones ante una escritura, no por inspección de código. Documentar el resultado en el PR. |
| R-3 | **`EntryPointAccessors` en el proceso equivocado.** El widget dibuja en el proceso del launcher, pero `provideGlance` se ejecuta en el proceso de la app; aun así, hay que resolver desde `context.applicationContext`, no desde el `Context` recibido sin más. | Usar `fromApplication` (no `fromActivity`/`fromFragment`) y probar el widget desde el picker con la app cerrada (proceso frío). |
| R-4 | **Proceso frío**: cuando el launcher pide un update sin que la app haya arrancado, el `SingletonComponent` se crea en ese momento. Si algún binding de la cadena hiciera trabajo caro en construcción, el widget tardaría más que hoy. | La cadena solo compone objetos ligeros sobre `NeverLateDatabase` (que ya se creaba antes, igual de perezosa). Verificar el arranque en frío: forzar `adb shell am force-stop` + provocar un update del widget. |
| R-5 | **Discusión de ubicación del token** (`domain/` vs `ui/theme/`) en review, tras implementar. | D3 fija `domain/tasks/` como opción por defecto y admite `ui/theme/` con justificación en el KDoc. No dejarlo en `ui/widget/`. |
| R-6 | **Alcance que se estira**: estando dentro de estos ficheros es tentador migrar también los workers o la notificación. | *Out of Scope* lo prohíbe explícitamente. Cualquier hallazgo va a `docs/diferidos.md`. |
| R-7 | **La lección puede querer renumerarse** (05c vs 13e) al escribirla. | Se decide al escribirla contra `tutorial/README.md`; **nunca** se renumera una lección publicada. |

---

## Testing Plan

- **JVM (`qa-engineer`)** — lo único de esta feature testeable sin dispositivo:
  - `UrgencyLevel → rol` para `Calm`, `Soon`, `Urgent`, `Overdue` (con `Urgent`/`Overdue` → rol de
    error).
  - `Priority → rol?` para `NONE` (→ `null`), `LOW`, `MEDIUM`, `HIGH`.
  - `./gradlew :app:testDebugUnitTest` en verde antes de commitear.
- **Verificación de reentrancia (AC-8)** — con la app instalada y el widget colocado: instrumentar
  temporalmente un contador/log en `provideGlance` y en `refreshSurfaces()`, completar una tarea y
  comprobar **una sola** pasada de refresco. Retirar la instrumentación antes del commit y anotar el
  resultado en el PR.
- **Comparación visual manual (V-1…V-10)** — capturas antes/después del widget y de la lista, en
  claro y oscuro, con las cuatro tareas escalonadas descritas arriba.
- **Arranque en frío (R-4)** — `adb shell am force-stop com.neverlate`, provocar un update del
  widget y comprobar que dibuja correctamente.
- **Sin tests instrumentados nuevos**: no hay comportamiento de UI nuevo que probar; la equivalencia
  es visual por definición.

**Agentes:** `mobile-engineer` (entry point, capa inyectada, token de rol y adelgazado de los cuatro
resolutores); `qa-engineer` (test JVM de las funciones puras, verificación de reentrancia y
comparación visual antes/después).

---

## Documentation Update checklist (before commit)

- [x] `docs/mockups/README.md` — **anotar la fila 05b** (deuda cerrada). **Sin fila nueva.**
- [x] `docs/arquitectura.md` — añadir las decisiones D1/D2 (entry point + capa `@ReminderRepo` y su
      porqué) y D3 (token de rol).
- [x] `docs/diferidos.md` — item 9 hecho; anotar que el item 1 ya tiene su prerrequisito cubierto.
- [x] `CLAUDE.md` — actualizar el mapa de módulos: `di/` gana `WidgetEntryPoint.kt`; la fila de
      `ui/widget/` deja de decir que monta el repositorio a mano; la nota de los gemelos de color en
      `WidgetColors.kt` se sustituye por la del token compartido.
- [x] `tutorial/13e-entrypoint-token-color.md` (español), reflejado en `tutorial/README.md` y
      `docs/conceptos-pendientes.md`.
- [x] `docs/api/contract.md` — **no aplica** (sin cambio de wire).
- [x] `gradle/libs.versions.toml` — **no aplica** (sin dependencia nueva).
- [x] Esquema Room / migración — **no aplica**.

---

## Approval

Por favor **revisa y aprueba esta spec antes de implementar**. La aprobación cubre las tres cosas
que el flujo firma juntas: **comportamiento** (D1, D2, D5), **aspecto** (que es *ninguno* — la
sección *Visual & UX Design* y sus criterios de equivalencia) y la **decisión de tutorial**
(`Sí, con lección`).

Puntos que conviene mirar con atención antes de aprobar:

1. **D2 — inyectar `@ReminderRepo` en vez de la cadena completa.** Es la decisión de fondo de la
   feature: elige la capa más externa que no reentra, a cambio de que una escritura futura desde el
   widget tenga que refrescar el widget explícitamente.
2. **D3 — el token es un nombre de rol** (`Calm`/`Soon`/`Error`), con **tres** roles para **cuatro**
   niveles. Si se prefiere un rol por nivel (más simétrico, pero introduce una distinción que hoy no
   existe), hay que decirlo ahora.
3. **D5 — las dos deudas en una sola rama.** Si se prefieren dos features, es una decisión legítima
   y este es el momento.
