package com.joechrist.medtrack.service

import io.minio.*
import io.minio.http.Method
import org.slf4j.LoggerFactory
import java.io.ByteArrayInputStream
import java.util.concurrent.TimeUnit

// =============================================================================
// MedTrack – StorageService
// Wraps MinIO SDK for profile picture + prescription PDF storage.
// All object keys follow a structured path convention so buckets stay tidy.
// =============================================================================

class StorageService {

    private val log = LoggerFactory.getLogger(StorageService::class.java)

    private val profilesBucket       = System.getenv("MINIO_BUCKET_PROFILES")      ?: "medtrack-profiles"
    private val prescriptionsBucket  = System.getenv("MINIO_BUCKET_PRESCRIPTIONS") ?: "medtrack-prescriptions"

    private val client: MinioClient = MinioClient.builder()
        .endpoint(System.getenv("MINIO_ENDPOINT") ?: "http://minio:9000")
        .credentials(
            System.getenv("MINIO_ACCESS_KEY") ?: error("MINIO_ACCESS_KEY not set"),
            System.getenv("MINIO_SECRET_KEY") ?: error("MINIO_SECRET_KEY not set")
        )
        .build()

    // ── Profile Pictures ─────────────────────────────────────────────────────

    /**
     * Uploads a user's profile picture.
     * @param userId   Firebase UID used as key prefix
     * @param bytes    Raw image bytes (JPEG or PNG)
     * @param mimeType "image/jpeg" | "image/png"
     * @return         The MinIO object key (store in users.avatar_object_key)
     */
    fun uploadAvatar(userId: String, bytes: ByteArray, mimeType: String): String {
        val key = "avatars/$userId/${System.currentTimeMillis()}.${mimeType.substringAfter("/")}"
        client.putObject(
            PutObjectArgs.builder()
                .bucket(profilesBucket)
                .`object`(key)
                .stream(ByteArrayInputStream(bytes), bytes.size.toLong(), -1)
                .contentType(mimeType)
                .build()
        )
        log.info("Avatar uploaded: $key")
        return key
    }

    /**
     * Generates a pre-signed URL for direct browser access to a profile picture.
     * URL expires after 1 hour.
     */
    fun getAvatarUrl(objectKey: String): String =
        client.getPresignedObjectUrl(
            GetPresignedObjectUrlArgs.builder()
                .bucket(profilesBucket)
                .`object`(objectKey)
                .method(Method.GET)
                .expiry(1, TimeUnit.HOURS)
                .build()
        )

    // ── Prescription PDFs ────────────────────────────────────────────────────

    /**
     * Stores a generated prescription PDF.
     * @param prescriptionId  UUID of the prescription
     * @param pdfBytes        Raw PDF bytes from Gotenberg
     * @return                The MinIO object key (store in prescriptions.pdf_object_key)
     */
    fun uploadPrescriptionPdf(prescriptionId: String, pdfBytes: ByteArray): String {
        val key = "prescriptions/$prescriptionId/prescription.pdf"
        client.putObject(
            PutObjectArgs.builder()
                .bucket(prescriptionsBucket)
                .`object`(key)
                .stream(ByteArrayInputStream(pdfBytes), pdfBytes.size.toLong(), -1)
                .contentType("application/pdf")
                .build()
        )
        log.info("Prescription PDF uploaded: $key")
        return key
    }

    /**
     * Generates a short-lived (15-minute) signed URL for downloading a prescription PDF.
     * The URL is returned to the doctor/patient — never the raw key.
     */
    fun getPrescriptionPdfUrl(objectKey: String): String =
        client.getPresignedObjectUrl(
            GetPresignedObjectUrlArgs.builder()
                .bucket(prescriptionsBucket)
                .`object`(objectKey)
                .method(Method.GET)
                .expiry(15, TimeUnit.MINUTES)
                .build()
        )

    /**
     * Deletes an object (e.g., replacing an old avatar or revoking a PDF).
     */
    fun delete(bucket: String, objectKey: String) {
        client.removeObject(
            RemoveObjectArgs.builder().bucket(bucket).`object`(objectKey).build()
        )
        log.info("Object deleted: $bucket/$objectKey")
    }

    fun profilesBucket() = profilesBucket
    fun prescriptionsBucket() = prescriptionsBucket
}
