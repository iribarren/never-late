# Feature — Que el nombre del perfil sirva para algo (y se pueda cambiar)

Lee `CLAUDE.md` (**Tutorial Track (optional)** + **Mandatory Workflow** + **Design in the Workflow**)
y las lecciones previas (en especial la 02: el onboarding que pide el nombre; la 07: la pantalla de
ajustes y las preferencias en el DataStore `user_prefs`; y la 18: la navegación por barra inferior,
que retiró la pantalla Home). Implementa **"que el nombre que se pide al empezar se vea en algún sitio
y se pueda cambiar"** siguiendo el flujo `/feature`.

> **Ojo, esto no es solo "añadir un campo editable".** El nombre que el onboarding pide y guarda hoy
> **no lo lee absolutamente nadie**: el único consumidor era el saludo de la pantalla Home, y la
> feature 18 retiró esa pantalla. Es decir, la app pide un dato personal en el primer arranque y
> después lo ignora para siempre. Hacer editable un valor invisible es construir un formulario que no
> cambia nada. **El spec tiene que decidir primero dónde se ve el nombre**; la edición viene después,
> y es la parte fácil.

## Qué construir

- **Un sitio donde el nombre importe.** El spec elige cuál y lo justifica: un saludo en la cabecera de
  Tareas, la tarjeta de Cuenta en Ajustes, el estado vacío ("Nada pendiente, Aritz"), o retirar la
  pregunta del onboarding por completo si se concluye que el dato no aporta. **"Retirarlo" es una
  respuesta legítima** y el spec debe considerarla en serio antes de descartarla.
- **Poder cambiarlo** desde Ajustes, sin volver a pasar por el onboarding.
- El cambio se refleja **al momento** allí donde se muestre.

## Tutorial

Antes de escribir el spec, el flujo `/feature` **debe preguntar con `AskUserQuestion`** si esta
feature lleva lección en español (*Sí, con lección / No / Decidir al final*), y la respuesta se anota
en el campo `Tutorial:` del spec.

**Recomendación: No.** El patrón entero —preferencia en DataStore, campo en `SettingsUiState`, setter,
fila en Ajustes— es exactamente lo que enseñó la lección **07**, y el formulario de texto validado lo
enseñó la **02**. No hay concepto nuevo de Kotlin ni de Compose. Lo interesante aquí es de producto
(un dato que se pide y no se usa), y eso se documenta en el spec. Si aun así se quisiera lección, lo
único con sustancia sería *"escrituras atómicas en DataStore: por qué `saveOnboarding` guarda dos
claves en un solo `edit {}` y qué se rompe al reutilizarlo para otra cosa"*.

## Notas

- Rama sugerida: `feature/editable-profile`.
- **El detalle técnico que hay que respetar.** `saveOnboarding(name)` en
  [`UserPreferencesRepository.kt`](../../app/src/main/java/com/neverlate/data/UserPreferencesRepository.kt)
  escribe **dos** claves en un único `edit {}` atómico: el nombre y `onboarded = true`. Renombrar
  desde Ajustes **no debe reutilizar ese método** — hoy sería inofensivo (ya estás onboardeado), pero
  convierte el contrato del método en una mentira y la próxima persona que lo lea se equivocará. El
  spec elige: añadir un `saveName(name)` aparte, o partir `saveOnboarding` en dos. Cualquiera de las
  dos **rompe tres fakes de test** (`data/sync/SyncTestDoubles.kt`,
  `ui/settings/SettingsViewModelTest.kt`, `ui/onboarding/OnboardingViewModelTest.kt`); es esperado, no
  un problema.
- **Extiende, no dupliques.** Ajustes ya tiene tres tarjetas (Tema, Recordatorios, Cuenta) construidas
  con `SettingsSectionCard` en
  [`SettingsScreen.kt`](../../app/src/main/java/com/neverlate/ui/settings/SettingsScreen.kt): la de
  **Cuenta** ya existe y hoy solo lleva el botón de sesión, así que el nombre encaja ahí sin inventar
  una sección nueva. La validación del nombre (no vacío, recortado) ya la resolvió el onboarding en
  [`OnboardingViewModel.kt`](../../app/src/main/java/com/neverlate/ui/onboarding/OnboardingViewModel.kt)
  — reutiliza el criterio, no escribas otro distinto.
- **La pregunta que decide el tamaño de la feature:** ¿el nombre es un dato **del dispositivo** o **de
  la cuenta**? Hoy vive en el DataStore local y no cruza el cable: `docs/api/contract.md` no tiene
  ningún endpoint de perfil. Si el spec decide que debe sincronizarse entre dispositivos, esto deja de
  ser una feature de Ajustes y pasa a ser **un cambio de contrato + backend**, con todo lo que eso
  implica (contrato primero, cliente y servidor reconciliados). Recomendación: mantenerlo local y
  decirlo explícitamente. Y en cualquier caso el spec debe aclarar qué pasa con el nombre en **modo
  invitado** y al cerrar sesión.
- **Diseño (obligatorio en el spec):** hay dos superficies posibles (donde se muestra y donde se
  edita) y hay que diseñar las dos. Criterios visuales concretos: el nombre no rompe el layout cuando
  es muy largo (truncar, no envolver la cabecera); el campo editable tiene etiqueta y mensaje de error
  localizados; objetivos ≥48dp; reflow a escala de fuente máxima; y el saludo —si se elige— **no puede
  ser lo único que ocupa la cabecera**, que ya tiene su cromo de marca de la feature 20. Añadir la
  fila correspondiente a `docs/mockups/README.md` (`—`, fuera del maquetado maestro, que no tiene
  saludo).
- **Referencia visual:** abre `docs/mockups/rediseno-ux-ui.html` como guía de dirección, **no** código
  a copiar.
- **Fuera de alcance:** múltiples perfiles, avatar o foto, y cualquier cosa relacionada con la cuenta
  del backend (email, contraseña) — eso es superficie de auth, no de perfil.
- Sin backend, sin contrato, sin migración de Room (es DataStore), sin dependencia nueva, sin permiso.
- Ficheros:
  [`UserPreferencesRepository.kt`](../../app/src/main/java/com/neverlate/data/UserPreferencesRepository.kt),
  [`SettingsViewModel.kt`](../../app/src/main/java/com/neverlate/ui/settings/SettingsViewModel.kt),
  [`SettingsScreen.kt`](../../app/src/main/java/com/neverlate/ui/settings/SettingsScreen.kt),
  [`OnboardingViewModel.kt`](../../app/src/main/java/com/neverlate/ui/onboarding/OnboardingViewModel.kt)
  (criterio de validación, reutilizado), la pantalla donde se decida mostrarlo,
  [`strings.xml`](../../app/src/main/res/values/strings.xml) +
  [`values-en/strings.xml`](../../app/src/main/res/values-en/strings.xml).
- Agentes: `mobile-engineer` (preferencia, edición y la superficie que lo muestre), `qa-engineer`
  (tests JVM de la validación y de que renombrar no toca la marca de onboarding; test instrumentado de
  que el cambio se refleja sin reiniciar).
