# Feature 15 (extra) — Iconografía y separación clara de secciones

Lee `CLAUDE.md` (**Tutorial Methodology** + **Mandatory Workflow**) y las lecciones previas (en
especial la 02: hub Home; la 07: pantalla de Ajustes). Implementa **"iconos de sección + agrupación
visual"** siguiendo el flujo `/feature`.

## Qué construir

- Dar **iconos** a las secciones del hub Home (Tareas, Artículos) y a la acción de Ajustes, usando
  `leadingContent` en los `ListItem` existentes y añadiendo `supportingContent` que describa cada
  opción.
- Reorganizar la pantalla de **Ajustes**: agrupar sus tres secciones (Tema, Recordatorios, Cuenta) en
  **`Card`s** con un encabezado con icono, y separar bloques con `HorizontalDivider` en lugar de solo un
  `Text` suelto.
- Resultado: jerarquía visual clara, cada sección reconocible de un vistazo.

## Conceptos nuevos a enseñar (lección en español)

- **Añadir una dependencia vía version catalog:** incorporar
  `androidx.compose.material:material-icons-extended` a `gradle/libs.versions.toml` (nunca hardcodear la
  versión en `build.gradle.kts`) y por qué el set core de iconos no basta.
- **`ListItem` completo:** `leadingContent`, `headlineContent`, `supportingContent`, `trailingContent`
  como bloques de construcción de una fila rica.
- **Semántica de iconos:** cuándo un `Icon` necesita `contentDescription` y cuándo es puramente
  decorativo (`contentDescription = null`).
- **Agrupación de superficies en Material 3:** `Card` vs `Surface`, `HorizontalDivider`, y el uso del
  espaciado/`tonalElevation` para separar sin recargar.

## Notas

- Rama sugerida: `feature/icons-sections`.
- **Referencia visual:** abre `docs/mockups/rediseno-ux-ui.html` (los `leadingContent` con icono de la
  lista y las secciones de Ajustes agrupadas en tarjetas con divisores muestran el objetivo). Es guía
  de dirección, **no** código a copiar.
- **Extiende, no dupliques:** reutiliza los `ListItem`/`Card` que ya usan Home y las filas de artículos;
  la sección de Ajustes ya tiene sus tres bloques y su `SelectableRadioRow` — se **envuelven** en
  tarjetas, no se reescriben.
- **Nueva dependencia** → reflejarla en `gradle/libs.versions.toml` (obligatorio, ver Documentation
  Update del `CLAUDE.md`). Sin backend, sin contrato, sin permisos.
- Ficheros: [`HomeScreen.kt`](../../app/src/main/java/com/neverlate/ui/home/HomeScreen.kt),
  [`SettingsScreen.kt`](../../app/src/main/java/com/neverlate/ui/settings/SettingsScreen.kt),
  `gradle/libs.versions.toml`.
- Agentes: `mobile-engineer` (UI + catálogo), `qa-engineer` (tests de UI de que las secciones y sus
  descripciones aparecen). Lección en `tutorial/15-iconos-secciones.md` (español), numerada tras la 14.
