# Lección 13e — `@EntryPoint` y un token de color compartido

La lección 13d migró casi todo el proyecto a Hilt: `MainActivity`, los nueve `ViewModel`s, la cadena
de decoradores de `TaskRepository`. Casi todo, porque dejó fuera al **widget** —
`PendingTasksWidget.provideGlance` seguía montando `NeverLateDatabase.getInstance(...)` y
`RoomTaskRepository(...)` a mano, exactamente como hacía `MainActivity` antes de la 13d. La 05b, a su
vez, dejó otra deuda escrita en un comentario: dos mapeos de color — uno en el mundo Compose, otro en
el mundo Glance — que codifican la misma decisión ("¿qué color es 'urgente'?") y que alguien tiene que
recordar cambiar a la vez.

Esta lección cierra las dos, y son la misma clase de lección: **13d resolvió el caso cómodo de Hilt**
(una `Activity`, un `ViewModel` — ambos construidos *por* Hilt). Esta resuelve **el caso incómodo**:
una clase que Hilt **no** construye, y con eso viene la pregunta que da nombre al problema real de
cualquier grafo de dependencias no trivial — no "¿cómo inyecto?", sino "¿**qué** inyecto?".

## Conceptos que aprendes aquí

Partiendo de la 13d (Hilt, `@Module`/`@Provides`/`@Binds`, la cadena de qualifiers de `TaskRepository`)
y la 05b (por qué el tema de Compose no cruza a Glance):

- **`@EntryPoint` / `EntryPointAccessors`:** cómo se saca algo del grafo de Hilt desde una clase que
  **no puedes anotar**, porque no la construyes tú. Es la otra cara de `@AndroidEntryPoint`: en vez de
  "Hilt, rellena mis campos cuando me construyas", dice "yo ya existo, dame acceso al grafo de todas
  formas".
- **El grafo tiene forma, y esa forma importa:** "inyecta el `TaskRepository`" no es una pregunta con
  una única respuesta cuando la interfaz tiene cuatro implementaciones apiladas. Elegir la capa
  equivocada no rompe la compilación — rompe el programa, en producción, de una forma que solo se ve
  al escribir desde el sitio equivocado.
- **Duplicar un valor frente a duplicar una decisión:** la 05b ya evitó que el mismo *color* viviera en
  dos sitios. Lo que quedó duplicado fue el ***mapeo*** — la decisión de qué rol le corresponde a cada
  nivel. Extraerla obliga a inventar un tipo que no es ni un `Color` ni un `ColorProvider`, sino un
  **nombre de rol** — y ese salto de abstracción es, en sí mismo, la lección.

---

## 1. El problema: el widget es el único cliente de datos fuera del grafo

Antes de esta feature, `PendingTasksWidget.provideGlance` tenía este aspecto:

```kotlin
// Antes (05-13d): el widget monta su propio acceso a datos, a mano, cada vez que dibuja.
override suspend fun provideGlance(context: Context, id: GlanceId) {
    val database = NeverLateDatabase.getInstance(context)
    val repository = RoomTaskRepository(database.taskDao())
    val tasks = repository.observeTasks().first()
    // ...
}
```

El comentario que lo acompañaba explicaba correctamente *por qué* no podía ser de otra forma con la DI
manual: el widget nunca ejecuta `MainActivity.onCreate`, así que no hay ningún sitio donde recibir el
repositorio ya construido — tiene que montarlo él mismo, desde su propio `Context`. Con Hilt de por
medio desde la 13d, esa razón ya no aplica al *resto* de la app (todo pasa por el grafo), pero seguía
aplicando al widget, por un motivo distinto: **un `GlanceAppWidget` no es una clase Android que Hilt
sepa construir**.

## 2. Por qué `@Inject` y `@AndroidEntryPoint` no sirven aquí

`@Inject` en un campo solo funciona si **Hilt es quien crea el objeto** — necesita interceptar la
construcción para poder rellenar esos campos después. `@AndroidEntryPoint` es la anotación que le dice
a Hilt "esta clase de Android sí la construyes tú, engánchate": funciona sobre `Application`,
`Activity`, `Fragment`, `Service` y `BroadcastReceiver` — las clases cuyo ciclo de vida el framework
dispara y Hilt puede interceptar con un `Application.ActivityLifecycleCallbacks` o equivalente.

`PendingTasksWidget` no es ninguna de esas. Se instancia con un `PendingTasksWidget()` normal y
corriente, directamente, en **tres** sitios distintos del proyecto:

```kotlin
// PendingTasksWidgetReceiver.kt — cuando el usuario coloca el widget desde el selector
override val glanceAppWidget: GlanceAppWidget = PendingTasksWidget()

// TaskSurfacesRefreshingRepository.kt — tras cada escritura de una tarea
PendingTasksWidget().updateAll(context)

// TaskSurfacesRefreshWorker.kt — en el refresco periódico de WorkManager
PendingTasksWidget().updateAll(context)
```

Ninguno de los tres es una clase que Hilt intercepte. `@Inject` sobre un campo de `PendingTasksWidget`
sencillamente no se rellenaría nunca — nadie lo dispara.

## 3. `@EntryPoint`: la otra cara de `@AndroidEntryPoint`

Hilt resuelve esto con una idea simétrica a `@AndroidEntryPoint`, pero desde el otro lado: en vez de
"engánchame cuando me construyas", un `@EntryPoint` dice "aquí tienes un punto de entrada manual al
grafo, ya no me importa quién te construyó a ti":

```kotlin
// app/src/main/java/com/neverlate/di/WidgetEntryPoint.kt
@EntryPoint
@InstallIn(SingletonComponent::class)
interface WidgetEntryPoint {

    @ReminderRepo
    fun taskRepository(): TaskRepository
}
```

Es una interfaz normal, sin cuerpo, con un método por cada cosa que se quiera sacar del grafo — Hilt
genera la implementación igual que genera el resto del código de un `@Module`. `@InstallIn` es el
mismo sistema de ámbitos que ya conoces de la 13d: este entry point cuelga del `SingletonComponent`,
el mismo componente raíz del que cuelgan `DatabaseModule`, `RepositoryModule`, etc.

Para usarlo, `provideGlance` lo resuelve con `EntryPointAccessors.fromApplication`, pasándole el
`Context` de aplicación y la clase del propio entry point:

```kotlin
// PendingTasksWidget.kt
override suspend fun provideGlance(context: Context, id: GlanceId) {
    val repository = EntryPointAccessors
        .fromApplication(context.applicationContext, WidgetEntryPoint::class.java)
        .taskRepository()

    val tasks = repository.observeTasks().first()
    // ...
}
```

`fromApplication` (no `fromActivity` ni `fromFragment`) porque un widget no tiene ni una ni otra a
mano — solo el `Context` que Glance le pasa, y de ahí solo se puede subir hasta el
`Application`/`SingletonComponent`. La ventaja frente a cualquier alternativa que tocara el
constructor de `PendingTasksWidget` es que se resuelve **dentro** de `provideGlance` — así que los
tres sitios que hacen `PendingTasksWidget()` de la sección 2 **no cambian ni una línea**.

## 4. El grafo tiene forma: qué capa, no solo cómo

Con `@EntryPoint` resuelto, queda la pregunta que de verdad importa. `TaskRepository` no es una
interfaz con una implementación — la 13d ya enseñó que son **cuatro**, apiladas como decoradores:

```
TaskSurfacesRefreshingRepository   (sin qualifier — la que inyecta el resto de la app)
  └─ ReminderSchedulingRepository  (@ReminderRepo)
       └─ OutboxTaskRepository     (@OutboxRepo)
            └─ RoomTaskRepository  (@RoomRepo)
```

`WidgetEntryPoint.taskRepository()` **no** pide la capa sin qualificar, que es la que pediría
cualquier otro consumidor de la app. Pide específicamente `@ReminderRepo`. La razón es un ciclo que no
existe hoy, pero que existiría en cuanto el widget hiciera algo más que leer.

`TaskSurfacesRefreshingRepository` — la capa más externa, la sin qualificar — es precisamente la que
**refresca el widget**:

```kotlin
// TaskSurfacesRefreshingRepository.kt (resumido)
private suspend fun refreshSurfaces() {
    PendingTasksWidget().updateAll(context)
    TasksNotificationService.refresh(context)
}
```

Si `WidgetEntryPoint` expusiera esa capa y el widget algún día **escribiera** a través de ella (marcar
una tarea completada desde una fila, por ejemplo — la feature que motivó escribir esto ahora, antes de
que existiera esa tentación), el ciclo se cerraría solo:

```
escritura desde el widget
  → TaskSurfacesRefreshingRepository.complete(...)
    → refreshSurfaces()
      → PendingTasksWidget().updateAll(context)
        → provideGlance(...)   ← el widget se reconstruye a sí mismo, con la misma capa
```

Nada de esto es un bug hipotético con nombre grandioso — es la misma clase de bug que una recursión sin
caso base: cada refresco dispara el siguiente. El cableado manual de antes de esta feature **evitaba**
este ciclo, pero por accidente: nadie eligió conscientemente qué capa recibía el widget, porque no
había capas — solo `RoomTaskRepository` a pelo. En cuanto el acceso pasa por el grafo, "qué capa" deja
de ser un detalle y se convierte en una decisión que hay que tomar y dejar escrita.

`@ReminderRepo` es la respuesta: es la capa más externa de la cadena que **no** vuelve a llamar a
`refreshSurfaces()`. Leer desde ahí da exactamente los mismos datos que la capa sin qualificar (cada
decorador intermedio delega `observeTasks()` sin tocarlo), y una escritura futura a través de ella
seguiría pasando por el outbox y la programación de recordatorios — lo que **sí** debe pasar — sin
volver a disparar el refresco de superficies. Quien escriba desde el widget en el futuro decidirá
explícitamente si redibujarlo; no quedará implícito en la capa.

La alternativa de "arreglar" esto con una guarda de reentrancia (un flag, un `ThreadLocal`) se descartó
a propósito: añade estado mutable y una condición de carrera a un decorador hoy trivial, para resolver
algo que la elección correcta de capa resuelve sin escribir código nuevo. Elegir bien la capa **es** la
solución, no un parche sobre ella.

## 5. Duplicar un valor frente a duplicar una decisión

La segunda mitad de esta lección no tiene nada que ver con Hilt. `WidgetColors.kt` (feature 05b)
tenía este aviso en su KDoc, literal:

> *"whoever changes `colorForUrgency` or `Priority.indicatorColor()` must change the matching
> function here too, or the task card and the widget will silently disagree on what a color means."*

Un comentario que le pide disciplina a un humano es exactamente el tipo de deuda que se paga con un
tipo, no con más disciplina. Antes de tocar nada, había **dos** funciones que hacían la misma
pregunta — "¿qué color le corresponde a este nivel de urgencia?" — en dos mundos:

```kotlin
// Compose — ui/tasks/TasksScreen.kt (antes)
private fun colorForUrgency(level: UrgencyLevel): Color = when (level) {
    UrgencyLevel.Calm -> NeverLateExtras.colors.calm
    UrgencyLevel.Soon -> NeverLateExtras.colors.soon
    UrgencyLevel.Urgent, UrgencyLevel.Overdue -> MaterialTheme.colorScheme.error
}

// Glance — ui/widget/WidgetColors.kt (antes)
fun urgencyColorProvider(level: UrgencyLevel): ColorProvider = when (level) {
    UrgencyLevel.Calm -> CalmColor
    UrgencyLevel.Soon -> SoonColor
    UrgencyLevel.Urgent, UrgencyLevel.Overdue -> GlanceTheme.colors.error
}
```

Nota lo que tienen en común: el `when` es **idéntico** en su forma — mismos casos, mismo agrupamiento
de `Urgent`/`Overdue`. Lo único que cambia es de dónde sale el `Color`/`ColorProvider` final. Eso es la pista de que hay
**una** decisión (el mapeo `UrgencyLevel → algo`) implementada dos veces, no dos decisiones distintas.

## 6. El tipo que no es un `Color`: un token de rol

La 05b ya había resuelto la mitad del problema — el *valor* del color no está duplicado, ambos mundos
leen las mismas paletas base (`ui/theme/Color.kt`). Lo que faltaba era dejar de duplicar el ***mapeo***
sin volver a romper la frontera que la 05b levantó: un `Color` de Compose no significa nada dentro de
una composición de Glance, así que la función compartida **no puede** devolver un `Color`.

La solución es un tipo intermedio — ni Compose ni Glance, puro Kotlin — que solo nombra el **rol**:

```kotlin
// domain/tasks/ColorRole.kt
enum class ColorRole { Calm, Soon, Error, Primary, Secondary, Tertiary }

fun urgencyColorRole(level: UrgencyLevel): ColorRole = when (level) {
    UrgencyLevel.Calm -> ColorRole.Calm
    UrgencyLevel.Soon -> ColorRole.Soon
    UrgencyLevel.Urgent, UrgencyLevel.Overdue -> ColorRole.Error
}

fun priorityColorRole(priority: Priority): ColorRole? = when (priority) {
    Priority.NONE -> null
    Priority.LOW -> ColorRole.Secondary
    Priority.MEDIUM -> ColorRole.Tertiary
    Priority.HIGH -> ColorRole.Primary
}
```

Fíjate en un detalle que no es casualidad: hay **cuatro** valores de `UrgencyLevel` pero solo **tres**
roles de urgencia se usan (`Calm`/`Soon`/`Error`). `Urgent` y `Overdue` comparten `Error` — eso ya era
así antes de esta feature (los dos son, visualmente, la misma señal de "mira esto ya"), y extraer el
mapeo a una función no cambia esa decisión, solo la deja en un único sitio. Es la prueba de que
`urgencyColorRole` sigue siendo **la misma decisión de antes**, no una decisión nueva.

`ColorRole` no vive en `ui/theme/` ni en `ui/widget/`, sino en `domain/tasks/`, junto a las demás
reglas puras del proyecto (`urgencyLevelFor`, `deadlineProgressFor`). El motivo es que lo que modela —
"qué **significa** este nivel de urgencia" — es vocabulario del dominio de tareas, no de un tema
visual concreto; y no vive en `ui/widget/` porque eso ataría un tipo compartido al consumidor menor
(el widget), obligando a la lista de tareas — el consumidor mayor — a depender "hacia arriba" de un
paquete que en principio no debería conocer.

## 7. Dos resolutores finos, uno por mundo

Con el mapeo extraído, las cuatro funciones originales dejan de decidir nada — solo traducen un rol ya
decidido a un color, cada una en su propio lenguaje de temas:

```kotlin
// Compose — ui/tasks/TasksScreen.kt (después)
private fun colorForUrgency(level: UrgencyLevel): Color = when (urgencyColorRole(level)) {
    ColorRole.Calm -> NeverLateExtras.colors.calm
    ColorRole.Soon -> NeverLateExtras.colors.soon
    ColorRole.Error -> MaterialTheme.colorScheme.error
    ColorRole.Primary, ColorRole.Secondary, ColorRole.Tertiary ->
        error("urgencyColorRole never returns a priority role")
}

// Glance — ui/widget/WidgetColors.kt (después)
fun urgencyColorProvider(level: UrgencyLevel): ColorProvider = when (urgencyColorRole(level)) {
    ColorRole.Calm -> CalmColor
    ColorRole.Soon -> SoonColor
    ColorRole.Error -> GlanceTheme.colors.error
    ColorRole.Primary, ColorRole.Secondary, ColorRole.Tertiary ->
        error("urgencyColorRole never returns a priority role")
}
```

El `when` sigue existiendo en los dos sitios — pero ya no es el **mismo** `when` duplicado: cada uno
es exhaustivo sobre `ColorRole`, no sobre `UrgencyLevel`, y solo traduce roles que su mundo sabe
resolver. Las ramas de `Primary`/`Secondary`/`Tertiary` (roles de prioridad, no de urgencia) usan
`error(...)` en vez de devolver algo con sentido, porque `urgencyColorRole` nunca los produce — es un
`when` exhaustivo por contrato del compilador, pero con un caso que la propia función que lo alimenta
garantiza que nunca ocurre; es preferible fallar ruidosamente ahí que devolver un color arbitrario si
`ColorRole` ganara un caso nuevo el día de mañana.

Con esto, cambiar qué color significa "urgente" es tocar `urgencyColorRole` — **un** sitio — y los dos
mundos lo recogen automáticamente, en vez de depender de que quien lo cambie recuerde el segundo sitio.
El aviso de "cambia los dos a la vez" desaparece del KDoc de `WidgetColors.kt` porque deja de ser
cierto: ahora es estructuralmente imposible que se desincronicen.

## 8. Lo que no cambia (y por qué)

Los pares `ColorProvider(day, night)` escritos a mano en `WidgetColors.kt` (`CalmColor`, `SoonColor`,
`dividerColor`) **no forman parte de esta duplicación** y esta lección no los toca. Son la respuesta a
un problema distinto, ya resuelto por la 05b: `calm`/`soon` no son roles de Material 3, y
`outlineVariant` existe en `ColorScheme` pero el puente `glance-material3` no lo expone. Siguen siendo
necesarios exactamente igual que antes — lo único que cambia es que ahora los alimenta la misma
función compartida (`urgencyColorRole`) que alimenta al mundo Compose, en vez de un `when` propio.

---

## Repaso: ficheros de la feature

**Nuevo**
- [`di/WidgetEntryPoint.kt`](../app/src/main/java/com/neverlate/di/WidgetEntryPoint.kt) — el
  `@EntryPoint` que le da al widget acceso al grafo, scoped a `@ReminderRepo`.
- [`domain/tasks/ColorRole.kt`](../app/src/main/java/com/neverlate/domain/tasks/ColorRole.kt) — el
  enum de rol y las dos funciones puras `urgencyColorRole`/`priorityColorRole`.
- `app/src/test/java/com/neverlate/domain/tasks/ColorRoleTest.kt` — test JVM de las 4+4 combinaciones.

**Modificados**
- [`PendingTasksWidget.kt`](../app/src/main/java/com/neverlate/ui/widget/PendingTasksWidget.kt) —
  `provideGlance` resuelve el repositorio vía `EntryPointAccessors.fromApplication` en vez de montarlo
  a mano.
- [`ui/widget/TaskSurfacesRefreshingRepository.kt`](../app/src/main/java/com/neverlate/ui/widget/TaskSurfacesRefreshingRepository.kt) —
  KDoc que deja constancia de por qué esta capa concreta **no** debe ser la del widget.
- [`ui/tasks/TasksScreen.kt`](../app/src/main/java/com/neverlate/ui/tasks/TasksScreen.kt),
  [`ui/tasks/PriorityUi.kt`](../app/src/main/java/com/neverlate/ui/tasks/PriorityUi.kt),
  [`ui/widget/WidgetColors.kt`](../app/src/main/java/com/neverlate/ui/widget/WidgetColors.kt) — los
  cuatro resolutores de color se adelgazan sobre `ColorRole`.

No hay dependencias nuevas en `gradle/libs.versions.toml`: `@EntryPoint`/`EntryPointAccessors` viajan
dentro de `hilt-android`, ya presente desde la 13d.

## Lo que te llevas

- `@EntryPoint` es la otra cara de `@AndroidEntryPoint`: sirve quando **tú** construyes la clase (no
  Hilt), y necesitas de todas formas un punto de entrada manual al grafo. `EntryPointAccessors`
  (`fromApplication`/`fromActivity`/`fromFragment`) es cómo se resuelve, eligiendo el ancla según qué
  tengas a mano en el punto de uso.
- Cuando una interfaz tiene varias implementaciones apiladas (un decorador), "inyéctame `X`" no es una
  frase completa — hay que decir **qué capa**, y esa elección puede tener consecuencias de
  comportamiento (aquí, evitar una reentrada) que no se ven leyendo solo la firma del tipo.
- Un ciclo de reentrada no siempre parece un bug hasta que se activa la condición que lo dispara — el
  cableado manual de antes lo evitaba sin querer; el grafo explícito obliga a decidirlo a propósito, y
  esa es una mejora, no un coste añadido.
- Duplicar un **valor** (un color) es distinto de duplicar una **decisión** (qué rol le toca a cada
  nivel). Lo primero se resuelve compartiendo una constante; lo segundo, casi siempre, se resuelve
  extrayendo una función pura y dejando que cada consumidor solo la *use* — nunca la repita.
- Un tipo puede existir solo para nombrar una decisión sin comprometerse a un mundo concreto: `ColorRole`
  no es un `Color` de Compose ni un `ColorProvider` de Glance — es vocabulario compartido que cada lado
  traduce por su cuenta.
