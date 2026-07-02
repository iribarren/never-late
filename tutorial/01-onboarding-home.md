# Lección 01 — Onboarding + Home: estado, ViewModel, navegación y DataStore

> Objetivo: pasar de una pantalla estática ("Hola Mundo") a nuestro primer **flujo real**. La
> primera vez que se abre la app, el usuario introduce su nombre (**onboarding**); las siguientes
> veces, la app le lleva directo a **Home** con un saludo personalizado. Por el camino aprendemos
> los cimientos de cualquier app Android moderna: **estado en Compose**, **ViewModel + StateFlow**,
> **navegación** y **persistencia con DataStore**.

## Conceptos que aprendes aquí

Partiendo de la Lección 00 (`@Composable`, `Scaffold`, `Column`, `Text`, `Modifier`, tema):

- **Estado en Compose:** `remember`, `mutableStateOf` y, sobre todo, el **state hoisting**
  (izar el estado): composables sin estado que reciben datos y devuelven eventos.
- **Entrada y formularios:** `OutlinedTextField`, `Button` y **validación básica** (nombre no vacío).
- **`ViewModel` + `StateFlow`:** exponer el estado de una pantalla de forma que **sobreviva a la
  rotación**, recolectándolo con `collectAsStateWithLifecycle`.
- **Navigation Compose:** un **grafo de navegación** con dos destinos (onboarding y home) y el
  *routing* de arranque.
- **DataStore (Preferences):** persistir el nombre y el flag `onboarded` en el dispositivo,
  leídos como un `Flow`.
- **Material 3:** `Scaffold` con `TopAppBar` y un `Snackbar`.
- **Inyección de dependencias manual:** un `ViewModelProvider.Factory` para construir ViewModels
  con parámetros, sin ninguna librería extra (todavía).

---

## 1. La idea general: flujo unidireccional de datos (UDF)

Toda la lección gira en torno a un patrón: **el estado fluye hacia abajo, los eventos fluyen
hacia arriba**.

```
DataStore (disco)
   ⭣  Flow<UserPreferences>
Repository
   ⭣
ViewModel  ──  StateFlow<UiState>
   ⭣ estado (por parámetro)          ⭡ eventos (por callback)
Composable (UI)  ── onNameChange / onSave ──┘
```

La UI **nunca** guarda datos ni decide lógica por su cuenta: solo dibuja el estado que recibe y
avisa de lo que hace el usuario. Esto hace que las pantallas sean fáciles de previsualizar
(`@Preview`) y de testear.

---

## 2. Estado en Compose: `remember`, `mutableStateOf` y *state hoisting*

En Compose la UI es una **función de su estado**: cuando el estado cambia, Compose vuelve a
llamar a los `@Composable` afectados (**recomposición**) y redibuja.

Para que Compose "recuerde" un valor entre recomposiciones se usa:

```kotlin
var name by remember { mutableStateOf("") }
```

- `mutableStateOf("")` crea un estado observable: al cambiarlo, se dispara la recomposición.
- `remember { ... }` conserva ese valor mientras el composable siga en pantalla (sin `remember`,
  se reiniciaría en cada recomposición).

**Pero** ese estado vive atado al composable, y **se pierde al rotar la pantalla**. Por eso en
esta lección el estado del formulario **no** vive en un `remember` dentro del composable, sino en
un **ViewModel** (sección 4).

### State hoisting (izar el estado)

Un composable "izado" no guarda su propio estado: lo **recibe** por parámetro y **notifica** los
cambios por callback. Nuestro `OnboardingScreen` es así
([OnboardingScreen.kt](../app/src/main/java/com/neverlate/ui/onboarding/OnboardingScreen.kt)):

```kotlin
@Composable
fun OnboardingScreen(
    uiState: OnboardingUiState,          // estado que entra
    onNameChange: (String) -> Unit,       // evento que sale
    onSave: () -> Unit,                   // evento que sale
    modifier: Modifier = Modifier,
) { ... }
```

Ventaja: este composable no depende de ningún ViewModel ni de DataStore, así que se puede
previsualizar y testear pasándole estados "a mano" (ver los `@Preview` al final del fichero, y el
test de UI).

Todavía usamos `remember` donde tiene sentido: en `HomeScreen` guardamos el `SnackbarHostState`
con `remember { SnackbarHostState() }`, porque ese objeto de UI sí debe sobrevivir a las
recomposiciones (pero no necesita sobrevivir a la rotación).

---

## 3. `TextField`, `Button` y validación

El patrón estándar de entrada de texto en Compose es **`value` + `onValueChange`**:

```kotlin
OutlinedTextField(
    value = uiState.name,          // qué mostrar
    onValueChange = onNameChange,  // qué hacer cuando el usuario teclea
    label = { Text(stringResource(R.string.onboarding_name_label)) },
    singleLine = true,
)
```

Fíjate en que el `TextField` **no guarda** el texto: cada pulsación llama a `onNameChange`, que
actualiza el estado en el ViewModel, y el nuevo `uiState.name` vuelve a bajar al `value`. Es el
flujo unidireccional en miniatura.

La **validación** es simple: el botón de guardar está deshabilitado si el nombre está en blanco.

```kotlin
Button(onClick = onSave, enabled = uiState.isSaveEnabled) {
    Text(stringResource(R.string.onboarding_save_button))
}
```

`isSaveEnabled` lo calcula el ViewModel con `newName.isNotBlank()`, de modo que un nombre de solo
espacios cuenta como vacío.

---

## 4. `ViewModel` + `StateFlow`

Un **`ViewModel`** es un objeto que Android mantiene vivo mientras la pantalla exista
*lógicamente*, aunque la Activity se recree (por ejemplo al rotar). Es el sitio correcto para el
estado de una pantalla.

Modelamos el estado como una **data class inmutable** (una única fuente de verdad):

```kotlin
data class OnboardingUiState(
    val name: String = "",
    val isSaveEnabled: Boolean = false,
)
```

Y lo exponemos con un **`StateFlow`** (un flujo que siempre tiene un valor "actual"):

```kotlin
class OnboardingViewModel(private val repository: UserPreferencesRepository) : ViewModel() {

    private val _uiState = MutableStateFlow(OnboardingUiState())
    val uiState: StateFlow<OnboardingUiState> = _uiState.asStateFlow()

    fun onNameChange(newName: String) {
        _uiState.update { it.copy(name = newName, isSaveEnabled = newName.isNotBlank()) }
    }

    fun save(onSaved: () -> Unit) {
        val name = _uiState.value.name
        if (name.isBlank()) return
        viewModelScope.launch {          // corrutina atada al ciclo de vida del ViewModel
            repository.saveOnboarding(name)
            onSaved()                    // avisa a la navegación de que puede ir a Home
        }
    }
}
```

Puntos clave:

- Patrón `_uiState` (privado, mutable) + `uiState` (público, de solo lectura). La UI no puede
  modificar el estado directamente; solo el ViewModel lo hace.
- **`viewModelScope`** es un `CoroutineScope` que se cancela solo cuando el ViewModel se destruye,
  así que no dejamos trabajo colgado. `saveOnboarding` es una función `suspend` (escribe en disco),
  por eso se llama dentro de una corrutina.
- Guardar es una acción de "un solo disparo": cuando termina, invoca `onSaved()` para que la capa
  de navegación decida a dónde ir (la UI no navega desde el ViewModel).

### El `HomeViewModel`: *observar* en lugar de escribir

Mientras que `OnboardingViewModel` **escribe** una vez, `HomeViewModel` **observa**
continuamente el `Flow` del repositorio y lo vuelca en su propio `StateFlow`:

```kotlin
init {
    viewModelScope.launch {
        repository.userPreferences.collect { preferences ->
            _uiState.value = HomeUiState(name = preferences.name)
        }
    }
}
```

Así, si el nombre cambiara en disco, Home se actualizaría solo.

### Conectar el ViewModel con la UI: `collectAsStateWithLifecycle`

El composable "route" (con estado) obtiene el ViewModel y recolecta su `StateFlow` como estado de
Compose:

```kotlin
@Composable
fun OnboardingRoute(repository: UserPreferencesRepository, onSaved: () -> Unit, ...) {
    val viewModel: OnboardingViewModel = viewModel(factory = AppViewModelFactory(repository))
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    OnboardingScreen(
        uiState = uiState,
        onNameChange = viewModel::onNameChange,
        onSave = { viewModel.save(onSaved) },
    )
}
```

- **`collectAsStateWithLifecycle`** (mejor que `collectAsState`) pausa la recolección cuando la
  pantalla no está visible, para no gastar recursos en balde.
- Separamos **route** (con estado, conoce el ViewModel) de **screen** (sin estado, solo dibuja).
  Es una convención muy común y la usamos en las dos pantallas.

---

## 5. DataStore (Preferences): persistir en el dispositivo

**DataStore Preferences** es la forma moderna de guardar pares clave-valor sencillos (sustituye a
las viejas `SharedPreferences`). Lo leemos como un `Flow` y lo escribimos con corrutinas.

Creamos una única instancia por proceso con un *delegate* de extensión sobre `Context`:

```kotlin
private val Context.userPrefsDataStore: DataStore<Preferences>
        by preferencesDataStore(name = "user_prefs")
```

Definimos las **claves** tipadas y leemos con `.data.map { ... }`, aportando valores por defecto
para una instalación nueva (fichero vacío):

```kotlin
private object Keys {
    val NAME = stringPreferencesKey("user_name")
    val ONBOARDED = booleanPreferencesKey("onboarded")
}

override val userPreferences: Flow<UserPreferences> =
    context.userPrefsDataStore.data.map { prefs ->
        UserPreferences(
            name = prefs[Keys.NAME] ?: "",
            onboarded = prefs[Keys.ONBOARDED] ?: false,
        )
    }

override suspend fun saveOnboarding(name: String) {
    context.userPrefsDataStore.edit { prefs ->   // transacción atómica
        prefs[Keys.NAME] = name.trim()           // recortamos espacios al guardar
        prefs[Keys.ONBOARDED] = true
    }
}
```

### ¿Por qué un `Repository` (y por qué una interfaz)?

Todo el acceso a datos vive detrás de un **repositorio**
([UserPreferencesRepository.kt](../app/src/main/java/com/neverlate/data/UserPreferencesRepository.kt)):

- Los ViewModels no saben nada de DataStore; solo hablan con `UserPreferencesRepository`.
- Lo declaramos como **interfaz** con una implementación real (`DataStoreUserPreferencesRepository`).
  Así, en los tests podemos pasar un **fake en memoria** al ViewModel, sin necesitar el runtime de
  Android (DataStore no funciona bien en un test JVM puro). Esto es el mismo motivo por el que
  hacemos state hoisting en la UI: **separar la lógica de sus dependencias facilita testear**.

---

## 6. Navigation Compose y el *routing* de arranque

Con **Navigation Compose** declaramos un **grafo**: una lista de destinos identificados por una
cadena (`route`) dentro de un `NavHost`
([AppNavHost.kt](../app/src/main/java/com/neverlate/ui/navigation/AppNavHost.kt)).

```kotlin
NavHost(navController = navController, startDestination = startDestination) {
    composable(Routes.ONBOARDING) {
        OnboardingRoute(
            repository = repository,
            onSaved = {
                navController.navigate(Routes.HOME) {
                    // Sacamos Onboarding de la pila: tras guardar, el botón "atrás"
                    // desde Home NO debe volver al onboarding.
                    popUpTo(Routes.ONBOARDING) { inclusive = true }
                }
            },
        )
    }
    composable(Routes.HOME) { HomeRoute(repository = repository) }
}
```

- `navController.navigate("home")` cambia de pantalla.
- **`popUpTo(...) { inclusive = true }`** limpia la pila de navegación para que "atrás" no
  devuelva al usuario a una pantalla que ya no tiene sentido revisitar.

### El *routing* de arranque y el "flash"

¿Qué pantalla mostramos al abrir la app? Depende del flag `onboarded`, que se lee **de disco de
forma asíncrona**. Si por defecto mostrásemos onboarding y luego llegara `onboarded = true`, el
usuario que ya se registró vería un **parpadeo** de la pantalla equivocada.

La solución: mientras el valor aún no ha llegado, mostramos un indicador de carga neutro.

```kotlin
val userPreferences by repository.userPreferences
    .collectAsStateWithLifecycle(initialValue = null)   // aún no sabemos

when (val preferences = userPreferences) {
    null -> LoadingIndicator()                            // cargando: nada de adivinar
    else -> {
        val startDestination =
            if (preferences.onboarded) Routes.HOME else Routes.ONBOARDING
        NavHost(...) { ... }
    }
}
```

---

## 7. Material 3: `Scaffold`, `TopAppBar` y `Snackbar`

Cada pantalla usa un `Scaffold` (estructura base, ya visto en la Lección 00) ahora **con barra
superior**:

```kotlin
Scaffold(
    topBar = { TopAppBar(title = { Text(stringResource(R.string.app_name)) }) },
    snackbarHost = { SnackbarHost(snackbarHostState) },
) { innerPadding -> /* contenido con padding */ }
```

En Home, las opciones (Tareas, Artículos) son **placeholders**: al pulsarlas mostramos un
`Snackbar` de "Próximamente". Como `showSnackbar` es una función `suspend` y el `onClick` no lo es,
lanzamos la corrutina con un `rememberCoroutineScope()`:

```kotlin
val snackbarHostState = remember { SnackbarHostState() }
val coroutineScope = rememberCoroutineScope()
...
onClick = { coroutineScope.launch { snackbarHostState.showSnackbar(comingSoonMessage) } }
```

---

## 8. Inyección de dependencias manual

Nuestros ViewModels reciben el repositorio por constructor, así que Compose no puede crearlos con
el constructor vacío por defecto. Le damos un `ViewModelProvider.Factory`
([AppViewModelFactory.kt](../app/src/main/java/com/neverlate/ui/navigation/AppViewModelFactory.kt)):

```kotlin
class AppViewModelFactory(private val repository: UserPreferencesRepository)
        : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T = when {
        modelClass.isAssignableFrom(OnboardingViewModel::class.java) ->
            OnboardingViewModel(repository) as T
        modelClass.isAssignableFrom(HomeViewModel::class.java) ->
            HomeViewModel(repository) as T
        else -> throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    }
}
```

El repositorio se crea **una sola vez** en `MainActivity` y se pasa hacia abajo. No usamos Hilt ni
Dagger todavía: para un proyecto de este tamaño, la DI manual es suficiente y se entiende mejor.

```kotlin
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val repository: UserPreferencesRepository =
            DataStoreUserPreferencesRepository(applicationContext)
        setContent { NeverLateTheme { AppNavHost(repository = repository) } }
    }
}
```

---

## 9. Dependencias nuevas (catálogo de versiones)

Todas las dependencias se añaden en `gradle/libs.versions.toml` y se referencian como `libs.*`
en `app/build.gradle.kts` (nunca versiones sueltas). Para esta feature:

- `androidx.datastore:datastore-preferences` — persistencia clave-valor.
- `androidx.navigation:navigation-compose` — grafo de navegación.
- `androidx.lifecycle:lifecycle-viewmodel-compose` y `lifecycle-runtime-compose` — obtener el
  `ViewModel` desde Compose y `collectAsStateWithLifecycle`.
- `kotlinx-coroutines-test` (solo tests) — para testear corrutinas de forma determinista.

---

## 10. Tests

- **Test unitario** (`app/src/test/.../OnboardingViewModelTest.kt`): usa un **fake en memoria** del
  repositorio y `kotlinx-coroutines-test` (`runTest`, `Dispatchers.setMain`). Comprueba el estado
  inicial, que un nombre en blanco deja el botón deshabilitado, que uno válido lo habilita, y que
  `save()` guarda el nombre **recortado** con `onboarded = true` y dispara `onSaved`.
- **Test de UI de Compose** (`app/src/androidTest/.../OnboardingScreenTest.kt`): con
  `createComposeRule()` prueba el `OnboardingScreen` sin estado (pasándole estado y callbacks a
  mano): botón deshabilitado/ habilitado según el nombre, y que teclear y pulsar disparan los
  callbacks. Requiere un emulador para ejecutarse.

```bash
# Tests unitarios (JVM, sin emulador)
./gradlew :app:testDebugUnitTest

# Test de UI (necesita un emulador/dispositivo en marcha)
./gradlew :app:connectedDebugAndroidTest
```

---

## 11. Probar la app

```bash
./gradlew :app:installDebug
adb shell am start -n com.neverlate/.MainActivity
```

- **Primer arranque:** verás la pantalla **Bienvenido**. Escribe tu nombre (el botón "Guardar" se
  habilita) y pulsa: pasas a **Home** con "Hola, {tu nombre}".
- **Cierra y vuelve a abrir:** ahora entra directo a **Home** (recuerda que ya hiciste onboarding).
- Pulsa una opción (Tareas / Artículos): aparece "Próximamente".
- Gira la pantalla mientras escribes en el onboarding: el texto **no se pierde** (está en el
  ViewModel).

---

## 12. Siguiente paso

Ya tenemos estado, ViewModel, navegación y persistencia sencilla. En las próximas lecciones
construiremos la primera feature funcional de verdad (tareas), donde necesitaremos guardar
**listas** de datos estructurados — el terreno de **Room** (base de datos) frente al DataStore de
clave-valor que hemos usado aquí.
