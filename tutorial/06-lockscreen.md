# Lección 06 — Tareas en la pantalla de bloqueo con notificaciones y foreground service

> Objetivo: llevar las tareas a la **pantalla de bloqueo**. Construimos una **notificación continua y
> actualizable** que lista las tareas pendientes con su **tiempo restante**, visible sin desbloquear el
> teléfono. Reutiliza la **misma base de datos Room** de la lección 04 y los **patrones de la lección
> 05** (función pura de mapeo, decorador, WorkManager) — de hecho, generalizamos aquel decorador para
> que un solo punto refresque **el widget y la notificación** a la vez. Por el camino aprendemos
> **notificaciones** (canales, `NotificationCompat`, notificación `ongoing`), **visibilidad y privacidad
> en el lockscreen**, el permiso **`POST_NOTIFICATIONS`** en runtime, y un **foreground service** con su
> ciclo de vida.

## Conceptos que aprendes aquí

Partiendo de las lecciones 04 (Room + `Flow`, repositorio tras interfaz, funciones puras de tiempo,
reloj de pared) y 05 (función pura de mapeo, patrón decorador, WorkManager, `PendingIntent`/deep-link):

- **[Notificaciones en Android](https://developer.android.com/develop/ui/views/notifications):**
  `NotificationManagerCompat`, `NotificationCompat.Builder` y por qué usamos siempre las variantes
  `*Compat`.
- **[Canales de notificación](https://developer.android.com/develop/ui/views/notifications/channels)**
  (`NotificationChannel`, API 26+) y la **guarda `SDK_INT`** para convivir con `minSdk = 24`.
- **Notificación continua y actualizable** (`setOngoing`, `setOnlyAlertOnce`) y el truco de **reemitir
  con el mismo `notificationId`** para actualizar en sitio — el mismo problema "empujar en vez de
  observar" del widget.
- **Visibilidad y privacidad en el lockscreen**: `setVisibility(...)` y `setPublicVersion(...)`.
- **Permiso [`POST_NOTIFICATIONS`](https://developer.android.com/develop/ui/views/notifications/notification-permission)**
  (Android 13+) solicitado desde Compose, y cómo **degradar con gracia** si se deniega.
- **[Foreground service](https://developer.android.com/develop/background-work/services/foreground-services)**:
  qué es, cómo se promociona con `startForeground`, su **ciclo de vida** y el requisito de
  `foregroundServiceType` en API 34+.
- **Compartir una regla entre dos features** extrayendo un helper puro común, para que widget y
  notificación **no puedan divergir**.

---

## 1. El reparto de piezas

Igual que el widget, la notificación es una superficie **pasiva y de solo lectura** que vive **fuera**
de la `Activity`. No podemos pasarle un objeto vivo ni suscribirla a un `Flow`: hay que **empujarle**
las actualizaciones. La feature se reparte en un paquete nuevo, `com.neverlate.ui.notification`:

- `NotificationModel.kt` — la **función pura de mapeo** `toNotificationModel(tasks, now)`. Cero
  imports de Android: es la parte testeable en JVM.
- `TasksNotificationHelper.kt` — la **carcasa Android**: crea el canal y construye/cancela la
  notificación con `NotificationCompat`.
- `TasksNotificationService.kt` — el **foreground service** que hospeda la notificación.
- `NotificationPermission.kt` — un pequeño *effect* de Compose que pide `POST_NOTIFICATIONS`.

Y reutiliza dos piezas de la lección 05, ahora **generalizadas** para refrescar ambas superficies:
`TaskSurfacesRefreshingRepository` (decorador) y `TaskSurfacesRefreshWorker` (WorkManager).

---

## 2. La regla compartida: `pendingRowsFor`

La lección 05 tenía dentro del widget la regla "qué cuenta como pendiente, en qué orden, cuántas filas
como máximo". La notificación necesita **exactamente la misma regla**: sería un fallo que el widget y la
notificación mostraran cosas distintas de la misma base de datos. Para que **no puedan divergir**,
extraemos esa regla a un único sitio, `com.neverlate.domain.tasks.PendingTaskRows`:

```kotlin
// domain/tasks/PendingTaskRows.kt
data class PendingTaskRow(val title: String, val remaining: String, val isTimedOut: Boolean)

fun pendingRowsFor(tasks: List<Task>, now: Long): List<PendingTaskRow> =
    tasks
        .map { task -> task to computeRemainingMillis(task, now) }   // reutiliza la lección 04
        .sortedBy { (_, remainingMillis) -> remainingMillis }         // más urgente primero
        .take(MAX_PENDING_ROWS)                                       // cap
        .map { (task, remainingMillis) ->
            PendingTaskRow(task.title, formatRemaining(remainingMillis), isTimedOut = remainingMillis == 0L)
        }
```

Es Kotlin puro: sin Glance, sin Android. El widget lo llama desde `toWidgetModel` y la notificación
desde `toNotificationModel`. Fíjate en un detalle de reutilización sin fricción: la lección 05 tenía
su propio `data class PendingTaskRow`; para no renombrar nada en el widget, ahora ese nombre es un
**`typealias`** al del paquete `domain`:

```kotlin
// ui/widget/PendingTasksWidgetState.kt
typealias PendingTaskRow = com.neverlate.domain.tasks.PendingTaskRow
```

> **Concepto — "una sola fuente de verdad".** Cuando dos partes del código deben coincidir siempre, no
> las escribas dos veces confiando en tu disciplina: extrae la regla a una función y llámala desde
> ambas. Lo blindamos con un test que compara ambas salidas (sección 10).

---

## 3. El modelo de la notificación (función pura)

`toNotificationModel` traduce el snapshot de tareas a un `sealed interface` — igual que el widget, pero
con un dato extra que el widget no necesitaba: **cuántas tareas hay en total** (`totalPendingCount`),
para la versión redactada del lockscreen (sección 6).

```kotlin
// ui/notification/NotificationModel.kt
sealed interface TasksNotificationModel {
    data object Empty : TasksNotificationModel                       // sin tareas → no notificar
    data class Content(val rows: List<PendingTaskRow>, val totalPendingCount: Int) : TasksNotificationModel {
        init { require(rows.isNotEmpty()) { "Content must have at least one row; use Empty otherwise" } }
        val mostUrgent: PendingTaskRow get() = rows.first()          // la lista nunca está vacía aquí
    }
}

fun toNotificationModel(tasks: List<Task>, now: Long): TasksNotificationModel {
    if (tasks.isEmpty()) return TasksNotificationModel.Empty
    return TasksNotificationModel.Content(pendingRowsFor(tasks, now), totalPendingCount = tasks.size)
}
```

El modelo **no** contiene frases para el usuario ("Tienes 3 tareas…"). Esas cadenas necesitan un
`Context` para leer `strings.xml`, lo que metería un import de Android en este archivo y lo haría no
testeable en JVM. El texto se arma en el *helper* (siguiente sección).

---

## 4. Canales de notificación (API 26+) y la guarda `SDK_INT`

Desde Android 8 (API 26), **toda** notificación pertenece a un **canal**: una categoría que la persona
usuaria puede configurar (silenciar, cambiar importancia…) por separado. En API 24–25 no existen los
canales, así que toda llamada relacionada va **guardada** tras `SDK_INT >= O`. Usamos
`NotificationChannelCompat`, que unifica la API:

```kotlin
// ui/notification/TasksNotificationHelper.kt
fun ensureChannel(context: Context) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        val channel = NotificationChannelCompat.Builder(
            TASKS_NOTIFICATION_CHANNEL_ID,
            NotificationManagerCompat.IMPORTANCE_DEFAULT,   // visible en el lockscreen…
        )
            .setName(context.getString(R.string.notification_channel_name))
            .setDescription(context.getString(R.string.notification_channel_description))
            .setSound(null, null)         // …pero sin sonido…
            .setVibrationEnabled(false)   // …ni vibración: es un resumen, no una alerta
            .build()
        NotificationManagerCompat.from(context).createNotificationChannel(channel)
    }
}
```

**La importancia y el problema del lockscreen en Xiaomi.** La primera versión de esta feature usaba
`IMPORTANCE_LOW`, que parecía lo natural para un resumen permanente que se **reemite** en cada refresco:
sin sonido y sin popup. Al **probarlo en un móvil real (Xiaomi / MIUI)** descubrimos que la notificación
salía en la barra de notificaciones pero **nunca en la pantalla de bloqueo** — justo lo contrario del
objetivo de la feature. El motivo: muchas capas de fabricante (MIUI/HyperOS entre ellas) clasifican todo
lo que esté por debajo de `IMPORTANCE_DEFAULT` como "silencioso" y **lo ocultan del lockscreen**.

La solución es `IMPORTANCE_DEFAULT` (para que el sistema la considere visible en el lockscreen)
combinado con `setSound(null, null)` y `setVibrationEnabled(false)` para quitarle el sonido y la
vibración. Así seguimos teniendo una notificación que **no molesta** al reemitirse, pero que **sí** se
ve en la pantalla de bloqueo. (`DEFAULT`, a diferencia de `HIGH`, tampoco muestra *heads-up* flotante.)

> ⚠️ **La importancia de un canal solo se aplica la primera vez que se crea.** Android la "congela"
> después para que mande la persona usuaria (puede cambiarla en Ajustes). En un móvil que ya creó el
> canal con la importancia antigua, hay que **reinstalar la app o borrar sus datos** para que la nueva
> importancia tenga efecto. Recrear un canal existente con el mismo id es, por lo demás, un **no-op**,
> así que llamar a `ensureChannel` en cada refresco es seguro.

---

## 5. Notificación continua y actualizable

El cuerpo de la notificación se construye con `NotificationCompat.Builder`. Tres conceptos nuevos, todos
en el mismo objeto:

```kotlin
NotificationCompat.Builder(context, TASKS_NOTIFICATION_CHANNEL_ID)
    .setSmallIcon(R.drawable.ic_launcher)
    .setContentTitle(context.getString(R.string.notification_title))
    .setContentText(/* título de la más urgente + su tiempo */)
    .setStyle(inboxStyle)          // varias líneas: una por tarea visible
    .setNumber(model.totalPendingCount)
    .setOngoing(true)              // no se puede descartar mientras haya tareas
    .setOnlyAlertOnce(true)        // reemitir no vuelve a sonar/vibrar
    .setPriority(NotificationCompat.PRIORITY_DEFAULT)      // equivalente a IMPORTANCE_DEFAULT en API 24-25
    .setSilent(true)               // …pero sin sonido/vibración (ver sección 4)
    .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)   // ver sección 6
    .setPublicVersion(buildPublicVersion(...))
    .setContentIntent(buildContentIntent(context))         // tocar → abrir la app
    .build()
```

- **`setOngoing(true)`** la fija como un indicador de estado, no una alerta descartable.
- **`setOnlyAlertOnce(true)`** + **reemitir con el mismo `notificationId`** = actualización **en sitio**.
  Este es el mismo problema del widget: la notificación **no observa** un `Flow`; algo tiene que volver
  a llamar a `notify`/`startForeground` con el mismo id para refrescar su contenido.
- **`InboxStyle`** es el estilo expandible multilínea: añadimos una línea por tarea visible.

El `PendingIntent` que abre la app **reutiliza el mismo mecanismo del widget** (`EXTRA_OPEN_TASKS`), para
que haya una sola receta "abrir en tareas" en toda la app, y con `FLAG_IMMUTABLE` (obligatorio desde
API 31).

---

## 6. Visibilidad y privacidad en el lockscreen

Los títulos de tarea pueden ser sensibles ("llamar al médico"). Android permite decidir qué se ve en la
**pantalla de bloqueo** con `setVisibility(...)`:

- `VISIBILITY_PUBLIC` — se ve todo (títulos incluidos).
- `VISIBILITY_PRIVATE` — en el lockscreen se muestra una **versión redactada** (la de
  `setPublicVersion`); al desbloquear, la completa.
- `VISIBILITY_SECRET` — no se muestra nada en el lockscreen.

**Decisión de esta feature (D3, aprobada):** de momento usamos **`VISIBILITY_PUBLIC`** — los títulos se
ven en el lockscreen. Ahora bien, **dejamos ya construida** una versión pública redactada
(`setPublicVersion`) que enseña solo un recuento y el tiempo de la más urgente, **sin títulos**:

```kotlin
// El texto redactado: "3 tareas pendientes · próxima en 12:30"
context.getString(R.string.notification_public_summary_format, model.totalPendingCount, mostUrgentLabel)
```

Bajo `VISIBILITY_PUBLIC`, Android **ignora** `setPublicVersion` (solo se usa con `PRIVATE`/`SECRET`), así
que hoy está *construida pero inactiva*. La dejamos lista a propósito: activar "ocultar títulos en el
lockscreen" en una futura **feature 07 (ajustes)** será tan simple como cambiar esa única línea a
`VISIBILITY_PRIVATE` y exponerlo como preferencia.

> **Buenas prácticas de privacidad.** Piensa siempre qué de tu notificación acaba en una pantalla que
> alguien puede ver sin desbloquear. Aunque hoy elijamos público, tener la versión redactada preparada
> es la postura correcta por defecto.

---

## 7. El permiso `POST_NOTIFICATIONS` (Android 13+)

Desde Android 13 (API 33, `TIRAMISU`), publicar notificaciones exige un **permiso concedido en runtime**.
En API 24–32 no existe: se concede implícitamente. Lo pedimos desde Compose, **en contexto** (la primera
vez que la persona llega a su lista de tareas), no de forma intrusiva al primer arranque:

```kotlin
// ui/notification/NotificationPermission.kt
@Composable
fun RequestNotificationPermissionEffect() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return   // guarda de versión

    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) TasksNotificationService.refresh(context)         // ahora sí, muéstrala ya
    }
    LaunchedEffect(Unit) {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}
```

Y se engancha en `TasksRoute` con una sola línea: `RequestNotificationPermissionEffect()`.

**Degradar con gracia** es clave: si la persona deniega, **nada revienta**. El servicio comprueba
`areNotificationsEnabled()` antes de promocionarse (sección 8) y, si no puede notificar, simplemente se
retira. La app sigue funcionando igual; solo no se ve la notificación.

---

## 8. Foreground service y su ciclo de vida

**Decisión de esta feature (D2, aprobada):** la notificación la hospeda un **foreground service**. Un
*service* es un componente sin UI; se convierte en **foreground** cuando llamamos a
`startForeground(id, notification)`, que hace dos cosas: **fija** esa notificación (sobrevive aunque la
app esté en segundo plano) y le dice al sistema "mantén vivo este proceso, está haciendo algo que le
importa a la persona ahora mismo".

```kotlin
// ui/notification/TasksNotificationService.kt (resumen)
override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    serviceScope.launch { refreshNotification() }
    return START_NOT_STICKY          // que el sistema no lo recree solo; ya lo despertaremos nosotros
}

private suspend fun refreshNotification() {
    TasksNotificationHelper.ensureChannel(applicationContext)
    val enabled = NotificationManagerCompat.from(applicationContext).areNotificationsEnabled()

    // Mismo snapshot que el widget: singleton de Room + .first() (leer, no observar)
    val tasks = RoomTaskRepository(NeverLateDatabase.getInstance(applicationContext).taskDao())
        .observeTasks().first()
    val model = toNotificationModel(tasks, System.currentTimeMillis())

    if (model is TasksNotificationModel.Content && enabled) {
        startForeground(TASKS_NOTIFICATION_ID, TasksNotificationHelper.buildNotification(applicationContext, model))
    } else {
        // Sin tareas (o sin permiso): nada debe quedar fijado. Aun así hay que llamar a
        // startForeground una vez (el sistema lo exige tras startForegroundService) y retirarlo.
        startForeground(TASKS_NOTIFICATION_ID, TasksNotificationHelper.buildEmptyPlaceholder(applicationContext))
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }
}
```

**El ciclo de vida**, combinado con la decisión D1 (mostrarla siempre que haya tareas pendientes):

1. Alguien **"pokea"** al servicio con `TasksNotificationService.refresh(context)`, que hace
   `ContextCompat.startForegroundService(...)`.
2. `onStartCommand` relee las tareas y decide: si hay pendientes → `startForeground` (aparece o se
   actualiza); si no → placeholder + `stopSelf` (se retira).
3. ¿Quién pokea? Tres sitios: cada **escritura** (el decorador), el **worker periódico**, y el
   **arranque de la app** (`MainActivity.onCreate`), para que aparezca de inmediato.

> **`foregroundServiceType` (API 34+).** Desde Android 14, cada foreground service debe declarar su
> **tipo** en el manifest, y pedir el permiso correspondiente. No hay un tipo "recordatorio de tareas"
> perfecto, así que usamos **`specialUse`** (con una `<property>` que justifica el uso). En una app
> publicada, `specialUse` requiere justificación ante Google Play; aquí, al ser local, no aplica.

> **El coste.** Un foreground service activo mientras haya tareas puede durar horas. El sistema vigila
> estos servicios. Por eso el canal es silencioso (sin sonido ni vibración) y el servicio se **para** en
> cuanto no queda nada pendiente. (De hecho, para una señal informativa como esta, una notificación normal sin servicio
> habría bastado; usamos el servicio aquí sobre todo para **aprender el concepto**.)

---

## 9. Empujar los cambios: decorador y WorkManager, ahora para dos superficies

La lección 05 introdujo el **patrón decorador**: `WidgetRefreshingTaskRepository` envolvía
`TaskRepository` y, tras cada escritura, refrescaba el widget. Ahora esa necesidad es **la misma para la
notificación**. En vez de anidar dos decoradores, **generalizamos** el existente a
`TaskSurfacesRefreshingRepository`, que refresca **ambas** superficies desde un único punto:

```kotlin
// ui/widget/TaskSurfacesRefreshingRepository.kt
private suspend fun refreshSurfaces() {
    PendingTasksWidget().updateAll(context)          // widget (lección 05)
    TasksNotificationService.refresh(context)        // notificación (lección 06)
}
```

Igualmente, el `CoroutineWorker` periódico pasa a ser `TaskSurfacesRefreshWorker` y refresca las dos.
Sigue teniendo el **suelo de ~15 min** de WorkManager: la notificación muestra el restante del último
refresco, **no** una cuenta atrás segundo a segundo (para eso está el contador dentro de la app). Se
encola de forma idempotente desde `MainActivity.onCreate`, igual que en la lección 05.

Los `ViewModel` de la lección 04 **siguen sin enterarse** de que existe una notificación: toda esta
maquinaria vive en el decorador y el servicio.

---

## 10. Manifest: permisos y servicio

```xml
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_SPECIAL_USE" />

<service
    android:name=".ui.notification.TasksNotificationService"
    android:exported="false"
    android:foregroundServiceType="specialUse">
    <property
        android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE"
        android:value="Ongoing summary of the user's pending tasks…" />
</service>
```

`android:exported="false"`: nadie fuera de la app arranca el servicio, solo nuestro propio
decorador/worker/Activity. `POST_NOTIFICATIONS` declarado aquí es inofensivo en API ≤ 32 (implícito).

---

## 11. Tests

Como en las lecciones 04 y 05, movemos **toda la lógica testeable a funciones puras** y dejamos la
carcasa Android (canal, `NotificationCompat`, servicio, permiso) lo más fina posible, porque publicar
notificaciones y el lockscreen **no** se testean en unidad de forma razonable.

`NotificationModelTest.kt` (JVM, sin emulador) cubre: lista vacía → `Empty`; una tarea → `Content` con
su fila, `totalPendingCount` y `mostUrgent`; orden más-urgente-primero; tarea agotada → `00:00` +
`isTimedOut`; cap a 5 filas pero el recuento refleja **todas** las pendientes; y un test que compara la
salida de la notificación con la del widget para el mismo snapshot — **el candado** que garantiza que no
divergen.

```bash
# Tests unitarios (JVM, sin emulador)
./gradlew :app:testDebugUnitTest

# Compila el APK (verifica que notificaciones y el servicio enlazan bien)
./gradlew :app:assembleDebug
```

Lo que se verifica **a mano en el emulador** (no en unidad): que la notificación aparece con el teléfono
bloqueado, el flujo de permiso conceder/denegar (en API 33+ y en API ≤ 32), tocar para abrir la app, y
que se retira al quedarse sin tareas.

---

## 12. Probar la app

1. Lanza la app y crea alguna tarea con duración o fecha límite (lección 04).
2. En Android 13+, acepta el permiso de notificaciones cuando lo pida al entrar en **Tareas**.
3. Baja la sombra de notificaciones: verás **"Tareas pendientes"** con las más urgentes y su tiempo.
4. **Bloquea** el teléfono: la notificación sigue visible en el lockscreen (con los títulos, por D3).
5. Tócala: te pedirá desbloquear y abrirá la app directamente en la lista de tareas.
6. Borra/completa todas las tareas: la notificación **desaparece** (el servicio se detiene).
7. Prueba a **denegar** el permiso: la app funciona igual, simplemente no aparece la notificación.

---

## Documentación oficial

- **Notificaciones** — [Notifications overview](https://developer.android.com/develop/ui/views/notifications)
  · [Crear una notificación](https://developer.android.com/develop/ui/views/notifications/build-notification)
- **Canales de notificación** — [Create and manage channels](https://developer.android.com/develop/ui/views/notifications/channels)
- **Permiso `POST_NOTIFICATIONS`** — [Notification runtime permission](https://developer.android.com/develop/ui/views/notifications/notification-permission)
- **Permisos en runtime** — [Permissions on Android](https://developer.android.com/guide/topics/permissions/overview)
- **Foreground services** — [Foreground services overview](https://developer.android.com/develop/background-work/services/foreground-services)
- **Comprobar la versión de Android** — [`Build.VERSION.SDK_INT`](https://developer.android.com/reference/android/os/Build.VERSION#SDK_INT)

---

## 13. Siguiente paso

La feature 07 (ajustes) es el hogar natural para hacer **configurable** lo que aquí fijamos: activar o
desactivar la notificación, y elegir la visibilidad en el lockscreen (público/privado/oculto) — recuerda
que la versión redactada ya está lista, esperando ese interruptor. Más adelante, la feature 09
(recordatorios) añadirá avisos **puntuales** al llegar la fecha límite, algo distinto de esta vista
continua.
