# Lección 21 — Build de release: variants, R8 y firma (llevar la app a producción)

> 🚧 **Lección pendiente.** Este es un placeholder que reserva el número **21** en la secuencia
> definitiva de tutoriales (ver [`README.md`](README.md) y
> [`docs/conceptos-pendientes.md`](../docs/conceptos-pendientes.md) §8). Se escribirá al implementar
> la feature siguiendo el flujo `/feature` — el prompt ya está listo en
> [`docs/prompts/21-build-release.md`](../docs/prompts/21-build-release.md).

## Qué enseñará

Los conceptos de build que faltan para un despliegue real: **build types y product flavors**,
`BuildConfig`, **minificación/ofuscación con R8/ProGuard** (reglas `keep`), y **firma** de la app
(APK/AAB, keystore de release). Cierra el **HTTPS pendiente** que arrastran las lecciones 11–12.

## Feature vehículo

Preparar una **build `release`** firmada, con R8 activado y apuntando al backend por **HTTPS**
(retirando la excepción de cleartext de debug, que **no** debe llegar a release).

## Prerrequisitos

Lecciones 11–13 (backend, HTTPS pendiente) y 20 (app ya completa visualmente). Última del roadmap.
