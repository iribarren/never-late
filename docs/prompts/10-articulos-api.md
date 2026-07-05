# Feature 10 (extra) — Artículos desde una API/Web

Lee `CLAUDE.md` (**Tutorial Methodology** + **Mandatory Workflow**) y las lecciones previas.
Requiere la feature de **artículos** (03). Implementa **"Artículos desde una API"** siguiendo el
flujo `/feature`.

## Qué construir

- Sustituir el contenido **empaquetado** de artículos por datos **remotos** (API REST pública o
  un endpoint propio simple).
- Manejar estados de **carga / error / vacío** y **caché offline**.

## Conceptos nuevos a enseñar (lección en español)

- **Networking** con **Retrofit** (o Ktor client): `suspend`, serialización JSON.
- **DTO** de red y **mapeo** a los modelos de dominio.
- Estados de UI (loading/success/error) con `ViewModel` + `StateFlow`.
- Caché offline con **Room** (single source of truth) y estrategia de refresco.

## Notas

- Rama sugerida: `feature/articles-api`.
- Añade Retrofit/OkHttp/serialization al catálogo `gradle/libs.versions.toml`; requiere permiso
  `INTERNET`.
- Introduce **networking** por primera vez: explícalo con calma en la lección.
- Tests con `qa-engineer` (repositorio con servidor mock). Lección en `tutorial/10-articulos-api.md`.
