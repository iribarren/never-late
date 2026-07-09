# Lección 10b — Carga de imágenes con Coil (cabecera de artículo)

> 🚧 **Lección pendiente.** Este es un placeholder que reserva el número **10b** en la secuencia
> definitiva de tutoriales (ver [`README.md`](README.md) y
> [`docs/conceptos-pendientes.md`](../docs/conceptos-pendientes.md) §7). Se escribirá al implementar
> la feature siguiendo el flujo `/feature` — el prompt ya está listo en
> [`docs/prompts/10b-coil-imagenes.md`](../docs/prompts/10b-coil-imagenes.md).

## Qué enseñará

**Coil** integrado con Compose: `AsyncImage`, carga asíncrona desde URL, caché en memoria/disco,
placeholders y estados de error, y `contentScale`. Añadir la dependencia vía version catalog.

## Feature vehículo

Una **imagen de cabecera por artículo** traída de la API (un campo de URL nuevo en el `ArticleDto`),
mostrada en la lista y/o el detalle.

## Prerrequisitos

Lección 10 (red, Retrofit/OkHttp, artículos desde API).
