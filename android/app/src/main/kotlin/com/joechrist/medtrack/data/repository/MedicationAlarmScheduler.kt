package com.joechrist.medtrack.data.repository

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.joechrist.medtrack.alarm.MedicationAlarmReceiver
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

// =============================================================================
// MedTrack – MedicationAlarmScheduler
//
// Wraps AlarmManager to set EXACT alarms that survive Doze mode.
// Android version matrix:
//   API 26–30  → setExactAndAllowWhileIdle (requires WAKE_LOCK)
//   API 31–32  → SCHEDULE_EXACT_ALARM permission gate + setExactAndAllowWhileIdle
//   API 33+    → USE_EXACT_ALARM (always granted) + setExactAndAllowWhileIdle
//
// Each alarm fires MedicationAlarmReceiver with full context so the receiver
// can build the notification without any database lookup.
// =============================================================================

@Singleton
class MedicationAlarmScheduler @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    companion object {
        const val TAG = "MedTrack.AlarmScheduler"
        const val ACTION_MEDICATION_ALARM = "com.joechrist.medtrack.ACTION_MEDICATION_ALARM"

        // Extras carried in the alarm Intent
        const val EXTRA_LOG_ID           = "logId"
        const val EXTRA_PRESCRIPTION_ID  = "prescriptionId"
        const val EXTRA_MEDICATION_NAME  = "medicationName"

        // Each request code must be unique per alarm.
        // We derive it from a stable hash of the log ID.
        fun requestCodeFor(logId: String): Int = logId.hashCode().and(0x7FFFFFFF)
    }

    /**
     * Schedules an exact alarm at [triggerAtMs].
     *
     * @return The request code used (store in IntakeLogEntity.alarmId for cancellation).
     */
    fun schedule(
        logId: String,
        prescriptionId: String,
        medicationName: String,
        triggerAtMs: Long
    ): Int {
        val requestCode = requestCodeFor(logId)

        val intent = buildIntent(logId, prescriptionId, medicationName)
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        when {
            // API 33+: USE_EXACT_ALARM is always granted — just fire it
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerAtMs,
                    pendingIntent
                )
                Log.d(TAG, "Exact alarm set (API33+) for $medicationName at $triggerAtMs")
            }

            // API 31-32: Check SCHEDULE_EXACT_ALARM permission at runtime
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
                if (alarmManager.canScheduleExactAlarms()) {
                    alarmManager.setExactAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        triggerAtMs,
                        pendingIntent
                    )
                    Log.d(TAG, "Exact alarm set (API31-32) for $medicationName at $triggerAtMs")
                } else {
                    // Fall back to inexact — will still fire but may be delayed by Doze
                    alarmManager.setAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        triggerAtMs,
                        pendingIntent
                    )
                    Log.w(TAG, "Exact alarm permission not granted; inexact alarm set for $medicationName")
                }
            }

            // API 26-30: setExactAndAllowWhileIdle available, no permission needed
            else -> {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerAtMs,
                    pendingIntent
                )
                Log.d(TAG, "Exact alarm set (API<31) for $medicationName at $triggerAtMs")
            }
        }

        return requestCode
    }

    /**
     * Cancels an alarm by its request code.
     * Call when: prescription is CANCELLED/PAUSED, or dose is TAKEN before it fires.
     */
    fun cancel(requestCode: Int) {
        val placeholderIntent = Intent(context, MedicationAlarmReceiver::class.java)
            .setAction(ACTION_MEDICATION_ALARM)
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            requestCode,
            placeholderIntent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )
        pendingIntent?.let {
            alarmManager.cancel(it)
            it.cancel()
            Log.d(TAG, "Alarm cancelled: requestCode=$requestCode")
        }
    }

    /**
     * Cancels all alarms for a list of alarm IDs (e.g. when a prescription is cancelled).
     */
    fun cancelAll(alarmIds: List<Int>) {
        alarmIds.forEach { cancel(it) }
    }

    private fun buildIntent(
        logId: String,
        prescriptionId: String,
        medicationName: String
    ) = Intent(context, MedicationAlarmReceiver::class.java).apply {
        action = ACTION_MEDICATION_ALARM
        putExtra(EXTRA_LOG_ID, logId)
        putExtra(EXTRA_PRESCRIPTION_ID, prescriptionId)
        putExtra(EXTRA_MEDICATION_NAME, medicationName)
    }
}
