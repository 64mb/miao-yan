package com.tw93.miaoyan.android.git

import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.ResetCommand
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.RefUpdate
import org.eclipse.jgit.lib.Repository

internal class RecoverableCheckout(
    private val recoveryRef: String = RecoveryRef,
) {
    fun apply(git: Git, target: ObjectId, validate: () -> Unit) {
        val repository = git.repository
        repository.resolve(Constants.HEAD)?.let { previous ->
            updateRecoveryRef(repository, previous)
        }
        git.reset().setMode(ResetCommand.ResetType.HARD).setRef(target.name).call()
        validate()
        deleteRecoveryRef(repository)
    }

    fun recoverIfNeeded(
        git: Git,
        validateTarget: (ObjectId) -> Unit,
        validateWorkingTree: () -> Unit,
    ): Boolean {
        val repository = git.repository
        val recoveryTarget = repository.resolve(recoveryRef) ?: return false
        validateTarget(recoveryTarget)
        git.reset().setMode(ResetCommand.ResetType.HARD).setRef(recoveryTarget.name).call()
        validateWorkingTree()
        deleteRecoveryRef(repository)
        return true
    }

    private fun updateRecoveryRef(repository: Repository, target: ObjectId) {
        val result = repository.updateRef(recoveryRef).apply {
            setNewObjectId(target)
            isForceUpdate = true
        }.update()
        if (result !in SuccessfulUpdates) {
            throw GitSyncException.Storage("Could not create the recoverable Git checkout snapshot ($result).")
        }
    }

    private fun deleteRecoveryRef(repository: Repository) {
        val result = repository.updateRef(recoveryRef).apply { isForceUpdate = true }.delete()
        if (result !in SuccessfulDeletes) {
            throw GitSyncException.Storage("Could not retire the recoverable Git checkout snapshot ($result).")
        }
    }

    private companion object {
        const val RecoveryRef = "refs/miaoyan/checkout-recovery"
        val SuccessfulUpdates = setOf(
            RefUpdate.Result.NEW,
            RefUpdate.Result.FAST_FORWARD,
            RefUpdate.Result.FORCED,
            RefUpdate.Result.NO_CHANGE,
        )
        val SuccessfulDeletes = setOf(RefUpdate.Result.FORCED, RefUpdate.Result.NO_CHANGE)
    }
}
