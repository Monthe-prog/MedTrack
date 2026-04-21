package com.joechrist.medtrack.plugins

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.plugins.ratelimit.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.util.pipeline.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

// =============================================================================
// MedTrack – Rate Limiting & Security Hardening
//
// Two-layer defence:
//   1. Nginx rate limits (see nginx.conf) — first line of defence
//   2. Ktor application-level limits — protects when running without Nginx
//
// Rate limit buckets:
//   auth    → 10 req/min per IP   (login brute-force protection)
//   api     → 120 req/min per IP  (general API abuse)
//   search  → 30 req/min per IP   (openFDA cost control)
// =============================================================================

// ── Rate limit bucket names ───────────────────────────────────────────────────
object RateLimit {
    const val AUTH   = "auth"
    const val API    = "api"
    const val SEARCH = "search"
}

fun Application.configureRateLimiting() {
    install(RateLimit) {
        // Auth endpoints: 10 requests/minute per IP
        register(RateLimitName(RateLimit.AUTH)) {
            rateLimiter(limit = 10, refillPeriod = 1.minutes)
            requestKey { call ->
                call.request.origin.remoteAddress
            }
        }
        // General API: 120 requests/minute per IP
        register(RateLimitName(RateLimit.API)) {
            rateLimiter(limit = 120, refillPeriod = 1.minutes)
            requestKey { call ->
                // Use Firebase UID if available, else IP (avoids shared-IP collateral)
                call.principal<FirebasePrincipal>()?.firebaseUid
                    ?: call.request.origin.remoteAddress
            }
        }
        // Medication search: 30 requests/minute per IP
        register(RateLimitName(RateLimit.SEARCH)) {
            rateLimiter(limit = 30, refillPeriod = 1.minutes)
            requestKey { call ->
                call.principal<FirebasePrincipal>()?.firebaseUid
                    ?: call.request.origin.remoteAddress
            }
        }
    }
}

// ── Security response headers ─────────────────────────────────────────────────
// Applied globally via an intercept on the ApplicationCallPipeline.

fun Application.configureSecurityHeaders() {
    intercept(ApplicationCallPipeline.Plugins) {
        proceed()
        call.response.headers.apply {
            // Prevent MIME-type sniffing
            append(HttpHeaders.XContentTypeOptions, "nosniff")
            // No clickjacking
            append("X-Frame-Options", "DENY")
            // XSS filter (legacy browsers)
            append("X-XSS-Protection", "1; mode=block")
            // No referrer info leaked
            append("Referrer-Policy", "strict-origin-when-cross-origin")
            // HSTS (only effective when TLS is terminated here — Nginx handles in prod)
            if (System.getenv("APP_ENV") == "production") {
                append(HttpHeaders.StrictTransportSecurity,
                    "max-age=63072000; includeSubDomains; preload")
            }
            // Remove server fingerprint
            remove(HttpHeaders.Server)
        }
    }
}

// ── Request ID middleware ──────────────────────────────────────────────────────
// Attaches a unique X-Request-ID to every response for tracing.

fun Application.configureRequestId() {
    intercept(ApplicationCallPipeline.Plugins) {
        val requestId = call.request.headers["X-Request-ID"]
            ?: java.util.UUID.randomUUID().toString().take(8)
        call.response.header("X-Request-ID", requestId)
        proceed()
    }
}
