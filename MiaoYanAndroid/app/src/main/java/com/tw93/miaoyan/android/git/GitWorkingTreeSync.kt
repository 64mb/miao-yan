package com.tw93.miaoyan.android.git

import android.content.Context
import java.io.File
import java.nio.file.Files
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.FileMode
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.PersonIdent
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.storage.file.FileRepositoryBuilder
import org.eclipse.jgit.transport.RefLeaseSpec
import org.eclipse.jgit.transport.RefSpec
import org.eclipse.jgit.transport.RemoteRefUpdate
import org.eclipse.jgit.transport.TagOpt
import org.eclipse.jgit.transport.Transport
import org.eclipse.jgit.transport.TransportHttp
import org.eclipse.jgit.transport.HttpConfig
import org.eclipse.jgit.transport.URIish
import org.eclipse.jgit.treewalk.TreeWalk

class GitWorkingTreeSync(context: Context) {
    private val libraryDirectory = File(context.filesDir, "libraries/default")
    private val recoverableCheckout = RecoverableCheckout()

    fun sync(
        configValue: GitSyncConfig,
        credentialsValue: GitCredentials,
        deadlineNanos: Long? = null,
    ): GitSyncResult {
        val config = configValue.validated()
        val credentials = credentialsValue.validated()
        ActiveDraftRegistry.requireSafeForCheckout()
        libraryDirectory.mkdirs()
        openRepository().use { repository ->
            configure(repository, config.repositoryUrl)
            ensureMain(repository)
            Git(repository).use { git ->
                recoverCheckoutIfNeeded(git, repository)
                validateTrackedPaths(repository)
                val localBeforeCommit = snapshotWorkingTree()
                val statusBeforeCommit = git.status().call()
                val changedLocalPaths = statusBeforeCommit.added + statusBeforeCommit.changed +
                    statusBeforeCommit.modified + statusBeforeCommit.untracked
                GitContentGate.validateAttachmentSizes(
                    localBeforeCommit.sizes.filterKeys { it in changedLocalPaths },
                    "The private library",
                )
                stageAllowedFiles(git, localBeforeCommit.sizes.keys)
                val staged = git.diff().setCached(true).call()
                if (staged.isNotEmpty()) commit(git, config)

                val localHead = repository.resolve(Constants.HEAD)
                val beforeFetch = repository.resolve(RemoteMain)
                val provider = OriginBoundCredentialsProvider(config.repositoryUrl, credentials)
                ensureWithinDeadline(deadlineNanos)
                val fetchResult = try {
                    TransportDeadlineGuard(deadlineNanos, config.repositoryUrl).use { guard ->
                        git.fetch()
                            .setRemote(Origin)
                            .setRefSpecs(RefSpec("+$MainRef:$RemoteMain"))
                            .setRemoveDeletedRefs(true)
                            .setTagOpt(TagOpt.NO_TAGS)
                            .setCheckFetchedObjects(true)
                            .setTimeout(NetworkTimeoutSeconds)
                            .setCredentialsProvider(provider)
                            .setTransportConfigCallback(guard::configure)
                            .call()
                    }
                } catch (error: Throwable) {
                    throw GitSyncException.Remote("HTTPS fetch from origin/main failed.", error)
                }
                requireExactDestination(fetchResult.uri.toString(), config.repositoryUrl)
                val advertisedMain = fetchResult.advertisedRefs.firstOrNull { it.name == MainRef }?.objectId
                val remoteHead = repository.resolve(RemoteMain)?.takeIf { advertisedMain != null }

                if (advertisedMain != null && remoteHead != advertisedMain) {
                    throw GitSyncException.Remote("origin/main was advertised but not fetched.")
                }
                remoteHead?.let { validateCommitTree(repository, it) }

                if (localHead == null && remoteHead == null) {
                    return GitSyncResult(0, 0, 0, "")
                }
                if (localHead == null) {
                    ensureWithinDeadline(deadlineNanos)
                    val remote = requireNotNull(remoteHead)
                    val remoteSnapshot = validateCommitTree(repository, remote)
                    validateChangedAttachments(emptyMap(), remoteSnapshot, "origin/main")
                    resetHard(git, remote)
                    return GitSyncResult(0, remoteSnapshot.size, 0, remote.name)
                }
                if (remoteHead == null) {
                    validateChangedAttachments(
                        emptyMap(),
                        snapshotCommit(repository, localHead),
                        "The local main branch",
                    )
                    push(git, config.repositoryUrl, provider, localHead, ObjectId.zeroId(), deadlineNanos)
                    return GitSyncResult(staged.size, 0, 0, localHead.name)
                }
                if (localHead == remoteHead) {
                    return GitSyncResult(0, 0, 0, localHead.name)
                }
                val relation = GitHistoryPolicy.relation(repository, localHead, remoteHead)
                if (relation == GitHistoryRelation.SameTree) {
                    ensureWithinDeadline(deadlineNanos)
                    resetHard(git, remoteHead)
                    return GitSyncResult(0, 0, 0, remoteHead.name)
                }
                if (relation == GitHistoryRelation.RemoteAhead) {
                    val before = snapshotCommit(repository, localHead)
                    val after = validateCommitTree(repository, remoteHead)
                    validateChangedAttachments(before, after, "origin/main")
                    ensureWithinDeadline(deadlineNanos)
                    resetHard(git, remoteHead)
                    return resultForFastForward(before, after, remoteHead)
                }
                if (relation == GitHistoryRelation.LocalAhead) {
                    validateChangedAttachments(
                        snapshotCommit(repository, remoteHead),
                        snapshotCommit(repository, localHead),
                        "The local main branch",
                    )
                    push(git, config.repositoryUrl, provider, localHead, remoteHead, deadlineNanos)
                    return GitSyncResult(staged.size.coerceAtLeast(1), 0, 0, localHead.name)
                }

                val trackingUpdate = fetchResult.trackingRefUpdates.firstOrNull { it.localName == RemoteMain }
                val wasKnownFastForward = localHead == beforeFetch &&
                    trackingUpdate?.result == org.eclipse.jgit.lib.RefUpdate.Result.FAST_FORWARD
                if (wasKnownFastForward) {
                    val before = snapshotCommit(repository, localHead)
                    val after = validateCommitTree(repository, remoteHead)
                    validateChangedAttachments(before, after, "origin/main")
                    ensureWithinDeadline(deadlineNanos)
                    resetHard(git, remoteHead)
                    return resultForFastForward(before, after, remoteHead)
                }
                throw GitSyncException.Conflict(
                    details = GitConflictResolver.describe(
                        repository,
                        localHead,
                        remoteHead,
                        localBeforeCommit.modifiedTimes,
                    ),
                )
            }
        }
    }

    fun resolve(
        configValue: GitSyncConfig,
        credentialsValue: GitCredentials,
        details: GitConflictDetails,
        choices: Map<String, GitConflictChoice>,
        deadlineNanos: Long? = null,
    ): GitSyncResult {
        val config = configValue.validated()
        val credentials = credentialsValue.validated()
        openRepository().use { repository ->
            configure(repository, config.repositoryUrl)
            ensureMain(repository)
            Git(repository).use { git ->
                recoverCheckoutIfNeeded(git, repository)
                validateTrackedPaths(repository)
                snapshotWorkingTree()
                verifyCleanAllowedTree(git)

                val localId = parseConflictCommit(details.localCommit)
                val remoteId = parseConflictCommit(details.remoteCommit)
                if (repository.resolve(Constants.HEAD) != localId || repository.resolve(RemoteMain) != remoteId) {
                    throw GitSyncException.Conflict(
                        message = "The library changed after this conflict was detected. Sync again before choosing files.",
                    )
                }
                val localTree = validateCommitTree(repository, localId)
                val remoteTree = validateCommitTree(repository, remoteId)
                val current = GitConflictResolver.describe(
                    repository,
                    localId,
                    remoteId,
                    snapshotWorkingTree().modifiedTimes,
                )
                if (current.files.map { it.path } != details.files.map { it.path }) {
                    throw GitSyncException.Conflict(
                        message = "The stored conflict list no longer matches the repository. Sync again.",
                    )
                }
                val selectedEntries = current.files.mapNotNull { file ->
                    val selected = if (choices[file.path] == GitConflictChoice.Remote) {
                        remoteTree[file.path]
                    } else {
                        localTree[file.path]
                    }
                    selected?.let { file.path to it.size }
                }.toMap()
                GitContentGate.validateAttachmentSizes(selectedEntries, "The conflict resolution")

                ensureWithinDeadline(deadlineNanos)
                val resolution = GitConflictResolver.createResolutionCommit(repository, current, choices, config)
                validateCommitTree(repository, resolution)
                ensureWithinDeadline(deadlineNanos)
                resetHard(git, resolution)

                val provider = OriginBoundCredentialsProvider(config.repositoryUrl, credentials)
                push(git, config.repositoryUrl, provider, resolution, remoteId, deadlineNanos)
                val remoteChoices = current.files.filter { choices[it.path] == GitConflictChoice.Remote }
                return GitSyncResult(
                    uploaded = 1,
                    downloaded = remoteChoices.count { it.remoteExists },
                    deleted = remoteChoices.count { !it.remoteExists },
                    commitId = resolution.name,
                )
            }
        }
    }

    private fun openRepository(): Repository {
        val gitDirectory = File(libraryDirectory, ".git")
        val repository = FileRepositoryBuilder()
            .setGitDir(gitDirectory)
            .setWorkTree(libraryDirectory)
            .build()
        if (!gitDirectory.exists()) repository.create()
        return repository
    }

    private fun configure(repository: Repository, url: String) {
        val config = repository.config
        val otherRemotes = config.getSubsections("remote") - Origin
        if (otherRemotes.isNotEmpty()) {
            throw GitSyncException.Configuration("Only the origin remote is supported.")
        }
        config.setString("remote", Origin, "url", url)
        config.unset("remote", Origin, "pushurl")
        config.setString("remote", Origin, "fetch", "+$MainRef:$RemoteMain")
        config.setString("branch", "main", "remote", Origin)
        config.setString("branch", "main", "merge", MainRef)
        config.setString("http", null, "followRedirects", "false")
        config.setInt("http", null, "maxRedirects", 0)
        config.setBoolean("http", null, "sslVerify", true)
        config.setBoolean("fetch", null, "fsckObjects", true)
        config.setBoolean("transfer", null, "fsckObjects", true)
        config.save()
        val httpConfig = HttpConfig(config, URIish(url))
        if (
            httpConfig.followRedirects != HttpConfig.HttpRedirectMode.FALSE ||
            httpConfig.maxRedirects != 0 || !httpConfig.isSslVerify
        ) {
            throw GitSyncException.Configuration("HTTPS redirect and TLS verification policy could not be enforced.")
        }
    }

    private fun ensureMain(repository: Repository) {
        val fullBranch = repository.fullBranch
        if (repository.resolve(Constants.HEAD) == null) {
            repository.updateRef(Constants.HEAD).link(MainRef)
        } else if (fullBranch != MainRef) {
            throw GitSyncException.Configuration("Git sync only supports the local main branch.")
        }
    }

    private fun snapshotWorkingTree(): WorkingTreeSnapshot {
        val root = libraryDirectory.canonicalFile
        val sizes = linkedMapOf<String, Long>()
        val modifiedTimes = linkedMapOf<String, Long>()
        val collisions = mutableSetOf<String>()
        var scannedEntries = 0
        root.walkTopDown()
            .onEnter { directory ->
                if (directory != root && ++scannedEntries > GitSyncLimits.MaxScannedEntries) {
                    throw GitSyncException.Limit(
                        "Git sync stopped after scanning ${GitSyncLimits.MaxScannedEntries} files and directories.",
                    )
                }
                if (directory != root && Files.isSymbolicLink(directory.toPath())) {
                    val relative = directory.relativeTo(root).invariantSeparatorsPath
                    throw GitSyncException.Storage("Symbolic-link directories cannot be synced: $relative")
                }
                directory == root || directory.name != ".git"
            }
            .filter(File::isFile)
            .forEach { file ->
                if (++scannedEntries > GitSyncLimits.MaxScannedEntries) {
                    throw GitSyncException.Limit(
                        "Git sync stopped after scanning ${GitSyncLimits.MaxScannedEntries} files and directories.",
                    )
                }
                if (Files.isSymbolicLink(file.toPath())) {
                    val relative = file.relativeTo(root).invariantSeparatorsPath
                    if (GitSyncPathPolicy.isAllowed(relative)) {
                        throw GitSyncException.Storage("Symbolic links cannot be synced: $relative")
                    }
                    return@forEach
                }
                val canonical = file.canonicalFile
                if (!canonical.path.startsWith(root.path + File.separator)) {
                    throw GitSyncException.Storage("A library path escapes the private library.")
                }
                val relative = canonical.relativeTo(root).invariantSeparatorsPath
                if (!GitSyncPathPolicy.isAllowed(relative)) return@forEach
                if (!collisions.add(GitSyncPathPolicy.collisionKey(relative))) {
                    throw GitSyncException.Conflict(message = "Case-colliding paths cannot be synced: $relative")
                }
                sizes[relative] = canonical.length()
                modifiedTimes[relative] = canonical.lastModified()
                if (sizes.size > GitSyncLimits.MaxFiles) {
                    throw GitSyncException.Limit("Git sync supports at most ${GitSyncLimits.MaxFiles} files.")
                }
            }
        GitContentGate.validateStructure(sizes, "The private library")
        return WorkingTreeSnapshot(sizes, modifiedTimes)
    }

    private fun validateTrackedPaths(repository: Repository) {
        val cache = repository.readDirCache()
        for (index in 0 until cache.entryCount) {
            val entry = cache.getEntry(index)
            if (!GitSyncPathPolicy.isAllowed(entry.pathString)) {
                throw GitSyncException.Storage("Tracked path is outside the MiaoYan allowlist: ${entry.pathString}")
            }
        }
    }

    private fun stageAllowedFiles(git: Git, paths: Set<String>) {
        if (paths.isNotEmpty()) {
            val additions = git.add()
            paths.sorted().forEach(additions::addFilepattern)
            additions.call()
        }
        git.add().setUpdate(true).addFilepattern(".").call()
    }

    private fun commit(git: Git, config: GitSyncConfig) {
        val identity = PersonIdent(config.authorName, config.authorEmail, Instant.now(), ZoneId.systemDefault())
        git.commit()
            .setMessage("Sync from MiaoYan Android")
            .setAuthor(identity)
            .setCommitter(identity)
            .call()
    }

    private fun resetHard(git: Git, commit: ObjectId) {
        ActiveDraftRegistry.requireSafeForCheckout()
        recoverableCheckout.apply(git, commit) {
            snapshotWorkingTree()
            verifyCleanAllowedTree(git)
        }
    }

    private fun recoverCheckoutIfNeeded(git: Git, repository: Repository) {
        ActiveDraftRegistry.requireSafeForCheckout()
        recoverableCheckout.recoverIfNeeded(
            git = git,
            validateTarget = { recoveryTarget -> validateCommitTree(repository, recoveryTarget) },
        ) {
            snapshotWorkingTree()
            verifyCleanAllowedTree(git)
        }
    }

    private fun verifyCleanAllowedTree(git: Git) {
        val status = git.status().call()
        val unsafe = status.added + status.changed + status.conflicting + status.missing +
            status.modified + status.removed + status.uncommittedChanges + status.untracked
        if (unsafe.any(GitSyncPathPolicy::isAllowed)) {
            throw GitSyncException.Storage("The recoverable Git checkout did not complete cleanly.")
        }
    }

    private fun push(
        git: Git,
        expectedUrl: String,
        provider: OriginBoundCredentialsProvider,
        localHead: ObjectId,
        expectedRemote: ObjectId,
        deadlineNanos: Long?,
    ) {
        ensureWithinDeadline(deadlineNanos)
        val results = try {
            TransportDeadlineGuard(deadlineNanos, expectedUrl).use { guard ->
                git.push()
                    .setRemote(Origin)
                    .setRefSpecs(RefSpec("$MainRef:$MainRef"))
                    .setRefLeaseSpecs(RefLeaseSpec(MainRef, expectedRemote.name))
                    .setTimeout(NetworkTimeoutSeconds)
                    .setCredentialsProvider(provider)
                    .setTransportConfigCallback(guard::configure)
                    .call()
            }
        } catch (error: Throwable) {
            throw GitSyncException.Remote("HTTPS push to origin/main failed; the local commit is retained.", error)
        }
        val resultList = results.toList()
        if (resultList.size != 1) throw GitSyncException.Remote("Unexpected push destination count.")
        val result = resultList.single()
        requireExactDestination(result.uri.toString(), expectedUrl)
        val updates = result.remoteUpdates.filter { it.remoteName == MainRef }
        if (updates.size != 1 || updates.single().status !in setOf(
                RemoteRefUpdate.Status.OK,
                RemoteRefUpdate.Status.UP_TO_DATE,
            )
        ) {
            val status = updates.singleOrNull()?.status?.name ?: "missing main update"
            throw GitSyncException.Conflict(message = "origin/main rejected the safe update ($status).")
        }
    }

    private fun requireExactDestination(actual: String, expected: String) {
        if (actual != expected) {
            throw GitSyncException.Remote("Git transport destination changed; credentials were not accepted for it.")
        }
    }

    private fun ensureWithinDeadline(deadlineNanos: Long?) {
        if (deadlineNanos != null && System.nanoTime() >= deadlineNanos) {
            throw GitSyncException.Remote("The bounded Git sync deadline was reached.")
        }
    }

    private fun parseConflictCommit(value: String): ObjectId = runCatching { ObjectId.fromString(value) }
        .getOrElse {
            throw GitSyncException.Configuration("Stored conflict state contains an invalid commit id.")
        }

    private fun validateCommitTree(repository: Repository, commitId: ObjectId): Map<String, GitTreeEntry> {
        val snapshot = snapshotCommit(repository, commitId)
        GitContentGate.validateStructure(snapshot.mapValues { it.value.size }, "origin/main")
        return snapshot
    }

    private fun validateChangedAttachments(
        before: Map<String, GitTreeEntry>,
        after: Map<String, GitTreeEntry>,
        source: String,
    ) {
        val changed = after.filter { (path, entry) -> before[path] != entry }.mapValues { it.value.size }
        GitContentGate.validateAttachmentSizes(changed, source)
    }

    private fun snapshotCommit(repository: Repository, commitId: ObjectId): Map<String, GitTreeEntry> {
        repository.newObjectReader().use { reader ->
            org.eclipse.jgit.revwalk.RevWalk(reader).use { walk ->
                val commit = walk.parseCommit(commitId)
                val result = linkedMapOf<String, GitTreeEntry>()
                val collisions = mutableSetOf<String>()
                TreeWalk(repository).use { tree ->
                    tree.addTree(commit.tree)
                    tree.isRecursive = true
                    while (tree.next()) {
                        val path = tree.pathString
                        if (!GitSyncPathPolicy.isAllowed(path)) {
                            throw GitSyncException.Storage("origin/main contains a path outside the allowlist: $path")
                        }
                        val mode = tree.getFileMode(0)
                        if (mode != FileMode.REGULAR_FILE && mode != FileMode.EXECUTABLE_FILE) {
                            throw GitSyncException.Storage("origin/main contains a non-regular path: $path")
                        }
                        if (!collisions.add(GitSyncPathPolicy.collisionKey(path))) {
                            throw GitSyncException.Conflict(
                                message = "origin/main contains case-colliding paths: $path",
                            )
                        }
                        val objectId = tree.getObjectId(0)
                        result[path] = GitTreeEntry(
                            size = reader.getObjectSize(objectId, Constants.OBJ_BLOB),
                            objectId = objectId.name,
                        )
                    }
                }
                return result
            }
        }
    }

    private fun resultForFastForward(
        before: Map<String, GitTreeEntry>,
        after: Map<String, GitTreeEntry>,
        commit: ObjectId,
    ): GitSyncResult {
        val downloaded = after.keys.count { before[it] != after[it] }
        val deleted = before.keys.count { it !in after }
        return GitSyncResult(0, downloaded, deleted, commit.name)
    }

    private data class GitTreeEntry(val size: Long, val objectId: String)

    private data class WorkingTreeSnapshot(
        val sizes: Map<String, Long>,
        val modifiedTimes: Map<String, Long>,
    )

    private class TransportDeadlineGuard(
        private val deadlineNanos: Long?,
        private val expectedUrl: String,
    ) : AutoCloseable {
        private val active = AtomicReference<Transport?>()
        private val executor = deadlineNanos?.let { Executors.newSingleThreadScheduledExecutor() }
        private val watchdog: ScheduledFuture<*>? = deadlineNanos?.let { deadline ->
            val delay = (deadline - System.nanoTime()).coerceAtLeast(0L)
            executor?.schedule({ active.get()?.close() }, delay, TimeUnit.NANOSECONDS)
        }

        fun configure(transport: Transport) {
            if (transport !is TransportHttp || transport.uri.toString() != expectedUrl) {
                throw GitSyncException.Configuration("Git transport must be the configured HTTPS origin.")
            }
            if (deadlineNanos != null && System.nanoTime() >= deadlineNanos) {
                transport.close()
                throw GitSyncException.Remote("The bounded Git sync deadline was reached.")
            }
            active.set(transport)
        }

        override fun close() {
            active.set(null)
            watchdog?.cancel(true)
            executor?.shutdownNow()
        }
    }

    private companion object {
        const val Origin = "origin"
        const val MainRef = "refs/heads/main"
        const val RemoteMain = "refs/remotes/origin/main"
        const val NetworkTimeoutSeconds = 30
    }
}
