# Lección 20 — Cromo de marca: barras superiores, chips de icono y FAB

> Objetivo: **llevar el color de marca a los componentes**. La lección 16 trajo la *paleta* (los
> roles de color de Material 3, la escala tipográfica, `NeverLateExtras` y el interruptor de Material
> You), pero deliberadamente dejó el "cromo" de la app sin teñir: las `TopAppBar` seguían usando la
> superficie por defecto, las filas de las listas no tenían el chip de icono coloreado del maquetado
> y el FAB iba sin tratamiento de marca. El maquetado (`docs/mockups/rediseno-ux-ui.html`) muestra lo
> contrario: una barra superior **rellena de marca** en cada pantalla, un **chip redondeado** al
> principio de cada fila y un **FAB de marca**. Esas tres filas llevaban esperando como ⬜/🟡 en
> `docs/mockups/README.md`; esta lección las cierra.
>
> No es una feature "nueva" desde cero: es la aplicación directa de lo que la 16 ya construyó. La
> regla de diseño de siempre — **extender, no duplicar** — aquí significa que **no definimos ni un
> solo color nuevo**: cada componente le pide un **rol** a `MaterialTheme.colorScheme` en vez de
> pintar a mano. Y como todo son roles, claro/oscuro y Material You salen "gratis".
>
> Es también la primera vez que el tutorial se dedica **a fondo a los roles de color de Material 3** y
> al patrón "no pintes a mano, pásale un objeto de colores".

## Conceptos que aprendes aquí

Partiendo de la lección 16 (paleta, roles, `NeverLateExtras`, `dynamicColor`), de la 15
(`SettingsSectionCard`: icono + título) y de la 17 (`MessageState`: extraer un componente reutilizable
a `ui/components`):

1. **Roles de color de Material 3 a fondo** — `primary` / `primaryContainer` / `onPrimaryContainer`,
   `secondaryContainer`, `surface`… por qué los roles vienen en pares `container` + `on-container`, y
   por qué pedir un *rol* (no un hex) es lo que hace que claro/oscuro y Material You "funcionen solos".
2. **Colorear un componente con su objeto `*Colors` / `*Defaults`** — `TopAppBarDefaults.topAppBarColors(...)`,
   `containerColor`/`contentColor` del `FloatingActionButton` — el idioma "no pintes el fondo a mano,
   pásale un objeto de colores".
3. **Extraer un componente de marca reutilizable** — un `BrandIconChip` en `ui/components`, igual que
   `MessageState` (17), con `contentDescription = null` decorativo cuando acompaña a un texto.
4. **Contraste y accesibilidad del color** — por qué un `container` viaja siempre con su
   `on-container`, y por qué el color nunca puede ser el único portador de significado (sigue la 18).

---

## 1. Roles de color: pedir un *papel*, no un color

La idea central de Material 3 es que un componente **no elige un color**, elige un **rol** (un
"papel" en el sistema de color). En vez de decir "píntate de azul #3F51B5", dice "píntate del color
`primary`". Quién decide qué azul (o qué tono) es `primary` en cada momento es el **tema**, no el
componente.

Los roles vienen casi siempre **en parejas** `container` + `on-container`:

| Rol contenedor | Rol de contenido (encima) | Uso típico |
|---|---|---|
| `primary` | `onPrimary` | superficie de marca **saturada** (nuestra barra y FAB) |
| `primaryContainer` | `onPrimaryContainer` | variante de marca más **suave** |
| `secondaryContainer` | `onSecondaryContainer` | acento secundario, tinte de marca **claro** (nuestro chip) |
| `surface` | `onSurface` | fondo neutro por defecto (lo que teníamos antes en las barras) |

La regla de oro: **cada `container` se usa con su `on-container`**. `onPrimary` está *diseñado* para
tener contraste suficiente sobre `primary`; `onSecondaryContainer` sobre `secondaryContainer`, etc.
Emparejarlos mal (texto `onSurface` sobre `primary`, por ejemplo) es exactamente cómo se rompe la
legibilidad.

Y aquí está el pago de hacerlo con roles y no con hex: el tema tiene **tres orígenes** de esos roles
(claro, oscuro y — desde la 16 — la paleta que Material You deriva del fondo de pantalla). Como el
componente solo nombra el rol, **el tono correcto se elige automáticamente** en cada caso. No
escribimos ni una rama `if (isDark)`; el "gratis" de esta lección es literalmente eso.

> **Decisión de la feature (confirmada en el spec):** para la barra y el FAB elegimos el `primary`
> **saturado** (la lectura fiel al maquetado, más contundente) en lugar del `primaryContainer` más
> suave; el chip usa `secondaryContainer`, un tinte más claro, para que las dos superficies de marca
> se distingan y no se fundan en un único bloque de color.

---

## 2. "No pintes a mano, pásale un objeto de colores"

Un principiante colorearía una barra poniéndole un fondo a mano (`Modifier.background(...)`). En
Material 3 eso es un antipatrón: cada componente **acepta un objeto de colores** con todos sus roles
(fondo, contenido, estados…), y hay un `*Defaults` que lo construye. Le pasas ese objeto y el
componente se encarga del resto (incluidos los estados y las animaciones internas).

### La barra: `TopAppBarDefaults.topAppBarColors(...)`, definido **una sola vez**

Todas nuestras pantallas ya usaban `Scaffold` + `TopAppBar`. No hay que reescribir ninguna: solo
pasarles un `colors =`. Pero teníamos **ocho** barras (Tareas, Artículos, Ajustes, Detalle de
artículo, Editar tarea, Login, Registro, Onboarding). Repetir los cuatro argumentos de color en cada
una sería duplicar. Igual que la 17 extrajo `MessageState` y la 15 su tarjeta, aquí extraemos el
objeto de colores **una vez** a `ui/components/AppBarDefaults.kt`:

```kotlin
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun brandedTopAppBarColors(): TopAppBarColors = TopAppBarDefaults.topAppBarColors(
    containerColor = MaterialTheme.colorScheme.primary,
    // Igual que containerColor: al hacer scroll, la barra mantiene el relleno de marca
    // en vez de desvanecerse a la superficie por defecto.
    scrolledContainerColor = MaterialTheme.colorScheme.primary,
    titleContentColor = MaterialTheme.colorScheme.onPrimary,
    navigationIconContentColor = MaterialTheme.colorScheme.onPrimary,
    actionIconContentColor = MaterialTheme.colorScheme.onPrimary,
)
```

Fíjate en tres cosas:

- **El contenedor va con su `on-`.** El fondo es `primary`; el **título**, el **icono de navegación**
  (la flecha atrás) y los **iconos de acción** son `onPrimary`. Así, todo lo que va *encima* del
  relleno de marca queda legible. Ese es el emparejamiento del apartado 1, aplicado.
- **`scrolledContainerColor` explícito.** Por defecto una barra cambia de tono al desplazarse el
  contenido bajo ella; lo fijamos también a `primary` para que la barra de marca no "parpadee" a la
  superficie por defecto al hacer scroll.
- **Es una función `@Composable`**, no una `val`. Tiene que serlo porque lee `MaterialTheme.colorScheme`,
  que solo existe dentro de la composición. La llamas donde antes no pasabas nada:

```kotlin
TopAppBar(
    title = { Text(stringResource(R.string.tasks_title)) },
    colors = brandedTopAppBarColors(),   // ← el único cambio en cada barra
)
```

### El FAB: `containerColor` / `contentColor`

El `FloatingActionButton` es aún más directo: recibe sus dos roles como parámetros sueltos. Usamos el
**mismo** par que la barra para que el FAB pertenezca al mismo cromo:

```kotlin
FloatingActionButton(
    onClick = onAddTaskClick,
    // Feature 20: mismo par de marca saturado que las barras → un cromo coherente.
    containerColor = MaterialTheme.colorScheme.primary,
    contentColor = MaterialTheme.colorScheme.onPrimary,
) {
    Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.tasks_add_content_description))
}
```

Sobre la **elevación**: dejamos la que Material 3 da por defecto. El maquetado tiene una sombra de
color suave que **no** clavamos al píxel; afinarla queda anotado como pendiente menor, no se persigue
aquí. Y una nota de accesibilidad importante: el icono del FAB **sí** conserva su `contentDescription`
(«Añadir tarea»). El FAB es un **control interactivo**, no decoración — al contrario que el chip del
siguiente apartado.

---

## 3. Extraer el chip de marca a `ui/components`

El maquetado pone al principio de cada fila (tarea y artículo) un pequeño recuadro redondeado
coloreado con un icono dentro: el `.leading`. La 15 ya usaba ese lenguaje "icono en un recuadro" en
la cabecera de `SettingsSectionCard`. En vez de reimplementarlo en cada fila, lo **generalizamos** a
un componente reutilizable en `ui/components`, exactamente como la 17 hizo con `MessageState`:

```kotlin
@Composable
fun BrandIconChip(
    icon: ImageVector,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,   // decorativo por defecto (ver 3.2)
) {
    Surface(
        modifier = modifier.size(40.dp),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(imageVector = icon, contentDescription = contentDescription)
        }
    }
}
```

### 3.1 Por qué un `Surface` con `color` + `contentColor`

`Surface` es la pieza correcta para una "superficie coloreada" en Material 3: le das el rol de fondo
(`color = secondaryContainer`) y su `contentColor = onSecondaryContainer`, y ese `contentColor` se
**propaga** a lo que haya dentro. Por eso el `Icon` no necesita indicar su tinte: hereda
`onSecondaryContainer` del `Surface`. Otra vez el par `container` + `on-container`, y otra vez
legible en claro/oscuro/Material You sin ramas.

Elegimos `secondaryContainer` (no `primary`) a propósito: es un tinte de marca **más claro** que el
`primary` saturado de la barra, así el chip y la barra se leen como dos superficies distintas y no se
funden.

### 3.2 `contentDescription = null`: decorativo a propósito

Este es el detalle de accesibilidad de la lección. El chip **acompaña siempre a un texto** (el título
de la tarea o del artículo) que ya dice lo que la fila significa. Si el icono también se anunciara,
un lector de pantalla leería información **redundante**. Por eso el `contentDescription` es `null` por
defecto: marca el icono como **decorativo** y TalkBack lo ignora. Es la misma regla que sigue el
icono de cabecera de `SettingsSectionCard` (15). El parámetro existe por si algún día se usa el chip
*sin* un texto equivalente al lado; ese caso raro sí pasaría una descripción real.

### 3.3 Colocarlo en las filas

- **Artículos** (`ArticleRow`): la fila ya usa `ListItem`, que tiene una ranura `leadingContent`.
  El chip entra ahí en una línea:

  ```kotlin
  leadingContent = { BrandIconChip(icon = Icons.AutoMirrored.Filled.MenuBook) },
  ```

- **Tareas** (`TaskRow`): la tarjeta tenía una `Column` con título, duración, cuenta atrás, botones y
  la barra de progreso de la 19. Para no tocar nada de eso, envolvemos esa `Column` **intacta** en un
  `Row` junto al chip; la `Column` toma el ancho restante con `weight(1f)`:

  ```kotlin
  Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.Top) {
      BrandIconChip(icon = Icons.AutoMirrored.Filled.Assignment)

      Column(modifier = Modifier.weight(1f).padding(start = 16.dp)) {
          // … título, cuenta atrás, botones y la barra de progreso de la 19, sin cambios
      }
  }
  ```

  El `weight(1f)` es clave: hace que los títulos largos o la cuenta atrás **reflowen** en su espacio
  en vez de empujar o recortar el chip cuando la fuente está al máximo tamaño.

> **Un tropiezo real con `weight`.** `weight()` es un miembro del *scope* `RowScope` (solo existe
> dentro de un `Row`). Importar explícitamente `androidx.compose.foundation.layout.weight` lo
> **eclipsa** con un símbolo no relacionado y deja de resolver. La solución: **no importar nada** —
> al estar dentro del `Row`, `weight()` ya está disponible como miembro del scope.

---

## 4. El color nunca es el único mensaje (continúa la 18)

Añadir color de marca no puede costar accesibilidad. Dos comprobaciones, las mismas que trae el spec:

1. **Cada `container` con su `on-container`.** Ninguna superficie de marca queda con un color de
   contenido por defecto o mal emparejado: barra (`primary`/`onPrimary`), chip
   (`secondaryContainer`/`onSecondaryContainer`), FAB (`primary`/`onPrimary`). Como los tonos de los
   roles de Material 3 están diseñados para cumplir contraste, emparejarlos bien **es** garantizar la
   legibilidad — pero solo si de verdad se emparejan.
2. **El color no es el único portador de significado.** El chip es **decorativo** (el título lleva el
   mensaje); la urgencia por color de la 17 y el texto de "vencido" de la 19 siguen **intactos** y
   siguen contando la urgencia con texto además de color. Esta feature no degrada ningún estado a
   "solo color".

Y una verificación de gusto, no solo de contraste: **Material You**. Como todo son roles, el contraste
está garantizado con cualquier paleta derivada del fondo de pantalla, pero conviene un vistazo con
`dynamicColor` **activado** en claro y oscuro para confirmar que la combinación también resulta
agradable. Eso se revisa en el paso de *Design review* del flujo.

> El chip no está sujeto a la regla de "≥ 48 dp" de la 18: esa regla es para *controles* que se
> tocan, y el chip es decoración de solo lectura. Lo que sí se mantiene es que la tarjeta reflowe sin
> recortes con la fuente al máximo (de ahí el `weight(1f)`).

---

## 5. Tests

Al ser un cambio solo de cromo, los tests se centran en que **no rompimos los controles existentes**
al reestructurar las filas y colorear el FAB, no en comprobar colores exactos (los tests de UI de
Compose no comparan colores con facilidad):

- Se **extendió** `TasksScreenTest.kt`: el botón de iniciar / pausar y el flujo de borrar (con su
  diálogo de confirmación) siguen invocando su callback tras envolver la `Column` en un `Row`; el FAB
  sigue encontrándose por su `contentDescription` y sigue siendo pulsable tras el cambio de color.
- Los tests **ya existentes** de `ArticlesScreenTest.kt` renderizan el `ArticleRow` real, que ahora
  incluye el chip en `leadingContent`: que sigan pasando sin tocarlos **es** la comprobación de
  regresión de las filas de artículo.

```bash
./gradlew :app:testDebugUnitTest         # JVM (no afectados por un cambio solo de Compose)
./gradlew :app:connectedDebugAndroidTest # UI de Compose (necesitan emulador/dispositivo)
```

---

## Resumen

Aplicamos el color de marca a los componentes sin cambiar arquitectura, datos, navegación, permisos
ni contrato, y **sin definir un solo color nuevo**:

1. **Roles, no hex.** Cada superficie pide un *rol* a `MaterialTheme.colorScheme` (`primary`,
   `secondaryContainer`, …), siempre en pareja `container` + `on-container`. Por eso claro, oscuro y
   Material You funcionan solos, sin ramas por tema.
2. **"Pásale un objeto de colores".** `TopAppBarDefaults.topAppBarColors(...)` para las barras y los
   parámetros `containerColor`/`contentColor` del FAB, en vez de pintar el fondo a mano. Los colores
   de la barra se definen **una vez** (`brandedTopAppBarColors()`) y se reutilizan en las ocho barras.
3. **Un componente de marca reutilizable.** `BrandIconChip` en `ui/components`, en la línea de
   `MessageState` (17) y del lenguaje icono+título de `SettingsSectionCard` (15); entra en las filas
   de tarea y de artículo como elemento *leading*.
4. **Accesibilidad.** El chip es decorativo (`contentDescription = null`), cada contenedor va con su
   `on-`, y ningún estado pasa a depender solo del color (la urgencia de la 17 y el texto de la 19
   siguen intactos).

Lo que te llevas: en Material 3 **no eliges un color, eliges un rol** — y colorear un componente es
pasarle su objeto de colores, no pintarle el fondo. Hacerlo así es lo que te regala claro/oscuro y
Material You de una sola vez.
