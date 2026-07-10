package com.neverlate.backend.tasks

import com.neverlate.backend.articles.InMemoryArticleRepository
import com.neverlate.backend.auth.AuthRequest
import com.neverlate.backend.auth.AuthResponse
import com.neverlate.backend.auth.InMemoryRefreshTokenRepository
import com.neverlate.backend.auth.InMemoryUserRepository
import com.neverlate.backend.configureApp
import com.neverlate.backend.jsonClient
import com.neverlate.backend.testConfig
import io.ktor.client.HttpClient
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Covers acceptance criteria 3/8/9 (contract.md §3, spec's Acceptance Criteria): CRUD scoped to
 *  the authenticated user, cross-user isolation, idempotent create by `clientRef`, `since=`
 *  tombstones, and PATCH last-write-wins. Every test wires a fresh
 *  [InMemoryTaskRepository]/[InMemoryUserRepository] pair, so tests are hermetic and need no
 *  Docker/Postgres. */
class TaskRoutesTest {

    private suspend fun HttpClient.registerAndLogin(email: String): AuthResponse {
        val response = post("/auth/register") {
            contentType(ContentType.Application.Json)
            setBody(Json.encodeToString(AuthRequest.serializer(), AuthRequest(email, "password123")))
        }
        return Json.decodeFromString(AuthResponse.serializer(), response.bodyAsText())
    }

    private suspend fun HttpClient.createTask(
        token: String,
        clientRef: String,
        title: String = "Buy milk",
        estimatedDurationMillis: Long? = 1_800_000L,
        deadline: Long? = null,
        completedAt: Long? = null,
        priority: String = "NONE",
    ): HttpResponse = post("/tasks") {
        header("Authorization", "Bearer $token")
        contentType(ContentType.Application.Json)
        setBody(
            Json.encodeToString(
                CreateTaskRequest.serializer(),
                CreateTaskRequest(
                    clientRef = clientRef,
                    title = title,
                    estimatedDurationMillis = estimatedDurationMillis,
                    deadline = deadline,
                    completedAt = completedAt,
                    priority = priority,
                    updatedAt = System.currentTimeMillis(),
                ),
            ),
        )
    }

    @Test
    fun `create then list via since=0 returns the task`() = testApplication {
        application { configureApp(testConfig(), InMemoryUserRepository(), InMemoryTaskRepository(), InMemoryRefreshTokenRepository(), InMemoryArticleRepository()) }
        val client = jsonClient()
        val auth = client.registerAndLogin("alice@example.com")

        val createResponse = client.createTask(auth.accessToken, clientRef = "ref-1")
        assertEquals(HttpStatusCode.Created, createResponse.status)
        val created = Json.decodeFromString(TaskDto.serializer(), createResponse.bodyAsText())

        val listResponse = client.get("/tasks?since=0") { header("Authorization", "Bearer ${auth.accessToken}") }
        val list = Json.decodeFromString(TasksResponse.serializer(), listResponse.bodyAsText())
        assertEquals(1, list.tasks.size)
        assertEquals(created.id, list.tasks.first().id)
    }

    @Test
    fun `posting the same clientRef twice does not create a duplicate`() = testApplication {
        application { configureApp(testConfig(), InMemoryUserRepository(), InMemoryTaskRepository(), InMemoryRefreshTokenRepository(), InMemoryArticleRepository()) }
        val client = jsonClient()
        val auth = client.registerAndLogin("bob@example.com")

        val first = client.createTask(auth.accessToken, clientRef = "same-ref")
        val second = client.createTask(auth.accessToken, clientRef = "same-ref")

        assertEquals(HttpStatusCode.Created, first.status)
        assertEquals(HttpStatusCode.OK, second.status) // idempotent replay, not a second create
        val firstTask = Json.decodeFromString(TaskDto.serializer(), first.bodyAsText())
        val secondTask = Json.decodeFromString(TaskDto.serializer(), second.bodyAsText())
        assertEquals(firstTask.id, secondTask.id)

        val list = Json.decodeFromString(
            TasksResponse.serializer(),
            client.get("/tasks?since=0") { header("Authorization", "Bearer ${auth.accessToken}") }.bodyAsText(),
        )
        assertEquals(1, list.tasks.size)
    }

    @Test
    fun `user A cannot read or modify user B's task`() = testApplication {
        application { configureApp(testConfig(), InMemoryUserRepository(), InMemoryTaskRepository(), InMemoryRefreshTokenRepository(), InMemoryArticleRepository()) }
        val client = jsonClient()
        val userA = client.registerAndLogin("userA@example.com")
        val userB = client.registerAndLogin("userB@example.com")

        val bTask = Json.decodeFromString(
            TaskDto.serializer(),
            client.createTask(userB.accessToken, clientRef = "b-ref").bodyAsText(),
        )

        val readAttempt = client.get("/tasks?since=0") { header("Authorization", "Bearer ${userA.accessToken}") }
        val aTasks = Json.decodeFromString(TasksResponse.serializer(), readAttempt.bodyAsText())
        assertTrue(aTasks.tasks.none { it.id == bTask.id }) // A's pull never surfaces B's task

        val patchAttempt = client.patch("/tasks/${bTask.id}") {
            header("Authorization", "Bearer ${userA.accessToken}")
            contentType(ContentType.Application.Json)
            setBody("""{"title": "hijacked", "updatedAt": ${System.currentTimeMillis()}}""")
        }
        assertEquals(HttpStatusCode.NotFound, patchAttempt.status) // cross-user -> 404, not 403

        val deleteAttempt = client.delete("/tasks/${bTask.id}") {
            header("Authorization", "Bearer ${userA.accessToken}")
        }
        assertEquals(HttpStatusCode.NotFound, deleteAttempt.status)
    }

    @Test
    fun `request without a token is rejected with 401 unauthorized`() = testApplication {
        application { configureApp(testConfig(), InMemoryUserRepository(), InMemoryTaskRepository(), InMemoryRefreshTokenRepository(), InMemoryArticleRepository()) }
        val client = jsonClient()

        val response = client.get("/tasks?since=0")

        assertEquals(HttpStatusCode.Unauthorized, response.status)
        assertTrue(response.bodyAsText().contains("unauthorized"))
    }

    @Test
    fun `delete tombstones the task and it is returned by a subsequent since pull`() = testApplication {
        application { configureApp(testConfig(), InMemoryUserRepository(), InMemoryTaskRepository(), InMemoryRefreshTokenRepository(), InMemoryArticleRepository()) }
        val client = jsonClient()
        val auth = client.registerAndLogin("erin@example.com")
        val task = Json.decodeFromString(
            TaskDto.serializer(),
            client.createTask(auth.accessToken, clientRef = "to-delete").bodyAsText(),
        )

        val deleteResponse = client.delete("/tasks/${task.id}") { header("Authorization", "Bearer ${auth.accessToken}") }
        assertEquals(HttpStatusCode.OK, deleteResponse.status)
        val tombstoned = Json.decodeFromString(TaskDto.serializer(), deleteResponse.bodyAsText())
        assertTrue(tombstoned.deleted)

        // since=0 must still include the tombstone so other devices learn about the deletion.
        val list = Json.decodeFromString(
            TasksResponse.serializer(),
            client.get("/tasks?since=0") { header("Authorization", "Bearer ${auth.accessToken}") }.bodyAsText(),
        )
        val pulledTombstone = list.tasks.first { it.id == task.id }
        assertTrue(pulledTombstone.deleted)
    }

    @Test
    fun `PATCH with an older updatedAt than stored is ignored (last-write-wins)`() = testApplication {
        application { configureApp(testConfig(), InMemoryUserRepository(), InMemoryTaskRepository(), InMemoryRefreshTokenRepository(), InMemoryArticleRepository()) }
        val client = jsonClient()
        val auth = client.registerAndLogin("frank@example.com")
        val created = Json.decodeFromString(
            TaskDto.serializer(),
            client.createTask(auth.accessToken, clientRef = "lww-ref", title = "Original title").bodyAsText(),
        )

        // A "newer" edit lands first, advancing the stored updatedAt...
        val newerEditResponse = client.patch("/tasks/${created.id}") {
            header("Authorization", "Bearer ${auth.accessToken}")
            contentType(ContentType.Application.Json)
            setBody("""{"title": "Newer title", "updatedAt": ${created.updatedAt + 10_000}}""")
        }
        val afterNewerEdit = Json.decodeFromString(TaskDto.serializer(), newerEditResponse.bodyAsText())
        assertEquals("Newer title", afterNewerEdit.title)

        // ...then a stale, older-timestamped edit arrives (e.g. a retried/delayed request) and
        // must be discarded, keeping the newer title.
        val staleEditResponse = client.patch("/tasks/${created.id}") {
            header("Authorization", "Bearer ${auth.accessToken}")
            contentType(ContentType.Application.Json)
            setBody("""{"title": "Stale title", "updatedAt": ${created.updatedAt}}""")
        }
        assertEquals(HttpStatusCode.OK, staleEditResponse.status)
        val afterStaleEdit = Json.decodeFromString(TaskDto.serializer(), staleEditResponse.bodyAsText())
        assertEquals("Newer title", afterStaleEdit.title) // stale write discarded, not "Stale title"
    }

    @Test
    fun `PATCH can explicitly clear the deadline while leaving duration untouched`() = testApplication {
        application { configureApp(testConfig(), InMemoryUserRepository(), InMemoryTaskRepository(), InMemoryRefreshTokenRepository(), InMemoryArticleRepository()) }
        val client = jsonClient()
        val auth = client.registerAndLogin("grace@example.com")
        val created = Json.decodeFromString(
            TaskDto.serializer(),
            client.createTask(
                auth.accessToken,
                clientRef = "patch-ref",
                estimatedDurationMillis = 1_800_000L,
                deadline = 9_999_999_999L,
            ).bodyAsText(),
        )
        assertEquals(9_999_999_999L, created.deadline)

        // Omit "deadline" entirely: since it's the only field carrying the "keep at least one of
        // duration/deadline" invariant, clearing it while duration is still present is valid.
        val response = client.patch("/tasks/${created.id}") {
            header("Authorization", "Bearer ${auth.accessToken}")
            contentType(ContentType.Application.Json)
            setBody("""{"deadline": null, "updatedAt": ${created.updatedAt + 1000}}""")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        val updated = Json.decodeFromString(TaskDto.serializer(), response.bodyAsText())
        assertEquals(null, updated.deadline)
        assertEquals(1_800_000L, updated.estimatedDurationMillis) // untouched
    }

    @Test
    fun `completedAt round-trips through create and a since pull`() = testApplication {
        application { configureApp(testConfig(), InMemoryUserRepository(), InMemoryTaskRepository(), InMemoryRefreshTokenRepository(), InMemoryArticleRepository()) }
        val client = jsonClient()
        val auth = client.registerAndLogin("hank@example.com")
        val completedAt = System.currentTimeMillis()

        val created = Json.decodeFromString(
            TaskDto.serializer(),
            client.createTask(auth.accessToken, clientRef = "completed-ref", completedAt = completedAt).bodyAsText(),
        )
        assertEquals(completedAt, created.completedAt)

        // completedAt travels with the task on a pull, like any other field (contract.md §5).
        val list = Json.decodeFromString(
            TasksResponse.serializer(),
            client.get("/tasks?since=0") { header("Authorization", "Bearer ${auth.accessToken}") }.bodyAsText(),
        )
        assertEquals(completedAt, list.tasks.first { it.id == created.id }.completedAt)
    }

    @Test
    fun `PATCH sets completedAt to mark a task done, and an omitted completedAt leaves it unchanged`() = testApplication {
        application { configureApp(testConfig(), InMemoryUserRepository(), InMemoryTaskRepository(), InMemoryRefreshTokenRepository(), InMemoryArticleRepository()) }
        val client = jsonClient()
        val auth = client.registerAndLogin("iris@example.com")
        val created = Json.decodeFromString(
            TaskDto.serializer(),
            client.createTask(auth.accessToken, clientRef = "mark-done-ref").bodyAsText(),
        )
        assertEquals(null, created.completedAt)

        val markDoneAt = created.updatedAt + 1000
        val markDoneResponse = client.patch("/tasks/${created.id}") {
            header("Authorization", "Bearer ${auth.accessToken}")
            contentType(ContentType.Application.Json)
            setBody("""{"completedAt": $markDoneAt, "updatedAt": $markDoneAt}""")
        }
        val markedDone = Json.decodeFromString(TaskDto.serializer(), markDoneResponse.bodyAsText())
        assertEquals(markDoneAt, markedDone.completedAt)

        // A PATCH that omits "completedAt" entirely must leave it unchanged (not clear it) — the
        // same absent-vs-null distinction the route already gives deadline/estimatedDurationMillis.
        val untouchedResponse = client.patch("/tasks/${created.id}") {
            header("Authorization", "Bearer ${auth.accessToken}")
            contentType(ContentType.Application.Json)
            setBody("""{"title": "Still done", "updatedAt": ${markDoneAt + 1000}}""")
        }
        val untouched = Json.decodeFromString(TaskDto.serializer(), untouchedResponse.bodyAsText())
        assertEquals(markDoneAt, untouched.completedAt) // unchanged by the omitted-field PATCH
    }

    @Test
    fun `priority round-trips through create and pull, and an unknown value is coerced to NONE`() = testApplication {
        application { configureApp(testConfig(), InMemoryUserRepository(), InMemoryTaskRepository(), InMemoryRefreshTokenRepository(), InMemoryArticleRepository()) }
        val client = jsonClient()
        val auth = client.registerAndLogin("prio@example.com")

        // A valid priority is stored and echoed back verbatim.
        val high = Json.decodeFromString(
            TaskDto.serializer(),
            client.createTask(auth.accessToken, clientRef = "high-ref", priority = "HIGH").bodyAsText(),
        )
        assertEquals("HIGH", high.priority)

        // An unrecognised value is sanitised to NONE server-side (contract.md §4/§5) — the client is
        // untrusted, so the server never lets a junk priority reach the column or the wire.
        val junk = Json.decodeFromString(
            TaskDto.serializer(),
            client.createTask(auth.accessToken, clientRef = "junk-ref", priority = "CRITICAL").bodyAsText(),
        )
        assertEquals("NONE", junk.priority)

        // Both come back on a pull with their stored priority.
        val list = Json.decodeFromString(
            TasksResponse.serializer(),
            client.get("/tasks?since=0") { header("Authorization", "Bearer ${auth.accessToken}") }.bodyAsText(),
        )
        assertEquals("HIGH", list.tasks.first { it.clientRef == "high-ref" }.priority)
        assertEquals("NONE", list.tasks.first { it.clientRef == "junk-ref" }.priority)
    }

    @Test
    fun `PATCH updates priority, and an omitted priority leaves it unchanged`() = testApplication {
        application { configureApp(testConfig(), InMemoryUserRepository(), InMemoryTaskRepository(), InMemoryRefreshTokenRepository(), InMemoryArticleRepository()) }
        val client = jsonClient()
        val auth = client.registerAndLogin("prio2@example.com")
        val created = Json.decodeFromString(
            TaskDto.serializer(),
            client.createTask(auth.accessToken, clientRef = "prio-patch-ref", priority = "LOW").bodyAsText(),
        )
        assertEquals("LOW", created.priority)

        val bumpedAt = created.updatedAt + 1000
        val bumped = Json.decodeFromString(
            TaskDto.serializer(),
            client.patch("/tasks/${created.id}") {
                header("Authorization", "Bearer ${auth.accessToken}")
                contentType(ContentType.Application.Json)
                setBody("""{"priority": "HIGH", "updatedAt": $bumpedAt}""")
            }.bodyAsText(),
        )
        assertEquals("HIGH", bumped.priority)

        // A PATCH that omits "priority" must leave the stored value untouched (absent != clear).
        val untouched = Json.decodeFromString(
            TaskDto.serializer(),
            client.patch("/tasks/${created.id}") {
                header("Authorization", "Bearer ${auth.accessToken}")
                contentType(ContentType.Application.Json)
                setBody("""{"title": "Renamed", "updatedAt": ${bumpedAt + 1000}}""")
            }.bodyAsText(),
        )
        assertEquals("HIGH", untouched.priority)
    }

    @Test
    fun `PATCH with present-null completedAt clears it (un-completes the task)`() = testApplication {
        application { configureApp(testConfig(), InMemoryUserRepository(), InMemoryTaskRepository(), InMemoryRefreshTokenRepository(), InMemoryArticleRepository()) }
        val client = jsonClient()
        val auth = client.registerAndLogin("jules@example.com")
        val completedAt = System.currentTimeMillis()
        val created = Json.decodeFromString(
            TaskDto.serializer(),
            client.createTask(auth.accessToken, clientRef = "uncomplete-ref", completedAt = completedAt).bodyAsText(),
        )
        assertEquals(completedAt, created.completedAt)

        val response = client.patch("/tasks/${created.id}") {
            header("Authorization", "Bearer ${auth.accessToken}")
            contentType(ContentType.Application.Json)
            setBody("""{"completedAt": null, "updatedAt": ${created.updatedAt + 1000}}""")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        val updated = Json.decodeFromString(TaskDto.serializer(), response.bodyAsText())
        assertEquals(null, updated.completedAt) // explicit null clears (un-completes)
    }
}
