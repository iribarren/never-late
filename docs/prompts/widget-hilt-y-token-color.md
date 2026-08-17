# Feature — El widget entra en Hilt y los colores de urgencia dejan de estar duplicados

Lee `CLAUDE.md` (**Tutorial Track (optional)** + **Mandatory Workflow** + **Design in the Workflow**)
y las lecciones previas (en especial la 13d: inyección de dependencias con Hilt, que migró la app pero
**no** el widget; la 05: el widget con Glance, que monta su repositorio a mano; y la 05b: por qué el
tema de Compose no cruza a Glance, que dejó dos mapeos de color gemelos con un aviso escrito).
Implementa **"cerrar las dos deudas que la 05b dejó anotadas en su propia spec"** siguiendo el flujo
`/feature`.

> **Van juntas porque tocan los mismos ficheros, no porque sean lo mismo.** Una es de cableado (quién
> construye el repositorio del widget) y otra de modelado (quién decide qué color significa "urgente").
> Si el spec concluye que conviene partirlas en dos features, es una decisión legítima — pero que la
> tome explícitamente.

> **Si vas a hacer `widget-adaptable-progreso.md` con acciones por fila, esta va antes.** Escribir
> desde el widget es justo lo que el cableado actual no soporta bien; ver la nota de reentrancia.

## Qué construir

- El widget **obtiene su repositorio por Hilt** en vez de instanciar la base de datos y
  `RoomTaskRepository` a mano dentro de `provideGlance`.
- Un **único token de color de urgencia**, compartido por la app y por el widget, de forma que
  cambiar qué color significa "urgente" sea un cambio en **un** sitio y no en dos que hay que acordarse
  de sincronizar.
- Cero cambios visibles: al terminar, la app y el widget se ven exactamente igual que antes. Es
  refactor, y el criterio de éxito es que no se note.

## Tutorial

Antes de escribir el spec, el flujo `/feature` **debe preguntar con `AskUserQuestion`** si esta
feature lleva lección en español (*Sí, con lección / No / Decidir al final*), y la respuesta se anota
en el campo `Tutorial:` del spec.

**Recomendación: Sí.** La 13d enseñó Hilt en el caso cómodo (Activities y ViewModels); esta enseña el
caso incómodo, que es donde de verdad se aprende:

- **`@EntryPoint` y `EntryPointAccessors`:** cómo se saca algo del grafo desde una clase que **no
  puedes anotar** — porque no la construyes tú. Un `GlanceAppWidget` no puede tener campos `@Inject`,
  y entender *por qué* enseña qué hace realmente `@AndroidEntryPoint`.
- **El grafo tiene forma, y esa forma importa:** la cadena de decoradores del repositorio significa que
  "inyectar el `TaskRepository`" no es una pregunta con una sola respuesta — inyectas una capa
  concreta, y elegir mal reentra en el propio widget.
- **Duplicar un valor frente a duplicar una decisión:** la 05b ya evitó duplicar colores; lo que quedó
  duplicado es el **mapeo** nivel→rol. Extraerlo obliga a inventar un tipo que no es un `Color` ni un
  `ColorProvider`, sino un **nombre de rol** — y ese salto de abstracción es la lección.

## Notas

- Rama sugerida: `feature/widget-hilt-color-token`.
- **La decisión difícil no es "cómo inyecto", es "qué inyecto".** La vinculación **sin cualificar** de
  `TaskRepository` en
  [`RepositoryModule.kt`](../../app/src/main/java/com/neverlate/di/RepositoryModule.kt) es
  `TaskSurfacesRefreshingRepository`, cuyo `refreshSurfaces()` llama a
  `PendingTasksWidget().updateAll(context)`. Leer desde ahí es inocuo; **escribir desde dentro del
  widget reentra en el widget**. El cableado manual de hoy evita ese bucle por accidente, no por
  diseño. El spec debe decidir qué capa recibe el widget (la cadena completa, `@ReminderRepo`, o una
  guarda de reentrancia) y **probar** que un refresco no dispara otro.
- **No hay precedente en el repo, así que esta feature lo establece.** Verificado: `androidx.hilt:hilt-work`
  **no está** en el catálogo, no hay ningún `@HiltWorker`, ningún `HiltWorkerFactory` y ningún
  `EntryPointAccessors` en todo el proyecto — los tres workers (`TaskSurfacesRefreshWorker`,
  `BootRescheduleWorker`, `SyncWorker`) son `CoroutineWorker` planos que **también** montan sus
  dependencias a mano. Dos caminos:
  - **`EntryPointAccessors.fromApplication(...)` dentro de `provideGlance`** — no necesita ningún
    artefacto nuevo (`@EntryPoint` viene dentro de `hilt-android`, ya presente) y es **la única forma
    que sirve para los tres sitios donde se construye el widget**
    (`PendingTasksWidgetReceiver`, `TaskSurfacesRefreshingRepository` y `TaskSurfacesRefreshWorker`,
    que hacen `PendingTasksWidget()` directamente). **Recomendado.**
  - **`@HiltWorker`** — exigiría añadir `androidx.hilt:hilt-work` al catálogo, que
    `NeverLateApplication` implemente `Configuration.Provider` y desactivar el inicializador por
    defecto de WorkManager en el manifest. Es más cambio, y **no resuelve el widget** (resuelve los
    workers). Si el spec lo quiere, que sea como feature aparte y dicho así.
- **El token de color debe ser un nombre de rol, no un color.** Los cuatro gemelos verificados son
  `colorForUrgency` y `Priority.indicatorColor()` en
  [`TasksScreen.kt`](../../app/src/main/java/com/neverlate/ui/tasks/TasksScreen.kt) /
  [`PriorityUi.kt`](../../app/src/main/java/com/neverlate/ui/tasks/PriorityUi.kt), frente a
  `urgencyColorProvider` y `Priority.glanceIndicatorColor()` en
  [`WidgetColors.kt`](../../app/src/main/java/com/neverlate/ui/widget/WidgetColors.kt), cuyo KDoc ya
  avisa de que hay que cambiarlos a la vez. La forma correcta es un enum de **rol**
  (`Calm`/`Soon`/`Error`) + una función pura `UrgencyLevel → rol`, con **dos resolutores finos**: uno
  que lee `MaterialTheme.colorScheme`/`NeverLateExtras` y otro que lee `GlanceTheme.colors` y los
  pares día/noche. Eso unifica el **mapeo** dejando la **resolución** en cada mundo, que es justo la
  distinción que `WidgetColors.kt` ya explica. Un token que devuelva un `Color` volvería a romper la
  frontera que la 05b levantó.
- **Extiende, no dupliques.** Los pares día/noche escritos a mano para `calm`, `soon` y
  `outlineVariant` **siguen haciendo falta**: no son un parche, son los roles que el puente de
  `glance-material3` no expone. El spec no debe intentar "arreglarlos".
- **El criterio de éxito es que no se note.** Al ser un refactor sin cambio visible, la sección
  **Visual & UX Design** debe decir explícitamente que **no hay cambio visual** y que la verificación
  consiste en comparar el widget y la lista **antes y después** en claro y oscuro y en los cuatro
  niveles de urgencia. No se añade fila a `docs/mockups/README.md`; si acaso se anota en la fila del
  widget (05b) que la deuda quedó cerrada.
- **Aprovecha para dejar la puerta abierta, sin cruzarla:** los tres workers y
  `TasksNotificationService`/`ReminderReceiver` repiten el mismo montaje manual. Migrarlos **está
  fuera de alcance**, pero el spec debe anotar que el `@EntryPoint` que se cree aquí es reutilizable
  por ellos, para que la próxima persona no invente un tercer mecanismo.
- Sin backend, sin contrato, sin migración de Room, sin permiso nuevo. Dependencia nueva **solo** si el
  spec eligiera `@HiltWorker` (no recomendado aquí), y siempre por el catálogo de versiones.
- Ficheros:
  [`PendingTasksWidget.kt`](../../app/src/main/java/com/neverlate/ui/widget/PendingTasksWidget.kt),
  [`PendingTasksWidgetReceiver.kt`](../../app/src/main/java/com/neverlate/ui/widget/PendingTasksWidgetReceiver.kt),
  [`WidgetColors.kt`](../../app/src/main/java/com/neverlate/ui/widget/WidgetColors.kt),
  [`TasksScreen.kt`](../../app/src/main/java/com/neverlate/ui/tasks/TasksScreen.kt),
  [`PriorityUi.kt`](../../app/src/main/java/com/neverlate/ui/tasks/PriorityUi.kt),
  [`RepositoryModule.kt`](../../app/src/main/java/com/neverlate/di/RepositoryModule.kt),
  un `di/WidgetEntryPoint.kt` nuevo, y el enum de token en `domain/tasks/` o `ui/theme/`.
- Agentes: `mobile-engineer` (entry point, capa inyectada y token compartido), `qa-engineer` (test JVM
  de la función pura `UrgencyLevel → token`, que es lo único de esto que se puede probar sin
  dispositivo; verificación manual comparativa antes/después del widget y de la lista, y comprobación
  explícita de que completar una tarea no dispara una cascada de refrescos).
