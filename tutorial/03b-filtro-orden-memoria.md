# Lección 03b — Filtro y ordenación de la lista en memoria (fundamentos de Kotlin)

> Objetivo: **poner nombre** al Kotlin que la app ya usa sin explicar. Añadimos a la pantalla de
> Tareas un **buscador por texto**, una **ordenación** elegible (por plazo o por título, ascendente o
> descendente) y una **agrupación por urgencia**. Nada de esto toca Room ni la red: todo ocurre **en
> memoria**, sobre la lista que el `ViewModel` ya tiene cargada. Es una lección de **lenguaje**: la
> feature es la excusa para explicar por fin null-safety, `when` como expresión, colecciones con
> funciones de orden superior, funciones de alcance y funciones de extensión.

## Conceptos que aprendes aquí

Partiendo de la Lección 02 (`ViewModel` + `StateFlow`) y la 03 (`data class`, `sealed`, listas):

- **Null-safety y smart casts:** los operadores `?`, `?:` (Elvis), `?.let { }`, por qué `!!` es un
  olor a código, y cómo el compilador **recuerda** que ya comprobaste un nulo (smart cast).
- **`when` como expresión exhaustiva** sobre un `enum`/`sealed`: cuando cubres todos los casos, el
  `when` **devuelve** un valor y no necesita `else`; si añades un caso nuevo, deja de compilar hasta
  que lo trates. El error se caza en tiempo de compilación, no en producción.
- **Desestructuración** (`val (a, b) = …`): repartir un `Pair` o un `Map.Entry` en variables.
- **Colecciones + funciones de orden superior:** `filter`, `map`, `mapValues`, `filterValues`,
  `mapNotNull`, `groupBy`, `sortedWith`, y los constructores de `Comparator` (`compareBy`,
  `compareByDescending`, `nullsLast`, `reverseOrder`). Lambdas y **referencias a función** (`::foo`).
- **Funciones de alcance** (`let`, `run`, `apply`, `also`, `with`): qué hace cada una y cómo elegir.
- **Funciones de extensión:** añadir métodos legibles a `List<…>` sin heredar ni envolver.

Todo el núcleo vive en un fichero **puro** y testeable desde la JVM,
[`TaskListShaping.kt`](../app/src/main/java/com/neverlate/domain/tasks/TaskListShaping.kt), igual que
`ReminderPlanning.kt` (Lección 09) separa la *decisión* en Kotlin plano del *envoltorio* de
plataforma. Recuerda ese reparto: **lógica pura → test sin emulador**.

---

## 1. Modelar los controles con una `data class`

Lo primero es representar **qué ha elegido el usuario** en la barra de controles. Un solo valor
inmutable, no cuatro variables sueltas:

```kotlin
enum class TaskSortField { Deadline, Title }
enum class SortDirection { Ascending, Descending }

data class TaskListCriteria(
    val query: String = "",
    val sortField: TaskSortField = TaskSortField.Deadline,
    val direction: SortDirection = SortDirection.Ascending,
    val grouped: Boolean = false,
)
```

Un **`enum class`** es un tipo con un conjunto **cerrado y conocido** de valores. Eso será clave más
abajo: el compilador sabe que `TaskSortField` solo puede ser `Deadline` o `Title`, así que un `when`
sobre él puede ser exhaustivo.

Los **valores por defecto** de la `data class` definen "como si el usuario no hubiera tocado nada":
sin filtro, por plazo más próximo primero, sin agrupar — el equivalente en memoria a la lista de
antes de esta feature. Y como es una `data class`, cambiar una sola parte es un `.copy(...)`:

```kotlin
_criteria.value = _criteria.value.copy(query = query)   // solo cambia query; el resto se conserva
```

Ese `.copy()` sobre un estado inmutable es el mismo patrón que el resto del proyecto usa para el
estado de UI: nunca mutamos el objeto, creamos uno nuevo con una porción distinta.

---

## 2. Filtrar: `filter`, lambdas y `if` como expresión

```kotlin
fun List<TaskUiModel>.filteredBy(query: String): List<TaskUiModel> =
    if (query.isBlank()) this else filter { it.task.title.contains(query, ignoreCase = true) }
```

Aquí hay tres ideas nuevas juntas:

- **Función de extensión.** `fun List<TaskUiModel>.filteredBy(...)` añade un método a `List` **sin
  heredar de ella**. Dentro, `this` es la lista y podemos llamar a `filter` directamente. En la
  pantalla se lee como si `filteredBy` fuera parte de la biblioteca estándar:
  `tasks.filteredBy(query)`. Ese es el objetivo: código que se **lee** como lo que hace.

- **Función de orden superior + lambda.** `filter { ... }` recibe **otra función** como argumento (una
  *lambda*, el bloque entre llaves). `filter` la aplica a cada elemento y conserva aquellos para los
  que devuelve `true`. Dentro de la lambda, `it` es el elemento actual cuando no le ponemos nombre.

- **`if` como expresión.** En Kotlin `if/else` **devuelve un valor**, así que toda la función es una
  sola expresión (`= ...`). Si la búsqueda está en blanco devolvemos `this` (la misma lista, sin
  copiarla), y si no, la lista filtrada. `contains(query, ignoreCase = true)` es la subcadena
  ignore-case que pide la US-1.

---

## 3. Ordenar: `Comparator`, nulos y por qué evitar `!!`

Esta es la parte más rica de la lección, porque el plazo de una tarea es **nullable**: una tarea que
solo tiene duración no tiene `deadline` (`Long?`, con la `?`). ¿Cómo ordenas por un campo que puede
faltar?

```kotlin
fun List<TaskUiModel>.sortedBy(field: TaskSortField, direction: SortDirection): List<TaskUiModel> {
    val comparator: Comparator<TaskUiModel> = when (field) {
        TaskSortField.Deadline -> when (direction) {
            SortDirection.Ascending -> compareBy(nullsLast()) { it.task.deadline }
            SortDirection.Descending -> compareBy(nullsLast(reverseOrder())) { it.task.deadline }
        }
        TaskSortField.Title -> when (direction) {
            SortDirection.Ascending -> compareBy { it.task.title.lowercase() }
            SortDirection.Descending -> compareByDescending { it.task.title.lowercase() }
        }
    }
    return sortedWith(comparator)
}
```

### `when` como expresión exhaustiva

Fíjate en que `when (field) { ... }` **produce** el `Comparator` que asignamos a `val comparator`.
Como `TaskSortField` es un `enum` y cubrimos sus dos casos, el `when` es **exhaustivo** y **no lleva
`else`**. Esto no es cosmético: si mañana añades `TaskSortField.Priority`, este `when` **deja de
compilar** hasta que lo trates. El compilador te obliga a decidir qué pasa con el caso nuevo, en vez
de que se cuele silenciosamente un comportamiento por defecto. Lo mismo aplica al `when (direction)`
anidado. Un `enum` + `when` exhaustivo es una de las combinaciones más útiles del lenguaje.

### El nulo, sin `!!`

Un **`Comparator`** es un objeto que sabe comparar dos elementos. `compareBy { it.task.deadline }`
construye uno que ordena por ese campo. Pero `deadline` es `Long?`, y necesitamos que las tareas sin
plazo queden **al final**. La respuesta idiomática es `nullsLast()`: le dice al `Comparator` "los
nulos, los últimos", **sin desenvolver nunca** el valor.

La alternativa tentadora sería `it.task.deadline!!` con algún valor centinela inventado. El operador
**`!!`** significa "confía en mí, esto no es nulo" — y si te equivocas, revienta con
`NullPointerException` en tiempo de ejecución. Es exactamente el tipo de fallo que la null-safety de
Kotlin existe para hacer innecesario. **Regla práctica:** si escribes `!!`, casi siempre hay una
forma mejor (`?.`, `?:`, `nullsLast`, un smart cast…). Aquí, `nullsLast()` es esa forma mejor.

### Descendente sin voltear los nulos

Un detalle sutil (y un bug que el test cazó): para descender **no** basta con
`comparator.reversed()`. Eso invertiría *también* la comparación null-vs-no-null, mandando las tareas
sin plazo al **principio**, en contra de la US-2 ("los nulos, siempre al final, en cualquier
dirección"). La solución es `nullsLast(reverseOrder())`: invierte solo el orden **entre los plazos no
nulos**, dejando intacta la regla "el nulo va al final". Merece la pena leerlo dos veces: es el tipo
de matiz que distingue "compila" de "hace lo correcto".

Finalmente, `sortedWith(comparator)` devuelve una **nueva** lista ordenada (no muta la original) y es
una **ordenación estable**: los elementos con la misma clave conservan su orden relativo previo. El
test lo verifica explícitamente.

---

## 4. Agrupar: `groupBy`, referencias a función y reutilizar dominio

```kotlin
fun List<TaskUiModel>.groupedByUrgency(): Map<UrgencyLevel, List<TaskUiModel>> =
    groupBy { urgencyLevelFor(it.remainingMillis, it.isTimedOut) }
```

`groupBy { clave }` recorre la lista y construye un **`Map`** de clave → lista de elementos con esa
clave. La clave aquí la calcula `urgencyLevelFor`, la **misma** función pura que la Lección 17 usa
para colorear la cuenta atrás. No recalculamos la urgencia a mano: la reutilizamos ("extiende, no
dupliques"). Si un día cambia la regla de urgencia, cambia en un solo sitio.

`groupBy` devuelve las entradas en **orden de primera aparición**, no en el orden de declaración del
`enum`. Como queremos mostrarlas siempre "Overdue → Urgent → Soon → Calm", reordenamos después (ver
§5). Es un buen recordatorio de que el orden de declaración de un `enum` es un detalle interno del
código (aquí, para los umbrales de `urgencyLevelFor`), no automáticamente el orden en que una UI debe
pintarlo.

---

## 5. La tubería completa: `sealed`, `with`, desestructuración y `mapNotNull`

Filtrar → ordenar, o filtrar → agrupar → ordenar dentro de cada grupo. El resultado se modela con un
`sealed interface`:

```kotlin
sealed interface ShapedTaskList {
    data class Flat(val tasks: List<TaskUiModel>) : ShapedTaskList
    data class Grouped(val sections: Map<UrgencyLevel, List<TaskUiModel>>) : ShapedTaskList
}
```

Dos formas mutuamente excluyentes de "lista lista para pintar": plana, o partida en secciones. Al ser
`sealed` (Lección 03), cada sitio que la renderiza en `TasksScreen` obtiene su propio `when`
exhaustivo — el mismo beneficio del §3, ahora en la UI.

```kotlin
fun List<TaskUiModel>.shapedBy(criteria: TaskListCriteria): ShapedTaskList {
    val filtered = filteredBy(criteria.query)

    return with(criteria) {
        if (grouped) {
            val sections = filtered.groupedByUrgency()
                .mapValues { (_, tasksInSection) -> tasksInSection.sortedBy(sortField, direction) }
                .filterValues { it.isNotEmpty() }

            val ordered = URGENCY_DISPLAY_ORDER
                .mapNotNull { level -> sections[level]?.let { tasksInSection -> level to tasksInSection } }
                .toMap()
            ShapedTaskList.Grouped(ordered)
        } else {
            ShapedTaskList.Flat(filtered.sortedBy(sortField, direction))
        }
    }
}
```

### `with`, y cuándo usar cada función de alcance

`with(criteria) { ... }` ejecuta el bloque **con `criteria` como receptor**, para poder leer
`grouped`, `sortField` y `direction` sin repetir `criteria.` tres veces. Este es el momento de poner
nombre a las cinco **funciones de alcance**, que hasta ahora aparecían sin explicación:

| Función | Receptor dentro | Devuelve | Uso típico |
|---------|-----------------|----------|------------|
| `let`   | `it`            | lo del bloque | transformar un valor; con `?.let` actuar solo si no es nulo |
| `run`   | `this`          | lo del bloque | igual que `let` pero con `this` (varias llamadas al objeto) |
| `with`  | `this` (arg)    | lo del bloque | varias lecturas del **mismo** objeto (no es extensión) |
| `apply` | `this`          | **el objeto** | configurar un objeto y devolverlo (`builder.apply { ... }`) |
| `also`  | `it`            | **el objeto** | efecto secundario de paso (log, validación) sin cambiar el valor |

Elegimos `with` aquí porque el bloque hace **varias lecturas del mismo objeto** y no nos interesa
devolver `criteria`, sino el resultado del bloque. `let`/`run` encajarían sintácticamente, pero una
función de alcance elegida "porque toca usar una" no aporta nada: úsala cuando **mejora la lectura**,
no por reflejo.

### `?.let`, Elvis y desestructuración en acción

- **`sections[level]?.let { ... }`**: acceder a un `Map` con `[]` devuelve `V?` (nulo si la clave no
  está). El `?.let { }` ejecuta el bloque **solo si no es nulo**, con el valor como `it`. Es el patrón
  de null-safety más común de la app, ahora con nombre.

- **`mapNotNull { ... }`**: transforma y **descarta los nulos** de un golpe. Recorremos
  `URGENCY_DISPLAY_ORDER` (el orden fijo de secciones); para cada nivel que exista producimos un
  `level to tasksInSection`, y `mapNotNull` tira los niveles ausentes. `.toMap()` conserva ese orden
  de encuentro, así que el `Map` final ya sale ordenado "Overdue → Urgent → Soon → Calm".

- **Desestructuración** `{ (_, tasksInSection) -> ... }`: un `Map.Entry` (y un `Pair`) se puede
  **repartir** en sus dos componentes. Aquí la clave no nos interesa, así que la ignoramos con `_`, y
  solo ordenamos el valor. Es el mismo mecanismo (`component1()`/`component2()`) que permite
  `val (nombre, edad) = persona`.

- El operador **`?:`** (Elvis, no aparece en este bloque pero sí lo usarás): `a ?: b` devuelve `a` si
  no es nulo, y `b` si lo es. La forma corta de "un valor por defecto cuando algo falta".

Por último, `isEmpty()` sobre el `ShapedTaskList` distingue "filtras/agrupas hasta cero visibles"
(`NoResults`) de "no hay ninguna tarea" (`Empty`) — dos pantallas vacías por razones distintas, con
mensajes y acciones distintas (US-4).

---

## 6. Conectarlo al `ViewModel` sin un segundo origen de datos

La regla de oro de esta feature: **no hay una segunda fuente de datos**. La lista sigue viniendo de
`repository.observeTasks()`; los criterios son otro `StateFlow` que **combinamos** con ella:

```kotlin
private val _criteria = MutableStateFlow(TaskListCriteria())
val criteria: StateFlow<TaskListCriteria> = _criteria.asStateFlow()

// dentro de init { ... }
uiTasksFlow.combine(_criteria) { uiTasks, criteria -> uiTasks to criteria }
    .collect { (uiTasks, criteria) -> onTasksTick(uiTasks, criteria) }
```

`combine` re-emite cuando **cualquiera** de las dos fuentes cambia: un nuevo tick de la cuenta atrás,
o el usuario tocando un control. Así el filtro se aplica **al instante**, sin esperar al siguiente
segundo, y una cuenta atrás en marcha mantiene el criterio elegido en cada tick. Fíjate de nuevo en
la **desestructuración** `{ (uiTasks, criteria) -> ... }` sobre el `Pair` que emite `combine`.

`onTasksTick` decide el estado aplicando la tubería pura:

```kotlin
_uiState.value = if (uiTasks.isEmpty()) {
    TasksUiState.Empty
} else {
    val shaped = uiTasks.shapedBy(criteria)
    if (shaped.isEmpty()) TasksUiState.NoResults else TasksUiState.Content(shaped)
}
```

Toda la **decisión** vive en `shapedBy` (Kotlin puro, testeado); el `ViewModel` solo la orquesta.

---

## 7. La UI: `OutlinedTextField`, `FilterChip` y accesibilidad

Los controles reutilizan primitivas de Material 3 (no inventamos estilos): un `OutlinedTextField`
para buscar y `FilterChip`s para ordenar y agrupar, en un `FlowRow` que refluye cuando la fuente es
grande.

```kotlin
FilterChip(
    selected = criteria.sortField == TaskSortField.Deadline,
    onClick = { onSortFieldChange(TaskSortField.Deadline) },
    label = { Text(stringResource(R.string.tasks_sort_deadline)) },
    modifier = Modifier.minimumInteractiveComponentSize(),
)
```

Dos detalles de accesibilidad (Lección 18):

- `Modifier.minimumInteractiveComponentSize()` garantiza el objetivo táctil de **≥ 48dp** aunque el
  chip dibuje más pequeño.
- El botón de dirección usa el **mismo `when (direction)`** para elegir el icono (`ArrowUpward` /
  `ArrowDownward`) y el texto anunciado por el lector de pantalla, así que **nunca pueden
  contradecirse**. Es el `when` exhaustivo del §3, ahora al servicio de la accesibilidad.

Todos los textos nuevos están en `res/values/strings.xml` (español) y `res/values-en/strings.xml`
(inglés), como manda la Lección 08.

---

## 8. Tests: por qué la lógica pura se prueba sola

Como `TaskListShaping.kt` no toca Android, se cubre entero con **tests JVM** (sin emulador), igual que
`ReminderPlanning.kt`. `TaskListShapingTest.kt` verifica lo que un humano olvidaría a mano:

- Filtro vacío devuelve la lista intacta; subcadena ignore-case; sin resultados → lista vacía.
- Orden por plazo con **nulos al final en ambas direcciones**; por título A→Z / Z→A; **estabilidad**
  en empates.
- Agrupación correcta, en el orden de display, **sin** secciones vacías.
- La tubería `shapedBy` compone filtro → orden → grupo, y `isEmpty()` distingue vacío de sin
  resultados.

Precisamente escribir el test destapó el bug de `comparator.reversed()` del §3: la mejor prueba de
que separar la lógica pura **paga**.

---

## Resumen

- Le pusimos **nombre** al Kotlin que la app ya usaba: null-safety (`?`, `?:`, `?.let`, por qué no
  `!!`), `when` **exhaustivo** como expresión, desestructuración, colecciones con funciones de orden
  superior y `Comparator`, funciones de alcance y de extensión.
- Toda la transformación es **pura y en memoria** sobre la lista ya cargada — sin Room, sin red, sin
  segundo origen de datos: solo un `StateFlow` de criterios **combinado** con el existente.
- La versión reactiva y con `debounce` de un buscador de verdad se deja a la **Lección 04b**; aquí el
  foco era el lenguaje.

**Siguiente:** [Lección 04 — Tareas y cuenta atrás](04-tareas-contador.md) (Room, `Flow`, lógica pura
de tiempo).
