# Lección 12b (bugfix) — Cuando el Keystore deja de descifrar: recuperación y exclusión de backup

> Objetivo: entender y arreglar un **crash real** que solo aparecía en dispositivos físicos (no en el
> emulador): la app se cerraba **nada más abrirse**, en bucle, sin forma de entrar. Esta no es una
> feature nueva sino un **bugfix**, pero enseña conceptos importantes de robustez que las lecciones 11
> y 12 dejaron implícitos: qué pasa cuando la clave del **Android Keystore** y el fichero cifrado de
> `EncryptedSharedPreferences` **se desincronizan**, cómo **recuperarse** en vez de crashear, y por qué
> los secretos **no deben viajar** en un backup. Reutilizamos todo de la 11/12: `EncryptedTokenStorage`,
> el *seam* `TokenStorage`, y el patrón de **extraer lógica pura para poder testearla** (como
> `ReminderPlanning.kt` o `SyncMerge.kt`). **Extendemos, no duplicamos.**

## El síntoma

En el emulador todo iba bien. Pero al instalar sobre un dispositivo físico (un Xiaomi/MIUI), la app
petaba en el arranque con este stack trace:

```
java.lang.RuntimeException: Unable to start activity ...MainActivity: javax.crypto.AEADBadTagException
Caused by: javax.crypto.AEADBadTagException
    at ...EncryptedSharedPreferences.create(...)
    at ...EncryptedTokenStorage.getAccessToken(TokenStorage.kt:82)
    at ...AuthRepositoryImpl.readInitialAuthState(AuthRepository.kt:114)
    at ...MainActivity.onCreate(MainActivity.kt:84)
Caused by: android.security.KeyStoreException: Signature/MAC verification failed
```

La clave está en la última línea: **`Signature/MAC verification failed`**. No es un problema de red,
de migración de base de datos ni de permisos: es **criptográfico**.

## Conceptos que aprendes aquí

- **Qué garantiza AES-GCM (AEAD) y por qué falla "en verificación".** GCM es un cifrado *autenticado*
  (AEAD = *Authenticated Encryption with Associated Data*): además de cifrar, adjunta una **etiqueta de
  autenticación** (MAC). Al descifrar, si los datos —o **la clave**— no cuadran con esa etiqueta, no te
  devuelve basura: **lanza `AEADBadTagException`**. Es una feature de seguridad (detecta manipulación),
  pero aquí nos estalla en la cara.
- **Por qué la clave y el fichero se desincronizan.** `EncryptedSharedPreferences` sella el fichero con
  una **clave maestra ligada al hardware** que **nunca sale del Keystore**. Si el fichero cifrado
  aparece en un dispositivo/instalación cuyo Keystore tiene **otra** clave (restaurado desde un backup,
  ciertas OTAs, o el Auto Backup agresivo de algunos OEM como MIUI), la etiqueta GCM ya no valida →
  `AEADBadTagException`.
- **Recuperarse en vez de crashear.** Cómo capturar el fallo, **borrar el estado corrupto** y
  **recrear** el almacén limpio **una sola vez** (sin bucle infinito). El usuario pierde la sesión
  (vuelve a `Guest`/login), pero la app **arranca**.
- **Excluir los secretos del backup.** `dataExtractionRules` (Android 12+) y `fullBackupContent`
  (Android 11 y anteriores) para que el fichero cifrado **no se respalde ni se transfiera** — atacando
  la causa raíz, no solo el síntoma.
- **Extraer lógica pura para testear lo intestable.** El Keystore real no existe en la JVM, así que
  sacamos el flujo de recuperación a una **función pura** con sus dependencias inyectadas como lambdas.

---

## 1. Por qué solo pasaba en el móvil físico

El emulador se instala casi siempre "desde cero" y su Keystore y el fichero cifrado nacen juntos, así
que nunca se desincronizan. Un móvil real es otra historia: **Android Auto Backup** está activo por
defecto (`android:allowBackup="true"`), sube tus `SharedPreferences` a la nube y **los restaura** al
reinstalar o al migrar de dispositivo. El problema:

- El **fichero cifrado** (`auth_secure_prefs`) **sí** se restaura.
- La **clave maestra** del Keystore es *hardware-bound* y **no** se restaura.

Resultado: en la instalación nueva tienes un fichero cifrado con una clave que ya no existe. Al primer
`getAccessToken()` en el arranque (`AuthRepositoryImpl.readInitialAuthState`), `EncryptedSharedPreferences.create`
intenta descifrar el keyset y revienta. Como pasa en `onCreate`, **la app no llega a pintar nada**.

## 2. La recuperación: borrar y recrear (una vez)

En [`TokenStorage.kt`](../app/src/main/java/com/neverlate/data/auth/TokenStorage.kt), la creación de
`prefs` estaba "desnuda": si `create` lanzaba, nadie lo cogía. Ahora la envolvemos:

```kotlin
private val prefs by lazy {
    createEncryptedPrefsWithRecovery(
        build = { buildEncryptedPrefs() },
        deleteCorruptState = { context.deleteSharedPreferences(PREFS_FILE_NAME) },
    )
}
```

Fíjate en que **no** metemos el `try/catch` dentro de la clase: lo extraemos a una **función pura** que
recibe sus dos efectos como lambdas —`build` (crear el almacén) y `deleteCorruptState` (borrar el
fichero de disco)—. Es el mismo patrón que ya usamos para poder testear lógica que depende de Android
sin arrancar Android (`ReminderPlanning.kt`, `domain/sync/SyncMerge.kt`):

```kotlin
internal fun createEncryptedPrefsWithRecovery(
    build: () -> SharedPreferences,
    deleteCorruptState: () -> Unit,
): SharedPreferences =
    try {
        build()
    } catch (e: GeneralSecurityException) {   // AEADBadTagException hereda de aquí
        recoverCorruptPrefs(e, build, deleteCorruptState)
    } catch (e: IOException) {                // keyset ilegible en disco
        recoverCorruptPrefs(e, build, deleteCorruptState)
    }

private fun recoverCorruptPrefs(
    cause: Exception,
    build: () -> SharedPreferences,
    deleteCorruptState: () -> Unit,
): SharedPreferences {
    Log.w("EncryptedTokenStorage", "Encrypted token store unreadable; clearing and recreating", cause)
    deleteCorruptState()
    return build()   // segundo (y ÚLTIMO) intento: regenera un keyset nuevo y vacío
}
```

Dos detalles didácticos:

1. **`AEADBadTagException` hereda de `GeneralSecurityException`** (vía `BadPaddingException`), así que un
   único `catch` la cubre sin acoplarnos al tipo exacto. Cogemos también `IOException` por si el keyset
   quedó ilegible en disco.
2. **Recuperamos exactamente una vez.** El `recoverCorruptPrefs` llama a `build()` **directamente**, no
   se vuelve a envolver. Si ese segundo intento también falla (algo más grave), la excepción **propaga**
   en vez de entrar en un bucle. Al borrar `auth_secure_prefs`, se va el keyset viejo; el `create`
   siguiente genera uno nuevo con la clave *actual* del Keystore y arranca vacío.

`context.deleteSharedPreferences(...)` existe desde API 24, justo nuestro `minSdk`, así que no
necesitamos *fallbacks*.

## 3. La causa raíz: no respaldes los secretos

Recuperarse está bien, pero mejor es que el fichero **nunca** se restaure a un dispositivo sin su clave.
Un token *bearer* no debería salir del dispositivo de todas formas. Añadimos dos ficheros de reglas y
los enganchamos en el manifest:

`app/src/main/res/xml/data_extraction_rules.xml` (Android 12+):

```xml
<data-extraction-rules>
    <cloud-backup>
        <exclude domain="sharedpref" path="auth_secure_prefs.xml" />
    </cloud-backup>
    <device-transfer>
        <exclude domain="sharedpref" path="auth_secure_prefs.xml" />
    </device-transfer>
</data-extraction-rules>
```

`app/src/main/res/xml/backup_rules.xml` (Android 11 y anteriores):

```xml
<full-backup-content>
    <exclude domain="sharedpref" path="auth_secure_prefs.xml" />
</full-backup-content>
```

Y en [`AndroidManifest.xml`](../app/src/main/AndroidManifest.xml):

```xml
<application
    android:allowBackup="true"
    android:dataExtractionRules="@xml/data_extraction_rules"
    android:fullBackupContent="@xml/backup_rules"
    ... >
```

¿Por qué **dos** ficheros? Google cambió el formato en Android 12: `fullBackupContent` es el esquema
antiguo (solo *backup*), y `dataExtractionRules` el nuevo, que además distingue **backup en la nube** de
**transferencia directa entre dispositivos**. Declaramos ambos para cubrir todo el rango `minSdk 24 →
targetSdk 36`. El `path` es el nombre del fichero **con** sufijo `.xml`, que es como Android guarda las
`SharedPreferences` en `shared_prefs/`.

## 4. Testear lo que no tiene Keystore

El Keystore real no existe en un test JVM, así que no podemos provocar un `AEADBadTagException` "de
verdad". Pero como extrajimos la lógica a `createEncryptedPrefsWithRecovery(build, deleteCorruptState)`,
podemos **inyectar el fallo**:

```kotlin
@Test
fun `on AEADBadTagException clears the corrupt file and rebuilds once`() {
    var builds = 0
    var deleteCalls = 0

    val result = createEncryptedPrefsWithRecovery(
        build = {
            builds++
            if (builds == 1) throw AEADBadTagException("tag mismatch") else goodPrefs
        },
        deleteCorruptState = { deleteCalls++ },
    )

    assertSame(goodPrefs, result)
    assertEquals(2, builds)       // un intento + un reintento
    assertEquals(1, deleteCalls)  // borró el fichero una vez
}
```

Cubrimos los cuatro casos que importan: (1) apertura limpia → no toca el fichero; (2) `AEADBadTagException`
→ borra y recrea una vez; (3) `IOException` → mismo camino; (4) si el reintento **también** falla, la
excepción propaga y **no** hay bucle. Ver
[`EncryptedPrefsRecoveryTest.kt`](../app/src/test/java/com/neverlate/data/auth/EncryptedPrefsRecoveryTest.kt).

## 5. Cómo desbloquear un dispositivo ya afectado

Si un móvil ya está en el bucle de crashes, el fichero corrupto sigue en sus datos. Con la app nueva ya
se auto-recupera al arrancar, pero para partir de cero manualmente:

```bash
adb uninstall com.neverlate      # o: adb shell pm clear com.neverlate
./gradlew :app:installDebug
```

Recuerda: un `installDebug` **encima** conserva los datos (por eso el crash persistía entre builds); solo
`uninstall`/`pm clear` los borra.

---

## Resumen

- `EncryptedSharedPreferences` puede lanzar **`AEADBadTagException`** si su fichero y la clave del
  Keystore se **desincronizan** (típico tras un restore de Auto Backup, y frecuente en MIUI).
- Sin recuperación, eso es un **crash-loop** en `onCreate`. Lo arreglamos capturando el fallo, **borrando
  el estado corrupto** y **recreando una vez**.
- Atacamos la **causa raíz** excluyendo el fichero cifrado del **backup y la transferencia** — que además
  es mejor postura de seguridad para un *bearer token*.
- Reforzamos el patrón de **extraer lógica pura** (con dependencias inyectadas como lambdas) para poder
  testear lo que depende de Android sin arrancar Android.
