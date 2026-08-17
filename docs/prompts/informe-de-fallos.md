# Feature — Enterarse de los fallos: informe de errores y observabilidad

Lee `CLAUDE.md` (**Tutorial Track (optional)** + **Mandatory Workflow** + **Definition of Done**, donde
esto aparece nombrado como hueco de producción con spec propia) y las lecciones previas (en especial la
13: el modo invitado, cuyas tareas viven **solo** en el dispositivo; la 12: el almacén cifrado y la
postura de seguridad del proyecto; y la 11: el backend local en `docker compose`). Implementa **"que un
fallo en el móvil de otra persona deje de ser invisible"** siguiendo el flujo `/feature`.

> **Hoy no hay absolutamente nada.** Ni Crashlytics, ni Sentry, ni un `Thread.setDefaultUncaughtExceptionHandler`,
> ni logging más allá del interceptor de OkHttp que solo se activa en `debug`. `NeverLateApplication`
> está literalmente vacío —su propio KDoc dice que existe solo para llevar la anotación de Hilt— y es
> el punto natural donde esto se instala. Si la app peta en el móvil de alguien, nadie se entera nunca.

> **Esta feature es mitad técnica y mitad decisión de privacidad.** No se puede resolver una sin la
> otra, y el spec debe tratarlas juntas.

## Qué construir

- Los **crashes se reportan** a algún sitio donde se puedan leer, agrupar y priorizar.
- La persona usuaria **decide** si quiere enviarlos, con un texto que explique qué se manda sin jerga.
- Los **títulos de tarea nunca salen del dispositivo** en un informe de fallo, ni en la traza, ni en
  las migas de navegación, ni en los extras de una notificación capturada.
- Queda documentado cómo se leen los informes y quién puede hacerlo.

## Tutorial

Antes de escribir el spec, el flujo `/feature` **debe preguntar con `AskUserQuestion`** si esta
feature lleva lección en español (*Sí, con lección / No / Decidir al final*), y la respuesta se anota
en el campo `Tutorial:` del spec.

**Recomendación: Sí.** Es el primer trabajo del proyecto donde la decisión técnica está **subordinada**
a una de privacidad, y eso vale una lección:

- **Qué contiene realmente una traza de pila** y por qué no es "solo código": nombres de clase, valores
  en el ámbito, y con `isMinifyEnabled = false` todo ello legible.
- **Ofuscación y símbolos:** qué hace R8, por qué una traza ofuscada necesita subir un fichero de
  mapping, y por qué eso ata esta feature a una build de release y a una CI que **todavía no existen**.
- **Consentimiento: opt-in frente a opt-out**, dónde se pregunta, y por qué en una app con modo
  invitado —donde los datos **nunca** han salido del dispositivo— la respuesta por defecto no es obvia.
- **Autoalojar frente a SaaS**, con el matiz de que este proyecto ya levanta su propio `docker compose`.

## Notas

- Rama sugerida: `feature/crash-reporting`.
- **La decisión que lo condiciona todo: proveedor y privacidad son la misma pregunta.**
  - **Sentry o GlitchTip autoalojados** — se levantan junto al `docker-compose.yml` que el backend ya
    tiene, el DSN entra por `local.properties` siguiendo el **mismo precedente** que
    `neverlate.backendBaseUrl` (fichero git-ignorado → `buildConfigField`), y los datos no salen de
    infraestructura propia. GlitchTip habla el protocolo de Sentry, así que el SDK de Android es el
    mismo. **Encaja con la postura del proyecto y es la recomendación de partida.**
  - **Firebase Crashlytics** — es SaaS sin opción autoalojada, exige el plugin `com.google.gms` y un
    `google-services.json` **rastreado en git**, y arrastra Play Services, de lo que el proyecto hoy
    no depende en absoluto. El spec puede elegirlo, pero tiene que argumentar contra los tres puntos.
  - **Un `Thread.setDefaultUncaughtExceptionHandler` propio** — cero terceros, cero dependencias, pero
    sin agrupación, sin desofuscación y sin nada que leer si el móvil no vuelve a conectarse. Vale
    como suelo mínimo; conviene decir por qué se descarta si se descarta.
- **El cruce incómodo que el spec debe resolver, no esquivar:** `release { isMinifyEnabled = false }`.
  Sin R8 no hay mapping que subir —lo cual simplifica— pero las trazas van con nombres legibles. Y
  activar R8 arrastra una build de release firmada y una subida de mapping que necesitaría la **CI que
  no existe** (`CLAUDE.md` la nombra como hueco aparte). El spec decide si R8 entra o se difiere
  **explícitamente**, y no lo deja implícito.
- **Modo invitado: el caso que hace esto delicado.** Las tareas de una persona invitada viven **solo**
  en Room y nunca han tocado un servidor —hasta el punto de que el proyecto prohíbe las migraciones
  destructivas por eso mismo. Un `Task.title` como "Llamar al oncólogo" puede acabar en una traza o en
  una miga de navegación. El spec debe resolver: (1) opt-in u opt-out y **dónde** se pregunta
  (onboarding u Ajustes); (2) si se reportan los crashes en modo invitado; (3) **saneado** activo de
  títulos de tarea antes de enviar nada, no confianza en que no aparezcan.
- **Extiende, no dupliques.** El punto de instalación es
  [`NeverLateApplication.kt`](../../app/src/main/java/com/neverlate/NeverLateApplication.kt). El
  secreto (DSN o equivalente) sigue el mecanismo `local.properties` → `buildConfigField` que ya está
  montado en [`app/build.gradle.kts`](../../app/build.gradle.kts). El gate de debug se hace con
  `BuildConfig.DEBUG`, igual que el interceptor de logging de OkHttp. El consentimiento va al DataStore
  `user_prefs` con el patrón de
  [`UserPreferencesRepository.kt`](../../app/src/main/java/com/neverlate/data/UserPreferencesRepository.kt)
  — nunca un segundo almacén, y **nunca** el almacén cifrado del Keystore, que es para credenciales.
- **Seguridad (requisito de entrega, no extra):** ningún secreto en ficheros rastreados. Si el
  proveedor elegido exige un fichero de configuración en el repo, eso **es** el argumento en contra, y
  el spec debe enfrentarlo en vez de rodearlo.
- **Diseño (obligatorio en el spec):** hay superficie visible — la pantalla o fila donde se pide el
  consentimiento. Criterios visuales concretos: el texto explica **qué se envía y qué no** en lenguaje
  llano; se puede cambiar de opinión después desde Ajustes; el interruptor sigue el idioma visual de
  los que ya hay; objetivos ≥48dp; reflow a escala de fuente máxima; y todo en `strings.xml` con base
  en español y variante inglesa. Añadir la fila correspondiente a `docs/mockups/README.md` (`—`, fuera
  del maquetado maestro).
- **Fuera de alcance:** analítica de producto y métricas de uso (es otra cosa y otra conversación de
  privacidad), la CI, la build de release firmada, y el rendimiento/trazado (APM).
- Sin migración de Room. Sin contrato **salvo** que se elija enviar los informes al propio backend, en
  cuyo caso `docs/api/contract.md` se actualiza **primero**, con cliente y servidor reconciliados.
  Dependencia nueva **sí**, salvo en la opción del manejador propio, y siempre por el catálogo de
  versiones. Sin permiso nuevo: `INTERNET` y `ACCESS_NETWORK_STATE` ya están declarados.
- Ficheros:
  [`NeverLateApplication.kt`](../../app/src/main/java/com/neverlate/NeverLateApplication.kt),
  [`app/build.gradle.kts`](../../app/build.gradle.kts),
  [`libs.versions.toml`](../../gradle/libs.versions.toml),
  [`UserPreferencesRepository.kt`](../../app/src/main/java/com/neverlate/data/UserPreferencesRepository.kt),
  [`SettingsScreen.kt`](../../app/src/main/java/com/neverlate/ui/settings/SettingsScreen.kt) +
  [`SettingsViewModel.kt`](../../app/src/main/java/com/neverlate/ui/settings/SettingsViewModel.kt),
  [`strings.xml`](../../app/src/main/res/values/strings.xml) +
  [`values-en/strings.xml`](../../app/src/main/res/values-en/strings.xml), posiblemente
  `backend/docker-compose.yml`, y `CLAUDE.md` (para tachar este hueco de la lista de producción).
- Agentes: `devops-security-engineer` (elección de proveedor, secretos, postura de privacidad y
  saneado), `mobile-engineer` (instalación, consentimiento y gating), `qa-engineer` (test JVM de que
  con el consentimiento desactivado no se inicializa nada, y de que el saneado elimina los títulos;
  verificación manual provocando un crash real y comprobando qué llega **exactamente** al panel).
