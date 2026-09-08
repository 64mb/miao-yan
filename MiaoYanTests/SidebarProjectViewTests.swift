import AppKit
import XCTest

@testable import MiaoYan

final class SidebarProjectViewTests: XCTestCase {
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
