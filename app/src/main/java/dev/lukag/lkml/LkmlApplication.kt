package dev.lukag.lkml

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import dev.lukag.lkml.work.SyncWorker
import javax.inject.Inject

@HiltAndroidApp
class LkmlApplication : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory

    /**
     * On-demand WorkManager initialisation (the default initialiser is disabled in the
     * manifest) so the Hilt-aware worker factory is in place before any worker is created.
     */
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()
        SyncWorker.schedule(this)
    }
}
