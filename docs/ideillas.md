# Posibles nuevas funcionalidades

Estas son algunas ideas para nuevas funcionalidades de la app. Evalua cada idea, y plantea un prompt para poder implementarlas cada una con el flujo habitual (sin añadir nada de codigo aún). Evalua si tiene sentido implementar algunas ideas al mismo tiempo, o si alguna es demasiado grande y tiene sentido dividirla en distintas tareas.

Los prompts deben crearse en la carpeta @docs/prompts 

## mejorar componente duración para poder horas y minutos

En la pantalla de crear nueva tarea, el usuario introduce la duración de la tarea en un solo campo de minutos. Esto no es muy amigable si la tarea es muy larga. Modificarel componente de UX para que el usuario pueda introducir horas y minutos. El campo solo debe ser visual, en BBDD la duración se debe seguir persistiendo en minutos

## mejorar visualizacion de tiempo restante

Cuando se visualiza una tarea (en el listado, widget, etc) el tiempo se ve en el formato hh:mm:ss esto puede ser un poco confuso. Se debe mejorar la UX de cuando se visualiza el tiempo restante de una tarea en cualquier parte de la app. El formato más adecuado seria hh:mm sin mostrar los segundos e incluir una letra para indicar las horas y los minutos. Por ejemplo, si quedan 2 horas y 28 minutos, en  pantalla se deberia ver "2h 38m". Actualmente para los idiomas existentes h como hora y m como minuto funcionan bien, pero se deberia implementar teniendo en cuenta que puede haber otros idiomas en el futuro que necesiten otras letras para indicar las horas y minutos

## mejorar aspecto visual del widget

El aspecto visual del widget es muy simple y poco atractivo. Se debe mejorar la presentacion del widget, incluyendo cosas como bordes redondeados, colores atractivos similares a los de la aplicación, indicadores mejores del tiempo restante, colores segun la prioridad de la tarea etc

## Modofoco

Añadir un botón a la aplicación que permita entrar en un modo de concentracion con el objetivo de que el usuario pueda centrarse en terminar las tareas. Al pulsar el botón la aplicación deberia cambiar a una pantalla completa donde solo se puedan ver las tareas que tiene pendiente el usuario. Para poder salir de esta pantalla el usuario deberá marcar las tareas pendientes como echas en un check box, deslzar una barra de desbloqueo e introducir un código que se le habrá solicitado al pulsar el botón. El nombre de la funcionalidad puede ser "Modo concentracion", "Modo foco" u otro que recomiendes. Evaluar la viabilidad de que en este modo ciertas funciones del telefono queden deshabilitadas

## Configurar la privacidad de la pantalla de bloqueo
