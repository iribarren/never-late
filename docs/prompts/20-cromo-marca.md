# Feature 20 (extra) — Cromo de marca: top app bars, chips de icono y FAB

Lee `CLAUDE.md` (**Tutorial Methodology** + **Mandatory Workflow** + **Design in the Workflow**) y las
lecciones previas (en especial la 16: identidad visual — paleta de marca, escala tipográfica y
`NeverLateExtras`; y la 15: tarjetas con icono+título en Ajustes). Implementa **"cromo de marca:
barras superiores, chips de icono y FAB con el color de la marca"** siguiendo el flujo `/feature`.

> **Cierra varias `slices` visuales.** La 16 trajo la **paleta**, pero el color de marca casi no llegó
> a los componentes: las `TopAppBar` usan la superficie por defecto de Material 3, las filas no tienen
> el chip de icono coloreado del maquetado y el FAB va sin tratamiento de marca (ver las filas ⬜/🟡 en
> `docs/mockups/README.md`). Esta feature aplica la marca a esos componentes.

## Qué construir

- **`TopAppBar` con color de marca** en las pantallas principales, usando `TopAppBarDefaults` y los
  roles de color del tema (p. ej. `primaryContainer`/`onPrimaryContainer`), en lugar de la superficie
  por defecto. Coherente en claro/oscuro y con `dynamicColor` (Material You) activado.
- Un **chip de icono principal** reutilizable (contenedor redondeado con `secondaryContainer`/
  `brand-container`) como `leadingContent` de las filas de tarea y de artículo, como en el maquetado.
- **FAB con tratamiento de marca** (color de contenedor + elevación coherentes con el resto del cromo).
- Repaso de que el color **nunca es el único portador de significado** (contraste suficiente, y los
  estados que ya se apoyaban en texto/urgencia lo siguen haciendo) — continúa el repaso de
  accesibilidad de la 18.

## Conceptos nuevos a enseñar (lección en español)

- **Roles de color de Material 3 a fondo:** `primary`/`primaryContainer`/`onPrimaryContainer`,
  `secondaryContainer`, `surface`… cómo un componente pide un **rol** al `MaterialTheme.colorScheme` en
  vez de un color fijo, y por qué eso hace que claro/oscuro y Material You funcionen "gratis".
- **Colorear componentes con sus `*Colors` / `*Defaults`:** `TopAppBarDefaults.topAppBarColors(...)`,
  colores de `FloatingActionButton`/`NavigationBar` — el patrón "no pintes a mano, pásale un objeto de
  colores".
- **Extraer un componente reutilizable de marca:** un chip de icono en `ui/components` (como
  `MessageState` de la 17), con su `contentDescription` decorativo `null` cuando acompaña a un texto.
- **Contraste y accesibilidad del color:** por qué un `container` va siempre con su `on-container`, y
  cómo comprobar que el texto sigue legible en ambos temas.

## Notas

- Rama sugerida: `feature/branded-chrome`.
- **Diseño (obligatorio en el spec):** la sección **Visual & UX Design** debe nombrar las `slices`
  "Brand-colored top app bars", "Colored leading-icon chips" y "Branded FAB styling" de
  `docs/mockups/README.md`, con criterios de aceptación visuales por componente (rol de color usado,
  contraste, comportamiento en oscuro/Material You). En el **Design review**, mover esas filas a ✅.
- **Referencia visual:** abre `docs/mockups/rediseno-ux-ui.html` (`.appbar` de marca, `.leading` como
  chip coloreado, `.fab`). Es guía de dirección, **no** código a copiar: tradúcela con los tokens
  reales del tema (`ui/theme/`), sin introducir colores nuevos fuera de la paleta de la 16.
- **Extiende, no dupliques:** la paleta y `NeverLateExtras` **ya existen** (16); el
  `SettingsSectionCard` ya combina icono+título (15) — el chip nuevo es el mismo lenguaje aplicado a las
  filas. Todas las pantallas ya usan `Scaffold`+`TopAppBar`: se les pasa un `colors=`, no se reescriben.
- Mapea a `docs/conceptos-pendientes.md` §7 (Recursos y UI: theming). Sin backend, sin contrato, sin
  nueva dependencia.
- Ficheros: [`TasksScreen.kt`](../../app/src/main/java/com/neverlate/ui/tasks/TasksScreen.kt),
  [`ArticlesScreen.kt`](../../app/src/main/java/com/neverlate/ui/articles/ArticlesScreen.kt),
  [`SettingsScreen.kt`](../../app/src/main/java/com/neverlate/ui/settings/SettingsScreen.kt),
  las pantallas de detalle/edición y la `NavigationBar` de
  [`AppNavHost.kt`](../../app/src/main/java/com/neverlate/ui/navigation/AppNavHost.kt), más un nuevo
  chip reutilizable en `ui/components`.
- Agentes: `mobile-engineer` (theming de componentes + chip), `qa-engineer` (tests de UI de que las
  filas muestran el chip y de que el cromo no rompe los controles existentes). Lección en
  `tutorial/20-cromo-marca.md` (español), numerada tras la 19.
