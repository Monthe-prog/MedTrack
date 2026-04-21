package com.joechrist.medtrack

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

// =============================================================================
// MedTrack – Application Class
// =============================================================================

@HiltAndroidApp
class MedTrackApp : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
    }

    // ── Notification Channels (required on API 26+) ───────────────────────────

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val manager = getSystemService(NotificationManager::class.java)

        // Channel 1: Medication reminders (high importance → heads-up)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_MEDICATION_REMINDER,
                "Medication Reminders",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Alerts when it's time to take your medication"
                enableVibration(true)
                enableLights(true)
            }
        )

        // Channel 2: General app notifications (FCM messages, updates)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_GENERAL,
                "General Notifications",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Doctor messages and prescription updates"
            }
        )
    }

    companion object {
        const val CHANNEL_MEDICATION_REMINDER = "medtrack_medication_reminder"
        const val CHANNEL_GENERAL             = "medtrack_general"
    }
}
