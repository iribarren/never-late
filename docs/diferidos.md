# Trabajo diferido — lo que el propio trabajo dejó pendiente

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
| 6 | El ajuste de sistema "reducir movimiento" no llega a la cadencia de 1 s del contador | [`reducir-movimiento.md`](prompts/reducir-movimiento.md) | Papercut | S |
| 7 | Se pide el nombre en el primer arranque y **nadie lo lee nunca**, ni se puede cambiar | [`perfil-editable.md`](prompts/perfil-editable.md) | Papercut | S |
| 8 | El orden, el agrupado y la búsqueda se pierden al cerrar la app (y al morir el proceso) | [`preferencias-lista-persistentes.md`](prompts/preferencias-lista-persistentes.md) | Papercut | S |
| ~~9~~ | ~~El widget monta su repositorio a mano y hay dos mapeos de color gemelos que pueden desincronizarse~~ **Hecho** (`docs/specs/2026-08-17-widget-hilt-color-token.md`) | [`widget-hilt-y-token-color.md`](prompts/widget-hilt-y-token-color.md) | Arquitectura | M |
| 10 | Un fallo en un móvil ajeno es invisible: no hay informe de errores de ningún tipo | [`informe-de-fallos.md`](prompts/informe-de-fallos.md) | Infra | L |

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

---

## Cómo usar este documento

1. Elegir una fila. Si se elige la 1, mirar antes las dependencias de orden.
2. Pegar el prompt correspondiente en una sesión nueva de Claude Code.
3. El flujo normal hace el resto: pregunta del tutorial → spec con `project-manager-docs` → aprobación
   → rama → implementación → tests → revisión de diseño → commit.
4. Al terminar, tachar la fila aquí y anotar lo que la feature dejó **a su vez** diferido — que es
   exactamente como nació este documento.
