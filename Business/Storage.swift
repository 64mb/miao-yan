import Cocoa
import CoreServices
import Foundation
import UniformTypeIdentifiers

struct DirectoryItem {
    let url: URL
    let modificationDate: Date
    let creationDate: Date
}

struct TrashOriginMetadata: Codable, Equatable {
    let rootPath: String
    let relativePath: String
}

struct TrashRestoreDestination: Equatable {
    let rootURL: URL
    let fileURL: URL
    let usesOriginalFolder: Bool
}

struct ProjectRenameResult: Equatable {
    let oldURL: URL
    let newURL: URL
}

enum ProjectRenameError: LocalizedError, Equatable {
    case invalidName
    case protectedProject
    case syncInProgress

    var errorDescription: String? {
        switch self {
        case .invalidName:
            return "Choose a valid folder name"
        case .protectedProject:
            return "This folder cannot be renamed"
        case .syncInProgress:
            return "Wait for Git sync to finish and try again"
        }
    }
}

@MainActor
class Storage {
    static var instance: Storage?

    var noteList = [Note]()
    private var projects = [Project]()
    private var imageFolders = [URL]()

    public var tagNames = [String]()
    public var tags = [String]()

    var notesDict: [String: Note] = [:]

    private struct LoadedProjectInfo {
        let lastScan: Date
        let contentModifiedAt: Date?
        let hadError: Bool
    }

    private var loadedProjectInfo: [String: LoadedProjectInfo] = [:]

    var allowedExtensions = [
        "md", "markdown",
        "txt",
    ]
    private static let attachmentDirectoryNames = ["i", "files"]

    var pinned: Int = 0

    private var bookmarks = [URL]()
    private var scopedURLs = [URL]()

    init() {
        guard var url = UserDefaultsManagement.storageUrl else {
            return
        }

        startAccessingSecurityScopedResourceIfNeeded(url, bookmarkData: UserDefaultsManagement.storageBookmark)

        if UserDefaultsManagement.isSingleMode, let singleModeUrl = UserDefaultsManagement.singleModeURL {
            let singleModeScopeURL = UserDefaultsManagement.singleModeScopeURL ?? singleModeUrl
            let singleModeScopeBookmark = UserDefaultsManagement.singleModeAccessBookmark ?? UserDefaultsManagement.singleModeBookmark

            startAccessingSecurityScopedResourceIfNeeded(singleModeScopeURL, bookmarkData: singleModeScopeBookmark)
            if !FileManager.default.directoryExists(atUrl: singleModeUrl) {
                url = singleModeUrl.deletingLastPathComponent()
            } else {
                url = singleModeUrl
            }
        }

        var name = url.lastPathComponent

        if let iCloudURL = getCloudDrive(), iCloudURL == url {
            name = "iCloud Drive"
        }

        let project = Project(url: url, label: name, isRoot: true, isDefault: true)

        _ = add(project: project)

        checkTrashForVolume(url: project.url)

        for url in bookmarks {
            if url.pathExtension == "css" {
                continue
            }

            guard !projectExist(url: url) else {
                continue
            }

            let project = Project(url: url, label: url.lastPathComponent, isRoot: true)
            _ = add(project: project)
        }
    }

    public func getChildProjects(project: Project) -> [Project] {
        projects.filter {
            $0.parent == project
        }
        .sorted(by: { $0.label.localizedCaseInsensitiveCompare($1.label) == .orderedAscending })
    }

    public func getRootProject() -> Project? {
        projects.first(where: { $0.isRoot })
    }

    public func getDefault() -> Project? {
        projects.first(where: { $0.isDefault })
    }

    public func getRootProjects() -> [Project] {
        projects.filter(\.isRoot).sorted(by: { $0.label.localizedCaseInsensitiveCompare($1.label) == .orderedAscending })
    }

    public func getDefaultTrash() -> Project? {
        projects.first(where: { $0.isTrash })
    }

    private func checkSub(url: URL, parent: Project) -> [Project] {
        var added = [Project]()
        let parentPath = url.path + "/i/"
        let filesPath = url.path + "/files/"

        if let subFolders = getSubFolders(url: url) {
            for subFolder in subFolders {
                if subFolder.lastPathComponent == "i" {
                    imageFolders.append(subFolder as URL)
                    continue
                }

                if projects.count > 100 {
                    return added
                }

                let subUrl = subFolder as URL

                guard !projectExist(url: subUrl),
                    subUrl.lastPathComponent != "i",
                    subUrl.lastPathComponent != "files",
                    !subUrl.path.contains(".Trash"),
                    !subUrl.path.contains("Trash"),
                    !subUrl.path.contains("/."),
                    !subUrl.path.contains(parentPath),
                    !subUrl.path.contains(filesPath),
                    true
                else {
                    continue
                }
                let project = Project(url: subUrl, label: subUrl.lastPathComponent, parent: parent)
                projects.append(project)
                added.append(project)
            }
        }

        return added
    }

    private func checkTrashForVolume(url: URL) {
        if UserDefaultsManagement.isSingleMode {
            return
        }

        var trashURL = getTrash(url: url)
        var needsTrashCreation = true

        if let currentTrash = trashURL, FileManager.default.fileExists(atPath: currentTrash.path) {
            needsTrashCreation = false
        }

        if needsTrashCreation {
            guard let trash = getDefault()?.url.appendingPathComponent("Trash") else {
                return
            }

            var isDir = ObjCBool(false)
            if !FileManager.default.fileExists(atPath: trash.path, isDirectory: &isDir) || !isDir.boolValue {
                do {
                    try FileManager.default.createDirectory(at: trash, withIntermediateDirectories: false, attributes: nil)
                } catch {
                    AppDelegate.trackError(error, context: "Storage.trashDir")
                }
            }

            trashURL = trash
        }

        if let trashURL = trashURL {
            guard !projectExist(url: trashURL) else {
                return
            }

            let project = Project(url: trashURL, isTrash: true)
            projects.append(project)
        }
    }

    private func getCloudDrive() -> URL? {
        if let iCloudDocumentsURL = FileManager.default.url(forUbiquityContainerIdentifier: nil)?.appendingPathComponent("Documents").resolvingSymlinksInPath() {
            var isDirectory = ObjCBool(true)
            if FileManager.default.fileExists(atPath: iCloudDocumentsURL.path, isDirectory: &isDirectory), isDirectory.boolValue {
                return iCloudDocumentsURL
            }
        }

        return nil
    }

    func projectExist(url: URL) -> Bool {
        projects.contains(where: { $0.url == url })
    }

    func project(at url: URL) -> Project? {
        let resolvedURL = url.resolvingSymlinksInPath()
        return projects.first(where: { $0.url == resolvedURL })
    }

    public func removeBy(project: Project) {
        let list = noteList.filter {
            $0.project == project
        }

        for note in list {
            if let i = noteList.firstIndex(where: { $0 === note }) {
                noteList.remove(at: i)
            }
        }

        if let i = projects.firstIndex(of: project) {
            projects.remove(at: i)
        }
        loadedProjectInfo.removeValue(forKey: project.url.path)
    }

    /// Moves a project directory first, then updates every in-memory path that
    /// belongs to that directory. The model is left untouched if the move
    /// fails, so a sidebar edit can never masquerade as a persisted rename.
    @discardableResult
    func renameProject(_ project: Project, to rawName: String) throws -> ProjectRenameResult {
        guard !project.isRoot, !project.isTrash else {
            throw ProjectRenameError.protectedProject
        }

        let name = rawName.trimmingCharacters(in: .whitespacesAndNewlines)
        guard Self.isValidProjectName(name) else {
            throw ProjectRenameError.invalidName
        }

        let oldURL = project.url.standardizedFileURL
        let newURL = oldURL.deletingLastPathComponent()
            .appendingPathComponent(name, isDirectory: true)
            .standardizedFileURL
        guard newURL.deletingLastPathComponent() == oldURL.deletingLastPathComponent() else {
            throw ProjectRenameError.invalidName
        }
        guard oldURL != newURL else {
            project.label = name
            return ProjectRenameResult(oldURL: oldURL, newURL: newURL)
        }
        guard GitSyncLibraryMutationGate.allowsMutation(at: oldURL),
            GitSyncLibraryMutationGate.allowsMutation(at: newURL)
        else {
            throw ProjectRenameError.syncInProgress
        }

        let affectedProjects = projects.filter { Self.url($0.url, isInside: oldURL) }
        let affectedNotes = noteList.filter { Self.url($0.url, isInside: oldURL) }
        let projectSettings = Dictionary(
            uniqueKeysWithValues: affectedProjects.compactMap { affectedProject in
                UserDefaults.standard.object(forKey: affectedProject.url.path)
                    .map { (affectedProject.url.path, $0) }
            })

        try FileManager.default.moveItem(at: oldURL, to: newURL)

        for affectedProject in affectedProjects {
            let previousURL = affectedProject.url
            affectedProject.url = Self.replacingPrefix(of: previousURL, from: oldURL, to: newURL)
            affectedProject.loadLabel()
            if let settings = projectSettings[previousURL.path] {
                UserDefaults.standard.set(settings, forKey: affectedProject.url.path)
                UserDefaults.standard.removeObject(forKey: previousURL.path)
            }
            affectedProject.saveSettings()
        }
        for note in affectedNotes {
            note.url = Self.replacingPrefix(of: note.url, from: oldURL, to: newURL)
        }

        loadedProjectInfo = Dictionary(
            uniqueKeysWithValues: loadedProjectInfo.map { path, info in
                let url = URL(fileURLWithPath: path)
                let updatedPath =
                    Self.url(url, isInside: oldURL)
                    ? Self.replacingPrefix(of: url, from: oldURL, to: newURL).path
                    : path
                return (updatedPath, info)
            })
        migratePersistedPaths(from: oldURL, to: newURL)

        return ProjectRenameResult(oldURL: oldURL, newURL: newURL)
    }

    private static func isValidProjectName(_ name: String) -> Bool {
        !name.isEmpty
            && name != "."
            && name != ".."
            && !name.contains("/")
            && !name.contains("\0")
    }

    private static func url(_ candidate: URL, isInside root: URL) -> Bool {
        let candidatePath = candidate.standardizedFileURL.path
        let rootPath = root.standardizedFileURL.path
        return candidatePath == rootPath || candidatePath.hasPrefix(rootPath + "/")
    }

    private static func replacingPrefix(of candidate: URL, from oldRoot: URL, to newRoot: URL) -> URL {
        let candidatePath = candidate.standardizedFileURL.path
        let oldPath = oldRoot.standardizedFileURL.path
        let suffix = String(candidatePath.dropFirst(oldPath.count))
        let relativePath = suffix.hasPrefix("/") ? String(suffix.dropFirst()) : suffix
        guard !relativePath.isEmpty else { return newRoot }
        return newRoot.appendingPathComponent(relativePath)
    }

    private func migratePersistedPaths(from oldURL: URL, to newURL: URL) {
        if let selectedURL = UserDefaultsManagement.lastSelectedURL,
            Self.url(selectedURL, isInside: oldURL)
        {
            UserDefaultsManagement.lastSelectedURL = Self.replacingPrefix(
                of: selectedURL, from: oldURL, to: newURL)
        }
        if let selectedProject = UserDataService.instance.lastProject,
            Self.url(selectedProject, isInside: oldURL)
        {
            UserDataService.instance.lastProject = Self.replacingPrefix(
                of: selectedProject, from: oldURL, to: newURL)
        }

        guard let order = UserDefaults.standard.stringArray(forKey: "SidebarProjectOrder") else {
            return
        }
        let migratedOrder = order.map { path -> String in
            let url = URL(fileURLWithPath: path)
            guard Self.url(url, isInside: oldURL) else { return path }
            return Self.replacingPrefix(of: url, from: oldURL, to: newURL).path
        }
        UserDefaults.standard.set(migratedOrder, forKey: "SidebarProjectOrder")
    }

    public func add(project: Project) -> [Project] {
        var added = [Project]()

        if !projects.contains(project) {
            projects.append(project)
            added.append(project)
        }

        let shouldScanSubProjects: Bool
        if project.isRoot {
            if UserDefaultsManagement.isSingleMode, let singleModeUrl = UserDefaultsManagement.singleModeURL {
                shouldScanSubProjects =
                    FileManager.default.directoryExists(atUrl: singleModeUrl)
                    && project.url == singleModeUrl
            } else {
                shouldScanSubProjects = true
            }
        } else {
            shouldScanSubProjects = false
        }

        if shouldScanSubProjects {
            let addedSubProjects = checkSub(url: project.url, parent: project)
            added += addedSubProjects
        }

        return added
    }

    private func startAccessingSecurityScopedResourceIfNeeded(_ url: URL, bookmarkData: Data?) {
        guard bookmarkData != nil else {
            return
        }

        if url.startAccessingSecurityScopedResource() {
            scopedURLs.append(url)
        }
    }

    func getTrash(url: URL) -> URL? {
        return try? FileManager.default.url(for: .trashDirectory, in: .allDomainsMask, appropriateFor: url, create: false)
    }

    static func isSystemTrashProject(_ project: Project) -> Bool {
        guard project.isTrash,
            let systemTrash = try? FileManager.default.url(
                for: .trashDirectory,
                in: .allDomainsMask,
                appropriateFor: project.url,
                create: false)
        else { return false }

        return systemTrash.resolvingSymlinksInPath().standardizedFileURL
            == project.url.resolvingSymlinksInPath().standardizedFileURL
    }

    static func isSyncedTrashProject(_ project: Project) -> Bool {
        project.isTrash
            && project.url.lastPathComponent == "items"
            && project.url.deletingLastPathComponent().lastPathComponent == ".Trash"
    }

    static func shouldHideRemovedTrashItem(at url: URL, in project: Project) -> Bool {
        project.isTrash
            && (try? url.extendedAttribute(forName: AppIdentifier.removedFromTrashKey)) != nil
    }

    static func trashOriginMetadataData(for fileURL: URL, root rootURL: URL) -> Data? {
        let root = rootURL.standardizedFileURL.resolvingSymlinksInPath()
        let file = fileURL.standardizedFileURL.resolvingSymlinksInPath()
        let prefix = root.path.hasSuffix("/") ? root.path : root.path + "/"
        guard file.path.hasPrefix(prefix) else { return nil }

        let relativePath = String(file.path.dropFirst(prefix.count))
        guard validTrashOriginRelativePath(relativePath) else { return nil }

        return try? JSONEncoder().encode(
            TrashOriginMetadata(rootPath: root.path, relativePath: relativePath))
    }

    static func trashOriginMetadata(from data: Data?) -> TrashOriginMetadata? {
        guard let data else { return nil }
        return try? JSONDecoder().decode(TrashOriginMetadata.self, from: data)
    }

    /// Resolves a persisted Trash origin only against roots the app currently
    /// owns. Corrupt/stale metadata and missing original folders fall back to
    /// the default root; an existing filename is never overwritten.
    static func trashRestoreDestination(
        for trashedURL: URL,
        metadata: TrashOriginMetadata?,
        availableRoots: [URL],
        defaultRoot: URL
    ) -> TrashRestoreDestination {
        let normalizedRoots = availableRoots.map {
            $0.standardizedFileURL.resolvingSymlinksInPath()
        }
        let fallbackRoot = defaultRoot.standardizedFileURL.resolvingSymlinksInPath()

        var selectedRoot = fallbackRoot
        var preferredURL: URL?
        if let metadata,
            validTrashOriginRelativePath(metadata.relativePath),
            let matchedRoot = normalizedRoots.first(where: { $0.path == metadata.rootPath })
        {
            let candidate = matchedRoot.appendingPathComponent(metadata.relativePath)
            let parent = candidate.deletingLastPathComponent()
            var isDirectory = ObjCBool(false)
            let resolvedParent = parent.resolvingSymlinksInPath().standardizedFileURL
            let rootPrefix = matchedRoot.path.hasSuffix("/") ? matchedRoot.path : matchedRoot.path + "/"
            let remainsInRoot =
                resolvedParent.path == matchedRoot.path
                || resolvedParent.path.hasPrefix(rootPrefix)
            let isReservedTrash =
                metadata.relativePath.split(separator: "/").first
                .map { $0.caseInsensitiveCompare("Trash") == .orderedSame || $0 == ".Trash" }
                ?? true

            if remainsInRoot,
                !isReservedTrash,
                FileManager.default.fileExists(atPath: parent.path, isDirectory: &isDirectory),
                isDirectory.boolValue
            {
                selectedRoot = matchedRoot
                preferredURL = candidate
            }
        }

        let originalName =
            metadata
            .flatMap { validTrashOriginRelativePath($0.relativePath) ? URL(fileURLWithPath: $0.relativePath).lastPathComponent : nil }
        let fallbackName = originalName?.isEmpty == false ? originalName! : trashedURL.lastPathComponent
        let desiredURL = preferredURL ?? fallbackRoot.appendingPathComponent(fallbackName)
        let destination = availableRestoreURL(for: desiredURL)

        return TrashRestoreDestination(
            rootURL: selectedRoot,
            fileURL: destination,
            usesOriginalFolder: preferredURL != nil)
    }

    private static func validTrashOriginRelativePath(_ path: String) -> Bool {
        guard !path.isEmpty, !path.hasPrefix("/"), !path.contains("\\") else { return false }
        let components = path.split(separator: "/", omittingEmptySubsequences: false)
        return !components.isEmpty
            && components.allSatisfy { !$0.isEmpty && $0 != "." && $0 != ".." }
    }

    private static func availableRestoreURL(for desiredURL: URL) -> URL {
        guard FileManager.default.fileExists(atPath: desiredURL.path) else { return desiredURL }

        let directory = desiredURL.deletingLastPathComponent()
        let baseName = desiredURL.deletingPathExtension().lastPathComponent
        let fileExtension = desiredURL.pathExtension
        var index = 1

        while true {
            var candidate = directory.appendingPathComponent("\(baseName) \(index)")
            if !fileExtension.isEmpty {
                candidate.appendPathExtension(fileExtension)
            }
            if !FileManager.default.fileExists(atPath: candidate.path) {
                return candidate
            }
            index += 1
        }
    }

    public func getBookmarks() -> [URL] {
        bookmarks
    }

    public static func sharedInstance() -> Storage {
        guard let storage = instance else {
            instance = Storage()
            return instance!
        }
        return storage
    }

    public func loadProjects(withTrash: Bool = false, skipRoot: Bool = false) {
        if !skipRoot {
            noteList.removeAll()
            loadedProjectInfo.removeAll()
        }

        let singleModeRootProject: Project? = {
            guard UserDefaultsManagement.isSingleMode,
                let singleModeURL = UserDefaultsManagement.singleModeURL,
                FileManager.default.directoryExists(atUrl: singleModeURL)
            else {
                return nil
            }
            return getRootProject()
        }()

        for project in projects {
            if project.isTrash, !withTrash {
                continue
            }

            if project.isRoot, skipRoot {
                continue
            }
            if let singleModeRootProject, project.isDescendant(of: singleModeRootProject) {
                loadLabel(project)
                continue
            }
            if UserDefaultsManagement.isSingleMode, let singleModeUrl = UserDefaultsManagement.singleModeURL {
                let singleRootUrl = singleModeUrl.deletingLastPathComponent()
                if project.url == singleModeUrl {
                    loadLabel(project)
                }
                if project.url == singleRootUrl {
                    loadLabel(project)
                }
            } else {
                loadLabel(project)
            }
        }
    }

    public func reconfigureForSingleMode(originalFileURL: URL? = nil, siblingFiles: [URL]? = nil) {
        guard UserDefaultsManagement.isSingleMode,
            let singleModeUrl = UserDefaultsManagement.singleModeURL
        else {
            return
        }

        for url in scopedURLs {
            url.stopAccessingSecurityScopedResource()
        }
        scopedURLs.removeAll()

        let singleModeScopeURL = UserDefaultsManagement.singleModeScopeURL ?? singleModeUrl
        let singleModeScopeBookmark = UserDefaultsManagement.singleModeAccessBookmark ?? UserDefaultsManagement.singleModeBookmark
        startAccessingSecurityScopedResourceIfNeeded(singleModeScopeURL, bookmarkData: singleModeScopeBookmark)

        let rootUrl: URL
        if FileManager.default.directoryExists(atUrl: singleModeUrl) {
            rootUrl = singleModeUrl
        } else {
            rootUrl = singleModeUrl.deletingLastPathComponent()
        }

        projects.removeAll()
        noteList.removeAll()
        loadedProjectInfo.removeAll()
        pinned = 0

        var name = rootUrl.lastPathComponent
        if let iCloudURL = getCloudDrive(), iCloudURL == rootUrl {
            name = "iCloud Drive"
        }
        let project = Project(url: rootUrl, label: name, isRoot: true, isDefault: true)
        _ = add(project: project)
        loadProjects()

        // Sandbox fallback: in App Store builds the sandbox extension from
        // application:open: may not persist to the deferred directory enumeration
        // in loadLabel. Use the pre-enumerated sibling files to populate the list
        // so ALL .md files in the directory are visible, not just the opened one.
        if noteList.isEmpty, let files = siblingFiles, !files.isEmpty {
            for fileURL in files {
                let resolved = fileURL.resolvingSymlinksInPath()
                guard FileManager.default.fileExists(atPath: resolved.path),
                    !FileManager.default.directoryExists(atUrl: resolved)
                else { continue }
                let note = Note(url: resolved, with: project)
                note.loadMetadataFromDisk()
                noteList.append(note)
            }
        } else if noteList.isEmpty, let fileURL = originalFileURL {
            // Final fallback: just the opened file
            let resolved = fileURL.resolvingSymlinksInPath()
            if FileManager.default.fileExists(atPath: resolved.path),
                !FileManager.default.directoryExists(atUrl: resolved)
            {
                let note = Note(url: resolved, with: project)
                note.loadMetadataFromDisk()
                noteList.append(note)
            }
        }
    }

    func loadDocuments(tryCount: Int = 0, completion: @escaping () -> Void) {
        _ = restoreCloudPins()

        noteList = sortNotes(noteList: noteList, filter: "")

        guard !checkFirstRun() else {
            if tryCount == 0 {
                loadProjects()
                loadDocuments(tryCount: 1) {}
                return
            }
            return
        }
    }

    public func getMainProject() -> Project {
        projects.first!
    }

    public func getProjects() -> [Project] {
        projects
    }

    public func getProjectBy(element: Int) -> Project? {
        if projects.indices.contains(element) {
            return projects[element]
        }

        return nil
    }

    public func getCloudDriveProjects() -> [Project] {
        projects.filter {
            $0.isCloudDrive == true
        }
    }

    public func getLocalProjects() -> [Project] {
        projects.filter {
            $0.isCloudDrive == false
        }
    }

    public func getProjectPaths() -> [String] {
        var pathList: [String] = []
        let projects = getProjects()

        for project in projects {
            pathList.append(NSString(string: project.url.path).expandingTildeInPath)
        }

        return pathList
    }

    public func getProjectBy(url: URL) -> Project? {
        let projectURL = url.deletingLastPathComponent()
        let path = projectURL.path

        // Find all projects that could be parents (prefix match)
        let candidates = projects.filter { project in
            let projectPath = project.url.path
            if path == projectPath {
                return true
            }
            let normalized = projectPath.hasSuffix("/") ? projectPath : projectPath + "/"
            return path.hasPrefix(normalized)
        }

        // Return the one with the longest path (most specific match)
        return candidates.max(by: { $0.url.path.count < $1.url.path.count })
    }

    /// Ensures the in-memory project chain exists for a note that appeared in
    /// an externally applied Git tree. This does not rescan or replace existing
    /// Note instances.
    func ensureProjectForGitNote(at noteURL: URL, under root: Project) -> Project? {
        let rootPath = root.url.standardizedFileURL.resolvingSymlinksInPath().path
        let directory = noteURL.deletingLastPathComponent().standardizedFileURL.resolvingSymlinksInPath()
        let directoryPath = directory.path
        guard directoryPath == rootPath || directoryPath.hasPrefix(rootPath + "/") else { return nil }
        if directoryPath == rootPath { return root }

        let relative = String(directoryPath.dropFirst(rootPath.count + 1))
        var parent = root
        var currentURL = root.url
        for component in relative.split(separator: "/").map(String.init) {
            currentURL.appendPathComponent(component, isDirectory: true)
            if let existing = projects.first(where: { $0.url == currentURL.resolvingSymlinksInPath() }) {
                parent = existing
            } else {
                let project = Project(url: currentURL, parent: parent)
                projects.append(project)
                parent = project
            }
        }
        return parent
    }

    /// Retires in-memory descendants whose directories disappeared during a
    /// Git checkout. Removing the project without retiring its notes would
    /// leave stale Note objects able to schedule writes into deleted paths.
    func retireMissingProjectsAfterGit(under root: Project) {
        let missing =
            projects
            .filter {
                $0 != root
                    && !$0.isTrash
                    && $0.isDescendant(of: root)
                    && !FileManager.default.directoryExists(atUrl: $0.url)
            }
            .sorted { $0.url.path.count > $1.url.path.count }

        for project in missing {
            for note in getNotesBy(project: project) {
                note.retireAfterRemoval()
                removeBy(note: note)
            }
            remove(project: project)
        }
    }

    func sortNotes(noteList: [Note], filter: String, project: Project? = nil, operation: Operation? = nil) -> [Note] {
        let hasFilter = !filter.isEmpty

        return noteList.sorted(by: {
            if let operation = operation, operation.isCancelled {
                return false
            }

            if hasFilter {
                let firstMatch = $0.title.range(of: filter, options: [.caseInsensitive, .anchored]) != nil
                if firstMatch {
                    let secondMatch = $1.title.range(of: filter, options: [.caseInsensitive, .anchored]) != nil
                    if secondMatch {
                        return sortQuery(note: $0, next: $1, project: project)
                    }
                    return true
                }
            }

            return sortQuery(note: $0, next: $1, project: project)
        })
    }

    private func sortQuery(note: Note, next: Note, project: Project?) -> Bool {
        let sortDirection: SortDirection = UserDefaultsManagement.sortDirection ? .desc : .asc

        let sort = UserDefaultsManagement.sort

        if note.isPinned == next.isPinned {
            switch sort {
            case .creationDate:
                if let prevDate = note.creationDate, let nextDate = next.creationDate {
                    return sortDirection == .asc && prevDate < nextDate || sortDirection == .desc && prevDate > nextDate
                }
            case .modificationDate, .none:
                return sortDirection == .asc && note.modifiedLocalAt < next.modifiedLocalAt || sortDirection == .desc && note.modifiedLocalAt > next.modifiedLocalAt
            case .title:
                let result = note.title.localizedCaseInsensitiveCompare(next.title)
                return sortDirection == .asc && result == .orderedAscending || sortDirection == .desc && result == .orderedDescending
            }
        }

        return note.isPinned && !next.isPinned
    }

    func loadLabel(_ item: Project, loadContent: Bool = false) {
        let result = readDirectoryWithStatus(item.url)
        let documents = result.items
        let contentModifiedAt = directoryContentModifiedAt(item.url)

        for document in documents {
            let url = document.url

            guard !Self.shouldHideRemovedTrashItem(at: url, in: item) else { continue }

            if let currentNoteURL = EditTextView.note?.url,
                currentNoteURL.resolvingSymlinksInPath().path == url.resolvingSymlinksInPath().path
            {
                // Re-use the existing Note object so the currently open file
                // stays visible in the list after a single-mode reload.
                if let existingNote = EditTextView.note {
                    if existingNote.isPinned {
                        pinned += 1
                    }
                    noteList.append(existingNote)
                }
                continue
            }

            let note = Note(url: url.resolvingSymlinksInPath(), with: item)

            if url.pathComponents.isEmpty {
                continue
            }

            note.modifiedLocalAt = document.modificationDate
            note.creationDate = document.creationDate
            note.project = item

            #if CLOUDKIT
            #else
                let pinData =
                    (try? note.url.extendedAttribute(forName: AppIdentifier.pinKey))
                    ?? (try? note.url.extendedAttribute(forName: AppIdentifier.legacyPinKey))
                if let data = pinData {
                    let isPinned = data.withUnsafeBytes { (ptr: UnsafeRawBufferPointer) -> Bool in
                        ptr.load(as: Bool.self)
                    }

                    note.isPinned = isPinned
                }
            #endif

            if loadContent {
                note.load()
            }

            if note.isPinned {
                pinned += 1
            }

            noteList.append(note)
        }
        loadedProjectInfo[item.url.path] = LoadedProjectInfo(
            lastScan: Date(),
            contentModifiedAt: contentModifiedAt,
            hadError: result.hadError
        )
    }

    public func loadMissingNotes(for project: Project) {
        let projectPath = project.url.path
        let now = Date()
        let contentModifiedAt = directoryContentModifiedAt(project.url)

        if let info = loadedProjectInfo[projectPath] {
            if !info.hadError {
                if project.isCloudDrive {
                    if now.timeIntervalSince(info.lastScan) < 2.0 {
                        return
                    }
                } else {
                    if let contentModifiedAt = contentModifiedAt,
                        contentModifiedAt == info.contentModifiedAt
                    {
                        return
                    }

                    if contentModifiedAt == nil,
                        now.timeIntervalSince(info.lastScan) < 1.0
                    {
                        return
                    }
                }
            } else if now.timeIntervalSince(info.lastScan) < 2.0 {
                return
            }
        }

        let result = readDirectoryWithStatus(project.url)
        let documents = result.items

        for document in documents {
            let url = document.url

            guard !Self.shouldHideRemovedTrashItem(at: url, in: project) else { continue }

            // Check if note is already loaded to avoid duplicates
            if noteList.contains(where: { $0.url == url }) {
                continue
            }

            let note = Note(url: url.resolvingSymlinksInPath(), with: project)

            if url.pathComponents.isEmpty {
                continue
            }

            note.modifiedLocalAt = document.modificationDate
            note.creationDate = document.creationDate
            note.project = project

            #if CLOUDKIT
            #else
                let pinData =
                    (try? note.url.extendedAttribute(forName: AppIdentifier.pinKey))
                    ?? (try? note.url.extendedAttribute(forName: AppIdentifier.legacyPinKey))
                if let data = pinData {
                    let isPinned = data.withUnsafeBytes { (ptr: UnsafeRawBufferPointer) -> Bool in
                        ptr.load(as: Bool.self)
                    }
                    note.isPinned = isPinned
                }
            #endif

            if note.isPinned {
                pinned += 1
            }

            noteList.append(note)
        }
        loadedProjectInfo[projectPath] = LoadedProjectInfo(
            lastScan: now,
            contentModifiedAt: contentModifiedAt,
            hadError: result.hadError
        )
    }

    public func unload(project: Project) {
        let notes = noteList.filter { $0.project == project }
        for note in notes {
            if let i = noteList.firstIndex(where: { $0 === note }) {
                noteList.remove(at: i)
            }
        }
        loadedProjectInfo.removeValue(forKey: project.url.path)
    }

    public func reLoadTrash() {
        noteList.removeAll(where: { $0.isTrash() })

        let systemTrashProjects = projects.filter { $0.isTrash && !Self.isSyncedTrashProject($0) }
        for project in systemTrashProjects {
            loadLabel(project, loadContent: true)
        }
        for root in getRootProjects() {
            loadSyncedTrash(for: root)
        }
    }

    private func loadSyncedTrash(for root: Project) {
        guard let project = ensureSyncedTrashProject(for: root, createDirectories: false) else { return }
        let itemsURL = project.url
        guard
            let itemDirectories = try? FileManager.default.contentsOfDirectory(
                at: itemsURL,
                includingPropertiesForKeys: [.isDirectoryKey, .isSymbolicLinkKey],
                options: [.skipsHiddenFiles])
        else { return }

        let entries: [GitSyncedTrashManifestEntry]
        do {
            entries = try readSyncedTrashManifest(for: root.url)
        } catch {
            AppDelegate.trackError(error, context: "Storage.syncedTrash.readManifest")
            entries = []
        }
        var entriesByPath = [String: GitSyncedTrashManifestEntry]()
        for entry in entries {
            entriesByPath[entry.trashRelativePath] = entry
        }
        let policy = GitSyncPathPolicy()
        for itemDirectory in itemDirectories {
            let values = try? itemDirectory.resourceValues(forKeys: [.isDirectoryKey, .isSymbolicLinkKey])
            guard values?.isDirectory == true, values?.isSymbolicLink != true,
                let uuid = UUID(uuidString: itemDirectory.lastPathComponent),
                uuid.uuidString.lowercased() == itemDirectory.lastPathComponent,
                let payloads = try? FileManager.default.contentsOfDirectory(
                    at: itemDirectory,
                    includingPropertiesForKeys: [
                        .isRegularFileKey, .isSymbolicLinkKey, .contentModificationDateKey, .creationDateKey,
                    ],
                    options: [.skipsHiddenFiles])
            else { continue }

            for payload in payloads {
                let relativePath = ".Trash/items/\(itemDirectory.lastPathComponent)/\(payload.lastPathComponent)"
                guard case .allowed(.trashNote) = policy.classify(relativePath: relativePath),
                    let payloadValues = try? payload.resourceValues(forKeys: [
                        .isRegularFileKey, .isSymbolicLinkKey, .contentModificationDateKey, .creationDateKey,
                    ]),
                    payloadValues.isRegularFile == true,
                    payloadValues.isSymbolicLink != true
                else { continue }

                let note = Note(url: payload.resolvingSymlinksInPath(), with: project)
                let entry = entriesByPath[relativePath].flatMap {
                    $0.id == itemDirectory.lastPathComponent ? $0 : nil
                }
                note.modifiedLocalAt = payloadValues.contentModificationDate ?? Date.distantPast
                note.creationDate =
                    entry.map {
                        Date(timeIntervalSince1970: TimeInterval($0.deletedAtMilliseconds) / 1_000)
                    } ?? payloadValues.creationDate
                note.load()
                noteList.append(note)
            }
        }
    }

    private func ensureSyncedTrashProject(for root: Project, createDirectories: Bool) -> Project? {
        let itemsURL: URL
        do {
            guard
                let validatedURL = try syncedTrashItemsURL(
                    for: root.url,
                    createDirectories: createDirectories)
            else { return nil }
            itemsURL = validatedURL
        } catch {
            AppDelegate.trackError(error, context: "Storage.syncedTrash.validate")
            return nil
        }
        if let existing = projects.first(where: { $0.url == itemsURL && Self.isSyncedTrashProject($0) }) {
            return existing
        }

        let project = Project(url: itemsURL, label: "Trash", isTrash: true, parent: root)
        project.showInSidebar = false
        projects.append(project)
        return project
    }

    func moveToSyncedTrash(fileURL: URL, root: Project) throws -> URL {
        let normalizedRoot = root.url.standardizedFileURL.resolvingSymlinksInPath()
        let normalizedFile = fileURL.standardizedFileURL.resolvingSymlinksInPath()
        let rootPrefix = normalizedRoot.path.hasSuffix("/") ? normalizedRoot.path : normalizedRoot.path + "/"
        guard normalizedFile.path.hasPrefix(rootPrefix) else {
            throw syncedTrashError("The note is outside the configured library")
        }
        let originalRelativePath = String(normalizedFile.path.dropFirst(rootPrefix.count))
        guard case .allowed(.note) = GitSyncPathPolicy().classify(relativePath: originalRelativePath) else {
            throw syncedTrashError("The note path is not supported by Git sync")
        }
        guard let project = ensureSyncedTrashProject(for: root, createDirectories: true) else {
            throw CocoaError(.fileWriteUnknown)
        }

        let previousEntries = try readSyncedTrashManifest(for: normalizedRoot)
        let identifier = UUID().uuidString.lowercased()
        let itemDirectory = project.url.appendingPathComponent(identifier, isDirectory: true)
        let destination = itemDirectory.appendingPathComponent(normalizedFile.lastPathComponent)
        let trashRelativePath = ".Trash/items/\(identifier)/\(normalizedFile.lastPathComponent)"
        let entry = GitSyncedTrashManifestEntry(
            id: identifier,
            originalRelativePath: originalRelativePath,
            trashRelativePath: trashRelativePath,
            deletedAtMilliseconds: Int64(Date().timeIntervalSince1970 * 1_000))

        try FileManager.default.createDirectory(at: itemDirectory, withIntermediateDirectories: false)
        do {
            try writeSyncedTrashManifest(previousEntries + [entry], for: normalizedRoot)
            try FileManager.default.moveItem(at: normalizedFile, to: destination)
        } catch {
            try? writeSyncedTrashManifest(previousEntries, for: normalizedRoot)
            try? FileManager.default.removeItem(at: itemDirectory)
            throw error
        }
        return destination
    }

    private func syncedTrashContext(for payloadURL: URL) -> (root: Project, entry: GitSyncedTrashManifestEntry?)? {
        let payload = payloadURL.standardizedFileURL.resolvingSymlinksInPath()
        for root in getRootProjects() {
            let normalizedRoot = root.url.standardizedFileURL.resolvingSymlinksInPath()
            let itemsURL = normalizedRoot.appendingPathComponent(".Trash/items", isDirectory: true)
            let prefix = itemsURL.path.hasSuffix("/") ? itemsURL.path : itemsURL.path + "/"
            guard payload.path.hasPrefix(prefix) else { continue }
            let suffix = String(payload.path.dropFirst(prefix.count))
            let components = suffix.split(separator: "/", omittingEmptySubsequences: false)
            guard components.count == 2 else { return nil }
            let relativePath = ".Trash/items/\(suffix)"
            guard case .allowed(.trashNote) = GitSyncPathPolicy().classify(relativePath: relativePath) else {
                return nil
            }
            let entries: [GitSyncedTrashManifestEntry]
            do {
                entries = try readSyncedTrashManifest(for: normalizedRoot)
            } catch {
                AppDelegate.trackError(error, context: "Storage.syncedTrash.readContext")
                entries = []
            }
            let entry = entries.first {
                $0.id == String(components[0]) && $0.trashRelativePath == relativePath
            }
            return (root, entry)
        }
        return nil
    }

    func removeSyncedTrashMetadata(for payloadURL: URL) throws {
        guard let context = syncedTrashContext(for: payloadURL) else { return }
        if let entry = context.entry {
            var entries = try readSyncedTrashManifest(for: context.root.url)
            entries.removeAll { $0.id == entry.id }
            try writeSyncedTrashManifest(entries, for: context.root.url)
        }
        let itemDirectory = payloadURL.deletingLastPathComponent()
        if (try? FileManager.default.contentsOfDirectory(atPath: itemDirectory.path).isEmpty) == true {
            try? FileManager.default.removeItem(at: itemDirectory)
        }
    }

    private func readSyncedTrashManifest(for rootURL: URL) throws -> [GitSyncedTrashManifestEntry] {
        guard let itemsURL = try syncedTrashItemsURL(for: rootURL, createDirectories: false) else { return [] }
        let manifestURL = itemsURL.deletingLastPathComponent().appendingPathComponent("manifest.v1")
        guard FileManager.default.fileExists(atPath: manifestURL.path) else { return [] }
        let values = try manifestURL.resourceValues(forKeys: [.isRegularFileKey, .isSymbolicLinkKey])
        guard values.isRegularFile == true, values.isSymbolicLink != true else {
            throw syncedTrashError("The Trash manifest is not a regular file")
        }
        let content = try String(contentsOf: manifestURL, encoding: .utf8)
        guard let entries = GitSyncedTrashManifestCodec.decodeValidated(content) else {
            throw CocoaError(.fileReadCorruptFile)
        }
        return entries
    }

    private func writeSyncedTrashManifest(_ entries: [GitSyncedTrashManifestEntry], for rootURL: URL) throws {
        guard let itemsURL = try syncedTrashItemsURL(for: rootURL, createDirectories: true) else {
            throw syncedTrashError("The Trash directory could not be created")
        }
        let manifestURL = itemsURL.deletingLastPathComponent().appendingPathComponent("manifest.v1")
        if FileManager.default.fileExists(atPath: manifestURL.path) {
            let values = try manifestURL.resourceValues(forKeys: [.isRegularFileKey, .isSymbolicLinkKey])
            guard values.isRegularFile == true, values.isSymbolicLink != true else {
                throw syncedTrashError("The Trash manifest is not a regular file")
            }
        }
        try GitSyncedTrashManifestCodec.encode(entries).write(to: manifestURL, atomically: true, encoding: .utf8)
    }

    private func syncedTrashItemsURL(for rootURL: URL, createDirectories: Bool) throws -> URL? {
        let root = rootURL.standardizedFileURL.resolvingSymlinksInPath()
        let trashURL = root.appendingPathComponent(".Trash", isDirectory: true).standardizedFileURL
        let itemsURL = trashURL.appendingPathComponent("items", isDirectory: true).standardizedFileURL
        for directory in [trashURL, itemsURL] {
            var isDirectory = ObjCBool(false)
            if FileManager.default.fileExists(atPath: directory.path, isDirectory: &isDirectory) {
                let values = try directory.resourceValues(forKeys: [.isDirectoryKey, .isSymbolicLinkKey])
                guard isDirectory.boolValue, values.isDirectory == true, values.isSymbolicLink != true else {
                    throw syncedTrashError("The Trash path contains an unsafe link or file")
                }
            } else if createDirectories {
                try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: false)
            } else {
                return nil
            }
        }

        guard itemsURL.resolvingSymlinksInPath() == itemsURL else {
            throw syncedTrashError("The Trash path escapes the configured library")
        }
        return itemsURL
    }

    private func syncedTrashError(_ description: String) -> NSError {
        NSError(
            domain: "com.tw93.miaoyan.syncedTrash",
            code: 1,
            userInfo: [NSLocalizedDescriptionKey: description])
    }

    /// Reconcile a Trash project before presenting it. FSEvents can coalesce
    /// or delay a removal from the system Trash, so the in-memory list may
    /// still contain a Note whose file is already gone. Retiring the old
    /// object closes every late-save path before it is removed from storage.
    func retireMissingNotes(in project: Project) {
        let missingNotes = noteList.filter {
            $0.project == project
                && (!FileManager.default.fileExists(atPath: $0.url.path)
                    || Self.shouldHideRemovedTrashItem(at: $0.url, in: project))
        }
        for note in missingNotes {
            note.retireAfterRemoval()
            removeBy(note: note)
        }
    }

    private func directoryContentModifiedAt(_ url: URL) -> Date? {
        (try? url.resourceValues(forKeys: [.contentModificationDateKey]))?.contentModificationDate
    }

    private struct DirectoryReadResult {
        let items: [DirectoryItem]
        let hadError: Bool
    }

    private func readDirectoryWithStatus(_ url: URL) -> DirectoryReadResult {
        let url = url.resolvingSymlinksInPath()

        do {
            let directoryFiles =
                try FileManager.default.contentsOfDirectory(at: url, includingPropertiesForKeys: [.contentModificationDateKey, .creationDateKey, .typeIdentifierKey], options: .skipsHiddenFiles)

            let items =
                directoryFiles.filter {
                    allowedExtensions.contains($0.pathExtension)
                        && isValidUTI(url: $0)
                }
                .map { url in
                    DirectoryItem(
                        url: url,
                        modificationDate: (try? url.resourceValues(forKeys: [.contentModificationDateKey]))?.contentModificationDate ?? Date.distantPast,
                        creationDate: (try? url.resourceValues(forKeys: [.creationDateKey]))?.creationDate ?? Date.distantPast
                    )
                }
            return DirectoryReadResult(items: items, hadError: false)
        } catch {
            AppDelegate.trackError(error, context: "Storage.notFound: \(url.path)")
        }

        return DirectoryReadResult(items: [], hadError: true)
    }

    public func readDirectory(_ url: URL) -> [DirectoryItem] {
        readDirectoryWithStatus(url).items
    }

    public func isValidUTI(url: URL) -> Bool {
        guard url.fileSize < 100_000_000 else {
            return false
        }

        guard let typeIdentifier = (try? url.resourceValues(forKeys: [.typeIdentifierKey]))?.typeIdentifier else {
            return false
        }

        guard let utType = UTType(typeIdentifier) else {
            return false
        }

        if utType.conforms(to: .directory) {
            return false
        }

        return utType.conforms(to: .text)
            || utType.conforms(to: .plainText)
            || typeIdentifier == "net.daringfireball.markdown"
            || typeIdentifier == "public.markdown"
    }

    func add(_ note: Note) {
        if !noteList.contains(where: { $0.name == note.name && $0.project == note.project }) {
            noteList.append(note)
        }
    }

    func removeBy(note: Note) {
        if let i = noteList.firstIndex(where: { $0 === note }) {
            noteList.remove(at: i)
        }
    }

    func getNextId() -> Int {
        noteList.count
    }

    /// Pure decision the storage takes on first launch / each launch about
    /// whether to seed the bundled demo folders. Extracted as a static helper
    /// so the case matrix (specifically the V3.5.1 regression `13964acd` where
    /// existing users with non-empty noteLists never got the initialized flag
    /// set) is regression-testable without spinning up a real Storage.
    enum InitContentDecision: Equatable {
        case skip  // already initialized; nothing to do
        case markInitialized  // user has notes; just record the flag
        case createInitFolders  // empty noteList + flag unset; seed demo
    }

    static func decideInitContent(noteListIsEmpty: Bool, hasCreatedInitContent: Bool) -> InitContentDecision {
        if !noteListIsEmpty { return .markInitialized }
        if hasCreatedInitContent { return .skip }
        return .createInitFolders
    }

    func checkFirstRun() -> Bool {
        switch Storage.decideInitContent(
            noteListIsEmpty: noteList.isEmpty,
            hasCreatedInitContent: UserDefaultsManagement.hasCreatedInitContent
        ) {
        case .markInitialized:
            UserDefaultsManagement.hasCreatedInitContent = true
            return false
        case .skip:
            return false
        case .createInitFolders:
            break
        }

        guard let resourceURL = Bundle.main.resourceURL else {
            return false
        }

        guard let destination = getDemoSubdirURL() else {
            return false
        }

        let initialPath = resourceURL.appendingPathComponent("Initial").path

        // Skip directory structure in single mode
        if UserDefaultsManagement.isSingleMode {
            return true
        }

        // Detect system language
        let isChinese = Locale.preferredLanguages.first?.hasPrefix("zh") ?? false

        // Define folder structure
        let folders = ["Guide", "Examples", "Notes", "Ideas"]

        // File distribution mapping
        let fileMapping: [String: [String]] = [
            "Guide": [
                isChinese ? "介绍妙言.md" : "Introduction to MiaoYan.md"
            ],
            "Examples": [
                isChinese ? "妙言 PPT.md" : "MiaoYan PPT.md",
                isChinese ? "妙言 Markdown 语法指南.md" : "MiaoYan Markdown Syntax Guide.md",
            ],
            "Notes": [
                isChinese ? "欢迎使用.md" : "Welcome.md"
            ],
            "Ideas": [
                isChinese ? "头脑风暴.md" : "Brainstorming.md"
            ],
        ]

        do {
            // Create folders and copy files
            for folder in folders {
                let folderURL = destination.appendingPathComponent(folder)
                try FileManager.default.createDirectory(at: folderURL, withIntermediateDirectories: true, attributes: nil)

                if let files = fileMapping[folder] {
                    for file in files {
                        let sourcePath = "\(initialPath)/\(file)"
                        let destPath = folderURL.appendingPathComponent(file).path

                        if FileManager.default.fileExists(atPath: sourcePath) {
                            try? FileManager.default.copyItem(atPath: sourcePath, toPath: destPath)
                        }
                    }
                }
            }

            // Rescan subdirectories and add them as projects
            guard let rootProject = getRootProject() else {
                return false
            }
            _ = checkSub(url: rootProject.url, parent: rootProject)
        } catch {
            AppDelegate.trackError(error, context: "Storage.initialSetup")
            return false
        }

        UserDefaultsManagement.hasCreatedInitContent = true
        return true
    }

    func getBy(url: URL) -> Note? {
        if noteList.isEmpty {
            return nil
        }

        let resolvedPath = url.resolvingSymlinksInPath().path.lowercased()

        return
            noteList.first(where: {
                $0.url.resolvingSymlinksInPath().path.lowercased() == resolvedPath
            })
    }

    func getBy(name: String) -> Note? {
        noteList.first(where: {
            $0.name == name

        })
    }

    func getBy(title: String) -> Note? {
        noteList.first(where: {
            $0.title.lowercased() == title.lowercased()

        })
    }

    func getBy(startWith: String) -> [Note]? {
        noteList.filter {
            $0.title.starts(with: startWith)
        }
    }

    func getDemoSubdirURL() -> URL? {
        if let project = projects.first {
            return project.url
        }

        return nil
    }

    func removeNotes(
        notes: [Note],
        fsRemove: Bool = true,
        completely: Bool = false,
        partialFailure: ((Int) -> Void)? = nil,
        didRemove: (([Note]) -> Void)? = nil,
        completion: @escaping ([URL: URL]?) -> Void
    ) {
        guard !notes.isEmpty else {
            completion(nil)
            return
        }

        // Run the file IO first. If the trash / move fails for a note, keep
        // it in the in-memory list and the sidebar so the user does not see
        // it disappear from the UI while the file is still on disk. Without
        // this ordering a denied permission, full Trash, or iCloud stall
        // would silently make the note vanish from MiaoYan but persist as
        // an orphan file.
        var removed = [URL: URL]()
        var succeeded = [Note]()
        var failedCount = 0

        for note in notes {
            if !fsRemove {
                succeeded.append(note)
                continue
            }

            // Preserve the latest editor bytes in the recoverable Trash copy.
            // The watcher path skips this because its file is already gone;
            // flushing there would recreate the externally removed note.
            guard note.flushPendingSave(globalStorage: false) else {
                failedCount += 1
                continue
            }

            let originalPath = note.url.path
            if completely {
                // Close every late write sink before the unlink. If unlinking
                // fails, the same object is reactivated below.
                note.retireAfterRemoval()
            }
            if let removal = note.removeFile(completely: completely) {
                if case .moved(let destination, let original) = removal {
                    removed[destination] = original
                }
                succeeded.append(note)
            } else if !FileManager.default.fileExists(atPath: originalPath) {
                // removeFile returned nil because the file was already gone.
                // Treat as success so the empty row does not linger.
                succeeded.append(note)
            } else {
                if completely {
                    note.reactivateAfterFailedRemoval()
                }
                failedCount += 1
            }
        }

        for note in succeeded {
            note.retireAfterRemoval()
            removeBy(note: note)
        }
        didRemove?(succeeded)

        if failedCount > 0 {
            let warning = NSError(
                domain: "com.tw93.miaoyan.delete",
                code: 1,
                userInfo: [NSLocalizedDescriptionKey: "removeNotes: \(failedCount) of \(notes.count) failed"])
            AppDelegate.trackError(warning, context: "Storage.removeNotes.partialFailure")
            partialFailure?(failedCount)
        }

        if !removed.isEmpty {
            completion(removed)
        } else {
            completion(nil)
        }
    }

    func restoreNotesFromTrash(_ notes: [Note]) -> (restored: [Note], failedCount: Int) {
        guard let defaultProject = getDefault() else { return ([], notes.count) }
        let roots = getRootProjects().map(\.url)
        var restored = [Note]()
        var failedCount = notes.filter { !$0.isTrash() }.count

        for note in notes where note.isTrash() {
            guard FileManager.default.fileExists(atPath: note.url.path) else {
                let error = NSError(
                    domain: NSCocoaErrorDomain,
                    code: CocoaError.fileNoSuchFile.rawValue,
                    userInfo: [NSFilePathErrorKey: note.url.path])
                AppDelegate.trackError(error, context: "Storage.restoreTrash.sourceMissing")
                failedCount += 1
                continue
            }
            guard note.flushPendingSave(globalStorage: false) else {
                let error = NSError(
                    domain: "com.tw93.miaoyan.trash",
                    code: 1,
                    userInfo: [NSLocalizedDescriptionKey: "Could not save the Trash note before restoring it"])
                AppDelegate.trackError(error, context: "Storage.restoreTrash.flush")
                failedCount += 1
                continue
            }

            let sourceURL = note.url
            let syncedTrash = syncedTrashContext(for: sourceURL)
            let originData = try? sourceURL.extendedAttribute(forName: AppIdentifier.trashOriginKey)
            let originMetadata: TrashOriginMetadata?
            if let syncedTrash, let entry = syncedTrash.entry {
                originMetadata = TrashOriginMetadata(
                    rootPath: syncedTrash.root.url.path,
                    relativePath: entry.originalRelativePath)
            } else {
                originMetadata = Self.trashOriginMetadata(from: originData)
            }
            let destination = Self.trashRestoreDestination(
                for: sourceURL,
                metadata: originMetadata,
                availableRoots: roots,
                defaultRoot: syncedTrash?.root.url ?? defaultProject.url)
            let rootProject =
                getRootProjects().first {
                    $0.url.standardizedFileURL.resolvingSymlinksInPath() == destination.rootURL
                } ?? defaultProject
            let targetProject =
                ensureProjectForGitNote(
                    at: destination.fileURL,
                    under: rootProject) ?? rootProject

            guard note.move(to: destination.fileURL, project: targetProject) else {
                let error = NSError(
                    domain: "com.tw93.miaoyan.trash",
                    code: 2,
                    userInfo: [
                        NSLocalizedDescriptionKey:
                            "Could not restore \(note.url.path) to \(destination.fileURL.path)"
                    ])
                AppDelegate.trackError(error, context: "Storage.restoreTrash.move")
                failedCount += 1
                continue
            }

            if syncedTrash != nil {
                do {
                    try removeSyncedTrashMetadata(for: sourceURL)
                } catch {
                    AppDelegate.trackError(error, context: "Storage.restoreTrash.syncedMetadata")
                }
            }
            try? note.url.removeExtendedAttribute(forName: AppIdentifier.trashOriginKey)
            try? note.url.removeExtendedAttribute(forName: AppIdentifier.removedFromTrashKey)
            note.invalidateCache()
            restored.append(note)
        }

        return (restored, failedCount)
    }

    func getSubFolders(url: URL) -> [NSURL]? {
        let keys: [URLResourceKey] = [.isDirectoryKey, .isPackageKey, .isHiddenKey, .isSymbolicLinkKey]
        let options: FileManager.DirectoryEnumerationOptions = [.skipsHiddenFiles, .skipsPackageDescendants, .skipsSubdirectoryDescendants]

        guard let fileEnumerator = FileManager.default.enumerator(at: url, includingPropertiesForKeys: keys, options: options) else {
            return nil
        }

        var extensions = allowedExtensions
        // Common image and file extensions to skip as "folders"
        for ext in ["jpg", "png", "gif", "jpeg", "json", "JPG", "PNG", ".icloud"] {
            extensions.append(ext)
        }
        // Specific folder names to skip
        let skipFolders = Set(["assets", ".cache", "i", ".Trash", "files"])

        var subDirs = [NSURL]()

        for case let fileURL as URL in fileEnumerator {
            // Skip check for extensions (optimization: check extension first as it's faster)
            if extensions.contains(fileURL.pathExtension) { continue }

            // Skip check for specific folder names
            if skipFolders.contains(fileURL.lastPathComponent) { continue }

            do {
                let resourceValues = try fileURL.resourceValues(forKeys: Set(keys))

                // Symlinks first: .isDirectoryKey follows the link, so a directory-symlink
                // would otherwise be appended twice.
                if resourceValues.isSymbolicLink ?? false {
                    let resolved = fileURL.resolvingSymlinksInPath()
                    var isDir: ObjCBool = false
                    if FileManager.default.fileExists(atPath: resolved.path, isDirectory: &isDir),
                        isDir.boolValue,
                        let resolvedValues = try? resolved.resourceValues(forKeys: [.isPackageKey]),
                        !(resolvedValues.isPackage ?? true)
                    {
                        if isAttachmentOnlyFolder(url: resolved) { continue }
                        subDirs.append(fileURL as NSURL)
                    }
                    continue
                }

                if let isDirectory = resourceValues.isDirectory, isDirectory,
                    let isPackage = resourceValues.isPackage, !isPackage
                {
                    if isAttachmentOnlyFolder(url: fileURL) { continue }
                    subDirs.append(fileURL as NSURL)
                }
            } catch {
                continue
            }
        }

        return subDirs
    }

    /// True when `url` is a leaf folder that holds only attachment-style files:
    /// at least one file, no note file (md/markdown/txt), and no subfolder. Such
    /// folders (an `images` / `videos` dir of inline media, regardless of name)
    /// carry nothing to navigate to, so the sidebar hides them. Empty folders and
    /// folders that contain notes or subfolders return false, so a freshly created
    /// folder still appears. Immediate children only, no recursion, so a large
    /// media dir costs one shallow read and symlink loops are impossible.
    func isAttachmentOnlyFolder(url: URL) -> Bool {
        let keys: [URLResourceKey] = [.isDirectoryKey, .isPackageKey]
        let options: FileManager.DirectoryEnumerationOptions = [.skipsHiddenFiles, .skipsPackageDescendants, .skipsSubdirectoryDescendants]
        guard let enumerator = FileManager.default.enumerator(at: url, includingPropertiesForKeys: keys, options: options) else {
            return false
        }

        var hasFile = false
        for case let fileURL as URL in enumerator {
            let values = try? fileURL.resourceValues(forKeys: Set(keys))
            // A subfolder means this is a structural folder, not a leaf media dump.
            if values?.isDirectory == true { return false }
            // A note file means there is something to navigate to; keep it.
            if allowedExtensions.contains(fileURL.pathExtension.lowercased()) { return false }
            hasFile = true
        }

        return hasFile
    }

    public func getCurrentProject() -> Project? {
        projects.first
    }

    public func getAllTrash() -> [Note] {
        noteList.filter {
            $0.isTrash()
        }
    }

    public func initiateCloudDriveSync() {
        for project in projects {
            syncDirectory(url: project.url)
        }

        for imageFolder in imageFolders {
            syncDirectory(url: imageFolder)
        }
    }

    public func syncDirectory(url: URL) {
        do {
            let directoryFiles =
                try FileManager.default.contentsOfDirectory(at: url, includingPropertiesForKeys: [.contentModificationDateKey, .creationDateKey])

            let files =
                directoryFiles.filter {
                    !isDownloaded(url: $0)
                }

            let images = files.map { url in
                (
                    url,
                    (try? url.resourceValues(forKeys: [.contentModificationDateKey]))?.contentModificationDate ?? Date.distantPast,
                    (try? url.resourceValues(forKeys: [.creationDateKey]))?.creationDate ?? Date.distantPast
                )
            }

            for image in images {
                let url = image.0 as URL

                if FileManager.default.isUbiquitousItem(at: url) {
                    try? FileManager.default.startDownloadingUbiquitousItem(at: url)
                }
            }
        } catch {
        }
    }

    public func findOrphanAttachments(completion: @escaping @MainActor ([URL]) -> Void) {
        let referenced = collectReferencedAttachmentPaths()
        let attachmentFolders = collectAttachmentFolders()

        DispatchQueue.global(qos: .userInitiated).async {
            let orphaned = Storage.scanOrphanAttachments(folders: attachmentFolders, referenced: referenced)

            DispatchQueue.main.async {
                completion(orphaned)
            }
        }
    }

    private func collectAttachmentFolders() -> [URL] {
        var folders = [URL]()
        let manager = FileManager.default

        for project in projects where !project.isTrash {
            for folderName in Storage.attachmentDirectoryNames {
                let folderURL = project.url.appendingPathComponent(folderName)
                var isDir = ObjCBool(false)

                if manager.fileExists(atPath: folderURL.path, isDirectory: &isDir), isDir.boolValue {
                    folders.append(folderURL)
                }
            }
        }

        return folders
    }

    nonisolated private static func scanOrphanAttachments(folders: [URL], referenced: Set<String>) -> [URL] {
        var orphaned = [URL]()
        let manager = FileManager.default

        for folderURL in folders {
            guard let enumerator = manager.enumerator(at: folderURL, includingPropertiesForKeys: [.isDirectoryKey], options: [.skipsHiddenFiles]) else {
                continue
            }

            for case let fileURL as URL in enumerator {
                if shouldSkipAttachmentCandidate(fileURL) {
                    continue
                }

                if !referenced.contains(fileURL.path) {
                    orphaned.append(fileURL)
                }
            }
        }

        return orphaned
    }

    public func removeAttachments(urls: [URL]) -> (removed: [URL], failed: [URL]) {
        var removed = [URL]()
        var failed = urls.filter { !GitSyncLibraryMutationGate.allowsMutation(at: $0) }
        let manager = FileManager.default

        for url in urls where GitSyncLibraryMutationGate.allowsMutation(at: url) {
            do {
                var resultingItemUrl: NSURL?
                try manager.trashItem(at: url, resultingItemURL: &resultingItemUrl)
                removed.append(url)
            } catch {
                do {
                    try manager.removeItem(at: url)
                    removed.append(url)
                } catch {
                    failed.append(url)
                    AppDelegate.trackError(error, context: "Storage.cleanOrphanAttachments")
                }
            }
        }

        return (removed, failed)
    }

    private func collectReferencedAttachmentPaths() -> Set<String> {
        var referenced = Set<String>()

        for note in noteList {
            referenced.formUnion(note.getReferencedAttachmentPaths())
        }

        return referenced
    }

    nonisolated private static func shouldSkipAttachmentCandidate(_ url: URL) -> Bool {
        var isDirectory = ObjCBool(false)
        if FileManager.default.fileExists(atPath: url.path, isDirectory: &isDirectory), isDirectory.boolValue {
            return true
        }

        let name = url.lastPathComponent
        if name.hasPrefix(".") || name.hasSuffix(".icloud") {
            return true
        }

        return false
    }

    public func isDownloaded(url: URL) -> Bool {
        var isDownloaded: AnyObject?

        do {
            try (url as NSURL).getResourceValue(&isDownloaded, forKey: URLResourceKey.ubiquitousItemDownloadingStatusKey)
        } catch _ {}

        if isDownloaded as? URLUbiquitousItemDownloadingStatus == URLUbiquitousItemDownloadingStatus.current {
            return true
        }

        return false
    }

    public func initNote(url: URL) -> Note? {
        guard let project = getProjectBy(url: url) else {
            return nil
        }

        guard !Self.shouldHideRemovedTrashItem(at: url, in: project) else {
            return nil
        }

        let note = Note(url: url, with: project)

        return note
    }

    private func cleanTrash() {
        guard let trash = try? FileManager.default.url(for: .trashDirectory, in: .allDomainsMask, appropriateFor: UserDefaultsManagement.storageUrl, create: false) else {
            return
        }

        do {
            let fileURLs = try FileManager.default.contentsOfDirectory(at: trash, includingPropertiesForKeys: nil, options: [])

            for fileURL in fileURLs {
                try FileManager.default.removeItem(at: fileURL)
            }
        } catch {
            AppDelegate.trackError(error, context: "Storage.copyPins")
        }
    }

    public func saveCloudPins() {
    }

    public func restoreCloudPins() -> (removed: [Note]?, added: [Note]?) {
        return (nil, nil)
    }

    public func getPinned() -> [Note]? {
        noteList.filter(\.isPinned)
    }

    public func remove(project: Project) {
        if let index = projects.firstIndex(of: project) {
            projects.remove(at: index)
        }
    }

    public func getNotesBy(project: Project) -> [Note] {
        noteList.filter {
            $0.project == project
        }
    }

    /// Flush every note with unpersisted content synchronously.
    /// Called from lifecycle hooks (applicationWillTerminate, windowWillClose,
    /// windowDidResignKey) and explicit Cmd+S so the 1.5s debounce window
    /// cannot eat user edits.
    ///
    /// The .filter() pass produces a snapshot array up front so we never
    /// iterate `noteList` directly. flushPendingSave -> executeSave can
    /// trigger storage.add(self) (line ~603, when globalStorage=true), which
    /// mutates noteList. Iterating the original would crash with the
    /// "modified during iteration" trap on a hot path that the user feels.
    @discardableResult
    public func flushPendingSaves() -> Bool {
        let dirtyNotes = noteList.filter(\.needsSave)
        var allSucceeded = true
        for note in dirtyNotes where !note.flushPendingSave() {
            allSucceeded = false
        }
        return allSucceeded
    }

    public func loadProjects(from urls: [URL]) {
        var result = [URL]()
        for url in urls {
            do {
                _ = try FileManager.default.contentsOfDirectory(atPath: url.path)
                result.append(url)
            } catch {
                AppDelegate.trackError(error, context: "Storage.enumerateNotes")
            }
        }

        let projects =
            result.compactMap {
                Project(url: $0)
            }

        guard !projects.isEmpty else {
            return
        }

        self.projects.removeAll()

        for project in projects {
            self.projects.append(project)
        }
    }

    public func trashItem(url: URL) -> URL? {
        guard let trashURL = Storage.sharedInstance().getDefaultTrash()?.url else {
            return nil
        }

        let fileName = url.deletingPathExtension().lastPathComponent
        let fileExtension = url.pathExtension

        var destination = trashURL.appendingPathComponent(url.lastPathComponent)

        var i = 0

        while FileManager.default.fileExists(atPath: destination.path) {
            let nextName = "\(fileName)_\(i).\(fileExtension)"
            destination = trashURL.appendingPathComponent(nextName)
            i += 1
        }

        return destination
    }

    deinit {
        for url in scopedURLs {
            url.stopAccessingSecurityScopedResource()
        }
    }
}
