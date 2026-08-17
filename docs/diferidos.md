
> **Qué es esto:** el backlog extraído de las secciones *Out of Scope* y *Risks* de `docs/specs/`, de
> las notas de [`mockups/README.md`](mockups/README.md) y de tres comentarios en código. Es **deuda
> que generó el propio trabajo**: cosas que se decidieron no hacer en su momento, con buen criterio,
> y que al acumularse merecen volver a mirarse.
>
> **En qué se diferencia de los otros dos backlogs:**
> - [`ideillas.md`](ideillas.md) recoge **ideas que nacieron del usuario** (Modo Foco, mejoras de UX
>   que pidió). Aquí no hay ninguna idea nueva: todo sale de decisiones ya tomadas y escritas.
> - [`conceptos-pendientes.md`](conceptos-pendientes.md) es el **backlog del tutorial**: conceptos de
>   Kotlin/Android sin lección. Aquí el punto de partida es el producto, no lo que enseña.
>
> **Qué NO es:** un compromiso ni un plan cerrado. El repo tiene **~90 items diferidos** registrados;
> los diez de abajo son los que se juzgaron mejora real. El resto sigue siendo constancia honesta de
> "aquí no lo hicimos", y está bien que se quede así.

---

## Los prompts

Cada fila tiene su prompt listo para pegar en una sesión nueva con `/feature` (o `/bugfix`).

| # | Qué resuelve | Prompt | Tipo | Tamaño |
|---|---|---|---|---|
| 1 | El widget no aprovecha el tamaño que le dan, no tiene barra de progreso y no deja completar nada | [`widget-adaptable-progreso.md`](prompts/widget-adaptable-progreso.md) | Producto | M |
| 2 | La cuenta atrás llega a cero **y no pasa nada**; una tarea sin fecha límite no avisa jamás | [`aviso-tiempo-agotado.md`](prompts/aviso-tiempo-agotado.md) | Producto | M |
| 3 | La prioridad se pinta pero no ordena, no filtra y no llega a notificación ni estadísticas | [`prioridad-operativa.md`](prompts/prioridad-operativa.md) | Producto | M |
| 4 | Los títulos de tarea se ven en claro en la pantalla de bloqueo y la notificación no se puede apagar | [`notificacion-privacidad-y-apagado.md`](prompts/notificacion-privacidad-y-apagado.md) | Producto | M |
| 5 | Las tareas completadas ocupan filas del widget y la notificación — y se cuelan **las primeras, en rojo** | [`tareas-completadas-widget-notificacion.md`](prompts/tareas-completadas-widget-notificacion.md) | **Bugfix** | S |
| ~~6~~ | ~~El ajuste de sistema "reducir movimiento" no llega a la cadencia de 1 s del contador~~ **Hecho** (`docs/specs/2026-08-17-reduce-motion.md`) | [`reducir-movimiento.md`](prompts/reducir-movimiento.md) | Papercut | S |
| 7 | Se pide el nombre en el primer arranque y **nadie lo lee nunca**, ni se puede cambiar | [`perfil-editable.md`](prompts/perfil-editable.md) | Papercut | S |
| 8 | El orden, el agrupado y la búsqueda se pierden al cerrar la app (y al morir el proceso) | [`preferencias-lista-persistentes.md`](prompts/preferencias-lista-persistentes.md) | Papercut | S |
| ~~9~~ | ~~El widget monta su repositorio a mano y hay dos mapeos de color gemelos que pueden desincronizarse~~ **Hecho** (`docs/specs/2026-08-17-widget-hilt-color-token.md`) | [`widget-hilt-y-token-color.md`](prompts/widget-hilt-y-token-color.md) | Arquitectura | M |
| 10 | Un fallo en un móvil ajeno es invisible: no hay informe de errores de ningún tipo | [`informe-de-fallos.md`](prompts/informe-de-fallos.md) | Infra | L |
| ~~11~~ | ~~El JDK del sistema ya es 25 y **Gradle 8.13 no arranca con él**; hoy lo tapa un JDK pinchado a mano fuera del repo~~ **Hecho** (`docs/specs/2026-08-17-gradle9-agp9-jdk25.md`) | [`actualizacion-agp9-gradle9-jdk25.md`](prompts/actualizacion-agp9-gradle9-jdk25.md) | Infra | L |

> El **11** es el único que no sale de una sección *Out of Scope* ni de una nota en código: apareció
> solo, cuando el JDK del sistema se actualizó por debajo del proyecto. Se anota aquí igualmente
> porque es deuda de infraestructura y este es el backlog donde se mira.

## Dependencias de orden

Solo hay dos, y ambas son reales, no preferencias de estilo:

- **El 5 antes que el 1.** El widget adaptable multiplica filas y les añade barras de progreso.
  Arrastrar ahí una tarea ya completada que además se pinta la primera y en rojo es amplificar el
  fallo justo en la feature que lo hace más visible. El 5 es el más pequeño de los diez.
- **El 9 antes que las acciones por fila del 1.** La vinculación sin cualificar de `TaskRepository` es
  el decorador que refresca las superficies, así que una escritura hecha desde dentro del widget
  reentra en el propio widget. Leer es seguro; escribir no. Si no se hace el 9 primero, el 1 se limita
  a la parte visual y declara las acciones fuera de alcance. **El 9 ya está hecho**
  (`docs/specs/2026-08-17-widget-hilt-color-token.md`, D2): el widget entra en Hilt inyectando la capa
  `@ReminderRepo`, precisamente para que una futura escritura desde el widget no reentre. El
  prerrequisito del 1 queda cubierto; sus acciones por fila pueden implementarse.

El resto son independientes entre sí.

## Diferido por `gradle9-agp9-jdk25` (agosto 2026)

Lo que la subida a Gradle 9 / AGP 9 / JDK 25 dejó **a su vez** pendiente, por disciplina de alcance:

- **`jansi` avisa en cada arranque del backend sobre JVM 25.** Al pasar el runtime a
  `eclipse-temurin:25-jre`, el contenedor imprime cuatro `WARNING` de `java.lang.System::load` llamado
  por `org.fusesource.jansi` (entra transitivamente por `logback-classic`, para colorear consola —
  algo que a un backend en Docker no le sirve de nada). **Hoy son avisos, no errores**, y el servicio
  arranca y responde con normalidad; pero el propio mensaje dice que *"restricted methods will be
  blocked in a future release"*, así que esto **se convierte en un fallo de arranque** en un JDK
  futuro. Arreglo real: excluir `jansi` de `logback-classic` (lo correcto, no se usa) o añadir
  `--enable-native-access=ALL-UNNAMED` al `ENTRYPOINT` (lo cosmético, silencia el aviso sin quitar la
  causa). No se hizo aquí porque era un cambio de dependencias fuera de la cadena
  AGP/Gradle/Kotlin/KSP/Hilt.
- **`sun.misc.Unsafe` en los tests unitarios de la app.** `androidx.datastore.preferences.protobuf`
  llama a métodos terminalmente deprecados; los tests pasan (455/455) pero el aviso saldrá hasta que
  DataStore publique una versión que no los use. Es de una dependencia ajena: se espera, no se parchea.
- **`sourceSets.getByName("androidTest").assets.srcDir(...)`** emite un aviso de deprecación de Gradle
  (`use directories instead`). Sigue siendo funcionalmente correcto; cambiarlo no lo forzaba nada.
- **Caché de configuración** (D6 de la spec): sigue apagada a propósito. Adoptarla con KSP + Hilt +
  Room detrás es su propia feature.
- **`compileSdk` 37 y `hilt-navigation-compose` 1.3.0+/1.4.0+**: la 1.3.0 mueve `hiltViewModel()` a
  otro artefacto (tocaría imports de cuatro pantallas) y la 1.4.0 exige de verdad `compileSdk` 37.
  Subir el `compileSdk` es decisión de producto con su propia verificación.
- **Kotlin 2.4.x**: existe y es estable, pero se eligió 2.3.20 a propósito (es la línea que la matriz
  de AGP 9 recomienda). Subir es otra decisión, para otro día.

## Qué se dejó fuera a propósito

Del inventario completo, esto se consideró y **no** se convirtió en prompt:

- **CI** (`build` + tests unitarios en cada PR). `CLAUDE.md` lo nombra como hueco de producción con
  spec propia y no hay `.github/`. Sigue pendiente; simplemente no se pidió prompt.
- **Build de release firmada, R8 y HTTPS del backend** — ya tienen prompt desde hace tiempo en
  [`prompts/21-build-release.md`](prompts/21-build-release.md), y es la obligación **más repetida** de
  todo el repo (aparece en ocho sitios distintos).
- **Imágenes de cabecera de artículo con Coil** — ya tiene prompt en
  [`prompts/10b-coil-imagenes.md`](prompts/10b-coil-imagenes.md).
- **Decenas de pulidos visuales** (sombra del FAB, esquinas de la barra de progreso, `SearchBar` de
  Material 3 con historial, animación del tachado, skeletons de carga…), **funcionalidad grande sin
  pedir** (tareas recurrentes, colaboración, sincronización en tiempo real, favoritos de artículos) y
  **límites aceptados a conciencia** (sin registro sin conexión, sin resolución manual de conflictos,
  sin bloqueo real de otras apps). Todo eso sigue anotado donde estaba.
- **Un interruptor "reducir movimiento" dentro de la app** — decidido en contra en el feature
  `reduce-motion` (D1, `docs/specs/2026-08-17-reduce-motion.md`). El argumento que decide: el ajuste
  de sistema es la respuesta *per-user, cross-app* ya bendecida por la plataforma, y un interruptor
  local solo podría ser **aditivo** (reducir más, nunca menos) — en cuanto el sistema instala una
  `MotionDurationScale` en cero, `animateItem`/`animateFloatAsState` ya han colapsado a un salto
  instantáneo, y ningún interruptor de la app puede devolverles el movimiento sin envolver cada
  animación en su propio scale override. Un control que queda inerte justo en el caso en que alguien
  lo usaría es peor que no tener control. Si algún día gana el argumento de la *descubribilidad* (un
  usuario que nunca encontró el ajuste de Android podría encontrar el nuestro), el criterio compartido
  (`MotionSettings`) ya está detrás de una interfaz — añadir el override sería un cambio de una línea
  (`systemReduced || prefs.reduceMotion`) más una fila de Settings, sin tocar ningún punto de consumo.
- **Un tratamiento visual alternativo para movimiento reducido** (p. ej. un cross-fade donde antes
  había un slide, o un resaltado "acaba de cambiar" sin movimiento, para que un corte instantáneo
  siga siendo perceptible) — fuera de alcance del feature `reduce-motion`: implica **diseñar
  movimiento nuevo**, justo lo que ese feature explícitamente no hace (su único objetivo era la
  cadencia del contador, no mejorar ninguna animación). Si se quiere alguna vez, empieza como su
  propia spec.

---

## Cómo usar este documento

1. Elegir una fila. Si se elige la 1, mirar antes las dependencias de orden.
2. Pegar el prompt correspondiente en una sesión nueva de Claude Code.
3. El flujo normal hace el resto: pregunta del tutorial → spec con `project-manager-docs` → aprobación
   → rama → implementación → tests → revisión de diseño → commit.
4. Al terminar, tachar la fila aquí y anotar lo que la feature dejó **a su vez** diferido — que es
   exactamente como nació este documento.
