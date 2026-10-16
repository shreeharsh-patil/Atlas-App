package com.meetingatlas.notes

import android.content.Context
import android.os.SystemClock
import androidx.work.*
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.*

@EntryPoint @InstallIn(SingletonComponent::class)
interface NotesWorkerDependencies { fun pipeline(): NotesPipeline }

@Singleton
class NotesScheduler @Inject constructor(@param:ApplicationContext private val context: Context, private val pipeline: NotesPipeline) {
    suspend fun enqueue(meetingId: String, template: MeetingTemplate, charging: Boolean, topicId: String? = null): String = withContext(Dispatchers.IO) {
        val id = pipeline.prepare(meetingId, template, topicId)
        try { WorkManager.getInstance(context).enqueueUniqueWork(id, ExistingWorkPolicy.KEEP, request(id, charging)).result.get() }
        catch (failure: Exception) { pipeline.markFailure(id, "Could not queue processing. Saved evidence is safe; retry."); throw failure }
        id
    }
    suspend fun cancel(jobId: String) = withContext(Dispatchers.IO) {
        pipeline.markFailure(jobId, "Processing paused. Completed transcript and notes have been kept.", "CANCELLED")
        WorkManager.getInstance(context).cancelUniqueWork(jobId).result.get()
    }
    companion object {
        internal fun request(id: String, charging: Boolean) = OneTimeWorkRequestBuilder<MeetingNotesWorker>()
            .setInputData(workDataOf("jobId" to id, "charging" to charging))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).setRequiresCharging(charging).setRequiresBatteryNotLow(true).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build()
    }
}

/** Standard WorkManager construction; Hilt entry point provides app-scoped repositories. */
class MeetingNotesWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val jobId = inputData.getString("jobId") ?: return@withContext Result.failure()
        val pipeline = EntryPointAccessors.fromApplication(applicationContext, NotesWorkerDependencies::class.java).pipeline()
        val deadline = SystemClock.elapsedRealtime() + 6 * 60_000L
        try {
            while (SystemClock.elapsedRealtime() < deadline) {
                ensureActive()
                when (pipeline.step(jobId)) {
                    PipelineStep.DONE -> return@withContext Result.success()
                    PipelineStep.DEFER -> return@withContext Result.retry()
                    PipelineStep.CONTINUE -> Unit
                }
            }
            // Yield below WorkManager's execution window. The successor starts only after this work succeeds.
            WorkManager.getInstance(applicationContext).enqueueUniqueWork(jobId, ExistingWorkPolicy.APPEND, NotesScheduler.request(jobId, inputData.getBoolean("charging", false))).result.get()
            Result.success()
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: com.meetingatlas.ai.OpenRouterException) {
            val retry = failure.statusCode in setOf(408, 429, 500, 502, 503, 504) && runAttemptCount < 4
            pipeline.markFailure(jobId, if (retry) "Provider temporarily unavailable. Saved progress is safe." else failure.message.orEmpty(), if (retry) "WAITING" else "FAILED")
            if (retry) Result.retry() else Result.failure()
        }
        catch (_: java.io.IOException) {
            pipeline.markFailure(jobId, "Connection interrupted. Saved progress is safe.", if (runAttemptCount < 4) "WAITING" else "FAILED")
            if (runAttemptCount < 4) Result.retry() else Result.failure()
        } catch (failure: Exception) {
            pipeline.markFailure(jobId, failure.message ?: "Processing could not finish. Saved progress is safe.")
            Result.failure()
        }
    }
}
