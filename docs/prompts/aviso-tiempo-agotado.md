# Feature — Avisar de verdad cuando se agota el tiempo de una tarea

Lee `CLAUDE.md` (**Tutorial Track (optional)** + **Mandatory Workflow** + **Design in the Workflow**)
y las lecciones previas (en especial la 04: la cuenta atrás y el temporizador de tarea; la 06: la
notificación de pantalla de bloqueo y sus dos canales; y sobre todo la 09: recordatorios con
`AlarmManager`, alarmas exactas y reprogramación tras reiniciar). Implementa **"que cuando el tiempo
de una tarea llegue a cero, la app avise de verdad"** siguiendo el flujo `/feature`.

> **Hoy no pasa absolutamente nada.** La cuenta atrás llega a cero y no suena, no vibra y no aparece
> ninguna notificación. Lo único que reacciona es `autoPauseTimedOut` en `TasksViewModel`, y solo
> corre **si la pantalla de Tareas está compuesta** — es decir, si ya estabas mirando. Peor: una tarea
> con duración estimada y **sin fecha límite no recibe ninguna alarma jamás**, porque `reminderTimeFor`
> parte de `task.deadline` y devuelve `null` si no lo hay. En una app para TDA/TDAH, un temporizador
> que se agota en silencio es el fallo funcional más caro que queda en el producto.

## Qué construir

- Un **aviso real en el momento en que el tiempo se agota**: notificación en el canal alertante, con
  sonido y vibración, igual que ya hace el recordatorio de antelación de la feature 09.
- Cubre los **dos caminos por los que una tarea se queda sin tiempo**: se alcanza el `deadline`, o se
  agota el temporizador en marcha (`timerEndsAt`).
- Las tareas de **solo duración, sin fecha límite** — que hoy son ciudadanas de segunda y no reciben
  nada — pasan a avisar cuando su temporizador termina.
- El aviso **sobrevive a un reinicio** del teléfono, como ya hacen los recordatorios.
- El aviso **desaparece** si la tarea se completa, se borra o se pausa el temporizador antes de llegar
  a cero: nada de avisar por algo que ya está hecho.

## Tutorial

Antes de escribir el spec, el flujo `/feature` **debe preguntar con `AskUserQuestion`** si esta
feature lleva lección en español (*Sí, con lección / No / Decidir al final*), y la respuesta se anota
en el campo `Tutorial:` del spec.

**Recomendación: Sí.** La 09 enseñó a programar *una* alarma por tarea; esta enseña qué pasa cuando
quieres *dos*, y ahí aparecen conceptos que la 09 no llegó a tocar:

- **La identidad de un `PendingIntent`:** por qué dos alarmas de la misma tarea se pisan en silencio
  si comparten `requestCode`, y qué distingue realmente a un `PendingIntent` de otro (`requestCode`,
  acción, datos — no los *extras*).
- **Alarmas ancladas a estado mutable:** un recordatorio relativo al `deadline` se programa una vez;
  uno anclado a `timerEndsAt` hay que **re**programarlo cada vez que la persona da al play o a la
  pausa. Es la diferencia entre un dato fijo y un dato que cambia bajo tus pies.
- **Dónde se engancha la reprogramación:** por qué el decorador de repositorio es el sitio correcto y
  el `ViewModel` no, y qué pasa hoy con `startTimer`/`pauseTimer`, que lo atraviesan sin hacer nada.

## Notas

- Rama sugerida: `feature/times-up-alert`.
- **El fallo silencioso que el spec debe resolver antes de escribir una línea.**
  `requestCodeFor(taskId) = taskId.toInt()`
  ([`ReminderScheduler.kt`](../../app/src/main/java/com/neverlate/ui/notification/ReminderScheduler.kt))
  **ya está ocupado** por el recordatorio de antelación, y `notificationIdFor` comparte ese mismo
  mapeo "una tarea, un entero". Una segunda alarma por tarea con el mismo `requestCode` **sustituye a
  la primera sin avisar** vía `FLAG_UPDATE_CURRENT`: se perdería el recordatorio previo y nadie se
  enteraría. El spec elige el esquema (códigos namespaced tipo `id*2` / `id*2+1`, o una `action`
  distinta en el `Intent`) y debe reflejarlo **a la vez** en el scheduler, en el id de notificación y
  en `BootRescheduleWorker` — si no, tras un reinicio resucita solo la mitad.
- **El segundo agujero: el temporizador no reprograma nada.** `ReminderSchedulingRepository.startTimer`
  y `pauseTimer` son hoy **pass-through puros**, así que una alarma anclada a `timerEndsAt` nunca se
  crearía al dar al play ni se cancelaría al pausar. Esta feature tiene que cerrarlo, y es la parte
  fácil de olvidar porque no se ve en la pantalla.
- **Extiende, no dupliques.** Ya existe toda la maquinaria: `ReminderScheduler` /
  `AlarmManagerReminderScheduler` (incluida su comprobación `canScheduleExactAlarms()` con
  degradación a inexacta), `ReminderReceiver` con su `goAsync()`, `ReminderNotificationHelper` y el
  canal alertante `task_reminders` (`IMPORTANCE_HIGH`), frente al silencioso `tasks_pending`. Lo único
  que falta de verdad es **una función pura** que responda "¿cuándo se queda sin tiempo esta tarea?"
  cubriendo los tres casos (deadline, temporizador en marcha, duración sin arrancar) — va en
  `domain/tasks/`, al lado de
  [`ReminderPlanning.kt`](../../app/src/main/java/com/neverlate/domain/tasks/ReminderPlanning.kt), y
  se prueba en la JVM.
- **Casos límite que el spec debe resolver explícitamente:** una tarea cuyo tiempo ya estaba agotado
  cuando se instala la actualización (¿avisa retroactivamente? no debería); una tarea con `deadline`
  **y** temporizador, donde los dos avisos podrían caer casi juntos; una tarea completada entre que se
  programa la alarma y se dispara; y qué texto se muestra, reutilizando `tasks_time_up` en vez de
  inventar otro.
- **Permiso:** no hace falta ninguno nuevo. `SCHEDULE_EXACT_ALARM`, `RECEIVE_BOOT_COMPLETED` y
  `POST_NOTIFICATIONS` ya están declarados. Sí hay que respetar la degradación a alarma inexacta que
  la 09 dejó montada, y el spec debe decir qué precisión se considera aceptable para un "se acabó el
  tiempo" (llegar tarde tres minutos a este aviso es peor que a un recordatorio de antelación).
- **Diseño (obligatorio en el spec):** no hay pantalla nueva, pero sí superficie visible. La sección
  **Visual & UX Design** debe cubrir el aspecto de la notificación (título, texto, si lleva acción),
  su relación con la notificación persistente de pendientes para que no se pisen visualmente, y
  añadir la fila correspondiente a `docs/mockups/README.md` (`—`, fuera del maquetado maestro).
- Sin backend, sin contrato, sin migración de Room (`timerEndsAt` y `remainingMillis` ya persisten),
  sin dependencia nueva.
- Ficheros:
  [`ReminderPlanning.kt`](../../app/src/main/java/com/neverlate/domain/tasks/ReminderPlanning.kt),
  [`ReminderScheduler.kt`](../../app/src/main/java/com/neverlate/ui/notification/ReminderScheduler.kt),
  [`ReminderReceiver.kt`](../../app/src/main/java/com/neverlate/ui/notification/ReminderReceiver.kt),
  [`ReminderNotificationHelper.kt`](../../app/src/main/java/com/neverlate/ui/notification/ReminderNotificationHelper.kt),
  [`ReminderSchedulingRepository.kt`](../../app/src/main/java/com/neverlate/ui/notification/ReminderSchedulingRepository.kt),
  [`BootRescheduleWorker.kt`](../../app/src/main/java/com/neverlate/ui/notification/BootRescheduleWorker.kt),
  [`TaskTiming.kt`](../../app/src/main/java/com/neverlate/data/tasks/TaskTiming.kt) (solo lectura),
  [`strings.xml`](../../app/src/main/res/values/strings.xml) +
  [`values-en/strings.xml`](../../app/src/main/res/values-en/strings.xml).
- Agentes: `mobile-engineer` (función pura del instante de agotamiento, esquema de `requestCode`,
  reprogramación en play/pausa, reinicio), `qa-engineer` (tests JVM exhaustivos de la función pura en
  los tres caminos y en sus fronteras; verificación manual de que el aviso suena con la app cerrada,
  sobrevive a un reinicio y **no** llega si la tarea se completa antes).
