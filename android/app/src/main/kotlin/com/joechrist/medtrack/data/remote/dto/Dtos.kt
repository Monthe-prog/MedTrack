package com.joechrist.medtrack.data.remote.dto

import kotlinx.serialization.Serializable

// =============================================================================
// MedTrack – Network Data Transfer Objects
// Serialized with kotlinx.serialization; mirrors Ktor response shapes.
// =============================================================================

// ── Requests ──────────────────────────────────────────────────────────────────

@Serializable
data class RegisterRequest(
    val fullName: String,
    val role: String,
    val phone: String?          = null,
    val dateOfBirth: String?    = null,
    val medicalLicenseNo: String? = null,
    val specialty: String?      = null
)

@Serializable
data class CreatePrescriptionRequest(
    val patientId: String,
    val medicationId: String,
    val medicationGenericName: String,
    val medicationBrandName: String?,
    val dosageAmount: Double,
    val dosageUnit: String,
    val frequencyPerDay: Int,
    val durationDays: Int?,
    val instructions: String?,
    val scheduleTimes: List<String>,
    val startDate: String,
    val endDate: String?
)

@Serializable
data class UpdateStatusRequest(val status: String)

@Serializable
data class MarkTakenRequest(val notes: String? = null)

// ── Responses ─────────────────────────────────────────────────────────────────

@Serializable
data class MessageResponse(val message: String)

@Serializable
data class UserProfileResponse(
    val id: String,
    val email: String,
    val fullName: String,
    val anonAlias: String,
    val role: String,
    val isActive: Boolean,
    val maskedName: String?  = null,
    val avatarUrl: String?   = null
)

@Serializable
data class AvatarResponse(
    val avatarUrl: String,
    val objectKey: String
)

@Serializable
data class PatientSummaryResponse(
    val id: String,
    val anonAlias: String,
    val maskedName: String,
    val role: String
)

@Serializable
data class PrescriptionResponse(
    val id: String,
    val doctorId: String,
    val patientId: String,
    val patientDisplayName: String,
    val patientAnonAlias: String,
    val medicationId: String,
    val genericName: String,
    val brandName: String?,
    val dosageAmount: Double,
    val dosageUnit: String,
    val frequencyPerDay: Int,
    val durationDays: Int?,
    val instructions: String?,
    val scheduleTimes: List<String>,
    val status: String,
    val startDate: String,
    val endDate: String?,
    val pdfObjectKey: String?,
    val createdAt: String
)

@Serializable
data class PdfUrlResponse(
    val pdfUrl: String,
    val expiresInMinutes: Int
)

@Serializable
data class MedicationResponse(
    val id: String,
    val genericName: String,
    val brandName: String?,
    val dosageForm: String?,
    val strength: String?,
    val route: String?,
    val indicationsAndUsage: String? = null,
    val source: String               = "local"
)

@Serializable
data class IntakeLogResponse(
    val id: String,
    val prescriptionId: String,
    val scheduledTime: String,
    val takenAt: String?,
    val status: String,          // PENDING | TAKEN | MISSED | SKIPPED
    val notes: String?
)
