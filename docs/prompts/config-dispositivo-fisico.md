# Config — Probar la app en un dispositivo físico contra el backend local

Lee `CLAUDE.md` (**Mandatory Workflow** + sección **Remote DB + offline-first sync** y **API Contract**)
y la lección 11 (backend + sync). Esto **no es una feature del tutorial** sino un arreglo de
**configuración de entorno de desarrollo**: hoy la app solo puede hablar con el backend desde el
**emulador**, y falla con "no hay conexión" al instalarla en un **teléfono físico**.

## El problema

- La base URL está fija a `http://10.0.2.2:8080/` en
  `app/src/main/java/com/neverlate/data/network/BackendNetwork.kt` (`DEFAULT_BACKEND_BASE_URL`).
  `10.0.2.2` es el alias que **solo el emulador** usa para llegar al `localhost` del PC; en un
  dispositivo físico no lleva a ninguna parte → "no hay conexión" al registrarse/loguearse.
- Además, el permiso de tráfico HTTP en claro (`app/src/debug/res/xml/network_security_config.xml`)
  solo abre `10.0.2.2` y `localhost`. Cualquier otra IP quedaría bloqueada por `targetSdk 36` aunque
  se corrija la URL.

## Qué construir

Que el mismo build de debug pueda apuntar al backend **sin editar código fuente**, para que funcione
tanto en emulador como en dispositivo físico. Elegir uno de estos enfoques (o soportar ambos):

- **Enfoque A — USB (`adb reverse`), recomendado:** permitir base URL `http://localhost:8080/`
  (ya está en la allowlist de cleartext). El flujo de uso es `adb reverse tcp:8080 tcp:8080` con el
  móvil por USB; no requiere Wi-Fi compartida ni tocar el firewall.
- **Enfoque B — Wi-Fi (IP de la LAN):** permitir apuntar a la IP del PC (p. ej. `192.168.x.x:8080`).
  Requiere backend escuchando en `0.0.0.0`, firewall abierto al puerto 8080, y añadir esa IP a
  `network_security_config.xml` (solo debug).

Hazlo **configurable** en lugar de hardcodear una IP nueva: expón la base URL como un `BuildConfig`
field alimentado desde `local.properties`/`gradle.properties` (no versionado), con default a
`10.0.2.2:8080/` para no romper el flujo del emulador. Así cada quien pone su IP/USB sin tocar
`.kt` ni commitear su red local.

## Conceptos nuevos a enseñar (nota breve, no lección completa)

- **`BuildConfig` fields desde Gradle:** cómo inyectar config de build (URLs, flags) sin hardcodear
  en Kotlin, leyéndola de `local.properties` para que no acabe en git.
- **`adb reverse` vs `10.0.2.2`:** por qué el alias del emulador no sirve en hardware real y cómo el
  reenvío de puertos por USB resuelve el `localhost`.
- **Network Security Config solo-debug:** por qué el cleartext se restringe a hosts concretos y por
  qué **nunca** se copia al manifest de release (recordatorio de la lección 11).

## Notas

- Rama sugerida: `bugfix/backend-url-dispositivo-fisico` (toca código fuente → no en `master`).
- **No** requiere cambios en `docs/api/contract.md` (el contrato de red no cambia, solo el destino).
- Mantén el default en `10.0.2.2:8080/` para no romper a quien prueba en emulador.
- Recuerda: sigue siendo **solo debug**. El aviso de "antes de desplegar, HTTPS obligatorio y sin
  excepción de cleartext en release" de la lección 11 sigue vigente.
- Actualiza `CLAUDE.md` (sección **Development → Backend**) documentando cómo probar en dispositivo
  físico (los pasos de `adb reverse` y/o de IP de LAN). Añade una nota breve al final de
  `tutorial/11-bbdd-remota.md` en lugar de una lección nueva, ya que es config y no una feature.
- Agentes: `mobile-engineer` (BuildConfig field + wiring), `devops-security-engineer` (revisar que la
  excepción de cleartext siga acotada y solo-debug).
