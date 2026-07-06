# Feature 13 (extra) — Modo invitado (local-only) y fusión al iniciar sesión

Lee `CLAUDE.md` (**Tutorial Methodology** + **Mandatory Workflow**), la sección **API Contract**, y
las lecciones previas (en especial la 11: sync offline-first, outbox, `serverId`, tombstones,
last-write-wins). Implementa **"modo invitado + fusión de datos locales al registrarse"** siguiendo el
flujo `/feature`.

## Qué construir

- Que se pueda **usar la app sin cuenta** (modo invitado / local-only): crear, editar, completar y
  borrar tareas contra Room, sin puerta de auth obligatoria. La feature 11 dejó esto **diferido** a
  propósito (en la 11 la cuenta es obligatoria).
- Que, cuando un invitado **se registra o inicia sesión**, sus **tareas locales preexistentes se
  fusionen** con la cuenta en el servidor (no se pierden ni se duplican), y a partir de ahí sincronicen
  con normalidad.
- Un camino claro de vuelta: qué pasa con las tareas de un invitado si nunca crea cuenta (siguen siendo
  locales) y qué pasa al cerrar sesión.

## Conceptos nuevos a enseñar (lección en español)

- **Datos sin dueño de servidor coexistiendo con datos de cuenta:** modelar tareas "huérfanas"
  (sin `serverId`, creadas como invitado) y distinguirlas de las sincronizadas.
- **Migración/fusión de datos al hacer login:** empujar las tareas locales del invitado como *creates*
  con `clientRef` (idempotencia de la 11) para adoptarlas en la cuenta, resolviendo choques con lo que
  ya haya en el servidor sin duplicar.
- **Estado de auth de tres caras:** `Guest` / `LoggedOut` / `LoggedIn` — cómo cambia la navegación
  (ya no hay puerta obligatoria) y las superficies existentes (widget 05, notificación 06,
  recordatorios 09) según el estado.
- **Decisiones de producto sobre la fusión:** ¿se fusiona automáticamente al primer login, o se
  pregunta? ¿qué pasa al cerrar sesión con tareas ya adoptadas? (documentar el trade-off elegido).

## Notas

- Rama sugerida: `feature/guest-mode`.
- Amplía **notablemente** la superficie de sync respecto a la 11 (por eso se difirió): céntrate en la
  **fusión idempotente** reutilizando outbox + `clientRef` + last-write-wins, sin reinventarlos.
- Reutiliza el seam `TaskRepository`, `AuthRepository` (añadiendo el estado `Guest`), el `SyncEngine` y
  el `EncryptedTokenStorage` de la 11. Extiende, no dupliques.
- Si esta feature va **después** de la 12 (refresh token), ten en cuenta el par access/refresh; si va
  antes, ignóralo. El **contrato** (`docs/api/contract.md`) se actualiza primero si cambia algo del
  lado servidor (probablemente poco: la adopción usa el `POST /tasks` idempotente existente).
- Agentes: `mobile-engineer` (estado invitado + fusión), `backend-engineer` (si hace falta tocar la
  adopción), `qa-engineer` (tests de la fusión: local+servidor sin duplicar, `clientRef` estable,
  logout tras adopción). Lección en `tutorial/13-modo-invitado.md` (español), numerada tras la 12.
