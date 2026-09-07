import Foundation
import XCTest
import CLibGit2

@testable import MiaoYan

final class GitRepositoryClientTests: XCTestCase {
    private var temporaryDirectory: URL!

    override func setUpWithError() throws {
        temporaryDirectory = FileManager.default.temporaryDirectory
            .appendingPathComponent("MiaoYanGitRepositoryClientTests-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: temporaryDirectory, withIntermediateDirectories: true)
    }

    override func tearDownWithError() throws {
        try? FileManager.default.removeItem(at: temporaryDirectory)
    }

    func testCommitFetchMergePushAndConflictWithoutOverwritingWorktree() async throws {
        git_libgit2_init()
        defer { git_libgit2_shutdown() }

        let remoteURL = temporaryDirectory.appendingPathComponent("remote.git", isDirectory: true)
        var bareRepository: OpaquePointer?
        XCTAssertEqual(git_repository_init(&bareRepository, remoteURL.path, 1), 0)
        git_repository_free(bareRepository)

        let firstURL = temporaryDirectory.appendingPathComponent("first", isDirectory: true)
        try FileManager.default.createDirectory(at: firstURL, withIntermediateDirectories: true)
        let firstClient = GitRepositoryClient(allowFileRemotesForTesting: true)
        let authentication = GitHTTPAuthentication(username: "", token: "")
        try await firstClient.prepareRepository(at: firstURL, remoteURL: remoteURL)
        try "initial\n".write(to: firstURL.appendingPathComponent("note.md"), atomically: true, encoding: .utf8)
        try await firstClient.stage(relativePaths: ["note.md"], in: firstURL)
        let initialCommitted = try await firstClient.commitIndex(
            in: firstURL,
            message: "Initial",
            authorName: "MiaoYan Tests",
            authorEmail: "tests@localhost"
        )
        XCTAssertTrue(initialCommitted)
        try await firstClient.pushMain(in: firstURL, configuredRemoteURL: remoteURL, authentication: authentication)

        // An empty local library adopts an existing origin/main without
        // creating an unrelated empty root commit.
        let emptyURL = temporaryDirectory.appendingPathComponent("empty-adopter", isDirectory: true)
        try FileManager.default.createDirectory(at: emptyURL, withIntermediateDirectories: true)
        let emptyClient = GitRepositoryClient(allowFileRemotesForTesting: true)
        try await emptyClient.prepareRepository(at: emptyURL, remoteURL: remoteURL)
        let emptyCommit = try await commit(emptyClient, at: emptyURL, message: "Must stay unborn")
        XCTAssertFalse(emptyCommit)
        try await emptyClient.fetchOrigin(in: emptyURL, configuredRemoteURL: remoteURL, authentication: authentication)
        let adopted = try await emptyClient.integrateOriginMain(
            in: emptyURL,
            authorName: "MiaoYan Tests",
            authorEmail: "tests@localhost"
        )
        XCTAssertEqual(adopted, .fastForward)
        XCTAssertEqual(try String(contentsOf: emptyURL.appendingPathComponent("note.md")), "initial\n")

        let secondURL = temporaryDirectory.appendingPathComponent("second", isDirectory: true)
        let secondClient = GitRepositoryClient(allowFileRemotesForTesting: true)
        try await secondClient.clone(
            remoteURL: remoteURL,
            to: secondURL,
            authentication: authentication
        )

        // A remote-only commit fast-forwards the first worktree.
        try "remote update\n".write(to: secondURL.appendingPathComponent("note.md"), atomically: true, encoding: .utf8)
        let attachmentDirectory = secondURL.appendingPathComponent("i", isDirectory: true)
        try FileManager.default.createDirectory(at: attachmentDirectory, withIntermediateDirectories: true)
        try Data(repeating: 7, count: 11).write(to: attachmentDirectory.appendingPathComponent("payload.bin"))
        try await secondClient.stage(relativePaths: ["note.md", "i/payload.bin"], in: secondURL)
        let remoteUpdateCommitted = try await commit(secondClient, at: secondURL, message: "Remote update")
        XCTAssertTrue(remoteUpdateCommitted)
        try await secondClient.pushMain(in: secondURL, configuredRemoteURL: remoteURL, authentication: authentication)
        try await firstClient.fetchOrigin(in: firstURL, configuredRemoteURL: remoteURL, authentication: authentication)
        let incoming = try await firstClient.incomingChangesFromOriginMain(in: firstURL)
        XCTAssertEqual(incoming.first(where: { $0.path == "i/payload.bin" })?.byteCount, 11)
        let fastForward = try await firstClient.integrateOriginMain(
            in: firstURL,
            authorName: "MiaoYan Tests",
            authorEmail: "tests@localhost"
        )
        XCTAssertEqual(fastForward, .fastForward)
        XCTAssertEqual(try String(contentsOf: firstURL.appendingPathComponent("note.md")), "remote update\n")

        // Different files on both sides produce a normal two-parent merge.
        try "local\n".write(to: firstURL.appendingPathComponent("local.md"), atomically: true, encoding: .utf8)
        try await firstClient.stage(relativePaths: ["local.md"], in: firstURL)
        let localFileCommitted = try await commit(firstClient, at: firstURL, message: "Local file")
        XCTAssertTrue(localFileCommitted)
        try "remote\n".write(to: secondURL.appendingPathComponent("remote.md"), atomically: true, encoding: .utf8)
        try await secondClient.stage(relativePaths: ["remote.md"], in: secondURL)
        let remoteFileCommitted = try await commit(secondClient, at: secondURL, message: "Remote file")
        XCTAssertTrue(remoteFileCommitted)
        try await secondClient.pushMain(in: secondURL, configuredRemoteURL: remoteURL, authentication: authentication)
        try await firstClient.fetchOrigin(in: firstURL, configuredRemoteURL: remoteURL, authentication: authentication)
        let revisionBeforeMerge = try await firstClient.headRevision(in: firstURL)
        let merge = try await firstClient.integrateOriginMain(
            in: firstURL,
            authorName: "MiaoYan Tests",
            authorEmail: "tests@localhost"
        )
        XCTAssertEqual(merge, .mergeCommit)
        XCTAssertTrue(FileManager.default.fileExists(atPath: firstURL.appendingPathComponent("local.md").path))
        XCTAssertTrue(FileManager.default.fileExists(atPath: firstURL.appendingPathComponent("remote.md").path))
        let appliedMergeChanges = try await firstClient.changesAppliedSince(revisionBeforeMerge, in: firstURL)
        XCTAssertEqual(appliedMergeChanges.map(\.path), ["remote.md"])
        try await firstClient.pushMain(in: firstURL, configuredRemoteURL: remoteURL, authentication: authentication)

        // Bring the second worktree forward, then edit the same note on both
        // sides. Conflict analysis must leave first/note.md untouched.
        try await secondClient.fetchOrigin(in: secondURL, configuredRemoteURL: remoteURL, authentication: authentication)
        _ = try await secondClient.integrateOriginMain(
            in: secondURL,
            authorName: "MiaoYan Tests",
            authorEmail: "tests@localhost"
        )
        try "local conflict\n".write(to: firstURL.appendingPathComponent("note.md"), atomically: true, encoding: .utf8)
        try await firstClient.stage(relativePaths: ["note.md"], in: firstURL)
        let localConflictCommitted = try await commit(firstClient, at: firstURL, message: "Local conflict")
        XCTAssertTrue(localConflictCommitted)
        try "remote conflict\n".write(to: secondURL.appendingPathComponent("note.md"), atomically: true, encoding: .utf8)
        try await secondClient.stage(relativePaths: ["note.md"], in: secondURL)
        let remoteConflictCommitted = try await commit(secondClient, at: secondURL, message: "Remote conflict")
        XCTAssertTrue(remoteConflictCommitted)
        try await secondClient.pushMain(in: secondURL, configuredRemoteURL: remoteURL, authentication: authentication)
        try await firstClient.fetchOrigin(in: firstURL, configuredRemoteURL: remoteURL, authentication: authentication)

        let conflict = try await firstClient.integrateOriginMain(
            in: firstURL,
            authorName: "MiaoYan Tests",
            authorEmail: "tests@localhost"
        )
        guard case .conflicts(let files) = conflict else {
            return XCTFail("Expected a conflict, got \(conflict)")
        }
        XCTAssertEqual(files.map(\.path), ["note.md"])
        XCTAssertEqual(files.first?.localText, "local conflict\n")
        XCTAssertEqual(files.first?.remoteText, "remote conflict\n")
        XCTAssertNotNil(files.first?.localModifiedAt)
        XCTAssertNotNil(files.first?.remoteModifiedAt)
        XCTAssertEqual(try String(contentsOf: firstURL.appendingPathComponent("note.md")), "local conflict\n")

        let currentLocalRevision = try await firstClient.headRevision(in: firstURL)
        let currentRemoteRevision = try await firstClient.originMainRevision(in: firstURL)
        let localRevision = try XCTUnwrap(currentLocalRevision)
        let remoteRevision = try XCTUnwrap(currentRemoteRevision)
        let resolved = try await firstClient.resolveOriginMainConflicts(
            in: firstURL,
            context: GitSyncConflictContext(
                localRevision: localRevision,
                remoteRevision: remoteRevision,
                files: files,
                recoveryRevision: localRevision
            ),
            resolutions: [.init(path: "note.md", choice: .remote)],
            authorName: "MiaoYan Tests",
            authorEmail: "tests@localhost"
        )
        XCTAssertEqual(resolved, .mergeCommit)
        XCTAssertEqual(try String(contentsOf: firstURL.appendingPathComponent("note.md")), "remote conflict\n")
        try await firstClient.pushMain(in: firstURL, configuredRemoteURL: remoteURL, authentication: authentication)
    }

    func testProductionClientRejectsNonHTTPSRemote() async throws {
        let client = GitRepositoryClient()
        do {
            try await client.prepareRepository(at: temporaryDirectory, remoteURL: temporaryDirectory)
            XCTFail("A production client must reject file remotes")
        } catch let error as GitRepositoryError {
            XCTAssertEqual(error, .invalidRemoteURL)
        }
    }

    func testWorktreeChangesUseFilesystemSizeWithoutRequiringBlobOID() async throws {
        let repositoryURL = temporaryDirectory.appendingPathComponent("worktree", isDirectory: true)
        try FileManager.default.createDirectory(at: repositoryURL, withIntermediateDirectories: true)
        let client = GitRepositoryClient(allowFileRemotesForTesting: true)
        try await client.prepareRepository(
            at: repositoryURL,
            remoteURL: temporaryDirectory.appendingPathComponent("unused-remote.git", isDirectory: true)
        )

        let attachmentDirectory = repositoryURL.appendingPathComponent("i", isDirectory: true)
        try FileManager.default.createDirectory(at: attachmentDirectory, withIntermediateDirectories: true)
        try Data(repeating: 42, count: 37).write(to: attachmentDirectory.appendingPathComponent("untracked.bin"))

        let changes = try await client.worktreeChanges(in: repositoryURL)

        XCTAssertEqual(changes.first(where: { $0.path == "i/untracked.bin" })?.byteCount, 37)
    }

    func testPushRejectsAConfiguredPushURLThatDiffersFromOrigin() async throws {
        let repositoryURL = temporaryDirectory.appendingPathComponent("push-url-worktree", isDirectory: true)
        let remoteURL = temporaryDirectory.appendingPathComponent("push-url-origin.git", isDirectory: true)
        let attackerURL = temporaryDirectory.appendingPathComponent("push-url-attacker.git", isDirectory: true)
        try FileManager.default.createDirectory(at: repositoryURL, withIntermediateDirectories: true)
        let client = GitRepositoryClient(allowFileRemotesForTesting: true)
        try await client.prepareRepository(at: repositoryURL, remoteURL: remoteURL)

        var repository: OpaquePointer?
        XCTAssertEqual(git_repository_open(&repository, repositoryURL.path), 0)
        defer { git_repository_free(repository) }
        XCTAssertEqual(git_remote_set_pushurl(repository, "origin", attackerURL.absoluteString), 0)

        do {
            try await client.pushMain(
                in: repositoryURL,
                configuredRemoteURL: remoteURL,
                authentication: GitHTTPAuthentication(username: "octocat", token: "secret")
            )
            XCTFail("A different push URL must be rejected before credentials or repository data are sent")
        } catch let error as GitRepositoryError {
            guard case .operationFailed(let operation, _) = error else {
                return XCTFail("Unexpected error: \(error)")
            }
            XCTAssertEqual(operation, "remote validation")
        }
    }

    func testExistingRepositoryRejectsChangedFetchURLAgainstConfiguredRemote() async throws {
        let repositoryURL = temporaryDirectory.appendingPathComponent("fetch-url-worktree", isDirectory: true)
        let configuredURL = temporaryDirectory.appendingPathComponent("fetch-url-origin.git", isDirectory: true)
        let attackerURL = temporaryDirectory.appendingPathComponent("fetch-url-attacker.git", isDirectory: true)
        try FileManager.default.createDirectory(at: repositoryURL, withIntermediateDirectories: true)
        let client = GitRepositoryClient(allowFileRemotesForTesting: true)
        try await client.prepareRepository(at: repositoryURL, remoteURL: configuredURL)

        var repository: OpaquePointer?
        XCTAssertEqual(git_repository_open(&repository, repositoryURL.path), 0)
        defer { git_repository_free(repository) }
        XCTAssertEqual(git_remote_set_url(repository, "origin", attackerURL.absoluteString), 0)

        do {
            try await client.fetchOrigin(
                in: repositoryURL,
                configuredRemoteURL: configuredURL,
                authentication: GitHTTPAuthentication(username: "octocat", token: "secret")
            )
            XCTFail("A changed fetch URL must be rejected before credentials are requested")
        } catch let error as GitRepositoryError {
            guard case .operationFailed(let operation, _) = error else {
                return XCTFail("Unexpected error: \(error)")
            }
            XCTAssertEqual(operation, "remote validation")
        }
    }

    func testPrepareExistingRepositoryRejectsForeignPushURL() async throws {
        let repositoryURL = temporaryDirectory.appendingPathComponent("prepare-push-worktree", isDirectory: true)
        let configuredURL = temporaryDirectory.appendingPathComponent("prepare-push-origin.git", isDirectory: true)
        let attackerURL = temporaryDirectory.appendingPathComponent("prepare-push-attacker.git", isDirectory: true)
        try FileManager.default.createDirectory(at: repositoryURL, withIntermediateDirectories: true)
        let client = GitRepositoryClient(allowFileRemotesForTesting: true)
        try await client.prepareRepository(at: repositoryURL, remoteURL: configuredURL)

        var repository: OpaquePointer?
        XCTAssertEqual(git_repository_open(&repository, repositoryURL.path), 0)
        XCTAssertEqual(git_remote_set_pushurl(repository, "origin", attackerURL.absoluteString), 0)
        git_repository_free(repository)

        do {
            try await client.prepareRepository(at: repositoryURL, remoteURL: configuredURL)
            XCTFail("Existing repository validation must reject a foreign push URL")
        } catch let error as GitRepositoryError {
            guard case .operationFailed(let operation, _) = error else {
                return XCTFail("Unexpected error: \(error)")
            }
            XCTAssertEqual(operation, "remote validation")
        }
    }

    func testFetchIgnoresRepositoryControlledFetchRefspec() async throws {
        let remoteURL = temporaryDirectory.appendingPathComponent("refspec-origin.git", isDirectory: true)
        var bareRepository: OpaquePointer?
        XCTAssertEqual(git_repository_init(&bareRepository, remoteURL.path, 1), 0)
        git_repository_free(bareRepository)

        let authentication = GitHTTPAuthentication(username: "", token: "")
        let publisherURL = temporaryDirectory.appendingPathComponent("refspec-publisher", isDirectory: true)
        try FileManager.default.createDirectory(at: publisherURL, withIntermediateDirectories: true)
        let publisher = GitRepositoryClient(allowFileRemotesForTesting: true)
        try await publisher.prepareRepository(at: publisherURL, remoteURL: remoteURL)
        try "main\n".write(to: publisherURL.appendingPathComponent("note.md"), atomically: true, encoding: .utf8)
        try await publisher.stage(relativePaths: ["note.md"], in: publisherURL)
        let committed = try await commit(publisher, at: publisherURL, message: "Main")
        XCTAssertTrue(committed)
        try await publisher.pushMain(in: publisherURL, configuredRemoteURL: remoteURL, authentication: authentication)

        let consumerURL = temporaryDirectory.appendingPathComponent("refspec-consumer", isDirectory: true)
        try FileManager.default.createDirectory(at: consumerURL, withIntermediateDirectories: true)
        let consumer = GitRepositoryClient(allowFileRemotesForTesting: true)
        try await consumer.prepareRepository(at: consumerURL, remoteURL: remoteURL)

        var repository: OpaquePointer?
        XCTAssertEqual(git_repository_open(&repository, consumerURL.path), 0)
        var config: OpaquePointer?
        XCTAssertEqual(git_repository_config(&config, repository), 0)
        XCTAssertEqual(
            git_config_set_string(config, "remote.origin.fetch", "+refs/heads/main:refs/remotes/origin/repo-controlled"),
            0
        )
        git_config_free(config)
        git_repository_free(repository)

        try await consumer.fetchOrigin(in: consumerURL, configuredRemoteURL: remoteURL, authentication: authentication)

        XCTAssertTrue(referenceExists("refs/remotes/origin/main", in: consumerURL))
        XCTAssertFalse(referenceExists("refs/remotes/origin/repo-controlled", in: consumerURL))
    }

    func testCredentialScopeAllowsOnlyConfiguredHTTPSOrigin() throws {
        let scope = try GitRemoteCredentialScope(
            configuredRemoteURL: XCTUnwrap(URL(string: "https://Git.Example.com/team/notes.git"))
        )

        XCTAssertTrue(scope.allowsCredentials(for: "https://git.example.com/team/notes.git"))
        XCTAssertTrue(scope.allowsCredentials(for: "https://git.example.com:443/redirected-path"))
        XCTAssertFalse(scope.allowsCredentials(for: "https://git.example.com:8443/team/notes.git"))
        XCTAssertFalse(scope.allowsCredentials(for: "https://git.example.com.attacker.invalid/team/notes.git"))
        XCTAssertFalse(scope.allowsCredentials(for: "http://git.example.com/team/notes.git"))
        XCTAssertFalse(scope.allowsCredentials(for: "https://user@git.example.com/team/notes.git"))
    }

    func testRestoreMainReturnsHeadAndWorktreeToRecoveryRevision() async throws {
        let repositoryURL = temporaryDirectory.appendingPathComponent("restore-worktree", isDirectory: true)
        let remoteURL = temporaryDirectory.appendingPathComponent("restore-origin.git", isDirectory: true)
        try FileManager.default.createDirectory(at: repositoryURL, withIntermediateDirectories: true)
        let client = GitRepositoryClient(allowFileRemotesForTesting: true)
        try await client.prepareRepository(at: repositoryURL, remoteURL: remoteURL)
        let noteURL = repositoryURL.appendingPathComponent("note.md")
        try "before\n".write(to: noteURL, atomically: true, encoding: .utf8)
        try await client.stage(relativePaths: ["note.md"], in: repositoryURL)
        let committedBefore = try await commit(client, at: repositoryURL, message: "Before")
        XCTAssertTrue(committedBefore)
        let recoveryRevisionValue = try await client.headRevision(in: repositoryURL)
        let recoveryRevision = try XCTUnwrap(recoveryRevisionValue)

        try "after\n".write(to: noteURL, atomically: true, encoding: .utf8)
        try await client.stage(relativePaths: ["note.md"], in: repositoryURL)
        let committedAfter = try await commit(client, at: repositoryURL, message: "After")
        XCTAssertTrue(committedAfter)

        try await client.restoreMain(to: recoveryRevision, in: repositoryURL)

        let restoredRevision = try await client.headRevision(in: repositoryURL)
        XCTAssertEqual(restoredRevision, recoveryRevision)
        XCTAssertEqual(try String(contentsOf: noteURL), "before\n")
    }

    func testUnrelatedHistoryCanReplaceLocalWithoutForcePushing() async throws {
        git_libgit2_init()
        defer { git_libgit2_shutdown() }

        let remoteURL = temporaryDirectory.appendingPathComponent("unrelated-remote.git", isDirectory: true)
        var bareRepository: OpaquePointer?
        XCTAssertEqual(git_repository_init(&bareRepository, remoteURL.path, 1), 0)
        git_repository_free(bareRepository)

        let authentication = GitHTTPAuthentication(username: "", token: "")
        let publisherURL = temporaryDirectory.appendingPathComponent("publisher", isDirectory: true)
        try FileManager.default.createDirectory(at: publisherURL, withIntermediateDirectories: true)
        let publisher = GitRepositoryClient(allowFileRemotesForTesting: true)
        try await publisher.prepareRepository(at: publisherURL, remoteURL: remoteURL)
        try "remote authority\n".write(
            to: publisherURL.appendingPathComponent("remote.md"),
            atomically: true,
            encoding: .utf8
        )
        try await publisher.stage(relativePaths: ["remote.md"], in: publisherURL)
        let remoteCommitted = try await commit(publisher, at: publisherURL, message: "Remote root")
        XCTAssertTrue(remoteCommitted)
        try await publisher.pushMain(in: publisherURL, configuredRemoteURL: remoteURL, authentication: authentication)

        let localURL = temporaryDirectory.appendingPathComponent("unrelated-local", isDirectory: true)
        try FileManager.default.createDirectory(at: localURL, withIntermediateDirectories: true)
        let local = GitRepositoryClient(allowFileRemotesForTesting: true)
        try await local.prepareRepository(at: localURL, remoteURL: remoteURL)
        try "keep only in recovery\n".write(
            to: localURL.appendingPathComponent("local.md"),
            atomically: true,
            encoding: .utf8
        )
        try await local.stage(relativePaths: ["local.md"], in: localURL)
        let localCommitted = try await commit(local, at: localURL, message: "Local root")
        XCTAssertTrue(localCommitted)
        try await local.fetchOrigin(in: localURL, configuredRemoteURL: remoteURL, authentication: authentication)

        do {
            _ = try await local.integrateOriginMain(
                in: localURL,
                authorName: "MiaoYan Tests",
                authorEmail: "tests@localhost"
            )
            XCTFail("Unrelated histories must require an explicit choice")
        } catch let error as GitRepositoryError {
            XCTAssertEqual(error, .unrelatedHistories)
        }

        let currentLocalRevision = try await local.headRevision(in: localURL)
        let currentRemoteRevision = try await local.originMainRevision(in: localURL)
        let localRevision = try XCTUnwrap(currentLocalRevision)
        let remoteRevision = try XCTUnwrap(currentRemoteRevision)
        let recoveryRevision = try await local.createRecoveryReference(in: localURL)
        XCTAssertEqual(recoveryRevision, localRevision)
        let replacement = try await local.replaceMainWithOrigin(
            in: localURL,
            expectedLocalRevision: localRevision,
            expectedRemoteRevision: remoteRevision
        )
        XCTAssertEqual(
            replacement,
            .fastForward
        )
        XCTAssertFalse(FileManager.default.fileExists(atPath: localURL.appendingPathComponent("local.md").path))
        XCTAssertEqual(
            try String(contentsOf: localURL.appendingPathComponent("remote.md")),
            "remote authority\n"
        )
        let replacedRevision = try await local.headRevision(in: localURL)
        XCTAssertEqual(replacedRevision, remoteRevision)
    }

    func testExistingRepositoryOnAnotherBranchIsNotRewritten() async throws {
        git_libgit2_init()
        defer { git_libgit2_shutdown() }

        var repository: OpaquePointer?
        XCTAssertEqual(git_repository_init(&repository, temporaryDirectory.path, 0), 0)
        XCTAssertEqual(git_repository_set_head(repository, "refs/heads/feature"), 0)
        git_repository_free(repository)

        let client = GitRepositoryClient(allowFileRemotesForTesting: true)
        do {
            try await client.prepareRepository(at: temporaryDirectory, remoteURL: temporaryDirectory)
            XCTFail("Existing branch must not be changed implicitly")
        } catch let error as GitRepositoryError {
            guard case .operationFailed(let operation, _) = error else {
                return XCTFail("Unexpected error: \(error)")
            }
            XCTAssertEqual(operation, "branch validation")
        }

        var reopened: OpaquePointer?
        XCTAssertEqual(git_repository_open(&reopened, temporaryDirectory.path), 0)
        var head: OpaquePointer?
        XCTAssertEqual(git_reference_lookup(&head, reopened, "HEAD"), 0)
        XCTAssertEqual(git_reference_symbolic_target(head).map(String.init(cString:)), "refs/heads/feature")
        git_reference_free(head)
        git_repository_free(reopened)
    }

    func testEmptyInitialIndexDoesNotCreateAnUnrelatedCommit() async throws {
        let repositoryURL = temporaryDirectory.appendingPathComponent("empty", isDirectory: true)
        try FileManager.default.createDirectory(at: repositoryURL, withIntermediateDirectories: true)
        let client = GitRepositoryClient(allowFileRemotesForTesting: true)
        try await client.prepareRepository(at: repositoryURL, remoteURL: temporaryDirectory)

        let committed = try await client.commitIndex(
            in: repositoryURL,
            message: "Should not exist",
            authorName: "MiaoYan Tests",
            authorEmail: "tests@localhost"
        )
        XCTAssertFalse(committed)
        let revision = try await client.headRevision(in: repositoryURL)
        XCTAssertNil(revision)
    }

    func testStagedDeletionKeepsTheOldPathForSafetyValidation() async throws {
        let repositoryURL = temporaryDirectory.appendingPathComponent("staged-deletion", isDirectory: true)
        try FileManager.default.createDirectory(at: repositoryURL, withIntermediateDirectories: true)
        let client = GitRepositoryClient(allowFileRemotesForTesting: true)
        try await client.prepareRepository(at: repositoryURL, remoteURL: temporaryDirectory)

        let unsupportedURL = repositoryURL.appendingPathComponent("README")
        try "tracked\n".write(to: unsupportedURL, atomically: true, encoding: .utf8)
        try await client.stage(relativePaths: ["README"], in: repositoryURL)
        let committed = try await commit(client, at: repositoryURL, message: "Track unsupported file")
        XCTAssertTrue(committed)
        try FileManager.default.removeItem(at: unsupportedURL)
        try await client.stage(relativePaths: ["README"], in: repositoryURL)

        let staged = try await client.stagedChanges(in: repositoryURL)
        XCTAssertEqual(staged, [GitSyncChange(kind: .deleted, path: "README")])
        XCTAssertEqual(
            GitSyncPathPolicy().violations(for: staged),
            [GitSyncPathViolation(path: "README", reason: .unsupportedFile)]
        )
    }

    func testTrackedEntriesPreserveSymlinkModeForAllowlistValidation() async throws {
        let repositoryURL = temporaryDirectory.appendingPathComponent("tracked-symlink", isDirectory: true)
        try FileManager.default.createDirectory(at: repositoryURL, withIntermediateDirectories: true)
        let client = GitRepositoryClient(allowFileRemotesForTesting: true)
        try await client.prepareRepository(at: repositoryURL, remoteURL: temporaryDirectory)

        let imageDirectory = repositoryURL.appendingPathComponent("i", isDirectory: true)
        try FileManager.default.createDirectory(at: imageDirectory, withIntermediateDirectories: true)
        try Data([1]).write(to: repositoryURL.appendingPathComponent("outside.png"))
        try FileManager.default.createSymbolicLink(
            atPath: imageDirectory.appendingPathComponent("linked.png").path,
            withDestinationPath: "../outside.png"
        )
        try await client.stage(relativePaths: ["i/linked.png"], in: repositoryURL)

        let entries = try await client.trackedEntries(in: repositoryURL)
        XCTAssertEqual(entries, [GitSyncChange(kind: .modified, path: "i/linked.png", entryKind: .symbolicLink)])
        XCTAssertEqual(
            GitSyncPathPolicy().violations(for: entries),
            [GitSyncPathViolation(path: "i/linked.png", reason: .nonRegularEntry)]
        )
    }

    func testHTTPSCloneWhenIntegrationRemoteIsProvided() async throws {
        guard let value = ProcessInfo.processInfo.environment["MIAOYAN_GIT_HTTPS_TEST_REMOTE"],
            let remoteURL = URL(string: value)
        else {
            throw XCTSkip("Set MIAOYAN_GIT_HTTPS_TEST_REMOTE to run the live HTTPS transport check")
        }

        let destination = temporaryDirectory.appendingPathComponent("https-clone", isDirectory: true)
        let client = GitRepositoryClient()
        try await client.clone(
            remoteURL: remoteURL,
            to: destination,
            authentication: GitHTTPAuthentication(username: "", token: "")
        )
        let revision = try await client.headRevision(in: destination)
        XCTAssertNotNil(revision)
    }

    private func commit(_ client: GitRepositoryClient, at url: URL, message: String) async throws -> Bool {
        try await client.commitIndex(
            in: url,
            message: message,
            authorName: "MiaoYan Tests",
            authorEmail: "tests@localhost"
        )
    }

    private func referenceExists(_ name: String, in repositoryURL: URL) -> Bool {
        var repository: OpaquePointer?
        guard git_repository_open(&repository, repositoryURL.path) == 0 else { return false }
        defer { git_repository_free(repository) }
        var reference: OpaquePointer?
        let result = git_reference_lookup(&reference, repository, name)
        git_reference_free(reference)
        return result == 0
    }
}
