# Feature 16 (extra) — Identidad visual: tema, color y tipografía propios

Lee `CLAUDE.md` (**Tutorial Methodology** + **Mandatory Workflow**) y las lecciones previas (en
especial la 07: preferencia de tema `ThemeMode` + DataStore `user_prefs`, aplicada en
`NeverLateTheme`). Implementa **"identidad visual propia (marca)"** siguiendo el flujo `/feature`.

## Qué construir

- Reemplazar la **paleta morada de plantilla** (`Color.kt`, `Theme.kt`) por una **paleta Material 3
  propia** de la marca, con roles de color completos para claro y oscuro (generada con **Material Theme
  Builder**, ver `docs/mejoras-ux-ui.md`).
- Definir una **escala tipográfica** propia en `Type.kt` (y opcionalmente una fuente en `res/font/`), en
  lugar del `Typography` casi por defecto actual.
- Añadir un **ajuste** para activar/desactivar **`dynamicColor`** (Material You): hoy está forzado a
  `true`, así que en Android 12+ el color lo pone el fondo de pantalla y la marca desaparece. El usuario
  debe poder elegir "color de la app" vs "color del sistema".

## Conceptos nuevos a enseñar (lección en español)

- **Theming de Material 3 a fondo:** qué son los **roles de color** (`primary`, `surface`,
  `onSurfaceVariant`, `container`…) y por qué se diseñan por rol y no por widget.
- **Escala tipográfica y recursos de fuente:** el sistema de estilos `displayLarge…labelSmall`, y cargar
  una fuente propia desde `res/font/` con `FontFamily`.
- **El compromiso de `dynamicColor` (Material You):** ventajas (integración con el sistema) frente a la
  identidad de marca, y cómo hacerlo **configurable** en vez de fijo.
- **Reutilizar el patrón de preferencia existente:** extender el DataStore `user_prefs` y `ThemeMode`
  (feature 07) con la nueva preferencia, aplicada en `NeverLateTheme` — **el mismo seam**, no uno nuevo.

## Notas

- Rama sugerida: `feature/visual-identity`.
- **Referencia visual:** abre `docs/mockups/rediseno-ux-ui.html` — su bloque `:root` de tokens contiene
  la **paleta de propuesta** (primario de marca + escala de urgencia semántica) que puedes tomar como
  punto de partida al generar el tema en Material Theme Builder. Es guía de dirección, **no** código a
  copiar.
- **Extiende, no dupliques:** la infraestructura de preferencia de tema (DataStore, `ViewModel` de
  Ajustes, `themeModeToDark`, `dynamicColor` como parámetro de `NeverLateTheme`) **ya existe** desde la
  07 — se añade una preferencia más siguiendo ese patrón, no se rehace el theming.
- Usa **Material Theme Builder** (m3.material.io) para generar/exportar `Color.kt`/`Theme.kt`; no elijas
  colores a mano. Si añades fuente, decláralo en la Documentation Update.
- Ficheros: [`Color.kt`](../../app/src/main/java/com/neverlate/ui/theme/Color.kt),
  [`Theme.kt`](../../app/src/main/java/com/neverlate/ui/theme/Theme.kt),
  [`Type.kt`](../../app/src/main/java/com/neverlate/ui/theme/Type.kt),
  [`SettingsScreen.kt`](../../app/src/main/java/com/neverlate/ui/settings/SettingsScreen.kt) + su
  ViewModel/DataStore. Sin backend, sin contrato.
- Agentes: `mobile-engineer` (tema + preferencia), `qa-engineer` (test de que la preferencia se
  persiste y `themeModeToDark`/la resolución de color reaccionan). Lección en
  `tutorial/16-identidad-visual.md` (español), numerada tras la 15.
