# Lección 13b — Migraciones de Room reales + `TypeConverter`

Cada vez que el proyecto cambiaba el esquema de la base de datos (una columna nueva, una tabla nueva),
la app **borraba todos los datos del dispositivo** y los volvía a crear vacíos. Se llama
`fallbackToDestructiveMigration` y era un atajo aceptable *antes de publicar*: no había usuarios reales
a los que perder nada. La lección 04c dio el primer paso serio con una migración de verdad
(`MIGRATION_3_4`), pero eligió a propósito el caso **fácil**: una columna *nullable* de un tipo que Room
ya sabe guardar (`Long?`).

Esta lección va a por lo que 04c dejó fuera:

1. Una columna **`NOT NULL` con `DEFAULT`**, para que las filas que ya existen reciban un valor válido.
2. Un campo de un tipo que Room **no** sabe guardar (un `enum`), que **obliga** a escribir un
   `@TypeConverter`.
3. **Exportar el esquema** (`exportSchema = true`) y escribir un **test de migración** con
   `MigrationTestHelper` que *demuestra* que los datos sobreviven al salto 4 → 5.
4. El contraste entre una **`Migration` manual** y una **`AutoMigration`**: cuándo usar cada una.

La feature vehículo es pequeña pero útil: `Task` gana un campo **`priority`** (`NONE`/`LOW`/`MEDIUM`/
`HIGH`), que el usuario ve como un selector en la pantalla de edición y como un puntito de color en la
lista. Elegimos un `enum` (y no un simple `String` como `notes`) precisamente porque un `enum` *fuerza*
el `TypeConverter` — que es el corazón de la lección.

## Conceptos que aprendes aquí

Partiendo de la 04 (Room, `@Entity`, `@Dao`), la 10 (`ArticleEntity` en la misma base de datos) y la 11
(metadatos de sincronización, `Converters` para `SyncState`):

- **Migraciones de Room:** `Migration(from, to)` manual con `ALTER TABLE`, `AutoMigration` generada por
  Room, y **por qué `fallbackToDestructiveMigration` no vale en producción**.
- **`TypeConverter` + `@TypeConverters`:** cómo persistir un tipo que Room no conoce (aquí un `enum`;
  el mismo patrón sirve para `List`, fechas, etc.).
- **Versionado del esquema:** subir `version`, activar `exportSchema`, y el fichero JSON por versión que
  eso genera.
- **Compatibilidad de datos hacia atrás:** el `DEFAULT` que da un valor a las filas viejas al añadir una
  columna `NOT NULL`.
- **Probar la migración de verdad:** `MigrationTestHelper` crea una base de datos en la versión antigua,
  ejecuta la migración y **valida** el resultado contra el esquema exportado.

---

## 1. Por qué `fallbackToDestructiveMigration` no vale

Cuando Room abre una base de datos en disco y su `version` es menor que la del código, tiene que llevar
el esquema de la versión vieja a la nueva. Si no le has dado una ruta para hacerlo, lanza una excepción.
`fallbackToDestructiveMigration` es la salida de emergencia: *si no sé migrar, borro todas las tablas y
las creo de cero*. Rápido de escribir, pero significa **perder todos los datos del usuario** en cada
actualización que toque el esquema.

Hasta la 04c eso era aceptable porque la app aún no se había publicado. Pero la lección 13 (modo
invitado) rompió esa suposición: **las tareas de un invitado viven solo en el dispositivo** (sin cuenta,
sin sincronización). Una migración destructiva ahora las perdería para siempre. Por eso, de la versión 4
en adelante, cada cambio de esquema **conserva los datos**. `fallbackToDestructiveMigration` sigue
registrado, pero solo como red de seguridad para saltos de versión que no tengan ninguna `Migration`
declarada — nunca es el camino que tomamos a propósito.

---

## 2. La `Migration` manual: `ALTER TABLE`

Una `Migration` describe, en SQL crudo, cómo pasar de una versión del esquema a la siguiente **sin
tocar las filas que ya hay**. Room la ejecuta automáticamente la primera vez que abre una base de datos
que todavía está en la versión de origen. Nuestra migración vive en
[`NeverLateDatabase.kt`](../app/src/main/java/com/neverlate/data/tasks/NeverLateDatabase.kt), justo al
lado de la `MIGRATION_3_4` que escribió la 04c:

```kotlin
val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE tasks ADD COLUMN priority TEXT NOT NULL DEFAULT 'NONE'")
    }
}
```

Y se registra en el builder:

```kotlin
Room.databaseBuilder(context, NeverLateDatabase::class.java, DATABASE_NAME)
    .addMigrations(MIGRATION_3_4, MIGRATION_4_5)   // ← ambas registradas, en orden
    .fallbackToDestructiveMigration(dropAllTables = true)   // solo como red de seguridad
    .build()
```

Fíjate en la diferencia clave con la `MIGRATION_3_4`:

| | `MIGRATION_3_4` (04c) | `MIGRATION_4_5` (esta lección) |
|---|---|---|
| Columna | `completedAt INTEGER` | `priority TEXT` |
| ¿Nullable? | Sí (`Long?`) | **No** (`NOT NULL`) |
| ¿Necesita `DEFAULT`? | No — las filas viejas quedan a `NULL` | **Sí** — ver §4 |

---

## 3. `TypeConverter`: guardar un tipo que Room no conoce

Room solo sabe guardar un puñado de tipos de columna: números, textos, booleanos, `ByteArray`… Un `enum`
como `Priority` **no** es uno de ellos. Si intentas poner `val priority: Priority` en la `@Entity` sin
más, Room ni siquiera compila.

La solución es un **`@TypeConverter`**: un par de funciones que le dicen a Room cómo convertir el tipo
que no conoce a uno que sí (`TEXT`) y de vuelta. El proyecto ya tenía un `@TypeConverter` desde la 11
(para el `enum SyncState`), así que **extendemos** la clase existente en vez de crear una nueva — en
[`Converters.kt`](../app/src/main/java/com/neverlate/data/sync/Converters.kt):

```kotlin
@TypeConverter
fun fromPriority(value: Priority): String = value.name   // al escribir: enum -> TEXT

@TypeConverter
fun toPriority(value: String): Priority =                // al leer: TEXT -> enum
    Priority.entries.firstOrNull { it.name == value } ?: Priority.NONE
```

Dos detalles importantes:

- **Guardamos `name`, no `ordinal`.** `value.name` es el texto `"HIGH"`, no el número `3`. Así el valor
  en la base de datos es legible con un inspector, y sobre todo **sobrevive a reordenar el enum**: si
  mañana insertas `URGENT` entre `MEDIUM` y `HIGH`, los `ordinal` de todo lo demás cambian y corromperían
  las filas viejas; los `name` no.
- **La lectura es tolerante.** Si el texto guardado no coincide con ningún valor (porque una versión
  futura renombró una constante, o porque llegó basura), devolvemos `Priority.NONE` en vez de reventar.
  Es el mismo criterio de *forward-compat* que aplica el lado de red (ver §7).

Como la clase `Converters` ya está registrada en la base de datos con
`@TypeConverters(Converters::class)`, **no hay que registrar nada más**: en cuanto añades el par de
métodos, Room los descubre y ya puede persistir `Priority`.

> El mismo patrón sirve para cualquier tipo: una `List<String>` (conviértela a un `String` con comas o
> JSON), una fecha `Instant` (guárdala como `Long` de epoch millis), un tipo propio… El
> `@TypeConverter` es el puente genérico entre "tu tipo" y "lo que SQLite sabe guardar".

---

## 4. Compatibilidad de datos: el `DEFAULT` para las filas viejas

Aquí está la sutileza que 04c no tuvo que resolver. Añadir una columna **`NOT NULL`** a una tabla que ya
tiene filas es un problema: esas filas no traen valor para la columna nueva, y `NOT NULL` prohíbe dejarla
vacía. SQLite rechazaría el `ALTER TABLE`.

La solución es el `DEFAULT`:

```sql
ALTER TABLE tasks ADD COLUMN priority TEXT NOT NULL DEFAULT 'NONE'
```

`'NONE'` es exactamente `Priority.NONE.name` — el valor que le corresponde a una tarea que nunca tuvo
prioridad. Cada fila que ya existía queda con `priority = 'NONE'` tras la actualización, que es justo lo
que el usuario esperaría ver. En el `@Entity` reflejamos el mismo valor por defecto en Kotlin, para que
una tarea nueva creada desde el código también arranque en `NONE`:

```kotlin
val priority: Priority = Priority.NONE,
```

Los dos defaults (el de SQL y el de Kotlin) tienen que coincidir: uno cubre las filas que migran, el otro
las filas que se crean.

---

## 5. `Migration` manual vs `AutoMigration`

Para un cambio puramente **aditivo** como este (añadir una columna), Room puede escribir el SQL por ti:
es la **`AutoMigration`**. Se declara en la anotación `@Database` y Room genera la migración comparando
el esquema exportado de la versión vieja con el de la nueva:

```kotlin
@Database(
    version = 5,
    exportSchema = true,
    autoMigrations = [AutoMigration(from = 4, to = 5)],   // Room genera el ALTER TABLE
)
```

Entonces, ¿por qué en esta lección la escribimos **a mano**? Por dos razones:

- **Didáctica.** El objetivo es *ver* el SQL que ejecuta una migración. Con `AutoMigration` el SQL queda
  oculto; escribiéndolo aprendes qué hace realmente.
- **`AutoMigration` no lo puede todo sola.** Sirve para cambios aditivos simples. En cuanto la migración
  necesita una decisión que Room no puede adivinar del *diff* (renombrar una columna, partir una tabla,
  rellenar la columna nueva calculándola a partir de otras), tienes que escribir la `Migration` a mano —
  a veces con la ayuda de una `AutoMigrationSpec`. Saber escribir el `ALTER TABLE` es la habilidad base;
  `AutoMigration` es el atajo para el caso fácil.

La regla práctica: **`AutoMigration` para lo aditivo trivial, `Migration` manual para todo lo demás.**

---

## 6. Versionar y exportar el esquema

Para que un test de migración pueda existir, Room necesita saber cómo era el esquema en cada versión. Eso
se activa con `exportSchema = true` en la anotación `@Database` (antes estaba en `false`):

```kotlin
@Database(
    entities = [Task::class, ArticleEntity::class, OutboxEntity::class],
    version = 5,
    exportSchema = true,
)
```

…más un argumento para el procesador KSP que le dice **dónde** escribir los JSON, en
[`app/build.gradle.kts`](../app/build.gradle.kts):

```kotlin
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}
```

Con eso, cada compilación escribe un fichero por versión en `app/schemas/…/`: `5.json` describe la tabla
`tasks` **con** la columna `priority`. Estos JSON se **commitean** al repositorio: son parte del historial
del esquema.

### El problema del `4.json` que no existía

Hay una trampa que conviene entender. `MigrationTestHelper` prueba el salto 4 → 5, así que necesita
**dos** esquemas: `4.json` (el de origen) y `5.json` (el de destino). Pero la versión 4 se publicó con
`exportSchema = false`, ¡así que `4.json` nunca se generó! Sin él, el test no tiene desde dónde migrar.

La solución es generar el `4.json` que *habría* existido: compilar el módulo una vez con la exportación
activada pero todavía en la versión 4 (con `Task` **sin** el campo `priority`), lo que produce el
`4.json` correcto, y luego volver a la versión 5. Es un paso puntual; a partir de ahora cada versión deja
su JSON automáticamente y no hay que volver a hacerlo.

---

## 7. Probar la migración: `MigrationTestHelper`

Este es el test estrella de la lección. Vive en
[`MigrationTest.kt`](../app/src/androidTest/java/com/neverlate/data/tasks/MigrationTest.kt) (es un test
**instrumentado**, en `androidTest`, porque necesita un SQLite real). Necesita la dependencia
`androidx.room:room-testing` (la añadimos al catálogo de versiones) y que los JSON del esquema estén
disponibles como *assets* del test:

```kotlin
sourceSets {
    getByName("androidTest").assets.srcDir("$projectDir/schemas")
}
```

El test tiene tres pasos: **sembrar** una fila en la versión vieja, **migrar**, y **comprobar** que la
fila sobrevivió con la columna nueva a `NONE`:

```kotlin
@get:Rule
val helper = MigrationTestHelper(
    InstrumentationRegistry.getInstrumentation(),
    NeverLateDatabase::class.java,
    emptyList(),
    FrameworkSQLiteOpenHelperFactory(),
)

@Test
fun migrate4To5_preservesExistingTasks_andDefaultsPriorityToNone() {
    // 1. Base de datos en la versión 4 (sin priority) + una fila real, por SQL crudo.
    helper.createDatabase(TEST_DB, 4).use { db ->
        db.execSQL(
            "INSERT INTO tasks (title, estimatedDurationMillis, deadline, updatedAt, syncState, deleted, completedAt) " +
                "VALUES ('Comprar leche', 600000, 1751900000000, 42, 'SYNCED', 0, NULL)",
        )
    }

    // 2. Ejecuta la migración y VALIDA el esquema resultante contra 5.json.
    val db = helper.runMigrationsAndValidate(TEST_DB, 5, true, NeverLateDatabase.MIGRATION_4_5)

    // 3. La fila sigue ahí, con sus campos intactos y priority por defecto = 'NONE'.
    db.query("SELECT title, priority FROM tasks").use { cursor ->
        assertEquals(1, cursor.count)
        cursor.moveToFirst()
        assertEquals("Comprar leche", cursor.getString(0))
        assertEquals("NONE", cursor.getString(1))
    }
}
```

Lo potente es ese `runMigrationsAndValidate` con el último `true` ("valida el esquema"): si el SQL de tu
`Migration` **no coincidiera** con lo que Room espera de la `@Entity` (un tipo mal puesto, un `NOT NULL`
olvidado, un nombre de columna con una errata), el test **falla ahí mismo**. Es exactamente la red de
seguridad que `fallbackToDestructiveMigration` nunca dio: la garantía de que la migración y la entidad
dicen lo mismo.

Se ejecuta con un emulador/dispositivo conectado:

```bash
./gradlew :app:connectedDebugAndroidTest
```

Además, un test JVM más ligero cubre el `@TypeConverter` en aislamiento
([`ConvertersTest.kt`](../app/src/test/java/com/neverlate/data/sync/ConvertersTest.kt)): que cada
`Priority` va y vuelve por su `name`, y que un valor desconocido cae a `NONE` sin reventar.

---

## 8. El campo en la interfaz

La prioridad no serviría de nada si no se pudiera ni poner ni ver. Reutilizamos piezas que ya existían:

- **Selector en edición** ([`TaskEditScreen.kt`](../app/src/main/java/com/neverlate/ui/tasks/TaskEditScreen.kt)):
  un grupo de `FilterChip` de Material 3 dentro de un `FlowRow`, exactamente el patrón que la lección 03b
  ya usa para los controles de orden/filtro. Uno por cada `Priority`, selección única. El `FlowRow` deja
  que los chips salten de línea con la fuente más grande, en vez de recortarse.
- **Indicador en la lista** ([`TasksScreen.kt`](../app/src/main/java/com/neverlate/ui/tasks/TasksScreen.kt)):
  un puntito pequeño delante del título para las prioridades distintas de `NONE`. El color sale **solo de
  los tokens del tema** (`MaterialTheme.colorScheme.primary`/`tertiary`/`secondary`), nunca de un hex
  suelto, para que respete claro/oscuro. Una tarea completada **no** muestra el puntito: su estilo tachado
  y apagado manda, igual que ya oculta la cuenta atrás.

Las etiquetas (`Ninguna`/`Baja`/`Media`/`Alta`) y la descripción de accesibilidad viven en
`strings.xml` (español) y `values-en/strings.xml` (inglés), siguiendo la i18n de la lección 08. El mapeo
`Priority → recurso/color` está aislado en
[`PriorityUi.kt`](../app/src/main/java/com/neverlate/ui/tasks/PriorityUi.kt), para que el `enum` de datos
no dependa de Android.

---

## 9. Que la prioridad viaje: sincronización

Como `completedAt` en la 04c, `priority` **se sincroniza de punta a punta**. Y como allí, no hace falta
ninguna lógica de sync nueva: la prioridad viaja como un campo más y se reconcilia con el mismo
*last-write-wins* por `updatedAt` que ya existe. Lo que sí hay que tocar, **empezando por el contrato**
([`docs/api/contract.md`](../docs/api/contract.md), la fuente de verdad):

1. **El contrato** gana `priority` en el `TaskDto` (§4) y en los cuerpos de `POST`/`PATCH`, documentando
   que un valor ausente o desconocido equivale a `NONE`.
2. **El cliente** añade `priority` al `TaskDto`/`CreateTaskRequest`/`UpdateTaskRequest` de red y lo mapea
   en ambos sentidos (`TaskMapping.kt`). Como el `enum` es `@Serializable`, kotlinx.serialization lo
   codifica por su `name` — el mismo `"HIGH"` del contrato.
3. **El backend** añade la columna `priority TEXT NOT NULL DEFAULT 'NONE'` (aditiva, no destructiva, igual
   que el resto de su esquema) y la pasa por `create`/`pull`/`patch`.

Un detalle de robustez: tanto el cliente como el servidor **coercionan** un valor desconocido a `NONE`. En
el cliente se activa con `coerceInputValues = true` en la config de `Json`; en el servidor, con una
función `normalizePriority` que descarta cualquier cosa fuera del conjunto válido. Así, un cliente viejo
que nunca envíe `priority`, o uno que envíe basura, nunca corrompe la columna ni la respuesta.

---

## Repaso: ficheros de la feature

- [`Priority.kt`](../app/src/main/java/com/neverlate/data/tasks/Priority.kt) — el `enum` nuevo.
- [`Task.kt`](../app/src/main/java/com/neverlate/data/tasks/Task.kt) — el campo `priority`.
- [`Converters.kt`](../app/src/main/java/com/neverlate/data/sync/Converters.kt) — el `@TypeConverter`.
- [`NeverLateDatabase.kt`](../app/src/main/java/com/neverlate/data/tasks/NeverLateDatabase.kt) —
  `version = 5`, `exportSchema = true`, `MIGRATION_4_5`.
- [`app/build.gradle.kts`](../app/build.gradle.kts) — `room.schemaLocation`, assets del esquema,
  dependencia `room-testing`.
- `app/schemas/…/4.json` y `5.json` — los esquemas exportados (commiteados).
- [`MigrationTest.kt`](../app/src/androidTest/java/com/neverlate/data/tasks/MigrationTest.kt) y
  [`ConvertersTest.kt`](../app/src/test/java/com/neverlate/data/sync/ConvertersTest.kt) — los tests.
- UI: [`TaskEditScreen.kt`], [`TasksScreen.kt`], [`PriorityUi.kt`], `strings.xml`.
- Sync: [`TaskDto.kt`], [`TaskMapping.kt`], `docs/api/contract.md`, y el `backend/` (`TaskModels`,
  `PostgresTaskRepository`, `TaskService`, `TaskRoutes`, `Database`).

## Lo que te llevas

- Una **migración de verdad** conserva los datos; `fallbackToDestructiveMigration` los borra y solo vale
  antes de publicar (o como red de seguridad).
- Añadir una columna **`NOT NULL` necesita un `DEFAULT`** para las filas que ya existen.
- Un tipo que Room no conoce (un `enum`) se persiste con un **`@TypeConverter`**; guarda el `name`, no el
  `ordinal`, y lee de forma tolerante.
- **`AutoMigration`** genera el SQL de los cambios aditivos simples; la **`Migration` manual** es para
  todo lo demás — y para *entender* qué pasa.
- Con `exportSchema` + `MigrationTestHelper` una migración deja de ser un acto de fe: se **prueba** que los
  datos sobreviven.
