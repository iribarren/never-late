# Lección 16 — Identidad visual propia: tema, color y tipografía de marca

> Objetivo: darle a la app una **identidad visual propia**. Hasta ahora "Never Late Again" seguía
> vistiendo la ropa de la plantilla de Android Studio: la paleta **morada** de relleno
> (`Purple80`/`Purple40`…), una `Typography` casi por defecto, y `dynamicColor` **fijado a `true`**, de
> modo que en Android 12+ el color lo ponía el fondo de pantalla del usuario y la marca desaparecía.
> En esta lección sustituimos la paleta por una **paleta Material 3 de marca** (roles completos, claro
> y oscuro) generada a partir de un color semilla, definimos una **escala tipográfica** propia, y
> convertimos `dynamicColor` en una **preferencia** que el usuario elige. La lección de diseño que
> venimos repitiendo desde la 13/14/15 vuelve a aplicar: **extendemos, no duplicamos** — el seam de
> preferencias de la lección 07 (DataStore `user_prefs` + `SettingsViewModel` + `NeverLateTheme`) ya
> existe; solo le añadimos **una preferencia más**.

## Conceptos que aprendes aquí

Partiendo de la lección 07 (preferencia de tema `ThemeMode`, DataStore `user_prefs`, `themeModeToDark`,
`NeverLateTheme` con su parámetro `dynamicColor`):

- **Los roles de color de Material 3.** Qué es un *rol* (`primary`, `onPrimary`, `primaryContainer`,
  `surface`, `onSurfaceVariant`, `outline`…), por qué el color se diseña **por rol y no por widget**, y
  cómo esa indirección es lo que permite re-tematizar toda la app cambiando un solo objeto
  `ColorScheme`.
- **Paletas tonales y HCT.** De dónde salen los ~40 colores de un tema a partir de **un solo color
  semilla**: el modelo **HCT** (Hue, Chroma, Tone) y la idea de *paleta tonal*. Es la misma matemática
  que usa Material Theme Builder.
- **La escala tipográfica.** El sistema de estilos con nombre `displayLarge … labelSmall`, por qué se
  eligen estilos con nombre en vez de tamaños sueltos, y cómo se carga (o no) una fuente propia desde
  `res/font/` con `FontFamily`.
- **El compromiso de `dynamicColor` (Material You).** Ventajas (integración con el sistema) frente a la
  identidad de marca, y cómo hacerlo **configurable** en vez de fijo.
- **Reutilizar el seam de preferencias.** Añadir un booleano al DataStore `user_prefs`, al
  `UserPreferences`, al `SettingsViewModel` y a `NeverLateTheme` siguiendo **exactamente** el patrón que
  ya existe desde la 07 — no un sistema nuevo.

---

## 1. El problema: seguíamos vestidos de plantilla

Cuando Android Studio crea un proyecto Compose, genera tres ficheros de tema de relleno en
`ui/theme/`:

- **`Color.kt`** con seis colores morados (`Purple80`, `PurpleGrey80`, `Pink80`, `Purple40`,
  `PurpleGrey40`, `Pink40`).
- **`Theme.kt`** con un `NeverLateTheme` que arma `lightColorScheme`/`darkColorScheme` cableando **solo
  tres roles** (`primary`, `secondary`, `tertiary`) y deja el resto en los valores por defecto de
  Material.
- **`Type.kt`** con una `Typography` en la que **solo `bodyLarge`** está personalizado.

Además, `dynamicColor` estaba **fijado a `true`**. En Android 12+ eso significa que el color de la app
lo deriva el sistema del **fondo de pantalla** del usuario (Material You). Es bonito, pero tiene un
coste: **la app no tiene identidad propia**. Dos usuarios ven dos apps de colores distintos, y ninguno
ve "los colores de Never Late Again", porque no existían.

Esta lección arregla las tres cosas.

---

## 2. Roles de color: por qué se diseña por rol y no por widget

La idea central de Material 3 —y probablemente el concepto más importante de esta lección— es que
**el color no se asigna a widgets, se asigna a roles**.

Un enfoque ingenuo diría "el botón es azul, la tarjeta es gris claro, el texto es negro". El problema:
en cuanto quieres un modo oscuro, o cambiar la marca, tienes que ir widget por widget reescribiendo
colores. Material 3 le da la vuelta. Define un conjunto **fijo** de *roles* con nombre semántico:

| Rol | Para qué sirve |
|---|---|
| `primary` | El color de marca principal (botones destacados, elementos activos) |
| `onPrimary` | El color del **contenido** que va **encima** de `primary` (texto/icono de un botón) |
| `primaryContainer` | Una variante más suave de `primary` para superficies rellenas |
| `onPrimaryContainer` | Contenido encima de `primaryContainer` |
| `secondary` / `tertiary` | Acentos de apoyo |
| `surface` / `background` | Los fondos de tarjetas y pantallas |
| `onSurface` / `onBackground` | El texto/iconos encima de esos fondos |
| `surfaceVariant`, `onSurfaceVariant` | Fondos y texto secundarios (p. ej. subtítulos) |
| `outline`, `outlineVariant` | Bordes y separadores |
| `error`, `onError` | Estados de error |

Fíjate en el patrón `X` / `onX`: **cada superficie tiene su color de contenido emparejado**. Eso no es
decorativo: garantiza el **contraste** (y por tanto la accesibilidad) esté donde esté ese widget. Un
`Text` que use `onSurface` sobre un `Surface` que use `surface` **siempre** se lee, en claro y en
oscuro, sin que tú calcules nada.

En el código, los widgets **nunca** escriben un hex. Leen su rol:

```kotlin
// A lo largo de ui/ verás siempre esto — nunca Color(0xFF...) suelto:
Text(text = "...", color = MaterialTheme.colorScheme.onSurfaceVariant)
Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer))
```

Esa indirección es exactamente lo que hace posible el resto de la lección: para cambiar de la paleta
morada a la de marca, o de la marca a Material You, **no tocamos ni una pantalla**. Solo cambiamos qué
objeto `ColorScheme` está activo. Míralo en el `KDoc` de [`Color.kt`](../app/src/main/java/com/neverlate/ui/theme/Color.kt):

> Material 3 no asigna colores a widgets individuales; los asigna a *roles* con nombre […] y cada
> widget lee su color de un rol en vez de un hex fijo. Esa indirección es lo que permite que toda la
> app se re-tematice […] cambiando qué `ColorScheme` está activo, en vez de editar cada pantalla.

---

## 3. De un color a un tema entero: HCT y paletas tonales

Aquí viene la pregunta natural: un `ColorScheme` tiene ~40 colores (los de la tabla anterior, × claro
y oscuro). **¿De dónde salen?** No se eligen a mano uno a uno — eso sería imposible de mantener y casi
seguro rompería el contraste. Se **derivan de un único color semilla** con un algoritmo.

### 3.1. El modelo HCT

Material 3 usa un espacio de color propio, **HCT**: **H**ue (tono), **C**hroma (saturación/viveza) y
**T**one (claridad, de 0 = negro a 100 = blanco). HCT está construido para que el **Tone se corresponda
con la luminosidad percibida** — algo que ni RGB ni HSL garantizan. Eso es clave para la accesibilidad:
si dos colores tienen tonos suficientemente separados en HCT, contrastan de verdad para el ojo humano.

### 3.2. Paletas tonales

A partir del color semilla, el algoritmo construye varias **paletas tonales**. Una paleta tonal es
una escala de 13 colores que comparten el **mismo tono y croma** y solo varían en **Tone**:

```
Tone:   0     10    20    30    40    50    60    70    80    90    95    99   100
        ███   ███   ███   ███   ███   ███   ███   ███   ███   ███   ███   ███   ███
       negro  ·····  azul de marca a distintas claridades  ·····            blanco
```

Nuestra semilla de marca es **`#3B5BDB`** (el azul de la propuesta en `docs/mockups/rediseno-ux-ui.html`).
De ella se derivan **cinco** paletas tonales:

- **primary** — el tono/croma de la propia semilla.
- **secondary** — mismo tono, croma bajo (acento discreto).
- **tertiary** — tono desplazado +60°, croma moderado (acento complementario).
- **neutral** y **neutral-variant** — casi grises (croma muy bajo, con un pelín del tono de marca) para
  fondos, superficies y bordes.

(A ellas se suma la paleta de **error**, que Material fija en un rojo estándar y **no** deriva de la
marca: un error debe verse como error en cualquier app.)

### 3.3. Rol = una paleta + un tono fijo

El último paso es el que conecta la §2 con la §3: **cada rol es un tono fijo de una paleta**, y ese
mapeo **lo fija la especificación de Material 3**, no lo elige la app:

| Rol | Claro | Oscuro |
|---|---|---|
| `primary` | primary **T40** | primary **T80** |
| `onPrimary` | primary T100 | primary T20 |
| `primaryContainer` | primary T90 | primary T30 |
| `onPrimaryContainer` | primary T10 | primary T90 |
| `surface` | neutral T98/99 | neutral T6/10 |
| `onSurface` | neutral T10 | neutral T90 |

Fíjate cómo el modo oscuro **no es** "invertir colores": es **elegir tonos distintos de las mismas
paletas**. Por eso el azul de marca en claro (`primaryLight = #3052D2`, un T40) y en oscuro
(`primaryDark = #B8C3FF`, un T80) "se sienten" el mismo color a distinta claridad.

> **Comprobación de que el algoritmo es fiel:** con la semilla `#3B5BDB`, el `primaryContainer` claro
> calculado sale `#DDE1FF`, prácticamente idéntico al `#DBE4FF` que la propuesta de diseño marcaba a
> ojo como color de contenedor. Buena señal de que el algoritmo reproduce la intención de diseño.

### 3.4. ¿Y Material Theme Builder?

Lo normal es generar todo esto con **Material Theme Builder** (m3.material.io): metes la semilla,
exporta un `Color.kt`/`Theme.kt` listos. En este entorno esa web no estaba disponible, así que se aplicó
**la misma matemática HCT** directamente (la librería `material-color-utilities` de Google es de código
abierto) para producir exactamente el mismo tipo de salida. El resultado en
[`Color.kt`](../app/src/main/java/com/neverlate/ui/theme/Color.kt) sigue **la convención de nombres del
propio exportador**: `<rol><Esquema>`, p. ej. `primaryLight`, `onPrimaryContainerDark`,
`surfaceVariantLight`… Lo importante para ti como aprendiz: **no elijas colores a mano**; parte de una
semilla y deja que el algoritmo derive el resto.

---

## 4. Cablear los roles en `Theme.kt`

Con todos los tokens en `Color.kt`, [`Theme.kt`](../app/src/main/java/com/neverlate/ui/theme/Theme.kt)
construye los dos esquemas cableando **todos** los roles (no solo tres como antes):

```kotlin
private val LightColorScheme = lightColorScheme(
    primary = primaryLight,
    onPrimary = onPrimaryLight,
    primaryContainer = primaryContainerLight,
    onPrimaryContainer = onPrimaryContainerLight,
    secondary = secondaryLight,
    // ... y así con tertiary, background, surface, surfaceVariant,
    //     outline, error, y toda la familia surfaceContainer* ...
)

private val DarkColorScheme = darkColorScheme(
    primary = primaryDark,
    // ... los mismos roles, con los tokens *Dark ...
)
```

El comentario de `Theme.kt` insiste en la idea de la §3.3:

> El mapeo rol → tono (p. ej. `primary` = tono 40 en claro, tono 80 en oscuro) lo fija la
> especificación de Material 3, no se elige por app: cada app M3 mapea sus paletas tonales sobre los
> roles igual, y eso es justo lo que mantiene el contraste (y la accesibilidad) consistente en todo el
> ecosistema. Solo las *paletas* (Color.kt) son de marca.

---

## 5. La escala tipográfica

El color no es lo único que se diseña "por sistema" en Material 3; el texto también. Existe una **escala
tipográfica**: un conjunto **fijo** de estilos con nombre que va de lo más grande a lo más pequeño:

```
displayLarge   displayMedium   displaySmall     ← titulares enormes (pantallas de bienvenida)
headlineLarge  headlineMedium  headlineSmall    ← cabeceras de sección
titleLarge     titleMedium     titleSmall       ← títulos de tarjetas, barras
bodyLarge      bodyMedium      bodySmall         ← texto corrido
labelLarge     labelMedium     labelSmall        ← botones, chips, etiquetas
```

La lógica es idéntica a la de los roles de color: los widgets **eligen un estilo con nombre**, no un
tamaño suelto.

```kotlin
Text("Ajustes", style = MaterialTheme.typography.titleLarge)
Text(descripcion, style = MaterialTheme.typography.bodyMedium)
```

Así, "un `titleLarge` se ve igual en toda la app", y **toda** la escala se puede reajustar (o cambiar de
fuente) desde un único fichero. En [`Type.kt`](../app/src/main/java/com/neverlate/ui/theme/Type.kt)
pasamos de personalizar **solo `bodyLarge`** a definir la escala **completa**, con tamaños, pesos,
`lineHeight` y `letterSpacing` deliberados por rol.

### 5.1. `FontFamily`: fuente propia vs. fuente del sistema

Aquí había una decisión de producto: **¿traer una fuente propia?** Una fuente de marca se coloca en
`res/font/` (uno o varios `.ttf`, uno por peso) y se carga así:

```kotlin
// Ejemplo (NO usado en esta app) de una fuente propia bajo res/font/:
val BrandFont = FontFamily(
    Font(R.font.brand_regular, FontWeight.Normal),
    Font(R.font.brand_bold, FontWeight.Bold),
)
```

…y se referencia desde cada `TextStyle` (`fontFamily = BrandFont`).

En esta app **decidimos quedarnos en `FontFamily.Default`** (la fuente del sistema) a propósito. El
razonamiento, documentado en el `KDoc` de `Type.kt`:

- Una fuente del sistema ya **renderiza todos los idiomas/escrituras** que el dispositivo soporta
  —importante para la lección 08 (español + inglés)— con **cero coste de tamaño de APK**.
- La identidad de marca la aportan ya el **esquema de color** (§2–4) y los **pesos/tamaños deliberados**
  de la escala. Meter una fuente añadiría mantenimiento sin un beneficio visual proporcional aquí.

La lección importante no es "las fuentes propias son malas", sino que **saber cargar una y decidir con
criterio no hacerlo** es parte del diseño.

---

## 6. `dynamicColor` (Material You): de fijo a configurable

Llegamos al tercer eje. `NeverLateTheme` ya recibía un parámetro `dynamicColor: Boolean`, y su cuerpo
elige el esquema así:

```kotlin
val colorScheme = when {
    dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
        val context = LocalContext.current
        if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    }
    darkTheme -> DarkColorScheme   // ← esquema de marca
    else -> LightColorScheme       // ← esquema de marca
}
```

**El compromiso:**

- **`dynamicColor = true`** (Material You, solo Android 12+): el color lo deriva Android del **fondo de
  pantalla**. Máxima integración con el sistema; el usuario "ve su color". Coste: **la marca
  desaparece**.
- **`dynamicColor = false`**: la app usa **su** `LightColorScheme`/`DarkColorScheme`. Identidad de marca
  consistente en todos los dispositivos y todas las versiones de Android.

El error de partida era tenerlo **fijado a `true`**. La corrección tiene **dos partes**:

1. **Cambiar el valor por defecto a `false`** (marca primero). Ahora, si alguien llama a `NeverLateTheme`
   sin pasar `dynamicColor`, obtiene la identidad de marca, no algo que depende del dispositivo.
2. **Hacerlo elegible por el usuario** — lo que nos lleva al seam de preferencias.

---

## 7. Extender el seam de preferencias (no duplicarlo)

Esta es la parte donde reutilizamos **exactamente** la infraestructura de la lección 07. Añadir la
preferencia `dynamicColor` toca los mismos cuatro puntos que en su día tocó `themeMode`, y **ni uno
más**:

### 7.1. El modelo y el DataStore — `UserPreferencesRepository.kt`

```kotlin
data class UserPreferences(
    // ... name, onboarded, themeMode, remindersEnabled, syncCursor ...
    val dynamicColor: Boolean = false,   // ← feature 16, default false (marca primero)
)
```

La clave vive en el **mismo** fichero `user_prefs`, igual que todas las anteriores:

```kotlin
private object Keys {
    // ... THEME_MODE, REMINDERS_ENABLED, SYNC_CURSOR ...
    // Añadido en feature 16 — el mismo fichero "user_prefs", sin un segundo DataStore.
    val DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
}
```

Y la lectura es **tolerante**, como el resto: si la clave no existe (instalación nueva, o una app previa
a la feature 16), cae al valor por defecto en vez de fallar:

```kotlin
dynamicColor = preferences[Keys.DYNAMIC_COLOR] ?: false,
```

Junto a un `saveDynamicColor(enabled)` en la interfaz y su implementación DataStore, calcado de
`saveThemeMode`. **Este es el patrón que se repite desde la 07, 09, 11 y ahora 16**: una preferencia más
= un campo + una `Key` + una lectura tolerante + un `save…`. Nunca un DataStore nuevo.

### 7.2. El ViewModel — `SettingsViewModel.kt`

```kotlin
data class SettingsUiState(
    // ... themeMode, remindersEnabled ...
    val dynamicColor: Boolean = false,
)

// En el mapeo del flujo de preferencias a estado:
dynamicColor = preferences.dynamicColor,

fun onDynamicColorChanged(enabled: Boolean) {
    viewModelScope.launch { repository.saveDynamicColor(enabled) }
}
```

El estado se actualiza **reactivamente** observando el flujo del repositorio, exactamente como
`onThemeModeSelected`.

### 7.3. La UI — `SettingsScreen.kt`

Añadimos una fila con un `Switch` **dentro de la tarjeta de sección "Tema" que ya existe** (de la
lección 15), no una sección nueva. Y aquí aparece la decisión de la lección 15 aplicada de nuevo,
**hidden vs. disabled**:

```kotlin
// Material You solo tiene efecto en Android 12+ (VERSION_CODES.S). Por debajo,
// NeverLateTheme siempre pinta el esquema de marca, así que un switch aquí sería un
// control muerto: lo ocultamos por completo en vez de mostrarlo deshabilitado.
if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
    HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = stringResource(R.string.settings_dynamic_color_label),
            modifier = Modifier.weight(1f),
        )
        Switch(checked = uiState.dynamicColor, onCheckedChange = onDynamicColorChanged)
    }
}
```

El texto viene de recursos de string en **ambos** idiomas (lección 08): `settings_dynamic_color_label` =
"Usar los colores del sistema (Material You)" / "Use system colors (Material You)".

### 7.4. Aplicarlo — `MainActivity.kt`

El último eslabón: leer la preferencia donde ya se lee `themeMode` y pasarla al tema. Con un
*null-guard* mientras el DataStore aún carga (mismo razonamiento que `themeMode`):

```kotlin
val dynamicColor = userPreferences?.dynamicColor ?: false
// ...
NeverLateTheme(darkTheme = darkTheme, dynamicColor = dynamicColor) {
    // toda la app
}
```

Como `NeverLateTheme` está en la raíz de la composición, cambiar el switch **recompone los colores de
toda la app al instante** — no hace falta reiniciar. Y como el valor está persistido, **sí** sobrevive
al reinicio.

---

## 8. El flujo completo, de punta a punta

1. El usuario abre **Ajustes** en un móvil Android 12+ y ve el switch "colores del sistema".
2. Lo activa → `onDynamicColorChanged(true)` → `saveDynamicColor(true)` escribe la clave `dynamic_color`
   en `user_prefs`.
3. El flujo `userPreferences` emite un nuevo `UserPreferences(dynamicColor = true)`.
4. `MainActivity` lo recoge, `NeverLateTheme` recompone y elige `dynamicLightColorScheme(context)` →
   toda la app adopta el color del fondo de pantalla **en vivo**.
5. El usuario cierra y reabre la app: la clave sigue en disco, el flujo la vuelve a emitir, sigue en
   Material You. En un móvil < Android 12, el switch ni aparece y siempre se ve la marca.

---

## 9. Qué tocamos (y qué NO)

**Tocamos:**

- `ui/theme/Color.kt` — paleta de marca completa (roles claro + oscuro) desde la semilla `#3B5BDB`.
- `ui/theme/Theme.kt` — `Light/DarkColorScheme` cableando todos los roles; `dynamicColor` por defecto
  `false`.
- `ui/theme/Type.kt` — escala tipográfica completa `displayLarge … labelSmall` sobre `FontFamily.Default`.
- `data/UserPreferencesRepository.kt` — campo `dynamicColor`, `Keys.DYNAMIC_COLOR`, lectura tolerante,
  `saveDynamicColor`.
- `ui/settings/SettingsViewModel.kt` — `SettingsUiState.dynamicColor`, `onDynamicColorChanged`.
- `ui/settings/SettingsScreen.kt` — fila `Switch` dentro de la tarjeta "Tema", oculta bajo Android 12.
- `MainActivity.kt` — lee y pasa `dynamicColor` a `NeverLateTheme`.
- `res/values/strings.xml` + `res/values-en/strings.xml` — el texto del switch.

**NO tocamos** (y es importante notarlo): ningún backend, contrato de API, versión de base de datos,
permiso ni dependencia nueva. Tampoco reescribimos el seam de tema de la lección 07 ni el comportamiento
de `ThemeMode` (claro/oscuro/sistema): **se reutilizan, no se rehacen**. Esta feature es puramente
tematización del cliente.

---

## 10. Ejercicios

1. **La escala de urgencia.** El mockup (`docs/mockups/rediseno-ux-ui.html`) define colores semánticos
   `ok`/`soon`/`late` para las tareas según lo cerca que esté su *deadline*. Piensa: ¿los mapearías a
   roles existentes (`tertiary`, `error`…) o añadirías tokens de marca extra? ¿Qué coste de
   accesibilidad tiene inventarte colores fuera del algoritmo HCT? (Aplicarlos a las filas de tareas es,
   a propósito, una feature futura.)
2. **Fuente propia.** Descarga una fuente de Google Fonts (p. ej. *Inter*), colócala en `res/font/`,
   construye un `FontFamily` con al menos `Normal` y `Bold`, y aplícalo a la escala en `Type.kt`.
   Compara el tamaño del APK antes y después.
3. **Previsualizar los dos esquemas.** Escribe un `@Preview` de una pantalla envuelta en
   `NeverLateTheme(darkTheme = true)` y otro con `darkTheme = false`. Comprueba que ningún texto
   "desaparece" (contraste) — es justo lo que garantiza el emparejamiento `X`/`onX`.
4. **Regla anti-hardcode.** Busca en `ui/` cualquier `Color(0xFF…)` suelto en una pantalla. ¿Debería ser
   un rol de `MaterialTheme.colorScheme`? (Ese es el criterio de aceptación US-1: ningún hex de marca en
   las pantallas.)

---

## Resumen

- Material 3 asigna color **a roles** (`primary`, `surface`, `onSurfaceVariant`…), no a widgets; los
  widgets leen su rol, y por eso re-tematizar es cambiar **un** `ColorScheme`, no editar pantallas.
- Los ~40 colores de un tema se **derivan de una semilla** (`#3B5BDB`) con **HCT** y **paletas tonales**;
  cada rol es un **tono fijo** de una paleta, y ese mapeo lo fija Material 3.
- La **escala tipográfica** (`displayLarge … labelSmall`) es el mismo principio para el texto; cargar una
  fuente propia con `FontFamily` desde `res/font/` es opcional — aquí elegimos **no** hacerlo, con
  criterio.
- `dynamicColor` (Material You) es un **compromiso** marca ↔ sistema; lo pasamos de **fijo** a
  **configurable** y con default **marca primero**.
- Todo ello reutilizando el **seam de preferencias de la 07** (un campo + una `Key` + lectura tolerante +
  un `save…` + `ViewModel` + `NeverLateTheme`): **extender, no duplicar**.
