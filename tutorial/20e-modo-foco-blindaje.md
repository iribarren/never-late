# Lección 20e — Blindar el Modo Foco: accesos especiales, deshacer estado global y elegir el disparador

> Objetivo: usar el blindaje del Modo Foco (`docs/specs/2026-08-18-focus-mode-shielding.md`) para
> aprender lo que pasa cuando una app deja de tocar **solo lo suyo** y empieza a tocar **el
> teléfono**. Tres medidas que parecen hermanas y no lo son; un permiso que no se pide con un
> diálogo; y sobre todo el problema central: **cómo deshacer un efecto global cuando tu proceso
> puede morir en cualquier momento y nadie va a deshacerlo por ti.**

## Conceptos que aprendes aquí

Partiendo de la lección 20d (Modo Foco: `BackHandler`, gestos accesibles, capas de estado), la 06
(canales de notificación), la 09 (`SCHEDULE_EXACT_ALARM` y el patrón de acceso especial) y la 07
(DataStore `user_prefs`):

1. **Accesos especiales frente a permisos runtime.** Por qué `ACCESS_NOTIFICATION_POLICY` no se pide
   nunca con un diálogo, en qué se parece a `SCHEDULE_EXACT_ALARM`, y por qué la respuesta correcta
   fue **extraer** el patrón que ya existía en vez de escribirlo por segunda vez.
2. **El *recibo write-ahead*: deshacer efectos globales a prueba de muerte de proceso.** El corazón
   de la lección. Persistir la *intención* antes que el efecto, y borrarla solo después de haberlo
   deshecho. Es la idea de un *write-ahead log*, en un caso lo bastante pequeño como para caber en
   una mano.
3. **Elegir el disparador correcto: predicado, alarma o `WorkManager`.** La 20d eligió a propósito un
   predicado puro para la caducidad de la sesión. Esta feature necesita lo contrario, y aun así
   tampoco necesita una alarma exacta.
4. **Efectos de ventana con `DisposableEffect`.** `WindowInsetsControllerCompat` y
   `FLAG_KEEP_SCREEN_ON`: el *alcance* como herramienta de diseño — qué efectos necesitan maquinaria
   para deshacerse y cuáles se deshacen gratis.
5. **Lock task mode y el hábito del "estado verificado".** Qué es de verdad fijar la pantalla sin ser
   *device owner*, y la costumbre más general de **leer de vuelta** el estado de la plataforma en
   lugar de dar por hecho que tu petición funcionó.

---

## 1. Tres medidas que no son hermanas

La feature ofrece tres interruptores en el diálogo de entrada al Modo Foco:

- **Pantalla siempre encendida** (y barras del sistema ocultas).
- **No molestar.**
- **Fijar la pantalla.**

Puestos en una lista parecen tres opciones equivalentes. La primera decisión del diseño —y la que
explica toda la arquitectura— fue darse cuenta de que **no lo son**, y clasificarlas por su
*alcance*:

| Medida | Alcance | ¿Sobrevive a que muera tu proceso? | ¿Necesita maquinaria para deshacerse? |
|---|---|---|---|
| Pantalla encendida + inmersivo | La **ventana** de tu Activity | ❌ muere con la ventana | **No** |
| Fijar la pantalla | La **pila de tareas** del sistema | ✅ pero el sistema siempre ofrece su propia salida, y podemos *consultar* el estado | **No hay recibo**, solo una consulta |
| No molestar | El **dispositivo**, globalmente | ✅ y nadie más lo va a deshacer nunca | **Sí** — el recibo |

Todo lo caro de esta feature existe para **una sola** de las tres. Decirlo en voz alta no es
pedantería: es lo que impide que alguien, dentro de seis meses, "unifique" el código persistiendo las
tres por coherencia e invente dos problemas de restauración que no existían.

La consecuencia de producto es directa: **tres interruptores independientes, nunca un único toggle
maestro de "blindaje"**. Un toggle maestro insinuaría que comparten modo de fallo. No lo comparten.

> **Regla que merece la pena llevarse:** antes de escribir la máquina de restauración, clasifica cada
> efecto por su alcance. Los que mueren con tu ventana no necesitan ninguna.

---

## 2. Accesos especiales: el permiso que no se pide

Para silenciar el teléfono hace falta `NotificationManager.setInterruptionFilter`, y eso exige
`ACCESS_NOTIFICATION_POLICY`. Se declara en el manifest… y ahí se acaba el parecido con un permiso
normal:

```xml
<uses-permission android:name="android.permission.ACCESS_NOTIFICATION_POLICY" />
```

**No hay `ActivityCompat.requestPermissions` que valga.** No existe un diálogo del sistema que puedas
lanzar para pedirlo. Es un **acceso especial**: el usuario lo concede a mano, en una pantalla de
Ajustes, y tu app solo puede hacer tres cosas:

1. **Comprobar** si lo tiene: `notificationManager.isNotificationPolicyAccessGranted`.
2. **Explicar** para qué lo quieres, con honestidad.
3. **Mandar** a la persona a la pantalla que lo concede, con un `Intent`.

Y luego **dejarla en paz**. Ni insistir, ni bloquear el flujo, ni volver a preguntar.

### Esto ya lo habíamos hecho: la lección 09

Compara con `SCHEDULE_EXACT_ALARM` (lección 09). Distinto permiso, distinta pantalla de Ajustes,
**exactamente la misma forma**: comprobar → explicar → mandar a Ajustes. El repo ya tenía ese código,
en un composable privado de `SettingsScreen.kt` llamado `ExactAlarmPermissionNotice`.

La tentación evidente era copiarlo y cambiarle el `Intent`. La decisión fue la contraria: **promover
el patrón** a `ui/components/SpecialAccessNotice.kt`, y que Ajustes pase a ser su primer usuario:

```kotlin
@Composable
fun SpecialAccessNotice(
    isGranted: () -> Boolean,       // se re-evalúa en cada ON_RESUME
    message: String,
    actionLabel: String,
    settingsIntent: () -> Intent,
    modifier: Modifier = Modifier,
)
```

Fíjate en dos detalles de la firma, porque son los que hacen que el componente sea reutilizable de
verdad:

- **`isGranted` es una lambda, no un `Boolean`.** No existe ningún *callback* que te avise de que un
  acceso especial ha cambiado. La única forma de saberlo es **volver a preguntar** a la plataforma. Un
  `Boolean` congelaría la respuesta en el momento de la composición.
- **`settingsIntent` también es una lambda.** El `Intent` se construye solo cuando alguien pulsa, no
  en cada recomposición.

### El regalo de extraer en vez de copiar

Al mover el composable salió a la luz un defecto que llevaba ahí desde la feature 09, admitido en su
propio KDoc: solo comprobaba el permiso **una vez por composición**. Si volvías de Ajustes después de
concederlo, el aviso seguía ahí, mintiendo, hasta que salías de la pantalla y volvías a entrar.

La versión compartida observa el ciclo de vida:

```kotlin
var granted by remember { mutableStateOf(isGranted()) }

val lifecycleOwner = LocalLifecycleOwner.current
DisposableEffect(lifecycleOwner) {
    val observer = LifecycleEventObserver { _, event ->
        if (event == Lifecycle.Event.ON_RESUME) granted = isGranted()
    }
    lifecycleOwner.lifecycle.addObserver(observer)
    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
}
```

`ON_RESUME` es exactamente el momento en el que la persona vuelve de la pantalla de Ajustes. El aviso
desaparece solo, sin remontar nada.

> **La moraleja no es "extraer es más limpio".** Es que **el segundo caso de uso es el que te enseña
> cómo debería haber sido el primero.** Al escribirlo para dos sitios, el fallo del original dejó de
> ser invisible — y la pantalla de Ajustes se llevó gratis una mejora.

### Degradación elegante: el permiso denegado es un estado normal

Regla no negociable de esta feature, heredada de cómo `ReminderReceiver` trata que le nieguen
`POST_NOTIFICATIONS`: **un permiso que falta no es un error**. Es un estado ordinario y esperado.

| Situación | Qué pasa |
|---|---|
| Sin acceso a No molestar al empezar | La sesión empieza igual. No se aplica la medida. **No se escribe recibo.** |
| Acceso revocado a mitad de sesión | No pasa nada durante la sesión; al restaurar se borra el recibo. |
| `setInterruptionFilter` lanza `SecurityException` | Se captura, se borra el recibo, la sesión sigue. |
| `startLockTask()` falla o el fabricante lo rechaza | Se captura, el indicador no dice que esté fijada, la sesión sigue. |

Ni un *crash*, ni un flujo muerto, ni un diálogo insistente.

---

## 3. El recibo *write-ahead* — el corazón de la lección

Aquí está el problema de verdad, y conviene enunciarlo con crudeza:

> **El fallo grave de esta feature sería dejar el teléfono de alguien en silencio para siempre.**

Android mata procesos por memoria constantemente. Una sesión de foco termina "por las malas" —proceso
muerto, app cerrada desde recientes, reinicio— **mucho más a menudo** de lo que termina por el ritual.
Un diseño que solo deshace No molestar en el camino feliz no está "correcto al 95%": está **roto**.

### La persistencia mínima

Una sola clave en el DataStore `user_prefs` que ya existe:

```
focus_shield_prior_filter : Int      # el filtro de interrupciones que había antes de que lo tocáramos
```

**La presencia de la clave *es* el recibo.** Si está, hay algo que deshacer. Si no está, no hay nada.

No hay un booleano aparte de "¿lo aplicamos?", ni un *timestamp*, ni un blob serializado con las tres
opciones. Cada campo extra es **una forma más de que el registro se contradiga a sí mismo**: un
booleano que dice `true` y un filtro que es `null` es un estado que alguien tendrá que interpretar, y
lo interpretará mal.

### El orden, que es todo el asunto

```
inicio:  escribir recibo → encolar backstop → aplicar No molestar → persistir sesión → navegar
fin:     restaurar        → borrar recibo    → cancelar backstop   → terminar sesión  → navegar
```

En código (`ui/focus/FocusShieldController.kt`), sin adornos:

```kotlin
suspend fun applyFocusShieldOnSessionStart(
    controller: FocusShieldController,
    userPreferencesRepository: UserPreferencesRepository,
    enqueueBackstop: () -> Unit,
    doNotDisturbRequested: Boolean,
) {
    if (!doNotDisturbRequested || !controller.isPolicyAccessGranted()) return

    // ① EL RECIBO PRIMERO — write-ahead, antes de nada que pueda silenciar el teléfono.
    val currentFilter = controller.currentInterruptionFilter()
    userPreferencesRepository.saveFocusShieldPriorFilter(currentFilter)

    // ② el backstop, antes del efecto.
    enqueueBackstop()

    // ③ el efecto, el último.
    val applied = controller.applyDoNotDisturb()
    if (!applied) {
        // El efecto no llegó a aplicarse: el recibo solo sería peso muerto.
        userPreferencesRepository.saveFocusShieldPriorFilter(null)
    }
}
```

¿Por qué ese orden y no el intuitivo (aplicar y luego apuntar)? Porque hay que preguntarse **"¿y si
muere aquí?"** en cada línea:

| El proceso muere… | Estado resultante | Qué hace la siguiente restauración |
|---|---|---|
| tras el recibo, antes de aplicar No molestar | recibo presente, filtro sin tocar | el filtro actual ≠ el que aplicamos ⇒ **no toca nada**, borra el recibo |
| tras aplicar No molestar, antes de persistir la sesión | recibo presente, sin sesión | restaura el filtro anterior |
| a mitad de sesión | recibo presente, sesión activa | nada — una sesión viva conserva su blindaje |
| tras restaurar, antes de terminar la sesión | filtro restaurado, sesión aún activa | nada; la persona vuelve a una sesión sin No molestar, que es la dirección **segura** |

Cada interrupción converge en un estado seguro. Con el orden inverso existiría una ventana —pequeña,
real— en la que **el teléfono está en silencio y no hay nada en disco que sepa por qué**. Esa ventana
es el bug.

> **La regla, en una frase:** *persiste la intención de cambiar un estado global **antes** de
> cambiarlo, y bórrala solo **después** de haberlo deshecho.* Es literalmente lo que hace un
> *write-ahead log* en una base de datos, y funciona por el mismo motivo.

### La decisión, en Kotlin puro

Toda la lógica que merece la pena probar vive en `domain/focus/FocusShieldRestore.kt`: una función
`(estado) → acción`, sin un solo `import` de Android, testeable en milisegundos.

```kotlin
sealed interface ShieldRestoreAction {
    data object None : ShieldRestoreAction                          // no tocar nada
    data class RestoreFilter(val filter: Int) : ShieldRestoreAction // restaurar + borrar el recibo
    data object ClearReceiptOnly : ShieldRestoreAction              // olvidar el recibo, no tocar el filtro
}

fun shieldRestoreActionFor(
    sessionActive: Boolean,
    priorFilter: Int?,        // el recibo; null si no hay
    currentFilter: Int,       // lo que el sistema reporta ahora mismo
    appliedFilter: Int,       // lo que ponemos nosotros (INTERRUPTION_FILTER_PRIORITY)
    policyAccessGranted: Boolean,
): ShieldRestoreAction
```

Fíjate en `appliedFilter`: se **pasa como parámetro** en vez de estar escrito a fuego. Es lo que
mantiene el fichero libre de `import android.*`. La misma razón por la que
`INTERRUPTION_FILTER_UNKNOWN` aparece ahí como una constante privada con el valor `0` y un comentario
que explica de qué constante de plataforma es espejo.

La tabla completa —seis filas, y cada fila es un criterio de aceptación y un test:

| # | `sessionActive` | recibo | `currentFilter` | acceso | Acción | Por qué |
|---|---|---|---|---|---|---|
| 1 | **true** | lo que sea | lo que sea | lo que sea | `None` | Una sesión viva conserva su blindaje. |
| 2 | false | ausente | — | — | `None` | Nunca se aplicó nada. |
| 3 | false | presente | `== appliedFilter` | sí | `RestoreFilter(prior)` | El caso normal: lo pusimos, lo devolvemos. |
| 4 | false | presente | `!= appliedFilter` | sí | `ClearReceiptOnly` | **La persona lo cambió a mano.** Gana ella. |
| 5 | false | presente | — | **no** | `ClearReceiptOnly` | Nos revocaron el acceso: no podemos actuar, y no vamos a *crashear*. |
| 6 | false | presente | `UNKNOWN` | sí | `None` | El sistema no supo decirnos: conservamos el recibo y lo reintentamos. |

**La fila 4 es la que merece decirse en voz alta.** Restaurar **no** es "devolverlo a como estaba pase
lo que pase". Si alguien activó No molestar a mano a mitad de sesión porque le empezó una reunión, y
tu app se lo desactiva sola al salir, tu app es peor vecino que si no hubiera hecho nada.

> **El estado global es prestado, no tuyo.** Devuélvelo como te lo dieron, salvo que su dueño ya lo
> haya tocado.

---

## 4. Elegir el disparador: predicado, alarma o `WorkManager`

Una decisión que ya se puede deshacer no sirve de nada si nadie la ejecuta. Hacen falta
**disparadores**, y la elección tiene más miga de la que parece.

La lección 20d eligió, para la caducidad de la sesión, un **predicado puro**: no hay alarma que avise
de que una sesión ha expirado, simplemente `isFocusSessionActive(...)` devuelve `false` la próxima vez
que alguien pregunte. Fue la decisión correcta porque **alguien iba a preguntar**.

Aquí es justo al revés: **nadie va a preguntar**. Si la persona deja de abrir la app, no hay ninguna
recomposición futura que se dé cuenta de que su teléfono sigue en silencio. Esto sí necesita un
evento.

Ahora bien, **necesitar un disparador no es lo mismo que necesitar uno exacto.** Tres disparadores, y
una sola función de restauración:

1. **La salida deliberada** — los dos caminos de salida de `FocusViewModel` (el ritual y "Abandonar
   sesión"). El camino rápido y normal.
2. **Cada arranque en frío** — desde `NeverLateApplication.onCreate`, al lado del
   `BootRescheduleWorker.enqueue` que ya estaba. Cubre "me lo mató el sistema y volví a abrir", que es
   el caso **común**, no un caso límite.
3. **Un *backstop* de `WorkManager` a 12 horas** — cubre el único caso que los otros dos no pueden:
   **que la persona no vuelva a abrir la app nunca.**

### Por qué `WorkManager` y no una cuarta alarma

"En algún momento de las próximas horas, pasada la marca de las 12h" es una garantía perfectamente
buena para dejar de silenciar el teléfono de alguien que abandonó la app hace medio día. Y aceptar esa
imprecisión compra tres cosas:

- `WorkManager` **persiste su propia cola y se reencola tras `BOOT_COMPLETED`**: el reinicio del
  teléfono sale gratis, sin receiver nuevo ni entrada nueva en el manifest.
- Nada de `SCHEDULE_EXACT_ALARM`, ni un `ReminderKind` nuevo, ni tener que reservar un hueco en la
  numeración por tarea de `requestCodeFor(taskId, kind)` (lección 09) — un análisis de colisiones que
  esta feature no tiene ningún motivo para abrir.
- Sigue el precedente que ya hay en el repo (`SyncWorker`, `BootRescheduleWorker`).

> **Criterio general:** primero pregúntate si de verdad necesitas un evento (¿va a preguntar alguien?).
> Si lo necesitas, pregunta **cuánta precisión** necesita. Una alarma exacta es cara —permisos,
> colisiones de request code, batería— y casi nunca es lo que hace falta.

### Y por qué *no* colgarlo del worker que ya existía

`BootRescheduleWorker` parecía el sitio obvio: ya se ejecuta al arrancar. Se descartó, y la razón es
instructiva: **ese worker va de alarmas**. Lee `remindersEnabled` y hace un `return` temprano si los
recordatorios están apagados. Eso no tiene absolutamente nada que ver con si un teléfono se ha quedado
atascado en No molestar — y habría **saltado la restauración a todo el mundo que tenga los
recordatorios desactivados**.

Meter dos invariantes que no se hablan dentro del mismo `return` temprano es una de las formas más
silenciosas de introducir un bug. Se reutilizó el *gancho* que comparten (`onCreate`), no el worker.

### Un detalle fino: `enqueue` no es `enqueueColdStartCheck`

```kotlin
/** El backstop de la sesión: trabajo ÚNICO, con 12h de retraso. */
fun enqueue(context: Context) {
    WorkManager.getInstance(context)
        .enqueueUniqueWork(UNIQUE_WORK_NAME, ExistingWorkPolicy.REPLACE, buildBackstopRequest())
}

/** La comprobación de arranque en frío: inmediata y deliberadamente NO única. */
fun enqueueColdStartCheck(context: Context) {
    WorkManager.getInstance(context).enqueue(OneTimeWorkRequestBuilder<FocusShieldRestoreWorker>().build())
}
```

Si el arranque en frío usara `enqueue` (trabajo único con `REPLACE`), **reemplazaría el backstop
pendiente de 12h de una sesión viva por uno inmediato**, recortándole el plazo. Son dos trabajos
independientes a propósito.

Es exactamente el tipo de error que un test no pilla y que solo se ve pensando en qué significa
`REPLACE` sobre trabajo único. Vale la pena pararse cada vez que escribes `enqueueUniqueWork` y
preguntarte *qué estoy reemplazando*.

---

## 5. Efectos de ventana: alcance en vez de maquinaria

Después de todo lo anterior, las otras dos medidas son un alivio — y ese contraste es justo lo que
enseñan.

Pantalla encendida e inmersivo son **un solo `DisposableEffect`** dentro de `FocusRoute`:

```kotlin
DisposableEffect(activity, keepScreenOn) {
    val window = activity?.window
    if (window == null || !keepScreenOn) return@DisposableEffect onDispose {}

    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    val insetsController = WindowCompat.getInsetsController(window, window.decorView)
    // Requisito duro, no un default: un deslizamiento SIEMPRE devuelve las barras.
    insetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    insetsController.hide(WindowInsetsCompat.Type.systemBars())

    onDispose {
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        insetsController.show(WindowInsetsCompat.Type.systemBars())
    }
}
```

Tres cosas que aprender de este bloque:

**a) Nunca en `MainActivity.onCreate`.** `MainActivity` hace exactamente una cosa con la ventana
(`enableEdgeToEdge()`) y eso no cambia. Un flag puesto en `onCreate` sobrevive a la sesión **por
definición**: dejaría la pantalla de toda la app encendida para siempre. Al ligarlo al composable,
salir de la pantalla —por el ritual, por abandono, por muerte de proceso, por lo que sea— lo revierte
**gratis**. Cero persistencia, cero worker, cero máquina de estados.

**b) `BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE` es accesibilidad, no un adorno.** Un modo inmersivo del
que no puedes salir deslizando es otra forma de atrapar a alguien. Es la misma línea de pensamiento de
la 20d: fricción sí, trampa no.

**c) La contrapartida se acepta, no se esconde.** Ocultar la barra de estado oculta el reloj… en una
app de gestión del tiempo. Se acepta a cambio de que la barra superior de la sesión siga mostrando el
progreso, de que un deslizamiento devuelva las barras, y de que la opción se pueda apagar. No es una
deuda: es una decisión con su motivo escrito.

### Los valores por defecto también salen de la tabla de alcances

| Medida | Por defecto | Por qué |
|---|---|---|
| Pantalla siempre encendida + inmersivo | **ON** | No puede sobrevivir a la sesión, no necesita permiso y no cambia nada fuera de nuestra ventana. |
| Fijar la pantalla | **OFF** | Cambia cómo se comporta el *sistema* y muestra un diálogo del sistema. |
| No molestar | **OFF** | Muta un ajuste global del dispositivo. Activarlo por defecto sería meter a la gente en un teléfono silenciado sin que lo pidan. |

> **La regla:** una medida viene activada por defecto **solo si no puede sobrevivir a la sesión**.

---

## 6. Lock task mode y el hábito del estado verificado

`Activity.startLockTask()` desde una app normal (sin ser *device owner*) entra en el **screen pinning**
de Android: el sistema enseña su propio diálogo, y la persona sale manteniendo pulsado **atrás +
recientes**.

Eso es **fricción real y útil**. No es un candado. Y la copy de la app lo dice con esas palabras, no
con un eufemismo.

Y aquí viene la costumbre que trasciende esta feature:

```kotlin
// Se pide...
if (shieldOptions.screenPinning) {
    try {
        activity?.startLockTask()
    } catch (_: IllegalStateException) {
        // Pinning desactivado en el sistema, restricción del fabricante, o una activity
        // en un estado no soportado — la sesión sigue igual.
    }
}

// ...y se lee de vuelta, en la pantalla de foco:
val screenPinningActive = remember {
    val activityManager = activity?.getSystemService(ActivityManager::class.java)
    activityManager?.lockTaskModeState != ActivityManager.LOCK_TASK_MODE_NONE
}
```

**Pedido ≠ activo.** El indicador de la sesión muestra únicamente lo que está *verificado activo*,
nunca lo que se solicitó. El comportamiento de `startLockTask()` varía lo bastante entre fabricantes
como para que una UI diciendo "pantalla fijada" mientras el teléfono no lo está sea, sencillamente,
una mentira.

Y como el sistema ya sabe la respuesta, **el pinning no necesita recibo**: en un arranque en frío sin
sesión activa, `MainActivity` consulta `getLockTaskModeState()` y llama a `stopLockTask()` si hace
falta. Una consulta, no un registro persistido.

> **El hábito general:** cuando le pides algo a la plataforma, **léelo de vuelta** antes de contárselo
> al usuario. `startLockTask()`, `setInterruptionFilter()`, `setBypassDnd()` — todos pueden ser
> ignorados, y ninguno te lo dice lanzando una excepción.

### Lo que esta feature se negó a prometer

El encargo original decía "evaluar la viabilidad de que ciertas funciones del teléfono queden
deshabilitadas". La respuesta honesta, para una app normal de Play Store, es que **bloquear otras apps
no se puede**:

- Requiere ser **device owner** (aprovisionamiento empresarial que el usuario de esta app nunca va a
  tener), o
- un **`AccessibilityService`** — que técnicamente funcionaría, pero usar las APIs de accesibilidad
  para vigilar el uso de apps es un camino conocido al rechazo y la retirada de Play Store.

Así que no se difiere: **se rechaza**, con el argumento escrito en el spec para que dentro de un año
nadie lea su ausencia como un despiste. Y la app nunca lo insinúa en su texto.

> Saber decir que no, **y dejar escrito por qué**, es parte del trabajo de ingeniería. Una promesa que
> el sistema operativo no puede cumplir no mejora por implementarla a medias.

---

## 7. Un caso interesante: No molestar contra tus propias notificaciones

Una pregunta que había que responder **a propósito**, no por accidente: la app tiene su notificación
permanente de tareas (lección 06) y sus recordatorios (lección 09), incluida la alerta de "tiempo
agotado". ¿La sesión de foco los silencia también?

Dos decisiones:

**a) `INTERRUPTION_FILTER_PRIORITY`, nunca `_NONE`.** El "silencio total" (`_NONE`) suprime **las
alarmas**. Alguien en una sesión de foco puede perderse la alarma de una reunión, de una medicación o
de un vuelo. Para una app cuyo propósito entero son las personas que se pelean con el tiempo, enviar un
modo que silencia el reloj no es una decisión valiente: es un defecto. `_PRIORITY`, además, respeta la
política de prioridad que la persona ya se configuró.

Y solo se llama a `setInterruptionFilter`; **nunca** a `setNotificationPolicy`. Tocar la política
global permitiría más precisión y añadiría un segundo estado global, más rico, que restaurar — y cuya
restauración parcial es muchísimo más fácil de hacer mal. Un `Int` para dentro, un `Int` para fuera: esa
es toda la huella de esta feature en el dispositivo, a propósito.

**b) El canal de alertas pide saltarse No molestar.** La alerta de "tiempo agotado" salta justo cuando
se acaba el plazo de una tarea; durante una sesión de foco, esas tareas **son exactamente en lo que la
persona está trabajando**. Silenciarla haría la sesión peor que no usar la feature. Por eso
`ReminderNotificationHelper.ensureChannel` pasó de `NotificationChannelCompat.Builder` al
`NotificationChannel` de plataforma (el único que expone el flag):

```kotlin
val channel = NotificationChannel(
    REMINDER_NOTIFICATION_CHANNEL_ID,
    context.getString(R.string.reminder_channel_name),
    NotificationManager.IMPORTANCE_HIGH,
).apply {
    description = context.getString(R.string.reminder_channel_description)
    val notificationManager = context.getSystemService(NotificationManager::class.java)
    if (notificationManager?.isNotificationPolicyAccessGranted == true) {
        setBypassDnd(true)
    }
}
```

Y con **dos límites honestos**, documentados en vez de escondidos:

1. **API 24–25 no tienen canales.** Ahí `_PRIORITY` consulta la política global, en la que nuestras
   notificaciones no son categoría prioritaria. Límite de plataforma, aceptado.
2. **Android puede ignorar `bypassDnd` en un canal que ya existía.** Los ajustes de un canal son del
   usuario a partir de su primera creación. Y **no borramos ni recreamos el canal** para forzarlo: eso
   resetearía todas las preferencias que la persona haya puesto ahí. El texto del diálogo lo dice, y la
   salida honesta es que la persona active "Anular No molestar" en Ajustes si quiere.

> Cuando la plataforma te puede decir que no en silencio, **dilo en tu propia UI**. Es más barato que
> un informe de bug que dice "no me sonó la alerta" seis meses después.

---

## Qué se llevó cada fichero

| Fichero | Qué aporta a la lección |
|---|---|
| `domain/focus/FocusShieldRestore.kt` | La decisión pura: `FocusShieldOptions` y `shieldRestoreActionFor` (seis filas, cero Android). |
| `ui/focus/FocusShieldController.kt` | El *seam* sobre `NotificationManager` y `applyFocusShieldOnSessionStart` — la secuencia write-ahead. |
| `ui/focus/FocusShieldRestoreWorker.kt` | El backstop de 12h y la comprobación de arranque en frío (y por qué son dos trabajos distintos). |
| `ui/components/SpecialAccessNotice.kt` | El patrón de acceso especial, compartido, con el re-chequeo en `ON_RESUME`. |
| `ui/focus/FocusScreen.kt` | El `DisposableEffect` de inmersivo + `FLAG_KEEP_SCREEN_ON`, y el indicador de estado verificado. |
| `ui/notification/ReminderNotificationHelper.kt` | `setBypassDnd(true)` y sus dos límites de plataforma. |
| `MainActivity.kt` | La liberación del pinning huérfano en arranque en frío — una consulta, no un recibo. |

## Lo que hay que probar a mano

Merece la pena terminar con esto, porque es lo más honesto de toda la feature: **la mayor parte de
esto no se puede probar de forma fiable en tests**. La lógica pura (`shieldRestoreActionFor`, el orden
de la secuencia de inicio contra fakes) sí, y está cubierta. Pero que No molestar silencie de verdad
una llamada, que la alarma del sistema siga sonando, que `bypassDnd` se aplique o no sobre un canal
preexistente, que el pinning se comporte igual en el móvil de un fabricante concreto… eso solo se ve
en un dispositivo real.

Una suite verde **no es prueba de que esta feature funciona**. Reconocerlo por escrito, y dejar la
lista de comprobación manual en la descripción del PR, es parte del trabajo.

---

## Siguiente paso

La lección **21** (`build-release`) cierra el bloque de producción: build variants, R8/ProGuard, firma
y HTTPS.
