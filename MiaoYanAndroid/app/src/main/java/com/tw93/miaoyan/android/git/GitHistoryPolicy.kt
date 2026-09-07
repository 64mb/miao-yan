package com.tw93.miaoyan.android.git

import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.revwalk.RevWalk

internal enum class GitHistoryRelation { LocalAhead, RemoteAhead, SameTree, Diverged }

internal object GitHistoryPolicy {
    fun relation(repository: Repository, local: ObjectId, remote: ObjectId): GitHistoryRelation {
        return repository.newObjectReader().use { reader ->
            RevWalk(reader).use { walk ->
                val localCommit = walk.parseCommit(local)
                val remoteCommit = walk.parseCommit(remote)
                when {
                    localCommit.tree.id == remoteCommit.tree.id -> GitHistoryRelation.SameTree
                    walk.isMergedInto(localCommit, remoteCommit) -> GitHistoryRelation.RemoteAhead
                    walk.isMergedInto(remoteCommit, localCommit) -> GitHistoryRelation.LocalAhead
                    else -> GitHistoryRelation.Diverged
                }
            }
        }
    }
}
