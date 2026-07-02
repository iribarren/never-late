# Feature 06 — Tareas en la pantalla de bloqueo

> Pega este prompt en una sesión nueva de Claude Code para arrancar la feature.

Lee `CLAUDE.md` (**Tutorial Methodology** + **Mandatory Workflow**) y las lecciones previas.
Requiere la feature de **tareas** (04). Implementa la feature **"Tareas en el lockscreen"**
siguiendo el flujo `/feature`.

## Qué construir
- Poder **consultar las tareas pendientes desde la pantalla de bloqueo**, con su tiempo restante.
- Enfoque recomendado: una **notificación persistente/continua** (visible en lockscreen) que
  muestre las tareas próximas; opcionalmente un servicio en primer plano mientras haya un
  contador activo.

## Conceptos nuevos a enseñar (lección `tutorial/06-*.md`, en español)
- **Notificaciones**: canales, `NotificationCompat`, notificación continua/actualizable.
- **Visibilidad en lockscreen** y buenas prácticas de privacidad.
- Permiso **`POST_NOTIFICATIONS`** (Android 13+) y su solicitud en runtime.
- **Foreground service** y su ciclo de vida (si se usa para el contador activo).

## Notas
- Rama sugerida: `feature/tasks-lockscreen`.
- Declara permisos/servicios en el `AndroidManifest.xml` y explícalo en la lección.
- Tests con `qa-engineer` donde aplique. Lección en `tutorial/06-lockscreen.md` antes de
  commitear.
