# Especificación — Datos en una BBDD remota (backend + sincronización offline-first)

- **Fecha:** 2026-07-06
- **Feature:** Feature 11 (extra) — almacenar **todos los datos de la app** (tareas y un perfil de
  usuario mínimo) en una **base de datos remota** detrás de un **backend**, con **sincronización
  offline-first** con la copia local en Room, más **autenticación básica** de usuario (Datos en una
  BBDD remota — backend + sync)
- **Prompt origen:** `docs/prompts/11-bbdd-remota.md`
- **Rama sugerida:** `feature/remote-db-sync`
- **Lección tutorial asociada:** `tutorial/11-bbdd-remota.md` (español) — **obligatoria** antes de commitear
- **Estado:** Aprobada (2026-07-06). OQ-1 → UI de sync mínima; OQ-2 → recursos REST separados; OQ-3 → mantener migración destructiva.
- **Decisiones cerradas:**
  1. **Stack de backend: Kotlin + Ktor + Postgres**, con **`docker compose`** para desarrollo local, en
     **monorepo** (nuevo sub-proyecto **`backend/`** en este mismo repo). Se elige Kotlin para reutilizar
     el mismo lenguaje del cliente y mantener el foco en los *conceptos* (API, auth, sync) y no en un
     lenguaje nuevo.
  2. **Cuenta obligatoria** en esta feature: el usuario **debe registrarse / iniciar sesión** y todos los
     datos son **propiedad del servidor** y se sincronizan. El **modo invitado / local-only** queda
     **diferido a una feature/lección futura** (ver *Out of Scope*), lo que simplifica la superficie de
     sync (no hay que fusionar tareas locales preexistentes en una cuenta nueva).
  3. **Auth con JWT sin estado (stateless)**; ante expiración/401 la app **limpia la sesión y navega a
     login**. **Sin refresh token** en v1 — el refresh se **difiere a una feature/lección futura** (ver
     *Out of Scope*).
  4. **Agentes de implementación:** el backend se construye con el agente **`backend-engineer`** (ya
     instalado), con **`devops-security-engineer`** para `docker compose` y gestión de secretos, y
     **`mobile-engineer`** para el cliente Android.
  5. **Se mantiene el seam `TaskRepository`**: la maquinaria de sync/auth entra **detrás** de la interfaz,
     igual que la feature 10 metió `CachingArticleRepository` detrás de `ArticleRepository`.

> Escrita en español con encabezados de sección en inglés, siguiendo la convención del resto de specs
> (p. ej. `2026-07-05-articles-api.md`). Solo la lección de `tutorial/` es 100% español; el código y los
> nombres siguen siendo en inglés (convención del proyecto).

---

## Overview

Hasta ahora "Never Late Again" es una app **local-only**: las tareas viven en la base de datos Room del
dispositivo (`NeverLateDatabase`), no hay servidor ni cuenta, y no hay forma de ver las mismas tareas en
un segundo dispositivo ni de recuperarlas tras desinstalar. La feature 10 ya dio el primer paso hacia la
red (**lee** artículos de un endpoint HTTP estático y los cachea en Room), pero es de solo lectura y sin
autenticación.

La feature 11 introduce el **primer backend real** del proyecto: un servidor pequeño con su propia base
de datos que es **dueño de los datos del usuario**. La app Android deja de ser la fuente de verdad de las
tareas y pasa a ser un **cliente offline-first** de ese backend. En concreto:

- El usuario **se registra / inicia sesión** con email + contraseña y recibe un **token de auth**; el
  token se guarda **de forma segura** en el dispositivo y se adjunta a cada llamada a la API.
- Las tareas (y un perfil de usuario mínimo) se **almacenan en el servidor**. El dispositivo mantiene una
  **caché en Room** de la que la UI siempre lee, de modo que la app sigue siendo **totalmente usable sin
  conexión**.
- Los cambios locales (crear / editar / borrar una tarea) se registran en una **cola de salida (outbox)**
  y se **empujan (push)** al servidor cuando hay conectividad; los cambios del servidor se **traen (pull)**
  y se fusionan en la caché local. Los conflictos se resuelven con una estrategia definida y justificada.

Es la feature más significativa arquitectónicamente del tutorial hasta ahora: es donde el proyecto gana un
**segundo sub-proyecto** (el backend), adquiere un **contrato de API** como fuente única de verdad entre
cliente y servidor, y enseña la mecánica real de **auth**, **almacenamiento seguro de credenciales** y
**sincronización offline-first**. Como "la app ahora habla con un servidor que es dueño de los datos", hay
que **reintroducir en `CLAUDE.md` la sección "API Contract"** (que se eliminó al declararse la app
local-only; ver *Documentation & CLAUDE.md changes*).

El principio rector es **offline-first**: la red es una mejora, nunca un requisito previo. Cada pantalla
debe funcionar sin conectividad, leyendo y escribiendo la copia local de Room; la sincronización ocurre en
segundo plano y reconcilia.

### Conceptos nuevos que enseña (para `tutorial/11-bbdd-remota.md`)

Partiendo de lecciones previas (03: el seam de repositorio; 09: Room + coroutines + WorkManager +
receivers; 10: networking con Retrofit/OkHttp, DTO vs. modelo de dominio, Room como única fuente de
verdad):

- **Diseño de API como contrato / fuente única de verdad.** Cómo un contrato documentado
  (endpoints, esquemas, auth, formas de error) desacopla cliente y servidor y deja evolucionar a ambos de
  forma independiente; y cómo consumirlo desde el cliente con el stack Retrofit ya introducido en la
  feature 10.
- **Autenticación con tokens.** Registro / login, qué es un token, cómo se adjunta a las peticiones
  (`Authorization: Bearer …`), y por qué es el **servidor** —no el cliente— quien verifica la identidad y
  es dueño de la autorización.
- **Almacenamiento seguro de credenciales en el cliente.** Por qué un token de auth **no** puede vivir en
  un `DataStore` / `SharedPreferences` en claro, y cómo guardarlo con `EncryptedSharedPreferences` /
  Jetpack Security respaldado por el Android Keystore.
- **Sync offline-first.** Room como caché local + una **cola de cambios (outbox)** + **push/pull** + una
  estrategia de **resolución de conflictos** (last-write-wins con timestamps), más los detalles prácticos
  que suelen dar problemas: **ids locales vs. ids de servidor**, **tombstones** para borrados,
  idempotencia y reintentos de push fallidos.
- **Postura de seguridad: la lógica sensible vive en el backend.** Las comprobaciones de propiedad, la
  validación y la autoridad sobre los datos se mueven al servidor; el cliente es no confiable. Es el
  momento en que la regla del proyecto "la seguridad es un requisito de MVP" deja de ser local-only.

---

## Goals

Éxito significa que:

1. El usuario puede **crear una cuenta e iniciar sesión**; la sesión (token) **persiste entre reinicios**
   de la app y se guarda **cifrada** en el dispositivo.
2. Las tareas son **propiedad del backend**: una tarea creada en el dispositivo A acaba apareciendo en el
   dispositivo B para la **misma cuenta** (habiendo conectividad en ambos).
3. La app es **totalmente usable sin conexión**: sin conectividad, el usuario puede listar, crear, editar,
   completar y borrar tareas contra la caché local; los cambios se **encolan** y se empujan más tarde.
4. Cuando vuelve la conectividad, **los cambios locales encolados se empujan** y **los cambios remotos se
   traen**, y ambos se **fusionan** en una vista consistente con una regla de conflictos definida.
5. Los **borrados se propagan** correctamente entre dispositivos (una tarea borrada en un dispositivo no
   "resucita" silenciosamente desde la copia obsoleta de otro dispositivo).
6. El **backend es un sub-proyecto real y ejecutable** con un **`docker compose`** para desarrollo local, y
   un **documento de contrato de API** que cliente y servidor tratan como fuente de verdad.
7. Se **preserva el seam `TaskRepository`** del cliente: la UI/ViewModels siguen dependiendo de la interfaz
   y la sincronización entra por detrás (igual que la feature 10 metió la caché detrás de
   `ArticleRepository`).
8. La lección `tutorial/11-bbdd-remota.md` explica el diseño de API, la auth con token + almacenamiento
   seguro, y el modelo de sync offline-first con referencia al código real.

---

## User Stories

### US-1 — Crear una cuenta e iniciar sesión

**Como** persona que gestiona sus tareas,
**quiero** registrarme e iniciar sesión con email y contraseña,
**para** que mis tareas queden ligadas a mi cuenta y puedan seguirme entre dispositivos.

**Criterios de aceptación:**
- Dado que la app no tiene sesión, cuando la abro, entonces veo una **pantalla de auth** (login, con acceso
  a registro) **antes** de poder llegar a mis tareas. *(La cuenta es obligatoria en esta feature; el modo
  invitado se difiere — ver Out of Scope.)*
- Dadas credenciales nuevas válidas, cuando me registro, entonces el backend crea mi cuenta y devuelve un
  **token**; aterrizo en la lista de tareas.
- Dadas credenciales existentes válidas, cuando inicio sesión, entonces recibo un token y veo mis tareas.
- Dadas credenciales inválidas o un email ya usado, cuando envío, entonces veo un **error legible** (no un
  crash) y no se guarda token.
- La contraseña viaja por **HTTPS** y se guarda **hasheada** en el servidor (nunca en claro, nunca logueada).

### US-2 — Mantener la sesión iniciada

**Como** usuario con sesión iniciada,
**quiero** que la app me recuerde,
**para** no reintroducir mi contraseña en cada arranque.

**Criterios de aceptación:**
- Dado que inicié sesión correctamente, cuando cierro y reabro la app, entonces voy directo a mis tareas
  **sin** reintroducir credenciales.
- El token se guarda con almacenamiento **cifrado** (respaldado por Keystore), **no** en el DataStore en
  claro que guarda las preferencias de tema/recordatorios.
- Dada una acción de **cerrar sesión**, cuando la uso, entonces se limpian el token y la caché local de la
  cuenta y vuelvo a la pantalla de auth.
- Dado que el servidor rechaza mi token como expirado/inválido (**401**), cuando cualquier llamada de sync
  devuelve 401, entonces la app limpia la sesión y me lleva a login. *(v1 usa JWT sin refresh: el usuario
  vuelve a iniciar sesión; el refresh se difiere — ver Out of Scope.)*

### US-3 — Usar la app totalmente sin conexión

**Como** usuario con conectividad poco fiable,
**quiero** seguir gestionando tareas sin red,
**para** que la app nunca quede bloqueada por el servidor.

**Criterios de aceptación:**
- Dado que no hay conectividad, cuando abro la app **ya con sesión iniciada**, entonces veo mis **tareas
  cacheadas** y puedo listar/crear/editar/completar/borrar.
- Cada mutación local se escribe en **Room de inmediato** y se registra en la **outbox** como cambio
  pendiente; la UI lo refleja al momento (optimista).
- Ninguna acción offline muestra un error bloqueante solo porque la red no esté disponible.

### US-4 — Sincronizar cuando vuelve la conectividad

**Como** usuario,
**quiero** que mis cambios offline lleguen al servidor y los del servidor lleguen a mí,
**para** que todos mis dispositivos converjan en la misma lista de tareas.

**Criterios de aceptación:**
- Dados cambios locales encolados, cuando vuelve la conectividad (o hago pull-to-refresh, o corre un sync
  periódico), entonces la outbox se **empuja** al servidor y los ítems **confirmados (ack)** se eliminan de
  la cola.
- Dado que el servidor tiene datos más nuevos, cuando corre un sync, entonces los cambios remotos se
  **traen** y se fusionan en la caché local, y la lista se re-renderiza.
- El sync es **idempotente y tolerante a reintentos**: un push que falló parcialmente (p. ej. se cayó la
  red) puede reintentarse sin duplicar tareas.
- El usuario ve un **estado de sync** al menos mínimo (p. ej. un indicador "sincronizando…" / "al día" /
  "sin conexión", o el spinner de pull-to-refresh). *(La profundidad de la UI de estado es OQ-1.)*

### US-5 — Los conflictos se resuelven de forma predecible

**Como** usuario que edita la misma tarea en dos dispositivos,
**quiero** que los conflictos se resuelvan de una forma definida,
**para** no perder datos inesperadamente ni ver corrupción.

**Criterios de aceptación:**
- Dado que la misma tarea se editó en dos dispositivos estando offline, cuando ambos sincronizan, entonces
  se aplica la **regla de resolución** (last-write-wins por `updatedAt`, ver *Sync Model*) de forma
  determinista y ambos dispositivos convergen en el mismo resultado.
- Dado que una tarea fue **borrada** en un dispositivo y **editada** en otro, entonces se aplica la regla
  acordada (**gana el borrado**, vía tombstone) y la tarea no resucita.
- La regla elegida está **documentada** en la lección para que su trade-off (posible sobrescritura
  silenciosa de la edición perdedora) sea explícito.

### US-6 — Los datos son propiedad del backend y están protegidos

**Como** dueño del proyecto,
**quiero** que la autorización y la validación se apliquen en el servidor,
**para** que un usuario solo pueda ver y cambiar **sus propias** tareas.

**Criterios de aceptación:**
- Cada llamada a la API de tareas está **autenticada**; una llamada sin auth devuelve **401**.
- Un usuario nunca puede leer ni modificar las tareas de otro (**alcance por id de usuario en el
  servidor**); intentarlo devuelve **404/403**, verificado por un test de backend.
- La validación de campos de la tarea existe **en el servidor** (no solo en el formulario del cliente), de
  modo que una petición malformada se rechaza con un error legible.

### US-7 — Se preserva el seam del cliente (restricción de arquitectura)

**Como** desarrollador de este proyecto-tutorial,
**quiero** que la capa de sync entre por detrás de la interfaz `TaskRepository` existente,
**para** que la UI/ViewModels de tareas apenas cambien y la lección se apoye en el patrón del seam.

**Criterios de aceptación:**
- La UI/ViewModels siguen dependiendo de **`TaskRepository`**, no de ningún tipo de red/sync directamente.
- Room sigue siendo la **única fuente de verdad local** de la que lee la UI (como en la feature 10 para
  artículos).
- La capacidad nueva (disparar sync, estado de sync, estado de auth) se añade de forma **aditiva**; los
  métodos existentes `observeTasks()` / `saveTask()` / `deleteTask()` / timer conservan su rol de
  lectura/escritura.

---

## Acceptance Criteria (resumen verificable)

Criterios concretos y testeables para dar la feature por completada:

1. **Round-trip de auth:** registro y login devuelven un token; credenciales incorrectas devuelven un error
   legible y no guardan token. *(Test de integración de backend + test de ViewModel del cliente con API
   fake/mock.)*
2. **Persistencia de sesión + almacenamiento seguro:** tras el login, un reinicio aterriza en la lista de
   tareas sin re-auth; el token se guarda con almacenamiento cifrado respaldado por Keystore, no en
   DataStore en claro. *(Inspección + test del cliente.)*
3. **CRUD offline:** con la red simulada como no disponible, crear/editar/completar/borrar funcionan contra
   Room y encolan una fila de outbox. *(Test de repositorio con Room en memoria + red fake.)*
4. **Push:** dadas filas de outbox, un sync las empuja al servidor y limpia las filas confirmadas; una tarea
   creada offline recibe un **id de servidor** y su fila local se reconcilia. *(Test de repositorio/sync con
   `MockWebServer` + Room en memoria; test de backend de que la fila se persiste.)*
5. **Pull + merge:** un sync trae cambios del servidor a la caché y la lista los refleja sin salir de la
   pantalla. *(Test de sync.)*
6. **Propagación de borrados (tombstones):** una tarea borrada localmente se empuja como delete y no
   reaparece en el siguiente pull; una tarea borrada en el servidor se elimina localmente en el pull. *(Test
   de sync en ambos sentidos.)*
7. **Resolución de conflictos:** dos ediciones en conflicto convergen de forma determinista por
   last-write-wins sobre `updatedAt`; delete-vs-edit resuelve a **gana el borrado**. *(Test unitario de
   lógica pura de la función de merge — esta lógica debe extraerse para ser testeable sin Android, igual que
   `ReminderPlanning.kt`.)*
8. **Reintento idempotente:** reproducir un push ya aplicado no crea duplicados. *(Test de sync: push,
   descartar el ack, push otra vez → el servidor tiene una tarea.)*
9. **Alcance de autorización:** el usuario A no puede leer/modificar las tareas del usuario B (401 sin auth,
   403/404 cruzado). *(Test de backend.)*
10. **Manejo de 401:** un token expirado/inválido lleva al cliente de vuelta a login y limpia la sesión.
    *(Test del cliente con API fake que devuelve 401.)*
11. **El backend corre en local:** `docker compose up` arranca el backend + su base de datos; una secuencia
    de humo documentada (registro → login → crear tarea → listar) tiene éxito. *(Comprobación manual /
    guiada por README.)*
12. **Existe el contrato de API:** un documento de contrato (endpoints, esquemas, auth, errores) está
    commiteado y referenciado por cliente y servidor; se reintroduce la sección "API Contract" de CLAUDE.md.
13. **Seam intacto:** la UI/ViewModels de tareas dependen solo de `TaskRepository`; el diff de las pantallas
    de tareas se limita a la puerta de auth + un indicador de sync/offline. *(Inspección.)*
14. **Documentación:** se añade `tutorial/11-bbdd-remota.md` (español) cubriendo diseño de API, auth con
    token + almacenamiento seguro, y sync offline-first (outbox, push/pull, conflictos, ids, tombstones).

---

## Proposed Architecture

Dos sub-proyectos, un contrato entre ellos.

```
Cliente Android (app/ existente)                  Backend (nuevo backend/)
┌───────────────────────────────┐                 ┌──────────────────────────────┐
│ ui/ (pantallas Compose, VMs)  │                 │ API HTTP (auth + tareas)     │
│   dependen de ↓ interfaces    │   HTTPS +       │   valida, autoriza,          │
│ data/                         │   token Bearer  │   es dueño de los datos      │
│   TaskRepository (seam)  ─────┼───────────────► │        │                     │
│   AuthRepository (nuevo)      │   JSON (DTOs    │        ▼                     │
│   SyncEngine (nuevo)          │   por contrato) │   Postgres (BBDD remota)     │
│   Room: tasks + outbox +      │                 │   users, tasks               │
│         metadatos de sync     │                 └──────────────────────────────┘
└───────────────────────────────┘
```

**Lado cliente (en `app/`, sobre todo `data/` + nuevos `data/sync` y `data/auth`):**

- **`AuthRepository`** — registro/login/logout, expone el estado de auth (`StateFlow<AuthState>`), guarda el
  token con almacenamiento cifrado. `MainActivity` condiciona el grafo de navegación al estado de auth.
- **`TaskRepository` (seam sin cambios)** — la UI sigue leyendo de Room. Una nueva **implementación
  consciente del sync** (o un decorador, en la línea del `ReminderSchedulingRepository` de la feature 09 y
  el `CachingArticleRepository` de la feature 10) escribe cada mutación en Room **y** en la outbox.
- **`SyncEngine`** — coordina push/pull. Se dispara por: apertura de la app, pull-to-refresh, y un job de
  **WorkManager** periódico/con restricción de conectividad (WorkManager ya está en el proyecto desde la
  feature 09). La lógica pura de merge/conflictos se extrae a una función sin Android y testeable
  (`domain/sync/…`), replicando el patrón de `domain/tasks/ReminderPlanning.kt`.
- **`TasksApi` + `AuthApi` (Retrofit/OkHttp)** — reutilizan el stack Retrofit + converter de
  `kotlinx.serialization` de la feature 10. Un **interceptor de auth** de OkHttp adjunta el token Bearer; una
  comprobación de respuesta dispara el logout ante un 401.

**Lado backend (nuevo `backend/`):**

- Endpoints REST para auth y tareas, acotando cada consulta de tareas al usuario autenticado, validando la
  entrada y persistiendo en una base de datos real (Postgres).
- Un `docker compose` que levanta backend + Postgres para desarrollo local.

### Ubicación del backend (decisión cerrada: monorepo)

**El backend es un nuevo sub-proyecto `backend/` dentro de este mismo repo (monorepo).** Al ser un
proyecto-tutorial, el objetivo es mostrar el contrato de API como fuente de verdad compartida entre cliente
y servidor: tenerlos en un mismo repo hace que contrato, servidor y cliente evolucionen en **una rama / un
PR / una revisión**, la lección puede apuntar a ficheros reales de ambos lados, y el `docker compose` vive
junto al código que ejecuta.

- *Impacto en la estructura:* el repo deja de ser un único proyecto Gradle en la raíz. Se mantiene la app
  Android donde está y se añade un directorio hermano `backend/` con su propia herramienta de build (mínima
  fricción con el Gradle existente y con las rutas documentadas en CLAUDE.md).
- *Impacto en CLAUDE.md:* añadir `backend/` a la sección **Structure**, añadir instrucciones de ejecución
  del backend a **Development**, y **reintroducir la sección "API Contract"** (ver abajo). Es un **nuevo
  sub-proyecto**, que la política de Documentation Update exige registrar.

---

## Backend Stack (decisión cerrada)

**Kotlin + Ktor + Postgres**, tan simple como sea posible sin dejar de enseñar los conceptos reales (una API
HTTP real, una BBDD relacional real, auth con token real, autorización real en el servidor).

- *Por qué Kotlin/Ktor:* el usuario ya está aprendiendo **Kotlin** para el cliente. Un backend en Kotlin
  permite **reutilizar el lenguaje**, comparte la mentalidad de DTOs de `kotlinx.serialization`, y mantiene
  la carga cognitiva en los *conceptos* (API/auth/sync) y no en un lenguaje nuevo. Ktor es ligero y legible
  — buen material didáctico. Postgres es el estándar relacional de facto y corre trivialmente en
  `docker compose`.
- *Auth:* email + contraseña (hasheada con **bcrypt/argon2**), emitiendo un **JWT sin estado**. El plugin
  `Authentication` + `jwt` de Ktor hace el flujo Bearer explícito y enseñable.
- *Persistencia:* Exposed o JDBC plano — mínimo; dos tablas (`users`, `tasks`).

> El backend debe traer un **`docker compose`** (backend + DB), un **README** breve con instrucciones de
> ejecución/humo, y almacenamiento de contraseñas **hasheado**. Los secretos (clave de firma JWT,
> credenciales de DB) vienen de **variables de entorno**, nunca commiteados — encaje natural para el agente
> `devops-security-engineer` durante la implementación.

---

## API Contract (sketch)

El contrato completo se redacta durante la implementación y se commitea (recomendado: `docs/api/contract.md`
o un fichero OpenAPI bajo `backend/`), y la sección **API Contract** de CLAUDE.md apunta a él. Boceto:

**Auth**
- `POST /auth/register` — body `{ email, password }` → `201 { token, user: { id, email } }`; `409` si el
  email ya existe; `400` en error de validación.
- `POST /auth/login` — body `{ email, password }` → `200 { token, user }`; `401` con credenciales incorrectas.
- *(sin `/auth/refresh` en v1 — el refresh se difiere; ver Out of Scope.)*

**Tasks** (todas requieren `Authorization: Bearer <token>`; todas acotadas al usuario autenticado)
- `GET /tasks?since=<timestamp>` — devuelve las tareas cambiadas desde un cursor (para el **pull**),
  **incluyendo tombstones** de los borrados: `200 { tasks: [ TaskDto… ], serverTime }`.
- `POST /tasks` — crear; el body lleva un **id/clientRef generado por el cliente** para idempotencia;
  devuelve la tarea con su **id de servidor** y su `updatedAt`.
- `PATCH /tasks/{id}` — actualizar; last-write-wins según `updatedAt`.
- `DELETE /tasks/{id}` — borrado lógico (tombstone) para que otros dispositivos puedan traérselo en el pull.
- *(opcional)* `POST /sync` — un único endpoint que batchea push+pull (OQ-2).

**`TaskDto` (forma de red)** — deliberadamente distinta de la entidad Room `Task` (continuando la enseñanza
DTO-≠-entidad de la feature 10): ids de servidor, `updatedAt`, flag `deleted`, `clientRef`, y timestamps en
epoch-millis; el cliente mapea `TaskDto ↔ Task` (+ metadatos de sync) en vez de persistir el DTO
directamente.

**Errores** — una forma JSON de error consistente (`{ error: { code, message } }`) y códigos de estado
estándar (400/401/403/404/409/5xx), documentados una vez y respetados por ambos lados.

---

## Sync Model (offline-first — el corazón de la feature)

**Única fuente de verdad local.** La UI siempre lee de la **caché Room**. Nada en la UI bloquea por red. Es
la misma invariante que la feature 10 estableció para artículos, ahora extendida a las escrituras.

**Outbox / cola de cambios.** Una nueva tabla Room (p. ej. `task_outbox`) registra cada **mutación local
pendiente** (`create` / `update` / `delete`) con lo necesario para reproducirla: el id local de la tarea, la
operación, el payload (o una referencia a la fila actual), un `clientRef` y un contador de reintentos. Cada
escritura va a la tabla de tareas **y** encola una fila de outbox en la **misma transacción**, para que un
crash no pueda perder la intención.

**Metadatos de sync en las tareas.** La fila de tarea (o una tabla paralela) gana columnas de sync:
- `serverId` (nullable) — el id asignado por el backend; null hasta el primer push con éxito.
- `updatedAt` (epoch millis) — última modificación local, usada para resolver conflictos.
- `syncState` — p. ej. `synced` / `pendingCreate` / `pendingUpdate` / `pendingDelete`.
- `deleted` (flag tombstone) — una tarea borrada localmente **no** se borra en duro de Room hasta que su
  delete lo confirma el servidor, para poder empujar el borrado de forma fiable.

**Ids locales vs. de servidor.** El cliente sigue usando su **id local autoincremental `Long`** como
identidad estable dentro de la app (el widget, los recordatorios keyed por id y los argumentos de navegación
dependen de él — ver features 05/06/09 en CLAUDE.md, y `saveTask(): Long`). El servidor asigna su **propio**
id, guardado como `serverId`. Un create se empuja con un **`clientRef`** (un token estable generado por el
cliente) para que: (a) la respuesta pueda casarse con la fila local correcta y rellenar `serverId`, y (b) un
create reintentado sea **idempotente** (el servidor deduplica por `clientRef`). Esto evita el clásico
doble-insert cuando se pierde un ack.

**Push.** Por cada fila de outbox, de la más antigua a la más nueva: enviar el `POST`/`PATCH`/`DELETE`
correspondiente. Con éxito, actualizar la fila local (`serverId`, `syncState = synced`, limpiar el tombstone
si era un delete → entonces borrar la fila en duro) y quitar la entrada de outbox. Ante fallo transitorio,
dejarla encolada y reintentar más tarde (backoff acotado).

**Pull.** Llamar `GET /tasks?since=<lastSyncCursor>`; aplicar cada `TaskDto` devuelto a la caché por
`serverId`: upsert de las tareas cambiadas, y **eliminar** las tareas cuyo DTO sea un tombstone. Avanzar el
cursor al `serverTime` reportado.

**Resolución de conflictos — Last-Write-Wins por `updatedAt` (recomendada y elegida).**
- *Regla:* cuando la misma tarea cambió tanto localmente (pendiente en outbox) como remotamente (llegó en un
  pull), gana la versión con `updatedAt` **más reciente**; la perdedora se sobrescribe.
- *Por qué LWW:* es **simple, determinista y convergente** — justo la complejidad adecuada para un
  proyecto-tutorial, fácil de razonar y de testear como función pura. Un merge a nivel de campo o CRDTs son
  mucho más de lo que esta feature debe enseñar.
- *Trade-off (a documentar):* LWW puede **descartar silenciosamente** la edición perdedora. Aceptable para
  una app de tareas personal; se explicita en la lección.
- *Delete vs. edit:* **gana el borrado** (el tombstone vence a una edición concurrente) para evitar tareas
  zombie; también documentado.
- *Elección de reloj:* preferir el timestamp del **servidor** como autoridad en el lado servidor; el cliente
  usa su propio `updatedAt` para los cambios locales pendientes. (El desfase de relojes es un caveat conocido
  — ver Risks.)

**Puntos de disparo.** El sync corre en: foreground/apertura de la app, **pull-to-refresh** manual, tras
cualquier mutación local (push inmediato best-effort si hay red), y un job de **WorkManager** con restricción
de conectividad (reutilizando la dependencia de WorkManager introducida en la feature 09) para que los
cambios encolados se vacíen aunque el usuario cerrara la app estando offline.

**Merge como lógica pura.** La reconciliación (dado el estado local + el batch remoto + la outbox → las
operaciones resultantes sobre la caché) se escribe como **función pura sin Android** en `domain/sync/…` para
que sea testeable en JVM — siguiendo directamente el patrón que el proyecto ya usa en `ReminderPlanning.kt`.

---

## Auth Model

- **Credenciales:** email + contraseña. Contraseña hasheada en el servidor con **bcrypt/argon2**; nunca
  guardada ni logueada en claro; siempre por **HTTPS**.
- **Token:** al registrar/loguear el servidor devuelve un **JWT sin estado** (Bearer). El cliente lo adjunta
  vía un **interceptor de OkHttp** en cada llamada de tareas.
- **Almacenamiento seguro en el cliente:** el token se guarda con **`EncryptedSharedPreferences` / Jetpack
  Security** (respaldado por Keystore), **no** en el DataStore en claro `user_prefs` que guarda las
  preferencias de tema/recordatorios. (Esta distinción — preferencias no sensibles en DataStore vs. secretos
  en almacenamiento cifrado — es un punto didáctico.)
- **Ciclo de vida de la sesión:** el token persiste entre reinicios (US-2). Un **401** de cualquier llamada
  limpia la sesión y navega a login. En v1 **no hay refresh token**: el usuario vuelve a iniciar sesión (el
  refresh se difiere; ver Out of Scope). El logout limpia el token y la caché local de la cuenta.
- **Autorización en el servidor:** cada consulta de tareas se acota al id del usuario autenticado; el cliente
  es no confiable. Aquí es donde "la lógica sensible vive en el backend" se vuelve concreto.

---

## Data Model changes

**Cliente (Room `NeverLateDatabase`).** La DB está hoy en **versión 2** (`Task`, `ArticleEntity`,
`fallbackToDestructiveMigration(dropAllTables = true)`, `exportSchema = false`). La feature 11 la sube a
**versión 3** y añade/cambia:
- **`Task`** gana metadatos de sync: `serverId: Long?`, `updatedAt: Long`, `syncState`, `deleted`
  (tombstone). *(Room no tiene problema con columnas nullable; `updatedAt` necesita un default/backfill.)*
- **Nueva entidad + DAO `task_outbox`** — la cola de cambios pendientes descrita en *Sync Model*.
- *(Quizá)* una pequeña tabla `user_profile`/sesión si se muestra algún perfil más allá del token.
- **Política de migración:** por la política existente `fallbackToDestructiveMigration`, la subida de versión
  **borra los datos existentes en el dispositivo** — el mismo atajo pre-release aceptado para la subida 1→2
  de la feature 10. Aquí es doblemente aceptable porque las tareas pasan a ser **propiedad del servidor**:
  tras el login la caché se repuebla desde el backend. Documentarlo en la lección. *(Recomendación: mantener
  la migración destructiva, siguiendo el precedente del proyecto — ver OQ-3.)*

**Backend (Postgres).**
- **`users`**: `id`, `email` (único), `password_hash`, `created_at`.
- **`tasks`**: `id`, `user_id` (FK, indexado), los campos de la tarea (`title`, `estimated_duration_ms`,
  `deadline`, campos de timer según aplique), `updated_at`, `deleted` (tombstone), `client_ref` (para creates
  idempotentes), `created_at`. El servidor es la autoridad sobre `updated_at` e `id`.

> Nota: los campos de **timer** en el dispositivo (`timerEndsAt`, `remainingMillis`) son estado de cuenta
> atrás local, atado al reloj de pared. Si se sincronizan o no es una decisión de diseño: sincronizar una
> cuenta atrás en marcha entre dispositivos es sutil. **Recomendación (adoptada):** **no** sincronizar el
> estado de timer en v1 — sincronizar los campos durables (`title`, `estimatedDurationMillis`, `deadline`) y
> mantener el estado de timer local (ver Out of Scope).

---

## Teaching Concepts (mapeo a la lección)

`tutorial/11-bbdd-remota.md` (español, numerada tras la lección 10) debe progresar así:

1. **Qué es un backend y un contrato de API** — por qué el contrato es la fuente única de verdad; leer el
   documento de contrato commiteado.
2. **Auth con tokens** — registro/login, cabecera Bearer, por qué el servidor verifica la identidad; añadir
   el interceptor de auth de OkHttp (apoyándose en el setup de OkHttp de la feature 10).
3. **Almacenamiento seguro de credenciales** — almacenamiento cifrado respaldado por Keystore vs. DataStore
   en claro, y *por qué*.
4. **Sync offline-first** — Room como fuente de verdad (repaso de la lección 10) extendido a las escrituras;
   la cola outbox; push/pull; ids locales vs. de servidor + `clientRef`; tombstones; resolución LWW y sus
   trade-offs; la función de merge pura y cómo se testea.
5. **Postura de seguridad** — propiedad/validación en el servidor; el cliente es no confiable.

---

## Out of Scope

Esta feature **no** incluye:

- **Despliegue real en la nube / hosting / dominio propio / certificados TLS de producción.** El backend
  corre **en local vía `docker compose`** para el tutorial; desplegarlo queda fuera (posible feature
  posterior).
- **Modo invitado / local-only:** en esta feature la **cuenta es obligatoria** y todos los datos son
  propiedad del servidor. Permitir usar la app sin cuenta y fusionar tareas locales preexistentes al
  registrarse se **difiere a una feature/lección futura** (superficie de sync bastante mayor).
- **Refresh token / renovación silenciosa de sesión:** v1 usa **JWT sin refresh**; ante 401 el usuario
  vuelve a iniciar sesión. El refresh se **difiere a una feature/lección futura**.
- **Login social / OAuth** (Google/Apple), **reset de contraseña por email**, **verificación de email**,
  **2FA**. La auth es deliberadamente mínima (email + contraseña + token).
- **Sync en tiempo real** (WebSockets/push para actualización instantánea entre dispositivos). El sync es por
  sondeo/disparo (apertura, pull-to-refresh, WorkManager).
- **Merge a nivel de campo, CRDTs, o UI de resolución de conflictos para el usuario.** Los conflictos se
  resuelven automáticamente por LWW; sin diálogo de "¿qué versión quieres?".
- **Sincronizar artículos.** Los artículos siguen siendo la caché remota de solo lectura de la feature 10;
  aquí solo **tareas** (y perfil mínimo) son datos sincronizados con escritura. *(Reutilizar el mismo backend
  para artículos más adelante es un posible seguimiento.)*
- **Compartición multi-usuario / colaboración** en la misma tarea; cada cuenta es dueña de sus tareas
  privadas.
- **Sincronizar el estado de la cuenta atrás (timer) en marcha** entre dispositivos (recomendado fuera de v1;
  ver *Data Model changes*).
- **Creación de cuenta offline.** Registrarse/loguearse requiere conectividad (no se puede emitir una sesión
  offline); solo el uso de *tareas* es offline una vez con sesión iniciada.
- **UI de admin del backend, analítica, endurecimiento de rate limiting** más allá de valores por defecto
  razonables.

---

## Dependencies

**Ya en el proyecto (se reutilizan):** Room 2.7.x (runtime/ktx/compiler vía KSP), Retrofit + OkHttp +
`retrofit2-kotlinx-serialization-converter` (feature 10), `kotlinx-serialization-json`,
`kotlinx-coroutines`, Navigation Compose, y **WorkManager** (feature 09) para el sync en segundo plano. El
seam `TaskRepository` (feature 04) y el patrón `getInstance` de Room se reutilizan.

**Nuevas dependencias de cliente a añadir al catálogo** (`gradle/libs.versions.toml`) — las versiones
concretas se eligen en implementación, no se inventan aquí:
- **Jetpack Security / `androidx.security:security-crypto`** (`EncryptedSharedPreferences`) — o la lib de
  almacenamiento cifrado recomendada vigente — para el guardado seguro del token.
- *(tests)* `com.squareup.okhttp3:mockwebserver` (ya usada en la feature 10) — API fake para los tests de
  repositorio de auth/sync.

**Manifest / permisos:**
- **`INTERNET`** ya está declarado (feature 10). Puede añadirse **`ACCESS_NETWORK_STATE`** si el sync usa
  comprobaciones de conectividad / restricciones de red de WorkManager. Sin nuevas solicitudes de permiso en
  runtime.

**Nuevo sub-proyecto backend** (`backend/`) — introducido por esta feature, no preexistente:
- Framework web + JSON (**Ktor** + `kotlinx.serialization`), una lib de **JWT/auth**, driver de **Postgres**
  + persistencia ligera (Exposed o JDBC), una lib de hashing de contraseñas (**bcrypt/argon2**), y un
  **`docker compose`** (backend + Postgres). Libs/versiones exactas se eligen en implementación.

**Dependencias de diseño (deben ser ciertas antes de implementar):**
- El **contrato de API** debe redactarse primero (es la fuente de verdad contra la que construyen ambos
  lados).
- Debe reintroducirse la sección **API Contract** de CLAUDE.md, y registrarse el **nuevo sub-proyecto** en
  Structure/Development (política de Documentation Update).
- Debe respetarse el seam `TaskRepository`: sync/auth entran **por detrás** de la interfaz.

---

## Documentation & CLAUDE.md changes (obligatorios)

Según el prompt y la política de Documentation Update, esta feature debe:
- **Reintroducir la sección "API Contract" en CLAUDE.md** (eliminada al declararse la app local-only) y
  tratar el contrato de API commiteado como fuente única de verdad para cliente + servidor.
- **Añadir `backend/` a la sección Structure** e **instrucciones de ejecución del backend** (`docker compose
  up`, secuencia de humo) a Development — es un **nuevo sub-proyecto**.
- **Anotar las nuevas dependencias** en el catálogo de versiones y el **nuevo permiso**
  (`ACCESS_NETWORK_STATE` si se añade).
- **Entregar `tutorial/11-bbdd-remota.md`** (español) — la feature no está hecha sin ella.

---

## Risks

- **Sobrecarga de alcance / didáctica.** Es con diferencia la feature más grande: backend + DB + auth +
  almacenamiento seguro + sync bidireccional completo + conflictos. *Mitigación:* mantener el backend mínimo
  (dos tablas, un método de auth), elegir LWW (regla convergente más simple), extraer la lógica pura de merge,
  y que la lección se apoye estrictamente en los patrones de seam + Room-como-fuente-de-verdad de las lecciones
  04/10.
- **Bugs de corrección del sync** (updates perdidos, creates duplicados en pushes reintentados, tareas zombie
  tras borrado). *Mitigación:* creates idempotentes basados en `clientRef`, tombstones para los borrados, y
  una función de merge pura y testeada con tests explícitos para cada sentido y el caso delete-vs-edit.
- **Desfase de relojes para LWW.** Dispositivos/servidores con relojes mal ajustados pueden elegir al "ganador
  equivocado". *Mitigación:* preferir el tiempo del servidor como autoridad donde se pueda; documentar el
  caveat; las apuestas de una app de tareas personal son bajas.
- **La migración destructiva de Room borra las tareas del dispositivo** en la subida de versión 2→3.
  *Mitigación:* política pre-release aceptada (como en la feature 10); la caché se repuebla desde el backend
  tras el login; documentado en la lección — o escribir una `Migration` real (OQ-3).
- **Manejo de secretos / regresiones de seguridad.** Tokens en el almacenamiento equivocado, secretos
  commiteados, contraseñas logueadas. *Mitigación:* token en almacenamiento cifrado respaldado por Keystore,
  secretos por variables de entorno, contraseñas hasheadas, y una pasada del agente
  `devops-security-engineer`; la seguridad es un requisito de MVP, no un objetivo secundario.
- **Fricción por reestructurar el repo.** Añadir `backend/` junto al proyecto Gradle puede confundir a las
  herramientas/rutas. *Mitigación:* mantener la app Android donde está y añadir un `backend/` hermano;
  actualizar las rutas de CLAUDE.md en el mismo PR.
- **Testear el sync sin dispositivos/servidores reales.** *Mitigación:* lógica de merge pura en JVM;
  `MockWebServer` + Room en memoria para la capa de repositorio/sync; tests de integración de backend contra
  un Postgres desechable (Testcontainers o la DB del compose).
- **Regresiones de la puerta de auth en features existentes.** El widget (05), la notificación de pantalla
  bloqueada (06) y los recordatorios (09) leen tareas; introducir cuenta/logout no debe romperlos cuando no
  hay sesión. *Mitigación:* definir explícitamente el comportamiento sin-sesión/deslogueado para esas
  superficies durante la implementación (p. ej. estado vacío/deslogueado), y que sigan leyendo el mismo
  `TaskRepository`.

---

## Open Questions (decidir antes de implementar)

*(Las decisiones de stack, ubicación del repo, cuenta obligatoria, estrategia de token y agentes ya están
cerradas — ver "Decisiones cerradas" arriba. Quedan estos puntos genuinamente abiertos:)*

- **OQ-1 — Profundidad de la UI de estado de sync.** Mínima (spinner de pull-to-refresh + una pista sutil de
  "sin conexión", **recomendada**) vs. una insignia de sync por tarea / pantalla de sync más rica.
- **OQ-2 — Forma de la API.** Recursos REST separados (`/tasks` CRUD + `GET ?since=`, **recomendado**, RESTful
  y claro) vs. un único endpoint batcheado `POST /sync` de push+pull (menos round-trips, más a medida).
- **OQ-3 — Migración de Room.** Mantener `fallbackToDestructiveMigration` (borra datos locales,
  **recomendado** por precedente del proyecto y porque las tareas pasan a ser propiedad del servidor) vs.
  escribir la primera `Migration` real del proyecto ahora que el esquema se estabiliza en torno a un backend
  (buena enseñanza, más trabajo).

---

## Siguiente paso

Por favor, **revisa y aprueba** esta especificación — en particular las Open Questions restantes (profundidad
de la UI de sync, forma de la API, política de migración de Room). Una vez aprobada, el flujo continúa según
el **Mandatory Workflow** de CLAUDE.md: crear la rama `feature/remote-db-sync`, redactar **primero el
contrato de API**, reintroducir la sección **API Contract** de CLAUDE.md, implementar cliente + backend
(`backend-engineer` + `devops-security-engineer` + `mobile-engineer`), añadir tests (`qa-engineer`: lógica de
merge pura, `MockWebServer` + Room en memoria, integración de backend), y escribir `tutorial/11-bbdd-remota.md`
antes de commitear.
