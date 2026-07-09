# Lección 13d — Inyección de dependencias con Hilt

> 🚧 **Lección pendiente.** Este es un placeholder que reserva el número **13d** en la secuencia
> definitiva de tutoriales (ver [`README.md`](README.md) y
> [`docs/conceptos-pendientes.md`](../docs/conceptos-pendientes.md) §5). Se escribirá al implementar
> la feature siguiendo el flujo `/feature` — el prompt ya está listo en
> [`docs/prompts/13d-hilt-di.md`](../docs/prompts/13d-hilt-di.md).

## Qué enseñará

**Hilt**: `@HiltAndroidApp`, `@Inject`, `@Module`/`@Provides`/`@Binds`, componentes y ámbitos
(`@Singleton`), y `hiltViewModel()` en Compose. El "antes/después" frente a la DI manual.

## Feature vehículo

**Migrar la DI manual** existente (`AppViewModelFactory`, base de datos, repos, sync/auth) a Hilt,
sin cambiar comportamiento — un refactor guiado, ahora que el cableado manual es máximamente tedioso.

## Prerrequisitos

Lecciones 02–11 (toda la DI manual acumulada: repos, backend, sync, auth).
