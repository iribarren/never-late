# Feature 13c (extra) — Lista de artículos paginada con Paging 3

Lee `CLAUDE.md` (**Tutorial Methodology** + **Mandatory Workflow** + **API Contract**) y las
lecciones previas (en especial la 10: artículos desde la API + caché en Room; y la 11: backend +
sync). Implementa **"paginar los artículos desde el backend con Paging 3"** siguiendo el flujo
`/feature`.

> **De "todo de golpe" a página a página.** Hoy la lista de artículos se trae entera. Paging 3 carga
> incrementalmente al hacer scroll, con Room como caché — el patrón estándar para listas largas.

## Qué construir

- **Paginación en el backend** para artículos: `GET /articles?page=&size=` (o cursor), reflejado en
  el contrato.
- En el cliente, un **`RemoteMediator`** (red + Room como caché) o `PagingSource`, un `Pager` que
  expone `Flow<PagingData<Article>>`, y `collectAsLazyPagingItems()` en la lista.
- **Estados de carga/append/error** (spinner al final, retry) reutilizando el lenguaje de estados de
  la 17 donde encaje.

## Conceptos nuevos a enseñar (lección en español)

- **Paging 3:** `PagingSource` vs `RemoteMediator`, `Pager`/`PagingConfig`, `PagingData` como `Flow`.
- **Compose + Paging:** `collectAsLazyPagingItems()`, `itemKey`/`itemContentType`, y `loadState`
  (refresh/append/prepend) para spinners y errores.
- **Red + Room como single source of truth** en paginación (el `RemoteMediator`).
- **Contrato de paginación:** parámetros de página/cursor y forma de la respuesta.

## Notas

- Rama sugerida: `feature/articles-paging`.
- **Actualiza el contrato primero:** define el endpoint paginado de artículos en
  `docs/api/contract.md` antes del código, y ajusta el backend + `docs/articles-api/` si aplica.
- **Extiende, no dupliques:** parte de `ArticlesApi`/`CachingArticleRepository`/`ArticleDao` (10); el
  `RemoteMediator` reutiliza el mapeo `ArticleDto.toDomain()` y la Room ya existente.
- Mapea a `docs/conceptos-pendientes.md` §6 (Datos: paginación). Nueva dependencia: `androidx.paging`
  (runtime + compose) en el catálogo.
- Ficheros: `data/articles/` (API, dao, mediator, repo),
  [`ArticlesScreen.kt`](../../app/src/main/java/com/neverlate/ui/articles/ArticlesScreen.kt),
  `gradle/libs.versions.toml`, `docs/api/contract.md`, y el backend.
- Agentes: `backend-engineer` (endpoint paginado), `mobile-engineer` (Paging 3 + UI), `qa-engineer`
  (tests del `PagingSource`/mediator y de `loadState`). Lección en `tutorial/13c-paginacion.md`
  (español), numerada como **13c** (entre la 13 y la 14).
