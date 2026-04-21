package com.joechrist.medtrack.data.local.entity

import androidx.room.*
import java.util.UUID

// =============================================================================
// MedTrack – Room Entities
// Mirror the Postgres schema for offline-first caching.
// All UUIDs stored as String (Room has no native UUID type).
// Timestamps stored as Long (epoch millis) for easy comparison and sorting.
// =============================================================================

// ── Medication ────────────────────────────────────────────────────────────────

@Entity(tableName = "medications")
data class MedicationEntity(
    @PrimaryKey val id: String,
    val openFdaId: String?,
    val brandName: String?,
    val genericName: String,
    val manufacturer: String?,
    val dosageForm: String?,
    val strength: String?,
    val route: String?,
    val source: String = "local",       // "local" | "openFDA"
    val cachedAtMs: Long = System.currentTimeMillis()
)

// ── Prescription ──────────────────────────────────────────────────────────────

@Entity(
    tableName = "prescriptions",
    foreignKeys = [
        ForeignKey(
            entity        = MedicationEntity::class,
            parentColumns = ["id"],
            childColumns  = ["medicationId"],
            onDelete      = ForeignKey.SET_NULL
        )
    ],
    indices = [Index("medicationId"), Index("patientId"), Index("status")]
)
data class PrescriptionEntity(
    @PrimaryKey val id: String,
    val doctorId: String,
    val patientId: String,
    val patientDisplayName: String,     // full name (DOCTOR) or own name (PATIENT)
    val patientAnonAlias: String,
    val medicationId: String?,
    val genericName: String,
    val brandName: String?,
    val dosageAmount: Double,
    val dosageUnit: String,
    val frequencyPerDay: Int,
    val durationDays: Int?,
    val instructions: String?,
    val scheduleTimes: String,          // JSON-encoded List<String> e.g. ["08:00","20:00"]
    val status: String,                 // ACTIVE | PAUSED | COMPLETED | CANCELLED
    val startDate: String,              // ISO date "2025-01-15"
    val endDate: String?,
    val pdfObjectKey: String?,
    val createdAtMs: Long,
    val syncedAtMs: Long = System.currentTimeMillis(),
    val isDirty: Boolean = false        // true = pending sync to server
)

// ── Intake log ────────────────────────────────────────────────────────────────

@Entity(
    tableName = "intake_logs",
    foreignKeys = [
        ForeignKey(
            entity        = PrescriptionEntity::class,
            parentColumns = ["id"],
            childColumns  = ["prescriptionId"],
            onDelete      = ForeignKey.CASCADE
        )
    ],
    indices = [Index("prescriptionId"), Index("patientId"), Index("scheduledTimeMs")]
)
data class IntakeLogEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val prescriptionId: String,
    val patientId: String,
    val scheduledTimeMs: Long,          // alarm fires at exactly this epoch millis
    val takenAtMs: Long?,
    val status: String = "PENDING",     // PENDING | TAKEN | MISSED | SKIPPED
    val notes: String?,
    val alarmId: Int?,                  // AlarmManager request code — for cancellation
    val createdAtMs: Long = System.currentTimeMillis(),
    val isDirty: Boolean = false        // pending sync to server
)

// ── User profile cache ────────────────────────────────────────────────────────

@Entity(tableName = "user_profile")
data class UserProfileEntity(
    @PrimaryKey val id: String,
    val firebaseUid: String,
    val email: String,
    val fullName: String,
    val anonAlias: String,
    val role: String,
    val avatarObjectKey: String?,
    val cachedAtMs: Long = System.currentTimeMillis()
)

// ── Pending sync queue ────────────────────────────────────────────────────────
// Stores failed network writes so WorkManager can retry them.

@Entity(tableName = "sync_queue")
data class SyncQueueEntity(
    @PrimaryKey(autoGenerate = true) val localId: Long = 0,
    val entityType: String,             // "IntakeLog" | "Prescription"
    val entityId: String,
    val operation: String,              // "MARK_TAKEN" | "MARK_MISSED" | "STATUS_UPDATE"
    val payload: String,                // JSON-encoded operation payload
    val retryCount: Int = 0,
    val createdAtMs: Long = System.currentTimeMillis()
)
