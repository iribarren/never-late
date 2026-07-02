# Feature 05 — Widget de tareas pendientes

> Pega este prompt en una sesión nueva de Claude Code para arrancar la feature.

Lee `CLAUDE.md` (**Tutorial Methodology** + **Mandatory Workflow**) y las lecciones previas.
Requiere que exista ya la feature de **tareas** (04). Implementa la feature **"Widget de tareas
pendientes"** siguiendo el flujo `/feature`.

## Qué construir
- Un **widget de pantalla de inicio** que muestre las tareas pendientes con el **tiempo restante**
  para terminarlas.
- El widget se actualiza periódicamente y al cambiar las tareas.

## Conceptos nuevos a enseñar (lección `tutorial/04-*.md`, en español)
- **App Widgets** con **Glance** (Jetpack Compose para widgets).
- Cómo un widget lee datos de la app: compartir el **repositorio Room** de tareas.
- Actualización en segundo plano con **WorkManager** (y/o `updateAll`).
- Ciclo de vida y limitaciones de los widgets.

## Notas
- Rama sugerida: `feature/tasks-widget`.
- Añade Glance y WorkManager al catálogo `gradle/libs.versions.toml`.
- Tests con `qa-engineer` donde sea posible (lógica de datos del widget). Lección en
  `tutorial/04-widget.md` antes de commitear.
