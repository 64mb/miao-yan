package com.tw93.miaoyan.android.git

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout

class GitSyncWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        return try {
            val trigger = GitSyncTrigger.from(inputData.getString(TriggerInputKey))
            val config = GitSyncPreferences(applicationContext).config.first()
            if (!GitSyncWorkerPolicy.shouldRun(trigger, config?.periodicEnabled == true)) {
                return Result.success()
            }
            val budget = GitSyncWorkerPolicy.budget(trigger)
            withTimeout(budget.maximumRunMillis) {
                GitSyncCoordinator(applicationContext).sync(budget.transportDeadlineMillis)
            }
            Result.success()
        } catch (_: GitSyncException.Configuration) {
            Result.success()
        } catch (_: GitSyncException.Conflict) {
            Result.failure()
        } catch (_: GitSyncException.Limit) {
            Result.failure()
        } catch (_: GitSyncException.Storage) {
            Result.failure()
        } catch (_: TimeoutCancellationException) {
            Result.retry()
        } catch (_: GitSyncException.Remote) {
            Result.retry()
        }
    }

    companion object {
        const val TriggerInputKey = "git_sync_trigger"
    }
}

internal enum class GitSyncTrigger(val serialized: String) {
    Periodic("periodic"),
    AppBackground("app_background"),
    ;

    companion object {
        fun from(value: String?): GitSyncTrigger = entries.firstOrNull { it.serialized == value } ?: Periodic
    }
}

internal data class GitSyncWorkerBudget(
    val maximumRunMillis: Long,
    val transportDeadlineMillis: Long,
)

internal object GitSyncWorkerPolicy {
    private val periodicBudget = GitSyncWorkerBudget(
        maximumRunMillis = 90_000L,
        transportDeadlineMillis = 75_000L,
    )
    private val backgroundBudget = GitSyncWorkerBudget(
        maximumRunMillis = 8_000L,
        transportDeadlineMillis = 6_000L,
    )

    fun shouldRun(trigger: GitSyncTrigger, periodicEnabled: Boolean): Boolean =
        trigger == GitSyncTrigger.AppBackground || periodicEnabled

    fun budget(trigger: GitSyncTrigger): GitSyncWorkerBudget = when (trigger) {
        GitSyncTrigger.Periodic -> periodicBudget
        GitSyncTrigger.AppBackground -> backgroundBudget
    }
}
