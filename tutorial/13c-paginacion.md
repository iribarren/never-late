# Lección 13c — Paginación con Paging 3 (artículos paginados)

> 🚧 **Lección pendiente.** Este es un placeholder que reserva el número **13c** en la secuencia
> definitiva de tutoriales (ver [`README.md`](README.md) y
> [`docs/conceptos-pendientes.md`](../docs/conceptos-pendientes.md) §6). Se escribirá al implementar
> la feature siguiendo el flujo `/feature` — el prompt ya está listo en
> [`docs/prompts/13c-paginacion.md`](../docs/prompts/13c-paginacion.md).

## Qué enseñará

**Paging 3**: `PagingSource`/`RemoteMediator`, `Pager`, `PagingData` como `Flow`,
`collectAsLazyPagingItems()` en Compose, y los estados de carga/append. Cómo paginar combinando red y
Room como caché.

## Feature vehículo

Una **lista de artículos paginada** desde el backend (cargar página a página al hacer scroll en vez
de todo de golpe).

## Prerrequisitos

Lecciones 10 y 11 (red + Room, backend).
