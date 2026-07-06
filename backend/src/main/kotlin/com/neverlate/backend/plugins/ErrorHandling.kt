package com.neverlate.backend.plugins

import com.neverlate.backend.common.ApiException
import com.neverlate.backend.common.ErrorBody
import com.neverlate.backend.common.ErrorEnvelope
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.JsonConvertException
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("ErrorHandling")

/**
 * The single place that turns an exception into the JSON error envelope (contract.md §1.1). Every
 * route throws [ApiException] subtypes for "expected" failures (bad input, not found, ...); this
 * handler is what actually writes the response, so that mapping isn't duplicated at every call
 * site. Anything unexpected becomes a generic 500 whose message never repeats the real exception
 * text — contract.md is explicit that internals must never leak into a response.
 */
fun Application.configureErrorHandling() {
    install(StatusPages) {
        exception<ApiException> { call, cause ->
            call.respond(
                HttpStatusCode.fromValue(cause.statusCode),
                ErrorEnvelope(ErrorBody(code = cause.code, message = cause.message ?: cause.code)),
            )
        }
        // Malformed JSON / a body that doesn't match the expected shape at all (as opposed to a
        // field failing our own domain validation, which throws ValidationException directly).
        exception<JsonConvertException> { call, _ ->
            call.respond(
                HttpStatusCode.BadRequest,
                ErrorEnvelope(ErrorBody(code = "validation_error", message = "Malformed request body")),
            )
        }
        exception<BadRequestException> { call, _ ->
            call.respond(
                HttpStatusCode.BadRequest,
                ErrorEnvelope(ErrorBody(code = "validation_error", message = "Malformed request body")),
            )
        }
        exception<Throwable> { call, cause ->
            logger.error("Unhandled exception", cause)
            call.respond(
                HttpStatusCode.InternalServerError,
                ErrorEnvelope(ErrorBody(code = "internal_error", message = "Something went wrong")),
            )
        }
    }
}
