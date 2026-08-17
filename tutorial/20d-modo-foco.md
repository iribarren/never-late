# Lección 20d — Modo Foco: `BackHandler`, gestos accesibles, y diseñar fricción sin trampa

> Objetivo: usar Modo Foco (`docs/specs/2026-08-18-focus-mode.md`) como excusa para tocar cuatro cosas
> que ninguna lección anterior había tocado — qué controla de verdad un `BackHandler` y qué no; cómo se
> le da a un gesto personalizado una acción accesible equivalente; en qué de las tres capas de estado
> vive cada cosa cuando una pantalla tiene que sobrevivir a rotación, muerte de proceso y reinicio; y,
> la más rara de las cuatro, el criterio de producto para diseñar una barrera que ayuda sin construir
> una trampa.

## Conceptos que aprendes aquí

Partiendo de la lección 04 (lista de tareas y cuenta atrás), la 04c (`toggleComplete`), la 18
(navegación y accesibilidad) y la 07 (preferencias en el DataStore `user_prefs`):

1. **`BackHandler` intercepta un gesto, no la app entera.** Qué controla de verdad (el botón atrás
   *dentro* de la ventana de tu propia app) y qué no controla nunca (home, recientes, la barra de
   notificaciones) — y por qué esa distinción define el límite honesto de cualquier "modo bloqueado".
2. **Gestos personalizados con semántica accesible.** Por qué una barra de "deslizar para desbloquear"
   sin más es la trampa clásica de TalkBack, y cómo `CustomAccessibilityAction` le da al mismo gesto
   una puerta de entrada alternativa que un lector de pantalla sí puede usar.
3. **Tres capas de estado, y una que se deja sin usar a propósito.** `ViewModel`, `SavedStateHandle` y
   DataStore sobreviven a cosas distintas del ciclo de vida — y saber *cuál no usar*, y por qué, es una
   decisión tan real como saber cuál usar.
4. **Diseñar fricción sin diseñar una trampa.** El criterio de producto detrás de "la salida de
   emergencia no se puede condicionar a nada que la persona pueda olvidar, fallar al hacer, o tener que
   esperar" — y por qué, aquí, guardar el código en texto plano es la decisión *más* segura, no la menos.

---

## 1. `BackHandler`: interceptar un gesto, no un dispositivo

El nombre de cara al usuario, "Modo Foco", suena a que algo se bloquea. Lo único que de verdad se
intercepta es un gesto muy concreto: el botón/gesto atrás del sistema, y solo mientras la ventana de
esta app está en primer plano.

```kotlin
// FocusScreen.kt — FocusRoute
BackHandler(enabled = true) { viewModel.onExitPanelToggle() }
```

El único precedente que ya existía en el repo, antes de esta feature, es
`ArticlesListDetailPane.kt`, del layout de dos paneles (lección 18b):

```kotlin
// ArticlesListDetailPane.kt
val navigator = rememberListDetailPaneScaffoldNavigator<String>()
BackHandler(enabled = navigator.canNavigateBack()) {
    navigator.navigateBack()
}
```

Comparar los dos usos es instructivo. En Artículos, `enabled` es **condicional**: el back del sistema
solo se intercepta mientras hay una selección que colapsar; en cuanto no la hay, el gesto vuelve a
hacer lo de siempre (salir de la pantalla). En Modo Foco, `enabled` es **`true` sin condición**,
durante toda la vida del composable: el back nunca hace lo de siempre aquí — siempre abre (o cierra)
el panel de salida. Es el mismo mecanismo — `BackHandler` interceptando un `NavHost` que, sin él,
haría `popBackStack()` — usado con la política de gate opuesta, porque el propósito opuesto: uno deja
salir cuando ya no hay nada que colapsar, el otro nunca deja salir por esa vía sin más.

Y aquí está el límite real, el que el spec obliga a decir con esas palabras exactas: `BackHandler`
solo puede interceptar el **gesto atrás del sistema, dentro de esta ventana**. No hay ningún API en
Android que le deje a una app de terceros interceptar el botón *Home*, el selector de *Recientes*, la
barra de notificaciones o el menú de encendido — esas superficies pertenecen al sistema operativo, no
a la ventana de la app en primer plano, y ninguna de las dos formas en que se usa `BackHandler` en
este repo cambia eso.

```kotlin
// UI copy — strings.xml (ES)
"El botón Inicio y los últimos usados siguen funcionando en todo momento: esto no bloquea el dispositivo."
```

Esta frase va en el diálogo de entrada, no como nota interna. Es la diferencia entre un dispositivo de
compromiso (*commitment device*, algo que hace la salida costosa a propósito) y un quiosco (*kiosk
mode*, algo que la hace técnicamente imposible sin un permiso especial que este repo nunca pide —
`startLockTask` requiere que la app sea *device owner* o esté en la lista blanca del administrador del
dispositivo, y eso pertenece, si acaso, a una feature aparte de blindaje, no a esta). Prometer lo
segundo mientras solo se entrega lo primero es el tipo de mentira de producto que se nota la primera
vez que alguien pulsa Home sin querer y descubre que "salió" sin ningún ritual.

> **La regla transferible:** un `BackHandler` te da la última palabra sobre un gesto muy concreto, no
> sobre la app. Documentar (en el código *y* en la UI) qué gesto exactamente controlas — y decir en
> voz alta lo que no controlas — es lo que separa una feature honesta de una que promete un blindaje
> que Android no te deja construir.

---

## 2. El gesto y su doble accesible

Una barra de "deslizar para desbloquear" es un gesto de arrastre continuo — mueves el dedo, el sistema
reporta deltas de posición, tú acumulas un offset. TalkBack, cuando está activo, **intercepta los
gestos táctiles crudos** para su propio uso (deslizar para navegar entre elementos, doble toque para
activar, etc.) — así que un `Modifier.draggable` normal, sin nada más, es simplemente inalcanzable
para una persona que usa lector de pantalla. No es una limitación menor: es la pantalla entera
volviéndose imposible de abandonar para ese subconjunto de usuarios, con o sin fricción "intencionada"
de por medio.

La solución de Compose no es "quitar el gesto" — eso borraría la fricción que el producto pide a
propósito — sino darle al mismo control una **acción semántica alternativa**, algo que TalkBack sí
sabe ofrecer y ejecutar:

```kotlin
// FocusScreen.kt — FocusSlideToUnlock
Box(
    modifier = modifier
        .fillMaxWidth()
        .height(trackHeight)
        // ...
        .semantics {
            contentDescription = contentDescriptionText
            stateDescription = stateDescriptionText
            role = Role.Button
            if (enabled) {
                customActions = listOf(CustomAccessibilityAction(actionLabel) { onComplete(); true })
            }
        },
) {
    // ...
    Box(
        modifier = Modifier
            .offset { IntOffset(offsetPx.roundToInt(), 0) }
            // ...
            .then(
                if (enabled) {
                    Modifier.draggable(
                        orientation = Orientation.Horizontal,
                        state = rememberDraggableState { delta -> /* ... */ },
                        onDragStopped = { /* ... */ onComplete() /* si superó el umbral */ },
                    )
                } else {
                    Modifier
                },
            ),
    ) { /* ... */ }
}
```

Tres piezas hacen el trabajo real:

- **`customActions`** añade una entrada al menú de acciones de TalkBack ("Desbloquear") que, al
  activarse, llama exactamente a `onComplete()` — la misma función lambda que el arrastre llama al
  soltar por encima del umbral. No hay una segunda ruta de "éxito": el gesto y la acción accesible
  convergen en la misma llamada.
- **`stateDescription`** anuncia, en cada momento, *por qué* la barra está inerte cuando lo está —
  "Termina las tareas pendientes para continuar" o "Introduce el código correcto para continuar" —
  en vez de un genérico "deshabilitado" que no le dice a nadie qué hacer a continuación.
- **`role = Role.Button`** hace que la barra se anuncie y se alcance como un control interactivo
  normal para TalkBack, el navegador por teclado y el acceso por conmutador (*switch access*), no
  como una zona de dibujo sin semántica.

Y el detalle que hace que esto sea *fricción proporcional* y no un atajo: `customActions` **solo se
añade cuando `enabled` es `true`** — exactamente la misma condición que activa el `Modifier.draggable`
del arrastre. Un usuario de TalkBack no tiene una puerta trasera que un usuario con arrastre táctil no
tenga, ni al revés: los dos caminos de entrada leen la **misma** variable de estado
(`tasksSatisfied && codeSatisfied`), así que no pueden divergir en ningún caso — algo que el propio
`FocusViewModel` refuerza, porque tanto el arrastre como la acción accesible acaban llamando a la
misma función `onSlideComplete()`, que vuelve a comprobar la condición antes de hacer nada.

La prueba de que esto funciona de verdad no es una inspección visual: es un test instrumentado que
completa la salida usando **solo** acciones de semántica, nunca un gesto de arrastre —

```kotlin
// FocusExitAccessibilityTest.kt
val slideNode = composeTestRule
    .onNodeWithContentDescription(string(R.string.focus_slide_content_description))
    .fetchSemanticsNode()
val unlockAction = slideNode.config[SemanticsActions.CustomActions]
    .first { it.label == string(R.string.focus_slide_action_label) }
composeTestRule.runOnUiThread { unlockAction.action.invoke() }
```

— exactamente lo que TalkBack acaba invocando cuando una persona selecciona esa acción, nunca un
`performTouchInput` simulando un dedo. Es el único criterio de aceptación de todo el spec marcado
explícitamente como "no se envía sin él": si ese test no puede pasar, la fricción que el producto pide
se ha convertido, para un subconjunto de personas, en una pared sin puerta.

> **La regla transferible:** un gesto personalizado nunca es "accesible" por defecto — necesita una
> acción semántica *equivalente*, gateada por la misma condición que el gesto, y una prueba que la
> ejercite sin simular un dedo. La fricción del producto se conserva (activar la acción sigue siendo
> un paso deliberado bajo TalkBack); lo que desaparece es la exclusión.

---

## 3. Tres capas de estado, y la que se deja vacía a propósito

Esta pantalla tiene que sobrevivir a tres cosas distintas del ciclo de vida de Android — rotación,
muerte de proceso (el sistema mata la app en segundo plano para liberar memoria) y reinicio del
teléfono — y no todo lo que hay en pantalla necesita sobrevivir a las tres por igual. Android da tres
sitios distintos para guardar estado, cada uno con su propio alcance:

| Estado | Dónde vive | Rotación | Muerte de proceso | Reinicio |
|---|---|---|---|---|
| Que existe una sesión, cuándo empezó, el código, la lista congelada de tareas | DataStore (`user_prefs`) | ✅ | ✅ | ✅ (hasta que expira, D7) |
| Panel de salida abierto/cerrado | `FocusViewModel` | ✅ | ❌ | ❌ |
| Dígitos escritos en el código | `FocusViewModel` | ✅ | ❌ | ❌ |
| Progreso del arrastre de la barra | `FocusViewModel` | ✅ | ❌ | ❌ |
| Cuenta atrás de "revelar código" | `FocusViewModel` | ✅ | ❌ | ❌ |

Un `ViewModel` sobrevive a una rotación (Android lo conserva mientras la `Activity` se recrea) pero
**no** a que el sistema mate el proceso entero en segundo plano — en ese caso se crea uno nuevo, vacío,
la próxima vez que la app vuelve a primer plano. Ahí es donde entraría normalmente la tercera capa,
`SavedStateHandle`: un `Bundle` pequeño que el sistema sí conserva incluso a través de una muerte de
proceso, pensado para justo el tipo de estado que sería caro o imposible de reconstruir de otra forma
(el texto que alguien llevaba escrito en un formulario largo, por ejemplo).

Esta feature **no usa `SavedStateHandle` en ningún sitio** — y es la decisión más instructiva del
diseño, precisamente porque no es la ausencia de una decisión, es una decisión tomada:

```kotlin
// FocusViewModel.kt
/** [ExitPanelUiState.revealSecondsRemaining]/[ExitPanelUiState.revealedCode]'s ViewModel-private
 *  source of truth — see D5: this is deliberately ViewModel-only state, not SavedStateHandle. */
private sealed interface RevealState {
    data object Hidden : RevealState
    data class CountingDown(val secondsRemaining: Int) : RevealState
    data object Revealed : RevealState
}
```

El razonamiento, tal como lo deja el spec (D5): `SavedStateHandle` existe para estado caro de perder.
Aquí, todo lo que vive solo en el `ViewModel` son unos pocos dígitos y un arrastre a medio terminar —
perderlos cuesta segundos, no algo que dé pena reconstruir. Y hay un matiz de producto escondido
detrás de la elección técnica: que el panel de salida **vuelva a estar cerrado** después de que el
sistema mate el proceso no es una regresión que haya que compensar — es, casi con seguridad, el
comportamiento correcto. La persona vuelve a la *sesión* (eso sí sobrevive, por DataStore), no a un
ritual de salida a medio hacer que ya no recuerda haber empezado.

La sesión en sí — que existe, cuándo empezó, el código, el roster congelado (lección de dominio D1
más abajo) — sí necesita la tercera capa, pero no es `SavedStateHandle`: es DataStore, la misma
`user_prefs` que ya usan el tema o las preferencias de recordatorio (lección 07), reutilizada con tres
claves nuevas en vez de un segundo almacén. DataStore sobrevive a las tres cosas de la tabla,
incluido el reinicio del teléfono — que es exactamente lo que hace creíble el modo: si la sesión solo
viviera en el `ViewModel`, matar la app desde Recientes sería una cuarta salida silenciosa, y todo el
ritual sería teatro.

> **La regla transferible:** no elijas la capa de estado por costumbre ni por la más duradera "por si
> acaso" — pregúntate qué evento del ciclo de vida tiene que sobrevivir *esta pieza concreta de
> estado*, y qué pasa realmente si no sobrevive. Aquí la respuesta para el panel de salida fue "nada
> grave, y hasta es lo correcto" — y dejar constancia de la capa que **no** se usó, con su razón, es
> tan parte del diseño como elegir la que sí.

---

## 4. Diseñar fricción sin diseñar una trampa

Este es el criterio de producto que hace que toda la feature exista, y el spec lo dice sin rodeos:

> Un modo que exige un código para salir puede **atrapar** a la persona, y en una app para TDA/TDAH
> olvidar ese código es el caso probable, no el raro.

La respuesta no es "quitar la fricción" — la fricción es el producto, es literalmente lo que alguien
pidió — sino trazar una línea muy concreta entre fricción que ayuda y fricción que se convierte en
una trampa:

> La salida de emergencia nunca puede condicionarse a algo que la persona pueda **olvidar** (un
> código), **fallar al ejecutar** (un gesto), o tener que **esperar** (un temporizador).

Por eso el panel de salida ofrece siempre dos caminos, no uno:

```kotlin
// FocusViewModel.kt
fun onSlideComplete() {
    val state = uiState.value
    if (state !is FocusUiState.Content || !state.exitPanel.slideEnabled) return
    // ... termina como "completed"
}

fun onAbandonConfirm() {
    _showAbandonConfirm.value = false
    viewModelScope.launch {
        userPreferencesRepository.endFocusSession()
        _exitEvents.send(FocusExitEvent.Abandoned)
    }
}
```

`onSlideComplete()` **comprueba una condición** antes de hacer nada — el ritual digno solo funciona
cuando las tareas y el código están satisfechos, a propósito. `onAbandonConfirm()` **no comprueba
absolutamente nada** — ni tareas, ni código, ni tiempo transcurrido. Un tap en "Abandonar sesión" más
una confirmación, siempre disponibles, siempre habilitados, desde el primer momento en que el panel se
puede abrir. Si alguna vez una revisión futura le añade una condición, un retraso o un gesto a ese
botón, la feature se ha roto, aunque los tests sigan en verde — es la única regla de todo el spec que
se formula tan tajante.

Hay un tercer camino, más suave, y su forma revela el mismo criterio aplicado con más matiz: "No
recuerdo el código" arranca una cuenta atrás visible de 60 segundos, tras la cual el código se muestra
en texto plano.

```kotlin
fun onForgetCodeClick() {
    if (_reveal.value !is RevealState.Hidden) return
    revealJob?.cancel()
    revealJob = viewModelScope.launch {
        var secondsRemaining = CODE_REVEAL_COUNTDOWN_SECONDS
        while (secondsRemaining > 0) {
            _reveal.value = RevealState.CountingDown(secondsRemaining)
            delay(1_000)
            secondsRemaining--
        }
        _reveal.value = RevealState.Revealed
    }
}
```

¿Por qué es aceptable temporizar *este* camino cuando la regla de arriba prohíbe temporizar la salida?
Porque no es *la* salida — es una salida *además de* "Abandonar sesión", nunca la única. Un camino con
temporizador solo es seguro cuando, al lado, hay uno sin él; si "revelar código" fuera la única forma
de salir sin completar el ritual, sería exactamente el tipo de trampa que la regla prohíbe, con un
cronómetro puesto encima. Y el propio cronómetro está calibrado para lo que es — 60 segundos, no cinco
minutos, "porque la espera no es un control de seguridad (D2), solo tiene que ser lo bastante larga
para no ser el camino por defecto".

Esa frase, "la espera no es un control de seguridad", es la puerta a la decisión más contraintuitiva
de todo el spec: el código de salida se guarda **en texto plano**.

```kotlin
// UserPreferencesRepository.kt — UserPreferences.focusSession
/**
 * ... [FocusSession.exitCode] is deliberately plaintext (D2 of the spec — this is
 * friction the person chose for themselves, not a credential; never hash this, never move it to
 * EncryptedTokenStorage). ...
 */
```

El instinto de seguridad habitual diría "un código nunca va en texto plano, hay que hashearlo". Aquí
hacerlo sería un error, y no por descuido: un hash es una función de un solo sentido *a propósito* —
no hay forma de recuperar el texto original a partir de él. Si el código estuviera hasheado, la
función de "revelar código" del párrafo anterior sería sencillamente imposible de construir, porque no
habría nada que mostrar. Hashear intercambiaría una propiedad de seguridad real (que nadie pueda leer
el código) por ninguna ganancia real, a cambio de borrar la única salida de emergencia con temporizador
que el diseño necesita — y la razón de fondo es que **atacante y protegido son la misma persona**: lo
único que este código resiste es el propio impulso de la persona, treinta segundos después de haberlo
fijado. Cualquiera que pueda leer `user_prefs` ya tiene el teléfono desbloqueado en la mano y podría,
sencillamente, pulsar Home.

> **La regla transferible, la más incómoda de las cuatro:** "más seguro" no es un eje único. Hashear
> este código sería objetivamente *más difícil de leer* y objetivamente *peor producto*, porque
> confunde dos preguntas distintas — "¿puede alguien más leer esto?" (aquí, irrelevante: el atacante y
> el protegido son la misma persona) y "¿puede esto atrapar a la persona a la que se supone que
> ayuda?" (aquí, la pregunta real). Diseñar fricción a propósito exige, primero, decidir contra quién
> es la fricción — y aquí la respuesta honesta es "contra el impulso de dentro de treinta segundos",
> nunca "contra un atacante externo".

---

## Lo que te llevas

- **Un `BackHandler` intercepta un gesto muy concreto** (el back del sistema, dentro de tu ventana),
  nunca la app entera ni el dispositivo — documentarlo, en código y en la copy de cara al usuario, es
  lo que evita prometer un blindaje que Android no deja construir sin un permiso especial que este
  repo nunca pide.
- **Un gesto personalizado necesita una acción semántica equivalente**, no solo un `contentDescription`
  — `CustomAccessibilityAction` gateada por la misma condición que el gesto, y una prueba que la
  ejercite sin simular un dedo, son lo que separa "accesible" de "parece accesible".
- **No todo el estado necesita la misma capa.** `SavedStateHandle` existe para lo caro de perder;
  cuando perder algo cuesta segundos — o cuando perderlo es, de hecho, lo correcto — dejarlo solo en
  el `ViewModel` es una decisión, no un descuido, y merece quedar escrita igual que la capa que sí se
  usa.
- **La fricción que ayuda y la trampa se distinguen por una regla precisa, no por una sensación**: la
  salida de emergencia nunca se condiciona a algo que se pueda olvidar, fallar al ejecutar, o tener
  que esperar. Un camino con temporizador solo es seguro si, al lado, hay uno sin él.
- **"Más seguro" no es un eje único.** Antes de "hashear" o "cifrar" algo por reflejo, pregúntate
  contra quién es la protección — a veces la respuesta correcta es guardarlo en texto plano, a
  propósito, y decir por qué.
