package com.joechrist.medtrack.worker

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.*
import com.joechrist.medtrack.data.repository.SyncRepository
import com.joechrist.medtrack.data.session.SessionManager
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

// =============================================================================
// MedTrack – SyncWorker
// Periodic WorkManager job (every 15 minutes) that:
//  1. Flushes the sync queue (failed optimistic writes)
//  2. Pulls fresh prescriptions + intake logs from the server
//  3. Extends the alarm window (schedules next 7 days)
//  4. Auto-misses overdue doses
//  5. Evicts stale Room data
//
// Constraints: network required (any). Retries with exponential backoff.
// =============================================================================

@HiltWorker
class SyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val syncRepository: SyncRepository,
    private val session: SessionManager
) : CoroutineWorker(context, params) {

    companion object {
        const val TAG = "MedTrack.SyncWorker"
        const val WORK_NAME_PERIODIC = "medtrack_sync_periodic"
        const val WORK_NAME_ONESHOT  = "medtrack_sync_oneshot"

        /**
         * Enqueues a periodic sync (every 15 min, network required).
         * Call once from MainActivity after auth completes.
         */
        fun enqueuePeriodic(workManager: WorkManager) {
            val request = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .addTag(TAG)
                .build()

            workManager.enqueueUniquePeriodicWork(
                WORK_NAME_PERIODIC,
                ExistingPeriodicWorkPolicy.KEEP,    // don't restart if already running
                request
            )
        }

        /**
         * One-shot sync triggered manually (pull-to-refresh, app foreground).
         */
        fun enqueueOneShot(workManager: WorkManager) {
            val request = OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .addTag(TAG)
                .build()

            workManager.enqueueUniqueWork(
                WORK_NAME_ONESHOT,
                ExistingWorkPolicy.REPLACE,
                request
            )
        }
    }

    override suspend fun doWork(): Result {
        Log.d(TAG, "SyncWorker started")

        val cached = session.getSession() ?: run {
            Log.d(TAG, "No session — skipping sync")
            return Result.success()
        }

        return runCatching {

            // 1. Flush any pending writes that failed while offline
            syncRepository.flushSyncQueue()
            Log.d(TAG, "Sync queue flushed")

            // 2. Pull fresh data from server
            syncRepository.syncPrescriptions(cached.firebaseUid)
            syncRepository.syncIntakeLogs(cached.firebaseUid)
            Log.d(TAG, "Remote data synced")

            // 3. Extend alarm window for the next 7 days
            syncRepository.scheduleAlarmsForActive(cached.firebaseUid, daysAhead = 7)
            Log.d(TAG, "Alarms scheduled")

            // 4. Auto-miss overdue doses
            syncRepository.autoMissExpired()
            Log.d(TAG, "Overdue doses auto-missed")

            // 5. Evict stale local data
            syncRepository.evictOldData()
            Log.d(TAG, "Old data evicted")

        }.fold(
            onSuccess = {
                Log.d(TAG, "SyncWorker completed successfully")
                Result.success()
            },
            onFailure = { e ->
                Log.e(TAG, "SyncWorker failed: ${e.message}")
                if (runAttemptCount < 3) Result.retry()
                else Result.failure()
            }
        )
    }
}
