# Feature 07 (extra) — Ajustes + modo oscuro

> Extra de complejidad **baja**. Pega este prompt en una sesión nueva para arrancarla.

Lee `CLAUDE.md` (**Tutorial Methodology** + **Mandatory Workflow**) y las lecciones previas.
Implementa la feature **"Ajustes + modo oscuro"** siguiendo el flujo `/feature`.

## Qué construir
- Pantalla de **Ajustes** accesible desde Home.
- Toggle de **tema claro / oscuro / seguir sistema**, persistido y aplicado en toda la app.

## Conceptos nuevos a enseñar (lección en español)
- Repaso y profundización de **DataStore** para preferencias de usuario.
- Tematizado dinámico de Material 3 según preferencia (conectar con `NeverLateTheme`).
- Exponer preferencias de forma reactiva (`Flow` → estado de la app).

## Notas
- Rama sugerida: `feature/settings-dark-mode`.
- Encaja bien después del onboarding (reutiliza DataStore). Lección en `tutorial/07-ajustes.md`.
