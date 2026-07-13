# Conceptos pendientes — ideas para futuras lecciones

> **Qué es esto:** un inventario de conceptos **básicos** de Kotlin y Android que las lecciones
> publicadas (**01–20**) **todavía no enseñan** (o solo rozan), con una **feature de la app** propuesta
> para introducir cada uno de forma didáctica y su **número definitivo** ya asignado en la secuencia de
> tutoriales.
>
> **Qué NO es:** un compromiso de implementar todo ya. Es un mapa de ideas para decidir qué enseñar a
> continuación. **No implementes nada de aquí sin arrancar el flujo normal** — cuando se elija una
> feature, se sigue: spec → aprobación → rama → implementación → lección. Ver el
> [flujo de nueva feature](../CLAUDE.md) y los prompts en [`docs/prompts/`](prompts/).

La app es un **tutorial progresivo**: cada lección introduce conceptos nuevos, de lo básico a lo
avanzado, reutilizando lo anterior (ver la *Tutorial Methodology* en el `CLAUDE.md`). Por eso cada
concepto de abajo lleva un **número de lección ya fijado**: solo puede colocarse **después** de las
lecciones que introducen sus prerrequisitos.

## El orden ya está bloqueado (léeme primero)

Este documento se escribió cuando solo existían las lecciones **01–13**. Desde entonces se publicaron
las features **14–20** (todas de diseño/UI), que de paso **ya cubrieron varios conceptos** de este
backlog (ver *Estado* abajo). Para que la curva de aprendizaje siga teniendo sentido **sin renumerar
nada de lo ya publicado**, las lecciones pendientes se insertan en su hueco pedagógico ideal usando
**sufijos de letra** (el mismo patrón que la ya existente `12b`): p. ej. la lección de fundamentos de
Kotlin es la **`03b`**, entre la 03 y la 04.

- El número de cada pendiente es **definitivo**: cuando se implemente, su lección se llamará
  `tutorial/NN-*.md` con ese número y su prompt ya está preparado en `docs/prompts/NN-*.md`.
- **No se renumera ninguna lección publicada.** El número `feature NN` está acoplado 1:1 al código
  (cientos de comentarios, tests, backend) y al historial git; los sufijos evitan tocar todo eso.
- El orden de lectura completo (índice reader-facing) vive en [`tutorial/README.md`](../tutorial/README.md).

---

## Roadmap bloqueado (orden definitivo)

★ = lección pendiente (placeholder en `tutorial/`, prompt listo en `docs/prompts/`). Ordenado por su
posición final en la secuencia.

| Nº | Concepto | Prerrequisitos | Feature propuesta | Estado |
|----|----------|----------------|-------------------|--------|
| **03b** ★ | Fundamentos de Kotlin (null-safety, `when`, colecciones, alcance, extensiones) | 03 (`data class`, `sealed`) | Filtro/orden de la lista de artículos/tareas en memoria | ⬜ pendiente |
| **04b** ★ | Corrutinas y `Flow` a fondo (`debounce`, `combine`, `stateIn`) | 04 (corrutinas, `Flow`, Room) | Buscador de tareas con `debounce` + `combine` | ✅ hecho en **feature 04b** |
| **04c** ★ | Testing (JVM + Compose UI, `runTest`) | 04 (lógica pura de tiempo) | Pantalla de estadísticas testeable + tests de UI | ✅ hecho en **feature 04c** |
| — | Side-effects en Compose (`LaunchedEffect`, `derivedStateOf`) | 02–04 | Snackbar "tarea creada" | ✅ hecho en **feature 17** |
| — | Animaciones en Compose (`animateItem`, `animate*AsState`) | 02–04 | Animar aparición/tachado/desaparición de tareas | ✅ hecho en **features 17 y 19** |
| **07b** ★ | Arquitectura nombrada (UDF/MVVM/capas) | 02–07 | Consolidar + documentar el patrón ya usado (poca UI nueva) | ✅ hecho en **feature 07b** |
| **10b** ★ | Carga de imágenes con Coil | 10 (red, Retrofit/OkHttp) | Imagen de cabecera por artículo desde la API | ⬜ pendiente |
| — | Theming dinámico (Material You / `dynamicColor`, roles de color) | 07 (tema, DataStore) | Preferencia de color dinámico + cromo de marca | ✅ hecho en **features 16 y 20** |
| **13b** ★ | Migraciones de Room reales + `TypeConverter` | 04/11 (Room, esquema) | Profundizar en migraciones: `TypeConverter`, `AutoMigration`, test de migración (el caso básico de añadir columna ya lo hizo 04c) | ✅ hecho en **feature 13b** |
| **13c** ★ | Paginación (Paging 3) | 10/11 (red + Room) | Lista de artículos paginada desde el backend | ✅ hecho en **feature 13c** |
| **13d** ★ | Inyección de dependencias (Hilt) | 02–11 (DI manual acumulada) | Migrar la DI manual (repos, ViewModels) a Hilt | ✅ hecho en **feature 13d** |
| — | Accesibilidad (repaso: `semantics`, `contentDescription`, ≥48dp, fuente dinámica) | 02–07 | Repaso de accesibilidad transversal | ✅ hecho en **feature 18** |
| **18b** ★ | Layouts adaptables / tamaños de pantalla (tablet) | 02–07/18 | Layout adaptable en tablet (continúa el repaso de a11y de la 18) | ✅ hecho en **feature 18b** |
| **21** ★ | Build variants, R8/ProGuard y firma de release | 11–13 (backend, HTTPS pendiente), 20 | Build `release` firmada con backend HTTPS + minificación | ⬜ pendiente |

**Pendientes: 3** (03b, 10b, 21). **Ya hechas por sus propias features:** 04b
(corrutinas/`Flow`), 04c (testing), 07b (arquitectura nombrada), 13b (migraciones de Room), 13c
(paginación), 13d (Hilt) y 18b (layouts adaptables). **Ya cubiertas por 14–20: 4** (side-effects, animaciones, theming
dinámico y el repaso de accesibilidad; ver detalle abajo). El slot **21** es el último de este roadmap;
cualquier feature futura no listada aquí se numeraría a partir de 22.

---

## Detalle por área

### 1. Kotlin — fundamentos del lenguaje → lección **03b**

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
alcance. **Ubicación:** **03b** (ya se conocen `data class` y `sealed`; se puede plantear sobre la
lista de artículos antes de profundizar en Room).

### 2. Corrutinas y `Flow` a fondo → lección **04b**

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
por `debounce` + `combine` con la lista de Room para filtrar en caliente. **Ubicación:** **04b**
(justo tras introducir `Flow` en la 04).

### 3. Testing (hueco grande — ninguna lección lo enseña a fondo) → lección **04c**

El proyecto ya tiene `src/test` (JVM) y `src/androidTest` (instrumentado), el flujo menciona un
`qa-engineer` y varias features escriben tests, pero **ninguna lección enseña a escribir tests** como
tema propio. Es probablemente el básico más importante que falta.

- **Tests unitarios JVM con JUnit** y aserciones (Truth/JUnit assertions), *test doubles*/fakes.
  Doc: [Test basics](https://developer.android.com/training/testing/fundamentals) ·
  [Local unit tests](https://developer.android.com/training/testing/local-tests).
- **Tests de UI de Compose** con `createComposeRule`, `onNodeWithText`, semántica.
  Doc: [Testing your Compose layout](https://developer.android.com/develop/ui/compose/testing).
- **Probar corrutinas/`Flow`** con `runTest` y `TestDispatcher`.
  Doc: [Testing coroutines](https://developer.android.com/kotlin/coroutines/test).

✅ **Testing: ya hecho** en la **feature 04c** (`04c-testing-estadisticas`). Una **pantalla de
estadísticas** ("tareas completadas esta semana", "% a tiempo", "por vencer") cuyo cálculo vive en la
función pura `weeklyStatsFor`, más el campo real de completado (`Task.completedAt`) sincronizado con el
backend. La lección enseña tests unitarios JVM (arrange/act/assert, fakes), probar `Flow`/corrutinas
(`runTest`, `StandardTestDispatcher`, `Clock` inyectable) y tests de UI de Compose (`createComposeRule`,
semántica). De paso, 04c introdujo la **primera migración de Room real** del proyecto (ver §6).

### 4. Arquitectura y Compose avanzado

El proyecto **usa** UDF/MVVM desde la 02 pero nunca lo nombra.

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

✅ **Side-effects y animaciones: ya hechos.** La **feature 17** (`17-estados-animaciones`) introdujo
`LaunchedEffect` para eventos de una sola vez (Snackbar "tarea creada"), `derivedStateOf`,
`Modifier.animateItem()` y `AnimatedVisibility`; la **feature 19** (`19-barra-progreso-tareas`) añadió
`animateFloatAsState`. Las propuestas originales (snackbar al crear, animar el tachado/desaparición)
quedaron cubiertas por esas features.

✅ **Arquitectura nombrada: ya hecha.** La **feature 07b** (`07b-arquitectura`) es una lección
**transversal** que *pone nombre* al patrón UDF/MVVM ya usado y documenta las capas UI/dominio/datos
(estado abajo / eventos arriba, funciones puras de dominio, el *seam* `TaskRepository` con sus
decoradores). Al ser de **consolidación**, la revisión confirmó que las capas ya eran coherentes: no
tocó código de producción ni cambió comportamiento. **Ubicación:** **07b** (tras tener UI + dominio +
datos con la 07).

### 5. Inyección de dependencias real (Hilt/Koin) → lección **13d**

Desde la 02 la app hace **DI manual** (`AppViewModelFactory`, singletons construidos a mano). El coste
crece con cada feature; es el momento didáctico perfecto para introducir un contenedor.

- **Hilt** (recomendado en Android): `@HiltAndroidApp`, `@Inject`, `@Module`, `@Provides`,
  `hiltViewModel()`. Doc: [Dependency injection with Hilt](https://developer.android.com/training/dependency-injection/hilt-android).
- Alternativa ligera: **Koin** (no oficial de Google).

**Feature propuesta:** **migrar la DI manual** existente (repositorios, base de datos, ViewModels) a
Hilt, sin cambiar comportamiento — una lección de refactor guiado que muestra el "antes/después".
**Ubicación:** **13d**, cuando el cableado manual (backend + sync + auth) ya es máximamente tedioso.

✅ **Hilt: ya hecho** en la **feature 13d** (`13d-hilt-di`). `AppViewModelFactory` y la construcción
manual en `MainActivity.onCreate` se retiran a favor de `@HiltAndroidApp`/`@AndroidEntryPoint` +
módulos `@Provides`/`@Binds` en `di/`; la cadena de decoradores de `TaskRepository` se provee con
qualifiers (`@RoomRepo`/`@OutboxRepo`/`@ReminderRepo`) en el mismo orden de siempre; los nueve
`ViewModel`s pasan a `@HiltViewModel` + `hiltViewModel()`, con `articleId`/`taskId` llegando por
`SavedStateHandle`. Cero cambios de comportamiento — ver el resumen en `CLAUDE.md` y la lección.

### 6. Datos: migraciones y paginación → lecciones **13b** y **13c**

La 04/10/11 usaban Room con `fallbackToDestructiveMigration` (borra datos al cambiar el esquema),
aceptado pre-release. La **feature 04c** ya rompió ese patrón: al añadir `Task.completedAt` envió la
**primera `Migration(3,4)` real** (`ALTER TABLE tasks ADD COLUMN completedAt INTEGER`, aditiva y no
destructiva), motivada por el modo invitado (feature 13) cuyas tareas viven solo en el dispositivo.

- **Migraciones de Room** (`Migration`, `AutoMigration`) y `TypeConverter`. Doc:
  [Migrate your Room database](https://developer.android.com/training/data-storage/room/migrating-db-versions).
- **Paginación con Paging 3** (con Room y/o red). Doc:
  [Paging library](https://developer.android.com/topic/libraries/architecture/paging/v3-overview).

**Feature propuesta (migración → 13b):** el caso básico (añadir una columna con migración no
destructiva) **ya se demostró de pasada en 04c**, así que 13b debe profundizar en lo que 04c no tocó:
`TypeConverter` para tipos no primitivos, `AutoMigration`, y **tests de migración** con `exportSchema`
activado (verificar que datos reales sobreviven al salto de versión). **Feature propuesta
(paging → 13c):** **lista de artículos paginada** desde el backend. **Ubicación:** tras el backend +
Room con escrituras (11–13).

### 7. Recursos y UI

- **Carga de imágenes con Coil** (integración con Compose, caché, placeholders). Doc:
  [Coil](https://coil-kt.github.io/coil/compose/) · fondo:
  [Load images](https://developer.android.com/develop/ui/compose/graphics/images/loading).
- **Theming dinámico (Material You / `dynamicColor`).** Doc:
  [Material 3 en Compose · dynamic color](https://developer.android.com/develop/ui/compose/designsystems/material3#dynamic).
- **Accesibilidad y tamaños de pantalla adaptativos.** Doc:
  [Accessibility in Compose](https://developer.android.com/develop/ui/compose/accessibility) ·
  [Adaptive layouts](https://developer.android.com/develop/ui/compose/layouts/adaptive).

✅ **Theming dinámico: ya hecho.** La **feature 16** (`16-identidad-visual`) hizo `dynamicColor`
configurable como preferencia y explicó los roles de color; la **feature 20** (`20-cromo-marca`)
aplicó esos roles a los componentes (top bars, chips, FAB).

✅ **Accesibilidad (repaso): ya hecho** en la **feature 18** (`18-navegacion-accesibilidad`):
`semantics`, `contentDescription` vs decorativo, tamaño de toque ≥ 48dp y reflow con fuente grande.

**Pendiente — Coil → lección 10b.** **Imagen de cabecera por artículo** traída de la API con Coil.
**Ubicación:** **10b** (tras la red de la 10).

✅ **Layouts adaptables: ya hecho** en la **feature 18b** (`18b-layouts-adaptables`), la mitad de
"adaptativo/tablet" que la 18 no abordó: `WindowSizeClass`, `NavigationBar` ↔ `NavigationRail` según el
ancho, Artículos en dos paneles (`ListDetailPaneScaffold`) en expanded, y `ReadableWidthContainer` para
Tareas/Ajustes — todo sobre el mismo grafo, sin regresión de la accesibilidad de la 18.

### 8. Build y release → lección **21**

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
seguridad de las lecciones 11–12. **Ubicación:** **21**, como lección final de "llevar la app a
producción".

---

## Cómo usar este documento

1. Elegir el **siguiente slot pendiente** (⬜) según lo que toque enseñar. Su número ya está fijado —
   no hay que decidir dónde encaja ni renumerar nada.
2. Coger el prompt ya preparado en [`docs/prompts/`](prompts/) (`NN-*.md`, mismo número que el slot) y
   arrancar el **flujo de nueva feature** del `CLAUDE.md` (spec con `project-manager-docs` → aprobación
   → rama `feature/<nombre>` → implementación → **lección Spanish** `tutorial/NN-*.md`).
3. Rellenar el **stub** `tutorial/NN-*.md` (hoy un placeholder 🚧) con la lección real, y marcar la fila
   como ✅ en la tabla de arriba y en [`tutorial/README.md`](../tutorial/README.md).
