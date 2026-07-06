# Especificación — Artículos desde una API remota (con caché offline)

- **Fecha:** 2026-07-05
- **Feature:** Feature 10 (extra) — cargar los artículos desde una **API REST remota** por HTTP, con
  **caché offline** en Room como única fuente de verdad, sustituyendo la fuente empaquetada
  (`assets/articles.json`) de la feature 03 (Articles from a remote API)
- **Prompt origen:** `docs/prompts/10-articulos-api.md`
- **Rama sugerida:** `feature/articles-api`
- **Lección tutorial asociada:** `tutorial/10-articulos-api.md` (español) — **obligatoria** antes de commitear
- **Estado:** Aprobada (2026-07-05)
- **Decisiones cerradas:** (1) **Opción A** — endpoint estático propio en JSON con un **DTO de wire
  deliberadamente distinto** (mapeo DTO → dominio real). (2) **Caché en la `NeverLateDatabase`
  existente**, subiendo `version` 1 → 2 (migración destructiva: borra tareas en dispositivos con
  datos; se documenta en la lección).

---

## Overview

Esta feature es la **evolución** de la feature 03 (Artículos): las pantallas de **lista** y
**detalle** siguen siendo las mismas, pero el contenido deja de venir del fichero empaquetado
`assets/articles.json` y pasa a **descargarse de una API REST remota por HTTP**. Como la red puede
fallar o no estar disponible, la app guarda lo descargado en una **caché local con Room**, que actúa
como **única fuente de verdad** (single source of truth): la UI siempre lee de la caché, y una
**estrategia de refresco** intenta traer datos frescos de la red y actualizar la caché.

Es la primera vez en el proyecto-tutorial que la app **habla con la red**. Por eso el objetivo
didáctico central es introducir **networking** con calma: cómo se define un cliente HTTP tipado
(Retrofit + OkHttp), cómo se deserializa JSON, cómo se separa el **DTO de red** del **modelo de
dominio**, y cómo se modelan los estados **cargando / con contenido / vacío / error** en la UI.

La restricción de arquitectura central es que la feature 03 **ya dejó preparado el seam**:

- El modelo de dominio `Article` (`data class Article(id, title, summary, body)`, en
  `data/articles/Article.kt`) es el **tipo estable** que ven todas las pantallas y ViewModels. **No
  cambia.**
- La **interfaz** `ArticleRepository` (`getArticles()` / `getArticleById(id)`, ambas `suspend`, en
  `data/articles/ArticleRepository.kt`) es el contrato del que dependen la UI y los ViewModels.
  Esta feature **añade una nueva implementación** detrás de esa interfaz; la capa de presentación
  cambia lo mínimo imprescindible (esencialmente, gana un **estado de error** y una acción de
  **reintento**).
- La implementación local actual `LocalArticleRepository` (lee de `assets/`) **deja de ser la
  implementación cableada**. Ver la *Decisión abierta* sobre si el JSON empaquetado se conserva como
  **semilla/fallback** o se retira.

Todo esto se integra sin framework de DI: los repositorios y ViewModels se construyen a mano en
`MainActivity` y `AppViewModelFactory` (igual que hoy), y la base de datos Room se crea a mano vía
`NeverLateDatabase.getInstance` (patrón ya existente para las tareas).

### Conceptos nuevos que enseña (para `tutorial/10-articulos-api.md`)

Partiendo de las lecciones previas (03: repositorio + `sealed interface` de estado; features 05/06/09:
Room, `@Entity`/`@Dao`, coroutines):

- **Networking con Retrofit + OkHttp:** definir una interfaz de API con funciones `suspend`,
  configurar el cliente HTTP y su `logging-interceptor`, y deserializar JSON reutilizando la
  `kotlinx.serialization` que ya usa el proyecto (mediante un *converter* de Retrofit).
- **DTO de red ≠ modelo de dominio:** una `data class` que refleja **la forma del JSON de la API**
  (deliberadamente distinta de `Article`) y una función de **mapeo** DTO → `Article`.
- **Estados de UI loading / success / empty / error** con `ViewModel` + `StateFlow`, ampliando el
  `sealed interface` de la feature 03 con un caso de **error** y una acción de **reintento**.
- **Caché offline con Room como única fuente de verdad** y una **estrategia de refresco**
  (leer siempre de la caché; refrescar desde la red y volcar el resultado en la caché).

---

## Goals

Éxito significa que:

1. Al abrir la lista de artículos con conexión, el usuario ve un indicador de carga y luego el
   contenido descargado de la API.
2. Lo descargado queda **cacheado en Room**, de modo que el usuario puede volver a leer los
   artículos **sin conexión**.
3. Si la red falla o no hay conexión pero **ya hay caché**, el usuario sigue viendo el contenido
   cacheado sin errores bloqueantes.
4. Si la red falla y **no hay caché**, el usuario ve un **estado de error legible con opción de
   reintentar**, no una pantalla en blanco ni un crash.
5. La pantalla de **detalle** funciona leyendo de la caché (fuente de verdad), incluido sin conexión.
6. El modelo `Article` y la interfaz `ArticleRepository` **siguen siendo el seam**: la nueva fuente
   remota se introduce detrás de la interfaz, y la lista/detalle apenas cambian salvo por el nuevo
   estado de error.
7. La lección `tutorial/10-articulos-api.md` explica los conceptos nuevos con referencia al código real.

---

## User Stories

### US-1 — Cargar los artículos desde la red

**Como** persona que quiere mejorar su gestión del tiempo,
**quiero** que la app descargue los artículos de una fuente remota,
**para** poder leer contenido que puede actualizarse sin reinstalar la app.

**Criterios de aceptación:**
- Dado que abro la lista **con conexión** y sin caché previa, cuando la pantalla carga, entonces veo
  primero un **estado de carga** (loading) y después la **lista de artículos** descargada.
- La descarga se hace con **Retrofit** sobre una función `suspend`, fuera del hilo principal.
- El JSON remoto se deserializa a un **DTO de red** y se **mapea** a `Article` antes de mostrarse; la
  UI y los ViewModels solo ven `Article`.
- Tras una descarga correcta, los artículos quedan **persistidos en la caché Room**.

### US-2 — Leer offline lo ya descargado (caché)

**Como** usuario,
**quiero** volver a leer los artículos que ya cargué aunque no tenga conexión,
**para** no depender de la red cada vez.

**Criterios de aceptación:**
- Dado que ya cargué la lista una vez (hay caché), cuando abro la app **en modo avión / sin red**,
  entonces veo la **lista cacheada** en lugar de un error.
- El detalle de un artículo previamente visto también se muestra sin conexión, leyéndolo de la caché.
- La caché es la **única fuente de verdad**: la UI lee de Room, no directamente de la respuesta de red.

### US-3 — Recuperarse de un fallo de red (error + reintento)

**Como** usuario,
**quiero** un mensaje claro y un botón para reintentar cuando la carga falla,
**para** no quedarme atascado en una pantalla vacía.

**Criterios de aceptación:**
- Dado que abro la lista **sin conexión y sin caché** (p. ej. primer arranque en modo avión), cuando
  la carga falla, entonces veo un **estado de error legible** con una acción de **Reintentar**.
- Al pulsar **Reintentar**, la app vuelve a intentar la descarga; si esta vez hay red, se muestra el
  contenido y se cachea.
- Si el fallo de red ocurre **pero sí hay caché**, la app **no** cae al estado de error: sigue
  mostrando el contenido cacheado (opcionalmente con un aviso discreto de "sin conexión").

### US-4 — Leer el detalle de un artículo

**Como** lector,
**quiero** tocar un artículo y ver su contenido completo,
**para** aprender la técnica.

**Criterios de aceptación:**
- Dado que estoy en la lista, cuando toco un artículo, entonces navego al detalle pasando el
  **`articleId`** como argumento de navegación (igual que en la feature 03).
- El detalle carga el artículo **desde la caché** por su id y muestra **título** y **cuerpo completo**
  con scroll si no cabe.
- Si el id no está en la caché, se muestra un estado **no encontrado / error** legible (no un crash);
  el detalle refleja también el caso de **fallo de red sin caché**.

### US-5 — Refrescar el contenido

**Como** usuario,
**quiero** obtener la versión más reciente de los artículos,
**para** ver contenido actualizado cuando vuelvo a tener conexión.

**Criterios de aceptación:**
- Al **abrir** la pantalla de lista, la app **intenta** una descarga fresca de la red (estrategia de
  refresco): si hay caché, la muestra de inmediato y la actualiza cuando llega la respuesta
  (*stale-while-revalidate*); si no hay caché, muestra carga hasta la primera respuesta.
- Existe un gesto de **pull-to-refresh** (deslizar hacia abajo) que vuelve a lanzar la descarga
  manualmente y actualiza la caché.
- Un refresco correcto **reemplaza/actualiza** el contenido cacheado y la lista se re-renderiza con lo
  nuevo sin salir de la pantalla.

### US-6 — El seam se mantiene (restricción de arquitectura)

**Como** desarrollador del proyecto,
**quiero** que la fuente remota entre detrás de la interfaz `ArticleRepository`,
**para** no reescribir la UI y dejar el terreno listo para la feature 11 (base de datos remota).

**Criterios de aceptación:**
- El modelo de dominio `Article` **no cambia** de forma.
- Las pantallas y ViewModels **siguen dependiendo de `ArticleRepository`**, no de la implementación
  concreta; el cableado se cambia en `MainActivity`/`AppViewModelFactory`, no en la UI.
- Cualquier ampliación del contrato del repositorio es **aditiva** (p. ej. una función de refresco);
  `getArticles()` y `getArticleById(id)` se conservan como métodos de **lectura** del seam.

---

## Acceptance Criteria (resumen verificable)

Criterios concretos y testeables para dar la feature por completada:

1. **Carga inicial:** con red y sin caché, la lista muestra **Loading** y luego **Content** con los
   artículos de la API. *(Test de ViewModel con repositorio/servidor fake: transición Loading → Content.)*
2. **Cacheo en éxito:** una descarga correcta escribe los artículos en la **tabla Room** de artículos.
   *(Test de integración del repositorio con `MockWebServer` + Room en memoria: tras `refresh`, el DAO
   devuelve las filas esperadas.)*
3. **Offline con caché:** con caché presente y **sin red**, `getArticles()` devuelve el contenido
   cacheado y la lista lo muestra. *(Test del repositorio: red que lanza excepción → se devuelve la
   caché; sin caer en error.)*
4. **Mapeo DTO → dominio:** el DTO de red (forma distinta a `Article`, ver *Technical Approach*) se
   mapea correctamente a `Article` (todos los campos, incluido `summary` derivado si la fuente no lo
   trae). *(Test unitario JVM puro del mapeador.)*
5. **Estado vacío:** si la API responde una lista vacía **y** no hay caché, la lista muestra el estado
   **Empty** (mensaje legible), distinto de Loading y de Error. *(Test de ViewModel.)*
6. **Estado de error + reintento:** sin red **y** sin caché, la lista muestra **Error** con un botón
   **Reintentar**; pulsarlo relanza la carga. *(Test de ViewModel: fallo → Error; reintento con fuente
   que ahora responde → Content. Test de UI Compose: el nodo de Reintentar existe y es clicable.)*
7. **Estrategia de refresco:** se intenta una descarga al **abrir** la pantalla y mediante
   **pull-to-refresh**; un refresco correcto actualiza la caché y la UI. *(Test de ViewModel/UI del
   disparo de refresco y de la actualización de estado.)*
8. **Detalle desde caché:** el detalle lee el artículo por `articleId` **de la caché** y muestra
   título + cuerpo; id ausente → **NotFound/Error** legible; el detalle refleja también el fallo de
   red sin caché. *(Test de ViewModel de detalle + test de UI.)*
9. **Seam intacto:** `Article` y la interfaz `ArticleRepository` conservan su rol; la UI/ViewModels
   dependen de la interfaz, no de la implementación remota. *(Verificable por inspección de tipos /
   firmas; el diff de las pantallas se limita al nuevo estado de error/reintento.)*
10. **Permiso de red:** el manifest declara el permiso **`INTERNET`** (permiso normal, sin solicitud
    en runtime). *(Verificable por inspección del `AndroidManifest.xml`.)*
11. **Documentación:** se añade `tutorial/10-articulos-api.md` (español) cubriendo networking, DTO/mapeo,
    estados de UI y caché con Room.

---

## Technical Approach (alto nivel)

> Estrategia orientativa; el diseño de detalle es responsabilidad de la fase de implementación
> (`mobile-engineer`). Se listan aquí las decisiones que afectan al alcance y a la extensibilidad.

- **Capa de red (Retrofit + OkHttp):**
  - Una interfaz de API tipada (p. ej. `ArticlesApi`) con una función `suspend` que devuelve la lista
    de **DTOs** de red.
  - Cliente **OkHttp** con `HttpLoggingInterceptor` (útil para la lección; nivel `BODY` solo en debug).
  - **Converter** que reutiliza `kotlinx.serialization` ya presente en el proyecto
    (`retrofit2-kotlinx-serialization-converter`), en lugar de introducir Moshi/Gson.
- **DTO ≠ dominio + mapeo:** definir un `ArticleDto` `@Serializable` cuya **forma difiera** de
  `Article` para que el mapeo sea real y didáctico (ver *Decisión abierta*; p. ej. campos `snake_case`
  como `article_id`, o `content` en vez de `body`, o sin `summary` — derivándolo del cuerpo). Una
  función pura `ArticleDto.toDomain(): Article` hace el mapeo y es trivialmente testeable en JVM.
- **Caché con Room como única fuente de verdad:**
  - Un `@Entity` `ArticleEntity` + un `@Dao` `ArticleDao` (insertar/reemplazar en bloque, leer todos,
    leer por id). La UI lee **siempre** de Room.
  - **Ubicación de la tabla (recomendación):** añadir `ArticleEntity`/`ArticleDao` a la
    **`NeverLateDatabase` ya existente** (subiendo `version` 1 → 2). Es la opción más simple para la
    lección: reutiliza `getInstance` y toda la infraestructura Room ya montada para las tareas.
    *Salvedad:* la BBDD usa hoy `fallbackToDestructiveMigration(dropAllTables = true)`, así que subir
    la versión **borra también las tareas** en dispositivos existentes — aceptable por ser un proyecto
    pre-release (política ya vigente), pero debe mencionarse en la lección. **Alternativa:** una base
    de datos Room independiente solo para artículos, que no toca el esquema de tareas a costa de un
    segundo `RoomDatabase`; se recomienda la opción de tabla única por simplicidad didáctica.
- **Repositorio remoto con caché:** una nueva implementación de `ArticleRepository` (p. ej.
  `CachingArticleRepository` / `RemoteArticleRepository`) que combina `ArticlesApi` + `ArticleDao`:
  - `getArticles()` / `getArticleById(id)` **leen de Room** (la caché es la fuente de verdad).
  - Un **refresco** intenta la descarga, mapea DTO → `Article`, vuelca en Room; ante fallo de red,
    conserva la caché. Para que el ViewModel distinga *vacío real* de *error sin caché* y pueda
    ofrecer reintento, el contrato del repositorio se amplía de forma **aditiva** (p. ej. una función
    `suspend refresh(): RefreshResult` que informe éxito/fallo, o que `getArticles()` lance/retorne un
    resultado tipado). La forma exacta es detalle de implementación; **la restricción es no romper
    `getArticles()`/`getArticleById()` como métodos de lectura del seam**.
- **Presentación (cambios mínimos):**
  - `ArticlesUiState` gana un caso **`Error`** (además de `Loading`/`Content`/`Empty`) y el ViewModel
    expone una acción `retry()`/`refresh()`. `ArticleDetailUiState` gana un caso **`Error`** para el
    fallo de red sin caché (junto al ya existente `NotFound`).
  - La `ArticlesScreen` añade **pull-to-refresh** y renderiza el nuevo estado de error con botón de
    reintento. Salvo eso, lista y detalle quedan como en la feature 03.
- **Cableado (DI manual):** en `MainActivity` se sustituye `LocalArticleRepository(...)` por la nueva
  implementación remota (construyendo Retrofit/OkHttp y el `ArticleDao` desde `NeverLateDatabase`).
  `AppViewModelFactory` sigue recibiendo un `ArticleRepository`; **no cambia su firma**.
- **Ubicación del código:** capa de red y caché en `data/articles/` (p. ej. `ArticlesApi`,
  `ArticleDto`, `ArticleMapper`, `ArticleEntity`, `ArticleDao`, `CachingArticleRepository`); las
  pantallas siguen en `ui/articles/`.

---

## Out of Scope

Esta feature **no** incluye:

- **Escribir en un backend / sincronización bidireccional / resolución de conflictos:** solo se
  **lee** de la API. Escribir tareas o artículos en un servidor remoto es la **feature 11**
  (`bbdd-remota`, base de datos remota) y queda fuera.
- **Autenticación / claves de API / cabeceras de auth.** La fuente recomendada es pública y sin auth.
- **Paginación / scroll infinito / carga incremental.**
- **Montar un servidor propio** (backend real, despliegue): la fuente es un endpoint estático o una
  API pública existente (ver *Decisión abierta*).
- **Búsqueda, filtrado, favoritos/marcadores, categorías/etiquetas, imágenes remotas, compartir,
  markdown enriquecido** en el cuerpo — igual que en la feature 03, siguen fuera.
- **Invalidación de caché por tiempo (TTL) / políticas de expiración sofisticadas:** basta con la
  estrategia *stale-while-revalidate* descrita (refresco al abrir + pull-to-refresh).
- **Sincronización en segundo plano** (WorkManager para refrescar artículos sin la app abierta).

---

## Dependencies

Ya presentes en el proyecto (no hay que añadirlas): **Room 2.7.1** (`room-runtime`, `room-ktx`,
`room-compiler` vía **KSP**), **kotlinx-serialization-json 1.7.3** + su plugin,
**kotlinx-coroutines**, y **Navigation Compose**. La feature 03 ya dejó el seam
(`Article` + `ArticleRepository`) y las pantallas lista/detalle.

**Nuevas dependencias a añadir al catálogo de versiones** (`gradle/libs.versions.toml`) — las
**versiones concretas se eligen en implementación**, no se inventan aquí:

- **Retrofit** (`com.squareup.retrofit2:retrofit`) — cliente HTTP tipado.
- **OkHttp** + **logging-interceptor** (`com.squareup.okhttp3:okhttp`,
  `com.squareup.okhttp3:logging-interceptor`) — motor HTTP y logging para la lección.
- **Retrofit ↔ kotlinx.serialization converter**
  (`com.jakewharton.retrofit:retrofit2-kotlinx-serialization-converter`) — para **reutilizar** la
  `kotlinx.serialization` existente en vez de añadir Moshi/Gson.
- **(Solo tests)** `com.squareup.okhttp3:mockwebserver` — servidor HTTP falso para probar el
  repositorio de red (lo pide el prompt: "repositorio con servidor mock").

**Manifest / permisos:**

- Declarar el permiso **`INTERNET`** en `AndroidManifest.xml`. Es un **permiso normal**: no requiere
  solicitud en runtime (a diferencia de `POST_NOTIFICATIONS`).

**Dependencias de diseño (no de código previo):**

- El seam de la feature 03 (`Article` estable + interfaz `ArticleRepository`) debe mantenerse; la
  fuente remota entra **detrás** de la interfaz.
- La base de datos Room existente (`NeverLateDatabase`) y su patrón `getInstance` se reutilizan para
  alojar la tabla de artículos (ver *Technical Approach* y su salvedad sobre la migración destructiva).

---

## Decisión abierta — ¿Qué API remota se usa?

La app **no tiene** una API pública canónica de "artículos de gestión del tiempo". Hay que elegir la
fuente; el usuario aprueba la opción. La **URL concreta se finaliza en implementación**.

- **Opción A (recomendada) — endpoint estático propio en JSON.** Servir el mismo tipo de contenido
  que hoy (curado, en español/inglés) desde un fichero estático accesible por HTTP (p. ej. un raw de
  GitHub / GitHub Pages, o un gist público), **sin clave de API**.
  - *Pros:* el contenido sigue siendo de calidad y sobre gestión del tiempo; sin auth; el trabajo de
    red es honesto (Retrofit real sobre HTTP).
  - *Contras:* si el DTO fuese idéntico a `Article`, la lección de "DTO ≠ dominio + mapeo" quedaría
    fina. **Mitigación:** dar al DTO de red una **forma deliberadamente distinta** (p. ej. campos
    `snake_case` como `article_id`, un campo `content` en lugar de `body`, o **sin** `summary`
    —derivándolo de las primeras líneas del cuerpo—) para que el mapeo sea real y merezca una sección
    en la lección.
- **Opción B — API pública genérica tipo JSONPlaceholder** (`/posts` → mapear `title`/`body` a
  `Article`).
  - *Pros:* endpoint canónico para "aprender Retrofit"; fuerza un DTO ≠ dominio genuino.
  - *Contras:* contenido *placeholder* sin sentido, sin campo `summary` (hay que derivarlo) y ajeno a
    la gestión del tiempo, lo que desentona con el propósito de la app.

**Recomendación:** **Opción A con un DTO de wire deliberadamente distinto**, para conservar contenido
curado y a la vez mantener una lección de mapeo significativa.

---

## Risks

- **Ruptura accidental del seam:** si la UI/ViewModels acaban dependiendo de la implementación remota
  o del DTO en vez de `Article`/`ArticleRepository`, la feature 11 (base de datos remota) obligaría a
  reescribir presentación. *Mitigación:* la fuente remota entra detrás de la interfaz; los cambios en
  la UI se limitan al estado de error/reintento (criterio de aceptación 9).
- **Confundir "vacío" con "error":** hoy `getArticles()` devuelve `emptyList()` tanto si no hay
  artículos como si algo falla; eso impide distinguir Empty de Error. *Mitigación:* ampliar el
  contrato de forma aditiva (función de refresco / resultado tipado) para señalar el fallo, sin tocar
  las firmas de lectura del seam.
- **Migración destructiva de Room:** subir `NeverLateDatabase` a `version = 2` con
  `fallbackToDestructiveMigration(dropAllTables = true)` **borra las tareas** en dispositivos con
  datos. *Mitigación:* aceptable en pre-release (política ya vigente); documentarlo en la lección, o
  usar una base de datos separada para artículos si se prefiere no tocar el esquema de tareas.
- **Fragilidad de la fuente remota:** si la URL/servicio cambia o cae, la carga inicial (sin caché)
  falla. *Mitigación:* estado de error con reintento + caché offline; en tests, `MockWebServer`
  desacopla las pruebas de la red real.
- **Sobrecarga didáctica:** la feature introduce muchos conceptos a la vez (Retrofit, OkHttp, DTO,
  mapeo, Room como fuente de verdad, refresco, nuevo estado de UI). *Mitigación:* la lección 10 debe
  presentarlos de forma progresiva, apoyándose en el seam ya conocido de la lección 03.
- **`summary` ausente en la fuente:** si el DTO no trae `summary` (Opción B o Opción A sin ese campo),
  hay que **derivarlo** (p. ej. primeras N palabras del cuerpo). *Mitigación:* función de mapeo con
  test unitario que fije la derivación.
- **Testear red en un entorno sin dispositivo:** el mapeo y el ViewModel se prueban en JVM puro; el
  repositorio con `MockWebServer` + Room en memoria puede requerir instrumentación/Robolectric.
  *Mitigación:* separar lógica pura (mapeo, transiciones de estado) de la parte que necesita Android.

---

## Nota sobre la lección tutorial

Según la **Tutorial Methodology** de `CLAUDE.md`, esta feature **no está completa** sin su lección en
español `tutorial/10-articulos-api.md`, numerada tras la lección 09. Debe explicar, de forma
progresiva y con referencia al código real, los conceptos nuevos: networking con Retrofit + OkHttp,
DTO de red vs. modelo de dominio y su mapeo, los estados loading/success/empty/error con
`ViewModel` + `StateFlow`, y la caché offline con Room como única fuente de verdad + estrategia de
refresco. **Esta especificación no incluye la lección**; se redacta en la fase de implementación.

---

## Siguiente paso

Por favor, **revisa y aprueba** esta especificación (incluida la **Decisión abierta** sobre qué API
remota usar) antes de comenzar la implementación. Una vez aprobada, el flujo continúa según el
**Mandatory Workflow** de `CLAUDE.md`: crear la rama `feature/articles-api`, implementar con
`mobile-engineer`, tests con `qa-engineer` (repositorio con `MockWebServer`) y la lección
`tutorial/10-articulos-api.md` antes de commitear.
