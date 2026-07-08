# Feature 19 (extra) — Barra de progreso hacia el plazo en las tarjetas de tarea

Lee `CLAUDE.md` (**Tutorial Methodology** + **Mandatory Workflow** + **Design in the Workflow**) y las
lecciones previas (en especial la 04: lista de tareas, cuenta atrás y `CountdownTicker`; y la 17: color
de urgencia con `urgencyLevelFor` + `derivedStateOf`, del que esta feature es continuación directa).
Implementa **"barra de progreso hacia el plazo en cada tarjeta de tarea"** siguiendo el flujo
`/feature`.

> **Cierra una `slice` diferida.** La 17 mostró la urgencia **solo con color** ("v1: no progress bar",
> ver `docs/mockups/README.md`). El maquetado sí lleva una barra de progreso por tarea; esta feature la
> implementa por fin y mueve esa fila a ✅ en la tabla de seguimiento.

## Qué construir

- Una **`LinearProgressIndicator` determinada** en cada tarjeta de tarea (`TaskRow`), que represente
  cuánto del tiempo hacia el `deadline` (o de la duración estimada) ya ha transcurrido: barra casi vacía
  al principio, casi llena al acercarse el plazo, llena/roja al vencer.
- La barra se **colorea reutilizando los colores de urgencia** de la 17 (`NeverLateExtras` + `error`),
  para que color y avance cuenten la misma historia de un vistazo.
- El avance se **anima** suavemente cuando cambia, en vez de saltar de golpe.
- **Accesibilidad:** el progreso se anuncia a los lectores de pantalla (no es solo decorativo), y nunca
  depende únicamente del color (el texto "Tiempo agotado" y el porcentaje siguen presentes).

## Conceptos nuevos a enseñar (lección en español)

- **Indicadores de progreso determinados:** `LinearProgressIndicator(progress = ...)`, la diferencia
  entre progreso **determinado** e **indeterminado**, y por qué el valor va en `0f..1f`.
- **Derivar el progreso de una función pura:** una helper testeable en `domain/tasks/` (estilo
  `urgencyLevelFor`/`ReminderPlanning`) que calcule la fracción transcurrida a partir de
  inicio/`deadline`/ahora, con sus casos límite (sin plazo, ya vencido, plazo en el futuro). Se prueba
  en la JVM sin Compose.
- **Animar un valor:** `animateFloatAsState` para que la barra transicione en vez de saltar, enlazando
  con las animaciones de la 17.
- **Semántica de progreso:** `Modifier.progressSemantics` / la semántica que ya aporta
  `LinearProgressIndicator`, y por qué un indicador visual necesita también anunciarse.

## Notas

- Rama sugerida: `feature/task-progress-bar`.
- **Diseño (obligatorio en el spec):** la sección **Visual & UX Design** debe nombrar la `slice`
  "Task-card time-elapsed progress bar" de `docs/mockups/README.md`, con criterios de aceptación
  visuales concretos (color por urgencia, animación, ≥48dp no aplica pero sí legibilidad a fuente
  grande). En el **Design review**, mover esa fila a ✅.
- **Referencia visual:** abre `docs/mockups/rediseno-ux-ui.html` (las tarjetas de tarea muestran la
  barra `.bar` bajo la cuenta atrás). Es guía de dirección, **no** código a copiar: tradúcela con los
  tokens reales del tema (`ui/theme/`).
- **Extiende, no dupliques:** el `CountdownTicker`/`formatRemaining` y `uiModel.remainingMillis` ya
  calculan el tiempo restante, y `urgencyLevelFor` ya deriva el nivel de urgencia — la fracción de
  progreso se **deriva** de esos mismos valores con `derivedStateOf`, no se recalcula por otra vía.
- Mapea a `docs/conceptos-pendientes.md` §4 (Compose avanzado / animaciones) y §7 (Recursos y UI). Sin
  backend, sin contrato, sin nueva dependencia.
- Ficheros: [`TasksScreen.kt`](../../app/src/main/java/com/neverlate/ui/tasks/TasksScreen.kt) (`TaskRow`)
  + una nueva función pura en `domain/tasks/` (p. ej. `DeadlineProgress.kt`).
- Agentes: `mobile-engineer` (barra + animación + semántica), `qa-engineer` (tests JVM de la función
  pura de progreso y test de UI de que la barra aparece). Lección en
  `tutorial/19-barra-progreso-tareas.md` (español), numerada tras la 18.
