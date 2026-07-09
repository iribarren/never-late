# Lección 18b — Layouts adaptables (tamaños de pantalla / tablet)

> 🚧 **Lección pendiente.** Este es un placeholder que reserva el número **18b** en la secuencia
> definitiva de tutoriales (ver [`README.md`](README.md) y
> [`docs/conceptos-pendientes.md`](../docs/conceptos-pendientes.md) §7). Se escribirá al implementar
> la feature siguiendo el flujo `/feature` — el prompt ya está listo en
> [`docs/prompts/18b-layouts-adaptables.md`](../docs/prompts/18b-layouts-adaptables.md).

## Qué enseñará

La mitad "adaptativa" que la 18 (accesibilidad) no abordó: `WindowSizeClass`, layouts que **reflows**
en pantallas grandes (patrón list-detail en tablet), navegación adaptable (`NavigationRail` vs
`NavigationBar`), y probar en distintos tamaños. Continúa el repaso de accesibilidad de la 18.

## Feature vehículo

Hacer que la app **aproveche pantallas grandes** (tablet/horizontal): p. ej. lista + detalle de
artículos en dos paneles, y la barra inferior convertida en `NavigationRail` en ancho grande.

## Prerrequisitos

Lecciones 02–07 (Compose, Material 3) y 18 (navegación + accesibilidad).
