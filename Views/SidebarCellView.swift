import Cocoa

final class SidebarLabelCell: NSTextFieldCell {
    var verticalNudge: CGFloat = 0

    override func drawingRect(forBounds rect: NSRect) -> NSRect {
        nudgedRect(forBounds: rect)
    }

    override func edit(
        withFrame cellFrame: NSRect,
        in controlView: NSView,
        editor textObj: NSText,
        delegate: Any?,
        event: NSEvent?
    ) {
        super.edit(
            withFrame: nudgedRect(forBounds: cellFrame),
            in: controlView,
            editor: textObj,
            delegate: delegate,
            event: event
        )
    }

    override func select(
        withFrame cellFrame: NSRect,
        in controlView: NSView,
        editor textObj: NSText,
        delegate: Any?,
        start selStart: Int,
        length selLength: Int
    ) {
        super.select(
            withFrame: nudgedRect(forBounds: cellFrame),
            in: controlView,
            editor: textObj,
            delegate: delegate,
            start: selStart,
            length: selLength
        )
    }

    override func copy(with zone: NSZone? = nil) -> Any {
        let copied = super.copy(with: zone) as! SidebarLabelCell
        copied.verticalNudge = verticalNudge
        return copied
    }

    private func nudgedRect(forBounds rect: NSRect) -> NSRect {
        var textRect = super.drawingRect(forBounds: rect)
        textRect.origin.y += verticalNudge
        return textRect
    }
}

@MainActor
class SidebarCellView: NSTableCellView {
    private enum LayoutConstants {
        static let trailingPadding: CGFloat = 12
        static let minimumRenameWidth: CGFloat = 96
        static let maximumRenameWidth: CGFloat = 220
        static let renameHorizontalPadding: CGFloat = 16
    }

    @IBOutlet var icon: NSImageView!
    @IBOutlet var label: NSTextField!

    var storage = AppEnvironment.current.storage
    var representedSidebarItem: SidebarItem?

    override func draw(_ dirtyRect: NSRect) {
        label?.font = UserDefaultsManagement.nameFont
        super.draw(dirtyRect)
    }

    override func awakeFromNib() {
        super.awakeFromNib()

        MainActor.assumeIsolated { [self] in
            guard let label = label else { return }

            installSidebarLabelCellIfNeeded(label)
            label.lineBreakMode = .byTruncatingTail
            label.cell?.truncatesLastVisibleLine = true
            label.cell?.wraps = false
            label.cell?.usesSingleLineMode = true
            label.setContentCompressionResistancePriority(.defaultLow, for: .horizontal)
            label.setContentHuggingPriority(.defaultLow, for: .horizontal)
        }
    }

    private func installSidebarLabelCellIfNeeded(_ label: NSTextField) {
        guard !(label.cell is SidebarLabelCell),
            let existingCell = label.cell as? NSTextFieldCell
        else { return }

        let labelCell = SidebarLabelCell(textCell: existingCell.stringValue)
        labelCell.font = existingCell.font
        labelCell.textColor = existingCell.textColor
        labelCell.alignment = existingCell.alignment
        labelCell.lineBreakMode = existingCell.lineBreakMode
        labelCell.isEditable = existingCell.isEditable
        labelCell.isSelectable = existingCell.isSelectable
        labelCell.isBordered = existingCell.isBordered
        labelCell.isBezeled = existingCell.isBezeled
        labelCell.drawsBackground = existingCell.drawsBackground
        labelCell.backgroundColor = existingCell.backgroundColor
        labelCell.sendsActionOnEndEditing = existingCell.sendsActionOnEndEditing
        labelCell.wraps = false
        labelCell.usesSingleLineMode = true
        labelCell.truncatesLastVisibleLine = true
        label.cell = labelCell
    }

    override func layout() {
        super.layout()
        updatePreferredLabelWidth()
    }

    private func updatePreferredLabelWidth() {
        guard let label else { return }

        let availableWidth = max(0, label.bounds.width)
        guard abs(label.preferredMaxLayoutWidth - availableWidth) > 0.5 else { return }

        label.preferredMaxLayoutWidth = availableWidth
        label.invalidateIntrinsicContentSize()
    }

    func beginProjectNameEditing() {
        layoutSubtreeIfNeeded()

        let availableWidth = max(0, bounds.width - label.frame.minX - LayoutConstants.trailingPadding)
        let contentWidth = ceil(label.attributedStringValue.size().width) + LayoutConstants.renameHorizontalPadding
        let preferredWidth = min(
            max(contentWidth, min(LayoutConstants.minimumRenameWidth, availableWidth)),
            min(LayoutConstants.maximumRenameWidth, availableWidth)
        )
        let trailingPadding = max(
            LayoutConstants.trailingPadding,
            bounds.width - label.frame.minX - preferredWidth
        )
        updateLabelTrailingPadding(trailingPadding)
        layoutSubtreeIfNeeded()

        label.isEditable = true
        label.isSelectable = true
        window?.makeFirstResponder(label)
    }

    private func updateLabelTrailingPadding(_ padding: CGFloat) {
        guard
            let trailingConstraint = constraints.first(where: {
                ($0.firstItem as? NSView) === self
                    && $0.firstAttribute == .trailing
                    && ($0.secondItem as? NSView) === label
                    && $0.secondAttribute == .trailing
            })
        else { return }

        trailingConstraint.constant = padding
    }

    private var trackingArea: NSTrackingArea?

    override func updateTrackingAreas() {
        MainActor.assumeIsolated { [self] in
            if let trackingArea = self.trackingArea {
                removeTrackingArea(trackingArea)
            }

            let options: NSTrackingArea.Options = [.mouseEnteredAndExited, .activeAlways]
            let trackingArea = NSTrackingArea(rect: bounds, options: options, owner: self, userInfo: nil)
            addTrackingArea(trackingArea)
        }
    }

    @IBAction func projectName(_ sender: NSTextField) {
        defer {
            finishProjectNameEditing(sender)
        }

        guard let sidebarItem = representedSidebarItem, let project = sidebarItem.project else {
            sender.stringValue = representedSidebarItem?.name ?? sender.stringValue
            return
        }

        let viewController = window?.contentViewController as? ViewController
        viewController?.blockFSUpdates()

        do {
            let result = try storage.renameProject(project, to: sender.stringValue)
            sidebarItem.name = result.newURL.lastPathComponent
            sender.stringValue = sidebarItem.name
        } catch {
            AppDelegate.trackError(error, context: "SidebarCellView.projectName.renameProject")
            sender.stringValue = project.url.lastPathComponent
            MiaoYanAlert.show(
                message: I18n.str(error.localizedDescription),
                style: .warning,
                for: window
            )
            return
        }

        guard let vc = viewController else { return }
        vc.fsManager?.restart()
        vc.storageOutlineView.reloadData()
        vc.updateTable()
    }

    private func finishProjectNameEditing(_ sender: NSTextField) {
        let displayName = sender.stringValue
        sender.abortEditing()
        sender.stringValue = displayName
        sender.isEditable = false
        sender.isSelectable = false
        updateLabelTrailingPadding(LayoutConstants.trailingPadding)
        layoutSubtreeIfNeeded()

        guard let window = sender.window else { return }

        var ancestor: NSView? = self
        while let view = ancestor {
            if let outlineView = view as? SidebarProjectView {
                window.makeFirstResponder(outlineView)
                return
            }
            ancestor = view.superview
        }
    }

    @IBAction func add(_ sender: Any) {
        guard let vc = AppContext.shared.viewController else { return }
        vc.storageOutlineView.addProject(self)
    }
}
