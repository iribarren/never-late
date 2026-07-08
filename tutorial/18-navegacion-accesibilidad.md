# Lección 18 — Barra de navegación inferior y accesibilidad

> Objetivo: cambiar el **modelo de navegación** de la app y, de paso, hacer una **revisión de
> accesibilidad** transversal. Hasta ahora las tres secciones principales (Tareas, Artículos, Ajustes)
> se alcanzaban desde una **pantalla-hub `Home`**: aterrizabas en Home y pulsabas una fila para
> "entrar" en una sección, y volvías con el botón atrás del sistema. Era un modelo *hub-and-spoke*
> (centro y radios). En esta lección lo sustituimos por el patrón estándar de Material 3: una
> **`NavigationBar`** inferior siempre visible, con las tres secciones como **pestañas** entre las que
> se salta lateralmente. Además: **confirmamos el cierre de sesión** con un diálogo (como ya hacíamos
> al borrar una tarea) y repasamos **contentDescription, tamaños de toque y fuente dinámica** en las
> pantallas existentes.
>
> Es una lección **arquitectónica** — toca cómo está montada la navegación entera — pero con una regla
> que ya conoces bien: **extender, no duplicar**. El `NavController`, el `object Routes` y la lógica de
> arranque **ya existían** (lección 13); aquí solo envolvemos el `NavHost` en un `Scaffold` con
> `bottomBar` y respetamos la **puerta de auth de tres caras** (`LoggedOut`/`Guest`/`LoggedIn`) sin
> tocarla.

## Conceptos que aprendes aquí

Partiendo de la lección 13 (la puerta de auth de tres caras en `AppNavHost` y por qué `Guest` y
`LoggedIn` son ramas `when` **separadas a propósito**) y de la 07/17 (Ajustes, y el patrón de diálogo
`AlertDialog` para confirmar el borrado de una tarea):

- **Navegación de nivel superior (lateral) con `NavigationBar`.**
  - `NavigationBar` + `NavigationBarItem`: la barra inferior de Material 3 y sus ítems.
  - Cómo se diferencia del `navigate()` de tipo *push* que veníamos usando: una pestaña es un cambio
    **lateral** entre pantallas hermanas, no un "entrar" que apila.
  - El **slot `bottomBar` del `Scaffold`** y cómo pasar su `innerPadding` al `NavHost`.
- **Mantener la pestaña activa sincronizada con la ruta.**
  - `currentBackStackEntryAsState()`: observar **reactivamente** el destino actual del `NavController`.
  - `NavDestination.hierarchy` para decidir qué pestaña está seleccionada.
  - **Derivar** el estado seleccionado de la navegación en lugar de **duplicarlo** en un `remember`
    que podría desincronizarse.
- **El idioma de cambio de pestaña:** `launchSingleTop`, `saveState`/`restoreState` y
  `popUpTo(startDestination)` — qué previene cada uno (destinos duplicados, estado perdido, back stack
  que crece sin fin).
- **Accesibilidad a fondo** (mapea a `conceptos-pendientes.md` §7): el árbol de `semantics`,
  `contentDescription` frente a decorativo `null`, el **tamaño mínimo de toque de 48 dp**
  (`minimumInteractiveComponentSize`), los roles/estado seleccionado, y por qué hay que probar los
  layouts con **fuente grande**.
- **Coexistencia con la puerta de auth:** por qué la barra vive **solo dentro de `MainAppNavHost`** y
  nunca en la puerta de login, sin fusionar las ramas `when` que la 13 mantiene separadas.
- **Reutilizar un patrón de diálogo** para proteger una acción destructiva (el logout), reforzando el
  `DeleteTaskDialog` que ya existe en el código.

---

## 1. El punto de partida: de hub a pestañas

Antes de esta lección, `MainAppNavHost` (la parte de `AppNavHost.kt` que se muestra a un usuario
`Guest` o `LoggedIn`) renderizaba directamente un `NavHost` cuyo `startDestination`, para un usuario ya
"onboarded", era `Routes.HOME`. `HomeScreen` era una pantalla con una lista de tarjetas ("Tareas",
"Artículos") y un icono de ajustes arriba a la derecha. Pulsabas una tarjeta → `navigate(Routes.TASKS)`
→ y volvías con el botón atrás.

Ese hub sobra en cuanto hay una barra inferior permanente: con ella, las tres secciones están siempre a
**un toque**, sin pantalla intermedia. Así que la **primera decisión** de la feature (confirmada en el
spec) fue **retirar `Home`**: borramos `HomeScreen.kt`, `HomeViewModel.kt` y `HomeRoute`, quitamos
`Routes.HOME`, y **Tareas** pasa a ser el destino de aterrizaje.

Fíjate en el detalle del `startDestination`, que sigue siendo asíncrono (se lee de disco vía DataStore,
igual que en la 13):

```kotlin
val startDestination = when {
    !preferences.onboarded -> Routes.ONBOARDING
    openTasksOnStart -> Routes.TASKS
    else -> Routes.TASKS
}
```

Las dos últimas ramas ahora **coinciden** (Tareas), porque Home ya no existe. ¿Por qué no las fusiono
en una sola? Porque `openTasksOnStart` (el atajo del widget de la lección 05) es una **costura
documentada y testeable**: `MainActivity` lo sigue calculando y pasando, y si una feature futura
reintroduce más de un destino "ya onboarded", el sitio donde decidirlo ya está aquí, explícito. Retirar
Home no debía significar borrar esa costura. Onboarding, como siempre, gana: pulsar el widget nunca
puede saltarse el onboarding.

> **Idea de diseño.** "Retirar una pantalla" no es solo borrar su archivo: es quitar su ruta, sus
> strings (`home_*`, en las dos locales), su entrada en el `AppViewModelFactory` y cualquier referencia
> en KDoc. El compilador te ayuda con casi todo; los strings y los comentarios, no. Por eso forma parte
> del checklist de documentación.

---

## 2. `Scaffold` con `bottomBar`: dónde vive la barra

Un `Scaffold` de Material 3 tiene *slots*: `topBar`, `bottomBar`, `floatingActionButton`,
`snackbarHost`... y un `content` que recibe un `innerPadding`. Ya usábamos `Scaffold` con `topBar` en
casi todas las pantallas; aquí lo usamos por primera vez con `bottomBar`, y **envolviendo el propio
`NavHost`**:

```kotlin
Scaffold(
    bottomBar = {
        // Visibilidad por ruta: solo las tres secciones de nivel superior muestran la barra.
        if (currentRoute in TOP_LEVEL_ROUTES) {
            MainBottomBar(navController = navController)
        }
    },
) { innerPadding ->
    NavHost(
        navController = navController,
        startDestination = startDestination,
        modifier = Modifier.padding(innerPadding),   // el contenido no se dibuja bajo la barra
    ) {
        // ...las composables de cada destino...
    }
}
```

Dos cosas importantes:

1. **`Modifier.padding(innerPadding)` en el `NavHost`.** El `Scaffold` reserva espacio para la barra y
   te lo comunica como `innerPadding`. Si no lo aplicas, el contenido de la pantalla se dibujaría
   **debajo** de la barra. Es el mismo `innerPadding` que ya pasábamos al `Column` en Ajustes, solo que
   ahora lo recibe el `NavHost` entero.
2. **El `Scaffold` vive *dentro* de `MainAppNavHost`.** No en `AppNavHost` (que decide qué grafo
   mostrar según `authState`), ni en `AuthGateNavHost` (la puerta de login). Por eso la barra **nunca**
   aparece en el login: la puerta de auth es otro grafo que ni siquiera tiene `Scaffold` con
   `bottomBar`. Esto es lo que el spec llama "coexistencia con la puerta de auth", y es la razón de que
   **no toquemos** el `when(authState)` de la 13.

### Visibilidad por ruta

La barra no se muestra en **todas** las pantallas, solo en las tres de nivel superior. Las pantallas
secundarias (Detalle de artículo, Editar tarea, Login/Registro desde Ajustes) son de pantalla
completa: se merecen toda la altura y no tienen una pestaña propia. Definimos el conjunto de rutas
"top-level" una sola vez:

```kotlin
private val TOP_LEVEL_ROUTES = setOf(Routes.TASKS, Routes.ARTICLES, Routes.SETTINGS)
```

Y en el `bottomBar` comprobamos `if (currentRoute in TOP_LEVEL_ROUTES)`. ¿De dónde sale
`currentRoute`? De observar el back stack **reactivamente** (siguiente sección). Fíjate en que las
rutas con argumento — `articleDetail/{articleId}`, `taskEdit/{taskId}` — tienen un sufijo, así que una
comparación exacta contra las tres rutas base las excluye limpiamente: nunca están "en el conjunto".

---

## 3. La pestaña activa: derivar, no duplicar

El error clásico al montar una barra de pestañas es guardar un `var selectedTab by remember {
mutableStateOf(0) }` y actualizarlo a mano en cada `onClick`. Funciona… hasta que la navegación cambia
por otra vía (un `popBackStack`, un *deep link*, el botón atrás) y el índice guardado **miente**: la
pantalla es una y la pestaña resaltada es otra.

La solución de Navigation Compose es **derivar** el estado seleccionado del back stack real:

```kotlin
@Composable
private fun MainBottomBar(navController: NavHostController) {
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination

    NavigationBar {
        bottomNavItems.forEach { item ->
            val selected = currentDestination?.hierarchy?.any { it.route == item.route } == true
            NavigationBarItem(
                selected = selected,
                onClick = { /* ...idioma de cambio de pestaña, sección 4... */ },
                icon = {
                    Icon(
                        imageVector = item.icon,
                        contentDescription = stringResource(item.contentDescriptionRes),
                    )
                },
                label = { Text(stringResource(item.labelRes)) },
            )
        }
    }
}
```

- **`currentBackStackEntryAsState()`** convierte "el destino actual del `NavController`" en un `State`
  de Compose: cuando la navegación cambia, esto **recompone** y el `selected` se recalcula solo. Es el
  mismo principio que `collectAsStateWithLifecycle` para un `Flow`, pero para la navegación.
- **`hierarchy`** (en vez de comparar solo `destination.route`) recorre el destino **y sus grafos
  padre**. Hoy nuestro grafo es plano, así que `hierarchy` contiene poco más que el propio destino;
  pero es lo que recomienda la documentación oficial, porque el día que anides sub-grafos (un grafo
  "Tareas" con varias pantallas dentro), la pestaña "Tareas" debe seguir marcada estando en cualquiera
  de sus hijos. Escribimos el código correcto **desde ya**, aunque el beneficio sea futuro.

### Una lista de ítems, no tres bloques copiados

Los tres `NavigationBarItem` comparten forma, así que en vez de escribirlos a mano usamos una pequeña
`data class` + lista, igual que `themeOptions`/`reminderLeadMinuteOptions` en `SettingsScreen.kt`:

```kotlin
private data class BottomNavItem(
    val route: String,
    val labelRes: Int,
    val contentDescriptionRes: Int,
    val icon: ImageVector,
)

private val bottomNavItems = listOf(
    BottomNavItem(Routes.TASKS,    R.string.nav_tasks,    R.string.nav_tasks_content_description,    Icons.AutoMirrored.Filled.Assignment),
    BottomNavItem(Routes.ARTICLES, R.string.nav_articles, R.string.nav_articles_content_description, Icons.AutoMirrored.Filled.MenuBook),
    BottomNavItem(Routes.SETTINGS, R.string.nav_settings, R.string.nav_settings_content_description, Icons.Filled.Settings),
)
```

Reutilizamos los **mismos iconos** que tenía Home, para que el usuario reconozca cada sección. Y las
etiquetas y descripciones son **recursos de string** en las dos locales (`nav_tasks`, etc.): nada de
texto incrustado en código (lección 08).

---

## 4. El idioma de cambio de pestaña

Este es el trozo que hay que entender bien. Cuando pulsas una pestaña:

```kotlin
onClick = {
    navController.navigate(item.route) {
        popUpTo(navController.graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
```

Cada línea previene un problema concreto:

- **`popUpTo(findStartDestination().id) { saveState = true }`** — sin esto, saltar Tareas → Artículos →
  Ajustes → Tareas… iría **apilando** destinos en el back stack, y el botón atrás te haría recorrer
  todo el historial de pestañas antes de salir. `popUpTo(startDestination)` recorta el back stack hasta
  el destino inicial en cada salto, así que el stack no crece sin fin. El `saveState = true` **guarda**
  el estado de la pestaña que dejas (su posición de scroll, por ejemplo).
- **`launchSingleTop = true`** — si ya estás en Tareas y vuelves a pulsar la pestaña Tareas, no crea una
  **segunda** copia encima; reutiliza la que hay.
- **`restoreState = true`** — al volver a una pestaña que ya visitaste, **restaura** el estado que
  `saveState` había guardado, en vez de arrancarla de cero.

`findStartDestination()` devuelve el destino inicial del grafo (Tareas, en nuestro caso). Es la pieza
que ata `popUpTo`/`saveState`/`restoreState` entre sí: todos hablan del mismo punto de anclaje.

> **Por qué importa el detalle.** Equivocarte en cualquiera de estas tres opciones produce bugs sutiles
> y molestos: pestañas duplicadas, scroll que se pierde al cambiar de sección, o un back stack que no
> se vacía nunca. Por eso el test de UI (sección 7) cubre exactamente esto.

---

## 5. Las pantallas de nivel superior pierden su flecha atrás

Tareas, Artículos y Ajustes tenían, cada una, un `TopAppBar` con una **flecha atrás**
(`navigationIcon` → `onBack` → `popBackStack`), porque se llegaba a ellas *empujándolas* desde Home.
Como pestañas ya **no** deben mostrar flecha atrás: no hay un "atrás" al que ir; se cambia de sección
lateralmente con la barra.

La solución más limpia es hacer que el callback de atrás sea **opcional**:

```kotlin
// Antes:  onBack: () -> Unit
// Ahora:  onBack: (() -> Unit)? = null

navigationIcon = {
    // Feature 18: como pestaña top-level, onBack es null y este slot queda vacío.
    if (onBack != null) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = /* ... */)
        }
    }
}
```

En `AppNavHost` pasamos `onBack = null` a las tres pantallas top-level, y **mantenemos** un `onBack`
real (que hace `popBackStack`) en las secundarias: Detalle de artículo y Editar tarea siguen teniendo
su flecha. Un mismo composable sirve para los dos usos —pestaña o pantalla empujada— según reciba
`null` o una lambda. Es *state hoisting* de siempre, aplicado al gesto de retroceso.

---

## 6. Confirmar el logout con un `AlertDialog`

Borrar una tarea ya pedía confirmación (el `DeleteTaskDialog` de la lección 04/17). Cerrar sesión, en
cambio, era un `TextButton` directo… y desde la lección 13 el logout es **destructivo**: borra las
tareas y el outbox locales al salir. Un toque accidental te vacía el dispositivo. Así que lo protegemos
con el **mismo patrón**:

```kotlin
if (uiState.authState is AuthState.LoggedIn) {
    var showLogoutConfirm by remember { mutableStateOf(false) }

    TextButton(onClick = { showLogoutConfirm = true }) {
        Text(stringResource(R.string.settings_logout_button))
    }

    if (showLogoutConfirm) {
        LogoutConfirmDialog(
            onConfirm = {
                showLogoutConfirm = false
                onLogoutClick()          // la acción real de SettingsViewModel.logout()
            },
            onDismiss = { showLogoutConfirm = false },
        )
    }
}
```

```kotlin
@Composable
private fun LogoutConfirmDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_logout_confirm_title)) },
        text = { Text(stringResource(R.string.settings_logout_confirm_message)) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(R.string.settings_logout_confirm_button)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_logout_cancel_button)) }
        },
    )
}
```

Es **literalmente** la misma forma que `DeleteTaskDialog`, reusada para otra acción destructiva:
`AlertDialog` con título, mensaje, botón de confirmar y botón de descartar. El estado
`showLogoutConfirm` es UI pura y efímera, así que vive en un `remember` local, **no** en el
`SettingsViewModel` — exactamente igual que `showDeleteConfirm` en `TasksScreen`. El mensaje avisa de
que se cerrará la sesión y se borrarán las tareas locales, para que el usuario decida con la
información delante.

> **Reutilizar un patrón** no es copiar y pegar sin pensar: es reconocer que "confirmar una acción
> destructiva" ya tenía una solución en el proyecto y aplicarla igual, para que el usuario encuentre un
> comportamiento **coherente** (borrar tarea y cerrar sesión se sienten iguales).

---

## 7. Accesibilidad a fondo (§7 de `conceptos-pendientes.md`)

Una barra de navegación es una superficie de accesibilidad de primera: etiquetas, estado
seleccionado, tamaños de toque. Aprovechamos la feature para un **repaso transversal**. Los conceptos:

- **El árbol de `semantics`.** Compose no expone tus `Text`/`Icon` a TalkBack directamente: construye
  un árbol paralelo de *semántica* (rol, etiqueta, estado seleccionado, acciones). Casi todo se rellena
  solo (`Text` aporta su texto, `NavigationBarItem` aporta su rol y su estado `selected`), pero cuando
  un control es solo un icono, **tú** tienes que dar la etiqueta.
- **`contentDescription` frente a `null` decorativo.** Un icono que *es* el control (la papelera de
  borrar, los iconos de la barra) necesita `contentDescription`. Un icono meramente **decorativo** que
  acompaña a un texto que ya lo describe (el icono de cada tarjeta de sección en Ajustes) lleva
  `contentDescription = null` **a propósito**, para que el lector de pantalla no lo lea dos veces. Las
  dos elecciones son correctas; lo incorrecto es no decidir.
- **`mergeDescendants` y roles.** Cuando varias piezas forman **un** control (una fila con radio +
  etiqueta), se fusionan en un solo nodo semántico con un `Role` (p. ej. `Role.RadioButton`), para que
  se anuncie como "opción, 1 de 3" y no como tres nodos sueltos. Ya lo hacíamos en Ajustes con
  `selectable(..., role = Role.RadioButton)` dentro de un `selectableGroup()`; la revisión confirma que
  sigue correcto.
- **Tamaño mínimo de toque: 48 dp.** Un objetivo táctil más pequeño que ~48 dp es difícil de acertar
  para dedos grandes o motricidad reducida. `IconButton`, `FloatingActionButton` y `NavigationBarItem`
  ya reservan 48 dp; el `Button` por defecto de Material mide 40 dp de alto. En el repaso encontramos
  **un** objetivo por debajo del mínimo: el botón de acción del estado vacío/error (`MessageState`, de
  la lección 17), y le aplicamos:

  ```kotlin
  Button(
      onClick = onAction,
      modifier = Modifier.minimumInteractiveComponentSize(),   // fuerza el mínimo de 48 dp
  ) { /* ... */ }
  ```

  `minimumInteractiveComponentSize()` amplía el **área táctil** al mínimo recomendado sin agrandar el
  dibujo del botón. El resto de controles ya cumplían, así que **no** tocamos nada más: una revisión de
  accesibilidad son retoques quirúrgicos, no un rediseño.
- **Fuente dinámica (texto grande).** El sistema deja subir el tamaño de letra; hay que comprobar que
  los layouts **refluyen** sin recortar texto ni esconder controles. La disciplina que ya aplicábamos
  —contenedores desplazables donde el contenido puede pasarse de alto (Ajustes usa
  `verticalScroll`)— es justo lo que salva estos casos. Si algo estructural no refluyera, se anota como
  seguimiento en vez de agrandar el alcance de esta feature.

### Tests

Los tests de esta feature son de **UI** (es comportamiento de pantalla), con `createComposeRule`, como
los de las lecciones anteriores:

- **Confirmación de logout** (`SettingsScreenTest`): pulsar "Cerrar sesión" **abre el diálogo** y **no**
  cierra la sesión de inmediato; confirmar invoca `onLogoutClick`; cancelar cierra el diálogo sin
  invocarlo. Un detalle fino: los strings del botón, del título y del botón de confirmar del diálogo son
  **el mismo texto** ("Cerrar sesión"), así que seleccionamos el botón de confirmar acotando al diálogo
  con `isDialog()` + `hasClickAction()`, no por texto (que sería ambiguo).
- **Sin flecha atrás como pestaña** (`SettingsScreenTest`/`TasksScreenTest`/`ArticlesScreenTest`):
  con `onBack = null` la flecha atrás **no existe**; con `onBack = { }` (uso secundario) sí se muestra.

> Nota sobre el alcance de los tests: la barra en sí vive dentro de composables `private` de
> `MainAppNavHost`, cuyo test de extremo a extremo exigiría falsear toda la pila de repositorios. Por
> eso los tests se centran en lo aislado y de alto valor (el diálogo de logout y la flecha atrás); el
> idioma de cambio de pestaña queda documentado y verificado a mano en la app.

---

## 8. Lo que NO cambió (y por qué)

- **La puerta de auth de tres caras** (`AppNavHost`, lección 13): intacta. `Guest` y `LoggedIn` siguen
  siendo ramas `when` **separadas**, porque de esa separación depende que, al iniciar sesión un
  invitado, se componga un `MainAppNavHost` nuevo y su `LaunchedEffect` **drene el outbox** (adopción de
  tareas de invitado). Fusionarlas rompería ese disparador. El `Scaffold` de esta lección se metió
  estrictamente **dentro** de `MainAppNavHost`, sin tocar ese `when`.
- **Backend, contrato de API, versión de base de datos, permisos, dependencias:** nada. `NavigationBar`
  y `NavigationBarItem` ya venían con Material 3; `currentBackStackEntryAsState`, `findStartDestination`
  y `hierarchy` ya venían con Navigation Compose.
- **La lógica de arranque** (onboarding-primero, `openTasksOnStart` del widget): preservada; solo cambió
  su destino "ya onboarded" de Home a Tareas.

## Resumen

Cambiamos el **modelo de navegación** de un hub con back-stack a **pestañas laterales** con una
`NavigationBar` inferior, envolviendo el `NavHost` en un `Scaffold` con `bottomBar` **dentro** de
`MainAppNavHost` para no romper la puerta de auth de la 13. La pestaña activa se **deriva** del back
stack (`currentBackStackEntryAsState` + `hierarchy`), y el salto entre pestañas usa el idioma canónico
`popUpTo(startDestination){saveState} + launchSingleTop + restoreState`. Retiramos el hub `Home`
(Tareas es el nuevo aterrizaje), hicimos opcional la flecha atrás de las pantallas top-level, protegimos
el logout con un `AlertDialog` reutilizando el patrón de borrado, y repasamos accesibilidad
(contentDescription, 48 dp con `minimumInteractiveComponentSize`, semántica y fuente dinámica). Todo
**extendiendo** estructuras que ya existían, sin backend, sin contrato y sin dependencias nuevas.
