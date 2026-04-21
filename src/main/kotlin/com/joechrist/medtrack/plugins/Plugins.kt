package com.joechrist.medtrack.plugins

import com.joechrist.medtrack.routes.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.plugins.requestvalidation.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import kotlinx.serialization.json.Json
import java.time.Duration

// =============================================================================
// MedTrack – Plugin Configuration Bundle
// =============================================================================

fun Application.configureSerialization() {
    install(ContentNegotiation) {
        json(Json {
            prettyPrint = false
            isLenient = true
            ignoreUnknownKeys = true
            encodeDefaults = true
        })
    }
}

fun Application.configureCORS() {
    install(CORS) {
        allowMethod(HttpMethod.Options)
        allowMethod(HttpMethod.Get)
        allowMethod(HttpMethod.Post)
        allowMethod(HttpMethod.Put)
        allowMethod(HttpMethod.Patch)
        allowMethod(HttpMethod.Delete)
        allowHeader(HttpHeaders.Authorization)
        allowHeader(HttpHeaders.ContentType)
        // Lock down to your actual domain in production
        val allowedOrigin = System.getenv("ALLOWED_ORIGIN") ?: "http://localhost:3000"
        allowHost(allowedOrigin.removePrefix("http://").removePrefix("https://"))
        allowCredentials = true
        maxAgeInSeconds = 3600
    }
}

fun Application.configureStatusPages() {
    install(StatusPages) {
        exception<IllegalArgumentException> { call, cause ->
            call.respond(HttpStatusCode.BadRequest,
                ApiError("BAD_REQUEST", cause.message ?: "Invalid request"))
        }
        exception<ForbiddenException> { call, cause ->
            call.respond(HttpStatusCode.Forbidden,
                ApiError("FORBIDDEN", cause.message ?: "Access denied"))
        }
        exception<NotFoundException> { call, cause ->
            call.respond(HttpStatusCode.NotFound,
                ApiError("NOT_FOUND", cause.message ?: "Resource not found"))
        }
        exception<ConflictException> { call, cause ->
            call.respond(HttpStatusCode.Conflict,
                ApiError("CONFLICT", cause.message ?: "Resource conflict"))
        }
        exception<UserNotRegisteredException> { call, cause ->
            call.respond(HttpStatusCode.Forbidden,
                ApiError("USER_NOT_REGISTERED", cause.message ?: "User not registered in database"))
        }
        exception<Throwable> { call, cause ->
            call.application.log.error("Unhandled exception", cause)
            call.respond(HttpStatusCode.InternalServerError,
                ApiError("INTERNAL_ERROR", "An unexpected error occurred"))
        }
        status(HttpStatusCode.Unauthorized) { call, _ ->
            call.respond(HttpStatusCode.Unauthorized,
                ApiError("UNAUTHORIZED", "Valid Firebase ID token required"))
        }
        status(HttpStatusCode.Forbidden) { call, _ ->
            call.respond(HttpStatusCode.Forbidden,
                ApiError("FORBIDDEN", "Insufficient role for this resource"))
        }
    }
}

fun Application.configureWebSockets() {
    install(WebSockets) {
        pingPeriod = Duration.ofSeconds(15)
        timeout = Duration.ofSeconds(30)
        maxFrameSize = Long.MAX_VALUE
        masking = false
    }
}

fun Application.configureRouting() {
    routing {
        // Public health endpoint (used by Docker HEALTHCHECK)
        get("/health") {
            call.respond(mapOf("status" to "UP", "service" to "MedTrack API"))
        }

        // Versioned API
        route("/api/v1") {
            authRoutes()
            userRoutes()
            prescriptionRoutes()
            medicationRoutes()
            intakeLogRoutes()
            chatRoutes()
        }
    }
}

// ---------------------------------------------------------------------------
// Shared error response model
// ---------------------------------------------------------------------------
@kotlinx.serialization.Serializable
data class ApiError(val code: String, val message: String)

// ---------------------------------------------------------------------------
// Domain exceptions (caught by StatusPages)
// ---------------------------------------------------------------------------
class ForbiddenException(message: String) : Exception(message)
class NotFoundException(message: String) : Exception(message)
class ConflictException(message: String) : Exception(message)
