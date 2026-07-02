# Feature 02 — Onboarding + Home

> Pega este prompt en una sesión nueva de Claude Code para arrancar la feature.

Lee `CLAUDE.md` (en especial la sección **Tutorial Methodology** y el **Mandatory Workflow**) y la
lección previa `tutorial/00-entorno-y-hola-mundo.md`. Luego implementa la feature **"Onboarding +
Home"** siguiendo el flujo `/feature`.

## Qué construir
- **Pantalla de onboarding** (primer arranque): el usuario introduce sus datos (al menos su
  nombre). Al guardar, se marca como "onboarded".
- **Pantalla Home** (usuario ya registrado): saludo personalizado y una lista de opciones de la
  app (de momento placeholders para las futuras features: tareas, artículos…).
- **Routing de arranque**: si no hay onboarding hecho → mostrar onboarding; si ya está → Home.

## Conceptos nuevos a enseñar (lección `tutorial/01-*.md`, en español)
- Estado en Compose: `remember`, `mutableStateOf`, state hoisting.
- `TextField`, `Button`, validación básica de formulario.
- `ViewModel` + `StateFlow` para exponer el estado de la pantalla.
- **Navigation Compose** (grafo de navegación entre onboarding y home).
- **DataStore (Preferences)** para persistir el nombre y el flag `onboarded`.
- Material 3: `Scaffold`, `TopAppBar`.

## Notas
- Rama sugerida: `feature/onboarding-home`.
- Añade dependencias vía el catálogo `gradle/libs.versions.toml` (DataStore, Navigation, Lifecycle
  ViewModel Compose).
- Tests con `qa-engineer` (ViewModel + un test de UI Compose del formulario).
- Recuerda la lección en `tutorial/01-onboarding-home.md` antes de commitear.
