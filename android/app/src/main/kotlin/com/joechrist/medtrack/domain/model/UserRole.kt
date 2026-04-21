package com.joechrist.medtrack.domain.model

/**
 * UserRole – mirrors the `roles` table, enforced at API layer via RBAC.
 * Shared conceptually between Android and Backend.
 */
enum class UserRole(val id: Int) {
    ADMIN(1),
    DOCTOR(2),
    PATIENT(3);

    companion object {
        fun fromId(id: Int): UserRole = entries.first { it.id == id }
        fun fromName(name: String): UserRole = entries.first { it.name == name }
    }
}
