# Lección 20b — Tiempo restante compacto y localizable: dónde puede *nacer* el texto de cara al usuario

> Objetivo: convertir la cuenta atrás de `02:38:07` a un **`2h 38m`** compacto, sin segundos, en las
> **tres superficies** que la pintan (la tarjeta de tarea de la lección 04, el widget de la 05 y la
> notificación de pantalla de bloqueo de la 06). Suena a un cambio de formato de media hora. **No lo
> es.** El día que las unidades ("h", "m") tienen que poder traducirse a otro idioma con otras letras
> y otro orden, el texto **ya no puede nacer donde nacía** —una función "pura" en la capa de datos—,
> porque para traducirlo hace falta un `Context` y recursos. Esta lección trata de ese movimiento: es
> un **refactor de capas disfrazado de cambio de formato**, y es exactamente el trabajo que después
> habilita el rediseño del widget.

## Conceptos que aprendes aquí

Partiendo de la lección 08 (i18n: por qué el texto de cara al usuario nace en `strings.xml`,
`plurals`, fechas y números locale-aware) y de la 04/05/06 (la cuenta atrás y `pendingRowsFor`, la
regla compartida por widget y notificación):

1. **Dónde puede nacer el texto de cara al usuario.** Por qué una función "pura" que devuelve un
   `String` **ya formateado** es una fuga de la capa de presentación hacia el dominio, y cómo se
   detecta: el día que hay que traducirla, la solución fácil **no compila**.
2. **Formatear números y unidades según el `Locale`.** `NumberFormat` para las cifras y recursos con
   *placeholders* (`%1$s`) para las unidades y el orden de palabras — frente a concatenar `"$h" + "h"`
   en Kotlin.
3. **`<string>` con placeholder vs `<plurals>`.** Por qué la abreviatura "2h" **no** necesita plural
   pero "2 horas" **sí**, y cómo se decide sin dudar.
4. **Refactorizar un tipo compartido por dos superficies** (`PendingTaskRow`) sin romper ninguna, con
   los tests como red de seguridad.

---

## 1. El síntoma: una función pura que devuelve texto ya formateado

Así vivía el formateo hasta esta feature, en `data/tasks/TaskTiming.kt`:

```kotlin
// ANTES — el formato nacía en la capa de datos, con el separador ":" cableado en Kotlin.
fun formatRemaining(millis: Long): String {
    val totalSeconds = millis / 1000
    val h = totalSeconds / 3600
    val m = (totalSeconds % 3600) / 60
    val s = totalSeconds % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}
```

Parece inofensiva. Es "pura" (no toca disco ni red), es testeable, la usaban las tres superficies. El
problema no es *qué hace*, sino **dónde está** y **qué devuelve**: devuelve un `String` de cara al
usuario. Y un `String` de cara al usuario lleva dentro **decisiones de presentación**:

- El separador `:` está **cableado en Kotlin**. Es una decisión de idioma disfrazada de constante.
- El orden (horas antes que minutos) está cableado en el `format`.
- Las cifras se pintan con `%d`/`%02d`, que **siempre** usa dígitos ASCII y punto/coma a la occidental
  — ignora por completo el `Locale` del dispositivo.

Mientras el formato fuera `hh:mm:ss`, nada de esto molestaba: los `:` y los dígitos son iguales en
español y en inglés. **La fuga era invisible porque el idioma aún no la había tocado.**

### La prueba de la fuga: el día que hay que traducir, no compila

En cuanto queremos mostrar `2h 38m` con las letras "h"/"m" **traducibles**, la solución fácil sería:

```kotlin
// La tentación: seguir en TaskTiming.kt, ahora con letras.
fun formatRemaining(millis: Long): String {
    val (h, m) = durationParts(millis)
    return "${h}h ${m}m"   // ← ¿y para un idioma cuya unidad no es "h"? ¿y el orden?
}
```

Para que "h"/"m" salgan de `strings.xml` necesitas `context.getString(R.string.…)`. Y para eso
necesitas un `Context`. Pero `TaskTiming.kt` es **plain Kotlin del dominio/datos**: meter un
`import android.content.Context` ahí es precisamente lo que la arquitectura por capas (lección 07b)
existe para impedir. **El compilador te está diciendo dónde estaba mal el código desde el principio.**

> Esta es la lección clave y la más transferible: una función pura que devuelve texto **ya
> presentado** es una fuga de la capa de presentación hacia abajo. El truco para detectarla no es
> filosófico, es mecánico: *intenta internacionalizarla*. Si para hacerlo necesitas un `Context` en un
> sitio donde no debería haberlo, la fuga estaba ahí desde el día uno; el idioma solo la ha hecho
> visible.

---

## 2. El arreglo: separar la *forma* (dominio) del *texto* (presentación)

La solución no es "mover `formatRemaining` a la UI". Es **partirla en dos** por la costura que la fuga
delata:

- **La forma** — ¿esto es "menos de un minuto", "solo minutos", "días con horas y minutos"? — es una
  decisión **pura**: no depende del idioma. Se queda en el dominio.
- **El texto** — qué letras, qué dígitos, en qué orden — es **presentación**. Necesita `Context` y
  `Locale`. Sube a la UI.

### 2.1 La forma: una función pura que devuelve un tipo, no un `String`

`domain/tasks/RemainingTime.kt`. Fíjate en que **no devuelve texto**: devuelve un `sealed interface`
que enumera las formas posibles. Ni una letra, ni un separador, ni un `Context`.

```kotlin
sealed interface RemainingTime {
    data object TimeUp : RemainingTime                                   // exactamente 0
    data object UnderMinute : RemainingTime                              // 1 s … 59 s  → "<1m"
    data class Minutes(val minutes: Long) : RemainingTime                // h == 0, m > 0
    data class Hours(val hours: Long) : RemainingTime                    // 0 < h < 24, m == 0
    data class HoursMinutes(val hours: Long, val minutes: Long) : RemainingTime
    data class DaysHoursMinutes(val days: Long, val hours: Long, val minutes: Long) : RemainingTime
}

fun remainingTimeFor(remainingMillis: Long): RemainingTime {
    if (remainingMillis == 0L) return RemainingTime.TimeUp
    if (remainingMillis < 60_000L) return RemainingTime.UnderMinute

    val (hours, minutes) = durationParts(remainingMillis)   // ← reutilizada, no reinventada
    return when {
        hours >= 24 -> RemainingTime.DaysHoursMinutes(
            days = hours / 24,
            hours = hours % 24,
            minutes = minutes,
        )
        hours == 0L -> RemainingTime.Minutes(minutes)
        minutes == 0L -> RemainingTime.Hours(hours)
        else -> RemainingTime.HoursMinutes(hours, minutes)
    }
}
```

Dos detalles de diseño que ya conoces del tutorial y que aquí se repiten a propósito:

- **Extender, no duplicar (lección 19).** No escribimos una división nueva de milisegundos a horas y
  minutos: reutilizamos `durationParts(millis)` tal cual, la misma helper que ya usaba la *duración
  estimada*. Las horas pueden pasar de 24, así que los días se derivan encima (`h / 24`, `h % 24`)
  sin tocar `durationParts`.
- **Una función pura testeable en JVM (lección 17, 19).** `remainingTimeFor` no tiene `Context`, ni
  reloj, ni Android. El test solo le pasa números y comprueba el `sealed` que sale. Toda la lógica de
  ramas —y todos los casos límite— se prueban **sin emulador**.

### 2.2 El texto: un único formateador con `Context`, compartido por las tres superficies

`ui/components/RemainingTimeLabel.kt`. Este es el **único** sitio donde el tiempo restante se vuelve
texto — igual que `durationLabel` en `TasksScreen.kt` es el único sitio donde la duración estimada se
vuelve texto. Que sea uno solo es lo que impide que la tarjeta, el widget y la notificación **se
separen** en el redondeo o en las palabras.

```kotlin
fun formatRemainingLabel(context: Context, remainingMillis: Long): String {
    val locale = context.resources.configuration.locales[0]
    val numberFormat = NumberFormat.getIntegerInstance(locale)

    return when (val remaining = remainingTimeFor(remainingMillis)) {
        RemainingTime.TimeUp -> context.getString(R.string.tasks_time_up)
        RemainingTime.UnderMinute -> context.getString(R.string.tasks_remaining_under_minute)
        is RemainingTime.Minutes -> context.getString(
            R.string.tasks_remaining_minutes,
            numberFormat.format(remaining.minutes),
        )
        is RemainingTime.Hours -> context.getString(
            R.string.tasks_remaining_hours,
            numberFormat.format(remaining.hours),
        )
        is RemainingTime.HoursMinutes -> context.getString(
            R.string.tasks_remaining_hours_minutes,
            numberFormat.format(remaining.hours),
            numberFormat.format(remaining.minutes),
        )
        is RemainingTime.DaysHoursMinutes -> context.getString(
            R.string.tasks_remaining_days_hours_minutes,
            numberFormat.format(remaining.days),
            numberFormat.format(remaining.hours),
            numberFormat.format(remaining.minutes),
        )
    }
}
```

Fíjate en una decisión concreta: es una **función normal con un `Context`**, no un `@Composable` que
lea `LocalContext`. ¿Por qué? Porque el widget (Glance) y el `TasksNotificationHelper` **no tienen
composición** desde la que leer `LocalContext`/`LocalConfiguration`. Al pedir el `Context` como
parámetro, la misma función sirve a las tres superficies; cada una pasa el suyo (desde Compose, con
`LocalContext.current`).

---

## 3. `NumberFormat` + recursos con placeholder, no concatenación

Aquí está el corazón de la i18n de esta lección, y es el patrón que la app ya demostró en
`durationLabel`. Dos piezas:

### 3.1 Los números: `NumberFormat.getIntegerInstance(locale)`, no `Long.toString()`

`"$minutes"` (o `%d`) siempre pinta dígitos ASCII. Eso está mal en cualquier idioma que use otro
sistema de dígitos o un separador de millares distinto. `NumberFormat.getIntegerInstance(locale)`
formatea la cifra **según el `Locale`**: los mismos 1234 días saldrían `1,234` en inglés de EE. UU. y
`1.234` en alemán. Nuestro caso normal (0–59) no lo nota, pero la regla es la regla, y el test lo
comprueba precisamente con un `Locale` que agrupa distinto.

### 3.2 Las unidades y el orden: recursos con placeholder

Las letras "h"/"m"/"d" y el **orden de las palabras** no se escriben en Kotlin: viven en `strings.xml`
como plantillas con placeholders posicionales.

```xml
<!-- app/src/main/res/values/strings.xml (base, español) -->
<string name="tasks_remaining_days_hours_minutes">%1$sd %2$sh %3$sm</string>
<string name="tasks_remaining_hours_minutes">%1$sh %2$sm</string>
<string name="tasks_remaining_hours">%1$sh</string>
<string name="tasks_remaining_minutes">%1$sm</string>
<string name="tasks_remaining_under_minute">&lt;1m</string>
```

El `%1$s`, `%2$s`… son placeholders **posicionales**: el `$1`, `$2` dicen *qué* argumento va en cada
hueco, independientemente del **orden** en que aparezcan en la frase. Eso es justo lo que un traductor
necesita: un idioma que ponga los minutos antes que las horas solo tiene que reordenar
`%2$sm %1$sh` en su `strings.xml`, **sin tocar Kotlin**. Con concatenación (`"$h" + unidadH + " " +
"$m" + unidadM`) ese reordenamiento sería imposible sin recompilar.

> **¿Por qué `%1$s` (string) y no `%1$d` (entero)?** Porque el número **ya viene formateado** por
> `NumberFormat` cuando llega al recurso: le pasamos un `String`, no un `Long`. Si usáramos `%d`, el
> propio `getString` volvería a formatear el entero con dígitos ASCII y perderíamos el trabajo de
> `NumberFormat`. Formatea primero la cifra por `Locale`, insértala como texto después.

### 3.3 Recursos nuevos, no reutilizar los de la duración

La *duración estimada* ya tenía `tasks_duration_hours_minutes` = `%1$s h %2$s min` (se lee "2 h 30
min"). Habría sido tentador reutilizarlos. **No lo hicimos**, y es una decisión, no un descuido: un
contador compacto ("38m") y una duración en prosa ("38 min") quieren **abreviaturas distintas**, y no
deben poder cambiarse el uno al otro por accidente. Por eso el tiempo restante estrena sus propios
hermanos `tasks_remaining_*`. El único recurso **reutilizado** es `tasks_time_up` ("Tiempo agotado"),
que ya existía.

---

## 4. `<string>` con placeholder vs `<plurals>`: por qué "2h" no lleva plural

En la lección 08 aprendiste `<plurals>` para "1 tarea" / "2 tareas". Aquí **no** usamos `<plurals>`, y
saber *por qué* es parte del aprendizaje.

`<plurals>` existe porque en muchos idiomas la **palabra** cambia con la cantidad: "1 hora" vs "2
horas", y en otros idiomas con reglas mucho más ricas (uno, dos, pocos, muchos…). Si escribiéramos el
contador en prosa ("2 horas 38 minutos"), **necesitaríamos** `<plurals>` para cada unidad.

Pero la **abreviatura** "2h" no tiene singular ni plural: "1h", "2h", "10h" son todas iguales. La
forma abreviada es invariante frente a la cantidad, así que un `<string>` con placeholder basta y
sobra. La regla práctica:

- ¿La unidad se escribe **con palabra** ("hora/horas")? → `<plurals>`.
- ¿La unidad es una **abreviatura invariante** ("h")? → `<string>` con placeholder.

Elegir la abreviatura fue, además, una decisión de producto (un contador **compacto** que no cambia de
ancho); una vez tomada, arrastra la decisión técnica de no necesitar `<plurals>`.

---

## 5. Refactorizar un tipo compartido con los tests como red

El formateo salía de `pendingRowsFor` (la regla compartida por widget y notificación de las lecciones
05/06). El tipo que devolvía cargaba texto ya formateado:

```kotlin
// ANTES: la fila llevaba texto presentado + un flag redundante.
data class PendingTaskRow(val title: String, val remaining: String, val isTimedOut: Boolean)

// AHORA: lleva el dato crudo. El texto lo pone cada superficie con su Context.
data class PendingTaskRow(val title: String, val remainingMillis: Long)
```

Dos limpiezas en un solo cambio:

1. **`remaining: String` → `remainingMillis: Long`.** El texto deja de nacer en el dominio; la fila
   transporta el dato crudo y cada superficie lo formatea con `formatRemainingLabel(context, …)`.
2. **Fuera `isTimedOut`.** Era estado **duplicado**: "agotado" es trivialmente `remainingMillis == 0L`.
   Mantener un flag aparte es arriesgarse a que un día el flag y el número se contradigan. Ahora hay
   **una sola fuente de verdad** y cada superficie deriva el "agotado" donde lo necesita.

La **regla** que `pendingRowsFor` posee —qué cuenta como pendiente, en qué orden (más urgente primero),
con qué tope (5 filas)— **no cambia**. Solo cambia el *payload* de cada fila.

### Los tests son la red, no el obstáculo

Cambiar un tipo que usan dos superficies asusta: rompes dos sitios a la vez. Aquí es donde el tutorial
cobra su deuda. Al compilar, **exactamente tres ficheros de test** dejaron de compilar —
`TaskTimingTest` (afirmaba `"00:00"` sobre la desaparecida `formatRemaining`), `NotificationModelTest`
y `PendingTasksWidgetStateTest` (construían/leían los campos viejos). Ninguno más. Esa lista *es* el
mapa de impacto del refactor: el compilador te dice cada sitio afectado, y los tests, al reescribirse,
confirman que el comportamiento (orden, tope, valor) sobrevive.

Los tests no se **borran**, se **reescriben** para afirmar `remainingMillis` en lugar del texto
formateado. Y aparece uno nuevo, `RemainingTimeTest`, que prueba la función pura contra toda la
*matriz de formato* (ver abajo). El formateo con `Context` se prueba aparte, con Robolectric, en más
de un `Locale` (incluido uno que agrupa dígitos distinto, para demostrar que las cifras pasan de
verdad por `NumberFormat`).

---

## 6. Las tres superficies (y un bug que cae de paso)

Con la forma en el dominio y el texto en un único formateador, las tres superficies quedan casi
idénticas: cada una llama a `formatRemainingLabel(context, remainingMillis)`.

- **Tarjeta** (`TasksScreen.kt`, `TaskRow`): antes tenía `if (isTimedOut) tasks_time_up else
  formatRemaining(…)`. Ahora es **una sola llamada**: el formateador ya posee la rama del cero. El
  color de urgencia (lección 17) y el estilo `headlineSmall` no cambian.
- **Widget** (`PendingTasksWidget.kt`): pinta `formatRemainingLabel(context, row.remainingMillis)` y
  deriva el rojo de "agotado" de `row.remainingMillis == 0L`.
- **Notificación** (`TasksNotificationHelper.kt`): su helper `remainingLabel` se reduce a una línea
  que delega en el formateador compartido.

### El bug que se corrige de paso

Había una **incoherencia real**: al agotarse el tiempo, la tarjeta y la notificación mostraban "Tiempo
agotado", pero el **widget** seguía pintando el contador congelado a cero. La causa era justo la que
esta feature elimina: cada superficie decidía el texto por su cuenta, y el widget no compartía la rama
de `tasks_time_up`. Al centralizar el texto en un formateador, la rama del cero **la posee uno solo**,
y las tres superficies quedan alineadas automáticamente. Un ejemplo perfecto de cómo **eliminar
duplicación no es solo estética: cierra la puerta a que dos copias se desincronicen.**

---

## 7. Las decisiones de los bordes (y por qué el tick de 1 s sobrevive)

El spec cerró explícitamente los tramos que "hacen o rompen" la feature. Merece la pena verlos porque
cada uno es una micro-lección de diseño:

| Tramo | Se muestra | Por qué |
|---|---|---|
| Exactamente 0 | `Tiempo agotado` | Unificado en las tres superficies (arregla el widget). |
| 1 s … 59 s | `<1m` | `0m` se lee como "hecho" 60 s enteros cuando no lo está; los segundos reintroducen el parpadeo de ancho que la feature elimina. `<1m` es estable y honesto. |
| ≥ 24 h | `1d 12h 10m` | Un plazo lejano se lee más tranquilo en días que como "36h". El tramo de días **siempre** muestra las tres partes (`2d 0h 0m`) para necesitar un único recurso y evitar el ambiguo `1d 30m`. |
| Redondeo | **Truncar** al minuto | Truncar mantiene el número **monótonamente decreciente** (nunca salta hacia atrás) y nunca promete tiempo de más. Redondear haría saltar el contador. |

### El tick de `CountdownTicker` no se toca "por si acaso"

Sin segundos en pantalla, el texto solo cambia una vez por minuto. La tentación es bajar la frecuencia
del `CountdownTicker` de 1 s (lección 04). **Pero la barra de progreso de la lección 19 consume ese
mismo tick** para drenarse suavemente: a cadencia de minuto, la barra pegaría tirones visibles. La
decisión —documentada en el KDoc del `CountdownTicker`— es **mantener el tick de 1 s**, cuya
justificación simplemente se traslada del texto a la barra. La moraleja transversal: cuando quitas el
consumidor evidente de un recurso compartido, **comprueba quién más lo usaba antes de "optimizarlo"**.

---

## Lo que te llevas

- **El texto de cara al usuario tiene un sitio donde nacer, y no es el dominio.** Una función pura que
  devuelve un `String` presentado es una fuga; la detectas intentando traducirla — si necesitas un
  `Context` donde no debería, la fuga estaba ahí.
- **Parte por la costura:** la *forma* (pura, testeable, un `sealed`) se queda en el dominio; el
  *texto* (letras, dígitos, orden) sube a un único formateador con `Context`.
- **`NumberFormat` para las cifras, recursos con placeholder para las unidades y el orden.** Nunca
  concatenar unidades en Kotlin: el traductor tiene que poder reordenar sin recompilar.
- **`<plurals>` para palabras que varían con la cantidad; `<string>` con placeholder para abreviaturas
  invariantes.** "2 horas" sí, "2h" no.
- **Un tipo compartido se refactoriza con los tests como red:** el compilador enumera el impacto, los
  tests reescritos confirman que el comportamiento sobrevive, y de paso desaparece el estado duplicado
  (`isTimedOut`) que permitía la incoherencia del widget.
