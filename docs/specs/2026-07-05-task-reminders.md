# Especificación — Recordatorios / notificaciones locales

- **Fecha:** 2026-07-05
- **Feature:** Recordatorios (avisos) programados antes del vencimiento de una tarea, que disparan
  una **notificación local** aunque la app esté cerrada y que **sobreviven al reinicio** del
  dispositivo (Task reminders)
- **Prompt origen:** `docs/prompts/09-recordatorios.md`
- **Rama sugerida:** `feature/reminders`
- **Lección tutorial asociada:** `tutorial/09-recordatorios.md` (español)
- **Estado:** Aprobada (2026-07-05) — decisiones tomadas en las preguntas abiertas (ver *Preguntas abiertas*)

---

## Overview

Esta feature añade **recordatorios**: la app programa un **aviso** para que suene **antes** de que
venza una tarea (por ejemplo, "faltan 10 min") y presenta una **notificación local** en ese
instante, **aunque la app esté cerrada o el proceso muerto**. Los recordatorios se **reprograman
automáticamente tras reiniciar el dispositivo**.

Hasta ahora la app tenía dos superficies de notificación de *solo lectura* y **pasivas** que se
refrescan por empuje (widget de la feature 05 y la notificación continua de la feature 06). Ambas
muestran el estado *actual* cada ~15 minutos vía `TaskSurfacesRefreshWorker` (WorkManager). Esta
feature introduce algo cualitativamente distinto: una notificación **puntual, que alerta** y que
debe dispararse en un **instante de reloj concreto**, con precisión, aunque el dispositivo esté en
**Doze** (ahorro de energía) o la app no se esté ejecutando. Eso saca a WorkManager de su zona de
confort (trabajo diferible y agrupable) y obliga a introducir **`AlarmManager` con alarmas
exactas**, junto con la reprogramación al arranque (`BOOT_COMPLETED`).

La restricción de arquitectura central es la **reutilización de la infraestructura de
notificaciones ya existente** de la feature 06, sin reinventarla:

- El permiso **`POST_NOTIFICATIONS`** (Android 13+) ya está declarado en el manifest y se solicita
  desde Compose con `RequestNotificationPermissionEffect` (`ui/notification/NotificationPermission.kt`).
  Se **reutiliza** tal cual.
- Ya hay un canal de notificaciones (`TASKS_NOTIFICATION_CHANNEL_ID = "tasks_pending"`) creado por
  `TasksNotificationHelper.ensureChannel`. Ahora bien, ese canal es **silencioso a propósito**
  (`IMPORTANCE_DEFAULT` + `setSound(null, null)` + `setVibrationEnabled(false)`) porque es un
  *resumen de estado* continuo. Un recordatorio es lo contrario: **debe alertar** (sonido /
  *heads-up*). Por eso esta feature **añade un segundo canal** (p. ej. `task_reminders`,
  `IMPORTANCE_HIGH`), reutilizando el *patrón* `ensureChannel`, pero **no** el canal silencioso.
- El modelo de tareas ya guarda un **vencimiento**: `Task.deadline` (epoch millis) en
  `data/tasks/Task.kt`, persistido en Room. El recordatorio se calcula **restando** el *lead time*
  a ese `deadline`. Se reutiliza el repositorio `TaskRepository` / `RoomTaskRepository` /
  `NeverLateDatabase.getInstance(...)` para leer las tareas fuera de la `Activity` (igual que hace
  hoy `TasksNotificationService`).

**Distinción clave (recordatorio ≠ notificación continua):** la notificación de la feature 06 es
**una sola**, *ongoing*, con id fijo `TASKS_NOTIFICATION_ID = 1001`, alojada en un **foreground
service** y silenciosa. Un recordatorio es **de un solo disparo**, **por tarea**, con **id propio**
(derivado del `Task.id`), **alerta**, y **no** necesita foreground service: lo dispara el sistema
vía `AlarmManager` → `BroadcastReceiver`, que publica la notificación y termina. Ambos mundos
coexisten sin pisarse (canales distintos, ids distintos, mecanismos distintos).

Todo sigue siendo **local-only** (sin backend). El estado que hace falta para reprogramar
(`Task.deadline` + preferencia de *lead time*) ya vive en disco (Room + DataStore `user_prefs`).

### Conceptos nuevos que enseña (para `tutorial/09-recordatorios.md`)

Partiendo de las lecciones 04 (Room + `Flow`, funciones puras de tiempo, reloj de pared), 05
(función pura de mapeo, patrón decorador, WorkManager, `PendingIntent`/deep-link), 06
(notificaciones, canales, `POST_NOTIFICATIONS`, degradación con gracia, foreground service) y 07
(DataStore para preferencias):

- **Trabajo diferido / programado — el contraste central de la lección:** cuándo usar
  **WorkManager** y cuándo **`AlarmManager`**.
  - *WorkManager* (ya usado en la feature 05/06) es para trabajo **diferible y agrupable** que debe
    ejecutarse *"en algún momento"* respetando la batería: su `PeriodicWorkRequest` tiene un mínimo
    de **15 minutos** y el sistema lo **agrupa y retrasa** en Doze. Perfecto para refrescar
    superficies, **inútil** para "avisa exactamente 10 minutos antes".
  - *`AlarmManager`* con **alarmas exactas** (`setExactAndAllowWhileIdle`) es para dispararse en un
    **instante de reloj concreto**, incluso en Doze. Ese es el recordatorio.
  - **Alarmas exactas vs. inexactas** y su coste en batería/permisos (ver más abajo el
    *Technical Approach* y los *Risks*).
- **`AlarmManager` + `PendingIntent` + `BroadcastReceiver`:** programar un `PendingIntent` que el
  sistema entrega a un `BroadcastReceiver` cuando llega la hora; construir el `PendingIntent` con
  `FLAG_IMMUTABLE` (obligatorio en API 31+) y un **request code determinista** por tarea para poder
  **reemplazar/cancelar** el aviso al editar o borrar. Publicar la notificación **desde el
  receiver** (contexto sin `Activity`).
- **Permisos de alarmas exactas por versión de Android:** `SCHEDULE_EXACT_ALARM` (API 31+),
  `AlarmManager.canScheduleExactAlarms()`, `Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM`, y la
  alternativa `USE_EXACT_ALARM` (API 33+, restringida por política de Google Play a apps de
  alarma/reloj/calendario). En **API < 31** no hace falta permiso especial. Y **degradar con
  gracia** a inexacta si el permiso no está.
- **Canal de notificación que *alerta*:** un segundo `NotificationChannel` con `IMPORTANCE_HIGH`
  (sonido + *heads-up*), contrastado con el canal silencioso de la feature 06 — por qué la
  *importancia* de un canal se congela tras crearlo.
- **Reprogramar al reiniciar (`BOOT_COMPLETED`):** al apagar el dispositivo el sistema **borra
  todas las alarmas**; hay que volver a programarlas. Un `BroadcastReceiver` registrado para
  `android.intent.action.BOOT_COMPLETED` (permiso `RECEIVE_BOOT_COMPLETED`) que, dado su **breve
  margen de ejecución**, **delega** la reprogramación masiva a un `OneTimeWorkRequest` de
  WorkManager (lee las tareas de Room y reprograma los avisos futuros). Aquí WorkManager sí encaja:
  es trabajo diferible de fondo, no un aviso puntual.
- **Preferencia de recordatorios en DataStore (repaso de la feature 07):** ampliar
  `UserPreferencesRepository` / `user_prefs` con el *lead time* por defecto (minutos) y un
  *on/off*, sin crear un segundo DataStore.

---

## Goals

Éxito significa que:

1. La persona usuaria recibe una **notificación local** un *lead time* configurable **antes** del
   `deadline` de una tarea (p. ej. 10 min antes), **aunque la app esté cerrada** o su proceso muerto.
2. El aviso se dispara en el **instante correcto con precisión** (no "en algún momento en los
   próximos 15 min"), incluso con el dispositivo en reposo (Doze), cuando el permiso de alarmas
   exactas está disponible.
3. Los recordatorios **se reprograman solos tras reiniciar** el dispositivo, sin abrir la app.
4. Programar / reprogramar / cancelar el aviso está **acoplado al ciclo de vida de la tarea**: al
   **crear** o **editar** una tarea con `deadline`, su aviso se (re)programa; al **borrarla** o
   **quitarle el deadline**, su aviso se **cancela**.
5. Se **reutiliza** la infraestructura de notificaciones existente (permiso `POST_NOTIFICATIONS`,
   patrón `ensureChannel`) y se **añade** solo lo estrictamente nuevo (un canal que alerta, un
   `AlarmManager` scheduler, dos `BroadcastReceiver`).
6. El recordatorio **degrada con gracia**: si falta el permiso `POST_NOTIFICATIONS`, no se muestra;
   si falta el de alarmas exactas, cae a un aviso **inexacto** (puede llegar con unos minutos de
   holgura) en vez de fallar o crashear.
7. Todo el texto visible del aviso vive en **string resources** (base español + variante inglesa),
   los minutos usan `<plurals>` y las horas/fechas se formatean **según el `Locale`** del
   dispositivo (feature 08).
8. La lección `tutorial/09-recordatorios.md` (español) explica los conceptos nuevos con referencia
   al código real, progresiva sobre 04–08.

---

## User Stories

### US-1 — Recibir un aviso antes del vencimiento
**Como** persona con TDA/TDAH que gestiona tareas con fecha límite,
**quiero** que la app me avise un rato antes de que venza una tarea,
**para** llegar a tiempo sin tener que mirar la app constantemente.

**Criterios de aceptación:**
- Para una tarea con `deadline`, la app programa un aviso en el instante
  `deadline − leadTime` (con `leadTime` la preferencia por defecto, p. ej. 10 min).
- Llegada esa hora, aparece una **notificación local** con el título de la tarea y cuánto falta
  para el vencimiento (texto desde `strings.xml`, minutos vía `<plurals>`).
- El aviso llega **aunque la app esté cerrada** o el proceso no se esté ejecutando.
- Tocar la notificación abre la app en la **lista de tareas** (reutilizando el deep-link
  `MainActivity.EXTRA_OPEN_TASKS`, igual que el widget y la notificación continua).
- Si el `deadline − leadTime` ya está en el **pasado** al programar (p. ej. tarea creada con muy
  poco margen), **no** se programa un aviso retroactivo. *(Decisión abierta OQ-6: opción de "avisar
  ya" si aún no ha vencido.)*

### US-2 — El aviso sobrevive al reinicio del dispositivo
**Como** persona usuaria,
**quiero** que mis recordatorios sigan funcionando aunque reinicie el móvil,
**para** no perderme un aviso solo porque se apagó y encendió el teléfono.

**Criterios de aceptación:**
- Tras un **reinicio** del dispositivo, los avisos **futuros** de todas las tareas con `deadline`
  quedan **reprogramados** sin abrir la app.
- La reprogramación lee las tareas de Room y **solo** programa avisos cuyo instante siga en el
  **futuro** (descarta los ya pasados).
- El receiver de arranque **no** hace trabajo pesado en su hilo: **delega** la reprogramación a un
  trabajo en segundo plano (WorkManager) para respetar su breve ventana de ejecución.

### US-3 — El aviso se mantiene coherente con la tarea
**Como** persona usuaria,
**quiero** que si cambio o borro una tarea el aviso se actualice en consecuencia,
**para** no recibir avisos de tareas que ya no existen o con la hora equivocada.

**Criterios de aceptación:**
- Al **crear** una tarea con `deadline`, se programa su aviso.
- Al **editar** el `deadline` (o el título) de una tarea, el aviso se **reprograma** (se reemplaza
  el anterior; no se acumulan dos avisos para la misma tarea).
- Al **quitar** el `deadline` de una tarea, su aviso se **cancela**.
- Al **borrar** una tarea, su aviso se **cancela**.
- La identidad del aviso se deriva del **`Task.id`**, de modo que reprogramar/cancelar afecta
  exactamente al aviso de esa tarea y no a los de otras.

### US-4 — Configurar cuánto tiempo antes avisar (y activarlo/desactivarlo)
**Como** persona usuaria,
**quiero** decidir con cuánta antelación se me avisa (y poder desactivar los avisos),
**para** ajustar los recordatorios a cómo trabajo yo.

**Criterios de aceptación:**
- Existe una preferencia de **antelación por defecto** (*lead time*, en minutos) y un **interruptor
  de activación** de recordatorios, persistidos en el `DataStore` existente (`user_prefs`).
- La preferencia se lee al programar cada aviso; cambiarla afecta a los avisos que se programen a
  partir de entonces. *(Si se reprograman los ya existentes al cambiarla es una **decisión abierta**,
  OQ-3.)*
- Con los recordatorios **desactivados**, no se programa ningún aviso nuevo y los pendientes se
  cancelan.
- La UI de configuración se integra en la **pantalla de Ajustes** de la feature 07 (ver
  *Technical Approach* / OQ-2), con etiquetas desde `strings.xml`.
- Un **install nuevo** arranca con un valor por defecto sensato (p. ej. avisos **activados**,
  *lead time* **10 min**).

### US-5 — Degradación con gracia si faltan permisos
**Como** persona usuaria que quizá no concede todos los permisos,
**quiero** que la app no se rompa si no doy permiso de notificaciones o de alarmas exactas,
**para** seguir usándola aunque los avisos sean menos precisos o no aparezcan.

**Criterios de aceptación:**
- Si **`POST_NOTIFICATIONS`** no está concedido (Android 13+), programar/disparar el aviso **no
  crashea**; simplemente no se muestra la notificación (mismo criterio de "degradar con gracia" de
  la feature 06).
- Si el permiso de **alarmas exactas** no está disponible (Android 12+/13+), la app **no crashea**:
  cae a una alarma **inexacta** (`setAndAllowWhileIdle` / ventana) que puede llegar con algo de
  holgura, y lo hace de forma transparente.
- En **API < 31** las alarmas exactas no requieren permiso especial y funcionan directamente.
- La app puede **informar** a la persona de que, para avisos puntuales, conviene conceder el permiso
  de alarmas exactas, y ofrecer un acceso a los ajustes del sistema
  (`ACTION_REQUEST_SCHEDULE_EXACT_ALARM`). *(Alcance mínimo; ver OQ-1.)*

---

## Acceptance Criteria (resumen verificable)

Criterios concretos y testeables para dar la feature por completada:

1. **Cálculo puro del instante del aviso:** existe una función pura (sin `Context` ni reloj propio),
   p. ej. `reminderTimeFor(task: Task, leadMillis: Long): Long?`, que devuelve
   `task.deadline − leadMillis` cuando hay `deadline`, y `null` cuando no lo hay. *(Test JVM
   exhaustivo: con/sin deadline, lead 0, lead mayor que el margen.)*
2. **Filtro "solo futuro":** una función pura decide, dado `(instanteAviso, now)`, si el aviso debe
   programarse (futuro) o descartarse (pasado). *(Test JVM: pasado, presente exacto, futuro.)*
3. **Selección de la lista a reprogramar:** dada una lista de `Task` y un `now`, una función pura
   devuelve los avisos a programar (tareas con `deadline` cuyo `deadline − lead` es futuro), con su
   id/tiempo. *(Test JVM contra listas variadas — usado por el arranque y por el rescheduler.)*
4. **Scheduler sobre `AlarmManager`:** existe un `ReminderScheduler` (o equivalente) con
   `schedule(task)` / `cancel(taskId)` que usa `setExactAndAllowWhileIdle` cuando puede, con
   `PendingIntent` `FLAG_IMMUTABLE` y **request code determinista** a partir de `Task.id`.
   *(Inspección + verificación en emulador; la parte pura de decisión se testea en JVM.)*
5. **Receiver del aviso:** un `BroadcastReceiver` (`exported="false"`) recibe la alarma, comprueba
   `areNotificationsEnabled()` y publica la notificación en el **canal nuevo** con un **id propio**
   (no `1001`). *(Inspección + verificación manual.)*
6. **Canal que alerta:** se crea un segundo canal (p. ej. `task_reminders`, `IMPORTANCE_HIGH`) vía
   el patrón `ensureChannel`, **sin** tocar el canal silencioso `tasks_pending` de la feature 06.
   *(Inspección + verificación manual: el aviso suena / hace heads-up, la notificación continua no.)*
7. **Reprogramación en `BOOT_COMPLETED`:** un `BroadcastReceiver` para `BOOT_COMPLETED`
   (`RECEIVE_BOOT_COMPLETED`) **delega** a un `OneTimeWorkRequest` que reprograma los avisos
   futuros. *(Inspección + verificación en emulador con `adb` simulando el broadcast de arranque.)*
8. **Acoplamiento al ciclo de vida de la tarea:** guardar/editar/borrar una tarea
   programa/reprograma/cancela su aviso — reutilizando el **patrón decorador** de
   `TaskSurfacesRefreshingRepository` (o un decorador análogo). *(Inspección + test del decorador
   contra un scheduler *fake*.)*
9. **Preferencia en DataStore:** `UserPreferencesRepository` expone el *lead time* y el *on/off* de
   recordatorios (clave nueva en `user_prefs`, con parse tolerante y valor por defecto), sin romper
   `name`/`onboarded`/`themeMode`. *(Test JVM contra el fake + inspección de la implementación.)*
10. **Textos externalizados + i18n:** título/cuerpo del aviso, etiquetas de la preferencia y
    `contentDescription` en `res/values/strings.xml` **y** `res/values-en/strings.xml`; los minutos
    de antelación usan `<plurals>`; la hora/fecha se formatea con el `Locale` del dispositivo
    (reutilizando `formatDeadlineForDisplay` de `TaskTiming.kt`). *(Inspección; sin literales.)*
11. **Manifest:** se declaran los permisos y receivers nuevos (ver *Dependencies*), sin romper los
    componentes existentes. *(Inspección del `AndroidManifest.xml`.)*
12. **Sin regresión:** la notificación continua (feature 06), el widget (05), Ajustes (07) e i18n
    (08) siguen funcionando. *(Tests existentes en verde + verificación manual.)*
13. **Build:** compila con `./gradlew :app:assembleDebug` (wrapper); los tests JVM pasan con
    `./gradlew :app:testDebugUnitTest`.
14. **Documentación:** se añade `tutorial/09-recordatorios.md` (español) cubriendo los conceptos
    nuevos con referencia al código real.

---

## Technical Approach (alto nivel)

> Estrategia orientativa; el diseño de detalle es responsabilidad de la fase de implementación
> (`mobile-engineer`). Se listan aquí las decisiones que afectan al alcance y a la reutilización.

**Mecanismo de disparo — recomendación: `AlarmManager` exacto (no WorkManager).**
Un recordatorio debe sonar en un **instante de reloj concreto**. WorkManager está pensado para
trabajo **diferible y agrupable**: su `PeriodicWorkRequest` tiene un mínimo de **15 min** (por eso
`TaskSurfacesRefreshWorker` refresca las superficies "más o menos cada 15 min", lo cual es aceptable
para un resumen pero **no** para "faltan 10 min"), y en Doze el sistema **agrupa y retrasa** los
`OneTimeWorkRequest` con retardo inicial. Por tanto:

- **Disparo del aviso → `AlarmManager` con `setExactAndAllowWhileIdle(...)`** (exacta y capaz de
  disparar en Doze), sobre un `PendingIntent` (`FLAG_IMMUTABLE`) que el sistema entrega a un
  `BroadcastReceiver` propio (`ReminderReceiver`, `exported="false"`) que publica la notificación.
- **WorkManager sí se usa, pero para la reprogramación masiva de arranque** (US-2): el
  `BootReceiver` (que tiene una ventana de ejecución muy corta) **encola un `OneTimeWorkRequest`**
  que lee las tareas de Room y reprograma los avisos futuros. Es trabajo de fondo diferible: el
  encaje natural de WorkManager. Esto **reutiliza** la dependencia `androidx.work:work-runtime-ktx`
  **ya presente** en el catálogo (feature 05) — sin dependencias nuevas.

**Permisos de alarmas exactas por versión (crítico, ver *Risks*):**
- **API < 31:** `setExactAndAllowWhileIdle` funciona **sin** permiso especial.
- **API 31/32:** `SCHEDULE_EXACT_ALARM` está **preconcedido** (pero es revocable);
  comprobar `AlarmManager.canScheduleExactAlarms()` antes de usar la variante exacta.
- **API 33+:** `SCHEDULE_EXACT_ALARM` **no** está preconcedido para la mayoría de apps → hay que
  comprobar `canScheduleExactAlarms()` y, si es `false`, o bien **degradar a inexacta**
  (`setAndAllowWhileIdle`), o bien **enviar a la persona a los ajustes del sistema** con
  `Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM`. La alternativa `USE_EXACT_ALARM` (preconcedida en
  instalación) existe, pero **Google Play la restringe** a apps cuya función *principal* sea
  alarma/reloj/calendario → decisión de política, ver **OQ-1**.
- **Recomendación de alcance:** declarar `SCHEDULE_EXACT_ALARM`, comprobar `canScheduleExactAlarms()`
  y **degradar con gracia** a inexacta cuando no esté; ofrecer (opcional, mínimo) un acceso a los
  ajustes del sistema. Enseñar el trade-off en la lección.

**Notificación del recordatorio (reutilizando el patrón de la feature 06, canal aparte):**
- Un **segundo canal** (p. ej. `task_reminders`, `IMPORTANCE_HIGH` → sonido + *heads-up*), creado
  con el **mismo patrón** `ensureChannel` de `TasksNotificationHelper`. **No** se reutiliza el canal
  silencioso `tasks_pending` (su importancia se congela al crearse y es deliberadamente muda).
- **Id de notificación por tarea** (derivado de `Task.id`, distinto de `TASKS_NOTIFICATION_ID = 1001`),
  para que varios avisos coexistan y para poder actualizar/cancelar el de una tarea concreta.
- La construcción de la notificación puede vivir junto a `TasksNotificationHelper` (o en un helper
  hermano en el paquete `ui/notification`), con **texto 100% desde `strings.xml`** y `<plurals>`
  para los minutos; el instante del vencimiento se formatea con `formatDeadlineForDisplay(deadline,
  locale)` de `TaskTiming.kt`.
- **Permiso `POST_NOTIFICATIONS`:** ya declarado y solicitado (`RequestNotificationPermissionEffect`).
  El receiver comprueba `NotificationManagerCompat.areNotificationsEnabled()` antes de publicar
  (degradación con gracia), igual que `TasksNotificationService`.
- **Deep-link al tocar:** reutilizar `MainActivity.EXTRA_OPEN_TASKS` (una sola receta "abrir en
  tareas" en toda la app).

**Modelo puro y testeable (patrón del proyecto):** concentrar la lógica en funciones puras JVM en
`domain/tasks` (o junto a `TaskTiming.kt`): `reminderTimeFor(task, leadMillis)`, el filtro
"solo futuro" y la selección de la lista a reprogramar. El scheduler y los receivers quedan como
**shells finos** de Android sobre esas funciones (mismo enfoque que `pendingRowsFor` /
`toNotificationModel`).

**Acoplamiento al ciclo de vida de la tarea (reutilizar el decorador):** hoy
`TaskSurfacesRefreshingRepository` (envuelve `TaskRepository` en `MainActivity`) refresca widget +
notificación en cada escritura. Esta feature necesita **programar/cancelar avisos** en `saveTask` /
`deleteTask`. Opciones: **(a)** extender ese decorador para que también hable con el
`ReminderScheduler`, o **(b)** añadir un **segundo decorador** análogo dedicado a recordatorios y
componerlos. Recomendación: **(b)** por separación de responsabilidades, con la misma técnica de la
feature 05. El scheduler se inyecta a mano (como el resto) en `MainActivity`.

**Preferencias (repaso de la feature 07):** ampliar `UserPreferences` con
`remindersEnabled: Boolean = true` y `reminderLeadMinutes: Int = 10` (o un `enum` de opciones), con
claves nuevas en `user_prefs` y parse tolerante — **misma interfaz, mismo fichero**, sin segundo
DataStore. La UI (US-4) se añade a la **pantalla de Ajustes** existente (`ui/settings`).

**Manifest:** añadir `RECEIVE_BOOT_COMPLETED`, `SCHEDULE_EXACT_ALARM` (y, si se decide OQ-1,
`USE_EXACT_ALARM`), y dos `<receiver>`: `ReminderReceiver` (`exported="false"`) y `BootReceiver`
(`exported="true"`, con `intent-filter` de `BOOT_COMPLETED`; sin export no recibe el broadcast del
sistema).

---

## Out of Scope

Esta feature **no** incluye:

- **Recordatorios para tareas sin `deadline`.** El aviso se calcula relativo al vencimiento; una
  tarea *solo duración* (sin `deadline`) no genera aviso en este MVP. *(Posible extensión futura:
  avisar al terminar el temporizador — fuera de alcance.)*
- **Múltiples avisos por tarea** (p. ej. "1 h antes" **y** "10 min antes") ni **lead time por
  tarea**. El MVP usa **un** *lead time* global por defecto. *(Ver OQ-4; ampliable después.)*
- **Recordatorios recurrentes / tareas repetitivas** (diarias, semanales). Las tareas actuales no
  son recurrentes; esto queda fuera.
- **Posponer ("snooze") o acciones en la notificación** (marcar hecha, +10 min desde el aviso).
- **Alarma a pantalla completa / sonido persistente estilo despertador** (`fullScreenIntent`,
  `USE_FULL_SCREEN_INTENT`). El recordatorio es una notificación *heads-up* normal, no una alarma de
  reloj.
- **Sincronización remota de recordatorios entre dispositivos** → relacionado con la **feature 11**
  (`docs/prompts/11-bbdd-remota.md`). Todo es local.
- **Tematizar la notificación del aviso** con la preferencia de tema de la feature 07 (esas
  superficies siguen el estilo del sistema).
- **Garantía absoluta de entrega en todos los fabricantes.** Algunos OEM (p. ej. MIUI/HyperOS,
  ciertos gestores de batería agresivos) pueden matar procesos o retrasar alarmas pese a usar la API
  correcta; el objetivo es usar bien las APIs de Android, no sortear cada personalización de OEM.
- **Nueva dependencia externa.** WorkManager y todo lo demás ya están en el proyecto (ver
  *Dependencies*).
- **Requisito pedagógico (Tutorial Methodology):** aunque la lección `tutorial/09-recordatorios.md`
  es **obligatoria antes de commitear**, su redacción detallada no forma parte de esta *spec*; aquí
  solo se fijan los conceptos que debe cubrir (ver *Overview* y *Deliverables*).

---

## Dependencies

- **Feature 04 — Tareas (dependencia dura):** requiere el modelo `Task` con `deadline` (epoch
  millis) y el `TaskRepository` / `RoomTaskRepository` / `NeverLateDatabase.getInstance(...)` ya en
  `master`. El recordatorio **lee** de ahí (dentro y fuera de la `Activity`, como hoy hace
  `TasksNotificationService`). No se reimplementa el modelo de tareas.
- **Feature 06 — Notificaciones (reutilización clave):** el permiso `POST_NOTIFICATIONS` **ya**
  está declarado en `AndroidManifest.xml` y se solicita con `RequestNotificationPermissionEffect`
  (`ui/notification/NotificationPermission.kt`); el patrón `ensureChannel` de
  `TasksNotificationHelper` se reutiliza para crear el **segundo** canal. El deep-link
  `MainActivity.EXTRA_OPEN_TASKS` se reutiliza para el toque en la notificación. **No** se reutiliza
  el canal silencioso `tasks_pending` ni el foreground service (un aviso no lo necesita).
- **Feature 05 — WorkManager (reutilización):** la dependencia `androidx.work:work-runtime-ktx`
  (`libs.androidx.work.runtime.ktx`, versión `2.10.0`) **ya** está en `gradle/libs.versions.toml`;
  la usa el `OneTimeWorkRequest` de reprogramación de arranque. El patrón **decorador** de
  `TaskSurfacesRefreshingRepository` es la base para acoplar programar/cancelar avisos a las
  escrituras de tareas.
- **Feature 07 — Ajustes + DataStore (reutilización):** `UserPreferencesRepository` /
  `DataStoreUserPreferencesRepository` / `UserPreferences` / `user_prefs` se **amplían** con las
  claves de recordatorios; la pantalla `ui/settings` alberga la nueva preferencia (US-4). Mismo
  patrón "interfaz + impl DataStore + fake en tests".
- **Feature 08 — i18n (restricción dura):** todo el texto visible del aviso y de la preferencia va
  en `res/values/strings.xml` **y** `res/values-en/strings.xml`; minutos con `<plurals>`;
  hora/fecha con `formatDeadlineForDisplay(..., locale)` de `data/tasks/TaskTiming.kt`. `java.time`
  ya está habilitado por *core library desugaring* (`minSdk = 24`).
- **Permisos / manifest nuevos (a declarar en esta feature):**
  - `android.permission.RECEIVE_BOOT_COMPLETED` (normal, concedido en instalación) — para el
    `BootReceiver`.
  - `android.permission.SCHEDULE_EXACT_ALARM` (API 31+) — alarmas exactas; comprobar
    `canScheduleExactAlarms()` en runtime. *(Opcional según OQ-1: `USE_EXACT_ALARM`, API 33+,
    sujeta a política de Google Play.)*
  - Dos `<receiver>`: `ReminderReceiver` (`exported="false"`) y `BootReceiver` (`exported="true"`
    con `intent-filter` de `BOOT_COMPLETED`).
- **Plataforma:** `minSdk = 24` / `targetSdk = 36` / `compileSdk = 36`. Comportamiento **por
  versión** a respetar: `POST_NOTIFICATIONS` runtime en API 33+; alarmas exactas sin permiso en
  API < 31 y con `canScheduleExactAlarms()` en API 31+; canales de notificación en API 26+
  (guarda `SDK_INT`, como ya hace `ensureChannel`).
- **Seguridad (requisito de MVP):** `PendingIntent` con `FLAG_IMMUTABLE`; `ReminderReceiver`
  `exported="false"` (nadie externo lo dispara); `BootReceiver` `exported="true"` **solo** para
  recibir `BOOT_COMPLETED` (sin lógica sensible en él, delega a WorkManager); ningún dato sale del
  dispositivo (local-only). Privacidad en lockscreen del aviso: ver OQ-5.
- **Sin dependencias nuevas en el catálogo:** si la implementación descubriera que falta algo,
  **parar y reportar** antes de añadirlo (Execution Policy), documentando la entrada en
  `gradle/libs.versions.toml` con `version.ref`.
- **Tooling/build:** debe compilar con `./gradlew :app:assembleDebug` y pasar
  `./gradlew :app:testDebugUnitTest` (wrapper). Si falta algún paquete del SDK, **parar y reportar**.

---

## Deliverables / Documentation (obligatorio antes de commitear)

- **Lección tutorial (Tutorial Methodology):** `tutorial/09-recordatorios.md` (español), progresiva
  sobre 04–08. Debe cubrir: **WorkManager vs. `AlarmManager`** (diferible/agrupable vs. instante
  exacto) y **exacto vs. inexacto**; `AlarmManager` + `PendingIntent` (`FLAG_IMMUTABLE`) +
  `BroadcastReceiver`; **permisos de alarmas exactas por versión** (`SCHEDULE_EXACT_ALARM`,
  `canScheduleExactAlarms()`, `USE_EXACT_ALARM`) y la degradación con gracia; el **segundo canal**
  que alerta frente al canal silencioso de la 06; **`BOOT_COMPLETED`** y por qué se delega a
  WorkManager; y la ampliación de `UserPreferencesRepository` con la preferencia de recordatorios.
- **Strings:** título/cuerpo del aviso (con `<plurals>` para los minutos), etiquetas de la
  preferencia en Ajustes y `contentDescription`, en `res/values/strings.xml` y
  `res/values-en/strings.xml` (sin literales en código).
- **`CLAUDE.md`:** actualizar **Structure** (nuevos componentes de recordatorios: scheduler +
  receivers, y el nuevo canal), la sección de **Permissions** (nuevos permisos y receivers) y, si
  aplica, el paquete nuevo. Confirmar con `/doc-check` que no hay dependencias nuevas en el catálogo.

---

## Testing notes

> Patrón del proyecto: **mover la lógica testeable a funciones puras JVM** y probar repositorio /
> decorador contra **fakes en memoria**. `AlarmManager`, los receivers y la publicación real de la
> notificación se verifican **manualmente en el emulador** (necesitan runtime de Android).

- **Tests unitarios JVM (`app/src/test/...`), el grueso — sin emulador:**
  - `reminderTimeFor(task, leadMillis)`: con `deadline` → `deadline − lead`; sin `deadline` → `null`;
    `lead = 0`; `lead` mayor que el margen (instante en el pasado).
  - Filtro **solo futuro** `(instante, now)`: pasado, presente exacto, futuro.
  - **Selección de la lista a reprogramar** dada `List<Task>` + `now`: mezcla de tareas con/sin
    deadline y con avisos pasados/futuros → solo los futuros con deadline.
  - **Repositorio de preferencias contra fake:** ampliar el fake de `UserPreferencesRepository` con
    `remindersEnabled`/`reminderLeadMinutes`; verificar lectura/escritura y que **no** altera
    `name`/`onboarded`/`themeMode`; parse tolerante de valores ausentes/erróneos → default.
  - **Decorador de recordatorios contra un `ReminderScheduler` fake:** `saveTask` con deadline →
    `schedule`; `saveTask` sin deadline → `cancel`; `deleteTask` → `cancel`.
- **Verificación manual / instrumentada (emulador):**
  - Crear una tarea con `deadline` a pocos minutos y *lead time* corto; **cerrar la app**; comprobar
    que el aviso **heads-up** aparece y suena, y que al tocarlo abre la lista de tareas.
  - Simular arranque: `adb shell am broadcast -a android.intent.action.BOOT_COMPLETED` (con las
    salvedades del emulador) y comprobar que los avisos futuros se reprograman.
  - Revocar `POST_NOTIFICATIONS` y/o el permiso de alarmas exactas y comprobar la **degradación con
    gracia** (sin crash; inexacta cuando toca).
  - Verificar que la notificación **continua** de la feature 06 (canal silencioso) y el aviso (canal
    que alerta) **coexisten** sin pisarse.
- **Comandos:**

```bash
# Tests unitarios (JVM, sin emulador)
./gradlew :app:testDebugUnitTest

# Build del APK de depuración
./gradlew :app:assembleDebug
```

---

## Risks

- **Permiso de alarmas exactas en Android 13+ (riesgo principal):** `SCHEDULE_EXACT_ALARM` no está
  preconcedido; sin él, un aviso "exacto" puede llegar tarde o requerir enviar a la persona a
  ajustes. *Mitigación:* comprobar `canScheduleExactAlarms()` y **degradar a inexacta** con
  transparencia; documentar el trade-off; decidir OQ-1 (¿reclamar `USE_EXACT_ALARM` bajo la política
  de Play, o quedarse en `SCHEDULE_EXACT_ALARM` + fallback?).
- **Doze / restricciones de batería:** en reposo el sistema retrasa el trabajo; solo
  `setExactAndAllowWhileIdle` dispara puntualmente en Doze, y aun así algunos OEM son agresivos.
  *Mitigación:* usar la variante *allow-while-idle*; declarar en Out of Scope que no se persigue
  sortear cada personalización de fabricante.
- **Se pierden las alarmas al reiniciar (por diseño de Android):** el sistema borra todas las
  alarmas al apagar. *Mitigación:* `BootReceiver` + reprogramación (US-2); recordar que el receiver
  tiene ventana corta → **delegar a WorkManager** (no bloquear su hilo).
- **Avisos duplicados o zombis al editar/borrar:** si el `PendingIntent`/request code no es
  determinista por `Task.id`, editar podría dejar dos alarmas, o borrar no cancelar la correcta.
  *Mitigación:* request code derivado de `Task.id` + `FLAG_UPDATE_CURRENT`/`cancel` explícito en el
  decorador (US-3), cubierto por tests del decorador contra un scheduler fake.
- **Confundir el canal que alerta con el canal silencioso de la feature 06:** reutilizar
  `tasks_pending` haría que el aviso **no sonara** (su importancia está congelada como muda).
  *Mitigación:* **canal nuevo** `IMPORTANCE_HIGH`; ids distintos; verificación manual de que ambos
  coexisten.
- **`POST_NOTIFICATIONS` denegado:** el aviso no aparece. *Mitigación:* comprobar
  `areNotificationsEnabled()` en el receiver y degradar con gracia (mismo criterio que la 06); la
  petición en contexto ya existe.
- **Sobrecarga didáctica alta:** la feature toca `AlarmManager` + permisos por versión +
  `BroadcastReceiver` + `BOOT_COMPLETED` + WorkManager + notificación que alerta + DataStore. Es la
  lección más densa hasta ahora. *Mitigación:* apoyarse fuerte en 04–08 (casi todo son **refuerzos**
  salvo `AlarmManager`/boot), mantener la lógica en funciones puras y la UI mínima; considerar
  recortar OQ-2/OQ-4 para no inflar el alcance.
- **Privacidad en lockscreen:** el aviso muestra el título de la tarea en la pantalla de bloqueo.
  *Mitigación:* decidir OQ-5 (mostrar título vs. versión redactada, reutilizando el patrón
  `setPublicVersion`/`VISIBILITY_PRIVATE` que la feature 06 ya dejó cableado).

---

## Preguntas abiertas (RESUELTAS — decisiones de 2026-07-05)

- **OQ-1 — Exactas vs. inexactas / política de Play → DECIDIDO: `SCHEDULE_EXACT_ALARM` + fallback.**
  Declarar solo `SCHEDULE_EXACT_ALARM`, comprobar `canScheduleExactAlarms()` y **degradar a inexacta**
  cuando no se conceda. **No** se reclama `USE_EXACT_ALARM` (evita el riesgo de política de Play). Es
  además el mejor material didáctico para enseñar el trade-off.
- **OQ-2 — Entrada en Ajustes → DECIDIDO: incluir la UI ahora.** La configuración (on/off + *lead
  time*) se añade a la pantalla de Ajustes de la feature 07 en **esta** feature (cierra US-4).
- **OQ-3 — Reprogramar al cambiar el *lead time* → DECIDIDO: solo avisos futuros.** Cambiar la
  preferencia aplica el nuevo valor a los avisos que se programen a partir de entonces; no se
  reprograman en bloque los ya existentes (más simple).
- **OQ-4 — ¿Un aviso o varios? → DECIDIDO: uno.** MVP con **un** *lead time* global por tarea.
  Varios avisos / *lead time* por tarea queda como ampliación futura.
- **OQ-5 — Privacidad del aviso en lockscreen → DECIDIDO: mostrar el título.** El aviso muestra el
  título de la tarea en la pantalla de bloqueo (coherente con la feature 06). Versión redactada
  queda fuera de alcance.
- **OQ-6 — Deadline ya muy cercano al crear → DECIDIDO: no avisar.** Si `deadline − lead` cae en el
  pasado, **no** se programa un aviso retroactivo (aunque el `deadline` aún sea futuro).

---

## Siguiente paso

Por favor, **revisa y aprueba** esta especificación (y las **preguntas abiertas** OQ-1…OQ-6) antes
de comenzar la implementación. Una vez aprobada, el flujo continúa (según el **Mandatory Workflow**
de `CLAUDE.md`): crear la rama `feature/reminders`, implementar con `mobile-engineer` (funciones
puras + `ReminderScheduler` sobre `AlarmManager` + `ReminderReceiver`/`BootReceiver` + segundo canal
+ ampliación de `UserPreferencesRepository`, reutilizando el permiso `POST_NOTIFICATIONS`, el patrón
`ensureChannel`, WorkManager y el patrón decorador), tests con `qa-engineer` (lógica pura de tiempo
+ selección de la lista + decorador/preferencias contra fakes) y la lección
`tutorial/09-recordatorios.md` antes de commitear.
