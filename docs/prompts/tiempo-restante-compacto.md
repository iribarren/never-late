# Feature — Tiempo restante en formato compacto y localizable ("2h 38m")

Lee `CLAUDE.md` (**Tutorial Track (optional)** + **Mandatory Workflow** + **Design in the Workflow**)
y las lecciones previas (en especial la 04: cuenta atrás y `CountdownTicker`; la 05 y la 06: widget y
notificación de pantalla de bloqueo, que comparten `pendingRowsFor`; la 08: i18n y por qué el texto
de cara al usuario nace en `strings.xml`; y la 19: barra de progreso, que consume el mismo
`remainingMillis`). Implementa **"mostrar el tiempo restante como `2h 38m` en toda la app, con las
unidades localizables"** siguiendo el flujo `/feature`.

> **Esta feature es un refactor de capas disfrazado de cambio de formato.** Hoy `formatRemaining`
> devuelve `hh:mm:ss` **desde la capa de dominio**, con el separador `:` cableado en Kotlin. En cuanto
> las unidades tienen que ser letras traducibles, el texto ya no puede nacer ahí: necesita `Context`
> y recursos. Ese movimiento es el corazón del trabajo, y es también lo que después habilita el
> rediseño del widget.

## Qué construir

- El tiempo restante se muestra como **`2h 38m`**, sin segundos, en **las tres superficies** que hoy
  pintan `hh:mm:ss`: la tarjeta de tarea, el widget de pantalla de inicio y la notificación de
  pantalla de bloqueo.
- Las **letras de unidad salen de `strings.xml`**, no de código: hoy "h" y "m" valen para español e
  inglés, pero un idioma futuro necesitará otras letras y otro orden. Se sigue el patrón que la app
  ya usa para la duración estimada — números formateados con `NumberFormat` según el `Locale` y
  unidades + orden de palabras controlados por el traductor.
- **`pendingRowsFor` deja de formatear texto.** `PendingTaskRow` pasa a llevar el
  `remainingMillis: Long` crudo, y cada superficie construye su propio texto con su `Context`. La
  regla compartida (qué cuenta como pendiente, en qué orden, con qué tope) se queda donde está.
- Se corrige de paso una **incoherencia real**: cuando el tiempo se agota, la lista y la notificación
  muestran "Tiempo agotado" (`tasks_time_up`) pero el widget sigue pintando el contador a cero.

## Tutorial

Antes de escribir el spec, el flujo `/feature` **debe preguntar con `AskUserQuestion`** si esta
feature lleva lección en español (*Sí, con lección / No / Decidir al final*), y la respuesta se anota
en el campo `Tutorial:` del spec.

**Recomendación: Sí.** Hay material real y no cubierto por ninguna lección previa:

- **Dónde puede nacer el texto de cara al usuario.** Por qué una función "pura" que devuelve un
  `String` ya formateado es una fuga de la capa de presentación hacia el dominio, y cómo se detecta
  (el día que hay que traducirlo, no compila la solución fácil).
- **Formatear números y unidades según el `Locale`:** `NumberFormat` para las cifras y recursos con
  placeholders (`%1$s`) para las unidades y el orden de palabras, frente a la concatenación en Kotlin.
- **`<string>` con placeholders vs `<plurals>`:** por qué las unidades abreviadas ("2h") no necesitan
  plural pero "2 horas" sí, y cómo se decide.
- **Refactorizar un tipo compartido por dos superficies** (`PendingTaskRow`) sin romper ninguna, con
  los tests como red.

## Notas

- Rama sugerida: `feature/compact-remaining-time`.
- **Decisiones que el spec debe cerrar explícitamente** (son las que hacen o rompen la feature):
  - **Menos de un minuto:** ¿`0m`? ¿`<1m`? ¿se muestran segundos solo en el último minuto? Es
    justamente el tramo en el que una persona con TDA/TDAH más necesita precisión.
  - **Cero exacto:** unificar con `tasks_time_up` en las tres superficies (hoy el widget no lo usa).
  - **Más de 24 h:** ¿`36h 10m` o `1d 12h`? Y si hay días, la unidad de día también va a recursos.
  - **Redondeo:** truncar o redondear al minuto. Truncar hace que "1h 0m" aparezca durante 60 s
    enteros; redondear hace que el contador salte hacia atrás. Elegir y justificar.
  - **Recursos nuevos o reutilizados:** `tasks_duration_hours_minutes` / `_hours` / `_minutes` ya
    existen para la *duración estimada*. Decidir si el tiempo restante los reutiliza o estrena
    hermanos (`tasks_remaining_*`) — probablemente lo segundo, porque "Duración: 2 h 30 min" y un
    contador compacto "2h 38m" pueden querer abreviaturas distintas sin pisarse.
- **Consecuencia a evaluar, no a improvisar:** sin segundos en pantalla, el tick de 1 s de
  [`CountdownTicker.kt`](../../app/src/main/java/com/neverlate/ui/tasks/CountdownTicker.kt) ya no lo
  justifica el texto — pero la **barra de progreso de la feature 19 sí lo aprovecha** para moverse
  suavemente. El spec decide si el tick se mantiene, se baja de frecuencia o se desacopla; lo que no
  vale es cambiarlo sin darse cuenta.
- **Extiende, no dupliques — el patrón ejemplar ya está escrito.** `durationLabel` en
  [`TasksScreen.kt`](../../app/src/main/java/com/neverlate/ui/tasks/TasksScreen.kt) combina
  `durationParts` + `NumberFormat.getIntegerInstance(locale)` + los tres recursos de duración, y es
  exactamente el mecanismo que el tiempo restante debe seguir. No se escribe un `String.format`
  nuevo, y `durationParts` en
  [`TaskTiming.kt`](../../app/src/main/java/com/neverlate/data/tasks/TaskTiming.kt) se reutiliza tal
  cual para partir los milisegundos.
- **Diseño (obligatorio en el spec):** la sección **Visual & UX Design** debe declarar que esto
  **no reclama ninguna `slice`** nueva del maquetado — refina el texto dentro de la `slice` ya ✅
  *"Urgency-colored countdown"* — y añadir criterios visuales concretos: el contador no cambia de
  ancho a cada segundo (era uno de los motivos de ruido de `hh:mm:ss`), sigue coloreado por urgencia,
  y es legible a `fontScale` máximo en la tarjeta, en el widget y en la notificación. En el **Design
  review**, actualizar la nota de esa fila en `docs/mockups/README.md`.
- **Referencia visual:** abre `docs/mockups/rediseno-ux-ui.html` como guía de dirección, **no** código
  a copiar.
- **Tests que hoy fallarán y hay que reescribir, no borrar:**
  [`TaskTimingTest.kt`](../../app/src/test/java/com/neverlate/data/tasks/TaskTimingTest.kt) asserta
  literales `"00:00"` / `"1:00:00"`, y
  [`PendingTasksWidgetStateTest.kt`](../../app/src/test/java/com/neverlate/ui/widget/PendingTasksWidgetStateTest.kt)
  comprueba el string ya formateado dentro de la fila. Al mover el formateo a la UI, esos tests pasan
  a asertar `remainingMillis` y aparecen tests nuevos para el formateador localizado.
- Sin backend, sin contrato, sin migración de Room, sin nueva dependencia.
- Ficheros:
  [`TaskTiming.kt`](../../app/src/main/java/com/neverlate/data/tasks/TaskTiming.kt) (`formatRemaining`),
  [`PendingTaskRows.kt`](../../app/src/main/java/com/neverlate/domain/tasks/PendingTaskRows.kt)
  (`PendingTaskRow` + `pendingRowsFor`),
  [`TasksScreen.kt`](../../app/src/main/java/com/neverlate/ui/tasks/TasksScreen.kt) (`TaskRow`),
  [`PendingTasksWidget.kt`](../../app/src/main/java/com/neverlate/ui/widget/PendingTasksWidget.kt),
  [`TasksNotificationHelper.kt`](../../app/src/main/java/com/neverlate/ui/notification/TasksNotificationHelper.kt),
  [`strings.xml`](../../app/src/main/res/values/strings.xml) +
  [`values-en/strings.xml`](../../app/src/main/res/values-en/strings.xml).
- Agentes: `mobile-engineer` (refactor de `PendingTaskRow` + formateador localizado en las tres
  superficies), `qa-engineer` (tests JVM del formateador con varios `Locale` y todos los casos
  límite, más los tests existentes reescritos).
- **Hazla antes que `widget-rediseno-visual.md`**: ese rediseño necesita el `PendingTaskRow` con
  datos crudos que esta feature deja. Al revés se paga el mismo refactor dos veces.
