# Feature 09 (extra) — Recordatorios / notificaciones locales

> Extra de complejidad **media**. Pega este prompt en una sesión nueva para arrancarla.

Lee `CLAUDE.md` (**Tutorial Methodology** + **Mandatory Workflow**) y las lecciones previas.
Requiere la feature de **tareas** (04). Implementa **"Recordatorios de tareas"** siguiendo el
flujo `/feature`.

## Qué construir
- Programar **avisos** antes del vencimiento de una tarea (p. ej. "faltan 10 min").
- El usuario recibe una **notificación** aunque la app esté cerrada.

## Conceptos nuevos a enseñar (lección en español)
- Programación de trabajo diferido: **WorkManager** y/o **AlarmManager** (exactos vs no exactos).
- Notificaciones (canales, permiso `POST_NOTIFICATIONS`).
- Reprogramar recordatorios al reiniciar el dispositivo (`BOOT_COMPLETED`).

## Notas
- Rama sugerida: `feature/reminders`.
- Encaja **antes** del widget/lockscreen (comparte notificaciones/permiso). Si ya se hicieron,
  reutiliza su infraestructura.
- Tests con `qa-engineer` sobre la lógica de programación. Lección en `tutorial/NN-recordatorios.md`.
