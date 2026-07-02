# Lección 00 — Entorno de desarrollo y "Hola Mundo"

> Objetivo: entender cómo está montado un proyecto Android moderno con Kotlin y Jetpack
> Compose, compilarlo y verlo funcionando en el emulador. Es el clásico primer paso.

## Conceptos que aprendes aquí

- Qué es un proyecto **Gradle** y por qué se compila con el **wrapper** (`./gradlew`).
- El **catálogo de versiones** (`gradle/libs.versions.toml`) como única fuente de versiones.
- La estructura de un módulo de app Android (`app/`).
- El `AndroidManifest.xml` y el concepto de **Activity**.
- Qué es un **`@Composable`** y cómo Jetpack Compose dibuja la interfaz.
- Cómo compilar, instalar y lanzar la app en el emulador.

## 1. Gradle y el wrapper

Android usa **Gradle** como sistema de construcción (build system): compila el código, empaqueta
recursos y genera el APK. No instalamos Gradle a mano: el proyecto incluye el **wrapper**
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

El **manifest** es la "ficha de identidad" de la app: nombre, icono, tema y qué componentes
tiene. Aquí declara una **Activity** (`MainActivity`) marcada como *launcher*: es la pantalla que
se abre al pulsar el icono de la app.

Una **Activity** es un punto de entrada de la app (históricamente, una pantalla). En una app
moderna con Compose lo normal es tener **una sola Activity** que hospeda toda la interfaz.

## 5. El primer `@Composable`

Con Jetpack Compose la interfaz se describe con **funciones** anotadas con `@Composable`. En vez
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

## 7. Siguiente paso

En la próxima lección crearemos las primeras pantallas de verdad: **onboarding** (alta de datos
la primera vez) y **home**, aprendiendo **estado en Compose**, **ViewModel**, **navegación** y
**DataStore** para recordar al usuario. Ver `docs/prompts/02-onboarding-home.md`.
