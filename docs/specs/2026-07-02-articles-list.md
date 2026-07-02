# Especificación — Listado de artículos de gestión del tiempo

- **Fecha:** 2026-07-02
- **Feature:** Listado de artículos (Articles List)
- **Prompt origen:** `docs/prompts/03-articulos-lista.md`
- **Rama sugerida:** `feature/articles-list`
- **Lección tutorial asociada:** `tutorial/03-articulos.md` (español)
- **Estado:** Pendiente de aprobación

---

## Overview

Esta feature añade a Never Late Again una sección de **contenido educativo**: una pantalla
con un **listado de artículos** sobre técnicas de gestión del tiempo (Pomodoro, time-blocking,
la regla de los 2 minutos, etc.) y una **pantalla de detalle** que muestra el artículo completo
al pulsar sobre él. El acceso a la sección se hace desde la **Home**.

El objetivo de producto es dar a las personas con TDA/TDAH recursos breves y accionables que
las ayuden a entender y aplicar técnicas concretas de gestión del tiempo, sin depender de una
conexión a internet.

Todo el contenido va **empaquetado dentro de la app** (JSON en `assets/` o datos en código);
**no** hay carga remota todavía. La sustitución por una API remota es una feature futura
(feature 10, `docs/prompts/10-articulos-api.md`), por lo que esta feature debe dejar la capa de
datos **preparada para el intercambio** sin tocar la UI.

Como parte del proyecto-tutorial, esta feature es el primer contacto con **listas** (`LazyColumn`),
**navegación con argumentos** (lista → detalle), el **patrón repositorio** con fuente de datos
local y la **lectura de assets**, construyendo sobre lo aprendido en las lecciones 01
(scaffolding/Compose) y 02 (onboarding/home, `ViewModel` + `StateFlow`, Navigation Compose,
DataStore).

### Conceptos nuevos que enseña (para `tutorial/03-articulos.md`)

- `data class` para modelar un artículo (`Article`).
- `LazyColumn` y renderizado eficiente de listas (uso de `items`, `key`).
- Navegación **lista → detalle** pasando argumentos con Navigation Compose (`route` con parámetro).
- Patrón **repositorio** simple con fuente de datos local + lectura desde `assets/`.
- Separación **UI / datos** (capa de datos independiente de Compose).

---

## Goals

Éxito significa que:

1. El usuario puede abrir, desde la Home, una lista de artículos de gestión del tiempo.
2. El usuario puede tocar un artículo y leer su contenido completo en una pantalla de detalle.
3. El usuario puede volver a la lista desde el detalle sin perder su posición de navegación.
4. Todo el contenido se sirve desde datos empaquetados en la app y funciona sin conexión.
5. La capa de datos queda tras una **interfaz de repositorio**, de modo que la feature 10 pueda
   sustituir la implementación local por una remota **sin cambiar la UI ni los ViewModels**.
6. La lección `tutorial/03-articulos.md` explica los conceptos nuevos con referencia al código real.

---

## User Stories

### US-1 — Ver el listado de artículos
**Como** persona que quiere mejorar su gestión del tiempo,
**quiero** ver una lista de artículos sobre técnicas de productividad,
**para** elegir cuál leer.

**Criterios de aceptación:**
- Dado que abro la pantalla de artículos, cuando la lista carga, entonces veo una fila por
  artículo con al menos el **título** y un **resumen/subtítulo** corto.
- La lista se renderiza con `LazyColumn` (renderizado perezoso, no una `Column` con scroll).
- La lista muestra todos los artículos empaquetados (al menos 3: Pomodoro, time-blocking,
  regla de los 2 minutos).
- Si por algún motivo no hay artículos, se muestra un estado vacío legible en vez de una
  pantalla en blanco.

### US-2 — Acceder a la sección desde la Home
**Como** usuario de la app,
**quiero** un punto de entrada visible en la Home,
**para** llegar a los artículos sin buscar.

**Criterios de aceptación:**
- Dado que estoy en la Home, veo un elemento (botón/tarjeta/opción) claramente etiquetado que
  lleva a la lista de artículos.
- Al pulsarlo, navego a la pantalla de listado usando la navegación ya existente (`AppNavHost`).

### US-3 — Leer el detalle de un artículo
**Como** lector,
**quiero** tocar un artículo y ver su contenido completo,
**para** aprender la técnica.

**Criterios de aceptación:**
- Dado que estoy en la lista, cuando toco un artículo, entonces navego a una pantalla de detalle.
- El identificador del artículo se pasa como **argumento de navegación**; el detalle carga el
  artículo correcto a partir de ese id.
- El detalle muestra el **título** y el **cuerpo completo** del artículo, con scroll si el
  contenido no cabe en pantalla.
- Si el id no corresponde a ningún artículo, se muestra un estado de error/no encontrado legible.

### US-4 — Volver a la lista
**Como** lector,
**quiero** volver a la lista desde el detalle,
**para** elegir otro artículo.

**Criterios de aceptación:**
- Dado que estoy en el detalle, cuando uso el gesto/botón de retroceso (o la flecha de la barra
  superior), entonces vuelvo a la lista.
- Al volver, la lista conserva su estado de navegación (no se reinicia la pila de forma inesperada).

### US-5 — Contenido empaquetado y offline
**Como** usuario,
**quiero** que los artículos estén disponibles sin conexión,
**para** poder leerlos en cualquier momento.

**Criterios de aceptación:**
- El contenido se carga desde datos empaquetados en la app (JSON en `assets/` o datos en código),
  no desde red.
- La lista y el detalle funcionan con el dispositivo en modo avión.

---

## Acceptance Criteria (resumen verificable)

Criterios concretos y testeables para dar la feature por completada:

1. **Renderizado de la lista:** la pantalla de artículos muestra una fila por artículo (título +
   resumen) usando `LazyColumn`. *(Test de UI Compose sobre presencia de nodos.)*
2. **Navegación a detalle:** tocar una fila navega a la ruta de detalle con el `articleId`
   correcto como argumento. *(Test de navegación / UI.)*
3. **Contenido del detalle:** la pantalla de detalle muestra el título y el cuerpo completo del
   artículo seleccionado. *(Test de UI comprobando el texto del artículo esperado.)*
4. **Retroceso:** desde el detalle, el retroceso devuelve a la lista. *(Test de navegación.)*
5. **Entrada desde Home:** existe un punto de entrada en la Home que navega al listado.
   *(Test de UI sobre la Home.)*
6. **Carga desde assets/datos empaquetados:** el repositorio local lee el contenido empaquetado
   y devuelve la lista de `Article`. *(Test unitario JVM del repositorio / parseo del JSON.)*
7. **Estados de borde:** lista vacía y artículo no encontrado muestran mensajes legibles, no
   pantallas en blanco ni crashes.
8. **Separación UI/datos:** la UI y los ViewModels dependen de una **interfaz** de repositorio,
   no de la implementación concreta. *(Verificable por inspección de tipos / firma del ViewModel.)*
9. **Documentación:** se añade `tutorial/03-articulos.md` (español) cubriendo los conceptos nuevos.

---

## Technical Approach (alto nivel)

> Estrategia orientativa; el diseño de detalle es responsabilidad de la fase de implementación
> (`mobile-engineer`). Se listan aquí las decisiones que afectan al alcance y a la extensibilidad.

- **Modelo de dominio:** `data class Article` (p. ej. `id`, `title`, `summary`, `body`) en un
  paquete de dominio/datos, independiente de Compose.
- **Fuente de datos local:** los artículos se empaquetan como **JSON en `app/src/main/assets/`**
  (preferido, para practicar lectura de assets y parseo) o como datos en código. El parseo puede
  hacerse con `kotlinx.serialization` u `org.json` según lo que decida implementación; cualquier
  dependencia nueva debe declararse en `gradle/libs.versions.toml`.
- **Repositorio con interfaz (clave para feature 10):** definir una **interfaz**
  `ArticleRepository` (métodos tipo `getArticles()` / `getArticleById(id)`) y una implementación
  local `LocalArticleRepository` (o `AssetArticleRepository`). La UI y los ViewModels dependen
  **solo de la interfaz**, de forma que la feature 10 pueda introducir una implementación remota
  (Retrofit/Room) sin tocar la capa de presentación.
- **Presentación:** `ArticlesViewModel` (lista) y `ArticleDetailViewModel` (detalle, recibe el
  `articleId`), exponiendo estado con `StateFlow`; Composables **stateless** con hoisting, en línea
  con la lección 02. Creación de ViewModels vía el `AppViewModelFactory` existente.
- **Navegación:** añadir rutas al `AppNavHost` existente: `articles` (lista) y
  `articleDetail/{articleId}` (detalle con argumento). El punto de entrada se añade en la Home.
- **Ubicación del código:** `ui/articles/` (pantallas + ViewModels), modelo y repositorio en
  `data/` (o `domain/` + `data/`) bajo `com.neverlate`.

---

## Out of Scope

Esta feature **no** incluye:

- **Carga remota / API:** obtener artículos desde una API REST o endpoint propio. Se difiere a la
  **feature 10** (`docs/prompts/10-articulos-api.md`), que introducirá Retrofit/Ktor, DTOs, mapeo
  y estados loading/error/vacío de red.
- **Búsqueda y filtrado** de artículos.
- **Favoritos / marcadores** (guardar artículos para leer luego).
- **Caché offline más allá de los datos empaquetados** (Room, single source of truth, refresco):
  parte de la feature 10.
- Ordenación configurable, categorías/etiquetas, paginación, imágenes remotas, compartir,
  analítica de lectura o markdown enriquecido en el cuerpo (más allá de texto simple).
- Edición o creación de artículos por parte del usuario.

---

## Dependencies

- **Navigation Compose ya en uso:** se construye sobre el `AppNavHost` existente
  (`ui/navigation/AppNavHost`) y el patrón de rutas ya establecido en la feature de
  onboarding/home. Esta feature introduce el paso de **argumentos** en las rutas.
- **Home existente:** el punto de entrada se integra en la pantalla Home actual (`ui/home/*`);
  requiere que la Home siga siendo el destino accesible tras el onboarding.
- **Patrón `ViewModel` + `StateFlow` + `AppViewModelFactory`:** reutiliza la infraestructura de
  presentación ya introducida en la lección 02.
- **Interfaz de repositorio (dependencia de diseño, no de código previo):** el repositorio DEBE
  definirse como una **interfaz** con una implementación local, para que la **feature 10** pueda
  sustituir la fuente local por una API **sin cambiar la UI**. Esta es la restricción de
  arquitectura más importante de la feature.
- **Posible dependencia nueva:** si se usa `kotlinx.serialization` para parsear el JSON de
  `assets/`, debe añadirse al catálogo de versiones `gradle/libs.versions.toml` (y su plugin si
  procede). Sin dependencia nueva si se parsea con `org.json` (incluido en Android) o con datos
  en código.
- **Sin permisos nuevos ni cambios de manifiesto:** al ser contenido local, no se requiere el
  permiso `INTERNET` (ese sí lo introducirá la feature 10).

---

## Risks

- **Diseño del repositorio insuficientemente desacoplado:** si la UI acaba dependiendo de la
  implementación local en vez de una interfaz, la feature 10 obligará a reescribir la capa de
  presentación. *Mitigación:* interfaz `ArticleRepository` desde el primer commit y ViewModels
  que solo la conozcan a ella.
- **Contrato de datos que no anticipa la API futura:** si el modelo `Article` local diverge del
  que expondrá la API, el mapeo futuro será costoso. *Mitigación:* modelar `Article` como modelo
  de dominio estable; en la feature 10 el DTO de red se mapeará a este modelo.
- **Parseo de assets frágil:** un JSON mal formado o un cambio de esquema puede romper la carga.
  *Mitigación:* test unitario del parseo y estado vacío/error legible.
- **Sobrecarga didáctica:** la feature introduce varios conceptos nuevos a la vez (listas +
  navegación con args + repositorio + assets). *Mitigación:* la lección 03 debe presentarlos de
  forma progresiva y referenciar las lecciones 01–02.
- **Ámbito del argumento de navegación:** pasar objetos complejos por la ruta es un antipatrón;
  debe pasarse solo el `articleId` y recargar desde el repositorio en el detalle. *Mitigación:*
  criterio de aceptación 3 exige cargar por id.

---

## Siguiente paso

Por favor, **revisa y aprueba** esta especificación antes de comenzar la implementación. Una vez
aprobada, el flujo continúa: crear la rama `feature/articles-list`, implementar con
`mobile-engineer`, tests con `qa-engineer` y la lección `tutorial/03-articulos.md` antes de
commitear (según el **Mandatory Workflow** de `CLAUDE.md`).
