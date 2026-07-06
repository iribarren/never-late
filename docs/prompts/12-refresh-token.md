# Feature 12 (extra) — Refresh token y renovación silenciosa de sesión

Lee `CLAUDE.md` (**Tutorial Methodology** + **Mandatory Workflow**), la sección **API Contract**, y
las lecciones previas (en especial la 11: auth con JWT, interceptor Bearer, almacenamiento seguro del
token). Implementa **"refresh token + renovación silenciosa"** siguiendo el flujo `/feature`.

## Qué construir

- Que la sesión **se renueve sola** sin obligar al usuario a volver a teclear la contraseña cuando el
  **access token** JWT caduca. La feature 11 dejó esto **diferido** a propósito: en v1, un `401`
  llevaba directamente a login. Ahora el `401` debe **renovar** primero y solo caer a login si la
  renovación también falla.
- Un par **access token (corto) + refresh token (largo)**: el backend emite ambos en
  registro/login; el cliente usa el access token en cada llamada y el refresh token solo para pedir
  uno nuevo.
- **Rotación** del refresh token en cada uso (el refresh viejo se invalida), y **revocación** en
  logout.

## Conceptos nuevos a enseñar (lección en español)

- **Ciclo de vida de tokens:** por qué un access token de vida corta + un refresh de vida larga es más
  seguro que un único JWT eterno; qué se guarda dónde.
- **Renovación silenciosa en el cliente:** el `Authenticator` de OkHttp (distinto del interceptor de la
  11) que intercepta el `401`, pide un token nuevo **una sola vez**, y **reintenta** la petición
  original de forma transparente; manejo de renovaciones concurrentes (varias llamadas fallando a la
  vez sin disparar N refrescos).
- **Estado en el servidor para tokens de refresco:** a diferencia del JWT sin estado de la 11, el
  refresh token necesita **persistencia/rotación/revocación** en el backend (tabla de refresh tokens),
  y por qué eso cambia el modelo respecto a "stateless".
- **Seguridad:** rotación, detección de reuso de un refresh ya rotado (posible robo), y almacenamiento
  del refresh token también en almacenamiento cifrado (Keystore), nunca en claro.

## Notas

- Rama sugerida: `feature/refresh-token`.
- **Actualiza el contrato primero:** añade `POST /auth/refresh` (y la forma del par de tokens en
  register/login) a `docs/api/contract.md` **antes** de tocar cliente o servidor — es la fuente única
  de verdad (política de la sección API Contract). Ajusta ambos lados al contrato.
- Reutiliza lo de la 11: interceptor Bearer, `EncryptedTokenStorage`, el seam `AuthRepository`, el
  backend Ktor + Postgres. Extiende, no dupliques.
- Agentes: `backend-engineer` (endpoint + tabla + rotación), `mobile-engineer` (Authenticator +
  storage), `devops-security-engineer` (revisión de rotación/revocación/reuso), `qa-engineer` (tests:
  refresh con `MockWebServer` — 401→refresh→retry, refresh caducado→login, rotación, concurrencia).
- Lección en `tutorial/12-refresh-token.md` (español), numerada tras la 11.
