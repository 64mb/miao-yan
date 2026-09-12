import XCTest

@testable import MiaoYan

/// Regression coverage for V3.5.1+ fix `cb46c987` "durable saves": the 1.5s
/// debounce window between `save(content:)` and the actual disk write is a
/// real data-loss surface. `flushPendingSave` must drain the queued work item
/// synchronously so app-lifecycle hooks (`applicationWillTerminate`,
/// window-will-close, resign-key) cannot exit with unsaved keystrokes.
final class NoteSaveDebounceTests: XCTestCase {

    private var tempDir: URL!
    private var defaultsSuites = [String]()

    override func setUp() {
        super.setUp()
        tempDir = FileManager.default.temporaryDirectory
            .appendingPathComponent("MiaoYanNoteSaveTests-\(UUID().uuidString)")
        try? FileManager.default.createDirectory(at: tempDir, withIntermediateDirectories: true)
    }

    override func tearDown() {
        try? FileManager.default.removeItem(at: tempDir)
        for suiteName in defaultsSuites {
            UserDefaults.standard.removePersistentDomain(forName: suiteName)
        }
        super.tearDown()
    }

    @MainActor
    private func configuredGitSyncStore(for rootURL: URL) throws -> GitSyncConfigurationStore {
        let suiteName = "MiaoYanTests.GitTrash.\(UUID().uuidString)"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suiteName))
        defaults.removePersistentDomain(forName: suiteName)
        defaultsSuites.append(suiteName)
        let store = GitSyncConfigurationStore(defaults: defaults)
        try store.save(
            GitSyncConfiguration(
                remoteURL: try XCTUnwrap(URL(string: "https://example.com/notes.git")),
                authorName: "MiaoYan Tests",
                authorEmail: "tests@example.com"
            ),
            for: rootURL)
        return store
    }

    @MainActor
    func testHasPendingSaveIsFalseOnFreshNote() {
        let url = tempDir.appendingPathComponent("fresh.md")
        let project = Project(url: tempDir, label: "test", isRoot: true)
        let note = Note(url: url, with: project)

        XCTAssertFalse(note.hasPendingSave, "a brand-new Note has no debounced work item")
    }

    @MainActor
    func testForceReloadAsyncReadsLargeNoteWithoutSynchronousMainActorAPI() async throws {
        let url = tempDir.appendingPathComponent("large-reload.md")
        let body = String(repeating: "0123456789abcdef", count: 131_072)
        try body.write(to: url, atomically: true, encoding: .utf8)
        let project = Project(url: tempDir, label: "test", isRoot: true)
        let note = Note(url: url, with: project)

        await note.forceReloadAsync()

        XCTAssertEqual(note.content.length, body.utf16.count)
        XCTAssertEqual(note.content.string.prefix(16), "0123456789abcdef")
    }

    @MainActor
    func testSaveContentSchedulesDebouncedWorkItem() {
        let url = tempDir.appendingPathComponent("scheduled.md")
        let project = Project(url: tempDir, label: "test", isRoot: true)
        let note = Note(url: url, with: project)

        note.save(attributed: NSAttributedString(string: "draft body"))

        XCTAssertTrue(
            note.hasPendingSave,
            "save(attributed:) routes through debounceSave and must mark the note as pending")
        XCTAssertTrue(note.needsSave)
    }

    @MainActor
    func testFlushPendingSaveClearsTheWorkItem() {
        let url = tempDir.appendingPathComponent("flushed.md")
        let project = Project(url: tempDir, label: "test", isRoot: true)
        let note = Note(url: url, with: project)

        note.save(attributed: NSAttributedString(string: "first content"))
        XCTAssertTrue(note.hasPendingSave)

        XCTAssertTrue(note.flushPendingSave(globalStorage: false))

        XCTAssertFalse(
            note.hasPendingSave,
            "flushPendingSave must drain (or clear) the debounced work item synchronously")
        XCTAssertFalse(note.needsSave)
    }

    @MainActor
    func testExternallyRemovedNoteCannotBeRecreatedByPendingSave() async throws {
        let url = tempDir.appendingPathComponent("externally-removed.md")
        try "original".write(to: url, atomically: true, encoding: .utf8)

        let project = Project(url: tempDir, label: "test", isRoot: true)
        let storage = Storage()
        let note = Note(url: url, with: project)
        note.sharedStorage = storage
        storage.add(note)

        note.save(attributed: NSAttributedString(string: "pending edit"))
        try FileManager.default.removeItem(at: url)
        storage.removeNotes(notes: [note], fsRemove: false) { _ in }

        let debounceFinished = expectation(description: "debounced save window elapsed")
        DispatchQueue.main.asyncAfter(deadline: .now() + 1.7) {
            debounceFinished.fulfill()
        }
        await fulfillment(of: [debounceFinished], timeout: 2.5)

        XCTAssertFalse(
            FileManager.default.fileExists(atPath: url.path),
            "a pending save must not recreate a note after the watcher removed it")
        XCTAssertFalse(
            storage.noteList.contains(where: { $0 === note }),
            "a retired note must not add itself back to Storage")
    }

    @MainActor
    func testAtomicSwapRefusesMissingDestination() throws {
        let noteURL = tempDir.appendingPathComponent("missing-during-save.md")
        let replacementURL = tempDir.appendingPathComponent("replacement.md")
        try "original".write(to: noteURL, atomically: true, encoding: .utf8)
        try "replacement".write(to: replacementURL, atomically: true, encoding: .utf8)
        try FileManager.default.removeItem(at: noteURL)

        XCTAssertThrowsError(
            try Note.swapExistingFile(at: noteURL, with: replacementURL)
        )
        XCTAssertFalse(FileManager.default.fileExists(atPath: noteURL.path))
        XCTAssertTrue(FileManager.default.fileExists(atPath: replacementURL.path))
    }

    @MainActor
    func testMissingLoadedNoteCannotBeRecreatedBeforeWatcherRetiresIt() async throws {
        let url = tempDir.appendingPathComponent("missing-before-watcher.md")
        let quarantineURL = tempDir.appendingPathComponent("quarantined.md")
        try "original".write(to: url, atomically: true, encoding: .utf8)

        let project = Project(url: tempDir, label: "test", isRoot: true)
        let note = Note(url: url, with: project)
        note.save(attributed: NSAttributedString(string: "pending edit"))
        try FileManager.default.moveItem(at: url, to: quarantineURL)

        let debounceFinished = expectation(description: "debounced save window elapsed")
        DispatchQueue.main.asyncAfter(deadline: .now() + 1.7) {
            debounceFinished.fulfill()
        }
        await fulfillment(of: [debounceFinished], timeout: 2.5)

        XCTAssertFalse(
            FileManager.default.fileExists(atPath: url.path),
            "a save sink must fail closed while the watcher is still processing a missing file")
        XCTAssertTrue(note.needsSave, "the unsaved edit remains retryable if the file returns")
        XCTAssertFalse(note.flushPendingSave(globalStorage: false))
        XCTAssertFalse(FileManager.default.fileExists(atPath: url.path))
    }

    @MainActor
    func testTrashReconciliationRetiresAFileMissingFromDisk() throws {
        let url = tempDir.appendingPathComponent("missing-trash-note.md")
        let quarantineURL = tempDir.appendingPathComponent("quarantined-trash-note.md")
        try "trash content".write(to: url, atomically: true, encoding: .utf8)

        let project = Project(url: tempDir, label: "Trash", isTrash: true)
        let storage = Storage()
        let note = Note(url: url, with: project)
        note.sharedStorage = storage
        storage.add(note)
        try FileManager.default.moveItem(at: url, to: quarantineURL)

        storage.retireMissingNotes(in: project)

        XCTAssertFalse(storage.noteList.contains(where: { $0 === note }))
        note.save(attributed: NSAttributedString(string: "late editor callback"))
        XCTAssertFalse(note.flushPendingSave(globalStorage: false))
        XCTAssertFalse(FileManager.default.fileExists(atPath: url.path))
    }

    @MainActor
    func testProjectRescanDoesNotDuplicateCanonicallyEquivalentUnicodeFilename() throws {
        let projectURL = tempDir.appendingPathComponent("Unicode", isDirectory: true)
        try FileManager.default.createDirectory(
            at: projectURL,
            withIntermediateDirectories: true)

        let composedName = "Сергей.md".precomposedStringWithCanonicalMapping
        let fileURL = projectURL.appendingPathComponent(composedName)
        try "body".write(to: fileURL, atomically: true, encoding: .utf8)

        let enumeratedURL = try XCTUnwrap(
            FileManager.default.contentsOfDirectory(
                at: projectURL,
                includingPropertiesForKeys: nil
            ).first)
        let resolvedURL = enumeratedURL.standardizedFileURL.resolvingSymlinksInPath()
        XCTAssertFalse(
            enumeratedURL.absoluteString.utf8.elementsEqual(resolvedURL.absoluteString.utf8),
            "the fixture must exercise APFS composed/decomposed URL forms")

        let storage = Storage(storageURL: nil)
        let project = Project(url: projectURL, label: "Unicode", isRoot: true)
        _ = storage.add(project: project)
        let existingNote = Note(url: resolvedURL, with: project)
        existingNote.sharedStorage = storage
        storage.add(existingNote)

        storage.loadMissingNotes(for: project)

        XCTAssertEqual(storage.noteList.count, 1)
        XCTAssertTrue(storage.noteList.first === existingNote)
    }

    @MainActor
    func testRemovedTrashMarkerIsHiddenOnlyWhileInsideTrash() throws {
        let url = tempDir.appendingPathComponent("recoverable-system-trash-note.md")
        try "recoverable content".write(to: url, atomically: true, encoding: .utf8)

        let trashProject = Project(url: tempDir, label: "Trash", isTrash: true)
        let regularProject = Project(url: tempDir, label: "Restored")

        XCTAssertFalse(Storage.shouldHideRemovedTrashItem(at: url, in: trashProject))
        try url.setExtendedAttribute(
            data: Data([1]),
            forName: AppIdentifier.removedFromTrashKey)

        XCTAssertTrue(Storage.shouldHideRemovedTrashItem(at: url, in: trashProject))
        XCTAssertFalse(
            Storage.shouldHideRemovedTrashItem(at: url, in: regularProject),
            "Finder recovery into a normal project must make the note visible again")

        let storage = Storage()
        _ = storage.add(project: trashProject)
        XCTAssertNil(
            storage.initNote(url: url),
            "the file watcher must not re-import a marked system Trash item")
        let note = Note(url: url, with: trashProject)
        note.sharedStorage = storage
        storage.add(note)
        storage.retireMissingNotes(in: trashProject)

        XCTAssertFalse(storage.noteList.contains(where: { $0 === note }))
        XCTAssertTrue(
            FileManager.default.fileExists(atPath: url.path),
            "hiding from MiaoYan Trash must preserve Finder recoverability")
        note.save(attributed: NSAttributedString(string: "late callback"))
        XCTAssertFalse(note.flushPendingSave(globalStorage: false))
        XCTAssertEqual(try String(contentsOf: url, encoding: .utf8), "recoverable content")
    }

    @MainActor
    func testExplicitRemovalFlushesLatestContentAndRejectsLateSave() throws {
        let sourceURL = tempDir.appendingPathComponent("to-delete.md")
        let trashURL = tempDir.appendingPathComponent("Trash", isDirectory: true)
        try FileManager.default.createDirectory(at: trashURL, withIntermediateDirectories: true)
        try "original".write(to: sourceURL, atomically: true, encoding: .utf8)

        let storage = Storage()
        if let existingTrash = storage.getDefaultTrash() {
            storage.removeBy(project: existingTrash)
        }
        _ = storage.add(project: Project(url: trashURL, isTrash: true))

        let previousStorage = Storage.instance
        Storage.instance = storage
        defer { Storage.instance = previousStorage }

        let project = Project(url: tempDir, label: "test", isRoot: true)
        let note = Note(url: sourceURL, with: project)
        note.sharedStorage = storage
        storage.add(note)
        note.save(attributed: NSAttributedString(string: "latest edit"))

        var removedURLs: [URL: URL]?
        var removedNotes = [Note]()
        storage.removeNotes(
            notes: [note],
            didRemove: { removedNotes = $0 }
        ) { removedURLs = $0 }

        let movedURL = try XCTUnwrap(removedURLs?.keys.first)
        XCTAssertEqual(removedNotes.count, 1)
        XCTAssertTrue(removedNotes.first === note)
        XCTAssertEqual(try String(contentsOf: movedURL, encoding: .utf8), "latest edit")
        XCTAssertFalse(note.hasPendingSave)
        XCTAssertFalse(storage.noteList.contains(where: { $0 === note }))

        note.save(attributed: NSAttributedString(string: "late callback"))
        note.flushPendingSave(globalStorage: false)

        XCTAssertEqual(
            try String(contentsOf: movedURL, encoding: .utf8),
            "latest edit",
            "callbacks holding the removed Note must not mutate its Trash copy")
    }

    @MainActor
    func testFailedRemovalKeepsTheNoteWritable() throws {
        let sourceURL = tempDir.appendingPathComponent("failed-delete.md")
        try "original".write(to: sourceURL, atomically: true, encoding: .utf8)

        let storage = Storage()
        if let existingTrash = storage.getDefaultTrash() {
            storage.removeBy(project: existingTrash)
        }
        let missingTrashURL = tempDir.appendingPathComponent("missing/Trash", isDirectory: true)
        _ = storage.add(project: Project(url: missingTrashURL, isTrash: true))

        let previousStorage = Storage.instance
        Storage.instance = storage
        defer { Storage.instance = previousStorage }

        let project = Project(url: tempDir, label: "test", isRoot: true)
        let note = Note(url: sourceURL, with: project)
        note.sharedStorage = storage
        storage.add(note)
        note.save(attributed: NSAttributedString(string: "latest before failure"))

        var failedCount = 0
        var removedNotes = [Note]()
        storage.removeNotes(
            notes: [note],
            partialFailure: { failedCount = $0 },
            didRemove: { removedNotes = $0 }
        ) { _ in }

        XCTAssertEqual(failedCount, 1)
        XCTAssertTrue(removedNotes.isEmpty)
        XCTAssertTrue(storage.noteList.contains(where: { $0 === note }))
        XCTAssertEqual(try String(contentsOf: sourceURL, encoding: .utf8), "latest before failure")

        note.save(attributed: NSAttributedString(string: "edit after failure"))
        note.flushPendingSave(globalStorage: false)
        XCTAssertEqual(try String(contentsOf: sourceURL, encoding: .utf8), "edit after failure")
    }

    @MainActor
    func testTrashOriginMetadataRestoresOriginalNestedLocation() throws {
        let rootURL = tempDir.appendingPathComponent("Library", isDirectory: true)
        let nestedURL = rootURL.appendingPathComponent("Projects/Ideas", isDirectory: true)
        let sourceURL = nestedURL.appendingPathComponent("Concept.md")
        let trashURL = tempDir.appendingPathComponent("Trash", isDirectory: true)
        try FileManager.default.createDirectory(at: nestedURL, withIntermediateDirectories: true)
        try FileManager.default.createDirectory(at: trashURL, withIntermediateDirectories: true)
        try "body".write(to: sourceURL, atomically: true, encoding: .utf8)

        let data = try XCTUnwrap(Storage.trashOriginMetadataData(for: sourceURL, root: rootURL))
        let metadata = try XCTUnwrap(Storage.trashOriginMetadata(from: data))
        let trashedURL = trashURL.appendingPathComponent("Concept.md")
        try FileManager.default.moveItem(at: sourceURL, to: trashedURL)
        let destination = Storage.trashRestoreDestination(
            for: trashedURL,
            metadata: metadata,
            availableRoots: [rootURL],
            defaultRoot: rootURL)

        XCTAssertEqual(metadata.relativePath, "Projects/Ideas/Concept.md")
        XCTAssertEqual(destination.fileURL, sourceURL)
        XCTAssertTrue(destination.usesOriginalFolder)
    }

    @MainActor
    func testTrashRestoreFallsBackToRootAndNeverOverwrites() throws {
        let rootURL = tempDir.appendingPathComponent("Library", isDirectory: true)
        try FileManager.default.createDirectory(at: rootURL, withIntermediateDirectories: true)
        let existingURL = rootURL.appendingPathComponent("Concept.md")
        try "existing".write(to: existingURL, atomically: true, encoding: .utf8)
        let metadata = TrashOriginMetadata(
            rootPath: rootURL.resolvingSymlinksInPath().path,
            relativePath: "Missing/Concept.md")

        let destination = Storage.trashRestoreDestination(
            for: tempDir.appendingPathComponent("Trash/Concept.md"),
            metadata: metadata,
            availableRoots: [rootURL],
            defaultRoot: rootURL)

        XCTAssertEqual(destination.fileURL, rootURL.appendingPathComponent("Concept 1.md"))
        XCTAssertFalse(destination.usesOriginalFolder)
        XCTAssertEqual(try String(contentsOf: existingURL, encoding: .utf8), "existing")
    }

    @MainActor
    func testTrashRestoreRejectsTraversalMetadata() throws {
        let rootURL = tempDir.appendingPathComponent("Library", isDirectory: true)
        try FileManager.default.createDirectory(at: rootURL, withIntermediateDirectories: true)
        let metadata = TrashOriginMetadata(
            rootPath: rootURL.resolvingSymlinksInPath().path,
            relativePath: "../Outside.md")

        let destination = Storage.trashRestoreDestination(
            for: tempDir.appendingPathComponent("Trash/Safe.md"),
            metadata: metadata,
            availableRoots: [rootURL],
            defaultRoot: rootURL)

        XCTAssertEqual(destination.fileURL, rootURL.appendingPathComponent("Safe.md"))
        XCTAssertFalse(destination.usesOriginalFolder)
    }

    @MainActor
    func testSoftDeletePersistsOriginAndRestoreMovesFreshTrashNoteBack() throws {
        let rootURL = tempDir.appendingPathComponent("Library", isDirectory: true)
        let nestedURL = rootURL.appendingPathComponent("Ideas", isDirectory: true)
        let trashURL = rootURL.appendingPathComponent("Trash", isDirectory: true)
        let sourceURL = nestedURL.appendingPathComponent("Restore Me.md")
        try FileManager.default.createDirectory(at: nestedURL, withIntermediateDirectories: true)
        try FileManager.default.createDirectory(at: trashURL, withIntermediateDirectories: true)
        try "recoverable".write(to: sourceURL, atomically: true, encoding: .utf8)

        let storage = Storage()
        for project in storage.getProjects() {
            storage.removeBy(project: project)
        }
        let rootProject = Project(url: rootURL, label: "Library", isRoot: true, isDefault: true)
        let nestedProject = Project(url: nestedURL, label: "Ideas", parent: rootProject)
        let trashProject = Project(url: trashURL, label: "Trash", isTrash: true)
        _ = storage.add(project: rootProject)
        _ = storage.add(project: nestedProject)
        _ = storage.add(project: trashProject)

        let previousStorage = Storage.instance
        Storage.instance = storage
        defer { Storage.instance = previousStorage }

        let sourceNote = Note(url: sourceURL, with: nestedProject)
        sourceNote.sharedStorage = storage
        storage.add(sourceNote)
        var movedURL: URL?
        storage.removeNotes(notes: [sourceNote]) { movedURL = $0?.keys.first }

        let trashedURL = try XCTUnwrap(movedURL)
        XCTAssertNotNil(try? trashedURL.extendedAttribute(forName: AppIdentifier.trashOriginKey))

        let trashNote = Note(url: trashedURL, with: trashProject)
        trashNote.sharedStorage = storage
        storage.add(trashNote)
        XCTAssertTrue(FileManager.default.fileExists(atPath: trashedURL.path))
        XCTAssertTrue(trashNote.isTrash())
        XCTAssertTrue(trashNote.flushPendingSave(globalStorage: false))
        let result = storage.restoreNotesFromTrash([trashNote])

        XCTAssertEqual(result.failedCount, 0)
        XCTAssertEqual(result.restored.count, 1)
        XCTAssertEqual(trashNote.url, sourceURL)
        XCTAssertEqual(trashNote.project, nestedProject)
        XCTAssertEqual(try String(contentsOf: sourceURL, encoding: .utf8), "recoverable")
        XCTAssertNil(try? sourceURL.extendedAttribute(forName: AppIdentifier.trashOriginKey))

        sourceNote.save(attributed: NSAttributedString(string: "late callback"))
        XCTAssertFalse(sourceNote.flushPendingSave(globalStorage: false))
        XCTAssertEqual(try String(contentsOf: sourceURL, encoding: .utf8), "recoverable")
    }

    @MainActor
    func testPermanentDeleteRemovesMarkedSystemTrashNoteAndRejectsLateSave() throws {
        let trashURL = tempDir.appendingPathComponent("Trash", isDirectory: true)
        let noteURL = trashURL.appendingPathComponent("Delete Forever.md")
        try FileManager.default.createDirectory(at: trashURL, withIntermediateDirectories: true)
        try "original".write(to: noteURL, atomically: true, encoding: .utf8)
        try noteURL.setExtendedAttribute(
            data: Data([1]),
            forName: AppIdentifier.removedFromTrashKey)

        let storage = Storage()
        let trashProject = Project(url: trashURL, label: "Trash", isTrash: true)
        let note = Note(url: noteURL, with: trashProject)
        note.sharedStorage = storage
        storage.add(note)
        note.save(attributed: NSAttributedString(string: "latest"))

        var removed = [Note]()
        storage.removeNotes(
            notes: [note],
            completely: true,
            didRemove: { removed = $0 }
        ) { _ in }

        XCTAssertEqual(removed.count, 1)
        XCTAssertFalse(FileManager.default.fileExists(atPath: noteURL.path))
        XCTAssertFalse(storage.noteList.contains(where: { $0 === note }))

        note.save(attributed: NSAttributedString(string: "late callback"))
        XCTAssertFalse(note.flushPendingSave(globalStorage: false))
        XCTAssertFalse(FileManager.default.fileExists(atPath: noteURL.path))
    }

    @MainActor
    func testSyncedTrashRoundTripsThroughMacRestoreAndRemovesManifestEntry() throws {
        let rootURL = tempDir.appendingPathComponent("Library", isDirectory: true)
        let nestedURL = rootURL.appendingPathComponent("Идеи", isDirectory: true)
        let sourceURL = nestedURL.appendingPathComponent("22 топ.md")
        try FileManager.default.createDirectory(at: nestedURL, withIntermediateDirectories: true)
        try "recoverable".write(to: sourceURL, atomically: true, encoding: .utf8)

        let configurationStore = try configuredGitSyncStore(for: rootURL)
        let storage = Storage(storageURL: nil, gitSyncConfigurationStore: configurationStore)
        let root = Project(url: rootURL, label: "Library", isRoot: true, isDefault: true)
        _ = storage.add(project: root)

        let trashedURL = try storage.moveToSyncedTrash(fileURL: sourceURL, root: root)
        XCTAssertFalse(FileManager.default.fileExists(atPath: sourceURL.path))
        XCTAssertTrue(FileManager.default.fileExists(atPath: trashedURL.path))

        storage.reLoadTrash()
        let trashNote = try XCTUnwrap(storage.getAllTrash().first)
        trashNote.sharedStorage = storage
        let result = storage.restoreNotesFromTrash([trashNote])

        XCTAssertEqual(result.failedCount, 0)
        XCTAssertEqual(result.restored.count, 1)
        XCTAssertEqual(trashNote.url, sourceURL)
        XCTAssertEqual(try String(contentsOf: sourceURL, encoding: .utf8), "recoverable")
        let manifestURL = rootURL.appendingPathComponent(GitSyncedTrashManifestCodec.relativePath)
        let manifest = try String(contentsOf: manifestURL, encoding: .utf8)
        XCTAssertTrue(GitSyncedTrashManifestCodec.decode(manifest).isEmpty)
    }

    @MainActor
    func testSyncedTrashRefusesATrashDirectorySymlink() throws {
        let rootURL = tempDir.appendingPathComponent("Library", isDirectory: true)
        let outsideURL = tempDir.appendingPathComponent("Outside", isDirectory: true)
        let sourceURL = rootURL.appendingPathComponent("note.md")
        try FileManager.default.createDirectory(at: rootURL, withIntermediateDirectories: true)
        try FileManager.default.createDirectory(at: outsideURL, withIntermediateDirectories: true)
        try "recoverable".write(to: sourceURL, atomically: true, encoding: .utf8)
        try FileManager.default.createSymbolicLink(
            at: rootURL.appendingPathComponent(".Trash"),
            withDestinationURL: outsideURL)

        let configurationStore = try configuredGitSyncStore(for: rootURL)
        let storage = Storage(storageURL: nil, gitSyncConfigurationStore: configurationStore)
        let root = Project(url: rootURL, label: "Library", isRoot: true, isDefault: true)

        XCTAssertThrowsError(try storage.moveToSyncedTrash(fileURL: sourceURL, root: root))
        XCTAssertTrue(FileManager.default.fileExists(atPath: sourceURL.path))
        XCTAssertFalse(FileManager.default.fileExists(atPath: outsideURL.appendingPathComponent("items").path))
    }

    @MainActor
    func testSyncedTrashRemainsInactiveWithoutGitConfiguration() throws {
        let rootURL = tempDir.appendingPathComponent("Library", isDirectory: true)
        let itemID = "123e4567-e89b-12d3-a456-426614174000"
        let itemDirectory = rootURL.appendingPathComponent(".Trash/items/\(itemID)", isDirectory: true)
        let trashedURL = itemDirectory.appendingPathComponent("Remote.md")
        let liveURL = rootURL.appendingPathComponent("Live.md")
        try FileManager.default.createDirectory(at: itemDirectory, withIntermediateDirectories: true)
        try "remote".write(to: trashedURL, atomically: true, encoding: .utf8)
        try "live".write(to: liveURL, atomically: true, encoding: .utf8)
        let entry = GitSyncedTrashManifestEntry(
            id: itemID,
            originalRelativePath: "Remote.md",
            trashRelativePath: ".Trash/items/\(itemID)/Remote.md",
            deletedAtMilliseconds: 1_788_862_000_000)
        try GitSyncedTrashManifestCodec.encode([entry]).write(
            to: rootURL.appendingPathComponent(GitSyncedTrashManifestCodec.relativePath),
            atomically: true,
            encoding: .utf8)

        let storage = Storage(storageURL: nil)
        let root = Project(url: rootURL, label: "Library", isRoot: true, isDefault: true)
        _ = storage.add(project: root)

        storage.reLoadTrash()

        XCTAssertTrue(storage.getAllTrash().isEmpty)
        XCTAssertThrowsError(try storage.moveToSyncedTrash(fileURL: liveURL, root: root))
        XCTAssertTrue(FileManager.default.fileExists(atPath: liveURL.path))
    }

    @MainActor
    func testSyncedTrashTransportProjectDoesNotAppearAsItemsCategory() throws {
        let rootURL = tempDir.appendingPathComponent("Library", isDirectory: true)
        let sourceURL = rootURL.appendingPathComponent("Delete me.md")
        try FileManager.default.createDirectory(at: rootURL, withIntermediateDirectories: true)
        try "recoverable".write(to: sourceURL, atomically: true, encoding: .utf8)

        let configurationStore = try configuredGitSyncStore(for: rootURL)
        let storage = Storage(storageURL: nil, gitSyncConfigurationStore: configurationStore)
        let root = Project(url: rootURL, label: "Library", isRoot: true, isDefault: true)
        _ = storage.add(project: root)
        let note = Note(url: sourceURL, with: root)
        note.sharedStorage = storage
        storage.add(note)

        storage.removeNotes(notes: [note]) { _ in }

        let sidebarItems = Sidebar(storage: storage).getList().compactMap { $0 as? SidebarItem }
        XCTAssertFalse(sidebarItems.contains { $0.type == .Category && $0.project?.url.lastPathComponent == "items" })
        XCTAssertTrue(sidebarItems.contains(where: { $0.type == .Trash }))
    }

    @MainActor
    func testSyncedTrashRepairsMissingNoteEntryButPreservesExistingFolderEntry() throws {
        let rootURL = tempDir.appendingPathComponent("Library", isDirectory: true)
        let missingID = "123e4567-e89b-12d3-a456-426614174000"
        let folderID = "223e4567-e89b-12d3-a456-426614174000"
        let folderPayload = rootURL.appendingPathComponent(
            ".Trash/items/\(folderID)/Archived Folder",
            isDirectory: true)
        try FileManager.default.createDirectory(at: folderPayload, withIntermediateDirectories: true)
        try "# Nested".write(
            to: folderPayload.appendingPathComponent("Nested.md"),
            atomically: true,
            encoding: .utf8)
        let entries = [
            GitSyncedTrashManifestEntry(
                id: missingID,
                originalRelativePath: "Missing.md",
                trashRelativePath: ".Trash/items/\(missingID)/Missing.md",
                deletedAtMilliseconds: 1_788_862_000_000),
            GitSyncedTrashManifestEntry(
                id: folderID,
                originalRelativePath: "Archived Folder",
                trashRelativePath: ".Trash/items/\(folderID)/Archived Folder",
                deletedAtMilliseconds: 1_788_862_100_000),
        ]
        let manifestURL = rootURL.appendingPathComponent(GitSyncedTrashManifestCodec.relativePath)
        try GitSyncedTrashManifestCodec.encode(entries).write(
            to: manifestURL,
            atomically: true,
            encoding: .utf8)

        let configurationStore = try configuredGitSyncStore(for: rootURL)
        let storage = Storage(storageURL: nil, gitSyncConfigurationStore: configurationStore)
        let root = Project(url: rootURL, label: "Library", isRoot: true, isDefault: true)
        _ = storage.add(project: root)

        storage.reLoadTrash()

        let repaired = GitSyncedTrashManifestCodec.decode(try String(contentsOf: manifestURL, encoding: .utf8))
        XCTAssertEqual(repaired, [entries[1]])
    }

    @MainActor
    func testConfiguredDeleteImmediatelyUpdatesSyncedTrashAndUndoManifest() throws {
        let rootURL = tempDir.appendingPathComponent("Library", isDirectory: true)
        let sourceURL = rootURL.appendingPathComponent("Delete me.md")
        try FileManager.default.createDirectory(at: rootURL, withIntermediateDirectories: true)
        try "recoverable".write(to: sourceURL, atomically: true, encoding: .utf8)

        let configurationStore = try configuredGitSyncStore(for: rootURL)
        let storage = Storage(storageURL: nil, gitSyncConfigurationStore: configurationStore)
        let root = Project(url: rootURL, label: "Library", isRoot: true, isDefault: true)
        _ = storage.add(project: root)
        let note = Note(url: sourceURL, with: root)
        note.sharedStorage = storage
        storage.add(note)
        var undoURLs: [URL: URL]?

        storage.removeNotes(notes: [note]) { undoURLs = $0 }

        XCTAssertEqual(storage.getAllTrash().count, 1)
        let trashNote = try XCTUnwrap(storage.getAllTrash().first)
        XCTAssertFalse(FileManager.default.fileExists(atPath: sourceURL.path))
        XCTAssertTrue(FileManager.default.fileExists(atPath: trashNote.url.path))
        let manifestURL = rootURL.appendingPathComponent(GitSyncedTrashManifestCodec.relativePath)
        XCTAssertEqual(
            GitSyncedTrashManifestCodec.decode(try String(contentsOf: manifestURL, encoding: .utf8)).count,
            1)

        let undo = try XCTUnwrap(undoURLs?.first)
        try storage.restoreTrashMoveForUndo(from: undo.key, to: undo.value)

        XCTAssertEqual(undo.value, sourceURL)
        XCTAssertTrue(FileManager.default.fileExists(atPath: sourceURL.path))
        XCTAssertTrue(
            GitSyncedTrashManifestCodec.decode(try String(contentsOf: manifestURL, encoding: .utf8)).isEmpty)
    }

    @MainActor
    func testPermanentDeleteRemovesSyncedTrashPayloadAndManifestEntry() throws {
        let rootURL = tempDir.appendingPathComponent("Library", isDirectory: true)
        let sourceURL = rootURL.appendingPathComponent("Delete forever.md")
        try FileManager.default.createDirectory(at: rootURL, withIntermediateDirectories: true)
        try "recoverable".write(to: sourceURL, atomically: true, encoding: .utf8)

        let configurationStore = try configuredGitSyncStore(for: rootURL)
        let storage = Storage(storageURL: nil, gitSyncConfigurationStore: configurationStore)
        let root = Project(url: rootURL, label: "Library", isRoot: true, isDefault: true)
        _ = storage.add(project: root)
        let note = Note(url: sourceURL, with: root)
        note.sharedStorage = storage
        storage.add(note)
        storage.removeNotes(notes: [note]) { _ in }
        let trashNote = try XCTUnwrap(storage.getAllTrash().first)
        trashNote.sharedStorage = storage
        let trashPayloadURL = trashNote.url

        storage.removeNotes(notes: [trashNote], completely: true) { _ in }

        XCTAssertFalse(FileManager.default.fileExists(atPath: trashPayloadURL.path))
        XCTAssertTrue(storage.getAllTrash().isEmpty)
        let manifestURL = rootURL.appendingPathComponent(GitSyncedTrashManifestCodec.relativePath)
        XCTAssertTrue(
            GitSyncedTrashManifestCodec.decode(try String(contentsOf: manifestURL, encoding: .utf8)).isEmpty)
    }
}
