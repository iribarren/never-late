# Feature — Modo Foco (blindaje): silenciar el teléfono durante la sesión

Lee `CLAUDE.md` (**Tutorial Track (optional)** + **Mandatory Workflow** + **Design in the Workflow**)
y las lecciones previas (en especial la 06 y la 09: notificaciones, canales y el permiso runtime
`POST_NOTIFICATIONS`; y la 09 de nuevo por `SCHEDULE_EXACT_ALARM`, el precedente exacto de "acceso
especial que se pide mandando al usuario a Ajustes"). Implementa **"que el Modo Foco reduzca de
verdad las interrupciones del teléfono"** siguiendo el flujo `/feature`.

> **Depende de `modo-foco-nucleo.md`: hazla después.** Esta feature no aporta pantalla nueva; le pone
> músculo a la sesión de foco que la otra ya define.

## Lo que NO se puede hacer (léelo antes de escribir el spec)

El usuario pidió "evaluar la viabilidad de que ciertas funciones del teléfono queden deshabilitadas".
La respuesta honesta, para una app normal de Play Store:

- **Bloquear otras apps es imposible.** Requiere ser *device owner* (aprovisionamiento empresarial) o
  un `AccessibilityService` — y usar accesibilidad para bloquear apps es terreno minado de políticas
  de Play Store. **Queda fuera de alcance y el spec no debe prometerlo.**
- **Fijar la pantalla (*screen pinning*, `startLockTask`)** sin *device owner* muestra un diálogo del
  sistema y la persona sale manteniendo pulsado atrás + recientes. Es **fricción, no bloqueo**.
- Lo que sí funciona de verdad y aporta valor real es **No Molestar**.

## Qué construir

Tres medidas, **cada una opcional e independiente**, activables al entrar en Modo Foco:

- **No Molestar** durante la sesión: silenciar llamadas y notificaciones mientras dura el foco, y
  restaurar el estado anterior al salir. Es la medida con más valor real de las tres.
- **Fijar la pantalla** (screen pinning) como fricción extra opcional, presentada al usuario por lo
  que es y no como un candado.
- **Mantener la pantalla encendida** y ocultar las barras del sistema (modo inmersivo) mientras dura
  la sesión.

## Tutorial

Antes de escribir el spec, el flujo `/feature` **debe preguntar con `AskUserQuestion`** si esta
feature lleva lección en español (*Sí, con lección / No / Decidir al final*), y la respuesta se anota
en el campo `Tutorial:` del spec.

**Recomendación: Decidir al final.** Depende de cuántas de las tres medidas sobrevivan al spec. Si
sobreviven las tres, hay lección de sobra: **accesos especiales frente a permisos runtime**, el
**modo lock-task** y sus límites reales, **`WindowInsetsControllerCompat`** e inmersivo, y sobre todo
**deshacer efectos globales de forma fiable** cuando el proceso puede morir en cualquier momento. Si
se queda solo en "mantener la pantalla encendida", no hay lección que escribir.

## Notas

- Rama sugerida: `feature/focus-mode-shielding`.
- **No Molestar — el patrón ya existe en el repo.** `NotificationManager.setInterruptionFilter`
  necesita el acceso especial `ACCESS_NOTIFICATION_POLICY`, que **no** se pide como permiso runtime:
  se comprueba con `isNotificationPolicyAccessGranted()` y se manda a la persona a
  `Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS`. Ese es exactamente el idioma de
  `ExactAlarmPermissionNotice` en
  [`SettingsScreen.kt`](../../app/src/main/java/com/neverlate/ui/settings/SettingsScreen.kt)
  (comprobar → aviso → intent a Ajustes). **Extiende ese patrón, no escribas uno nuevo.**
- **El fallo grave de esta feature sería dejar el teléfono en silencio para siempre.** El spec debe
  cubrir explícitamente la restauración de No Molestar y del pinning cuando la sesión termina *por
  las malas*: proceso muerto, app cerrada desde recientes, reinicio del teléfono. Guardar el estado
  anterior y restaurarlo en el arranque si hace falta. Esto no es un caso límite: es el
  comportamiento por defecto de Android cuando falta memoria.
- **Degradación elegante obligatoria.** Permiso denegado ⇒ el Modo Foco sigue funcionando sin esa
  medida: ni un crash, ni un flujo bloqueado, ni un diálogo insistente. Mismo criterio que ya aplica
  [`ReminderReceiver.kt`](../../app/src/main/java/com/neverlate/ui/notification/ReminderReceiver.kt)
  cuando `POST_NOTIFICATIONS` está denegado.
- **Interacción con la propia app, que el spec debe resolver:** la app tiene su propia notificación
  permanente de tareas pendientes (feature 06) y sus recordatorios (feature 09). ¿No Molestar los
  silencia también? ¿Se le da a la app una excepción de política? Decidirlo a propósito; que no
  ocurra por accidente.
- **Inmersivo y pantalla encendida.** Hoy solo existe `enableEdgeToEdge()` en
  [`MainActivity.kt`](../../app/src/main/java/com/neverlate/MainActivity.kt); no hay
  `WindowInsetsControllerCompat` ni `FLAG_KEEP_SCREEN_ON` en ningún sitio. Ambos deben aplicarse
  **solo mientras dura la sesión** y revertirse al salir, no dejarse activados a nivel de `Activity`.
- **Permisos y manifest ⇒ documentación.** Si entra `ACCESS_NOTIFICATION_POLICY` u otro cambio de
  manifest, hay que reflejarlo en `CLAUDE.md` y en la sección *Transversal — Permisos y manifest* de
  `docs/arquitectura.md`, en la misma rama (regla de *Documentation Update*).
- **Diseño (obligatorio en el spec):** la sección **Visual & UX Design** debe describir cómo se
  ofrecen las tres medidas al entrar (opt-in, con una explicación honesta de qué hace cada una y qué
  no) y cómo se muestra que están activas durante la sesión. Declarar que **no reclama ninguna
  `slice`** del maquetado y, si añade UI visible, extender la fila de Modo Foco que dejó
  `modo-foco-nucleo.md` en `docs/mockups/README.md` en lugar de crear otra. Criterios visuales:
  targets ≥48dp, avisos de permiso legibles a fuente máxima, y que el estado inmersivo no esconda
  ningún control necesario para salir.
- **Referencia visual:** abre `docs/mockups/rediseno-ux-ui.html` como guía de dirección, **no** código
  a copiar.
- Sin backend, sin contrato, sin migración de Room, sin nueva dependencia. Sí, probablemente, nuevo
  permiso en el manifest.
- Ficheros:
  [`AndroidManifest.xml`](../../app/src/main/AndroidManifest.xml),
  [`MainActivity.kt`](../../app/src/main/java/com/neverlate/MainActivity.kt) (inmersivo, pantalla
  encendida, `startLockTask`), el paquete `ui/focus/` que crea `modo-foco-nucleo.md`,
  [`SettingsScreen.kt`](../../app/src/main/java/com/neverlate/ui/settings/SettingsScreen.kt)
  (patrón de aviso de acceso especial, a extraer y reutilizar),
  [`strings.xml`](../../app/src/main/res/values/strings.xml) +
  [`values-en/strings.xml`](../../app/src/main/res/values-en/strings.xml).
- Agentes: `devops-security-engineer` (revisión de los permisos nuevos y de que la restauración de
  estado global es a prueba de muerte de proceso), `mobile-engineer` (No Molestar, lock task,
  inmersivo), `qa-engineer` (tests de la máquina de restauración; verificación manual en dispositivo,
  porque nada de esto se puede probar de forma fiable en tests unitarios).
