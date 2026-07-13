# Lección 18b — Layouts adaptables (tamaños de pantalla / tablet)

> Objetivo: cerrar la **mitad "adaptativa"** que la lección 18 dejó fuera. La 18 hizo el repaso de
> **accesibilidad** (semántica, `contentDescription`, 48 dp, fuente dinámica) pero dejó la app pensada
> para **un solo tamaño**: un móvil en vertical. Aquí la app aprende a **medir el ancho disponible** y a
> **reorganizarse** (*reflow*) según él: en un móvil no cambia nada, pero en una tablet o en horizontal
> la barra inferior se convierte en una **`NavigationRail`** lateral y Artículos pasa a mostrar **lista y
> detalle a la vez, en dos paneles**.
>
> Es, como la 18, una lección **arquitectónica pero con la misma regla de siempre: extender, no
> duplicar**. No hay un segundo grafo de navegación ni una segunda versión de las pantallas: el grafo, las
> pantallas (`TasksScreen`/`ArticlesScreen`/`SettingsScreen` con su `onBack` nullable de la 18) y los
> `ViewModel` (con Hilt, lección 13d) son **exactamente los mismos**. Lo adaptable es una **capa de
> presentación por encima** de todo eso.

## Conceptos que aprendes aquí

Partiendo de la 18 (`NavigationBar`, sincronía con el back stack, el idioma de cambio de pestaña, la
puerta de auth de tres caras `LoggedOut`/`Guest`/`LoggedIn`) y de la 03/13c (lista + detalle de
artículos, `getArticleById`):

- **`WindowSizeClass`** — qué es una *clase de tamaño* (`Compact` / `Medium` / `Expanded`), por qué se
  basa en el **ancho disponible** y no en "es un móvil" o "es una tablet", y cómo obtenerla en Compose
  con `calculateWindowSizeClass(activity)`.
- **Navegación adaptable sin duplicar el grafo** — decidir `NavigationBar` (abajo) vs `NavigationRail`
  (al lado) según la clase de tamaño, **reutilizando** la misma lista de destinos, la misma derivación de
  la pestaña activa desde el back stack, y el mismo idioma de `navigate` de la 18.
- **El patrón list-detail** — cuándo partir **un** destino en **dos paneles**, y cómo hacerlo con la API
  con nombre `ListDetailPaneScaffold` (de `androidx.compose.material3.adaptive`). Contraste con la
  navegación *push* de un solo panel que se mantiene en móvil.
- **Restringir el ancho de lectura** — por qué una sola columna estirada de borde a borde en una tablet
  es incómoda de leer, y cómo acotarla (`widthIn(max = …)` centrado).
- **Probar tamaños** — `@Preview(widthDp = …)` a anchos compact/medium/expanded, y por qué el diseño
  responsive es una **continuación** de la accesibilidad: ambos son "la UI se adapta al contexto del
  usuario" en vez de asumir un entorno fijo.

---

## 1. El problema: una UI pensada para un solo ancho

Desde la 18, la app tiene tres secciones (Tareas, Artículos, Ajustes) que se alcanzan desde una
`NavigationBar` inferior siempre visible, sobre **un** grafo (`MainAppNavHost`). Ese layout está afinado
para un ancho: un móvil en vertical.

En una pantalla ancha (una tablet, un móvil grande en horizontal, un plegable abierto) ese mismo layout
se **estira**: la barra inferior queda perdida bajo un área de contenido enorme y casi vacía, y las filas
de artículos tienen líneas de texto larguísimas, incómodas de leer. La app no *aprovecha* el ancho: solo
lo rellena.

La solución no es "detectar tablets". Los tamaños de dispositivo son un continuo (móviles pequeños,
móviles grandes, plegables medio abiertos, tablets, ventanas redimensionables en escritorio/Chromebook,
multiventana…), y lo que de verdad importa no es la etiqueta del dispositivo sino **cuánto ancho tengo
para pintar ahora mismo**. Eso es exactamente lo que modela `WindowSizeClass`.

## 2. `WindowSizeClass`: medir el ancho, no el dispositivo

`WindowSizeClass` (de `androidx.compose.material3:material3-window-size-class`) clasifica la ventana
actual en **tres cubos** de ancho:

| Clase | Ancho aprox. | Ejemplo típico |
|-------|--------------|----------------|
| `Compact` | < 600 dp | móvil en vertical |
| `Medium`  | 600–840 dp | tablet en vertical, móvil grande en horizontal |
| `Expanded`| ≥ 840 dp | tablet en horizontal, ventana grande |

La idea clave es que son **puntos de corte de ancho**, no tipos de hardware. Un plegable que se abre, o
una ventana de escritorio que se redimensiona, **cambian de clase en caliente** sin cambiar de
dispositivo. Por eso el layout se decide a partir de la clase, y por eso reacciona a rotaciones y
redimensionados solo.

### Obtenerla en Compose (una vez, arriba del todo)

Se calcula en la `Activity`, dentro de `setContent`, y se pasa hacia abajo:

```kotlin
// MainActivity.kt
@OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
setContent {
    // @Composable: se recalcula solo cuando la ventana se redimensiona o rota.
    val windowSizeClass = calculateWindowSizeClass(this)
    NeverLateTheme {
        AppNavHost(
            // …dependencias inyectadas por Hilt (lección 13d)…
            widthSizeClass = windowSizeClass.widthSizeClass,
        )
    }
}
```

Dos detalles:

- `calculateWindowSizeClass` es **`@Composable`**: no es una consulta puntual, es una lectura reactiva.
  Cuando rotas el dispositivo o redimensionas la ventana, se recompone con la nueva clase y el layout se
  reorganiza solo.
- `WindowSizeClass` trae `widthSizeClass` **y** `heightSizeClass`. Aquí solo usamos el **ancho**: es lo
  que decide cuántas columnas caben. (La altura importaría para otras decisiones, p. ej. ocultar una
  cabecera alta en horizontal; fuera del alcance de esta feature.)
- Es una API todavía **experimental**, de ahí el `@OptIn(ExperimentalMaterial3WindowSizeClassApi::class)`.

A partir de aquí, `widthSizeClass` viaja como **un parámetro normal** por `AppNavHost` →
`MainAppNavHost`. No es estado global, ni un `CompositionLocal`, ni nada mágico: un `enum` que se pasa de
mano en mano. La puerta de auth (`AuthGateNavHost`, la pantalla de login) **lo ignora a propósito**: el
login es pantalla completa sin cromo a cualquier ancho, así que no tiene nada que adaptar.

## 3. Navegación adaptable: `NavigationBar` ↔ `NavigationRail` sobre **un** grafo

El corazón de la lección. En `MainAppNavHost` ramificamos según el ancho:

```kotlin
if (widthSizeClass == WindowWidthSizeClass.Compact) {
    Scaffold(
        bottomBar = { if (showTopLevelChrome) MainBottomBar(navController) },
    ) { innerPadding ->
        MainNavGraph(navController, startDestination, articleRepository,
                     widthSizeClass, Modifier.padding(innerPadding))
    }
} else {
    // Medium / Expanded: rail al lado, en vez de barra abajo.
    Row(Modifier.fillMaxSize()) {
        if (showTopLevelChrome) MainNavigationRail(navController)
        MainNavGraph(navController, startDestination, articleRepository,
                     widthSizeClass, Modifier.weight(1f))
    }
}
```

Fíjate en lo que **no** cambia entre las dos ramas:

- **El grafo es el mismo.** El `NavHost` con todos sus `composable(...)` está extraído en una única
  función privada, `MainNavGraph`, que ambas ramas invocan. Antes de esta feature el `NavHost` vivía
  dentro del `Scaffold`; ahora lo sacamos a su propia función para que **la rama compact y la rama
  medium/expanded compartan literalmente el mismo grafo**, en vez de declararlo dos veces. Este es el
  punto didáctico central de la lección: **la capa adaptable es presentación por encima de un grafo,
  nunca un segundo grafo**. Un grafo duplicado se desincronizaría a la primera de cambio.
- **La visibilidad sigue *route-gated*.** Igual que en la 18, la barra/rail solo se muestra
  (`showTopLevelChrome`) cuando la ruta actual es una de las tres de nivel superior (`TOP_LEVEL_ROUTES`).
  En Detalle de Artículo (compact), Editar Tarea, Estadísticas o Login/Registro-desde-Ajustes, ni barra
  ni rail: pantalla completa.
- **La puerta de auth no se toca.** Todo esto vive **dentro** de `MainAppNavHost`. Las ramas `when
  (authState)` de `AppNavHost` (`Guest` y `LoggedIn` separadas a propósito, ver lección 13) quedan
  intactas: el cromo adaptable nunca aparece en la pantalla de login.

### Barra y rail comparten el idioma de navegación

`MainBottomBar` (la barra de la 18) y `MainNavigationRail` (el rail nuevo) son casi gemelos:

```kotlin
@Composable
private fun MainNavigationRail(navController: NavHostController) {
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination
    NavigationRail {
        bottomNavItems.forEach { item ->
            val selected = currentDestination?.hierarchy?.any { it.route == item.route } == true
            NavigationRailItem(
                selected = selected,
                onClick = { navController.navigateToTopLevelRoute(item.route) },
                icon = { Icon(item.icon, stringResource(item.contentDescriptionRes)) },
                label = { Text(stringResource(item.labelRes)) },
            )
        }
    }
}
```

Comparado con `MainBottomBar`, cambia **solo** `NavigationRail`/`NavigationRailItem` por
`NavigationBar`/`NavigationBarItem`. Todo lo demás es **el mismo código reutilizado**:

- La **misma lista** `bottomNavItems` (las tres secciones con su icono, etiqueta y `contentDescription`).
- La **misma derivación** de la pestaña activa desde `currentBackStackEntryAsState()` +
  `NavDestination.hierarchy` — reactiva desde el back stack, nunca un índice `remember`ado que se
  desincronice (lección 18).
- El **mismo idioma de cambio de pestaña**, ahora factorizado en **una** extensión compartida para que
  barra y rail no puedan divergir en dos idiomas sutilmente distintos:

```kotlin
private fun NavHostController.navigateToTopLevelRoute(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }  // back stack acotado + estado guardado
        launchSingleTop = true                                          // no apila copias de la misma pestaña
        restoreState = true                                             // restaura el estado guardado (scroll…)
    }
}
```

Esto es "extender, no duplicar" aplicado a la navegación: el mismo principio que ya aplicamos a la lógica
de dominio. Barra y rail son **la misma decisión de navegación, presentada de dos formas**; ninguna posee
estado propio.

## 4. El patrón list-detail: Artículos en dos paneles

En `Expanded`, Artículos deja de ser "lista → pulsar → pantalla completa de detalle" y pasa a mostrar
**lista (izquierda) y detalle (derecha) a la vez**. En compact/medium se mantiene el flujo *push* de
siempre, sin tocar nada. La decisión vive en el propio destino del grafo:

```kotlin
composable(Routes.ARTICLES) {
    if (widthSizeClass == WindowWidthSizeClass.Expanded) {
        ArticlesListDetailPane(articleRepository = articleRepository)   // dos paneles
    } else {
        ArticlesRoute(onArticleClick = { id -> navController.navigate("articleDetail/$id") }, onBack = null)
    }
}
```

### `ListDetailPaneScaffold`: la API con nombre para esto

En vez de montar los dos paneles a mano con un `Row`, usamos `ListDetailPaneScaffold`
(`androidx.compose.material3.adaptive`), la API **con nombre** de Material 3 para exactamente este patrón.
Ella misma sabe cuántos paneles caben y gestiona la navegación entre ellos:

```kotlin
@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
fun ArticlesListDetailPane(articleRepository: ArticleRepository, modifier: Modifier = Modifier) {
    val navigator = rememberListDetailPaneScaffoldNavigator<String>()  // <String> = el id del artículo seleccionado
    BackHandler(enabled = navigator.canNavigateBack()) { navigator.navigateBack() }

    ListDetailPaneScaffold(
        directive = navigator.scaffoldDirective,
        value = navigator.scaffoldValue,
        listPane = {
            AnimatedPane {
                ArticlesRoute(   // ← ¡la MISMA lista paginada, sin cambios!
                    onArticleClick = { id -> navigator.navigateTo(ListDetailPaneScaffoldRole.Detail, id) },
                    onBack = null,
                )
            }
        },
        detailPane = {
            AnimatedPane {
                val id = navigator.currentDestination?.content
                if (id != null) ArticleDetailPaneContent(id, articleRepository)
                else MessageState(/* "Selecciona un artículo" */)   // placeholder, nunca un hueco vacío
            }
        },
    )
}
```

Puntos que enseñar aquí:

- **La lista se reutiliza tal cual.** El panel izquierdo es `ArticlesRoute`, la misma pantalla paginada
  de la 13c, con toda su tubería de Paging 3 intacta (pull-to-refresh, spinner de *append*, reintento en
  línea, estados vacío/error). No reimplementamos la paginación para dos paneles; solo cambiamos qué hace
  el *onClick* de una fila: en vez de `navigate(...)` a pantalla completa, `navigator.navigateTo(Detail,
  id)` actualiza **el panel derecho en el sitio**.
- **El navigator guarda qué está seleccionado** (`currentDestination?.content`) y, en anchos menores que
  expanded, colapsaría a un solo panel él solo. Aquí no llegamos a depender de eso (compact/medium ya van
  por el camino *push* de un panel), pero conectamos su `BackHandler`: así, el botón atrás del sistema
  primero **deshace la selección** antes de salir de Artículos.
- **Un placeholder en el panel de detalle** cuando aún no hay nada seleccionado (`MessageState` con
  "Selecciona un artículo"). Nunca un hueco en blanco ni un crash.

### Reutilizar el *render*, no la tubería del `ViewModel`

¿Por qué el panel derecho no usa `hiltViewModel()` / `ArticleDetailViewModel` como la ruta *push*? Porque
ese `ViewModel` lee su `articleId` de un `SavedStateHandle` ligado a una **entrada del back stack**
(lección 13d) — perfecto para un destino navegado, pero **aquí no hay navegación**, solo una selección
local en memoria. Forzar `hiltViewModel()` a esto sería pelearse con su diseño.

La solución limpia: cargar el artículo **directamente** del repositorio (el mismo `getArticleById` que el
`ViewModel` llamaría por dentro) y renderizarlo con el **mismo cuerpo visual** que la ruta *push*:

```kotlin
@Composable
private fun ArticleDetailPaneContent(articleId: String, articleRepository: ArticleRepository, …) {
    var uiState by remember(articleId) { mutableStateOf<ArticleDetailUiState>(ArticleDetailUiState.Loading) }
    LaunchedEffect(articleId) {   // se re-ejecuta al pulsar otra fila mientras el panel sigue compuesto
        val article = articleRepository.getArticleById(articleId)
        uiState = if (article != null) ArticleDetailUiState.Content(article) else ArticleDetailUiState.NotFound
    }
    ArticleDetailBody(uiState = uiState)   // ← el MISMO composable que usa la ruta push
}
```

La pieza que lo hace posible es una pequeña refactorización en `ArticleDetailScreen.kt`: extraer
**`ArticleDetailBody`**, la parte de *solo contenido* (sin `Scaffold`, sin flecha de atrás), separada del
`ArticleDetailScreen` que sí lleva su barra superior. Así, la ruta *push* usa `Scaffold + ArticleDetailBody`
y el panel de detalle usa `ArticleDetailBody` a secas — **una sola fuente de verdad para el aspecto del
detalle**, imposible que los dos caminos deriven visualmente. Reutilizamos el *render*, no el andamiaje
del `ViewModel`.

## 5. Tareas y Ajustes: no todo se parte en dos

`ListDetailPaneScaffold` es la respuesta para Artículos porque Artículos **es** una relación
lista→detalle. Tareas y Ajustes no lo son de forma tan natural (partir Tareas en lista + editor es una
mejora futura, ver *Out of Scope* del spec). Pero **sí** sufren el problema de "columna estirada de borde
a borde" en una tablet.

La respuesta mínima y honesta: **acotar el ancho de lectura**. Un contenedor reutilizable,
`ReadableWidthContainer`, deja el contenido a lo ancho en compact (como siempre) y lo limita a un ancho
cómodo, centrado, en medium/expanded:

```kotlin
@Composable
fun ReadableWidthContainer(widthSizeClass: WindowWidthSizeClass, …, content: @Composable () -> Unit) {
    if (widthSizeClass == WindowWidthSizeClass.Compact) {
        Box(Modifier.fillMaxSize()) { content() }                      // borde a borde
    } else {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            Box(Modifier.widthIn(max = 640.dp).fillMaxSize()) { content() }   // acotado y centrado
        }
    }
}
```

Nótese que aquí basta la distinción **Compact / no-Compact** (medium y expanded se tratan igual), a
diferencia del rail, que necesitaba la clase completa. Cada capa usa **la granularidad que necesita**:
no hay que arrastrar siempre las tres clases.

## 6. Probar tamaños: previews con distintos `widthDp`

No hace falta una tablet para *ver* el resultado. `@Preview` acepta un `widthDp` (y `heightDp`), así que
podemos previsualizar el mismo composable a varios anchos:

```kotlin
@Preview(name = "Compact — barra inferior", widthDp = 400, heightDp = 120)
@Composable private fun MainBottomBarPreview() { NeverLateTheme { MainBottomBar(rememberNavController()) } }

@Preview(name = "Medium/Expanded — rail", widthDp = 96, heightDp = 600)
@Composable private fun MainNavigationRailPreview() { NeverLateTheme { MainNavigationRail(rememberNavController()) } }
```

Igual que en el resto del proyecto, previsualizamos los composables **stateless** (barra, rail,
`ReadableWidthContainer`, el placeholder del panel de detalle), no los `*Route` respaldados por
`hiltViewModel()` (una preview no tiene grafo de Hilt). Es la misma regla que ya seguíamos.

### Los tests

En `androidText` (Compose UI tests, la convención del proyecto) añadimos:

- `ReadableWidthContainerTest`: envolviendo el contenedor en un `Box` de ancho fijo (una preview no
  controla el ancho real de ventana), comprueba que Compact ocupa todo el ancho y Medium/Expanded lo
  acotan a 640 dp y lo centran, sin desbordarse cuando el hueco es más estrecho que 640 dp.
- `ArticleDetailBodyTest`: verifica el cuerpo extraído (Content/NotFound/Loading) y, sobre todo, que
  **nunca** pinta una flecha de atrás — la propiedad de la que depende el panel de detalle.

El `ListDetailPaneScaffold` completo no se testea en aislamiento porque su panel-lista es `ArticlesRoute`
(respaldado por `hiltViewModel()`, sin un `HiltAndroidRule` montado para UI en este proyecto); montar esa
infraestructura para un solo panel sería desproporcionado, así que se documenta la limitación en vez de
reimplementar la pantalla en el test.

## 7. Por qué esto *es* accesibilidad

La lección 18 cerró con accesibilidad y esta abre con tamaños de pantalla, y no es casualidad: **son la
misma idea**. Accesibilidad es "la UI se adapta al usuario" — su tamaño de fuente, su lector de pantalla,
su capacidad motora (los 48 dp). Diseño responsive es "la UI se adapta al contexto" — su ancho de
pantalla, su orientación, su ventana. Ambos rechazan la suposición de **un** entorno fijo.

Por eso el criterio transversal de esta feature es *no regresión de accesibilidad*: al pasar a rail y a
dos paneles, se mantiene todo lo de la 18 — objetivos de toque ≥ 48 dp (`minimumInteractiveComponentSize`),
`contentDescription`s coherentes en los iconos del rail (los mismos de la barra), y **reflow correcto con
fuente grande**. Un layout que se adapta al ancho pero se rompe con la fuente más grande no ha ganado
nada: ha cambiado una suposición rígida por otra.

## 8. Resumen

- **`WindowSizeClass`** clasifica el **ancho de la ventana** (no el dispositivo) en Compact/Medium/
  Expanded; se obtiene con `calculateWindowSizeClass(this)` (un `@Composable` reactivo) en `MainActivity`
  y se pasa hacia abajo como un parámetro normal.
- **Navegación adaptable:** `NavigationBar` en compact, `NavigationRail` en medium/expanded —
  **reutilizando** la lista de destinos, la derivación de la pestaña activa y el idioma de `navigate`. El
  grafo se **factoriza** (`MainNavGraph`) y se comparte: nunca se duplica.
- **List-detail** con `ListDetailPaneScaffold`: en expanded, Artículos muestra lista + detalle a la vez;
  el panel de detalle reutiliza `ArticleDetailBody` (el *render*), cargando por `getArticleById` en vez
  de pelearse con el `ViewModel` de la ruta *push*.
- **`ReadableWidthContainer`** acota Tareas/Ajustes en anchos grandes (dos paneles para ellos: futuro).
- **Previews con `widthDp`** para ver cada tamaño sin una tablet; los tests cubren lo *stateless*.
- Todo respeta las tres caras de la puerta de auth de la 13 y toda la accesibilidad de la 18: **responsive
  y accesible son la misma disciplina**.

### Dependencias nuevas

- `androidx.compose.material3:material3-window-size-class` — `WindowSizeClass`. Gestionada por el Compose
  BOM (sin versión explícita). En el catálogo se llama `material3-windowsizeclass` (una palabra) porque
  Gradle no admite un alias cuyo último segmento sea literalmente `class`.
- `androidx.compose.material3.adaptive:{adaptive, adaptive-layout, adaptive-navigation}` — el
  `ListDetailPaneScaffold` y su navigator. Versionadas **independientemente** del BOM, fijadas a **1.0.0**
  (no la más nueva) por el mismo motivo de compatibilidad con AGP 8.13.2 que las de Hilt en la 13d:
  revisar el pin al subir el Compose BOM.

## Prerrequisitos

Lecciones 02–07 (Compose, Material 3), 13c (Artículos + Paging + `getArticleById`), 13d (Hilt /
`hiltViewModel()`), y 18 (grafo único de navegación, `onBack` nullable, idioms de accesibilidad).
