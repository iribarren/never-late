# Lección 04b — Buscador de tareas (corrutinas y `Flow` a fondo)

> 🚧 **Lección pendiente.** Este es un placeholder que reserva el número **04b** en la secuencia
> definitiva de tutoriales (ver [`README.md`](README.md) y
> [`docs/conceptos-pendientes.md`](../docs/conceptos-pendientes.md) §2). Se escribirá al implementar
> la feature siguiendo el flujo `/feature` — el prompt ya está listo en
> [`docs/prompts/04b-buscador-tareas.md`](../docs/prompts/04b-buscador-tareas.md).

## Qué enseñará

Los conceptos de corrutinas/`Flow` que la 04 usa pero no nombra: concurrencia estructurada,
`viewModelScope` y dispatchers, `async`/`await`, y sobre todo los **operadores de `Flow`**
(`debounce`, `combine`, `map`, `stateIn`), `StateFlow` vs `SharedFlow`, y el manejo de cancelación.

## Feature vehículo

Un **buscador de tareas**: un `TextField` cuyo texto (un `StateFlow`) pasa por `debounce` y se
`combine` con la lista de Room para filtrar en caliente.

## Prerrequisitos

Lección 04 (corrutinas, `Flow`, Room).
