package com.joechrist.medtrack.routes

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.selectAll
import com.joechrist.medtrack.data.table.UsersTable
import kotlinx.serialization.Serializable
import java.time.Instant

// =============================================================================
// MedTrack – Health & Readiness Routes
//
//  GET /health          → liveness probe  (used by Docker HEALTHCHECK + K8s)
//  GET /health/ready    → readiness probe (checks DB + downstream services)
//  GET /health/version  → build info
// =============================================================================

fun Route.healthRoutes() {

    // ── Liveness: always 200 if the JVM is running ────────────────────────────
    get("/health") {
        call.respond(mapOf(
            "status"    to "UP",
            "service"   to "MedTrack API",
            "timestamp" to Instant.now().toString()
        ))
    }

    // ── Readiness: checks DB connectivity ────────────────────────────────────
    get("/health/ready") {
        val checks = mutableMapOf<String, String>()
        var allHealthy = true

        // Postgres check — run a minimal SELECT
        val dbOk = runCatching {
            transaction { UsersTable.selectAll().limit(1).count() }
            true
        }.getOrElse { false }
        checks["postgres"] = if (dbOk) "UP" else "DOWN"
        if (!dbOk) allHealthy = false

        // Gotenberg check
        val gotenbergUrl = System.getenv("GOTENBERG_URL") ?: "http://gotenberg:3000"
        val gotenbergOk = runCatching {
            val client = io.ktor.client.HttpClient(io.ktor.client.engine.cio.CIO)
            val resp   = client.get("$gotenbergUrl/health")
            client.close()
            resp.status.value in 200..299
        }.getOrElse { false }
        checks["gotenberg"] = if (gotenbergOk) "UP" else "DOWN"
        if (!gotenbergOk) allHealthy = false

        val status = if (allHealthy) HttpStatusCode.OK else HttpStatusCode.ServiceUnavailable
        call.respond(status, ReadinessResponse(
            status = if (allHealthy) "READY" else "DEGRADED",
            checks = checks,
            timestamp = Instant.now().toString()
        ))
    }

    // ── Version info ──────────────────────────────────────────────────────────
    get("/health/version") {
        call.respond(mapOf(
            "version"     to (System.getenv("APP_VERSION") ?: "1.0.0"),
            "environment" to (System.getenv("APP_ENV")     ?: "production"),
            "buildTime"   to (System.getenv("BUILD_TIME")  ?: "unknown")
        ))
    }
}

@Serializable
data class ReadinessResponse(
    val status: String,
    val checks: Map<String, String>,
    val timestamp: String
)
