# Widget adaptable: barra de progreso por fila y acciones sin abrir la app

- **Fecha:** 2026-08-17
- **Rama sugerida:** `feature/widget-adaptive-layout`
- **Tutorial:** **Sí** (lección de la pista tutorial; ver *Tutorial* al final — el fichero
  `tutorial/NN-*.md` se escribe **después** de implementar, no ahora)
- **Agentes:** `android-engineer` (implementación **y** tests, en una sola pasada; el proyecto
  retiró el antiguo reparto `mobile-engineer` / `qa-engineer`)
- **Estado:** pendiente de aprobación del usuario

---

## Overview

El widget de pantalla de inicio (`PendingTasksWidget`, Glance) dibuja hoy **siempre el mismo
layout**, sin importar el tamaño que le dé el lanzador, y expone **un único destino táctil**: toques
donde toques, abre la app en la lista de tareas.

Esta feature hace dos cosas, ambas dependientes del tamaño:

1. **El widget responde a su tamaño.** Con `SizeMode.Responsive` y un conjunto pequeño de `DpSize`
   declarados, Glance pre-genera un `RemoteViews` por *bucket* y el lanzador elige el que encaja.
   El bucket **pequeño** dibuja exactamente las filas de hoy; el bucket **grande** añade barra de
   progreso por fila y muestra más filas.
2. **Cada fila del bucket grande se puede completar desde el widget**, sin abrir la app, mediante
   `actionRunCallback<CompleteTaskActionCallback>(parameters)`.

La barra de progreso es la misma idea que la de la tarjeta de tarea (feature 19): *tiempo
consumido*, calculada por la **misma** función pura `deadlineProgressFor(remainingMillis,
totalMillis, isTimedOut)`. No se duplica esa lógica; el fichero `DeadlineProgress.kt` se toca
**en modo lectura**.

Esta feature cierra las dos deudas escritas explícitamente en la fila *Home-screen widget visual
refresh* de `docs/mockups/README.md` (feature 05b): «per-row **progress bar** and per-row tap
targets → a future size-aware (`SizeMode.Responsive`) widget feature». Esa fila **se actualiza**,
no se crea una nueva.

---

## Goals

- El widget deja de ser una imagen fija: aprovecha el espacio que el usuario le da al
  redimensionarlo, y sigue siendo legible en su tamaño mínimo.
- Ver *cuánto tiempo llevas consumido* de una tarea de un vistazo, sin abrir la app.
- Completar una tarea desde la pantalla de inicio: cero fricción para el caso más común
  («ya está hecha»), que es exactamente donde el TDA/TDAH pierde tareas.
- Ni una línea de lógica duplicada: fracción de progreso, color de urgencia y texto de cuenta atrás
  vienen de las funciones que ya existen.
- Ninguna escritura desde el widget puede provocar un bucle de refresco de superficies.

**No-goals** medibles: ninguna dependencia nueva, ningún permiso nuevo, ninguna migración de Room,
ningún cambio en el contrato de API.

---

## User Stories

### US-1 — Widget que aprovecha el espacio

> Como usuaria que ha estirado el widget a media pantalla, quiero que muestre más información en
> lugar de dejar hueco vacío, para que el espacio que le he dado sirva de algo.

**Criterios de aceptación**

- El widget declara `SizeMode.Responsive` con un conjunto pequeño de tamaños (ver **D1**).
- En el bucket **pequeño**, el contenido es el de hoy: cabecera + filas con marcador de prioridad,
  título y cuenta atrás. Sin barra de progreso.
- En el bucket **grande**, cada fila añade la barra de progreso (cuando aplica, ver US-2) y se
  dibujan hasta 5 filas.
- Redimensionar el widget entre buckets no recorta ni desborda nada: ningún texto se pierde,
  ninguna barra empuja la cuenta atrás fuera de la fila.
- El estado vacío (`PendingTasksWidgetModel.Empty`) se ve igual en ambos buckets.

### US-2 — Barra de tiempo consumido por fila

> Como usuaria con una tarea de duración estimada, quiero ver cuánto llevo consumido de esa
> duración, para decidir si me da tiempo sin abrir la app.

**Criterios de aceptación**

- La fracción viene **exclusivamente** de `deadlineProgressFor(remainingMillis, totalMillis,
  isTimedOut)` — misma función que la tarjeta de tarea. `DeadlineProgress.kt` no se modifica.
- `isTimedOut` se deriva como en el resto del widget: `remainingMillis == 0L` (invariante de la
  feature 20b), vía `PendingTaskRow.urgencyLevel()`/la misma expresión.
- Si `deadlineProgressFor` devuelve `null` (tarea sin `estimatedDurationMillis` utilizable), la fila
  **no dibuja barra alguna** — ni una barra a 0, ni un placeholder.
- Se usa `androidx.glance.appwidget.LinearProgressIndicator(progress, modifier, color,
  backgroundColor)`; el color pedido es `urgencyColorProvider(level)` (`WidgetColors.kt`), el mismo
  proveedor que ya colorea la cuenta atrás.
- Una tarea agotada (`remainingMillis == 0L`) muestra la barra llena (`1f`), coherente con la
  tarjeta.

### US-3 — Completar desde el widget

> Como usuaria que acaba de terminar algo, quiero marcarlo como hecho tocando su fila en el widget,
> para no abrir la app solo para eso.

**Criterios de aceptación**

- En el bucket **grande**, cada fila es un destino táctil propio que ejecuta
  `actionRunCallback<CompleteTaskActionCallback>(actionParametersOf(taskIdKey to row.id))`.
- El callback (`suspend fun onAction(context, glanceId, parameters)`) lee la tarea por id, la guarda
  con `completedAt = System.currentTimeMillis()` y refresca las superficies **explícitamente**
  (ver **D4**).
- Tras completar, la fila desaparece del widget (la tarea deja de ser *pendiente* según
  `pendingRowsFor`) y la notificación de pantalla de bloqueo queda coherente.
- Un `taskId` que ya no existe (borrada entre el dibujo y el toque) no crashea: el callback
  no hace nada y refresca.
- El escrito pasa por el outbox y por la reprogramación de recordatorios, igual que un completado
  hecho desde la app (misma cadena de decoradores por debajo del `@ReminderRepo`).
- **Ninguna escritura desde el widget re-entra en el widget** (ver **D4** y su test obligatorio).
- En el bucket **pequeño** se conserva el comportamiento de hoy: todo el widget abre la app en la
  lista de tareas.
- En el bucket **grande**, tocar la **banda de cabecera** abre la app en la lista de tareas (el
  destino «abrir» no se pierde al ceder las filas a la acción de completar).

### US-4 — Accesibilidad de las nuevas acciones

> Como usuaria de TalkBack, quiero saber qué hace tocar una fila del widget, para no completar una
> tarea sin querer.

**Criterios de aceptación**

- En el bucket grande, la `contentDescription` de cada fila incluye la acción, no solo los datos:
  se mantiene el patrón actual (título, tiempo restante, prioridad en palabras) y se le añade el
  verbo — p. ej. `Completar: Enviar informe, 4 m, Prioridad: Alta`.
- La barra de progreso **no** añade un nodo anunciable propio (sería ruido: el tiempo restante ya se
  anuncia en texto); si Glance lo exige, se marca como decorativa.
- Cadenas nuevas en `values/strings.xml` (base español) **y** `values-en/strings.xml`.
- Destinos táctiles por fila ≥ 48dp de alto en el bucket grande.

---

## Acceptance Criteria (behavioural, consolidados)

| # | Criterio |
|---|---|
| AC-1 | `PendingTasksWidget` declara `override val sizeMode = SizeMode.Responsive(setOf(...))` con los tamaños de **D1**; `LocalSize.current` decide el layout dentro de la composición. |
| AC-2 | La fracción de la barra procede sólo de `deadlineProgressFor`; `DeadlineProgress.kt` no aparece en el diff salvo, como mucho, en KDoc. |
| AC-3 | `PendingTaskRow` gana `id: Long = 0L` y `totalMillis: Long? = null`, ambos con valor por defecto (**D2**). |
| AC-4 | El modelo de la notificación de pantalla de bloqueo **ignora** los dos campos nuevos; existe un test que lo fija por escrito. |
| AC-5 | El `ActionCallback` escribe a través de la capa `@ReminderRepo` obtenida de `WidgetEntryPoint`, nunca del binding sin cualificar (**D4**). |
| AC-6 | Existe un test que prueba que completar desde el widget **no** dispara `refreshSurfaces()` (ausencia de bucle), contando refrescos en un doble. |
| AC-7 | Tests JVM de `pendingRowsFor` cubren que `id` y `totalMillis` se propagan desde `Task` (incluyendo `estimatedDurationMillis == null`). |
| AC-8 | Tests JVM del recorte por bucket: el bucket pequeño dibuja como máximo N filas y el grande hasta `MAX_PENDING_ROWS` (**D3**), sin tocar `MAX_PENDING_ROWS`. |
| AC-9 | `timeout 600 ./gradlew :app:testDebugUnitTest --console=plain` en verde (lo ejecuta el orquestador, una sola vez, antes de commitear). |
| AC-10 | Sin nuevas entradas en `gradle/libs.versions.toml`, sin cambios en `AndroidManifest.xml` (el `ActionCallbackBroadcastReceiver` viene ya fusionado desde el manifiesto de `glance-appwidget`), sin cambios en `docs/api/contract.md`, sin cambio de versión de Room. |
| AC-11 | `docs/mockups/README.md`: la fila *Home-screen widget visual refresh* se **actualiza** marcando entregadas la barra de progreso y los destinos táctiles por fila. |

---

## Decisiones técnicas cerradas

Estas no quedan abiertas para la implementación: son parte de lo que se aprueba.

### D1 — Buckets de tamaño

Dos buckets, declarados así:

```kotlin
private val SMALL_WIDGET = DpSize(250.dp, 110.dp)   // el mínimo actual del appwidget-provider
private val LARGE_WIDGET = DpSize(250.dp, 220.dp)   // ~4x4 celdas

override val sizeMode = SizeMode.Responsive(setOf(SMALL_WIDGET, LARGE_WIDGET))
```

- **Por qué dos y no cuatro:** `SizeMode.Responsive` pre-genera **un `RemoteViews` completo por
  tamaño declarado** y los mete todos en la misma actualización; cada bucket extra es peso real en
  la transacción con el lanzador (y hay un límite duro de tamaño de `RemoteViews`). Dos buckets
  cubren la única distinción que esta feature necesita: *¿cabe una barra de progreso y filas de
  48dp, o no?*
- El ancho no diferencia buckets (ambos 250dp): la decisión es de **alto**. Se declara ancho igual
  a propósito para que quede claro que el eje relevante es vertical.
- `pending_tasks_widget_info.xml` **no cambia**: `minWidth`/`minHeight` siguen siendo 250×110dp, y
  el bucket pequeño está diseñado para caber ahí exactamente como hoy. `targetCellWidth/Height`
  (4×2) tampoco cambia — el widget sigue apareciendo con su tamaño inicial de siempre y es el
  usuario quien lo estira.

### D2 — `PendingTaskRow` gana **dos** campos, y este es el límite

`deadlineProgressFor` necesita `totalMillis`, y la acción por fila necesita el `id` de la tarea.
Ambos se añaden a `PendingTaskRow` con valor por defecto, igual que la feature 05b hizo con
`priority`:

```kotlin
data class PendingTaskRow(
    val title: String,
    val remainingMillis: Long,
    val priority: Priority = Priority.NONE,
    val id: Long = 0L,
    val totalMillis: Long? = null,
)
```

La notificación de pantalla de bloqueo **ignora los dos** — igual que ya ignora `priority` para su
propio uso. Leer un `data class` por nombre significa que un campo que un consumidor no lee no le
cuesta nada.

**La llamada explícita que pedía el encargo:** con esto van **tres** campos añadidos para un solo
consumidor (el widget). Eso es el techo. Se decide:

- **Ahora:** se mantiene el tipo compartido. Extraer una proyección hoy costaría un `map` extra,
  un tipo más y un rename en tests, a cambio de cero beneficio observable — y `id`/`totalMillis`
  son datos genuinamente *de la tarea*, no decoraciones de widget (la notificación podría leerlos
  mañana sin que suene raro).
- **Disparador escrito:** el **próximo** campo que sólo sirva al widget obliga a extraer una
  proyección `WidgetTaskRow` en `PendingTasksWidgetState.kt`, construida desde `PendingTaskRow`, y
  `PendingTaskRow` se queda como el mínimo común de las superficies pasivas. Esta regla se anota en
  el KDoc de `PendingTaskRow` para que la próxima persona no tenga que re-derivarla.

### D3 — Cuántas filas por bucket

`MAX_PENDING_ROWS` (5, en `PendingTaskRows.kt`) **no se toca**: es la regla compartida con la
notificación. El recorte por bucket es una decisión de *render*, no de dominio:

- Bucket pequeño: `rows.take(2)` — cabecera (~40dp) + 2 filas compactas dentro de 110dp.
- Bucket grande: `rows` completas (hasta 5), cada una con alto mínimo 48dp.

La función que decide el recorte es pura y vive junto a `toWidgetModel` (por ejemplo
`rowsForSize(model, isLarge)`), para que sea testeable en JVM sin host de widget — mismo criterio
que ya sigue `toWidgetModel`.

### D4 — Reentrada: por qué capa escribe la acción (el riesgo real de esta feature)

El binding sin cualificar de `TaskRepository` es `TaskSurfacesRefreshingRepository`, cuyo
`refreshSurfaces()` llama a `PendingTasksWidget().updateAll(context)`. Si la acción de fila
escribiera por ahí, la cadena sería: *write → `refreshSurfaces()` → `updateAll` → `provideGlance`*,
y cualquier escritura futura desde el widget se convierte en un bucle.

**Decisión:** el `ActionCallback` obtiene el repositorio del **`WidgetEntryPoint` ya existente**
(`@ReminderRepo`), que es exactamente la capa que ese entry point expone y por la razón que su
KDoc ya documenta (`docs/specs/2026-08-17-widget-hilt-color-token.md`, D2). Es decir: **no hace
falta ampliar `WidgetEntryPoint` ni tocar `RepositoryModule`**. Esta feature es el consumidor que
aquella decisión anticipó.

Consecuencia asumida: escribir por `@ReminderRepo` **no** refresca superficies solo. El callback lo
hace **explícitamente y una vez**, que es lo correcto (el que escribe decide si redibuja, no el
decorador):

```kotlin
PendingTasksWidget().update(context, glanceId)
TasksNotificationService.refresh(context)
```

Nota: se usa `update(context, glanceId)` — el `glanceId` que el propio callback recibe — en lugar de
`updateAll`, porque la instancia que hay que redibujar es precisamente la que recibió el toque.

**Test obligatorio (AC-6):** un test JVM con un doble de repositorio que cuenta invocaciones,
probando que completar por el camino del callback produce exactamente **un** `saveTask` y **cero**
llamadas a `refreshSurfaces()` de `TaskSurfacesRefreshingRepository`; más un test que fija por
escrito que la instancia devuelta por `WidgetEntryPoint.taskRepository()` **no** es un
`TaskSurfacesRefreshingRepository`.

### D5 — Límite de plataforma en el tinte de la barra (API 24–30)

El *translator* de Glance sólo llama a `setProgressBarProgressTintList` cuando
`Build.VERSION.SDK_INT >= 31`. Con `minSdk = 24`, en API 24–30 la barra se dibuja en el **color de
acento del sistema**, no en el color de urgencia.

**Decisión — se diseña alrededor del límite, no se trabaja alrededor de él:**

- **No** se esconde la barra con un `if (SDK_INT >= 31)`: una barra con el color equivocado sigue
  comunicando su información principal (*cuánto llevas consumido*), que es geométrica, no cromática.
- **No** se finge que el color llega.
- En API 24–30, el **canal de peso tipográfico** que introdujo la lección 05b (negrita en
  `Urgent`/`Overdue`) pasa a ser el **portador principal** de la señal de urgencia, no un refuerzo.
  Esto se declara como criterio visual aceptado, no como bug conocido.

### D6 — Destinos táctiles y riesgo de toque accidental

- El toque por fila existe **sólo en el bucket grande**, donde se puede garantizar ≥ 48dp de alto
  por fila. En el bucket pequeño las filas son demasiado compactas para ser destinos seguros, así
  que ahí se conserva el destino único de hoy (abrir la app). Esto es deliberado: es preferible no
  ofrecer la acción a ofrecerla en un destino de 30dp junto a otros cuatro.
- Un toque completa **directamente**, sin confirmación (una confirmación en un widget significa
  abrir la app, que es justo lo que la feature evita). El deshacer vive en la app: la tarea
  completada sigue existiendo y se puede reabrir desde la lista. Riesgo R-3 lo recoge.

---

## Visual & UX Design

### Slice del mockup maestro

`docs/mockups/rediseno-ux-ui.html` es **sólo móvil**: no tiene marco de widget, ni contexto de
lanzador, ni selector. Como en la feature 05b, **esta feature no reclama un slice del mockup**;
traduce la *intención* de la tarjeta de tarea (barra de tiempo transcurrido teñida por urgencia,
jerarquía tipográfica, prioridad legible sin color) a lo que Glance sabe expresar. No se copia
HTML/CSS.

### Tracking

`docs/mockups/README.md` ya tiene la fila **«Home-screen widget visual refresh»** (feature 05b),
que lista la barra de progreso por fila y los destinos táctiles por fila como **diferidos a esta
feature**. Esta feature **actualiza esa fila existente** con un bloque `**Update (feature
widget-adaptive-layout, 2026-08-17):**` — no crea una fila nueva.

Debe registrar: entregado el layout por tamaño (`SizeMode.Responsive`, dos buckets), la barra de
progreso por fila en el bucket grande (`deadlineProgressFor` + `urgencyColorProvider`) y el
completado por fila; y **explícitamente diferido**:

- **Tinte de la barra en API 24–30** (límite de plataforma, D5) — no es una deuda que se pueda
  «pagar»; se documenta como comportamiento degradado aceptado.
- **Material You / `ThemeMode` de la app dentro del widget** — sigue diferido desde 05b, esta
  feature no lo toca.
- **Prioridad en stats/notificación**: ya cerrado por `priority-sorting`; sin cambios aquí.
- **Toques por fila en el bucket pequeño** — diferido a una futura revisión que replantee el
  `minHeight` del widget (hoy 110dp), fuera de alcance.

### Tokens y componentes reutilizados

| Necesidad | De dónde sale (no se inventa) |
|---|---|
| Fracción de progreso | `domain/tasks/DeadlineProgress.kt` → `deadlineProgressFor` (sólo lectura) |
| Color de la barra y de la cuenta atrás | `ui/widget/WidgetColors.kt` → `urgencyColorProvider(level)` (resolutor fino sobre `domain/tasks/ColorRole.kt`) |
| Fondo de la barra | `GlanceTheme.colors.surfaceVariant` |
| Texto de cuenta atrás | `ui/components/RemainingTimeLabel.kt` → `formatRemainingLabel(context, millis)` |
| Marcador de prioridad (`!`/`!!`/`!!!`) y su color | `ui/tasks/PriorityUi.kt` → `markerRes()` + `Priority.glanceIndicatorColor()` |
| Nivel de urgencia | `PendingTaskRow.urgencyLevel()` → `urgencyLevelFor` |
| Esquema de color claro/oscuro | `GlanceTheme(colors = ColorProviders(light = LightColorScheme, dark = DarkColorScheme))`, ya en su sitio |
| Esquinas redondeadas | drawables + `ColorFilter.tint` (nunca `GlanceModifier.cornerRadius`, API 31+) |

### Visual acceptance criteria

| # | Criterio visual |
|---|---|
| V-1 | La barra de progreso aparece **sólo** en el bucket grande **y sólo** cuando la tarea tiene duración estimada utilizable (`deadlineProgressFor != null`). |
| V-2 | Al redimensionar el widget entre buckets no se recorta nada: ni títulos (siguen a `maxLines = 1` con elipsis), ni cuenta atrás, ni barra. La barra nunca empuja la cuenta atrás fuera de la fila. |
| V-3 | Cada fila sigue mostrando marcador de prioridad + título + cuenta atrás **en una sola línea**; la barra va **debajo** de esa línea, a lo ancho de la fila, no dentro de ella. |
| V-4 | Alto de la barra 4dp, con esquinas del propio `LinearProgressIndicator`; separación 4dp respecto a la línea de texto. |
| V-5 | En el bucket grande, cada fila táctil mide **≥ 48dp de alto** (criterio nuevo: hoy sólo hay un destino, el widget entero, y filas apretadas lo violarían con facilidad). |
| V-6 | Contraste suficiente en claro y en oscuro para los cuatro niveles de urgencia sobre `surfaceVariant`; verificado en ambos temas. |
| V-7 | **API 24–30 (aceptado, D5):** la barra se dibuja en el color de acento del sistema y **no** en el color de urgencia. En esas versiones el color de la cuenta atrás **y su negrita** (05b) son el portador de la señal de urgencia; se verifica que un `Urgent`/`Overdue` sigue siendo distinguible de un `Calm` sin depender del color de la barra. |
| V-8 | Los divisores hairline entre filas, la banda de cabecera con `primaryContainer` y el estado vacío se conservan idénticos en ambos buckets. |
| V-9 | El layout aguanta la escala de fuente más grande del sistema: con texto grande, las filas del bucket grande crecen y el número de filas visibles baja, pero nada se solapa ni se recorta. |
| V-10 | Una tarea con el tiempo agotado muestra la barra **llena** y la cuenta atrás en el texto de «tiempo agotado» ya existente — coherente con la tarjeta. |

### Design review (paso 7 del workflow)

Verificación en la app real (`/run`) sobre un widget colocado en el lanzador: colocar en 4×2
(pequeño), estirar a 4×4 (grande), completar una fila, y repetir en tema oscuro. La comprobación
final en dispositivo la hace el usuario; el agente no lanza emulador por su cuenta.

---

## Technical Approach

1. **`domain/tasks/PendingTaskRows.kt`** — añadir `id` y `totalMillis` a `PendingTaskRow` (con
   defaults) y propagarlos desde `Task` en `pendingRowsFor`. Anotar en el KDoc la regla de D2
   (próximo campo widget-only ⇒ proyección aparte). Sin cambios de comportamiento para la
   notificación.
2. **`ui/widget/PendingTasksWidgetState.kt`** — añadir la función pura de recorte por bucket
   (D3), junto a `toWidgetModel`, sin imports de Glance/Android.
3. **`ui/widget/PendingTasksWidget.kt`** — `sizeMode = SizeMode.Responsive(...)`; leer
   `LocalSize.current` dentro del contenido para elegir bucket; fila grande = `Column` con la línea
   de texto actual + `LinearProgressIndicator`; `clickable(actionRunCallback<…>(…))` por fila en el
   bucket grande y `clickable(openTasks)` en la banda de cabecera; el bucket pequeño conserva el
   `clickable(openTasks)` sobre el `Column` raíz.
4. **`ui/widget/CompleteTaskActionCallback.kt`** (nuevo) — `ActionCallback` con
   `ActionParameters.Key<Long>` para el id; resuelve `WidgetEntryPoint` con
   `EntryPointAccessors.fromApplication`; lee la tarea (`observeTask(id).first()`), guarda con
   `completedAt`, y refresca widget + notificación explícitamente (D4).
5. **Strings** — nuevas cadenas de acción/`contentDescription` en `values/` (base español) y
   `values-en/`.
6. **Tests (`android-engineer`, misma pasada)** — JVM: propagación de campos en `pendingRowsFor`,
   recorte por bucket, contrato de `deadlineProgressFor` aplicado a filas (incluido `null`),
   no-reentrada (AC-6) y capa del entry point (AC-5), notificación indiferente a los campos nuevos
   (AC-4). Instrumentado, sólo si aporta algo que el JVM no puede probar: composición del bucket
   grande vía el host de test de Glance.

Sin tocar: `DeadlineProgress.kt` (lectura), `RepositoryModule.kt`, `WidgetEntryPoint.kt`,
`pending_tasks_widget_info.xml`, `AndroidManifest.xml`, `libs.versions.toml`, backend y contrato.

---

## Out of Scope

Declarado explícitamente para que no se cuele:

- **Cambiar la cadencia de refresco.** `TaskSurfacesRefreshWorker` sigue a ~15 min, con su lag
  conocido. Esta feature no lo toca.
- **Actividad de configuración del widget** (elegir cuántas filas, qué filtro, etc.).
- **Widgets adicionales** (ni de estadísticas, ni de una sola tarea, ni en pantalla de bloqueo).
- **Migrar el widget a Hilt** — ya está hecho (`di/WidgetEntryPoint.kt`,
  `docs/specs/2026-08-17-widget-hilt-color-token.md`); esta feature **consume** ese trabajo y no lo
  amplía (no añade accesores nuevos al entry point).
- **Material You / dinámico** y el `ThemeMode` de la app dentro del widget: siguen diferidos desde
  05b.
- **Deshacer / confirmar** el completado dentro del widget.
- **Otras acciones por fila** (empezar/pausar temporizador, posponer, borrar).
- **Barra de progreso en la notificación de pantalla de bloqueo.**
- Sin cambios de backend, sin cambios en `docs/api/contract.md`, sin migración de Room, sin
  dependencia nueva, sin permiso nuevo (el `ActionCallbackBroadcastReceiver` viene dentro de
  `glance-appwidget` y se fusiona desde su propio manifiesto).

---

## Dependencies

- **Ninguna dependencia nueva.** Glance 1.1.1 (ya en el catálogo) aporta todo:
  `androidx.glance.appwidget.LinearProgressIndicator`,
  `androidx.glance.appwidget.SizeMode.Responsive`, `androidx.glance.LocalSize`,
  `androidx.glance.appwidget.action.actionRunCallback` + `ActionCallback`.
- **Ya existente y requerido:** `di/WidgetEntryPoint.kt` con el binding `@ReminderRepo` (feature
  `widget-hilt-color-token`, mergeada el 2026-08-17). Sin él, esta feature tendría que resolver el
  acceso al grafo *y* la reentrada a la vez.
- `domain/tasks/DeadlineProgress.kt` (feature 19), `ui/components/RemainingTimeLabel.kt`,
  `ui/widget/WidgetColors.kt`, `ui/tasks/PriorityUi.kt`: todos presentes.
- Verificación visual final en dispositivo/emulador: la hace el **usuario**.

---

## Risks

| # | Riesgo | Mitigación |
|---|---|---|
| R-1 | **Reentrada del widget** al escribir desde el callback (write → `refreshSurfaces()` → `updateAll` → `provideGlance` → …). Es el riesgo principal de la feature. | D4: se escribe por `@ReminderRepo`; refresco explícito y acotado; AC-6 lo prueba con un doble que cuenta refrescos. |
| R-2 | **La barra sin tinte en API 24–30** puede leerse como bug en una review. | D5 + V-7: se declara como comportamiento aceptado en el spec y en el tracking del mockup, con el canal de negrita como portador de urgencia en esas versiones. |
| R-3 | **Toque accidental** que completa una tarea sin querer desde la pantalla de inicio. | Sólo en el bucket grande, con filas ≥ 48dp (D6/V-5); `contentDescription` con verbo (US-4); la tarea completada se puede reabrir desde la app. Si en uso real resulta molesto, la respuesta correcta es una feature de *deshacer*, no una confirmación dentro del widget. |
| R-4 | **Peso del `RemoteViews`**: `Responsive` genera un árbol por bucket; con filas más ricas podría acercarse al límite de la transacción. | Sólo dos buckets (D1) y máximo 5 filas (`MAX_PENDING_ROWS` intacto). Si apareciera el límite, se reduce el bucket pequeño antes que añadir buckets. |
| R-5 | **Lag del refresco**: tras completar en la app, el widget puede tardar (worker a ~15 min) — no es nuevo, pero completar *desde* el widget lo hace más visible por contraste. | El callback refresca inmediatamente su propia instancia; el lag general queda fuera de alcance, ya documentado. |
| R-6 | **`PendingTaskRow` se convierte en un cajón de sastre** del widget. | D2 fija el techo y escribe el disparador en el KDoc: el próximo campo widget-only obliga a extraer `WidgetTaskRow`. |
| R-7 | **Escala de fuente grande** puede dejar el bucket grande con filas que no caben. | V-9 lo convierte en criterio verificable; el recorte por bucket (D3) es una función pura ajustable sin tocar el resto. |

**Supuestos declarados** (marcarlos como tales, no como hechos): (a) el bucket pequeño de 250×110dp
sigue cabiendo con 2 filas + cabecera tras añadir nada nuevo a esa variante; (b) el host de test de
Glance permite componer un bucket concreto en test instrumentado — si no, la cobertura de layout se
queda en las funciones puras (D3) más la revisión visual, y eso es aceptable.

---

## Tutorial

**Tutorial: Sí** — decidido con el usuario vía `AskUserQuestion` antes de escribir este spec, según
*Tutorial Track (optional)* de `CLAUDE.md`.

La lección (española, `tutorial/NN-*.md`, **numeración con sufijo de letra si toca — nunca
renumerar una lección publicada**) se escribe **después de implementar**, en el paso 8 del workflow.
Debe cubrir:

1. **`SizeMode.Responsive` + `LocalSize`**, y por qué esto **no** es el `BoxWithConstraints` de
   Compose: Glance **pre-genera un `RemoteViews` por bucket declarado** y el lanzador elige; no hay
   una composición medida en vivo. De ahí que los tamaños se declaren y que su número importe.
2. **`actionRunCallback` / `ActionCallback`**: cómo un widget ejecuta trabajo real (una escritura en
   Room) desde el proceso del lanzador, por qué la firma es `suspend` y por qué recibe un
   `GlanceId` (identifica *qué instancia* del widget hay que redibujar).
3. **El límite de tinte de la plataforma (`setProgressBarProgressTintList` sólo en API ≥ 31)** como
   ejemplo de **diseñar alrededor de una restricción** en lugar de esquivarla con un `if (SDK_INT)`.
4. **El riesgo de reentrada en la cadena de decoradores del repositorio**: cómo detectarlo, por qué
   el cualificador `@ReminderRepo` es la respuesta, y por qué «el que escribe decide si redibuja».

---

## Aprobación

Este spec **necesita aprobación explícita del usuario** antes de crear la rama
`feature/widget-adaptive-layout` e implementar. La aprobación cubre **comportamiento, aspecto visual
y la decisión de tutorial** (las tres cosas se firman juntas).
