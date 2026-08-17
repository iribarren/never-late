# Lección 09b — Una segunda alarma: identidad de `PendingIntent` y estado mutable

> Objetivo: la lección 09 programó **una** alarma por tarea — el recordatorio de "faltan N minutos".
> Esta feature añade una **segunda**: el aviso de "se acabó el tiempo", que suena tanto si la tarea
> tiene fecha límite como si es un temporizador de solo-duración ("concéntrate 25 minutos"). Sumar una
> segunda alarma por tarea suena trivial — es "lo mismo otra vez, con otro instante" — y esa apariencia
> es exactamente lo que escondía un bug real: las dos alarmas se pisaban **en silencio**, sin *crash*
> y sin log. Esta lección explica por qué, y de paso enseña el segundo problema que trae cualquier
> alarma anclada a un dato que **cambia**: hay que volver a programarla, y el sitio donde se hace
> importa tanto como el propio hecho de hacerlo.

## Conceptos que aprendes aquí

Partiendo de la lección 09 (`AlarmManager`, `PendingIntent` con *request code* determinista,
`BroadcastReceiver`, degradación de alarmas exactas, `BootRescheduleWorker`):

- **La identidad real de un `PendingIntent`.** Qué compara `AlarmManager` para decidir si dos
  `PendingIntent` son "el mismo" o "dos distintos" — y por qué los `extras` **no** cuentan.
- **Alarmas ancladas a estado mutable.** La diferencia entre programar algo una vez (porque el dato
  del que depende es fijo) y tener que **reprogramarlo** cada vez que ese dato cambia bajo tus pies.
- **Dónde se engancha una reprogramación transversal.** Por qué el decorador de repositorio, y no el
  `ViewModel`, es el único sitio por el que pasan *todas* las escrituras que pueden invalidar una
  alarma — y cómo un método que parece "terminado" (un simple *pass-through*) puede ser justo el que
  falta.

---

## 1. El bug que no se ve: identidad de `PendingIntent`

La lección 09 programaba la alarma del recordatorio así:

```kotlin
// Antes (lección 09)
fun requestCodeFor(taskId: Long): Int = taskId.toInt()

private fun buildPendingIntent(taskId: Long): PendingIntent {
    val intent = Intent(context, ReminderReceiver::class.java).apply {
        putExtra(ReminderReceiver.EXTRA_TASK_ID, taskId)
    }
    return PendingIntent.getBroadcast(
        context, requestCodeFor(taskId), intent,
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}
```

Un `PendingIntent`, una tarea, un `Int`. Perfecto para **una** alarma por tarea. El problema aparece en
cuanto quieres una **segunda**: si programas el aviso de "tiempo agotado" reutilizando exactamente esta
misma función — mismo `requestCodeFor(taskId)`, mismo `Intent` sin `action`, la única diferencia son los
extras — pasa esto:

> `PendingIntent.getBroadcast(context, requestCode, intent, FLAG_UPDATE_CURRENT)` decide si dos
> `PendingIntent` son "el mismo" comparando **tres cosas**: el componente destino, el `requestCode`, y
> los campos `filterEquals` del `Intent` — `action`, `data`, `type`, `categories` y el propio
> componente. **Los `extras` no forman parte de esa comparación**, ni aunque contengan datos distintos.

Con el código de arriba, el recordatorio (`LEAD_TIME`) y el aviso de tiempo agotado (`TIME_UP`) de la
misma tarea generan un `PendingIntent` **idéntico** a ojos de Android: mismo componente
(`ReminderReceiver`), mismo `requestCode` (`taskId.toInt()`), mismo `Intent` sin `action` — solo
cambian los extras, que no cuentan. `FLAG_UPDATE_CURRENT` entonces hace exactamente lo que su nombre
promete: **actualiza** el `PendingIntent` existente con el nuevo `Intent`. La segunda alarma no se suma
a la primera — la **sustituye**. Programar el aviso de tiempo agotado borraría, en silencio, el
recordatorio de los 10 minutos antes.

Y es un bug especialmente traicionero porque **no hay ningún síntoma que apunte a la causa**: no hay
excepción, no hay log de Android que diga "sustituí tu alarma", la app compila y corre igual. Lo único
observable es que, algún tiempo después, un recordatorio que debería haber sonado, simplemente no sonó.
Por eso la lección importa más que el propio fix: si el modelo mental de qué distingue a un
`PendingIntent` está mal, el bug puede volver a aparecer la próxima vez que alguien añada una tercera
alarma, con la misma invisibilidad.

### El arreglo: dos capas de protección independientes

La spec de esta feature (D1) decide corregirlo por **partida doble**, a propósito — no porque una sola
protección no bastara, sino porque cada una defiende contra un error distinto en el futuro:

```kotlin
// ui/notification/ReminderKind.kt
enum class ReminderKind(val slot: Int, val action: String) {
    LEAD_TIME(0, "com.neverlate.action.LEAD_TIME_REMINDER"),
    TIME_UP(1, "com.neverlate.action.TIME_UP"),
}
```

1. **Namespacing del *request code*** — cada tarea ya no tiene un único `Int`, tiene uno **por tipo de
   alarma**:

   ```kotlin
   // ui/notification/ReminderScheduler.kt
   fun requestCodeFor(taskId: Long, kind: ReminderKind): Int = taskId.toInt() * 2 + kind.slot
   ```

   `slot` vale 0 para `LEAD_TIME` y 1 para `TIME_UP`, así que multiplicar por 2 y sumar el slot separa
   los dos espacios de números sin colisión posible: la tarea 7 ocupa los códigos 14 y 15, nunca se
   solapa con la tarea 8 (16 y 17). Fíjate en que **no queda una sobrecarga de un solo argumento** —
   `requestCodeFor(taskId)` se elimina, no se deja como valor por defecto. Es deliberado: un valor por
   defecto dejaría que un sitio de llamada "olvidara" decir qué tipo de alarma quiere y aterrizara,
   silenciosamente, en el espacio de `LEAD_TIME`. Forzando el segundo parámetro, es el **compilador**
   quien impide ese olvido, no la disciplina de quien escriba la siguiente alarma.

2. **`Intent.action` distinto** — además del *request code*, cada alarma lleva su propia `action`:

   ```kotlin
   // ui/notification/ReminderScheduler.kt — buildPendingIntent
   val intent = Intent(context, ReminderReceiver::class.java).apply {
       action = kind.action
       putExtra(ReminderReceiver.EXTRA_TASK_ID, taskId)
   }
   ```

   Esto es **belt and braces**: aunque la aritmética del *request code* tuviera algún día un error, los
   dos `Intent` seguirían siendo distintos por `filterEquals` gracias a la `action`. Y de propina, le da
   a `ReminderReceiver` una forma de primera clase de saber qué alarma disparó — lee `intent.action` en
   vez de tener que inferirlo de qué extras trae:

   ```kotlin
   // ui/notification/ReminderReceiver.kt — onReceive
   val kind = ReminderKind.entries.firstOrNull { it.action == intent.action } ?: return
   ```

   Un `action` desconocido o ausente (por ejemplo, una alarma zombi programada por una versión anterior
   de la app, antes de que `ReminderKind` existiera) simplemente se descarta — otra vez, degradar con
   gracia en vez de adivinar.

El esquema tiene que aplicarse en **los cinco sitios** que tocan la identidad de una alarma
(`AlarmManagerReminderScheduler`, `ReminderNotificationHelper.notificationIdFor`,
`ReminderSchedulingRepository`, `SettingsViewModel` y `BootRescheduleWorker`) o solo funciona a medias
— exactamente el mismo tipo de fallo invisible, solo que ahora limitado a uno de los caminos.

---

## 2. Alarmas ancladas a estado mutable

Con la identidad arreglada, aparece una pregunta distinta: **¿cuándo hay que (re)programar cada
alarma?**

El recordatorio de la lección 09 se ancla al `deadline` de la tarea. `deadline` es un dato que, una vez
puesto, no cambia solo — cambia únicamente si el usuario edita la tarea, y **eso ya pasa por
`saveTask`**, que ya reprograma. Es decir: "prográmalo una vez, en el momento en que se escribe el
dato del que depende" es una regla correcta para una alarma anclada a un dato **fijo**.

El aviso de tiempo agotado (`TIME_UP`) es distinto de raíz: su ancla no es `deadline` — es
`min(timerEndsAt, deadline)` cuando hay un temporizador corriendo, y `timerEndsAt` **se mueve** cada
vez que el usuario le da a *play* o a *pausa*, sin que nadie llame a `saveTask`:

```kotlin
// domain/tasks/TimeUpPlanning.kt
fun timeUpInstantFor(task: Task): Long? {
    if (task.completedAt != null || task.deleted) return null
    val timerEndsAt = task.timerEndsAt
    val deadline = task.deadline
    return when {
        timerEndsAt != null && deadline != null -> minOf(timerEndsAt, deadline)
        timerEndsAt != null -> timerEndsAt
        deadline != null -> deadline
        else -> null
    }
}
```

Pulsar *play* en una tarea de solo-duración escribe un `timerEndsAt` **nuevo** (`now +` lo que quede de
la duración estimada); pulsar *pausa* lo pone a `null`. Cada una de esas dos acciones mueve el instante
en que "se acaba el tiempo" — pero ninguna de las dos pasa por `saveTask`, tienen sus propios métodos
en `TaskRepository`: `startTimer` y `pauseTimer`.

> **Concepto — dato fijo vs. dato que cambia bajo tus pies.** Una alarma anclada a un dato fijo se
> programa una vez, en el punto donde ese dato se escribe, y ya está: el dato no vuelve a moverse por
> su cuenta. Una alarma anclada a un dato que **el propio flujo normal de uso de la app** cambia con
> frecuencia (aquí, cada *play*/*pausa*) necesita reprogramarse en **cada** uno de esos puntos, o
> quedará apuntando a un instante que ya no es cierto. Tratar el segundo caso con la disciplina del
> primero — "ya la programé una vez, misión cumplida" — es un error de categoría, no un descuido menor:
> la alarma sigue viva, pero apunta al *timerEndsAt* de la vez anterior.

---

## 3. El sitio donde faltaba el enganche: `startTimer` y `pauseTimer`

Antes de esta feature, `ReminderSchedulingRepository` — el decorador que la lección 09 ya presentó
como "el decorador de recordatorios" — dejaba estos dos métodos como *pass-throughs* puros:

```kotlin
// Antes de esta feature
override suspend fun startTimer(id: Long) = delegate.startTimer(id)
override suspend fun pauseTimer(id: Long) = delegate.pauseTimer(id)
```

Nótese que **compilan**, **pasan** cualquier test que solo compruebe que la tarea arranca o se pausa, y
**no rompen nada visible en pantalla**. Es exactamente el tipo de código que "parece terminado": cumple
su contrato — delegar la llamada — sin dar ninguna señal de que le falta algo. Eso es lo que la spec de
esta feature llama, literalmente, "la parte fácil de olvidar: no tiene ninguna superficie de UI". Nada
en la lista de tareas, ni en la barra de progreso, ni en el widget, revela que el aviso de tiempo
agotado sigue apuntando al `timerEndsAt` de antes de la última pausa.

El arreglo añade la reprogramación explícita en los dos sitios:

```kotlin
// ui/notification/ReminderSchedulingRepository.kt — después
override suspend fun startTimer(id: Long) {
    delegate.startTimer(id)          // writes the new timerEndsAt
    rescheduleTimeUp(id)             // must re-read the task: the instant changed underneath us
}

override suspend fun pauseTimer(id: Long) {
    delegate.pauseTimer(id)
    rescheduleTimeUp(id)             // re-read yields timerEndsAt == null -> cancel only
}
```

Dos detalles no son casualidad:

- **`rescheduleTimeUp` recibe el `id`, no la `Task`.** A diferencia de `saveTask`, que ya tiene la
  tarea completa en la mano, `startTimer`/`pauseTimer` solo reciben un `Long`. Así que
  `rescheduleTimeUp(id: Long)` **relee** la tarea de `delegate.observeTask(id).first()` — el mismo
  patrón que este decorador ya usa para leer preferencias — para ver el `timerEndsAt` que el `delegate`
  acaba de escribir, no uno obsoleto capturado antes de la llamada.
- **Una sola pausa cancela sola.** Tras `pauseTimer`, la relectura trae `timerEndsAt == null`;
  `timeUpInstantFor` devuelve `null` si no hay deadline, así que `rescheduleTimeUp` cancela la alarma
  anterior y no programa ninguna nueva — sin ningún `if` especial para "caso pausa". Es la misma forma
  "cancela siempre, programa solo si aún tiene sentido" que la lección 09 ya usaba para editar una
  tarea; aquí se reutiliza sin cambios.

### Por qué aquí, y no en el `ViewModel`

Sería tentador poner la reprogramación en `TasksViewModel`, justo donde vive la función que llama a
`startTimer`/`pauseTimer` desde la pantalla. Es la opción equivocada, y por una razón estructural, no
de estilo:

> **Concepto — el decorador es el único sitio por el que pasa *todo*.** `ReminderSchedulingRepository`
> envuelve `TaskRepository` — cada escritura que atraviesa la app pasa por él: la pantalla de tareas,
> los futuros caminos de escritura del widget, la reconciliación del motor de sincronización, un
> `Worker` en segundo plano. Un `TasksViewModel` que reprogramara la alarma cubriría exactamente **una**
> de esas rutas — precisamente la que menos lo necesita, porque si el usuario está mirando la pantalla
> de tareas ya está viendo la cuenta atrás, y todas las demás rutas se quedarían con el bug intacto sin
> que ningún test de pantalla lo revelara.

Es el mismo argumento que ya sostiene `di/WidgetEntryPoint.kt` (lección 13e): la cadena de decoradores
de `TaskRepository` no es un detalle de implementación, es **el** sitio donde vive cada responsabilidad
transversal. Añadir una tercera vía de escritura en el futuro (por ejemplo, una acción "posponer" desde
la propia notificación) heredaría la reprogramación gratis, sin tocar el decorador ni el `ViewModel`,
precisamente porque pasa por `TaskRepository`.

---

## Repaso: ficheros de la feature

**Nuevo**
- [`ui/notification/ReminderKind.kt`](../app/src/main/java/com/neverlate/ui/notification/ReminderKind.kt) —
  el enum `LEAD_TIME`/`TIME_UP` con su `slot` y su `action`.
- [`domain/tasks/TimeUpPlanning.kt`](../app/src/main/java/com/neverlate/domain/tasks/TimeUpPlanning.kt) —
  `timeUpInstantFor` (el instante puro, sección 2) y `timeUpAlertsToSchedule` (el contrapunto para
  arranque/reinicio, reutilizando `ReminderPlan`/`isReminderInFuture` de la lección 09).

**Modificados**
- [`ui/notification/ReminderScheduler.kt`](../app/src/main/java/com/neverlate/ui/notification/ReminderScheduler.kt) —
  `schedule`/`cancel` ahora piden un `ReminderKind`; `requestCodeFor(taskId, kind)` reemplaza a la
  versión de un solo argumento (sección 1).
- [`ui/notification/ReminderSchedulingRepository.kt`](../app/src/main/java/com/neverlate/ui/notification/ReminderSchedulingRepository.kt) —
  `startTimer`/`pauseTimer` reprograman (sección 3); `saveTask`/`deleteTask` gestionan ambos tipos de
  alarma.
- [`ui/notification/ReminderReceiver.kt`](../app/src/main/java/com/neverlate/ui/notification/ReminderReceiver.kt) —
  bifurca por `intent.action`, y añade una guarda de "obsoleta" en la entrega (D8: si el aviso llega
  tarde pero la tarea ya lleva más de un minuto de margen de nuevo, se descarta — pertenece a un plan
  ya superado).
- [`ui/notification/BootRescheduleWorker.kt`](../app/src/main/java/com/neverlate/ui/notification/BootRescheduleWorker.kt) —
  reprograma **los dos** tipos tras un reinicio, y ahora también se encola en cada arranque en frío de
  la app (no solo tras `BOOT_COMPLETED`), para que instalar la actualización no deje el aviso de tiempo
  agotado sin armar hasta el próximo reinicio o la próxima edición de cada tarea.
- [`ui/settings/SettingsViewModel.kt`](../app/src/main/java/com/neverlate/ui/settings/SettingsViewModel.kt) —
  el bucle de "apagar recordatorios" cancela ambos tipos por tarea.

No hay dependencias nuevas, ni permiso nuevo, ni canal de notificación nuevo: todo reutiliza la
carcasa de `AlarmManager`/`BroadcastReceiver`/canal `task_reminders` que ya construyó la lección 09.

## Lo que te llevas

- La identidad de un `PendingIntent` bajo `FLAG_UPDATE_CURRENT` es **componente + request code +
  `filterEquals` del `Intent`** (`action`, `data`, `type`, `categories`) — nunca los extras. Dos
  `PendingIntent` que solo difieren en sus extras son, para Android, el mismo, y el segundo sustituye
  al primero sin avisar.
- Cuando el fallo de un bug es *nada visible* — ni *crash*, ni log, solo un aviso que un día no suena —
  el mejor arreglo no es solo el parche puntual, es tener el modelo mental correcto para que no vuelva
  a pasar con la próxima alarma que alguien añada. Por eso el arreglo aquí es doble (request code
  namespaced **y** `action` distinta) y elimina la sobrecarga insegura en vez de dejarla con un valor
  por defecto.
- No todas las alarmas se programan igual: una anclada a un dato **fijo** se programa una vez, en el
  punto donde ese dato se escribe. Una anclada a un dato que **cambia con el uso normal de la app**
  necesita reprogramarse en cada uno de esos puntos — tratarla como la primera es un error de
  categoría, no un detalle menor.
- Un método que se limita a delegar (`= delegate.foo(id)`) puede parecer terminado precisamente porque
  compila y no rompe nada visible. La pregunta correcta no es "¿delega bien?" sino "¿hay algún dato
  aguas abajo de esta llamada del que dependa algo más?".
- En una cadena de decoradores, la responsabilidad transversal va en el decorador — el único sitio por
  el que pasa *toda* escritura —, no en el `ViewModel` de la única pantalla que hoy dispara esa
  escritura. Es la misma lección que la 13e ya enseñó sobre "qué capa", aplicada ahora a "en qué
  método".
