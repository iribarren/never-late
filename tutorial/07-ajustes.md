# Lección 07 — Ajustes y modo oscuro: preferencias reactivas que cambian toda la app

> Objetivo: añadir una pantalla de **Ajustes** con un selector de tema (**claro / oscuro / seguir
> el sistema**). Es la primera vez que una preferencia del usuario **cambia la apariencia global**
> de la app en caliente, sin reiniciar. Por el camino repasamos y profundizamos en **DataStore**,
> aprendemos a **persistir un `enum`** de forma tolerante a fallos, y conectamos la preferencia con
> nuestro `NeverLateTheme` leyendo un `Flow` **en lo más alto de la composición**.

## Conceptos que aprendes aquí

Partiendo de las lecciones 01–06 (Compose, `ViewModel`/`StateFlow`, DI manual, repositorio tras
interfaz, DataStore del onboarding, navegación, Room + `Flow`):

- **DataStore, otra vuelta de tuerca:** cómo **ampliar** un repositorio de preferencias existente
  con una **clave nueva** en el **mismo fichero**, en vez de crear un segundo almacén.
- **Persistir un `enum` como `String`:** guardar `mode.name` y volver a leerlo con un *parse
  tolerante* que nunca revienta (un valor ausente o desconocido cae en un valor por defecto seguro).
- **Tematizado dinámico de Material 3:** una preferencia (`ThemeMode`) que se traduce a un `Boolean`
  y entra por el parámetro `darkTheme` de `NeverLateTheme`, sin duplicar la lógica de color.
- **Exponer preferencias de forma reactiva:** leer el `Flow` de preferencias en `setContent` con
  `collectAsStateWithLifecycle`, y elegir un **valor inicial** para evitar el *parpadeo* de arranque.
- **Selección única en Compose:** una lista de opciones con `RadioButton`, `selectableGroup()` y
  `Modifier.selectable(role = Role.RadioButton)` (accesibilidad incluida).
- **Función pura y testeable:** aislar la decisión "modo → claro/oscuro" en una función sin tipos de
  Android para poder probarla en la JVM.

---

## 1. La idea: tres estados, no un booleano

La tentación es guardar un `Boolean isDark`. Pero "seguir el sistema" **no es** claro ni oscuro:
es *"lo que diga el dispositivo en cada momento"*. Si guardásemos un booleano perderíamos esa
tercera posibilidad y la app dejaría de reaccionar cuando el móvil cambia de modo (por ejemplo, al
anochecer). Por eso modelamos **tres estados** con un `enum`:

```kotlin
// data/UserPreferencesRepository.kt
enum class ThemeMode {
    LIGHT,
    DARK,
    SYSTEM;
    // ...
}
```

La decisión concreta claro/oscuro **se deriva** en cada recomposición a partir del modo. Nunca se
persiste.

---

## 2. Persistir un `enum`: guardar `String`, leer con tolerancia

DataStore Preferences no sabe de `enum`s; guarda tipos primitivos (`String`, `Boolean`, `Int`…).
El truco estándar es **guardar el nombre del enum** (`mode.name`, p. ej. `"DARK"`) y reconstruirlo
al leer.

La parte importante es **la lectura**. ¿Qué pasa si el valor no está (instalación nueva) o es
desconocido (una clave vieja, un dato corrupto, o un modo que borremos en el futuro)? No puede
petar. Por eso el parseo vive en una función tolerante que cae en el valor por defecto seguro:

```kotlin
companion object {
    fun fromStorage(value: String?): ThemeMode =
        entries.firstOrNull { it.name == value } ?: SYSTEM
}
```

`entries` es la lista de todos los valores del `enum` (API moderna de Kotlin). Buscamos el que
coincide **exactamente** con lo guardado; si no hay ninguno —`null`, `""`, `"PURPLE"`, `"dark"` en
minúsculas—, devolvemos `SYSTEM`. Esta función es **pura** y la cubrimos con tests (sección 8).

---

## 3. Ampliar el repositorio, no duplicarlo

En la lección 02 creamos `UserPreferencesRepository` (interfaz) y su implementación con DataStore
sobre el fichero `user_prefs`, con el nombre y el flag `onboarded`. Aquí **no** creamos un segundo
DataStore: **añadimos una clave** al mismo fichero y **un campo** al `data class`.

```kotlin
data class UserPreferences(
    val name: String = "",
    val onboarded: Boolean = false,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,   // nuevo, con valor por defecto
)
```

El valor por defecto (`SYSTEM`) es clave para la **retrocompatibilidad**: una instalación que ya
tenía `name`/`onboarded` guardados, pero nunca vio esta feature, simplemente lee `SYSTEM` sin
migraciones ni sobresaltos.

En la implementación DataStore añadimos la clave y la usamos al leer y al escribir:

```kotlin
private object Keys {
    val NAME = stringPreferencesKey("user_name")
    val ONBOARDED = booleanPreferencesKey("onboarded")
    val THEME_MODE = stringPreferencesKey("theme_mode")   // nuevo
}

override val userPreferences: Flow<UserPreferences> =
    context.userPrefsDataStore.data.map { preferences ->
        UserPreferences(
            name = preferences[Keys.NAME] ?: "",
            onboarded = preferences[Keys.ONBOARDED] ?: false,
            themeMode = ThemeMode.fromStorage(preferences[Keys.THEME_MODE]),
        )
    }

override suspend fun saveThemeMode(mode: ThemeMode) {
    context.userPrefsDataStore.edit { preferences ->
        preferences[Keys.THEME_MODE] = mode.name
    }
}
```

Recuerda de la lección 02: `edit { }` es una **transacción atómica** de lectura-modificación-
escritura, y `userPreferences` es un `Flow` que **vuelve a emitir** cada vez que el fichero cambia.
Es decir: en cuanto guardamos un modo nuevo, todo el que esté observando ese `Flow` se entera.

---

## 4. De la preferencia al color: una función pura

`NeverLateTheme` (lección 01) **ya** aceptaba un parámetro `darkTheme: Boolean`. Hasta ahora su
valor por defecto era `isSystemInDarkTheme()`. La novedad de esta feature **no es cambiar el tema**,
sino **cambiar quién decide ese booleano**. Aislamos esa decisión en una función pura, sin tipos de
Compose ni de Android, para poder testearla en la JVM:

```kotlin
// ui/theme/Theme.kt
fun themeModeToDark(mode: ThemeMode, systemInDark: Boolean): Boolean = when (mode) {
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
    ThemeMode.SYSTEM -> systemInDark
}
```

`LIGHT` y `DARK` ignoran el sistema; `SYSTEM` delega en él. Fíjate en que `NeverLateTheme`
**no cambia su firma**: solo pasamos a darle el `darkTheme` explícitamente. El color dinámico
(Material You, Android 12+) sigue funcionando igual, porque `Theme.kt` elige entre el esquema
dinámico claro y el oscuro **según ese mismo booleano**.

---

## 5. Leer el `Flow` en lo más alto: `MainActivity`

Para que el tema afecte a **toda** la app (no solo a la pantalla de Ajustes), la preferencia debe
leerse en la raíz de la composición, envolviendo el `NeverLateTheme` que a su vez envuelve el
`AppNavHost`:

```kotlin
setContent {
    val userPreferences by repository.userPreferences
        .collectAsStateWithLifecycle(initialValue = null)
    val themeMode = userPreferences?.themeMode ?: ThemeMode.SYSTEM
    val darkTheme = themeModeToDark(themeMode, isSystemInDarkTheme())

    NeverLateTheme(darkTheme = darkTheme) {
        AppNavHost(/* ... */)
    }
}
```

### El parpadeo de arranque (*startup flash*)

DataStore lee de disco de forma **asíncrona**. Durante el primer instante `userPreferences` es
`null`. Si en ese hueco compusiéramos con un valor cualquiera y luego llegara el valor real, se
vería un *flash* de tema equivocado. La mitigación es la misma idea que ya usa `AppNavHost` con su
ruta de arranque: elegir un **valor inicial sensato** (`SYSTEM`) mientras carga. Para la inmensa
mayoría de casos el primer frame ya es correcto; y como todo es reactivo, si el valor definitivo
difiere, la app se **recompone** al esquema correcto sin más código.

Este es el corazón de "exponer preferencias de forma reactiva": un cambio en disco → nueva emisión
del `Flow` → recomposición → color nuevo. No hay que "avisar" a nadie manualmente.

---

## 6. La pantalla de Ajustes: route + screen + ViewModel

Seguimos el mismo patrón de las lecciones anteriores. El **ViewModel** observa el `Flow` (como
`HomeViewModel`) y expone el modo actual; al elegir uno, lo escribe:

```kotlin
class SettingsViewModel(private val repository: UserPreferencesRepository) : ViewModel() {
    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            repository.userPreferences.collect { preferences ->
                _uiState.value = SettingsUiState(themeMode = preferences.themeMode)
            }
        }
    }

    fun onThemeModeSelected(mode: ThemeMode) {
        viewModelScope.launch { repository.saveThemeMode(mode) }
    }
}
```

Un detalle bonito del flujo unidireccional: al pulsar una opción **no** tocamos el estado a mano.
Escribimos en el repositorio; eso hace re-emitir el `Flow`; el `init { }` recibe el nuevo valor y
actualiza `uiState`. El `RadioButton` seleccionado refleja **lo que está persistido de verdad**,
no una copia optimista.

El **composable** es sin estado (state hoisting) y usa selección única accesible:

```kotlin
Column(modifier = Modifier.selectableGroup()) {
    themeOptions.forEach { option ->
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .selectable(
                    selected = uiState.themeMode == option.mode,
                    onClick = { onThemeModeSelected(option.mode) },
                    role = Role.RadioButton,
                ),
        ) {
            RadioButton(selected = uiState.themeMode == option.mode, onClick = null)
            Text(stringResource(option.labelRes))
        }
    }
}
```

Tres detalles de accesibilidad y Material 3:

- `selectableGroup()` marca el grupo como "elige uno de N" para el lector de pantalla.
- `Modifier.selectable(..., role = Role.RadioButton)` hace **toda la fila** pulsable, no solo el
  círculo, y la describe correctamente.
- `RadioButton(onClick = null)`: el clic ya lo gestiona la fila; el botón no debe consumirlo otra
  vez (guía oficial de Material 3 para selección a nivel de fila).

---

## 7. Navegación y el acceso desde Home

Añadimos una ruta `settings` al grafo (lección 02/03) y, en Home, una **acción en la `TopAppBar`**
(un icono de engranaje) que navega hasta ella:

```kotlin
// AppNavHost.kt
composable(Routes.SETTINGS) {
    SettingsRoute(repository = repository, onBack = { navController.popBackStack() })
}

// HomeScreen.kt — dentro de TopAppBar(actions = { ... })
IconButton(onClick = onSettingsClick) {
    Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.home_settings_content_description))
}
```

El `SettingsViewModel` se construye en `AppViewModelFactory` reutilizando el
`userPreferencesRepository` que ya circulaba por la app, igual que `HomeViewModel`.

---

## 8. Tests (JVM, sin emulador)

Como siempre, movemos la lógica testeable a **funciones puras** y probamos el ViewModel contra un
**fake en memoria** (DataStore real necesita Android):

- **`ThemeModeTest`** — `fromStorage`: cada nombre conocido vuelve a su enum; `null`, `""`, un valor
  desconocido y `"dark"` en minúsculas caen en `SYSTEM`, sin excepción.
- **`ThemeModeMappingTest`** — `themeModeToDark`: `LIGHT` siempre claro, `DARK` siempre oscuro,
  `SYSTEM` sigue al parámetro del sistema (ambos valores).
- **`SettingsViewModelTest`** — con un fake: el estado inicial refleja el modo persistido, y tras
  `onThemeModeSelected(LIGHT)` el fake registra el guardado y el `uiState` se actualiza.

Ampliamos también el fake de `OnboardingViewModelTest` con `saveThemeMode`, ya que la interfaz
`UserPreferencesRepository` ganó ese método.

```bash
# Tests unitarios (JVM, sin emulador)
./gradlew :app:testDebugUnitTest

# Compila el APK
./gradlew :app:assembleDebug
```

Lo que **no** se testea en unidad —que `NeverLateTheme` aplique de verdad el esquema, el arranque
sin parpadeo, la navegación Home↔Ajustes— se comprueba **a mano** en el emulador: cambia a oscuro y
mira que Home y el resto cambian; mata y reabre la app (debe recordar el modo); con "seguir sistema"
activo, cambia el modo del dispositivo y observa que la app reacciona sola.

---

## 9. Siguiente paso

La pantalla de Ajustes nace con un único ajuste. El siguiente candidato natural es la
**internacionalización (i18n)** —feature 08—, que encaja aquí como un segundo ajuste (idioma) y
reutiliza exactamente esta misma tubería: una preferencia en DataStore, expuesta como `Flow`,
aplicada arriba del todo.
