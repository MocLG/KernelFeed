/*
 * KernelFeed — an offline-first reader for the lore.kernel.org mailing-list archives.
 * Copyright (C) 2026 Luka Gejak
 *
 * This program is free software: you can redistribute it and/or modify it
 * under the terms of the GNU General Public License, version 3, as published
 * by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for
 * more details.
 *
 * You should have received a copy of the GNU General Public License along
 * with this program. If not, see <https://www.gnu.org/licenses/>.
 *
 * Alternatively, this file is available under a commercial licence that lifts
 * the obligations of the GPL. Enquiries: lukagejak5@gmail.com
 */

package dev.lukag.kernelfeed.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dev.lukag.kernelfeed.core.Resource
import dev.lukag.kernelfeed.domain.repository.ThreadRepository
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.hours

/**
 * Periodic background sync.
 *
 * Two jobs, in this order:
 *
 *  1. **Refresh saved threads.** Threads the user pinned are re-downloaded when stale, so
 *     a saved thread opened on a plane shows the replies that arrived since.
 *  2. **Evict unsaved cached threads.** Everything the user merely *read* is discarded
 *     after two weeks. Saved threads are never eligible, which is what makes the offline
 *     promise unconditional.
 *
 * The feed index is deliberately not refreshed here: it is cheap to fetch on open and
 * doing it in the background would spend the user's battery and the archive's bandwidth
 * on a list they may not look at.
 */
@HiltWorker
class SyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val repository: ThreadRepository,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = when (val outcome = repository.syncSavedThreads()) {
        is Resource.Success -> {
            // A partial failure is worth one retry; a total one usually means the archive
            // or the connection is down, and WorkManager's backoff handles that better
            // than an immediate re-run would.
            if (outcome.data.failed > 0 && outcome.data.refreshed == 0) Result.retry()
            else Result.success()
        }
        is Resource.Offline -> Result.retry()
        is Resource.Error -> Result.retry()
        is Resource.Loading -> Result.success()
    }

    companion object {
        private const val UNIQUE_NAME = "lkml-sync"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<SyncWorker>(
                repeatInterval = 6.hours.inWholeMinutes,
                repeatIntervalTimeUnit = TimeUnit.MINUTES,
            )
                .setConstraints(
                    Constraints.Builder()
                        // Unmetered only: a saved patch series can be several megabytes,
                        // and no one wants their thread cache refreshed on cellular data.
                        .setRequiredNetworkType(NetworkType.UNMETERED)
                        .setRequiresBatteryNotLow(true)
                        .build(),
                )
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_NAME,
                // KEEP, so an already-scheduled job is not reset on every app launch —
                // which with a 6-hour period could mean it never actually runs.
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }
    }
}
