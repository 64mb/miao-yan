import AppKit
import XCTest

@testable import MiaoYan

final class SidebarProjectViewTests: XCTestCase {
    @MainActor
    func testProjectRenameActionMovesFolderUsingExplicitSidebarItem() throws {
        let rootURL = FileManager.default.temporaryDirectory
            .appendingPathComponent("MiaoYanProjectRenameActionTests-\(UUID().uuidString)", isDirectory: true)
        let oldURL = rootURL.appendingPathComponent("Ideas", isDirectory: true)
        let newURL = rootURL.appendingPathComponent("🔮 Ideas", isDirectory: true)
        try FileManager.default.createDirectory(at: oldURL, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: rootURL) }

        let storage = Storage(storageURL: nil)
        let root = Project(url: rootURL, isRoot: true, isDefault: true)
        _ = storage.add(project: root)
        let project = try XCTUnwrap(storage.getChildProjects(project: root).first)
        let cell = SidebarCellView(frame: NSRect(x: 0, y: 0, width: 180, height: 32))
        let label = NSTextField(frame: cell.bounds)
        let sidebarItem = SidebarItem(name: "Ideas", project: project, type: .Category)
        cell.storage = storage
        cell.representedSidebarItem = sidebarItem
        cell.objectValue = nil
        label.stringValue = "🔮 Ideas"

        cell.projectName(label)

        XCTAssertFalse(FileManager.default.fileExists(atPath: oldURL.path))
        XCTAssertTrue(FileManager.default.fileExists(atPath: newURL.path))
        XCTAssertEqual(project.url, newURL)
        XCTAssertEqual(sidebarItem.name, "🔮 Ideas")
        XCTAssertEqual(label.stringValue, "🔮 Ideas")
        XCTAssertFalse(label.isEditable)
    }

    @MainActor
    func testStorageRenameMovesDirectoryAndUpdatesDescendantModels() throws {
        let rootURL = FileManager.default.temporaryDirectory
            .appendingPathComponent("MiaoYanProjectRenameTests-\(UUID().uuidString)", isDirectory: true)
        let oldURL = rootURL.appendingPathComponent("Ideas", isDirectory: true)
        let nestedURL = oldURL.appendingPathComponent("Drafts", isDirectory: true)
        let noteURL = nestedURL.appendingPathComponent("Plan.md")
        try FileManager.default.createDirectory(at: nestedURL, withIntermediateDirectories: true)
        try "plan\n".write(to: noteURL, atomically: true, encoding: .utf8)
        defer { try? FileManager.default.removeItem(at: rootURL) }

        let storage = Storage(storageURL: nil)
        let root = Project(url: rootURL, isRoot: true, isDefault: true)
        _ = storage.add(project: root)
        let project = try XCTUnwrap(storage.getChildProjects(project: root).first)
        let nested = Project(url: nestedURL, parent: project)
        _ = storage.add(project: nested)
        let note = Note(url: noteURL, with: nested)
        storage.add(note)

        let result = try storage.renameProject(project, to: "🔮 Ideas")

        let renamedURL = rootURL.appendingPathComponent("🔮 Ideas", isDirectory: true)
        let renamedNestedURL = renamedURL.appendingPathComponent("Drafts", isDirectory: true)
        let renamedNoteURL = renamedNestedURL.appendingPathComponent("Plan.md")
        XCTAssertEqual(result, ProjectRenameResult(oldURL: oldURL, newURL: renamedURL))
        XCTAssertFalse(FileManager.default.fileExists(atPath: oldURL.path))
        XCTAssertTrue(FileManager.default.fileExists(atPath: renamedNoteURL.path))
        XCTAssertEqual(project.url, renamedURL)
        XCTAssertEqual(project.label, "🔮 Ideas")
        XCTAssertEqual(nested.url, renamedNestedURL)
        XCTAssertEqual(note.url, renamedNoteURL)
        XCTAssertTrue(note.project === nested)
    }

    @MainActor
    func testStorageRenameFailureLeavesDiskAndModelsAtOriginalPaths() throws {
        let rootURL = FileManager.default.temporaryDirectory
            .appendingPathComponent("MiaoYanProjectRenameFailureTests-\(UUID().uuidString)", isDirectory: true)
        let oldURL = rootURL.appendingPathComponent("Ideas", isDirectory: true)
        let occupiedURL = rootURL.appendingPathComponent("Archive", isDirectory: true)
        let noteURL = oldURL.appendingPathComponent("Plan.md")
        try FileManager.default.createDirectory(at: oldURL, withIntermediateDirectories: true)
        try FileManager.default.createDirectory(at: occupiedURL, withIntermediateDirectories: true)
        try "plan\n".write(to: noteURL, atomically: true, encoding: .utf8)
        defer { try? FileManager.default.removeItem(at: rootURL) }

        let storage = Storage(storageURL: nil)
        let root = Project(url: rootURL, isRoot: true, isDefault: true)
        _ = storage.add(project: root)
        let project = try XCTUnwrap(storage.getChildProjects(project: root).first { $0.url == oldURL })
        let note = Note(url: noteURL, with: project)
        storage.add(note)

        XCTAssertThrowsError(try storage.renameProject(project, to: "Archive"))

        XCTAssertTrue(FileManager.default.fileExists(atPath: noteURL.path))
        XCTAssertEqual(project.url, oldURL)
        XCTAssertEqual(project.label, "Ideas")
        XCTAssertEqual(note.url, noteURL)
    }

    @MainActor
    func testCompletingProjectRenameReturnsLabelToDisplayMode() {
        let cell = SidebarCellView(frame: NSRect(x: 0, y: 0, width: 180, height: 32))
        let label = NSTextField(frame: cell.bounds)
        label.isEditable = true
        label.isSelectable = true
        cell.addSubview(label)

        cell.projectName(label)

        XCTAssertFalse(label.isEditable)
        XCTAssertFalse(label.isSelectable)
    }

    @MainActor
    func testCompletingProjectRenameRetiresTheFieldEditor() {
        let window = NSWindow(
            contentRect: NSRect(x: 0, y: 0, width: 320, height: 180),
            styleMask: [.titled],
            backing: .buffered,
            defer: false
        )
        let outlineView = SidebarProjectView(frame: window.contentView?.bounds ?? .zero)
        window.contentView = outlineView
        let cell = SidebarCellView(frame: NSRect(x: 0, y: 0, width: 180, height: 32))
        let label = NSTextField(frame: cell.bounds)
        label.isEditable = true
        label.isSelectable = true
        cell.addSubview(label)
        outlineView.addSubview(cell)

        XCTAssertTrue(window.makeFirstResponder(label))
        XCTAssertNotNil(label.currentEditor())

        cell.projectName(label)

        XCTAssertNil(label.currentEditor())
        XCTAssertFalse(window.firstResponder is NSTextView)
    }

    @MainActor
    func testSidebarLabelCellReplacementPreservesRenameAction() {
        let cell = SidebarCellView(frame: NSRect(x: 0, y: 0, width: 180, height: 32))
        let label = NSTextField(frame: cell.bounds)
        label.target = cell
        label.action = #selector(SidebarCellView.projectName(_:))
        label.cell?.sendsActionOnEndEditing = true
        cell.label = label

        cell.awakeFromNib()

        XCTAssertTrue(label.target === cell)
        XCTAssertEqual(label.action, #selector(SidebarCellView.projectName(_:)))
        XCTAssertEqual(label.cell?.sendsActionOnEndEditing, true)
    }

    @MainActor
    func testEndingProjectNameEditingMovesFolderAndRetiresFieldEditor() throws {
        let rootURL = FileManager.default.temporaryDirectory
            .appendingPathComponent("MiaoYanProjectRenameLifecycleTests-\(UUID().uuidString)", isDirectory: true)
        let oldURL = rootURL.appendingPathComponent("Ideas", isDirectory: true)
        let newURL = rootURL.appendingPathComponent("🔮 Ideas", isDirectory: true)
        try FileManager.default.createDirectory(at: oldURL, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: rootURL) }

        let storage = Storage(storageURL: nil)
        let root = Project(url: rootURL, isRoot: true, isDefault: true)
        _ = storage.add(project: root)
        let project = try XCTUnwrap(storage.getChildProjects(project: root).first)
        let sidebarItem = SidebarItem(name: "Ideas", project: project, type: .Category)

        let window = NSWindow(
            contentRect: NSRect(x: 0, y: 0, width: 320, height: 180),
            styleMask: [.titled],
            backing: .buffered,
            defer: false
        )
        let outlineView = SidebarProjectView(frame: window.contentView?.bounds ?? .zero)
        window.contentView = outlineView

        let cell = SidebarCellView(frame: NSRect(x: 0, y: 0, width: 220, height: 40))
        let label = NSTextField(labelWithString: sidebarItem.name)
        label.frame = NSRect(x: 28, y: 10, width: 180, height: 20)
        label.target = cell
        label.action = #selector(SidebarCellView.projectName(_:))
        label.cell?.sendsActionOnEndEditing = true
        cell.storage = storage
        cell.representedSidebarItem = sidebarItem
        cell.label = label
        cell.addSubview(label)
        outlineView.addSubview(cell)
        cell.awakeFromNib()

        cell.beginProjectNameEditing()
        let fieldEditor = try XCTUnwrap(label.currentEditor())
        fieldEditor.string = "🔮 Ideas"

        XCTAssertTrue(window.makeFirstResponder(outlineView))

        XCTAssertFalse(FileManager.default.fileExists(atPath: oldURL.path))
        XCTAssertTrue(FileManager.default.fileExists(atPath: newURL.path))
        XCTAssertEqual(project.url, newURL)
        XCTAssertNil(label.currentEditor())
        XCTAssertFalse(label.isEditable)
        XCTAssertFalse(label.isSelectable)
    }

    @MainActor
    func testProjectRenameFieldFitsItsContentAndKeepsTrailingPadding() {
        let cell = SidebarCellView(frame: NSRect(x: 0, y: 0, width: 360, height: 40))
        let label = NSTextField(labelWithString: "Ideas")
        label.translatesAutoresizingMaskIntoConstraints = false
        cell.addSubview(label)
        cell.label = label
        NSLayoutConstraint.activate([
            label.leadingAnchor.constraint(equalTo: cell.leadingAnchor, constant: 28),
            cell.trailingAnchor.constraint(equalTo: label.trailingAnchor, constant: 12),
            label.centerYAnchor.constraint(equalTo: cell.centerYAnchor),
        ])
        cell.layoutSubtreeIfNeeded()

        cell.beginProjectNameEditing()

        XCTAssertLessThanOrEqual(label.frame.width, 180.5)
        XCTAssertGreaterThanOrEqual(cell.bounds.maxX - label.frame.maxX, 15.5)
    }

    @MainActor
    func testSearchFieldPlaceholderAndEditorRectsShareVerticalCenterInLightAndDarkAppearances() throws {
        let cell = SearchFieldCell()
        let bounds = NSRect(x: 0, y: 0, width: 240, height: SearchFieldCell.height)

        for appearanceName in [NSAppearance.Name.aqua, .darkAqua] {
            let appearance = try XCTUnwrap(NSAppearance(named: appearanceName))
            appearance.performAsCurrentDrawingAppearance {
                let editorRect = cell.searchTextRect(forBounds: bounds)
                let placeholderRect = cell.drawingRect(forBounds: bounds)

                XCTAssertEqual(editorRect.midY, bounds.midY, accuracy: 0.01, appearanceName.rawValue)
                XCTAssertEqual(placeholderRect.midY, bounds.midY, accuracy: 0.01, appearanceName.rawValue)
                XCTAssertEqual(editorRect, placeholderRect, appearanceName.rawValue)
            }
        }
    }

    @MainActor
    func testTileRestoresNonScrollableWidthAfterSidebarReload() {
        let scrollView = NSScrollView(frame: NSRect(x: 0, y: 0, width: 127, height: 300))
        scrollView.hasHorizontalScroller = false

        let outlineView = SidebarProjectView(frame: NSRect(x: 0, y: 0, width: 127, height: 300))
        let column = NSTableColumn(identifier: NSUserInterfaceItemIdentifier("sidebar"))
        outlineView.addTableColumn(column)
        outlineView.outlineTableColumn = column
        scrollView.documentView = outlineView
        scrollView.layoutSubtreeIfNeeded()

        let clipView = scrollView.contentView
        let clipWidth = clipView.bounds.width

        // Reproduce the AppKit geometry observed after a deletion refresh:
        // reloadData leaves the outline wider than its clip view, making the
        // otherwise hidden horizontal range scrollable during the next switch.
        outlineView.setFrameSize(NSSize(width: clipWidth + 32, height: outlineView.frame.height))
        column.width = clipWidth
        clipView.scroll(to: NSPoint(x: 32, y: clipView.bounds.origin.y))

        XCTAssertGreaterThan(outlineView.frame.width, clipWidth)
        XCTAssertGreaterThan(clipView.bounds.origin.x, 0)

        outlineView.reloadData()

        XCTAssertEqual(outlineView.frame.width, clipWidth, accuracy: 0.5)
        XCTAssertEqual(column.width, clipWidth, accuracy: 0.5)
        XCTAssertEqual(clipView.bounds.origin.x, 0, accuracy: 0.5)
    }
}
