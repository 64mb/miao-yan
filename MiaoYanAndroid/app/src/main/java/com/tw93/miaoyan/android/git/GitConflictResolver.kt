package com.tw93.miaoyan.android.git

import java.time.Instant
import java.time.ZoneId
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.dircache.DirCache
import org.eclipse.jgit.dircache.DirCacheEditor
import org.eclipse.jgit.dircache.DirCacheEntry
import org.eclipse.jgit.lib.CommitBuilder
import org.eclipse.jgit.lib.FileMode
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.PersonIdent
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.treewalk.TreeWalk

internal object GitConflictResolver {
    fun describe(
        repository: Repository,
        localId: ObjectId,
        remoteId: ObjectId,
        localFileModifiedAtMillis: Map<String, Long>,
    ): GitConflictDetails {
        val local = entries(repository, localId)
        val remote = entries(repository, remoteId)
        val paths = (local.keys + remote.keys).filter { local[it] != remote[it] }.sorted()
        if (paths.isEmpty()) {
            throw GitSyncException.Storage("Diverged commits unexpectedly contain identical trees.")
        }
        return GitConflictDetails(
            localCommit = localId.name,
            remoteCommit = remoteId.name,
            files = paths.map { path ->
                GitConflictFile(
                    path = path,
                    localModifiedAtMillis = localFileModifiedAtMillis[path].takeIf { path in local },
                    remoteModifiedAtMillis = lastChangeMillis(repository, remoteId, path),
                    localExists = path in local,
                    remoteExists = path in remote,
                )
            },
        )
    }

    fun createResolutionCommit(
        repository: Repository,
        details: GitConflictDetails,
        choices: Map<String, GitConflictChoice>,
        config: GitSyncConfig,
    ): ObjectId {
        val expectedPaths = details.files.mapTo(linkedSetOf()) { it.path }
        if (expectedPaths.isEmpty() || choices.keys != expectedPaths) {
            throw GitSyncException.Configuration("Choose Local or Remote for every conflicting file.")
        }
        val localId = parseCommit(details.localCommit)
        val remoteId = parseCommit(details.remoteCommit)
        val remoteEntries = entries(repository, remoteId)

        repository.newObjectReader().use { reader ->
            RevWalk(reader).use { walk ->
                val localCommit = walk.parseCommit(localId)
                val cache = DirCache.newInCore()
                cache.builder().apply {
                    addTree(ByteArray(0), 0, reader, localCommit.tree)
                    finish()
                }
                cache.editor().apply {
                    choices.filterValues { it == GitConflictChoice.Remote }.keys.sorted().forEach { path ->
                        val remote = remoteEntries[path]
                        if (remote == null) {
                            add(DirCacheEditor.DeletePath(path))
                        } else {
                            add(
                                object : DirCacheEditor.PathEdit(path) {
                                    override fun apply(entry: DirCacheEntry) {
                                        entry.fileMode = remote.mode
                                        entry.setObjectId(remote.objectId)
                                    }
                                },
                            )
                        }
                    }
                    finish()
                }
                repository.newObjectInserter().use { inserter ->
                    val treeId = cache.writeTree(inserter)
                    val identity = PersonIdent(
                        config.authorName,
                        config.authorEmail,
                        Instant.now(),
                        ZoneId.systemDefault(),
                    )
                    val commit = CommitBuilder().apply {
                        setTreeId(treeId)
                        setParentIds(localId, remoteId)
                        author = identity
                        committer = identity
                        message = "Resolve Git sync choices from MiaoYan Android"
                    }
                    return inserter.insert(commit).also { inserter.flush() }
                }
            }
        }
    }

    private fun entries(repository: Repository, commitId: ObjectId): Map<String, TreeEntry> {
        repository.newObjectReader().use { reader ->
            RevWalk(reader).use { walk ->
                val commit = walk.parseCommit(commitId)
                val result = linkedMapOf<String, TreeEntry>()
                TreeWalk(reader).use { tree ->
                    tree.addTree(commit.tree)
                    tree.isRecursive = true
                    while (tree.next()) {
                        result[tree.pathString] = TreeEntry(tree.getObjectId(0), tree.getFileMode(0))
                    }
                }
                return result
            }
        }
    }

    private fun lastChangeMillis(repository: Repository, commitId: ObjectId, path: String): Long? =
        Git(repository).log().add(commitId).addPath(path).setMaxCount(1).call().firstOrNull()
            ?.commitTime?.times(1_000L)

    private fun parseCommit(value: String): ObjectId = runCatching { ObjectId.fromString(value) }.getOrElse {
        throw GitSyncException.Configuration("Stored conflict state contains an invalid commit id.")
    }

    private data class TreeEntry(val objectId: ObjectId, val mode: FileMode)
}
