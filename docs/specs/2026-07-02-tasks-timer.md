# Especificación — Tareas + contador de tiempo

- **Fecha:** 2026-07-02
- **Feature:** Tareas con contador de cuenta atrás (Tasks + time countdown timer)
- **Prompt origen:** `docs/prompts/04-tareas-contador.md`
- **Rama sugerida:** `feature/tasks-timer`
- **Lección tutorial asociada:** `tutorial/04-tareas-contador.md` (español)
- **Estado:** Pendiente de aprobación

---

## Overview

Esta feature es el **núcleo de Never Late Again**: permite a la persona usuaria **crear, listar,
editar y borrar tareas**, y para cada tarea ver un **contador de cuenta atrás** con el tiempo
restante que se puede **iniciar y pausar**. Las tareas se **persisten en el dispositivo** con
**Room (SQLite)**, de modo que sobreviven al cierre de la app.

El objetivo de producto es que las personas con TDA/TDAH puedan externalizar la gestión del tiempo
en la app: apuntar lo que tienen que hacer, cuánto estiman que les llevará o para cuándo es, y
tener una señal visual clara (la cuenta atrás) de cuánto tiempo les queda. Esto ataca directamente
la ceguera temporal (*time blindness*) que es central en el TDA/TDAH.

Todo es **local-only** (sin backend); la seguridad de los datos en el dispositivo es un requisito
de MVP, no un extra. La app sigue sin necesitar el permiso `INTERNET`.

Esta es la primera feature con **base de datos**. Hasta ahora la persistencia era clave-valor con
DataStore (lección 02) y el contenido de artículos venía empaquetado tras una interfaz de
repositorio (lección 03). Aquí damos el salto a **datos estructurados y CRUD completo** sobre Room,
reutilizando el patrón **repositorio (interfaz) + `ViewModel` + `StateFlow`** y la **inyección de
dependencias manual** (`AppViewModelFactory`, instanciación en `MainActivity`) ya establecidos.

### Reutilización por features futuras (restricción de diseño clave)

El **repositorio de tareas** y su base de datos Room los reutilizarán:

- La **feature 05 — Widget** (`docs/prompts/05-widget-tareas.md`): el widget de Glance leerá las
  tareas pendientes y su tiempo restante desde el mismo repositorio Room.
- La **feature 06 — Lockscreen** (`docs/prompts/06-tareas-lockscreen.md`): la notificación
  persistente / foreground service leerá las mismas tareas.

Por tanto la capa de datos debe diseñarse **para compartirse**: repositorio tras **interfaz**,
consultas que devuelven **`Flow`** (para que widget y notificación puedan observar cambios), y una
única instancia de la base de datos (`@Database` como singleton).

### Conceptos nuevos que enseña (para `tutorial/04-tareas-contador.md`)

- **Room (SQLite):** `@Entity`, `@Dao`, `@Database`, consultas que devuelven `Flow`, y `KSP` como
  procesador de anotaciones.
- **CRUD completo** (crear / leer / actualizar / borrar) desde un DAO → repositorio → `ViewModel`.
- **Coroutines y `Flow`** aplicados a un **temporizador** (cuenta atrás con `delay`).
- **Estado más complejo en Compose** (varias tareas, cada una con estado de contador) y
  `collectAsStateWithLifecycle` para observar `Flow` de forma consciente del ciclo de vida.
- **Formato de tiempo y fechas** (duración restante `mm:ss` / `hh:mm:ss`, fecha límite legible).

---

## Goals

Éxito significa que:

1. La persona usuaria puede **crear** una tarea con un título y una **duración estimada** y/o una
   **fecha límite**.
2. Puede **ver la lista** de sus tareas, **editar** cualquiera y **borrarla**.
3. Las tareas **persisten** entre ejecuciones de la app (sobreviven a cerrarla y reabrirla).
4. Para una tarea puede **iniciar y pausar un contador** de cuenta atrás que muestra el **tiempo
   restante** de forma legible y actualizada en tiempo real.
5. La capa de datos queda tras una **interfaz `TaskRepository`** con consultas basadas en `Flow`,
   de modo que las **features 05 (widget) y 06 (lockscreen)** puedan **reutilizarla sin cambiar la
   UI** de esta feature.
6. `Room` y `kotlinx-coroutines` quedan declarados en el **catálogo de versiones**
   (`gradle/libs.versions.toml`), con **KSP** configurado para Room.
7. La lección `tutorial/04-tareas-contador.md` explica los conceptos nuevos con referencia al
   código real, de forma progresiva sobre las lecciones 01–03.

---

## User Stories

### US-1 — Crear una tarea
**Como** persona que quiere no llegar tarde a lo que tiene que hacer,
**quiero** crear una tarea con un título y una estimación de tiempo o una fecha límite,
**para** dejar registrado qué tengo que hacer y cuándo.

**Criterios de aceptación:**
- Dado que estoy en la lista de tareas, cuando pulso el botón de añadir, entonces se abre un
  formulario de creación.
- El formulario permite introducir un **título** (obligatorio) y **al menos uno** de: **duración
  estimada** (p. ej. horas/minutos) o **fecha límite**.
- Si el título está vacío, no se puede guardar y se muestra un mensaje de validación legible.
- Al guardar, la tarea se **persiste en Room** y aparece en la lista sin necesidad de reiniciar la
  app.

### US-2 — Ver la lista de tareas
**Como** usuaria de la app,
**quiero** ver todas mis tareas en una lista,
**para** tener a la vista lo que tengo pendiente.

**Criterios de aceptación:**
- La lista se renderiza con `LazyColumn` y muestra, por cada tarea, al menos el **título** y su
  **duración estimada** y/o **fecha límite** formateada de forma legible.
- La lista se alimenta de un `Flow` del repositorio (`collectAsStateWithLifecycle`), de modo que al
  crear, editar o borrar una tarea, la lista **se actualiza automáticamente**.
- Si no hay tareas, se muestra un **estado vacío** legible (no una pantalla en blanco).
- Existe un punto de entrada a la lista de tareas desde la **Home**.

### US-3 — Editar una tarea
**Como** usuaria,
**quiero** modificar el título, la duración estimada o la fecha límite de una tarea existente,
**para** corregirla o ajustarla cuando cambian mis planes.

**Criterios de aceptación:**
- Dado que estoy en la lista, cuando selecciono una tarea para editar, entonces se abre el
  formulario **precargado** con sus datos actuales.
- Al guardar, los cambios se **persisten en Room** y se reflejan en la lista.
- Las mismas validaciones de US-1 aplican en edición (título obligatorio, al menos duración o
  fecha).

### US-4 — Borrar una tarea
**Como** usuaria,
**quiero** eliminar una tarea que ya no necesito,
**para** mantener mi lista limpia y relevante.

**Criterios de aceptación:**
- Dado que estoy en la lista o en el detalle/edición de una tarea, existe una acción clara de
  **borrar**.
- Al borrar, la tarea **desaparece de Room** y de la lista de inmediato.
- (Deseable, no bloqueante) Se ofrece una confirmación o un deshacer para evitar borrados
  accidentales.

### US-5 — Contador de cuenta atrás
**Como** persona con ceguera temporal,
**quiero** iniciar una cuenta atrás para una tarea y ver el tiempo restante,
**para** percibir cuánto tiempo me queda mientras trabajo en ella.

> **Regla del contador (decisión aprobada 2026-07-02):** el contador cuenta atrás **hacia la fecha
> límite** siempre que la tarea tenga una; en ese caso la duración estimada es solo informativa (se
> muestra, pero no gobierna la cuenta atrás). Si la tarea **solo** tiene duración estimada (sin
> fecha límite), la cuenta atrás dura esa **duración**.

**Criterios de aceptación:**
- Dada una tarea con fecha límite, al **iniciar** el contador el **tiempo restante** cuenta atrás
  **hacia esa fecha límite**, actualizándose (al menos cada segundo). Si la tarea solo tiene
  duración estimada (sin fecha), la cuenta atrás dura esa duración.
- Puedo **pausar** el contador; al pausar, el tiempo restante deja de decrecer y se conserva.
- Puedo **reanudar** desde donde lo pausé.
- El tiempo restante se **formatea** de forma legible (`mm:ss`, y `hh:mm:ss` cuando supera la hora).
- Cuando la cuenta atrás llega a **cero**, el contador se detiene y la tarea se muestra en un estado
  claro de "tiempo agotado" (sin números negativos).
- La lógica del contador vive en una **coroutine/`Flow`** (cuenta atrás con `delay`), no en la capa
  de UI; la UI solo observa el estado.

### US-6 — Persistencia local
**Como** usuaria,
**quiero** que mis tareas sigan ahí al reabrir la app,
**para** no perder lo que había apuntado.

**Criterios de aceptación:**
- Las tareas se guardan en la **base de datos Room** en disco.
- Tras cerrar por completo la app y volver a abrirla, la lista muestra exactamente las tareas
  guardadas, con sus datos.
- Todo funciona en **modo avión** (sin red).

---

## Acceptance Criteria (resumen verificable)

Criterios concretos y testeables para dar la feature por completada:

1. **CRUD sobre Room:** crear, editar y borrar tareas persiste en la base de datos y la lista
   (alimentada por `Flow`) refleja los cambios sin reinicio. *(Test JVM del DAO con base de datos
   Room in-memory: insertar/actualizar/borrar y observar el `Flow`.)*
2. **Persistencia real:** las tareas sobreviven al cierre de la app. *(Verificable por test de DAO
   in-memory + revisión de que la base de datos se crea en disco como singleton.)*
3. **Validación:** no se puede guardar una tarea sin título ni sin al menos duración o fecha
   límite; se muestra mensaje legible. *(Test de UI / test del `ViewModel` de edición.)*
4. **Contador — iniciar/pausar/reanudar:** el tiempo restante decrece al iniciar, se conserva al
   pausar y continúa al reanudar. *(Test unitario de la lógica del contador con
   `kotlinx-coroutines-test` y reloj/`TestDispatcher` virtual.)*
5. **Contador — límite en cero:** al llegar a cero el contador se detiene, sin valores negativos, y
   la tarea entra en estado "tiempo agotado". *(Test unitario de la lógica del contador.)*
6. **Formato de tiempo/fecha:** duración restante en `mm:ss` / `hh:mm:ss` y fecha límite en formato
   legible. *(Test unitario de las funciones de formateo.)*
7. **Estado vacío:** sin tareas se muestra un estado vacío legible. *(Test de UI Compose.)*
8. **Entrada desde Home + navegación:** hay un punto de entrada en la Home a la lista de tareas, y
   la creación/edición son alcanzables desde la lista. *(Test de UI / navegación.)*
9. **Repositorio compartible:** la UI y los ViewModels dependen de la **interfaz `TaskRepository`**
   (no de la implementación Room) y las consultas de lectura devuelven `Flow`. *(Verificable por
   inspección de tipos / firmas.)*
10. **Catálogo de versiones:** `Room` (con KSP) y `kotlinx-coroutines` están declarados en
    `gradle/libs.versions.toml` y no hardcodeados en `build.gradle.kts`. *(Revisión del catálogo y
    del módulo.)*
11. **Documentación:** se añade `tutorial/04-tareas-contador.md` (español) cubriendo los conceptos
    nuevos con referencia al código real.

---

## Technical Approach (alto nivel)

> Estrategia orientativa; el diseño de detalle es responsabilidad de la fase de implementación
> (`mobile-engineer`). Se listan aquí las decisiones que afectan al alcance y a la extensibilidad.

- **Modelo de dominio / entidad:** una `data class Task` como **`@Entity`** de Room con, al menos:
  `id` (clave primaria, autogenerada o `String`/UUID), `title`, `estimatedDurationMillis: Long?`
  y `deadline` (epoch millis `Long?`), más los campos de estado del contador que se decidan
  (ver abajo). Al menos uno de duración/fecha debe estar presente (validado en la capa de dominio,
  no por el esquema).
- **Persistencia (Room + KSP):**
  - `TaskDao` con operaciones `insert` / `update` / `delete` (suspend) y `observeTasks(): Flow<List<Task>>`
    / `observeTask(id): Flow<Task?>` para lecturas reactivas.
  - `NeverLateDatabase` (`@Database`) expuesta como **singleton** (una sola instancia por proceso),
    creada en la capa de datos y **threadeada desde `MainActivity`** igual que
    `DataStoreUserPreferencesRepository` y `LocalArticleRepository` hoy.
  - **KSP** como procesador de anotaciones de Room (no kapt), declarado como plugin en el catálogo.
- **Repositorio tras interfaz (clave para features 05 y 06):** interfaz **`TaskRepository`**
  (mismo patrón que `ArticleRepository` y `UserPreferencesRepository`) con implementación
  `RoomTaskRepository`. La UI, los ViewModels **y las futuras features widget/lockscreen** dependen
  **solo de la interfaz**. Las lecturas devuelven `Flow` para que widget y notificación puedan
  observar cambios.
- **Contador de tiempo (coroutines/`Flow`):** un temporizador basado en coroutines con `delay` que
  emite el tiempo restante. **Decisión de robustez recomendada:** derivar el tiempo restante del
  **reloj de pared** (guardar `runningSince`/`endsAt` en la tarea) en lugar de un contador en
  memoria que se pierda al recrearse el proceso; así el tiempo restante es correcto aunque la app
  se cierre y se reabra, y las features 05/06 pueden calcular el mismo restante sin heredar un
  temporizador vivo. El tick de UI (cada segundo) solo refresca la visualización.
- **Presentación:** `TasksViewModel` (lista + acciones CRUD + control del contador) y, si procede,
  un `TaskEditViewModel` (crear/editar, recibe un `taskId?` como argumento de navegación al estilo
  de `ArticleDetailViewModel`). Estado expuesto con `StateFlow`; Composables **stateless** con
  hoisting. ViewModels construidos vía el **`AppViewModelFactory`** existente (añadiendo el
  `TaskRepository` y, para edición, el `taskId`, con los mismos `require*()` de comprobación).
- **Navegación:** añadir rutas al `AppNavHost` existente: `tasks` (lista) y una ruta de
  creación/edición (p. ej. `taskEdit` y `taskEdit/{taskId}`), pasando **solo el `taskId`** como
  argumento (no el objeto `Task`), recargando desde el repositorio en edición — mismo patrón que
  artículos. Punto de entrada añadido en la **Home**.
- **Ubicación del código:** `ui/tasks/` (pantallas + ViewModels + lógica de UI del contador);
  modelo, DAO, base de datos y repositorio en `data/tasks/` bajo `com.neverlate`.

---

## Out of Scope

Esta feature **no** incluye:

- **Widget de pantalla de inicio** con las tareas y su tiempo restante → **feature 05**
  (`docs/prompts/05-widget-tareas.md`). Esta feature solo debe **dejar el repositorio preparado**
  para reutilizarse.
- **Tareas en la pantalla de bloqueo / notificaciones / foreground service** → **feature 06**
  (`docs/prompts/06-tareas-lockscreen.md`). Aquí no se crean notificaciones ni servicios en primer
  plano, ni se pide el permiso `POST_NOTIFICATIONS`.
- **Recordatorios / alarmas programadas** (avisar al llegar la fecha límite o al agotarse el
  tiempo) → **feature 09** (`docs/prompts/09-recordatorios.md`).
- **Sincronización con backend o base de datos remota** → **feature 11**
  (`docs/prompts/11-bbdd-remota.md`). Todo es local-only.
- **Ajustes / modo oscuro configurable** (feature 07) e **internacionalización** (feature 08).
- Categorías/etiquetas, prioridades, subtareas, tareas recurrentes, ordenación/filtrado
  configurable, búsqueda, adjuntos, y estadísticas de uso.
- **Sonido/vibración o alerta activa** al terminar la cuenta atrás (más allá del cambio de estado
  visual "tiempo agotado"); una alerta real encaja mejor con recordatorios (feature 09).

---

## Dependencies

- **Nuevas dependencias (a declarar en `gradle/libs.versions.toml`):**
  - **Room** — `androidx.room:room-runtime` y `androidx.room:room-ktx` (soporte de coroutines/`Flow`),
    con el **procesador `androidx.room:room-compiler` vía KSP**. Requiere añadir el **plugin KSP**
    (`com.google.devtools.ksp`, alineado con la versión de Kotlin `2.1.0` del catálogo) en el
    catálogo y aplicarlo en `app/build.gradle.kts`.
  - **kotlinx-coroutines** — `org.jetbrains.kotlinx:kotlinx-coroutines-android` (y `-core`) para el
    temporizador. *Nota:* `kotlinx-coroutines-test` **ya está** en el catálogo (usado en tests);
    la novedad es la dependencia de runtime.
  - Todas con `version.ref` en `[versions]`; **nada hardcodeado** en `build.gradle.kts`
    (convención del proyecto).
- **Patrón de presentación existente:** reutiliza `ViewModel` + `StateFlow` + `AppViewModelFactory`
  (inyección manual) y `collectAsStateWithLifecycle`, introducidos en las lecciones 02–03.
- **Navegación existente:** se construye sobre `ui/navigation/AppNavHost` y el patrón de rutas con
  argumentos ya usado por artículos (`articleDetail/{articleId}`).
- **Home existente:** el punto de entrada a tareas se integra en `ui/home/*`, junto al de artículos.
- **Interfaz de repositorio compartible (dependencia de diseño, no de código previo):** la interfaz
  `TaskRepository` con lecturas en `Flow` es **requisito** para que las features 05 y 06 la
  reutilicen sin tocar esta UI. Es la restricción de arquitectura más importante de la feature.
- **Sin permisos nuevos ni cambios de manifiesto** en esta feature: no se requiere `INTERNET`,
  `POST_NOTIFICATIONS` ni servicios (esos llegan en las features 05/06/11).
- **Tooling/build:** añadir el plugin KSP puede requerir una configuración de build correcta
  (orden de plugins, `compileSdk`/JDK ya fijados). Debe compilar con `./gradlew :app:assembleDebug`
  usando el wrapper.

---

## Risks

- **Repositorio no lo bastante desacoplado para compartir:** si la UI o los ViewModels acaban
  dependiendo de `RoomTaskRepository` o de la clase `@Database` en vez de la interfaz, las features
  05/06 obligarán a reescribir. *Mitigación:* interfaz `TaskRepository` con lecturas en `Flow`
  desde el primer commit; ViewModels que solo conozcan la interfaz.
- **Temporizador frágil ante el ciclo de vida de Android:** un contador en memoria se pierde al
  recrearse la Activity o el proceso, dando tiempos incorrectos. *Mitigación:* derivar el tiempo
  restante del **reloj de pared** (`endsAt`/`runningSince` persistidos), usando el tick solo para
  refrescar la UI; así el estado es correcto al reabrir y reutilizable por widget/notificación.
- **Fugas de coroutines / ticks tras salir de pantalla:** un `Flow` de cuenta atrás que siga
  emitiendo fuera de scope malgasta batería. *Mitigación:* lanzar en `viewModelScope` y observar
  con `collectAsStateWithLifecycle`; detener el tick al pausar o salir.
- **Configuración de Room + KSP:** mala alineación de versiones KSP↔Kotlin o kapt vs KSP puede
  romper el build. *Mitigación:* fijar la versión de KSP compatible con Kotlin `2.1.0` en el
  catálogo y verificar `assembleDebug` antes de continuar (Execution Policy: parar y reportar si
  falta tooling).
- **Migraciones de esquema futuras:** las features 05/06/09/11 pueden ampliar el esquema de `Task`;
  cambios sin migración provocan crashes en dispositivos con datos previos. *Mitigación:* versionar
  el `@Database` desde el inicio y documentar que futuros cambios requieren migración (o
  `fallbackToDestructiveMigration` explícito mientras la app sea pre-release).
- **Sobrecarga didáctica:** la feature introduce varios conceptos a la vez (Room + KSP + CRUD +
  coroutines/`Flow` + temporizador + formato de fechas). *Mitigación:* la lección 04 debe
  presentarlos de forma progresiva y apoyarse en las lecciones 01–03; mantener funciones pequeñas y
  nombres claros (código como material didáctico).
- **Modelado título+tiempo ambiguo:** permitir "duración y/o fecha límite" puede complicar la UI y
  el contador (¿desde qué se cuenta atrás?). *Resuelto (2026-07-02):* la cuenta atrás va **hacia la
  fecha límite** si la tarea tiene una (la duración queda informativa); si solo hay duración, la
  cuenta atrás dura esa duración. Regla fijada en los criterios de aceptación de US-5.

---

## Siguiente paso

Por favor, **revisa y aprueba** esta especificación antes de comenzar la implementación. Una vez
aprobada, el flujo continúa (según el **Mandatory Workflow** de `CLAUDE.md`): crear la rama
`feature/tasks-timer`, añadir Room/coroutines/KSP al catálogo, implementar con `mobile-engineer`,
tests con `qa-engineer` (DAO Room in-memory + lógica del contador con `kotlinx-coroutines-test` +
ViewModel/UI) y la lección `tutorial/04-tareas-contador.md` antes de commitear.
