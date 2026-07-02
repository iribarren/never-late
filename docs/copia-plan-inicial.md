Ready for review
Select text to add comments on the plan
Plan — "Never Late Again": app Android (Kotlin) como tutorial progresivo
Context
Queremos construir una app Android nativa en Kotlin + Jetpack Compose que ayude a personas con TDA/TDAH a gestionar su tiempo y sus tareas. El objetivo doble es: (1) una app usable, y (2) que cada funcionalidad sea una lección de tutorial que introduzca conceptos nuevos de Kotlin y Android, de lo más básico a lo más avanzado. Esta naturaleza "tutorial" debe quedar fijada en la configuración del proyecto para que se respete en cada feature futura, no solo hoy.

El repo está prácticamente vacío: solo el CLAUDE.md plantilla (con placeholders <...>), carpetas docs/ y tutorial/ vacías, y no es un repositorio git todavía. El toolchain local ya está disponible: JDK 21, Android SDK en ~/Android/Sdk (plataforma API 36, build-tools 36/37, adb, emulador con AVD Nexus_5X_API_29), cmdline-tools/latest (con sdkmanager y avdmanager en ~/Android/Sdk/cmdline-tools/latest/bin) y Android Studio en ~/android-studio (con su propio JBR en ~/android-studio/jbr). Compilaremos con el Gradle wrapper desde la CLI (reproducible y buen material de tutorial) y podremos probar la app tanto por adb en el emulador como abriéndola en Android Studio (opción visual más sencilla). Con sdkmanager podemos aceptar licencias e instalar cualquier paquete que falte nosotros mismos.

Esta primera tarea (entorno + CLAUDE.md) es obligatoria y va primero. El resto de funcionalidades se ordenan para un aprendizaje progresivo y se implementarán después, cada una por el flujo /feature (spec → aprobación → rama → implementación → test → lección).

Decisiones ya confirmadas con el usuario:

UI: Jetpack Compose (estándar moderno).
Idioma de las lecciones (tutorial/*.md): español. El código, comentarios y nombres siguen en inglés (convención del proyecto en CLAUDE.md).
Tarea 1 (AHORA — obligatoria): Entorno local + scaffold + CLAUDE.md
Objetivo: dejar un proyecto Android mínimo que compila y arranca en el emulador, con la documentación del repo adaptada y la metodología de tutorial fijada.

1. Inicializar git y ramas
git init, rama por defecto master, primer commit con la documentación.
Los archivos de código fuente se crean en una rama feature/project-scaffold (el hook .claude/hooks/check-branch.sh bloquea editar código en master; docs/config sí se permiten en master). CLAUDE.md, .gitignore y lecciones se pueden commitear en master.
2. Scaffold mínimo Compose (rama feature/project-scaffold)
Estructura Gradle estándar de un módulo app:

Raíz: settings.gradle.kts, build.gradle.kts, gradle.properties, catálogo de versiones gradle/libs.versions.toml.
Gradle wrapper (gradlew, gradle/wrapper/gradle-wrapper.properties + jar), versión de Gradle compatible con AGP 8.7+ y JDK 21.
Plugins: AGP 8.7+, Kotlin 2.x, org.jetbrains.kotlin.plugin.compose (compilador Compose integrado en Kotlin 2.0+), Compose BOM, Material 3.
Módulo app/:
build.gradle.kts: namespace = "com.neverlate", applicationId = "com.neverlate", compileSdk 36, targetSdk 36, minSdk 24 (corre en el emulador API 29), jvmTarget = 21, buildFeatures { compose = true }.
src/main/AndroidManifest.xml, MainActivity.kt (ComponentActivity + setContent).
Tema Compose: ui/theme/Color.kt, Theme.kt, Type.kt.
Pantalla "Hola Mundo" (el primer ejercicio del tutorial): un @Composable que muestra un saludo centrado con Material 3, de forma que al lanzar la app se vea algo en pantalla. Es la prueba visual de que toda la cadena (Gradle → Kotlin → Compose → emulador) funciona.
res/values/strings.xml, themes.xml, icono de launcher.
local.properties con sdk.dir=/home/aritz/Android/Sdk (en .gitignore).
.gitignore para Android/Gradle (build/, .gradle/, local.properties, *.iml, .idea/).
Las versiones exactas se fijan en el catálogo libs.versions.toml durante la implementación, comprobando compatibilidad con build-tools 36/37 y JDK 21.

3. Validar el toolchain
./gradlew assembleDebug compila sin errores.
Arrancar emulador Nexus_5X_API_29, ./gradlew installDebug, lanzar la app y ver el "Hola Mundo" en pantalla. (Detalle en Verificación.)
4. Reescribir CLAUDE.md (sustituir todos los <...>)
Ficheros: CLAUDE.md

Overview: afinar la descripción actual.
Structure: describir el layout real (módulo app, paquetes ui/theme, ui/screens, data, MainActivity, recursos, Gradle + version catalog).
Development: comandos reales — arrancar emulador, ./gradlew assembleDebug, ./gradlew installDebug, ./gradlew test/connectedAndroidTest; rellenar la tabla "Android emulator" con el comando del AVD.
Key Conventions: mantener la regla de código en inglés; añadir convenciones Kotlin/Compose.
Eliminar la sección "API Contract" completa: la app es local, sin backend (la guía indica borrarla si no aplica).
Sustituir <main-branch> por master en Mandatory Workflow / Branch Rules.
Añadir sección nueva "Tutorial Methodology" (esto es la "configuración fija" que pide el usuario): toda feature futura debe (a) introducir conceptos nuevos y progresivos de Kotlin/Android, (b) entregar una lección en español en tutorial/NN-tema.md que explique los conceptos y el código, (c) empezar por lo básico y subir de nivel. El flujo /feature debe incluir esta lección como parte del "Documentation Update" antes de commitear.
5. Fijar la mandato del tutorial en memoria
Guardar una memoria project en el store de memoria del usuario para que el mandato "cada feature es una lección progresiva de Kotlin/Android, con doc en español en tutorial/" persista entre sesiones (además de estar en CLAUDE.md).
6. Primera lección — "Hola Mundo"
tutorial/01-entorno-y-hola-mundo.md (español): estructura de un proyecto Android con Gradle, qué es el wrapper y el version catalog, AndroidManifest, Activity, y el primer @Composable (la pantalla "Hola Mundo"); cómo compilar, ejecutar en el emulador y ver el saludo en pantalla. Es el clásico primer paso del tutorial.
7. Dejar preparados los prompts de arranque de cada feature
Crear un fichero por feature en docs/prompts/ (continuando la numeración de 01-inicial.md), cada uno autocontenido para poder pegarlo en una sesión nueva de Claude sin depender del contexto actual. Ver detalle y numeración en la sección "Cómo añadir las siguientes features". Así cada feature futura arranca copiando su prompt.
Roadmap del resto de funcionalidades (a implementar después, por /feature)
Orden pensado para progresión de conceptos (de básico a avanzado). Cada hito = 1 lección tutorial/NN-*.md.

Onboarding + Home (primer arranque) — features 2 y 3, acopladas. Pantalla de alta de datos la 1ª vez y pantalla de inicio con opciones para el usuario ya registrado; routing entre ambas según si ya se hizo el onboarding. Conceptos: estado en Compose (remember/mutableStateOf), TextField/Button, Scaffold, ViewModel + StateFlow, Navigation Compose, DataStore (Preferences) para guardar nombre y flag "onboarded", tema Material 3.

Listado de artículos de técnicas de gestión del tiempo — feature 5. Lista de solo lectura con pantalla de detalle, contenido empaquetado (JSON/assets). Conceptos: LazyColumn, data class, listas, navegación lista→detalle, repositorio simple, lectura de assets. (Intro suave a listas antes de meter base de datos.)

Tareas + contador de tiempo — feature 4 (núcleo de la app). Crear tareas con un temporizador/cuenta atrás. Conceptos: Room (entidades, DAO, Flow), CRUD, LazyColumn editable, coroutines y temporizador (countdown), estado más complejo. Salto grande: persistencia estructurada.

Widget de tareas pendientes con tiempo restante — feature 6. Conceptos: App Widgets con Glance (Compose para widgets), actualización en segundo plano con WorkManager, compartir el repositorio Room con el widget.

Tareas en la pantalla de bloqueo — feature 7. Conceptos: notificaciones, visibilidad en lockscreen, permiso POST_NOTIFICATIONS, posible foreground service, AlarmManager/WorkManager para recordatorios.

Extras de tutorial, ordenados por complejidad ascendente (se intercalan como lecciones a lo largo del roadmap):

Ajustes + modo oscuro (DataStore + Material 3) — baja. Pantalla de ajustes y toggle de tema claro/oscuro persistido. Refuerza DataStore y tematizado.
i18n / localización — baja. Extraer todos los textos a res/values/strings.xml y añadir values-en/ (u otros locales), formateo de números/fechas por locale. Conceptos: recursos por configuración, Locale, buenas prácticas de strings.
Recordatorios / notificaciones locales — media. Avisos antes del vencimiento de una tarea. Conceptos: notificaciones, permiso POST_NOTIFICATIONS, AlarmManager/WorkManager. (Encaja justo antes del widget y el lockscreen.)
Artículos desde una API/Web — media-alta. Reemplazar el contenido empaquetado del hito de artículos por datos remotos. Conceptos: Retrofit/Ktor, suspend, DTO + mapeo, estados de carga/error, caché offline con Room. Introduce networking.
Todos los datos en una BBDD remota (backend + sync) — alta (la más compleja). Mover la persistencia a un backend con sincronización. Conceptos: diseño de API, auth, sync offline-first, manejo de conflictos. Aquí se re-añadiría la sección "API Contract" a CLAUDE.md (que se elimina ahora por ser app local) y probablemente entren los agentes backend-engineer/devops-security-engineer.
Transversal: lecciones de testing (unit test de ViewModel/repositorio + UI test Compose) vía qa-engineer, tejidas en cada hito, no como un paso lineal.

Cómo añadir las siguientes features (desde sesiones nuevas)
Cada feature futura se arranca en una sesión nueva de Claude Code (sin el contexto de esta), así que dejamos el disparador preparado y autocontenido.

Flujo por feature (lo hace el /feature, según el Mandatory Workflow de CLAUDE.md):

Abrir sesión nueva en el proyecto y pegar el prompt de docs/prompts/NN-*.md (o escribir /feature <nombre>).
project-manager-docs redacta la spec en docs/specs/YYYY-MM-DD-*.md → aprobación del usuario.
Rama feature/<nombre> desde master.
Implementación con mobile-engineer (Kotlin/Compose), tests con qa-engineer.
Lección tutorial/NN-*.md en español (obligatorio por "Tutorial Methodology") + doc-check.
Commit en la rama y PR con /pr.
Prompts a dejar en docs/prompts/ (autocontenidos; empiezan por "Lee CLAUDE.md y la sección Tutorial Methodology, luego implementa … siguiendo el flujo /feature"). Numeración siguiendo 01-inicial.md, en el orden del roadmap (los extras se intercalan cuando se decidan):

02-onboarding-home.md — Onboarding + Home (state, ViewModel, Navigation, DataStore).
03-articulos-lista.md — Lista de artículos local (LazyColumn, detalle, assets).
04-tareas-contador.md — Tareas + contador (Room, coroutines, countdown).
05-widget-tareas.md — Widget con Glance + WorkManager.
06-tareas-lockscreen.md — Tareas en pantalla de bloqueo (notificaciones, permisos).
07-ajustes-modo-oscuro.md — Ajustes + modo oscuro (extra, baja complejidad).
08-i18n.md — Localización / i18n (extra, baja).
09-recordatorios.md — Recordatorios/notificaciones locales (extra, media).
10-articulos-api.md — Artículos desde API/Web (extra, media-alta).
11-bbdd-remota.md — Datos en BBDD remota + sync (extra, alta).
Cada fichero es una plantilla corta; su spec detallada la genera project-manager-docs al lanzar la feature. Cada prompt indica el nombre de rama sugerido y los conceptos nuevos que debe enseñar la lección, para mantener la progresión.

Verification (Tarea 1)
Compila: ./gradlew assembleDebug termina en BUILD SUCCESSFUL y genera app/build/outputs/apk/debug/app-debug.apk.
Arranca el emulador: ~/Android/Sdk/emulator/emulator -avd Nexus_5X_API_29 & y esperar a adb wait-for-device.
Instala y lanza: ./gradlew installDebug (o adb install -r <apk>), luego adb shell am start -n com.neverlate/.MainActivity. Alternativa visual: abrir el proyecto en Android Studio (~/android-studio/bin/studio.sh) y ejecutar con el botón Run.
Comprobar visualmente: el "Hola Mundo" aparece en el emulador; captura con adb exec-out screencap -p > tutorial/assets/01-hola-mundo.png (para ilustrar la lección).
Docs: CLAUDE.md sin ningún placeholder <...>, sección "API Contract" eliminada, <main-branch>→main, y sección "Tutorial Methodology" presente. tutorial/01-*.md creada.
Todo se ejecuta dentro del árbol del proyecto (Execution Policy); el caché de Gradle en ~/.gradle es el comportamiento estándar del wrapper, no un workaround de scratch dir.
Notas / riesgos
El scaffold se escribe a mano (sin wizard): reproducible y buen material de tutorial. Como alternativa, existe Android Studio (~/android-studio) para abrir/ejecutar visualmente.
Riesgo de licencias/paquetes: mitigado. Con cmdline-tools/latest disponible, si al compilar falta una licencia o paquete, lo resolvemos con ~/Android/Sdk/cmdline-tools/latest/bin/sdkmanager --licenses / sdkmanager <paquete> (todo dentro del SDK, no scratch dir).
Solo está instalada la plataforma API 36; el AVD es API 29. minSdk 24 / compileSdk 36 cubre ambos.
El hook check-branch.sh bloquea código fuente en main: por eso el código va en feature/project-scaffold.