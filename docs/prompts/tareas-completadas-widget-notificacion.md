# Bugfix — El widget y la notificación muestran tareas ya completadas (y una de ellas, la primera)

Lee `CLAUDE.md` (**Bug Fix Workflow** + **Definition of Done**) y las lecciones previas (en especial la
04c: completar una tarea con `completedAt`; la 05 y la 06: el widget y la notificación, que comparten
la regla `pendingRowsFor`; y la 05b: el rediseño visual del widget, que fue quien detectó y anotó este
fallo). Arregla **"que las superficies pasivas dejen de contar tareas que ya están hechas"** siguiendo
el flujo `/bugfix`.

> **Los bugfix no llevan lección en español** y la pregunta del tutorial **no se hace** (ver
> *Bug Fix Workflow* en `CLAUDE.md`). Si al terminar se ve que hay material didáctico, se plantea
> aparte.

## El fallo

`pendingRowsFor`
([`PendingTaskRows.kt`](../../app/src/main/java/com/neverlate/domain/tasks/PendingTaskRows.kt)) **no
filtra nada**: mapea, ordena por tiempo restante, corta a cinco y devuelve. Nunca mira `completedAt`.
Consecuencias, de menos a más grave:

1. Una tarea completada **ocupa una de las cinco filas** del widget y de la notificación,
   desplazando a una pendiente de verdad.
2. Si esa tarea tenía temporizador y venció, `computeRemainingMillis` devuelve `0`, así que **ordena
   la primera** y, desde la 05b, se pinta **en rojo y en negrita** como lo más urgente que tienes.
   Está hecha.
3. La app **sí lo hace bien**: la lista hunde las completadas al final y les quita la cuenta atrás.
   Las dos superficies pasivas son las únicas que disienten, y son justo las que se miran sin abrir
   la app.

Preexistente desde que la 04c introdujo el completado; la 05b lo dejó anotado en sus *Risks* como
candidato a rama de arreglo propia, que es esta.

## Qué arreglar

- Las tareas completadas **no aparecen** ni en el widget ni en la notificación de pantalla de bloqueo.
- El conteo del resumen redactado de la notificación ("N tareas pendientes") deja de incluirlas.
- Con **todas** las tareas completadas, ambas superficies muestran su estado vacío normal, no una
  cabecera huérfana ni un fallo.

## Notas

- Rama sugerida: `bugfix/completed-tasks-in-passive-surfaces`.
- **El arreglo NO cabe en un solo sitio, y ahí está la trampa.** Los dos consumidores comprueban si
  hay tareas mirando la lista **sin filtrar**, no las filas resultantes:
  - [`PendingTasksWidgetState.kt`](../../app/src/main/java/com/neverlate/ui/widget/PendingTasksWidgetState.kt)
    devuelve `Empty` solo si `tasks.isEmpty()`; filtrando únicamente dentro del helper, una lista de
    solo completadas daría `Content` con cero filas → **cabecera sin nada debajo**, y nunca el mensaje
    de vacío.
  - [`NotificationModel.kt`](../../app/src/main/java/com/neverlate/ui/notification/NotificationModel.kt)
    hace lo mismo, **y su `Content` tiene un `require(rows.isNotEmpty())`**. Es decir: filtrar solo en
    el helper convierte este bug cosmético en un **`IllegalArgumentException` dentro del servicio en
    primer plano** en cuanto alguien complete todas sus tareas. El arreglo tiene que tocar los tres
    puntos de forma coordinada, y el `totalPendingCount` que alimenta el plural redactado también.
- **Hay que reescribir el KDoc, no solo el cuerpo.** El comentario actual de `pendingRowsFor` afirma
  que "toda tarea cuenta" como si fuera una regla deliberada. Después de este arreglo será falso: la
  documentación miente igual de bien que el código.
- **Ningún test existente falla hoy, y eso es parte del fallo.** Ni `PendingTaskRowsTest`, ni
  `PendingTasksWidgetStateTest`, ni `NotificationModelTest` construyen jamás una `Task` con
  `completedAt` no nulo. **Los tests de regresión son obligatorios en los tres ficheros**, y deben
  fallar antes del arreglo: una tarea completada no aparece; una completada con temporizador vencido
  no se cuela la primera; y con todo completado se obtiene el estado vacío **sin excepción**.
- **Extiende, no dupliques.** "Completada" ya tiene una definición en el proyecto (`completedAt != null`),
  y la lista de la app ya la usa en
  [`TaskListShaping.kt`](../../app/src/main/java/com/neverlate/domain/tasks/TaskListShaping.kt). El
  filtro va en `pendingRowsFor`, que es **la única casa** de la regla "qué cuenta como pendiente" — no
  en cada superficie por separado, que es exactamente lo que ese helper existe para evitar.
- **Comprobar de paso, sin arreglarlo aquí:** el `deleted` no se filtra tampoco en el helper; hoy
  funciona solo porque la consulta del DAO ya excluye lo borrado. Merece un comentario que lo diga,
  para que nadie asuma que el helper protege de algo que en realidad protege la capa de datos.
- **Diseño:** cambio visible (filas que desaparecen, estado vacío que ahora sí sale), así que la
  sección correspondiente del análisis debe verificar el estado vacío en ambas superficies y reflejar
  lo que cambie en `docs/mockups/README.md` si aplica.
- Sin backend, sin contrato, sin migración de Room, sin dependencia nueva, sin permiso nuevo.
- Ficheros:
  [`PendingTaskRows.kt`](../../app/src/main/java/com/neverlate/domain/tasks/PendingTaskRows.kt),
  [`PendingTasksWidgetState.kt`](../../app/src/main/java/com/neverlate/ui/widget/PendingTasksWidgetState.kt),
  [`NotificationModel.kt`](../../app/src/main/java/com/neverlate/ui/notification/NotificationModel.kt),
  y sus tres ficheros de test en `app/src/test/`.
- Agentes: `qa-engineer` primero (escribir los tests que fallan, incluido el del `require` que
  revienta), luego `mobile-engineer` (el arreglo coordinado en los tres puntos).
