# Feature 13d (extra) — Migrar la inyección de dependencias a Hilt

Lee `CLAUDE.md` (**Tutorial Methodology** + **Mandatory Workflow**) y las lecciones previas (en
especial la 02: primer `ViewModelProvider.Factory` manual; la 09/11: decoradores de repositorio
compuestos a mano en `MainActivity`; y la 13: guest mode y su cableado). Implementa **"migrar la DI
manual a Hilt sin cambiar comportamiento"** siguiendo el flujo `/feature`.

> **Refactor guiado "antes/después", no producto nuevo.** Desde la 02 la app inyecta a mano
> (`AppViewModelFactory`, singletons, decoradores compuestos en `MainActivity`). Con el backend +
> sync + auth el cableado ya es máximamente tedioso: el momento perfecto para un contenedor.

## Qué construir

- **Hilt** en el proyecto: `@HiltAndroidApp` en una `Application`, `@AndroidEntryPoint` en
  `MainActivity`.
- **Módulos** (`@Module`/`@Provides`/`@Binds`) para: `NeverLateDatabase` y DAOs, Retrofit/OkHttp y
  APIs, `TokenStorage`, y los `TaskRepository` con sus **decoradores** (sync + recordatorios) en el
  orden actual.
- **ViewModels con `@HiltViewModel` + `@Inject`**, obtenidos con `hiltViewModel()` en Compose.
- Retirar `AppViewModelFactory` y las construcciones manuales, **preservando el comportamiento**
  (mismos seams, misma composición de decoradores).

## Conceptos nuevos a enseñar (lección en español)

- **Por qué DI:** el coste de la DI manual y qué resuelve un contenedor.
- **Hilt básico:** `@HiltAndroidApp`, `@Inject` (constructor), `@Module`, `@Provides` vs `@Binds`,
  `@Singleton` y componentes/ámbitos.
- **Hilt + Compose + Navigation:** `@HiltViewModel`, `hiltViewModel()`, y el ciclo de vida.
- **Proveer una cadena de decoradores** con Hilt (qualifiers si hace falta) sin cambiar el orden.

## Notas

- Rama sugerida: `feature/hilt-di`.
- **Extiende, no dupliques:** el objetivo es sustituir el cableado, **no** cambiar los seams
  (`TaskRepository`, decoradores de sync/recordatorios) ni el comportamiento observable.
- Mapea a `docs/conceptos-pendientes.md` §5 (Inyección de dependencias). Nueva dependencia:
  `hilt-android` + `hilt-compiler` (kapt/ksp) + `hilt-navigation-compose` en el catálogo.
- Ficheros: nueva `Application`,
  [`MainActivity.kt`](../../app/src/main/java/com/neverlate/MainActivity.kt),
  [`AppViewModelFactory.kt`](../../app/src/main/java/com/neverlate/ui/navigation/AppViewModelFactory.kt)
  (se retira), todos los `ViewModel`, nuevos módulos en `di/`, `AndroidManifest.xml`,
  `gradle/libs.versions.toml`.
- Agentes: `mobile-engineer` (migración completa), `qa-engineer` (verificar sin regresión: toda la
  batería de tests sigue verde). Lección en `tutorial/13d-hilt-di.md` (español), numerada como **13d**
  (entre la 13 y la 14).
