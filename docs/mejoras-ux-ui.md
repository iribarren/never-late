# Mejoras de UX/UI — análisis y hoja de ruta

> **Qué es esto:** un análisis del estado actual de la interfaz (tosca y de plantilla) con una lista
> **priorizada** de mejoras de diseño y usabilidad, cada una convertida en una **feature/lección**
> del tutorial (con el concepto nuevo de Kotlin/Compose que enseña) y con un **prompt listo para
> pegar** en [`docs/prompts/`](prompts/). Incluye también las **herramientas de diseño** que encajan
> con Claude Code.
>
> **Qué NO es:** un compromiso ni un plan cerrado. **No implementes nada de aquí todavía** — cuando se
> elija una mejora, se sigue el flujo normal (spec → aprobación → rama → implementación → lección),
> ver el [flujo de nueva feature](../CLAUDE.md) y los prompts numerados **14–18** ya redactados.
>
> Complementa a [`conceptos-pendientes.md`](conceptos-pendientes.md): varias de estas mejoras encajan
> con conceptos que aquel documento ya marcaba pendientes (animaciones, side-effects, Material You,
> accesibilidad), pero aquí el punto de partida es el **problema de UX**, no el concepto.

---

## Diagnóstico del estado actual

La app funciona pero la UI es de plantilla y de baja fricción visual. Hallazgos concretos (verificados
en código):

- **Fricción #1 — la fecha se teclea a mano.** El `deadline` se introduce como **texto libre** con el
  patrón fijo `dd/MM/yyyy HH:mm`, en un `OutlinedTextField` **sin picker, sin teclado adecuado y sin
  icono** ([TaskEditScreen.kt:138-145](../app/src/main/java/com/neverlate/ui/tasks/TaskEditScreen.kt#L138-L145)).
  Cualquier desviación del patrón da error de validación. Es la interacción más costosa de la app.
- **Sin iconografía de secciones.** El hub Home usa `ListItem` **planos, sin `leadingContent`**
  ([HomeScreen.kt](../app/src/main/java/com/neverlate/ui/home/HomeScreen.kt)); Ajustes separa sus tres
  secciones **solo con un `Text` `titleMedium`** — sin `Divider`, sin `Card`, sin icono
  ([SettingsScreen.kt](../app/src/main/java/com/neverlate/ui/settings/SettingsScreen.kt)).
- **Identidad visual débil.** La paleta morada de plantilla de Android Studio está **intacta**
  ([Color.kt](../app/src/main/java/com/neverlate/ui/theme/Color.kt),
  [Theme.kt](../app/src/main/java/com/neverlate/ui/theme/Theme.kt)) y `Type.kt` es casi por defecto.
  Además `dynamicColor = true` hace que en Android 12+ el color lo ponga el **fondo de pantalla** del
  usuario, así que no hay marca propia **ni control** para desactivarlo.
- **Sin animaciones ni micro-interacciones.** La cuenta atrás es texto plano `mm:ss` **sin señal de
  urgencia**; los estados vacío/error son **texto centrado sin icono**; el `SnackbarHost` está
  cableado en Home pero **sin usar**; las listas no usan `animateItem` (aparición/borrado sin
  transición).
- **Navegación solo por hub + back-stack.** No hay barra inferior; todo pasa por Home y la pila de
  navegación ([AppNavHost.kt](../app/src/main/java/com/neverlate/ui/navigation/AppNavHost.kt)).
- **Acción destructiva sin confirmar.** `logout` es un `TextButton` directo en Ajustes, **sin diálogo
  de confirmación** (borrar una tarea sí lo tiene).

---

## Mejoras priorizadas

Ordenadas por relación valor/esfuerzo. Cada fila es una feature con su prompt ya redactado.

| # | Mejora | Problema que resuelve | Concepto nuevo que enseña | Prompt | Esfuerzo |
|---|--------|-----------------------|---------------------------|--------|----------|
| 14 | **Selector de fecha/hora nativo** | La fricción #1: teclear el `deadline` a mano | Diálogos en Compose, `DatePickerState`/time picker, millis **UTC** del picker | [`14-selector-fecha.md`](prompts/14-selector-fecha.md) | Bajo |
| 15 | **Iconografía y separación de secciones** | Home y Ajustes planos, sin jerarquía visual | Dependencia vía version catalog, `ListItem` con `leadingContent`, `HorizontalDivider`, semántica de `Icon` | [`15-iconos-secciones.md`](prompts/15-iconos-secciones.md) | Bajo |
| 16 | **Identidad visual (tema, color, tipografía)** | Paleta de plantilla, sin marca ni control de Material You | Theming M3 a fondo, escala tipográfica, recursos de fuente, trade-off de `dynamicColor` | [`16-identidad-visual.md`](prompts/16-identidad-visual.md) | Medio |
| 17 | **Estados vacíos, animaciones y urgencia** | UI estática y sin feedback; cuenta atrás sin urgencia | Animaciones (`animateItem`, `AnimatedVisibility`), side-effects (`LaunchedEffect`, `derivedStateOf`) | [`17-estados-animaciones.md`](prompts/17-estados-animaciones.md) | Medio |
| 18 | **Barra inferior + accesibilidad** (opcional) | Navegación solo por hub; a11y sin repaso; logout sin confirmar | `NavigationBar`, `semantics`, tamaños de toque, fuente dinámica | [`18-navegacion-accesibilidad.md`](prompts/18-navegacion-accesibilidad.md) | Alto |

**Recomendación de secuencia:** 14 → 15 → 16 → 17, y la 18 solo si se quiere reestructurar la
navegación (es un cambio arquitectónico mayor sobre `AppNavHost`). La 14 y la 15 son las de mayor
retorno inmediato.

### Ideas menores (sin prompt propio, para incorporar dentro de las de arriba)
- **Confirmar el `logout`** con un `AlertDialog` como el de borrar tarea (cabe en la 18, o como bugfix
  aparte).
- **Teclado numérico** ya está bien en duración; revisar `imeAction`/`Next` entre campos del formulario.
- **`FloatingActionButton` extendido** con texto "Nueva tarea" en la lista vacía (cabe en la 17).
- **`supportingContent`** en las tarjetas de Home describiendo cada sección (cabe en la 15).

---

## Herramientas de diseño que encajan con Claude Code

Cómo cada una se integra en este flujo de trabajo:

- **Figma + Dev Mode MCP server.** Figma expone un **servidor MCP** que Claude Code puede consumir para
  leer un diseño y generar Compose a partir de él. Requiere **autorizar el MCP en una sesión
  interactiva** (`claude mcp` o `/mcp`); en sesiones no interactivas no puede completarse el OAuth.
  Útil cuando se parte de una maqueta en Figma.
- **Material Theme Builder** (m3.material.io). Genera una paleta Material 3 completa (roles de color,
  claro/oscuro) desde un color semilla y **exporta `Color.kt`/`Theme.kt`** listos para pegar.
  Alimenta directamente la **feature 16** — se evita elegir colores a mano.
- **Android Studio: `@Preview` + Compose/Interactive Preview + Layout Inspector.** Ya en uso en todas
  las pantallas. Las capturas de preview son PNG que **Claude puede leer** para iterar sobre el diseño
  sin arrancar la app.
- **Screenshot testing (Roborazzi o Paparazzi).** Renderizan composables a **PNG sin emulador**; Claude
  lee esos PNG para verificar cambios visuales y detectar regresiones. Encaja además con el hueco de
  testing que señala [`conceptos-pendientes.md`](conceptos-pendientes.md) (§3).
- **`adb exec-out screencap -p > shot.png`.** Captura del emulador/dispositivo en marcha para que
  Claude compruebe el resultado **real** tras un cambio (complementa la skill `/run` y `/verify`).
- **Artifacts HTML de Claude Code.** Maquetas navegables (claro/oscuro, responsive) para acordar la
  dirección visual **antes** de escribir Compose. La de este rediseño vive **versionada** en el repo,
  en [`mockups/rediseno-ux-ui.html`](mockups/rediseno-ux-ui.html), para que cualquier sesión que
  implemente una feature pueda leerla directamente (cada prompt 14–18 la referencia en sus *Notas*).

---

## Cómo usar este documento

1. Elegir una mejora de la tabla (recomendado empezar por la **14**).
2. Arrancar el **flujo de nueva feature** del `CLAUDE.md`: pegar el prompt correspondiente de
   [`docs/prompts/`](prompts/) en una sesión nueva (spec con `project-manager-docs` → aprobación →
   rama `feature/<nombre>` → implementación → **lección en español** `tutorial/NN-*.md`).
3. Numerar la lección según su orden real y actualizar la tabla/prompts si cambia la secuencia.
