import com.cloudinary.Cloudinary
import com.cloudinary.utils.ObjectUtils

class StorageService {

    private val cloudinary = Cloudinary(mapOf(
        "cloud_name" to System.getenv("CLOUDINARY_CLOUD_NAME"),
        "api_key"    to System.getenv("747746954195187"),
        "api_secret" to System.getenv("yHOJqNG93y9jJ4DTFidxOYiSdFk")
    ))

    fun uploadAvatar(userId: String, bytes: ByteArray, mimeType: String): String {
        val result = cloudinary.uploader().upload(bytes, ObjectUtils.asMap(
            "public_id", "avatars/$userId",
            "folder",    "medtrack/avatars",
            "overwrite", true
        ))
        return result["secure_url"] as String
    }

    fun getAvatarUrl(objectKey: String): String = objectKey  // Cloudinary returns full URL directly

    fun uploadPrescriptionPdf(prescriptionId: String, pdfBytes: ByteArray): String {
        val result = cloudinary.uploader().upload(pdfBytes, ObjectUtils.asMap(
            "public_id",     "prescriptions/$prescriptionId",
            "folder",        "medtrack/prescriptions",
            "resource_type", "raw",
            "overwrite",     true
        ))
        return result["secure_url"] as String
    }

    fun getPrescriptionPdfUrl(objectKey: String): String = objectKey
}