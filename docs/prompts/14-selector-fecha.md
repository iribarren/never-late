# Feature 14 (extra) — Selector de fecha/hora nativo para el `deadline`

Lee `CLAUDE.md` (**Tutorial Methodology** + **Mandatory Workflow**) y las lecciones previas (en
especial la 04: formulario de tarea y lógica pura de tiempo; la 08: formato de fechas locale-aware).
Implementa **"selección de fecha y hora con pickers nativos de Material 3"** siguiendo el flujo
`/feature`.

## Qué construir

- Sustituir el campo de `deadline` de **texto libre** (`dd/MM/yyyy HH:mm`) por un **selector nativo**:
  un `OutlinedTextField` en modo `readOnly` con un `trailingIcon` de calendario que abre un
  `DatePickerDialog` de Material 3 y, a continuación, un selector de hora (time picker).
- El campo sigue mostrando la fecha ya elegida, formateada de forma legible y **locale-aware**, pero el
  usuario **ya no teclea** el patrón: desaparece el error `INVALID_DEADLINE_FORMAT` como camino normal.
- Mantener el resto del formulario igual (título, duración) y la validación de negocio (fecha futura,
  duración-o-deadline).

## Conceptos nuevos a enseñar (lección en español)

- **Diálogos en Compose:** mostrar/ocultar un `DatePickerDialog`/`TimePicker` con estado local
  (`var showDialog by remember { mutableStateOf(false) }`), botones de confirmar/descartar, y por qué el
  estado del diálogo vive en la UI y no en el `ViewModel`.
- **Componentes de fecha/hora de Material 3:** `rememberDatePickerState` / `DatePickerState`,
  `rememberTimePickerState`, y cómo leer el resultado.
- **Zona horaria — el matiz clave:** el `DatePickerState` devuelve millis en **UTC (medianoche)**;
  combinarlo con la hora elegida y convertirlo a la zona local antes de persistir. Explicar por qué un
  descuido aquí produce fechas "un día antes".
- **`readOnly` + `trailingIcon`:** un campo que se ve como input pero se rellena por diálogo, con
  `contentDescription` en el icono (accesibilidad).

## Notas

- Rama sugerida: `feature/date-picker`.
- **Referencia visual:** abre `docs/mockups/rediseno-ux-ui.html` (maqueta de la dirección de diseño;
  el bloque *before→after* del campo de fecha y el panel del picker muestran el objetivo). Es guía de
  dirección, **no** código a copiar.
- **Reutiliza, no dupliques:** `TaskTiming.kt` ya tiene `formatDeadlineForInput`/`parseDeadline`/
  `formatDeadlineForDisplay` y el `deadlineText: String` en `TaskEditUiState`. Puedes mantener el estado
  como epoch-millis o seguir con el `String` formateado; en cualquier caso **el parseo/formateo pasa por
  las funciones existentes**, no por lógica nueva de fechas. El seam `TaskEditViewModel` no cambia de
  forma.
- Cambios acotados a [`TaskEditScreen.kt`](../../app/src/main/java/com/neverlate/ui/tasks/TaskEditScreen.kt)
  y su `ViewModel`; sin backend, sin contrato, sin nueva dependencia (los pickers están en Material 3).
- Agentes: `mobile-engineer` (pickers + conversión de zona), `qa-engineer` (tests de la conversión
  UTC↔local y de que el resultado sigue pasando la validación existente). Lección en
  `tutorial/14-selector-fecha.md` (español), numerada tras la 13.
