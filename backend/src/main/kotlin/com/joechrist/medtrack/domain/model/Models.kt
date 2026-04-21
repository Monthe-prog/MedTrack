package com.joechrist.medtrack.domain.model

// =============================================================================
// MedTrack – Domain Models (shared across layers)
// =============================================================================

// ---------------------------------------------------------------------------
// UserRole – mirrors the `roles` table, enforced at API layer via RBAC
// ---------------------------------------------------------------------------
enum class UserRole(val id: Int) {
    ADMIN(1),
    DOCTOR(2),
    PATIENT(3);

    companion object {
        fun fromId(id: Int): UserRole = entries.first { it.id == id }
        fun fromName(name: String): UserRole = entries.first { it.name == name }
    }
}

// ---------------------------------------------------------------------------
// Anonymisation helpers
// The service layer MUST call these before writing to anonymised_audit_logs
// or returning data to non-Doctor consumers.
// ---------------------------------------------------------------------------

object Anonymiser {

    /**
     * Returns a masked version of a full name suitable for shared panels.
     * "John Doe" → "J*** D**"
     */
    fun maskName(fullName: String): String =
        fullName.trim().split(" ").joinToString(" ") { part ->
            if (part.length <= 1) part
            else part.first() + "*".repeat(part.length - 1)
        }

    /**
     * Masks an email address, preserving domain for support purposes.
     * "john.doe@gmail.com" → "j***.***@gmail.com"
     */
    fun maskEmail(email: String): String {
        val (local, domain) = email.split("@").let {
            if (it.size != 2) return "***@***"
            it[0] to it[1]
        }
        val maskedLocal = if (local.length <= 1) "*"
        else local.first() + "*".repeat(local.length - 1)
        return "$maskedLocal@$domain"
    }

    /**
     * Builds a stable anon alias from an existing one (for audit logs).
     * Alias is generated once at DB level; this just sanitises it.
     */
    fun toAnonAlias(alias: String): String = alias.uppercase().take(16)
}

// ---------------------------------------------------------------------------
// Lightweight DB user representation (returned by UserRepository)
// ---------------------------------------------------------------------------
data class DbUser(
    val id: java.util.UUID,
    val firebaseUid: String,
    val role: UserRole,
    val fullName: String,
    val email: String,
    val anonAlias: String,
    val isActive: Boolean
)
