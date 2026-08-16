# Lección 05b — El tema no cruza: dar identidad visual a un widget con Glance

> Objetivo: que el widget de la lección 05 **se parezca a la app**. Esquinas redondeadas, paleta de
> marca, tema claro/oscuro de verdad, la misma escala de urgencia de cuatro niveles que usa la lista
> y el indicador de prioridad de la 13b. Suena a "cambiar cuatro colores". **No lo es.** En cuanto
> intentas reutilizar el color que ya tienes, descubres que `MaterialTheme.colorScheme` y
> `NeverLateExtras.colors` **no existen** dentro de la composición del widget, y que la función que
> pinta la urgencia en la tarjeta no se puede llamar desde aquí. El mismo color, dos mundos. Esta
> lección va de ese muro y de cómo se cruza sin duplicar la paleta.

## Conceptos que aprendes aquí

Partiendo de la lección 05 (el widget con Glance y `RemoteViews`), la 16 (identidad visual y paleta
de marca), la 17 (color de urgencia con `urgencyLevelFor`) y la 13b (prioridad de tarea):

1. **Por qué un `CompositionLocal` no cruza de Compose a Glance.** Qué es realmente un tema en
   Compose, por qué hay *dos* árboles de composición y qué significa eso para reutilizar código.
2. **`GlanceTheme` + `glance-material3` frente a `ColorProvider(day =, night =)`.** Los dos
   mecanismos que tiene un widget para resolver claro/oscuro, cuándo se usa cada uno, y por qué esta
   feature acaba usando **los dos** en vez de elegir.
3. **Límites de API dentro de un widget.** `GlanceModifier.cornerRadius` es API 31+ y el `minSdk` del
   proyecto es 24: cómo se degrada con elegancia **sin escribir un solo `if (Build.VERSION.SDK_INT)`**.
4. **Previews de widget** (`previewLayout` / `previewImage`) y por qué son parte del producto.
5. De propina: **el color nunca como único portador de información**, aplicado a una superficie que
   no tiene tooltips ni leyenda.

---

## 1. El punto de partida: cuatro hexadecimales del template morado

Así empezaba `PendingTasksWidget.kt` antes de esta feature:

```kotlin
/** Background/text colors kept local and simple — see the feature spec's "no advanced theming". */
private val WidgetBackground = Color(0xFFEFE6FF)
private val WidgetTitleColor = ColorProvider(Color(0xFF4A3B77))
private val WidgetTextColor = ColorProvider(Color(0xFF1B1B1B))
private val WidgetTimedOutColor = ColorProvider(Color(0xFFB3261E))
```

Cuatro valores fijos, heredados del template de widget que genera Android Studio. Merece la pena
mirarlos con atención, porque cada uno esconde un problema distinto:

- **No son de la marca.** La paleta de la app (lección 16) nace de la semilla `#3B5BDB`, un azul.
  Ese `0xFFEFE6FF` es un lila del template: el widget llevaba desde la lección 05 anunciando una
  identidad visual que la app ya no tiene.
- **No tienen variante oscura.** Un `Color(0xFFEFE6FF)` es *un* color, no un par. Con el sistema en
  modo oscuro, el widget seguía siendo un rectángulo lila pálido brillando en la pantalla de inicio.
- **Codifican la urgencia como un binario.** El texto era rojo si `remainingMillis == 0L` y morado si
  no. La app tiene una escala de **cuatro** niveles desde la 17 (`Calm` / `Soon` / `Urgent` /
  `Overdue`).

El comentario admitía la deuda con honestidad ("no advanced theming" era una exclusión explícita del
spec de la 05). Esta lección es el día que se paga.

> **Antes de nada, un requisito previo que ya está hecho.** La lección 20b sacó el formateo del texto
> del dominio: `PendingTaskRow` ya lleva los **milisegundos crudos** en vez de un `String` ya
> formateado. Sin eso, el widget no podría *decidir* nada sobre su cuenta atrás —ni el color, ni el
> peso— porque recibiría el texto ya hecho. El orden de las features no es casualidad.

---

## 2. Por qué `colorForUrgency` no se puede llamar desde el widget

El instinto correcto en este proyecto es "extiende, no dupliques". La lista de tareas ya sabe pintar
la urgencia:

```kotlin
// ui/tasks/TasksScreen.kt
private fun colorForUrgency(level: UrgencyLevel): Color = when (level) {
    UrgencyLevel.Calm -> NeverLateExtras.colors.calm
    UrgencyLevel.Soon -> NeverLateExtras.colors.soon
    UrgencyLevel.Urgent, UrgencyLevel.Overdue -> MaterialTheme.colorScheme.error
}
```

Y la prioridad (lección 13b):

```kotlin
// ui/tasks/PriorityUi.kt
fun Priority.indicatorColor(): Color? = when (this) {
    Priority.NONE -> null
    Priority.LOW -> MaterialTheme.colorScheme.secondary
    Priority.MEDIUM -> MaterialTheme.colorScheme.tertiary
    Priority.HIGH -> MaterialTheme.colorScheme.primary
}
```

Parece obvio: importarlas y ya está. **No se puede.** Y el motivo es el concepto central de esta
lección.

### 2.1 Un tema en Compose es un `CompositionLocal`, y un `CompositionLocal` es un árbol

Cuando escribes `MaterialTheme.colorScheme`, no estás leyendo una constante global. Estás leyendo un
**`CompositionLocal`**: un valor que alguien, más arriba en el árbol de composición, ha *provisto*.
`MaterialTheme { … }` hace exactamente eso — instala el `ColorScheme` en el árbol para que todo lo
que cuelgue de él pueda leerlo sin pasarlo por parámetro. `NeverLateExtras.colors` (lección 16)
funciona igual con nuestros colores extendidos.

La palabra clave es **el árbol**. Y el widget tiene otro.

La app compone dentro de `NeverLateTheme { … }` en `MainActivity`. El widget compone dentro de
`provideContent { … }`, en un proceso y un momento completamente distintos, y su composición **no la
pinta Compose**: Glance la traduce a `RemoteViews`, la estructura serializable que el *launcher*
infla en **su** proceso. Nadie ha llamado a `MaterialTheme` en esa composición, así que el
`CompositionLocal` de Material 3 no está provisto. Leerlo devolvería el `ColorScheme` por defecto
(el morado de Material, otra vez) o directamente fallaría.

Hay además una segunda barrera, más dura todavía: **los tipos no coinciden**. `colorForUrgency`
devuelve un `androidx.compose.ui.graphics.Color`, y `TextStyle` de Glance no acepta un `Color`, sino
un **`ColorProvider`** — precisamente porque un widget necesita *diferir* la decisión del color hasta
que el launcher lo pinte, sabiendo la configuración del sistema en ese momento. No es un
inconveniente de la API: es la diferencia entre "pintar ahora" y "describir cómo se pintará".

### 2.2 La regla que te llevas

> Un `@Composable` de Material 3 **no es** código reutilizable desde Glance, por mucho que lo
> parezca. Lo que sí se reutiliza es lo que está **por debajo** del tema: las funciones puras
> (`urgencyLevelFor`, `pendingRowsFor`) y los valores crudos de `Color.kt`.

Fíjate en el reparto que sale de aquí, porque es el mapa entero de la feature:

| Qué | ¿Se reutiliza desde Glance? | Por qué |
|---|---|---|
| `urgencyLevelFor`, `pendingRowsFor`, `deadlineProgressFor` | **Sí, tal cual** | Funciones puras: sin `Context`, sin composición, sin tema |
| `primaryContainerLight`, `urgencyCalmDark`… (`ui/theme/Color.kt`) | **Sí** | Son `Color` crudos, constantes; solo hay que envolverlos |
| `LightColorScheme` / `DarkColorScheme` (`Theme.kt`) | **Sí, con un puente** | Son objetos, no un `CompositionLocal` — ver §3 |
| `MaterialTheme.colorScheme`, `NeverLateExtras.colors` | **No** | Leen un `CompositionLocal` que el widget nunca provee |
| `colorForUrgency`, `Priority.indicatorColor()` | **No** | `@Composable` de Material 3 + devuelven el tipo equivocado |

---

## 3. El puente: `glance-material3`

Sabiendo eso, había dos caminos. El spec obligaba a elegir **y justificar**, no a elegir por inercia:

- **(a)** Un fichero pequeño de colores Glance que envuelva los `*Light`/`*Dark` de `Color.kt` a
  mano: `ColorProvider(day = surfaceLight, night = surfaceDark)`, y así con cada rol que use el
  widget.
- **(b)** Añadir `androidx.glance:glance-material3` y envolver el widget en
  `GlanceTheme(colors = ColorProviders(light = LightColorScheme, dark = DarkColorScheme))`.

### 3.1 Por qué (b)

La opción (a) es tentadora porque no añade dependencias, y de hecho **no duplica ningún
hexadecimal**: leería los mismos valores de `Color.kt`. Pero duplica otra cosa, más difícil de ver y
más fácil de que se pudra: **el mapeo rol→color**. `Theme.kt` ya decide que el fondo es
`surfaceLight` en claro y `surfaceDark` en oscuro, que el texto encima es `onSurface*`, etcétera. Con
(a), el widget vuelve a decidirlo por su cuenta. El día que alguien retoque un rol en `Theme.kt`, la
app cambia y el widget no.

Es exactamente la clase de duplicación que este proyecto lleva rechazando desde el principio
(`pendingRowsFor` para la regla, `formatRemainingLabel` para el texto, `colorForUrgency` para el
color): **una sola casa por decisión**.

El coste de (b) resultó ser mínimo: `glance-material3` va en el mismo tren de versiones que el
`glance-appwidget` que ya estaba en el build, así que entra en el catálogo **reutilizando el
`version.ref` que ya existía** — sin pin nuevo:

```toml
# gradle/libs.versions.toml
androidx-glance-material3 = { group = "androidx.glance", name = "glance-material3", version.ref = "glance" }
```

(Y sí: **siempre por el catálogo**, nunca una versión a mano en `build.gradle.kts`.)

### 3.2 `private` → `internal`: una visibilidad que *es* la decisión

Para poder pasarle al widget los `ColorScheme` de la app hay que dejarle verlos:

```kotlin
// ui/theme/Theme.kt
internal val LightColorScheme = lightColorScheme( … )   // antes: private
internal val DarkColorScheme  = darkColorScheme( … )    // antes: private
```

Este cambio de una palabra es el corazón de la feature, así que merece el comentario que lleva al
lado en el código. `private` decía "solo `NeverLateTheme` compone con estos esquemas". `internal`
dice "hay un segundo consumidor dentro del módulo, y hereda este mapeo **literalmente** en vez de
volver a inventarlo". Abrir visibilidad es una decisión de diseño, no un atajo para que compile.

Y el widget queda así:

```kotlin
// PendingTasksWidget.kt
GlanceTheme(colors = ColorProviders(light = LightColorScheme, dark = DarkColorScheme)) {
    PendingTasksWidgetContent(model = model, context = context)
}
```

> **Ojo con `GlanceTheme()` sin argumentos.** Esa variante usa **Material You** (color dinámico del
> fondo de pantalla) en API 31+. Es una línea más corta y una decisión distinta: el widget dejaría de
> ser azul de marca en muchos móviles. La app tiene `dynamicColor = false` por defecto, así que el
> widget la acompaña. Que el widget siga la preferencia de tema *de dentro de la app* es harina de
> otro costal — habría que leer el DataStore `user_prefs` desde `provideGlance`, y eso ya es
> comportamiento, no aspecto. Queda diferido, escrito en el spec.

### 3.3 Lo que el puente **no** trae (y aquí es donde reaparece `ColorProvider`)

Aquí viene el matiz que hace esta lección más interesante que "añade una dependencia y ya".

`ColorProviders` traduce un `ColorScheme` de Material 3 a los roles que Glance conoce. Pero el widget
necesita tres colores que **no están** ahí:

- **`calm` y `soon`.** No son roles de Material 3 — de hecho existen en `NeverLateExtendedColors`
  (lección 16) precisamente porque Material 3 **no tiene** un rol para "esto va con calma". Ningún
  puente puede traer lo que el estándar no define.
- **`outlineVariant`.** Este sorprende más: *sí* es un rol real de Material 3, y `ColorScheme` lo
  tiene. Pero el `ColorProviders` de Glance expone solo `outline`, no `outlineVariant`. El puente es
  más estrecho que el esquema.

Para los tres, la solución es la opción (a) — **aplicada donde de verdad hace falta**:

```kotlin
// ui/widget/WidgetColors.kt
private val CalmColor: ColorProvider = ColorProvider(day = urgencyCalmLight, night = urgencyCalmDark)
private val SoonColor: ColorProvider = ColorProvider(day = urgencySoonLight, night = urgencySoonDark)
val dividerColor: ColorProvider = ColorProvider(day = outlineVariantLight, night = outlineVariantDark)
```

Y con eso, los dos "gemelos" del widget:

```kotlin
@Composable
fun urgencyColorProvider(level: UrgencyLevel): ColorProvider = when (level) {
    UrgencyLevel.Calm -> CalmColor
    UrgencyLevel.Soon -> SoonColor
    UrgencyLevel.Urgent, UrgencyLevel.Overdue -> GlanceTheme.colors.error
}

@Composable
fun Priority.glanceIndicatorColor(): ColorProvider? = when (this) {
    Priority.NONE -> null
    Priority.LOW -> GlanceTheme.colors.secondary
    Priority.MEDIUM -> GlanceTheme.colors.tertiary
    Priority.HIGH -> GlanceTheme.colors.primary
}
```

Compáralas con `colorForUrgency` y `Priority.indicatorColor()` del §2: **misma estructura, mismo
`when`, mismos roles**. Cambia de dónde sale el color, no qué color es.

> **La deuda que esto deja, dicha en voz alta.** Estas dos funciones duplican un *mapeo*. Si alguien
> cambia `colorForUrgency` y no toca su gemela, la tarjeta y el widget dirán cosas distintas con el
> mismo color. No hay compilador que lo detecte, así que la mitigación es KDoc cruzado en las cuatro
> funciones diciendo explícitamente que son gemelas. Es una mitigación **débil** y el código lo
> reconoce; la solución fuerte (un enum de *tokens* `UrgencyLevel → token` que consuman los dos
> mundos) queda apuntada como trabajo futuro en vez de colarse aquí. Reconocer una deuda con
> precisión vale más que fingir que no existe.

---

## 4. `ColorProvider(day =, night =)`: claro/oscuro sin `CompositionLocal`

Vale la pena parar en qué es exactamente un `ColorProvider`, porque es el concepto que sustituye al
tema en el mundo de los widgets.

En la app, "¿qué color toca?" se responde **en tiempo de composición**: `isSystemInDarkTheme()` mira
la configuración, `NeverLateTheme` elige un `ColorScheme` y lo provee. Hay un momento en el que la
pregunta se resuelve, y todo el árbol lo ve.

En un widget no hay tal momento, porque **quien pinta no eres tú**. Tú produces `RemoteViews` y se
las das al launcher, que las infla más tarde, en su proceso, con **su** configuración. Un
`ColorProvider(day = X, night = Y)` es literalmente eso: en vez de un color, mandas **las dos
respuestas** y dejas que el sistema elija cuando toque.

Esto tiene una consecuencia práctica que conviene saber antes de que te sorprenda: **el modo oscuro
del widget lo decide el launcher, no la app**. Si un launcher corre con un `uiMode` distinto al de tu
app, el widget puede salir en la variante "equivocada". Es inherente a los widgets, y es otra razón
para que el widget **no** lea la preferencia de tema de dentro de la app: serían dos fuentes de
verdad en conflicto.

---

## 5. `cornerRadius` es API 31+ y el `minSdk` es 24

El widget tenía esquinas cuadradas. La forma directa de arreglarlo es:

```kotlin
GlanceModifier.cornerRadius(16.dp)   // ⚠️ requiere API 31
```

Y el proyecto soporta desde **API 24**. Este es el segundo muro de la lección, y su interés está en
**cómo no** resolverlo.

### 5.1 Por qué no un `if (Build.VERSION.SDK_INT >= 31)`

Es lo primero que se le ocurre a cualquiera:

```kotlin
val modifier = if (Build.VERSION.SDK_INT >= 31) GlanceModifier.cornerRadius(16.dp) else GlanceModifier
```

Compila, no rompe... y **falla en el objetivo**. Las esquinas cuadradas seguirían exactamente en
API 24–30: los móviles más antiguos, con launchers más viejos, que son los que **menos** probable es
que redondeen el widget por su cuenta. Es decir: el bug sobreviviría justo donde más se nota.

> Degradar con elegancia no es "que no pete en versiones antiguas". Es **que la versión antigua
> también reciba el arreglo**, aunque sea por otro camino.

### 5.2 La forma en un drawable, el color en el tema

El camino que sí funciona en todas partes es tan viejo como Android: un `<shape>`.

```xml
<!-- res/drawable/widget_background.xml -->
<shape android:shape="rectangle">
    <corners android:radius="@dimen/widget_corner_radius" />
    <solid android:color="@android:color/white" />
</shape>
```

Pero fíjate en el `<solid>` blanco: **no es el color del widget**. Si metiéramos aquí el color de
marca, habríamos vuelto al punto de partida (un hexadecimal fijo sin variante oscura, ahora escondido
en XML). El truco es que el drawable aporte **solo la forma**, y el color siga viniendo del tema:

```kotlin
GlanceModifier.background(
    ImageProvider(R.drawable.widget_background),
    colorFilter = ColorFilter.tint(GlanceTheme.colors.background),
)
```

`ColorFilter.tint` repinta el drawable en tiempo de render con el `ColorProvider` del tema. Resultado:
**cero hexadecimales en XML**, cero duplicación claro/oscuro y **un solo camino de código** en todas
las APIs. El mismo patrón se repite para la banda de cabecera, con `primaryContainer` y un drawable
cuyas esquinas inferiores son cuadradas para que encaje a ras con las filas.

### 5.3 `values-v31`: el `if` que no escribes

Queda un detalle fino. En Android 12+ el sistema publica **su propio** radio para widgets, el que usa
el launcher en su marco. Lo suyo es respetarlo en vez de adivinar 16dp. ¿Y no vuelve eso a exigir un
`if`? No:

```xml
<!-- res/values/dimens.xml -->
<dimen name="widget_corner_radius">16dp</dimen>

<!-- res/values-v31/dimens.xml -->
<dimen name="widget_corner_radius">@android:dimen/system_app_widget_background_radius</dimen>
```

Un mismo nombre de recurso, dos definiciones, y el **sistema de recursos** elige según la API. Es la
misma máquina que ya usas para `values-en` (idioma, lección 08) o `values-night` (modo oscuro),
aplicada a la versión de plataforma. El Kotlin no se entera de que existen dos casos: no hay rama, no
hay `SDK_INT`, no hay nada que se olvide de actualizar cuando suba el `minSdk`.

> Ésta es la moraleja transversal del apartado: **en Android, muchos `if` se escriben como
> calificadores de recurso.** Cuando te descubras ramificando por idioma, por tema, por tamaño de
> pantalla o por versión de API, pregúntate si el sistema de recursos no lo hace ya por ti.

---

## 6. Previews: el widget también existe antes de colocarlo

`res/xml/pending_tasks_widget_info.xml` no declaraba ni `previewImage` ni `previewLayout`. Efecto: en
el selector de widgets del launcher aparecía el **icono genérico de la app**, no el widget. La primera
impresión del producto —el momento exacto en el que alguien decide si lo instala— estaba sin diseñar.

Se declaran los dos, porque cubren cosas distintas:

```xml
android:previewLayout="@layout/widget_preview"     <!-- API 31+: layout real, sigue el tema -->
android:previewImage="@drawable/widget_preview_image"  <!-- API 24-30, herramientas, tiendas -->
```

- **`previewLayout`** es un layout XML de verdad que el selector infla. Es el preview bueno: se ve
  como el widget y respeta el tema del sistema.
- **`previewImage`** es una imagen. Aquí es un **vector escrito a mano**, no una captura PNG: este
  repo no tiene emulador en CI para capturarla, y un vector es un fichero de texto revisable en el
  diff.

> **Una excepción documentada al "nada de layouts XML".** El `CLAUDE.md` del proyecto prohíbe los
> layouts XML: la UI es Compose. `previewLayout` **no puede** ser un composable por construcción — lo
> infla el selector del sistema, fuera de tu proceso, antes de que exista ningún widget. Así que es
> una excepción **acotada y justificada**, anotada en `docs/arquitectura.md` para que el siguiente
> que la lea no la confunda con una convención que se está aflojando. Las convenciones se saltan por
> escrito, o no se saltan.
>
> Un corolario menor pero real: como ese layout no es una composición Glance, **no puede leer
> `GlanceTheme.colors`**. Sus colores viven en `res/values/colors.xml` copiados de `Color.kt` (solo
> los claros: una miniatura estática no sigue el modo oscuro, solo lo hace el widget vivo). Es la
> única duplicación de color que sobrevive a la feature, y está encerrada donde no puede contagiar.

---

## 7. El color nunca va solo

La lección 18 introdujo la accesibilidad como criterio de *done*, no como extra. Aquí aprieta más de
lo normal, porque un widget es una superficie **sin leyenda, sin tooltip y sin ayuda contextual**: si
un color es la única pista, para quien no distinga ese color simplemente no hay información.

Dos casos, dos canales extra:

**Urgencia** → color + **peso tipográfico**. `Urgent` y `Overdue` van en negrita; `Calm` y `Soon`, no.
Y el propio texto ya lo dice (`4m`, `Tiempo agotado`), gracias al `formatRemainingLabel` compartido de
la 20b.

**Prioridad** → color + **glifo repetido** + palabras. En la tarjeta de tarea la prioridad es un
puntito de color, que aquí no valdría: un punto **es** color y nada más. El widget usa `!` / `!!` /
`!!!`, donde el rango se lee como **cuenta de repeticiones** aunque no percibas el color, y el color
lo refuerza. Además la fila entera lleva un `contentDescription` que lo dice con palabras
("Prioridad: Alta"), reutilizando los mismos recursos que la app.

Y sí, los glifos son **recursos de string**, no literales:

```xml
<string name="widget_priority_marker_high">!!!</string>
```

Por el motivo de siempre desde la lección 08: es texto de cara al usuario, y un idioma podría querer
otra convención. Que hoy la traducción sea idéntica no lo convierte en una constante de código.

---

## 8. Añadir un campo a un tipo que comparten dos superficies

Último detalle, pequeño pero instructivo. Para pintar la prioridad hacía falta llevarla en la fila:

```kotlin
data class PendingTaskRow(
    val title: String,
    val remainingMillis: Long,
    val priority: Priority = Priority.NONE,   // nuevo
)
```

Ese tipo lo comparten **el widget y la notificación** de pantalla de bloqueo (lección 06). Dos
decisiones aquí:

1. **El valor por defecto no es pereza.** `= Priority.NONE` hace que todos los tests y llamadas que
   ya existían **compilen sin tocarlos**. Eso convierte a la batería de tests existente en una red de
   seguridad de verdad: si siguen pasando *sin modificarse*, es prueba de que añadir el campo no
   cambió ninguna regla (ni el orden, ni el tope de 5 filas).
2. **La notificación ignora el campo, y eso está bien.** Sus líneas son texto plano que el sistema ya
   trunca; meter un tercer trozo empujaría a los puntos suspensivos justo lo que importa (qué tarea y
   cuánto queda). `pendingRowsFor` es dueño de **la regla** —qué está pendiente, en qué orden,
   cuántas filas—, no de lo que cada superficie decide pintar. Un `data class` cuyos campos se leen
   por nombre permite exactamente eso: la notificación no cambia ni una línea.

Y de paso, una función pura nueva junto al tipo, para que la decisión de urgencia del widget sea
testeable en la JVM sin ningún launcher:

```kotlin
fun PendingTaskRow.urgencyLevel(): UrgencyLevel =
    urgencyLevelFor(remainingMillis, remainingMillis == 0L)
```

> **Lo que no se puede testear, dicho claro.** Glance produce `RemoteViews` que pinta el launcher: el
> color, el radio de las esquinas, los separadores, el reflujo al redimensionar y el preview del
> selector **no** tienen aserción posible en un test JVM. Los tests cubren el modelo (prioridad que
> viaja, niveles de urgencia y sus fronteras, vacío vs contenido); lo demás se verifica a mano, en
> claro y en oscuro, y así está escrito en el spec en vez de fingir una cobertura que no existe.

---

## Lo que te llevas

- **Un tema en Compose es un `CompositionLocal`, y un `CompositionLocal` es un árbol.** Si tu código
  se ejecuta en otro árbol —un widget, un `RemoteViews`, otro proceso—, el tema no viaja con él por
  mucho que el `import` funcione.
- **Reutiliza por debajo del tema, no a través de él.** Funciones puras y valores crudos cruzan
  cualquier frontera; los `@Composable` que leen el tema, no.
- **`ColorProvider(day =, night =)` es diferir la decisión**, no elegir un color. Es lo que necesitas
  cuando quien pinta es otro y decide más tarde.
- **Un puente (`glance-material3`) merece la pena cuando evita duplicar una *decisión*, no cuando
  solo evita duplicar *valores*.** Y aun con el puente puesto, prepárate para lo que no cruza.
- **Muchos `if` de Android se escriben como calificadores de recurso** (`values-v31`, `values-night`,
  `values-en`). Si estás ramificando por versión, tema o idioma en Kotlin, mira primero si el sistema
  de recursos ya lo hace.
- **Degradar con elegancia = la versión antigua también recibe el arreglo**, no "al menos no rompe".
- **El preview del selector es producto.** Es donde alguien decide si tu widget entra en su pantalla
  de inicio.
