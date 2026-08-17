# Feature — Privacidad de la notificación de bloqueo y poder apagarla

Lee `CLAUDE.md` (**Tutorial Track (optional)** + **Mandatory Workflow** + **Design in the Workflow**)
y las lecciones previas (en especial la 06: la notificación de pantalla de bloqueo, su servicio en
primer plano y la versión pública redactada que dejó construida; y la 07: la pantalla de ajustes y las
preferencias en el DataStore `user_prefs`). Implementa **"que la persona decida qué se ve de sus
tareas en la pantalla de bloqueo, y si quiere ver la notificación siquiera"** siguiendo el flujo
`/feature`.

> **Es la promesa incumplida más clara del repo.** La feature 06 dejó esto **explícitamente diferido a
> la 07** — a petición de la propia persona usuaria — y la 07 se envió sin recogerlo. El resultado es
> que hoy los **títulos de las tareas se muestran en claro en la pantalla de bloqueo**, sin forma de
> ocultarlos ni de apagar la notificación. Y lo más llamativo: la versión redactada **ya está escrita
> y cableada** (`setPublicVersion` con un resumen tipo "3 tareas pendientes"), inactiva solo porque la
> visibilidad está fijada a `VISIBILITY_PUBLIC`. Hay un comentario en el código que dice literalmente
> que cambiar esa línea es todo lo que la feature 07 tendría que hacer.

## Qué construir

- Un ajuste para **ocultar los títulos en la pantalla de bloqueo**: la notificación sigue ahí, pero
  desbloqueando se ve el detalle y bloqueado solo el resumen ("3 tareas pendientes").
- Un ajuste para **apagar la notificación persistente** por completo, para quien la vive como ruido en
  vez de como ayuda.
- Ambos viven en Ajustes, junto a los de tema y recordatorios que ya existen, con el mismo idioma
  visual.
- Cambiar cualquiera de los dos surte efecto **al momento**, sin reiniciar la app.

## Tutorial

Antes de escribir el spec, el flujo `/feature` **debe preguntar con `AskUserQuestion`** si esta
feature lleva lección en español (*Sí, con lección / No / Decidir al final*), y la respuesta se anota
en el campo `Tutorial:` del spec.

**Recomendación: Sí.** La 06 enseñó a *poner* una notificación permanente; esta enseña a **quitarla**,
que resulta ser mucho más delicado:

- **Visibilidad y versión pública:** cómo Android decide qué se ve con el móvil bloqueado, por qué
  `setPublicVersion` **solo surte efecto** bajo `VISIBILITY_PRIVATE`/`SECRET`, y por qué diseñar la
  versión redactada *antes* de necesitarla fue una buena decisión de la 06.
- **El ciclo de vida de un servicio en primer plano:** por qué hay que llamar a `startForeground`
  en pocos segundos o el sistema lanza `ForegroundServiceDidNotStartInTimeException`, y el truco que
  el código ya usa — publicar un marcador y retirarlo en el mismo paso de corrutina, sin suspensión
  intermedia, para que nunca llegue a dibujarse.
- **Arrancar servicios desde segundo plano en Android 12+:** `ForegroundServiceStartNotAllowedException`
  y por qué "no lo arranques" y "arráncalo y que se apague solo" son dos diseños con fallos distintos.
- **Privacidad como decisión de producto:** una app de tareas sabe cosas ("Llamar al oncólogo") que su
  dueño puede no querer en la pantalla de bloqueo de un móvil que deja encima de la mesa.

## Notas

- Rama sugerida: `feature/notification-privacy`.
- **Lo que ya está hecho y no hay que rehacer.** En
  [`TasksNotificationHelper.kt`](../../app/src/main/java/com/neverlate/ui/notification/TasksNotificationHelper.kt):
  `buildPublicVersion` existe, usa el plural `notification_public_summary` y está pasado por
  `setPublicVersion`; lo único que lo desactiva es el `.setVisibility(NotificationCompat.VISIBILITY_PUBLIC)`
  de la línea de al lado, con un comentario que documenta la deuda. **Apagar la notificación con el
  servicio vivo también está resuelto ya**:
  [`TasksNotificationService.kt`](../../app/src/main/java/com/neverlate/ui/notification/TasksNotificationService.kt)
  tiene una rama que publica un marcador, llama a `stopForeground(STOP_FOREGROUND_REMOVE)` y
  `stopSelf()`. La feature es sobre todo **exponer** decisiones ya tomadas, no inventarlas.
- **El riesgo real que el spec debe resolver.** `TasksNotificationHelper` es un `object` sin estado que
  solo recibe `(Context, model)`: **no tiene acceso al DataStore**. La preferencia se lee con `suspend`
  y por tanto solo puede leerse en `refreshNotification`, que ya es `suspend`, y bajarse como
  parámetro. Pero el servicio se arranca desde **cuatro** sitios, y tres de ellos
  (`TaskSurfacesRefreshingRepository`, `TaskSurfacesRefreshWorker`, y el efecto de permisos) llaman a
  `startForegroundService` desde contexto de fondo, donde Android 12+ puede lanzar
  `ForegroundServiceStartNotAllowedException`. El spec decide: **"apagada" significa no arrancar el
  servicio** (requiere una lectura cacheada/síncrona en esos puntos) o **"arráncalo y que se
  autodestruya"** (la forma que ya existe, más segura, a costa de un arranque inútil). Elegir y
  justificar.
- **Son dos preferencias, no una.** "Ocultar títulos" y "apagarla del todo" tienen fallos distintos y
  públicos distintos. Colapsarlas en un switch es una decisión de producto legítima **si se argumenta**;
  hacerlo por comodidad de implementación, no. El spec debe pronunciarse.
- **Extiende, no dupliques — el patrón de preferencia ya tiene precedente exacto.** Copia el de
  `dynamicColor` (feature 16) en
  [`UserPreferencesRepository.kt`](../../app/src/main/java/com/neverlate/data/UserPreferencesRepository.kt):
  campo en `UserPreferences` con valor por defecto → clave en `Keys` → lectura tolerante en el `map` →
  `suspend fun save…` en la interfaz → implementación → campo en `SettingsUiState` + setter en
  [`SettingsViewModel.kt`](../../app/src/main/java/com/neverlate/ui/settings/SettingsViewModel.kt) →
  `Row` + `Switch` dentro de un `SettingsSectionCard` en
  [`SettingsScreen.kt`](../../app/src/main/java/com/neverlate/ui/settings/SettingsScreen.kt).
  **Nunca un segundo DataStore.**
- **Aviso de compilación:** cualquier método nuevo en la interfaz `UserPreferencesRepository` rompe
  **tres fakes de test** (`data/sync/SyncTestDoubles.kt`, `ui/settings/SettingsViewModelTest.kt`,
  `ui/onboarding/OnboardingViewModelTest.kt`). No es un problema, pero que no pille por sorpresa.
- **Valores por defecto: decisión de producto, no técnica.** ¿La app arranca ocultando títulos (más
  privada, menos útil de un vistazo) o mostrándolos (como hoy)? Cambiar el comportamiento por defecto
  afecta a instalaciones existentes. El spec lo decide explícitamente y dice qué pasa al actualizar.
- **Diseño (obligatorio en el spec):** Ajustes gana una sección o dos filas nuevas; hay que decidir si
  van en una tarjeta propia de "Notificaciones" o dentro de la de recordatorios que ya existe.
  Criterios visuales concretos: los switches se alinean con los ya presentes, el texto de apoyo explica
  qué significa cada uno **sin jerga** ("en la pantalla de bloqueo se verá solo cuántas tareas tienes,
  no cuáles"), objetivos ≥48dp, reflow a escala de fuente máxima, y añadir la fila correspondiente a
  `docs/mockups/README.md`.
- **Fuera de alcance:** rediseñar el aspecto de la notificación, añadirle botones de acción, y tocar
  los recordatorios de la feature 09 (que tienen su propio interruptor y su propio canal).
- Sin backend, sin contrato, sin migración de Room (es DataStore), sin dependencia nueva, sin permiso
  nuevo — `POST_NOTIFICATIONS`, `FOREGROUND_SERVICE` y `FOREGROUND_SERVICE_SPECIAL_USE` ya están.
- Ficheros:
  [`TasksNotificationHelper.kt`](../../app/src/main/java/com/neverlate/ui/notification/TasksNotificationHelper.kt),
  [`TasksNotificationService.kt`](../../app/src/main/java/com/neverlate/ui/notification/TasksNotificationService.kt),
  [`UserPreferencesRepository.kt`](../../app/src/main/java/com/neverlate/data/UserPreferencesRepository.kt),
  [`SettingsViewModel.kt`](../../app/src/main/java/com/neverlate/ui/settings/SettingsViewModel.kt),
  [`SettingsScreen.kt`](../../app/src/main/java/com/neverlate/ui/settings/SettingsScreen.kt),
  [`strings.xml`](../../app/src/main/res/values/strings.xml) +
  [`values-en/strings.xml`](../../app/src/main/res/values-en/strings.xml).
- Agentes: `mobile-engineer` (preferencias, visibilidad, ciclo de vida del servicio),
  `qa-engineer` (tests JVM de las preferencias y del modelo de notificación en ambos modos;
  verificación manual **con el móvil realmente bloqueado**, que es el único sitio donde se comprueba
  que la versión redactada es la que se ve, y de que apagarla no deja un servicio zombi).
