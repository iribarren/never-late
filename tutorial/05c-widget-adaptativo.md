# Lección 05c — Un widget que reacciona a su tamaño

La 05 enseñó a dibujar el widget con Glance y `RemoteViews`, la 05b a darle color y tema fuera de
Compose. Ninguna de las dos le dio al widget la capacidad de **reaccionar**: hasta esta feature,
`PendingTasksWidget` dibuja siempre el mismo layout, sin importar cuánto espacio le dé el lanzador,
y expone un único destino táctil — toques donde toques, abre la app.

Esta lección cierra las dos deudas que la 05b dejó escritas explícitamente en
`docs/mockups/README.md`: barra de progreso por fila y destinos táctiles por fila. Las dos dependen
de la misma pregunta previa — **¿cuánto sitio tengo?** — así que antes de tocar ninguna de las dos
hace falta que el widget sepa responderla.

## Conceptos que aprendes aquí

Partiendo de la 05 (Glance/`RemoteViews`) y la 05b (`GlanceTheme`, límites de API dentro de un
widget):

- **`SizeMode.Responsive` y `LocalSize`:** cómo un widget declara un conjunto de tamaños y compone
  distinto en cada uno — y por qué eso **no** es un `BoxWithConstraints` de Compose.
- **`actionRunCallback` y `ActionCallback`:** cómo un widget ejecuta trabajo real (una escritura en
  Room) desde el proceso del lanzador, no desde el proceso de la app.
- **Un límite de plataforma que decide el diseño:** el tinte del `LinearProgressIndicator` de
  Glance no llega por debajo de API 31, y la respuesta correcta no es esconder el problema.
- **Reentrancia, otra vez:** por qué una escritura hecha *desde dentro* del widget es un caso
  especial que la 13e ya había resuelto para lecturas, pero que una escritura pone a prueba de
  verdad.

---

## 1. Por qué `SizeMode.Responsive` no es un `BoxWithConstraints`

En Compose normal, un layout que se adapta a su espacio se escribe con `BoxWithConstraints`: el
sistema mide el espacio disponible **en tiempo de composición** y el cuerpo del composable decide
qué dibujar según esa medida, en el mismo proceso, con el mismo árbol de composición.

Un `GlanceAppWidget` no vive en ese mundo. Lo que Glance produce no es una jerarquía de vistas de
Android normal — es un `RemoteViews`, una estructura serializable que se envía al **proceso del
lanzador** (el `Launcher`/`Home` del sistema), que es quien de verdad la infla y la dibuja. Glance
no tiene forma de "medir en vivo" dentro de ese proceso ajeno, así que resuelve el problema al
revés: en lugar de medir y decidir, **declara de antemano** el conjunto de tamaños que quiere saber
distinguir, y genera un `RemoteViews` completo — ya renderizado — por cada uno:

```kotlin
// PendingTasksWidget.kt
private val SMALL_WIDGET = DpSize(250.dp, 110.dp)   // el mínimo actual del appwidget-provider
private val LARGE_WIDGET = DpSize(250.dp, 220.dp)   // ~4x4 celdas

override val sizeMode: SizeMode = SizeMode.Responsive(setOf(SMALL_WIDGET, LARGE_WIDGET))
```

`Responsive` no es el único `SizeMode` — Glance también ofrece `SizeMode.Single` (ignora el tamaño
real, siempre el mínimo declarado en el manifest) y `SizeMode.Exact` (recompone para *cada* tamaño
concreto que el lanzador pida, sin lista fija). `Responsive` es el término medio: tú eliges cuántos
"escalones" te interesa distinguir, y Glance calcula qué `RemoteViews` de la lista es más cercano al
espacio real que el lanzador tiene, sin recomponer nada en el momento del redimensionado.

Ese "más cercano" tiene una consecuencia directa: **cuántos buckets declares importa de verdad**.
Cada tamaño extra es un `RemoteViews` completo más que va dentro de la misma transacción de
actualización con el lanzador — y `RemoteViews` tiene un límite duro de tamaño de transacción. Esta
feature declara solo **dos**, porque solo hay una pregunta real que responder: ¿cabe una barra de
progreso y filas de 48dp, o no? Nótese también que los dos buckets comparten el mismo ancho
(`250.dp` en ambos) — el eje que de verdad distingue "pequeño" de "grande" aquí es la altura, y
declararlos con el mismo ancho lo deja dicho sin necesidad de un comentario.

Dentro de la composición, `LocalSize.current` es el `CompositionLocal` que resuelve, para la
recomposición en curso, a **cuál de los tamaños declarados** se está dibujando — no al tamaño real
en píxeles que el lanzador tiene, sino al valor exacto de la lista que se pasó a `Responsive`:

```kotlin
// PendingTasksWidgetContent
val isLargeBucket = LocalSize.current == LARGE_WIDGET
```

Comparar contra `LARGE_WIDGET` en vez de contra `SMALL_WIDGET` no es arbitrario: trata "grande" como
el bucket al que hay que **apuntarse explícitamente**. Si mañana se añadiera un tercer tamaño por
encima de `LARGE_WIDGET`, esa composición caería en la rama grande por defecto en vez de aterrizar,
silenciosamente, en el layout compacto — el fallo seguro es "demasiada información", no "falta
información".

## 2. La fila pequeña no cambia una línea

Con el tamaño resuelto, el resto es una decisión de qué dibujar en cada rama. El bucket **pequeño**
es, deliberadamente, el layout que ya existía antes de esta feature — mismo `clickable` sobre la
columna raíz que abre la app, sin barra, sin acción por fila. Que el bucket pequeño no cambie nada
es en sí una elección: por debajo de 110dp de alto no hay sitio para garantizar una fila táctil de
48dp sin apretar el resto, así que ahí se conserva el único destino de siempre en vez de ofrecer una
acción en un objetivo demasiado pequeño para ser seguro (ver §4).

## 3. `actionRunCallback` y `ActionCallback`: ejecutar código desde el lanzador

La barra de progreso es "solo" render — llama a la misma `deadlineProgressFor` que ya usa la
tarjeta de tarea (feature 19), sin lógica nueva. El completado por fila es distinto: por primera vez
el widget necesita **ejecutar** algo, no solo dibujar algo.

Un `clickable` normal de Glance (el que ya abría la app) envuelve un `actionStartActivity` — lanzar
una `Activity` es algo que el sistema operativo sabe hacer directamente desde el proceso del
lanzador. Pero "guarda esta tarea completada en Room" no es una acción que el sistema operativo
entienda; hace falta ejecutar código **de la app**, y eso implica cruzar de vuelta del proceso del
lanzador al proceso de la app. `actionRunCallback` es el puente:

```kotlin
// PendingTasksWidget.kt — dentro del bucket grande
rowModifier = rowModifier.clickable(
    actionRunCallback<CompleteTaskActionCallback>(actionParametersOf(taskIdKey to row.id)),
)
```

```kotlin
// CompleteTaskActionCallback.kt
val taskIdKey = ActionParameters.Key<Long>("com.neverlate.widget.TASK_ID")

class CompleteTaskActionCallback : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val taskId = parameters[taskIdKey] ?: return
        // ... completar la tarea ...
    }
}
```

Dos detalles de la firma de `onAction` no son casualidad:

- **Es `suspend`.** Glance lo llama desde un `Worker` propio (el mecanismo real detrás de
  `ActionCallbackBroadcastReceiver`, que llega ya empaquetado con `glance-appwidget` — nada nuevo
  que añadir al manifest), precisamente para que el trabajo que hace, aquí una escritura en Room a
  través de la cadena de repositorios, pueda ser una corrutina normal en lugar de tener que
  resolverse de forma síncrona en un `onReceive` con tiempo límite.
- **Recibe un `GlanceId`.** Un mismo `GlanceAppWidgetReceiver` puede tener **varias instancias**
  colocadas a la vez (el usuario puso el widget dos veces, en dos pantallas de inicio). El
  `GlanceId` identifica *cuál* de esas instancias generó el toque, y es lo que permite, más abajo,
  redibujar solo esa una en vez de todas.

`actionParametersOf(taskIdKey to row.id)` es cómo se le pasa un dato a la acción sin variables
compartidas: el par clave-valor viaja serializado con el `RemoteViews`, y `parameters[taskIdKey]`
lo recupera dentro de `onAction`, ya en el proceso de la app. Un `taskId` que ya no existe — la
tarea se borró entre que se dibujó la fila y se tocó — resuelve a un `Task?` nulo al leer, y el
callback simplemente no hace nada con él; no es un caso de error, es el mismo tipo de carrera que ya
existe en cualquier UI reactiva con datos que pueden cambiar entre el dibujo y el toque.

## 4. Diseñar alrededor de un límite de plataforma, no esconderlo

`androidx.glance.appwidget.LinearProgressIndicator` existe y acepta un `color` — pero ese color no
siempre llega. El *translator* de Glance (el código que convierte la composición en `RemoteViews`
de verdad) solo llama a `setProgressBarProgressTintList` cuando `Build.VERSION.SDK_INT >= 31`;
por debajo, la barra se pinta con el color de acento del propio sistema, no con el `color` que se le
pidió. El `minSdk` del proyecto es 24, así que en API 24–30 esto pasa de verdad, no en teoría.

Hay dos formas fáciles y equivocadas de reaccionar a esto:

- Esconder la barra con un `if (Build.VERSION.SDK_INT >= 31)`. Resuelve la incomodidad de "un color
  que no es el correcto" borrando la barra entera — es decir, borrando la mitad de la información
  que sí llega (la posición geométrica de la barra sigue siendo correcta en cualquier API).
- No decir nada y dejar que alguien lo descubra como un bug en producción.

La decisión de esta feature es la tercera opción: **la barra se dibuja siempre**, y en API 24–30 se
declara explícitamente que el color no es fiable ahí. Pero eso solo es aceptable si la información
que el color debía transmitir — el nivel de urgencia — sigue llegando por **otro canal** en esas
versiones. Y aquí es donde encaja algo que la 05b ya había construido sin saber que lo necesitaría:
el texto de la cuenta atrás no solo cambia de color según `urgencyColorProvider`, también cambia de
peso tipográfico —negrita en `Urgent`/`Overdue`—:

```kotlin
style = TextStyle(
    color = urgencyColorProvider(level),
    fontWeight = if (level == UrgencyLevel.Urgent || level == UrgencyLevel.Overdue) {
        FontWeight.Bold
    } else {
        FontWeight.Medium
    },
    fontSize = 14.sp,
)
```

En API 31+, ese peso es un refuerzo: el color ya dice todo, la negrita insiste. En API 24–30, donde
la barra no puede teñirse, ese mismo peso deja de ser un refuerzo y pasa a ser **el** portador
principal de la señal — sin él, una tarea `Urgent` y una `Calm` solo se distinguirían por el color
de la cuenta atrás (que sí llega en cualquier API, porque el texto no pasa por el mismo *translator*
con la misma restricción). El límite de plataforma no se rodeó con más código: se **diseñó
alrededor**, aprovechando un canal que ya existía por otra razón.

## 5. Reentrancia: por qué una escritura es un caso distinto de una lectura

La 13e ya resolvió cómo el widget lee del grafo de Hilt sin que Hilt lo construya (`@EntryPoint` +
`EntryPointAccessors`), y eligió a propósito la capa `@ReminderRepo` en vez de la capa sin
cualificar, `TaskSurfacesRefreshingRepository`, porque esa última es la que **refresca las
superficies** después de escribir:

```kotlin
// TaskSurfacesRefreshingRepository.kt (resumido)
private suspend fun refreshSurfaces() {
    PendingTasksWidget().updateAll(context)
    TasksNotificationService.refresh(context)
}
```

En la 13e esa decisión era preventiva — el widget solo leía, así que el ciclo
*escritura → refresco → `updateAll` → `provideGlance` → …* no podía dispararse todavía. Esta
feature es la primera vez que existe una escritura real *desde dentro* del widget, así que es la
primera vez que esa decisión preventiva se pone a prueba de verdad. `CompleteTaskActionCallback`
resuelve el mismo `WidgetEntryPoint` y llama al mismo `taskRepository()` — sin ampliar nada de la
13e, porque esa capa ya era exactamente la que este caso necesita:

```kotlin
val repository = EntryPointAccessors
    .fromApplication(context.applicationContext, WidgetEntryPoint::class.java)
    .taskRepository()   // @ReminderRepo — nunca la capa sin cualificar

completeTask(repository, taskId)

PendingTasksWidget().update(context, glanceId)   // no updateAll: solo la instancia que recibió el toque
TasksNotificationService.refresh(context)
```

Como `@ReminderRepo` no refresca nada por su cuenta, el refresco pasa a ser **responsabilidad
explícita de quien escribe** — el callback lo hace él mismo, una sola vez, y con `update(context,
glanceId)` en vez de `updateAll(context)`: redibuja únicamente la instancia que generó el toque
(identificada por el `GlanceId` de §3), no todas las instancias colocadas.

Detectar este riesgo antes de escribir el código, no después de que se manifieste en un dispositivo
real, es la parte que de verdad importa: la pregunta no es "¿qué repositorio inyecto?" sino "¿qué
**capa**, de una cadena de decoradores apilados, es segura para *este* consumidor concreto?" — y esa
pregunta hay que hacérsela cada vez que un nuevo consumidor entra a la cadena, no solo la primera
vez.

---

## Repaso: ficheros de la feature

**Nuevo**
- [`ui/widget/CompleteTaskActionCallback.kt`](../app/src/main/java/com/neverlate/ui/widget/CompleteTaskActionCallback.kt) —
  el `ActionCallback` y la función `completeTask` que de verdad hace la escritura.
- `app/src/test/java/com/neverlate/ui/widget/CompleteTaskActionCallbackTest.kt` — prueba, con un
  espía de refrescos hecho a mano, que completar desde el widget no dispara `refreshSurfaces()`.
- `app/src/test/java/com/neverlate/di/WidgetEntryPointTest.kt` — fija por escrito que la capa
  `@ReminderRepo` nunca es `TaskSurfacesRefreshingRepository`.

**Modificados**
- [`ui/widget/PendingTasksWidget.kt`](../app/src/main/java/com/neverlate/ui/widget/PendingTasksWidget.kt) —
  `SizeMode.Responsive`, `LocalSize.current`, barra de progreso y `clickable` por fila en el bucket
  grande.
- [`ui/widget/PendingTasksWidgetState.kt`](../app/src/main/java/com/neverlate/ui/widget/PendingTasksWidgetState.kt) —
  `rowsForBucket`, la función pura que recorta filas según el bucket.
- [`domain/tasks/PendingTaskRows.kt`](../app/src/main/java/com/neverlate/domain/tasks/PendingTaskRows.kt) —
  `PendingTaskRow` gana `id` y `totalMillis`, con el límite de campos ya anotado en su KDoc.

Sin dependencias nuevas: `LinearProgressIndicator`, `SizeMode.Responsive`, `LocalSize` y
`actionRunCallback` viajan todos dentro de `glance-appwidget`, ya presente desde la feature 05.

## Lo que te llevas

- `SizeMode.Responsive` no mide en vivo como un `BoxWithConstraints` — pre-genera un `RemoteViews`
  completo por cada tamaño declarado, y el lanzador elige el más cercano. Por eso el número de
  tamaños declarados no es un detalle cosmético: es peso real en cada actualización.
- `actionRunCallback`/`ActionCallback` es cómo un widget deja de ser un dibujo y empieza a ejecutar
  trabajo de verdad — la firma `suspend` y el `GlanceId` no son incidentales, reflejan que el
  trabajo ocurre en un proceso ajeno y que puede haber varias instancias del mismo widget a la vez.
- Un límite de plataforma no siempre se puede evitar, y no siempre hay que esconderlo. Si la
  información sigue llegando por otro canal ya existente, declarar el límite como comportamiento
  aceptado es mejor diseño que fingir que no existe.
- Una decisión de "qué capa inyecto" tomada por precaución (13e) no queda validada hasta que el
  caso que la motivó ocurre de verdad. Esta feature es ese caso, y la capa elegida entonces sigue
  siendo la correcta sin necesitar cambios — la señal de que se razonó bien la primera vez.
