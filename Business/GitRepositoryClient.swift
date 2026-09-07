import Foundation
import CLibGit2

struct GitHTTPAuthentication: Sendable {
    let username: String
    let token: String
}

enum GitRepositoryError: LocalizedError, Equatable {
    case invalidRemoteURL
    case invalidRelativePath(String)
    case unrelatedHistories
    case remoteRejected(String)
    case operationFailed(operation: String, message: String)

    var errorDescription: String? {
        switch self {
        case .invalidRemoteURL:
            return "Git sync requires an HTTPS repository URL without embedded credentials."
        case let .invalidRelativePath(path):
            return "Git sync refused an unsafe repository path: \(path)"
        case .unrelatedHistories:
            return "The local and remote Git histories do not share a common commit."
        case let .remoteRejected(message):
            return "The Git server rejected the push: \(message)"
        case let .operationFailed(operation, message):
            return "Git \(operation) failed: \(message)"
        }
    }
}

enum GitHeadIntegration: Equatable, Sendable {
    case upToDate
    case fastForward
    case mergeCommit
    case conflicts([GitSyncConflictFile])
}

/// The narrow libgit2 boundary used by macOS Git sync.
///
/// Calls are serialized by the actor. Authentication exists only for the
/// duration of a network operation and is never written to Git configuration.
actor GitRepositoryClient {
    private let allowFileRemotesForTesting: Bool

    init(allowFileRemotesForTesting: Bool = false) {
        self.allowFileRemotesForTesting = allowFileRemotesForTesting
        git_libgit2_init()
    }

    deinit {
        git_libgit2_shutdown()
    }

    func clone(
        remoteURL: URL,
        to destinationURL: URL,
        branch: String = "main",
        authentication: GitHTTPAuthentication
    ) throws {
        try Self.validate(remoteURL: remoteURL, allowFile: allowFileRemotesForTesting)

        let context = try GitRemoteOperationContext(
            authentication: authentication,
            configuredRemoteURL: remoteURL,
            allowFileRemote: allowFileRemotesForTesting
        )
        var options = git_clone_options()
        try Self.check(
            git_clone_options_init(&options, UInt32(GIT_CLONE_OPTIONS_VERSION)),
            operation: "clone setup"
        )
        options.fetch_opts.callbacks = Self.callbacks(for: context)
        options.fetch_opts.follow_redirects = GIT_REMOTE_REDIRECT_NONE
        options.fetch_opts.download_tags = GIT_REMOTE_DOWNLOAD_TAGS_NONE

        var repository: OpaquePointer?
        let result = branch.withCString { branchPointer in
            options.checkout_branch = branchPointer
            return remoteURL.absoluteString.withCString { remote in
                destinationURL.path.withCString { destination in
                    git_clone(&repository, remote, destination, &options)
                }
            }
        }
        try Self.check(result, operation: "clone")
        git_repository_free(repository)
    }

    /// Creates an empty repository with an HTTPS `origin` and an unborn main
    /// branch. Existing repositories are opened without rewriting config.
    func prepareRepository(at repositoryURL: URL, remoteURL: URL) throws {
        try Self.validate(remoteURL: remoteURL, allowFile: allowFileRemotesForTesting)

        var repository: OpaquePointer?
        let openedExistingRepository = git_repository_open(&repository, repositoryURL.path) == 0
        if !openedExistingRepository {
            try Self.check(
                git_repository_init(&repository, repositoryURL.path, 0),
                operation: "repository initialization"
            )
        }
        defer { git_repository_free(repository) }

        guard git_repository_is_bare(repository) == 0,
            let workdirPointer = git_repository_workdir(repository)
        else {
            throw GitRepositoryError.operationFailed(
                operation: "repository validation",
                message: "the selected library is not a normal Git working tree"
            )
        }
        let configuredWorkdir = URL(fileURLWithPath: String(cString: workdirPointer), isDirectory: true)
            .standardizedFileURL.resolvingSymlinksInPath()
        guard configuredWorkdir == repositoryURL.standardizedFileURL.resolvingSymlinksInPath() else {
            throw GitRepositoryError.operationFailed(
                operation: "repository validation",
                message: "the selected library belongs to a different Git working tree"
            )
        }

        if openedExistingRepository {
            var headReference: OpaquePointer?
            try Self.check(git_reference_lookup(&headReference, repository, "HEAD"), operation: "HEAD validation")
            defer { git_reference_free(headReference) }
            guard let target = git_reference_symbolic_target(headReference),
                String(cString: target) == "refs/heads/main"
            else {
                throw GitRepositoryError.operationFailed(
                    operation: "branch validation",
                    message: "the existing repository must already use the main branch"
                )
            }
        } else {
            try Self.check(
                git_repository_set_head(repository, "refs/heads/main"),
                operation: "branch setup"
            )
        }

        var existingRemote: OpaquePointer?
        let lookup = git_remote_lookup(&existingRemote, repository, "origin")
        if lookup == 0 {
            defer { git_remote_free(existingRemote) }
            _ = try validatedOriginURLs(existingRemote, configuredRemoteURL: remoteURL)
        } else if lookup == GIT_ENOTFOUND.rawValue {
            var createdRemote: OpaquePointer?
            try Self.check(
                git_remote_create(&createdRemote, repository, "origin", remoteURL.absoluteString),
                operation: "remote setup"
            )
            defer { git_remote_free(createdRemote) }
            _ = try validatedOriginURLs(createdRemote, configuredRemoteURL: remoteURL)
        } else {
            try Self.check(lookup, operation: "remote lookup")
        }

    }

    /// Stages exactly the supplied paths. Missing paths become index removals;
    /// no wildcard or `add all` operation is used.
    func stage(relativePaths: [String], in repositoryURL: URL) throws {
        let paths = try relativePaths.map(Self.validatedRelativePath)
        let repository = try Self.openRepository(at: repositoryURL)
        defer { git_repository_free(repository) }

        var index: OpaquePointer?
        try Self.check(git_repository_index(&index, repository), operation: "index open")
        defer { git_index_free(index) }

        for path in paths {
            let fileURL = repositoryURL.appendingPathComponent(path)
            if FileManager.default.fileExists(atPath: fileURL.path) {
                try Self.check(git_index_add_bypath(index, path), operation: "stage \(path)")
            } else {
                let result = git_index_remove_bypath(index, path)
                if result != 0, result != GIT_ENOTFOUND.rawValue {
                    try Self.check(result, operation: "stage deletion \(path)")
                }
            }
        }

        try Self.check(git_index_write(index), operation: "index write")
    }

    func trackedEntries(in repositoryURL: URL) throws -> [GitSyncChange] {
        let repository = try Self.openRepository(at: repositoryURL)
        defer { git_repository_free(repository) }
        var index: OpaquePointer?
        try Self.check(git_repository_index(&index, repository), operation: "index open")
        defer { git_index_free(index) }

        return (0..<git_index_entrycount(index)).compactMap { position in
            guard let entry = git_index_get_byindex(index, position), let path = entry.pointee.path else { return nil }
            let entryKind: GitSyncEntryKind
            switch entry.pointee.mode {
            case UInt32(GIT_FILEMODE_LINK.rawValue): entryKind = .symbolicLink
            case UInt32(GIT_FILEMODE_COMMIT.rawValue): entryKind = .submodule
            default: entryKind = .regularFile
            }
            return GitSyncChange(kind: .modified, path: String(cString: path), entryKind: entryKind)
        }
    }

    /// Describes index entries that differ from HEAD before MiaoYan stages its
    /// own allowlisted files. In particular, this catches a pre-staged deletion
    /// of an unsupported tracked path that is no longer present in the index.
    func stagedChanges(in repositoryURL: URL) throws -> [GitSyncChange] {
        let repository = try Self.openRepository(at: repositoryURL)
        defer { git_repository_free(repository) }
        var index: OpaquePointer?
        try Self.check(git_repository_index(&index, repository), operation: "index open")
        defer { git_index_free(index) }

        var headTree: OpaquePointer?
        if let headOID = try Self.optionalOID(named: "refs/heads/main", in: repository) {
            let headCommit = try Self.lookupCommit(headOID, in: repository, operation: "HEAD commit lookup")
            defer { git_commit_free(headCommit) }
            try Self.check(git_commit_tree(&headTree, headCommit), operation: "HEAD tree lookup")
        }
        defer { git_tree_free(headTree) }

        var diff: OpaquePointer?
        try Self.check(
            git_diff_tree_to_index(&diff, repository, headTree, index, nil),
            operation: "staged diff"
        )
        defer { git_diff_free(diff) }
        try Self.check(git_diff_find_similar(diff, nil), operation: "staged rename detection")

        return try Self.syncChanges(in: diff, repository: repository)
    }

    func worktreeChanges(in repositoryURL: URL) throws -> [GitSyncChange] {
        let repository = try Self.openRepository(at: repositoryURL)
        defer { git_repository_free(repository) }
        var index: OpaquePointer?
        try Self.check(git_repository_index(&index, repository), operation: "worktree index open")
        defer { git_index_free(index) }
        var options = git_diff_options()
        try Self.check(git_diff_options_init(&options, UInt32(GIT_DIFF_OPTIONS_VERSION)), operation: "worktree diff setup")
        options.flags = UInt32(GIT_DIFF_INCLUDE_UNTRACKED.rawValue | GIT_DIFF_RECURSE_UNTRACKED_DIRS.rawValue)
        var diff: OpaquePointer?
        try Self.check(git_diff_index_to_workdir(&diff, repository, index, &options), operation: "worktree diff")
        defer { git_diff_free(diff) }
        return try Self.syncChanges(in: diff, repository: repository)
    }

    func headRevision(in repositoryURL: URL) throws -> String? {
        let repository = try Self.openRepository(at: repositoryURL)
        defer { git_repository_free(repository) }
        guard let oid = try Self.optionalOID(named: "refs/heads/main", in: repository) else { return nil }
        var value = oid.value
        guard let pointer = git_oid_tostr_s(&value) else { return nil }
        return String(cString: pointer)
    }

    func originMainRevision(in repositoryURL: URL) throws -> String? {
        let repository = try Self.openRepository(at: repositoryURL)
        defer { git_repository_free(repository) }
        guard var oid = try Self.optionalOID(named: "refs/remotes/origin/main", in: repository)?.value,
            let pointer = git_oid_tostr_s(&oid)
        else { return nil }
        return String(cString: pointer)
    }

    /// Keeps the last pre-apply commit reachable even after main advances.
    /// This is a local recovery ref and is never part of the push refspec.
    func createRecoveryReference(in repositoryURL: URL) throws -> String? {
        let repository = try Self.openRepository(at: repositoryURL)
        defer { git_repository_free(repository) }
        guard let oid = try Self.optionalOID(named: "refs/heads/main", in: repository) else { return nil }
        var value = oid.value
        var reference: OpaquePointer?
        try Self.check(
            git_reference_create(
                &reference,
                repository,
                "refs/miaoyan/recovery/latest",
                &value,
                1,
                "MiaoYan sync recovery point"
            ),
            operation: "recovery reference"
        )
        git_reference_free(reference)
        guard let pointer = git_oid_tostr_s(&value) else { return nil }
        return String(cString: pointer)
    }

    func restoreMain(to revision: String, in repositoryURL: URL) throws {
        let repository = try Self.openRepository(at: repositoryURL)
        defer { git_repository_free(repository) }

        var targetOID = git_oid()
        try revision.withCString { pointer in
            try Self.check(git_oid_fromstr(&targetOID, pointer), operation: "recovery revision parsing")
        }
        let target = GitOID(value: targetOID)
        let commit = try Self.lookupCommit(target, in: repository, operation: "recovery commit lookup")
        defer { git_commit_free(commit) }
        let current = try Self.optionalOID(named: "refs/heads/main", in: repository)
        try Self.checkoutAndReplaceMain(
            commit,
            in: repository,
            oldOID: current,
            newOID: target,
            reflogMessage: "MiaoYan sync: restore recovery point",
            checkoutStrategy: UInt32(GIT_CHECKOUT_FORCE.rawValue)
        )
    }

    /// Describes the fetched candidate before checkout so policy validation can
    /// reject unsafe paths and entry modes while the live worktree is intact.
    func incomingChangesFromOriginMain(in repositoryURL: URL) throws -> GitSyncIncomingSnapshot {
        let repository = try Self.openRepository(at: repositoryURL)
        defer { git_repository_free(repository) }
        let localOID = try Self.optionalOID(named: "refs/heads/main", in: repository)
        guard let remoteOID = try Self.optionalOID(named: "refs/remotes/origin/main", in: repository) else {
            return GitSyncIncomingSnapshot(changes: [], remoteRevision: nil)
        }
        let remoteRevision = try Self.revisionString(for: remoteOID, operation: "origin/main revision")

        var localTree: OpaquePointer?
        if let localOID {
            let commit = try Self.lookupCommit(localOID, in: repository, operation: "local tree commit lookup")
            defer { git_commit_free(commit) }
            try Self.check(git_commit_tree(&localTree, commit), operation: "local tree lookup")
        }
        defer { git_tree_free(localTree) }

        let remoteCommit = try Self.lookupCommit(remoteOID, in: repository, operation: "remote tree commit lookup")
        defer { git_commit_free(remoteCommit) }
        var remoteTree: OpaquePointer?
        try Self.check(git_commit_tree(&remoteTree, remoteCommit), operation: "remote tree lookup")
        defer { git_tree_free(remoteTree) }

        var diff: OpaquePointer?
        try Self.check(
            git_diff_tree_to_tree(&diff, repository, localTree, remoteTree, nil),
            operation: "incoming diff"
        )
        defer { git_diff_free(diff) }
        try Self.check(git_diff_find_similar(diff, nil), operation: "rename detection")

        return GitSyncIncomingSnapshot(
            changes: try Self.syncChanges(in: diff, repository: repository),
            remoteRevision: remoteRevision
        )
    }

    /// Returns the tree changes that were actually applied to `main`. For a
    /// divergent history this deliberately compares the old local commit with
    /// the resulting merge commit, rather than comparing local with remote.
    /// The latter would incorrectly describe local-only files as deletions.
    func changesAppliedSince(_ revision: String?, in repositoryURL: URL) throws -> [GitSyncChange] {
        let repository = try Self.openRepository(at: repositoryURL)
        defer { git_repository_free(repository) }

        var previousTree: OpaquePointer?
        if let revision {
            var previousOID = git_oid()
            let parseResult = revision.withCString { git_oid_fromstr(&previousOID, $0) }
            try Self.check(parseResult, operation: "previous revision parsing")
            let previousCommit = try Self.lookupCommit(
                GitOID(value: previousOID),
                in: repository,
                operation: "previous revision lookup"
            )
            defer { git_commit_free(previousCommit) }
            try Self.check(git_commit_tree(&previousTree, previousCommit), operation: "previous tree lookup")
        }
        defer { git_tree_free(previousTree) }

        guard let currentOID = try Self.optionalOID(named: "refs/heads/main", in: repository) else {
            return []
        }
        let currentCommit = try Self.lookupCommit(currentOID, in: repository, operation: "current revision lookup")
        defer { git_commit_free(currentCommit) }
        var currentTree: OpaquePointer?
        try Self.check(git_commit_tree(&currentTree, currentCommit), operation: "current tree lookup")
        defer { git_tree_free(currentTree) }

        var diff: OpaquePointer?
        try Self.check(
            git_diff_tree_to_tree(&diff, repository, previousTree, currentTree, nil),
            operation: "applied diff"
        )
        defer { git_diff_free(diff) }
        try Self.check(git_diff_find_similar(diff, nil), operation: "applied rename detection")

        return try Self.syncChanges(in: diff, repository: repository)
    }

    /// Commits the current index and returns `false` when it is identical to
    /// HEAD. This method never stages files on its own.
    func commitIndex(
        in repositoryURL: URL,
        message: String,
        authorName: String,
        authorEmail: String
    ) throws -> Bool {
        let repository = try Self.openRepository(at: repositoryURL)
        defer { git_repository_free(repository) }

        var index: OpaquePointer?
        try Self.check(git_repository_index(&index, repository), operation: "index open")
        defer { git_index_free(index) }

        var treeOID = git_oid()
        try Self.check(git_index_write_tree(&treeOID, index), operation: "tree write")

        var parent: OpaquePointer?
        let headResult = git_revparse_single(&parent, repository, "HEAD^{commit}")
        if headResult != 0, headResult != GIT_ENOTFOUND.rawValue,
            headResult != GIT_EUNBORNBRANCH.rawValue
        {
            try Self.check(headResult, operation: "HEAD lookup")
        }
        defer { git_commit_free(parent) }

        if parent == nil, git_index_entrycount(index) == 0 {
            return false
        }

        if let parent, let parentTreeOID = git_commit_tree_id(parent),
            git_oid_equal(&treeOID, parentTreeOID) == 1
        {
            return false
        }

        var tree: OpaquePointer?
        try Self.check(git_tree_lookup(&tree, repository, &treeOID), operation: "tree lookup")
        defer { git_tree_free(tree) }

        var signature: UnsafeMutablePointer<git_signature>?
        try Self.check(
            git_signature_now(&signature, authorName, authorEmail),
            operation: "signature creation"
        )
        defer { git_signature_free(signature) }

        var commitOID = git_oid()
        if let parent {
            var parents: [OpaquePointer?] = [parent]
            try parents.withUnsafeMutableBufferPointer { buffer in
                try Self.check(
                    git_commit_create(
                        &commitOID,
                        repository,
                        "HEAD",
                        signature,
                        signature,
                        nil,
                        message,
                        tree,
                        1,
                        buffer.baseAddress
                    ),
                    operation: "commit"
                )
            }
        } else {
            try Self.check(
                git_commit_create(
                    &commitOID,
                    repository,
                    "HEAD",
                    signature,
                    signature,
                    nil,
                    message,
                    tree,
                    0,
                    nil
                ),
                operation: "initial commit"
            )
        }
        return true
    }

    func fetchOrigin(
        in repositoryURL: URL,
        configuredRemoteURL: URL,
        authentication: GitHTTPAuthentication
    ) throws {
        let repository = try Self.openRepository(at: repositoryURL)
        defer { git_repository_free(repository) }
        let configuredOrigin = try openOrigin(in: repository, configuredRemoteURL: configuredRemoteURL)
        defer { git_remote_free(configuredOrigin) }
        let fetchURL = try validatedOriginURLs(configuredOrigin, configuredRemoteURL: configuredRemoteURL).fetch

        // The operation remote is anonymous, skips all insteadOf rewriting,
        // and has no repository-controlled refspecs.
        let remote = try Self.createOperationRemote(in: repository, url: fetchURL, operation: "fetch remote setup")
        defer { git_remote_free(remote) }

        let context = try GitRemoteOperationContext(
            authentication: authentication,
            configuredRemoteURL: configuredRemoteURL,
            allowFileRemote: allowFileRemotesForTesting
        )
        var options = git_fetch_options()
        try Self.check(
            git_fetch_options_init(&options, UInt32(GIT_FETCH_OPTIONS_VERSION)),
            operation: "fetch setup"
        )
        options.callbacks = Self.callbacks(for: context)
        options.follow_redirects = GIT_REMOTE_REDIRECT_NONE
        options.download_tags = GIT_REMOTE_DOWNLOAD_TAGS_NONE
        let refspec = "+refs/heads/main:refs/remotes/origin/main"
        let result = refspec.withCString { pointer in
            var mutablePointer: UnsafeMutablePointer<CChar>? = UnsafeMutablePointer(mutating: pointer)
            return withUnsafeMutablePointer(to: &mutablePointer) { strings in
                var refspecs = git_strarray(strings: strings, count: 1)
                return git_remote_fetch(remote, &refspecs, &options, nil)
            }
        }
        try Self.check(result, operation: "fetch")
    }

    func pushMain(
        in repositoryURL: URL,
        configuredRemoteURL: URL,
        authentication: GitHTTPAuthentication
    ) throws {
        let repository = try Self.openRepository(at: repositoryURL)
        defer { git_repository_free(repository) }
        let configuredOrigin = try openOrigin(in: repository, configuredRemoteURL: configuredRemoteURL)
        defer { git_remote_free(configuredOrigin) }
        let pushURL = try validatedOriginURLs(configuredOrigin, configuredRemoteURL: configuredRemoteURL).push
        let remote = try Self.createOperationRemote(in: repository, url: pushURL, operation: "push remote setup")
        defer { git_remote_free(remote) }

        let context = try GitRemoteOperationContext(
            authentication: authentication,
            configuredRemoteURL: configuredRemoteURL,
            allowFileRemote: allowFileRemotesForTesting
        )
        var options = git_push_options()
        try Self.check(
            git_push_options_init(&options, UInt32(GIT_PUSH_OPTIONS_VERSION)),
            operation: "push setup"
        )
        options.callbacks = Self.callbacks(for: context)
        options.follow_redirects = GIT_REMOTE_REDIRECT_NONE

        let refspec = "refs/heads/main:refs/heads/main"
        let result = refspec.withCString { pointer in
            var mutablePointer: UnsafeMutablePointer<CChar>? = UnsafeMutablePointer(mutating: pointer)
            return withUnsafeMutablePointer(to: &mutablePointer) { strings in
                var refspecs = git_strarray(strings: strings, count: 1)
                return git_remote_push(remote, &refspecs, &options)
            }
        }
        try Self.check(result, operation: "push")
        if let rejection = context.pushRejection {
            throw GitRepositoryError.remoteRejected(rejection)
        }
    }

    /// Integrates `origin/main` without ever placing conflict markers in the
    /// live notes directory. A divergent merge is first computed in an
    /// in-memory index; conflicts are reported while HEAD and the worktree stay
    /// untouched. Clean updates use safe checkout and a compare-and-swap ref
    /// update so a concurrently changed HEAD cannot be overwritten.
    func integrateOriginMain(
        in repositoryURL: URL,
        expectedRemoteRevision: String?,
        authorName: String,
        authorEmail: String
    ) throws -> GitHeadIntegration {
        let repository = try Self.openRepository(at: repositoryURL)
        defer { git_repository_free(repository) }

        let localOID = try Self.optionalOID(named: "refs/heads/main", in: repository)
        let remoteOID = try Self.optionalOID(named: "refs/remotes/origin/main", in: repository)
        try Self.validateExactExpectedRevision(
            expectedRemoteRevision,
            actual: remoteOID,
            operation: "origin/main integration"
        )

        guard let remoteOID else { return .upToDate }
        guard let localOID else {
            let remoteCommit = try Self.lookupCommit(remoteOID, in: repository, operation: "origin/main lookup")
            defer { git_commit_free(remoteCommit) }
            try Self.checkoutAndReplaceMain(
                remoteCommit,
                in: repository,
                oldOID: nil,
                newOID: remoteOID,
                reflogMessage: "MiaoYan sync: create main"
            )
            return .fastForward
        }

        if Self.oidsEqual(localOID, remoteOID) {
            return .upToDate
        }

        var localValue = localOID.value
        var remoteValue = remoteOID.value
        let localContainsRemote = git_graph_descendant_of(repository, &localValue, &remoteValue)
        try Self.checkGraphResult(localContainsRemote, operation: "history comparison")
        if localContainsRemote == 1 {
            return .upToDate
        }

        let remoteContainsLocal = git_graph_descendant_of(repository, &remoteValue, &localValue)
        try Self.checkGraphResult(remoteContainsLocal, operation: "history comparison")
        if remoteContainsLocal == 1 {
            let remoteCommit = try Self.lookupCommit(remoteOID, in: repository, operation: "origin/main lookup")
            defer { git_commit_free(remoteCommit) }
            try Self.checkoutAndReplaceMain(
                remoteCommit,
                in: repository,
                oldOID: localOID,
                newOID: remoteOID,
                reflogMessage: "MiaoYan sync: fast-forward"
            )
            return .fastForward
        }

        let localCommit = try Self.lookupCommit(localOID, in: repository, operation: "local commit lookup")
        defer { git_commit_free(localCommit) }
        let remoteCommit = try Self.lookupCommit(remoteOID, in: repository, operation: "remote commit lookup")
        defer { git_commit_free(remoteCommit) }

        var mergeBase = git_oid()
        let mergeBaseResult = git_merge_base(&mergeBase, repository, &localValue, &remoteValue)
        if mergeBaseResult == GIT_ENOTFOUND.rawValue {
            throw GitRepositoryError.unrelatedHistories
        }
        try Self.check(mergeBaseResult, operation: "merge base lookup")

        var mergeIndex: OpaquePointer?
        try Self.check(
            git_merge_commits(&mergeIndex, repository, localCommit, remoteCommit, nil),
            operation: "merge analysis"
        )
        defer { git_index_free(mergeIndex) }

        if git_index_has_conflicts(mergeIndex) == 1 {
            return .conflicts(
                try Self.conflictFiles(
                    in: mergeIndex,
                    repository: repository,
                    localOID: localOID,
                    remoteOID: remoteOID
                )
            )
        }

        var treeOID = git_oid()
        try Self.check(
            git_index_write_tree_to(&treeOID, mergeIndex, repository),
            operation: "merge tree write"
        )
        var tree: OpaquePointer?
        try Self.check(git_tree_lookup(&tree, repository, &treeOID), operation: "merge tree lookup")
        defer { git_tree_free(tree) }

        var signature: UnsafeMutablePointer<git_signature>?
        try Self.check(
            git_signature_now(&signature, authorName, authorEmail),
            operation: "merge signature creation"
        )
        defer { git_signature_free(signature) }

        var mergeOID = git_oid()
        var parents: [OpaquePointer?] = [localCommit, remoteCommit]
        try parents.withUnsafeMutableBufferPointer { buffer in
            try Self.check(
                git_commit_create(
                    &mergeOID,
                    repository,
                    nil,
                    signature,
                    signature,
                    nil,
                    "Merge origin/main",
                    tree,
                    2,
                    buffer.baseAddress
                ),
                operation: "merge commit"
            )
        }

        let mergeCommit = try Self.lookupCommit(
            GitOID(value: mergeOID),
            in: repository,
            operation: "merge commit lookup"
        )
        defer { git_commit_free(mergeCommit) }
        try Self.checkoutAndReplaceMain(
            mergeCommit,
            in: repository,
            oldOID: localOID,
            newOID: GitOID(value: mergeOID),
            reflogMessage: "MiaoYan sync: merge origin/main"
        )
        return .mergeCommit
    }

    func replaceMainWithOrigin(
        in repositoryURL: URL,
        expectedLocalRevision: String? = nil,
        expectedRemoteRevision: String? = nil
    ) throws -> GitHeadIntegration {
        let repository = try Self.openRepository(at: repositoryURL)
        defer { git_repository_free(repository) }
        let localOID = try Self.optionalOID(named: "refs/heads/main", in: repository)
        guard let remoteOID = try Self.optionalOID(named: "refs/remotes/origin/main", in: repository) else {
            return .upToDate
        }
        try Self.validateExpectedRevision(expectedLocalRevision, actual: localOID, operation: "local history replacement")
        try Self.validateExpectedRevision(expectedRemoteRevision, actual: remoteOID, operation: "remote history replacement")
        if let localOID, Self.oidsEqual(localOID, remoteOID) { return .upToDate }

        let remoteCommit = try Self.lookupCommit(remoteOID, in: repository, operation: "origin/main replacement lookup")
        defer { git_commit_free(remoteCommit) }
        if let localOID {
            try Self.checkoutAndReplaceMain(
                remoteCommit,
                in: repository,
                oldOID: localOID,
                newOID: remoteOID,
                reflogMessage: "MiaoYan sync: replace local history from origin/main"
            )
        } else {
            try Self.checkoutAndReplaceMain(
                remoteCommit,
                in: repository,
                oldOID: nil,
                newOID: remoteOID,
                reflogMessage: "MiaoYan sync: create replacement main"
            )
        }
        return .fastForward
    }

    func resolveOriginMainConflicts(
        in repositoryURL: URL,
        context: GitSyncConflictContext,
        resolutions: [GitSyncConflictResolution],
        authorName: String,
        authorEmail: String
    ) throws -> GitHeadIntegration {
        let repository = try Self.openRepository(at: repositoryURL)
        defer { git_repository_free(repository) }
        guard let localOID = try Self.optionalOID(named: "refs/heads/main", in: repository),
            let remoteOID = try Self.optionalOID(named: "refs/remotes/origin/main", in: repository)
        else {
            throw GitRepositoryError.operationFailed(operation: "conflict resolution", message: "The expected branches no longer exist")
        }
        try Self.validateExpectedRevision(context.localRevision, actual: localOID, operation: "conflict resolution local revision")
        try Self.validateExpectedRevision(context.remoteRevision, actual: remoteOID, operation: "conflict resolution remote revision")

        let expectedPaths = Set(context.files.map(\.path))
        let choices = Dictionary(uniqueKeysWithValues: resolutions.map { ($0.path, $0.choice) })
        guard expectedPaths == Set(choices.keys) else {
            throw GitRepositoryError.operationFailed(operation: "conflict resolution", message: "Every conflict must have exactly one resolution")
        }

        let localCommit = try Self.lookupCommit(localOID, in: repository, operation: "conflict local lookup")
        defer { git_commit_free(localCommit) }
        let remoteCommit = try Self.lookupCommit(remoteOID, in: repository, operation: "conflict remote lookup")
        defer { git_commit_free(remoteCommit) }
        var index: OpaquePointer?
        try Self.check(git_merge_commits(&index, repository, localCommit, remoteCommit, nil), operation: "conflict merge recreation")
        defer { git_index_free(index) }

        for path in expectedPaths.sorted() {
            var ancestor: UnsafePointer<git_index_entry>?
            var ours: UnsafePointer<git_index_entry>?
            var theirs: UnsafePointer<git_index_entry>?
            try Self.check(git_index_conflict_get(&ancestor, &ours, &theirs, index, path), operation: "conflict entry lookup")
            guard let choice = choices[path] else { continue }
            switch choice {
            case .local:
                try Self.addResolvedEntry(ours, to: index)
            case .remote:
                try Self.addResolvedEntry(theirs, to: index)
            case .mergedText(let text):
                guard let template = ours ?? theirs ?? ancestor else {
                    throw GitRepositoryError.operationFailed(operation: "AI conflict resolution", message: "No file entry is available")
                }
                var blobOID = git_oid()
                let data = Data(text.utf8)
                try data.withUnsafeBytes { bytes in
                    try Self.check(
                        git_blob_create_frombuffer(&blobOID, repository, bytes.baseAddress, data.count),
                        operation: "AI resolution blob creation"
                    )
                }
                var entry = template.pointee
                entry.id = blobOID
                entry.flags &= ~UInt16(0x3000)
                try Self.check(git_index_add(index, &entry), operation: "AI resolution index update")
            }
            try Self.check(git_index_conflict_remove(index, path), operation: "conflict entry removal")
        }
        guard git_index_has_conflicts(index) == 0 else {
            throw GitRepositoryError.operationFailed(operation: "conflict resolution", message: "Unresolved conflict entries remain")
        }

        var treeOID = git_oid()
        try Self.check(git_index_write_tree_to(&treeOID, index, repository), operation: "resolved merge tree write")
        var tree: OpaquePointer?
        try Self.check(git_tree_lookup(&tree, repository, &treeOID), operation: "resolved merge tree lookup")
        defer { git_tree_free(tree) }
        var signature: UnsafeMutablePointer<git_signature>?
        try Self.check(git_signature_now(&signature, authorName, authorEmail), operation: "resolved merge signature")
        defer { git_signature_free(signature) }
        var mergeOID = git_oid()
        var parents: [OpaquePointer?] = [localCommit, remoteCommit]
        try parents.withUnsafeMutableBufferPointer { buffer in
            try Self.check(
                git_commit_create(
                    &mergeOID,
                    repository,
                    nil,
                    signature,
                    signature,
                    nil,
                    "Resolve origin/main conflicts",
                    tree,
                    2,
                    buffer.baseAddress
                ),
                operation: "resolved merge commit"
            )
        }
        let mergeCommit = try Self.lookupCommit(GitOID(value: mergeOID), in: repository, operation: "resolved merge commit lookup")
        defer { git_commit_free(mergeCommit) }
        try Self.checkoutAndReplaceMain(
            mergeCommit,
            in: repository,
            oldOID: localOID,
            newOID: GitOID(value: mergeOID),
            reflogMessage: "MiaoYan sync: resolve origin/main conflicts"
        )
        return .mergeCommit
    }

    private static func addResolvedEntry(_ source: UnsafePointer<git_index_entry>?, to index: OpaquePointer?) throws {
        guard let source else { return }
        var entry = source.pointee
        entry.flags &= ~UInt16(0x3000)
        try check(git_index_add(index, &entry), operation: "resolved conflict index update")
    }

    private static func validateExpectedRevision(_ expected: String?, actual: GitOID?, operation: String) throws {
        guard let expected else { return }
        guard var value = actual?.value, let pointer = git_oid_tostr_s(&value), String(cString: pointer) == expected else {
            throw GitRepositoryError.operationFailed(operation: operation, message: "Repository changed while waiting for a choice; run sync again")
        }
    }

    private static func validateExactExpectedRevision(_ expected: String?, actual: GitOID?, operation: String) throws {
        if expected == nil, actual == nil { return }
        guard let expected else {
            throw GitRepositoryError.operationFailed(operation: operation, message: "Repository changed after incoming validation; run sync again")
        }
        guard var value = actual?.value,
            let pointer = git_oid_tostr_s(&value),
            String(cString: pointer) == expected
        else {
            throw GitRepositoryError.operationFailed(operation: operation, message: "Repository changed after incoming validation; run sync again")
        }
    }

    private static func revisionString(for oid: GitOID, operation: String) throws -> String {
        var value = oid.value
        guard let pointer = git_oid_tostr_s(&value) else {
            throw GitRepositoryError.operationFailed(operation: operation, message: "Revision could not be encoded")
        }
        return String(cString: pointer)
    }

    private static func validate(remoteURL: URL, allowFile: Bool = false) throws {
        let scheme = remoteURL.scheme?.lowercased()
        if allowFile, scheme == "file", remoteURL.isFileURL { return }
        guard scheme == "https",
            remoteURL.host != nil,
            remoteURL.user == nil,
            remoteURL.password == nil,
            remoteURL.query == nil,
            remoteURL.fragment == nil
        else {
            throw GitRepositoryError.invalidRemoteURL
        }
    }

    private static func validatedRelativePath(_ path: String) throws -> String {
        let standardized = NSString(string: path).standardizingPath
        guard !path.isEmpty,
            !path.hasPrefix("/"),
            standardized != ".",
            standardized != "..",
            !standardized.hasPrefix("../"),
            !standardized.contains("/../"),
            !standardized.hasPrefix(".git/"),
            standardized != ".git"
        else {
            throw GitRepositoryError.invalidRelativePath(path)
        }
        return standardized
    }

    private static func openRepository(at url: URL) throws -> OpaquePointer {
        var repository: OpaquePointer?
        try check(git_repository_open(&repository, url.path), operation: "repository open")
        guard let repository else {
            throw GitRepositoryError.operationFailed(operation: "repository open", message: "No repository returned")
        }
        return repository
    }

    private func openOrigin(in repository: OpaquePointer, configuredRemoteURL: URL) throws -> OpaquePointer {
        var remote: OpaquePointer?
        try Self.check(git_remote_lookup(&remote, repository, "origin"), operation: "origin lookup")
        guard let remote else {
            throw GitRepositoryError.operationFailed(operation: "origin lookup", message: "No remote returned")
        }
        do {
            _ = try validatedOriginURLs(remote, configuredRemoteURL: configuredRemoteURL)
        } catch {
            git_remote_free(remote)
            throw error
        }
        return remote
    }

    private struct ValidatedRemoteURLs {
        let fetch: URL
        let push: URL
    }

    private func validatedOriginURLs(_ remote: OpaquePointer?, configuredRemoteURL: URL) throws -> ValidatedRemoteURLs {
        try Self.validate(remoteURL: configuredRemoteURL, allowFile: allowFileRemotesForTesting)
        guard let urlPointer = git_remote_url(remote),
            let url = URL(string: String(cString: urlPointer))
        else {
            throw GitRepositoryError.invalidRemoteURL
        }
        try Self.validate(remoteURL: url, allowFile: allowFileRemotesForTesting)
        let configuredKey = try Self.remoteIdentity(configuredRemoteURL, allowFile: allowFileRemotesForTesting)
        let fetchKey = try Self.remoteIdentity(url, allowFile: allowFileRemotesForTesting)
        guard fetchKey == configuredKey else {
            throw GitRepositoryError.operationFailed(
                operation: "remote validation",
                message: "origin fetch URL does not match the configured repository"
            )
        }

        let pushURL: URL
        if let pushURLPointer = git_remote_pushurl(remote) {
            guard let parsedPushURL = URL(string: String(cString: pushURLPointer)) else {
                throw GitRepositoryError.invalidRemoteURL
            }
            pushURL = parsedPushURL
        } else {
            pushURL = url
        }
        try Self.validate(remoteURL: pushURL, allowFile: allowFileRemotesForTesting)
        let pushKey = try Self.remoteIdentity(pushURL, allowFile: allowFileRemotesForTesting)
        guard pushKey == configuredKey else {
            throw GitRepositoryError.operationFailed(
                operation: "remote validation",
                message: "origin push URL does not match the configured repository"
            )
        }
        return ValidatedRemoteURLs(fetch: url, push: pushURL)
    }

    private static func remoteIdentity(_ url: URL, allowFile: Bool) throws -> String {
        if allowFile, url.isFileURL {
            return url.standardizedFileURL.resolvingSymlinksInPath().path
        }
        return try GitCredentialStore.normalizedRemoteURL(url)
    }

    private static func createOperationRemote(
        in repository: OpaquePointer,
        url: URL,
        operation: String
    ) throws -> OpaquePointer {
        var options = git_remote_create_options()
        try check(
            git_remote_create_options_init(&options, UInt32(GIT_REMOTE_CREATE_OPTIONS_VERSION)),
            operation: operation
        )
        options.repository = repository
        options.flags =
            UInt32(GIT_REMOTE_CREATE_SKIP_INSTEADOF.rawValue)
            | UInt32(GIT_REMOTE_CREATE_SKIP_DEFAULT_FETCHSPEC.rawValue)

        var remote: OpaquePointer?
        try check(git_remote_create_with_opts(&remote, url.absoluteString, &options), operation: operation)
        guard let remote else {
            throw GitRepositoryError.operationFailed(operation: operation, message: "No remote returned")
        }
        return remote
    }

    private static func optionalOID(named referenceName: String, in repository: OpaquePointer) throws -> GitOID? {
        var oid = git_oid()
        let result = git_reference_name_to_id(&oid, repository, referenceName)
        if result == GIT_ENOTFOUND.rawValue || result == GIT_EUNBORNBRANCH.rawValue {
            return nil
        }
        try check(result, operation: "\(referenceName) lookup")
        return GitOID(value: oid)
    }

    private static func lookupCommit(
        _ oid: GitOID,
        in repository: OpaquePointer,
        operation: String
    ) throws -> OpaquePointer {
        var value = oid.value
        var commit: OpaquePointer?
        try check(git_commit_lookup(&commit, repository, &value), operation: operation)
        guard let commit else {
            throw GitRepositoryError.operationFailed(operation: operation, message: "No commit returned")
        }
        return commit
    }

    private static func checkout(
        _ treeish: OpaquePointer,
        in repository: OpaquePointer,
        strategy: UInt32 = UInt32(GIT_CHECKOUT_SAFE.rawValue),
        baseline: OpaquePointer? = nil
    ) throws {
        var options = git_checkout_options()
        try check(
            git_checkout_options_init(&options, UInt32(GIT_CHECKOUT_OPTIONS_VERSION)),
            operation: "checkout setup"
        )
        options.checkout_strategy = strategy
        options.baseline = baseline
        try check(git_checkout_tree(repository, treeish, &options), operation: "checkout")
    }

    private static func checkoutAndReplaceMain(
        _ commit: OpaquePointer,
        in repository: OpaquePointer,
        oldOID: GitOID?,
        newOID: GitOID,
        reflogMessage: String,
        checkoutStrategy: UInt32 = UInt32(GIT_CHECKOUT_SAFE.rawValue)
    ) throws {
        var transaction: OpaquePointer?
        try check(git_transaction_new(&transaction, repository), operation: "main update transaction setup")
        defer { git_transaction_free(transaction) }
        try check(
            git_transaction_lock_ref(transaction, "refs/heads/main"),
            operation: "main update transaction lock"
        )
        try check(
            git_transaction_lock_ref(transaction, "HEAD"),
            operation: "HEAD update transaction lock"
        )
        var headReference: OpaquePointer?
        try check(git_reference_lookup(&headReference, repository, "HEAD"), operation: "HEAD validation")
        defer { git_reference_free(headReference) }
        guard let headTarget = git_reference_symbolic_target(headReference),
            String(cString: headTarget) == "refs/heads/main"
        else {
            throw GitRepositoryError.operationFailed(
                operation: "atomic main update",
                message: "HEAD changed before checkout; run sync again"
            )
        }
        guard optionalOIDsEqual(try optionalOID(named: "refs/heads/main", in: repository), oldOID) else {
            throw GitRepositoryError.operationFailed(
                operation: "atomic main update",
                message: "Repository changed before checkout; run sync again"
            )
        }

        var previousIndex: OpaquePointer?
        try check(git_repository_index(&previousIndex, repository), operation: "checkout rollback index open")
        defer { git_index_free(previousIndex) }
        var previousTreeOID = git_oid()
        try check(git_index_write_tree(&previousTreeOID, previousIndex), operation: "checkout rollback tree write")
        var previousTree: OpaquePointer?
        try check(git_tree_lookup(&previousTree, repository, &previousTreeOID), operation: "checkout rollback tree lookup")
        defer { git_tree_free(previousTree) }
        guard let previousTree else {
            throw GitRepositoryError.operationFailed(operation: "checkout rollback tree lookup", message: "No tree returned")
        }

        var targetTree: OpaquePointer?
        try check(git_commit_tree(&targetTree, commit), operation: "checkout target tree lookup")
        defer { git_tree_free(targetTree) }
        guard let targetTree else {
            throw GitRepositoryError.operationFailed(operation: "checkout target tree lookup", message: "No tree returned")
        }

        let dryRunStrategy = checkoutStrategy | UInt32(GIT_CHECKOUT_DRY_RUN.rawValue)
        try checkout(commit, in: repository, strategy: dryRunStrategy)
        do {
            try checkout(commit, in: repository, strategy: checkoutStrategy)
            var newValue = newOID.value
            try check(
                git_transaction_set_target(
                    transaction,
                    "refs/heads/main",
                    &newValue,
                    nil,
                    reflogMessage
                ),
                operation: "main update transaction stage"
            )
            try check(
                git_transaction_set_symbolic_target(
                    transaction,
                    "HEAD",
                    "refs/heads/main",
                    nil,
                    reflogMessage
                ),
                operation: "HEAD update transaction stage"
            )
            try check(git_transaction_commit(transaction), operation: "main update transaction commit")
        } catch {
            let updateError = error
            do {
                // The pre-checkout index tree also exists for an unborn main.
                // Using the attempted target as the baseline lets SAFE checkout
                // reverse completed writes while refusing to overwrite any file
                // that an external process changed during the operation.
                try checkout(previousTree, in: repository, strategy: checkoutStrategy, baseline: targetTree)
            } catch let rollbackError {
                throw GitRepositoryError.operationFailed(
                    operation: "checkout rollback",
                    message: "\(updateError.localizedDescription); rollback failed: \(rollbackError.localizedDescription)"
                )
            }
            throw updateError
        }
    }

    private static func conflictFiles(
        in index: OpaquePointer?,
        repository: OpaquePointer,
        localOID: GitOID,
        remoteOID: GitOID
    ) throws -> [GitSyncConflictFile] {
        var iterator: OpaquePointer?
        try check(git_index_conflict_iterator_new(&iterator, index), operation: "conflict scan")
        defer { git_index_conflict_iterator_free(iterator) }

        var files = [GitSyncConflictFile]()
        while true {
            var ancestor: UnsafePointer<git_index_entry>?
            var ours: UnsafePointer<git_index_entry>?
            var theirs: UnsafePointer<git_index_entry>?
            let result = git_index_conflict_next(&ancestor, &ours, &theirs, iterator)
            if result == GIT_ITEROVER.rawValue { break }
            try check(result, operation: "conflict scan")
            guard let pathPointer = ours?.pointee.path ?? theirs?.pointee.path ?? ancestor?.pointee.path else { continue }
            let path = String(cString: pathPointer)
            files.append(
                GitSyncConflictFile(
                    path: path,
                    localModifiedAt: try lastChangeDate(for: path, startingAt: localOID, repository: repository),
                    remoteModifiedAt: try lastChangeDate(for: path, startingAt: remoteOID, repository: repository),
                    ancestorText: try blobText(for: ancestor, repository: repository),
                    localText: try blobText(for: ours, repository: repository),
                    remoteText: try blobText(for: theirs, repository: repository),
                    localExists: ours != nil,
                    remoteExists: theirs != nil
                )
            )
        }
        return files.sorted { $0.path < $1.path }
    }

    private static func blobText(
        for entry: UnsafePointer<git_index_entry>?,
        repository: OpaquePointer,
        maximumBytes: Int = 1024 * 1024
    ) throws -> String? {
        guard let entry else { return nil }
        var oid = entry.pointee.id
        var blob: OpaquePointer?
        try check(git_blob_lookup(&blob, repository, &oid), operation: "conflict blob lookup")
        defer { git_blob_free(blob) }
        let size = git_blob_rawsize(blob)
        guard size <= maximumBytes, let raw = git_blob_rawcontent(blob) else { return nil }
        let data = Data(bytes: raw, count: Int(size))
        guard !data.contains(0) else { return nil }
        return String(data: data, encoding: .utf8)
    }

    private static func lastChangeDate(for path: String, startingAt oid: GitOID, repository: OpaquePointer) throws -> Date? {
        var current = try lookupCommit(oid, in: repository, operation: "conflict timestamp lookup")
        defer { git_commit_free(current) }

        while true {
            let currentEntry = try treeEntryOID(for: path, commit: current)
            guard git_commit_parentcount(current) > 0 else {
                return currentEntry == nil ? nil : Date(timeIntervalSince1970: TimeInterval(git_commit_time(current)))
            }
            var parent: OpaquePointer?
            try check(git_commit_parent(&parent, current, 0), operation: "conflict timestamp parent lookup")
            guard let parent else { return nil }
            let parentEntry = try treeEntryOID(for: path, commit: parent)
            if !optionalOIDsEqual(currentEntry, parentEntry) {
                git_commit_free(parent)
                return Date(timeIntervalSince1970: TimeInterval(git_commit_time(current)))
            }
            git_commit_free(current)
            current = parent
        }
    }

    private static func treeEntryOID(for path: String, commit: OpaquePointer) throws -> GitOID? {
        var tree: OpaquePointer?
        try check(git_commit_tree(&tree, commit), operation: "conflict timestamp tree lookup")
        defer { git_tree_free(tree) }
        var entry: OpaquePointer?
        let result = git_tree_entry_bypath(&entry, tree, path)
        if result == GIT_ENOTFOUND.rawValue { return nil }
        try check(result, operation: "conflict timestamp path lookup")
        defer { git_tree_entry_free(entry) }
        guard let pointer = git_tree_entry_id(entry) else { return nil }
        return GitOID(value: pointer.pointee)
    }

    private static func optionalOIDsEqual(_ lhs: GitOID?, _ rhs: GitOID?) -> Bool {
        switch (lhs, rhs) {
        case (.none, .none): return true
        case (.some(let lhs), .some(let rhs)): return oidsEqual(lhs, rhs)
        default: return false
        }
    }

    private static func syncChanges(in diff: OpaquePointer?, repository: OpaquePointer) throws -> [GitSyncChange] {
        var changes = [GitSyncChange]()
        for position in 0..<git_diff_num_deltas(diff) {
            guard let delta = git_diff_get_delta(diff, position)?.pointee else { continue }
            if let change = try syncChange(from: delta, repository: repository) {
                changes.append(change)
            }
        }
        return changes
    }

    private static func syncChange(from delta: git_diff_delta, repository: OpaquePointer) throws -> GitSyncChange? {
        let oldPath = delta.old_file.path.map(String.init(cString:))
        let newPath = delta.new_file.path.map(String.init(cString:))
        let kind: GitSyncChangeKind
        let path: String
        let previousPath: String?

        switch delta.status {
        case GIT_DELTA_ADDED, GIT_DELTA_UNTRACKED:
            guard let newPath else { return nil }
            kind = .added
            path = newPath
            previousPath = nil
        case GIT_DELTA_DELETED:
            guard let oldPath else { return nil }
            kind = .deleted
            path = oldPath
            previousPath = nil
        case GIT_DELTA_RENAMED:
            guard let newPath else { return nil }
            kind = .renamed
            path = newPath
            previousPath = oldPath
        default:
            guard let newPath = newPath ?? oldPath else { return nil }
            kind = .modified
            path = newPath
            previousPath = nil
        }

        let mode = kind == .deleted ? delta.old_file.mode : delta.new_file.mode
        let entryKind: GitSyncEntryKind
        if mode == GIT_FILEMODE_LINK.rawValue {
            entryKind = .symbolicLink
        } else if mode == GIT_FILEMODE_COMMIT.rawValue {
            entryKind = .submodule
        } else {
            entryKind = .regularFile
        }
        let byteCount: Int64?
        if kind != .deleted, entryKind != .submodule {
            let flags = delta.new_file.flags
            if flags & UInt32(GIT_DIFF_FLAG_VALID_SIZE.rawValue) != 0 {
                let rawSize = delta.new_file.size
                byteCount = rawSize > UInt64(Int64.max) ? Int64.max : Int64(rawSize)
            } else if flags & UInt32(GIT_DIFF_FLAG_VALID_ID.rawValue) != 0 {
                var oid = delta.new_file.id
                var blob: OpaquePointer?
                try check(git_blob_lookup(&blob, repository, &oid), operation: "changed blob lookup")
                defer { git_blob_free(blob) }
                let rawSize = git_blob_rawsize(blob)
                byteCount = rawSize > UInt64(Int64.max) ? Int64.max : Int64(rawSize)
            } else {
                byteCount = nil
            }
        } else {
            byteCount = nil
        }
        return GitSyncChange(
            kind: kind,
            path: path,
            previousPath: previousPath,
            entryKind: entryKind,
            byteCount: byteCount
        )
    }

    private static func oidsEqual(_ lhs: GitOID, _ rhs: GitOID) -> Bool {
        var left = lhs.value
        var right = rhs.value
        return git_oid_equal(&left, &right) == 1
    }

    private static func checkGraphResult(_ status: Int32, operation: String) throws {
        if status < 0 {
            try check(status, operation: operation)
        }
    }

    private static func callbacks(for context: GitRemoteOperationContext) -> git_remote_callbacks {
        var callbacks = git_remote_callbacks()
        git_remote_init_callbacks(&callbacks, UInt32(GIT_REMOTE_CALLBACKS_VERSION))
        callbacks.credentials = miaoyanGitCredentialCallback
        callbacks.push_update_reference = miaoyanGitPushUpdateReferenceCallback
        callbacks.payload = Unmanaged.passUnretained(context).toOpaque()
        // Intentionally leave certificate_check nil. libgit2 then enforces the
        // platform trust result instead of allowing the app to bypass TLS.
        return callbacks
    }

    private static func check(_ status: Int32, operation: String) throws {
        guard status >= 0 else {
            let message: String
            if let error = git_error_last(), let cMessage = error.pointee.message {
                message = String(cString: cMessage)
            } else {
                message = "libgit2 error \(status)"
            }
            throw GitRepositoryError.operationFailed(operation: operation, message: message)
        }
    }
}

private struct GitOID {
    var value: git_oid
}

struct GitRemoteCredentialScope: Equatable, Sendable {
    private let host: String
    private let port: Int

    init(configuredRemoteURL: URL) throws {
        let normalized = try GitCredentialStore.normalizedRemoteURL(configuredRemoteURL)
        guard let url = URL(string: normalized), let host = url.host?.lowercased() else {
            throw GitRepositoryError.invalidRemoteURL
        }
        self.host = host
        port = url.port ?? 443
    }

    func allowsCredentials(for challengedURL: String) -> Bool {
        guard let url = URL(string: challengedURL) else { return false }
        return url.scheme?.lowercased() == "https"
            && url.host?.lowercased() == host
            && (url.port ?? 443) == port
            && url.user == nil
            && url.password == nil
    }
}

private final class GitRemoteOperationContext: @unchecked Sendable {
    let authentication: GitHTTPAuthentication
    private let credentialScope: GitRemoteCredentialScope?
    private let lock = NSLock()
    private var storedPushRejection: String?

    var pushRejection: String? {
        lock.lock()
        defer { lock.unlock() }
        return storedPushRejection
    }

    init(
        authentication: GitHTTPAuthentication,
        configuredRemoteURL: URL,
        allowFileRemote: Bool
    ) throws {
        self.authentication = authentication
        if configuredRemoteURL.isFileURL && allowFileRemote {
            credentialScope = nil
        } else {
            credentialScope = try GitRemoteCredentialScope(configuredRemoteURL: configuredRemoteURL)
        }
    }

    func allowsCredentials(for challengedURL: String) -> Bool {
        credentialScope?.allowsCredentials(for: challengedURL) == true
    }

    func recordPushRejection(_ message: String) {
        lock.lock()
        storedPushRejection = message
        lock.unlock()
    }
}

private let miaoyanGitCredentialCallback: git_credential_acquire_cb = {
    credential, url, usernameFromURL, allowedTypes, payload in
    guard let credential, let url, let payload else {
        return GIT_EUSER.rawValue
    }
    let userpass = UInt32(GIT_CREDENTIAL_USERPASS_PLAINTEXT.rawValue)
    guard allowedTypes & userpass != 0 else {
        return GIT_PASSTHROUGH.rawValue
    }

    let context = Unmanaged<GitRemoteOperationContext>.fromOpaque(payload).takeUnretainedValue()
    guard context.allowsCredentials(for: String(cString: url)) else {
        return GIT_EUSER.rawValue
    }
    let username =
        context.authentication.username.isEmpty
        ? usernameFromURL.map(String.init(cString:)) ?? "git"
        : context.authentication.username
    return git_credential_userpass_plaintext_new(
        credential,
        username,
        context.authentication.token
    )
}

private let miaoyanGitPushUpdateReferenceCallback: git_push_update_reference_cb = {
    _, status, payload in
    guard let status, let payload else { return 0 }
    let context = Unmanaged<GitRemoteOperationContext>.fromOpaque(payload).takeUnretainedValue()
    context.recordPushRejection(String(cString: status))
    return 0
}
