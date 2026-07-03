# Especificación — Ajustes + modo oscuro

- **Fecha:** 2026-07-03
- **Feature:** Pantalla de Ajustes con selector de tema claro / oscuro / seguir sistema, persistido y aplicado en toda la app (Settings + Dark Mode)
- **Prompt origen:** `docs/prompts/07-ajustes-modo-oscuro.md`
- **Rama sugerida:** `feature/settings-dark-mode`
- **Lección tutorial asociada:** `tutorial/07-ajustes.md` (español)
- **Estado:** Pendiente de aprobación

---

## Overview

Esta feature añade una **pantalla de Ajustes**, accesible desde Home, cuyo primer y único ajuste
por ahora es el **tema de la app**: la persona usuaria elige entre **claro**, **oscuro** o **seguir
el sistema**. La elección se **persiste** y se **aplica de forma inmediata a toda la app**, no solo
a la pantalla de Ajustes.

Es la primera vez que una preferencia del usuario **cambia la apariencia global** de la aplicación.
Hasta ahora `NeverLateTheme` (en `ui/theme/Theme.kt`) decidía claro/oscuro **únicamente** a partir
del ajuste del sistema (`isSystemInDarkTheme()`, valor por defecto de su parámetro `darkTheme`).
Esta feature convierte esa decisión en algo **controlado por una preferencia guardada**: el tema
que ve la persona pasa a ser el resultado de leer un `Flow` de DataStore en lo más alto de la
composición y traducirlo al parámetro `darkTheme` de `NeverLateTheme`.

La restricción de arquitectura central es la **reutilización de la capa de preferencias existente**:
la feature 02 ya introdujo `UserPreferencesRepository` sobre **Jetpack DataStore (Preferences)** con
el fichero `user_prefs`. Esta feature **amplía ese mismo repositorio y el mismo fichero** con una
clave nueva para el tema; **no** crea un segundo DataStore ni una segunda fuente de verdad. El
patrón "interfaz + implementación DataStore + fake en tests" ya establecido para el onboarding se
extiende, no se reinventa.

Todo sigue siendo **local-only** (sin backend). La preferencia de tema vive en disco, en el mismo
`DataStore` que el nombre y el flag de onboarding.

### Conceptos nuevos que enseña (para `tutorial/07-ajustes.md`)

Partiendo de las lecciones 01–06 (Compose, `ViewModel`/`StateFlow`, DI manual, repositorio tras
interfaz, DataStore para el onboarding, navegación, Room + `Flow`):

- **Repaso y profundización de DataStore para preferencias:** volver sobre `UserPreferencesRepository`
  de la feature 02 y **ampliarlo** con una preferencia nueva. Cómo se añade una **clave** al mismo
  `Preferences` DataStore (`stringPreferencesKey`), cómo se **lee con un valor por defecto** y cómo se
  **escribe** de forma atómica con `edit { ... }`. Persistir un **`enum`** como `String` (guardar
  `mode.name`, leer con un *parse* tolerante a valores desconocidos que cae en un valor por defecto
  seguro).
- **Tematizado dinámico de Material 3 según una preferencia:** conectar la preferencia con
  `NeverLateTheme`. Entender que `NeverLateTheme` ya recibe un parámetro `darkTheme`; la novedad es
  **quién decide su valor**: un `enum ThemeMode { LIGHT, DARK, SYSTEM }` que se traduce a un `Boolean`
  (`LIGHT` → `false`, `DARK` → `true`, `SYSTEM` → `isSystemInDarkTheme()`). El recálculo es reactivo:
  al cambiar la preferencia, la composición se recompone con el nuevo esquema de color.
- **Exponer preferencias de forma reactiva (`Flow` → estado de la app):** leer el `Flow` de
  preferencias **en lo más alto de la composición** (dentro de `setContent`, envolviendo `NeverLateTheme`)
  con `collectAsStateWithLifecycle`, y elegir un **valor inicial** sensato para evitar un *parpadeo* de
  tema erróneo mientras DataStore carga desde disco (mismo tipo de razonamiento que el "startup flash"
  ya resuelto en `AppNavHost`). Y el patrón `ViewModel` + `StateFlow` para la propia pantalla de
  Ajustes (leer el modo actual y escribir el nuevo).

---

## Goals

Éxito significa que:

1. Existe una **pantalla de Ajustes** navegable **desde Home** (y con vuelta atrás a Home).
2. La pantalla de Ajustes ofrece un **selector de tema** con tres opciones mutuamente excluyentes:
   **claro**, **oscuro** y **seguir sistema**, mostrando cuál está activa.
3. Al elegir una opción, el **tema de toda la app cambia de inmediato** (no solo la pantalla de
   Ajustes), sin reiniciar la app.
4. La elección se **persiste** en el `DataStore` existente (`user_prefs`) y se **mantiene tras
   cerrar y reabrir** la app.
5. Con **"seguir sistema"** seleccionado, la app respeta el modo claro/oscuro del dispositivo y
   **reacciona** si el sistema cambia de modo.
6. La preferencia se lee de forma **reactiva** (`Flow` → estado) y se aplica vía el parámetro
   `darkTheme` de `NeverLateTheme`, **sin duplicar** la lógica de selección de esquema de color que
   ya vive en `Theme.kt`.
7. Se **amplía** `UserPreferencesRepository` (misma interfaz, mismo fichero DataStore) **sin romper**
   el onboarding ni la lectura de `onboarded`/`name` existentes.
8. La lección `tutorial/07-ajustes.md` (español) explica los conceptos nuevos con referencia al
   código real, de forma progresiva sobre las lecciones 01–06.

---

## User Stories

### US-1 — Abrir Ajustes desde Home
**Como** persona usuaria de la app,
**quiero** un acceso a Ajustes desde la pantalla de inicio,
**para** poder configurar la app sin buscar dónde está.

**Criterios de aceptación:**
- Home muestra un punto de entrada a **Ajustes** (una acción en la `TopAppBar` —p. ej. un icono de
  engranaje— y/o una opción en la lista de Home), con su texto/`contentDescription` desde
  `strings.xml`.
- Tocarlo navega a la ruta **`settings`** del `AppNavHost`.
- Desde Ajustes, **atrás** (botón del sistema y/o flecha de la `TopAppBar`) vuelve a Home sin
  romper la pila de navegación.

### US-2 — Elegir el tema de la app
**Como** persona usuaria,
**quiero** elegir entre tema claro, oscuro o seguir el sistema,
**para** que la app se vea como yo prefiero.

**Criterios de aceptación:**
- La pantalla de Ajustes presenta las **tres opciones** (claro / oscuro / seguir sistema) como una
  selección única (p. ej. `RadioButton` en filas, o un control equivalente de Material 3), con
  etiquetas desde `strings.xml`.
- La opción **actualmente activa** aparece marcada al abrir la pantalla, reflejando el valor
  persistido (o el valor por defecto si nunca se ha tocado).
- Al seleccionar una opción, esa opción queda marcada y el resto desmarcadas.

### US-3 — El cambio se aplica a toda la app de inmediato
**Como** persona usuaria,
**quiero** ver el cambio de tema al instante y en todas las pantallas,
**para** confirmar que mi elección tuvo efecto.

**Criterios de aceptación:**
- Al elegir **claro** u **oscuro**, la app cambia de esquema de color **inmediatamente** (la propia
  pantalla de Ajustes y, al volver, Home y el resto de pantallas), sin reiniciar la app.
- El cambio se aplica porque la preferencia se traduce al parámetro `darkTheme` de `NeverLateTheme`
  en lo más alto de la composición; **no** hay pantallas que se queden con el tema anterior.

### US-4 — La preferencia se recuerda entre sesiones
**Como** persona usuaria,
**quiero** que la app recuerde el tema que elegí,
**para** no tener que volver a configurarlo cada vez.

**Criterios de aceptación:**
- Tras elegir un tema y **cerrar completamente** la app, al reabrirla la app arranca con el tema
  elegido.
- La preferencia se guarda en el `DataStore` existente (`user_prefs`), como una clave nueva, sin
  afectar a `name`/`onboarded`.
- Un **install nuevo** (sin preferencia guardada) arranca con el valor por defecto **"seguir
  sistema"**.

### US-5 — "Seguir sistema" respeta y reacciona al dispositivo
**Como** persona usuaria que prefiere no gestionar el tema manualmente,
**quiero** que la app siga el modo claro/oscuro del sistema,
**para** que se adapte automáticamente (p. ej. de día/de noche).

**Criterios de aceptación:**
- Con **"seguir sistema"** seleccionado, la app usa el modo claro/oscuro del dispositivo
  (`isSystemInDarkTheme()`).
- Si el sistema cambia de modo mientras la app está en primer plano (o al volver de segundo plano),
  la app **recompone** al esquema correspondiente **sin** que la persona tenga que reelegir nada.
- "Seguir sistema" **no** guarda un booleano claro/oscuro fijo: guarda el **modo `SYSTEM`**, de modo
  que la decisión concreta se recalcula en cada composición.

---

## Acceptance Criteria (resumen verificable)

Criterios concretos y testeables para dar la feature por completada:

1. **Modelo de preferencia:** existe un `enum ThemeMode { LIGHT, DARK, SYSTEM }` y `UserPreferences`
   incluye un campo `themeMode` con **valor por defecto `SYSTEM`**. *(Inspección + test JVM del
   repositorio/fake.)*
2. **Persistencia (lectura/escritura):** `UserPreferencesRepository` expone el `themeMode` dentro de
   su `Flow<UserPreferences>` y ofrece un método para guardarlo (p. ej. `suspend fun saveThemeMode(mode: ThemeMode)`);
   la implementación DataStore usa una **clave nueva** (`stringPreferencesKey("theme_mode")`) en el
   mismo fichero `user_prefs`, guardando `mode.name`. *(Test JVM contra un fake + inspección de la
   implementación DataStore.)*
3. **Parse tolerante:** leer un valor de tema ausente o desconocido en DataStore devuelve el valor por
   defecto `SYSTEM` (sin crashear). *(Test JVM de la función pura de parseo `String? -> ThemeMode`.)*
4. **Traducción a `darkTheme`:** existe una función pura que mapea `(ThemeMode, systemInDark: Boolean) -> Boolean`
   con `LIGHT→false`, `DARK→true`, `SYSTEM→systemInDark`. *(Test JVM exhaustivo de los tres casos.)*
5. **Aplicación global:** `MainActivity` lee la preferencia (Flow) en `setContent` y la pasa a
   `NeverLateTheme(darkTheme = ...)`, envolviendo `AppNavHost`; el cambio afecta a toda la app.
   *(Inspección + verificación manual: cambiar el tema y comprobar Home y otras pantallas.)*
6. **Sin parpadeo:** mientras DataStore carga, la app usa un valor inicial sensato (p. ej. `SYSTEM`)
   para no mostrar un flash de tema erróneo. *(Inspección del `initialValue`/estado inicial +
   verificación manual en arranque.)*
7. **Navegación:** nueva ruta `settings` en `AppNavHost`, accesible desde Home y con vuelta atrás.
   *(Inspección del nav graph + verificación manual.)*
8. **Pantalla de Ajustes:** `SettingsScreen` (stateless) + `SettingsRoute` + `SettingsViewModel`
   siguiendo el patrón route/screen/ViewModel + `StateFlow` del resto de features; muestra la opción
   activa y permite cambiarla. *(Inspección + verificación manual.)*
9. **Textos externalizados:** todo el texto visible (título "Ajustes", etiquetas de las tres opciones,
   `contentDescription` del icono) vive en `res/values/strings.xml`. *(Inspección; sin literales en el
   código.)*
10. **Sin regresión de onboarding:** `name`/`onboarded` y el flujo de arranque de `AppNavHost` siguen
    funcionando igual. *(Tests existentes de onboarding en verde + verificación manual.)*
11. **Build:** compila con `./gradlew :app:assembleDebug` usando el wrapper; los tests JVM pasan con
    `./gradlew :app:testDebugUnitTest`.
12. **Documentación:** se añade `tutorial/07-ajustes.md` (español) cubriendo los conceptos nuevos con
    referencia al código real.

---

## Technical Approach (alto nivel)

> Estrategia orientativa; el diseño de detalle es responsabilidad de la fase de implementación
> (`mobile-engineer`). Se listan aquí las decisiones que afectan al alcance y a la reutilización.

- **Modelo de tema:** un `enum ThemeMode { LIGHT, DARK, SYSTEM }` (nuevo, p. ej. en
  `data/UserPreferencesRepository.kt` junto a `UserPreferences`, o en un fichero propio del paquete
  `data`). `UserPreferences` gana el campo `val themeMode: ThemeMode = ThemeMode.SYSTEM`.
- **Ampliación del repositorio (reutilización, clave):** en `DataStoreUserPreferencesRepository` se
  añade una clave `THEME_MODE = stringPreferencesKey("theme_mode")` al `object Keys` existente; el
  `map { ... }` del `Flow` la lee (con parse tolerante → `SYSTEM` por defecto) y se añade
  `override suspend fun saveThemeMode(mode: ThemeMode)` que hace `edit { it[Keys.THEME_MODE] = mode.name }`.
  **Mismo fichero `user_prefs`, misma interfaz.** El fake de tests de la feature 02 se amplía en
  consecuencia.
- **Función pura de traducción:** una función `themeModeToDark(mode: ThemeMode, systemInDark: Boolean): Boolean`
  (o equivalente) que concentra la lógica testeable, y una función de parseo `String? -> ThemeMode`
  tolerante. Ambas puras y cubiertas por tests JVM; el composable y `Theme.kt` se apoyan en ellas y
  quedan finos.
- **Cableado del tema en `MainActivity`:** en `setContent`, antes de `NeverLateTheme`, colectar la
  preferencia con `collectAsStateWithLifecycle` (valor inicial `ThemeMode.SYSTEM` para evitar
  parpadeo) y calcular `darkTheme = themeModeToDark(mode, isSystemInDarkTheme())`, pasándolo a
  `NeverLateTheme(darkTheme = darkTheme) { AppNavHost(...) }`. **`NeverLateTheme` no cambia su firma**:
  ya acepta `darkTheme`; solo cambia quién le da valor. `dynamicColor` se mantiene como está (Material
  You en Android 12+ sigue funcionando: la preferencia solo decide claro vs. oscuro, y `Theme.kt` ya
  elige `dynamicDark`/`dynamicLight` según ese booleano).
- **Navegación:** añadir `const val SETTINGS = "settings"` a `Routes` y un `composable(Routes.SETTINGS)`
  en `AppNavHost` que renderice `SettingsRoute(repository = repository, onBack = { navController.popBackStack() })`.
  Home recibe un `onSettingsClick` nuevo que navega a `Routes.SETTINGS` (mismo patrón que
  `onArticlesClick`/`onTasksClick`).
- **Pantalla de Ajustes:** `ui/settings/SettingsScreen.kt` (stateless) + `SettingsRoute` (stateful) +
  `SettingsViewModel` con `StateFlow` que expone el `ThemeMode` actual (derivado de
  `repository.userPreferences`) y un `onThemeModeSelected(mode)` que llama a `saveThemeMode` en el
  `viewModelScope`. Se construye vía `AppViewModelFactory` (añadir la rama para `SettingsViewModel`,
  reutilizando `userPreferencesRepository`). UI: `Scaffold` + `TopAppBar` con flecha atrás y una lista
  de tres filas con `RadioButton` (selección única), estilo Material 3.
- **Punto de entrada en Home:** una acción en la `TopAppBar` de `HomeScreen` (icono de ajustes con
  `contentDescription`) que dispara `onSettingsClick`. (Alternativa/added: una fila más en la lista de
  Home; a decidir en implementación, pero la `TopAppBar` es lo más idiomático para "Ajustes").
- **Sin permisos ni dependencias nuevas:** DataStore, Navigation Compose y Material 3 ya están en el
  proyecto (features 02–06). No se toca el manifest.

---

## Out of Scope

Esta feature **no** incluye:

- **Otros ajustes** más allá del tema (nombre editable, notificaciones, idioma, etc.). La pantalla de
  Ajustes nace con **un solo ajuste**: el tema. El **idioma / i18n** es explícitamente la **feature 08**
  (`docs/prompts/08-i18n.md`).
- **Colores/paleta personalizados por el usuario**, editor de temas, o un toggle separado para el
  **color dinámico (Material You)**. `dynamicColor` se mantiene con su comportamiento actual; no se
  expone su control en esta feature.
- **Tema por pantalla o por componente.** El tema es **global** para toda la app; no hay overrides
  locales.
- **Persistencia remota / sincronización del ajuste entre dispositivos** → relacionado con la
  **feature 11** (`docs/prompts/11-bbdd-remota.md`). Todo es local.
- **Tematizado del widget (feature 05) o de la notificación de pantalla de bloqueo (feature 06)** más
  allá de lo que ya hacen por su cuenta. Esas superficies viven en otros procesos/estilos del sistema;
  aplicar esta preferencia a ellas queda fuera de esta feature.
- **Animación/transición de crossfade entre temas.** El cambio de esquema de color se aplica por
  recomposición estándar de Compose; no se implementa una transición animada a medida.
- **Migración de datos de DataStore.** Se añade una clave nueva con valor por defecto; no hay migración
  de esquema que hacer.

---

## Dependencies

- **Capa de preferencias existente (dependencia dura):** requiere la feature 02 ya en `master`:
  `UserPreferencesRepository` (interfaz), `DataStoreUserPreferencesRepository` (implementación),
  `UserPreferences` (data class) y el `DataStore` `user_prefs`. Esta feature **amplía** esos tipos; no
  los reimplementa.
- **Navegación existente:** `AppNavHost` + `Routes` (feature 02/03) para añadir la ruta `settings`, y
  `HomeScreen`/`HomeRoute` para el punto de entrada. `AppViewModelFactory` para construir el
  `SettingsViewModel`.
- **Tema existente:** `NeverLateTheme` en `ui/theme/Theme.kt`, que **ya** acepta el parámetro
  `darkTheme` (hoy con valor por defecto `isSystemInDarkTheme()`). Esta feature pasa a **dárselo
  explícitamente** desde `MainActivity`. No requiere cambiar la firma de `NeverLateTheme`.
- **Sin dependencias nuevas en `gradle/libs.versions.toml`:** DataStore (`androidx.datastore:datastore-preferences`),
  Navigation Compose, Lifecycle (`collectAsStateWithLifecycle`) y Material 3 ya están declarados. Si
  la implementación descubriera que falta alguna (p. ej. un icono de Material que exija
  `material-icons-extended`), **parar y reportar** antes de añadirla (Execution Policy), documentando
  la entrada en el catálogo con `version.ref`.
- **Sin cambios de permisos ni de manifest:** la feature es puramente de UI + preferencias locales.
- **Tooling/build:** debe compilar con `./gradlew :app:assembleDebug` y pasar
  `./gradlew :app:testDebugUnitTest` (wrapper). Si falta algún paquete del SDK, **parar y reportar**
  (Execution Policy).

---

## Deliverables / Documentation (obligatorio antes de commitear)

- **Lección tutorial (Tutorial Methodology):** `tutorial/07-ajustes.md` (español), progresiva sobre
  01–06. Debe cubrir: repaso de DataStore y **ampliación** de `UserPreferencesRepository` con una clave
  nueva; persistir un `enum` como `String` con parse tolerante; el `enum ThemeMode` y su traducción a
  `darkTheme`; cómo se conecta con `NeverLateTheme`; leer el `Flow` de preferencias **en lo alto de la
  composición** con `collectAsStateWithLifecycle` y el valor inicial para evitar el parpadeo; y el
  patrón route/screen/`ViewModel` + `StateFlow` de la pantalla de Ajustes.
- **Strings:** título de Ajustes, etiquetas de las tres opciones de tema y `contentDescription` del
  acceso desde Home, en `res/values/strings.xml` (sin texto visible hardcodeado).
- **`CLAUDE.md`:** actualizar la sección **Structure** para reflejar el nuevo paquete `ui/settings/`
  (y, si se crea, el fichero del `enum ThemeMode`). No hay permisos ni dependencias nuevas que reflejar
  (confirmarlo en el `/doc-check`).

---

## Testing notes

> Siguiendo el patrón del proyecto: **mover la lógica testeable a funciones puras JVM** y probar el
> repositorio contra un **fake en memoria** (DataStore real necesita runtime de Android). La UI Compose
> se verifica manualmente y, opcionalmente, con un test de Compose ligero.

- **Tests unitarios JVM (`app/src/test/.../`), sin emulador — el grueso:**
  - **Traducción de tema** `themeModeToDark(mode, systemInDark)`: los tres modos × ambos estados del
    sistema (`LIGHT→false`, `DARK→true`, `SYSTEM→systemInDark` en ambos valores).
  - **Parseo tolerante** `String? -> ThemeMode`: `"LIGHT"/"DARK"/"SYSTEM"` → su enum; `null` y una
    cadena desconocida → `SYSTEM` (por defecto), sin excepción.
  - **Repositorio contra fake:** ampliar el fake de `UserPreferencesRepository` (feature 02) con
    `themeMode`/`saveThemeMode`; verificar que guardar un modo se refleja en el siguiente `UserPreferences`
    emitido y que **no** altera `name`/`onboarded`.
  - **`SettingsViewModel`:** con un fake, el `StateFlow` expone el `themeMode` inicial y, tras
    `onThemeModeSelected(DARK)`, el estado (y el `saveThemeMode` del fake) reflejan el cambio.
- **Lo que NO se testea en unidad (documentarlo):** la aplicación real del esquema de color por
  `NeverLateTheme`, el arranque sin parpadeo y la navegación Home↔Ajustes se verifican **manualmente en
  el emulador** (cambiar a oscuro y comprobar Home y otras pantallas; matar y reabrir la app; cambiar el
  modo del sistema con "seguir sistema" activo). Opcionalmente, un test de Compose (`ComposeTestRule`)
  puede comprobar que al pulsar una opción se invoca el callback correspondiente.
- **Comandos:**

```bash
# Tests unitarios (JVM, sin emulador)
./gradlew :app:testDebugUnitTest

# Build del APK de depuración
./gradlew :app:assembleDebug
```

---

## Risks

- **Parpadeo de tema en el arranque (*startup flash*):** DataStore lee de disco de forma asíncrona; si
  se compone con un tema por defecto y luego llega el valor persistido, puede verse un flash. *Mitigación:*
  elegir un `initialValue` sensato (`SYSTEM`) y aceptar que, para install ya configurados, el primer
  frame puede diferir un instante; documentarlo en la lección igual que se hizo con el arranque de
  `AppNavHost`. (Alternativa fuera de alcance: bloquear el primer frame hasta tener el valor, que
  perjudica el arranque.)
- **Ampliar `UserPreferences` sin romper el onboarding:** añadir un campo con **valor por defecto** y
  una **clave nueva** es retrocompatible, pero hay que asegurar que el `map { ... }` sigue leyendo
  `name`/`onboarded` igual. *Mitigación:* default `SYSTEM`, clave independiente, y los tests de
  onboarding existentes deben seguir en verde.
- **Confusión "seguir sistema" vs. guardar claro/oscuro:** si por error se guardara un booleano
  claro/oscuro en vez del modo `SYSTEM`, "seguir sistema" dejaría de reaccionar a los cambios del
  dispositivo. *Mitigación:* persistir el **`ThemeMode`** (tres estados), no un booleano; el booleano
  `darkTheme` se **deriva** en cada composición.
- **Interacción con `dynamicColor` (Material You):** en Android 12+ el esquema es dinámico; la
  preferencia solo debe decidir claro vs. oscuro, no romper el color dinámico. *Mitigación:* dejar
  `dynamicColor` intacto en `NeverLateTheme`; la preferencia entra únicamente por `darkTheme`.
- **Superficies fuera de la `Activity` (widget/notificación):** la persona podría esperar que el tema
  elegido afecte también al widget (feature 05) o a la notificación (feature 06). *Mitigación:* declarar
  explícitamente en Out of Scope que esta feature aplica el tema **dentro de la app**; esas superficies
  siguen su propio estilo del sistema.
- **Sobrecarga didáctica baja pero real:** la feature toca DataStore + enum + tematizado + navegación +
  `ViewModel`. *Mitigación:* todo son **refuerzos** de conceptos de 02–06 más un único concepto nuevo
  (tematizado por preferencia); la lección 07 se apoya en lo ya visto y mantiene la UI mínima.

---

## Siguiente paso

Por favor, **revisa y aprueba** esta especificación antes de comenzar la implementación. Una vez
aprobada, el flujo continúa (según el **Mandatory Workflow** de `CLAUDE.md`): crear la rama
`feature/settings-dark-mode`, implementar con `mobile-engineer` (ampliando `UserPreferencesRepository`
y cableando `NeverLateTheme` desde `MainActivity`, sin tocar la firma del tema), tests con
`qa-engineer` (funciones puras de traducción/parseo + repositorio/`ViewModel` contra fake) y la lección
`tutorial/07-ajustes.md` antes de commitear.
