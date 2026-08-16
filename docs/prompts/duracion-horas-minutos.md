# Feature — Duración de la tarea en horas y minutos

Lee `CLAUDE.md` (**Tutorial Track (optional)** + **Mandatory Workflow** + **Design in the Workflow**)
y las lecciones previas (en especial la 04: alta de tarea, duración estimada y validación del
formulario; la 08: i18n y el porqué de no concatenar texto en código; y la 14: selector nativo de
fecha, que ya sustituyó el otro campo escrito a mano del mismo formulario). Implementa **"introducir
la duración estimada en horas y minutos, no en un único campo de minutos"** siguiendo el flujo
`/feature`.

> **Cambio solo de presentación.** La columna `estimatedDurationMillis` de Room **no se toca**: sigue
> guardando milisegundos y por tanto **no hay migración, ni cambio de esquema, ni cambio de
> contrato**. Lo que cambia es cómo la persona introduce ese número.

## Qué construir

- El campo único **"Duración estimada (minutos)"** del formulario de tarea se sustituye por una
  entrada de **horas + minutos** (dos campos numéricos contiguos, o un selector de duración), de
  forma que "2 h 30 min" no obligue a escribir `150`.
- Al **editar** una tarea existente, los dos campos vienen **precargados** a partir de los
  milisegundos ya guardados (90 min → `1` h y `30` min).
- La **validación** sigue siendo una única función pura: la regla de "qué es una duración válida"
  vive en un solo sitio y se prueba en la JVM, ahora con dos entradas en vez de una.
- **Cero impacto aguas abajo:** la lista, el widget, la notificación, la barra de progreso y el
  `TaskDto` siguen viendo exactamente el mismo `estimatedDurationMillis` de siempre.

## Tutorial

Antes de escribir el spec, el flujo `/feature` **debe preguntar con `AskUserQuestion`** si esta
feature lleva lección en español (*Sí, con lección / No / Decidir al final*), y la respuesta se anota
en el campo `Tutorial:` del spec.

**Recomendación: No.** Es un rediseño de un formulario que ya existe, con conceptos (state hoisting,
`OutlinedTextField`, validación pura) todos ya enseñados en la lección 04. Si aun así se quisiera
lección, lo único con sustancia sería *modelar el estado de un formulario con varios campos que
representan un único valor de dominio*.

## Notas

- Rama sugerida: `feature/duration-hours-minutes`.
- **Diseño (obligatorio en el spec):** la sección **Visual & UX Design** debe describir cómo se
  colocan los dos campos (fila con `weight`, reflow en pantalla estrecha, comportamiento a fuente
  máxima) y declarar que **no reclama ninguna `slice`** del maquetado — `docs/mockups/README.md` no
  tiene fila para el formulario de duración, así que esto es UI net-new sobre una pantalla ya
  existente. Criterios de aceptación visuales concretos: ambos campos con etiqueta propia visible,
  targets ≥48dp, sin desbordamiento horizontal a `fontScale` máximo.
- **Referencia visual:** abre `docs/mockups/rediseno-ux-ui.html` como guía de dirección, **no** código
  a copiar; tradúcela con los tokens reales del tema (`ui/theme/`).
- **Extiende, no dupliques:**
  - `durationParts(millis)` en
    [`TaskTiming.kt`](../../app/src/main/java/com/neverlate/data/tasks/TaskTiming.kt) **ya** parte los
    milisegundos en `(horas, minutos)` y está testeada — el precargado de edición la reutiliza en vez
    de volver a dividir a mano (hoy `TaskEditViewModel` hace `it / 60_000L` por su cuenta).
  - La regla de validación sigue en
    [`TaskValidation.kt`](../../app/src/main/java/com/neverlate/data/tasks/TaskValidation.kt)
    (`validateTaskForm` + su `parseDurationMinutes` privada), **ampliada** a dos entradas. No se
    duplica lógica de parseo en el `ViewModel` ni en el composable.
- **Casos límite que el spec debe resolver explícitamente** (no dejarlos al criterio del que
  implemente): minutos ≥ 60 escritos en el campo de minutos (¿se normaliza a horas, se rechaza, se
  acepta?), ambos campos vacíos (sigue siendo válido si hay fecha límite), ambos a cero (inválido),
  solo horas, y entrada no numérica — ojo: hoy `KeyboardType.Number` **solo elige el teclado**, no
  filtra lo que se pega, así que un pegado de texto llega tal cual a la validación.
- **i18n:** etiquetas nuevas en `res/values/strings.xml` (español, base) **y** `res/values-en/`.
  `task_edit_duration_label` ("Duración estimada (minutos)" / "Estimated duration (minutes)") deja de
  mencionar minutos, y `tasks_error_invalid_duration` se reescribe para reflejar la nueva entrada.
  Nada de concatenar unidades en Kotlin.
- Sin backend, sin contrato, sin migración de Room, sin nueva dependencia. Sin `slice` de maquetado.
- Ficheros:
  [`TaskEditScreen.kt`](../../app/src/main/java/com/neverlate/ui/tasks/TaskEditScreen.kt) (el campo),
  [`TaskEditViewModel.kt`](../../app/src/main/java/com/neverlate/ui/tasks/TaskEditViewModel.kt)
  (`TaskEditUiState`, precargado, `onDurationMinutesChange`),
  [`TaskValidation.kt`](../../app/src/main/java/com/neverlate/data/tasks/TaskValidation.kt),
  [`strings.xml`](../../app/src/main/res/values/strings.xml) +
  [`values-en/strings.xml`](../../app/src/main/res/values-en/strings.xml).
- Agentes: `mobile-engineer` (formulario + estado + precargado), `qa-engineer` (tests JVM de la
  validación con las dos entradas y sus casos límite, más test de UI del round-trip guardar/reabrir).
