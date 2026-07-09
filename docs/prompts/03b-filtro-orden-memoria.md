# Feature 03b (extra) — Filtro y ordenación de la lista en memoria (fundamentos de Kotlin)

Lee `CLAUDE.md` (**Tutorial Methodology** + **Mandatory Workflow**) y las lecciones previas (en
especial la 03: lista y detalle de artículos, `data class`, `sealed`; y la 02: `ViewModel` +
`StateFlow`). Implementa **"filtrar y ordenar la lista en memoria"** siguiendo el flujo `/feature`.

> **Lección de lenguaje, poca infraestructura nueva.** El objetivo pedagógico es *poner nombre* al
> Kotlin que la app ya usa sin explicar. Elige el vehículo más simple sobre lo que exista en su
> momento (la lista de artículos, o la de tareas): filtrar/ordenar/agrupar **en memoria**, sin tocar
> Room ni la red.

## Qué construir

- Un **filtro por texto** (subcadena, ignore-case) sobre la lista visible.
- Una **ordenación** seleccionable (p. ej. por título y por fecha, ascendente/descendente).
- Una **agrupación por estado/categoría** que devuelva un `Map` o cabeceras de sección.
- Toda la transformación ocurre en **funciones puras** (o en el `ViewModel`) sobre la lista ya
  cargada; **nada** de nuevas consultas a la base de datos ni a la API.

## Conceptos nuevos a enseñar (lección en español)

- **Null-safety y smart casts:** `?`, `?:` (Elvis), `?.let { }`, `!!` y por qué evitarlo — hoy
  aparecen sin explicación.
- **`when` como expresión exhaustiva** sobre `sealed`/`enum`, y **desestructuración** (`val (a, b) = …`).
- **Colecciones + funciones de orden superior:** `filter`/`map`/`sortedBy`/`sortedWith`/`groupBy`,
  `Comparator`, lambdas y referencias a función.
- **Funciones de alcance** (`let`/`run`/`apply`/`also`/`with`): cuándo usar cada una.
- **Funciones de extensión:** encapsular el filtro/orden como extensión de `List<…>` legible.

## Notas

- Rama sugerida: `feature/list-filter-sort`.
- **Extiende, no dupliques:** reutiliza el estado ya expuesto por el `ViewModel` de la pantalla
  elegida y su `StateFlow`; el filtro/orden se aplica sobre esa lista, sin un segundo origen de datos.
- Mapea a `docs/conceptos-pendientes.md` §1 (Kotlin — fundamentos del lenguaje). Sin backend, sin
  contrato, sin nueva dependencia.
- Ficheros: la pantalla de lista elegida y su `ViewModel` (p. ej.
  [`ArticlesScreen.kt`](../../app/src/main/java/com/neverlate/ui/articles/ArticlesScreen.kt) o
  [`TasksScreen.kt`](../../app/src/main/java/com/neverlate/ui/tasks/TasksScreen.kt)), más una función
  pura de filtro/orden en `domain/`.
- Agentes: `mobile-engineer` (UI del filtro/orden + lógica), `qa-engineer` (tests JVM de las funciones
  puras: filtro vacío, sin resultados, orden estable, agrupación). Lección en
  `tutorial/03b-filtro-orden-memoria.md` (español), numerada como **03b** (entre la 03 y la 04).
