# Feature 18 (extra, opcional) — Barra de navegación inferior y accesibilidad

Lee `CLAUDE.md` (**Tutorial Methodology** + **Mandatory Workflow**) y las lecciones previas (en
especial cómo `AppNavHost` monta la puerta de auth de tres caras `LoggedOut`/`Guest`/`LoggedIn` de la
13). Implementa **"navegación con barra inferior + repaso de accesibilidad"** siguiendo el flujo
`/feature`.

> **Opcional / cambio arquitectónico mayor.** Reestructura la navegación (hoy es un hub Home +
> back-stack). Aborda solo si se quiere cambiar el modelo de navegación; las features 14–17 son
> independientes de esta.

## Qué construir

- Una **`NavigationBar`** inferior con las secciones principales (Tareas, Artículos, Ajustes),
  preservando la puerta de auth de tres caras y el arranque condicional (onboarding, `openTasksOnStart`
  del widget) de `AppNavHost`.
- **Repaso de accesibilidad transversal:** `contentDescription` coherentes, `semantics` donde falten,
  **tamaños de toque ≥ 48dp** y comprobación con **fuente dinámica** (texto grande) sin romper layouts.
- **Confirmar el `logout`** con un `AlertDialog` (hoy es un `TextButton` directo, a diferencia de borrar
  tarea que sí confirma).

## Conceptos nuevos a enseñar (lección en español)

- **Navegación con estado seleccionado:** `NavigationBar`/`NavigationBarItem`, mantener el ítem activo
  sincronizado con la ruta actual del `NavController`, y `saveState`/`restoreState`/`launchSingleTop`.
- **Accesibilidad a fondo:** el árbol de `semantics`, `mergeDescendants`, roles, y por qué los tamaños
  de toque y la fuente dinámica importan (mapea a `conceptos-pendientes.md` §7).
- **Coexistencia con la puerta de auth:** por qué la barra solo aparece en `MainAppNavHost` (Guest/
  LoggedIn) y no en la puerta de login, sin fusionar las ramas `when` que la 13 mantiene separadas a
  propósito.

## Notas

- Rama sugerida: `feature/bottom-nav-a11y`.
- **Referencia visual:** abre `docs/mockups/rediseno-ux-ui.html` como muestra de la dirección de diseño
  general. Es guía de dirección, **no** código a copiar.
- **Extiende, no dupliques:** el `NavController`, las rutas del `object Routes` y la lógica de arranque
  **ya existen** en `AppNavHost` — se añade el `Scaffold` con `bottomBar`, **respetando** las dos ramas
  `Guest`/`LoggedIn` separadas (no fusionarlas: la 13 depende de que se recompongan por separado).
- Reutiliza el `DeleteTaskDialog` como patrón para el diálogo de confirmación de logout.
- Ficheros: [`AppNavHost.kt`](../../app/src/main/java/com/neverlate/ui/navigation/AppNavHost.kt),
  [`SettingsScreen.kt`](../../app/src/main/java/com/neverlate/ui/settings/SettingsScreen.kt), y toques de
  `semantics` en las pantallas existentes. Sin backend, sin contrato, sin nueva dependencia.
- Agentes: `mobile-engineer` (navegación + a11y), `qa-engineer` (tests de navegación entre pestañas y de
  que logout pide confirmación). Lección en `tutorial/18-navegacion-accesibilidad.md` (español),
  numerada tras la 17.
