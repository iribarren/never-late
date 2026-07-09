# Lección 04c — Testing (pantalla de estadísticas testeable)

> 🚧 **Lección pendiente.** Este es un placeholder que reserva el número **04c** en la secuencia
> definitiva de tutoriales (ver [`README.md`](README.md) y
> [`docs/conceptos-pendientes.md`](../docs/conceptos-pendientes.md) §3). Se escribirá al implementar
> la feature siguiendo el flujo `/feature` — el prompt ya está listo en
> [`docs/prompts/04c-testing-estadisticas.md`](../docs/prompts/04c-testing-estadisticas.md).

## Qué enseñará

La primera lección dedicada a **escribir tests**: tests unitarios JVM con JUnit y aserciones,
*test doubles*/fakes, tests de UI de Compose (`createComposeRule`, `onNodeWithText`, semántica) y
cómo probar corrutinas/`Flow` con `runTest` y `TestDispatcher`.

## Feature vehículo

Una **pantalla de estadísticas** ("tareas completadas esta semana", "% a tiempo") cuyo cálculo vive
en una función pura muy testeable, más un par de tests de UI de la pantalla.

## Prerrequisitos

Lección 04 (lógica pura de tiempo, `TaskTiming`).
