package com.neverlate.data.tasks

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Exercises [TaskDao] against a real, in-memory Room database (no fake/mock involved), covering
 * acceptance criteria 1 and 2 of the feature spec: CRUD persists to Room and [TaskDao.observeTasks]
 * / [TaskDao.observeTask] reflect it via [Flow][kotlinx.coroutines.flow.Flow]. An in-memory
 * database needs no disk cleanup and is destroyed automatically when [database] is closed, but it
 * still runs through the real Room-generated SQL Room would run against the on-disk database used
 * in production ([NeverLateDatabase.getInstance]) — the part that matters for "does CRUD actually
 * persist and get observed" is identical either way.
 *
 * Needs a connected device/emulator to run (`./gradlew :app:connectedDebugAndroidTest`); it does
 * not run as part of `:app:testDebugUnitTest`.
 */
@RunWith(AndroidJUnit4::class)
class TaskDaoTest {

    private lateinit var database: NeverLateDatabase
    private lateinit var dao: TaskDao

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        database = Room.inMemoryDatabaseBuilder(context, NeverLateDatabase::class.java).build()
        dao = database.taskDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun insertTask_appearsInObserveTasks() = runBlocking {
        val id = dao.insert(Task(title = "Comprar leche", estimatedDurationMillis = 10 * 60_000L))

        val tasks = dao.observeTasks().first()

        assertEquals(1, tasks.size)
        assertEquals(id, tasks.first().id)
        assertEquals("Comprar leche", tasks.first().title)
    }

    @Test
    fun updateTask_reflectedInObserveTaskById() = runBlocking {
        val id = dao.insert(Task(title = "Original", estimatedDurationMillis = 5 * 60_000L))
        val inserted = dao.observeTask(id).first()!!

        dao.update(inserted.copy(title = "Actualizada", estimatedDurationMillis = 15 * 60_000L))

        val updated = dao.observeTask(id).first()
        assertEquals("Actualizada", updated?.title)
        assertEquals(15 * 60_000L, updated?.estimatedDurationMillis)
    }

    @Test
    fun deleteById_removesTaskFromObserveTasksAndObserveTask() = runBlocking {
        val id = dao.insert(Task(title = "Borrar esto", estimatedDurationMillis = 5 * 60_000L))

        dao.deleteById(id)

        assertTrue(dao.observeTasks().first().isEmpty())
        assertNull(dao.observeTask(id).first())
    }

    @Test
    fun observeTask_forUnknownId_emitsNull() = runBlocking {
        assertNull(dao.observeTask(id = 12345L).first())
    }

    @Test
    fun observeTasks_returnsEveryInsertedTask() = runBlocking {
        val firstId = dao.insert(Task(title = "Primera", estimatedDurationMillis = 5 * 60_000L))
        val secondId = dao.insert(Task(title = "Segunda", estimatedDurationMillis = 5 * 60_000L))

        val ids = dao.observeTasks().first().map { it.id }.toSet()

        assertEquals(setOf(firstId, secondId), ids)
    }
}
