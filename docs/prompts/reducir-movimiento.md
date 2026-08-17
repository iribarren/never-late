# Feature — Respetar "reducir movimiento": lo que la plataforma ya da y lo que falta

Lee `CLAUDE.md` (**Tutorial Track (optional)** + **Mandatory Workflow** + **Design in the Workflow**)
y las lecciones previas (en especial la 17: animaciones y estados con `animateItem` y
`animate*AsState`; la 18: el repaso de accesibilidad; la 19: la barra de progreso animada de la
tarjeta; y la 20b, que dejó una instrucción explícita sobre la cadencia del contador que esta feature
tiene que derogar). Implementa **"que quien ha pedido menos movimiento en su móvil lo note también
aquí"** siguiendo el flujo `/feature`.

> **Lee esto antes de escribir el spec: la mayor parte ya funciona.** Compose instala un
> `MotionDurationScale` leído de `Settings.Global.animator_duration_scale`, así que `animateItem`
> (listas de Tareas y Artículos) y `animateFloatAsState` (la barra de progreso de la tarjeta) **ya
> honran** el ajuste de Accesibilidad → *Quitar animaciones* sin que la app haga nada. Si el spec
> promete "ahora respetamos reducir movimiento", estará vendiendo humo. El trabajo real es **el hueco
> que la plataforma no cubre**, y es pequeño pero concreto — este prompt existe para acotarlo con
> honestidad, no para inflarlo.

## Qué construir

- **Verificar y documentar** qué se honra ya gratis (y dejarlo escrito, para que nadie vuelva a
  "arreglarlo" en el futuro).
- **La cadencia de 1 segundo del contador**, que hoy recompone la pantalla de Tareas cada segundo con
  el único fin de que la barra de progreso baje suave. Con el movimiento reducido eso deja de ser una
  ventaja y se convierte en parpadeo constante y batería tirada.
- **Los `AnimatedPane`** del layout de dos paneles de Artículos (feature 18b), que vienen de
  `material3-adaptive` y hay que comprobar caso por caso si respetan la escala del sistema.
- Un criterio único y reutilizable para "¿hay que reducir el movimiento?", en vez de que cada pantalla
  lo consulte a su manera.

## Tutorial

Antes de escribir el spec, el flujo `/feature` **debe preguntar con `AskUserQuestion`** si esta
feature lleva lección en español (*Sí, con lección / No / Decidir al final*), y la respuesta se anota
en el campo `Tutorial:` del spec.

**Recomendación: Sí**, y precisamente por lo raro del caso:

- **Lo que el framework ya hace por ti:** `MotionDurationScale` como `CoroutineContext.Element`, cómo
  el `Recomposer` de la ventana lo instala leyendo `Settings.Global`, y por qué eso significa que tus
  animaciones ya obedecen sin que las toques. Es una lección de **investigar antes de implementar**:
  el primer entregable de esta feature fue descubrir que casi no hacía falta.
- **Dónde se acaba esa magia:** una recomposición periódica **no es una animación**, así que ningún
  `MotionDurationScale` la va a frenar. La diferencia entre "animar" y "refrescar" se ve aquí en su
  forma más pura.
- **`LocalAccessibilityManager` no sirve para esto** (solo expone
  `calculateRecommendedTimeoutMillis`), y saberlo evita media hora de búsqueda equivocada.
- **Derogar una decisión anterior con conocimiento de causa:** el proyecto escribió "no bajes esta
  cadencia"; esta feature explica cuándo una regla escrita deja de aplicar.

## Notas

- Rama sugerida: `feature/reduce-motion`.
- **La feature deroga una instrucción explícita, y el spec debe decirlo con esas palabras.** El KDoc de
  [`CountdownTicker.kt`](../../app/src/main/java/com/neverlate/ui/tasks/CountdownTicker.kt) dice que la
  cadencia de 1 s existe **solo** para que la barra no dé tirones, y ordena *no bajarla ni desacoplarla
  del refresco del texto*. Reducir movimiento es exactamente el caso donde el tirón es aceptable y la
  recomposición por segundo no. El spec debe **citar esa instrucción, explicar por qué deja de aplicar
  en este modo concreto, y actualizar el KDoc** — no cambiarla por la espalda.
- **Verifica la versión de Compose antes de citar nada.** El catálogo pinea `composeBom = 2024.12.01`,
  pero las dependencias resueltas apuntan a artefactos de Compose **1.10.0** (probablemente arrastrados
  por `paging-compose`). Ejecuta `./gradlew :app:dependencies --configuration debugRuntimeClasspath` y
  pon el número **real** en el spec. El mecanismo de `MotionDurationScale` existe desde Compose UI 1.2,
  así que la conclusión no cambia, pero un spec que cite una versión que no es la que se compila
  envejece mal.
- **Inventario completo de animaciones** (verificado, no estimado): `Modifier.animateItem()` en la
  lista de Tareas y en su variante agrupada
  ([`TasksScreen.kt`](../../app/src/main/java/com/neverlate/ui/tasks/TasksScreen.kt)) y en Artículos
  ([`ArticlesScreen.kt`](../../app/src/main/java/com/neverlate/ui/articles/ArticlesScreen.kt));
  `animateFloatAsState` para la barra de progreso de la tarjeta; y cuatro `AnimatedPane` en
  [`ArticlesListDetailPane.kt`](../../app/src/main/java/com/neverlate/ui/articles/ArticlesListDetailPane.kt).
  **No hay ningún `AnimatedVisibility` en la app.** La navegación usa las transiciones por defecto de
  `navigation-compose`, sin nada declarado.
- **Extiende, no dupliques.** Si el spec decide añadir además un interruptor propio en Ajustes (que es
  una decisión de producto: el sistema ya tiene el suyo, y duplicarlo puede confundir), la preferencia
  va al DataStore `user_prefs` existente siguiendo el patrón de
  [`UserPreferencesRepository.kt`](../../app/src/main/java/com/neverlate/data/UserPreferencesRepository.kt).
  La lectura del ajuste del sistema se hace con `Settings.Global.ANIMATOR_DURATION_SCALE` desde el
  `contentResolver`, no con una heurística inventada.
- **La pregunta de producto que el spec debe contestar:** ¿un ajuste propio en la app, o solo obedecer
  al del sistema? Obedecer al sistema es menos código y menos superficie; un ajuste propio ayuda a
  quien quiere menos movimiento **solo aquí**. Hay argumentos para las dos; lo que no vale es no
  elegir.
- **Diseño (obligatorio en el spec):** el criterio visual central es que **con movimiento reducido la
  app siga siendo comprensible**: las tareas siguen apareciendo y desapareciendo de la lista, la barra
  sigue reflejando el tiempo consumido — simplemente saltan en vez de deslizarse. Nada debe quedarse
  congelado ni desaparecer. Hay que **actualizar la fila 🟡 de accesibilidad** de
  `docs/mockups/README.md`, que es la única parcial de la tabla.
- **Fuera de alcance:** rediseñar o añadir animaciones nuevas, y el resto del repaso de accesibilidad
  pendiente por pantalla (esa fila 🟡 cubre más cosas que esta).
- Sin backend, sin contrato, sin migración de Room, sin dependencia nueva, sin permiso nuevo (leer
  `Settings.Global` no requiere ninguno).
- Ficheros:
  [`CountdownTicker.kt`](../../app/src/main/java/com/neverlate/ui/tasks/CountdownTicker.kt),
  [`TasksViewModel.kt`](../../app/src/main/java/com/neverlate/ui/tasks/TasksViewModel.kt),
  [`TasksScreen.kt`](../../app/src/main/java/com/neverlate/ui/tasks/TasksScreen.kt),
  [`ArticlesListDetailPane.kt`](../../app/src/main/java/com/neverlate/ui/articles/ArticlesListDetailPane.kt),
  y un lugar compartido nuevo para el criterio (`ui/components/` o `ui/theme/`).
- Agentes: `mobile-engineer` (el criterio compartido y la cadencia del contador), `qa-engineer` (test
  JVM de que el intervalo del ticker cambia con el criterio; verificación manual activando
  *Accesibilidad → Quitar animaciones* y comprobando que la lista y la barra siguen siendo legibles y
  que la pantalla deja de recomponerse cada segundo).
