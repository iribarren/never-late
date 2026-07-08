# Lección 14 — Selector nativo de fecha y hora para la fecha límite

> Objetivo: quitar la **última fricción de entrada** del formulario de tareas. Hasta ahora la fecha
> límite se escribía **a mano** con un patrón fijo (`dd/MM/yyyy HH:mm`): había que conocer el formato,
> teclear las barras y los dos puntos, y un desliz devolvía el error `INVALID_DEADLINE_FORMAT` como
> resultado *habitual*, no como rareza. En esta lección sustituimos ese campo de texto libre por un
> **flujo de selección nativo de Material 3**: un `OutlinedTextField` de solo lectura con un icono de
> calendario que abre un `DatePickerDialog` y, a continuación, un selector de hora (`TimePicker`). El
> usuario **ya no teclea** el patrón — lo elige a golpe de dedo — y el campo sigue mostrando la fecha
> ya elegida **formateada según el idioma del dispositivo** (reutilizamos el `formatDeadlineForDisplay`
> de la lección 08). La lección de diseño más importante: **reutilizamos, no duplicamos**. El *seam*
> `TaskEditViewModel` no cambia de forma, y el parseo/formateo sigue pasando por las funciones de
> `TaskTiming.kt` que ya teníamos.

## Conceptos que aprendes aquí

Partiendo de las lecciones 04 (formulario de tarea + lógica pura de tiempo) y 08 (formato de fechas
locale-aware con `java.time` y desugaring):

- **Diálogos en Compose con estado local.** Mostrar/ocultar un `DatePickerDialog` y un `TimePicker`
  con `var showDialog by remember { mutableStateOf(false) }`, botones de confirmar/descartar, y **por
  qué ese estado vive en la UI y no en el `ViewModel`** (estado efímero de interfaz vs. datos durables
  del formulario).
- **Componentes de fecha/hora de Material 3.** `rememberDatePickerState` / `DatePickerState`,
  `rememberTimePickerState`, `DatePicker`, `TimePicker`, y cómo **leer el resultado** de cada uno.
- **La zona horaria — el matiz que rompe todo si se descuida.** El `DatePickerState` devuelve millis
  en **UTC a medianoche**; hay que combinarlos con la hora elegida y **convertirlos a la zona local**
  antes de persistir. Un descuido aquí produce fechas "un día antes" para quien esté en un huso con
  desfase negativo (América).
- **`readOnly` + `trailingIcon`.** Un campo que *parece* un input pero se rellena por diálogo, con un
  icono de calendario que lleva `contentDescription` (accesibilidad / TalkBack).
- **Reutilizar en vez de duplicar.** Enrutar el valor elegido de vuelta por el *seam* existente
  (`formatDeadlineForInput` / `parseDeadline`) en lugar de escribir lógica de fechas nueva — y extraer
  la **única** conversión genuinamente nueva a una función pura, comprobable por tests JVM.

---

## 1. El punto de partida: el coste oculto del texto libre

En la lección 04 el campo de fecha límite era un `OutlinedTextField` normal enlazado a
`uiState.deadlineText: String`. Al guardar, `validateTaskForm` llamaba a `parseDeadline(text)`, que
devuelve `null` si el texto no encaja con el patrón `dd/MM/yyyy HH:mm`, y en ese caso se mostraba
`INVALID_DEADLINE_FORMAT`.

Ese patrón tiene tres problemas de usabilidad, especialmente para el público de esta app (personas con
TDAH):

1. Hay que **recordar** el orden exacto (¿día/mes o mes/día?) y los separadores.
2. Teclear una fecha larga en un móvil es lento y propenso a erratas.
3. El error de formato aparece **como camino normal**, no excepcional. Un error de validación que
   salta constantemente deja de ayudar y solo frustra.

La observación clave: si el usuario **elige** la fecha de un calendario en lugar de escribirla, el valor
elegido **siempre** es una fecha válida. `INVALID_DEADLINE_FORMAT` deja de ser un camino alcanzable en
uso normal. No lo borramos (sigue siendo el contrato de `parseDeadline`), simplemente deja de aparecer.

---

## 2. Qué NO tocamos: el *seam* del ViewModel

Antes de escribir la UI nueva, fijémonos en lo que **no** cambia. El `TaskEditViewModel` sigue
exponiendo exactamente el mismo estado y el mismo callback que en la lección 04:

```kotlin
data class TaskEditUiState(
    val title: String = "",
    val estimatedDurationMinutes: String = "",
    val deadlineText: String = "",          // sigue siendo un String, sigue en formato canónico
    val validationError: TaskValidationError? = null,
    val isSaved: Boolean = false,
)

fun onDeadlineTextChange(text: String) { /* … */ }   // sigue recibiendo un String
```

`deadlineText` guarda la fecha en el **formato canónico** `dd/MM/yyyy HH:mm` (lo produce
`formatDeadlineForInput`, lo lee `parseDeadline`). Es una cadena de **ida y vuelta máquina-máquina**,
fija a `Locale.ROOT` (lección 08): lo que escribe una parte lo lee la otra, en cualquier idioma de
dispositivo.

La decisión de diseño: **el picker no cambia la forma del *seam***. En vez de meter un nuevo campo en
`TaskEditUiState`, el picker calcula un instante, lo convierte al String canónico con
`formatDeadlineForInput(...)` y se lo pasa al **mismo** `onDeadlineChange` de siempre. El ViewModel y
`validateTaskForm` siguen consumiendo `deadlineText` exactamente igual que antes. Toda la novedad vive
en la capa de UI. Esto es *state hoisting* llevado a su conclusión: una costura estable absorbe un
cambio grande de interfaz sin enterarse.

> **Regla de oro del proyecto:** cuando un cambio de UI *puede* resolverse sin tocar el ViewModel, se
> resuelve sin tocarlo. Un ViewModel que no cambia es un ViewModel cuyos tests no hay que reescribir.

---

## 3. El campo: `readOnly` + `trailingIcon`

El nuevo campo de fecha límite se ve como un input, pero no deja teclear:

```kotlin
OutlinedTextField(
    value = deadlineDisplayText,           // ← NO es el deadlineText crudo (ver §4)
    onValueChange = {},                    // readOnly: el usuario elige, nunca teclea
    readOnly = true,
    interactionSource = deadlineFieldInteractionSource,
    label = { Text(stringResource(R.string.task_edit_deadline_label)) },
    trailingIcon = {
        Row {
            if (uiState.deadlineText.isNotBlank()) {
                IconButton(onClick = { onDeadlineChange("") }) {          // borrar la fecha
                    Icon(Icons.Filled.Clear, contentDescription = /* … */)
                }
            }
            IconButton(onClick = { showDatePicker = true }) {            // abrir el picker
                Icon(Icons.Filled.CalendarMonth, contentDescription = /* … */)
            }
        }
    },
    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
)
```

Tres detalles que enseña este fragmento:

- **`readOnly = true`** desactiva el teclado software al enfocar el campo, pero **mantiene** el aspecto
  de un `OutlinedTextField` (borde, etiqueta flotante). Es "display que parece input".
- **`trailingIcon` con `contentDescription`.** Cada `IconButton` lleva un `contentDescription` desde un
  recurso de string (nunca un literal), para que **TalkBack** anuncie "Elegir fecha y hora" / "Borrar
  fecha límite". La accesibilidad no es opcional: un icono sin descripción es invisible para quien usa
  lector de pantalla.
- **Afijo de "borrar".** Como un `OutlinedTextField` solo admite **un** `trailingIcon`, metemos ambos
  iconos (borrar, cuando hay fecha; y calendario, siempre) en un `Row`. Borrar es simplemente
  `onDeadlineChange("")`.

### El matiz del `readOnly`: no recibe clics normales

Un `OutlinedTextField` en modo `readOnly` **no** dispara un `onClick` como lo haría uno editable. Si
queremos que **tocar el campo** (no solo el icono) abra el diálogo, hay que observar su
`interactionSource` a mano:

```kotlin
val deadlineFieldInteractionSource = remember { MutableInteractionSource() }
LaunchedEffect(deadlineFieldInteractionSource) {
    deadlineFieldInteractionSource.interactions.collectLatest { interaction ->
        if (interaction is PressInteraction.Release) {
            showDatePicker = true
        }
    }
}
```

Pasamos ese `interactionSource` al campo y escuchamos sus interacciones: una **pulsación soltada**
(`PressInteraction.Release`) sobre el campo abre el mismo flujo que tocar el icono de calendario. Así el
usuario puede tocar en cualquier parte del campo, no solo en el icono.

---

## 4. El campo muestra la fecha *locale-aware*, no el patrón crudo

Aquí entra en juego la lección 08. El estado guarda `deadlineText` en formato canónico
(`24/12/2026 20:30`), pero **enseñar** ese patrón fijo sería un paso atrás: en un móvil en inglés
esperaríamos `12/24/2026 8:30 PM`. Así que el campo **no** muestra `deadlineText` directamente. Lo
parsea y lo re-formatea según el idioma:

```kotlin
// deadlineText (canónico) → epoch millis, recalculado solo cuando cambia.
val parsedDeadlineMillis = remember(uiState.deadlineText) { parseDeadline(uiState.deadlineText) }

// epoch millis → texto legible en el idioma del dispositivo (feature 08).
val locale = LocalConfiguration.current.locales[0]
val deadlineDisplayText = parsedDeadlineMillis?.let { formatDeadlineForDisplay(it, locale) } ?: ""
```

Fíjate en la separación de responsabilidades:

- **`deadlineText`** (estado, canónico, `Locale.ROOT`) — la representación **máquina** que viaja por el
  *seam* y se valida.
- **`deadlineDisplayText`** (derivado, locale-aware) — la representación **humana** que se pinta.

`formatDeadlineForDisplay` (de `TaskTiming.kt`, lección 08) usa
`DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT).withLocale(locale)`, así que respeta el orden
día/mes, los separadores y el 12h/24h del dispositivo. **Reutilizamos**, no reinventamos.

El `remember(uiState.deadlineText)` es una micro-optimización didáctica: parsear solo se rehace cuando
el texto cambia, no en cada recomposición.

---

## 5. Diálogos en Compose: el estado vive en la UI

Un diálogo en Compose no es una pantalla nueva ni una entidad de navegación: es UI que se muestra
condicionalmente. Su estado de "abierto/cerrado" es **efímero** — no necesita sobrevivir a la muerte del
proceso ni forma parte de los datos del formulario. Por eso vive en el composable con `remember`, **no**
en el `ViewModel`:

```kotlin
var showDatePicker by remember { mutableStateOf(false) }
var showTimePicker by remember { mutableStateOf(false) }
// Puente del diálogo de fecha al de hora: los millis UTC-medianoche que el usuario acaba de
// confirmar, guardados hasta que también se conozca la hora.
var pickedDateUtcMillis by remember { mutableStateOf<Long?>(null) }
```

> **¿Cuándo va el estado en el ViewModel y cuándo en el composable?** Regla práctica: si el usuario
> giraría la pantalla o volvería a la app y **esperaría** que el valor siguiera ahí, es dato durable →
> ViewModel. Si es un afijo momentáneo de interacción (un diálogo abierto, un menú desplegado, un
> `interactionSource`), es UI efímera → `remember` en el composable. El `deadlineText` es durable; el
> "diálogo abierto" no.

El flujo encadena **fecha → hora**. El diálogo de fecha, al confirmar, guarda su resultado en
`pickedDateUtcMillis`, se cierra y abre el de hora:

```kotlin
if (showDatePicker) {
    val datePickerState = rememberDatePickerState(initialSelectedDateMillis = initialUtcMillis)
    DatePickerDialog(
        onDismissRequest = { showDatePicker = false },
        confirmButton = {
            TextButton(onClick = {
                pickedDateUtcMillis = datePickerState.selectedDateMillis   // leer el resultado
                showDatePicker = false
                showTimePicker = true                                     // encadenar a la hora
            }) { Text(stringResource(R.string.task_edit_date_confirm)) }
        },
        dismissButton = {
            TextButton(onClick = { showDatePicker = false }) {            // descartar = no cambia nada
                Text(stringResource(R.string.task_edit_date_dismiss))
            }
        },
    ) {
        DatePicker(state = datePickerState)
    }
}
```

- **`rememberDatePickerState`** crea y recuerda el estado del calendario. Se lee el resultado por
  `datePickerState.selectedDateMillis`.
- **Confirmar vs. descartar.** Solo el botón de confirmar propaga el valor. Descartar (o tocar fuera,
  `onDismissRequest`) cierra el diálogo dejando `deadlineText` **intacto**.
- **`TimePicker` no trae diálogo propio.** A diferencia de `DatePickerDialog`, el `TimePicker` de
  Material 3 es solo el componente. Lo envolvemos en un `AlertDialog` con sus botones de confirmar y
  descartar, y `TimePicker(state = timePickerState)` como contenido.

El `TimePicker` respeta el formato 12h/24h del dispositivo pasándole
`is24Hour = DateFormat.is24HourFormat(context)` (la API pública de plataforma).

---

## 6. El matiz clave: UTC-medianoche → hora local

Este es **el** punto de esta lección, el que produce bugs silenciosos si se descuida.

`DatePickerState.selectedDateMillis` **no** son los millis de la medianoche *local* del día elegido: son
los de la medianoche **UTC**. Es una decisión deliberada de Material 3 — el calendario habla en UTC para
no depender del huso del dispositivo.

¿Qué pasa si los tratamos ingenuamente como un instante local? Imagina a alguien en Nueva York (UTC-5) que
elige el **24 de diciembre**. El picker devuelve la medianoche UTC del 24 = `2026-12-24T00:00Z`. Si lo
leemos en la zona local, ese instante es **las 19:00 del día 23** en Nueva York. Formateamos, guardamos… y
la fecha límite ha retrocedido **un día**. El clásico bug "off-by-one-day".

La conversión correcta, en tres pasos: (1) leer la **fecha de calendario** interpretando los millis en
UTC, (2) combinarla con la hora/minuto elegidos en el `TimePicker`, (3) resolver ese `LocalDateTime` en la
zona **local** para obtener el instante final.

Como esta conversión es lo más *test-worthy* de toda la feature, no la dejamos incrustada en el composable
(donde solo un test instrumentado la alcanzaría). La **extraemos a una función pura** en `TaskTiming.kt`,
junto al resto de la lógica de fechas — igual que en la lección 04 sacamos `computeRemainingMillis` para
poder probarla con tests JVM sin emulador:

```kotlin
// TaskTiming.kt
fun deadlineFromPickedDateTime(
    dateUtcMidnightMillis: Long,          // DatePickerState.selectedDateMillis (UTC medianoche)
    hour: Int,                            // TimePicker
    minute: Int,                          // TimePicker
    zone: ZoneId = ZoneId.systemDefault(),
): Long {
    val pickedDate = Instant.ofEpochMilli(dateUtcMidnightMillis)
        .atZone(ZoneId.of("UTC"))         // ← paso 1: leer la FECHA en UTC, no en local
        .toLocalDate()
    return pickedDate.atTime(hour, minute)   // paso 2: combinar con la hora elegida
        .atZone(zone)                        // paso 3: resolver en la zona LOCAL
        .toInstant()
        .toEpochMilli()
}
```

Fíjate en el parámetro `zone` con valor por defecto `ZoneId.systemDefault()`: en producción usa la zona
del dispositivo, pero en los tests le **inyectamos** una zona fija (`America/New_York`, `Asia/Tokyo`) para
que el test sea determinista sin depender del reloj de la máquina de CI. Esto es lo que en la lección 04
llamábamos "función pura, sin lecturas de reloj ni de base de datos propias".

El composable, en el `confirmButton` del diálogo de hora, solo llama a la función pura y enruta el
resultado por el *seam*:

```kotlin
val epochMillis = deadlineFromPickedDateTime(
    dateUtcMidnightMillis = selectedUtcMillis,
    hour = timePickerState.hour,
    minute = timePickerState.minute,
)
onDeadlineChange(formatDeadlineForInput(epochMillis))   // ← de vuelta al formato canónico
```

Y para **pre-rellenar** los pickers al editar una tarea que ya tiene fecha, hacemos la conversión
**inversa**: del epoch local sacamos la `LocalDate`, y la re-expresamos como medianoche UTC para sembrar
`initialSelectedDateMillis`. Es el espejo exacto de lo anterior, y por eso el ciclo editar→abrir→confirmar
no desplaza la fecha.

---

## 7. Los tests: cubrir el matiz sin emulador

Como la conversión es una función pura, la probamos con **tests JVM** rápidos (JUnit4), en
`TaskTimingTest.kt`, sin instrumentar la UI. Los casos que cubren la feature:

1. **Zona con desfase negativo (US-4).** En `America/New_York`, elegir una fecha + hora y comprobar que
   el resultado, releído en esa zona, cae en el **mismo día** de calendario (no en el anterior) y a la
   misma hora/minuto. Es el guardián directo del bug "off-by-one".
2. **Zona con desfase positivo.** En `Asia/Tokyo` (UTC+9), el caso espejo.
3. **Fronteras de hora.** `00:00` no retrocede al día anterior; `23:59` no avanza al siguiente.
4. **Ida y vuelta por el *seam* (US-5).** Meter el resultado de `deadlineFromPickedDateTime` por
   `formatDeadlineForInput` y luego `parseDeadline`, y comprobar `parseDeadline(formatDeadlineForInput(x)) == x`.
   Es decir: **un valor elegido siempre parsea limpio**, así que `INVALID_DEADLINE_FORMAT` es inalcanzable
   desde el picker.

Cada test construye los millis UTC-medianoche igual que lo haría el `DatePickerState`:
`LocalDate.of(y, m, d).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()`, y **siempre** pasa una
`ZoneId` explícita a `deadlineFromPickedDateTime` — nunca `ZoneId.systemDefault()` — para no depender del
huso de la máquina.

---

## 8. Lo que hemos aprendido

- **Diálogos en Compose** con estado efímero en la UI (`remember`), botones de confirmar/descartar, y el
  criterio para decidir qué estado va en el ViewModel y qué va en el composable.
- **Los componentes de fecha/hora de Material 3** (`rememberDatePickerState`, `DatePicker`,
  `rememberTimePickerState`, `TimePicker`) y cómo encadenar fecha → hora leyendo el resultado de cada uno.
- **La trampa de la zona horaria:** `selectedDateMillis` es UTC-medianoche; la conversión correcta a hora
  local en tres pasos, y por qué saltársela mueve la fecha un día. Extraída a función pura para poder
  **testearla** como en la lección 04.
- **`readOnly` + `trailingIcon`** con `contentDescription` accesible: un campo que se ve como input pero se
  rellena por diálogo.
- **Reutilizar en vez de duplicar:** el picker enruta su resultado por `formatDeadlineForInput` /
  `parseDeadline` y muestra con `formatDeadlineForDisplay` (lección 08); el *seam* `TaskEditViewModel` no
  cambia de forma. La única lógica de fechas nueva es la conversión UTC→local, y hasta esa vive donde puede
  probarse.

Un cambio de UX visible —adiós al teclear fechas— resuelto casi entero en la capa de interfaz, con una sola
función pura nueva y cero cambios en el ViewModel, el backend, el contrato, la base de datos o las
dependencias. Cuando las costuras están bien puestas, las mejoras salen baratas.
