# Feature — Subir a Gradle 9 + AGP 9 para poder compilar con JDK 25

Lee `CLAUDE.md` (**Development**, **Definition of Done**, **Execution Policy**, **Build & test
execution** y **Mandatory Workflow**) e implementa **"que el proyecto compile y pase los tests en el
JDK que trae el sistema, sin depender de un JDK pinchado a mano"** siguiendo el flujo `/feature`.

## Por qué ahora

El JDK del sistema pasó a ser **25** (Ubuntu 26.04) y **Gradle 8.13 no arranca con él**: aborta con
un `IllegalArgumentException: 25.0.3` antes de ejecutar una sola línea de lógica de build. El
mensaje no dice nada útil, así que el síntoma real es "gradle falla al instante y no se entiende por
qué".

Hoy eso está **tapado, no resuelto**: hay un `org.gradle.java.home` apuntando al JBR 21 de Android
Studio en el `~/.gradle/gradle.properties` del usuario (fichero de máquina, sin versionar). Funciona,
pero es una muleta invisible: no está en el repo, no se hereda al clonar y ata el proyecto a que
Android Studio siga instalado en esa ruta. **La feature está terminada cuando esa línea se puede
borrar** y `./gradlew` sigue funcionando.

## Qué construir

- **Subir Gradle** en los **dos** builds del monorepo — `app/` (wrapper raíz) y `backend/` (wrapper
  propio) — a una versión que corra sobre JDK 25.
- **Subir AGP** en el build de Android, porque la cadena es rígida: no se puede subir Gradle sin
  subir AGP.
- **Despinchar Hilt**, que hoy está clavado *explícitamente* por compatibilidad con AGP 8.
- **Borrar la muleta**: quitar `org.gradle.java.home` de `~/.gradle/gradle.properties` y comprobar
  que todo sigue verde con el JDK del sistema.
- **Actualizar `CLAUDE.md`**, cuya sección *Development* hoy dice "JDK 21 — y solo 21" y explica cómo
  pincharlo. Esa instrucción deja de ser cierta con esta feature y debe reescribirse, no ampliarse.

## Tutorial

Antes de escribir el spec, el flujo `/feature` **debe preguntar con `AskUserQuestion`** si esta
feature lleva lección en español (*Sí, con lección / No / Decidir al final*), y la respuesta se anota
en el campo `Tutorial:` del spec.

**Recomendación: No** — o, como mucho, *decidir al final*. Es fontanería de tooling: no introduce
ningún concepto de Kotlin ni de Android que el lector vaya a usar escribiendo la app. Si al
implementarla aparece algo que sí merece explicarse (típicamente la caché de configuración), el
sitio natural **no es una lección nueva**: es engordar la 21 (`docs/prompts/21-build-release.md`),
que ya tiene reservado el hueco de build y release. No inventes un número para esto.

## La cadena de versiones (verificado, no supuesto)

Consultado en la [matriz de compatibilidad de Gradle](https://docs.gradle.org/current/userguide/compatibility.html)
y en la [tabla de versiones de AGP](https://developer.android.com/build/releases/about-agp):

| Para correr sobre | Gradle mínimo |
|---|---|
| JDK 24 | 8.14 |
| **JDK 25** | **9.1.0** |
| JDK 26 | 9.4.0 |

| AGP | Gradle mínimo |
|---|---|
| 8.13 (el de hoy) | 8.13 |
| 9.0 | 9.1.0 |
| 9.1 | 9.3.1 |
| 9.2 | 9.4.1 |
| 9.3 | 9.5.0 |

Estado actual del repo: **Gradle 8.13** en ambos wrappers, **AGP 8.13.2**, **Kotlin 2.1.0**,
**KSP 2.1.0-1.0.29**, **Hilt 2.58**, **hilt-navigation-compose 1.2.0**.

El mínimo viable es **AGP 9.0 + Gradle 9.1.0**. **Decide en el spec** si se va al mínimo o a un par
actual (AGP 9.3 + Gradle 9.5): ir al mínimo es un salto más corto pero deja otra subida pendiente a
los pocos meses; ir al día es más trabajo de una vez. Argumenta la elección, no la des por obvia.

## Notas

- Rama sugerida: `feature/gradle9-agp9-jdk25`.

- **Hilt y `hilt-navigation-compose` están pinchados por esto mismo, y el propio catálogo lo dice.**
  [`libs.versions.toml`](../../gradle/libs.versions.toml) tiene comentarios explícitos: `hilt = "2.58"`
  y `hiltNavigationCompose = "1.2.0"` están congelados porque *"newer releases hard-require AGP 9"*.
  Esta feature es exactamente el momento de despincharlos, y los comentarios que justificaban el
  pinchazo hay que **borrarlos o reescribirlos** — dejarlos mintiendo es peor que no haber subido.

- **Son dos builds, no uno.** `backend/` tiene su propio `gradlew`, su propio wrapper (también en
  8.13) y su propio catálogo de versiones. No lleva AGP, así que solo necesita el salto de Gradle —
  pero **si te olvidas de él, el backend deja de compilar en cuanto se borre la muleta del JDK**.
  El `docker compose` del backend no salva de esto: compila con Gradle igual.

- **Lo que hay que verificar durante el spec, no dar por hecho.** No asumas ninguno de estos puntos:
  la migración a AGP 9 es la que más "sorpresas de una línea" acumula.
  - Si **Kotlin 2.1.0 y KSP** siguen sirviendo con AGP 9, o hay que subirlos en el mismo cambio.
  - Qué hace AGP 9 con la **caché de configuración** (si pasa a estar activa por defecto y si el
    build la aguanta con KSP + Hilt + Room). `gradle.properties` no la tiene activada hoy a
    propósito.
  - Si sobrevive `testOptions { unitTests.isIncludeAndroidResources = true }` en
    [`app/build.gradle.kts`](../../app/build.gradle.kts), del que **dependen las 9 clases de
    Robolectric** para levantar recursos.
  - Si **Robolectric 4.16.1** y sus `@Config(sdk = [34])` siguen funcionando.
  - Si AGP 9 exige subir `compileSdk` (hoy 36) o toca `minSdk` (24, y es un compromiso del producto:
    no subirlo por conveniencia del build).
  - Si sobreviven las piezas que se añadieron al bloque `tasks.withType<Test>`: `maxParallelForks`,
    `timeout` y `testLogging`. Ese `timeout` es el que impide que un test colgado bloquee la sesión
    entera — si AGP/Gradle 9 cambian su forma, hay que reponerlo, no perderlo por el camino.

- **Room es la parte que no puede fallar en silencio.** El esquema va por la versión 6 con los JSON
  exportados en `app/schemas/` y migraciones probadas con `MigrationTestHelper`. Un cambio de KSP o
  de la ruta de `room.schemaLocation` puede dejar de exportar el esquema **sin romper el build**.
  Criterio de aceptación explícito: después de la subida, `app/schemas/6.json` sigue existiendo,
  igual que antes, y los tests de migración pasan.

- **No hay CI que te cubra.** Es el hueco de producción que `CLAUDE.md` nombra y que
  [`diferidos.md`](../diferidos.md) repite: no existe `.github/`. Nadie va a compilar esto en una
  máquina limpia, así que la verificación local **es** la verificación. Ten esto presente al decidir
  cuánto saltar de una vez.

- **Cómo se prueba que funcionó** (criterios de aceptación, todos comprobables):
  1. `java -version` dice 25 y **no** hay `org.gradle.java.home` en `~/.gradle/gradle.properties`.
  2. `timeout 1800 ./gradlew :app:testDebugUnitTest --console=plain` verde, sin prefijo `JAVA_HOME`.
  3. `./gradlew :app:assembleDebug` produce APK.
  4. `cd backend && ./gradlew build` verde.
  5. `app/schemas/6.json` intacto y los tests de `MigrationTestHelper` pasan.
  6. La app instalada arranca y se navega (verificación final en el dispositivo: la hace el usuario).

- **Fuera de alcance, dicho para que no se cuele:** montar **CI** (es su propia feature con spec
  propia), la **build de release firmada + R8** (ya tiene prompt: `21-build-release.md`), activar la
  **caché de configuración** como optimización buscada (si viene activada por defecto se gestiona;
  perseguirla es otra cosa), subir dependencias *ajenas* a la cadena AGP/Gradle/Hilt sólo porque hay
  versión nueva, y cualquier cambio de comportamiento del producto.

- **Sin cambio visual.** No toca UI, así que la sección *Visual & UX Design* del spec debe decir
  explícitamente **"ninguno: esta feature no cambia ni un píxel"**, y `docs/mockups/README.md` **no**
  se toca. Declararlo es obligatorio; omitir la sección, no.

- Sin backend nuevo, sin cambio de contrato, sin migración de Room, sin permiso nuevo.

- Ficheros: [`gradle/libs.versions.toml`](../../gradle/libs.versions.toml),
  [`gradle/wrapper/gradle-wrapper.properties`](../../gradle/wrapper/gradle-wrapper.properties),
  [`app/build.gradle.kts`](../../app/build.gradle.kts),
  [`build.gradle.kts`](../../build.gradle.kts),
  [`gradle.properties`](../../gradle.properties),
  `backend/gradle/wrapper/gradle-wrapper.properties`, `backend/gradle/libs.versions.toml`,
  `backend/build.gradle.kts`, [`CLAUDE.md`](../../CLAUDE.md) (sección *Development*),
  [`docs/arquitectura.md`](../arquitectura.md) (la decisión de hasta dónde subir y por qué).

- Agentes: `android-engineer` (cadena AGP/Gradle/Kotlin/KSP/Hilt del build de Android y que la suite
  siga verde), `backend-engineer` (el wrapper y el catálogo de `backend/`). Ojo con la regla de
  **Build & test execution** de `CLAUDE.md`: un solo actor toca el árbol a la vez, y aquí es
  especialmente fácil incumplirla porque hay dos builds — no los compiles en paralelo.
