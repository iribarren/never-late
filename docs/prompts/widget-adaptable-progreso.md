# Feature — Widget adaptable: barra de progreso por fila y acciones sin abrir la app

Lee `CLAUDE.md` (**Tutorial Track (optional)** + **Mandatory Workflow** + **Design in the Workflow**)
y las lecciones previas (en especial la 05: el widget con Glance y `RemoteViews`; la 05b: por qué el
tema de Compose no cruza a Glance y de dónde salen hoy sus colores; la 19: la barra de progreso de la
tarjeta con `deadlineProgressFor`; y la 04c: completar una tarea con `toggleComplete`). Implementa
**"un widget que aprovecha el tamaño que le den y deja completar una tarea sin abrir la app"**
siguiendo el flujo `/feature`.

## Qué construir

- El widget **responde al tamaño que le da el launcher** en vez de dibujar siempre lo mismo:
  `SizeMode.Responsive` con un par de tamaños declarados y `LocalSize` para elegir qué cabe. Pequeño:
  las filas de hoy. Grande: filas con barra de progreso y más de ellas.
- Cada fila con duración estimada muestra una **barra de tiempo consumido**, equivalente a la de la
  tarjeta de tarea, alimentada por la **misma** función pura `deadlineProgressFor`.
- Cada fila se puede **tocar por separado**: completar la tarea desde el widget, sin abrir la app.
  Hoy el widget entero es un único destino que abre la lista.
- El widget sigue siendo legible en su tamaño mínimo: la barra aparece **solo** donde hay sitio, no se
  recorta ni empuja la cuenta atrás fuera de la fila.

## Tutorial

Antes de escribir el spec, el flujo `/feature` **debe preguntar con `AskUserQuestion`** si esta
feature lleva lección en español (*Sí, con lección / No / Decidir al final*), y la respuesta se anota
en el campo `Tutorial:` del spec.

**Recomendación: Sí.** La 05 enseñó a dibujar un widget y la 05b a darle color; esta enseña a que
**reaccione**, y ninguna de las dos lo cubre:

- **`SizeMode.Responsive` y `LocalSize`:** cómo un widget declara un conjunto de tamaños y compone
  distinto en cada uno — y por qué eso no es lo mismo que un `BoxWithConstraints` de Compose, porque
  Glance genera **un `RemoteViews` por bucket** por adelantado, no uno que se mide al vuelo.
- **`actionRunCallback` y `ActionCallback`:** cómo un widget ejecuta trabajo real (una escritura en
  Room) desde el proceso del launcher, y por qué la firma es `suspend` y recibe un `GlanceId`.
- **Un límite de plataforma que decide el diseño:** el `LinearProgressIndicator` de Glance **no se
  puede tintar por debajo de API 31**. Es el caso perfecto para enseñar que a veces la restricción no
  se rodea, se *diseña alrededor*.
- **Reentrancia:** por qué escribir a través de la cadena de repositorios desde dentro del propio
  widget puede volver a llamarlo, y cómo se detecta antes de escribir el código.

## Notas

- Rama sugerida: `feature/widget-adaptive-layout`.
- **Lo que Glance 1.1.1 sí tiene** (verificado en el AAR, no supuesto):
  `androidx.glance.appwidget.LinearProgressIndicator(progress, modifier, color, backgroundColor)`,
  `androidx.glance.appwidget.SizeMode.Responsive(sizes: Set<DpSize>)`, `androidx.glance.LocalSize` y
  `actionRunCallback<T : ActionCallback>(parameters)`. **No hace falta ninguna dependencia nueva.**
- **El límite duro que el spec debe resolver, no descubrir a medias.** El traductor de Glance ramifica
  en `Build.VERSION.SDK_INT >= 31` antes de llamar a `setProgressBarProgressTintList`, así que en
  **API 24–30 la barra se pinta con el acento del sistema**, no con el color de urgencia. El `minSdk`
  es 24. Consecuencia de diseño: el canal de **peso tipográfico** que la 05b añadió a la cuenta atrás
  (negrita en Urgent/Overdue) pasa de refuerzo a **portador principal** en esos dispositivos, y los
  criterios de aceptación visuales deben decirlo. No caben aquí ni un `if (SDK_INT)` que oculte la
  barra en API antiguas sin justificarlo, ni fingir que el color llega.
- **Extiende, no dupliques.** La fracción de progreso sale de `deadlineProgressFor(remainingMillis,
  totalMillis, isTimedOut)`
  ([`DeadlineProgress.kt`](../../app/src/main/java/com/neverlate/domain/tasks/DeadlineProgress.kt)),
  **la misma** que llama la tarjeta de tarea — no una segunda regla "parecida". El color de la barra
  sale de `urgencyColorProvider`
  ([`WidgetColors.kt`](../../app/src/main/java/com/neverlate/ui/widget/WidgetColors.kt)) y el texto de
  `formatRemainingLabel`
  ([`RemainingTimeLabel.kt`](../../app/src/main/java/com/neverlate/ui/components/RemainingTimeLabel.kt)).
- **`PendingTaskRow` necesita un campo más y el spec debe decirlo en voz alta.**
  `deadlineProgressFor` pide el `totalMillis` (`Task.estimatedDurationMillis`), que la fila **no
  lleva**. Se añade con valor por defecto, igual que hizo la 05b con `priority` — y, como ese tipo lo
  comparte la notificación de pantalla de bloqueo
  ([`PendingTaskRows.kt`](../../app/src/main/java/com/neverlate/domain/tasks/PendingTaskRows.kt)), el
  spec debe declarar explícitamente que la notificación lo ignora. Es el segundo campo que se añade
  para un solo consumidor: si el spec cree que ya son demasiados, esa es una conversación legítima
  que tener **en el spec**, no un silencio.
- **Reentrancia — el riesgo real de las acciones por fila.** La vinculación **sin cualificar** de
  `TaskRepository` ([`RepositoryModule.kt`](../../app/src/main/java/com/neverlate/di/RepositoryModule.kt))
  es `TaskSurfacesRefreshingRepository`, cuyo `refreshSurfaces()` llama a
  `PendingTasksWidget().updateAll(context)`. Una escritura hecha desde un `ActionCallback` **vuelve a
  entrar en el widget**. El spec debe decidir por dónde escribe (la capa `@ReminderRepo`, la cadena
  completa con guarda, o esperar a `widget-hilt-y-token-color.md`) y **probar** que completar desde el
  widget no genera un bucle de refrescos.
- **Diseño (obligatorio en el spec):** `docs/mockups/README.md` ya tiene una fila de widget (05b) con
  la barra de progreso listada como **diferida a esta feature** — hay que **actualizar esa fila**, no
  crear otra. El maquetado maestro es solo de móvil: la barra se traduce en *intención* desde la
  tarjeta de tarea, no se copia. Criterios visuales concretos: la barra aparece solo en el bucket
  grande y solo si hay duración estimada; nada se recorta al redimensionar entre buckets; la fila
  sigue mostrando marcador de prioridad + título + cuenta atrás en una línea; contraste suficiente en
  claro y oscuro; **objetivos de toque por fila ≥ 48dp** (hoy solo hay uno, el widget entero, así que
  este criterio es nuevo y es fácil incumplirlo con filas apretadas).
- **Referencia visual:** abre `docs/mockups/rediseno-ux-ui.html` (tarjeta de tarea: barra de progreso
  y color de urgencia) como guía de dirección, **no** código a copiar.
- **Fuera de alcance, dicho para que no se cuele:** cambiar la cadencia de refresco
  (`TaskSurfacesRefreshWorker` sigue en ~15 min, con su desfase conocido), una actividad de
  configuración del widget, widgets adicionales, y migrar el widget a Hilt (eso es
  `widget-hilt-y-token-color.md`).
- Sin backend, sin contrato, sin migración de Room, sin dependencia nueva, sin permiso nuevo (el
  `ActionCallbackBroadcastReceiver` viene dentro de `glance-appwidget` y se fusiona desde su manifest).
- Ficheros:
  [`PendingTasksWidget.kt`](../../app/src/main/java/com/neverlate/ui/widget/PendingTasksWidget.kt),
  [`PendingTasksWidgetState.kt`](../../app/src/main/java/com/neverlate/ui/widget/PendingTasksWidgetState.kt),
  [`PendingTaskRows.kt`](../../app/src/main/java/com/neverlate/domain/tasks/PendingTaskRows.kt),
  [`DeadlineProgress.kt`](../../app/src/main/java/com/neverlate/domain/tasks/DeadlineProgress.kt)
  (solo lectura),
  [`pending_tasks_widget_info.xml`](../../app/src/main/res/xml/pending_tasks_widget_info.xml),
  un `ui/widget/*ActionCallback.kt` nuevo.
- Agentes: `mobile-engineer` (buckets de tamaño, barra, callback de acción y su ruta de escritura),
  `qa-engineer` (tests JVM del modelo con el campo nuevo y de la fracción de progreso por fila;
  verificación manual redimensionando el widget en el launcher entre buckets, en claro y oscuro, y en
  un emulador API 24 para comprobar qué hace la barra sin tinte).
