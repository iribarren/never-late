# Feature 07b (extra) — Arquitectura: poner nombre al patrón (UDF / MVVM / capas)

Lee `CLAUDE.md` (**Tutorial Methodology** + **Mandatory Workflow**) y las lecciones previas (en
especial la 02: `ViewModel` + `StateFlow` + estado hoisteado; la 04: repos y funciones puras de
dominio; la 07: DataStore). Implementa **"consolidar y documentar la arquitectura ya usada"**
siguiendo el flujo `/feature`.

> **Lección transversal, poca UI nueva.** Desde la 02 la app usa UDF/MVVM y capas sin nombrarlo. Esta
> lección *pone nombre*, diagrama y consolida — no reescribe comportamiento. Es más didáctica que de
> producto: el "código nuevo" es sobre todo pequeñas alineaciones para que el patrón sea coherente.

## Qué construir

- Un **repaso/consolidación** del patrón: identificar y, si hace falta, alinear los seams UI ↔
  dominio ↔ datos que hoy existen (repos, ViewModels, `StateFlow`, funciones puras).
- Pequeñas mejoras de coherencia si aparecen (p. ej. un `UiState` que no siga el patrón, un cálculo
  que debería estar en dominio) — **sin** cambiar comportamiento observable.
- Un diagrama/explicación de capas en la propia lección.

## Conceptos nuevos a enseñar (lección en español)

- **UDF (flujo de datos unidireccional):** estado baja, eventos suben; por qué la UI es una función
  del estado.
- **MVVM en Android:** el rol del `ViewModel`, `StateFlow` como fuente de estado, la UI stateless.
- **Capas UI / dominio / datos:** qué vive en cada una (Compose; funciones puras como
  `ReminderPlanning`/`urgencyLevelFor`/`SyncMerge`; repos/Room/red), y por qué el dominio es puro y
  testeable.
- **El "seam" como concepto:** interfaces (`TaskRepository`) que permiten decorar/inyectar sin tocar
  la UI (ya usado por los decoradores de sync/recordatorios).

## Notas

- Rama sugerida: `feature/architecture-consolidation`.
- **Extiende, no dupliques:** el objetivo es *nombrar y documentar* lo que ya hay, no introducir una
  arquitectura nueva. Cualquier cambio debe ser mínimo y justificado por coherencia.
- Mapea a `docs/conceptos-pendientes.md` §4 (Arquitectura). Sin backend, sin contrato, sin nueva
  dependencia.
- Ficheros: transversal — sobre todo `ui/`, `domain/` y `data/`; probablemente muchos toques pequeños
  o ninguno, más la lección.
- Agentes: `mobile-engineer` (alineaciones puntuales), `qa-engineer` (verificar que no hay regresión:
  los tests existentes siguen verdes). Lección en `tutorial/07b-arquitectura.md` (español), numerada
  como **07b** (entre la 07 y la 08).
