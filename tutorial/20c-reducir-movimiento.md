# Lección 20c — Reducir movimiento: lo que el framework ya te da, y dónde se acaba

> Objetivo: entender por qué Compose **ya respeta** el ajuste de Accesibilidad → *Quitar animaciones*
> en casi todas las animaciones de la app sin que hayamos escrito una sola línea para ello — y, sobre
> todo, entender **por qué exactamente una cosa no lo respeta** (el tick de un segundo del
> `CountdownTicker`, lección 04) y qué hay que hacer al respecto. Es una lección corta y rara a
> propósito: el primer entregable de esta feature fue descubrir que casi no hacía falta.

## Conceptos que aprendes aquí

Partiendo de la lección 17 (animaciones con `animateItem`/`animate*AsState`), la 19 (la barra de
progreso animada de la tarjeta) y la 20b (la decisión de mantener el tick de 1 s del `CountdownTicker`,
cuya justificación esta lección acota):

1. **`MotionDurationScale`**: un `CoroutineContext.Element` que el `Recomposer` de la ventana instala
   leyendo `Settings.Global.animator_duration_scale`, y por qué viajar por el *contexto de la
   corrutina* (en vez de por un `CompositionLocal` o un parámetro) es lo que hace que cualquier
   animación, a cualquier profundidad, obedezca sin que nadie la conecte a mano.
2. **Animar vs. refrescar**: por qué una recomposición periódica (un `delay` en un bucle) **no es una
   animación**, y por tanto ningún `MotionDurationScale` la va a frenar nunca. La distinción, en su
   forma más pura.
3. **Por qué `LocalAccessibilityManager` no sirve para esto** — y por qué saberlo de antemano ahorra
   media hora de búsqueda por el sitio equivocado.
4. **Derogar una instrucción escrita, con conocimiento de causa**: la lección 20b dejó dicho *"no
   bajes el tick de 1 s"*, con su razón adjunta. Esta lección explica cuándo esa razón deja de aplicar
   y cómo se acota una regla sin borrarla.

---

## 1. La sorpresa: casi todo ya funciona

El punto de partida de esta feature no fue una `TODO` en el código: fue una pregunta de producto —
*"¿la app respeta 'reducir movimiento' del sistema?"* — y la respuesta, tras investigar antes de
escribir nada, fue **"la mayor parte, sí, ya"**.

Jetpack Compose expone `MotionDurationScale`:

```kotlin
// androidx.compose.ui — resumen conceptual, no el código real de la librería
interface MotionDurationScale : CoroutineContext.Element {
    val scaleFactor: Float
}
```

Es un `CoroutineContext.Element`: un valor que viaja **colgado del contexto de la corrutina**, no de un
parámetro ni de un `CompositionLocal`. El `Recomposer` de la ventana (el que Compose crea para tu
`Activity` en `setContent`) instala uno concreto, cuyo `scaleFactor` sale de
`Settings.Global.animator_duration_scale` — el mismísimo ajuste que activa *Quitar animaciones* — y lo
mantiene sincronizado con un `ContentObserver`.

¿Por qué importa que viaje por el **contexto** y no por un parámetro? Porque `animateFloatAsState`,
`Animatable.animateTo`, `Modifier.animateItem()` y `AnimatedPane` están todos implementados, en algún
punto de su pila de llamadas, con una corrutina que **lee ese contexto** para decidir cuánto dura la
animación. Ninguno de ellos necesita que tú le pases el `scaleFactor`: lo heredan automáticamente,
igual que heredan el `Dispatcher` o el `Job` de quien los lanzó. Es el mismo mecanismo por el que un
`Context` de corrutina "atraviesa" funciones `suspend` sin aparecer en ninguna firma — aquí, en vez de
para cancelación o para el hilo, se usa para accesibilidad.

**Resultado verificado, no asumido** (inspeccionando los `.aar` resueltos del proyecto — ver el spec
de esta feature para la lista completa): la lista de tareas y la de artículos (`animateItem()`), la
barra de progreso de la tarjeta (`animateFloatAsState`, lección 19) y los cuatro `AnimatedPane` del
layout de dos paneles de Artículos (lección 18b) **ya obedecen** el ajuste, sin una sola línea nueva.

> **La lección transferible aquí no es sobre animaciones: es sobre investigar antes de implementar.**
> El primer instinto ante "hay que respetar X" es escribir código que respete X. El trabajo real, antes
> de eso, es comprobar cuánto respeta X *ya* la plataforma en la que estás — porque cada línea que
> escribes de más es una línea que puede desincronizarse de lo que el framework decide mañana.

---

## 2. Dónde se acaba la magia: una recomposición no es una animación

Si casi todo ya funciona, ¿qué queda por hacer? Exactamente una cosa, y es la más instructiva:
`CountdownTicker.kt` (lección 04).

```kotlin
fun countdownTicker(intervalMillis: Long = TICK_INTERVAL_MILLIS): Flow<Unit> = flow {
    while (true) {
        emit(Unit)
        delay(intervalMillis)
    }
}
```

Esto no es una animación. Es un **reloj**: emite una señal cada segundo para que `TasksViewModel`
recalcule el tiempo restante de cada tarea y la pantalla se recomponga. Ningún valor se interpola aquí
— no hay un `0.3f` que avanza hacia `0.8f` a lo largo de varios frames. Hay un `delay` en un bucle, sin
más.

Y ese es exactamente el motivo por el que `MotionDurationScale` **no puede tocarlo**: el mecanismo
entero (`animateFloatAsState`, `Animatable`, `Transition`) existe para escalar la *duración de una
interpolación*. Si no hay interpolación, no hay nada que escalar. Es tan literal como parece: mira el
código de `countdownTicker` — no hay ningún `Animatable`, ningún `Transition`, ninguna llamada que
pudiera leer el contexto de corrutina en busca de una escala. `delay(1_000)` es `delay(1_000)`,
lo diga la configuración de accesibilidad lo que diga.

Con movimiento reducido, esto deja de ser un detalle inocuo. La barra de progreso de la lección 19
(`animateFloatAsState`) ya está colapsando a saltos instantáneos — **no hay drenaje suave que
proteger**. Así que la pantalla de Tareas sigue recomponiéndose entera 60 veces por minuto, sin comprar
nada a cambio: solo parpadeo y batería, exactamente lo que la persona pidió que dejara de pasar.

> **La distinción que te llevas:** "animar" es interpolar un valor a lo largo del tiempo, y ese proceso
> puede acelerarse, ralentizarse o colapsarse. "Refrescar" es decidir *cuándo* volver a mirar un dato
> que cambia por sí solo (aquí, el reloj de pared). Un `MotionDurationScale` gobierna lo primero. Lo
> segundo solo lo gobierna quien escribió el bucle — en este caso, nosotros.

---

## 3. El callejón sin salida: `LocalAccessibilityManager`

Antes de llegar a `Settings.Global` directamente, es fácil (y tentador) buscar el "sitio de Compose
para preguntar por accesibilidad", que ya existe para otra cosa:

```kotlin
val accessibilityManager = LocalAccessibilityManager.current
accessibilityManager?.calculateRecommendedTimeoutMillis(...)  // esto SÍ existe...
accessibilityManager?.isReduceMotionEnabled  // ...pero esto NO
```

`AccessibilityManager` en Compose (`androidx.compose.ui.platform`) expone exactamente un método:
`calculateRecommendedTimeoutMillis`, pensado para decidir cuánto tiempo dejar visible un mensaje
temporal (un `Snackbar`, por ejemplo) según si el usuario tiene activado *texto grande* o *toque
prolongado*. No expone nada sobre la escala de animación.

Documentarlo aquí — igual que en el KDoc de `MotionSettings.kt` — no es relleno: es evitar que la
próxima persona pierda media hora en la misma búsqueda razonable que no lleva a ningún sitio. La señal
correcta sigue siendo la de siempre: `Settings.Global.ANIMATOR_DURATION_SCALE`, leída directamente vía
`contentResolver` — el mismo valor que `WindowRecomposer` lee para construir el `MotionDurationScale`
que el resto de la app ya disfruta gratis.

---

## 4. Derogar una regla escrita, en voz alta

La lección 20b cerró con esta frase, documentada en el KDoc de `CountdownTicker`:

> *"La decisión —documentada en el KDoc del `CountdownTicker`— es mantener el tick de 1 s, cuya
> justificación simplemente se traslada del texto a la barra."*

Es una instrucción real, no una nota de paso: **no bajes esta cadencia ni la desacoples del refresco
del texto**, porque la barra de progreso de la lección 19 la necesita para drenarse suavemente. Esta
feature la incumple — baja la cadencia bajo movimiento reducido — y el spec exige decirlo **con esas
palabras**, no cambiarlo por la espalda.

¿Cómo se deroga una regla con conocimiento de causa, en vez de simplemente ignorarla? Mirando **la
razón que la propia regla llevaba adjunta**. La instrucción de 20b no decía "el tick es 1 s porque sí";
decía "el tick es 1 s *porque* la barra necesita drenarse suave". Esa razón tiene una condición
implícita: que la barra pueda, de hecho, drenarse suave. Bajo movimiento reducido, esa condición ya no
se cumple — `animateFloatAsState` ya ha colapsado a un salto instantáneo, con o sin nuestro tick.

Así que la regla no se **derriba**: se **acota**. El KDoc de `CountdownTicker.kt` no borra el párrafo
de la 20b — lo conserva palabra por palabra — y le añade una excepción explícita: *"mantener el tick de
1 s, salvo cuando el propio motivo por el que se mantiene ha dejado de existir"*. Quien llegue después
vía `git blame` encuentra la regla, la excepción y el razonamiento, en el mismo sitio, en vez de un
número que cambió sin decir por qué.

> **La moraleja transferible:** una regla escrita con su razón adjunta se puede acotar con seguridad,
> porque la razón te dice exactamente cuándo deja de aplicar. Una regla escrita sin razón solo se puede
> obedecer o romper a ciegas. Esto es, en el fondo, la misma disciplina que en la lección 20b hizo que
> *no* se bajara el tick sin comprobar quién más lo usaba — aquí se aplica a la inversa, para bajarlo
> con seguridad cuando toca.

### El acotamiento en código: una función pura, testeable sin Android

```kotlin
fun tickIntervalFor(reduceMotion: Boolean, tasks: List<Task>, now: Long): Long {
    if (!reduceMotion) return TICK_INTERVAL_MILLIS

    val soonestExpiry = tasks
        .asSequence()
        .filter { it.isRunning }
        .mapNotNull { it.timerEndsAt }
        .minOrNull()
        ?: return REDUCED_MOTION_TICK_INTERVAL_MILLIS

    val millisUntilExpiry = soonestExpiry - now
    return millisUntilExpiry.coerceIn(TICK_INTERVAL_MILLIS, REDUCED_MOTION_TICK_INTERVAL_MILLIS)
}
```

Con movimiento reducido, la cadencia por defecto sube a un minuto — la misma granularidad que ya tiene
el propio texto de la cuenta atrás (`2h 38m`, lección 20b), así que texto y barra nunca pueden llegar a
contradecirse. Pero hay un matiz que casi se escapa: `TasksViewModel.autoPauseTimedOut` usa este mismo
tick para **escribir en la base de datos** cuando una tarea llega a cero. Un minuto plano podría
retrasar esa escritura — y el estado "Tiempo agotado" en pantalla — hasta 59 segundos. Reducir
movimiento nunca debe hacer la app menos **correcta**, solo menos animada. Por eso la función recorta
("clampa") la cadencia hasta el instante exacto en que la tarea más próxima vence, con un suelo de 1 s
para que una tarea ya vencida no produzca un bucle a cadencia cero. Al ser una función pura de
`(¿reducir?, lista de tareas, ahora)`, se prueba entera en la JVM, sin emulador — la misma disciplina de
la lección 17/19.

---

## Lo que te llevas

- **`MotionDurationScale` viaja por el contexto de la corrutina**, no por un parámetro ni un
  `CompositionLocal` — por eso cualquier animación construida sobre `Animatable`/`Transition` lo obedece
  sin que nadie la conecte a mano.
- **Animar no es lo mismo que refrescar.** Solo lo primero puede escalarse; un `delay` en un bucle no
  tiene nada que interpolar, así que ningún ajuste de accesibilidad lo va a frenar por sí solo.
- **`LocalAccessibilityManager` no expone esto** — solo `calculateRecommendedTimeoutMillis`. Saber
  dónde no buscar es tan útil como saber dónde buscar.
- **Investigar antes de implementar** puede reducir una feature a una fracción de lo que parecía. El
  entregable más valioso a veces es el inventario, no el código.
- **Derogar una regla escrita se hace citándola, explicando por qué su propia razón dejó de aplicar, y
  acotándola en vez de borrarla** — así el historial cuenta la historia completa, no solo el resultado.
