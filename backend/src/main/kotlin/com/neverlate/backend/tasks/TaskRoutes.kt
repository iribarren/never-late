package com.neverlate.backend.tasks

import com.neverlate.backend.auth.Jwt
import com.neverlate.backend.common.PatchValue
import com.neverlate.backend.common.ValidationException
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/** All `/tasks*` routes require a valid Bearer JWT (contract.md §1.2). "auth-jwt" is the
 *  provider name registered in plugins/Security.kt. */
fun Route.taskRoutes(taskService: TaskService) {
    authenticate("auth-jwt") {
        route("/tasks") {
            get {
                val userId = currentUserId()
                val since = call.request.queryParameters["since"]?.toLongOrNull() ?: 0L
                call.respond(HttpStatusCode.OK, taskService.listChangedSince(userId, since))
            }

            post {
                val userId = currentUserId()
                val request = call.receive<CreateTaskRequest>()
                val result = taskService.create(
                    userId = userId,
                    clientRef = request.clientRef,
                    title = request.title,
                    estimatedDurationMillis = request.estimatedDurationMillis,
                    deadline = request.deadline,
                )
                // 201 for a genuine create, 200 when this clientRef was already seen (idempotent
                // replay) — contract.md §3.
                val status = if (result.created) HttpStatusCode.Created else HttpStatusCode.OK
                call.respond(status, result.task)
            }

            patch("/{id}") {
                val userId = currentUserId()
                val id = pathTaskId()
                // Read as a raw JSON object (not the typed UpdateTaskRequest) so we can tell
                // "key omitted" from "key present with value null" — see PatchValue's doc.
                val body = call.receive<JsonObject>()
                val updatedAt = (body["updatedAt"] as? JsonPrimitive)?.longOrNull
                    ?: throw ValidationException("updatedAt is required")
                val title = (body["title"] as? JsonPrimitive)?.contentOrNullSafe()
                val updated = taskService.update(
                    userId = userId,
                    id = id,
                    title = title,
                    estimatedDurationMillis = body.patchLong("estimatedDurationMillis"),
                    deadline = body.patchLong("deadline"),
                    clientUpdatedAt = updatedAt,
                )
                call.respond(HttpStatusCode.OK, updated)
            }

            delete("/{id}") {
                val userId = currentUserId()
                val id = pathTaskId()
                call.respond(HttpStatusCode.OK, taskService.delete(userId, id))
            }
        }
    }
}

/** Extracts the authenticated user's id from the verified JWT's claim (see auth/Jwt.kt for the
 *  claim name both sides agree on). By the time a route body runs, Ktor's Authentication plugin
 *  has already rejected any request without a valid, unexpired token. */
private fun RoutingContext.currentUserId(): Long =
    call.principal<JWTPrincipal>()!!.payload.getClaim(Jwt.USER_ID_CLAIM).asLong()

private fun RoutingContext.pathTaskId(): Long =
    call.parameters["id"]?.toLongOrNull() ?: throw ValidationException("Invalid task id")

/** Present-and-non-null -> that string; present-and-null -> null (meaning "leave unchanged" for
 *  title, which can never legitimately be null — see [UpdateTaskRequest] doc). */
private fun JsonPrimitive.contentOrNullSafe(): String? =
    if (this is JsonNull) null else content

/** Builds a [PatchValue] for an optional `Long?` field by checking whether [key] was present in
 *  the raw JSON body at all, per [PatchValue]'s absent-vs-null distinction. */
private fun JsonObject.patchLong(key: String): PatchValue<Long> =
    if (!containsKey(key)) {
        PatchValue.Absent
    } else {
        PatchValue.Present((this[key] as? JsonPrimitive)?.longOrNull)
    }
