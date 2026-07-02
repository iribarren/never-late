# Feature 04 — Tareas con contador de tiempo

> Pega este prompt en una sesión nueva de Claude Code para arrancar la feature.

Lee `CLAUDE.md` (**Tutorial Methodology** + **Mandatory Workflow**) y las lecciones previas.
Implementa la feature **"Tareas + contador de tiempo"** siguiendo el flujo `/feature`. Es el
núcleo de la app.

## Qué construir
- Crear, listar, editar y borrar **tareas** (título, duración estimada / fecha límite).
- Para una tarea, un **contador de tiempo** (cuenta atrás) que muestra el tiempo restante y se
  puede iniciar/pausar.
- Persistencia local de las tareas para que sobrevivan al cierre de la app.

## Conceptos nuevos a enseñar (lección `tutorial/04-*.md`, en español)
- **Room** (base de datos SQLite): `@Entity`, `@Dao`, `@Database`, consultas que devuelven `Flow`.
- CRUD completo desde un repositorio + `ViewModel`.
- **Coroutines** y `Flow` para el temporizador (cuenta atrás con `delay`).
- Estado más complejo en Compose y `collectAsStateWithLifecycle`.
- Formato de tiempo/fechas.

## Notas
- Rama sugerida: `feature/tasks-timer`.
- Añade Room y kotlin coroutines al catálogo `gradle/libs.versions.toml` (usa KSP para Room).
- Este repositorio de tareas lo reutilizarán el **widget** (feature 05) y el **lockscreen**
  (feature 06). Diséñalo pensando en compartirlo.
- Tests con `qa-engineer` (DAO in-memory + ViewModel + lógica del contador). Lección en
  `tutorial/04-tareas-contador.md` antes de commitear.
