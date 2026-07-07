# Lección 01 — Entorno de desarrollo y "Hola Mundo"

> Objetivo: entender cómo está montado un proyecto Android moderno con Kotlin y Jetpack
> Compose, compilarlo y verlo funcionando en el emulador. Es el clásico primer paso.

## Conceptos que aprendes aquí

- Qué es un proyecto **[Gradle](https://developer.android.com/build)** y por qué se compila con el
  **wrapper** (`./gradlew`).
- El **[catálogo de versiones](https://developer.android.com/build/migrate-to-catalogs)**
  (`gradle/libs.versions.toml`) como única fuente de versiones.
- La estructura de un módulo de app Android (`app/`).
- El `AndroidManifest.xml` y el concepto de **[Activity](https://developer.android.com/guide/components/activities/intro-activities)**.
- Qué es un **[`@Composable`](https://developer.android.com/develop/ui/compose/mental-model)** y cómo
  Jetpack Compose dibuja la interfaz.
- Cómo compilar, instalar y lanzar la app en el emulador.
- Cómo **depurar en un dispositivo físico** y los comandos de `adb`/Gradle para instalar,
  desinstalar y dejar una instalación limpia.

## 1. Gradle y el wrapper

Android usa **[Gradle](https://developer.android.com/build)** como sistema de construcción (build
system): compila el código, empaqueta recursos y genera el APK. No instalamos Gradle a mano: el
proyecto incluye el **wrapper**
(`gradlew`, `gradlew.bat` y `gradle/wrapper/`). La primera vez que ejecutas `./gradlew`, éste
descarga la versión exacta de Gradle indicada en
`gradle/wrapper/gradle-wrapper.properties` (aquí, Gradle 8.13). Así todo el mundo construye con
la misma versión.

Ficheros de construcción principales:

- `settings.gradle.kts` — declara los módulos del proyecto (`:app`) y de dónde bajar dependencias.
- `build.gradle.kts` (raíz) — declara los **plugins** con `apply false` (se aplican en el módulo).
- `app/build.gradle.kts` — configuración del módulo de app: SDK, dependencias, Compose.
- `gradle/libs.versions.toml` — el **catálogo de versiones**: aquí viven TODAS las versiones
  (AGP, Kotlin, Compose…). En los `build.gradle.kts` se referencian como `libs.<algo>`; nunca se
  escribe un número de versión suelto.

## 2. SDK y `local.properties`

El **Android SDK** (herramientas, plataformas de compilación, emulador) está en tu máquina en
`~/Android/Sdk`. El proyecto sabe dónde encontrarlo por `local.properties`
(`sdk.dir=/home/aritz/Android/Sdk`). Este fichero es **local** y no se sube a git (está en
`.gitignore`), porque la ruta depende de cada ordenador.

Claves de configuración del SDK en `app/build.gradle.kts`:

- `compileSdk = 36` — versión de Android contra la que compilamos (API 36 / Android 16).
- `targetSdk = 36` — versión para la que la app está probada/optimizada.
- `minSdk = 24` — versión mínima de Android en la que se puede instalar (Android 7.0).

## 3. Estructura del módulo `app`

```
app/
├─ build.gradle.kts
└─ src/
   ├─ main/
   │  ├─ AndroidManifest.xml            # Declara la app y sus componentes
   │  ├─ java/com/neverlate/
   │  │  ├─ MainActivity.kt             # La única Activity, arranca la UI Compose
   │  │  └─ ui/theme/                   # Tema Material 3 (Color, Theme, Type)
   │  └─ res/                           # Recursos: strings, tema XML, icono
   ├─ test/                             # Tests unitarios (JVM)
   └─ androidTest/                      # Tests instrumentados / UI de Compose
```

## 4. El `AndroidManifest.xml` y la Activity

El **[manifest](https://developer.android.com/guide/topics/manifest/manifest-intro)** es la "ficha
de identidad" de la app: nombre, icono, tema y qué componentes tiene. Aquí declara una **Activity**
(`MainActivity`) marcada como *launcher*: es la pantalla que se abre al pulsar el icono de la app.

Una **[Activity](https://developer.android.com/guide/components/activities/intro-activities)** es un
punto de entrada de la app (históricamente, una pantalla). En una app
moderna con Compose lo normal es tener **una sola Activity** que hospeda toda la interfaz.

## 5. El primer `@Composable`

Con [Jetpack Compose](https://developer.android.com/develop/ui/compose/mental-model) la interfaz se
describe con **funciones** anotadas con `@Composable`. En vez
de escribir XML y "inflarlo", declaras cómo debe verse la pantalla y Compose se encarga de
dibujarla y de volver a dibujarla cuando cambian los datos (esto último lo veremos en la
lección de estado).

En `MainActivity.kt`:

```kotlin
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {                 // <- aquí empieza la UI de Compose
            NeverLateTheme {         // <- aplica el tema Material 3 a todo
                Scaffold { padding ->
                    HomeScreen(Modifier.padding(padding))
                }
            }
        }
    }
}

@Composable
fun HomeScreen(modifier: Modifier = Modifier) {
    Column(/* centrado */) {
        Text("Never Late Again")
        Text("Hola Mundo 👋")
    }
}
```

Ideas clave:

- `setContent { }` conecta la Activity con el árbol de Composables.
- `NeverLateTheme` (en `ui/theme/Theme.kt`) provee colores y tipografía Material 3 a toda la UI.
- `Scaffold` es una estructura base de pantalla (deja hueco para barras, FAB, etc.).
- `Column`, `Text` son Composables que ya trae Compose.
- `Modifier` sirve para ajustar tamaño, padding, alineación… de cada Composable.
- `@Preview` permite ver el Composable en Android Studio sin lanzar la app.

## 6. Compilar, instalar y ver el "Hola Mundo"

```bash
# 1. Compilar el APK de debug
./gradlew :app:assembleDebug

# 2. Arrancar el emulador (en otra terminal) y esperar a que esté listo
~/Android/Sdk/emulator/emulator -avd Nexus_5X_API_29 &
adb wait-for-device

# 3. Instalar y lanzar
./gradlew :app:installDebug
adb shell am start -n com.neverlate/.MainActivity
```

Deberías ver en el emulador el texto **"Never Late Again"** y **"Hola Mundo 👋"** centrados.
¡Ese es tu primer programa Android en Kotlin + Compose funcionando de punta a punta
(Gradle → Kotlin → Compose → emulador)!

## 7. Depurar en un dispositivo físico

El emulador está bien para empezar, pero tarde o temprano querrás ver la app en un teléfono real:
es más rápido, y hay cosas (rendimiento real, sensores, notificaciones, cámara) que solo se
prueban bien en hardware. La herramienta que conecta tu ordenador con el dispositivo es
**[`adb`](https://developer.android.com/tools/adb)** (*Android Debug Bridge*), la misma que ya usa
Gradle por debajo para instalar en el emulador.

### 7.1. Preparar el teléfono

1. Abre **Ajustes → Acerca del teléfono** y toca **7 veces** en "Número de compilación" para
   desbloquear las **Opciones de desarrollador**.
2. En **Ajustes → Sistema → Opciones de desarrollador**, activa **Depuración por USB**.
3. Conecta el teléfono por USB. La primera vez, el teléfono muestra un diálogo *"¿Permitir
   depuración USB?"* con la **huella RSA** de tu ordenador: acéptalo (marca "Permitir siempre" si
   es tu equipo).
4. Comprueba que el sistema ve el dispositivo:

   ```bash
   adb devices          # debe listar tu teléfono como "device" (no "unauthorized" ni "offline")
   ```

Guía oficial paso a paso: [Ejecutar apps en un dispositivo de
hardware](https://developer.android.com/studio/run/device).

### 7.2. Depurar desde Android Studio

Con el teléfono conectado y autorizado, aparece en el **desplegable de destinos** (arriba, junto al
botón Run). Elígelo y pulsa:

- **Run** (`Shift+F10`) — compila, instala y lanza la app en el teléfono.
- **Debug** (`Shift+F9`) — igual, pero además **adjunta el depurador**: puedes poner *breakpoints*,
  inspeccionar variables y ver los logs en la ventana **Logcat**.

Dos extras útiles:

- **Depuración inalámbrica** (Wi-Fi, Android 11+): en las Opciones de desarrollador, activa
  *Depuración inalámbrica* y empareja el dispositivo con `adb pair`; así depuras sin cable (el
  teléfono y el PC deben estar en la misma red).
- **`adb reverse`**: reenvía un puerto del PC al teléfono. Lo usaremos más adelante (lección 11)
  para que el móvil llegue a un backend que corre en tu ordenador:

  ```bash
  adb reverse tcp:8080 tcp:8080   # el "localhost:8080" del teléfono apunta al 8080 del PC
  ```

### 7.3. Comandos de instalación y ciclo de vida

Estos comandos funcionan **igual en emulador y en dispositivo físico** (si hay varios conectados,
`adb` pide desambiguar con `-s <serial>`, que ves en `adb devices`):

```bash
adb devices                                     # 1) listar dispositivos/emuladores conectados
./gradlew :app:installDebug                      # 2) compilar + instalar el build de debug
adb install -r app/build/outputs/apk/debug/app-debug.apk   # 3) (re)instalar un APK ya compilado (-r = reemplazar)
adb shell am start -n com.neverlate/.MainActivity          # 4) lanzar la app por su Activity
./gradlew :app:uninstallDebug                    # 5) desinstalar (variante debug, vía Gradle)
adb uninstall com.neverlate                      # 6) desinstalar por package id (equivalente)
adb shell pm clear com.neverlate                 # 7) borrar datos y caché SIN desinstalar
./gradlew :app:uninstallDebug && ./gradlew :app:installDebug   # 8) reinstalación totalmente limpia
adb logcat --pid=$(adb shell pidof -s com.neverlate)           # 9) ver solo los logs de la app
```

**¿`pm clear` o desinstalar?** `pm clear` deja la app instalada pero borra su almacenamiento
(Room, DataStore, ficheros): es la forma rápida de partir "de cero" — muy útil, por ejemplo, tras
un cambio de esquema de base de datos con `fallbackToDestructiveMigration` (lección 04+).
Desinstalar borra **todo**, incluidos los **permisos concedidos** (notificaciones, alarmas
exactas…), así que úsalo cuando quieras probar el flujo de permisos como un usuario nuevo.

## 8. Siguiente paso

En la próxima lección crearemos las primeras pantallas de verdad: **onboarding** (alta de datos
la primera vez) y **home**, aprendiendo **estado en Compose**, **ViewModel**, **navegación** y
**DataStore** para recordar al usuario. Ver `docs/prompts/02-onboarding-home.md`.

## Documentación oficial

- **Gradle y el build de Android** — [Configura tu compilación](https://developer.android.com/build)
- **Catálogo de versiones** — [Migrar a catálogos de versiones](https://developer.android.com/build/migrate-to-catalogs)
- **Estructura de un proyecto** — [Descripción general de proyectos](https://developer.android.com/studio/projects)
- **AndroidManifest** — [Descripción general del manifiesto](https://developer.android.com/guide/topics/manifest/manifest-intro)
- **Activity** — [Introducción a las actividades](https://developer.android.com/guide/components/activities/intro-activities)
- **Jetpack Compose (`@Composable`)** — [Pensar en Compose](https://developer.android.com/develop/ui/compose/mental-model)
  · [`@Preview`](https://developer.android.com/develop/ui/compose/tooling/previews)
- **`adb` (Android Debug Bridge)** — [adb](https://developer.android.com/tools/adb)
- **Depurar en un dispositivo** — [Ejecutar apps en un dispositivo de hardware](https://developer.android.com/studio/run/device)
