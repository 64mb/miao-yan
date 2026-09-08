package com.tw93.miaoyan.android.git

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit

object GitSyncScheduler {
    private const val PeriodicWorkName = "miaoyan-git-sync-periodic"
    private const val BackgroundWorkName = "miaoyan-git-sync-background"
    private val networkConstraint = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    fun updatePeriodic(context: Context, enabled: Boolean) {
        val manager = WorkManager.getInstance(context)
        if (!enabled) {
            manager.cancelUniqueWork(PeriodicWorkName)
            return
        }
        val request = PeriodicWorkRequestBuilder<GitSyncWorker>(15, TimeUnit.MINUTES)
            .setConstraints(networkConstraint)
            .setInputData(workDataOf(GitSyncWorker.TriggerInputKey to GitSyncTrigger.Periodic.serialized))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        manager.enqueueUniquePeriodicWork(PeriodicWorkName, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    fun enqueueBackground(context: Context) {
        val request = OneTimeWorkRequestBuilder<GitSyncWorker>()
            .setConstraints(networkConstraint)
            .setInputData(workDataOf(GitSyncWorker.TriggerInputKey to GitSyncTrigger.AppBackground.serialized))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            BackgroundWorkName,
            ExistingWorkPolicy.KEEP,
            request,
        )
    }
}
