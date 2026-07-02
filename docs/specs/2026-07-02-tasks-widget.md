# Especificación — Widget de tareas pendientes

- **Fecha:** 2026-07-02
- **Feature:** Widget de pantalla de inicio con tareas pendientes y tiempo restante (Pending-tasks home-screen App Widget)
- **Prompt origen:** `docs/prompts/05-widget-tareas.md`
- **Rama sugerida:** `feature/tasks-widget`
- **Lección tutorial asociada:** `tutorial/05-widget.md` (español)
- **Estado:** Pendiente de aprobación

> **Corrección respecto al prompt origen:** `docs/prompts/05-widget-tareas.md` indica la lección en
> `tutorial/04-widget.md`. Ese número es **incorrecto**: `tutorial/04-tareas-contador.md` ya existe
> (feature 04). La lección de esta feature es **`tutorial/05-widget.md`**.

---

## Overview

Esta feature añade un **widget de pantalla de inicio** (Android App Widget) que muestra las **tareas
pendientes** de la persona usuaria con el **tiempo restante** de cada una, sin necesidad de abrir la
app. Es la primera vez que la app pinta algo **fuera de su propia `Activity`**: un widget vive en el
proceso del *launcher* (la pantalla de inicio) y se dibuja mediante `RemoteViews`. Para construirlo
usamos **Glance**, la biblioteca de Jetpack que permite escribir widgets con una API declarativa al
estilo Compose.

El objetivo de producto es **reducir la fricción**: para una persona con TDA/TDAH, tener las tareas
y su cuenta atrás **siempre a la vista** en la pantalla de inicio ataca la ceguera temporal (*time
blindness*) sin exigir el paso deliberado de abrir la app y navegar hasta la lista. El widget es una
señal ambiental, pasiva y permanente.

La restricción de arquitectura central es que el widget **reutiliza la capa de datos existente** de
la feature 04: lee las tareas y calcula el tiempo restante a través del **mismo repositorio Room**
(`TaskRepository` / `RoomTaskRepository` sobre `NeverLateDatabase`) y de las **mismas funciones puras
de tiempo** (`TaskTiming.kt`). No se inventa un modelo de datos nuevo ni una segunda fuente de
verdad: el widget es un **consumidor de lectura** del repositorio que ya existe.

Todo sigue siendo **local-only** (sin backend, sin permiso `INTERNET`). El widget lee de la base de
datos en disco y se actualiza en segundo plano.

### Conceptos nuevos que enseña (para `tutorial/05-widget.md`)

Partiendo de las lecciones 01–04 (Compose, `ViewModel`/`StateFlow`, DI manual, repositorio tras
interfaz, Room + `Flow`, reloj de pared):

- **App Widgets con Glance:** qué es un App Widget, el `GlanceAppWidget` y su
  `GlanceAppWidgetReceiver`, y cómo Glance traduce composables a `RemoteViews`. Diferencias con la
  Compose "normal" (subconjunto de componentes: `Column`, `Row`, `Text`, `LazyColumn` de Glance;
  sin acceso al `Modifier` completo, sin estado en memoria persistente).
- **Cómo un widget lee datos de la app:** obtener la instancia **singleton** de `NeverLateDatabase`
  desde el `Context` del widget (`NeverLateDatabase.getInstance(context)`), construir
  `RoomTaskRepository(database.taskDao())` y leer un **snapshot** de las tareas con `.first()` sobre
  el `Flow` (un widget no mantiene una suscripción viva: se dibuja, no "observa").
- **Cálculo del tiempo restante reutilizando el reloj de pared:** aplicar `computeRemainingMillis` y
  `formatRemaining` sobre cada tarea en el instante del render, exactamente como hace la app. Aquí se
  paga el diseño "reloj de pared" de la feature 04: el widget calcula el mismo restante sin heredar
  ningún temporizador vivo.
- **Actualización en segundo plano con WorkManager:** un `CoroutineWorker` periódico que llama a
  `MiWidget.updateAll(context)` para refrescar el tiempo restante (los widgets **no** tienen su
  propio bucle de `delay`). Y la **actualización dirigida por cambios**: invocar `updateAll(...)`
  desde el repositorio/ViewModel cuando una tarea se crea, edita, borra, inicia o pausa.
- **Ciclo de vida y limitaciones de los widgets:** frecuencia mínima de refresco del sistema
  (`updatePeriodMillis` con suelo de ~30 min), por qué no se puede animar una cuenta atrás segundo a
  segundo, ejecución con recursos limitados y en un proceso ajeno, y el manejo del `PendingIntent`
  para abrir la app al tocar el widget.

---

## Goals

Éxito significa que:

1. La persona usuaria puede **añadir un widget** de "Never Late" a su pantalla de inicio desde el
   selector de widgets del launcher.
2. El widget muestra una **lista de tareas pendientes** con, por cada una, su **título** y su
   **tiempo restante** formateado de forma legible (`mm:ss` / `hh:mm:ss`), calculado con la misma
   lógica que la app.
3. El widget muestra un **estado vacío** legible cuando no hay tareas pendientes (no un recuadro en
   blanco).
4. El widget se **actualiza al cambiar las tareas** (crear / editar / borrar / iniciar / pausar /
   completar) sin que la persona tenga que hacer nada.
5. El widget se **refresca periódicamente** en segundo plano para mantener el tiempo restante
   razonablemente al día, dentro de los límites del sistema.
6. **Tocar el widget abre la app** (la lista de tareas).
7. El widget **reutiliza** `TaskRepository` / `NeverLateDatabase` / `TaskTiming` **sin modificar el
   modelo de datos** ni la UI de la feature 04.
8. **Glance** y **WorkManager** quedan declarados en `gradle/libs.versions.toml` (nada hardcodeado en
   `build.gradle.kts`).
9. La lección `tutorial/05-widget.md` (español) explica los conceptos nuevos con referencia al código
   real, de forma progresiva sobre las lecciones 01–04.

---

## User Stories

### US-1 — Añadir el widget a la pantalla de inicio
**Como** persona que quiere no llegar tarde a lo que tiene que hacer,
**quiero** colocar un widget de la app en mi pantalla de inicio,
**para** ver mis tareas pendientes sin tener que abrir la app.

**Criterios de aceptación:**
- El widget de "Never Late" aparece en el **selector de widgets** del launcher con un nombre y una
  vista previa (`previewImage` / `description`) legibles.
- Al soltarlo en la pantalla de inicio, se dibuja con contenido real (o el estado vacío) **sin
  crashear** y sin requerir abrir la app primero.
- El widget respeta un **tamaño mínimo** razonable declarado en su metadata (`minWidth` / `minHeight`
  y `targetCellWidth`/`targetCellHeight`), y su contenido no se recorta de forma ilegible en ese
  tamaño.

### US-2 — Ver las tareas pendientes con su tiempo restante
**Como** usuaria con el widget colocado,
**quiero** ver mis tareas pendientes y cuánto tiempo me queda en cada una,
**para** percibir de un vistazo qué tengo por delante.

**Criterios de aceptación:**
- El widget lista las tareas **pendientes** obtenidas del repositorio Room (`observeTasks()` leído
  como snapshot con `.first()`), mostrando por cada fila al menos **título** y **tiempo restante**.
- El **tiempo restante** se calcula con `computeRemainingMillis(task, now)` y se formatea con
  `formatRemaining(...)`, de modo que coincide con lo que muestra la app para esa tarea en ese
  instante (misma regla US-5 de la feature 04: cuenta atrás hacia la fecha límite si la hay; si no,
  hacia la duración estimada).
- Una tarea con el tiempo **agotado** (restante `0`) se muestra en un estado claro (p. ej. `00:00` o
  una etiqueta "tiempo agotado"), **nunca en negativo**.
- Si hay más tareas de las que caben, el widget usa la `LazyColumn` de Glance para permitir
  desplazamiento (o limita a las N más urgentes de forma documentada), sin romper el layout.

### US-3 — Estado vacío
**Como** usuaria sin tareas pendientes,
**quiero** que el widget me lo diga con claridad,
**para** no confundir "vacío" con "roto".

**Criterios de aceptación:**
- Cuando el repositorio no devuelve tareas pendientes, el widget muestra un **mensaje de estado
  vacío** legible (texto desde `strings.xml`), no un recuadro en blanco ni un error.

### US-4 — Actualización al cambiar las tareas
**Como** usuaria que gestiona tareas dentro de la app,
**quiero** que el widget refleje los cambios que hago,
**para** que lo que veo en la pantalla de inicio sea fiable.

**Criterios de aceptación:**
- Tras **crear, editar, borrar, iniciar o pausar** una tarea en la app, el widget se actualiza para
  reflejar ese cambio (vía `updateAll(...)` disparado por la escritura correspondiente), sin
  necesidad de recolocar el widget ni reiniciar el dispositivo.
- La actualización dirigida por cambios **no** depende del refresco periódico: ocurre de forma
  proactiva al escribir en el repositorio.

### US-5 — Refresco periódico en segundo plano
**Como** usuaria que deja el widget a la vista,
**quiero** que el tiempo restante se mantenga razonablemente al día,
**para** confiar en la cifra aunque no toque nada.

**Criterios de aceptación:**
- Un trabajo periódico (**WorkManager** `PeriodicWorkRequest`, o el `updatePeriodMillis` del
  `appwidget-provider`) llama a `updateAll(...)` de forma recurrente para recalcular el tiempo
  restante.
- La lección y el spec **documentan explícitamente** que el sistema impone un suelo de frecuencia
  (~15 min para WorkManager periódico, ~30 min para `updatePeriodMillis`), por lo que el widget **no
  cuenta atrás segundo a segundo**; muestra el restante en el momento del último refresco. Este
  límite es una expectativa aceptada, no un bug.

### US-6 — Tocar el widget abre la app
**Como** usuaria,
**quiero** tocar el widget y que se abra la app en las tareas,
**para** pasar de "ver" a "gestionar" en un toque.

**Criterios de aceptación:**
- Tocar el widget (o una fila) lanza un **`PendingIntent`** que abre `MainActivity` en la **lista de
  tareas** (ruta `tasks` del `AppNavHost`).
- El intent es inmutable y usa las flags correctas (`FLAG_IMMUTABLE`) según las exigencias de la API
  actual.

---

## Acceptance Criteria (resumen verificable)

Criterios concretos y testeables para dar la feature por completada:

1. **Widget instalable:** el App Widget aparece en el selector del launcher y se puede colocar; se
   dibuja con contenido o estado vacío sin crashear. *(Verificación manual en emulador; smoke test.)*
2. **Contenido correcto:** el widget muestra título + tiempo restante por tarea pendiente,
   coincidiendo con lo que calcula la app. *(Test JVM de la **función de mapeo** tareas→modelo de UI
   del widget, ver Testing notes.)*
3. **Tiempo restante y formato:** el restante se deriva de `computeRemainingMillis` y `formatRemaining`
   (reutilizados), sin negativos y con la frontera `mm:ss` ↔ `hh:mm:ss`. *(Cubierto por los tests
   existentes de `TaskTiming` + test de la función de mapeo del widget.)*
4. **Estado vacío:** sin tareas pendientes, el widget muestra el mensaje de estado vacío. *(Test JVM
   de la función de mapeo: entrada lista vacía → estado vacío.)*
5. **Refresco por cambios:** crear/editar/borrar/iniciar/pausar una tarea dispara `updateAll(...)`.
   *(Verificación por inspección del punto de invocación + verificación manual.)*
6. **Refresco periódico:** existe un `PeriodicWorkRequest` (o `updatePeriodMillis`) que refresca el
   widget; documentado el suelo de frecuencia del sistema. *(Inspección de la configuración de
   WorkManager / metadata + revisión de la lección.)*
7. **Tap abre la app:** tocar el widget abre `MainActivity` en la ruta `tasks` con un `PendingIntent`
   `FLAG_IMMUTABLE`. *(Inspección del código + verificación manual.)*
8. **Reutilización sin tocar el modelo:** el widget usa `NeverLateDatabase.getInstance(...)`,
   `RoomTaskRepository`/`TaskRepository` y `TaskTiming`; no añade entidades ni cambia el esquema de
   `Task`. *(Inspección de tipos e imports; `git diff` no toca `data/tasks/Task.kt` salvo, como mucho,
   añadir un criterio de "pendiente".)*
9. **Manifest y metadata:** `AndroidManifest.xml` declara el `receiver` del widget con el
   `intent-filter APPWIDGET_UPDATE` y el `meta-data` que apunta al XML `appwidget-provider`; el XML
   existe en `res/xml/`. *(Inspección del manifest y del recurso.)*
10. **Catálogo de versiones:** Glance (`androidx.glance:glance-appwidget`) y WorkManager
    (`androidx.work:work-runtime-ktx`) están en `gradle/libs.versions.toml` con `version.ref`, no
    hardcodeados. *(Revisión del catálogo y del módulo.)*
11. **Build:** compila con `./gradlew :app:assembleDebug` usando el wrapper.
12. **Documentación:** se añade `tutorial/05-widget.md` (español) cubriendo los conceptos nuevos con
    referencia al código real.

---

## Technical Approach (alto nivel)

> Estrategia orientativa; el diseño de detalle es responsabilidad de la fase de implementación
> (`mobile-engineer`). Se listan aquí las decisiones que afectan al alcance y a la reutilización.

- **Ubicación del código:** un nuevo paquete `ui/widget/` bajo `com.neverlate` (p. ej.
  `PendingTasksWidget` (`GlanceAppWidget`), `PendingTasksWidgetReceiver`
  (`GlanceAppWidgetReceiver`), la función de mapeo tareas→UI del widget, y el `CoroutineWorker` de
  refresco). El recurso `res/xml/pending_tasks_widget_info.xml` (`appwidget-provider`) y las cadenas
  en `res/values/strings.xml`.
- **Acceso a datos (reutilización, clave):** el widget **no** recibe el repositorio por DI desde
  `MainActivity` (vive en otro contexto). En su lugar, dentro de `provideGlance(...)` obtiene la
  instancia singleton con `NeverLateDatabase.getInstance(context)` y construye
  `RoomTaskRepository(database.taskDao())`, exactamente el mismo tipo que usa la app. Lee un
  **snapshot** con `repository.observeTasks().first()` (no una suscripción viva): un widget se
  **dibuja bajo demanda**, no observa continuamente.
- **Cálculo y formato de tiempo (reutilización):** por cada tarea pendiente se calcula
  `computeRemainingMillis(task, System.currentTimeMillis())` y se formatea con `formatRemaining(...)`
  de `data/tasks/TaskTiming.kt`. **No se duplica** la lógica de tiempo.
- **Definición de "pendiente":** decisión de alcance a confirmar — lo más simple y coherente con la
  feature 04 es tratar como pendientes **todas** las tareas (que aún no han agotado su tiempo), u
  opcionalmente filtrar/ordenar por urgencia (menor tiempo restante primero) y ocultar las agotadas.
  El orden y el filtro viven en la **función pura de mapeo** (testeable en JVM), no en el composable.
- **Composición Glance:** `provideContent { ... }` con un `Column`/`LazyColumn` de Glance que pinta
  el estado vacío o la lista de filas (título + restante). Estilo mínimo (Material de Glance / colores
  del tema); **sin** intentar replicar toda la UI de la app.
- **Actualización dirigida por cambios:** invocar `PendingTasksWidget().updateAll(context)` tras las
  escrituras del repositorio (crear/editar/borrar/iniciar/pausar). Opciones de implementación a
  decidir por `mobile-engineer`: (a) llamar a `updateAll` desde un `CoroutineWorker` encolado por el
  `TasksViewModel`/`TaskEditViewModel` tras `saveTask`/`deleteTask`/`startTimer`/`pauseTimer`, o
  (b) envolver/decorar el `TaskRepository` para disparar el refresco. Preferible mantener el
  repositorio de la feature 04 intacto y disparar el refresco desde la capa que ya conoce el `Context`.
- **Refresco periódico:** un `PeriodicWorkRequest` (WorkManager) que llama a `updateAll(...)`, o el
  `updatePeriodMillis` del `appwidget-provider`. WorkManager es lo que enseña la lección (control
  explícito, constraints); documentar el suelo de ~15 min.
- **Tap para abrir la app:** un `PendingIntent` a `MainActivity` (con extra/deep-link a la ruta
  `tasks`), usando `actionStartActivity`/`FLAG_IMMUTABLE`.
- **Manifest:** añadir el `<receiver>` del `GlanceAppWidgetReceiver` (`android:exported="true"` con
  el `intent-filter android.appwidget.action.APPWIDGET_UPDATE` y el `meta-data
  android.appwidget.provider` apuntando al XML). WorkManager registra su propio provider por defecto;
  documentar si hace falta alguna inicialización.

---

## Out of Scope

Esta feature **no** incluye:

- **Tareas en la pantalla de bloqueo / notificación persistente / foreground service** → **feature
  06** (`docs/prompts/06-tareas-lockscreen.md`). El widget vive en la pantalla de inicio, no en la de
  bloqueo.
- **Cuenta atrás animada segundo a segundo en el widget.** Los widgets se refrescan con la
  granularidad que permite el sistema (minutos, no segundos); el widget muestra el restante del
  último refresco. Una animación fina es explícitamente no-objetivo.
- **Crear / editar / borrar / iniciar / pausar tareas desde el propio widget.** El widget es de
  **solo lectura**; la interacción es tocar para abrir la app. Acciones directas desde el widget
  (botones de play/pause) quedan fuera.
- **Datos remotos / sincronización / backend** → **feature 11** (`docs/prompts/11-bbdd-remota.md`).
  Todo es local.
- **Recordatorios / alarmas al agotarse el tiempo o llegar la fecha** → **feature 09**
  (`docs/prompts/09-recordatorios.md`).
- **Theming avanzado / modo oscuro configurable del widget** (más allá de respetar el tema del
  sistema de forma básica) → relacionado con **feature 07** (ajustes/tema). Sin selector de tamaños
  configurable complejo ni personalización por el usuario.
- **Configuración del widget** (pantalla de configuración al añadirlo, `configure` activity) — no se
  incluye; el widget funciona sin configuración.
- **Widgets adicionales** (p. ej. widget de una sola tarea, o de artículos): esta feature entrega
  **un** widget de tareas pendientes.

---

## Dependencies

- **Nuevas dependencias (a declarar en `gradle/libs.versions.toml`, con `version.ref`):**
  - **Glance para App Widgets** — `androidx.glance:glance-appwidget` (arrastra `androidx.glance:glance`).
    Es la API declarativa para construir el widget. **Consideración de API level:** `glance-appwidget`
    requiere **minSdk 23** como mínimo; el proyecto tiene **`minSdk = 24`**, así que es compatible sin
    ajustes. Fijar una versión estable de Glance compatible con el Compose/Kotlin `2.1.0` del catálogo
    (a validar en implementación; parar y reportar si hay conflicto de versiones, según Execution
    Policy).
  - **WorkManager** — `androidx.work:work-runtime-ktx` para el refresco periódico y/o el disparo de
    `updateAll(...)` en segundo plano con `CoroutineWorker`.
  - Ambas con su entrada en `[versions]` y `[libraries]`; **nada hardcodeado** en `build.gradle.kts`.
- **Capa de datos existente (dependencia dura de esta feature):** requiere la feature 04 ya en
  `master`: `NeverLateDatabase` (+ `getInstance`), `TaskDao`, `TaskRepository`/`RoomTaskRepository`,
  la entidad `Task` y las funciones de `TaskTiming.kt` (`computeRemainingMillis`, `formatRemaining`).
  El widget **depende de la interfaz `TaskRepository` y del singleton de la base de datos**; no
  reimplementa acceso a datos.
- **Navegación existente:** el `PendingIntent` abre `MainActivity` en la ruta `tasks` del
  `AppNavHost`; puede requerir soportar un **extra/deep-link** inicial en `MainActivity`/`AppNavHost`
  para arrancar directamente en tareas (cambio pequeño y acotado).
- **Manifest:** cambios necesarios (nuevo `<receiver>` + `meta-data` + recurso `res/xml`). Sin
  permisos nuevos: el widget **no** requiere `INTERNET` ni `POST_NOTIFICATIONS`. WorkManager no exige
  permisos para este uso.
- **Tooling/build:** debe compilar con `./gradlew :app:assembleDebug` (wrapper). Si falta algún
  paquete del SDK o hay incompatibilidad de versión de Glance, **parar y reportar** (Execution
  Policy), proponiendo la corrección.

---

## Deliverables / Documentation (obligatorio antes de commitear)

- **Lección tutorial (Tutorial Methodology):** `tutorial/05-widget.md` (español), progresiva sobre
  01–04. Debe cubrir: qué es un App Widget y cómo Glance lo construye; `GlanceAppWidget` +
  `GlanceAppWidgetReceiver`; cómo el widget lee la base de datos reutilizando
  `NeverLateDatabase.getInstance` + `RoomTaskRepository` + `.first()`; el cálculo del restante con las
  funciones puras de la feature 04; WorkManager + `updateAll(...)` (periódico y por cambios); y el
  ciclo de vida/limitaciones (suelo de frecuencia, solo lectura, proceso ajeno, `PendingIntent`).
- **Manifest:** declarar el `<receiver>` del `GlanceAppWidgetReceiver` (exportado, con
  `intent-filter APPWIDGET_UPDATE` y `meta-data android.appwidget.provider`).
- **Recurso `appwidget-provider`:** nuevo `res/xml/pending_tasks_widget_info.xml` con
  `minWidth`/`minHeight`, `targetCellWidth`/`targetCellHeight`, `updatePeriodMillis`,
  `previewImage`/`description`, `resizeMode` y `widgetCategory=home_screen`.
- **Strings:** cadenas del widget (nombre, estado vacío, etiquetas) en `res/values/strings.xml` (el
  código no lleva texto visible hardcodeado).
- **Catálogo de versiones:** entradas de Glance y WorkManager en `gradle/libs.versions.toml`.
- **`CLAUDE.md`:** actualizar la sección **Structure** (nuevo paquete `ui/widget/` y el recurso
  `res/xml`) y, si se toca, la nota de manifest. No hay permisos nuevos que reflejar.

---

## Testing notes

> El énfasis, siguiendo el patrón de la feature 04, es **mover toda la lógica testeable a funciones
> puras JVM** y dejar el `GlanceAppWidget` (la UI) lo más fino posible, porque la UI de Glance es
> **difícil de testear en unidad** (renderiza a `RemoteViews`, no hay un `ComposeTestRule` estándar
> equivalente y requiere instrumentación/host del launcher).

- **Tests unitarios JVM (`app/src/test/.../ui/widget/`), sin emulador — el grueso:**
  - **Función de mapeo del widget** (`tasks: List<Task>`, `now: Long`) → modelo de UI del widget
    (lista de filas con título + restante formateado, o estado vacío). Casos: lista vacía → estado
    vacío; una/varias tareas → filas con el restante correcto; tarea agotada → `00:00`/etiqueta, sin
    negativos; orden/filtro de "pendiente" si se implementa (p. ej. más urgente primero, agotadas al
    final u ocultas); truncado a N si se aplica. Esta función es el equivalente al `onTasksTick` del
    `TasksViewModel`, aislada para el widget.
  - Se **reutilizan** los tests existentes de `TaskTiming` (`computeRemainingMillis`,
    `formatRemaining`); no hace falta reimplementarlos, pero la función de mapeo debe apoyarse en
    ellos para que el restante coincida con la app.
- **Lo que NO se testea en unidad (documentarlo):** el render de Glance, el `GlanceAppWidgetReceiver`,
  el `PendingIntent` y el disparo real de `updateAll(...)` se verifican **manualmente en el emulador**
  (colocar el widget, crear/editar/borrar una tarea y ver el refresco, tocar para abrir la app).
  Opcionalmente, un `CoroutineWorker` que encapsule el refresco puede tener un test de instrumentación
  ligero con `WorkManagerTestInitHelper`, pero no es bloqueante.
- **Comandos:**

```bash
# Tests unitarios (JVM, sin emulador)
./gradlew :app:testDebugUnitTest

# Build del APK de depuración (verificación de que Glance/WorkManager compilan)
./gradlew :app:assembleDebug
```

---

## Risks

- **Reutilización del repositorio desde otro contexto:** el widget corre en el proceso del app (a
  través del receiver), no en `MainActivity`, y debe obtener el singleton de Room por su cuenta
  (`getInstance(context)`), no por la DI manual de `MainActivity`. *Mitigación:* usar el `Context` del
  widget para `getInstance` y construir `RoomTaskRepository` allí; leer con `.first()` (snapshot), no
  suscripción viva.
- **Expectativa de "cuenta atrás en vivo":** el usuario podría esperar que el widget cuente segundo a
  segundo como la app. Los widgets **no** lo permiten (suelo de refresco en minutos). *Mitigación:*
  documentarlo en la lección y en los criterios (US-5); mostrar el restante del último refresco y
  refrescar por cambios para que los momentos importantes (iniciar/pausar) se reflejen al instante.
- **Compatibilidad de versiones Glance ↔ Compose/Kotlin 2.1.0:** una versión de Glance incompatible
  puede romper el build. *Mitigación:* fijar en el catálogo una versión estable compatible y verificar
  `assembleDebug` antes de continuar; parar y reportar si hay conflicto (Execution Policy).
- **Coste de batería del refresco:** un refresco demasiado frecuente o un `CoroutineWorker` mal
  acotado malgasta batería. *Mitigación:* respetar el suelo del sistema, usar `PeriodicWorkRequest`
  con constraints razonables y `updateAll` puntual solo en cambios reales.
- **Sobrecarga didáctica:** la feature junta Glance + WorkManager + `PendingIntent` + ciclo de vida de
  widgets. *Mitigación:* la lección 05 los presenta de forma progresiva apoyándose en 01–04; mantener
  el `GlanceAppWidget` mínimo y toda la lógica en la función pura de mapeo.
- **Deep-link a la ruta `tasks`:** abrir `MainActivity` directamente en tareas puede requerir tocar
  `AppNavHost`/`MainActivity`. *Mitigación:* mantener el cambio acotado (un extra/`startDestination`
  condicional) y no alterar la navegación existente de artículos/onboarding.
- **Cobertura de test limitada de la UI del widget:** al no poder testear Glance en unidad, hay riesgo
  de regresiones visuales no cubiertas. *Mitigación:* concentrar la lógica en la función de mapeo
  (bien testeada) y dejar constancia del checklist de verificación manual en la lección.

---

## Siguiente paso

Por favor, **revisa y aprueba** esta especificación antes de comenzar la implementación. Una vez
aprobada, el flujo continúa (según el **Mandatory Workflow** de `CLAUDE.md`): crear la rama
`feature/tasks-widget`, añadir Glance y WorkManager al catálogo, implementar con `mobile-engineer`
(reutilizando `TaskRepository`/`NeverLateDatabase`/`TaskTiming` sin tocar el modelo), tests con
`qa-engineer` (función pura de mapeo del widget en JVM) y la lección `tutorial/05-widget.md` antes de
commitear.
