# Lección 13c — Paginación con Paging 3 (artículos página a página)

Hasta ahora la lista de artículos se traía **entera de golpe**: una sola llamada
`GET("articles.json")` devolvía `List<ArticleDto>` con todo el catálogo, y `CachingArticleRepository`
lo volcaba entero en Room. Para un puñado de artículos funciona, pero es el patrón que **no escala**:
en una lista larga (cientos o miles de filas) descargarías y guardarías todo antes de mostrar nada,
gastando datos, memoria y tiempo en filas que quizá el usuario nunca llegue a ver.

Esta lección cambia ese "todo de golpe" por **carga incremental**: se descarga la **primera página**,
se muestra, y a medida que el usuario hace scroll se piden las siguientes — con **Room como caché** por
debajo, para que lo ya descargado siga disponible sin red (y, como manda el modo invitado de la 13, sin
cuenta). La herramienta estándar de Android para esto es **Jetpack Paging 3**.

De paso introduce el **primer endpoint público** del backend: los artículos pasan de un fichero estático
servido por GitHub raw (lección 10) a un endpoint **paginado de verdad** en el Ktor, `GET /articles?page=&size=`.

## Conceptos que aprendes aquí

Partiendo de la 10 (red con Retrofit + `ArticleDto` ≠ modelo de dominio + Room como caché), la 11
(backend Ktor + Room como *single source of truth* del lado sync) y la 13b (migraciones de Room reales):

- **Paging 3 y su doble responsabilidad:** `PagingSource` (leer páginas de una fuente, aquí Room) vs
  `RemoteMediator` (traer páginas de la red **hacia** la caché). Por qué una app red+BBDD necesita las dos.
- **`Pager` / `PagingConfig`:** cómo se ensamblan las dos mitades y qué controla `pageSize`.
- **`PagingData` como `Flow`:** el estado de la lista es un **stream** que va emitiendo, no un `List`
  de una sola vez; `cachedIn(viewModelScope)` para sobrevivir a rotaciones.
- **Compose + Paging:** `collectAsLazyPagingItems()`, `itemKey` / `itemContentType`, y `loadState`
  (refresh / append / prepend) para spinners y reintentos.
- **Red + Room como *single source of truth* al paginar:** el `RemoteMediator` **escribe** en Room; el
  `PagingSource` solo **lee** de Room. El offline-first sale gratis.
- **El contrato de paginación:** parámetros `page`/`size` y la forma de la respuesta, y cómo se traducen
  a `endOfPaginationReached`.

---

## 1. El problema: una lista no se carga entera

La versión de la 10 tenía tres piezas:

```kotlin
// Antes (10/13b): todo el catálogo en una llamada y en una lista.
@GET("articles.json") suspend fun getArticles(): List<ArticleDto>          // red
suspend fun getArticles(): List<Article>                                   // repo: lee la caché entera
suspend fun refresh(): RefreshResult                                       // repo: baja todo y reescribe
```

El `ViewModel` llamaba primero a `getArticles()` (cache) y luego a `refresh()` (red) — el patrón
*stale-while-revalidate* que enseñó la 10. Simple, pero **carga el catálogo completo** en cada refresh.
Paginar a mano ese esquema (llevar la cuenta de la página actual, concatenar listas, saber cuándo parar,
mostrar un spinner al final, reintentar solo la última página…) es mucho código repetitivo y fácil de
equivocar. **Paging 3 es precisamente esa fontanería, ya escrita y probada.**

---

## 2. Las dos mitades de Paging 3

La idea central de Paging 3 en una app que combina **red y base de datos** es partir la
responsabilidad en dos:

| Mitad | Quién | Qué hace |
|---|---|---|
| **Lectura local** | `PagingSource` | Lee filas de una fuente *ya materializada*. Aquí lo genera Room a partir del DAO: solo lee de la tabla `articles`. No sabe nada de HTTP. |
| **Traer de la red** | `RemoteMediator` | Cuando a la mitad local se le acaban las filas, va a la red, **escribe** la página nueva en Room, y deja que la mitad local la vuelva a leer. |

Es exactamente el mismo reparto "la red **escribe**, la UI **lee** la caché" que ya defendía
`CachingArticleRepository` en la 10 — pero ahora expresado a través de los puntos de extensión de Paging
en lugar de un `refresh()` a mano. La UI nunca toca la red directamente: lee un `PagingSource` que solo
mira Room.

> Si la fuente fuera **solo** red (sin caché) usarías un `PagingSource` propio y ya está. Si fuera
> **solo** Room (sin red) te bastaría el `PagingSource` que Room genera. La combinación red+Room es la
> que pide el `RemoteMediator` — y es la más común en apps reales.

---

## 3. El contrato de paginación primero

Como siempre en este proyecto desde la 11, el **contrato manda** y se escribe **antes** que el código
([`docs/api/contract.md`](../docs/api/contract.md) §7). El endpoint nuevo:

```
GET /articles?page=<int>&size=<int>      (PÚBLICO, sin cabecera Authorization)
```

Respuesta:

```json
{ "items": [ ArticleDto, ... ], "page": 0, "size": 20, "total": 42 }
```

Dos decisiones del contrato que conviene entender:

- **Página/offset, no cursor.** Para un catálogo **pequeño, estático y solo-crece**, `page`/`size` es la
  forma correcta: se traduce directamente a las claves enteras que el `RemoteMediator` guarda por
  artículo (`prevKey`/`nextKey`), y `endOfPaginationReached` es un simple `items.size < size`. Un
  **cursor** opaco (`nextCursor`) es mejor para *feeds* grandes que cambian mucho (donde los offsets se
  desplazan), pero aquí solo añadiría complejidad sin beneficio.
- **Endpoint público.** Es el **primer y único** endpoint sin auth del backend. La razón es el **modo
  invitado** (13): los artículos deben leerse **sin cuenta**, igual que la vieja descarga de GitHub raw
  no pedía token. En el Ktor eso significa registrarlo **fuera** del bloque `authenticate("auth-jwt")`.

---

## 4. La red: `ArticlesApi` paginado

`ArticlesApi` cambia su única función a una **paginada**
([`ArticlesApi.kt`](../app/src/main/java/com/neverlate/data/articles/ArticlesApi.kt)):

```kotlin
@GET("articles")
suspend fun getArticles(
    @Query("page") page: Int,
    @Query("size") size: Int,
): ArticlesPageDto
```

`@Query` añade cada parámetro a la URL (`/articles?page=0&size=20`). El tipo de retorno es la nueva
envoltura de página, no ya una `List` pelada
([`ArticlesPageDto.kt`](../app/src/main/java/com/neverlate/data/articles/ArticlesPageDto.kt)):

```kotlin
@Serializable
data class ArticlesPageDto(
    val items: List<ArticleDto>,   // los artículos de esta página, en orden estable del servidor
    val page: Int,                 // el índice (base 0) que respondemos
    val size: Int,                 // el tamaño de página que el servidor USÓ (tras recortar)
    val total: Int,                // total del catálogo (contraste para endOfPaginationReached)
)
```

Fíjate en que `ArticleDto` **no cambia** (`article_id`/`content`, sin `summary`): sigue siendo la forma
de wire de la 10, y se sigue mapeando con el mismo `ArticleDto.toDomain()`. Solo lo **envolvemos** en la
metadata de paginación. Y `ArticlesRemoteMediator` es el **único** que llama a este endpoint — ningún
ViewModel ni pantalla lo toca directamente.

Un detalle: `ArticlesNetwork` ahora apunta el `baseUrl` por defecto al **backend**
(`BuildConfig.BACKEND_BASE_URL`, el mismo `http://10.0.2.2:8080/` que usa la sync), no a GitHub raw.

---

## 5. La caché: Room, con un orden estable

La mitad local es el `PagingSource` que **Room genera** por ti. Basta con declarar en el DAO un método
que devuelva `PagingSource` en vez de `List`
([`ArticleDao.kt`](../app/src/main/java/com/neverlate/data/articles/ArticleDao.kt)):

```kotlin
@Query("SELECT * FROM articles ORDER BY remoteOrder ASC")
fun pagingSource(): PagingSource<Int, ArticleEntity>   // ← NO es suspend: lo maneja el Pager
```

Aquí aparece la sutileza más importante de paginar desde Room: **SQLite no tiene orden de fila inherente**.
Mientras bajábamos el catálogo entero de una vez, el orden de inserción bastaba. Al llegar **por páginas**,
si no guardamos explícitamente el orden del servidor, `pagingSource()` podría devolver filas
desordenadas — saltos o huecos visibles al hacer scroll. Por eso `ArticleEntity` gana una columna nueva
([`ArticleEntity.kt`](../app/src/main/java/com/neverlate/data/articles/ArticleEntity.kt)):

```kotlin
@Entity(tableName = "articles")
data class ArticleEntity(
    @PrimaryKey val id: String,
    val title: String,
    val summary: String,
    val body: String,
    val remoteOrder: Int = 0,   // ← posición GLOBAL del artículo en el catálogo
)
```

El `RemoteMediator` fija `remoteOrder` a la posición **global** del artículo (`page * size + índice`), y
`ORDER BY remoteOrder ASC` hace que el orden local calque el del servidor.

### La tabla de "remote keys"

Paging necesita saber, dado el último artículo cargado, **qué página pedir a continuación**. Eso se
guarda en una **segunda tabla** dedicada
([`ArticleRemoteKeys.kt`](../app/src/main/java/com/neverlate/data/articles/ArticleRemoteKeys.kt)):

```kotlin
@Entity(tableName = "article_remote_keys")
data class ArticleRemoteKeys(
    @PrimaryKey val articleId: String,
    val prevKey: Int?,   // página anterior, o null si estaba en la página 0
    val nextKey: Int?,   // página siguiente, o null en la última página
)
```

Es una tabla aparte (y no unas columnas más en `ArticleEntity`) porque modela **otra cosa**: `ArticleEntity`
es el *contenido* del artículo; `ArticleRemoteKeys` es metadata de paginación **puramente local**, que no
significa nada en el wire ni tiene equivalente en el modelo de dominio `Article`.

---

## 6. El `RemoteMediator`: el corazón de la lección

Aquí vive toda la lógica de "traer la página siguiente"
([`ArticlesRemoteMediator.kt`](../app/src/main/java/com/neverlate/data/articles/ArticlesRemoteMediator.kt)).
Paging llama a `load(loadType, state)` con uno de tres `LoadType`, según cómo se mueva el usuario:

```kotlin
override suspend fun load(loadType: LoadType, state: PagingState<Int, ArticleEntity>): MediatorResult {
    val page = when (loadType) {
        LoadType.REFRESH -> 0                 // primera carga o pull-to-refresh: siempre página 0
        LoadType.PREPEND -> return MediatorResult.Success(endOfPaginationReached = true)  // no paginamos hacia arriba
        LoadType.APPEND -> {                  // el usuario llegó cerca del final
            val lastItem = state.lastItemOrNull()
                ?: return MediatorResult.Success(endOfPaginationReached = true)
            remoteKeysDao.remoteKeysByArticleId(lastItem.id)?.nextKey
                ?: return MediatorResult.Success(endOfPaginationReached = true)
        }
    }
    ...
}
```

- **REFRESH**: primera carga, o cuando el usuario tira para refrescar (`LazyPagingItems.refresh()`).
  Siempre (re)pide la página 0.
- **APPEND**: el usuario hizo scroll cerca del final (lo gobierna `PagingConfig.prefetchDistance`). La
  página a pedir sale del `nextKey` que guardamos para el último artículo cacheado. Si no hay `nextKey`,
  ya estamos en la última página → `endOfPaginationReached`.
- **PREPEND**: hacer scroll *hacia arriba* más allá del primer ítem. Este catálogo no tiene "página antes
  de la 0" (la feature es unidireccional a propósito), así que devuelve `endOfPaginationReached = true` de
  inmediato, **sin llamar a la red**.

Luego, la parte que pide y **escribe**:

```kotlin
val response = api.getArticles(page = page, size = state.config.pageSize)
val endOfPaginationReached = response.items.size < response.size   // una página corta = no hay más

database.withTransaction {                       // ← TODO o NADA
    if (loadType == LoadType.REFRESH) {          // en REFRESH vaciamos antes de repoblar
        articleDao.clear()
        remoteKeysDao.clear()
    }
    val prevKey = if (page == 0) null else page - 1
    val nextKey = if (endOfPaginationReached) null else page + 1

    remoteKeysDao.insertAll(response.items.map { ArticleRemoteKeys(it.articleId, prevKey, nextKey) })
    articleDao.upsertAll(
        response.items.mapIndexed { i, dto ->
            dto.toDomain().toEntity(remoteOrder = page * state.config.pageSize + i)  // posición GLOBAL
        },
    )
}
return MediatorResult.Success(endOfPaginationReached = endOfPaginationReached)
```

Tres cosas clave:

1. **`endOfPaginationReached = items.size < size`.** Si el servidor devuelve menos ítems que el tamaño de
   página pedido, es que era la última página. `total` está en la respuesta como contraste, pero este
   chequeo se basta solo.
2. **Todo dentro de un `database.withTransaction { }`.** Borrar + repoblar (REFRESH) o añadir (APPEND) van
   en **una** transacción: si el proceso muere a mitad, o entró la página entera (artículos **y** sus
   remote keys) o no entró nada. Nunca una página a medias que desincronice `remoteOrder` de las claves.
3. **`remoteOrder` es la posición global** (`page * size + i`), no el índice dentro de la página. Es lo
   que mantiene `ORDER BY remoteOrder` estable entre páginas.

El `catch` es idéntico en espíritu al `refresh()` de la 10: captura `IOException` (sin red), `HttpException`
(respuesta no-2xx) y `SerializationException` (JSON inesperado) devolviendo `MediatorResult.Error`, pero
**no** `Throwable` — para no tragarse la `CancellationException` de la que dependen las corrutinas para
cancelarse. Un `Error` aquí es lo que la UI verá como estado de error/reintento.

Además, `initialize()` devuelve `LAUNCH_INITIAL_REFRESH`: al abrir la pantalla estando online, se re-pide
la página 0 en vez de confiar en lo que Room tuviera de una sesión anterior.

> `@OptIn(ExperimentalPagingApi::class)`: `RemoteMediator` sigue marcado como experimental *upstream*
> aunque es la forma documentada y estándar de respaldar un `Pager` con red **y** caché.

---

## 7. Ensamblar el `Pager`

Las dos mitades se unen en `CachingArticleRepository`, que ahora expone un `Flow<PagingData<Article>>`
([`CachingArticleRepository.kt`](../app/src/main/java/com/neverlate/data/articles/CachingArticleRepository.kt)):

```kotlin
override fun articlesPager(): Flow<PagingData<Article>> = Pager(
    config = PagingConfig(pageSize = ARTICLES_PAGE_SIZE, enablePlaceholders = false),
    remoteMediator = ArticlesRemoteMediator(api, database),   // la mitad de red
    pagingSourceFactory = { dao.pagingSource() },             // la mitad local
).flow.map { pagingData -> pagingData.map { it.toDomain() } }  // ArticleEntity -> Article
```

- **`PagingConfig(pageSize = 20)`** debe coincidir con el `size` que el mediator pide a la red: un `Pager`
  que leyera de Room en trozos más pequeños que lo que el mediator escribe por página funcionaría, pero
  no tendría sentido.
- **`.map { it.toDomain() }`** (el de `androidx.paging`) transforma cada `ArticleEntity` de la caché al
  `Article` de dominio, sin que la UI vea nunca la entidad de Room.

El repo pierde `getArticles()`/`refresh()`/`RefreshResult`: Paging es ahora el **único** mecanismo de
carga y refresco. Conserva `getArticleById(id)`, que la pantalla de detalle sigue usando para leer un
artículo suelto de la misma caché que el mediator rellena. (Ojo: ahora recibe el `NeverLateDatabase`
entero en el constructor, no un `ArticleDao` suelto, porque el mediator necesita la base de datos para el
`withTransaction`.)

El `ViewModel` queda casi vacío
([`ArticlesViewModel.kt`](../app/src/main/java/com/neverlate/ui/articles/ArticlesViewModel.kt)):

```kotlin
val articles: Flow<PagingData<Article>> = repository.articlesPager().cachedIn(viewModelScope)
```

`cachedIn(viewModelScope)` comparte un mismo stream de `PagingData` a través de cambios de configuración
(p. ej. una rotación de pantalla): sin él, cada recomposición que re-colecte `articles` reiniciaría la
paginación desde la página 0. Todo el `ArticlesUiState` (loading/content/empty/error) y el `loadThenRefresh`
que teníamos desaparecen: ese estado ahora sale del `loadState` de Paging.

---

## 8. Compose + Paging: `collectAsLazyPagingItems` y `loadState`

En la pantalla, el `Flow<PagingData>` se colecta con el puente de Compose
([`ArticlesScreen.kt`](../app/src/main/java/com/neverlate/ui/articles/ArticlesScreen.kt)):

```kotlin
val articles = viewModel.articles.collectAsLazyPagingItems()
```

`LazyPagingItems` te da algo que puedes **indexar como una lista** (`articles[index]`, `articles.itemCount`)
mientras Paging sigue cargando páginas por detrás. Se pinta con el `LazyColumn` y su overload de
`count`/`key`/`contentType`:

```kotlin
items(
    count = articles.itemCount,
    key = articles.itemKey { it.id },              // clave estable -> recomposición y animateItem() correctas
    contentType = articles.itemContentType { "article" },
) { index ->
    val article = articles[index]
    if (article != null) {
        ArticleRow(article = article, onClick = { onArticleClick(article.id) },
                   modifier = Modifier.animateItem())
    }
}
```

- **`itemKey { it.id }`** da a cada fila una identidad estable (el `id` del artículo). Es lo que hace que
  el `animateItem()` de la 17 funcione y que Compose no se confunda al insertar filas nuevas al final.
- **`itemContentType`** ayuda a Compose a reciclar composables entre ítems del mismo tipo.
- **`articles[index]` puede ser `null`** (un *placeholder* de una fila aún no cargada); por eso el
  `if (article != null)`.

Lo que en la 13b era un `ArticlesUiState` calculado a mano, aquí sale de **`articles.loadState`**, que
tiene tres facetas — `refresh`, `append` y `prepend`:

```kotlin
val refreshState = articles.loadState.refresh
val isRefreshing = refreshState is LoadState.Loading   // ← alimenta el PullToRefreshBox

when {
    refreshState is LoadState.Loading && articles.itemCount == 0 -> Unit  // no parpadear el vacío
    refreshState is LoadState.Error   && articles.itemCount == 0 ->
        MessageState(icon = ..., message = ..., actionLabel = ..., onAction = { articles.retry() })  // error a pantalla completa
    articles.itemCount == 0 -> MessageState(...)   // cargó bien pero el catálogo está vacío
    else -> ArticleList(articles, ...)
}
```

Y el estado de **append** (cargar la página siguiente) se pinta como **última fila** de la lista:

```kotlin
when (articles.loadState.append) {
    is LoadState.Loading -> item { AppendLoadingRow() }               // spinner al final
    is LoadState.Error   -> item { AppendErrorRow(onRetry = { articles.retry() }) }  // reintento en línea
    is LoadState.NotLoading -> Unit
}
```

La diferencia entre los dos errores es didáctica:

- **Error de `refresh` con la lista vacía** → `MessageState` a pantalla completa (no hay nada que mostrar).
  El botón reintentar re-lanza el mismo `RemoteMediator.REFRESH` que el pull-to-refresh.
- **Error de `append`** → una fila compacta al final con un botón *Reintentar* (`llamará a
  `articles.retry()`), **sin** descartar las filas ya visibles. El botón usa
  `Modifier.minimumInteractiveComponentSize()` para respetar el objetivo táctil ≥48dp de la 18.

Reutilizamos el `MessageState` de la 17 para los estados vacío/error de pantalla completa, y el
`PullToRefreshBox` para el gesto de tirar-para-refrescar, que llama a `articles.refresh()`. Las cadenas
nuevas (`articles_append_error`, la descripción del spinner) van en `strings.xml` (español) y
`values-en/strings.xml` (inglés), siguiendo la i18n de la 08.

---

## 9. La migración 5 → 6

Añadir la tabla `article_remote_keys` y la columna `remoteOrder` cambia el esquema, así que
`NeverLateDatabase` sube de `version = 5` a `6`, con una `MIGRATION_5_6` **aditiva** al estilo de la 13b
([`NeverLateDatabase.kt`](../app/src/main/java/com/neverlate/data/tasks/NeverLateDatabase.kt)):

```kotlin
val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS article_remote_keys (" +
                "articleId TEXT NOT NULL PRIMARY KEY, prevKey INTEGER, nextKey INTEGER)",
        )
        db.execSQL("ALTER TABLE articles ADD COLUMN remoteOrder INTEGER NOT NULL DEFAULT 0")
    }
}
```

¿Por qué **aditiva** y no destructiva, si `articles` es solo una caché que se repuebla de la red? Porque
esta base de datos **también** guarda `tasks`/`task_outbox`, y desde la 13 (modo invitado) **las tareas de
un invitado viven solo en el dispositivo**. Un `fallbackToDestructiveMigration` aquí las borraría como
efecto colateral de un cambio que solo tocaba artículos. Es exactamente el razonamiento que la 13b ya
documentó — repásala para el detalle completo de `exportSchema`, el `6.json` commiteado y el test con
`MigrationTestHelper` que prueba que las filas sobreviven al salto 5 → 6.

`remoteOrder` arranca en `0` para las filas viejas: se re-ordenarán (y la caché se rellenará desde la
página 0) la próxima vez que el mediator haga un REFRESH.

---

## 10. El backend público

El catálogo deja de ser un fichero estático de GitHub raw y pasa a un endpoint del Ktor, con la misma
estructura que el dominio `tasks` (Model / Repository + impl Postgres e InMemory / Service / Routes):

- **`ArticleService`** valida y **recorta** los parámetros: `page` por defecto 0 (negativo → `400`), `size`
  por defecto 20 y **recortado a `1..100`**; calcula `offset = page * size` y arma `ArticlesPage` con
  `total = count()`.
- **`ArticleRoutes`** registra `get("/articles")` **fuera** del bloque `authenticate("auth-jwt")` que
  envuelve a `taskRoutes` — ese es el mecanismo concreto que lo hace **público** (modo invitado).
- **La semilla:** al arrancar, si la tabla `articles` está vacía, se inserta el catálogo desde un recurso
  del *classpath* (`backend/src/main/resources/seed/articles.json`), asignando un `position` estable por el
  orden del array — el orden total que el contrato garantiza y del que depende que las páginas no se
  solapen ni salten. El fichero histórico `docs/articles-api/articles.json` queda como **contenido
  canónico** del que se copia la semilla (ver su `README`).

El endpoint sirve un catálogo **global de solo lectura**: no lee ni confía en ningún token, y no expone
nada por-usuario. Crear/editar/borrar artículos por HTTP queda fuera de alcance en v1.

---

## Repaso: ficheros de la feature

**Contrato** · [`docs/api/contract.md`](../docs/api/contract.md) §7 (endpoint público paginado).

**Cliente (`app/`)**
- [`ArticlesApi.kt`](../app/src/main/java/com/neverlate/data/articles/ArticlesApi.kt) — `@GET("articles")` con `@Query`.
- [`ArticlesPageDto.kt`](../app/src/main/java/com/neverlate/data/articles/ArticlesPageDto.kt) — la respuesta de página.
- [`ArticlesNetwork.kt`](../app/src/main/java/com/neverlate/data/articles/ArticlesNetwork.kt) — base URL al backend.
- [`ArticleEntity.kt`](../app/src/main/java/com/neverlate/data/articles/ArticleEntity.kt) — columna `remoteOrder`.
- [`ArticleDao.kt`](../app/src/main/java/com/neverlate/data/articles/ArticleDao.kt) — `pagingSource(): PagingSource`.
- [`ArticleRemoteKeys.kt`](../app/src/main/java/com/neverlate/data/articles/ArticleRemoteKeys.kt) + [`ArticleRemoteKeysDao.kt`](../app/src/main/java/com/neverlate/data/articles/ArticleRemoteKeysDao.kt) — la tabla de claves.
- [`ArticlesRemoteMediator.kt`](../app/src/main/java/com/neverlate/data/articles/ArticlesRemoteMediator.kt) — la mitad de red.
- [`CachingArticleRepository.kt`](../app/src/main/java/com/neverlate/data/articles/CachingArticleRepository.kt) — el `Pager`.
- [`ArticlesViewModel.kt`](../app/src/main/java/com/neverlate/ui/articles/ArticlesViewModel.kt) + [`ArticlesScreen.kt`](../app/src/main/java/com/neverlate/ui/articles/ArticlesScreen.kt) — `collectAsLazyPagingItems` + `loadState`.
- [`NeverLateDatabase.kt`](../app/src/main/java/com/neverlate/data/tasks/NeverLateDatabase.kt) — `version = 6`, `MIGRATION_5_6`.
- [`gradle/libs.versions.toml`](../gradle/libs.versions.toml) + [`app/build.gradle.kts`](../app/build.gradle.kts) — `androidx.paging` (runtime + compose) y `room-paging`.

**Backend (`backend/`)** · `articles/ArticleModels.kt`, `ArticleRepository.kt` (+ Postgres/InMemory),
`ArticleService.kt`, `ArticleRoutes.kt`, y `db/Database.kt` (tabla + semilla).

## Lo que te llevas

- Paginar es cargar **página a página al hacer scroll**, no el catálogo entero; es el patrón estándar
  para listas largas.
- Paging 3 parte la carga en dos mitades: **`PagingSource`** (leer de Room) y **`RemoteMediator`** (traer
  de la red **hacia** Room) — la misma regla "la red escribe, la UI lee la caché" de la 10, ahora con
  soporte de framework.
- Al paginar desde Room hace falta un **orden explícito** (`remoteOrder`), porque SQLite no tiene orden de
  fila propio, y una **tabla de remote keys** para saber qué página pedir después.
- El `RemoteMediator` escribe cada página **en una transacción** y calcula `endOfPaginationReached` a
  partir de la respuesta.
- En Compose, **`collectAsLazyPagingItems()` + `loadState`** te dan spinners de refresh/append y reintentos
  sin escribir tú la máquina de estados — reutilizando `MessageState` y `PullToRefreshBox` para lo demás.
- El contrato de paginación (`page`/`size` + forma de la respuesta) es una decisión de diseño: **offset**
  para catálogos pequeños/estables, **cursor** para *feeds* grandes que cambian.
```

