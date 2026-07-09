# Feature 18b (extra) — Layouts adaptables para pantallas grandes (tablet)

Lee `CLAUDE.md` (**Tutorial Methodology** + **Mandatory Workflow** + **Design in the Workflow**) y las
lecciones previas (en especial la 18: `NavigationBar`, `Scaffold` y repaso de accesibilidad; y la 03:
lista + detalle de artículos). Implementa **"que la app aproveche pantallas grandes"** siguiendo el
flujo `/feature`.

> **Cierra la mitad "adaptativa" que la 18 dejó fuera.** La 18 cubrió accesibilidad (semántica, 48dp,
> fuente dinámica) pero no los **tamaños de pantalla**. Aquí la app deja de estar pensada solo para
> móvil en vertical.

## Qué construir

- Uso de **`WindowSizeClass`** para decidir el layout según el ancho disponible.
- Un **patrón list-detail** en ancho grande (p. ej. artículos: lista + detalle en dos paneles en
  tablet/horizontal, navegación normal en móvil).
- La navegación principal como **`NavigationRail`** en ancho grande en vez de `NavigationBar` inferior,
  preservando las tres secciones y la puerta de auth de la 13.
- Verificar reflow correcto en móvil, tablet y horizontal, y con fuente grande (no regresión de a11y).

## Conceptos nuevos a enseñar (lección en español)

- **`WindowSizeClass`** (compact/medium/expanded) y cómo obtenerlo en Compose.
- **Layouts adaptables:** list-detail (`ListDetailPaneScaffold` o composición manual), y cuándo dividir
  en paneles.
- **Navegación adaptable:** `NavigationBar` ↔ `NavigationRail` según tamaño, sin duplicar el grafo.
- **Probar tamaños:** previews con distintos `widthDp`, y por qué el diseño responsive complementa la
  accesibilidad.

## Notas

- Rama sugerida: `feature/adaptive-layouts`.
- **Diseño (obligatorio en el spec):** la sección **Visual & UX Design** debe indicar cómo se comporta
  cada pantalla en compact/expanded (y qué `slice` del maquetado aplica, si alguna), con criterios de
  aceptación visuales por tamaño. En el **Design review**, verificar en emulador de tablet.
- **Referencia visual:** abre `docs/mockups/rediseno-ux-ui.html` como guía de dirección, **no** código
  a copiar.
- **Extiende, no dupliques:** reutiliza el grafo y las pantallas de la 18 (`AppNavHost`,
  `TasksScreen`/`ArticlesScreen`/`SettingsScreen` con su `onBack` nullable); el layout adaptable es una
  capa por encima, no un segundo grafo.
- Mapea a `docs/conceptos-pendientes.md` §7 (accesibilidad/adaptativo). Nueva dependencia posible:
  `material3-adaptive` / `material3-window-size-class` en el catálogo.
- Ficheros: [`AppNavHost.kt`](../../app/src/main/java/com/neverlate/ui/navigation/AppNavHost.kt),
  [`ArticlesScreen.kt`](../../app/src/main/java/com/neverlate/ui/articles/ArticlesScreen.kt) y detalle,
  y `MainActivity` (obtención del `WindowSizeClass`).
- Agentes: `mobile-engineer` (adaptive + rail), `qa-engineer` (tests de UI en compact/expanded).
  Lección en `tutorial/18b-layouts-adaptables.md` (español), numerada como **18b** (entre la 18 y la 19).
