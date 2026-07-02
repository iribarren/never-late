# Lección 03 — Artículos: listas con `LazyColumn`, navegación con argumentos y repositorio

> Objetivo: añadir una sección de **contenido**. Desde Home abrimos una **lista** de artículos
> sobre gestión del tiempo (Pomodoro, time blocking, la regla de los 2 minutos) y, al pulsar uno,
> vamos a una **pantalla de detalle** con el texto completo. El contenido viaja **empaquetado
> dentro de la app** (un JSON en `assets/`), sin red todavía. Por el camino aprendemos a pintar
> listas eficientes, a **navegar pasando un argumento** (el id del artículo) y a montar un
> **repositorio** con su propia fuente de datos local.

## Conceptos que aprendes aquí

Partiendo de la Lección 02 (`ViewModel` + `StateFlow`, Navigation Compose, route/screen, DI manual):

- **`data class` como modelo de dominio:** una clase de datos `Article` que representa un artículo,
  independiente de la UI y de dónde vengan los datos.
- **`LazyColumn`:** el widget para listas potencialmente largas, que solo compone las filas
  visibles (a diferencia de una `Column` con scroll).
- **Navegación con argumentos:** una ruta con un parámetro (`articleDetail/{articleId}`) para pasar
  el id de una pantalla a otra.
- **Patrón repositorio con fuente de datos local:** una **interfaz** `ArticleRepository` y una
  implementación que **lee un fichero de `assets/`** y lo parsea.
- **`kotlinx.serialization`:** convertir JSON en objetos Kotlin (`@Serializable`).
- **Estados de UI con `sealed interface`:** modelar "cargando / con contenido / vacío" (o
  "no encontrado") de forma que sean casos mutuamente excluyentes.

---

## 1. El modelo: una `data class`

Una **`data class`** es una clase pensada para **contener datos**. Kotlin te genera gratis
`equals`, `hashCode`, `toString` y `copy`, así que es ideal para modelar "una cosa con campos".
Nuestro artículo ([Article.kt](../app/src/main/java/com/neverlate/data/articles/Article.kt)):

```kotlin
@Serializable
data class Article(
    val id: String,
    val title: String,
    val summary: String,
    val body: String,
)
```

- `id` identifica el artículo de forma estable (lo usaremos como argumento de navegación y como
  `key` de la lista).
- `title` y `summary` se ven en la lista; `body` es el texto largo del detalle.
- **`@Serializable`** (de `kotlinx.serialization`) marca la clase para que se pueda construir
  directamente desde JSON, sin escribir código de parseo a mano.

Este `Article` es un **modelo de dominio**: la UI y los ViewModels solo conocen este tipo, no saben
si vino de `assets/` (esta feature) o de una API (feature 10). Esa independencia es la que permitirá
cambiar la fuente de datos más adelante sin tocar la pantalla.

---

## 2. El contenido: un JSON en `assets/`

La carpeta `app/src/main/assets/` empaqueta ficheros tal cual dentro del APK; se leen en tiempo de
ejecución con el `AssetManager`. Ahí ponemos [articles.json](../app/src/main/assets/articles.json):

```json
[
  {
    "id": "pomodoro",
    "title": "La técnica Pomodoro",
    "summary": "Divide el trabajo en bloques cortos de 25 minutos para mantener el foco.",
    "body": "La técnica Pomodoro consiste en..."
  },
  ...
]
```

Fíjate en que las **claves del JSON coinciden con los campos de `Article`** (`id`, `title`,
`summary`, `body`): por eso `kotlinx.serialization` puede mapear cada objeto del array a un `Article`
sin configuración extra.

> El contenido va en **español** porque es texto que ve la persona usuaria. El **código** (nombres,
> comentarios) sigue en inglés, como manda la convención del proyecto.

---

## 3. El repositorio: una interfaz + una implementación local

Igual que en la Lección 02 el acceso a DataStore vivía detrás de `UserPreferencesRepository`, aquí
el acceso a los artículos vive detrás de una **interfaz**
([ArticleRepository.kt](../app/src/main/java/com/neverlate/data/articles/ArticleRepository.kt)):

```kotlin
interface ArticleRepository {
    suspend fun getArticles(): List<Article>
    suspend fun getArticleById(id: String): Article?
}
```

Las funciones son **`suspend`** porque leer de disco y parsear JSON son operaciones que pueden
tardar: se llaman desde una corrutina y no bloquean el hilo principal. `getArticleById` devuelve
`Article?` (nullable): puede que no exista un artículo con ese id.

La implementación
([LocalArticleRepository.kt](../app/src/main/java/com/neverlate/data/articles/LocalArticleRepository.kt))
lee el fichero de `assets/` y lo parsea:

```kotlin
class LocalArticleRepository(private val context: Context) : ArticleRepository {

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun getArticles(): List<Article> = withContext(Dispatchers.IO) {
        try {
            val text = context.assets.open("articles.json").bufferedReader().use { it.readText() }
            json.decodeFromString<List<Article>>(text)
        } catch (error: IOException) {
            Log.w(LOG_TAG, "Could not read articles.json", error)
            emptyList()
        } catch (error: SerializationException) {
            Log.w(LOG_TAG, "Could not parse articles.json", error)
            emptyList()
        }
    }

    override suspend fun getArticleById(id: String): Article? =
        getArticles().firstOrNull { it.id == id }
}
```

Puntos clave:

- **`withContext(Dispatchers.IO)`** mueve el trabajo bloqueante (leer/parsear) a un pool de hilos
  pensado para E/S, en vez de al hilo principal (donde bloquearía la UI). Este es el primer contacto
  con el cambio de *dispatcher* dentro de una corrutina.
- **Degradación elegante:** si el fichero falta (`IOException`) o el JSON está roto
  (`SerializationException`), devolvemos una lista vacía y registramos un aviso, en lugar de que la
  app se caiga. Ese caso "lista vacía" es el que la pantalla mostrará como estado vacío.
- **`ignoreUnknownKeys = true`:** si el JSON trae campos de más, no falla. Será útil cuando en la
  feature 10 los DTO de la API tengan campos que aquí no usamos.

### ¿Por qué una interfaz otra vez?

Porque **separar el "qué" del "cómo"** es lo que permite intercambiar la fuente de datos:

- Hoy la implementación es local (`LocalArticleRepository`, lee de `assets/`).
- En la feature 10 habrá una implementación remota (con Retrofit/Room) que respete la **misma
  interfaz** `ArticleRepository`. Los ViewModels y la UI no cambiarán ni una línea, porque solo
  conocen la interfaz.

---

## 4. Los ViewModels: estado con `sealed interface`

En la Lección 02 el estado de la pantalla era una `data class`. Aquí, en cambio, la pantalla puede
estar en **varios estados excluyentes**: cargando, con contenido, o vacía. Eso se modela muy bien
con una **`sealed interface`** (una jerarquía cerrada de casos)
([ArticlesViewModel.kt](../app/src/main/java/com/neverlate/ui/articles/ArticlesViewModel.kt)):

```kotlin
sealed interface ArticlesUiState {
    data object Loading : ArticlesUiState
    data class Content(val articles: List<Article>) : ArticlesUiState
    data object Empty : ArticlesUiState
}
```

Al ser `sealed`, el compilador sabe que **esos son todos los casos posibles**, y un `when` sobre el
estado te obliga a cubrirlos todos (sin `else`). Así distinguimos "todavía cargando" de "cargó, pero
de verdad no hay nada", que son cosas distintas para el usuario.

El ViewModel hace una **carga única** al crearse:

```kotlin
class ArticlesViewModel(private val repository: ArticleRepository) : ViewModel() {

    private val _uiState = MutableStateFlow<ArticlesUiState>(ArticlesUiState.Loading)
    val uiState: StateFlow<ArticlesUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            val articles = repository.getArticles()
            _uiState.value =
                if (articles.isEmpty()) ArticlesUiState.Empty
                else ArticlesUiState.Content(articles)
        }
    }
}
```

A diferencia de `HomeViewModel`, que **observaba** un `Flow` que puede cambiar en cualquier momento,
aquí basta una lectura de una vez (`getArticles()`): el catálogo empaquetado no cambia mientras la
app está en marcha.

El [ArticleDetailViewModel.kt](../app/src/main/java/com/neverlate/ui/articles/ArticleDetailViewModel.kt)
es análogo, pero recibe un **`articleId`** y busca ese artículo:

```kotlin
sealed interface ArticleDetailUiState {
    data object Loading : ArticleDetailUiState
    data class Content(val article: Article) : ArticleDetailUiState
    data object NotFound : ArticleDetailUiState
}

class ArticleDetailViewModel(
    private val repository: ArticleRepository,
    private val articleId: String,
) : ViewModel() {
    // ... init { getArticleById(articleId) → Content | NotFound }
}
```

---

## 5. La lista: `LazyColumn`

Una `Column` normal con scroll **compone todas** sus filas de golpe, aunque no se vean: para listas
largas eso desperdicia trabajo y memoria. **`LazyColumn`** solo compone y mide las filas **visibles
en pantalla** (como el viejo `RecyclerView`, pero declarativo)
([ArticlesScreen.kt](../app/src/main/java/com/neverlate/ui/articles/ArticlesScreen.kt)):

```kotlin
LazyColumn(modifier = modifier.fillMaxSize()) {
    items(articles, key = { it.id }) { article ->
        ArticleRow(article = article, onClick = { onArticleClick(article.id) })
    }
}
```

- **`items(lista) { ... }`** define cómo se dibuja cada elemento.
- **`key = { it.id }`** le da a cada fila una identidad estable (el id del artículo). Así, si la
  lista cambia, Compose sabe **qué fila es cuál** en lugar de guiarse por la posición — más eficiente
  y evita fallos sutiles de estado al reordenar.
- Cada fila (`ArticleRow`) es una `Card` clicable con `ListItem` (título + resumen). Al pulsarla,
  llama a `onArticleClick(article.id)`: la pantalla **no navega**, solo **avisa** con el id (state
  hoisting, igual que en la Lección 02).

La pantalla `ArticlesScreen` es **stateless** y hace un `when` sobre el estado sellado:

```kotlin
when (uiState) {
    is ArticlesUiState.Loading -> Unit                    // aún nada: evita un parpadeo
    is ArticlesUiState.Empty   -> EmptyArticles(...)      // mensaje "no hay artículos"
    is ArticlesUiState.Content -> ArticleList(uiState.articles, onArticleClick, ...)
}
```

---

## 6. Navegación con argumentos: pasar el `articleId`

Hasta ahora nuestras rutas eran cadenas fijas (`"home"`, `"onboarding"`). Para el detalle
necesitamos una ruta **parametrizada**: `articleDetail/{articleId}`
([AppNavHost.kt](../app/src/main/java/com/neverlate/ui/navigation/AppNavHost.kt)).

**Declarar** el destino con su argumento:

```kotlin
composable(
    route = "articleDetail/{articleId}",
    arguments = listOf(navArgument("articleId") { type = NavType.StringType }),
) { backStackEntry ->
    val articleId = backStackEntry.arguments?.getString("articleId").orEmpty()
    ArticleDetailRoute(
        articleRepository = articleRepository,
        articleId = articleId,
        onBack = { navController.popBackStack() },
    )
}
```

**Navegar** a él poniendo el id concreto en la ruta:

```kotlin
composable(Routes.ARTICLES) {
    ArticlesRoute(
        articleRepository = articleRepository,
        onArticleClick = { articleId ->
            navController.navigate("articleDetail/$articleId")   // p. ej. "articleDetail/pomodoro"
        },
        onBack = { navController.popBackStack() },
    )
}
```

Y desde Home, la opción "Artículos" (que en la Lección 02 solo mostraba "Próximamente") ahora navega
de verdad:

```kotlin
composable(Routes.HOME) {
    HomeRoute(
        repository = repository,
        onArticlesClick = { navController.navigate(Routes.ARTICLES) },
    )
}
```

### Regla de oro: pasa el **id**, no el objeto

Por la ruta viaja **solo el `articleId`**, nunca el `Article` completo. El detalle **recarga** el
artículo desde el repositorio a partir del id. Meter objetos grandes en la ruta es un antipatrón
(las rutas son cadenas / argumentos simples); pasar un id y releer es la forma correcta y, además,
deja el detalle preparado para cuando los datos vengan de una API.

### ¿Y cómo llega el id al ViewModel del detalle?

`ArticleDetailViewModel` recibe el `articleId` como **parámetro del constructor**, construido por el
`AppViewModelFactory`. AndroidX tiene un mecanismo más idiomático para esto (`SavedStateHandle`),
pero en este proyecto **todos** los ViewModels reciben sus dependencias por constructor mediante DI
manual; reusar ese patrón conocido mantiene la lección centrada en **un solo concepto nuevo**
(navegar con argumentos) en vez de introducir un segundo mecanismo a la vez. La factoría
([AppViewModelFactory.kt](../app/src/main/java/com/neverlate/ui/navigation/AppViewModelFactory.kt))
gana dos parámetros opcionales (`articleRepository`, `articleId`) que Onboarding y Home simplemente
no usan.

---

## 7. Cablear el repositorio en `MainActivity`

El repositorio de artículos, como el de preferencias, se crea **una vez** y se pasa hacia abajo:

```kotlin
val articleRepository: ArticleRepository = LocalArticleRepository(applicationContext)
setContent {
    NeverLateTheme {
        AppNavHost(repository = repository, articleRepository = articleRepository)
    }
}
```

Usamos `applicationContext` (no la Activity) porque el repositorio vive mientras viva la app y no
debe retener una referencia a la Activity.

---

## 8. Dependencia nueva: `kotlinx.serialization`

Para parsear el JSON añadimos `kotlinx.serialization` al **catálogo de versiones**
(`gradle/libs.versions.toml`) y lo aplicamos en `build.gradle.kts` (nunca versiones sueltas):

- **Plugin** `org.jetbrains.kotlin.plugin.serialization` (va con la versión de Kotlin): genera el
  código de (de)serialización para las clases `@Serializable`.
- **Librería** `org.jetbrains.kotlinx:kotlinx-serialization-json`: el formato JSON en tiempo de
  ejecución (`Json { ... }`, `decodeFromString`).

---

## 9. Tests

- **Tests unitarios** (`app/src/test/.../ui/articles/`): usan un **fake en memoria** de
  `ArticleRepository` (sin tocar los assets reales) y `kotlinx-coroutines-test`
  (`runTest`, `Dispatchers.setMain`, `advanceUntilIdle`). Comprueban:
  `ArticlesViewModel` → estado `Content` con datos, `Empty` sin datos; `ArticleDetailViewModel` →
  `Content` para un id válido y `NotFound` para uno desconocido.
- **Tests de UI de Compose** (`app/src/androidTest/.../ui/articles/`): con `createComposeRule()`
  prueban las pantallas **stateless** pasándoles estado a mano: que la lista pinta una fila por
  artículo y que tocar una fila dispara `onArticleClick` con el id correcto; que el detalle muestra
  título y cuerpo, y que el estado "no encontrado" muestra su mensaje. Requieren emulador.

```bash
# Tests unitarios (JVM, sin emulador)
./gradlew :app:testDebugUnitTest

# Tests de UI (necesitan un emulador/dispositivo en marcha)
./gradlew :app:connectedDebugAndroidTest
```

> Nota: `LocalArticleRepository` necesita un `Context` de Android para leer `assets/`, así que el
> parseo real del JSON no se cubre en un test JVM puro (haría falta Robolectric o un test
> instrumentado). Queda como posible mejora futura.

---

## 10. Probar la app

```bash
./gradlew :app:installDebug
adb shell am start -n com.neverlate/.MainActivity
```

- Desde **Home**, pulsa **Artículos**: se abre la **lista** (Pomodoro, Time blocking, Regla de los
  2 minutos).
- Pulsa un artículo: vas al **detalle** con el texto completo y scroll si no cabe.
- Pulsa la **flecha atrás** de la barra superior (o el gesto de retroceso): vuelves a la lista.
- Activa el **modo avión**: todo sigue funcionando, porque el contenido está empaquetado.

---

## 11. Siguiente paso

Ya sabemos pintar listas, navegar con argumentos y aislar la fuente de datos tras un repositorio.
Con esa base, la feature 10 (`docs/prompts/10-articulos-api.md`) sustituirá `LocalArticleRepository`
por una implementación que trae los artículos **desde una API** (Retrofit, DTOs, estados de
carga/error y caché con Room) — **sin cambiar** ni la UI ni los ViewModels, gracias a la interfaz
`ArticleRepository` que hemos definido aquí.
