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
            if (
                inputData.getBoolean(AutomaticInputKey, false) &&
                GitSyncPreferences(applicationContext).config.first()?.periodicEnabled != true
            ) {
                return Result.success()
            }
            withTimeout(MaximumRunMillis) {
                GitSyncCoordinator(applicationContext).sync(TransportDeadlineMillis)
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
        const val AutomaticInputKey = "automatic_git_sync"
        private const val MaximumRunMillis = 90_000L
        private const val TransportDeadlineMillis = 75_000L
    }
}
