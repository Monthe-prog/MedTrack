package com.joechrist.medtrack.plugins

import com.joechrist.medtrack.data.repository.PrescriptionRepository
import com.joechrist.medtrack.data.repository.UserRepository
import com.joechrist.medtrack.data.repository.MedicationRepository
import com.joechrist.medtrack.data.repository.IntakeLogRepository
import com.joechrist.medtrack.data.repository.AuditRepository
import com.joechrist.medtrack.service.PrescriptionService
import com.joechrist.medtrack.service.StorageService
import com.joechrist.medtrack.service.PdfService
import com.joechrist.medtrack.service.MedicationSearchService
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import kotlinx.serialization.json.Json
import org.koin.dsl.module
import org.koin.ktor.plugin.Koin
import org.koin.logger.slf4jLogger

// =============================================================================
// MedTrack – Koin Dependency Injection
// =============================================================================

val appModule = module {

    // ── HTTP Client (shared) ──────────────────────────────────────────────────
    single {
        HttpClient(CIO) {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true; isLenient = true })
            }
        }
    }

    // ── Repositories ──────────────────────────────────────────────────────────
    single { UserRepository() }
    single { PrescriptionRepository() }
    single { MedicationRepository() }
    single { IntakeLogRepository() }
    single { AuditRepository() }

    // ── Services ──────────────────────────────────────────────────────────────
    single { StorageService() }
    single { PdfService(get()) }                        // needs HttpClient
    single { MedicationSearchService(get()) }           // needs HttpClient
    single { PrescriptionService(get(), get(), get(), get(), get()) }
}

fun Application.configureDI() {
    install(Koin) {
        slf4jLogger()
        modules(appModule)
    }
}
