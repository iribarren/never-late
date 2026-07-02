# Feature 03 — Listado de artículos de gestión del tiempo

> Pega este prompt en una sesión nueva de Claude Code para arrancar la feature.

Lee `CLAUDE.md` (**Tutorial Methodology** + **Mandatory Workflow**) y las lecciones previas de
`tutorial/`. Implementa la feature **"Listado de artículos"** siguiendo el flujo `/feature`.

## Qué construir
- Pantalla con una **lista** de artículos sobre técnicas de gestión del tiempo (Pomodoro,
  time-blocking, regla de los 2 minutos…). Contenido **empaquetado** en la app (JSON en assets o
  datos en código), no remoto todavía.
- Pantalla de **detalle** del artículo al pulsar en uno.
- Acceso desde una opción de la Home.

## Conceptos nuevos a enseñar (lección `tutorial/03-*.md`, en español)
- `data class` para modelar un artículo.
- `LazyColumn` y renderizado eficiente de listas.
- Navegación **lista → detalle** pasando argumentos con Navigation Compose.
- Patrón **repositorio** simple (fuente de datos local) + lectura de **assets**.
- Separación UI / datos.

## Notas
- Rama sugerida: `feature/articles-list`.
- Deja el repositorio preparado para, en el futuro, cambiar la fuente local por una API
  (ver `docs/prompts/10-articulos-api.md`).
- Tests con `qa-engineer`. Lección en `tutorial/03-articulos.md` antes de commitear.
