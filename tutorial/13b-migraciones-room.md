# Lección 13b — Migraciones de Room reales + `TypeConverter`

> 🚧 **Lección pendiente.** Este es un placeholder que reserva el número **13b** en la secuencia
> definitiva de tutoriales (ver [`README.md`](README.md) y
> [`docs/conceptos-pendientes.md`](../docs/conceptos-pendientes.md) §6). Se escribirá al implementar
> la feature siguiendo el flujo `/feature` — el prompt ya está listo en
> [`docs/prompts/13b-migraciones-room.md`](../docs/prompts/13b-migraciones-room.md).

## Qué enseñará

La **migración de Room de verdad**, superando el `fallbackToDestructiveMigration` que hasta ahora
borra datos al cambiar el esquema: `Migration(from, to)` (y/o `AutoMigration`), y `TypeConverter`
para tipos no soportados (p. ej. un `enum` o `List`).

## Feature vehículo

Añadir un campo a `Task` (p. ej. `notes` o `priority`) con una **migración no destructiva** que
conserve los datos existentes, bumpeando la versión de `NeverLateDatabase`.

## Prerrequisitos

Lecciones 04 y 11 (Room, esquema, backend + escrituras).
