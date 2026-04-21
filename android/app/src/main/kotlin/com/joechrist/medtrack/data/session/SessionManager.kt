package com.joechrist.medtrack.data.session

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.joechrist.medtrack.domain.model.UserRole
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "medtrack_session")

@Singleton
class SessionManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private object Keys {
        val FIREBASE_UID   = stringPreferencesKey("firebase_uid")
        val ROLE           = stringPreferencesKey("role")
        val DISPLAY_NAME   = stringPreferencesKey("display_name")
        val EMAIL          = stringPreferencesKey("email")
    }

    suspend fun saveSession(
        firebaseUid: String,
        role: UserRole,
        displayName: String,
        email: String
    ) {
        context.dataStore.edit { prefs ->
            prefs[Keys.FIREBASE_UID]  = firebaseUid
            prefs[Keys.ROLE]          = role.name
            prefs[Keys.DISPLAY_NAME]  = displayName
            prefs[Keys.EMAIL]         = email
        }
    }

    /** Returns null if no session is cached. */
    suspend fun getSession(): CachedSession? =
        context.dataStore.data.map { prefs ->
            val uid  = prefs[Keys.FIREBASE_UID] ?: return@map null
            val role = prefs[Keys.ROLE]?.let { runCatching { UserRole.fromName(it) }.getOrNull() }
                ?: return@map null
            CachedSession(
                firebaseUid = uid,
                role        = role,
                displayName = prefs[Keys.DISPLAY_NAME] ?: "",
                email       = prefs[Keys.EMAIL] ?: ""
            )
        }.firstOrNull()

    suspend fun clearSession() {
        context.dataStore.edit { it.clear() }
    }
}

data class CachedSession(
    val firebaseUid: String,
    val role: UserRole,
    val displayName: String,
    val email: String
)
