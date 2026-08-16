# Posibles nuevas funcionalidades

Estas son algunas ideas para nuevas funcionalidades de la app. Evalua cada idea, y plantea un prompt para poder implementarlas cada una con el flujo habitual (sin añadir nada de codigo aún). Evalua si tiene sentido implementar algunas ideas al mismo tiempo, o si alguna es demasiado grande y tiene sentido dividirla en distintas tareas.

Los prompts deben crearse en la carpeta @docs/prompts 

---

## Estado: de idea a prompt

Cada idea de abajo ya tiene su prompt listo para pegar en una sesión nueva con `/feature`. Dos de
ellas tienen **dependencias de orden** que conviene respetar:

| Idea | Prompt | Tamaño | Orden |
|---|---|---|---|
| Duración en horas y minutos | [`duracion-horas-minutos.md`](prompts/duracion-horas-minutos.md) | S | independiente |
| Tiempo restante "2h 38m" | [`tiempo-restante-compacto.md`](prompts/tiempo-restante-compacto.md) | M | **antes del widget** |
| Aspecto visual del widget | [`widget-rediseno-visual.md`](prompts/widget-rediseno-visual.md) | M | después del anterior |
| Modo Foco — núcleo | [`modo-foco-nucleo.md`](prompts/modo-foco-nucleo.md) | L | **antes del blindaje** |
| Modo Foco — blindaje del dispositivo | [`modo-foco-blindaje.md`](prompts/modo-foco-blindaje.md) | M | después del anterior |

Por qué esos dos órdenes:

- El nuevo formato de tiempo obliga a que `pendingRowsFor` deje de construir texto en la capa de
  dominio y pase a devolver los milisegundos crudos. El rediseño del widget necesita justo ese dato
  para colorear por urgencia y pintar prioridad, así que al revés se pagaría el refactor dos veces.
- El blindaje del dispositivo (No Molestar, fijar pantalla) no aporta pantalla nueva: le pone músculo
  a la sesión de foco que define el núcleo. El núcleo tiene que ser útil y completo sin él.

Las ideas 1 y 2 se dejaron como **prompts separados** aunque compartan los recursos de string de las
unidades: la 1 es un cambio de formulario aislado y la 2 es transversal (lista, widget, notificación)
con un refactor de capas detrás. El "Modo Foco" se partió en dos porque como feature única era
demasiado grande para aprobarla y revisarla de una vez.

---

## mejorar componente duración para poder horas y minutos

**Prompt:** [`prompts/duracion-horas-minutos.md`](prompts/duracion-horas-minutos.md)

En la pantalla de crear nueva tarea, el usuario introduce la duración de la tarea en un solo campo de minutos. Esto no es muy amigable si la tarea es muy larga. Modificarel componente de UX para que el usuario pueda introducir horas y minutos. El campo solo debe ser visual, en BBDD la duración se debe seguir persistiendo en minutos

> Matiz al enunciado: la BBDD no guarda minutos sino **milisegundos** (`estimatedDurationMillis`). La
> intención se mantiene igual — el cambio es solo visual y no toca el esquema ni requiere migración.

## mejorar visualizacion de tiempo restante

**Prompt:** [`prompts/tiempo-restante-compacto.md`](prompts/tiempo-restante-compacto.md)

Cuando se visualiza una tarea (en el listado, widget, etc) el tiempo se ve en el formato hh:mm:ss esto puede ser un poco confuso. Se debe mejorar la UX de cuando se visualiza el tiempo restante de una tarea en cualquier parte de la app. El formato más adecuado seria hh:mm sin mostrar los segundos e incluir una letra para indicar las horas y los minutos. Por ejemplo, si quedan 2 horas y 28 minutos, en  pantalla se deberia ver "2h 38m". Actualmente para los idiomas existentes h como hora y m como minuto funcionan bien, pero se deberia implementar teniendo en cuenta que puede haber otros idiomas en el futuro que necesiten otras letras para indicar las horas y minutos

## mejorar aspecto visual del widget

**Prompt:** [`prompts/widget-rediseno-visual.md`](prompts/widget-rediseno-visual.md) — hacerla
**después** de "mejorar visualizacion de tiempo restante".

El aspecto visual del widget es muy simple y poco atractivo. Se debe mejorar la presentacion del widget, incluyendo cosas como bordes redondeados, colores atractivos similares a los de la aplicación, indicadores mejores del tiempo restante, colores segun la prioridad de la tarea etc

## Modofoco

**Prompts:** [`prompts/modo-foco-nucleo.md`](prompts/modo-foco-nucleo.md) y, después,
[`prompts/modo-foco-blindaje.md`](prompts/modo-foco-blindaje.md).

> **Nombre elegido:** "Modo Foco" (*Focus Mode* en inglés, `FocusMode` en código).
>
> **Sobre "deshabilitar funciones del teléfono":** solo es parcialmente viable. Bloquear otras apps
> exige ser *device owner* o un `AccessibilityService` (terreno minado de políticas de Play Store) y
> queda descartado. Sí son viables: **No Molestar** (la de más valor real), **fijar la pantalla**
> (que sin *device owner* es fricción, no bloqueo: se sale con atrás + recientes) y **mantener la
> pantalla encendida**. Las tres van en el prompt de blindaje.

Añadir un botón a la aplicación que permita entrar en un modo de concentracion con el objetivo de que el usuario pueda centrarse en terminar las tareas. Al pulsar el botón la aplicación deberia cambiar a una pantalla completa donde solo se puedan ver las tareas que tiene pendiente el usuario. Para poder salir de esta pantalla el usuario deberá marcar las tareas pendientes como echas en un check box, deslzar una barra de desbloqueo e introducir un código que se le habrá solicitado al pulsar el botón. El nombre de la funcionalidad puede ser "Modo concentracion", "Modo foco" u otro que recomiendes. Evaluar la viabilidad de que en este modo ciertas funciones del telefono queden deshabilitadas

## Configurar la privacidad de la pantalla de bloqueo

> Idea aún **sin enunciar** — sin descripción no se ha generado prompt. Escribe aquí qué debe poder
> configurarse (¿ocultar títulos de tarea? ¿elegir entre resumen público y detalle?) y se convierte
> en prompt como las demás.
