import XCTest

@testable import MiaoYan

final class GitSyncFoundationTests: XCTestCase {
    private actor SyncRecorder {
        private(set) var roots = [URL]()
        private var shouldWait = false

        func setShouldWait(_ value: Bool) {
            shouldWait = value
        }

        func run(root: URL) async {
            roots.append(root)
            while shouldWait {
                await Task.yield()
            }
        }

        func count() -> Int {
            roots.count
        }

        func lastRoot() -> URL? {
            roots.last
        }
    }

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

    func testIgnoresFinderMetadataAtAnyLibraryDepth() {
        XCTAssertEqual(policy.classify(relativePath: ".DS_Store"), .ignored)
        XCTAssertEqual(policy.classify(relativePath: "Notes/.DS_Store"), .ignored)
        XCTAssertEqual(policy.classify(relativePath: ".Trash/.DS_Store"), .ignored)
        XCTAssertTrue(
            policy.violations(for: [GitSyncChange(kind: .added, path: "Notes/.DS_Store")]).isEmpty)
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

    func testAllowsOnlyCanonicalSyncedTrashTransportPaths() {
        let id = "123e4567-e89b-12d3-a456-426614174000"
        XCTAssertEqual(policy.classify(relativePath: ".Trash/manifest.v1"), .allowed(.trashManifest))
        XCTAssertEqual(
            policy.classify(relativePath: ".Trash/items/\(id)/22 топ.md"),
            .allowed(.trashNote))
        XCTAssertEqual(
            policy.classify(relativePath: ".Trash/items/\(id)/Folder/i/photo.webp"),
            .allowed(.trashAttachment))
        assertRejected(".Trash/manifest.tsv", reason: .trashPath)
        assertRejected(".Trash/items/not-a-uuid/note.md", reason: .trashPath)
        assertRejected(".Trash/items/\(id)/.secret.md", reason: .hiddenPath)
        assertRejected(".Trash/items/\(id)/payload.bin", reason: .unsupportedFile)
        assertRejected("Trash/items/\(id)/note.md", reason: .trashPath)
    }

    func testSyncedTrashManifestMatchesAndroidEncodingAndRoundTripsUnicode() throws {
        let entry = GitSyncedTrashManifestEntry(
            id: "123e4567-e89b-12d3-a456-426614174000",
            originalRelativePath: "Идеи/22 топ.md",
            trashRelativePath: ".Trash/items/123e4567-e89b-12d3-a456-426614174000/22 топ.md",
            deletedAtMilliseconds: 1_788_862_000_000)

        let encoded = GitSyncedTrashManifestCodec.encode([entry])

        XCTAssertTrue(encoded.hasPrefix("MiaoYanTrashManifest\t1\n"))
        XCTAssertFalse(encoded.contains("Идеи"), "manifest fields must use Android-compatible base64url")
        XCTAssertEqual(GitSyncedTrashManifestCodec.decode(encoded), [entry])
    }

    func testSyncedTrashManifestRejectsPartialCorruptionAndDuplicatePaths() {
        let id = "123e4567-e89b-12d3-a456-426614174000"
        let entry = GitSyncedTrashManifestEntry(
            id: id,
            originalRelativePath: "Ideas/note.md",
            trashRelativePath: ".Trash/items/\(id)/note.md",
            deletedAtMilliseconds: 1_788_862_000_000)
        let validLine = GitSyncedTrashManifestCodec.encode([entry])
            .split(whereSeparator: { $0.isNewline })[1]

        XCTAssertNil(GitSyncedTrashManifestCodec.decodeValidated("MiaoYanTrashManifest\t1\ninvalid\n"))
        XCTAssertNil(
            GitSyncedTrashManifestCodec.decodeValidated(
                "MiaoYanTrashManifest\t1\n\(validLine)\n\(validLine)\n"))
    }

    func testLocalPreflightCollectsTheHiddenSyncedTrashTransport() throws {
        let root = FileManager.default.temporaryDirectory
            .appendingPathComponent("MiaoYanTrashPreflight-\(UUID().uuidString)", isDirectory: true)
        defer { try? FileManager.default.removeItem(at: root) }
        let id = "123e4567-e89b-12d3-a456-426614174000"
        let itemDirectory = root.appendingPathComponent(".Trash/items/\(id)", isDirectory: true)
        try FileManager.default.createDirectory(at: itemDirectory, withIntermediateDirectories: true)
        try "deleted".write(
            to: itemDirectory.appendingPathComponent("22 топ.md"),
            atomically: true,
            encoding: .utf8)
        try "MiaoYanTrashManifest\t1\n".write(
            to: root.appendingPathComponent(".Trash/manifest.v1"),
            atomically: true,
            encoding: .utf8)

        let preflight = try GitSyncFileCollector.collect(
            rootURL: root,
            maximumAttachmentBytes: 25 * 1024 * 1024)

        XCTAssertEqual(
            preflight.managedPaths,
            [
                ".Trash/items/\(id)/22 топ.md",
                ".Trash/manifest.v1",
            ])
        XCTAssertTrue(preflight.violations.isEmpty)
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

    func testEditorReconciliationPlanDetectsActiveDeleteAndRename() {
        let root = URL(fileURLWithPath: "/tmp/notes", isDirectory: true)
        let owner = root.appendingPathComponent("active.md")

        XCTAssertEqual(
            GitEditorReconciliationPlan.make(
                changes: [.init(kind: .deleted, path: "active.md")],
                rootURL: root,
                ownerURL: owner
            ),
            GitEditorReconciliationPlan(
                ownerURL: owner.standardizedFileURL.resolvingSymlinksInPath(),
                mutation: .deleted
            )
        )
        XCTAssertEqual(
            GitEditorReconciliationPlan.make(
                changes: [.init(kind: .renamed, path: "renamed.md", previousPath: "active.md")],
                rootURL: root,
                ownerURL: owner
            )?.mutation,
            .renamed(to: root.appendingPathComponent("renamed.md").standardizedFileURL.resolvingSymlinksInPath())
        )
        XCTAssertNil(
            GitEditorReconciliationPlan.make(
                changes: [.init(kind: .deleted, path: "other.md")],
                rootURL: root,
                ownerURL: owner
            )
        )
    }

    func testEditorReconciliationContextTracksBufferOwnerAndPreviewSelectionIndependently() {
        let root = URL(fileURLWithPath: "/tmp/notes", isDirectory: true)
        let bufferOwner = root.appendingPathComponent("editing.md")
        let selected = root.appendingPathComponent("previewed.md")
        let renamedSelection = root.appendingPathComponent("previewed-renamed.md")
        let context = GitEditorReconciliationContext.make(
            changes: [
                .init(kind: .deleted, path: "editing.md"),
                .init(kind: .renamed, path: "previewed-renamed.md", previousPath: "previewed.md"),
            ],
            rootURL: root,
            storageOwnerURL: bufferOwner,
            selectedNoteURL: selected
        )

        XCTAssertEqual(context.storageOwner?.mutation, .deleted)
        XCTAssertEqual(context.selectedNote?.mutation, .renamed(to: renamedSelection))
        XCTAssertEqual(context.preferredSelectionURL, renamedSelection)
        XCTAssertTrue(context.requiresSelectionRefresh)
    }

    func testEditorReconciliationContextPreservesIndependentUnchangedPreviewSelection() {
        let root = URL(fileURLWithPath: "/tmp/notes", isDirectory: true)
        let bufferOwner = root.appendingPathComponent("editing.md")
        let selected = root.appendingPathComponent("previewed.md")
        let context = GitEditorReconciliationContext.make(
            changes: [
                .init(kind: .renamed, path: "editing-renamed.md", previousPath: "editing.md")
            ],
            rootURL: root,
            storageOwnerURL: bufferOwner,
            selectedNoteURL: selected
        )

        XCTAssertEqual(
            context.storageOwner?.mutation,
            .renamed(to: root.appendingPathComponent("editing-renamed.md"))
        )
        XCTAssertNil(context.selectedNote)
        XCTAssertEqual(context.preferredSelectionURL, selected)
        XCTAssertTrue(context.requiresSelectionRefresh)
    }

    func testSingleFileModePolicyDisablesGitSyncEntryPoints() {
        XCTAssertTrue(GitSyncModePolicy.allowsGitSync(isSingleFileMode: false))
        XCTAssertFalse(GitSyncModePolicy.allowsGitSync(isSingleFileMode: true))
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

    func testLegacyGitSyncConfigurationDecodesWithAutomaticSyncDisabled() throws {
        struct LegacyConfiguration: Encodable {
            let remoteURL: URL
            let authorName: String
            let authorEmail: String
            let ai: GitAIConfiguration?
        }

        let data = try JSONEncoder().encode(
            LegacyConfiguration(
                remoteURL: URL(string: "https://example.com/notes.git")!,
                authorName: "Miao Yan",
                authorEmail: "notes@example.com",
                ai: nil
            )
        )
        let configuration = try JSONDecoder().decode(GitSyncConfiguration.self, from: data)

        XCTAssertFalse(configuration.automaticSyncEnabled)
    }

    func testGitSyncConfigurationRoundTripsAutomaticSyncOptIn() throws {
        let configuration = GitSyncConfiguration(
            remoteURL: URL(string: "https://example.com/notes.git")!,
            authorName: "Miao Yan",
            authorEmail: "notes@example.com",
            automaticSyncEnabled: true
        )

        let decoded = try JSONDecoder().decode(
            GitSyncConfiguration.self,
            from: JSONEncoder().encode(configuration)
        )

        XCTAssertEqual(decoded, configuration)
        XCTAssertTrue(decoded.automaticSyncEnabled)
    }

    @MainActor
    func testAutomaticSchedulerUsesFifteenMinuteIntervalAndLatestConfiguredRoot() async {
        let recorder = SyncRecorder()
        let scheduler = GitSyncScheduler { root in
            await recorder.run(root: root)
        }
        defer { scheduler.stop() }
        let oldRoot = URL(fileURLWithPath: "/tmp/old-notes", isDirectory: true)
        let currentRoot = URL(fileURLWithPath: "/tmp/current-notes", isDirectory: true)

        scheduler.configure(rootURL: oldRoot, enabled: true)
        scheduler.configure(rootURL: currentRoot, enabled: true)
        scheduler.triggerForTesting()

        XCTAssertEqual(GitSyncScheduler.interval, 15 * 60)
        let didRun = await waitUntil { await recorder.count() == 1 }
        let recordedRoot = await recorder.lastRoot()
        XCTAssertTrue(didRun)
        XCTAssertEqual(recordedRoot, currentRoot.standardizedFileURL.resolvingSymlinksInPath())
    }

    @MainActor
    func testAutomaticSchedulerDoesNotRunWhenDisabled() async {
        let recorder = SyncRecorder()
        let scheduler = GitSyncScheduler { root in
            await recorder.run(root: root)
        }
        scheduler.configure(rootURL: URL(fileURLWithPath: "/tmp/notes"), enabled: false)

        scheduler.triggerForTesting()
        await Task.yield()

        let runCount = await recorder.count()
        XCTAssertNil(scheduler.scheduledRootURL)
        XCTAssertEqual(runCount, 0)
    }

    @MainActor
    func testAutomaticSchedulerPreventsOverlappingRuns() async {
        let recorder = SyncRecorder()
        await recorder.setShouldWait(true)
        let scheduler = GitSyncScheduler { root in
            await recorder.run(root: root)
        }
        defer { scheduler.stop() }
        scheduler.configure(rootURL: URL(fileURLWithPath: "/tmp/notes"), enabled: true)

        scheduler.triggerForTesting()
        scheduler.triggerForTesting()

        let firstRunStarted = await waitUntil { await recorder.count() == 1 }
        let firstRunCount = await recorder.count()
        XCTAssertTrue(firstRunStarted)
        XCTAssertTrue(scheduler.isSyncRunning)
        XCTAssertEqual(firstRunCount, 1)

        await recorder.setShouldWait(false)
        let firstRunFinished = await waitUntil { !scheduler.isSyncRunning }
        XCTAssertTrue(firstRunFinished)
        scheduler.triggerForTesting()
        let secondRunStarted = await waitUntil { await recorder.count() == 2 }
        XCTAssertTrue(secondRunStarted)
    }

    @MainActor
    func testTerminationReplyGateRepliesOnlyOnce() {
        var replyCount = 0
        let gate = GitSyncTerminationReplyGate {
            replyCount += 1
        }

        XCTAssertTrue(gate.begin())
        XCTAssertFalse(gate.begin())
        XCTAssertTrue(gate.replyOnce())
        XCTAssertFalse(gate.replyOnce())
        XCTAssertEqual(replyCount, 1)
        XCTAssertEqual(gate.state, .replied)
    }

    @MainActor
    func testTerminationTimeoutRepliesAndIgnoresLateCompletion() async throws {
        let timeoutCount = LockedCounter()
        let replyCount = LockedCounter()
        let controller = GitSyncTerminationController(
            timeout: 0.01,
            onTimeout: { timeoutCount.increment() },
            reply: { replyCount.increment() }
        )

        XCTAssertTrue(controller.begin())
        try await Task.sleep(nanoseconds: 50_000_000)

        XCTAssertEqual(timeoutCount.value, 1)
        XCTAssertEqual(replyCount.value, 1)
        XCTAssertEqual(controller.state, .replied)
        XCTAssertFalse(controller.finish())
        XCTAssertEqual(replyCount.value, 1)
    }

    @MainActor
    func testTerminationDeadlineAdvancesWhileMainActorIsBlocked() {
        let controller = GitSyncTerminationController(timeout: 0.01, onTimeout: {}, reply: {})

        XCTAssertTrue(controller.begin())
        Thread.sleep(forTimeInterval: 0.05)

        XCTAssertEqual(controller.state, .replied)
        XCTAssertFalse(controller.finish())
    }

    @MainActor
    func testPreQuitGitSyncTimeoutIsShortAndBounded() {
        XCTAssertGreaterThan(AppDelegate.preQuitGitSyncTimeout, 0)
        XCTAssertLessThanOrEqual(AppDelegate.preQuitGitSyncTimeout, 10)
    }

    @MainActor
    func testCoordinatorRejectsSingleFileModeBeforeRepositoryAccess() async {
        let previousSingleMode = UserDefaultsManagement.isSingleMode
        UserDefaultsManagement.isSingleMode = true
        defer { UserDefaultsManagement.isSingleMode = previousSingleMode }

        let root = Project(url: URL(fileURLWithPath: "/tmp/single-file-parent"), label: "test", isRoot: true)
        let configuration = GitSyncConfiguration(
            remoteURL: URL(string: "https://example.com/notes.git")!,
            authorName: "Miao Yan",
            authorEmail: "notes@example.com"
        )
        let result = await GitSyncCoordinator().sync(
            root: root,
            configuration: configuration,
            credential: GitHTTPSCredential(username: "octocat", personalAccessToken: "secret"),
            viewController: ViewController()
        )

        XCTAssertEqual(result, .blocked(.singleFileMode))
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

    @MainActor
    private func waitUntil(
        attempts: Int = 1_000,
        _ condition: @escaping @MainActor () async -> Bool
    ) async -> Bool {
        for _ in 0..<attempts {
            if await condition() { return true }
            await Task.yield()
        }
        return false
    }
}

private final class LockedCounter: @unchecked Sendable {
    private let lock = NSLock()
    private var count = 0

    var value: Int {
        lock.lock()
        defer { lock.unlock() }
        return count
    }

    func increment() {
        lock.lock()
        count += 1
        lock.unlock()
    }
}
