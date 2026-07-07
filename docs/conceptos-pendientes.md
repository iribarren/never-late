# Conceptos pendientes — ideas para futuras lecciones

> **Qué es esto:** un inventario de conceptos **básicos** de Kotlin y Android que las lecciones
> 01–13 **todavía no enseñan** (o solo rozan), con una **feature de la app** propuesta para
> introducir cada uno de forma didáctica y su **ubicación sugerida** en la secuencia de tutoriales.
>
> **Qué NO es:** un compromiso ni un plan cerrado. Es un mapa de ideas para decidir qué enseñar a
> continuación. **No implementes nada de aquí todavía** — cuando se elija una feature, se sigue el
> flujo normal (spec → aprobación → rama → implementación → lección). Ver el
> [flujo de nueva feature](../CLAUDE.md) y los prompts en [`docs/prompts/`](prompts/).

La app es un **tutorial progresivo**: cada lección introduce conceptos nuevos, de lo básico a lo
avanzado, reutilizando lo anterior (ver la *Tutorial Methodology* en el `CLAUDE.md`). Por eso cada
concepto de abajo lleva una **ubicación sugerida**: solo puede colocarse **después** de las
lecciones que introducen sus prerrequisitos.

---

## Índice por ubicación en la secuencia (roadmap)

La columna "Encaja" indica entre qué lecciones actuales cabría la nueva lección por dificultad.
Ordenado de lo más temprano a lo más tardío.

| Concepto | Encaja | Prerrequisitos | Feature propuesta |
|----------|--------|----------------|-------------------|
| Fundamentos de Kotlin (null-safety, `when`, colecciones) | entre 03 y 04 | 03 (`data class`, `sealed`) | Filtro/orden de la lista de artículos en memoria |
| Funciones de alcance y de extensión | entre 03 y 04 | 03 | Refactor de mapeo/formato con `let`/`apply` + extensiones |
| Testing (JVM + Compose UI) | tras 04 | 04 (lógica pura de tiempo) | Tests de las funciones puras de tiempo y de una pantalla |
| Corrutinas y `Flow` a fondo | entre 04 y 05 | 04 (corrutinas, `Flow`) | Buscador de tareas con `debounce` + `combine` |
| Side-effects y ciclo de vida en Compose | entre 04 y 05 | 02–04 (estado, `ViewModel`) | Auto-scroll / snackbar de "tarea creada" con `LaunchedEffect` |
| Animaciones en Compose | entre 05 y 06 | 02–04 (Compose, estado) | Animar el tachado/aparición de tareas al completarlas |
| Carga de imágenes (Coil) | tras 10 | 10 (red, Retrofit/OkHttp) | Imagen de cabecera por artículo desde la API |
| Inyección de dependencias (Hilt) | tras 09 (o tras 11) | 02–09 (DI manual acumulada) | Migrar la DI manual (repos, ViewModels) a Hilt |
| Arquitectura nombrada (UDF/MVVM/MVI) | transversal, tras 07 | 02–07 | Documentar + consolidar el patrón ya usado (poca UI nueva) |
| Migraciones de Room reales + `TypeConverter` | tras 11 | 04/11 (Room, esquema) | Añadir un campo a `Task` con migración no destructiva |
| Paginación (Paging 3) | tras 11 | 10/11 (red + Room) | Lista de artículos paginada desde el backend |
| Build variants, R8/ProGuard y firma de release | tras 13 | 11–13 (backend, HTTPS pendiente) | Build `release` firmada con backend HTTPS + minificación |
| Accesibilidad y tamaños de pantalla adaptativos | transversal, tras 07 | 02–07 (Compose, Material 3) | Repaso de accesibilidad + layout adaptable en tablet |

---

## Detalle por área

### 1. Kotlin — fundamentos del lenguaje

Las lecciones usan Kotlin idiomático, pero nunca se ha dedicado una lección a **explicar el propio
lenguaje**. Un lector que venga de otro lenguaje agradecería un repaso explícito.

- **Null-safety, smart casts y el operador Elvis.** `?`, `?:`, `!!`, `?.let { }`. Hoy aparecen sin
  explicación. Doc: [Null safety](https://kotlinlang.org/docs/null-safety.html).
- **`when`, expresiones y desestructuración.** `when` como expresión exhaustiva sobre `sealed`,
  `val (a, b) = pair`. Doc: [Control flow](https://kotlinlang.org/docs/control-flow.html).
- **Colecciones y funciones de orden superior.** `map`/`filter`/`fold`/`groupBy`, lambdas.
  Doc: [Collections](https://kotlinlang.org/docs/collections-overview.html) ·
  [Higher-order functions](https://kotlinlang.org/docs/lambdas.html).
- **Funciones de alcance.** `let`/`run`/`also`/`apply`/`with` y cuándo usar cada una.
  Doc: [Scope functions](https://kotlinlang.org/docs/scope-functions.html).
- **Funciones de extensión.** Añadir comportamiento sin herencia. Doc:
  [Extensions](https://kotlinlang.org/docs/extensions.html).
- **`object`, `companion object` y delegación (`by`, `lazy`).** Doc:
  [Object declarations](https://kotlinlang.org/docs/object-declarations.html) ·
  [Delegated properties](https://kotlinlang.org/docs/delegated-properties.html).

**Feature propuesta:** un **filtro y ordenación** de la lista de artículos/tareas hecho en memoria
(por texto, por fecha, agrupado por estado), que se apoya en colecciones + lambdas + funciones de
alcance sin tocar la base de datos. **Ubicación:** entre la 03 y la 04 (ya se conocen `data class` y
`sealed`, aún no Room).

### 2. Corrutinas y `Flow` a fondo

La 04 introduce corrutinas y `Flow` de forma pragmática, pero quedan conceptos centrales sin nombrar.

- **Concurrencia estructurada, `CoroutineScope`, `viewModelScope`, dispatchers.** Doc:
  [Coroutines on Android](https://developer.android.com/kotlin/coroutines) ·
  [Coroutines guide](https://kotlinlang.org/docs/coroutines-guide.html).
- **`async`/`await` y trabajo en paralelo.** Doc:
  [Composing suspending functions](https://kotlinlang.org/docs/composing-suspending-functions.html).
- **Operadores de `Flow` (`map`, `combine`, `debounce`, `stateIn`) y `StateFlow` vs `SharedFlow`.**
  Doc: [Flow](https://kotlinlang.org/docs/flow.html) ·
  [StateFlow and SharedFlow](https://kotlinlang.org/docs/flow.html#stateflow-and-sharedflow).
- **Manejo de excepciones y cancelación.** Doc:
  [Exceptions](https://kotlinlang.org/docs/exception-handling.html).

**Feature propuesta:** un **buscador de tareas** con un `TextField` cuyo texto (un `StateFlow`) pasa
por `debounce` + `combine` con la lista de Room para filtrar en caliente. **Ubicación:** entre la 04
y la 05.

### 3. Testing (hueco grande — ninguna lección lo enseña)

El proyecto ya tiene `src/test` (JVM) y `src/androidTest` (instrumentado) y el flujo de trabajo
menciona un `qa-engineer`, pero **ninguna lección enseña a escribir tests**. Es probablemente el
básico más importante que falta.

- **Tests unitarios JVM con JUnit** y aserciones (Truth/JUnit assertions), *test doubles*/fakes.
  Doc: [Test basics](https://developer.android.com/training/testing/fundamentals) ·
  [Local unit tests](https://developer.android.com/training/testing/local-tests).
- **Tests de UI de Compose** con `createComposeRule`, `onNodeWithText`, semántica.
  Doc: [Testing your Compose layout](https://developer.android.com/develop/ui/compose/testing).
- **Probar corrutinas/`Flow`** con `runTest` y `TestDispatcher`.
  Doc: [Testing coroutines](https://developer.android.com/kotlin/coroutines/test).

**Feature propuesta:** una **pantalla de estadísticas** ("tareas completadas esta semana", "% a
tiempo") cuyo cálculo vive en una función pura muy testeable, más un par de tests de UI de la
pantalla. Enlaza de forma natural con las funciones puras de tiempo de la 04. **Ubicación:** tras la
04 (o como lección transversal, ya que todas las features futuras la reutilizarían).

### 4. Arquitectura y Compose avanzado

El proyecto **usa** UDF/MVVM desde la 02 pero nunca lo nombra ni explica los *side-effects* de
Compose, que hoy aparecen (`LaunchedEffect` en la 13) sin lección propia.

- **UDF / MVVM / capas (UI, dominio, datos).** Doc:
  [Guide to app architecture](https://developer.android.com/topic/architecture).
- **Side-effects:** `LaunchedEffect`, `DisposableEffect`, `rememberCoroutineScope`,
  `derivedStateOf`, `snapshotFlow`. Doc:
  [Side-effects in Compose](https://developer.android.com/develop/ui/compose/side-effects).
- **Ciclo de vida y cambios de configuración.** Doc:
  [Lifecycle](https://developer.android.com/topic/libraries/architecture/lifecycle) ·
  [Handle configuration changes](https://developer.android.com/guide/topics/resources/runtime-changes).
- **Animaciones.** `animate*AsState`, `AnimatedVisibility`. Doc:
  [Animations in Compose](https://developer.android.com/develop/ui/compose/animation/introduction).

**Feature propuesta (side-effects):** un **snackbar/scroll automático** al crear una tarea
(desplazar la lista al elemento nuevo con `LaunchedEffect`). **Feature propuesta (animaciones):**
animar el **tachado y la desaparición** de una tarea al completarla. **Ubicación:** side-effects
entre la 04 y la 05; animaciones entre la 05 y la 06. La lección de arquitectura puede ser
transversal (poca UI nueva, mucho "poner nombre" a lo ya hecho) tras la 07.

### 5. Inyección de dependencias real (Hilt/Koin)

Desde la 02 la app hace **DI manual** (`ViewModelProvider.Factory`, singletons construidos a mano).
El coste crece con cada feature; es el momento didáctico perfecto para introducir un contenedor.

- **Hilt** (recomendado en Android): `@HiltAndroidApp`, `@Inject`, `@Module`, `@Provides`,
  `hiltViewModel()`. Doc: [Dependency injection with Hilt](https://developer.android.com/training/dependency-injection/hilt-android).
- Alternativa ligera: **Koin** (no oficial de Google).

**Feature propuesta:** **migrar la DI manual** existente (repositorios, base de datos, ViewModels) a
Hilt, sin cambiar comportamiento — una lección de refactor guiado que muestra el "antes/después".
**Ubicación:** tras la 09 o la 11, cuando el cableado manual ya es visiblemente tedioso.

### 6. Datos: migraciones y paginación

La 04/10/11 usan Room con `fallbackToDestructiveMigration` (borra datos al cambiar el esquema),
aceptado pre-release. Un básico pendiente es la **migración de verdad**.

- **Migraciones de Room** (`Migration`, `AutoMigration`) y `TypeConverter`. Doc:
  [Migrate your Room database](https://developer.android.com/training/data-storage/room/migrating-db-versions).
- **Paginación con Paging 3** (con Room y/o red). Doc:
  [Paging library](https://developer.android.com/topic/libraries/architecture/paging/v3-overview).

**Feature propuesta (migración):** añadir un campo a `Task` (p. ej. `notes` o `priority`) con una
**migración no destructiva** que conserve los datos existentes. **Feature propuesta (paging):**
**lista de artículos paginada** desde el backend. **Ubicación:** tras la 11 (ya hay backend + Room
con escrituras).

### 7. Recursos y UI

- **Carga de imágenes con Coil** (integración con Compose, caché, placeholders). Doc:
  [Coil](https://coil-kt.github.io/coil/compose/) · fondo:
  [Load images](https://developer.android.com/develop/ui/compose/graphics/images/loading).
- **Theming dinámico (Material You / `dynamicColor`).** Doc:
  [Material 3 en Compose · dynamic color](https://developer.android.com/develop/ui/compose/designsystems/material3#dynamic).
- **Accesibilidad a fondo y tamaños de pantalla adaptativos.** Doc:
  [Accessibility in Compose](https://developer.android.com/develop/ui/compose/accessibility) ·
  [Adaptive layouts](https://developer.android.com/develop/ui/compose/layouts/adaptive).

**Feature propuesta:** **imagen de cabecera por artículo** traída de la API con Coil (encaja con la
10), y/o un repaso de accesibilidad + layout adaptable para tablet. **Ubicación:** Coil tras la 10;
accesibilidad/adaptativo transversal tras la 07.

### 8. Build y release

La app solo construye `debug`. Antes de un despliegue real (el `CLAUDE.md` ya marca **HTTPS
pendiente**), hay conceptos de build sin cubrir.

- **Build types y product flavors, `BuildConfig`.** Doc:
  [Configure build variants](https://developer.android.com/build/build-variants).
- **Minificación y ofuscación con R8/ProGuard.** Doc:
  [Shrink, obfuscate, and optimize](https://developer.android.com/build/shrink-code).
- **Firmar la app (APK/AAB).** Doc:
  [Sign your app](https://developer.android.com/studio/publish/app-signing).

**Feature propuesta:** preparar una **build `release`** firmada, con R8 activado y apuntando al
backend por **HTTPS** (retirando la excepción de cleartext de debug) — cierra el pendiente de
seguridad de las lecciones 11–12. **Ubicación:** tras la 13, como lección de "llevar la app a
producción".

---

## Cómo usar este documento

1. Elegir un concepto/feature de la tabla según lo que toque enseñar a continuación.
2. Arrancar el **flujo de nueva feature** del `CLAUDE.md` (spec con `project-manager-docs` →
   aprobación → rama `feature/<nombre>` → implementación → **lección Spanish** `tutorial/NN-*.md`).
3. Numerar la lección según su ubicación real y actualizar los prompts en
   [`docs/prompts/`](prompts/) si procede.
