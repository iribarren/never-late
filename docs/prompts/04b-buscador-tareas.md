# Feature 04b (extra) — Buscador de tareas (corrutinas y `Flow` a fondo)

Lee `CLAUDE.md` (**Tutorial Methodology** + **Mandatory Workflow**) y las lecciones previas (en
especial la 04: tareas con Room, `Flow` y `viewModelScope`; y la 03b: colecciones y lambdas).
Implementa **"buscar tareas en caliente con `debounce` + `combine`"** siguiendo el flujo `/feature`.

> **Profundiza en lo que la 04 usó sin nombrar.** La 04 introdujo `Flow` de forma pragmática; aquí se
> explican los operadores y la concurrencia estructurada con un caso real de búsqueda reactiva.

## Qué construir

- Un **`TextField` de búsqueda** en la pantalla de tareas cuyo texto se expone como `StateFlow`.
- El texto pasa por **`debounce`** (evitar filtrar en cada pulsación) y se **`combine`** con el
  `Flow` de tareas de Room para producir la lista filtrada, expuesta como estado de UI vía `stateIn`.
- Estado vacío coherente cuando la búsqueda no tiene resultados (reutilizando `MessageState` de la 17).
- El filtro se resuelve del lado de datos/dominio de forma **cancelable** (una búsqueda nueva cancela
  la anterior).

## Conceptos nuevos a enseñar (lección en español)

- **Concurrencia estructurada:** `CoroutineScope`, `viewModelScope`, dispatchers y por qué el trabajo
  se cancela con el scope.
- **Operadores de `Flow`:** `debounce`, `combine`, `map`, `distinctUntilChanged`, y `stateIn` para
  convertir un `Flow` frío en `StateFlow` caliente con estado inicial.
- **`StateFlow` vs `SharedFlow`** y por qué la búsqueda encaja en `StateFlow`.
- **Cancelación y `async`/`await`** (mención) para trabajo en paralelo.

## Notas

- Rama sugerida: `feature/task-search`.
- **Extiende, no dupliques:** parte del `TaskRepository`/`Flow<List<Task>>` que ya alimenta la lista;
  el buscador añade un operador sobre ese flujo, no un segundo origen. Reutiliza `MessageState` (17)
  para el "sin resultados".
- Mapea a `docs/conceptos-pendientes.md` §2 (Corrutinas y `Flow` a fondo). Sin backend, sin contrato,
  sin nueva dependencia.
- Ficheros: [`TasksScreen.kt`](../../app/src/main/java/com/neverlate/ui/tasks/TasksScreen.kt) y su
  `ViewModel`, más el seam de `TaskRepository`.
- Agentes: `mobile-engineer` (buscador + cableado del `Flow`), `qa-engineer` (tests con `runTest` +
  `TestDispatcher`: `debounce`, `combine`, sin resultados, cancelación). Lección en
  `tutorial/04b-buscador-tareas.md` (español), numerada como **04b** (entre la 04 y la 05).
