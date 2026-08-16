# Feature — Modo Foco (núcleo): pantalla completa y ritual de salida

Lee `CLAUDE.md` (**Tutorial Track (optional)** + **Mandatory Workflow** + **Design in the Workflow**)
y las lecciones previas (en especial la 04: lista de tareas y cuenta atrás; la 04c: completar tarea
con `toggleComplete`; la 18: navegación y accesibilidad; y la 07: preferencias en el DataStore
`user_prefs`). Implementa **"Modo Foco: una pantalla completa que solo muestra las tareas pendientes,
con una salida deliberadamente costosa"** siguiendo el flujo `/feature`.

> **Es la mitad de producto de la idea.** El blindaje del dispositivo (fijar pantalla, No Molestar,
> mantener la pantalla encendida) va aparte, en `modo-foco-blindaje.md`, y es opcional: esta feature
> tiene que ser útil y completa **sin** nada de eso.

El nombre de cara al usuario es **"Modo Foco"** (en inglés, *Focus Mode*); en código, `FocusMode` /
`focus`, como todo el resto.

## Qué construir

- Un **botón de entrada** al Modo Foco desde la pantalla de Tareas, que al pulsarlo pide a la persona
  un **código de salida** y arranca la sesión.
- Un **destino a pantalla completa** que muestra **solo las tareas pendientes**, sin barra inferior,
  sin rail, sin navegación a otras secciones: nada que invite a irse a otro sitio.
- Cada tarea se puede **marcar como hecha** con un checkbox desde esa misma pantalla.
- Un **ritual de salida** con las tres piezas que pidió el usuario: marcar las pendientes, **deslizar
  una barra de desbloqueo** e **introducir el código** que se fijó al entrar.
- Una **salida de emergencia** siempre disponible (ver *Notas*): el modo es fricción deliberada, no
  una cárcel.

## Tutorial

Antes de escribir el spec, el flujo `/feature` **debe preguntar con `AskUserQuestion`** si esta
feature lleva lección en español (*Sí, con lección / No / Decidir al final*), y la respuesta se anota
en el campo `Tutorial:` del spec.

**Recomendación: Sí.** Es la feature más rica de las cuatro ideas y toca cosas que ninguna lección ha
tocado:

- **`BackHandler` e interceptar la navegación:** qué controla realmente una app (el botón atrás
  dentro de su propia ventana) y qué no (home y recientes), y por qué eso define el alcance honesto
  de un "modo bloqueado".
- **Gestos personalizados con semántica accesible:** una barra de deslizar es la trampa clásica de
  TalkBack; cómo se le da una acción semántica alternativa para que no excluya a nadie.
- **Estado de sesión que sobrevive al ciclo de vida:** rotación, muerte de proceso y reinicio, y
  dónde vive cada cosa (`ViewModel` vs `SavedStateHandle` vs DataStore).
- **Diseñar fricción sin diseñar una trampa:** el criterio de producto de cuándo una barrera ayuda a
  alguien con TDA/TDAH y cuándo se vuelve hostil.

## Notas

- Rama sugerida: `feature/focus-mode`.
- **Navegación — el patrón ya existe y hay que seguirlo, no inventar otro.** En
  [`AppNavHost.kt`](../../app/src/main/java/com/neverlate/ui/navigation/AppNavHost.kt):
  añadir una constante al `private object Routes`, un `composable(...)` dentro de `MainNavGraph`, y
  **dejarlo fuera de `TOP_LEVEL_ROUTES`** — con eso solo, la barra inferior y el rail ya se ocultan.
  El ejemplo canónico de ruta secundaria es **Stats**, alcanzable desde un icono de la top bar de
  Tareas; el botón de Modo Foco se cablea igual que `onStatsClick`. A diferencia del resto de
  pantallas, esta **no** se envuelve en `ReadableWidthContainer`: quiere ocupar todo el ancho.
- **Datos — no hay ninguna consulta "solo pendientes".** `observeTasks()` devuelve todas las tareas y
  "completada" es `completedAt != null`. Añade una **función pura** en `domain/tasks/` (al estilo de
  [`TaskListShaping.kt`](../../app/src/main/java/com/neverlate/domain/tasks/TaskListShaping.kt)) y
  pruébala en la JVM, en vez de filtrar dentro del composable.
- **Extiende, no dupliques — completar tarea ya funciona.** Reutiliza `toggleComplete` de
  [`TasksViewModel.kt`](../../app/src/main/java/com/neverlate/ui/tasks/TasksViewModel.kt), que baja
  por `saveTask` y la cadena de decoradores del repositorio. Marcar hecho desde el Modo Foco refresca
  el widget y la notificación, cancela el recordatorio y encola la fila del outbox **sin una línea
  extra**. No crear una segunda ruta de escritura.
- **El riesgo de producto que el spec debe resolver, no ignorar.** Un modo que exige un código para
  salir puede **atrapar** a la persona, y en una app para TDA/TDAH olvidar ese código es el caso
  probable, no el raro. El spec debe definir una salida de emergencia concreta — por ejemplo que el
  código se vuelva visible tras N minutos, o un botón de "abandonar sesión" que la marque como
  fallida en vez de bloquear. Además, `BackHandler` (único precedente en el repo:
  [`ArticlesListDetailPane.kt`](../../app/src/main/java/com/neverlate/ui/articles/ArticlesListDetailPane.kt))
  solo intercepta el atrás **dentro de la app**: home y recientes siguen funcionando. El spec debe
  decirlo con esas palabras y no prometer un bloqueo que no existe.
- **Accesibilidad — aquí no es un check más, es la diferencia entre útil e inutilizable.** Una barra
  de "deslizar para desbloquear" sin acción semántica alternativa deja encerrada a una persona que
  use TalkBack. Criterios: acción accesible equivalente al gesto, etiqueta en el campo de código,
  todos los objetivos ≥48dp, y reflow correcto a `fontScale` máximo. Si con lector de pantalla no se
  puede salir, la feature está rota aunque pasen los tests.
- **Estado y preferencias.** El spec decide qué sobrevive a qué: rotación, muerte de proceso,
  reinicio del teléfono. Lo que se persista va al DataStore `user_prefs` **ya existente**, siguiendo
  el patrón de
  [`UserPreferencesRepository.kt`](../../app/src/main/java/com/neverlate/data/UserPreferencesRepository.kt)
  (campo en `UserPreferences` + `suspend fun save…` en la interfaz + clave en `Keys` + lectura
  tolerante + `edit`) — **nunca un segundo DataStore**. Nota de seguridad para que nadie se confunda:
  el código de salida **no es una credencial**, es fricción; no va al almacén cifrado del Keystore ni
  se trata como un secreto, y el spec debe decirlo explícitamente.
- **Diseño (obligatorio en el spec):** el maquetado maestro **no tiene pantalla de Modo Foco**, así
  que esta feature **añade una fila nueva** a `docs/mockups/README.md` (`—`, UI net-new) en vez de
  mover una existente. La sección **Visual & UX Design** debe describir la jerarquía de la pantalla
  (una sola cosa importa: la tarea de ahora), el estado "no queda nada pendiente", y reutilizar los
  componentes que ya hay: `MessageState` para el estado vacío, `brandedTopAppBarColors()`,
  `NeverLateExtras` para la urgencia y `BrandIconChip`. Criterios visuales concretos: contraste
  suficiente, targets ≥48dp, reflow a fuente máxima, y que la pantalla siga siendo legible con muchas
  tareas pendientes.
- **Referencia visual:** abre `docs/mockups/rediseno-ux-ui.html` como guía de dirección, **no** código
  a copiar; tradúcela con los tokens reales del tema (`ui/theme/`).
- Sin backend, sin contrato, sin migración de Room, sin nueva dependencia. Si se acaba necesitando un
  permiso o un cambio de manifest, eso pertenece a `modo-foco-blindaje.md`, no aquí.
- Ficheros:
  [`AppNavHost.kt`](../../app/src/main/java/com/neverlate/ui/navigation/AppNavHost.kt),
  [`TasksScreen.kt`](../../app/src/main/java/com/neverlate/ui/tasks/TasksScreen.kt) (botón de
  entrada, igual que el de estadísticas),
  [`TasksViewModel.kt`](../../app/src/main/java/com/neverlate/ui/tasks/TasksViewModel.kt)
  (`toggleComplete`, reutilizado), un paquete nuevo `ui/focus/` con su pantalla y su `ViewModel`,
  una función pura nueva en `domain/tasks/`,
  [`UserPreferencesRepository.kt`](../../app/src/main/java/com/neverlate/data/UserPreferencesRepository.kt),
  [`strings.xml`](../../app/src/main/res/values/strings.xml) +
  [`values-en/strings.xml`](../../app/src/main/res/values-en/strings.xml).
- Agentes: `mobile-engineer` (pantalla, ritual de salida, estado de sesión, accesibilidad del gesto),
  `qa-engineer` (tests JVM del filtro de pendientes y de la máquina de estados de la sesión; test
  instrumentado de que la salida es alcanzable con acciones de accesibilidad, no solo con el gesto).
