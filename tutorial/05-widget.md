# Lección 05 — Widget de tareas pendientes con Glance y WorkManager

> Objetivo: sacar la app **fuera de su propia pantalla**. Construimos un **widget de pantalla de
> inicio** que muestra las tareas pendientes con su **tiempo restante**, sin abrir la app. El widget
> **reutiliza la base de datos Room** de la lección 04 (no inventa datos nuevos), se **refresca solo**
> cuando cambian las tareas y, además, cada cierto tiempo en segundo plano. Por el camino aprendemos
> **App Widgets con Glance** (Compose para widgets), el patrón **decorador**, y **WorkManager** para
> trabajo periódico.

## Conceptos que aprendes aquí

Partiendo de la lección 04 (Room + `Flow`, repositorio tras interfaz, funciones puras de tiempo,
reloj de pared):

- **[App Widgets con Glance](https://developer.android.com/develop/ui/compose/glance):** qué es un
  widget, el `GlanceAppWidget` y su `GlanceAppWidgetReceiver`, y cómo Glance traduce composables a
  `RemoteViews` para dibujar en el proceso del *launcher*.
- **Un widget leyendo los datos de la app:** obtener el **singleton** de la base de datos desde el
  `Context` del widget y leer un **snapshot** con `.first()` (un widget se dibuja, no observa).
- **Reutilizar las funciones puras de tiempo** de la lección 04 desde una **nueva función pura de
  mapeo** — la única parte del widget testeable en JVM.
- **El patrón decorador:** envolver `TaskRepository` para refrescar el widget en cada escritura, sin
  tocar el repositorio original ni los `ViewModel`.
- **[WorkManager](https://developer.android.com/topic/libraries/architecture/workmanager):** un
  `CoroutineWorker` periódico para refrescar en segundo plano, y por qué su intervalo mínimo
  (~15 min) impide una cuenta atrás segundo a segundo.
- **Ciclo de vida y límites de los widgets**, el
  [`PendingIntent`](https://developer.android.com/reference/android/app/PendingIntent) para abrir la
  app, y un **[deep-link](https://developer.android.com/develop/ui/compose/navigation#deeplinks)**
  para arrancar directamente en la lista de tareas.

---

## 1. ¿Qué es un App Widget y por qué Glance?

Un **App Widget** es una porción de UI de tu app que el sistema dibuja **dentro de otro proceso**: el
del *launcher* (la pantalla de inicio). Eso tiene una consecuencia enorme que gobierna todo el diseño
de esta feature: **tu código no está corriendo ahí**. No puedes pasarle un objeto vivo, ni mantener
una suscripción a un `Flow`, ni animar píxeles a voluntad. El sistema pide un dibujo (`RemoteViews`,
una descripción serializable de vistas) y lo pinta cuando quiere.

Históricamente, construir `RemoteViews` a mano era tedioso y limitado. **Glance** es la biblioteca de
Jetpack que te deja escribir el widget con una API **declarativa al estilo Compose** y se encarga de
traducir tus composables a `RemoteViews` por debajo. Ojo: **no es Compose normal**. Es un
**subconjunto**: tienes `Column`, `Row`, `Text`, `LazyColumn`… pero un `GlanceModifier` propio (no el
`Modifier` de Compose), sin estado en memoria persistente y sin animaciones finas. Escribes parecido,
pero el resultado son vistas remotas, no una jerarquía Compose viva.

---

## 2. El `GlanceAppWidget`: leer datos y dibujar

El widget es una clase que hereda de `GlanceAppWidget` y sobrescribe `provideGlance`, donde (a) lee
los datos y (b) declara el contenido
([PendingTasksWidget.kt](../app/src/main/java/com/neverlate/ui/widget/PendingTasksWidget.kt)):

```kotlin
class PendingTasksWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        // El widget NO puede reutilizar el repositorio inyectado en MainActivity: nunca ejecuta
        // MainActivity.onCreate. Alcanza el mismo singleton de base de datos desde su propio
        // Context, igual que hace MainActivity, y construye el mismo RoomTaskRepository encima.
        val database = NeverLateDatabase.getInstance(context)
        val repository = RoomTaskRepository(database.taskDao())

        // `.first()` toma una foto puntual en vez de una suscripción continua: un widget se dibuja
        // bajo demanda (una vez por llamada a provideGlance), no observa como TasksViewModel.
        val tasks = repository.observeTasks().first()
        val model = toWidgetModel(tasks, System.currentTimeMillis())

        provideContent {
            PendingTasksWidgetContent(model = model, context = context)
        }
    }
}
```

Tres ideas clave:

- **Reutiliza la capa de datos de la lección 04, sin cambiarla.** El widget no tiene una segunda
  fuente de verdad: pide el **mismo** `NeverLateDatabase.getInstance(context)` (el singleton con
  *double-checked locking* de la lección 04) y monta el **mismo** `RoomTaskRepository`. Aquí se paga
  el diseño de la lección 04: como todo depende de la interfaz `TaskRepository`, el widget "engancha"
  sin tocar ni una línea de la UI de tareas.
- **`.first()` en vez de observar.** `TasksViewModel` **observaba** `observeTasks()` para refrescarse
  solo. Un widget no: se dibuja cuando el sistema (o nosotros) se lo pedimos, así que toma una **foto**
  del `Flow` con `.first()` y termina. No hay una recolección viva que mantener.
- **La lógica no vive aquí.** `provideGlance` solo lee y delega en `toWidgetModel(...)` (sección 3) y
  en un composable (sección 4). El `GlanceAppWidget` es una cáscara fina.

---

## 3. La función pura de mapeo: el corazón testeable

Igual que la lección 04 metía toda la lógica del contador en `computeRemainingMillis` (una función
pura, fácil de testear), aquí metemos **todas las decisiones del widget** en una sola función pura,
**sin imports de Glance ni de Android**
([PendingTasksWidgetState.kt](../app/src/main/java/com/neverlate/ui/widget/PendingTasksWidgetState.kt)):

```kotlin
data class PendingTaskRow(val title: String, val remaining: String, val isTimedOut: Boolean)

sealed interface PendingTasksWidgetModel {
    data object Empty : PendingTasksWidgetModel
    data class Content(val rows: List<PendingTaskRow>) : PendingTasksWidgetModel
}

fun toWidgetModel(tasks: List<Task>, now: Long): PendingTasksWidgetModel {
    if (tasks.isEmpty()) return PendingTasksWidgetModel.Empty

    val rows = tasks
        .map { task -> task to computeRemainingMillis(task, now) }
        .sortedBy { (_, remainingMillis) -> remainingMillis }   // más urgente primero
        .take(MAX_VISIBLE_TASKS)                                // el widget tiene poco sitio
        .map { (task, remainingMillis) ->
            PendingTaskRow(
                title = task.title,
                remaining = formatRemaining(remainingMillis),
                isTimedOut = remainingMillis == 0L,
            )
        }
    return PendingTasksWidgetModel.Content(rows)
}
```

Puntos didácticos:

- **Reutiliza las funciones puras de la lección 04.** El tiempo restante sale de
  `computeRemainingMillis(task, now)` y el texto de `formatRemaining(...)`. **No duplicamos** la lógica
  de tiempo: el widget muestra exactamente el mismo restante que la app para esa tarea en ese instante.
  Aquí se cobra otra vez el diseño "reloj de pared": el widget recalcula el restante desde
  `System.currentTimeMillis()` sin heredar ningún temporizador vivo.
- **Es el equivalente al `onTasksTick` de la lección 04**, pero para el widget: decide qué es
  "pendiente", el orden (**más urgente primero**), el recorte a `MAX_VISIBLE_TASKS` (5) y el estado
  vacío. Como es pura, se testea en JVM pasándole listas de `Task` y un `now` fijo, sin arrancar un
  host de widgets.
- **Definición de "pendiente" (la spec la dejó abierta):** cuentan **todas** las tareas, **incluidas
  las agotadas** (restante 0). El trabajo de un widget es enseñar lo que está pendiente, y ocultar una
  tarea agotada escondería justo lo que más urge ver. Como las agotadas tienen restante 0, el orden
  ascendente las coloca **arriba del todo**.

---

## 4. La UI del widget: composables de Glance

`provideContent` recibe un bloque `@Composable` que usa los componentes **de Glance**
([PendingTasksWidget.kt](../app/src/main/java/com/neverlate/ui/widget/PendingTasksWidget.kt)):

```kotlin
@Composable
private fun PendingTasksWidgetContent(model: PendingTasksWidgetModel, context: Context) {
    // Tocar cualquier parte del widget abre MainActivity directo en la lista de tareas. Glance
    // construye el PendingIntent por nosotros (con FLAG_IMMUTABLE, como exige Android moderno).
    val openTasks = actionStartActivity(
        Intent(context, MainActivity::class.java).apply {
            putExtra(MainActivity.EXTRA_OPEN_TASKS, true)
        },
    )

    Column(
        modifier = GlanceModifier.fillMaxSize().background(WidgetBackground).padding(12.dp)
            .clickable(openTasks),
    ) {
        Text(text = context.getString(R.string.widget_pending_tasks_title), /* ... */)
        when (model) {
            is PendingTasksWidgetModel.Empty ->
                Text(text = context.getString(R.string.widget_pending_tasks_empty), /* ... */)
            is PendingTasksWidgetModel.Content ->
                for (row in model.rows) PendingTaskRowContent(row)
        }
    }
}
```

- **`GlanceModifier`, `Column`, `Row`, `Text`** son las versiones de Glance, no las de Compose (fíjate
  en los imports: `androidx.glance.*`). El estilo es mínimo a propósito (la spec pide "sin theming
  avanzado"): un color de fondo, un título en negrita y filas título + restante. Una fila agotada pinta
  su tiempo en rojo para que salte a la vista.
- **Texto siempre desde `strings.xml`** (`context.getString(...)`): el código no lleva texto visible
  hardcodeado, como en toda la app.
- **El `when` sobre el `sealed interface`** (`Empty` / `Content`) es el mismo patrón de estado de las
  lecciones anteriores: el compilador obliga a cubrir el estado vacío, así que nunca sale un recuadro
  en blanco.

### Abrir la app al tocar: `PendingIntent` y deep-link

`actionStartActivity(intent)` le dice a Glance que, al tocar, lance un `Intent` hacia `MainActivity`.
Glance envuelve eso en un **`PendingIntent`** (un "permiso para que otro proceso lance algo tuyo más
tarde") con las flags correctas (`FLAG_IMMUTABLE`) sin que tengamos que construirlo a mano.

Le añadimos un **extra** (`EXTRA_OPEN_TASKS`) para que la app **arranque directamente en la lista de
tareas** en vez de en su destino normal. `MainActivity` lo lee y lo pasa al `AppNavHost`
([MainActivity.kt](../app/src/main/java/com/neverlate/MainActivity.kt)):

```kotlin
val openTasksOnStart = intent?.getBooleanExtra(EXTRA_OPEN_TASKS, false) ?: false
// ...
AppNavHost(/* ... */, openTasksOnStart = openTasksOnStart)
```

En [AppNavHost.kt](../app/src/main/java/com/neverlate/ui/navigation/AppNavHost.kt) el destino inicial
pasa a ser: si el usuario **no** ha hecho onboarding → `onboarding`; si sí y viene del widget
(`openTasksOnStart`) → `tasks`; en otro caso → `home`. El onboarding siempre gana primero, para no
romper el arranque de un usuario nuevo.

---

## 5. Refresco por cambios: el patrón decorador

Problema: cuando el usuario crea, edita, borra, inicia o pausa una tarea **dentro de la app**, el
widget debería reflejarlo. Pero el widget no observa nada (sección 2). Alguien tiene que **decirle
"redibújate"** tras cada escritura, llamando a `PendingTasksWidget().updateAll(context)`.

¿Dónde ponemos esa llamada? Podríamos meterla en cada `ViewModel`, pero eso los ataría al widget.
Mejor solución, y concepto nuevo de esta lección: el **patrón decorador**. Envolvemos `TaskRepository`
en otra implementación de la **misma interfaz** que delega todo en el original y, además, refresca el
widget tras cada escritura
([WidgetRefreshingTaskRepository.kt](../app/src/main/java/com/neverlate/ui/widget/WidgetRefreshingTaskRepository.kt)):

```kotlin
class WidgetRefreshingTaskRepository(
    private val delegate: TaskRepository,
    private val context: Context,
) : TaskRepository {

    // Las lecturas se delegan tal cual.
    override fun observeTasks(): Flow<List<Task>> = delegate.observeTasks()
    override fun observeTask(id: Long): Flow<Task?> = delegate.observeTask(id)

    // Las escrituras se delegan y, después, refrescan el widget.
    override suspend fun saveTask(task: Task) { delegate.saveTask(task); refreshWidget() }
    override suspend fun deleteTask(id: Long) { delegate.deleteTask(id); refreshWidget() }
    override suspend fun startTimer(id: Long) { delegate.startTimer(id); refreshWidget() }
    override suspend fun pauseTimer(id: Long) { delegate.pauseTimer(id); refreshWidget() }

    private suspend fun refreshWidget() {
        // updateAll es barato (un no-op) si el usuario nunca colocó el widget: no hace falta
        // comprobar "¿hay un widget en la pantalla?" antes.
        PendingTasksWidget().updateAll(context)
    }
}
```

Y en `MainActivity` **envolvemos una sola vez**, sin tocar nada más:

```kotlin
val taskRepository: TaskRepository =
    WidgetRefreshingTaskRepository(RoomTaskRepository(database.taskDao()), applicationContext)
```

Por qué es elegante:

- **Los `ViewModel` ni se enteran** de que existe un widget: siguen dependiendo de `TaskRepository`.
- **El `RoomTaskRepository` original queda intacto** — el decorador lo *envuelve*, no lo modifica.
- Toda la responsabilidad "refrescar el widget" vive en **un único sitio**. Este es el mismo espíritu
  "acceso a datos tras una interfaz" de las lecciones 03–04, ahora aprovechado para **añadir
  comportamiento** sin herencia.

---

## 6. Refresco periódico: WorkManager

El refresco por cambios cubre lo que pasa **dentro de la app**. Pero el tiempo restante avanza aunque
nadie toque nada: si el widget se queda quieto, su cifra se va quedando vieja. Para eso está el
segundo mecanismo: un trabajo **periódico** en segundo plano con **WorkManager**
([WidgetRefreshWorker.kt](../app/src/main/java/com/neverlate/ui/widget/WidgetRefreshWorker.kt)):

```kotlin
class WidgetRefreshWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        PendingTasksWidget().updateAll(applicationContext)
        return Result.success()
    }

    companion object {
        fun enqueuePeriodic(context: Context) {
            val request = PeriodicWorkRequestBuilder<WidgetRefreshWorker>(15, TimeUnit.MINUTES).build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork("widget_refresh", ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
```

- **`CoroutineWorker`** es la clase base de WorkManager amigable con corrutinas: `doWork` corre **fuera
  del hilo principal** y puede llamar funciones `suspend` directamente (como `updateAll`).
- **`enqueueUniquePeriodicWork` + `ExistingPeriodicWorkPolicy.KEEP`** lo hace **idempotente**: lo
  encolamos en cada `MainActivity.onCreate`, pero si ya hay un trabajo con ese nombre único, WorkManager
  lo deja como está y **no** apila duplicados. Así no hay que preguntarse "¿ya lo programé?".
- **El límite de los 15 minutos.** WorkManager **no permite** un `PeriodicWorkRequest` más frecuente
  que ~15 min; por debajo, lo sube en silencio. Por eso el widget **no cuenta atrás segundo a
  segundo**: muestra el restante del último refresco y puede estar hasta ~15 min desfasado si nadie
  toca una tarea. **Esto es una limitación aceptada del sistema, no un bug** (spec, US-5). El contador
  fino segundo a segundo sigue viviendo en la app (`countdownTicker` de la lección 04).

> **Dos relojes, un mismo dato.** La app se refresca cada segundo mientras está a la vista; el widget
> se refresca por cambios (al instante) y periódicamente (~15 min). Ambos derivan el restante de las
> **mismas** funciones puras sobre las **mismas** filas de Room: nunca se contradicen, solo se refrescan
> a ritmos distintos.

---

## 7. El receiver y el manifiesto: cómo entra el sistema

El sistema no habla con tu `GlanceAppWidget` directamente, sino con un **`BroadcastReceiver`**. Glance
nos da uno casi vacío
([PendingTasksWidgetReceiver.kt](../app/src/main/java/com/neverlate/ui/widget/PendingTasksWidgetReceiver.kt)):

```kotlin
class PendingTasksWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = PendingTasksWidget()
}
```

`GlanceAppWidgetReceiver` recibe cada *broadcast* relevante del `AppWidgetManager` (colocado,
redimensionado, actualización pedida, eliminado) y lo reenvía a nuestro `PendingTasksWidget`. Hay que
declararlo en el **manifiesto**
([AndroidManifest.xml](../app/src/main/AndroidManifest.xml)):

```xml
<receiver
    android:name=".ui.widget.PendingTasksWidgetReceiver"
    android:exported="true">
    <intent-filter>
        <action android:name="android.appwidget.action.APPWIDGET_UPDATE" />
    </intent-filter>
    <meta-data
        android:name="android.appwidget.provider"
        android:resource="@xml/pending_tasks_widget_info" />
</receiver>
```

- **`exported="true"`**: lo invoca un proceso externo (el *launcher*/sistema), así que debe ser
  accesible desde fuera.
- **El `intent-filter APPWIDGET_UPDATE`** lo marca como un proveedor de widgets.
- **El `meta-data`** apunta al XML de configuración del widget.

Ese XML, `res/xml/pending_tasks_widget_info.xml`, describe tamaño y comportamiento
([pending_tasks_widget_info.xml](../app/src/main/res/xml/pending_tasks_widget_info.xml)):

```xml
<appwidget-provider
    android:minWidth="250dp" android:minHeight="110dp"
    android:targetCellWidth="4" android:targetCellHeight="2"
    android:updatePeriodMillis="0"
    android:resizeMode="horizontal|vertical"
    android:widgetCategory="home_screen"
    android:description="@string/widget_pending_tasks_description" />
```

Fíjate en **`updatePeriodMillis="0"`**: desactiva a propósito el refresco periódico **propio del
sistema**. Ya conducimos nosotros los refrescos (decorador + WorkManager); dejarlo activo sería tener
**dos horarios de refresco compitiendo** sin dueño claro. `minWidth`/`minHeight` y las celdas objetivo
fijan un tamaño razonable; `resizeMode` permite estirarlo; `widgetCategory="home_screen"` lo limita a
la pantalla de inicio (no a la de bloqueo — eso será la feature 06).

---

## 8. Dependencias nuevas: Glance y WorkManager

Como siempre, al **catálogo de versiones** (`gradle/libs.versions.toml`), nada hardcodeado en el
`build.gradle.kts`:

- **Glance para App Widgets** (`androidx.glance:glance-appwidget`, versión `1.1.1`): la API declarativa
  del widget. Requiere `minSdk 23` como mínimo; el proyecto tiene `minSdk 24`, así que compatible sin
  ajustes. La versión `1.1.1` es estable y compila con el Kotlin `2.1.0` / Compose BOM `2024.12.01` del
  proyecto.
- **WorkManager** (`androidx.work:work-runtime-ktx`, versión `2.10.0`): el planificador de trabajo en
  segundo plano para el refresco periódico. WorkManager registra su propio proveedor por defecto, así
  que no hace falta inicialización extra ni permisos nuevos.

Ninguna de las dos requiere permisos en el manifiesto: el widget es local y de solo lectura (nada de
`INTERNET` ni `POST_NOTIFICATIONS`).

---

## 9. Ciclo de vida y límites de los widgets (resumen)

Lo esencial que hay que interiorizar de esta feature:

- **Vive en otro proceso** (el del *launcher*): no puedes pasarle objetos vivos; el widget alcanza el
  singleton de Room por su cuenta con `getInstance(context)`.
- **Se dibuja, no observa:** lee un snapshot con `.first()` en cada `provideGlance`. Para que refleje
  cambios, **alguien** debe llamar a `updateAll` (decorador) o programarlo (WorkManager).
- **Refresco con granularidad de minutos:** el suelo de ~15 min de WorkManager (y de
  `updatePeriodMillis`) impide una animación segundo a segundo. Aceptado por diseño.
- **Solo lectura:** el widget muestra y abre la app al tocar. Crear/editar/iniciar/pausar desde el
  propio widget queda fuera de alcance.
- **`updateAll` es barato si no hay widget colocado:** no hay que comprobar antes si el usuario lo
  añadió.

---

## 10. Tests

La UI de Glance es **difícil de testear en unidad** (renderiza a `RemoteViews`, no hay un
`ComposeTestRule` equivalente estándar). Por eso, como en la lección 04, empujamos **toda la lógica a
una función pura** y la testeamos en JVM
([PendingTasksWidgetStateTest.kt](../app/src/test/java/com/neverlate/ui/widget/PendingTasksWidgetStateTest.kt)):

- **`toWidgetModel(tasks, now)`** cubierta en JVM, sin emulador: lista vacía → `Empty`; una tarea →
  una fila con título + restante formateado; orden **más urgente primero** con entrada desordenada;
  tarea agotada → `00:00`, `isTimedOut = true` y **arriba del todo**; más de 5 tareas → exactamente 5
  filas (las 5 más urgentes, y la sexta ausente); y la frontera de formato `mm:ss` ↔ `h:mm:ss`.
- Se **apoya** en las funciones ya testeadas de `TaskTiming` (lección 04); no las reimplementa.

Lo que **no** se testea en unidad (se verifica a mano en el emulador): el render de Glance, el
`PendingTasksWidgetReceiver`, el `PendingIntent` y el disparo real de `updateAll`/WorkManager.

```bash
# Tests unitarios (JVM, sin emulador)
./gradlew :app:testDebugUnitTest

# Compila el APK (verifica que Glance y WorkManager enlazan bien)
./gradlew :app:assembleDebug
```

---

## 11. Probar la app

```bash
./gradlew :app:installDebug
adb shell am start -n com.neverlate/.MainActivity
```

- Crea alguna tarea en la app (lección 04).
- Ve a la **pantalla de inicio**, mantén pulsado → **Widgets**, busca **"Never Late"** y **coloca** el
  widget. Debe aparecer con tus tareas pendientes y su tiempo restante (o el mensaje de estado vacío si
  no hay ninguna), **sin abrir la app primero**.
- Vuelve a la app, **crea/edita/borra o inicia/pausa** una tarea: el widget se **actualiza** para
  reflejarlo (refresco por cambios, vía el decorador).
- **Toca el widget:** la app se abre directamente en la **lista de tareas** (deep-link).
- Deja el widget quieto: su cifra se refresca **periódicamente** en segundo plano (hasta ~15 min de
  desfase; no cuenta segundo a segundo — es esperado).
- Todo funciona **sin conexión**: es local.

---

## Documentación oficial

- **Glance (App Widgets con Compose)** — [Jetpack Glance](https://developer.android.com/develop/ui/compose/glance)
  · [Crear un widget](https://developer.android.com/develop/ui/compose/glance/create-app-widget)
- **App Widgets (fundamentos)** — [App widgets overview](https://developer.android.com/develop/ui/views/appwidgets/overview)
- **WorkManager** — [WorkManager](https://developer.android.com/topic/libraries/architecture/workmanager)
  · [Definir el trabajo (`CoroutineWorker`)](https://developer.android.com/develop/background-work/background-tasks/persistent/getting-started/define-work)
- **`PendingIntent`** — [PendingIntent (referencia)](https://developer.android.com/reference/android/app/PendingIntent)
- **Deep links en Compose** — [Navigation with Compose · deep links](https://developer.android.com/develop/ui/compose/navigation#deeplinks)
- **Patrón decorador** — [Delegation (Kotlin)](https://kotlinlang.org/docs/delegation.html)

---

## 12. Siguiente paso

Ya sabemos pintar **fuera de la Activity** y compartir la base de datos con otro proceso. Sobre esto:

- La **feature 06** (`docs/prompts/06-tareas-lockscreen.md`) mostrará la tarea activa en la **pantalla
  de bloqueo** (notificación / *foreground service*), reutilizando de nuevo el mismo
  `TaskRepository`.
- La **feature 09** (`docs/prompts/09-recordatorios.md`) añadirá **recordatorios/alarmas** al agotarse
  el tiempo, apoyándose también en WorkManager.

El patrón se repite: la interfaz `TaskRepository` y el singleton de Room son el punto de reutilización;
cada nueva superficie (widget, notificación, recordatorio) **lee los mismos datos** sin tocar las
demás.
