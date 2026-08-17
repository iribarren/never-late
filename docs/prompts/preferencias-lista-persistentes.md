# Feature — Que el filtro, el orden y la búsqueda de la lista sobrevivan a cerrar la app

Lee `CLAUDE.md` (**Tutorial Track (optional)** + **Mandatory Workflow** + **Design in the Workflow**)
y las lecciones previas (en especial la 03b: filtro y ordenación en memoria, que introdujo
`TaskListCriteria`; la 04b: el buscador reactivo con `debounce`/`combine`/`stateIn`; y la 07:
preferencias persistidas en el DataStore `user_prefs`). Implementa **"que la lista se recuerde de cómo
la dejaste"** siguiendo el flujo `/feature`.

> **Hoy se pierde todo, y antes de lo que parece.** El orden, la dirección, el agrupado y la búsqueda
> viven en `MutableStateFlow` dentro de `TasksViewModel`, que **ni siquiera tiene `SavedStateHandle`**:
> no se pierden solo al cerrar la app, se pierden si el sistema mata el proceso mientras estás en otra
> aplicación. Para alguien que ordena por prioridad o agrupa por urgencia porque así es como consigue
> ver su lista, volver a configurarla en cada arranque es fricción diaria y silenciosa.

## Qué construir

- El **orden** (campo y dirección), el **agrupado** y —si el spec lo aprueba— la **búsqueda** se
  guardan y se restauran al volver a abrir la app.
- La restauración **no produce parpadeo**: no se ve medio segundo la lista con los ajustes por defecto
  antes de saltar a los tuyos.
- Un valor guardado que ya no exista (porque una versión futura renombre un criterio) **no rompe la
  app**: se cae al valor por defecto sin ruido.

## Tutorial

Antes de escribir el spec, el flujo `/feature` **debe preguntar con `AskUserQuestion`** si esta
feature lleva lección en español (*Sí, con lección / No / Decidir al final*), y la respuesta se anota
en el campo `Tutorial:` del spec.

**Recomendación: No.** DataStore y preferencias persistidas son la lección **07**; el pipeline
reactivo con `combine`/`stateIn`/`debounce` es la **04b**; el filtro y el orden son la **03b**. Esta
feature es la intersección de las tres, no un concepto nuevo. Lo único que roza lo didáctico es el
problema del arranque asíncrono descrito abajo, y se documenta bien en el spec. Si se quisiera lección
igualmente, el ángulo honesto sería *"el primer frame: qué haces mientras el disco todavía no ha
contestado"*, y merecería más ser una lección transversal que una de esta feature.

## Notas

- Rama sugerida: `feature/persisted-list-preferences`.
- **El problema real es el arranque, no el guardado.** En
  [`TasksViewModel.kt`](../../app/src/main/java/com/neverlate/ui/tasks/TasksViewModel.kt) el `uiState`
  se construye **declarativamente al inicializar el campo**, combinando las tareas con un
  `TaskListCriteria()` síncrono. DataStore es asíncrono, así que restaurar significa que durante unos
  frames los chips muestran los valores por defecto y luego saltan. El spec debe elegir:
  - **(a)** una rama de carga explícita, como ya hacen
    [`MainActivity.kt`](../../app/src/main/java/com/neverlate/MainActivity.kt) y
    [`AppNavHost.kt`](../../app/src/main/java/com/neverlate/ui/navigation/AppNavHost.kt) para el tema
    y el destino inicial (ambos esperan un valor nulo antes de decidir) — consistente con lo que el
    proyecto ya hace, y es la recomendación;
  - **(b)** un `init { }` que siembre el estado, lo cual **reintroduce el colector imperativo que la
    feature 04b eliminó a propósito** y que su KDoc documenta. Si se elige, hay que justificarlo
    contra esa decisión previa, no ignorarla.
- **La trampa del debounce.** `debouncedQuery` retiene 300 ms antes de propagar. Una búsqueda
  **restaurada** no es una pulsación de teclado y no debería esperar: el camino de restauración tiene
  que esquivar el debounce, o el arranque en frío se siente lento sin motivo.
- **Extiende, no dupliques.** `TaskListCriteria`
  ([`TaskListShaping.kt`](../../app/src/main/java/com/neverlate/domain/tasks/TaskListShaping.kt)) ya
  es el objeto "todo lo que configuran los controles": se persiste **eso**, no un DTO paralelo.
  Para guardar los enums, copia el precedente de `ThemeMode.fromStorage` en
  [`UserPreferencesRepository.kt`](../../app/src/main/java/com/neverlate/data/UserPreferencesRepository.kt):
  clave de tipo `String` + parseo **tolerante** con caída al valor por defecto ante un valor
  desconocido o ausente. Nunca `enum.ordinal` ni `enum.name` sin red — un renombrado futuro reventaría
  las preferencias de todo el mundo.
- **La decisión de producto que el spec debe tomar: ¿se persiste también la búsqueda?** Hay un buen
  argumento en contra: abrir la app y encontrarte la lista filtrada por un texto que escribiste hace
  tres días parece un fallo, no una comodidad — y en una app para TDA/TDAH, "faltan tareas y no sé por
  qué" es especialmente caro. Orden y agrupado son configuración; una búsqueda suele ser algo puntual.
  **Recomendación: persistir orden y agrupado, no la búsqueda** — pero el spec decide y argumenta.
- **Aviso de compilación:** cualquier método nuevo en la interfaz `UserPreferencesRepository` rompe
  **tres fakes de test** (`data/sync/SyncTestDoubles.kt`, `ui/settings/SettingsViewModelTest.kt`,
  `ui/onboarding/OnboardingViewModelTest.kt`).
- **Diseño (obligatorio en el spec):** no hay controles nuevos, pero sí un comportamiento visual
  nuevo. Criterio visual central: **al abrir la app no se ve un estado intermedio incorrecto** — ni
  chips que saltan, ni una lista que se reordena sola delante de tus ojos. Si se persiste la búsqueda,
  el campo debe mostrar el texto restaurado y su botón de limpiar desde el primer frame en que se ve,
  para que se entienda por qué faltan tareas. Reflejar en `docs/mockups/README.md` lo que aplique.
- **Fuera de alcance:** persistir el estado de scroll, sincronizar estas preferencias entre
  dispositivos, y añadir criterios de filtro nuevos (eso es `prioridad-operativa.md`).
- Sin backend, sin contrato, sin migración de Room (es DataStore), sin dependencia nueva, sin permiso.
- Ficheros:
  [`TasksViewModel.kt`](../../app/src/main/java/com/neverlate/ui/tasks/TasksViewModel.kt),
  [`TaskListShaping.kt`](../../app/src/main/java/com/neverlate/domain/tasks/TaskListShaping.kt)
  (parseo tolerante de los dos enums),
  [`UserPreferencesRepository.kt`](../../app/src/main/java/com/neverlate/data/UserPreferencesRepository.kt).
- Agentes: `mobile-engineer` (persistencia y camino de restauración sin parpadeo), `qa-engineer`
  (tests JVM del parseo tolerante con valores desconocidos y ausentes, y del `ViewModel` restaurando
  un criterio guardado; verificación manual del arranque en frío buscando específicamente el salto de
  los chips).
