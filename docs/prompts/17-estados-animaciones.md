# Feature 17 (extra) — Estados vacíos, animaciones y urgencia visual

Lee `CLAUDE.md` (**Tutorial Methodology** + **Mandatory Workflow**) y las lecciones previas (en
especial la 04: lista de tareas, cuenta atrás y `LazyColumn`; la 13: primer uso de `LaunchedEffect`).
Implementa **"micro-interacciones: estados vacíos, animaciones y señal de urgencia"** siguiendo el
flujo `/feature`.

## Qué construir

- Un **composable reutilizable de estado vacío/error** (icono + mensaje + acción) para las pantallas de
  Tareas y Artículos, en lugar del `Text` centrado actual.
- **Animaciones de lista:** `animateItem` en los `LazyColumn` para que las tareas aparezcan/desaparezcan
  con transición al crearlas, completarlas o borrarlas.
- **Feedback al crear una tarea:** un `Snackbar` "tarea creada" disparado con `LaunchedEffect`
  (aprovechando el `SnackbarHost` ya cableado en Home pero hoy sin uso).
- **Urgencia en la cuenta atrás:** color y/o `LinearProgressIndicator` que cambie según el tiempo
  restante (calmado → urgente → vencido), derivado del tiempo con `derivedStateOf`.

## Conceptos nuevos a enseñar (lección en español)

- **Animaciones en Compose:** `animate*AsState` (color/tamaño), `AnimatedVisibility` y `animateItem` en
  listas perezosas; qué se anima "gratis" y qué hay que orquestar.
- **Side-effects a fondo:** `LaunchedEffect` para eventos de una sola vez (mostrar un snackbar) y
  `derivedStateOf` para estado calculado (el nivel de urgencia) sin recomponer de más.
- **Estados vacíos/error como parte del diseño:** por qué un buen estado vacío guía al usuario, y cómo
  extraer un composable reutilizable con parámetros (icono, texto, `onAction`).

## Notas

- Rama sugerida: `feature/states-animations`.
- **Referencia visual:** abre `docs/mockups/rediseno-ux-ui.html` (la lista muestra la cuenta atrás
  coloreada por urgencia y la barra de progreso hacia el plazo). Es guía de dirección, **no** código a
  copiar.
- **Extiende, no dupliques:** el `SnackbarHost`/`SnackbarHostState` **ya está cableado** en Home; el
  `CountdownTicker` y `formatRemaining` ya calculan el tiempo restante — la urgencia se **deriva** de
  ellos, no se recalcula. Reutiliza el `when(uiState)` existente para enchufar el nuevo estado vacío.
- Mapea a conceptos que `docs/conceptos-pendientes.md` ya marcaba pendientes (animaciones §4,
  side-effects §4). Sin backend, sin contrato, sin nueva dependencia.
- Ficheros: [`TasksScreen.kt`](../../app/src/main/java/com/neverlate/ui/tasks/TasksScreen.kt),
  [`ArticlesScreen.kt`](../../app/src/main/java/com/neverlate/ui/articles/ArticlesScreen.kt),
  [`HomeScreen.kt`](../../app/src/main/java/com/neverlate/ui/home/HomeScreen.kt) (snackbar) + un nuevo
  composable de estado vacío reutilizable.
- Agentes: `mobile-engineer` (animaciones + estados), `qa-engineer` (tests de UI del estado vacío y de
  que el snackbar aparece tras crear). Lección en `tutorial/17-estados-animaciones.md` (español),
  numerada tras la 16.
