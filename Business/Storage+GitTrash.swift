import Foundation

extension Storage {
    /// The sidebar Trash is an aggregate view, but it still carries one
    /// project so selection restoration and project-scoped refreshes work.
    /// Prefer the Git transport project when it owns a visible Trash note;
    /// otherwise the row points at the system Trash and a transient nil
    /// sidebar type can filter every Git-backed note out of the table.
    func getSidebarTrashProject() -> Project? {
        if let syncedProject = noteList.first(where: {
            $0.isTrash() && Self.isSyncedTrashProject($0.project)
        })?.project {
            return syncedProject
        }

        return getDefaultTrash()
    }

    func deleteSyncedTrashPayload(at payloadURL: URL) throws {
        guard let context = syncedTrashContext(for: payloadURL) else {
            throw syncedTrashError("The Trash payload is outside a configured Git library")
        }
        let previousEntries = try readSyncedTrashManifest(for: context.root.url)
        let remainingEntries = previousEntries.filter { $0.id != context.entry?.id }
        if context.entry != nil {
            try writeSyncedTrashManifest(remainingEntries, for: context.root.url)
        }

        do {
            try FileManager.default.removeItem(at: payloadURL)
        } catch {
            if context.entry != nil {
                try? writeSyncedTrashManifest(previousEntries, for: context.root.url)
            }
            throw error
        }

        let itemDirectory = payloadURL.deletingLastPathComponent()
        if (try? FileManager.default.contentsOfDirectory(atPath: itemDirectory.path).isEmpty) == true {
            try? FileManager.default.removeItem(at: itemDirectory)
        }
    }

    func loadSyncedTrash(for root: Project) {
        guard usesGitSyncedTrash(for: root) else { return }
        guard let project = ensureSyncedTrashProject(for: root, createDirectories: false) else { return }
        let itemsURL = project.url
        guard
            let itemDirectories = try? FileManager.default.contentsOfDirectory(
                at: itemsURL,
                includingPropertiesForKeys: [.isDirectoryKey, .isSymbolicLinkKey],
                options: [.skipsHiddenFiles])
        else { return }

        var entries: [GitSyncedTrashManifestEntry]
        do {
            entries = try readSyncedTrashManifest(for: root.url)
        } catch {
            AppDelegate.trackError(error, context: "Storage.syncedTrash.readManifest")
            entries = []
        }
        do {
            entries = try removingMissingManifestEntries(entries, root: root)
        } catch {
            // Payloads remain readable even if repairing an old manifest fails.
            AppDelegate.trackError(error, context: "Storage.syncedTrash.repairManifest")
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

    private func removingMissingManifestEntries(
        _ entries: [GitSyncedTrashManifestEntry],
        root: Project
    ) throws -> [GitSyncedTrashManifestEntry] {
        let remaining = entries.filter { entry in
            guard
                let uuid = UUID(uuidString: entry.id),
                uuid.uuidString.lowercased() == entry.id,
                entry.trashRelativePath.hasPrefix(".Trash/items/\(entry.id)/")
            else {
                // Do not rewrite entries whose semantics this version does not
                // understand. The sync path policy will reject unsafe paths.
                return true
            }
            let suffix = entry.trashRelativePath.split(separator: "/", omittingEmptySubsequences: false).dropFirst(3)
            guard !suffix.isEmpty, !suffix.contains(where: { $0.isEmpty || $0 == "." || $0 == ".." }) else {
                return true
            }

            let payloadURL = root.url.appendingPathComponent(entry.trashRelativePath)
            if FileManager.default.fileExists(atPath: payloadURL.path) {
                return true
            }
            // Preserve a broken symlink for the existing safety checks instead
            // of silently changing a potentially hostile repository.
            let values = try? payloadURL.resourceValues(forKeys: [.isSymbolicLinkKey])
            return values?.isSymbolicLink == true
        }
        guard remaining.count != entries.count else { return entries }
        try writeSyncedTrashManifest(remaining, for: root.url)
        return remaining
    }
}
