package com.meetingatlas.dictation

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.meetingatlas.ai.OpenRouterSettingsStore
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException

@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface DictationDependencies {
    fun coordinator(): DictationCoordinator
    fun preferences(): DictationSettingsRepository
    fun keys(): OpenRouterSettingsStore
    fun history(): DictationHistoryRepository
}

/** Does local housekeeping only. No microphone, network or transcription retries. */
class DictationRetentionWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = try {
        val graph = EntryPointAccessors.fromApplication(applicationContext, DictationDependencies::class.java)
        graph.history().prune(graph.preferences().readDurable())
        Result.success()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        if (runAttemptCount < 2) Result.retry() else Result.failure()
    }

    companion object {
        private const val NAME = "atlas-dictation-retention"
        internal fun schedule(context: Context, enabled: Boolean) {
            val work = WorkManager.getInstance(context)
            if (enabled) work.enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<DictationRetentionWorker>(1, TimeUnit.DAYS).build())
            else work.cancelUniqueWork(NAME)
        }
    }
}
