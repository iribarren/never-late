# Feature — Rediseño visual del widget de tareas pendientes

Lee `CLAUDE.md` (**Tutorial Track (optional)** + **Mandatory Workflow** + **Design in the Workflow**)
y las lecciones previas (en especial la 05: el widget con Glance y `RemoteViews`, que esta feature
reviste; la 16: identidad visual y la paleta de marca; la 17: color de urgencia con `urgencyLevelFor`;
la 19: barra de progreso con `deadlineProgressFor`; y la 13b: prioridad de tarea, cuyo indicador nunca
llegó al widget). Implementa **"que el widget se parezca a la app"** siguiendo el flujo `/feature`.

> **Depende de `tiempo-restante-compacto.md`: hazla después.** Ese trabajo deja `PendingTaskRow` con
> los milisegundos crudos en vez de un texto ya formateado, que es justo lo que aquí hace falta para
> colorear por urgencia y pintar progreso. Hacerlo antes significa pagar el mismo refactor dos veces.

## Qué construir

- El widget deja de ser un **rectángulo plano de color morado** y adopta la identidad de la app:
  **esquinas redondeadas**, paleta de marca y **variante clara/oscura** real (hoy los colores son
  cuatro hexadecimales fijos de modo claro).
- El tiempo restante de cada fila se **colorea por urgencia** con la misma escala calma / pronto /
  urgente / vencido que ya usa la lista, en vez del actual binario "rojo o no rojo".
- Cada fila muestra el **indicador de prioridad** de la tarea, cerrando el pendiente que la feature
  13b dejó anotado en `docs/mockups/README.md` ("priority on widget/notification/stats").
- Mejor lectura del tiempo restante en general: jerarquía tipográfica, separación entre filas y, si
  el spec lo aprueba, un **indicador de progreso** por fila equivalente al de la tarjeta de tarea.
- El widget deja de verse feo **en el selector del launcher**: hoy
  `res/xml/pending_tasks_widget_info.xml` no declara `previewImage` ni `previewLayout`.

## Tutorial

Antes de escribir el spec, el flujo `/feature` **debe preguntar con `AskUserQuestion`** si esta
feature lleva lección en español (*Sí, con lección / No / Decidir al final*), y la respuesta se anota
en el campo `Tutorial:` del spec.

**Recomendación: Sí.** El tema tiene sustancia propia y no lo cubre ninguna lección:

- **Por qué el tema de Compose no cruza a Glance:** `MaterialTheme.colorScheme` y
  `NeverLateExtras.colors` se leen de un `CompositionLocal` que la composición del widget nunca
  provee, y `colorForUrgency` / `PriorityUi.indicatorColor()` son `@Composable` de Material 3 que
  desde Glance directamente no se pueden llamar. Es un caso muy claro de "el mismo color, dos mundos".
- **`ColorProvider(day =, night =)`:** cómo un widget resuelve claro/oscuro sin `CompositionLocal`,
  y qué se gana o se pierde frente a añadir `glance-material3` + `GlanceTheme`.
- **Límites de API dentro de un widget:** `GlanceModifier.cornerRadius` es API 31+ y la app soporta
  desde API 24 — cómo se degrada con elegancia en vez de romper.
- **Previews de widget** y por qué `previewImage`/`previewLayout` son parte del producto, no un extra.

## Notas

- Rama sugerida: `feature/widget-visual-refresh`.
- **De dónde vienen los colores actuales:** los cuatro `private val` de
  [`PendingTasksWidget.kt`](../../app/src/main/java/com/neverlate/ui/widget/PendingTasksWidget.kt)
  (`0xFFEFE6FF`, `0xFF4A3B77`, `0xFF1B1B1B`, `0xFFB3261E`) son restos del **template morado de Android
  Studio**. No pertenecen a la paleta de marca (semilla `#3B5BDB`, feature 16) y no tienen variante
  oscura: en tema oscuro el widget sigue siendo lila pálido. El comentario del propio fichero admite
  que era deliberado ("no advanced theming" en el spec de la 05); esta feature revierte esa exclusión.
- **Extiende, no dupliques — con un matiz que el spec debe resolver antes de implementar:**
  - **Sí son reutilizables** desde Glance los `Color` crudos de
    [`Color.kt`](../../app/src/main/java/com/neverlate/ui/theme/Color.kt)
    (`primaryContainerLight`, `urgencyCalmLight`, `urgencyCalmDark`, `errorDark`…), envueltos en
    `ColorProvider(day =, night =)`.
  - **Sí son reutilizables** las funciones puras `urgencyLevelFor`
    ([`Urgency.kt`](../../app/src/main/java/com/neverlate/domain/tasks/Urgency.kt)) y
    `deadlineProgressFor`
    ([`DeadlineProgress.kt`](../../app/src/main/java/com/neverlate/domain/tasks/DeadlineProgress.kt)),
    llamables desde `provideGlance` sin ningún `Context`.
  - **No son reutilizables** `NeverLateExtras.colors`, `MaterialTheme.colorScheme`, `colorForUrgency`
    (en `TasksScreen.kt`) ni `PriorityUi.indicatorColor()`. El spec elige entre **(a)** un fichero
    pequeño de colores Glance que referencie los `*Light`/`*Dark` directamente, o **(b)** añadir
    `androidx.glance:glance-material3` al catálogo
    ([`libs.versions.toml`](../../gradle/libs.versions.toml)) y usar
    `GlanceTheme(colors = ColorProviders(light = LightColorScheme, dark = DarkColorScheme))`.
    La opción (b) da coherencia automática con el tema a cambio de una dependencia nueva; decidir y
    justificar, no elegir por inercia.
- **`PendingTaskRow` no lleva `priority` hoy** — hay que añadírsela en
  [`PendingTaskRows.kt`](../../app/src/main/java/com/neverlate/domain/tasks/PendingTaskRows.kt).
  Como ese tipo lo comparte la notificación de pantalla de bloqueo, el spec debe decir si la
  notificación también muestra prioridad o simplemente ignora el campo nuevo.
- **Diseño (obligatorio en el spec):** `docs/mockups/README.md` **no tiene ninguna fila para el
  widget** — el maquetado maestro es solo de la app. Esta feature debe **añadir una fila nueva** a la
  tabla (previsiblemente `—`, UI net-new fuera del maquetado) explicando qué entrega y qué queda
  pendiente, en lugar de mover una fila existente. Además debe **actualizar la nota de la fila de la
  feature 13b**, que hoy lista "priority on widget" como diferido. Criterios de aceptación visuales
  concretos: legibilidad en tema claro y oscuro, contraste suficiente del texto sobre el fondo nuevo,
  el color nunca como único portador de información (urgencia y prioridad deben tener también texto
  o forma), y reflow correcto al redimensionar el widget en el launcher.
- **Referencia visual:** abre `docs/mockups/rediseno-ux-ui.html` (tarjetas de tarea: color de
  urgencia, barra de progreso, punto de prioridad) como guía de dirección, **no** código a copiar —
  y ten presente que Glance **no** soporta todo lo que soporta Compose, así que se traduce la
  *intención*, no el layout.
- **Deuda que se menciona pero queda explícitamente fuera de alcance:** el widget construye su
  repositorio a mano (`NeverLateDatabase.getInstance` + `RoomTaskRepository` dentro de
  `provideGlance`) y **no pasa por Hilt** — la feature 13d no lo migró. Es un cambio de arquitectura,
  no de aspecto; que el spec lo declare fuera de alcance y no se cuele en esta rama.
- Sin backend, sin contrato, sin migración de Room. Posible dependencia nueva
  (`androidx.glance:glance-material3`) **solo** si el spec elige la opción (b), y siempre vía el
  catálogo de versiones, nunca hardcodeada en `build.gradle.kts`.
- Ficheros:
  [`PendingTasksWidget.kt`](../../app/src/main/java/com/neverlate/ui/widget/PendingTasksWidget.kt),
  [`PendingTasksWidgetState.kt`](../../app/src/main/java/com/neverlate/ui/widget/PendingTasksWidgetState.kt),
  [`PendingTaskRows.kt`](../../app/src/main/java/com/neverlate/domain/tasks/PendingTaskRows.kt),
  [`pending_tasks_widget_info.xml`](../../app/src/main/res/xml/pending_tasks_widget_info.xml),
  [`Color.kt`](../../app/src/main/java/com/neverlate/ui/theme/Color.kt) (solo lectura de tokens),
  [`libs.versions.toml`](../../gradle/libs.versions.toml) (si entra `glance-material3`).
- Agentes: `mobile-engineer` (theming Glance, colores día/noche, prioridad y urgencia, preview),
  `qa-engineer` (tests JVM del modelo del widget con prioridad y urgencia; verificación manual en
  launcher claro y oscuro, y redimensionando el widget, porque el render de Glance no se cubre con
  tests unitarios).
