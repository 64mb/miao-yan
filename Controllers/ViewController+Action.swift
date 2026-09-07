import Carbon
import Cocoa

struct GitSyncSettingsInput {
    let remoteURL: String
    let username: String
    let personalAccessToken: String
    let authorName: String
    let authorEmail: String
    let automaticSyncEnabled: Bool
    let aiEnabled: Bool
    let aiBaseURL: String
    let aiModel: String
    let aiAPIKey: String
    let aiPrompt: String
}

private enum GitSyncAutomaticRunError: LocalizedError {
    case cloudBackedLibrary
    case missingCredentials
    case blocked(String)

    var errorDescription: String? {
        switch self {
        case .cloudBackedLibrary:
            return "Automatic Git sync refused a cloud-backed or non-local library."
        case .missingCredentials:
            return "Automatic Git sync could not load the configured Git credentials."
        case .blocked(let description):
            return "Automatic Git sync was blocked: \(description)"
        }
    }
}

// MARK: - User Actions and Operations
extension ViewController {

    // MARK: - IBAction Methods
    @IBAction func activeWindow(_ sender: Any) {
        activeShortcut()
    }

    @IBAction func showInfo(_ sender: Any) {
        let selectedCell = notesTableView.view(atColumn: 0, row: notesTableView.selectedRow, makeIfNecessary: false)

        guard let positioningView = selectedCell else {
            return
        }
        let positioningRect = NSRect.zero

        let preferredEdge = NSRectEdge(rectEdge: .maxXEdge)

        popover.show(relativeTo: positioningRect, of: positioningView, preferredEdge: preferredEdge)

        let popoverWindowX = popover.contentViewController?.view.window?.frame.origin.x ?? 0
        let popoverWindowY = popover.contentViewController?.view.window?.frame.origin.y ?? 0

        popover.contentViewController?.view.window?.setFrameOrigin(
            NSPoint(x: popoverWindowX + 18, y: popoverWindowY)
        )

        popover.contentViewController?.view.window?.makeKey()
    }

    @IBAction func searchAndCreate(_ sender: Any) {
        guard let vc = ViewController.shared() else {
            return
        }

        let size = vc.splitView.subviews[0].frame.width

        if size == 0 {
            toggleNoteList(self)
        }

        vc.search.window?.makeFirstResponder(vc.search)
    }

    @IBAction func sortDirectionBy(_ sender: NSMenuItem) {
        let name = sender.identifier!.rawValue
        if name == "Ascending", UserDefaultsManagement.sortDirection {
            UserDefaultsManagement.sortDirection = false
            reSortByDirection()
        }
        if name == "Descending", !UserDefaultsManagement.sortDirection {
            UserDefaultsManagement.sortDirection = true
            reSortByDirection()
        }
    }

    @IBAction func sortBy(_ sender: NSMenuItem) {
        if let id = sender.identifier {
            let key = String(id.rawValue.dropFirst(3))
            guard let sortBy = SortBy(rawValue: key) else { return }
            UserDefaultsManagement.sort = sortBy
            syncSortFieldMenuState()
            reSortByDirection()
        }
    }

    @IBAction func quiteApp(_ sender: Any) {
        if UserDefaultsManagement.isSingleMode {
            UserDefaultsManagement.clearSingleMode()
            UserDefaultsManagement.isFirstLaunch = true
            DispatchQueue.main.asyncAfter(deadline: .now() + UIDelay.short) {
                NSApplication.shared.terminate(self)
            }
        } else {
            NSApplication.shared.terminate(self)
        }
    }

    @IBAction func makeNote(_ sender: SearchTextField) {
        guard let vc = ViewController.shared() else { return }
        if let type = vc.getSidebarType(), type == .Trash {
            vc.storageOutlineView.deselectAll(nil)
        }

        let value = sender.stringValue

        // Avoid editArea.clear() here: it hides titleBarView and would cause a
        // hide-then-show flicker. prepareForNoteCreation (inside createNote)
        // already clears the title field and editor body in the same frame.
        if !value.isEmpty {
            search.stringValue = String()
            createNote(name: value, content: "")
        } else {
            createNote(content: "")
        }
    }

    @IBAction func fileMenuNewNote(_ sender: Any) {
        guard let vc = ViewController.shared() else { return }
        if vc.sessionMagicPPTMode {
            return
        }
        if let type = vc.getSidebarType(), type == .Trash {
            vc.storageOutlineView.deselectAll(nil)
        }
        vc.createNote(name: "", content: "")
    }

    @IBAction func singleOpen(_ sender: NSMenuItem) {
        let panel = NSOpenPanel()
        panel.allowsMultipleSelection = false
        panel.canChooseDirectories = true
        panel.canChooseFiles = true
        panel.canCreateDirectories = false
        panel.begin { result in
            if result == NSApplication.ModalResponse.OK {
                let urls = panel.urls
                UserDefaultsManagement.beginSingleMode(for: urls[0])
                self.reloadForSingleMode()
            }
        }
    }

    @IBAction func importFiles(_ sender: NSMenuItem) {
        let panel = NSOpenPanel()
        panel.allowsMultipleSelection = true
        panel.canChooseDirectories = true
        panel.canChooseFiles = true
        panel.canCreateDirectories = false
        panel.message = I18n.str("Select Markdown files or folders to import")
        panel.begin { result in
            guard result == NSApplication.ModalResponse.OK else { return }
            let extensions = AppEnvironment.current.storage.allowedExtensions
            let pickedUrls = panel.urls
            // The recursive scan is unbounded (a picked folder can be huge);
            // keep it off the main thread. The md copies in importNotes stay
            // on main: they are bounded by the scan result and entangled with
            // the FS watcher's focusOnImport timing.
            DispatchQueue.global(qos: .userInitiated).async {
                let files = ViewController.collectImportableFiles(from: pickedUrls, allowedExtensions: extensions)
                DispatchQueue.main.async {
                    guard !files.isEmpty else {
                        ViewController.shared()?.toast(message: I18n.str("No importable Markdown files found~"), style: .failure)
                        return
                    }
                    guard let appDelegate = NSApplication.shared.delegate as? AppDelegate else { return }
                    appDelegate.importNotes(urls: files)
                }
            }
        }
    }

    /// Expands the open-panel selection into importable note files. Folders are
    /// scanned recursively; attachment folders (`i/`, `files/`), the in-storage
    /// `.Trash`, and hidden entries are skipped. Symlinked directories are not
    /// followed, which keeps the scan loop-free.
    nonisolated static func collectImportableFiles(from urls: [URL], allowedExtensions: [String]) -> [URL] {
        let skippedDirectories: Set<String> = [".Trash", "i", "files"]
        var collected: [URL] = []
        // Dedupe on the symlink-resolved path: a folder pick plus a file pick
        // inside it can yield /var vs /private/var forms of the same file.
        var seenPaths = Set<String>()

        func collect(_ url: URL) {
            let canonical = url.resolvingSymlinksInPath().path
            if seenPaths.insert(canonical).inserted {
                collected.append(url)
            }
        }

        for url in urls {
            var isDirectory: ObjCBool = false
            guard FileManager.default.fileExists(atPath: url.path, isDirectory: &isDirectory) else { continue }

            if !isDirectory.boolValue {
                if allowedExtensions.contains(url.pathExtension.lowercased()) {
                    collect(url)
                }
                continue
            }

            guard
                let enumerator = FileManager.default.enumerator(
                    at: url, includingPropertiesForKeys: [.isDirectoryKey], options: [.skipsHiddenFiles])
            else { continue }

            while let item = enumerator.nextObject() as? URL {
                let values = try? item.resourceValues(forKeys: [.isDirectoryKey])
                if values?.isDirectory == true {
                    if skippedDirectories.contains(item.lastPathComponent) {
                        enumerator.skipDescendants()
                    }
                    continue
                }
                if allowedExtensions.contains(item.pathExtension.lowercased()) {
                    collect(item)
                }
            }
        }
        return collected.sorted { $0.path < $1.path }
    }

    @IBAction func moveMenu(_ sender: Any) {
        guard let vc = ViewController.shared() else { return }
        if vc.notesTableView.selectedRow >= 0 {
            vc.loadMoveMenu()

            let moveTitle = I18n.str("Move")
            let moveMenu = vc.noteMenu.item(withTitle: moveTitle)
            let view = vc.notesTableView.rect(ofRow: vc.notesTableView.selectedRow)
            let x = vc.splitView.subviews[0].frame.width + 5
            let general = moveMenu?.submenu?.item(at: 0)

            moveMenu?.submenu?.popUp(positioning: general, at: NSPoint(x: x, y: view.origin.y + 8), in: vc.notesTableView)
        }
    }

    @IBAction func exportMenu(_ sender: Any) {
        guard let vc = ViewController.shared() else { return }
        if vc.notesTableView.selectedRow >= 0 {
            let exportTitle = I18n.str("Export")
            let exportMenu = vc.noteMenu.item(withTitle: exportTitle)
            let view = vc.notesTableView.rect(ofRow: vc.notesTableView.selectedRow)
            let x = vc.splitView.subviews[0].frame.width + 5
            let general = exportMenu?.submenu?.item(at: 0)

            exportMenu?.submenu?.popUp(positioning: general, at: NSPoint(x: x, y: view.origin.y + 8), in: vc.notesTableView)
        }
    }

    @IBAction func fileName(_ sender: NSTextField) {
        let pendingNote = UserDataService.instance.pendingTitleChange?.note
        let focusedNote = titleLabel.hasFocus() ? EditTextView.note : nil
        guard let note = pendingNote ?? focusedNote ?? notesTableView.getNoteFromSelectedRow() else {
            return
        }

        let value = sender.stringValue
        let url = note.url

        let newName = sender.stringValue + "." + note.url.pathExtension
        let isSoftRename = note.url.lastPathComponent.lowercased() == newName.lowercased()

        if note.project.fileExist(fileName: value, ext: note.url.pathExtension), !isSoftRename {
            MiaoYanAlert.show(
                message: I18n.str("Duplicate note name"),
                informativeText: String(format: I18n.str("Note \"%@\" already exists"), value),
                for: view.window
            )

            note.parseURL()
            sender.stringValue = note.getTitleWithoutLabel()
            return
        }

        guard !value.isEmpty else {
            sender.stringValue = note.getTitleWithoutLabel()
            return
        }

        sender.isEditable = false

        let newUrl = note.getNewURL(name: value)
        UserDataService.instance.focusOnImport = newUrl
        guard GitSyncLibraryMutationGate.allowsMutation(at: url),
            GitSyncLibraryMutationGate.allowsMutation(at: newUrl)
        else {
            sender.stringValue = note.getTitleWithoutLabel()
            return
        }

        if note.url.path == newUrl.path {
            updateTitleAndFinishImport(note: note, title: value)
            return
        }

        // Suppress our own FSEvent during the rename so the editor isn't
        // reloaded mid-flight. Even on failure the 0.2s window self-resets.
        blockFSUpdates()

        // Order matters for crash safety:
        //   1. Move the file on disk first.
        //   2. Only after success do we update note.url, otherwise a failed
        //      moveItem leaves note.url pointing at a path that doesn't exist
        //      and the next debounced save writes to the wrong place,
        //      splitting content across two files.
        do {
            try FileManager.default.moveItem(at: url, to: newUrl)
            note.overwrite(url: newUrl)
            updateTitleAndFinishImport(note: note, title: value)
        } catch {
            AppDelegate.trackError(error, context: "Note.rename.moveItem")
            note.parseURL()
            let originalTitle = note.getTitleWithoutLabel()
            updateTitleAndFinishImport(note: note, title: originalTitle)
            MiaoYanAlert.show(
                message: I18n.str("Save Failed"),
                informativeText: I18n.str(error.localizedDescription),
                for: view.window
            )
        }
    }

    private func updateTitleAndFinishImport(note: Note, title: String) {
        note.title = title
        titleLabel.setStringValueSafely(title)
        titleLabel.updateNotesTableView()
        UserDataService.instance.focusOnImport = nil
    }

    @IBAction func finderMenu(_ sender: NSMenuItem) {
        if let notes = notesTableView.getSelectedNotes() {
            var urls = [URL]()
            for note in notes {
                urls.append(note.url)
            }
            NSWorkspace.shared.activateFileViewerSelecting(urls)
        }
    }

    @IBAction func makeMenu(_ sender: Any) {
        guard let vc = ViewController.shared() else { return }
        if let type = vc.getSidebarType(), type == .Trash {
            vc.storageOutlineView.deselectAll(nil)
        }

        vc.createNote()
    }

    @IBAction func pinMenu(_ sender: Any) {
        guard let vc = ViewController.shared() else { return }
        vc.pin(vc.notesTableView.selectedRowIndexes)
    }

    @IBAction func renameMenu(_ sender: Any) {
        guard let vc = ViewController.shared() else { return }
        vc.titleLabel.restoreResponder = vc.view.window?.firstResponder
        switchTitleToEditMode()
    }

    // MARK: - Reload Note from Disk
    @IBAction func reloadCurrentNote(_ sender: Any) {
        guard let vc = ViewController.shared() else { return }
        guard let note = vc.notesTableView.getSelectedNote() else { return }

        // Ensure content is loaded before comparison
        note.ensureContentLoaded()

        // Check if editor has unsaved changes
        let editorContent = vc.editArea.string
        let noteContent = note.content.string

        if editorContent != noteContent {
            vc.showReloadConfirmation(note: note)
            return
        }

        vc.reloadNoteFromDisk(note)
    }

    private func showReloadConfirmation(note: Note) {
        MiaoYanAlert.confirm(
            message: I18n.str("Reload Note"),
            informativeText: I18n.str("You have unsaved changes. Reload will discard them."),
            confirmTitle: I18n.str("Reload"),
            for: view.window
        ) { [weak self] confirmed in
            if confirmed {
                self?.reloadNoteFromDisk(note)
            }
        }
    }

    private func reloadNoteFromDisk(_ note: Note) {
        let oldContent = editArea.string

        // Force reload from disk
        note.forceReload()
        note.loadModifiedLocalAt()

        let newContent = note.content.string

        // Find cursor position at end of changed content
        let cursorPosition = findChangeEndPosition(oldContent: oldContent, newContent: newContent)

        // Update editor (suppressSave: true to prevent overwriting disk content with old editor content)
        refillEditArea(cursor: cursorPosition, force: true, suppressSave: true)

        // Update table row
        notesTableView.reloadRow(note: note)

        // Toast feedback
        toast(message: I18n.str("Note reloaded~"), style: .success)
    }

    private func findChangeEndPosition(oldContent: String, newContent: String) -> Int {
        // Find common prefix length
        var prefixEnd = 0
        let oldChars = Array(oldContent)
        let newChars = Array(newContent)
        let minLen = min(oldChars.count, newChars.count)

        while prefixEnd < minLen && oldChars[prefixEnd] == newChars[prefixEnd] {
            prefixEnd += 1
        }

        // Find common suffix length
        var suffixLen = 0
        while suffixLen < minLen - prefixEnd
            && oldChars[oldChars.count - 1 - suffixLen] == newChars[newChars.count - 1 - suffixLen]
        {
            suffixLen += 1
        }

        // Cursor goes to end of changed region in new content
        return newChars.count - suffixLen
    }

    @IBAction func deleteNote(_ sender: Any) {
        guard let vc = ViewController.shared() else {
            return
        }

        let isPreviewSearchVisible = vc.editArea.markdownView?.isSearchBarVisible ?? false
        if vc.titleLabel.hasFocus() || vc.editArea.hasFocus() || vc.search.hasFocus() || vc.sessionMagicPPTMode || vc.sessionPresentationMode || vc.editArea.isSearchBarVisible || isPreviewSearchVisible {
            return
        }

        guard let notes = vc.notesTableView.getSelectedNotes() else {
            return
        }

        if let si = vc.getSidebarItem(), si.isTrash() {
            removeForever()
            return
        }

        let selectedRow = vc.notesTableView.selectedRowIndexes.min() ?? -1

        UserDataService.instance.searchTrigger = true

        let onPartialFailure: (Int) -> Void = { failedCount in
            DispatchQueue.main.async { [weak vc] in
                guard let vc = vc else { return }
                vc.toast(
                    message: String(format: I18n.str("Failed to move %d note(s) to Trash~"), failedCount),
                    style: .failure
                )
            }
        }
        vc.storage.removeNotes(
            notes: notes,
            partialFailure: onPartialFailure,
            didRemove: { removedNotes in
                vc.notesTableView.removeAndReselect(
                    notes: removedNotes,
                    originalRow: selectedRow)
            }
        ) { urls in
            if let appd = NSApplication.shared.delegate as? AppDelegate,
                let md = appd.mainWindowController
            {
                let undoManager = md.notesListUndoManager

                if let ntv = vc.notesTableView {
                    undoManager.registerUndo(withTarget: ntv, selector: #selector(ntv.unDelete), object: urls)
                    undoManager.setActionName(I18n.str("Delete"))
                }

                UserDataService.instance.searchTrigger = false
            }

            // Only clear the editor when the list became empty. With a next
            // note auto-selected above, handleSelectionChange has already
            // filled the editor with the new content, so calling clear() now
            // would just blank the editor for one frame before the new fill.
            if vc.notesTableView.noteList.isEmpty {
                vc.editArea.clear()
            }
        }

        NSApp.mainWindow?.makeFirstResponder(vc.notesTableView)
    }

    @IBAction func cleanUnusedAttachments(_ sender: NSMenuItem) {
        guard let vc = ViewController.shared() else {
            return
        }

        storage.findOrphanAttachments { [weak self, weak vc] orphanAttachments in
            guard let self = self, let vc = vc else { return }

            if orphanAttachments.isEmpty {
                vc.toast(message: I18n.str("No orphan attachments found"))
                return
            }

            MiaoYanAlert.confirm(
                message: I18n.str("Clean Orphan Attachments"),
                informativeText: String(format: I18n.str("Detected %d unused attachment(s). Move them to Trash?"), orphanAttachments.count),
                confirmTitle: I18n.str("Clean"),
                for: vc.view.window
            ) { confirmed in
                guard confirmed else { return }

                let result = self.storage.removeAttachments(urls: orphanAttachments)

                if !result.removed.isEmpty {
                    NSSound(named: "Pop")?.play()
                    vc.toast(message: String(format: I18n.str("Removed %d unused attachment(s)"), result.removed.count), style: .success)
                }

                if !result.failed.isEmpty {
                    MiaoYanAlert.show(
                        message: I18n.str("Some attachments could not be removed"),
                        informativeText: I18n.str("Please try again"),
                        style: .warning,
                        for: vc.view.window
                    )
                }
            }
        }
    }

    @IBAction func openProjectViewSettings(_ sender: NSMenuItem) {
        guard let vc = ViewController.shared() else {
            return
        }

        if let controller = vc.storyboard?.instantiateController(withIdentifier: "ProjectSettingsViewController")
            as? ProjectSettingsViewController
        {
            projectSettingsViewController = controller

            if let project = vc.getSidebarProject() {
                vc.presentAsSheet(controller)
                controller.load(project: project)
            }
        }
    }

    @IBAction func configureGitSync(_ sender: Any) {
        configureGitSync(presentingWindow: view.window)
    }

    func configureGitSync(presentingWindow: NSWindow?, completion: (() -> Void)? = nil) {
        guard let root = selectedGitSyncRoot() else {
            MiaoYanAlert.show(
                message: I18n.str("Select a project in the main library first."),
                style: .warning,
                for: presentingWindow ?? view.window
            )
            return
        }
        Task { @MainActor [weak self] in
            guard let self else { return }
            if await GitSyncLibraryLocationPolicy.isCloudBacked(root.url) {
                presentGitCloudMigrationOffer(for: root, presentingWindow: presentingWindow)
            } else {
                presentGitConfiguration(for: root, presentingWindow: presentingWindow, completion: completion)
            }
        }
    }

    func saveGitSyncSettings(
        _ input: GitSyncSettingsInput,
        presentingWindow: NSWindow?,
        completion: (() -> Void)? = nil
    ) {
        guard let root = selectedGitSyncRoot() else {
            MiaoYanAlert.show(
                message: I18n.str("Select a project in the main library first."),
                style: .warning,
                for: presentingWindow ?? view.window
            )
            return
        }

        Task { @MainActor [weak self] in
            guard let self else { return }
            if await GitSyncLibraryLocationPolicy.isCloudBacked(root.url) {
                presentGitCloudMigrationOffer(for: root, presentingWindow: presentingWindow)
                return
            }

            do {
                try persistGitSyncSettings(input, for: root)
                toast(message: I18n.str("Git sync configured~"), style: .success)
                completion?()
            } catch {
                AppDelegate.trackError(error, context: "ViewController.saveGitSyncSettings")
                MiaoYanAlert.show(
                    message: I18n.str("Git sync configuration is invalid."),
                    informativeText: gitConfigurationErrorDescription(error),
                    style: .warning,
                    for: presentingWindow ?? view.window
                )
            }
        }
    }

    func requestGitLibraryMigration(presentingWindow: NSWindow?) {
        guard let root = selectedGitSyncRoot() else {
            MiaoYanAlert.show(
                message: I18n.str("Select a project in the main library first."),
                style: .warning,
                for: presentingWindow ?? view.window
            )
            return
        }

        Task { @MainActor [weak self] in
            guard let self else { return }
            if await GitSyncLibraryLocationPolicy.isCloudBacked(root.url) {
                presentGitCloudMigrationOffer(for: root, presentingWindow: presentingWindow)
            } else {
                MiaoYanAlert.show(
                    message: I18n.str("The current library is already stored locally."),
                    style: .informational,
                    for: presentingWindow ?? view.window
                )
            }
        }
    }

    private func persistGitSyncSettings(_ input: GitSyncSettingsInput, for root: Project) throws {
        let existingConfiguration = gitSyncConfigurationStore.configuration(for: root.url)
        let existingCredential = existingConfiguration.flatMap {
            try? AppEnvironment.current.gitCredentialStore.credential(for: $0.remoteURL)
        }
        let existingAI = existingConfiguration?.ai
        let existingAIKey = existingAI.flatMap {
            try? AppEnvironment.current.gitAIKeyStore.apiKey(for: $0.baseURL)
        }

        guard let remoteURL = URL(string: input.remoteURL),
            !input.authorName.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
            !input.authorEmail.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
        else { throw GitCredentialStoreError.invalidRemoteURL }

        let aiConfiguration: GitAIConfiguration?
        let desiredAIKey: String?
        if input.aiEnabled {
            guard let baseURL = URL(string: input.aiBaseURL) else {
                throw GitAIConflictResolverError.invalidConfiguration
            }
            let candidate = GitAIConfiguration(
                baseURL: baseURL,
                model: input.aiModel.trimmingCharacters(in: .whitespacesAndNewlines),
                prompt: input.aiPrompt.trimmingCharacters(in: .whitespacesAndNewlines)
            )
            guard candidate.chatCompletionsURL != nil, !candidate.model.isEmpty, !candidate.prompt.isEmpty else {
                throw GitAIConflictResolverError.invalidConfiguration
            }
            let enteredAIKey = input.aiAPIKey.trimmingCharacters(in: .whitespacesAndNewlines)
            if !enteredAIKey.isEmpty {
                desiredAIKey = enteredAIKey
            } else if existingAI?.baseURL != baseURL || existingAIKey == nil {
                throw GitAIConflictResolverError.invalidConfiguration
            } else {
                desiredAIKey = existingAIKey
            }
            aiConfiguration = candidate
        } else {
            aiConfiguration = nil
            desiredAIKey = nil
        }

        let configuration = GitSyncConfiguration(
            remoteURL: remoteURL,
            authorName: input.authorName.trimmingCharacters(in: .whitespacesAndNewlines),
            authorEmail: input.authorEmail.trimmingCharacters(in: .whitespacesAndNewlines),
            automaticSyncEnabled: input.automaticSyncEnabled,
            ai: aiConfiguration
        )
        let token = input.personalAccessToken.trimmingCharacters(in: .whitespacesAndNewlines)
        let canReuseToken: Bool
        if let oldURL = existingConfiguration?.remoteURL {
            canReuseToken =
                try GitCredentialStore.normalizedRemoteURL(oldURL)
                == GitCredentialStore.normalizedRemoteURL(remoteURL)
        } else {
            canReuseToken = false
        }
        let finalToken: String
        if token.isEmpty, canReuseToken, let existingCredential {
            finalToken = existingCredential.personalAccessToken
        } else {
            finalToken = token
        }
        let credential = GitHTTPSCredential(
            username: input.username.trimmingCharacters(in: .whitespacesAndNewlines),
            personalAccessToken: finalToken
        )
        try persistGitSyncSettings(
            configuration,
            credential: credential,
            aiKey: desiredAIKey,
            replacing: existingConfiguration,
            for: root.url
        )
    }

    private func presentGitCloudMigrationOffer(for root: Project, presentingWindow: NSWindow?) {
        let details = I18n.str(
            "This folder is managed by iCloud, a File Provider, or a network volume. "
                + "MiaoYan will copy it to local Application Support and verify every file. "
                + "An app-owned iCloud library is moved to Trash; broad cloud roots are left in place."
        )
        let alert = MiaoYanAlert.make(
            message: I18n.str("Move the library before enabling Git sync?"),
            informativeText: details,
            style: .warning,
            buttons: [I18n.str("Move and Restart"), I18n.str("Cancel")]
        )
        MiaoYanAlert.present(alert, for: presentingWindow ?? view.window) { [weak self] response in
            guard response == .alertFirstButtonReturn, let self else { return }
            migrateGitLibraryToLocalStorage(root, presentingWindow: presentingWindow)
        }
    }

    private func migrateGitLibraryToLocalStorage(_ root: Project, presentingWindow: NSWindow?) {
        guard !gitSyncCoordinator.state.isRunning else {
            MiaoYanAlert.show(
                message: I18n.str("The library could not be moved."),
                informativeText: I18n.str("Wait for the current Git sync to finish."),
                style: .warning,
                for: presentingWindow ?? view.window
            )
            return
        }
        if let owner = editArea.storageNote, owner.project.getParent() == root {
            editArea.saveTextStorageContent(to: owner)
        }
        guard storage.flushPendingSaves() else {
            MiaoYanAlert.show(
                message: I18n.str("The library could not be moved."),
                informativeText: I18n.str("Pending note changes could not be saved."),
                style: .warning,
                for: presentingWindow ?? view.window
            )
            return
        }
        guard GitSyncLibraryMutationGate.beginMigration(of: root.url) else {
            MiaoYanAlert.show(
                message: I18n.str("The library could not be moved."),
                informativeText: I18n.str("Wait for active image uploads or the current library migration to finish."),
                style: .warning,
                for: presentingWindow ?? view.window
            )
            return
        }
        editArea.isEditable = false
        fsManager?.beginGitMutation()
        toastPersistent(message: I18n.str("Moving library to local storage…"))
        let previousStoragePath = UserDefaultsManagement.storagePath
        let previousBookmark = UserDefaultsManagement.storageBookmark
        let ownedICloudLibraryRoot = UserDefaultsManagement.iCloudDocumentsContainer
        Task { @MainActor [weak self] in
            defer { GitSyncLibraryMutationGate.endMigration() }
            guard let self else { return }
            do {
                let result = try await Task.detached {
                    try GitSyncLibraryMigrator.migrate(source: root.url)
                }.value
                UserDefaultsManagement.storagePath = result.destination.path
                UserDefaultsManagement.clearSingleMode()
                let bookmark = try? result.destination.bookmarkData(
                    options: .withSecurityScope,
                    includingResourceValuesForKeys: nil,
                    relativeTo: nil
                )
                UserDefaultsManagement.storageBookmark = bookmark
                UserDefaults.standard.synchronize()
                var originalWasTrashed = false
                do {
                    originalWasTrashed = try await Task.detached {
                        try GitSyncLibraryMigrator.trashOriginal(
                            root.url,
                            verifiedCopyAt: result.destination,
                            ownedICloudLibraryRoot: ownedICloudLibraryRoot
                        )
                    }.value
                } catch {
                    if case GitSyncLibraryMigrationError.originalTrashFailed = error {
                        originalWasTrashed = false
                    } else if FileManager.default.fileExists(atPath: root.url.path) {
                        UserDefaultsManagement.storagePath = previousStoragePath ?? root.url.path
                        UserDefaultsManagement.storageBookmark = previousBookmark
                        UserDefaults.standard.synchronize()
                        let preservedCopy = I18n.str("The verified local copy was preserved at:")
                        let detailedError = NSError(
                            domain: "MiaoYan.GitLibraryMigration",
                            code: 1,
                            userInfo: [
                                NSLocalizedDescriptionKey:
                                    error.localizedDescription + "\n\n" + preservedCopy + "\n" + result.destination.path
                            ]
                        )
                        throw detailedError
                    } else {
                        // trashItem may report a late provider error after moving
                        // the source. In that case the verified local copy is the
                        // only authoritative location and must remain configured.
                        originalWasTrashed = true
                    }
                }
                fsManager?.endGitMutation()
                toastDismiss()
                if !originalWasTrashed {
                    MiaoYanAlert.show(
                        message: I18n.str("Library moved, but the original remains."),
                        informativeText: I18n.str("MiaoYan will use the local copy after restart. Remove the old cloud copy manually to avoid confusion."),
                        style: .warning,
                        for: presentingWindow ?? view.window
                    ) { _ in AppDelegate.relaunchApp() }
                } else {
                    AppDelegate.relaunchApp()
                }
            } catch {
                fsManager?.endGitMutation()
                editArea.isEditable = true
                toastDismiss()
                AppDelegate.trackError(error, context: "ViewController.migrateGitLibraryToLocalStorage")
                MiaoYanAlert.show(
                    message: I18n.str("The library could not be moved."),
                    informativeText: I18n.str("Please try again") + "\n\n" + error.localizedDescription,
                    style: .warning,
                    for: presentingWindow ?? view.window
                )
            }
        }
    }

    private func presentGitConfiguration(
        for root: Project,
        presentingWindow: NSWindow?,
        completion: (() -> Void)?
    ) {

        let existingConfiguration = gitSyncConfigurationStore.configuration(for: root.url)
        let existingCredential = existingConfiguration.flatMap {
            try? AppEnvironment.current.gitCredentialStore.credential(for: $0.remoteURL)
        }
        let remoteField = NSTextField(string: existingConfiguration?.remoteURL.absoluteString ?? "")
        remoteField.placeholderString = "https://github.com/owner/notes.git"
        let usernameField = NSTextField(string: existingCredential?.username ?? "")
        usernameField.placeholderString = I18n.str("Git username")
        let tokenField = NSSecureTextField(string: "")
        tokenField.placeholderString = existingCredential == nil ? I18n.str("Personal access token") : I18n.str("Stored token (leave blank to keep)")
        let authorNameField = NSTextField(string: existingConfiguration?.authorName ?? "")
        authorNameField.placeholderString = I18n.str("Commit author name")
        let authorEmailField = NSTextField(string: existingConfiguration?.authorEmail ?? "")
        authorEmailField.placeholderString = I18n.str("Commit author email")
        let existingAI = existingConfiguration?.ai
        let aiEnabledButton = NSButton(
            checkboxWithTitle: I18n.str("Enable AI conflict resolution"),
            target: nil,
            action: nil
        )
        aiEnabledButton.state = existingAI == nil ? .off : .on
        let aiBaseURLField = NSTextField(string: existingAI?.baseURL.absoluteString ?? GitAIConfiguration.defaultBaseURL.absoluteString)
        aiBaseURLField.placeholderString = "https://api.deepseek.com"
        let aiModelField = NSTextField(string: existingAI?.model ?? GitAIConfiguration.defaultModel)
        aiModelField.placeholderString = "deepseek-v4-flash"
        let existingAIKey = existingAI.flatMap { try? AppEnvironment.current.gitAIKeyStore.apiKey(for: $0.baseURL) }
        let aiKeyField = NSSecureTextField(string: "")
        aiKeyField.placeholderString = existingAIKey == nil ? I18n.str("AI API key") : I18n.str("Stored API key (leave blank to keep)")
        let promptView = NSTextView(frame: NSRect(x: 0, y: 0, width: 410, height: 150))
        promptView.isRichText = false
        promptView.font = NSFont.monospacedSystemFont(ofSize: NSFont.smallSystemFontSize, weight: .regular)
        promptView.string = existingAI?.prompt ?? GitAIConfiguration.defaultPrompt
        let promptScrollView = NSScrollView(frame: NSRect(x: 0, y: 0, width: 430, height: 150))
        promptScrollView.hasVerticalScroller = true
        promptScrollView.borderType = .bezelBorder
        promptScrollView.documentView = promptView
        promptScrollView.heightAnchor.constraint(equalToConstant: 150).isActive = true

        let stack = NSStackView(views: [
            labeledGitField(I18n.str("Repository HTTPS URL"), field: remoteField),
            labeledGitField(I18n.str("Username"), field: usernameField),
            labeledGitField(I18n.str("Personal access token"), field: tokenField),
            labeledGitField(I18n.str("Author name"), field: authorNameField),
            labeledGitField(I18n.str("Author email"), field: authorEmailField),
            aiEnabledButton,
            labeledGitField(I18n.str("AI API base URL"), field: aiBaseURLField),
            labeledGitField(I18n.str("AI model name"), field: aiModelField),
            labeledGitField(I18n.str("AI API key"), field: aiKeyField),
            labeledGitView(I18n.str("Conflict resolution prompt"), view: promptScrollView),
        ])
        stack.orientation = .vertical
        stack.alignment = .leading
        stack.spacing = 10
        stack.frame = NSRect(x: 0, y: 0, width: 430, height: 560)
        stack.views.forEach { $0.widthAnchor.constraint(equalTo: stack.widthAnchor).isActive = true }

        let alert = MiaoYanAlert.make(
            message: I18n.str("Configure Git Sync"),
            informativeText: I18n.str("The token is stored in macOS Keychain. MiaoYan syncs only the main branch over HTTPS."),
            buttons: [I18n.str("Save"), I18n.str("Cancel")]
        )
        alert.accessoryView = stack
        alert.window.initialFirstResponder = remoteField
        MiaoYanAlert.present(alert, for: presentingWindow ?? view.window) { [weak self] response in
            guard response == .alertFirstButtonReturn, let self else { return }
            do {
                guard let remoteURL = URL(string: remoteField.stringValue),
                    !authorNameField.stringValue.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
                    !authorEmailField.stringValue.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
                else {
                    throw GitCredentialStoreError.invalidRemoteURL
                }
                let aiConfiguration: GitAIConfiguration?
                let desiredAIKey: String?
                if aiEnabledButton.state == .on {
                    guard let baseURL = URL(string: aiBaseURLField.stringValue) else {
                        throw GitAIConflictResolverError.invalidConfiguration
                    }
                    let candidate = GitAIConfiguration(
                        baseURL: baseURL,
                        model: aiModelField.stringValue.trimmingCharacters(in: .whitespacesAndNewlines),
                        prompt: promptView.string.trimmingCharacters(in: .whitespacesAndNewlines)
                    )
                    guard candidate.chatCompletionsURL != nil, !candidate.model.isEmpty, !candidate.prompt.isEmpty else {
                        throw GitAIConflictResolverError.invalidConfiguration
                    }
                    let enteredAIKey = aiKeyField.stringValue.trimmingCharacters(in: .whitespacesAndNewlines)
                    if !enteredAIKey.isEmpty {
                        desiredAIKey = enteredAIKey
                    } else if existingAI?.baseURL != baseURL || existingAIKey == nil {
                        throw GitAIConflictResolverError.invalidConfiguration
                    } else {
                        desiredAIKey = existingAIKey
                    }
                    aiConfiguration = candidate
                } else {
                    aiConfiguration = nil
                    desiredAIKey = nil
                }
                let configuration = GitSyncConfiguration(
                    remoteURL: remoteURL,
                    authorName: authorNameField.stringValue.trimmingCharacters(in: .whitespacesAndNewlines),
                    authorEmail: authorEmailField.stringValue.trimmingCharacters(in: .whitespacesAndNewlines),
                    automaticSyncEnabled: existingConfiguration?.automaticSyncEnabled ?? false,
                    ai: aiConfiguration
                )
                let token = tokenField.stringValue.trimmingCharacters(in: .whitespacesAndNewlines)
                let canReuseToken: Bool
                if let oldURL = existingConfiguration?.remoteURL {
                    canReuseToken =
                        try GitCredentialStore.normalizedRemoteURL(oldURL)
                        == GitCredentialStore.normalizedRemoteURL(remoteURL)
                } else {
                    canReuseToken = false
                }
                let finalToken: String
                if token.isEmpty, canReuseToken, let existingCredential {
                    finalToken = existingCredential.personalAccessToken
                } else {
                    finalToken = token
                }
                let credential = GitHTTPSCredential(
                    username: usernameField.stringValue.trimmingCharacters(in: .whitespacesAndNewlines),
                    personalAccessToken: finalToken
                )
                try self.persistGitSyncSettings(
                    configuration,
                    credential: credential,
                    aiKey: desiredAIKey,
                    replacing: existingConfiguration,
                    for: root.url
                )
                self.toast(message: I18n.str("Git sync configured~"), style: .success)
                completion?()
            } catch {
                AppDelegate.trackError(error, context: "ViewController.configureGitSync")
                MiaoYanAlert.show(
                    message: I18n.str("Git sync configuration is invalid."),
                    informativeText: gitConfigurationErrorDescription(error),
                    style: .warning,
                    for: presentingWindow ?? self.view.window
                )
            }
        }
    }

    private func gitConfigurationErrorDescription(_ error: Error) -> String {
        if error is GitAIConflictResolverError {
            return I18n.str("Check the AI HTTPS endpoint, model name, prompt, and API key.")
        }
        if let credentialError = error as? GitCredentialStoreError {
            switch credentialError {
            case .invalidRemoteURL, .unsupportedRemoteScheme, .missingRemoteHost, .missingRepositoryPath:
                return I18n.str("Use an HTTPS repository URL without embedded credentials, query, or fragment.")
            case .emptyUsername, .emptyPersonalAccessToken:
                return I18n.str("Username and personal access token are required.")
            case .serializationFailed, .invalidStoredCredential, .keychain:
                return I18n.str("The Git credential could not be stored in macOS Keychain.")
            }
        }
        return I18n.str("Review the Git sync settings and try again.")
    }

    private func persistGitSyncSettings(
        _ configuration: GitSyncConfiguration,
        credential: GitHTTPSCredential,
        aiKey: String?,
        replacing previousConfiguration: GitSyncConfiguration?,
        for rootURL: URL
    ) throws {
        let credentialStore = AppEnvironment.current.gitCredentialStore
        let aiKeyStore = AppEnvironment.current.gitAIKeyStore
        let previousTargetCredential = try credentialStore.credential(for: configuration.remoteURL)
        let previousTargetAIKey: String?
        if let ai = configuration.ai {
            guard let aiKey, !aiKey.isEmpty else { throw GitAIConflictResolverError.invalidConfiguration }
            previousTargetAIKey = try aiKeyStore.apiKey(for: ai.baseURL)
        } else {
            previousTargetAIKey = nil
        }

        do {
            try credentialStore.save(credential, for: configuration.remoteURL)
            if let ai = configuration.ai, let aiKey {
                try aiKeyStore.save(aiKey, for: ai.baseURL)
            }
            try gitSyncConfigurationStore.save(configuration, for: rootURL)
        } catch {
            let originalError = error
            do {
                if let previousTargetCredential {
                    try credentialStore.save(previousTargetCredential, for: configuration.remoteURL)
                } else {
                    try credentialStore.deleteCredential(for: configuration.remoteURL)
                }
                if let ai = configuration.ai {
                    if let previousTargetAIKey {
                        try aiKeyStore.save(previousTargetAIKey, for: ai.baseURL)
                    } else {
                        try aiKeyStore.deleteAPIKey(for: ai.baseURL)
                    }
                }
            } catch let rollbackError {
                AppDelegate.trackError(rollbackError, context: "ViewController.persistGitSyncSettings.rollback")
            }
            throw originalError
        }

        if let previousRemote = previousConfiguration?.remoteURL,
            (try? GitCredentialStore.normalizedRemoteURL(previousRemote))
                != (try? GitCredentialStore.normalizedRemoteURL(configuration.remoteURL))
        {
            do {
                try credentialStore.deleteCredential(for: previousRemote)
            } catch {
                AppDelegate.trackError(error, context: "ViewController.persistGitSyncSettings.oldGitCredentialCleanup")
            }
        }
        if let previousAI = previousConfiguration?.ai {
            let shouldDeletePreviousKey: Bool
            if let newAI = configuration.ai {
                shouldDeletePreviousKey = (try? aiKeyStore.referencesSameKey(previousAI.baseURL, newAI.baseURL)) == false
            } else {
                shouldDeletePreviousKey = true
            }
            if shouldDeletePreviousKey {
                do {
                    try aiKeyStore.deleteAPIKey(for: previousAI.baseURL)
                } catch {
                    AppDelegate.trackError(error, context: "ViewController.persistGitSyncSettings.oldAIKeyCleanup")
                }
            }
        }
        (NSApplication.shared.delegate as? AppDelegate)?.refreshAutomaticGitSyncSchedule()
    }

    @IBAction func syncGitRepository(_ sender: Any) {
        // Main-menu actions are connected to the storyboard's proxy
        // ViewController object, which has no outlets. Resolve the live window
        // controller before touching view state or starting a sync.
        Self.routeGitSyncAction(to: ViewController.shared(), sender: sender)
    }

    static func routeGitSyncAction(to viewController: ViewController?, sender: Any) {
        guard let viewController, viewController.isViewLoaded else { return }
        viewController.performGitRepositorySync(sender)
    }

    private func performGitRepositorySync(_ sender: Any) {
        guard let root = selectedGitSyncRoot(),
            let configuration = gitSyncConfigurationStore.configuration(for: root.url)
        else {
            configureGitSync(sender)
            return
        }

        let credential: GitHTTPSCredential
        do {
            guard let stored = try AppEnvironment.current.gitCredentialStore.credential(for: configuration.remoteURL) else {
                configureGitSync(sender)
                return
            }
            credential = stored
        } catch {
            AppDelegate.trackError(error, context: "ViewController.syncGitRepository.credentials")
            MiaoYanAlert.show(
                message: I18n.str("Git credentials are unavailable."),
                informativeText: I18n.str("Review the Git sync settings and try again."),
                style: .warning,
                for: view.window
            )
            return
        }

        runGitSync(root: root, configuration: configuration, credential: credential)
    }

    func performAutomaticGitSync(for scheduledRootURL: URL) async -> GitSyncResult? {
        guard !gitSyncCoordinator.state.isRunning,
            let root = selectedGitSyncRoot(),
            root.url.standardizedFileURL.resolvingSymlinksInPath()
                == scheduledRootURL.standardizedFileURL.resolvingSymlinksInPath(),
            let configuration = gitSyncConfigurationStore.configuration(for: root.url),
            configuration.automaticSyncEnabled
        else { return nil }

        guard !(await GitSyncLibraryLocationPolicy.isCloudBacked(root.url)) else {
            AppDelegate.trackError(
                GitSyncAutomaticRunError.cloudBackedLibrary,
                context: "ViewController.performAutomaticGitSync.location"
            )
            return nil
        }

        let credential: GitHTTPSCredential
        do {
            guard let stored = try AppEnvironment.current.gitCredentialStore.credential(for: configuration.remoteURL) else {
                throw GitSyncAutomaticRunError.missingCredentials
            }
            credential = stored
        } catch {
            AppDelegate.trackError(error, context: "ViewController.performAutomaticGitSync.credentials")
            return nil
        }

        let result = await gitSyncCoordinator.sync(
            root: root,
            configuration: configuration,
            credential: credential,
            viewController: self
        )
        if case .blocked(let reason) = result {
            AppDelegate.trackError(
                GitSyncAutomaticRunError.blocked(gitSyncBlockDescription(reason)),
                context: "ViewController.performAutomaticGitSync.blocked"
            )
        }
        return result
    }

    private func runGitSync(
        root: Project,
        configuration: GitSyncConfiguration,
        credential: GitHTTPSCredential,
        unrelatedHistoryResolution: GitSyncUnrelatedHistoryResolution = .keepLocal
    ) {
        guard !gitSyncCoordinator.state.isRunning else {
            toast(message: I18n.str("Wait for the current Git sync to finish."), style: .failure)
            return
        }
        toastPersistent(message: I18n.str("Syncing Git repository…"))
        gitSyncCoordinator.stateDidChange = { [weak self] state in
            guard state.isRunning else { return }
            self?.toastUpdate(message: self?.gitSyncStatusMessage(state) ?? "")
        }
        Task { @MainActor [weak self] in
            guard let self else { return }
            let result = await gitSyncCoordinator.sync(
                root: root,
                configuration: configuration,
                credential: credential,
                viewController: self,
                unrelatedHistoryResolution: unrelatedHistoryResolution
            )
            gitSyncCoordinator.stateDidChange = nil
            toastDismiss()
            if (NSApplication.shared.delegate as? AppDelegate)?.hasStartedTermination == true {
                return
            }
            presentGitSyncResult(result, root: root, configuration: configuration, credential: credential)
        }
    }

    func selectedGitSyncRoot() -> Project? {
        guard !UserDefaultsManagement.isSingleMode,
            let root = storage.getDefault(),
            root.isDefault
        else { return nil }
        return root
    }

    private func labeledGitField(_ title: String, field: NSTextField) -> NSView {
        let label = NSTextField(labelWithString: title)
        label.font = NSFont.systemFont(ofSize: NSFont.smallSystemFontSize, weight: .medium)
        let stack = NSStackView(views: [label, field])
        stack.orientation = .vertical
        stack.alignment = .leading
        stack.spacing = 3
        field.widthAnchor.constraint(equalTo: stack.widthAnchor).isActive = true
        return stack
    }

    private func labeledGitView(_ title: String, view: NSView) -> NSView {
        let label = NSTextField(labelWithString: title)
        label.font = NSFont.systemFont(ofSize: NSFont.smallSystemFontSize, weight: .medium)
        let stack = NSStackView(views: [label, view])
        stack.orientation = .vertical
        stack.alignment = .leading
        stack.spacing = 3
        view.widthAnchor.constraint(equalTo: stack.widthAnchor).isActive = true
        return stack
    }

    private func gitSyncStatusMessage(_ state: GitSyncState) -> String {
        switch state {
        case .flushingEdits: return I18n.str("Saving notes before Git sync…")
        case .inspectingLocalChanges: return I18n.str("Inspecting Git changes…")
        case .fetching: return I18n.str("Fetching Git repository…")
        case .validatingIncomingChanges: return I18n.str("Validating incoming files…")
        case .resolvingConflicts: return I18n.str("Resolving Git conflicts…")
        case .committing: return I18n.str("Committing note changes…")
        case .applying: return I18n.str("Applying Git changes…")
        case .reloading: return I18n.str("Reloading notes…")
        case .pushing: return I18n.str("Pushing Git changes…")
        case .idle: return ""
        }
    }

    private func presentGitSyncResult(
        _ result: GitSyncResult,
        root: Project,
        configuration: GitSyncConfiguration,
        credential: GitHTTPSCredential
    ) {
        switch result {
        case .upToDate:
            toast(message: I18n.str("Git repository is up to date~"), style: .success)
        case .updated:
            toast(message: I18n.str("Notes updated from Git~"), style: .success)
        case .published:
            toast(message: I18n.str("Notes synced with Git~"), style: .success)
        case .blocked(.conflicts(let context)):
            presentGitConflictChoices(context, root: root, configuration: configuration, credential: credential)
        case .blocked(.divergedHistory):
            presentUnrelatedHistoryChoice(root: root, configuration: configuration, credential: credential)
        case .blocked(let reason):
            MiaoYanAlert.show(
                message: I18n.str("Git sync was blocked for safety."),
                informativeText: gitSyncBlockDescription(reason),
                style: .warning,
                for: view.window
            )
        case .failed(let failure):
            MiaoYanAlert.show(
                message: I18n.str("Git sync failed."),
                informativeText: gitSyncFailureDescription(failure),
                style: .warning,
                for: view.window
            )
        }
    }

    private func gitSyncFailureDescription(_ failure: GitSyncFailure) -> String {
        switch failure.stage {
        case .flush:
            return I18n.str("Could not save notes before Git sync. No repository changes were made.")
        case .inspectLocalChanges, .preflight, .commit:
            return I18n.str("Could not inspect or commit local Git changes. Review the library and try again.")
        case .fetch, .push:
            return I18n.str("Could not contact origin/main. Check network access and Git credentials.")
        case .apply, .reload:
            return I18n.str("Could not safely apply incoming Git changes. The recovery revision was preserved.")
        case .recovery:
            return I18n.str("Git sync could not complete safety recovery. Review diagnostics before retrying.")
        }
    }

    private enum GitConflictDialogChoice {
        case local
        case remote
        case ai
    }

    private func presentUnrelatedHistoryChoice(
        root: Project,
        configuration: GitSyncConfiguration,
        credential: GitHTTPSCredential
    ) {
        let alert = MiaoYanAlert.make(
            message: I18n.str("Local and remote histories are unrelated."),
            informativeText: I18n.str("Replace Local downloads origin/main and replaces the local library. Keep Local leaves every library file unchanged and does not push anything."),
            style: .critical,
            buttons: [I18n.str("Keep Local"), I18n.str("Replace Local")]
        )
        // The non-destructive choice owns Return. Destructive replacement must
        // always require an explicit click and must never be activated by Esc.
        alert.buttons.last?.keyEquivalent = ""
        alert.buttons.last?.hasDestructiveAction = true
        MiaoYanAlert.present(alert, for: view.window) { [weak self] response in
            guard response == .alertSecondButtonReturn, let self else { return }
            runGitSync(
                root: root,
                configuration: configuration,
                credential: credential,
                unrelatedHistoryResolution: .replaceLocalWithRemote
            )
        }
    }

    private func presentGitConflictChoices(
        _ context: GitSyncConflictContext,
        root: Project,
        configuration: GitSyncConfiguration,
        credential: GitHTTPSCredential
    ) {
        let editorWasEditable = editArea.isEditable
        editArea.isEditable = false
        Task { @MainActor [weak self] in
            guard let self else { return }
            defer { editArea.isEditable = editorWasEditable }
            var resolutions = [GitSyncConflictResolution]()
            for file in context.files {
                guard let choice = await chooseGitConflictVersion(file, configuration: configuration) else { return }
                switch choice {
                case .local:
                    resolutions.append(.init(path: file.path, choice: .local))
                case .remote:
                    resolutions.append(.init(path: file.path, choice: .remote))
                case .ai:
                    guard let ai = configuration.ai,
                        let apiKey = try? AppEnvironment.current.gitAIKeyStore.apiKey(for: ai.baseURL),
                        !apiKey.isEmpty
                    else { return }
                    do {
                        toastPersistent(message: I18n.str("Resolving conflict with AI…"))
                        let merged = try await gitAIConflictResolver.resolve(file, configuration: ai, apiKey: apiKey)
                        toastDismiss()
                        guard let accepted = await confirmAIConflictResult(merged, path: file.path) else { return }
                        resolutions.append(.init(path: file.path, choice: .mergedText(accepted)))
                    } catch {
                        toastDismiss()
                        AppDelegate.trackError(error, context: "ViewController.resolveGitConflictWithAI")
                        MiaoYanAlert.show(
                            message: I18n.str("AI conflict resolution failed."),
                            informativeText: I18n.str("Check the AI endpoint credentials and response, then try again."),
                            style: .warning,
                            for: view.window
                        )
                        return
                    }
                }
            }

            toastPersistent(message: I18n.str("Resolving Git conflicts…"))
            gitSyncCoordinator.stateDidChange = { [weak self] state in
                guard state.isRunning else { return }
                self?.toastUpdate(message: self?.gitSyncStatusMessage(state) ?? "")
            }
            let result = await gitSyncCoordinator.resolveConflicts(
                root: root,
                configuration: configuration,
                credential: credential,
                context: context,
                resolutions: resolutions,
                viewController: self
            )
            gitSyncCoordinator.stateDidChange = nil
            toastDismiss()
            presentGitSyncResult(result, root: root, configuration: configuration, credential: credential)
        }
    }

    private func chooseGitConflictVersion(
        _ file: GitSyncConflictFile,
        configuration: GitSyncConfiguration
    ) async -> GitConflictDialogChoice? {
        let formatter = DateFormatter()
        formatter.dateStyle = .medium
        formatter.timeStyle = .medium
        let localDate = file.localModifiedAt.map(formatter.string) ?? I18n.str("unknown")
        let remoteDate = file.remoteModifiedAt.map(formatter.string) ?? I18n.str("unknown")
        let localState = file.localExists ? localDate : I18n.str("file deleted")
        let remoteState = file.remoteExists ? remoteDate : I18n.str("file deleted")
        var buttons = [I18n.str("Use Local"), I18n.str("Use Remote")]
        let aiAvailable = configuration.ai != nil && file.canResolveWithAI
        if aiAvailable { buttons.append(I18n.str("Resolve with AI")) }
        buttons.append(I18n.str("Cancel"))
        let alert = MiaoYanAlert.make(
            message: I18n.str("Choose a version for the conflicted file"),
            informativeText: "\(file.path)\n\n\(I18n.str("Local modified:")) \(localState)\n\(I18n.str("Remote modified:")) \(remoteState)",
            style: .warning,
            buttons: buttons
        )
        return await withCheckedContinuation { continuation in
            MiaoYanAlert.present(alert, for: view.window) { response in
                switch response.rawValue - NSApplication.ModalResponse.alertFirstButtonReturn.rawValue {
                case 0: continuation.resume(returning: .local)
                case 1: continuation.resume(returning: .remote)
                case 2 where aiAvailable: continuation.resume(returning: .ai)
                default: continuation.resume(returning: nil)
                }
            }
        }
    }

    private func confirmAIConflictResult(_ result: String, path: String) async -> String? {
        let textView = NSTextView(frame: NSRect(x: 0, y: 0, width: 560, height: 320))
        textView.isRichText = false
        textView.font = NSFont.monospacedSystemFont(ofSize: NSFont.smallSystemFontSize, weight: .regular)
        textView.string = result
        let scrollView = NSScrollView(frame: textView.frame)
        scrollView.hasVerticalScroller = true
        scrollView.hasHorizontalScroller = false
        scrollView.borderType = .bezelBorder
        scrollView.documentView = textView
        let alert = MiaoYanAlert.make(
            message: I18n.str("Review AI conflict resolution"),
            informativeText: path,
            buttons: [I18n.str("Use AI Result"), I18n.str("Cancel")]
        )
        alert.accessoryView = scrollView
        return await withCheckedContinuation { continuation in
            MiaoYanAlert.present(alert, for: view.window) { response in
                continuation.resume(returning: response == .alertFirstButtonReturn ? textView.string : nil)
            }
        }
    }

    private func gitSyncBlockDescription(_ reason: GitSyncBlockReason) -> String {
        switch reason {
        case .pendingSaveFailed:
            return I18n.str("Pending note changes could not be saved. Git sync did not start.")
        case .workingTreeNotClean(let paths):
            return I18n.str("Git sync found local worktree changes that must be resolved first:")
                + "\n" + paths.joined(separator: "\n")
        case .disallowedLocalChanges(let violations):
            return I18n.str("Git sync found unsupported local paths:")
                + "\n" + violations.map(\.path).joined(separator: "\n")
        case .disallowedIncomingChanges(let violations):
            return I18n.str("Git sync found unsupported incoming paths:")
                + "\n" + violations.map(\.path).joined(separator: "\n")
        case .conflicts:
            return I18n.str("Git sync found conflicts.")
        case .divergedHistory:
            return I18n.str("Local and remote Git histories cannot be merged automatically.")
        case .missingAuthorIdentity:
            return I18n.str("Commit author name and email are required.")
        case .repositoryUnavailable:
            return I18n.str("The selected project cannot be used as a Git repository.")
        case .cloudBackedRepository:
            return I18n.str("Choose a local folder so only one sync engine owns the working copy.")
        }
    }

    @IBAction func duplicate(_ sender: Any) {
        if let notes = notesTableView.getSelectedNotes() {
            for note in notes {
                guard let name = note.getDupeName() else {
                    continue
                }

                // Make sure note.content actually reflects what is on disk
                // before we copy it. Two failure modes the next two lines
                // defend against:
                //   1. The note was never opened in this session, so
                //      isContentLoaded is false and note.content is an
                //      empty NSMutableAttributedString.
                //   2. The active note has unsaved edits sitting in the
                //      1.5s debounce queue; without flushing, the duplicate
                //      contains the previous on-disk version.
                if note === EditTextView.note {
                    editArea.saveTextStorageContent(to: note)
                }
                note.flushPendingSave()
                if !note.isContentLoaded {
                    note.ensureContentLoaded()
                }

                let noteDupe = Note(name: name, project: note.project, type: note.type)
                noteDupe.content = NSMutableAttributedString(attributedString: note.content)

                // Clone images
                if note.type == .markdown, note.container == .none {
                    let images = note.getAllImages()
                    for image in images {
                        move(note: noteDupe, from: image.url, imagePath: image.path, to: note.project, copy: true)
                    }
                }

                noteDupe.save()

                storage.add(noteDupe)
                notesTableView.insertNew(note: noteDupe)
            }
        }
    }

    @IBAction func noteCopy(_ sender: Any) {
        guard let responder = view.window?.firstResponder else { return }

        if self.responder(responder, belongsTo: editArea) {
            editArea.copy(sender)
            return
        }

        if let preview = editArea.markdownView,
            self.responder(responder, belongsTo: preview)
        {
            preview.copySelectionToPasteboard()
            return
        }

        if self.responder(responder, belongsTo: notesTableView) {
            saveTextAtClipboard()
        }
    }

    @IBAction func copyURL(_ sender: Any) {
        if let note = notesTableView.getSelectedNote(), let title = note.title.addingPercentEncoding(withAllowedCharacters: .urlHostAllowed) {
            let name = "miaoyan://goto/\(title)"
            let pasteboard = NSPasteboard.general
            pasteboard.declareTypes([NSPasteboard.PasteboardType.string], owner: nil)
            pasteboard.setString(name, forType: NSPasteboard.PasteboardType.string)
            toast(message: I18n.str("URL is successfully copied, Use it anywhere~"), style: .success)
        }
    }

    @IBAction func openInTerminal(_ sender: Any) {
        // When invoked from the main menu, `self` may be the App Scene proxy with nil outlets.
        // Always delegate to the real ViewController instance, matching the pattern used by other IBActions.
        guard let vc = ViewController.shared() else { return }
        let path: String
        if let si = vc.getSidebarItem(), !si.isTrash(), let p = si.project {
            path = p.url.path
        } else if let rootURL = UserDefaultsManagement.storageUrl {
            path = rootURL.path
        } else {
            return
        }
        let bundleID = Self.preferredTerminalBundleID()
        let process = Process()
        process.executableURL = URL(fileURLWithPath: "/usr/bin/open")
        process.arguments = ["-b", bundleID, path]
        try? process.run()
    }

    private static func preferredTerminalBundleID() -> String {
        let candidates = [
            "fun.tw93.kaku",  // Kaku
            "dev.warp.Warp-Stable",  // Warp
            "com.googlecode.iterm2",  // iTerm2
            "net.kovidgoyal.kitty",  // Kitty
            "com.apple.Terminal",  // Terminal (always present)
        ]
        for id in candidates where NSWorkspace.shared.urlForApplication(withBundleIdentifier: id) != nil {
            return id
        }
        return "com.apple.Terminal"
    }

    @IBAction func copyPath(_ sender: Any) {
        guard let notes = notesTableView.getSelectedNotes() else { return }
        let paths = notes.map { $0.url.path }.joined(separator: "\n")
        let pasteboard = NSPasteboard.general
        pasteboard.clearContents()
        pasteboard.setString(paths, forType: .string)
        toast(message: I18n.str("Path is successfully copied~"), style: .success)
    }

    private func responder(_ responder: NSResponder, belongsTo view: NSView?) -> Bool {
        guard let view else { return false }

        if let responderView = responder as? NSView {
            return responderView === view || responderView.isDescendant(of: view)
        }

        return responder === view
    }

    @IBAction func copyTitle(_ sender: Any) {
        if let note = notesTableView.getSelectedNote() {
            let pasteboard = NSPasteboard.general
            pasteboard.declareTypes([NSPasteboard.PasteboardType.string], owner: nil)
            pasteboard.setString(note.title, forType: NSPasteboard.PasteboardType.string)
        }
    }

    @IBAction func exportImage(_ sender: Any) {
        exportFile(type: "Image")
    }

    @IBAction func exportHtml(_ sender: Any) {
        exportFile(type: "Html")
    }

    @IBAction func exportPdf(_ sender: Any) {
        exportFile(type: "PDF")
    }

    @IBAction func exportMiaoYanPPT(_ sender: Any) {
        guard isMiaoYanPPT() else { return }

        // Only enable PPT mode if not already enabled
        let needsDisableAfterExport = !sessionMagicPPTMode
        if needsDisableAfterExport {
            enableMiaoYanPPT()
        }
        shouldDisablePPTAfterExport = needsDisableAfterExport

        // Mark export state
        sessionIsExportingPPT = true

        // Short delay then export (1.2s is sufficient for PPT layout)
        DispatchQueue.main.asyncAfter(deadline: .now() + 1.2) {
            self.editArea.markdownView?.exportPPTOptimized()
        }
    }

    @IBAction func textFinder(_ sender: NSMenuItem) {
        guard let vc = ViewController.shared() else { return }

        if !vc.shouldUseEditorTextContent {
            if let webView = vc.editArea.markdownView {
                if vc.editArea.isSearchBarVisible {
                    vc.editArea.hideSearchBar()
                }
                if sender.tag == NSFindPanelAction.next.rawValue {
                    webView.findNext()
                    return
                }
                if sender.tag == NSFindPanelAction.previous.rawValue {
                    webView.findPrevious()
                    return
                }

                if let textFinderAction = NSTextFinder.Action(rawValue: sender.tag) {
                    if textFinderAction == .showReplaceInterface {
                        webView.showSearchBar(mode: .replace)
                        return
                    }
                }

                webView.showSearchBar(mode: .find)
                return
            }
            return
        }

        if let action = NSFindPanelAction(rawValue: UInt(sender.tag)) {
            switch action {
            case .next:
                if !vc.editArea.isSearchBarVisible {
                    vc.editArea.showSearchBar(prefilledText: vc.editArea.currentSearchQuery)
                }
                vc.editArea.findNext()
                return
            case .previous:
                if !vc.editArea.isSearchBarVisible {
                    vc.editArea.showSearchBar(prefilledText: vc.editArea.currentSearchQuery)
                }
                vc.editArea.findPrevious()
                return
            case .setFindString:
                vc.editArea.useSelectionForFind()
                return
            default:
                break
            }
        }

        if let textFinderAction = NSTextFinder.Action(rawValue: sender.tag) {
            switch textFinderAction {
            case .showFindInterface:
                vc.editArea.showSearchBar(prefilledText: nil, mode: .find)
                return
            case .showReplaceInterface:
                vc.editArea.showSearchBar(prefilledText: nil, mode: .replace)
                return
            case .hideFindInterface:
                vc.editArea.hideSearchBar()
                return
            default:
                break
            }
        }

        DispatchQueue.main.async {
            vc.editArea.performTextFinderAction(sender)
        }
    }

    // MARK: - Note Operations
    @objc func moveNote(_ sender: NSMenuItem) {
        let project = sender.representedObject as! Project

        guard let notes = notesTableView.getSelectedNotes() else {
            return
        }

        move(notes: notes, project: project)
    }

    public func move(notes: [Note], project: Project) {
        guard GitSyncLibraryMutationGate.allowsMutation(at: project.url),
            notes.allSatisfy({ GitSyncLibraryMutationGate.allowsMutation(at: $0.url) })
        else { return }
        let selectedRow = notesTableView.selectedRowIndexes.min()
        for note in notes {
            if note.project == project {
                continue
            }

            let destination = project.url.appendingPathComponent(note.name)

            if note.type == .markdown, note.container == .none {
                let imagesMeta = note.getAllImages()
                for imageMeta in imagesMeta {
                    move(note: note, from: imageMeta.url, imagePath: imageMeta.path, to: project)
                }

                if !imagesMeta.isEmpty {
                    note.save()
                }
            }

            _ = note.move(to: destination, project: project)

            if !isFit(note: note, shouldLoadMain: true) {
                notesTableView.removeByNotes(notes: [note])

                if let i = selectedRow, i > -1 {
                    if notesTableView.noteList.count > i {
                        notesTableView.selectRow(i)
                    } else {
                        notesTableView.selectRow(notesTableView.noteList.count - 1)
                    }
                }
            }

            note.invalidateCache()
        }

        editArea.clear()
    }

    private func move(note: Note, from imageURL: URL, imagePath: String, to project: Project, copy: Bool = false) {
        let dstPrefix = NotesTextProcessor.getAttachPrefix(url: imageURL)
        let dest = project.url.appendingPathComponent(dstPrefix)
        guard GitSyncLibraryMutationGate.allowsMutation(at: imageURL),
            GitSyncLibraryMutationGate.allowsMutation(at: dest)
        else { return }

        if !FileManager.default.fileExists(atPath: dest.path) {
            try? FileManager.default.createDirectory(at: dest, withIntermediateDirectories: false, attributes: nil)
        }

        do {
            if copy {
                try FileManager.default.copyItem(at: imageURL, to: dest)
            } else {
                try FileManager.default.moveItem(at: imageURL, to: dest)
            }
        } catch {
            if let fileName = ImagesProcessor.getFileName(from: imageURL, to: dest, ext: imageURL.pathExtension) {
                let dest = dest.appendingPathComponent(fileName)

                if copy {
                    try? FileManager.default.copyItem(at: imageURL, to: dest)
                } else {
                    try? FileManager.default.moveItem(at: imageURL, to: dest)
                }

                let prefix = "]("
                let postfix = ")"

                let find = prefix + imagePath + postfix
                let replace = prefix + dstPrefix + fileName + postfix

                guard find != replace else {
                    return
                }

                while note.content.mutableString.contains(find) {
                    let range = note.content.mutableString.range(of: find)
                    note.content.replaceCharacters(in: range, with: replace)
                }
            }
        }
    }

    func createNote(name: String = "", content: String = "", type: NoteType? = nil, project: Project? = nil, load: Bool = false) {
        if exitReadOnlyModeIfNeededBeforeCreatingNote(name: name, content: content, type: type, project: project, load: load) {
            return
        }

        performCreateNote(name: name, content: content, type: type, project: project, load: load)
    }

    private func exitReadOnlyModeIfNeededBeforeCreatingNote(
        name: String,
        content: String,
        type: NoteType?,
        project: Project?,
        load: Bool
    ) -> Bool {
        let retryDelay: TimeInterval

        if sessionMagicPPTMode {
            disableMiaoYanPPT()
            retryDelay = 0.35
        } else if sessionPresentationMode {
            disablePresentation()
            retryDelay = 0.35
        } else if sessionPreviewMode {
            disablePreview()
            retryDelay = 0.2
        } else {
            return false
        }

        DispatchQueue.main.asyncAfter(deadline: .now() + retryDelay) { [weak self] in
            self?.performCreateNote(name: name, content: content, type: type, project: project, load: load)
        }

        return true
    }

    private func performCreateNote(name: String, content: String, type: NoteType?, project: Project?, load: Bool) {
        let vc = self
        let selectedProjects = vc.storageOutlineView.getSidebarProjects()
        var sidebarProject = project ?? selectedProjects?.first
        let text = content

        if sidebarProject == nil {
            let projects = storage.getProjects()
            sidebarProject = projects.first
        }

        guard let project = sidebarProject else {
            return
        }

        let note = Note(name: name, project: project, type: type)
        note.content = NSMutableAttributedString(string: text)
        note.save()

        if let selectedProjects = selectedProjects, !selectedProjects.contains(project) {
            return
        }

        editArea.markdownView?.removeFromSuperview()
        previewScrollView?.documentView = nil
        editArea.markdownView = nil

        guard let editor = editArea else {
            return
        }
        editor.subviews.removeAll(where: { $0.isKind(of: MPreviewView.self) })

        // Set flag to prevent edit area updates during note creation
        UserDataService.instance.isCreatingNote = true

        // Prepare for new note creation - ensure clean state
        prepareForNoteCreation()

        // Set new note content through publishStorage so the buffer owner
        // moves together with EditTextView.note. A keystroke can land before
        // the async fill() republishes ownership; with a stale owner the
        // textDidChange guard would refuse the save and the fill overwrite
        // would silently drop the typed input.
        editArea.publishStorage(NSAttributedString(string: text), owner: note)
        EditTextView.note = note

        if sessionSplitMode {
            let previewOptions = FillOptions(
                highlight: false,
                saveTyping: true,
                force: true,
                needScrollToCursor: false,
                previewOnly: true,
                animatePreview: false
            )
            editArea.fill(note: note, options: previewOptions)
            editArea.markdownView?.setSplitChrome(true)
        }

        // Move focus into the title field in the same frame, so the cursor is
        // already blinking by the time the table reload finishes.
        titleLabel.editModeOn()

        // Update table and handle completion
        updateTable {
            DispatchQueue.main.async {
                // Clear the flag before final setup
                UserDataService.instance.isCreatingNote = false
                self.completeNoteCreation(for: note)
            }
        }
    }

    // Prepare UI state for new note creation
    private func prepareForNoteCreation() {
        notesTableView.deselectNotes()
        search.stringValue.removeAll()

        // Drop any half-edited title from the previous note so it cannot bleed
        // into the new note via pendingTitleChange or controlTextDidEndEditing.
        UserDataService.instance.pendingTitleChange = nil

        // Tripwire: titiebarHeight is supposed to be 0 only inside PPT mode
        // (Controllers/ViewController+Editor.swift sets it to 0 on enter and
        // back to 40 on exit). If we land here with constant=0 outside PPT
        // / Presentation, an exit path leaked. Track it once so we can fix
        // the leak instead of relying on the defensive reset below.
        //
        // TODO(tripwire): this is a temporary diagnostic. Remove (along with
        // the err init) once two consecutive releases have shipped without
        // any "prepareForNoteCreation.titlebarHeight" report. Defensive
        // checkTitlebarTopConstraint() below stays as the actual fix.
        if titiebarHeight.constant == 0 && !sessionMagicPPTMode && !sessionPresentationMode {
            let leak = NSError(
                domain: "com.tw93.miaoyan.layout",
                code: 1,
                userInfo: [NSLocalizedDescriptionKey: "titiebarHeight stuck at 0 outside PPT/Presentation"])
            AppDelegate.trackError(leak, context: "prepareForNoteCreation.titlebarHeight")
        }

        // Keep the title bar visible (we may be coming back from preview / PPT
        // where it was hidden) and clear the previous title in the same frame
        // as the editor body, so users never see the old title on a new note.
        titleBarView.isHidden = false
        titleBarView.alphaValue = 1
        titleLabel.isHidden = false
        titleLabel.alphaValue = 1
        titleLabel.isEditable = true

        // Force the height constraint back to the layout-driven value (40
        // or 64 depending on notelist width). PPT entry sets this to 0 and
        // a non-clean exit can leave it stuck, which makes the title bar
        // collapse to invisible even with isHidden=false. Restart fixes it
        // because storyboard re-applies the initial 52, but a Cmd+N path
        // should self-heal too.
        checkTitlebarTopConstraint()

        UserDataService.instance.isUpdatingTitle = true
        titleLabel.setStringValueSafely("")
        UserDataService.instance.isUpdatingTitle = false
    }

    // Complete final setup for newly created note
    private func completeNoteCreation(for note: Note) {
        guard let index = notesTableView.getIndex(note) else {
            return
        }

        notesTableView.selectRow(index, suppressSideEffects: true)
        DispatchQueue.main.async { [weak self] in
            self?.notesTableView.scrollRowToVisible(index)
        }
    }

    private func removeForever() {
        guard let vc = ViewController.shared() else { return }
        guard let notes = vc.notesTableView.getSelectedNotes() else { return }
        guard let window = MainWindowController.shared() else { return }

        MiaoYanAlert.confirm(
            message: String(format: I18n.str("Are you sure you want to move %d note(s) to the system Trash?"), notes.count),
            informativeText: I18n.str("The note(s) will be moved to the system Trash and can be recovered."),
            confirmTitle: I18n.str("Move to Trash"),
            for: window
        ) { confirmed in
            if confirmed {
                let selectedRow = vc.notesTableView.selectedRowIndexes.min() ?? -1
                let onPartialFailure: (Int) -> Void = { failedCount in
                    DispatchQueue.main.async {
                        vc.toast(
                            message: String(format: I18n.str("Failed to move %d note(s) to Trash~"), failedCount),
                            style: .failure
                        )
                    }
                }
                vc.storage.removeNotes(
                    notes: notes,
                    partialFailure: onPartialFailure,
                    didRemove: { removedNotes in
                        vc.notesTableView.removeAndReselect(
                            notes: removedNotes,
                            originalRow: selectedRow)
                    }
                ) { _ in
                    DispatchQueue.main.async {
                        vc.storageOutlineView.reloadSidebar()
                        if vc.getSidebarItem() == nil {
                            vc.storageOutlineView.selectRowIndexes([0], byExtendingSelection: false)
                            vc.notesTableView.selectRow(0)
                        }
                        if vc.notesTableView.noteList.isEmpty {
                            vc.editArea.clear()
                        }
                    }
                }
            }
        }
    }

    func pin(_ selectedRows: IndexSet) {
        guard !selectedRows.isEmpty, let notes = filteredNoteList, var state = filteredNoteList else {
            return
        }

        var updatedNotes = [(Int, Note)]()
        for row in selectedRows {
            guard let rowView = notesTableView.rowView(atRow: row, makeIfNecessary: false) as? NoteRowView,
                let cell = rowView.view(atColumn: 0) as? NoteCellView,
                let note = cell.objectValue as? Note
            else {
                continue
            }

            updatedNotes.append((row, note))
            note.togglePin()
            cell.renderPin()
        }

        let resorted = storage.sortNotes(noteList: notes, filter: search.stringValue)
        let indexes = updatedNotes.compactMap { _, note in
            resorted.firstIndex(where: { $0 === note })
        }
        let newIndexes = IndexSet(indexes)

        notesTableView.beginUpdates()
        let nowPinned = updatedNotes.filter { _, note in
            note.isPinned
        }
        for (row, note) in nowPinned {
            guard let newRow = resorted.firstIndex(where: { $0 === note }) else {
                continue
            }
            notesTableView.moveRow(at: row, to: newRow)
            let toMove = state.remove(at: row)
            state.insert(toMove, at: newRow)
        }

        let nowUnpinned =
            updatedNotes
            .filter { _, note -> Bool in
                !note.isPinned
            }
            .compactMap { _, note -> (Int, Note)? in
                guard let curRow = state.firstIndex(where: { $0 === note }) else {
                    return nil
                }
                return (curRow, note)
            }
        for (row, note) in nowUnpinned.reversed() {
            guard let newRow = resorted.firstIndex(where: { $0 === note }) else {
                continue
            }
            notesTableView.moveRow(at: row, to: newRow)
            let toMove = state.remove(at: row)
            state.insert(toMove, at: newRow)
        }

        notesTableView.noteList = resorted
        notesTableView.reloadData(forRowIndexes: newIndexes, columnIndexes: [0])
        notesTableView.selectRowIndexes(newIndexes, byExtendingSelection: false)
        notesTableView.endUpdates()
        filteredNoteList = resorted
    }

    @objc func switchTitleToEditMode() {
        guard let vc = ViewController.shared() else {
            return
        }

        vc.titleLabel.editModeOn()
    }

    @objc func breakUndo() {
        editArea.breakUndoCoalescing()
    }

    // MARK: - File Operations
    public func copy(project: Project, url: URL) -> URL {
        let fileName = url.lastPathComponent
        guard GitSyncLibraryMutationGate.allowsMutation(at: url),
            GitSyncLibraryMutationGate.allowsMutation(at: project.url)
        else { return url }

        do {
            let destination = project.url.appendingPathComponent(fileName)
            try FileManager.default.copyItem(at: url, to: destination)
            return destination
        } catch {
            // If file already exists, create a copy with "Copy" suffix
            let baseName = url.deletingPathExtension().lastPathComponent
            let ext = url.pathExtension

            var copyName = baseName + " Copy"
            var copyNumber = 2

            while FileManager.default.fileExists(atPath: project.url.appendingPathComponent(copyName).appendingPathExtension(ext).path) {
                copyName = baseName + " Copy \(copyNumber)"
                copyNumber += 1
            }

            let baseUrl = project.url.appendingPathComponent(copyName).appendingPathExtension(ext)
            try? FileManager.default.copyItem(at: url, to: baseUrl)

            return baseUrl
        }
    }

    func restart() {
        AppDelegate.relaunchApp()
    }

    // MARK: - Menu Management
    func loadMoveMenu() {
        guard let vc = ViewController.shared(), let note = vc.notesTableView.getSelectedNote() else { return }

        let moveTitle = I18n.str("Move")
        if let prevMenu = noteMenu.item(withTitle: moveTitle) {
            noteMenu.removeItem(prevMenu)
        }

        let historyTitle = I18n.str("Version History")
        if let prevHistory = noteMenu.item(withTitle: historyTitle) {
            noteMenu.removeItem(prevHistory)
        }

        let moveMenuItem = NSMenuItem()
        moveMenuItem.title = I18n.str("Move")
        moveMenuItem.setIdentifier("noteMenu.move")

        if #available(macOS 11.0, *),
            let symbolName = MenuIconRegistry.symbol(for: moveMenuItem),
            let image = NSImage(systemSymbolName: symbolName, accessibilityDescription: moveMenuItem.title)
        {
            image.isTemplate = true
            moveMenuItem.image = image
        }

        if !note.isTrash() {
            let historyItem = NSMenuItem()
            historyItem.title = historyTitle
            historyItem.action = #selector(vc.showVersionHistory(_:))
            historyItem.keyEquivalent = "H"
            historyItem.keyEquivalentModifierMask = [.command]
            if #available(macOS 11, *) {
                historyItem.image = NSImage(systemSymbolName: "clock.arrow.circlepath", accessibilityDescription: nil)
            }
            noteMenu.addItem(historyItem)
        }

        noteMenu.addItem(moveMenuItem)
        let moveMenu = NSMenu()

        if !note.isTrash() {
            let trashMenu = NSMenuItem()
            trashMenu.title = I18n.str("Trash")
            trashMenu.action = #selector(vc.deleteNote(_:))
            trashMenu.tag = 555
            if #available(macOS 11.0, *) {
                trashMenu.image = NSImage(systemSymbolName: "trash", accessibilityDescription: "Trash")
            }
            moveMenu.addItem(trashMenu)
            moveMenu.addItem(NSMenuItem.separator())
        }

        let projects = storage.getProjects()
        for item in projects {
            if note.project == item || item.isTrash {
                continue
            }

            let menuItem = NSMenuItem()
            menuItem.title = item.label
            menuItem.representedObject = item
            menuItem.action = #selector(vc.moveNote(_:))
            if #available(macOS 11.0, *) {
                menuItem.image = NSImage(systemSymbolName: "folder", accessibilityDescription: "Folder")
            }
            moveMenu.addItem(menuItem)
        }

        let personalSelection = [
            "noteMove.rename"
        ]

        for menu in noteMenu.items {
            if let identifier = menu.identifier?.rawValue,
                personalSelection.contains(identifier)
            {
                menu.isHidden = (vc.notesTableView.selectedRowIndexes.count > 1)
            }
        }

        if #available(macOS 11.0, *) {
            moveMenu.applyMenuIcons()
            noteMenu.applyMenuIcons()
        }

        noteMenu.setSubmenu(moveMenu, for: moveMenuItem)
    }

    @IBAction func showVersionHistory(_ sender: Any) {
        guard let note = notesTableView.getSelectedNote() else { return }
        NoteVersionManager.shared.saveVersionIfNeeded(for: note, force: false) { [weak self] in
            guard let self else { return }
            let controller = VersionHistoryViewController(note: note)
            self.presentAsSheet(controller)
        }
    }

    // MARK: - Clipboard Operations
    public func saveTextAtClipboard() {
        if let note = notesTableView.getSelectedNote() {
            let pasteboard = NSPasteboard.general
            pasteboard.declareTypes([NSPasteboard.PasteboardType.string], owner: nil)
            pasteboard.setString(note.content.string, forType: NSPasteboard.PasteboardType.string)
        }
    }

    public func saveHtmlAtClipboard() {
        if let note = notesTableView.getSelectedNote() {
            let useGithubLineBreak = UserDefaultsManagement.editorLineBreak == "Github"
            if let render = renderMarkdownHTML(markdown: note.content.string, useGithubLineBreak: useGithubLineBreak) {
                let pasteboard = NSPasteboard.general
                pasteboard.declareTypes([NSPasteboard.PasteboardType.html], owner: nil)
                pasteboard.setString(render, forType: NSPasteboard.PasteboardType.html)
            }
        }
    }

    // MARK: - Utility Methods
    func activeShortcut() {
        guard let mainWindow = MainWindowController.shared() else {
            return
        }

        if NSApplication.shared.isActive,
            !NSApplication.shared.isHidden,
            !mainWindow.isMiniaturized
        {
            NSApplication.shared.hide(nil)
            return
        }

        NSApp.activate(ignoringOtherApps: true)
        mainWindow.makeKeyAndOrderFront(self)
    }

    public func replace(validateString: String, regex: String, content: String) -> String {
        do {
            let RE = try NSRegularExpression(pattern: regex, options: .caseInsensitive)
            let modified = RE.stringByReplacingMatches(in: validateString, options: .reportProgress, range: NSRange(location: 0, length: validateString.count), withTemplate: content)
            return modified
        } catch {
            return validateString
        }
    }

    // MARK: - Keyboard Event Handling
    // swiftlint:disable:next cyclomatic_complexity
    public func handleKeyDown(with event: NSEvent) -> Bool {
        guard let mw = MainWindowController.shared() else {
            return false
        }

        guard alert == nil else {
            if event.keyCode == kVK_Escape, let unwrapped = alert {
                mw.endSheet(unwrapped.window)
                alert = nil
            }

            return true
        }

        if event.modifierFlags.contains(.command),
            event.modifierFlags.contains(.shift),
            !event.modifierFlags.contains(.control),
            event.keyCode == kVK_ANSI_H
        {
            showVersionHistory(self)
            return false
        }

        if event.modifierFlags.contains(.shift), event.modifierFlags.contains(.control), event.keyCode == kVK_ANSI_H {
            exportHtml(self)
            return false
        }

        if event.keyCode == kVK_Escape {
            if sessionMagicPPTMode {
                disableMiaoYanPPT()
                return false
            } else if sessionPresentationMode {
                disablePresentation()
                return false
            }
        }

        if event.keyCode == kVK_Delete, event.modifierFlags.contains(.command), search.hasFocus() {
            search.stringValue.removeAll()
            configureNotesList()
            return false
        }

        if event.keyCode == kVK_Escape, search.hasFocus() {
            search.stringValue.removeAll()
            configureNotesList()
            return false
        }

        if event.keyCode == kVK_Escape, titleLabel.hasFocus() {
            focusEditArea()
            return false
        }

        if event.keyCode == kVK_Delete, event.modifierFlags.contains(.command), editArea.hasFocus(), shouldUseEditorTextContent {
            editArea.deleteToBeginningOfLine(nil)
            return false
        }

        if event.keyCode == kVK_Delete, event.modifierFlags.contains(.command), titleLabel.hasFocus(), shouldUseEditorTextContent {
            updateTitle(newTitle: "")
            return false
        }

        if event.keyCode == kVK_ANSI_D, event.modifierFlags.contains(.command), editArea.hasFocus() {
            return false
        }

        if event.keyCode == kVK_ANSI_Z, event.modifierFlags.contains(.command), titleLabel.hasFocus() {
            let currentNote = notesTableView.getSelectedNote()
            updateTitle(newTitle: currentNote?.getTitleWithoutLabel() ?? I18n.str("Untitled Note"))
            return false
        }

        if event.keyCode == kVK_ANSI_Z, event.modifierFlags.contains(.command), editArea.hasFocus(), formatContent != "" {
            if let note = notesTableView.getSelectedNote(), note.content.string == formatContent {
                let cursor = editArea.selectedRanges[0].rangeValue.location
                DispatchQueue.main.async {
                    self.editArea.setSelectedRange(NSRange(location: cursor, length: 0))
                }
                formatContent = ""
            }
        }

        if event.modifierFlags.contains(.command), event.modifierFlags.contains(.option), event.keyCode == kVK_ANSI_I, !sessionPresentationMode {
            toggleInfo()
            return false
        }

        if event.modifierFlags.contains(.command), event.modifierFlags.contains(.option), event.keyCode == kVK_ANSI_U {
            copyURL(self)
            return false
        }

        // ⌘= zoom-in alias (no Shift). The View-menu item carries "+" so ⌘+
        // (⌘⇧=) routes through the menu; this branch adds the bare ⌘= without
        // double-firing, since no menu item binds the "=" key.
        if event.modifierFlags.contains(.command),
            !event.modifierFlags.contains(.shift),
            !event.modifierFlags.contains(.option),
            !event.modifierFlags.contains(.control),
            event.keyCode == kVK_ANSI_Equal
        {
            zoomInFontSize(self)
            return false
        }

        if event.keyCode == kVK_ANSI_W, event.modifierFlags.contains(.command), event.modifierFlags.contains(.shift) {
            if UserDefaultsManagement.isSingleMode {
                UserDefaultsManagement.clearSingleMode()
                UserDefaultsManagement.isFirstLaunch = true
                DispatchQueue.main.asyncAfter(deadline: .now() + 0.3) {
                    self.restart()
                }
            }
            return false
        }

        // Up/Down arrow navigation in notes list
        if event.keyCode == kVK_UpArrow || event.keyCode == kVK_DownArrow {
            if let fr = NSApp.mainWindow?.firstResponder, fr.isKind(of: NotesTableView.self), !event.modifierFlags.contains(.command) {
                if event.keyCode == kVK_UpArrow {
                    notesTableView.selectPrev()
                } else {
                    notesTableView.selectNext()
                }
                return false
            }
        }

        // Return / Cmd + Return navigation
        if event.keyCode == kVK_Return {
            if let fr = NSApp.mainWindow?.firstResponder, alert == nil {
                // Ensure selection handling works correctly in PPT mode
                if sessionMagicPPTMode {
                    DispatchQueue.main.async {
                        self.editArea.markdownView!.evaluateJavaScript("Reveal.toggleOverview();", completionHandler: nil)
                    }
                    return false
                }

                if event.modifierFlags.contains(.command) {
                    if fr.isKind(of: NotesTableView.self) {
                        NSApp.mainWindow?.makeFirstResponder(storageOutlineView)
                        return false
                    }
                } else {
                    if fr.isKind(of: SidebarProjectView.self) {
                        notesTableView.selectNext()
                        NSApp.mainWindow?.makeFirstResponder(notesTableView)
                        return false
                    }

                    if fr.isKind(of: NotesTableView.self), shouldUseEditorTextContent {
                        NSApp.mainWindow?.makeFirstResponder(editArea)
                        return false
                    }

                    // Handle the different input behavior used in Japanese locales
                    if titleLabel.hasFocus() {
                        if UserDefaultsManagement.defaultLanguage != 0x02 {
                            focusEditArea()
                        }
                        return false
                    }
                }
            }

            return true
        }

        // Tab / Control + Tab
        if event.keyCode == kVK_Tab {
            if event.modifierFlags.contains(.control) {
                notesTableView.window?.makeFirstResponder(notesTableView)
                return true
            }

            if let fr = NSApp.mainWindow?.firstResponder, fr.isKind(of: NotesTableView.self) {
                NSApp.mainWindow?.makeFirstResponder(notesTableView)
                return false
            }

            // Handle TAB in title field - save and switch to editor
            if let fr = NSApp.mainWindow?.firstResponder, fr.isKind(of: NSTextView.self),
                titleLabel.hasFocus()
            {
                saveTitleSafely()
                focusEditArea()
                return true
            }
        }

        // Focus search bar on ESC
        if event.characters == ".",
            event.modifierFlags.contains(.command),

            NSApplication.shared.mainWindow == NSApplication.shared.keyWindow
        {
            UserDataService.instance.resetLastSidebar()

            if let view = NSApplication.shared.mainWindow?.firstResponder as? NSTextView, let textField = view.superview?.superview, textField.isKind(of: NameTextField.self) {
                NSApp.mainWindow?.makeFirstResponder(notesTableView)
                return false
            }

            if editArea.isSearchBarVisible || (editArea.markdownView?.isSearchBarVisible ?? false) {
                cancelTextSearch()
                return false
            }

            // Renaming is in progress
            if titleLabel.isEditable {
                titleLabel.window?.makeFirstResponder(notesTableView)
                return false
            }

            UserDefaultsManagement.lastProject = 0
            UserDefaultsManagement.lastSelectedURL = nil

            notesTableView.scroll(.zero)

            let hasSelectedNotes = notesTableView.selectedRow > -1
            let hasSelectedBarItem = storageOutlineView.selectedRow > -1

            if hasSelectedBarItem, hasSelectedNotes {
                UserDefaultsManagement.lastProject = 0
                UserDataService.instance.isNotesTableEscape = true
                notesTableView.deselectAll(nil)
                NSApp.mainWindow?.makeFirstResponder(search)
                return false
            }

            storageOutlineView.deselectAll(nil)
            cleanSearchAndEditArea()

            return true
        }

        if event.keyCode == kVK_ANSI_F, event.modifierFlags.contains(.command), !event.modifierFlags.contains(.shift), !event.modifierFlags.contains(.control) {
            if notesTableView.getSelectedNote() != nil {
                // If in preview mode, use WebView search instead of exiting preview
                if !shouldUseEditorTextContent {
                    if let webView = editArea.markdownView {
                        webView.showSearchBar()
                        return true
                    }
                }
                // Otherwise use editor search
                editArea.showSearchBar(prefilledText: nil)
                return true
            }
        }

        if event.keyCode == kVK_ANSI_Slash, event.modifierFlags.contains(.command), !event.modifierFlags.contains(.shift), !event.modifierFlags.contains(.control) {
            if notesTableView.getSelectedNote() != nil {
                DispatchQueue.main.asyncAfter(deadline: .now() + 0.1) {
                    if self.titleLabel.isEditable {
                        self.fileName(self.titleLabel)
                    }
                }
                NSApp.mainWindow?.makeFirstResponder(search)
                return true
            }
        }

        if event.modifierFlags.contains(.command), event.keyCode == kVK_ANSI_S {
            if titleLabel.isEditable {
                fileName(titleLabel)
            }
            // Force flush of the 1.5s debounce queue so the user can rely
            // on Cmd+S as a hard-save point instead of a rename shortcut.
            // Toast only when something was actually flushed; otherwise a
            // user spamming Cmd+S sees a constant stream of "Saved~" toasts
            // even when there is nothing pending.
            if let activeNote = EditTextView.note {
                editArea.saveTextStorageContent(to: activeNote)
            }
            let hadUnsavedChanges = storage.noteList.contains { $0.needsSave }
            let allSaved = storage.flushPendingSaves()
            if hadUnsavedChanges, allSaved {
                toast(message: I18n.str("Saved~"), style: .success)
            }
            return false
        }

        if let fr = mw.firstResponder, !fr.isKind(of: EditTextView.self), !fr.isKind(of: NSTextView.self), !event.modifierFlags.contains(.command),
            !event.modifierFlags.contains(.control)
        {
            if let char = event.characters {
                let newSet = CharacterSet(charactersIn: char)
                if newSet.isSubset(of: CharacterSet.alphanumerics) {
                    _ = search.becomeFirstResponder()
                }
            }
        }

        return true
    }

    // MARK: - Info Panel Management
    func toggleInfo() {
        if popoverVisible {
            popover.performClose(nil)
        } else {
            showInfo("")
        }
    }

    private var popoverVisible: Bool {
        popover.isShown
    }

    func toastInSingleMode() {
        toast(message: I18n.str("In single open mode, Exit with Command+Shift+W ~"))
    }
}
