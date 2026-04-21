package com.joechrist.medtrack

import com.joechrist.medtrack.plugins.*
import io.ktor.server.application.*
import io.ktor.server.netty.*

// =============================================================================
// MedTrack – Ktor Application Entry Point
// =============================================================================

fun main(args: Array<String>): Unit = EngineMain.main(args)

@Suppress("unused")          // referenced in application.conf
fun Application.module() {
    configureDI()            // Koin dependency injection
    configureDatabase()      // HikariCP + Exposed
    configureSerialization() // kotlinx.json
    configureCORS()
    configureStatusPages()
    configureSecurity()      // Firebase JWT  ← the focus of Security.kt
    configureWebSockets()
    configureRouting()       // all route trees
}
