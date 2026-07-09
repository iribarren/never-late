# Feature 13b (extra) — Migración de Room real + `TypeConverter`

Lee `CLAUDE.md` (**Tutorial Methodology** + **Mandatory Workflow**) y las lecciones previas (en
especial la 04: `Task` + Room; la 10: `ArticleEntity` en la misma `NeverLateDatabase`; y la 11: sync
y metadatos de tarea, versión 2→3). Implementa **"añadir un campo a `Task` con migración no
destructiva"** siguiendo el flujo `/feature`.

> **Superar el `fallbackToDestructiveMigration`.** Hasta ahora cada cambio de esquema **borraba** los
> datos (aceptado pre-release). Esta lección enseña la migración de verdad: conservar los datos al
> subir de versión.

## Qué construir

- Un **campo nuevo en `Task`** (p. ej. `notes: String` o `priority: Priority`), que suba la versión
  de `NeverLateDatabase`.
- Una **`Migration(N, N+1)`** explícita (o `AutoMigration` con `@AutoMigration`) que **conserve** las
  filas existentes, sustituyendo el `fallbackToDestructiveMigration` para ese salto.
- Si el campo es un `enum`/tipo no soportado, un **`TypeConverter`**.
- El campo se refleja en la UI de edición/lista donde tenga sentido.

## Conceptos nuevos a enseñar (lección en español)

- **Migraciones de Room:** `Migration` manual (`ALTER TABLE`), `AutoMigration`, y cuándo cada una;
  por qué `fallbackToDestructiveMigration` no vale en producción.
- **`TypeConverter`:** persistir tipos que Room no conoce (enum, `List`, fecha) y `@TypeConverters`.
- **Versionado de esquema:** subir `version`, y probar la migración con `MigrationTestHelper`.
- **Compatibilidad de datos:** valores por defecto para filas antiguas al añadir una columna.

## Notas

- Rama sugerida: `feature/room-migration`.
- **Extiende, no dupliques:** parte del `Task`/`TaskDao`/`NeverLateDatabase` existentes; el sync
  (metadatos `serverId`/`updatedAt`) no debe romperse — si el campo debe sincronizarse, revisa también
  el `TaskDto` y el contrato.
- Mapea a `docs/conceptos-pendientes.md` §6 (Datos: migraciones). Sin nueva dependencia (Room ya
  incluye el `testing` helper en el catálogo o se añade ahí).
- Ficheros: [`Task.kt`](../../app/src/main/java/com/neverlate/data/tasks/Task.kt),
  [`NeverLateDatabase.kt`](../../app/src/main/java/com/neverlate/data/tasks/NeverLateDatabase.kt),
  [`TaskDao.kt`](../../app/src/main/java/com/neverlate/data/tasks/TaskDao.kt) y la UI de edición.
- Agentes: `mobile-engineer` (campo + migración + converter), `qa-engineer` (test de migración con
  `MigrationTestHelper`: datos preservados). Lección en `tutorial/13b-migraciones-room.md` (español),
  numerada como **13b** (entre la 13 y la 14).
