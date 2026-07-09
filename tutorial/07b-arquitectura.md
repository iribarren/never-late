# Lección 07b — Arquitectura: poner nombre al patrón (UDF / MVVM / capas)

> 🚧 **Lección pendiente.** Este es un placeholder que reserva el número **07b** en la secuencia
> definitiva de tutoriales (ver [`README.md`](README.md) y
> [`docs/conceptos-pendientes.md`](../docs/conceptos-pendientes.md) §4). Se escribirá al implementar
> la feature siguiendo el flujo `/feature` — el prompt ya está listo en
> [`docs/prompts/07b-arquitectura.md`](../docs/prompts/07b-arquitectura.md).

## Qué enseñará

Una lección **transversal** que *pone nombre* al patrón que la app usa desde la 02 sin nombrarlo:
**UDF** (flujo de datos unidireccional), **MVVM** y la separación en capas **UI / dominio / datos**.
Poca UI nueva; mucho consolidar, diagramar y documentar lo ya hecho.

## Feature vehículo

Consolidar y documentar el patrón existente (repos, ViewModels, `StateFlow`, funciones puras de
dominio como `ReminderPlanning`/`urgencyLevelFor`), sin reescribir comportamiento.

## Prerrequisitos

Lecciones 02–07 (Compose, `ViewModel`, DataStore, repos).
