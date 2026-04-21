package com.joechrist.medtrack.data.remote

import com.joechrist.medtrack.data.remote.dto.*
import retrofit2.http.*

// =============================================================================
// MedTrack – Retrofit API Service Interface
// Mirrors the Ktor route definitions from backend/routes/Routes.kt
// =============================================================================

interface MedTrackApiService {

    // ── Auth ──────────────────────────────────────────────────────────────────

    @POST("auth/register")
    suspend fun register(
        @Body body: RegisterRequest
    ): MessageResponse

    @GET("auth/me")
    suspend fun getMe(): UserProfileResponse

    // ── Users ─────────────────────────────────────────────────────────────────

    @GET("users/{id}")
    suspend fun getUserById(@Path("id") id: String): UserProfileResponse

    @Multipart
    @POST("users/{id}/avatar")
    suspend fun uploadAvatar(
        @Path("id") userId: String,
        @Part image: okhttp3.MultipartBody.Part
    ): AvatarResponse

    @GET("users/patients")
    suspend fun getMyPatients(): List<PatientSummaryResponse>

    // ── Prescriptions ─────────────────────────────────────────────────────────

    @POST("prescriptions")
    suspend fun createPrescription(@Body body: CreatePrescriptionRequest): PrescriptionResponse

    @GET("prescriptions/{id}")
    suspend fun getPrescriptionById(@Path("id") id: String): PrescriptionResponse

    @GET("prescriptions/{id}/pdf")
    suspend fun getPrescriptionPdfUrl(@Path("id") id: String): PdfUrlResponse

    @GET("prescriptions/patient/{patientId}")
    suspend fun getPrescriptionsForPatient(
        @Path("patientId") patientId: String,
        @Query("status") status: String? = null
    ): List<PrescriptionResponse>

    @GET("prescriptions/my-patients")
    suspend fun getDoctorPrescriptions(): List<PrescriptionResponse>

    @PATCH("prescriptions/{id}/status")
    suspend fun updatePrescriptionStatus(
        @Path("id") id: String,
        @Body body: UpdateStatusRequest
    ): MessageResponse

    // ── Medications ───────────────────────────────────────────────────────────

    @GET("medications/search")
    suspend fun searchMedications(@Query("q") query: String): List<MedicationResponse>

    // ── Intake Logs ───────────────────────────────────────────────────────────

    @GET("intake-logs/patient/{patientId}")
    suspend fun getIntakeLogs(@Path("patientId") patientId: String): List<IntakeLogResponse>

    @PATCH("intake-logs/{id}/taken")
    suspend fun markDoseTaken(
        @Path("id") logId: String,
        @Body body: MarkTakenRequest = MarkTakenRequest()
    ): MessageResponse

    @PATCH("intake-logs/{id}/missed")
    suspend fun markDoseMissed(@Path("id") logId: String): MessageResponse
}
