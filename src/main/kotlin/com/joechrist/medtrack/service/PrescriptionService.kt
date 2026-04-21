package com.joechrist.medtrack.service

import com.joechrist.medtrack.data.repository.*
import com.joechrist.medtrack.domain.model.UserRole
import com.joechrist.medtrack.plugins.NotFoundException
import org.slf4j.LoggerFactory
import java.time.LocalDate
import java.util.UUID

// =============================================================================
// MedTrack – PrescriptionService
// Orchestrates the full prescription lifecycle:
//   Create → Generate PDF (Gotenberg) → Store PDF (MinIO) → Audit log
// =============================================================================

class PrescriptionService(
    private val prescriptionRepo: PrescriptionRepository,
    private val userRepo: UserRepository,
    private val pdfService: PdfService,
    private val storageService: StorageService,
    private val auditRepo: AuditRepository
) {
    private val log = LoggerFactory.getLogger(PrescriptionService::class.java)

    // ── Create a new prescription + generate PDF ──────────────────────────────

    /**
     * Full prescription creation flow:
     * 1. Validate doctor-patient link.
     * 2. Persist the prescription row.
     * 3. Fetch PHI needed for the PDF template.
     * 4. Render HTML → PDF via Gotenberg.
     * 5. Upload PDF to MinIO.
     * 6. Update prescription with PDF object key.
     * 7. Write to anonymised audit log (NO PHI).
     *
     * @return The newly created [PrescriptionDto]
     */
    suspend fun createPrescription(
        doctorFirebaseUid: String,
        doctorAnonAlias: String,
        request: CreatePrescriptionRequest
    ): PrescriptionDto {

        // 1. Resolve doctor
        val doctor = userRepo.findByFirebaseUid(doctorFirebaseUid)
            ?: throw NotFoundException("Doctor not found")

        // 2. Resolve patient + verify link
        val patient = userRepo.findById(request.patientId)
            ?: throw NotFoundException("Patient not found")

        val linked = userRepo.isDoctorOfPatient(doctorFirebaseUid, request.patientId.toString())
        if (!linked && doctor.role != UserRole.ADMIN) {
            throw com.joechrist.medtrack.plugins.ForbiddenException(
                "Doctor ${doctor.id} is not linked to patient ${request.patientId}"
            )
        }

        // 3. Persist prescription
        val prescriptionId = prescriptionRepo.create(
            doctorId        = doctor.id,
            patientId       = request.patientId,
            medicationId    = request.medicationId,
            dosageAmount    = request.dosageAmount,
            dosageUnit      = request.dosageUnit,
            frequencyPerDay = request.frequencyPerDay,
            durationDays    = request.durationDays,
            instructions    = request.instructions,
            scheduleTimes   = request.scheduleTimes,
            startDate       = request.startDate,
            endDate         = request.endDate
        )

        log.info("Prescription created: $prescriptionId for patient ${patient.anonAlias}")

        // 4. Generate PDF (runs in background; doesn't block the API response)
        try {
            val pdfData = PrescriptionPdfData(
                prescriptionId      = prescriptionId.toString(),
                issueDate           = LocalDate.now().toString(),
                doctorFullName      = doctor.fullName,
                doctorLicenseNo     = null,  // enriched if needed from DB
                doctorSpecialty     = null,
                patientFullName     = patient.fullName,      // PHI – only server-side
                patientDob          = null,
                patientAnonAlias    = patient.anonAlias,
                medicationGenericName = request.medicationGenericName,
                medicationBrandName   = request.medicationBrandName,
                dosageAmount        = request.dosageAmount,
                dosageUnit          = request.dosageUnit,
                frequencyPerDay     = request.frequencyPerDay,
                durationDays        = request.durationDays,
                scheduleTimes       = request.scheduleTimes,
                instructions        = request.instructions,
                status              = "ACTIVE"
            )
            val pdfBytes  = pdfService.generatePrescriptionPdf(pdfData)
            val objectKey = storageService.uploadPrescriptionPdf(prescriptionId.toString(), pdfBytes)
            prescriptionRepo.setPdfObjectKey(prescriptionId, objectKey)
            log.info("PDF stored at $objectKey")
        } catch (e: Exception) {
            // PDF failure is non-fatal: prescription is already persisted.
            log.error("PDF generation failed for $prescriptionId (non-fatal): ${e.message}")
        }

        // 5. Audit (no PHI — aliases only)
        auditRepo.log(
            eventType    = "PRESCRIPTION_CREATED",
            actorRole    = UserRole.DOCTOR,
            actorAlias   = doctorAnonAlias,
            subjectAlias = patient.anonAlias,
            resourceType = "Prescription",
            resourceId   = prescriptionId,
            meta         = mapOf(
                "drug" to request.medicationGenericName,
                "dose" to "${request.dosageAmount} ${request.dosageUnit}",
                "freq" to "${request.frequencyPerDay}x/day"
            )
        )

        // 6. Return DTO — doctor caller sees full name
        return prescriptionRepo.findById(prescriptionId, UserRole.DOCTOR)
            ?: throw NotFoundException("Created prescription not found")
    }

    // ── Get prescription PDF download URL ─────────────────────────────────────

    suspend fun getPrescriptionPdfUrl(prescriptionId: UUID, callerRole: UserRole): String {
        val rx = prescriptionRepo.findById(prescriptionId, callerRole)
            ?: throw NotFoundException("Prescription not found")

        val objectKey = rx.pdfObjectKey
            ?: throw NotFoundException("PDF not yet generated for this prescription")

        return storageService.getPrescriptionPdfUrl(objectKey)
    }

    // ── Update status ─────────────────────────────────────────────────────────

    suspend fun updateStatus(
        prescriptionId: UUID,
        newStatus: String,
        actorAlias: String,
        actorRole: UserRole
    ) {
        val validStatuses = setOf("ACTIVE", "PAUSED", "COMPLETED", "CANCELLED")
        require(newStatus in validStatuses) { "Invalid status: $newStatus" }

        prescriptionRepo.updateStatus(prescriptionId, newStatus)

        auditRepo.log(
            eventType    = "PRESCRIPTION_STATUS_CHANGED",
            actorRole    = actorRole,
            actorAlias   = actorAlias,
            resourceType = "Prescription",
            resourceId   = prescriptionId,
            meta         = mapOf("newStatus" to newStatus)
        )
    }
}

// ── Request model ─────────────────────────────────────────────────────────────

@kotlinx.serialization.Serializable
data class CreatePrescriptionRequest(
    val patientId: UUID,
    val medicationId: UUID,
    val medicationGenericName: String,    // denormalised for PDF (avoids extra query)
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
