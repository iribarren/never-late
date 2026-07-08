# Lección 17 — Micro-interacciones: estados vacíos, animaciones y urgencia visual

> Objetivo: darle **vida** a pantallas que ya funcionaban. Hasta ahora, cuando una lista estaba vacía
> mostrábamos una línea de texto centrada; cuando creabas una tarea, aparecía de golpe sin más; y la
> cuenta atrás decía los minutos, pero nada te avisaba "de un vistazo" de que un plazo estaba a punto
> de vencer. En esta lección añadimos cuatro **micro-interacciones** encima de código que ya era
> correcto: un **estado vacío/error reutilizable** (icono + mensaje + acción), **animaciones de lista**
> para que las tareas entren y salgan con transición, un **`Snackbar` de "tarea creada"** disparado
> con un evento de una sola vez, y una **señal de urgencia por color** en la cuenta atrás. Nada del
> estado, la navegación ni los datos cambia: todo se **enchufa en las estructuras que ya existían**
> (el `when(uiState)`, los `LazyColumn`, el `SnackbarHost`, el `CountdownTicker`). Es la lección de
> diseño de siempre — **extender, no duplicar** — pero esta vez sobre la capa de presentación.
>
> Es también la primera vez que el tutorial dedica una lección a las **animaciones de Compose** y la
> primera que mira los **side-effects** en profundidad: `LaunchedEffect` (que apareció por primera vez,
> sin lección propia, en la 13) y `derivedStateOf`.

## Conceptos que aprendes aquí

Partiendo de la lección 04 (la lista de tareas, el `LazyColumn`, la cuenta atrás con `CountdownTicker`
y `formatRemaining`) y la 13 (primer uso de `LaunchedEffect`):

- **Animaciones en Compose, tres niveles.**
  - `Modifier.animateItem()` en listas perezosas: animar la aparición, desaparición y recolocación de
    filas **gratis**, siempre que la lista tenga `key`s estables.
  - `animate*AsState` (p. ej. `animateColorAsState`): animar el cambio de **un valor** (un color, un
    tamaño) sin gestionar tú el reloj de la animación.
  - `AnimatedVisibility`: animar que un composable **aparezca o desaparezca**.
  - La idea clave: qué se anima "solo" y qué tienes que **orquestar** tú.
- **Side-effects a fondo.**
  - `LaunchedEffect` para **eventos de una sola vez** (mostrar un `Snackbar` exactamente una vez) y por
    qué el evento hay que **consumirlo** para que no se repita en una recomposición o al rotar.
  - `derivedStateOf` para **estado calculado**: derivar un valor barato (el nivel de urgencia) de un
    estado que cambia muy a menudo (los milisegundos restantes) sin recomponer de más.
- **Los estados vacíos/error como parte del diseño.** Por qué un buen estado vacío **guía** al usuario
  (icono + mensaje + acción) en vez de dejarlo mirando un hueco en blanco, y cómo se extrae un
  **composable reutilizable con parámetros** que dos pantallas distintas comparten.

---

## 1. El problema: pantallas correctas pero mudas

Antes de esta lección, tres cosas funcionaban pero no *comunicaban*:

1. **Los estados vacíos y de error** eran un `Text` centrado. En `TasksScreen` había un composable
   privado `EmptyTasks`; en `ArticlesScreen`, `EmptyArticles` y `ErrorArticles`. Tres cajas
   casi idénticas (un `Box` que centra una columna con un texto), copiadas y pegadas. Y el estado de
   error de artículos ni siquiera ofrecía reintentar: te decía "ha fallado" y ahí te quedabas.

2. **La lista saltaba.** Al crear, completar o borrar una tarea, el `LazyColumn` re-dibujaba las filas
   en su nueva posición **instantáneamente**. Funcionalmente correcto, pero el ojo pierde el hilo: una
   fila desaparece de golpe y las de debajo dan un brinco hacia arriba.

3. **La cuenta atrás no tenía "temperatura".** `formatRemaining` te decía `2h 15min` o `4min`, pero
   para saber si algo era urgente tenías que **leer** el número en cada tarjeta. El diseño de
   referencia (`docs/mockups/rediseno-ux-ui.html`) pedía que el propio color gritara "esto es urgente".

Y una cuarta, más sutil: **crear una tarea no daba feedback**. Guardabas, volvías a la lista, y la
tarea estaba ahí… si te fijabas. En `HomeScreen` había un `SnackbarHost` **cableado pero sin usar**
desde hacía varias lecciones, esperando exactamente este momento.

Vamos una por una.

---

## 2. Un composable reutilizable: `MessageState`

La primera regla de esta lección ya la conoces de las 13–16: **extraer lo que se repite**. Teníamos
tres composables privados que dibujaban lo mismo salvo el icono, el texto y (a veces) un botón. Eso es
la señal clásica de que hay que **parametrizar**: escribir el molde una vez y pasarle como argumentos
justo lo que varía.

`app/src/main/java/com/neverlate/ui/components/MessageState.kt`:

```kotlin
@Composable
fun MessageState(
    icon: ImageVector,
    message: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = 32.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null, // decorativo: el mensaje de debajo dice lo mismo
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(48.dp),
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 16.dp),
            )
            if (actionLabel != null && onAction != null) {
                Button(onClick = onAction, modifier = Modifier.padding(top = 16.dp)) {
                    Text(actionLabel)
                }
            }
        }
    }
}
```

Tres detalles didácticos:

- **`actionLabel` y `onAction` son opcionales y van en pareja.** El `Button` sólo se dibuja si **ambos**
  son `!= null`. Así, una pantalla que no tiene ninguna acción útil (la lista de artículos vacía: no
  hay un flujo de "crear artículo") simplemente **omite los dos** y obtiene icono + mensaje, sin un
  botón colgando ni tener que inventar un `onClick` que no hace nada. Fíjate en el orden de los
  parámetros: `modifier` va **antes** de los opcionales de acción, respetando la convención de Compose
  de que `modifier` sea el primer parámetro opcional.

- **`contentDescription = null` en el icono es intencionado.** El icono es **decorativo**: el `Text` de
  debajo ya dice lo mismo. Si le pusiéramos descripción, un lector de pantalla lo leería dos veces. La
  accesibilidad no es "poner texto a todo", es "no repetir".

- **Todo el color viene del tema**, no de hex sueltos: el `tint` del icono usa `onSurfaceVariant` (un
  gris apagado del `ColorScheme`), coherente con la lección 16.

Ahora las tres pantallas usan el **mismo** composable. En `TasksScreen`, la rama `Empty` del
`when(uiState)` que ya existía:

```kotlin
is TasksUiState.Empty -> MessageState(
    icon = Icons.AutoMirrored.Filled.Assignment,
    message = stringResource(R.string.tasks_empty),
    actionLabel = stringResource(R.string.tasks_empty_action),
    onAction = onAddTaskClick, // el mismo destino que el FAB
)
```

En `ArticlesScreen`, la rama de error ahora **sí** ofrece reintentar, reutilizando el `onRefresh` que
ya teníamos:

```kotlin
is ArticlesUiState.Error -> MessageState(
    icon = Icons.Filled.ErrorOutline,
    message = stringResource(R.string.articles_error),
    actionLabel = stringResource(R.string.articles_retry),
    onAction = onRefresh,
)
```

**La estructura del `when(uiState)` no cambió**: mismas ramas, mismos estados. Sólo cambiamos el
composable *hoja* que cada rama pinta. Los tres privados (`EmptyTasks`, `EmptyArticles`,
`ErrorArticles`) se borran. Y la rama `Loading` sigue sin pintar nada — evita ese parpadeo de "vacío"
de un frame mientras carga.

> **i18n (lección 08).** Todo el texto nuevo va en los dos ficheros: `res/values/strings.xml`
> (español, base) y `res/values-en/strings.xml` (inglés). Las claves nuevas son `tasks_empty_action`
> y `tasks_task_created_snackbar`; además reformulamos `tasks_empty` para quitarle el "pulsa +" que
> ahora sobra, porque el botón de acción ya lo dice.

---

## 3. Animaciones de lista: `Modifier.animateItem()`

Aquí está la primera animación, y es la que más rendimiento da por menos esfuerzo. En un `LazyColumn`,
si a la fila le pones `Modifier.animateItem()`, Compose **anima automáticamente**:

- la **aparición** de una fila nueva (fade-in),
- la **desaparición** de una fila que se va (fade-out),
- y la **recolocación** de las filas que se mueven porque otra entró o salió.

En `TasksScreen`, dentro del `items(...)` del `LazyColumn`:

```kotlin
items(uiState.tasks, key = { it.task.id }) { uiModel ->
    TaskRow(
        uiModel = uiModel,
        // ...
        modifier = Modifier.animateItem(),
    )
}
```

Y lo mismo en `ArticlesScreen` para las filas de artículos.

**El requisito imprescindible: `key`s estables.** Fíjate en el `key = { it.task.id }`. Sin claves,
Compose identifica cada fila por su **posición** en la lista, así que si borras la primera tarea, para
Compose "la fila 0 cambió de contenido" — no "una fila desapareció". Con `key = { it.task.id }`,
Compose sabe que la fila con id 42 **es** la misma antes y después, y que la que ya no está es la que
se fue. Eso es lo que le permite animar la salida en vez de un reemplazo brusco. **Esas claves ya
estaban** desde la lección 04; `animateItem()` simplemente las aprovecha.

Este es el sentido de "qué se anima gratis": la posición y la aparición/desaparición de un ítem de
lista con clave estable **es** gratis — una línea. Lo que **no** es gratis es animar algo *dentro* de
la fila (un tachado, un cambio de color): eso lo tienes que orquestar tú, y para eso están
`animate*AsState` y `AnimatedVisibility`, que veremos justo debajo con el color de urgencia. (El
tachado animado al completar una tarea lo dejamos **fuera de alcance** en esta versión, para mantener
la lección enfocada; queda anotado como mejora futura.)

---

## 4. El `Snackbar` de "tarea creada": un evento de una sola vez

Este es el corazón de la parte de **side-effects**, y el detalle más fácil de hacer mal.

Queremos: tras crear una tarea con éxito, mostrar un `Snackbar` "Tarea creada" **una vez**. El
`SnackbarHost` que reutilizamos es el patrón que ya estaba en `HomeScreen`. La decisión de producto
(aprobada en la spec) fue mostrarlo en la pantalla de **Tareas**, porque al guardar volvemos ahí — es
donde el usuario aterriza y donde la confirmación tiene sentido.

### 4.1 El error clásico que hay que evitar

Un `LaunchedEffect` corre su bloque cuando entra en la composición **y cada vez que cambia su `key`**.
La tentación es tener un `Boolean` en el estado ("¿se acaba de crear?") y hacer:

```kotlin
// ❌ MAL: se re-dispara al rotar la pantalla, porque el estado sigue diciendo `true`
LaunchedEffect(uiState.justCreated) {
    if (uiState.justCreated) snackbarHostState.showSnackbar("Tarea creada")
}
```

El problema: un `Boolean` de estado es **permanente** hasta que alguien lo cambie. Si el usuario rota
el móvil (una recomposición nueva) mientras sigue en `true`, el `Snackbar` **vuelve a salir**. Un
`Snackbar` es un **evento** ("acaba de pasar algo"), no un **estado** ("algo es verdad"). Y los
eventos hay que **consumirlos**: leerlos una vez y apagarlos.

### 4.2 Cómo lo resolvemos: pasar un resultado hacia atrás y consumirlo

En esta app, crear y guardar cruza una frontera de navegación: la pantalla de edición
(`TaskEditRoute`) guarda y hace `popBackStack()` de vuelta a Tareas. El sitio idiomático de Navigation
Compose para "devolver un resultado a la pantalla anterior" es el `SavedStateHandle` de su entrada en
la pila. En `AppNavHost`, en el destino de **crear** (la ruta `TASK_EDIT` sin `{taskId}`):

```kotlin
onSaved = {
    // Llegar aquí sólo puede significar "creada": esta ruta no tiene {taskId},
    // así que la única forma de que isSaved sea true es un create con éxito.
    navController.previousBackStackEntry
        ?.savedStateHandle
        ?.set(TASK_CREATED_RESULT_KEY, true)
    navController.popBackStack()
},
```

Nota lo elegante del diseño: **no hizo falta un `StateFlow` nuevo en el ViewModel**. Como
`TaskEditViewModel.deleteTask()` es un no-op cuando `taskId == null`, la ruta de creación *sólo* puede
llegar a `onSaved` por una **creación** con éxito — nunca por editar. Así que la señal se pone justo en
ese punto. Editar una tarea existente usa otra ruta (`TASK_EDIT/{taskId}`), que **no** toca esa clave,
y por eso editar nunca dispara el "creada".

Del lado de Tareas, `TasksRoute` recibe su propio `SavedStateHandle` y lo observa:

```kotlin
if (taskCreatedHandle != null) {
    LaunchedEffect(taskCreatedHandle) {
        taskCreatedHandle.getStateFlow(TASK_CREATED_RESULT_KEY, false).collect { created ->
            if (created) {
                // Consumir de inmediato: a partir de aquí el handle vuelve a leer `false`,
                // así que una recomposición posterior (p. ej. una rotación) no repite el Snackbar.
                taskCreatedHandle[TASK_CREATED_RESULT_KEY] = false
                snackbarHostState.showSnackbar(taskCreatedMessage)
            }
        }
    }
}
```

Las dos líneas que importan son, en este orden:

1. **`taskCreatedHandle[...] = false`** — consumimos el evento *antes* de mostrar nada. Ese reset es
   lo que garantiza que se dispare **exactamente una vez** y no reviva al rotar.
2. **`snackbarHostState.showSnackbar(...)`** — una `suspend fun`; por eso vive dentro de un
   `LaunchedEffect`, que nos da un `CoroutineScope` atado al ciclo de vida de la composición.

El `SnackbarHostState` se crea con `remember { SnackbarHostState() }` en `TasksRoute` y se pasa al
`Scaffold` de `TasksScreen` (`snackbarHost = { SnackbarHost(snackbarHostState) }`), para que el
`LaunchedEffect` de arriba y el `SnackbarHost` de abajo compartan **exactamente la misma instancia** —
el mismo reparto de responsabilidades que `HomeScreen` ya usaba, aplicado ahora sobre Tareas.

> **Idea transversal.** Estado vs. evento es una distinción que vas a repetir toda tu vida en UI. El
> tema (lección 16) es **estado**: es verdad de forma continua. "Se acaba de crear una tarea" es un
> **evento**: pasa en un instante y se consume. `LaunchedEffect` + consumir el valor es el patrón para
> eventos; `derivedStateOf` (siguiente sección) es el patrón para estado derivado.

---

## 5. Urgencia por color: `derivedStateOf` sobre una lógica pura

Última micro-interacción, y la que junta las dos ideas de la lección: **estado derivado** y
**animación de un valor**.

### 5.1 La decisión, en Kotlin puro y testeable

Igual que la lógica de recordatorios vive en `ReminderPlanning.kt` como Kotlin puro (lección 09), el
**qué nivel de urgencia aplica** es una función pura, sin dependencias de Android, en
`domain/tasks/Urgency.kt`:

```kotlin
enum class UrgencyLevel { Calm, Soon, Urgent, Overdue }

private const val URGENT_THRESHOLD_MILLIS = 5 * 60_000L   // 5 min
private const val SOON_THRESHOLD_MILLIS = 60 * 60_000L    // 60 min

fun urgencyLevelFor(remainingMillis: Long, isTimedOut: Boolean): UrgencyLevel = when {
    isTimedOut -> UrgencyLevel.Overdue
    remainingMillis <= URGENT_THRESHOLD_MILLIS -> UrgencyLevel.Urgent
    remainingMillis <= SOON_THRESHOLD_MILLIS -> UrgencyLevel.Soon
    else -> UrgencyLevel.Calm
}
```

- Es **función pura de dos valores**: los mismos `remainingMillis` e `isTimedOut` que la cuenta atrás
  ya lee. No lee el reloj por su cuenta, así que un test le pasa combinaciones directamente, sin falso
  reloj (compáralo con lo que costaba testear código que llama a `System.currentTimeMillis()`).
- Los umbrales (`5 min` / `60 min`, umbrales `<=`, inclusivos) son constantes con nombre: fáciles de
  tunear y de testear en los **bordes** exactos (justo en 5:00, justo un ms después…).
- **No** recalcula el tiempo restante — lo **deriva** de lo que el `CountdownTicker` ya calcula. Es,
  otra vez, "extender, no duplicar".

### 5.2 `derivedStateOf`: derivar lo barato de lo ruidoso

La cuenta atrás cambia **cada segundo**. Pero el *nivel* de urgencia sólo cambia cuando cruzas un
umbral: de `Calm` a `Soon` una vez, de `Soon` a `Urgent` una vez. Si en cada tick recalculásemos el
color y eso forzara recomposiciones río abajo, estaríamos recomponiendo 60 veces por minuto para un
valor que casi nunca cambia.

`derivedStateOf` es exactamente para esto: envuelve un cálculo y sólo **notifica a quien lo lee**
cuando el **resultado** cambia, no cuando cambian las entradas. En `TaskRow`:

```kotlin
val urgencyLevel by remember(uiModel.remainingMillis, uiModel.isTimedOut) {
    derivedStateOf { urgencyLevelFor(uiModel.remainingMillis, uiModel.isTimedOut) }
}
```

Así, aunque `remainingMillis` baje `4:03 → 4:02 → 4:01…`, mientras el resultado siga siendo `Urgent`
nadie que lea `urgencyLevel` se recompone. Sólo el instante en que el nivel *de verdad* cambia
propaga una recomposición. Es la misma idea de "derivar lo barato de lo ruidoso" que hace el framework
por debajo; aquí la hacemos nosotros.

### 5.3 Del nivel al color (y por qué no es hex suelto)

Mapear el nivel a un `Color` es una función aparte, y los colores viven en el **tema**, extendiendo la
paleta de marca de la lección 16:

```kotlin
@Composable
private fun colorForUrgency(level: UrgencyLevel): Color = when (level) {
    UrgencyLevel.Calm -> NeverLateExtras.colors.calm
    UrgencyLevel.Soon -> NeverLateExtras.colors.soon
    UrgencyLevel.Urgent, UrgencyLevel.Overdue -> MaterialTheme.colorScheme.error
}
```

- **`Urgent` y `Overdue` reutilizan el rol `error`** del `ColorScheme`. Visualmente "urgente" y "error"
  son la misma señal — "mira esto ya" — así que no inventamos un rojo nuevo: usamos el que el tema ya
  define para claro y oscuro.
- **`Calm` y `Soon` (verde y ámbar) no tienen un rol equivalente en Material 3.** Por eso la lección
  añade un pequeño juego de colores extra, `NeverLateExtendedColors(val calm, val soon)`, expuesto vía
  un `CompositionLocal` (`NeverLateExtras.colors.calm`) — el patrón estándar para "colores semánticos
  que M3 no cubre" — con tonos propios para claro y oscuro en `Color.kt` (`urgencyCalmLight`,
  `urgencyCalmDark`, etc.), de modo que siguen siendo legibles en ambos temas.
- **El color nunca es la única señal del estado vencido**: encima de la cuenta atrás sigue el texto
  "Tiempo agotado" / "Time's up". Quien no distinga bien el rojo lo lee igual. La accesibilidad manda
  que el color sea *refuerzo*, no *único* portador de información.

> **¿Y dónde encaja `animate*AsState`?** Si en lugar de un cambio de color instantáneo quisiéramos que
> el rojo **apareciera con transición** al cruzar el umbral, envolveríamos el color en
> `val color by animateColorAsState(colorForUrgency(urgencyLevel))`: Compose interpola del color viejo
> al nuevo por ti. En esta versión el cambio es directo, pero es útil que veas dónde entraría esa pieza:
> `animateItem()` anima la lista, `animateColorAsState` animaría un valor dentro de la fila.

---

## 6. Qué NO cambió (y por qué importa)

Esta lección es un buen ejemplo de cuánto se puede mejorar **sin tocar la arquitectura**:

- **Sin cambios de estado.** `TasksUiState`, `ArticlesUiState`, `TaskUiModel` y los `StateFlow` de los
  ViewModels siguen igual. La urgencia se **deriva** en la capa de UI; no es un campo nuevo del estado.
- **Sin cambios de navegación.** El grafo es el mismo; el "resultado hacia atrás" viaja por el
  `SavedStateHandle` que Navigation Compose ya ofrece.
- **Sin backend, sin contrato de API, sin versión de BD, sin permisos, sin dependencias nuevas.** Todas
  las APIs de animación (`animateItem`, `AnimatedVisibility`, `animate*AsState`) ya venían en el
  Compose BOM que teníamos.

Mapea, además, a dos conceptos que `docs/conceptos-pendientes.md` marcaba pendientes en su §4:
*side-effects* (snackbar con `LaunchedEffect`) y *animaciones* (aparición/desaparición de filas).

---

## 7. Tests

- **`urgencyLevelFor` (JVM, `app/src/test`).** Al ser pura, se testea en los **bordes**: `Overdue`
  cuando `isTimedOut` (incluso con `remainingMillis` 0 o negativo, e incluso si `remainingMillis` fuera
  enorme — `isTimedOut` manda); `Urgent` justo en 5:00 (inclusivo) y `Soon` un ms después; `Soon` justo
  en 60:00 y `Calm` un ms después. Un caso interesante: `remainingMillis == 0` con `isTimedOut == false`
  es `Urgent`, **no** `Overdue` — porque `isTimedOut` es la única señal de "vencido".
- **`MessageState` (Compose UI, `app/src/androidTest`).** Que pinta el mensaje; que muestra el botón y
  llama a `onAction` cuando se le pasan `actionLabel` + `onAction`; y que **no** hay botón cuando se
  omiten.
- **El `Snackbar` (Compose UI).** Que aparece una vez cuando la señal del `SavedStateHandle` se pone a
  `true`, que la señal se **consume** (vuelve a `false`), que **no** se repite al remontar con el mismo
  handle (simulando una rotación) y que **sí** puede volver a salir en una segunda creación de verdad.

Los de JVM corren con `./gradlew :app:testDebugUnitTest`; los instrumentados necesitan un emulador o
dispositivo (`./gradlew :app:connectedDebugAndroidTest`).

---

## Resumen

Cuatro micro-interacciones, cero cambios de arquitectura:

1. **`MessageState`** — un composable reutilizable y parametrizado (icono + mensaje + acción opcional)
   que sustituye tres cajas casi idénticas, enchufado en el `when(uiState)` que ya existía.
2. **`Modifier.animateItem()`** — animación de aparición/desaparición/recolocación de filas **gratis**,
   apoyándose en las `key`s estables de la lección 04.
3. **Snackbar de "tarea creada"** — un **evento de una sola vez** vía `SavedStateHandle` +
   `LaunchedEffect`, **consumido** al leerlo para que no se repita al rotar; reutiliza el patrón de
   `SnackbarHost` que ya estaba cableado.
4. **Urgencia por color** — un `UrgencyLevel` puro y testeable, **derivado** de la cuenta atrás con
   `derivedStateOf` para no recomponer de más, mapeado a colores semánticos del tema (reusando `error`,
   extendiendo la paleta con `calm`/`soon`), con el texto "Tiempo agotado" como refuerzo accesible.

La distinción que te llevas: **estado** (continuo — el tema, el nivel de urgencia → `derivedStateOf`)
frente a **evento** (instantáneo — "se creó una tarea" → `LaunchedEffect` + consumir). Y la regla de
animación: lo que anima la lista es una línea; lo que anima *dentro* de una fila lo orquestas tú.
