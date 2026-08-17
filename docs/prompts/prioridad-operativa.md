# Feature — Que la prioridad sirva para algo: ordenar, filtrar y llegar a todas las superficies

Lee `CLAUDE.md` (**Tutorial Track (optional)** + **Mandatory Workflow** + **Design in the Workflow**)
y las lecciones previas (en especial la 13b: la prioridad de tarea y su migración de Room; la 03b:
filtro y ordenación en memoria, cuyo `TaskListShaping` hay que extender; la 04c: la pantalla de
estadísticas; y la 06: la notificación de pantalla de bloqueo). Implementa **"que marcar una tarea
como alta prioridad cambie algo"** siguiendo el flujo `/feature`.

> **Hoy la prioridad es puramente decorativa.** Se elige en el formulario, se guarda en Room, se pinta
> como un punto en la tarjeta y como `!!!` en el widget… y ahí se acaba. No ordena, no filtra, no
> agrupa, no aparece en la notificación ni en las estadísticas. La 13b la dejó anotada como diferida
> en `docs/mockups/README.md` y la 05b cerró solo el tercio del widget. Marcar algo como urgente y que
> el producto no reaccione es una promesa incumplida en la cara del usuario.

## Qué construir

- **Ordenar por prioridad** desde los controles que ya existen en la lista de Tareas, junto a "fecha
  límite" y "título".
- **Filtrar por prioridad**: ver solo lo importante cuando la lista se ha vuelto larga.
- **Agrupar por prioridad**, como alternativa al agrupado por urgencia que ya existe.
- La prioridad **llega a la notificación de pantalla de bloqueo y a las estadísticas**, cerrando los
  dos tercios que la 13b dejó pendientes.
- El punto de color de la tarjeta deja de ser el único indicio: la prioridad debe poder leerse **sin
  percibir el color** también en la app, no solo en el widget.

## Tutorial

Antes de escribir el spec, el flujo `/feature` **debe preguntar con `AskUserQuestion`** si esta
feature lleva lección en español (*Sí, con lección / No / Decidir al final*), y la respuesta se anota
en el campo `Tutorial:` del spec.

**Recomendación: No.** El filtro, la ordenación y el agrupado en memoria ya son la lección **03b**
entera, y la prioridad como dato es la **13b**. Lo que esta feature aporta de nuevo no es un concepto
de Kotlin sino una **decisión de modelado** (ver la nota sobre `ShapedTaskList` más abajo), y eso se
documenta bien en el spec y en `docs/arquitectura.md` sin necesidad de lección. Si aun así se quisiera
una, lo único con sustancia sería *"cuando un `sealed interface` deja de servir: generalizar
`Grouped` a más de una dimensión sin romper los `when` exhaustivos que ya lo consumen"*.

## Notas

- Rama sugerida: `feature/priority-sorting`.
- **La decisión difícil está en el modelo, no en los chips — y el spec debe resolverla antes de tocar
  nada.** En
  [`TaskListShaping.kt`](../../app/src/main/java/com/neverlate/domain/tasks/TaskListShaping.kt):
  - `sortedBy` ya fija **"completadas al final"** como clave primaria del comparador. Añadir prioridad
    obliga a declarar la pila entera de precedencias: completada → prioridad → fecha/título, o el
    orden que se decida, pero **escrito**.
  - `groupedByUrgency` devuelve `Map<UrgencyLevel, …>` y fuerza las completadas a `Calm`. Un segundo
    eje de agrupación **cambia el tipo de `ShapedTaskList.Grouped`**, sobre el que `ShapedTaskListView`
    y `SectionHeader` ([`TasksScreen.kt`](../../app/src/main/java/com/neverlate/ui/tasks/TasksScreen.kt))
    hacen `when` exhaustivo. Ese es el coste real de la feature. El spec elige: generalizar el tipo,
    o dos variantes separadas de `Grouped` — y justifica.
  - **No existe ningún enum de filtro hoy**: `filteredBy(query)` solo busca por texto. "Filtrar por
    prioridad" es superficie de API nueva, no una extensión de algo que ya esté.
- **Extiende, no dupliques.** `TaskListCriteria` ya es el objeto "todo lo que configuran los
  controles": la prioridad entra ahí, no en un flujo paralelo del `ViewModel`. Los chips se añaden al
  `FlowRow` de `TaskListControls` reutilizando el patrón `FilterChip` existente (con
  `minimumInteractiveComponentSize()`). Las etiquetas salen de `Priority.labelRes()`
  ([`PriorityUi.kt`](../../app/src/main/java/com/neverlate/ui/tasks/PriorityUi.kt)), que ya existe en
  ambos idiomas, y el color de `Priority.indicatorColor()`.
- **Cuidado con el ordinal.** El KDoc de
  [`Priority.kt`](../../app/src/main/java/com/neverlate/data/tasks/Priority.kt) advierte
  explícitamente de que **nada debe depender del ordinal del enum**. Si el orden por prioridad se
  implementa comparando ordinales, esa advertencia deja de ser cierta: el spec debe o bien declarar un
  `rank` explícito, o bien actualizar ese KDoc conscientemente. Lo que no vale es romperlo en silencio.
- **La notificación tiene presupuesto de texto, no de espacio.** La 05b decidió (D2) **no** meter la
  prioridad ahí porque sus líneas `InboxStyle` ya las trunca el sistema y un tercer trozo empuja "qué
  tarea / cuánto queda" hacia los puntos suspensivos. La fila **ya lleva el dato**
  ([`PendingTaskRows.kt`](../../app/src/main/java/com/neverlate/domain/tasks/PendingTaskRows.kt)); lo
  que falta es una decisión de producto. El spec debe **rebatir o confirmar** esa decisión con
  argumentos, no darle la vuelta por inercia — y si la mete, que sea con el marcador compacto
  (`widget_priority_marker_*`, ya existente) y no con la palabra completa.
- **Estadísticas: decidir qué se mide, no añadir una tarjeta porque sí.**
  [`TaskStats.kt`](../../app/src/main/java/com/neverlate/domain/tasks/TaskStats.kt) hoy calcula tres
  números y no lee la prioridad. "Completadas de alta prioridad esta semana" es útil; "reparto de
  tareas por prioridad" probablemente no. El spec elige **una** métrica y explica por qué ayuda a
  alguien con TDA/TDAH.
- **Diseño (obligatorio en el spec):** hay que **actualizar la fila de la 13b** en
  `docs/mockups/README.md`, que hoy lista "priority on notification/stats" como diferido, y la fila
  del widget (05b) si cambia algo. Criterios visuales concretos: los chips nuevos no desbordan el
  `FlowRow` a escala de fuente máxima; el agrupado por prioridad tiene cabeceras localizadas; y —
  importante — **la prioridad en la app deja de ser solo un punto de color**, porque el punto por sí
  solo es un cue exclusivamente cromático (el widget ya lo resolvió con `!`/`!!`/`!!!`; unificar los
  dos idiomas es candidato explícito aquí).
- **Referencia visual:** abre `docs/mockups/rediseno-ux-ui.html` como guía de dirección, **no** código
  a copiar.
- **Fuera de alcance:** mezclar prioridad y urgencia en un color combinado (sigue diferido desde la
  13b), y cambiar cómo se elige la prioridad en el formulario de edición.
- Sin backend, sin contrato (la prioridad ya cruza el cable), sin migración de Room (la columna existe
  desde la v5), sin dependencia nueva.
- Ficheros:
  [`TaskListShaping.kt`](../../app/src/main/java/com/neverlate/domain/tasks/TaskListShaping.kt),
  [`TaskStats.kt`](../../app/src/main/java/com/neverlate/domain/tasks/TaskStats.kt),
  [`TasksScreen.kt`](../../app/src/main/java/com/neverlate/ui/tasks/TasksScreen.kt),
  [`TasksViewModel.kt`](../../app/src/main/java/com/neverlate/ui/tasks/TasksViewModel.kt),
  [`StatsScreen.kt`](../../app/src/main/java/com/neverlate/ui/stats/StatsScreen.kt),
  [`TasksNotificationHelper.kt`](../../app/src/main/java/com/neverlate/ui/notification/TasksNotificationHelper.kt),
  [`PriorityUi.kt`](../../app/src/main/java/com/neverlate/ui/tasks/PriorityUi.kt),
  [`strings.xml`](../../app/src/main/res/values/strings.xml) +
  [`values-en/strings.xml`](../../app/src/main/res/values-en/strings.xml).
- Agentes: `mobile-engineer` (el modelo de `ShapedTaskList` primero, luego chips y superficies),
  `qa-engineer` (tests JVM de la pila de precedencias del orden con casos donde prioridad y urgencia
  se contradicen — no fixtures donde coincidan por casualidad — y del filtro y el agrupado nuevos).
