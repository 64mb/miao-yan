import Foundation
import FileProvider

enum GitSyncEntryKind: String, Equatable, Sendable {
    case regularFile
    case symbolicLink
    case submodule
}

enum GitSyncChangeKind: String, Equatable, Sendable {
    case added
    case modified
    case deleted
    case renamed
}

struct GitSyncChange: Equatable, Sendable {
    let kind: GitSyncChangeKind
    let path: String
    let previousPath: String?
    let entryKind: GitSyncEntryKind
    let byteCount: Int64?

    init(
        kind: GitSyncChangeKind,
        path: String,
        previousPath: String? = nil,
        entryKind: GitSyncEntryKind = .regularFile,
        byteCount: Int64? = nil
    ) {
        self.kind = kind
        self.path = path
        self.previousPath = previousPath
        self.entryKind = entryKind
        self.byteCount = byteCount
    }

    var affectedPaths: [String] {
        if let previousPath, previousPath != path { return [previousPath, path] }
        return [path]
    }
}

enum GitSyncManagedPathKind: String, Equatable, Sendable {
    case note
    case attachment
    case repositoryConfiguration
}

enum GitSyncPathRejectionReason: String, Equatable, Sendable {
    case emptyPath
    case absolutePath
    case pathTraversal
    case malformedPath
    case hiddenPath
    case trashPath
    case nonRegularEntry
    case unsupportedFile
    case oversizedAttachment
}

struct GitSyncPathViolation: Error, Equatable, Sendable {
    let path: String
    let reason: GitSyncPathRejectionReason
}

enum GitSyncPathDecision: Equatable, Sendable {
    case allowed(GitSyncManagedPathKind)
    case rejected(GitSyncPathViolation)
}

/// Only exact root projects that MiaoYan already opened are eligible. This
/// deliberately rejects descendants and same-prefix sibling directories.
struct GitSyncRepositoryRootPolicy: Sendable {
    private let allowedCanonicalPaths: Set<String>

    init(allowedRoots: [URL]) {
        allowedCanonicalPaths = Set(allowedRoots.compactMap(Self.canonicalPath))
    }

    func allows(_ candidate: URL) -> Bool {
        guard let candidatePath = Self.canonicalPath(candidate) else { return false }
        return allowedCanonicalPaths.contains(candidatePath)
    }

    private static func canonicalPath(_ url: URL) -> String? {
        guard url.isFileURL else { return nil }
        return url.standardizedFileURL.resolvingSymlinksInPath().path
    }
}

/// Lexical preflight for both local status and incoming tree diffs. Symlinks,
/// submodules, hidden areas, Trash and checkout-affecting configuration are
/// rejected before libgit2 is allowed to mutate the worktree.
struct GitSyncPathPolicy: Sendable {
    private let noteExtensions: Set<String>
    private let attachmentDirectoryNames: Set<String>
    private let repositoryConfigurationNames: Set<String>
    private let maximumPathByteCount: Int
    private let maximumAttachmentBytes: Int64?

    init(
        noteExtensions: Set<String> = ["md", "markdown", "txt"],
        attachmentDirectoryNames: Set<String> = ["i", "files"],
        repositoryConfigurationNames: Set<String> = [".gitignore"],
        maximumPathByteCount: Int = 4096,
        maximumAttachmentBytes: Int64? = nil
    ) {
        self.noteExtensions = Set(noteExtensions.map { $0.lowercased() })
        self.attachmentDirectoryNames = attachmentDirectoryNames
        self.repositoryConfigurationNames = repositoryConfigurationNames
        self.maximumPathByteCount = maximumPathByteCount
        self.maximumAttachmentBytes = maximumAttachmentBytes
    }

    func classify(relativePath path: String, entryKind: GitSyncEntryKind = .regularFile) -> GitSyncPathDecision {
        guard !path.isEmpty else { return reject(path, because: .emptyPath) }
        guard path.utf8.count <= maximumPathByteCount,
            !path.unicodeScalars.contains(where: CharacterSet.controlCharacters.contains),
            !path.contains("\\")
        else {
            return reject(path, because: .malformedPath)
        }
        guard !path.hasPrefix("/"), path != "~", !path.hasPrefix("~/") else {
            return reject(path, because: .absolutePath)
        }
        guard entryKind == .regularFile else {
            return reject(path, because: .nonRegularEntry)
        }

        let components = path.split(separator: "/", omittingEmptySubsequences: false).map(String.init)
        guard !components.contains(where: { $0.isEmpty }) else {
            return reject(path, because: .malformedPath)
        }
        guard !components.contains("."), !components.contains("..") else {
            return reject(path, because: .pathTraversal)
        }
        guard !components.contains(where: isTrashComponent) else {
            return reject(path, because: .trashPath)
        }

        let leaf = components[components.count - 1]
        let parents = components.dropLast()
        guard !parents.contains(where: { $0.hasPrefix(".") }) else {
            return reject(path, because: .hiddenPath)
        }
        if repositoryConfigurationNames.contains(leaf) {
            return .allowed(.repositoryConfiguration)
        }
        guard !leaf.hasPrefix(".") else { return reject(path, because: .hiddenPath) }
        if parents.contains(where: attachmentDirectoryNames.contains) {
            return .allowed(.attachment)
        }
        if noteExtensions.contains((leaf as NSString).pathExtension.lowercased()) {
            return .allowed(.note)
        }
        return reject(path, because: .unsupportedFile)
    }

    func violations(for changes: [GitSyncChange]) -> [GitSyncPathViolation] {
        changes.flatMap { change in
            change.affectedPaths.compactMap { path in
                let decision = classify(relativePath: path, entryKind: change.entryKind)
                if case .rejected(let violation) = decision {
                    return violation
                }
                if case .allowed(.attachment) = decision,
                    change.kind != .deleted,
                    let maximumAttachmentBytes,
                    let byteCount = change.byteCount,
                    byteCount > maximumAttachmentBytes
                {
                    return GitSyncPathViolation(path: path, reason: .oversizedAttachment)
                }
                return nil
            }
        }
    }

    private func isTrashComponent(_ component: String) -> Bool {
        component.caseInsensitiveCompare("Trash") == .orderedSame
            || component.caseInsensitiveCompare(".Trash") == .orderedSame
    }

    private func reject(_ path: String, because reason: GitSyncPathRejectionReason) -> GitSyncPathDecision {
        .rejected(GitSyncPathViolation(path: path, reason: reason))
    }
}

struct GitSyncChangeSummary: Equatable, Sendable {
    let added: Int
    let modified: Int
    let deleted: Int
    let renamed: Int
    let notePaths: [String]
    let attachmentPaths: [String]

    init(changes: [GitSyncChange], policy: GitSyncPathPolicy = GitSyncPathPolicy()) {
        added = changes.count(where: { $0.kind == .added })
        modified = changes.count(where: { $0.kind == .modified })
        deleted = changes.count(where: { $0.kind == .deleted })
        renamed = changes.count(where: { $0.kind == .renamed })
        var notes = Set<String>()
        var attachments = Set<String>()
        for change in changes {
            for path in change.affectedPaths {
                switch policy.classify(relativePath: path, entryKind: change.entryKind) {
                case .allowed(.note): notes.insert(path)
                case .allowed(.attachment): attachments.insert(path)
                case .allowed(.repositoryConfiguration), .rejected: break
                }
            }
        }
        notePaths = notes.sorted()
        attachmentPaths = attachments.sorted()
    }

    var totalChanges: Int { added + modified + deleted + renamed }
}

struct GitSyncConflictFile: Equatable, Sendable {
    let path: String
    let localModifiedAt: Date?
    let remoteModifiedAt: Date?
    let ancestorText: String?
    let localText: String?
    let remoteText: String?
    let localExists: Bool
    let remoteExists: Bool

    var canResolveWithAI: Bool {
        localExists && remoteExists && localText != nil && remoteText != nil
    }
}

struct GitSyncConflictContext: Equatable, Sendable {
    let localRevision: String
    let remoteRevision: String
    let files: [GitSyncConflictFile]
    let recoveryRevision: String?
}

enum GitSyncConflictChoice: Equatable, Sendable {
    case local
    case remote
    case mergedText(String)
}

struct GitSyncConflictResolution: Equatable, Sendable {
    let path: String
    let choice: GitSyncConflictChoice
}

enum GitSyncUnrelatedHistoryResolution: Equatable, Sendable {
    case keepLocal
    case replaceLocalWithRemote
}

enum GitSyncLibraryLocationPolicy {
    private final class DetectionGate: @unchecked Sendable {
        private let lock = NSLock()
        private var completed = false

        func resume(_ continuation: CheckedContinuation<Bool, Never>, with value: Bool) {
            lock.lock()
            guard !completed else {
                lock.unlock()
                return
            }
            completed = true
            lock.unlock()
            continuation.resume(returning: value)
        }
    }

    static func isCloudBacked(_ url: URL, fileManager: FileManager = .default) async -> Bool {
        let resolved = url.standardizedFileURL.resolvingSymlinksInPath()
        if fileManager.isUbiquitousItem(at: resolved) || isKnownCloudPath(resolved) { return true }
        if let values = try? resolved.resourceValues(forKeys: [.volumeIsLocalKey]), values.volumeIsLocal == false {
            return true
        }
        return await withCheckedContinuation { continuation in
            let gate = DetectionGate()
            NSFileProviderManager.getIdentifierForUserVisibleFile(at: resolved) { itemIdentifier, domainIdentifier, error in
                if itemIdentifier != nil || domainIdentifier != nil {
                    gate.resume(continuation, with: true)
                } else if let error = error as NSError?,
                    error.domain == NSCocoaErrorDomain,
                    error.code == CocoaError.fileNoSuchFile.rawValue
                {
                    // The documented response for an ordinary local URL.
                    // Known provider roots and ubiquitous items were already
                    // rejected above before this membership query.
                    gate.resume(continuation, with: false)
                } else {
                    // Permission/transient/undocumented failures are unknown,
                    // so location policy must fail closed.
                    gate.resume(continuation, with: true)
                }
            }
            DispatchQueue.global().asyncAfter(deadline: .now() + 2) {
                // Unknown ownership must fail closed: enabling Git inside a
                // slow File Provider is more dangerous than asking to move a
                // local folder unnecessarily.
                gate.resume(continuation, with: true)
            }
        }
    }

    static func isKnownCloudPath(_ url: URL, homeDirectory: URL = FileManager.default.homeDirectoryForCurrentUser) -> Bool {
        let path = url.standardizedFileURL.resolvingSymlinksInPath().path
        let home = homeDirectory.standardizedFileURL.resolvingSymlinksInPath().path
        let roots = [
            home + "/Library/Mobile Documents",
            home + "/Library/CloudStorage",
            home + "/Dropbox",
            home + "/Google Drive",
            home + "/OneDrive",
        ]
        return roots.contains { path == $0 || path.hasPrefix($0 + "/") }
            || path.hasPrefix(home + "/OneDrive-")
    }
}

enum GitSyncLibraryMigrationError: LocalizedError {
    case destinationOccupied(URL)
    case sourceMissing
    case verificationFailed(String)
    case originalTrashFailed(String)

    var errorDescription: String? {
        switch self {
        case .destinationOccupied(let url): return "The local Git library destination already exists: \(url.path)"
        case .sourceMissing: return "The current library folder no longer exists."
        case .verificationFailed(let path): return "The migrated library could not be verified: \(path)"
        case .originalTrashFailed(let message): return "The verified local copy is ready, but the original could not be moved to Trash: \(message)"
        }
    }
}

struct GitSyncLibraryMigrationResult: Sendable {
    let destination: URL
}

enum GitSyncLibraryMutationGate {
    private final class State: @unchecked Sendable {
        let lock = NSLock()
        var migratingRoot: URL?
        var gitOperationRoot: URL?
        var activeUploads = 0
    }

    private static let state = State()

    static func beginUpload() -> Bool {
        state.lock.lock()
        defer { state.lock.unlock() }
        guard state.migratingRoot == nil, state.gitOperationRoot == nil else { return false }
        state.activeUploads += 1
        return true
    }

    static func endUpload() {
        state.lock.lock()
        state.activeUploads = max(0, state.activeUploads - 1)
        state.lock.unlock()
    }

    static func beginMigration(of root: URL) -> Bool {
        state.lock.lock()
        defer { state.lock.unlock() }
        guard state.migratingRoot == nil, state.gitOperationRoot == nil, state.activeUploads == 0 else { return false }
        state.migratingRoot = root.standardizedFileURL.resolvingSymlinksInPath()
        return true
    }

    static func endMigration() {
        state.lock.lock()
        state.migratingRoot = nil
        state.lock.unlock()
    }

    static func beginGitOperation(of root: URL) -> Bool {
        state.lock.lock()
        defer { state.lock.unlock() }
        guard state.migratingRoot == nil, state.gitOperationRoot == nil, state.activeUploads == 0 else { return false }
        state.gitOperationRoot = root.standardizedFileURL.resolvingSymlinksInPath()
        return true
    }

    static func endGitOperation() {
        state.lock.lock()
        state.gitOperationRoot = nil
        state.lock.unlock()
    }

    static func allowsMutation(at url: URL) -> Bool {
        state.lock.lock()
        let protectedRoot = state.migratingRoot ?? state.gitOperationRoot
        state.lock.unlock()
        guard let protectedRoot else { return true }
        let rootPath = protectedRoot.path
        let candidatePath = url.standardizedFileURL.resolvingSymlinksInPath().path
        return candidatePath != rootPath && !candidatePath.hasPrefix(rootPath + "/")
    }
}

enum GitSyncLibraryMigrator {
    static func destinationURL(fileManager: FileManager = .default) throws -> URL {
        let applicationSupport = try fileManager.url(
            for: .applicationSupportDirectory,
            in: .userDomainMask,
            appropriateFor: nil,
            create: true
        )
        return applicationSupport.appendingPathComponent("MiaoYan/GitLibrary", isDirectory: true)
    }

    static func migrate(
        source: URL,
        fileManager: FileManager = .default,
        destination destinationOverride: URL? = nil
    ) throws -> GitSyncLibraryMigrationResult {
        let source = source.standardizedFileURL.resolvingSymlinksInPath()
        guard fileManager.directoryExists(atUrl: source) else { throw GitSyncLibraryMigrationError.sourceMissing }
        let destination = try (destinationOverride ?? destinationURL(fileManager: fileManager))
            .standardizedFileURL
            .resolvingSymlinksInPath()
        if fileManager.fileExists(atPath: destination.path) {
            do {
                try verifyCopy(source: source, destination: destination, fileManager: fileManager)
                return GitSyncLibraryMigrationResult(destination: destination)
            } catch {
                throw GitSyncLibraryMigrationError.destinationOccupied(destination)
            }
        }
        let parent = destination.deletingLastPathComponent()
        try fileManager.createDirectory(at: parent, withIntermediateDirectories: true)
        let staging = parent.appendingPathComponent(".GitLibrary-migrating-\(UUID().uuidString)", isDirectory: true)
        do {
            try fileManager.copyItem(at: source, to: staging)
            try verifyCopy(source: source, destination: staging, fileManager: fileManager)
            try fileManager.moveItem(at: staging, to: destination)
        } catch {
            try? fileManager.removeItem(at: staging)
            throw error
        }

        return GitSyncLibraryMigrationResult(destination: destination)
    }

    static func trashOriginal(
        _ source: URL,
        verifiedCopyAt destination: URL,
        ownedICloudLibraryRoot: URL?,
        fileManager: FileManager = .default
    ) throws -> Bool {
        guard
            canTrashOriginal(
                source,
                ownedICloudLibraryRoot: ownedICloudLibraryRoot,
                fileManager: fileManager
            )
        else { return false }

        var coordinationError: NSError?
        var operationError: Error?
        var trashed = false
        var verificationSucceeded = false
        let coordinator = NSFileCoordinator(filePresenter: nil)
        coordinator.coordinate(
            writingItemAt: source,
            options: .forDeleting,
            error: &coordinationError
        ) { coordinatedSource in
            do {
                // Re-read the entire source while File Provider writers are
                // coordinated. A cloud-side change after the initial copy
                // must keep the source authoritative instead of being lost.
                try verifyCopy(source: coordinatedSource, destination: destination, fileManager: fileManager)
                verificationSucceeded = true
                var trashedURL: NSURL?
                try fileManager.trashItem(at: coordinatedSource, resultingItemURL: &trashedURL)
                trashed = true
            } catch {
                if verificationSucceeded {
                    operationError = GitSyncLibraryMigrationError.originalTrashFailed(error.localizedDescription)
                } else {
                    operationError = error
                }
            }
        }
        if trashed { return true }
        if let operationError { throw operationError }
        if let coordinationError {
            if verificationSucceeded {
                throw GitSyncLibraryMigrationError.originalTrashFailed(coordinationError.localizedDescription)
            }
            throw coordinationError
        }
        return false
    }

    static func canTrashOriginal(
        _ source: URL,
        ownedICloudLibraryRoot: URL?,
        fileManager: FileManager = .default,
        homeDirectory: URL? = nil,
        volumeRoot: URL? = nil
    ) -> Bool {
        let sourcePath = source.standardizedFileURL.resolvingSymlinksInPath().path
        let homePath = (homeDirectory ?? fileManager.homeDirectoryForCurrentUser)
            .standardizedFileURL.resolvingSymlinksInPath().path
        if ownedICloudLibraryRoot?.standardizedFileURL.resolvingSymlinksInPath().path == sourcePath {
            return true
        }
        if sourcePath == homePath || sourcePath == homePath + "/Documents" { return false }
        if sourcePath == homePath + "/Dropbox" || sourcePath == homePath + "/Google Drive" {
            return false
        }
        if sourcePath.hasPrefix(homePath + "/OneDrive-") {
            let remainder = sourcePath.dropFirst((homePath + "/OneDrive-").count)
            if !remainder.contains("/") { return false }
        }
        if sourcePath == homePath + "/OneDrive" { return false }
        let cloudStorage = homePath + "/Library/CloudStorage"
        if sourcePath == cloudStorage { return false }
        if sourcePath.hasPrefix(cloudStorage + "/") {
            let remainder = sourcePath.dropFirst(cloudStorage.count + 1)
            if !remainder.contains("/") { return false }
        }
        let mobileDocuments = homePath + "/Library/Mobile Documents"
        if sourcePath == mobileDocuments { return false }
        if sourcePath.hasPrefix(mobileDocuments + "/") {
            let remainder = sourcePath.dropFirst(mobileDocuments.count + 1)
            if !remainder.contains("/") || sourcePath.hasSuffix("/Documents") { return false }
        }
        if let volumeURL = volumeRoot ?? (try? source.resourceValues(forKeys: [.volumeURLKey]).volume),
            volumeURL.standardizedFileURL.resolvingSymlinksInPath().path == sourcePath
        {
            return false
        }
        return true
    }

    private enum MigratedEntryKind: Equatable {
        case directory
        case regularFile
        case symbolicLink
    }

    private static func verifyCopy(source: URL, destination: URL, fileManager: FileManager) throws {
        let sourceEntries = try migrationInventory(at: source, fileManager: fileManager)
        let destinationEntries = try migrationInventory(at: destination, fileManager: fileManager)
        guard sourceEntries == destinationEntries else {
            throw GitSyncLibraryMigrationError.verificationFailed(destination.path)
        }

        for (relative, kind) in sourceEntries {
            let original = source.appendingPathComponent(relative)
            let copy = destination.appendingPathComponent(relative)
            switch kind {
            case .regularFile:
                guard fileManager.contentsEqual(atPath: original.path, andPath: copy.path) else {
                    throw GitSyncLibraryMigrationError.verificationFailed(relative)
                }
            case .symbolicLink:
                guard
                    try fileManager.destinationOfSymbolicLink(atPath: original.path)
                        == fileManager.destinationOfSymbolicLink(atPath: copy.path)
                else {
                    throw GitSyncLibraryMigrationError.verificationFailed(relative)
                }
            case .directory:
                break
            }
        }
    }

    private static func migrationInventory(
        at root: URL,
        fileManager: FileManager
    ) throws -> [String: MigratedEntryKind] {
        var entries = [String: MigratedEntryKind]()
        let keys: Set<URLResourceKey> = [.isDirectoryKey, .isRegularFileKey, .isSymbolicLinkKey]

        func collect(directory: URL, relativeDirectory: String) throws {
            let children = try fileManager.contentsOfDirectory(
                at: directory,
                includingPropertiesForKeys: Array(keys),
                options: []
            )
            for item in children {
                let relative =
                    relativeDirectory.isEmpty
                    ? item.lastPathComponent
                    : relativeDirectory + "/" + item.lastPathComponent
                let values = try item.resourceValues(forKeys: keys)
                if values.isSymbolicLink == true {
                    entries[relative] = .symbolicLink
                } else if values.isDirectory == true {
                    entries[relative] = .directory
                    try collect(directory: item, relativeDirectory: relative)
                } else if values.isRegularFile == true {
                    entries[relative] = .regularFile
                } else {
                    throw GitSyncLibraryMigrationError.verificationFailed(relative)
                }
            }
        }

        try collect(directory: root, relativeDirectory: "")
        return entries
    }
}

enum GitSyncBlockReason: Equatable, Sendable {
    case pendingSaveFailed(notePaths: [String])
    case workingTreeNotClean(paths: [String])
    case disallowedLocalChanges([GitSyncPathViolation])
    case disallowedIncomingChanges([GitSyncPathViolation])
    case conflicts(GitSyncConflictContext)
    case divergedHistory
    case missingAuthorIdentity
    case repositoryUnavailable
    case cloudBackedRepository
}

enum GitSyncRecoveryAction: Equatable, Sendable {
    case retry
    case resolveWorkingTreeExternally
    case inspectDisallowedPaths([String])
    case restoreRevision(String)
    case reauthorizeRepository
}

enum GitSyncFailureStage: String, Equatable, Sendable {
    case flush
    case inspectLocalChanges
    case fetch
    case preflight
    case commit
    case apply
    case reload
    case push
    case recovery
}

struct GitSyncFailure: Equatable, Sendable {
    let stage: GitSyncFailureStage
    let description: String
    let recovery: GitSyncRecoveryAction
}

enum GitSyncResult: Equatable, Sendable {
    case upToDate(revision: String)
    case updated(revision: String, recoveryRevision: String?)
    case published(revision: String, recoveryRevision: String?)
    case blocked(GitSyncBlockReason)
    case failed(GitSyncFailure)
}

enum GitSyncState: Equatable, Sendable {
    case idle(lastResult: GitSyncResult?)
    case flushingEdits
    case inspectingLocalChanges
    case fetching
    case validatingIncomingChanges
    case resolvingConflicts
    case committing
    case applying
    case reloading
    case pushing

    var isRunning: Bool {
        if case .idle = self { return false }
        return true
    }
}
