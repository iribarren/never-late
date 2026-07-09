# Feature 10b (extra) — Imagen de cabecera por artículo con Coil

Lee `CLAUDE.md` (**Tutorial Methodology** + **Mandatory Workflow** + **API Contract**) y las
lecciones previas (en especial la 10: artículos desde la API, Retrofit/OkHttp, `ArticleDto` ↔
`Article`, caché en Room; y la 03: pantalla de detalle de artículo). Implementa **"imagen de cabecera
por artículo cargada desde la API"** siguiendo el flujo `/feature`.

> **Cierra el hueco de imágenes remotas.** Hasta ahora la app solo pinta iconos vectoriales; nunca ha
> cargado una imagen de red. Coil es el estándar en Compose para hacerlo con caché y placeholders.

## Qué construir

- Un **campo de URL de imagen** en el `ArticleDto` (contrato) y su mapeo a `Article`/`ArticleEntity`.
- La **imagen de cabecera** en el detalle del artículo (y opcionalmente una miniatura en la lista)
  con `AsyncImage` de Coil: `placeholder`, estado de error y `contentScale` adecuado.
- `contentDescription` coherente (o `null` si es puramente decorativa junto al título).

## Conceptos nuevos a enseñar (lección en español)

- **Coil + Compose:** `AsyncImage`, `ImageRequest`, carga asíncrona desde URL.
- **Caché y estados:** caché en memoria/disco de Coil, `placeholder`/`error`, y por qué no bloquea la
  UI.
- **Añadir una dependencia vía version catalog:** `coil-compose` en `gradle/libs.versions.toml` (nunca
  hardcodear la versión).
- **Contrato primero:** un cambio en la forma del `ArticleDto` se refleja **antes** en
  `docs/api/contract.md` y luego en cliente y `docs/articles-api/articles.json`.

## Notas

- Rama sugerida: `feature/article-cover-image`.
- **Actualiza el contrato primero:** edita `docs/api/contract.md` (nuevo campo de imagen en el
  artículo) antes del código, y actualiza el JSON estático de `docs/articles-api/articles.json`.
- **Extiende, no dupliques:** reutiliza `ArticlesApi`/`CachingArticleRepository` y el mapeo
  `ArticleDto.toDomain()` ya existentes (10); solo se añade un campo y el render de la imagen.
- Mapea a `docs/conceptos-pendientes.md` §7 (Recursos y UI: Coil). Nueva dependencia: `coil-compose`.
- Ficheros: [`ArticleDto`/`ArticlesApi`](../../app/src/main/java/com/neverlate/data/articles/),
  [`Article.kt`](../../app/src/main/java/com/neverlate/data/articles/Article.kt),
  [`ArticleDetailScreen.kt`](../../app/src/main/java/com/neverlate/ui/articles/ArticleDetailScreen.kt),
  `gradle/libs.versions.toml`, `docs/api/contract.md`, `docs/articles-api/articles.json`.
- Agentes: `mobile-engineer` (Coil + mapeo + dependencia), `backend-engineer`/tú mismo para el
  contrato + JSON, `qa-engineer` (test de mapeo del nuevo campo y de que la lista/detalle no rompe sin
  imagen). Lección en `tutorial/10b-coil-imagenes.md` (español), numerada como **10b** (entre la 10 y
  la 11).
