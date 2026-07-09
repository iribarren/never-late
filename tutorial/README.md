# Tutorial *Never Late Again* — orden de lectura

Este proyecto es un **tutorial progresivo** de Kotlin + Android (Jetpack Compose): cada lección
implementa una feature real de la app e introduce conceptos nuevos, de lo básico a lo avanzado,
reutilizando lo anterior (ver *Tutorial Methodology* en [`../CLAUDE.md`](../CLAUDE.md)).

Esta es la **secuencia de lectura definitiva por curva de aprendizaje**. Es el índice reader-facing;
el backlog con prerrequisitos y features propuestas vive en
[`../docs/conceptos-pendientes.md`](../docs/conceptos-pendientes.md).

## Cómo leer esta lista

- El **número es definitivo**: las lecciones nuevas se intercalan con **sufijos de letra** (patrón
  `12b`) para no renumerar lo ya publicado. Así `03b` va entre la 03 y la 04.
- **Estado:** ✅ publicada · 🚧 pendiente (placeholder reservado; prompt listo en
  [`../docs/prompts/`](../docs/prompts/)).
- Los ficheros ordenan alfabéticamente igual que esta lista (`03` < `03b` < `04`).

## Bloque 1 — Fundamentos (UI, estado, lenguaje, datos locales)

*De "hola mundo" a una app con estado, base de datos local y las bases del lenguaje y el testing.*

| Nº | Lección | Enseña | Estado |
|----|---------|--------|--------|
| 01 | [entorno-y-hola-mundo](01-entorno-y-hola-mundo.md) | Entorno, estructura de un proyecto Android/Compose, Gradle | ✅ |
| 02 | [onboarding-home](02-onboarding-home.md) | Compose, estado, `ViewModel`, primeros tests JVM | ✅ |
| 03 | [articulos](03-articulos.md) | Lista + detalle, navegación, `data class`/`sealed` | ✅ |
| 03b | [filtro-orden-memoria](03b-filtro-orden-memoria.md) | Fundamentos de Kotlin: null-safety, `when`, colecciones, alcance, extensiones | ✅ |
| 04 | [tareas-contador](04-tareas-contador.md) | Room (CRUD), cuenta atrás, lógica pura de tiempo, `Flow` | ✅ |
| 04b | [buscador-tareas](04b-buscador-tareas.md) | Corrutinas y `Flow` a fondo: `debounce`, `combine`, `stateIn` | ✅ |
| 04c | [testing-estadisticas](04c-testing-estadisticas.md) | Testing: JUnit JVM, Compose UI, `runTest` | 🚧 |

## Bloque 2 — Plataforma Android (background, sistema, preferencias)

*La app sale de su pantalla: widget, notificaciones, ajustes, idiomas y alarmas.*

| Nº | Lección | Enseña | Estado |
|----|---------|--------|--------|
| 05 | [widget](05-widget.md) | Widget con Glance + WorkManager (trabajo en background) | ✅ |
| 06 | [lockscreen](06-lockscreen.md) | Notificaciones + servicio en primer plano | ✅ |
| 07 | [ajustes](07-ajustes.md) | Pantalla de ajustes, tema claro/oscuro con DataStore | ✅ |
| 07b | [arquitectura](07b-arquitectura.md) | Poner nombre al patrón: UDF / MVVM / capas (transversal) | 🚧 |
| 08 | [i18n](08-i18n.md) | Internacionalización, `plurals`, fechas locale-aware, desugaring | ✅ |
| 09 | [recordatorios](09-recordatorios.md) | Alarmas antes del plazo, permiso de alarma exacta, reboot | ✅ |

## Bloque 3 — Red, backend y sincronización

*De contenido remoto a un backend real con auth, sync offline-first y datos avanzados.*

| Nº | Lección | Enseña | Estado |
|----|---------|--------|--------|
| 10 | [articulos-api](10-articulos-api.md) | Red con Retrofit/OkHttp, caché, estados carga/error/vacío | ✅ |
| 10b | [coil-imagenes](10b-coil-imagenes.md) | Carga de imágenes con Coil (caché, placeholders) | 🚧 |
| 11 | [bbdd-remota](11-bbdd-remota.md) | Backend, auth JWT, sync offline-first (outbox, cursor) | ✅ |
| 12 | [refresh-token](12-refresh-token.md) | Refresh token + renovación silenciosa de sesión | ✅ |
| 12b | [keystore-recuperacion](12b-keystore-recuperacion.md) | Depurar un crash real de dispositivo: Keystore, backup | ✅ |
| 13 | [modo-invitado](13-modo-invitado.md) | Modo invitado local + merge al iniciar sesión | ✅ |
| 13b | [migraciones-room](13b-migraciones-room.md) | Migraciones de Room reales + `TypeConverter` | 🚧 |
| 13c | [paginacion](13c-paginacion.md) | Paginación con Paging 3 (red + Room) | 🚧 |
| 13d | [hilt-di](13d-hilt-di.md) | Inyección de dependencias con Hilt (migrar la DI manual) | 🚧 |

## Bloque 4 — Diseño e identidad visual

*La app converge con el maquetado: pickers, iconos, marca, animaciones, navegación y accesibilidad.*

| Nº | Lección | Enseña | Estado |
|----|---------|--------|--------|
| 14 | [selector-fecha](14-selector-fecha.md) | Diálogos y pickers de fecha/hora de Material 3, zona horaria | ✅ |
| 15 | [iconos-secciones](15-iconos-secciones.md) | `ListItem`, iconos, agrupación de superficies (Card/Surface) | ✅ |
| 16 | [identidad-visual](16-identidad-visual.md) | Theming Material 3: roles de color, tipografía, `dynamicColor` | ✅ |
| 17 | [estados-animaciones](17-estados-animaciones.md) | Estados vacíos, animaciones, side-effects (`LaunchedEffect`, `derivedStateOf`) | ✅ |
| 18 | [navegacion-accesibilidad](18-navegacion-accesibilidad.md) | `NavigationBar`, sincronía con back stack, accesibilidad | ✅ |
| 18b | [layouts-adaptables](18b-layouts-adaptables.md) | Layouts adaptables / tablet (`WindowSizeClass`, list-detail) | 🚧 |
| 19 | [barra-progreso-tareas](19-barra-progreso-tareas.md) | `LinearProgressIndicator` determinado, `animateFloatAsState` | ✅ |
| 20 | [cromo-marca](20-cromo-marca.md) | Roles de color aplicados a componentes: top bars, chips, FAB | ✅ |

## Bloque 5 — Producción

*Llevar la app fuera del emulador.*

| Nº | Lección | Enseña | Estado |
|----|---------|--------|--------|
| 21 | [build-release](21-build-release.md) | Build variants, R8/ProGuard, firma, HTTPS | 🚧 |

---

> **Nota sobre "feature NN" vs "lección NN".** Históricamente el número de lección coincide con el
> número de *feature* (orden de construcción), y ese número está incrustado en el código, los tests y
> el historial git. Las lecciones intercaladas con sufijo (`03b`, `04b`, …) se implementarán más
> adelante pero **se leen** en la posición que indica su número. Es lo que permite mantener una buena
> curva de aprendizaje sin renumerar nada de lo ya publicado.
