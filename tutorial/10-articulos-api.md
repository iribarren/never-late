# Lección 10 — Artículos desde una API: red, caché y estados de UI

> Objetivo: que los artículos dejen de venir de un fichero **empaquetado** en la app y se
> **descarguen de una API REST** por HTTP, guardándolos en una **caché local** para que se puedan
> leer **sin conexión**. Es la **primera vez** que el proyecto habla con la red, así que lo tomamos
> con calma: aparecen **Retrofit** y **OkHttp**, la distinción entre un **DTO de red** y el
> **modelo de dominio**, un nuevo estado de UI de **error** con **reintento**, **pull-to-refresh**,
> y **Room como única fuente de verdad** con una estrategia *stale-while-revalidate*. Reutilizamos
> casi todo lo anterior: el *seam* (`Article` + `ArticleRepository`) que la lección 03 dejó
> preparado a propósito, Room y los `@Entity`/`@Dao` de la 04, `kotlinx.serialization` de la 03, las
> corrutinas `suspend`, el `sealed interface` de estado, y el `AppViewModelFactory` de DI manual.

## Conceptos que aprendes aquí

Partiendo de las lecciones 03 (repositorio tras interfaz, `sealed interface` de estado, un solo
`suspend` de carga, `kotlinx.serialization`), 04 (**Room**: `@Entity`/`@Dao`/`@Database`, `suspend`
en el DAO) y 07 (pantalla que reacciona a `StateFlow`):

- **Networking con [Retrofit](https://square.github.io/retrofit/) +
  [OkHttp](https://square.github.io/okhttp/):** describir un endpoint como una **interfaz** con
  funciones `suspend`, y dejar que Retrofit escriba la implementación (URL, conexión, parseo) por ti.
- **El converter de [`kotlinx.serialization`](https://kotlinlang.org/docs/serialization.html):**
  reutilizar la misma librería JSON del proyecto para deserializar las respuestas de red, en vez de
  añadir Moshi/Gson.
- **DTO de red ≠ modelo de dominio + mapeo:** una `data class` que refleja **la forma del JSON** de
  la API (deliberadamente distinta de `Article`) y una función **pura** que la traduce a dominio.
- **Caché offline con [Room](https://developer.android.com/training/data-storage/room) como única
  fuente de verdad** y una estrategia **stale-while-revalidate**: la UI lee siempre de la caché; un
  **refresco** trae datos frescos de la red y actualiza la caché.
- **Estados de UI loading / success / empty / error** con `ViewModel` + `StateFlow`, **ampliando** el
  `sealed interface` de la 03 con un caso de **error** y una acción de **reintento**.
- **[Pull-to-refresh](https://developer.android.com/develop/ui/compose/components/pull-to-refresh)**
  con el `PullToRefreshBox` de Material 3, gobernado por un `StateFlow<Boolean>`.
- **Ampliar un contrato de forma aditiva:** añadir `refresh()` a `ArticleRepository` sin tocar las
  firmas de lectura, para poder distinguir "vacío de verdad" de "falló la red y no hay caché".
- **El permiso [`INTERNET`](https://developer.android.com/develop/connectivity/network-ops/connecting):**
  un permiso *normal* (no se pide en runtime).

---

## 1. El reparto de piezas

Todo lo nuevo del lado de datos vive en el paquete `com.neverlate.data.articles` que ya conocíamos de
la lección 03. La lista/detalle (`ui/articles`) apenas cambian: solo ganan el estado de error y el
pull-to-refresh.

- `data/articles/ArticleDto.kt` — el **DTO de red** (la forma del JSON) y la función pura de **mapeo**
  a `Article`, más `summarize()` (deriva el resumen que la API no manda).
- `data/articles/ArticlesApi.kt` — la **interfaz Retrofit** del endpoint (`suspend fun getArticles()`).
- `data/articles/ArticlesNetwork.kt` — la **fábrica** que arma Retrofit + OkHttp + el converter JSON.
- `data/articles/ArticleEntity.kt` — la **fila de Room** de la caché, y su mapeo a/desde `Article`.
- `data/articles/ArticleDao.kt` — el **`@Dao`** de la tabla `articles` (leer todo, leer por id, upsert,
  vaciar).
- `data/articles/CachingArticleRepository.kt` — la **nueva implementación** de `ArticleRepository`:
  combina API + DAO, con Room como única fuente de verdad.
- `data/articles/ArticleRepository.kt` — la **interfaz** de siempre, ampliada con `refresh()` y el
  tipo `RefreshResult`.
- `docs/articles-api/articles.json` — el JSON remoto (en forma de DTO), servido por GitHub raw.

Y **desaparece** lo empaquetado: borramos `LocalArticleRepository.kt` y `assets/articles.json`. La
lección 03 ya lo anticipaba ("la feature 10 sustituirá `LocalArticleRepository`"), así que cerramos
ese círculo.

> **El *seam* de la lección 03 hace todo esto barato.** `Article` (el modelo de dominio) y la
> interfaz `ArticleRepository` **no cambian de rol**: la lista y el detalle siguen dependiendo de la
> interfaz, nunca de la implementación concreta. Cambiamos la fuente de datos **detrás** del contrato
> y la UI casi ni se entera. Ese fue el objetivo de declarar una interfaz en la 03 aunque solo hubiera
> una implementación local.

---

## 2. El problema nuevo: hablar con la red

Hasta ahora todos los datos del proyecto eran **locales**: `assets/` (artículos, 03), `DataStore`
(preferencias, 07) y Room (tareas, 04). La red trae tres complicaciones que un fichero local no tiene:

1. **Es lenta y bloqueante** → hay que hacerla en un hilo de fondo (`suspend` + `Dispatchers.IO`).
2. **Puede fallar** (sin cobertura, servidor caído, JSON corrupto) → necesitamos un estado de **error**.
3. **No siempre está** → si queremos leer offline, hay que **guardar** lo descargado en algún sitio.

Las dos primeras las resuelven Retrofit (secciones 3–4) y un nuevo estado de UI (sección 8). La
tercera es la **caché con Room** (secciones 5–7), que además nos da el patrón *single source of truth*.

---

## 3. Retrofit: describir el endpoint como una interfaz

En vez de abrir una conexión HTTP a mano, **describimos** el endpoint como una interfaz y dejamos que
Retrofit genere la implementación en tiempo de ejecución:

```kotlin
// data/articles/ArticlesApi.kt
interface ArticlesApi {
    @GET("articles.json")
    suspend fun getArticles(): List<ArticleDto>
}
```

- **`@GET("articles.json")`** dice el verbo HTTP y la ruta (relativa a la *base URL*, sección 6).
- **El tipo de retorno** (`List<ArticleDto>`) le dice a Retrofit **en qué** deserializar el cuerpo de
  la respuesta.
- **`suspend`** es la misma pieza de corrutinas que ya usa el `TaskDao` (04): Retrofit ejecuta la
  llamada de red en un hilo de fondo y **reanuda** esta corrutina con el resultado, sin que tengamos
  que gestionar *callbacks* ni hilos a mano.

> **Concepto — declarar en vez de implementar.** Igual que `@Dao` (Room) genera el SQL a partir de
> firmas anotadas, `ArticlesApi` genera el cliente HTTP a partir de una interfaz. Escribes el *qué*
> (verbo, ruta, tipo), no el *cómo* (sockets, streams, parseo).

---

## 4. DTO de red ≠ modelo de dominio, y el mapeo

Aquí está el concepto central de la lección. El JSON que manda el servidor **no tiene por qué** tener
la forma de nuestro `Article`. De hecho, a propósito, **no la tiene**:

```json
// docs/articles-api/articles.json (fragmento)
{ "article_id": "pomodoro", "title": "La técnica Pomodoro", "content": "La técnica Pomodoro consiste en…" }
```

Dos diferencias con `Article(id, title, summary, body)`, ambas deliberadas para que el mapeo sea real:

- La API usa **`article_id`** (`snake_case`); nuestro código usa **`camelCase`**.
- La API manda **`content`** (no `body`) y **no manda `summary`** en absoluto.

El **DTO** refleja la forma del JSON, y una anotación tiende el puente entre convenciones de nombres:

```kotlin
// data/articles/ArticleDto.kt
@Serializable
data class ArticleDto(
    @SerialName("article_id") val articleId: String,   // la clave JSON es article_id; la propiedad, articleId
    val title: String,
    val content: String,
)
```

`@SerialName` (de `kotlinx.serialization`, ya presente desde la 03) dice: "la clave del JSON se llama
`article_id`, pero nombra la propiedad Kotlin `articleId`". Así el lado Kotlin no tiene que adoptar
`snake_case`.

El **mapeo** DTO → dominio es una **función pura** (sin Android, sin I/O), lo que la hace trivial de
testear en JVM:

```kotlin
fun ArticleDto.toDomain(): Article = Article(
    id = articleId,
    title = title,
    summary = summarize(content),   // ← el resumen que la API no manda, lo derivamos nosotros
    body = content,
)
```

Y como la API no manda `summary`, lo **derivamos** del cuerpo: preferimos la primera frase, y si no
hay uno razonable, cortamos por la última palabra entera y añadimos "…":

```kotlin
internal fun summarize(content: String): String {
    val trimmed = content.trim()
    val sentenceEnd = trimmed.indexOfFirst { it == '.' || it == '!' || it == '?' }
    if (sentenceEnd in 0 until 160) return trimmed.substring(0, sentenceEnd + 1).trim()
    if (trimmed.length <= 120) return trimmed
    val cutoff = trimmed.substring(0, 120)
    val lastSpace = cutoff.lastIndexOf(' ')
    return (if (lastSpace > 0) cutoff.substring(0, lastSpace) else cutoff) + "…"
}
```

> **Concepto — por qué separar DTO y dominio.** Si la lista y el detalle dependieran directamente del
> JSON de la API, cualquier cambio del servidor (renombrar un campo, añadir uno) se propagaría por
> toda la UI. Con un DTO en medio, **el único sitio que conoce la forma del servidor** es
> `ArticleDto` + `toDomain()`. El resto del proyecto sigue viendo el `Article` estable de siempre. Es
> el mismo principio del *seam*, aplicado a la frontera de red. `summarize()` es `internal` (no
> `private`) justo para que los tests del módulo puedan ejercitarlo sin pasar por toda la cadena.

---

## 5. La caché: `@Entity` + `@Dao` para artículos

Para leer offline necesitamos **guardar** lo descargado. Reutilizamos Room (lección 04) con una tabla
nueva. La fila es igual que `Article` (no como el DTO): la caché guarda la forma **de dominio**, ya
mapeada, no el formato de red.

```kotlin
// data/articles/ArticleEntity.kt
@Entity(tableName = "articles")
data class ArticleEntity(
    @PrimaryKey val id: String,   // ← String, no Long autogenerado: el id ya viene del catálogo remoto
    val title: String,
    val summary: String,
    val body: String,
)
```

Fíjate en la diferencia con `Task` (04): allí `@PrimaryKey(autoGenerate = true) val id: Long`, porque
SQLite inventaba el id; aquí el id (`"pomodoro"`) **ya lo asigna el servidor**, así que es un `String`
y no se autogenera.

El DAO son cuatro operaciones `suspend` de una sola vez (no `Flow`):

```kotlin
// data/articles/ArticleDao.kt
@Dao
interface ArticleDao {
    @Query("SELECT * FROM articles")               suspend fun getAll(): List<ArticleEntity>
    @Query("SELECT * FROM articles WHERE id = :id") suspend fun getById(id: String): ArticleEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertAll(items: List<ArticleEntity>)
    @Query("DELETE FROM articles")                  suspend fun clear()
}
```

> **Contraste con `TaskDao` (04):** aquel devolvía `Flow` porque la lista/widget/notificación deben
> **reaccionar en vivo** a cada cambio. Los artículos no funcionan así: la pantalla carga la caché
> **una vez** (y otra al refrescar explícitamente), tal como la lección 03 ya cargaba de una sola vez.
> Por eso aquí todo es `suspend` de una vez, sin `Flow`. **Elige `Flow` o `suspend` según si la UI
> necesita observar cambios continuos o solo leer una foto.**

`OnConflictStrategy.REPLACE` convierte el `@Insert` en un *upsert*: si ya existe una fila con ese id,
la reemplaza en lugar de fallar — justo lo que un refresco necesita.

### La tabla vive en la base de datos que ya teníamos

En vez de crear una segunda `RoomDatabase`, **añadimos** la entidad a la existente y **subimos la
versión** de 1 a 2:

```kotlin
// data/tasks/NeverLateDatabase.kt
@Database(entities = [Task::class, ArticleEntity::class], version = 2, exportSchema = false)
abstract class NeverLateDatabase : RoomDatabase() {
    abstract fun taskDao(): TaskDao
    abstract fun articleDao(): ArticleDao
}
```

> ⚠️ **La migración destructiva borra las tareas.** La base de datos usa
> `fallbackToDestructiveMigration(dropAllTables = true)` (04): al subir la versión sin escribir una
> `Migration` real, Room **destruye y recrea** todas las tablas, incluidas las tareas. Es aceptable
> porque el proyecto sigue **pre-release** (la misma política que la 04 ya asumía), pero conviene
> saberlo: en cuanto haya usuarios con datos, cada cambio de esquema tendrá que traer su `Migration`.

---

## 6. Armar el cliente: `ArticlesNetwork`

Retrofit necesita tres piezas: un **cliente HTTP** (OkHttp), una **base URL** y un **converter** que
convierta bytes JSON en objetos Kotlin. Las juntamos en una pequeña fábrica:

```kotlin
// data/articles/ArticlesNetwork.kt (resumen)
private const val DEFAULT_BASE_URL =
    "https://raw.githubusercontent.com/iribarren/never-late/master/docs/articles-api/"

fun create(baseUrl: String = DEFAULT_BASE_URL): ArticlesApi {
    val logging = HttpLoggingInterceptor().apply {
        level = if (BuildConfig.DEBUG) HttpLoggingInterceptor.Level.BODY
                else HttpLoggingInterceptor.Level.NONE
    }
    val client = OkHttpClient.Builder().addInterceptor(logging).build()
    val json = Json { ignoreUnknownKeys = true }
    return Retrofit.Builder()
        .baseUrl(baseUrl)
        .client(client)
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()
        .create(ArticlesApi::class.java)
}
```

Piezas nuevas:

- **OkHttp** es el motor HTTP real que hay debajo de Retrofit. Un **interceptor** observa (o modifica)
  cada petición/respuesta que pasa por el cliente: así enganchamos el logging.
- **`HttpLoggingInterceptor`** imprime en Logcat cada petición/respuesta — utilísimo para aprender —
  pero a nivel `BODY` vuelca todo el contenido, demasiado verboso (e inseguro con datos sensibles)
  para producción. `BuildConfig.DEBUG` es una constante que Gradle genera por *build type*, así que la
  *release* se compila con `NONE`. (Para que `BuildConfig` exista hay que activar
  `buildFeatures { buildConfig = true }` en `app/build.gradle.kts`.)
- **`asConverterFactory`** conecta el `Json` de `kotlinx.serialization` (la **misma** librería de la
  03) con Retrofit, así no añadimos Moshi ni Gson. `ignoreUnknownKeys` hace que, si el servidor añade
  un campo nuevo (p. ej. `author`), el parseo **no** se rompa.

> **Sobre la URL remota.** El endpoint es un **fichero estático de este mismo repositorio**
> (`docs/articles-api/articles.json`), servido por el "raw" de GitHub (HTTPS, sin auth, sin clave). Por
> cómo funciona GitHub raw, ese endpoint **solo responde una vez este código está mergeado y subido a
> `master`**. Los tests no dependen de eso: le pasan su propia `baseUrl` apuntando a un `MockWebServer`
> local (por eso `create` acepta el parámetro en vez de tener la URL fija). El parámetro `baseUrl` es,
> a la vez, una decisión de diseño **y** un punto de inyección para tests.

---

## 7. Room como única fuente de verdad + *stale-while-revalidate*

Ahora el repositorio combina API y DAO. La regla de oro: **la UI nunca lee la API directamente**.

```kotlin
// data/articles/CachingArticleRepository.kt
class CachingArticleRepository(
    private val api: ArticlesApi,
    private val dao: ArticleDao,
) : ArticleRepository {

    override suspend fun getArticles(): List<Article> = dao.getAll().map { it.toDomain() }
    override suspend fun getArticleById(id: String): Article? = dao.getById(id)?.toDomain()

    override suspend fun refresh(): RefreshResult = withContext(Dispatchers.IO) {
        try {
            val articles = api.getArticles().map { it.toDomain() }
            dao.clear()                                   // 1) tira lo viejo…
            dao.upsertAll(articles.map { it.toEntity() }) // 2) …y escribe lo nuevo
            RefreshResult.Success
        } catch (error: IOException) {          // sin red, DNS, timeout
            RefreshResult.Error(error)
        } catch (error: HttpException) {        // el servidor respondió 4xx/5xx
            RefreshResult.Error(error)
        } catch (error: SerializationException) { // 2xx pero el JSON no encaja con ArticleDto
            RefreshResult.Error(error)
        }
    }
}
```

Tres ideas:

- **Única fuente de verdad:** `getArticles`/`getArticleById` leen **siempre** de `dao`. `refresh()` es
  lo único que toca la red, y su único trabajo es **actualizar la caché** para que la *siguiente*
  lectura la refleje. Nunca entrega datos de red directamente. Por eso la app funciona offline: una
  vez cacheado algo, las lecturas van sin red.
- **`clear()` + `upsertAll()`** (en vez de solo *upsert*) hace que la caché sea un **espejo fiel** del
  catálogo del servidor: si el servidor **borró** un artículo, el *upsert* solo nunca lo quitaría.
- **Capturamos excepciones concretas, no `Throwable`.** Atrapar `Throwable` también tragaría
  `CancellationException`, la excepción con la que las corrutinas se **cancelan** (p. ej. cuando el
  `ViewModel` se destruye a mitad del refresco). Hay que dejarla propagar.

El contrato se amplió de forma **aditiva**: `refresh()` es nuevo; las dos lecturas de la 03 siguen
igual.

```kotlin
// data/articles/ArticleRepository.kt
interface ArticleRepository {
    suspend fun getArticles(): List<Article>          // lee la caché
    suspend fun getArticleById(id: String): Article?  // lee la caché
    suspend fun refresh(): RefreshResult              // red → mapeo → caché  (NUEVO)
}
sealed interface RefreshResult {
    data object Success : RefreshResult
    data class Error(val cause: Throwable) : RefreshResult
}
```

> **Concepto — ampliar sin romper.** `getArticles()` nunca lanza ni informa de fallos (un error de red
> simplemente deja la caché intacta), así que por sí sola no puede distinguir "vacío porque no hay
> nada" de "vacío porque falló la red y aún no hay caché". `refresh()` cierra ese hueco informando
> éxito/fallo **explícitamente**, sin cambiar la firma ni el significado de las dos lecturas
> originales. `RefreshResult` es un `sealed interface` (no un `Boolean`) para que `Error` pueda llevar
> la causa — el mismo estilo que los estados de UI.

---

## 8. El nuevo estado de UI: `Error` + reintento, con *stale-while-revalidate*

La lección 03 modelaba tres estados: `Loading`, `Content`, `Empty`. Con la red hace falta un cuarto,
`Error`:

```kotlin
// ui/articles/ArticlesViewModel.kt
sealed interface ArticlesUiState {
    data object Loading : ArticlesUiState
    data class Content(val articles: List<Article>) : ArticlesUiState
    data object Empty : ArticlesUiState
    data object Error : ArticlesUiState          // ← nuevo en la feature 10
}
```

El `ViewModel` implementa el *stale-while-revalidate* en tres pasos: **enseña la caché ya**, refresca
después, y vuelve a leer la caché para decidir el estado final:

```kotlin
private fun loadThenRefresh() {
    viewModelScope.launch {
        // 1) Enseña lo cacheado de inmediato, sin red.
        val cached = repository.getArticles()
        _uiState.value = if (cached.isNotEmpty()) ArticlesUiState.Content(cached) else ArticlesUiState.Loading

        // 2) Intenta refrescar. isRefreshing rodea SOLO esta llamada (es lo que la ruedita representa).
        _isRefreshing.value = true
        val result = repository.refresh()
        _isRefreshing.value = false

        // 3) Relee la caché y decide el estado final.
        val fresh = repository.getArticles()
        _uiState.value = when {
            fresh.isNotEmpty()             -> ArticlesUiState.Content(fresh)
            result is RefreshResult.Error  -> ArticlesUiState.Error   // vacío Y la red falló
            else                           -> ArticlesUiState.Empty   // vacío pero la red respondió: no hay artículos
        }
    }
}
```

Esa tabla de decisión del paso 3 es la clave: **solo** caemos a `Error` si además de no tener nada que
enseñar, el refresco falló. Si el refresco fue bien pero el catálogo está vacío, es `Empty` de verdad.
Y si hay caché (aunque el refresco falle), seguimos en `Content`: **un fallo de red con caché no es un
error bloqueante** (así, abrir la app en modo avión tras haber cargado una vez muestra el contenido,
no un error).

`refresh()` es público y lo llaman **dos** disparadores que, desde el `ViewModel`, piden lo mismo: el
**botón de reintentar** del estado de error y el **pull-to-refresh**.

`isRefreshing` es un `StateFlow<Boolean>` **aparte** del `uiState`, porque responde a otra pregunta:
"¿hay una llamada de red en curso ahora mismo?" (para la ruedita), no "¿qué muestro en el área
principal?".

### El detalle: `Error` frente a `NotFound`

El detalle gana también un `Error`, junto al `NotFound` que ya tenía. Se parecen (ambos muestran "no
hay nada"), pero significan cosas distintas, y por eso, ante una **caché fría**, primero intenta
refrescar antes de rendirse:

```kotlin
// ui/articles/ArticleDetailViewModel.kt (resumen)
val cached = repository.getArticleById(articleId)
if (cached != null) { _uiState.value = Content(cached); return@launch }

val result = repository.refresh()                       // no estaba: baja el catálogo y reintenta
val refreshed = repository.getArticleById(articleId)
_uiState.value = when {
    refreshed != null              -> Content(refreshed)
    result is RefreshResult.Error  -> Error       // no sabemos si el id es inválido o la red falló
    else                           -> NotFound    // catálogo accesible; ese id no existe
}
```

> **Concepto — que el estado cuente la verdad.** `NotFound` = "llegué a la caché y ese id no está"
> (p. ej. un enlace viejo). `Error` = "no pude confirmarlo porque la red falló". Modelarlos por
> separado permite a la UI ofrecer **reintentar** en el segundo caso y un mensaje definitivo en el
> primero. Es la misma disciplina de la 03 (distinguir `Loading` de `Empty`), llevada a la red.

---

## 9. La pantalla: pull-to-refresh y el botón de reintento

La `ArticlesScreen` envuelve su contenido en el **`PullToRefreshBox`** de Material 3, gobernado por el
`isRefreshing`/`refresh` del `ViewModel`, y añade el render del estado `Error`:

```kotlin
// ui/articles/ArticlesScreen.kt (resumen)
@OptIn(ExperimentalMaterial3Api::class)
PullToRefreshBox(isRefreshing = isRefreshing, onRefresh = onRefresh) {
    when (uiState) {
        is ArticlesUiState.Loading  -> Unit
        is ArticlesUiState.Empty    -> EmptyArticles(...)
        is ArticlesUiState.Error    -> ErrorArticles(onRetry = onRefresh, ...)   // mensaje + botón
        is ArticlesUiState.Content  -> ArticleList(uiState.articles, ...)
    }
}
```

`PullToRefreshBox` (aún `@ExperimentalMaterial3Api`, hay que hacer *opt-in*) reconoce el gesto de
deslizar hacia abajo y llama a `onRefresh`, mostrando su ruedita mientras `isRefreshing` sea `true`. El
estado de error es un mensaje corto más un `Button` de **Reintentar** que llama a la misma `refresh`.
Los textos salen de `strings.xml` en ambos idiomas (feature 08):

```xml
<!-- res/values/strings.xml (es) -->
<string name="articles_error">No se han podido cargar los artículos.</string>
<string name="articles_retry">Reintentar</string>
<!-- res/values-en/strings.xml (en) -->
<string name="articles_error">Couldn\'t load the articles.</string>
<string name="articles_retry">Retry</string>
```

El cableado en `MainActivity` es un cambio de una línea gracias al *seam*: solo se sustituye la
implementación que se construye.

```kotlin
// MainActivity.kt (resumen)
val database = NeverLateDatabase.getInstance(applicationContext)
val articleRepository: ArticleRepository =
    CachingArticleRepository(ArticlesNetwork.create(), database.articleDao())
```

`AppViewModelFactory` **no cambia de firma**: sigue recibiendo un `ArticleRepository`. Los `ViewModel`
tampoco cambian por fuera. Ese es el pago de haber programado contra una interfaz.

---

## 10. Dependencias y permiso

En el catálogo de versiones (`gradle/libs.versions.toml`) añadimos, con sus versiones (nunca se fijan
a mano en `build.gradle.kts`):

- **Retrofit** (`com.squareup.retrofit2:retrofit`, 2.11.0) — cliente HTTP tipado.
- **OkHttp** + **logging-interceptor** (`com.squareup.okhttp3`, 4.12.0) — motor HTTP y logging.
- **retrofit2-kotlinx-serialization-converter** (`com.jakewharton.retrofit`, 1.0.0) — el puente para
  reutilizar `kotlinx.serialization`.
- **mockwebserver** (`com.squareup.okhttp3:mockwebserver`, solo tests) — servidor HTTP falso.

Ya estaban (de lecciones anteriores): Room, `kotlinx-serialization-json` + su plugin, corrutinas.

Y en el manifest, un permiso nuevo:

```xml
<uses-permission android:name="android.permission.INTERNET" />
```

> **Concepto — permiso normal vs. peligroso.** `INTERNET` es un permiso **normal**: se concede en la
> instalación y **no** se pide en runtime, a diferencia de `POST_NOTIFICATIONS` (06), que sí requería
> una petición al usuario. No todos los permisos se tratan igual. Como GitHub raw es HTTPS, tampoco
> hace falta habilitar tráfico en claro (`usesCleartextTraffic`).

---

## 11. Tests

Fiel al patrón del proyecto, **el grueso se testea en JVM puro** (sin emulador): las funciones puras y
la red se prueban con un **`MockWebServer`**, y los `ViewModel` con un *fake* del repositorio.

- `ArticleDtoTest.kt` — `toDomain()` (mapea `article_id`→`id`, `content`→`body`), la deserialización con
  `@SerialName`/`ignoreUnknownKeys`, y `summarize()` a fondo (frase corta tal cual, corte por frase,
  corte por palabra con "…", palabra única demasiado larga, y los bordes 120/160, además de
  vacío/espacios).
- `ArticleEntityTest.kt` — el *round-trip* `Article ↔ ArticleEntity`.
- `CachingArticleRepositoryTest.kt` — el `ArticlesApi` **real** (vía `ArticlesNetwork.create` apuntando
  al `MockWebServer`) con un **`FakeArticleDao` en memoria** (implementa la interfaz `ArticleDao` con
  un mapa, evitando Room/Robolectric en JVM): 200 con el JSON de la API → `Success` y la caché queda
  poblada; 500 o socket caído o JSON corrupto → `Error` y la caché **anterior se preserva**;
  `getArticleById` presente/ausente.
- `ArticlesViewModelTest.kt` / `ArticleDetailViewModelTest.kt` — los `FakeArticleRepository` ahora
  implementan `refresh()` (configurable a `Success`/`Error`) para dirigir todas las rutas: offline con
  caché (error de refresco → sigue en `Content`), vacío real → `Empty`, error sin caché → `Error`,
  reintento que se recupera a `Content`, `isRefreshing` solo activo durante el refresco; y en el
  detalle, `NotFound` vs `Error` vs `retry()`.
- Tests de UI Compose (`androidTest`) — el estado `Error` muestra su mensaje y un botón **Reintentar**
  que invoca el callback; se mantienen las aserciones de `Loading`/`Empty`/`Content`/`NotFound` con las
  nuevas firmas.

```bash
# Tests unitarios (JVM, sin emulador) — incluye la red con MockWebServer
./gradlew :app:testDebugUnitTest

# Compila el APK y los tests instrumentados
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
```

> **Concepto — testear la red sin red.** `MockWebServer` levanta un servidor HTTP **local** al que le
> dices exactamente qué responder (un 200 con tu JSON, un 500, cerrar el socket…). Así puedes probar
> "qué pasa cuando el servidor falla" de forma **determinista y offline**, sin depender de una URL real
> ni de tener conexión. La clave que lo hace posible es el parámetro `baseUrl` de `ArticlesNetwork.create`.

---

## 12. Probar la app

1. Abre **Artículos**: verás una carga breve y luego la lista descargada (una vez este código esté en
   `master`, que es de donde GitHub raw sirve el JSON).
2. **Desliza hacia abajo** en la lista: aparece la ruedita de pull-to-refresh y se vuelve a descargar.
3. Abre un artículo: el **detalle** se lee de la caché. Vuelve atrás y activa el **modo avión**; los
   artículos ya cargados siguen viéndose (caché offline).
4. Con **modo avión desde el primer arranque** (caché vacía y sin red), la lista muestra el estado de
   **error** con **Reintentar**. Desactiva el avión y pulsa **Reintentar**: aparece el contenido.
5. Fíjate en **Logcat** (build debug) para ver el `HttpLoggingInterceptor` volcando cada petición y
   respuesta.

---

## Documentación oficial

- **Retrofit** — [Retrofit](https://square.github.io/retrofit/)
- **OkHttp** — [OkHttp](https://square.github.io/okhttp/)
  · [Interceptors](https://square.github.io/okhttp/features/interceptors/)
- **`kotlinx.serialization`** — [Serialization (Kotlin)](https://kotlinlang.org/docs/serialization.html)
- **Conectar a la red** — [Connect to the network](https://developer.android.com/develop/connectivity/network-ops/connecting)
- **Room como caché** — [Save data with Room](https://developer.android.com/training/data-storage/room)
- **Pull-to-refresh** — [Pull to refresh (Compose)](https://developer.android.com/develop/ui/compose/components/pull-to-refresh)

---

## 13. Siguiente paso

La app ya **lee** de la red y funciona offline. Quedaron **fuera de alcance** a propósito (ver la
spec): **escribir** en un backend, autenticación, paginación, invalidación por tiempo (TTL) y
sincronización en segundo plano. El siguiente paso natural es la **feature 11 — base de datos remota**
(`docs/prompts/11-bbdd-remota.md`), donde los datos dejan de ser de solo lectura: habrá que decidir
cómo **sincronizar** cambios en ambos sentidos y resolver conflictos. El *seam* que hemos respetado en
esta lección (dominio estable + repositorio tras interfaz) es justo lo que hará ese salto manejable.
