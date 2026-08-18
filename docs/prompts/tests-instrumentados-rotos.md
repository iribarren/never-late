# Bugfix — `:app:compileDebugAndroidTestKotlin` está roto: dos tests desincronizados del código

Lee `CLAUDE.md` (**Bug Fix Workflow** + **Build & test execution** + **Definition of Done**) y sigue
el flujo `/bugfix`. No es una feature: no lleva spec, no lleva lección de tutorial y no pregunta por
ella.

## El síntoma

El *source set* de tests instrumentados **no compila**. La suite de unit tests (`:app:testDebugUnitTest`,
que es la que corre el gate antes de cada commit) está verde, así que esto no se ve nunca:

```bash
timeout 600 ./gradlew :app:compileDebugAndroidTestKotlin --console=plain
```

falla con **61 errores**, todos concentrados en **dos ficheros** y con **dos causas independientes**:

### Causa 1 — `TasksScreenTest.kt`: 14 llamadas con la firma vieja de `TasksScreen`

```
ui/tasks/TasksScreenTest.kt:66:21  No parameter with name 'onToggleGrouping' found.
ui/tasks/TasksScreenTest.kt:67:21  No value passed for parameter 'onGroupAxisChange'.
ui/tasks/TasksScreenTest.kt:67:21  No value passed for parameter 'onPriorityFilterToggle'.
ui/tasks/TasksScreenTest.kt:67:21  No value passed for parameter 'onClearFilters'.
```

…y lo mismo en las líneas 101, 138, 167, 222, 258, 299, 341, 383, 423, 469, 505, 549, 589 y 638.

El fichero se tocó por última vez en **`4aaff11` (2026-07-09**, feature 20). La firma de `TasksScreen`
cambió después, en **`e8ebb67` (2026-08-17**, feature `priority-sorting`): el agrupado dejó de ser un
booleano (`onToggleGrouping`) y pasó a ser un **eje** (`onGroupAxisChange: (TaskGroupAxis) -> Unit`),
y aparecieron `onPriorityFilterToggle` y `onClearFilters`.

**Ojo: esto no es un renombrado mecánico.** `onToggleGrouping` era un interruptor de dos estados y
`onGroupAxisChange` recibe un eje. Los tests que ejercitaban el agrupado hay que **releerlos y
adaptarlos a lo que la pantalla hace hoy**, no rellenarlos con `{}` para que compilen.

### Causa 2 — `TaskEditScreenTest.kt`: un import que ya no resuelve

```
ui/tasks/TaskEditScreenTest.kt:6:33  Unresolved reference 'assertExists'.
```

```kotlin
import androidx.compose.ui.test.assertExists   // línea 6
```

`assertExists()` es una **función miembro** de `SemanticsNodeInteraction`, así que no necesita import
ninguno. Dato que lo confirma: en `app/src/androidTest/` hay **12 ficheros que llaman a
`assertExists()`** y **solo este importa el símbolo**. Los otros once compilan perfectamente. La
corrección es borrar esa línea; si al hacerlo algo más se rompe, es que el problema era otro y hay que
mirarlo antes de seguir.

## Lo que de verdad hay que arreglar (el bug detrás del bug)

Reparar los dos ficheros es la mitad del trabajo. **La pregunta que importa es por qué esto pudo
pudrirse durante semanas sin que nadie se enterara**, y la respuesta está en `CLAUDE.md`: el gate
anterior a cada commit es

```bash
timeout 600 ./gradlew :app:testDebugUnitTest --console=plain
```

que **nunca compila `androidTest/`**. Un test instrumentado que deja de compilar es indistinguible de
uno que no existe: no falla, no avisa, simplemente desaparece de la cobertura real. Y como corolario
incómodo — esos 14 tests de `TasksScreenTest` **llevan sin ejecutarse desde el 17 de agosto**, así que
al repararlos es perfectamente posible que alguno falle de verdad. Eso sería una buena noticia (un bug
real encontrado), no un obstáculo: repórtalo en vez de silenciarlo.

Así que el arreglo tiene **dos entregables**, y el segundo es el que evita la recaída:

1. **Reparar los dos ficheros** para que `:app:compileDebugAndroidTestKotlin` quede limpio.
2. **Añadir la compilación de `androidTest` al gate documentado** en `CLAUDE.md` (sección
   **Build & test execution** y la **Definition of Done**), de forma que la deriva no pueda repetirse
   en silencio. Propuesta a valorar — decídela y justifícala, no la des por buena sin pensarla:

   ```bash
   timeout 600 ./gradlew :app:testDebugUnitTest :app:compileDebugAndroidTestKotlin --console=plain
   ```

   Compilar es barato (no necesita emulador); es *ejecutar* lo que exige dispositivo. Ese es
   justamente el punto: el guardián que faltaba no cuesta nada.

> **`/bugfix` pide un test de regresión que falle antes del arreglo.** Aquí ese papel lo hace el
> propio paso de compilación añadido al gate: hoy falla, después del arreglo pasa. Dilo así de
> explícito en el commit y en el PR — no te inventes un test artificial para cumplir el trámite.

## Límites de alcance (respétalos)

- **No borres ni comentes tests para que compile.** Si alguno ya no tiene sentido con la pantalla
  actual, dilo y espera decisión; no lo elimines por tu cuenta.
- **No debilites las aserciones.** Un test que compila y ya no comprueba nada es peor que uno roto,
  porque encima parece verde.
- **No metas CI aquí.** `CLAUDE.md` lista **CI** (`.github/`, build + tests en cada PR) como hueco de
  producción conocido, a abordar como su propia feature con su spec. Este bugfix se queda en el gate
  local documentado; no improvises un workflow de GitHub Actions dentro de él.
- **No toques la pantalla ni el ViewModel.** El código de producción está bien: son los tests los que
  se quedaron atrás. Si al adaptarlos descubres un bug real en `TasksScreen`, **para y repórtalo**
  antes de arreglarlo — sería otro bugfix, con su propia rama.
- **No lances el emulador.** Compilar demuestra que el arreglo está bien formado; **ejecutar** los
  instrumentados en un dispositivo es cosa del usuario. Deja escrito en el PR qué queda por ejecutar.

## Notas

- Rama sugerida: `bugfix/instrumented-tests-drift`, desde `master`.
- Ficheros:
  [`TasksScreenTest.kt`](../../app/src/androidTest/java/com/neverlate/ui/tasks/TasksScreenTest.kt),
  [`TaskEditScreenTest.kt`](../../app/src/androidTest/java/com/neverlate/ui/tasks/TaskEditScreenTest.kt),
  y como referencia de la firma actual
  [`TasksScreen.kt`](../../app/src/main/java/com/neverlate/ui/tasks/TasksScreen.kt).
- Firma vigente de `TasksScreen` en el momento de escribir esto (compruébala, no la copies a ciegas):
  además de los tres callbacks nuevos, tiene `focusShieldOptions: FocusShieldOptions = FocusShieldOptions()`
  y `onFocusClick: (String, FocusShieldOptions) -> Unit`, ambos con valor por defecto — así que los
  tests no necesitan pasarlos salvo que quieran ejercitarlos.
- **Mientras arreglas, comprueba si hay más deriva.** Estos dos ficheros son los que fallan hoy, pero
  el mismo agujero del gate permite que haya otros a medio camino. Que
  `:app:compileDebugAndroidTestKotlin` quede limpio entero es el criterio, no que desaparezcan estos
  61 errores concretos.
- Sin backend, sin contrato, sin migración de Room, sin dependencia nueva, sin cambio de manifest.
- Documentación: solo `CLAUDE.md` si se acepta el entregable 2. Nada de mockups (no hay cambio
  visible) y **ninguna lección de tutorial** (los bugfixes no llevan, salvo petición explícita).
- Agente: `android-engineer` (repara los tests y prueba que compilan, en una sola pasada).
