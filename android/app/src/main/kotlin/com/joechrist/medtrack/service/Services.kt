// =============================================================================
// MedTrack – Firebase Cloud Messaging Service
// =============================================================================
package com.joechrist.medtrack.service

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.joechrist.medtrack.MainActivity
import com.joechrist.medtrack.MedTrackApp
import com.joechrist.medtrack.R

class MedTrackMessagingService : FirebaseMessagingService() {

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)

        val title = message.notification?.title
            ?: message.data["title"] ?: "MedTrack"
        val body = message.notification?.body
            ?: message.data["body"] ?: ""
        val type = message.data["type"] ?: "general"   // "medication_reminder" | "chat" | "prescription"

        val channelId = if (type == "medication_reminder")
            MedTrackApp.CHANNEL_MEDICATION_REMINDER
        else
            MedTrackApp.CHANNEL_GENERAL

        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP
            message.data.forEach { (k, v) -> putExtra(k, v) }
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        getSystemService(NotificationManager::class.java)
            .notify(System.currentTimeMillis().toInt(), notification)
    }

    /** Called when FCM rotates the registration token — sync with backend. */
    override fun onNewToken(token: String) {
        super.onNewToken(token)
        // TODO: POST /users/{id}/fcm-token in next step
    }
}
