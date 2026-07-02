# Feature 11 (extra) — Datos en una BBDD remota (backend + sync)

> Extra de complejidad **alta** (la más compleja del roadmap). Pega este prompt en una sesión
> nueva para arrancarla.

Lee `CLAUDE.md` (**Tutorial Methodology** + **Mandatory Workflow**) y las lecciones previas.
Implementa **"Datos en BBDD remota + sincronización"** siguiendo el flujo `/feature`.

## Qué construir
- Que **todos los datos** (tareas, perfil, etc.) se almacenen en una **base de datos remota** vía
  un backend, con **sincronización** con la copia local (offline-first).
- Autenticación básica del usuario.

## Conceptos nuevos a enseñar (lección en español)
- Diseño de **API** (contrato como fuente de verdad) y consumo desde el cliente.
- **Auth** (tokens) y almacenamiento seguro de credenciales.
- **Sync offline-first**: Room como caché local + cola de cambios + resolución de conflictos.
- Seguridad (la lógica sensible vive en el backend, no en el cliente).

## Notas
- Rama sugerida: `feature/remote-db-sync`.
- **Importante:** al introducir backend, **reintroduce en `CLAUDE.md` la sección "API Contract"**
  (que se eliminó por ser app local) y trata la spec de API como fuente única de verdad.
- Probablemente intervengan los agentes `backend-engineer` (si se añade) y
  `devops-security-engineer`. Puede requerir su propio repo/servicio y `docker compose`.
- Tests con `qa-engineer` (cliente contra API mock + lógica de sync). Lección en
  `tutorial/11-bbdd-remota.md`.
