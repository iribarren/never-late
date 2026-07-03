# Especificación — Tareas en la pantalla de bloqueo

- **Fecha:** 2026-07-02
- **Feature:** Feature 06 — Tareas en la pantalla de bloqueo (Tasks on the lock screen)
- **Prompt origen:** `docs/prompts/06-tareas-lockscreen.md`
- **Rama sugerida:** `feature/tasks-lockscreen`
- **Lección tutorial asociada:** `tutorial/06-lockscreen.md` (español)
- **Estado:** **Aprobada** (2026-07-02)

## Decisiones aprobadas (2026-07-02)

Las tres decisiones de diseño quedan resueltas así (ver sección **Decisiones de diseño** para el detalle):

- **D1 — Cuándo se muestra: Opción A.** La notificación se muestra **siempre que haya tareas
  pendientes** (haya o no un contador corriendo).
- **D2 — Foreground service: Opción B (SÍ).** La notificación continua la **hospeda un foreground
  service** con `startForeground`. El servicio se enseña como concepto de primera clase (ciclo de
  vida, `foregroundServiceType` en API 34+). *Combinado con D1:* el servicio vive **mientras existan
  tareas pendientes** y se detiene (`stopSelf`) cuando no queda ninguna.
- **D3 — Visibilidad: `VISIBILITY_PUBLIC` por ahora.** Los títulos se muestran en el lockscreen. Se
  deja **`setPublicVersion` redactada como opción documentada pero no activada**. Hacer la visibilidad
  (y un interruptor de activar/desactivar la notificación) **configurable por la persona usuaria** se
  pospone a una **feature futura de ajustes (feature 07)** — ver Out of Scope.

---

## Overview

Esta feature permite a la persona usuaria **consultar sus tareas pendientes y el tiempo que le
queda directamente desde la pantalla de bloqueo**, sin desbloquear el teléfono. El vehículo
recomendado es una **notificación continua/persistente y actualizable** (`ongoing`) que el sistema
muestra también en el *lockscreen*, listando las tareas más urgentes con su cuenta atrás.

El objetivo de producto es el mismo que motiva todo el proyecto —atacar la **ceguera temporal**
(*time blindness*) de las personas con TDA/TDAH— pero llevándolo al lugar donde el teléfono se
consulta decenas de veces al día **sin llegar a abrirse**: la pantalla de bloqueo. Mientras el
widget de la feature 05 es una señal ambiental en la pantalla de inicio, la notificación de esta
feature es una señal que sigue visible cuando el teléfono está bloqueado sobre la mesa. Ambas son
pasivas y de solo lectura; ninguna exige el paso deliberado de abrir la app.

La restricción de arquitectura central es idéntica a la del widget: esta feature **reutiliza la capa
de datos existente** de la feature 04 y **no** crea una segunda fuente de verdad. Lee las tareas a
través de la **misma interfaz `TaskRepository`** (su KDoc ya anticipa explícitamente que la feature
06 dependerá de este contrato para leer tareas sin tocar la UI de la feature 04), obtiene el
singleton de Room con `NeverLateDatabase.getInstance(context)` y calcula/formatea el tiempo restante
con las **mismas funciones puras** de `TaskTiming.kt` (`computeRemainingMillis`, `formatRemaining`).
La lógica de "qué mostrar" vive en una **función pura de mapeo** testeable en JVM, siguiendo el
patrón que la feature 05 estableció en `PendingTasksWidgetState.kt` (`toWidgetModel`); la carcasa
Android (canal, notificación, permiso) se mantiene lo más fina posible.

Todo sigue siendo **local-only** (sin backend, sin permiso `INTERNET`). La notificación se construye
y refresca en el propio dispositivo.

### Definición de "pendiente" (coherente con el widget)

Para no divergir de la feature 05, "pendiente" significa lo mismo que en el widget: **cuentan todas
las tareas**, incluidas las que ya han agotado su tiempo (restante `0`), **ordenadas de más urgente
a menos** (menor tiempo restante primero) y **limitadas a un número máximo** de filas visibles. El
razonamiento del widget aplica igual aquí: ocultar una tarea agotada podría esconder justo lo que la
persona necesita ver. Esta regla vive en la función pura de mapeo, no en el código de notificación.

### Conceptos nuevos que enseña (para `tutorial/06-lockscreen.md`)

Partiendo de las lecciones 01–05 (Compose, `ViewModel`/`StateFlow`, DI manual, repositorio tras
interfaz, Room + `Flow`, reloj de pared, WorkManager, decorador que refresca superficies fuera de la
`Activity`):

- **Notificaciones en Android:** qué es una notificación, `NotificationManager` /
  `NotificationManagerCompat`, y la construcción con **`NotificationCompat.Builder`** (la variante de
  compatibilidad que degrada con elegancia en versiones antiguas).
- **Canales de notificación (`NotificationChannel`, API 26+):** por qué a partir de Android 8 toda
  notificación necesita un canal, cómo crearlo de forma idempotente y guardado tras un
  `Build.VERSION.SDK_INT >= O` (el proyecto es `minSdk = 24`, así que hay que contemplar API 24–25
  sin canales, donde `NotificationCompat` ya lo ignora).
- **Notificación continua y actualizable (`ongoing`):** `setOngoing(true)` para que no se pueda
  descartar mientras hay tareas, y la **reemisión con el mismo `notificationId`** para actualizar el
  contenido en sitio (mismo problema que el widget: una notificación **no observa** un `Flow`, algo
  tiene que empujar la actualización). Uso de `InboxStyle`/`BigTextStyle` para mostrar varias líneas.
- **Visibilidad y privacidad en el lockscreen:** `setVisibility(VISIBILITY_PUBLIC/PRIVATE/SECRET)` y
  `setPublicVersion(...)` para publicar una **versión redactada** en la pantalla de bloqueo cuando
  los títulos de tarea pudieran ser sensibles.
- **Permiso `POST_NOTIFICATIONS` en runtime (API 33+):** por qué desde Android 13 publicar
  notificaciones requiere permiso concedido por la persona usuaria, cómo solicitarlo desde Compose
  con `rememberLauncherForActivityResult` + `ActivityResultContracts.RequestPermission`, y cómo
  **degradar con gracia** si se deniega (la app sigue funcionando; solo no se ve la notificación).
  En API 24–32 el permiso no existe y no hay que pedirlo.
- **Foreground service y su ciclo de vida (concepto de primera clase, D2 aprobada = SÍ):** qué es un
  servicio en primer plano, cómo se arranca (`Context.startForegroundService`) y cómo se promociona con
  `startForeground(notificationId, notification)`, su **ciclo de vida** (arranque cuando aparecen
  tareas pendientes, `stopSelf`/`stopForeground` cuando ya no quedan), y la exigencia en **API 34+**
  (`targetSdk = 36`) de declarar el **tipo** de servicio (`android:foregroundServiceType` + el permiso
  `FOREGROUND_SERVICE_*` correspondiente). Para una notificación informativa de tareas el tipo honesto
  es `specialUse` (a confirmar por `mobile-engineer`), documentando por qué. La lección explica también
  el coste (batería, restricciones del sistema a servicios de larga duración) para que se entienda el
  *trade-off* frente a una notificación sin servicio.

---

## Goals

Éxito significa que:

1. Con al menos una tarea pendiente, la persona usuaria ve en la **pantalla de bloqueo** una
   notificación con sus tareas más urgentes y el **tiempo restante** de cada una, sin desbloquear.
2. La notificación se **mantiene razonablemente al día**: se actualiza al cambiar las tareas y se
   refresca periódicamente en segundo plano, dentro de los límites del sistema.
3. **Tocar la notificación abre la app** en la lista de tareas.
4. En Android 13+ (API 33+) la app **solicita el permiso `POST_NOTIFICATIONS`** en el momento
   adecuado y **degrada con gracia** si se deniega; en API 24–32 funciona sin pedir permiso.
5. La **privacidad en el lockscreen** está protegida por defecto: los títulos de tarea no se filtran
   en una pantalla bloqueada salvo que la configuración del sistema lo permita.
6. Cuando **no hay tareas pendientes**, no se muestra (o se retira) la notificación: nunca una
   notificación vacía o "rota".
7. La feature **reutiliza** `TaskRepository` / `NeverLateDatabase` / `TaskTiming` **sin modificar el
   modelo de datos** ni la UI de la feature 04, y comparte la definición de "pendiente" con el widget.
8. Cualquier dependencia nueva queda declarada en `gradle/libs.versions.toml`; los permisos/servicios
   nuevos quedan declarados en `AndroidManifest.xml`.
9. La lección `tutorial/06-lockscreen.md` (español) explica los conceptos nuevos con referencia al
   código real, de forma progresiva sobre las lecciones 01–05.

---

## User Stories

### US-1 — Ver las tareas pendientes con su tiempo restante desde el lockscreen
**Como** persona que quiere no llegar tarde a lo que tiene que hacer,
**quiero** ver mis tareas pendientes y cuánto tiempo me queda **sin desbloquear el teléfono**,
**para** tener presente qué me toca sin la fricción de abrir la app.

**Criterios de aceptación:**
- Con al menos una tarea pendiente y el permiso concedido, existe una **notificación continua**
  (`setOngoing(true)`) que aparece en la pantalla de bloqueo (respetando la privacidad, ver US-5).
- La notificación muestra las tareas **más urgentes primero** (menor tiempo restante primero),
  limitadas a un máximo documentado de filas, cada una con **título** (según visibilidad) y **tiempo
  restante** formateado con `formatRemaining(...)`.
- El tiempo restante se calcula con `computeRemainingMillis(task, now)` de la feature 04, de modo que
  coincide con lo que muestra la app para esa tarea en ese instante (misma regla de cuenta atrás).
- Una tarea con el tiempo **agotado** se muestra en un estado claro (p. ej. `00:00` o etiqueta
  "tiempo agotado"), **nunca en negativo**.
- El contenido (título de la notificación, resumen, líneas, estado agotado) procede de una **función
  pura de mapeo** `List<Task> + now → modelo de notificación`, sin texto visible hardcodeado (cadenas
  en `strings.xml`).

### US-2 — La notificación se mantiene al día
**Como** usuaria que deja el teléfono bloqueado a la vista,
**quiero** que la notificación refleje mis cambios y no se quede obsoleta,
**para** poder fiarme de lo que muestra.

**Criterios de aceptación:**
- Tras **crear, editar, borrar, iniciar o pausar** una tarea en la app, la notificación se
  **reemite con el mismo `notificationId`** para reflejar el cambio, sin que la persona haga nada.
- Un trabajo periódico en segundo plano (**WorkManager `PeriodicWorkRequest`**) recalcula y reemite
  la notificación para mantener el tiempo restante razonablemente actualizado.
- El spec y la lección **documentan explícitamente** que el sistema impone un suelo de frecuencia
  (~15 min para WorkManager periódico), por lo que la notificación **no cuenta atrás segundo a
  segundo**: muestra el restante del último refresco. Este límite es una expectativa aceptada, no un
  bug (mismo criterio que el widget, US-5 de la feature 05).
- Si al recalcular **no quedan tareas pendientes**, la notificación se **retira** (`cancel`) en lugar
  de quedarse obsoleta (ver US-6).

### US-3 — Tocar la notificación abre la app
**Como** usuaria,
**quiero** tocar la notificación y que se abra la app en mis tareas,
**para** pasar de "ver" a "gestionar" en un toque.

**Criterios de aceptación:**
- La notificación lleva un **`contentIntent`** (`PendingIntent`) que abre `MainActivity` en la
  **lista de tareas** (ruta `tasks` del `AppNavHost`).
- El `PendingIntent` usa `FLAG_IMMUTABLE` (exigido desde API 31) y las flags correctas para reusarse.
- Al tocarla desde el lockscreen, el sistema pide desbloquear (comportamiento estándar) y luego abre
  la app en tareas.

### US-4 — Conceder o denegar el permiso de notificaciones con gracia (API 33+)
**Como** usuaria de un teléfono con Android 13 o superior,
**quiero** decidir si la app puede mostrarme notificaciones,
**para** mantener el control, sabiendo que la app sigue siendo útil aunque diga que no.

**Criterios de aceptación:**
- En **API 33+**, la app solicita `POST_NOTIFICATIONS` en runtime en un momento razonable (no de
  forma intrusiva en el primer arranque sin contexto; ver Decisiones de diseño), mediante el contrato
  `ActivityResultContracts.RequestPermission` desde Compose.
- Si se **concede**, la notificación aparece según US-1. Si se **deniega**, la app **no crashea ni
  bloquea ninguna otra funcionalidad**: simplemente no se publica la notificación.
- La app **no acosa** repetidamente con el diálogo del sistema (respeta la respuesta; a lo sumo
  ofrece un punto de reintento no intrusivo, p. ej. deep-link a los ajustes del sistema).
- En **API 24–32** la app **no solicita** el permiso (no existe) y publica la notificación
  directamente; el código guarda la solicitud tras `Build.VERSION.SDK_INT >= TIRAMISU`.

### US-5 — Control de privacidad/visibilidad en el lockscreen
**Como** usuaria cuyas tareas pueden tener títulos sensibles,
**quiero** que la pantalla de bloqueo no exponga esos títulos a quien mire el teléfono,
**para** proteger mi privacidad sin renunciar a la señal de "tengo cosas pendientes".

**Criterios de aceptación:**
- La notificación fija su visibilidad en el lockscreen con `setVisibility(...)`. Por defecto **no
  expone los títulos** completos en la pantalla bloqueada (ver recomendación en Decisiones de diseño).
- Se proporciona una **versión pública redactada** vía `setPublicVersion(...)` que, en el lockscreen,
  muestra información no sensible (p. ej. "Tienes N tareas pendientes" y/o el tiempo de la más
  urgente) **sin títulos**. Al desbloquear, la persona ve la versión completa.
- El comportamiento respeta además el ajuste del sistema del dispositivo sobre contenido en el
  lockscreen (público/privado/oculto); la app no intenta forzar la exposición.

### US-6 — Estado sin tareas pendientes
**Como** usuaria sin tareas pendientes,
**quiero** que no aparezca una notificación vacía,
**para** no ver ruido cuando no tengo nada que atender.

**Criterios de aceptación:**
- Cuando la función de mapeo determina que **no hay tareas pendientes**, la notificación **no se
  publica** o, si estaba publicada, se **retira** (`NotificationManagerCompat.cancel(notificationId)`).
- La función pura de mapeo devuelve un **estado vacío** explícito (equivalente a
  `PendingTasksWidgetModel.Empty`) que la carcasa traduce en "no notificar / cancelar".

---

## Acceptance Criteria (resumen verificable)

Criterios concretos y testeables para dar la feature por completada:

1. **Notificación visible en lockscreen:** con permiso concedido y ≥1 tarea pendiente, aparece una
   notificación continua con las tareas más urgentes y su tiempo restante. *(Verificación manual en
   emulador con pantalla bloqueada.)*
2. **Contenido correcto y coherente con la app:** título + tiempo restante por tarea, calculado con
   `computeRemainingMillis`/`formatRemaining`, sin negativos, con la frontera `mm:ss` ↔ `h:mm:ss`.
   *(Test JVM de la **función de mapeo** notificación; los tests de `TaskTiming` ya cubren el
   formato.)*
3. **Definición de pendiente/orden/cap coherente con el widget:** todas las tareas, más urgente
   primero, limitadas a N. *(Test JVM de la función de mapeo: orden, cap, tarea agotada → `00:00`,
   lista vacía → estado vacío.)*
4. **Refresco por cambios:** crear/editar/borrar/iniciar/pausar reemite la notificación con el mismo
   `notificationId`. *(Inspección del punto de invocación + verificación manual.)*
5. **Refresco periódico:** existe un `PeriodicWorkRequest` que reemite/recalcula la notificación;
   documentado el suelo de ~15 min. *(Inspección de la configuración de WorkManager + lección.)*
6. **Tap abre la app:** tocar la notificación abre `MainActivity` en la ruta `tasks` con un
   `PendingIntent` `FLAG_IMMUTABLE`. *(Inspección del código + verificación manual.)*
7. **Permiso runtime (API 33+):** en API 33+ se solicita `POST_NOTIFICATIONS`; denegar no rompe nada;
   en API ≤32 no se solicita. *(Verificación manual en emuladores API 33+ y API 24–32 + inspección de
   la guarda `SDK_INT`.)*
8. **Privacidad en lockscreen:** la versión pública (`setPublicVersion`) no muestra títulos; la
   completa sí, tras desbloquear. *(Verificación manual con distintos ajustes de lockscreen.)*
9. **Estado vacío:** sin tareas pendientes no hay notificación (no se publica o se cancela). *(Test
   JVM de la función de mapeo: lista vacía → estado vacío; verificación manual del cancel.)*
10. **Reutilización sin tocar el modelo:** usa `NeverLateDatabase.getInstance(...)`, `TaskRepository`
    y `TaskTiming`; no añade entidades ni cambia el esquema de `Task`. *(Inspección de tipos e
    imports; `git diff` no toca `data/tasks/Task.kt`.)*
11. **Manifest:** `AndroidManifest.xml` declara `POST_NOTIFICATIONS`, `FOREGROUND_SERVICE`,
    `FOREGROUND_SERVICE_SPECIAL_USE` y el `<service>` con `android:foregroundServiceType="specialUse"`.
    *(Inspección del manifest.)*
12. **Catálogo de versiones:** cualquier dependencia nueva (si la hubiera) está en
    `gradle/libs.versions.toml` con `version.ref`. *(Revisión del catálogo.)*
13. **Build:** compila con `./gradlew :app:assembleDebug` usando el wrapper.
14. **Documentación:** se añade `tutorial/06-lockscreen.md` (español) cubriendo los conceptos nuevos
    con referencia al código real.
15. **Ciclo de vida del servicio:** el foreground service arranca al aparecer tareas pendientes y se
    detiene (`stopSelf`) cuando no queda ninguna. *(Verificación manual + inspección del código.)*

---

## Technical Approach (alto nivel)

> Estrategia orientativa; el diseño de detalle es responsabilidad de la fase de implementación
> (`mobile-engineer`). Se listan aquí las decisiones que afectan al alcance y a la reutilización.

- **Ubicación del código:** un nuevo paquete `ui/notification/` bajo `com.neverlate`, con, al menos:
  el **modelo + función pura de mapeo** (equivalente a `PendingTasksWidgetState.kt`), un **helper de
  notificación** (crea el canal, construye y publica/cancela la notificación con
  `NotificationManagerCompat`), y un **`CoroutineWorker`** de refresco periódico (equivalente a
  `WidgetRefreshWorker`). Las cadenas van a `res/values/strings.xml`.
- **Función pura de mapeo (clave, testeable en JVM):** una función `toNotificationModel(tasks, now)`
  que reutiliza `computeRemainingMillis` + `formatRemaining` y aplica la **misma** regla de
  "pendiente/orden/cap" que `toWidgetModel`. Devuelve un modelo tipo `sealed interface`:
  `Empty` (→ no notificar/cancelar) o `Content(rows, ...)` con las líneas ya formateadas para el
  cuerpo (`InboxStyle`) y el resumen para la versión pública redactada. Para no duplicar la regla de
  ordenación/cap, se puede **extraer un helper puro compartido** (p. ej. `pendingRowsFor(tasks, now)`)
  reutilizado por el widget y la notificación; a decidir por `mobile-engineer` (ver Riesgos).
- **Acceso a datos (reutilización):** el `CoroutineWorker` y el helper obtienen el singleton con
  `NeverLateDatabase.getInstance(context)`, construyen `RoomTaskRepository(database.taskDao())` y leen
  un **snapshot** con `repository.observeTasks().first()` — exactamente como el widget. No hay
  suscripción viva cross-process; la notificación se **reconstruye bajo demanda**.
- **Actualización dirigida por cambios (reutilización del patrón decorador):** el proyecto ya resuelve
  "una superficie fuera de la `Activity` no puede observar un `Flow`, algo tiene que empujar" con
  `WidgetRefreshingTaskRepository`, que en cada escritura llama a `updateAll(...)`. Esta feature debe
  añadir el mismo empuje para la notificación. **Recomendación:** **generalizar el decorador
  existente** para que, tras cada escritura, refresque **ambas** superficies (widget + notificación)
  desde un único punto —renombrándolo a algo como `TaskSurfacesRefreshingRepository`— en vez de anidar
  dos decoradores. Alternativa aceptable: un segundo decorador que envuelva al actual. En cualquier
  caso, los `ViewModel` de la feature 04 permanecen ajenos a que existe una notificación.
- **Refresco periódico:** un `PeriodicWorkRequest` (WorkManager) análogo a `WidgetRefreshWorker`, con
  `enqueueUniquePeriodicWork` + `ExistingPeriodicWorkPolicy.KEEP` (idempotente), encolado desde
  `MainActivity.onCreate` como ya se hace para el widget. Documentar el suelo de ~15 min. Se puede
  reutilizar un único worker que refresque widget **y** notificación, o mantener workers separados;
  a decidir por `mobile-engineer`.
- **Permiso `POST_NOTIFICATIONS` (API 33+):** solicitarlo desde Compose con
  `rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission())`, guardado tras
  `Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU`. Momento de la petición: **ver Decisiones de
  diseño**. Toda publicación de notificación se hace vía `NotificationManagerCompat`, que además
  comprueba `areNotificationsEnabled()`.
- **Canal de notificación (API 26+):** crear un `NotificationChannel` de importancia baja/por defecto
  (sin sonido intrusivo — es una notificación informativa continua) de forma idempotente, guardado
  tras `SDK_INT >= O`. `NotificationCompat` degrada solo en API 24–25.
- **Visibilidad/privacidad:** `setVisibility(...)` en la notificación principal + `setPublicVersion`
  con una notificación redactada (recuento/urgencia, sin títulos). Ver recomendación en Decisiones.
- **Tap para abrir la app:** `contentIntent` con `PendingIntent` a `MainActivity` (extra/deep-link a
  la ruta `tasks`), `FLAG_IMMUTABLE`. Puede reutilizar el mismo mecanismo de deep-link que el widget
  (US-6 de la feature 05), evitando duplicar la lógica de arranque en `tasks`.
- **Foreground service (D2 = SÍ):** un `Service` propio (p. ej. `ui/notification/TasksNotificationService`)
  que en `onStartCommand` lee el snapshot de tareas, construye la notificación con la función de mapeo y
  se promociona con `startForeground(notificationId, notification)`. El decorador de escrituras y el
  worker periódico lo arrancan con `ContextCompat.startForegroundService(...)`; cuando la función de
  mapeo devuelve `Empty`, el servicio llama a `stopSelf()` (y `stopForeground(...)`) para retirar la
  notificación. Documentar el ciclo de vida en la lección.
- **Manifest:** añadir el permiso `POST_NOTIFICATIONS`, el permiso `FOREGROUND_SERVICE` y el
  `FOREGROUND_SERVICE_SPECIAL_USE` (tipo propuesto `specialUse`), y declarar el
  `<service android:name=".ui.notification.TasksNotificationService" android:foregroundServiceType="specialUse" ...>`
  (el `foregroundServiceType` es exigido en API 34+, `targetSdk = 36`). El tipo final lo confirma
  `mobile-engineer`.

---

## Decisiones de diseño (a resolver/confirmar con la persona usuaria)

> Estas tres decisiones afectan al alcance y a la carga didáctica de la lección. El spec **recomienda**
> una opción en cada una; se piden confirmar antes de implementar.

### D1 — ¿Notificación siempre presente vs. solo mientras corre un contador (foreground service)?
- **Opción A (recomendada):** notificación continua **siempre que haya tareas pendientes** (haya o no
  un contador corriendo), publicada y refrescada como una notificación normal actualizable. Simple,
  cubre todos los casos de uso (también tareas sin timer activo) y no requiere servicio.
- **Opción B:** mostrarla solo mientras hay un contador activo, respaldada por un foreground service.
- **Recomendación:** **A.** El valor está en ver *todas* las tareas pendientes desde el lockscreen,
  no solo las que tienen un timer corriendo; y evita el coste/complejidad del servicio.
- **✅ Aprobado: Opción A.**

### D2 — ¿Foreground service, sí o no?
- **Opción A (recomendada en el borrador):** **NO** usar foreground service. Una **notificación normal
  actualizable** mantenida fresca por **WorkManager periódico + refresco dirigido por escrituras** (el
  mismo patrón que ya resolvió el widget) sería suficiente para una señal informativa.
- **Opción B:** foreground service con `startForeground`, que en **API 34+** (`targetSdk = 36`) obliga
  a declarar `foregroundServiceType` y el permiso `FOREGROUND_SERVICE_*`, y añade gestión de ciclo de
  vida (arranque/parada del servicio según haya o no tareas pendientes).
- **✅ Aprobado: Opción B (usar foreground service).** Decisión de la persona usuaria, con foco
  **didáctico**: la feature aprovecha para enseñar servicios en primer plano como concepto de primera
  clase. La notificación continua se hospeda en el servicio; combinado con **D1**, el servicio arranca
  cuando aparece la primera tarea pendiente y se detiene (`stopSelf`) cuando no queda ninguna. Se
  mantiene además el **refresco periódico (WorkManager)** para que el tiempo restante mostrado no se
  quede obsoleto por el mero avance del reloj (sin escrituras de por medio), pokeando al servicio a
  reevaluar. La lección debe cubrir el ciclo de vida completo, el `foregroundServiceType` (API 34+, se
  propone `specialUse`) y el *trade-off* de batería/restricciones de servicios de larga duración.

### D3 — Visibilidad por defecto en el lockscreen
- **Opción A (recomendada):** `VISIBILITY_PRIVATE` en la notificación principal **+** una
  `setPublicVersion(...)` **redactada** (p. ej. "Tienes 3 tareas pendientes · próxima en 12:30", sin
  títulos). En el lockscreen se ve la versión redactada; al desbloquear, la completa.
- **Opción B:** `VISIBILITY_PUBLIC` (títulos visibles en el lockscreen) — más útil de un vistazo, pero
  filtra posibles títulos sensibles.
- **Opción C:** `VISIBILITY_SECRET` (nada en el lockscreen) — máxima privacidad, pero anula el objetivo
  de la feature (ver algo sin desbloquear).
- **Recomendación (borrador):** A, por privacidad de títulos potencialmente sensibles.
- **✅ Aprobado: Opción B (`VISIBILITY_PUBLIC`) por ahora.** Los títulos se muestran en el lockscreen.
  El código deja **`setPublicVersion` redactada implementada pero comentada/documentada** como camino
  fácil de activar. **Hacer la visibilidad configurable** por la persona usuaria (elegir
  público/privado/oculto) **y un interruptor para activar/desactivar la notificación** se difieren a la
  **feature 07 (ajustes/tema)** — ver Out of Scope. La lección debe explicar los tres niveles de
  `VISIBILITY_*` y por qué se eligió público de momento, dejando la puerta abierta a hacerlo
  configurable.

---

## Out of Scope

Esta feature **no** incluye:

- **Botones de acción en la notificación (iniciar/pausar/completar desde el lockscreen).**
  Decisión: **fuera**. La notificación es de **solo lectura**, coherente con el widget (que también lo
  es). Añadir acciones exige `PendingIntent`s con `BroadcastReceiver`s que muten el repositorio y
  reintroduce el diálogo de permisos/interacción en pantalla bloqueada; se pospone a una feature
  futura si se pide.
- **Notificaciones individuales por tarea** (una notificación por cada tarea). Esta feature entrega
  **una** notificación agregada con las tareas más urgentes.
- **Alarmas/recordatorios al llegar la fecha límite o agotarse el tiempo** → **feature 09**
  (`docs/prompts/09-recordatorios.md`). Esta notificación es una vista continua, no un disparo puntual
  en un instante.
- **Cuenta atrás animada segundo a segundo** en la notificación. Igual que el widget, se refresca con
  la granularidad que permite el sistema (minutos); muestra el restante del último refresco.
- **Datos remotos / sincronización / backend** → **feature 11** (`docs/prompts/11-bbdd-remota.md`).
  Todo es local.
- **Preferencia de usuario para activar/desactivar la notificación o cambiar su visibilidad
  (público/privado/oculto) desde la app** → **diferido explícitamente a la feature 07 (ajustes/tema)**,
  a petición de la persona usuaria. Esta feature usa un comportamiento por defecto fijo (D3 =
  `VISIBILITY_PUBLIC`) y deja el código preparado (`setPublicVersion` redactada documentada) para que
  la feature 07 solo tenga que exponer la elección como preferencia persistida.
- **Estilos avanzados / imágenes / progreso animado** en la notificación (más allá de
  `InboxStyle`/`BigTextStyle` y el texto de las filas).

---

## Dependencies

- **Capa de datos existente (dependencia dura):** requiere la feature 04 ya en `master`:
  `NeverLateDatabase` (+ `getInstance`), `TaskDao`, `TaskRepository`/`RoomTaskRepository`, la entidad
  `Task` y las funciones de `TaskTiming.kt` (`computeRemainingMillis`, `formatRemaining`). La
  notificación **depende de la interfaz `TaskRepository`** (cuyo KDoc ya anticipa esta feature) y del
  singleton de Room; no reimplementa acceso a datos.
- **Patrones de la feature 05 (dependencia blanda, de diseño):** reutiliza el patrón de **función pura
  de mapeo** (`PendingTasksWidgetState.kt`), el **decorador de refresco** (`WidgetRefreshingTaskRepository`)
  y el **`CoroutineWorker` periódico** (`WidgetRefreshWorker`), así como el posible **deep-link** a la
  ruta `tasks`. Compartir la definición de "pendiente/orden/cap" es un requisito, no una opción.
- **Bibliotecas AndroidX:** las notificaciones y el permiso runtime se cubren con **`androidx.core`**
  (`NotificationCompat`, `NotificationManagerCompat`, `NotificationChannelCompat`) y **`activity`**
  (`ActivityResultContracts.RequestPermission`), que el proyecto ya tiene (core-ktx, lifecycle,
  Compose/Activity). **WorkManager** ya está en el catálogo (feature 05). **En principio no hacen falta
  dependencias nuevas**; si alguna versión concreta de core/activity necesitara subirse para una API,
  se declara en `gradle/libs.versions.toml` con `version.ref` (nada hardcodeado). Si al implementar se
  detecta una incompatibilidad, **parar y reportar** (Execution Policy).
- **Manifest — permisos/servicios nuevos (D2 = SÍ):**
  - `POST_NOTIFICATIONS` (necesario en API 33+; declararlo no molesta en API ≤32).
  - `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_SPECIAL_USE` (tipo propuesto `specialUse`), y el
    `<service>` con `android:foregroundServiceType` (exigido en API 34+ por `targetSdk = 36`).
- **Navegación existente:** el `contentIntent` abre `MainActivity` en la ruta `tasks`; reutiliza (o
  introduce, si aún no existe) el mismo extra/deep-link que el widget para arrancar en tareas. Cambio
  pequeño y acotado.
- **Tooling/build:** debe compilar con `./gradlew :app:assembleDebug` (wrapper).

---

## Deliverables / Documentation (obligatorio antes de commitear)

- **Lección tutorial (Tutorial Methodology):** `tutorial/06-lockscreen.md` (español), progresiva sobre
  01–05. Debe cubrir: notificaciones y `NotificationCompat`; canales (API 26+) y la guarda `SDK_INT`;
  notificación continua/actualizable y la reemisión con el mismo `notificationId` (el problema
  "empujar en vez de observar", reutilizando el patrón del widget); visibilidad/privacidad en el
  lockscreen (`VISIBILITY_*`, `setPublicVersion`); el permiso `POST_NOTIFICATIONS` en runtime desde
  Compose y su degradación con gracia; el refresco por cambios (decorador) y periódico (WorkManager);
  y el **foreground service**: qué es, su ciclo de vida, `startForeground`, el `foregroundServiceType`
  de API 34+ y el *trade-off* de batería/restricciones.
- **Manifest:** declarar `POST_NOTIFICATIONS`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE`
  y el `<service>` con su `foregroundServiceType`.
- **Strings:** cadenas de la notificación (título, resumen, líneas, estado, textos de la versión
  pública) en `res/values/strings.xml` (sin texto visible hardcodeado en el código).
- **Catálogo de versiones:** cualquier subida/entrada de dependencia en `gradle/libs.versions.toml`.
- **`CLAUDE.md`:** actualizar la sección **Structure** (nuevo paquete `ui/notification/`) y la nota de
  **manifest/permisos** (nuevo permiso `POST_NOTIFICATIONS`, y servicio si aplica) — el `CLAUDE.md`
  indica explícitamente reflejar nuevos permisos/manifest y nuevos paquetes.

---

## Testing notes

> Igual que en las features 04 y 05, el énfasis es **mover toda la lógica testeable a funciones puras
> JVM** y dejar la carcasa Android (canal, `NotificationCompat`, permiso, worker) lo más fina posible,
> porque publicar/mostrar notificaciones y el lockscreen **no** se testean en unidad razonablemente.

- **Tests unitarios JVM (`app/src/test/.../ui/notification/`), sin emulador — el grueso:**
  - **Función de mapeo** (`tasks: List<Task>`, `now: Long`) → modelo de notificación. Casos: lista
    vacía → `Empty` (no notificar); una/varias tareas → filas con el restante correcto; tarea agotada
    → `00:00`/etiqueta, sin negativos; **orden más-urgente-primero**; **cap a N**; contenido de la
    **versión pública redactada** (recuento/urgencia sin títulos). Debe dar los **mismos** resultados
    de orden/cap/pendiente que `toWidgetModel` (idealmente compartiendo el helper puro).
  - Se **reutilizan** los tests existentes de `TaskTiming`; la función de mapeo se apoya en ellos para
    que el restante coincida con la app y el widget.
- **Lo que NO se testea en unidad (documentarlo):** la creación del canal, la publicación/actualización
  real de la notificación, la visibilidad en el lockscreen, el flujo del permiso `POST_NOTIFICATIONS`
  y el `contentIntent` se verifican **manualmente en el emulador** (API 33+ y API 24–32): permiso
  concedido/denegado, notificación visible con teléfono bloqueado, versión pública redactada, tocar
  para abrir la app, y cancelación al quedarse sin tareas. Opcionalmente, el `CoroutineWorker` puede
  tener un test de instrumentación ligero con `WorkManagerTestInitHelper` (no bloqueante).
- **Comandos:**

```bash
# Tests unitarios (JVM, sin emulador)
./gradlew :app:testDebugUnitTest

# Build del APK de depuración
./gradlew :app:assembleDebug
```

---

## Risks

- **Permiso denegado / notificaciones desactivadas:** en API 33+ la persona puede denegar el permiso,
  o desactivar el canal, y la notificación no aparece. *Mitigación:* degradar con gracia (US-4), no
  acosar, comprobar `areNotificationsEnabled()` antes de publicar, y documentarlo en la lección.
- **Expectativa de "cuenta atrás en vivo":** la persona podría esperar segundos moviéndose en el
  lockscreen. No es posible sin un servicio que gaste batería. *Mitigación:* mostrar el restante del
  último refresco, refrescar por cambios para reflejar iniciar/pausar al instante, y documentar el
  suelo de ~15 min (US-2).
- **Duplicación de la regla "pendiente/orden/cap":** si la notificación reimplementa la lógica del
  widget, pueden divergir. *Mitigación:* extraer un **helper puro compartido** (`pendingRowsFor`)
  reutilizado por widget y notificación, con un único juego de tests.
- **Doble punto de empuje (widget + notificación):** anidar dos decoradores o dispersar llamadas a
  `updateAll`/`notify` es frágil. *Mitigación:* un **único decorador** que refresque ambas superficies
  desde un punto (ver Technical Approach D-decorador).
- **Fragmentación por API level:** canales (26+), permiso runtime (33+), FGS types (34+) y `minSdk 24`
  obligan a guardas `SDK_INT` correctas. *Mitigación:* usar las variantes `*Compat`, guardar cada
  llamada específica, y probar en emuladores de API 24–25, 32 y 34+.
- **Coste de batería / spam de notificación:** refrescos demasiado frecuentes o reemisiones que hagan
  "parpadear" la notificación. *Mitigación:* respetar el suelo del sistema, canal de importancia
  baja/sin sonido, reemitir con el mismo `notificationId` (actualización en sitio, sin re-alertar) y
  `setOnlyAlertOnce(true)`.
- **Privacidad en el lockscreen:** exponer títulos sensibles por defecto sería un fallo de privacidad.
  *Mitigación:* D3 = opción A (versión pública redactada), respetando el ajuste del sistema.
- **Sobrecarga didáctica:** la feature junta notificaciones, canales, permiso runtime, privacidad y
  **foreground service** (D2 = B). Es la lección más densa hasta ahora. *Mitigación:* apoyarse en el
  patrón ya conocido (decorador + WorkManager), reutilizar `TaskTiming`/`TaskRepository`, y presentar
  los conceptos progresivamente en la lección, con el servicio como bloque final bien acotado.
- **Servicio de larga duración / restricciones del sistema:** un foreground service activo mientras
  haya tareas pendientes puede durar horas/días; Android limita y vigila estos servicios, y el tipo
  `specialUse` requiere justificación (irrelevante aquí porque la app no se publica). *Mitigación:*
  arrancar/parar el servicio según haya o no tareas pendientes, canal de importancia baja, y
  documentar la limitación en la lección.

---

## Siguiente paso

Especificación **aprobada** (2026-07-02) con las decisiones **D1 = notificación siempre-presente**,
**D2 = usar foreground service** y **D3 = `VISIBILITY_PUBLIC` por ahora** (visibilidad configurable
diferida a la feature 07). El flujo continúa (según el **Mandatory Workflow** de `CLAUDE.md`): crear la
rama `feature/tasks-lockscreen`, implementar con `mobile-engineer` (reutilizando
`TaskRepository`/`NeverLateDatabase`/`TaskTiming` y los patrones de la feature 05 sin tocar el modelo),
declarar los permisos y el `<service>` en el manifest, tests con `qa-engineer` (función pura de mapeo
en JVM) y la lección `tutorial/06-lockscreen.md` antes de commitear.
