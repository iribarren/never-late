# Lección 03b — Filtro y ordenación de la lista en memoria (fundamentos de Kotlin)

> 🚧 **Lección pendiente.** Este es un placeholder que reserva el número **03b** en la secuencia
> definitiva de tutoriales (ver [`README.md`](README.md) y
> [`docs/conceptos-pendientes.md`](../docs/conceptos-pendientes.md) §1). Se escribirá al implementar
> la feature siguiendo el flujo `/feature` — el prompt ya está listo en
> [`docs/prompts/03b-filtro-orden-memoria.md`](../docs/prompts/03b-filtro-orden-memoria.md).

## Qué enseñará

Un repaso explícito del **lenguaje Kotlin**, que hasta ahora se usa idiomáticamente pero nunca se
explica: null-safety (`?`, `?:`, `?.let`), `when` como expresión exhaustiva y desestructuración,
colecciones y funciones de orden superior (`map`/`filter`/`sortedBy`/`groupBy` + lambdas), funciones
de alcance (`let`/`run`/`apply`/`also`/`with`) y funciones de extensión.

## Feature vehículo

Un **filtro y ordenación en memoria** de la lista de artículos/tareas (por texto, por fecha, agrupado
por estado), resuelto con colecciones + lambdas, sin tocar la base de datos.

## Prerrequisitos

Lección 03 (`data class`, `sealed`, lista de artículos).
