# Lección 15 — Iconos de sección y agrupación visual: filas ricas y tarjetas

> Objetivo: dar **jerarquía visual** a dos pantallas que hasta ahora eran listas planas de texto. El
> hub de Inicio (lección 02) mostraba sus opciones (Tareas, Artículos) como filas con **solo una
> etiqueta**; la pantalla de Ajustes (lección 07) apilaba sus tres bloques (Tema, Recordatorios,
> Cuenta) como títulos `Text` sueltos separados únicamente por *padding*. En esta lección cada opción
> de Inicio pasa a ser un `ListItem` **completo** —icono + etiqueta + descripción— y cada sección de
> Ajustes se **envuelve** en una `Card` con cabecera de icono y separadores `HorizontalDivider`. La
> lección de diseño que se repite desde la 14: **extendemos, no duplicamos**. No reescribimos ni una
> sola de las opciones ni de los controles existentes; les añadimos estructura alrededor.

## Conceptos que aprendes aquí

Partiendo de la lección 02 (hub de Inicio, `ListItem`, `Scaffold`) y la lección 07 (Ajustes, grupos de
radios, `SelectableRadioRow`):

- **El `ListItem` completo.** `leadingContent`, `headlineContent`, `supportingContent` y
  `trailingContent` como los cuatro bloques de construcción de una fila rica, y cómo una fila que solo
  tenía `headlineContent` crece hasta tener icono y descripción **sin cambiar su comportamiento**.
- **Añadir una dependencia vía *version catalog*.** Por qué el set de iconos que viene "de serie"
  (`material-icons-core`) no basta, cómo se declara `material-icons-extended` en
  `gradle/libs.versions.toml` y se referencia como `libs.androidx.material.icons.extended`, y por qué
  **nunca** se escribe la versión a mano.
- **Semántica de iconos.** Cuándo un `Icon` necesita `contentDescription` (aporta información) y cuándo
  es puramente decorativo (`contentDescription = null`), y por qué esa distinción le importa a
  TalkBack.
- **Agrupar superficies en Material 3.** `Card` frente a `Surface`, `HorizontalDivider`, y el uso del
  espaciado y la `tonalElevation` para separar secciones sin recargar la pantalla.

---

## 1. El punto de partida: dos listas planas

Recordemos cómo se veían las dos pantallas antes de esta lección.

En **Inicio** (lección 02), cada opción era un `ListItem` con un único bloque de contenido:

```kotlin
// Antes: una fila con solo la etiqueta
ListItem(
    headlineContent = { Text(label) },
)
```

El usuario veía "Tareas" y "Artículos" como dos textos, sin ninguna pista visual de qué hace cada uno
antes de tocarlo.

En **Ajustes** (lección 07), las tres secciones se apilaban con un `Text` de título y *padding*:

```kotlin
// Antes: títulos sueltos separados solo por espacio
Text(stringResource(R.string.settings_theme_section), style = ...)
// ...controles del tema...
Text(stringResource(R.string.settings_reminders_section), style = ...)
// ...controles de recordatorios...
```

El problema no es de contenido —la información está toda ahí— sino de **jerarquía**: nada le dice al
ojo dónde acaba una sección y empieza la siguiente, y nada anticipa qué hace cada opción. Para el
público de esta app (personas con TDAH), una pantalla que se lee "de un vistazo" no es un lujo estético:
reduce la carga cognitiva de decidir dónde tocar.

---

## 2. La dependencia que ya teníamos: `material-icons-extended`

El primer concepto nuevo es de *tooling*, no de UI: **de dónde salen los iconos**.

Compose incluye dos librerías de iconos:

- **`material-icons-core`** — un puñado de iconos de uso muy común (`Settings`, `ArrowBack`, `Check`,
  `Close`…). Viene arrastrado por `material3`, así que casi siempre está disponible sin pedir nada.
- **`material-icons-extended`** — el catálogo **completo** de iconos Material (miles): `Assignment`,
  `MenuBook`, `Palette`, `Notifications`, `AccountCircle`… Es una dependencia **aparte** porque es
  grande, y no se incluye por defecto para no inflar el APK de quien no la necesita.

Para esta lección necesitamos iconos que **no** están en el set *core* (un icono de "tarea"/portapapeles
para Tareas, uno de "libro" para Artículos, y los de las cabeceras de Ajustes). Ahí es donde entra el
set *extended*.

### El *version catalog*, otra vuelta de tuerca

Como vimos en lecciones anteriores, las versiones de las dependencias **no** se escriben a mano en los
`build.gradle.kts`: viven en un único sitio, el *version catalog* `gradle/libs.versions.toml`. Ahí está
declarada la librería:

```toml
# gradle/libs.versions.toml
[libraries]
androidx-material-icons-extended = { group = "androidx.compose.material", name = "material-icons-extended" }
```

Fíjate en un detalle: **no lleva `version`**. La versión de los artefactos de Compose la fija el
**BOM** (*Bill of Materials*) de Compose que el módulo ya importa, de modo que todos los artefactos van
coordinados entre sí. Escribir una versión aquí sería no solo redundante, sino una fuente de
desincronización.

Y en el módulo de la app se referencia por su alias, sin número alguno:

```kotlin
// app/build.gradle.kts
implementation(libs.androidx.material.icons.extended)
```

> **Nota honesta sobre esta feature.** Esta dependencia **ya estaba** en el proyecto: la añadimos en la
> lección 05 (para `Icons.Filled.Pause` del widget) y la reutilizó la 14 (`CalendarMonth`, `Clear`).
> Así que en esta lección **no** hemos añadido ninguna línea nueva al catálogo: solo la hemos
> reutilizado. El concepto que enseña la lección —*cómo* se declara una dependencia de iconos por
> catálogo y por qué el BOM fija la versión— sigue siendo válido y merece explicarse; simplemente el
> paso mecánico de "añadir la línea" ya estaba hecho. Añadir un alias duplicado, de hecho, **rompería**
> la compilación.

---

## 3. Inicio: de fila con etiqueta a `ListItem` completo

El `ListItem` de Material 3 tiene **cuatro** ranuras de contenido, y hasta ahora solo usábamos una:

| Ranura | Qué va | En nuestra fila |
| --- | --- | --- |
| `leadingContent` | Elemento de cabecera a la izquierda | El **icono** de la sección |
| `headlineContent` | El texto principal | La **etiqueta** ("Tareas") |
| `supportingContent` | Texto secundario, debajo del principal | La **descripción** de una línea |
| `trailingContent` | Elemento a la derecha (switch, flecha…) | *(sin usar aquí)* |

Primero ampliamos el modelo de datos que alimenta cada fila para que lleve icono y descripción, además
de la etiqueta y el `onClick` que ya tenía:

```kotlin
// HomeScreen.kt
private data class HomeOption(
    val label: String,
    val description: String,
    val icon: ImageVector,   // el tipo de un icono vectorial de Compose
    val onClick: () -> Unit,
)
```

`ImageVector` es el tipo de los iconos de Compose: lo que devuelven `Icons.Filled.Settings`,
`Icons.AutoMirrored.Filled.MenuBook`, etc. Guardarlo en el `data class` mantiene la lista de opciones
declarativa: cada fila es un dato, no código repetido.

Al construir la lista, cada opción trae su icono y su cadena de descripción (siempre desde recursos,
nunca un literal):

```kotlin
val options = listOf(
    HomeOption(
        label = stringResource(R.string.home_option_tasks),
        description = stringResource(R.string.home_option_tasks_description),
        icon = Icons.AutoMirrored.Filled.Assignment,
        onClick = onTasksClick,
    ),
    HomeOption(
        label = stringResource(R.string.home_option_articles),
        description = stringResource(R.string.home_option_articles_description),
        icon = Icons.AutoMirrored.Filled.MenuBook,
        onClick = onArticlesClick,
    ),
)
```

### `Icons.AutoMirrored`: el detalle de los idiomas RTL

¿Por qué `Icons.AutoMirrored.Filled.MenuBook` y no `Icons.Filled.MenuBook`? Un icono de libro abierto
o de lista "apunta" en una dirección. En idiomas que se leen de derecha a izquierda (árabe, hebreo) la
interfaz entera se **refleja**, y estos iconos deben reflejarse con ella. Las variantes
`Icons.AutoMirrored.*` hacen justo eso automáticamente; las normales no. Es gratis usarlas y evita un
bug sutil de localización, así que se prefieren siempre que existan.

### La fila rica: `HomeOptionCard`

El composable que dibuja cada fila **no** se ha duplicado por opción: sigue siendo uno solo, que ahora
recibe tres parámetros (`icon`, `label`, `description`) y los coloca en las tres ranuras del `ListItem`:

```kotlin
@Composable
private fun HomeOptionCard(
    label: String,
    description: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        ListItem(
            leadingContent = { Icon(imageVector = icon, contentDescription = null) },
            headlineContent = { Text(label) },
            supportingContent = { Text(description) },
        )
    }
}
```

Esa `contentDescription = null` no es un descuido: es una **decisión**, y la explicamos en la sección 5.

---

## 4. Ajustes: envolver, no reescribir

El objetivo en Ajustes es puramente estructural: los mismos controles de siempre, pero cada bloque
dentro de su propia **tarjeta** con cabecera de icono. La clave para no duplicar código es un pequeño
composable reutilizable que aporta *solo* la envoltura:

```kotlin
@Composable
private fun SettingsSectionCard(
    title: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,   // el bloque existente entra aquí, tal cual
) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(imageVector = icon, contentDescription = null, modifier = Modifier.padding(end = 12.dp))
                Text(text = title, style = MaterialTheme.typography.titleMedium)
            }
            HorizontalDivider(modifier = Modifier.padding(top = 12.dp, bottom = 16.dp))
            content()
        }
    }
}
```

Fíjate en el tipo del último parámetro: `content: @Composable ColumnScope.() -> Unit`. Es una **lambda
con *receiver***: lo que escribas dentro se compone como si estuviera dentro de la `Column` de la
tarjeta, así que los bloques pueden usar `Modifier.weight`, `Arrangement`, etc., sin ceremonia. Es el
mismo patrón que usan `Column`, `Row` o `Scaffold` internamente. Poner el `content` como **último**
parámetro permite la sintaxis de *trailing lambda*, que es lo que hace que las tres llamadas se lean
tan limpias:

```kotlin
SettingsSectionCard(
    title = stringResource(R.string.settings_theme_section),
    icon = Icons.Filled.Palette,
) {
    // El grupo de radios del tema, EXACTAMENTE el de la lección 07, sin cambios:
    Column(modifier = Modifier.selectableGroup()) {
        themeOptions.forEach { option ->
            SelectableRadioRow(
                label = stringResource(option.labelRes),
                selected = uiState.themeMode == option.mode,
                onClick = { onThemeModeSelected(option.mode) },
            )
        }
    }
}
```

Las otras dos secciones (Recordatorios y Cuenta) se envuelven igual, con `Icons.Filled.Notifications`
y `Icons.Filled.AccountCircle` en sus cabeceras. Ni el `Switch` de recordatorios, ni la lista de
tiempos de antelación, ni `ExactAlarmPermissionNotice`, ni el `TextButton` de cuenta (con su ramas
`LoggedIn` / invitado de la lección 13) se han tocado: **entran verbatim** como `content`.

### `HorizontalDivider`: separar dentro de la tarjeta

Dentro de la sección de Recordatorios había dos sub-bloques que antes solo separaba el *padding*: el
interruptor de encender/apagar y, debajo, la lista de tiempos de antelación (que solo aparece con los
recordatorios activados). Ahora un `HorizontalDivider` los ancla como sub-bloques distintos:

```kotlin
if (uiState.remindersEnabled) {
    HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
    // ...etiqueta + lista de radios de antelación + aviso de alarma exacta...
}
```

`HorizontalDivider` es el sucesor en Material 3 del antiguo `Divider`: una línea fina de un solo píxel
que usa el color del tema, por lo que se adapta a claro/oscuro sin que hagamos nada.

### El espaciado entre tarjetas

Las tres tarjetas se separan entre sí con `Arrangement.spacedBy(16.dp)` en la `Column` exterior, y esa
`Column` **conserva el `verticalScroll`** de la lección 07. Esto último importa: al añadir *padding* y
elevación, la sección de Recordatorios (con su lista de tiempos y el aviso de alarma exacta) puede
crecer bastante, y sin *scroll* la tarjeta de Cuenta quedaría fuera de la pantalla, inalcanzable.

---

## 5. Semántica de iconos: cuándo describir y cuándo callar

Este es el concepto de **accesibilidad** de la lección. Un `Icon` en Compose siempre pide un
`contentDescription`, y tienes dos opciones legítimas:

- **Una cadena** → el lector de pantalla (TalkBack) la anuncia. Se usa cuando el icono **es** la única
  fuente de esa información. Ejemplo: el botón de Ajustes en la barra superior es *solo* un engranaje,
  sin texto al lado; si no lo describiéramos, TalkBack anunciaría "botón" a secas. Por eso lleva
  `contentDescription = stringResource(R.string.home_settings_content_description)`.
- **`null`** → el icono se marca explícitamente como **decorativo** y TalkBack lo **ignora**. Se usa
  cuando un texto adyacente ya transmite el mismo significado; describir el icono solo duplicaría el
  anuncio.

En esta feature, **todos** los iconos que añadimos son decorativos:

- En las filas de Inicio, la etiqueta ("Tareas") se lee como `headlineContent` y la descripción justo
  después como `supportingContent`. El icono no aporta nada que TalkBack necesite anunciar por separado
  → `contentDescription = null`.
- En las cabeceras de las tarjetas de Ajustes, el título ("Tema", "Recordatorios", "Cuenta") está
  literalmente al lado del icono. Describir además el icono sería repetir → `contentDescription = null`.

La regla, en una frase: **describe el icono si es la única forma de saber qué significa; ponlo a `null`
si el texto de al lado ya lo dice.** Un `null` explícito no es pereza: le dice a Compose "sé que esto no
lleva descripción, es a propósito", lo cual es distinto de olvidarlo.

---

## 6. `Card` frente a `Surface`, y por qué elegimos `Card`

Material 3 ofrece dos contenedores para "elevar" contenido sobre el fondo:

- **`Surface`** — la primitiva base. Aporta color de fondo, forma, elevación y semántica de contenedor,
  pero **tú** decides todo (padding, forma, cuándo se usa). Es lo que hay debajo de casi todo Material.
- **`Card`** — una `Surface` con **opinión**: forma redondeada, color de contenedor y una elevación por
  defecto pensados específicamente para "agrupar un bloque de contenido relacionado". Es exactamente
  nuestro caso de uso, así que usamos `Card` y no reinventamos sus valores.

La elevación de una `Card` en Material 3 es **tonal**, no una sombra dura: la superficie se tiñe
ligeramente con el color primario del tema según su altura (`tonalElevation`). En modo oscuro esto se
traduce en un fondo un punto más claro que el de la pantalla, en vez de una sombra —que apenas se vería
sobre negro—. Por eso **no tocamos colores a mano**: dejamos que el tema (`NeverLateTheme`) resuelva el
contraste en claro y en oscuro, y nos limitamos a elegir el componente correcto.

> **Regla de oro que se repite en el proyecto:** nunca copiamos colores ni elevaciones "a ojo" desde el
> mockup (`docs/mockups/rediseno-ux-ui.html`). El mockup es **dirección**, no código. Usamos los
> componentes de Material 3 con sus valores por defecto y los *tokens* del tema, y así claro/oscuro
> funcionan gratis.

---

## 7. Cadenas nuevas: siempre en los dos idiomas

Coherentes con la lección 08 (i18n), toda cadena visible va a recursos, y a **ambos** ficheros. En esta
feature solo hicieron falta dos cadenas nuevas —las descripciones de las opciones de Inicio—, porque
los títulos de las cabeceras reutilizan los `settings_*_section` que ya existían y ningún icono nuevo
necesitó descripción (todos son decorativos):

```xml
<!-- res/values/strings.xml (base en español) -->
<string name="home_option_tasks_description">Gestiona tus tareas y sus fechas límite.</string>
<string name="home_option_articles_description">Consejos y artículos para gestionar tu tiempo.</string>
```

```xml
<!-- res/values-en/strings.xml (variante en inglés) -->
<string name="home_option_tasks_description">Manage your tasks and their deadlines.</string>
<string name="home_option_articles_description">Tips and articles to help you manage your time.</string>
```

Si una cadena aparece en `values/` pero falta en `values-en/`, un usuario con el móvil en inglés vería
el texto en español para esa línea concreta. Añadir siempre las dos a la vez es lo que evita ese
*fallback* silencioso.

---

## 8. Qué probamos

Como la lógica nueva es de UI, las comprobaciones son tests de **Compose** (en `androidTest`), siguiendo
el mismo patrón que `TasksScreenTest` y `SettingsScreenTest`: se lanza el composable *stateless*
directamente con estado y *callbacks* falseados, y se afirma sobre lo **observable**, no sobre los
internos:

- **Inicio:** que se muestran las dos descripciones nuevas junto a sus etiquetas, y que tocar cada fila
  sigue invocando el *callback* de navegación correcto (envolver la fila en `Card`/`ListItem` no cambió
  el comportamiento).
- **Ajustes:** que aparecen las tres cabeceras de sección, y que los controles siguen funcionando dentro
  de las tarjetas: seleccionar un tema invoca `onThemeModeSelected`, el `Switch` invoca
  `onRemindersEnabledChanged`, y la acción de cuenta muestra "Iniciar sesión" (invitado) o "Cerrar
  sesión" (autenticado) según el estado.

No probamos los iconos ni los composables privados por dentro: probamos texto mostrado y clics, que es
lo que el usuario percibe.

---

## 9. Resumen

- Un **`ListItem`** tiene cuatro ranuras (`leadingContent`, `headlineContent`, `supportingContent`,
  `trailingContent`); pasar de una fila con solo etiqueta a una fila rica es **rellenar más ranuras**,
  no reescribir la fila.
- Los iconos "completos" viven en **`material-icons-extended`**, una dependencia declarada por
  ***version catalog*** cuya versión fija el **BOM** de Compose —nunca se escribe a mano—.
- Un `Icon` decorativo (con texto adyacente que ya lo explica) lleva **`contentDescription = null`**;
  uno que es la única fuente de su significado lleva una **cadena**. Es una decisión de accesibilidad,
  no un descuido.
- Para agrupar se usa **`Card`** (una `Surface` con opinión), se separan sub-bloques con
  **`HorizontalDivider`**, y se confía en la **elevación tonal** del tema en vez de colores a mano —así
  claro y oscuro funcionan solos—.
- Y, una vez más: **extender, no duplicar.** Ni una opción de Inicio ni un control de Ajustes se
  reescribió; solo les pusimos estructura alrededor con un composable envoltorio reutilizable.
