# Lección 19 — Barra de progreso hacia el plazo en cada tarjeta de tarea

> Objetivo: **cerrar la mitad que faltaba** de la lección 17. Allí le dimos a la cuenta atrás una
> señal de urgencia **solo por color** (verde → ámbar → rojo) y dejamos anotado en el maquetado un
> "v1: sin barra de progreso". El maquetado (`docs/mockups/rediseno-ux-ui.html`) sí lleva una barra
> fina bajo la cuenta atrás de cada tarjeta: casi vacía cuando sobra tiempo, casi llena según se
> acerca el plazo, llena y roja al vencer. En esta lección la implementamos por fin. No es una
> feature "nueva" desde cero: es la **continuación directa de la 17**, y por eso reutiliza sus mismas
> piezas (el valor vivo `remainingMillis`, `urgencyLevelFor`, `colorForUrgency`, `derivedStateOf`) en
> lugar de calcular nada por un camino paralelo. La misma regla de diseño de siempre: **extender, no
> duplicar** — ahora, que el color y el relleno cuenten *una sola* historia, nunca dos que puedan
> contradecirse.
>
> Es también la primera vez que el tutorial se dedica a los **indicadores de progreso determinados**
> de Material 3 y añade un segundo ejemplo, más limpio, del patrón "función pura + `derivedStateOf`"
> que ya viste en la 17 (`urgencyLevelFor`) y en la 09 (`ReminderPlanning.kt`).

## Conceptos que aprendes aquí

Partiendo de la lección 04 (la lista, la cuenta atrás con `CountdownTicker`, el valor
`remainingMillis`) y de la 17 (urgencia por color con `urgencyLevelFor` + `derivedStateOf`):

1. **Indicadores de progreso determinados** — `LinearProgressIndicator(progress = { … })`, la
   diferencia entre progreso **determinado** e **indeterminado**, y por qué el valor va en `0f..1f`.
2. **Derivar el progreso de una función pura** — una helper testeable en JVM,
   `deadlineProgressFor`, en `domain/tasks/`, con sus casos límite (sin ventana total, ya vencido,
   plazo aún lejano).
3. **Animar un valor** — `animateFloatAsState` para que la barra **transicione** en vez de saltar,
   enlazando con las animaciones de la 17.
4. **Semántica de progreso y accesibilidad** — por qué un indicador visual también tiene que
   **anunciarse** a los lectores de pantalla, y por qué el estado nunca puede depender solo del color.

---

## 1. Determinado vs. indeterminado: por qué el valor va en `0f..1f`

Material 3 tiene dos sabores de `LinearProgressIndicator`:

- **Indeterminado** — el que se ve por defecto si no le pasas `progress`. Es la barra que **se
  desplaza sola** en bucle. Dice "está pasando algo, no sé cuánto falta". Lo usarías, por ejemplo,
  mientras esperas una respuesta de red cuya duración no puedes predecir.
- **Determinado** — le pasas un `progress` entre `0f` (vacío) y `1f` (lleno). Dice **exactamente**
  cuánto se ha completado. Es el que queremos: la fracción de tiempo transcurrido hacia el plazo es
  un número perfectamente conocido en cada instante.

La clave conceptual: un indicador determinado necesita que le **traduzcas tu magnitud al rango
`0f..1f`**. Nuestra magnitud es "tiempo transcurrido", y el `100%` es "se agotó el plazo". Ese
mapeo — de milisegundos a fracción — es justo lo que aísla la función pura del siguiente apartado.

> Un valor fuera de `0f..1f` no es un error de compilación, pero la barra lo recorta y el resultado
> es confuso. Por eso la fracción se **acota** (`coerceIn(0f, 1f)`) en la propia función pura, no en
> la UI: mejor garantizar el invariante en el sitio donde se calcula.

---

## 2. La función pura: `deadlineProgressFor`

Igual que la 17 no metió el cálculo de la urgencia dentro del `Composable` sino en una función pura
(`urgencyLevelFor`), aquí el cálculo de la fracción vive en `domain/tasks/DeadlineProgress.kt`,
**sin Compose, sin reloj propio, sin Android**. Es un simple `fun` de valores ya calculados, lo que
lo hace trivialmente testeable en la JVM: el test solo pasa números.

```kotlin
fun deadlineProgressFor(remainingMillis: Long, totalMillis: Long?, isTimedOut: Boolean): Float? {
    if (totalMillis == null || totalMillis <= 0) return null   // sin ventana → sin barra
    if (isTimedOut || remainingMillis <= 0) return 1f          // vencido → lleno
    if (remainingMillis >= totalMillis) return 0f              // aún no ha empezado a correr → vacío

    val elapsedMillis = totalMillis - remainingMillis
    return (elapsedMillis.toFloat() / totalMillis.toFloat()).coerceIn(0f, 1f)
}
```

### La ventana total y por qué a veces devolvemos `null`

Una barra de "% transcurrido" necesita algo que la cuenta atrás por sí sola no tiene: una **ventana
total** contra la que medir. `remainingMillis` es el numerador (lo que falta); necesitamos el
denominador (cuánto duraba todo).

El modelo de datos (`Task`) **no guarda un `createdAt` ni un instante de inicio** — solo
`estimatedDurationMillis` y `deadline`. Así que usamos esa **duración estimada** como ventana total:
transcurrido = `total − restante`. Fue una decisión de producto explícita del spec (Riesgo 1):
inventar un ancla de inicio habría exigido cambiar la base de datos, y eso quedaba fuera del alcance.

La consecuencia honesta de esa decisión: una tarea **sin** duración usable (solo con plazo, o sin
nada que medir) **no tiene un "porcentaje transcurrido" bien definido**. En vez de dibujar un relleno
arbitrario y mentiroso, devolvemos `null` y la UI **no pinta barra** en ese caso. El color y la
cuenta atrás siguen comunicando la urgencia; simplemente no añadimos una barra que engañaría.

### Los casos límite (que el test fija uno a uno)

| Entrada | Salida | Motivo |
|---|---|---|
| `totalMillis` `null` o `<= 0` | `null` | no hay ventana → sin barra (aunque `isTimedOut` sea `true`) |
| `isTimedOut` o `remaining <= 0` | `1f` | se agotó → barra llena |
| `remaining >= total` | `0f` | aún no ha empezado a consumirse → barra vacía |
| resto | `(total − remaining) / total`, acotado | fracción normal |

El orden de las guardas importa: comprobamos primero la ventana (`null`) — un vencido **sin** ventana
sigue sin barra —, luego el vencido, luego el "aún vacío", y solo entonces el cálculo general. Cada
fila de esa tabla es un test en `DeadlineProgressTest` (13 en total).

---

## 3. En `TaskRow`: derivar, animar, dibujar

Todo el cambio de UI vive en `TaskRow`, dentro de `ui/tasks/TasksScreen.kt`. Tres pasos.

### 3.1 Derivar la fracción del mismo valor vivo (una sola fuente de verdad)

Al lado del `derivedStateOf` que la 17 ya usaba para el color, añadimos un **hermano**, con las
**mismas claves** (`remainingMillis`, `isTimedOut`):

```kotlin
val urgencyLevel by remember(uiModel.remainingMillis, uiModel.isTimedOut) {
    derivedStateOf { urgencyLevelFor(uiModel.remainingMillis, uiModel.isTimedOut) }
}

// Feature 19: hermano del anterior, con las MISMAS entradas. La fracción de la barra y el color de
// la cuenta atrás son dos vistas del único valor vivo — nunca dos cálculos que puedan discrepar.
val progress by remember(uiModel.remainingMillis, uiModel.isTimedOut) {
    derivedStateOf {
        deadlineProgressFor(uiModel.remainingMillis, task.estimatedDurationMillis, uiModel.isTimedOut)
    }
}
```

Recuerda de la 17 por qué `derivedStateOf` y no un cálculo suelto: `remainingMillis` cambia **cada
segundo** (el `CountdownTicker`), pero la fracción visible a menudo no se mueve de forma apreciable.
`derivedStateOf` hace que quien *lee* `progress` solo recomponga cuando el `Float?` derivado cambia
de verdad, no en cada tic. Y como color y fracción parten del **mismo** dato por el **mismo**
mecanismo, es imposible que la barra diga una cosa y el color otra.

### 3.2 Animar el valor con `animateFloatAsState`

Si `progress` es `null`, no dibujamos nada. Si no, animamos el relleno para que **deslice** hacia su
nuevo valor en lugar de dar un salto:

```kotlin
progress?.let { targetFraction ->
    val animatedProgress by animateFloatAsState(
        targetValue = targetFraction,
        label = "deadlineProgress",
    )
    // …
    LinearProgressIndicator(
        progress = { animatedProgress },
        color = colorForUrgency(urgencyLevel),          // reutiliza el mapa de color de la 17
        trackColor = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .semantics { stateDescription = progressStateDescription },
    )
}
```

La idea que enlaza con la 17: **animas el valor *renderizado*, mientras el *objetivo* viene de
`derivedStateOf`**. El objetivo solo cambia cuando la fracción se mueve de verdad; `animateFloatAsState`
suaviza el camino entre un objetivo y el siguiente. Eso incluye la transición a barra llena al pasar
a "vencido": tampoco salta a `1f`, la anima.

Fíjate en dos detalles de API:

- El `progress` es una **lambda** `() -> Float`, no un `Float` directo. Es la sobrecarga vigente de
  Material 3 (la que toma un valor directo está *deprecated*); pasar una lambda deja que el indicador
  lea el valor en el momento del dibujo y anima mejor. Por usar esa sobrecarga, `TaskRow` lleva
  `@OptIn(ExperimentalMaterial3Api::class)`.
- El **color** sale de `colorForUrgency(urgencyLevel)`, exactamente el mismo que ya tiñe el texto de
  la cuenta atrás. **No definimos ningún color nuevo**: `calm`/`soon` de `NeverLateExtras` y `error`
  de Material para urgente/vencido. La pista (`trackColor`) usa `surfaceVariant`, un tono de baja
  énfasis del tema, legible en claro y oscuro.

### 3.3 La barra vive **debajo** de la fila de la cuenta atrás

La colocamos al final de la `Column` de la tarjeta, tras la `Row` que contiene el texto de la cuenta
atrás y los botones — igual que en el maquetado, donde la `.bar` va bajo el número. Traducimos la
**intención** del maquetado (barra fina, redondeada, teñida por urgencia) con los tokens reales del
tema y los valores por defecto de Material 3; no copiamos su CSS ni clavamos su grosor exacto en px
(eso quedaría como pendiente menor si algún día se quiere afinar).

---

## 4. Semántica de progreso: que la barra también se **anuncie**

Una barra que solo se ve no sirve a quien usa un lector de pantalla. Aquí hay dos capas:

1. **La que da Material gratis.** Un `LinearProgressIndicator` **determinado** ya expone su
   `progressBarRangeInfo` en el árbol de semántica: el sistema sabe que es una barra de progreso y en
   qué punto del rango está. Es decir, **no** es decorativo; no lo marcamos como tal.
2. **La que añadimos nosotros.** Sobre eso ponemos un `stateDescription` legible: en vez de que
   TalkBack lea un ratio crudo `0..1`, anuncia algo como **"45 % transcurrido"**. El porcentaje se
   formatea con `NumberFormat.getPercentInstance(locale)` (respetando el idioma del dispositivo, como
   manda la lección 08) y el texto sale de un recurso nuevo en **ambos** idiomas:

```xml
<!-- res/values/strings.xml (español, base) -->
<string name="tasks_progress_state_description">%1$s transcurrido</string>
<!-- res/values-en/strings.xml (inglés) -->
<string name="tasks_progress_state_description">%1$s elapsed</string>
```

Y el principio transversal de accesibilidad, el mismo que en la 17: **el estado nunca depende solo
del color**. El texto "Tiempo agotado" sigue apareciendo al vencer, y ahora, además de color y texto,
tenemos el relleno y su porcentaje anunciado. Cuatro formas de contar la misma historia, una de ellas
sin depender de la vista.

> Nota sobre tamaños táctiles: la regla de "≥ 48 dp" de la lección 18 **no aplica** aquí. Esa regla
> es para *controles* que se tocan; la barra es un **indicador de solo lectura**. Lo que sí aplica es
> la legibilidad con fuente grande: la tarjeta debe reflowar sin recortes al máximo tamaño de letra.

---

## 5. Tests

Dos frentes, siguiendo la separación pura/UI:

- **JVM (rápidos, sin Compose)** — `DeadlineProgressTest.kt` prueba `deadlineProgressFor` caso a
  caso: ventana `null`/`0`/negativa → `null` (incluso estando vencido), vencido/`remaining<=0` →
  `1f`, `remaining>=total` → `0f`, una fracción intermedia y el acotado en los extremos. Al ser una
  función pura, cada test es una línea de "entra esto, sale esto".
- **UI instrumentado (Compose)** — en `TasksScreenTest.kt`, dos tests: una tarea **con**
  `estimatedDurationMillis` **sí** pinta barra (se busca un nodo con `ProgressBarRangeInfo` en el
  árbol *sin fusionar*, acotado por el título de la tarea para no confundirlo con el indicador del
  `PullToRefreshBox`); una tarea **sin** duración usable **no** pinta barra.

```bash
./gradlew :app:testDebugUnitTest        # los de JVM (incluye DeadlineProgressTest)
./gradlew :app:connectedDebugAndroidTest # los de Compose (necesitan emulador/dispositivo)
```

---

## Resumen

Cerramos la barra que la 17 había dejado como "v1: sin barra", sin cambiar arquitectura, datos,
navegación ni contrato:

1. **Progreso determinado** — un `LinearProgressIndicator(progress = { … })` con valor en `0f..1f`,
   frente al indeterminado que solo gira. Nuestra magnitud ("tiempo transcurrido") se traduce a ese
   rango.
2. **Función pura `deadlineProgressFor`** — el cálculo vive en `domain/tasks/`, sin Compose ni reloj,
   testeable a números. Usa `estimatedDurationMillis` como ventana total y devuelve `null` (→ sin
   barra) cuando no hay ventana con sentido, en vez de mentir con un relleno arbitrario.
3. **`derivedStateOf` hermano** — la fracción se **deriva** del mismo `remainingMillis` que el color,
   con las mismas claves: una sola fuente de verdad, recomposición solo cuando cambia de verdad.
4. **`animateFloatAsState`** — se anima el valor *renderizado* mientras el *objetivo* viene del
   `derivedStateOf`; la barra desliza, no salta (ni siquiera al llenarse al vencer).
5. **Semántica + accesibilidad** — el indicador determinado ya se anuncia como barra de progreso; le
   añadimos un `stateDescription` con el porcentaje localizado, y mantenemos el texto como refuerzo
   para no depender del color. La regla de ≥ 48 dp no aplica (indicador, no control).

Lo que te llevas: cuando ya tienes un valor vivo y bien calculado, **una tercera vista de ese valor
(la barra) se *deriva*, no se recalcula** — y un indicador visual no está terminado hasta que también
se **anuncia**.
