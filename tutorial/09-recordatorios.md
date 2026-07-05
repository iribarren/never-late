# Lección 09 — Recordatorios: avisos puntuales antes del vencimiento

> Objetivo: que la app **avise** un rato antes de que venza una tarea (p. ej. "faltan 10 minutos")
> con una **notificación que suena**, aunque la app esté **cerrada** o el proceso muerto, y que esos
> avisos **sobrevivan a un reinicio** del teléfono. Es la primera feature que necesita disparar algo
> en un **instante de reloj concreto**, y para eso WorkManager (lecciones 05 y 06) no basta: aparece
> **`AlarmManager`** con alarmas exactas. Por el camino aprendemos **`BroadcastReceiver`**,
> **`PendingIntent`** para un broadcast, los **permisos de alarmas exactas** por versión de Android,
> un **segundo canal de notificación** que sí alerta (en contraste con el silencioso de la 06), y a
> **reprogramar al arrancar** con `BOOT_COMPLETED`. Reutilizamos casi todo lo anterior: el permiso
> `POST_NOTIFICATIONS`, el patrón `ensureChannel`, el deep-link a tareas, el patrón decorador y el
> `DataStore` de ajustes.

## Conceptos que aprendes aquí

Partiendo de las lecciones 04 (Room + `Flow`, repositorio tras interfaz, funciones puras de tiempo,
reloj de pared), 05 (función pura de mapeo, patrón **decorador**, **WorkManager**, `PendingIntent`/
deep-link), 06 (notificaciones, **canales**, `POST_NOTIFICATIONS`, degradar con gracia) y 07
(**DataStore** de preferencias + pantalla de Ajustes):

- **Trabajo diferido vs. trabajo puntual — el contraste central de la lección.** Cuándo usar
  **WorkManager** ("hazlo en algún momento, ahorrando batería") y cuándo **`AlarmManager`** ("hazlo
  en este instante exacto, aunque el teléfono duerma").
- **Alarmas exactas vs. inexactas** (`setExactAndAllowWhileIdle` vs. `setAndAllowWhileIdle`) y su
  coste en batería y permisos.
- **`AlarmManager` + `PendingIntent` + `BroadcastReceiver`:** programar un aviso que el sistema
  entrega a un receiver cuando llega la hora, con un **request code determinista** para poder
  reemplazarlo/cancelarlo.
- **Permisos de alarmas exactas por versión** (`SCHEDULE_EXACT_ALARM`, `canScheduleExactAlarms()`)
  y la **degradación con gracia** cuando no están.
- **Un segundo canal que alerta** (`IMPORTANCE_HIGH`, con sonido y *heads-up*), frente al canal
  silencioso de la lección 06 — y por qué la importancia de un canal se **congela** al crearlo.
- **`goAsync()`** en un `BroadcastReceiver` para hacer un trabajo corto asíncrono sin que el sistema
  mate el proceso.
- **Reprogramar al reiniciar** (`BOOT_COMPLETED`) y por qué el receiver de arranque **delega** en
  WorkManager en vez de hacer el trabajo él mismo.
- **Componer dos decoradores** de repositorio y **ampliar** el `DataStore` de la lección 07 con una
  preferencia nueva sin crear un segundo almacén.

---

## 1. El reparto de piezas

Todo lo nuevo vive (casi) en el paquete que ya conocíamos de la lección 06,
`com.neverlate.ui.notification`, más una función pura en `com.neverlate.domain.tasks`:

- `domain/tasks/ReminderPlanning.kt` — la **lógica pura** (sin Android): cuándo debe sonar el aviso,
  si aún tiene sentido programarlo, y qué avisos hay que reprogramar tras un reinicio. Es la parte
  testeable en JVM.
- `ui/notification/ReminderScheduler.kt` — la **interfaz** `ReminderScheduler` y su implementación
  real `AlarmManagerReminderScheduler`: la carcasa Android que habla con `AlarmManager`.
- `ui/notification/ReminderReceiver.kt` — el `BroadcastReceiver` que el sistema dispara cuando llega
  la hora, y que publica la notificación.
- `ui/notification/ReminderNotificationHelper.kt` — el **helper** que crea el segundo canal y
  construye la notificación del aviso (hermano de `TasksNotificationHelper`).
- `ui/notification/ReminderSchedulingRepository.kt` — el **segundo decorador** que engancha
  programar/cancelar avisos al ciclo de vida de la tarea.
- `ui/notification/BootReceiver.kt` + `ui/notification/BootRescheduleWorker.kt` — el receiver de
  `BOOT_COMPLETED` y el worker de WorkManager al que delega la reprogramación.

Y **reutiliza** de lecciones anteriores: el permiso `POST_NOTIFICATIONS` y
`RequestNotificationPermissionEffect` (06), el patrón `ensureChannel` (06), el deep-link
`MainActivity.EXTRA_OPEN_TASKS` (05/06), el patrón decorador de `TaskSurfacesRefreshingRepository`
(05/06), WorkManager (05), y el `UserPreferencesRepository`/`user_prefs` con su pantalla de Ajustes
(07).

---

## 2. El problema nuevo: un instante de reloj concreto

Hasta ahora todo el "trabajo de fondo" del proyecto lo hacía **WorkManager**: el widget (05) y la
notificación continua (06) se refrescan "más o menos cada 15 minutos" con un `PeriodicWorkRequest`.
Eso está bien para un **resumen de estado**: da igual que se actualice a los 14 o a los 16 minutos.

Un recordatorio es otra cosa. "Avísame **10 minutos antes** de que venza" solo tiene sentido si suena
**a esa hora**, no "en algún momento del próximo cuarto de hora". Y WorkManager, por diseño, **no**
garantiza un instante:

- Su `PeriodicWorkRequest` tiene un **mínimo de 15 minutos** de periodo.
- En **Doze** (el modo de ahorro cuando el teléfono lleva un rato quieto) el sistema **agrupa y
  retrasa** el trabajo diferible para no despertar la CPU constantemente.

La herramienta correcta para "ejecuta esto en tal instante de reloj, aunque el móvil duerma" es
**`AlarmManager`** con una **alarma exacta**. Ese es el corazón de esta lección.

> **Concepto — elige la herramienta por su garantía, no por costumbre.** WorkManager y `AlarmManager`
> no compiten: uno promete *"se hará, ahorrando batería"* y el otro *"se hará en este instante"*.
> Usar `AlarmManager` para todo agotaría la batería; usar WorkManager para un aviso puntual lo haría
> llegar tarde. En esta feature usamos **los dos**, cada uno donde encaja (ver sección 8).

---

## 3. La lógica pura: `ReminderPlanning.kt`

Igual que en las lecciones 04–06, sacamos **toda la decisión** a funciones puras de Kotlin, sin un
solo import de Android, para poder testearlas en JVM sin emulador. La carcasa (`AlarmManager`,
receivers, notificación) queda lo más fina posible.

```kotlin
// domain/tasks/ReminderPlanning.kt
private const val MILLIS_PER_MINUTE = 60_000L

fun minutesToMillis(minutes: Int): Long = minutes * MILLIS_PER_MINUTE

/** El instante (epoch millis) en que debe sonar el aviso: leadMillis antes del deadline.
 *  null si la tarea no tiene deadline (una tarea solo-duración no recibe aviso en este MVP). */
fun reminderTimeFor(task: Task, leadMillis: Long): Long? =
    task.deadline?.let { deadline -> deadline - leadMillis }

/** Solo vale la pena programar un aviso si su instante sigue estrictamente en el futuro. */
fun isReminderInFuture(reminderAtMillis: Long, now: Long): Boolean = reminderAtMillis > now

data class ReminderPlan(val taskId: Long, val triggerAtMillis: Long)

/** Todos los avisos a programar dado el estado actual: tareas con deadline cuyo instante de aviso
 *  aún es futuro. Lo usa el reprogramado de arranque (sección 8). */
fun remindersToSchedule(tasks: List<Task>, now: Long, leadMillis: Long): List<ReminderPlan> =
    tasks.mapNotNull { task ->
        reminderTimeFor(task, leadMillis)
            ?.takeIf { reminderAt -> isReminderInFuture(reminderAt, now) }
            ?.let { reminderAt -> ReminderPlan(task.id, reminderAt) }
    }
```

Fíjate en `isReminderInFuture` con `>` **estricto**: implementa la **decisión OQ-6** aprobada en la
spec — si al crear una tarea el instante `deadline − lead` **ya ha pasado** (tarea creada con muy
poco margen), **no** programamos un aviso retroactivo. Y `reminderTimeFor` no "recorta" nada: hace la
resta tal cual y deja que quien llame decida con `isReminderInFuture`. Separar "calcular" de "decidir"
es lo que hace estas funciones fáciles de testear caso por caso.

---

## 4. `AlarmManager` + `PendingIntent`: el `ReminderScheduler`

Como con `TaskRepository` (lección 04), declaramos primero una **interfaz**, para poder inyectar un
*fake* en los tests sin un `AlarmManager` real (que necesita runtime de Android):

```kotlin
// ui/notification/ReminderScheduler.kt
interface ReminderScheduler {
    fun schedule(taskId: Long, triggerAtMillis: Long)
    fun cancel(taskId: Long)
}
```

La implementación real habla con `AlarmManager`:

```kotlin
class AlarmManagerReminderScheduler(private val context: Context) : ReminderScheduler {

    private val alarmManager get() = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    override fun schedule(taskId: Long, triggerAtMillis: Long) {
        val pendingIntent = buildPendingIntent(taskId)

        val canScheduleExact =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()

        if (canScheduleExact) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
        } else {
            // Degradación con gracia (US-5): la misma alarma, sin la garantía exacta.
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
        }
    }

    override fun cancel(taskId: Long) = alarmManager.cancel(buildPendingIntent(taskId))
    // ...
}
```

Tres conceptos nuevos aquí:

- **`RTC_WAKEUP`**: la alarma se expresa en **hora de reloj** (epoch millis, la misma convención que
  `Task.deadline`) y **despierta el dispositivo** si está dormido. (La otra familia, `ELAPSED_*`,
  cuenta desde el arranque; no nos sirve para "a las 20:30".)
- **`...AndAllowWhileIdle`**: sin este sufijo, en Doze la alarma se aplaza. Con él, el sistema la deja
  sonar aunque el teléfono esté en reposo (con un límite de frecuencia razonable).
- **exacta vs. inexacta**: `setExactAndAllowWhileIdle` dispara en el instante pedido;
  `setAndAllowWhileIdle` dispara "cerca" (el sistema elige el momento para agrupar). Caemos a la
  inexacta solo cuando no tenemos permiso para la exacta (sección 5).

### El `PendingIntent` y el request code determinista

Un `PendingIntent` es un "intent enlatado" que **otro proceso** (aquí, el sistema) disparará **en
nuestro nombre** más tarde. Para el aviso, apunta a nuestro `ReminderReceiver`:

```kotlin
private fun buildPendingIntent(taskId: Long): PendingIntent {
    val intent = Intent(context, ReminderReceiver::class.java).apply {
        putExtra(ReminderReceiver.EXTRA_TASK_ID, taskId)
    }
    return PendingIntent.getBroadcast(
        context,
        requestCodeFor(taskId),      // ← clave: identidad por tarea
        intent,
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}

// La identidad "una tarea → un int", compartida con el id de la notificación.
fun requestCodeFor(taskId: Long): Int = taskId.toInt()
```

- **`FLAG_IMMUTABLE`** es **obligatorio** desde API 31 para cualquier `PendingIntent` que no vayamos a
  rellenar después: impide que otra app modifique su intent interno (seguridad, requisito de MVP).
- **El request code determinista** (derivado de `Task.id`) es lo que hace que `AlarmManager` sepa que
  dos alarmas de la misma tarea son **la misma**: reprogramar una tarea (`FLAG_UPDATE_CURRENT`)
  **reemplaza** su alarma en vez de acumular una segunda, y `cancel` apunta exactamente a la alarma de
  esa tarea. Si usáramos un contador incremental o una constante fija, editar dejaría avisos zombis o
  cancelaría el equivocado. Room asigna ids secuenciales desde 1, así que `taskId.toInt()` no colisiona
  en la práctica.

---

## 5. Permisos de alarmas exactas (por versión de Android)

Las alarmas exactas gastan batería (obligan a despertar el sistema en un momento concreto), así que
Android las ha ido restringiendo con cada versión. El comportamiento **depende de la API**, y hay que
tratarlo con guardas de `SDK_INT`:

- **API < 31:** las exactas funcionan **sin permiso especial**. Nada que pedir.
- **API 31/32:** el permiso `SCHEDULE_EXACT_ALARM` viene **preconcedido**, pero el usuario/sistema
  puede **revocarlo** → hay que comprobar `alarmManager.canScheduleExactAlarms()` **en runtime** antes
  de usar la variante exacta.
- **API 33+:** `SCHEDULE_EXACT_ALARM` **no** está preconcedido para la mayoría de apps → mismo
  `canScheduleExactAlarms()`, y si es `false`, o **degradamos a inexacta** o **enviamos** a la persona
  a los ajustes del sistema.

Existe otro permiso, `USE_EXACT_ALARM` (API 33+), que sí viene concedido de fábrica — pero **Google
Play lo restringe** a apps cuya función *principal* sea alarma/reloj/calendario. Una app de tareas no
lo es, así que **no lo declaramos** (esa fue la **decisión OQ-1** aprobada): declaramos solo
`SCHEDULE_EXACT_ALARM` y **degradamos con gracia**. Es, además, el mejor material para entender el
trade-off.

Esa comprobación es exactamente el `canScheduleExact` de la sección 4. Y en Ajustes ofrecemos un
acceso opcional a la pantalla del sistema que concede el permiso:

```kotlin
// ui/settings/SettingsScreen.kt — ExactAlarmPermissionNotice (resumen)
if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return          // API < 31: nada que pedir
val alarmManager = context.getSystemService(AlarmManager::class.java)
if (alarmManager == null || alarmManager.canScheduleExactAlarms()) return   // ya concedido: no molestar
// …si no, mostramos un aviso + botón que abre:
Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}"))
```

> **Concepto — "degradar con gracia", otra vez.** Igual que la lección 06 con `POST_NOTIFICATIONS`, la
> app **nunca revienta** por falta de permiso: si no hay alarma exacta, cae a inexacta (el aviso puede
> llegar unos minutos tarde) y sigue funcionando. Programar la app para el peor caso de permisos es la
> postura correcta.

---

## 6. El segundo canal (que sí alerta) y el `ReminderReceiver`

La lección 06 creó un canal **silencioso a propósito** (`tasks_pending`, `IMPORTANCE_DEFAULT`, sin
sonido ni vibración), porque era un **resumen continuo** que se reemitía cada pocos minutos: molestar
en cada refresco sería insoportable. Un recordatorio es **lo contrario**: es un aviso puntual que
**debe** sonar. Por eso creamos un **segundo canal**, con el mismo patrón `ensureChannel` pero
`IMPORTANCE_HIGH`:

```kotlin
// ui/notification/ReminderNotificationHelper.kt
const val REMINDER_NOTIFICATION_CHANNEL_ID = "task_reminders"

fun ensureChannel(context: Context) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        val channel = NotificationChannelCompat.Builder(
            REMINDER_NOTIFICATION_CHANNEL_ID,
            NotificationManagerCompat.IMPORTANCE_HIGH,   // ← sonido + heads-up flotante
        )
            .setName(context.getString(R.string.reminder_channel_name))
            .setDescription(context.getString(R.string.reminder_channel_description))
            .build()
        NotificationManagerCompat.from(context).createNotificationChannel(channel)
    }
}
```

> ⚠️ **La importancia de un canal se "congela" al crearse** (lo vimos en la 06). Por eso **no**
> reutilizamos `tasks_pending`: su importancia ya está fijada como muda y no podríamos "subirla" para
> el aviso. Un canal **nuevo**, con id nuevo, es la única forma de tener un canal que alerte conviviendo
> con el silencioso. Ambos coexisten sin pisarse: canales distintos, e **ids de notificación
> distintos** (el aviso usa `notificationIdFor(taskId)`, no el `1001` fijo de la notificación continua).

La notificación se construye con texto 100 % desde `strings.xml` (feature 08): el cuerpo usa un
`<plurals>` para los minutos y `formatDeadlineForDisplay(deadline, locale)` para la hora, ya
localizada:

```kotlin
val minutesRemaining = ((deadline - now) / MILLIS_PER_MINUTE).coerceAtLeast(0L).toInt()
val body = context.resources.getQuantityString(
    R.plurals.reminder_notification_body, minutesRemaining, minutesRemaining,
    formatDeadlineForDisplay(deadline, locale),
)   // → "Vence en 10 minutos · 24/12/2026 20:30"
```

Un detalle fino: los minutos restantes se **recalculan** en el momento de construir la notificación
(`deadline − now`), **no** se leen del lead time con que se programó la alarma. Así, si la alarma
llegó un poco tarde (fallback inexacto), el texto sigue siendo veraz.

### El `ReminderReceiver` y `goAsync()`

Cuando llega la hora, el sistema dispara nuestro `BroadcastReceiver`. Un receiver tiene una regla
dura: su `onReceive` debe **volver casi al instante**, y **no** tiene un scope de corrutinas como un
`ViewModel`. Pero nosotros necesitamos hacer una lectura de Room y publicar una notificación. La
válvula de escape del sistema para esto es **`goAsync()`**:

```kotlin
// ui/notification/ReminderReceiver.kt
override fun onReceive(context: Context, intent: Intent) {
    val taskId = intent.getLongExtra(EXTRA_TASK_ID, NO_TASK_ID)
    if (taskId == NO_TASK_ID) return

    val pendingResult = goAsync()                 // "aún no he terminado, no me mates"
    CoroutineScope(Dispatchers.IO).launch {
        try {
            showReminder(context, taskId)         // lee la tarea de Room y notifica
        } finally {
            pendingResult.finish()                // "ahora sí he terminado"
        }
    }
}
```

`goAsync()` mantiene el proceso vivo unos segundos más para terminar un trabajo **corto** en segundo
plano. Es imprescindible llamar a `finish()` al acabar; si no, Android acabaría matando el proceso
mientras "espera" por el receiver.

Dentro de `showReminder` **releemos la tarea de Room** (el receiver puede correr con la app cerrada,
sin repositorio vivo que reutilizar), comprobamos `areNotificationsEnabled()` para **degradar con
gracia** si falta `POST_NOTIFICATIONS`, y si la tarea aún tiene deadline, publicamos.

---

## 7. Enganchar avisos al ciclo de vida de la tarea: un segundo decorador

Editar una tarea debe **reprogramar** su aviso; borrarla, **cancelarlo**. La lección 05 ya nos dio la
herramienta: el **patrón decorador**. Allí `TaskSurfacesRefreshingRepository` envuelve el
`TaskRepository` y, tras cada escritura, refresca el widget y la notificación. Aquí necesitamos algo
paralelo para los avisos, así que escribimos un **segundo decorador** dedicado:

```kotlin
// ui/notification/ReminderSchedulingRepository.kt
class ReminderSchedulingRepository(
    private val delegate: TaskRepository,
    private val scheduler: ReminderScheduler,
    private val preferences: UserPreferencesRepository,
    private val now: () -> Long = System::currentTimeMillis,
) : TaskRepository {

    override suspend fun saveTask(task: Task): Long {
        val id = delegate.saveTask(task)          // Room asigna el id de una tarea nueva
        reschedule(task.copy(id = id))            // …y programamos por ESE id, no por 0
        return id
    }

    override suspend fun deleteTask(id: Long) {
        delegate.deleteTask(id)
        scheduler.cancel(id)
    }

    private suspend fun reschedule(task: Task) {
        scheduler.cancel(task.id)                 // 1) cancela SIEMPRE el aviso anterior
        val prefs = preferences.userPreferences.first()
        if (!prefs.remindersEnabled) return       // 2) apagados: no programes nada nuevo
        val leadMillis = minutesToMillis(prefs.reminderLeadMinutes)
        val triggerAt = reminderTimeFor(task, leadMillis) ?: return   // sin deadline: nada
        if (isReminderInFuture(triggerAt, now())) scheduler.schedule(task.id, triggerAt)  // OQ-6
    }
}
```

La secuencia **cancelar-siempre y luego-quizá-programar** es lo que hace que **editar** funcione sin
casos especiales: cambiar el deadline reemplaza el aviso; quitarlo lo deja cancelado; y nunca quedan
dos alarmas para la misma tarea. Fíjate también en que `saveTask` ahora **devuelve el `Long`** que
Room asignó: para una tarea nueva `task.id` es 0, y el aviso tiene que programarse por el id **real**.

Nota didáctica: dejamos este decorador **separado** del de la lección 05 (en vez de meterlo todo en
uno) para que cada uno tenga una sola responsabilidad — aquel refresca las superficies *pasivas*
(widget/notificación continua), este posee la alarma *alertante*. En `MainActivity` los **componemos**,
uno envolviendo al otro:

```kotlin
// MainActivity.kt (resumen)
val reminderScheduler = AlarmManagerReminderScheduler(applicationContext)
val taskRepository = TaskSurfacesRefreshingRepository(
    ReminderSchedulingRepository(RoomTaskRepository(database.taskDao()), reminderScheduler, repository),
    applicationContext,
)
ReminderNotificationHelper.ensureChannel(applicationContext)   // crea el canal al arrancar
```

> **Concepto — decoradores que se apilan.** Cada decorador añade **una** responsabilidad transversal
> (refrescar superficies / programar avisos) sin que el repositorio base ni los `ViewModel` se enteren.
> Componerlos es tan simple como envolver uno con otro. Es el mismo principio de "responsabilidad
> única" llevado a la composición.

**Un matiz sobre apagar los recordatorios.** El decorador reacciona a escrituras de *tareas*, no a
cambios de *preferencia*. Así que "apagar recordatorios cancela los pendientes" (US-4) vive donde se
cambia la preferencia, en el `SettingsViewModel`:

```kotlin
// ui/settings/SettingsViewModel.kt (resumen)
fun onRemindersEnabledChanged(enabled: Boolean) {
    viewModelScope.launch {
        repository.saveRemindersEnabled(enabled)
        if (!enabled) {
            taskRepository.observeTasks().first().forEach { task -> reminderScheduler.cancel(task.id) }
        }
    }
}
```

---

## 8. Sobrevivir al reinicio: `BOOT_COMPLETED` + WorkManager

Cuando el teléfono se apaga, Android **borra todas las alarmas** de `AlarmManager`. No es un bug: es
como funciona. Para no perder los avisos, hay que **reprogramarlos** cuando el dispositivo vuelve a
arrancar. El sistema emite un broadcast `BOOT_COMPLETED`; lo escuchamos con un receiver:

```kotlin
// ui/notification/BootReceiver.kt
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        BootRescheduleWorker.enqueue(context)     // ← delega, no trabaja aquí
    }
}
```

Aquí **no** hacemos el trabajo en el propio receiver: un `BroadcastReceiver` tiene una ventana de
ejecución de **pocos segundos**, y releer todas las tareas de Room y reprogramar N alarmas puede
llevar más. Así que **delegamos** en un `OneTimeWorkRequest` de **WorkManager** — que puede tardar lo
que necesite. Y aquí WorkManager **sí** encaja: reprogramar tras el arranque es trabajo **diferible**
("hazlo poco después de arrancar"), no un aviso puntual.

```kotlin
// ui/notification/BootRescheduleWorker.kt
class BootRescheduleWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val prefs = DataStoreUserPreferencesRepository(applicationContext).userPreferences.first()
        if (!prefs.remindersEnabled) return Result.success()   // apagados: nada que reprogramar

        val tasks = RoomTaskRepository(NeverLateDatabase.getInstance(applicationContext).taskDao())
            .observeTasks().first()
        val leadMillis = minutesToMillis(prefs.reminderLeadMinutes)
        val scheduler = AlarmManagerReminderScheduler(applicationContext)

        // La MISMA función pura de la sección 3, ahora aplicada a todas las tareas a la vez.
        remindersToSchedule(tasks, now = System.currentTimeMillis(), leadMillis = leadMillis)
            .forEach { plan -> scheduler.schedule(plan.taskId, plan.triggerAtMillis) }
        return Result.success()
    }
}
```

Observa cómo se cierra el círculo de la sección 3: el arranque **reutiliza `remindersToSchedule`**, la
misma función pura que decide qué avisos siguen teniendo sentido. Y **descarta los pasados** gracias a
`isReminderInFuture`, así que un reinicio horas después no dispara avisos de tareas ya vencidas.

> **Concepto — receiver corto delega en trabajo largo.** Es el mismo dilema de la sección 6 (`goAsync`
> daba "un poco más" de tiempo), llevado al extremo: aquí el trabajo puede ser mucho, así que en vez de
> estirar el receiver, encolamos un worker. Elige la herramienta según **cuánto** trabajo hay.

---

## 9. Preferencias y UI (repaso de la lección 07)

Ampliamos el `UserPreferences` de la lección 07 con dos campos, **en el mismo `DataStore`
`user_prefs`** (sin crear un segundo almacén), con parse tolerante y valores por defecto sensatos:

```kotlin
// data/UserPreferencesRepository.kt (resumen)
data class UserPreferences(
    val name: String = "",
    val onboarded: Boolean = false,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val remindersEnabled: Boolean = true,                              // ← nuevo
    val reminderLeadMinutes: Int = DEFAULT_REMINDER_LEAD_MINUTES,      // ← nuevo (10)
)
// …y en el map del Flow, un valor ausente (instalación previa a la 09) cae al default:
remindersEnabled = preferences[Keys.REMINDERS_ENABLED] ?: true,
reminderLeadMinutes = preferences[Keys.REMINDER_LEAD_MINUTES] ?: DEFAULT_REMINDER_LEAD_MINUTES,
```

Un install nuevo arranca con avisos **activados** y **10 min** de antelación (US-4): la persona recibe
recordatorios sin tener que buscar un ajuste primero.

La UI se añade a la **pantalla de Ajustes** existente: un `Switch` para activar/desactivar y un grupo
de opciones de antelación (5/10/15/30/60 min) como radios. Reutilizamos un `SelectableRadioRow`
compartido con las opciones de tema, y la etiqueta de cada opción sale de un `<plurals>`:

```kotlin
// ui/settings/SettingsScreen.kt (resumen)
Switch(checked = uiState.remindersEnabled, onCheckedChange = onRemindersEnabledChanged)
// …y solo si están activados, el selector de antelación:
if (uiState.remindersEnabled) {
    reminderLeadMinuteOptions.forEach { minutes ->
        SelectableRadioRow(
            label = pluralStringResource(R.plurals.settings_reminder_lead_minutes, minutes, minutes),
            selected = uiState.reminderLeadMinutes == minutes,
            onClick = { onReminderLeadMinutesSelected(minutes) },
        )
    }
    ExactAlarmPermissionNotice()      // el aviso de permiso de la sección 5
}
```

Cambiar la antelación afecta **solo a los avisos que se programen a partir de entonces** (decisión
OQ-3): no reprogramamos en bloque los ya existentes.

---

## 10. Manifest: permisos y receivers

```xml
<!-- Alarmas exactas (API 31+) — comprobadas en runtime con canScheduleExactAlarms().
     NO declaramos USE_EXACT_ALARM (restringido por Google Play). -->
<uses-permission android:name="android.permission.SCHEDULE_EXACT_ALARM" />
<!-- Escuchar BOOT_COMPLETED (permiso normal, concedido en instalación). -->
<uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />

<!-- Dispara el aviso de UNA tarea. exported="false": solo nuestro propio PendingIntent lo activa. -->
<receiver android:name=".ui.notification.ReminderReceiver" android:exported="false" />

<!-- Reprograma tras reiniciar. exported="true" es OBLIGATORIO para que el sistema entregue
     BOOT_COMPLETED; el receiver no hace trabajo sensible, solo encola el worker. -->
<receiver android:name=".ui.notification.BootReceiver" android:exported="true">
    <intent-filter>
        <action android:name="android.intent.action.BOOT_COMPLETED" />
    </intent-filter>
</receiver>
```

La regla de `exported` es de **seguridad** (requisito de MVP): `ReminderReceiver` es `false` porque
nadie de fuera debe poder dispararlo; `BootReceiver` **tiene que** ser `true` para recibir el broadcast
del sistema, pero por eso mismo **no** contiene lógica sensible — se limita a encolar el worker.

---

## 11. Tests

Fiel al patrón del proyecto, **el grueso de la lógica es puro y se testea en JVM** (sin emulador); la
carcasa Android (`AlarmManager`, receivers, publicar la notificación) se verifica **a mano**.

- `ReminderPlanningTest.kt` — cubre `minutesToMillis`, `reminderTimeFor` (con/sin deadline, `lead = 0`,
  lead mayor que el margen → instante en el pasado), `isReminderInFuture` (pasado / presente exacto →
  `false` por el `>` estricto / futuro), y `remindersToSchedule` con listas mixtas.
- `ReminderSchedulingRepositoryTest.kt` — el decorador contra un `FakeReminderScheduler` y un fake de
  preferencias: `saveTask` con deadline futuro → `schedule` con el **id asignado** (no 0); sin deadline
  o con avisos apagados o con instante pasado → **solo** `cancel`; `deleteTask` → `cancel`; y que el
  lead time persistido se aplica al instante.
- `SettingsViewModelTest.kt` — apagar cancela el aviso de cada tarea (con 0, 1 y N tareas); encender
  **no** cancela nada; elegir antelación persiste.

```bash
# Tests unitarios (JVM, sin emulador)
./gradlew :app:testDebugUnitTest

# Compila el APK (verifica que scheduler, receivers y canal enlazan bien)
./gradlew :app:assembleDebug
```

Lo que se verifica **a mano en el emulador**: que el aviso *heads-up* aparece y suena con la app
cerrada; que al tocarlo abre la lista de tareas; que tras `adb shell am broadcast -a
android.intent.action.BOOT_COMPLETED` los avisos futuros se reprograman; que denegar
`POST_NOTIFICATIONS` o el permiso de alarmas exactas **no** rompe nada (degrada); y que el aviso (canal
que alerta) y la notificación continua de la 06 (canal silencioso) **coexisten** sin pisarse.

---

## 12. Probar la app

1. Crea una tarea con **fecha límite** a pocos minutos vista (lección 04).
2. En Ajustes, comprueba que **Recordatorios** está activado y baja la antelación a **5 min** (así el
   aviso salta antes y no esperas tanto). En Android 12+, si aparece el aviso de permiso de alarmas
   exactas, concédelo para que suene puntual.
3. **Cierra la app** (deslízala fuera de recientes).
4. Al llegar `deadline − antelación`, aparece un aviso **flotante que suena** con el título de la
   tarea y "Vence en N minutos · <hora>".
5. **Tócalo**: abre la app directamente en la lista de tareas.
6. Prueba a **apagar** los recordatorios en Ajustes: los avisos pendientes se cancelan.
7. Prueba a **denegar** el permiso de notificaciones o el de alarmas exactas: la app sigue
   funcionando; el aviso simplemente no aparece, o llega con algo de holgura.

---

## 13. Siguiente paso

Con esto la app ya **avisa a tiempo** aunque esté cerrada. Sobre esta base se pueden construir
extensiones naturales que quedaron **fuera de alcance** a propósito (ver la spec): **varios avisos por
tarea** (p. ej. 1 h y 10 min antes) o antelación **por tarea**; acciones en la notificación
(**posponer**, marcar hecha); recordatorios para tareas **solo-duración**; o tareas **recurrentes**.
Y cuando llegue un backend (feature 11), habrá que decidir cómo **sincronizar** recordatorios entre
dispositivos — hoy todo es local.
