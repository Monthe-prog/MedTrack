package com.joechrist.medtrack.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class MedicationAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            "com.joechrist.medtrack.ACTION_MEDICATION_ALARM" -> {
                val prescriptionId = intent.getStringExtra("prescriptionId") ?: return
                val medicationName = intent.getStringExtra("medicationName") ?: "your medication"
                val logId          = intent.getStringExtra("logId") ?: return
                // Fire notification with Taken / Snooze actions
                // Full implementation in Option B
            }
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                // Re-schedule all active alarms from Room DB after reboot
                // Full implementation in Option B
            }
        }
    }
}
