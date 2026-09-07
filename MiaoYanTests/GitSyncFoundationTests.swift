import XCTest

@testable import MiaoYan

final class GitSyncFoundationTests: XCTestCase {
    @MainActor
    func testGitSyncMenuRouteNeverLoadsAnUnwiredViewController() {
        let storyboardProxy = ViewController()
        XCTAssertFalse(storyboardProxy.isViewLoaded)

        ViewController.routeGitSyncAction(to: storyboardProxy, sender: self)

        XCTAssertFalse(storyboardProxy.isViewLoaded)
    }

    private let policy = GitSyncPathPolicy()

    func testAllowsSupportedNotesAndManagedAttachments() {
        XCTAssertEqual(policy.classify(relativePath: "Journal/Today.MD"), .allowed(.note))
        XCTAssertEqual(policy.classify(relativePath: "notes/readme.markdown"), .allowed(.note))
        XCTAssertEqual(policy.classify(relativePath: "plain.txt"), .allowed(.note))
        XCTAssertEqual(policy.classify(relativePath: "Journal/i/photo.heic"), .allowed(.attachment))
        XCTAssertEqual(policy.classify(relativePath: "files/archive.zip"), .allowed(.attachment))
        assertRejected("assets/photo.png", reason: .unsupportedFile)
    }

    func testAllowsOnlyGitignoreFromRepositoryConfiguration() {
        XCTAssertEqual(policy.classify(relativePath: ".gitignore"), .allowed(.repositoryConfiguration))
        assertRejected(".git/config", reason: .hiddenPath)
        assertRejected("Journal/.secrets/note.md", reason: .hiddenPath)
        assertRejected("Journal/.env", reason: .hiddenPath)
        assertRejected(".gitattributes", reason: .hiddenPath)
    }

    func testRejectsAbsoluteTraversalMalformedAndTrashPaths() {
        assertRejected("/tmp/note.md", reason: .absolutePath)
        assertRejected("~/note.md", reason: .absolutePath)
        assertRejected("Journal/../outside.md", reason: .pathTraversal)
        assertRejected("Journal//note.md", reason: .malformedPath)
        assertRejected("Journal\\note.md", reason: .malformedPath)
        assertRejected("note\nname.md", reason: .malformedPath)
        assertRejected("Trash/deleted.md", reason: .trashPath)
        assertRejected("Journal/.Trash/deleted.md", reason: .trashPath)
    }

    func testRejectsSymlinksAndSubmodules() {
        assertRejected("note.md", entryKind: .symbolicLink, reason: .nonRegularEntry)
        assertRejected("Journal", entryKind: .submodule, reason: .nonRegularEntry)
    }

    func testRepositoryRootMustMatchExactly() {
        let allowed = URL(fileURLWithPath: "/tmp/MiaoYanNotes", isDirectory: true)
        let rootPolicy = GitSyncRepositoryRootPolicy(allowedRoots: [allowed])
        XCTAssertTrue(rootPolicy.allows(allowed.appendingPathComponent("..").appendingPathComponent("MiaoYanNotes")))
        XCTAssertFalse(rootPolicy.allows(allowed.appendingPathComponent("Nested")))
        XCTAssertFalse(rootPolicy.allows(URL(fileURLWithPath: "/tmp/MiaoYanNotes-copy")))
        XCTAssertFalse(rootPolicy.allows(URL(string: "https://example.com/notes")!))
    }

    func testRenameValidatesBothPaths() {
        let change = GitSyncChange(kind: .renamed, path: "safe.md", previousPath: "../outside.md")
        XCTAssertEqual(
            policy.violations(for: [change]),
            [GitSyncPathViolation(path: "../outside.md", reason: .pathTraversal)]
        )
    }

    func testIncomingAttachmentSizeLimitIsEnforced() {
        let limit = Int64(25 * 1024 * 1024)
        let limitedPolicy = GitSyncPathPolicy(maximumAttachmentBytes: limit)
        let exactLimit = GitSyncChange(kind: .added, path: "i/exact.png", byteCount: limit)
        let oversized = GitSyncChange(kind: .added, path: "i/photo.png", byteCount: limit + 1)
        XCTAssertTrue(limitedPolicy.violations(for: [exactLimit]).isEmpty)
        XCTAssertEqual(
            limitedPolicy.violations(for: [oversized]),
            [GitSyncPathViolation(path: "i/photo.png", reason: .oversizedAttachment)]
        )
    }

    func testChangeSummaryCountsAndClassifiesPaths() {
        let changes = [
            GitSyncChange(kind: .added, path: "new.md"),
            GitSyncChange(kind: .modified, path: "Journal/i/photo.png"),
            GitSyncChange(kind: .deleted, path: ".gitignore"),
            GitSyncChange(kind: .renamed, path: "renamed.txt", previousPath: "old.txt"),
        ]
        let summary = GitSyncChangeSummary(changes: changes, policy: policy)
        XCTAssertEqual(summary.totalChanges, 4)
        XCTAssertEqual(summary.notePaths, ["new.md", "old.txt", "renamed.txt"])
        XCTAssertEqual(summary.attachmentPaths, ["Journal/i/photo.png"])
    }

    func testSyncStateDistinguishesIdleFromActivePhases() {
        XCTAssertFalse(GitSyncState.idle(lastResult: nil).isRunning)
        XCTAssertTrue(GitSyncState.fetching.isRunning)
        XCTAssertTrue(GitSyncState.pushing.isRunning)
    }

    func testRecognizesKnownCloudStorageRootsWithoutSubstringFalsePositives() {
        let home = URL(fileURLWithPath: "/Users/test", isDirectory: true)
        XCTAssertTrue(
            GitSyncLibraryLocationPolicy.isKnownCloudPath(
                URL(fileURLWithPath: "/Users/test/Library/CloudStorage/Dropbox/Notes"),
                homeDirectory: home
            )
        )
        XCTAssertTrue(
            GitSyncLibraryLocationPolicy.isKnownCloudPath(
                URL(fileURLWithPath: "/Users/test/Library/Mobile Documents/iCloud~com~tw93~miaoyan/Documents"),
                homeDirectory: home
            )
        )
        XCTAssertTrue(
            GitSyncLibraryLocationPolicy.isKnownCloudPath(
                URL(fileURLWithPath: "/Users/test/OneDrive-Company/Notes"),
                homeDirectory: home
            )
        )
        XCTAssertFalse(
            GitSyncLibraryLocationPolicy.isKnownCloudPath(
                URL(fileURLWithPath: "/Users/test/Documents/CloudStorage Notes"),
                homeDirectory: home
            )
        )
    }

    func testMigrationNeverAutomaticallyTrashesBroadCloudRoots() {
        let home = URL(fileURLWithPath: "/Users/test", isDirectory: true)
        let protectedRoots = [
            home,
            home.appendingPathComponent("Documents", isDirectory: true),
            home.appendingPathComponent("Dropbox", isDirectory: true),
            home.appendingPathComponent("OneDrive-Company", isDirectory: true),
            home.appendingPathComponent("Library/CloudStorage/Dropbox", isDirectory: true),
            home.appendingPathComponent("Library/Mobile Documents/com~apple~CloudDocs/Documents", isDirectory: true),
        ]

        for root in protectedRoots {
            XCTAssertFalse(
                GitSyncLibraryMigrator.canTrashOriginal(
                    root,
                    ownedICloudLibraryRoot: nil,
                    homeDirectory: home
                ),
                root.path
            )
        }
    }

    func testMigrationMayTrashOnlyTheExactOwnedICloudLibraryRoot() {
        let home = URL(fileURLWithPath: "/Users/test", isDirectory: true)
        let ownedRoot = home.appendingPathComponent(
            "Library/Mobile Documents/com~apple~CloudDocs/Documents",
            isDirectory: true
        )
        XCTAssertTrue(
            GitSyncLibraryMigrator.canTrashOriginal(
                ownedRoot,
                ownedICloudLibraryRoot: ownedRoot,
                homeDirectory: home
            )
        )
        XCTAssertFalse(
            GitSyncLibraryMigrator.canTrashOriginal(
                ownedRoot.deletingLastPathComponent(),
                ownedICloudLibraryRoot: ownedRoot,
                homeDirectory: home
            )
        )
    }

    func testMigrationResumesOnlyFromAnIdenticalVerifiedDestination() throws {
        let fileManager = FileManager.default
        let temporaryRoot = fileManager.temporaryDirectory.appendingPathComponent(UUID().uuidString, isDirectory: true)
        let source = temporaryRoot.appendingPathComponent("source", isDirectory: true)
        let destination = temporaryRoot.appendingPathComponent("destination", isDirectory: true)
        defer { try? fileManager.removeItem(at: temporaryRoot) }

        try fileManager.createDirectory(at: source, withIntermediateDirectories: true)
        try Data("note".utf8).write(to: source.appendingPathComponent("note.md"))

        let first = try GitSyncLibraryMigrator.migrate(
            source: source,
            fileManager: fileManager,
            destination: destination
        )
        XCTAssertEqual(first.destination.path, destination.standardizedFileURL.resolvingSymlinksInPath().path)

        let resumed = try GitSyncLibraryMigrator.migrate(
            source: source,
            fileManager: fileManager,
            destination: destination
        )
        XCTAssertEqual(resumed.destination.path, destination.path)

        try Data("changed".utf8).write(to: source.appendingPathComponent("note.md"))
        XCTAssertThrowsError(
            try GitSyncLibraryMigrator.migrate(
                source: source,
                fileManager: fileManager,
                destination: destination
            )
        ) { error in
            guard case GitSyncLibraryMigrationError.destinationOccupied(let occupied) = error else {
                return XCTFail("Unexpected error: \(error)")
            }
            XCTAssertEqual(occupied.path, destination.path)
        }
    }

    @MainActor
    func testMigrationGateWaitsForUploadsAndBlocksLibraryWrites() {
        let root = URL(fileURLWithPath: "/Users/test/Notes", isDirectory: true)
        XCTAssertTrue(GitSyncLibraryMutationGate.beginUpload())
        XCTAssertFalse(GitSyncLibraryMutationGate.beginMigration(of: root))
        GitSyncLibraryMutationGate.endUpload()

        XCTAssertTrue(GitSyncLibraryMutationGate.beginMigration(of: root))
        defer { GitSyncLibraryMutationGate.endMigration() }
        XCTAssertFalse(GitSyncLibraryMutationGate.beginUpload())
        XCTAssertFalse(GitSyncLibraryMutationGate.allowsMutation(at: root.appendingPathComponent("note.md")))
        XCTAssertTrue(
            GitSyncLibraryMutationGate.allowsMutation(
                at: URL(fileURLWithPath: "/Users/test/Other/note.md")
            )
        )
    }

    @MainActor
    func testGitOperationGateIsExclusiveAndBlocksUploadsAndLibraryWrites() {
        let root = URL(fileURLWithPath: "/Users/test/Notes", isDirectory: true)
        XCTAssertTrue(GitSyncLibraryMutationGate.beginGitOperation(of: root))
        defer { GitSyncLibraryMutationGate.endGitOperation() }

        XCTAssertFalse(GitSyncLibraryMutationGate.beginUpload())
        XCTAssertFalse(GitSyncLibraryMutationGate.beginMigration(of: root))
        XCTAssertFalse(GitSyncLibraryMutationGate.beginGitOperation(of: root))
        XCTAssertFalse(GitSyncLibraryMutationGate.allowsMutation(at: root.appendingPathComponent("note.md")))
        XCTAssertTrue(
            GitSyncLibraryMutationGate.allowsMutation(
                at: URL(fileURLWithPath: "/Users/test/Other/note.md")
            )
        )
    }

    func testAIConfigurationBuildsOpenAICompatibleChatEndpoint() {
        let deepSeek = GitAIConfiguration(
            baseURL: URL(string: "https://api.deepseek.com")!,
            model: "deepseek-v4-flash",
            prompt: "merge"
        )
        XCTAssertEqual(deepSeek.chatCompletionsURL?.absoluteString, "https://api.deepseek.com/chat/completions")
        let openAICompatible = GitAIConfiguration(
            baseURL: URL(string: "https://models.example.com/v1/")!,
            model: "custom-model",
            prompt: "merge"
        )
        XCTAssertEqual(openAICompatible.chatCompletionsURL?.absoluteString, "https://models.example.com/v1/chat/completions")
        XCTAssertNil(
            GitAIConfiguration(baseURL: URL(string: "http://localhost:8000/v1")!, model: "local", prompt: "merge")
                .chatCompletionsURL
        )
    }

    private func assertRejected(
        _ path: String,
        entryKind: GitSyncEntryKind = .regularFile,
        reason: GitSyncPathRejectionReason,
        file: StaticString = #filePath,
        line: UInt = #line
    ) {
        XCTAssertEqual(
            policy.classify(relativePath: path, entryKind: entryKind),
            .rejected(GitSyncPathViolation(path: path, reason: reason)),
            file: file,
            line: line
        )
    }
}
