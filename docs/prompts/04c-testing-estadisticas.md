# Feature 04c (extra) — Pantalla de estadísticas testeable (introducción al testing)

Lee `CLAUDE.md` (**Tutorial Methodology** + **Mandatory Workflow**) y las lecciones previas (en
especial la 04: tareas, cuenta atrás y lógica pura de tiempo en `TaskTiming`; y la 02: primeros tests
JVM). Implementa **"estadísticas de tareas con lógica pura muy testeable"** siguiendo el flujo
`/feature`.

> **La lección es el testing, la pantalla es la excusa.** El proyecto ya tiene `src/test` y
> `src/androidTest` y varias features escriben tests, pero ninguna lección los enseña como tema. Aquí
> se hace explícito: función pura → tests JVM; pantalla → test de UI de Compose.

## Qué construir

- Una **pantalla de estadísticas**: "tareas completadas esta semana", "% de tareas a tiempo",
  "pendientes por vencer", calculadas a partir de las tareas de Room.
- Todo el cálculo vive en una **función pura** en `domain/tasks/` (estilo `urgencyLevelFor`/
  `ReminderPlanning`), con `Clock`/`now` inyectable para ser determinista.
- La pantalla es stateless (estado hoisteado desde un `ViewModel` + `StateFlow`).
- **Tests** que acompañan la feature: unitarios JVM de la función pura (casos límite) y de UI de la
  pantalla.

## Conceptos nuevos a enseñar (lección en español)

- **Tests unitarios JVM con JUnit:** estructura *arrange/act/assert*, aserciones, y *test doubles*/
  fakes (p. ej. un `TaskRepository` falso).
- **Tests de UI de Compose:** `createComposeRule`, `onNodeWithText`/`onNodeWithContentDescription`,
  y la **semántica** como superficie de test.
- **Probar corrutinas/`Flow`:** `runTest`, `TestDispatcher`/`StandardTestDispatcher` y control del
  tiempo virtual.
- **Diseñar para testear:** por qué extraer una función pura con dependencias inyectables
  (`Clock`) hace el test trivial y determinista.

## Notas

- Rama sugerida: `feature/stats-testing`.
- **Extiende, no dupliques:** reutiliza el `TaskRepository` y las funciones puras de tiempo de la 04;
  la estadística es una función pura nueva, no lógica repartida por la UI.
- Mapea a `docs/conceptos-pendientes.md` §3 (Testing). Sin backend, sin contrato, sin nueva
  dependencia (las libs de test ya están en el catálogo).
- Ficheros: nueva pantalla en `ui/` + su `ViewModel`, función pura en
  [`domain/tasks/`](../../app/src/main/java/com/neverlate/domain/tasks/), y tests en `app/src/test/`
  y `app/src/androidTest/`.
- Agentes: `mobile-engineer` (pantalla + función pura), `qa-engineer` (protagonista: batería de tests
  JVM + UI que la lección explica). Lección en `tutorial/04c-testing-estadisticas.md` (español),
  numerada como **04c** (entre la 04 y la 05).
