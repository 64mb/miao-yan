import Cocoa

typealias MainViewControllerProvider = @MainActor () -> ViewController?

@MainActor
final class PrefsWindowController: NSWindowController, NSWindowDelegate {
    private let viewControllerProvider: MainViewControllerProvider
    private var splitViewController: NSSplitViewController!
    private var sidebarViewController: NSViewController!
    private var prefsContentViewController: NSViewController!
    private var sidebarView: PrefsSidebarView!

    private lazy var generalPrefsVC = GeneralPrefsViewController()
    private lazy var editorPrefsVC = EditorPrefsViewController()
    private lazy var typographyPrefsVC = TypographyPrefsViewController()
    private lazy var gitSyncPrefsVC = GitSyncPrefsViewController(viewControllerProvider: viewControllerProvider)

    private var currentCategory: PreferencesCategory = .general
    private var hasPreparedWindowForDisplay = false

    private enum Metrics {
        static let windowSize = NSSize(width: 800, height: 520)
        static let sidebarWidth: CGFloat = 176
    }

    init(viewControllerProvider: @escaping MainViewControllerProvider) {
        self.viewControllerProvider = viewControllerProvider
        let window = NSWindow(
            contentRect: NSRect(origin: .zero, size: Metrics.windowSize),
            styleMask: [.titled, .closable],
            backing: .buffered,
            defer: false
        )

        window.minSize = Metrics.windowSize
        window.maxSize = Metrics.windowSize

        window.styleMask.insert(.titled)
        window.styleMask.insert(.closable)
        window.styleMask.insert(.fullSizeContentView)
        window.isReleasedWhenClosed = false

        super.init(window: window)

        setupUIComponents()
        NotificationCenter.default.addObserver(
            self,
            selector: #selector(updateAlwaysOnTopState),
            name: .alwaysOnTopChanged,
            object: nil
        )
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        fatalError("init(coder:) is unavailable")
    }

    deinit {
        NotificationCenter.default.removeObserver(self, name: .alwaysOnTopChanged, object: nil)
    }

    override func windowDidLoad() {
        super.windowDidLoad()
        if splitViewController == nil {
            setupUIComponents()
        }
    }

    private func setupUIComponents() {
        guard window != nil else { return }

        window?.delegate = self

        setupWindow()
        setupSplitView()
        setupSidebar()
        setupContent()
        showCategory(.general)
        applyWindowAppearance()

        window?.titleVisibility = .hidden
        window?.titlebarAppearsTransparent = true
        window?.title = currentCategory.title
        window?.toolbarStyle = .preference
        window?.standardWindowButton(.miniaturizeButton)?.isHidden = true
        window?.standardWindowButton(.zoomButton)?.isHidden = true
    }

    private func setupWindow() {
        guard window != nil else {
            fatalError("PrefsWindowController window should be initialized during init")
        }
    }

    private func setupSplitView() {
        splitViewController = NSSplitViewController()

        // Replace default splitView with custom one
        let customSplitView = PrefsSplitView()
        customSplitView.isVertical = true
        customSplitView.dividerStyle = .thin
        customSplitView.autoresizesSubviews = false

        splitViewController.splitView = customSplitView

        splitViewController.splitViewItems.forEach { item in
            item.canCollapse = false
        }

        window?.contentViewController = splitViewController
    }

    private func setupSidebar() {
        sidebarView = PrefsSidebarView(frame: NSRect(x: 0, y: 0, width: Metrics.sidebarWidth, height: Metrics.windowSize.height))
        sidebarView.delegate = self

        sidebarViewController = NSViewController()
        sidebarViewController.view = sidebarView

        let sidebarItem = NSSplitViewItem(viewController: sidebarViewController)
        sidebarItem.minimumThickness = Metrics.sidebarWidth
        sidebarItem.maximumThickness = Metrics.sidebarWidth
        sidebarItem.canCollapse = false

        sidebarItem.allowsFullHeightLayout = true
        sidebarItem.titlebarSeparatorStyle = .none

        splitViewController.addSplitViewItem(sidebarItem)
    }

    private func setupContent() {
        prefsContentViewController = NSViewController()
        let contentView = PrefsContentBackgroundView(
            frame: NSRect(
                x: 0,
                y: 0,
                width: Metrics.windowSize.width - Metrics.sidebarWidth,
                height: Metrics.windowSize.height
            ))
        prefsContentViewController.view = contentView

        let contentItem = NSSplitViewItem(viewController: prefsContentViewController)
        contentItem.canCollapse = false

        splitViewController.addSplitViewItem(contentItem)
    }

    private func showCategory(_ category: PreferencesCategory) {
        currentCategory = category

        if let currentVC = prefsContentViewController.children.first {
            currentVC.removeFromParent()
            currentVC.view.removeFromSuperview()
        }

        let newVC = viewController(for: category)
        window?.title = category.title

        prefsContentViewController.addChild(newVC)

        newVC.view.translatesAutoresizingMaskIntoConstraints = false
        prefsContentViewController.view.addSubview(newVC.view)

        NSLayoutConstraint.activate([
            newVC.view.leadingAnchor.constraint(equalTo: prefsContentViewController.view.leadingAnchor),
            newVC.view.trailingAnchor.constraint(equalTo: prefsContentViewController.view.trailingAnchor),
            newVC.view.topAnchor.constraint(equalTo: prefsContentViewController.view.topAnchor),
            newVC.view.bottomAnchor.constraint(equalTo: prefsContentViewController.view.bottomAnchor),
        ])

        sidebarView?.selectCategory(category)
    }

    private func viewController(for category: PreferencesCategory) -> NSViewController {
        switch category {
        case .general:
            return generalPrefsVC
        case .typography:
            return typographyPrefsVC
        case .editor:
            return editorPrefsVC
        case .gitSync:
            return gitSyncPrefsVC
        }
    }

    func show() {
        if !isWindowLoaded {
            _ = window
        }

        prepareWindowForDisplayIfNeeded()
        updateAlwaysOnTopState()

        showWindow(self)
        window?.makeKeyAndOrderFront(self)
        NSApp.activate(ignoringOtherApps: true)
    }

    @objc private func updateAlwaysOnTopState() {
        window?.level = UserDefaultsManagement.alwaysOnTop ? .floating : .normal
    }

    func selectCategory(_ category: PreferencesCategory) {
        showCategory(category)
    }
}

@MainActor
final class GitSyncPrefsViewController: BasePrefsViewController {
    private let viewControllerProvider: MainViewControllerProvider
    private var libraryPathControl: NSPathControl!
    private var remoteField: NSTextField!
    private var usernameField: NSTextField!
    private var tokenField: NSSecureTextField!
    private var authorNameField: NSTextField!
    private var authorEmailField: NSTextField!
    private var moveLibraryButton: NSButton!
    private var automaticSyncButton: NSButton!
    private var aiEnabledButton: NSButton!
    private var aiBaseURLField: NSTextField!
    private var aiModelField: NSTextField!
    private var aiKeyField: NSSecureTextField!
    private var promptView: NSTextView!
    private var saveButton: NSButton!
    private var syncButton: NSButton!

    init(viewControllerProvider: @escaping MainViewControllerProvider) {
        self.viewControllerProvider = viewControllerProvider
        super.init(nibName: nil, bundle: nil)
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        fatalError("init(coder:) is unavailable")
    }

    override func setupUI() {
        let scrollView = NSScrollView()
        scrollView.translatesAutoresizingMaskIntoConstraints = false
        scrollView.hasVerticalScroller = true
        scrollView.drawsBackground = false
        scrollView.borderType = .noBorder
        view.addSubview(scrollView)

        let footerView = NSView()
        footerView.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(footerView)

        let documentView = GitSyncPrefsDocumentView()
        documentView.translatesAutoresizingMaskIntoConstraints = false
        scrollView.documentView = documentView

        let stack = NSStackView()
        stack.translatesAutoresizingMaskIntoConstraints = false
        stack.orientation = .vertical
        stack.alignment = .leading
        stack.spacing = PrefsFormMetrics.rowSpacing
        documentView.addSubview(stack)

        NSLayoutConstraint.activate([
            scrollView.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            scrollView.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            scrollView.topAnchor.constraint(equalTo: view.topAnchor),
            scrollView.bottomAnchor.constraint(equalTo: footerView.topAnchor),
            footerView.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            footerView.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            footerView.bottomAnchor.constraint(equalTo: view.bottomAnchor),
            footerView.heightAnchor.constraint(equalToConstant: 58),
            documentView.widthAnchor.constraint(equalTo: scrollView.contentView.widthAnchor),
            stack.leadingAnchor.constraint(equalTo: documentView.leadingAnchor, constant: 28),
            stack.trailingAnchor.constraint(lessThanOrEqualTo: documentView.trailingAnchor, constant: -28),
            stack.topAnchor.constraint(equalTo: documentView.topAnchor, constant: 54),
            documentView.bottomAnchor.constraint(equalTo: stack.bottomAnchor, constant: 28),
        ])

        libraryPathControl = NSPathControl()
        libraryPathControl.pathStyle = .standard
        libraryPathControl.lineBreakMode = .byTruncatingMiddle
        moveLibraryButton = NSButton(
            title: I18n.str("Move Library to Local Storage…"),
            target: self,
            action: #selector(moveLibrary(_:))
        )
        moveLibraryButton.bezelStyle = .rounded
        moveLibraryButton.isEnabled = false

        remoteField = NSTextField()
        remoteField.placeholderString = "https://github.com/owner/notes.git"
        usernameField = NSTextField()
        usernameField.placeholderString = I18n.str("Git username")
        tokenField = NSSecureTextField()
        authorNameField = NSTextField()
        authorNameField.placeholderString = I18n.str("Commit author name")
        authorEmailField = NSTextField()
        authorEmailField.placeholderString = I18n.str("Commit author email")

        automaticSyncButton = NSButton(
            checkboxWithTitle: I18n.str("Automatically sync every 15 minutes"),
            target: nil,
            action: nil
        )

        aiEnabledButton = NSButton(
            checkboxWithTitle: I18n.str("Enable AI diff conflict resolver"),
            target: self,
            action: #selector(aiEnabledChanged(_:))
        )
        aiBaseURLField = NSTextField()
        aiBaseURLField.placeholderString = "https://api.deepseek.com"
        aiModelField = NSTextField()
        aiModelField.placeholderString = "deepseek-v4-flash"
        aiKeyField = NSSecureTextField()
        [remoteField, usernameField, tokenField, authorNameField, authorEmailField, aiBaseURLField, aiModelField, aiKeyField]
            .forEach(configureSingleLineField)

        promptView = NSTextView(frame: NSRect(x: 0, y: 0, width: 300, height: 140))
        promptView.isRichText = false
        promptView.isHorizontallyResizable = false
        promptView.font = NSFont.monospacedSystemFont(ofSize: NSFont.smallSystemFontSize, weight: .regular)
        promptView.textContainer?.widthTracksTextView = true
        let promptScrollView = NSScrollView()
        promptScrollView.hasVerticalScroller = true
        promptScrollView.hasHorizontalScroller = false
        promptScrollView.borderType = .bezelBorder
        promptScrollView.documentView = promptView
        promptScrollView.heightAnchor.constraint(equalToConstant: 140).isActive = true

        saveButton = NSButton(
            title: I18n.str("Save"),
            target: self,
            action: #selector(saveSettings(_:))
        )
        saveButton.keyEquivalent = "\r"
        syncButton = NSButton(
            title: I18n.str("Sync Now"),
            target: self,
            action: #selector(syncNow(_:))
        )
        syncButton.bezelStyle = .rounded

        let actions = makeControlStack([syncButton, saveButton])
        footerView.addSubview(actions)
        let footerSeparator = NSBox()
        footerSeparator.boxType = .separator
        footerSeparator.translatesAutoresizingMaskIntoConstraints = false
        footerView.addSubview(footerSeparator)
        NSLayoutConstraint.activate([
            footerSeparator.leadingAnchor.constraint(equalTo: footerView.leadingAnchor),
            footerSeparator.trailingAnchor.constraint(equalTo: footerView.trailingAnchor),
            footerSeparator.topAnchor.constraint(equalTo: footerView.topAnchor),
            footerSeparator.heightAnchor.constraint(equalToConstant: 1),
            actions.trailingAnchor.constraint(equalTo: footerView.trailingAnchor, constant: -28),
            actions.centerYAnchor.constraint(equalTo: footerView.centerYAnchor),
        ])

        let explanation = NSTextField(
            wrappingLabelWithString: I18n.str(
                "The token is stored in macOS Keychain. MiaoYan syncs only the main branch over HTTPS."
            )
        )
        explanation.font = .systemFont(ofSize: NSFont.smallSystemFontSize)
        explanation.textColor = Theme.secondaryTextColor
        explanation.maximumNumberOfLines = 3

        let promptRow = makePreferencesRow(
            labelText: "\(I18n.str("Conflict resolution prompt")):",
            control: promptScrollView,
            controlWidth: 300
        )
        promptRow.heightAnchor.constraint(greaterThanOrEqualToConstant: 150).isActive = true

        [
            makePreferencesRow(
                labelText: "\(I18n.str("Current library")):",
                control: libraryPathControl,
                controlWidth: 300
            ),
            makePreferencesRow(labelText: "", control: moveLibraryButton, controlWidth: nil),
            makePreferencesRow(
                labelText: "\(I18n.str("Repository HTTPS URL")):", control: remoteField,
                controlWidth: 300
            ),
            makePreferencesRow(
                labelText: "\(I18n.str("Username")):", control: usernameField,
                controlWidth: 300
            ),
            makePreferencesRow(labelText: "\(I18n.str("Personal access token")):", control: tokenField, controlWidth: 300),
            makePreferencesRow(labelText: "\(I18n.str("Author name")):", control: authorNameField, controlWidth: 300),
            makePreferencesRow(labelText: "\(I18n.str("Author email")):", control: authorEmailField, controlWidth: 300),
            makePreferencesRow(labelText: "", control: automaticSyncButton, controlWidth: nil),
            makePreferencesSeparator(),
            makePreferencesRow(labelText: "", control: aiEnabledButton, controlWidth: nil),
            makePreferencesRow(labelText: "\(I18n.str("AI API base URL")):", control: aiBaseURLField, controlWidth: 300),
            makePreferencesRow(labelText: "\(I18n.str("AI model name")):", control: aiModelField, controlWidth: 300),
            makePreferencesRow(labelText: "\(I18n.str("AI API key")):", control: aiKeyField, controlWidth: 300),
            promptRow,
            makePreferencesRow(labelText: "", control: explanation, controlWidth: 300),
        ].forEach { stack.addArrangedSubview($0) }
    }

    override func setupValues() {
        refreshValues()
    }

    override func viewWillAppear() {
        super.viewWillAppear()
        refreshValues()
    }

    @objc private func aiEnabledChanged(_ sender: NSButton) {
        updateAIControls()
    }

    @objc private func moveLibrary(_ sender: NSButton) {
        guard let viewController = viewControllerProvider() else { return }
        viewController.requestGitLibraryMigration(presentingWindow: view.window)
    }

    @objc private func saveSettings(_ sender: NSButton) {
        guard let viewController = viewControllerProvider() else { return }
        let input = GitSyncSettingsInput(
            remoteURL: remoteField.stringValue,
            username: usernameField.stringValue,
            personalAccessToken: tokenField.stringValue,
            authorName: authorNameField.stringValue,
            authorEmail: authorEmailField.stringValue,
            automaticSyncEnabled: automaticSyncButton.state == .on,
            aiEnabled: aiEnabledButton.state == .on,
            aiBaseURL: aiBaseURLField.stringValue,
            aiModel: aiModelField.stringValue,
            aiAPIKey: aiKeyField.stringValue,
            aiPrompt: promptView.string
        )
        viewController.saveGitSyncSettings(
            input,
            presentingWindow: view.window,
            completion: { [weak self] in self?.refreshValues() }
        )
    }

    @objc private func syncNow(_ sender: NSButton) {
        guard let viewController = viewControllerProvider() else { return }
        guard let root = viewController.selectedGitSyncRoot(),
            viewController.gitSyncConfigurationStore.configuration(for: root.url) != nil
        else {
            saveSettings(saveButton)
            return
        }
        view.window?.orderOut(sender)
        viewController.view.window?.makeKeyAndOrderFront(sender)
        viewController.syncGitRepository(sender)
    }

    private func refreshValues() {
        guard isViewLoaded, let viewController = viewControllerProvider(),
            let root = viewController.selectedGitSyncRoot()
        else {
            libraryPathControl.url = nil
            remoteField.stringValue = ""
            remoteField.placeholderString = I18n.str("Select the main library in the sidebar.")
            moveLibraryButton.isEnabled = false
            moveLibraryButton.toolTip = nil
            automaticSyncButton.state = .off
            saveButton.isEnabled = false
            syncButton.isEnabled = false
            return
        }

        libraryPathControl.url = root.url
        updateMoveLibraryButton(for: root.url)
        let configuration = viewController.gitSyncConfigurationStore.configuration(for: root.url)
        let credential = configuration.flatMap {
            try? AppEnvironment.current.gitCredentialStore.credential(for: $0.remoteURL)
        }
        remoteField.stringValue = configuration?.remoteURL.absoluteString ?? ""
        usernameField.stringValue = credential?.username ?? ""
        tokenField.stringValue = ""
        tokenField.placeholderString =
            credential == nil ? I18n.str("Personal access token") : I18n.str("Stored token (leave blank to keep)")
        authorNameField.stringValue = configuration?.authorName ?? ""
        authorEmailField.stringValue = configuration?.authorEmail ?? ""
        automaticSyncButton.state = configuration?.automaticSyncEnabled == true ? .on : .off
        let ai = configuration?.ai
        aiEnabledButton.state = ai == nil ? .off : .on
        aiBaseURLField.stringValue = ai?.baseURL.absoluteString ?? GitAIConfiguration.defaultBaseURL.absoluteString
        aiModelField.stringValue = ai?.model ?? GitAIConfiguration.defaultModel
        aiKeyField.stringValue = ""
        let hasStoredAIKey = ai.flatMap { try? AppEnvironment.current.gitAIKeyStore.apiKey(for: $0.baseURL) } != nil
        aiKeyField.placeholderString =
            hasStoredAIKey ? I18n.str("Stored API key (leave blank to keep)") : I18n.str("AI API key")
        promptView.string = ai?.prompt ?? GitAIConfiguration.defaultPrompt
        saveButton.isEnabled = true
        syncButton.isEnabled = configuration != nil
        updateAIControls()
    }

    private func updateMoveLibraryButton(for rootURL: URL) {
        let expectedPath = rootURL.standardizedFileURL.resolvingSymlinksInPath().path
        moveLibraryButton.isEnabled = GitSyncLibraryLocationPolicy.isKnownCloudPath(rootURL)
        moveLibraryButton.toolTip = moveLibraryButton.isEnabled ? nil : I18n.str("Checking library location…")

        Task { @MainActor [weak self] in
            let isCloudBacked = await GitSyncLibraryLocationPolicy.isCloudBacked(rootURL)
            guard let self,
                libraryPathControl.url?.standardizedFileURL.resolvingSymlinksInPath().path == expectedPath
            else { return }
            moveLibraryButton.isEnabled = isCloudBacked
            moveLibraryButton.toolTip =
                isCloudBacked
                ? I18n.str("Move the current library out of cloud storage before enabling Git sync.")
                : I18n.str("The current library is already stored locally.")
        }
    }

    private func updateAIControls() {
        let enabled = aiEnabledButton.state == .on
        [aiBaseURLField, aiModelField, aiKeyField].forEach { $0?.isEnabled = enabled }
        promptView.isEditable = enabled
        promptView.textColor = enabled ? Theme.textColor : Theme.secondaryTextColor
    }

    private func configureSingleLineField(_ field: NSTextField) {
        field.usesSingleLineMode = true
        field.lineBreakMode = .byTruncatingTail
        field.cell?.wraps = false
        field.cell?.isScrollable = true
    }
}

private final class GitSyncPrefsDocumentView: NSView {
    override var isFlipped: Bool { true }
}

extension PrefsWindowController: PrefsSidebarDelegate {
    func sidebarDidSelectCategory(_ category: PreferencesCategory) {
        guard category != currentCategory else { return }
        showCategory(category)
    }

    func refreshThemeAppearance() {
        updateWindowBackgroundColors()
        sidebarView?.refreshAppearance()
    }
}

extension PrefsWindowController {
    func windowShouldClose(_ sender: NSWindow) -> Bool {
        window?.orderOut(self)
        return false
    }

    func windowDidChangeEffectiveAppearance(_ notification: Notification) {
        applyWindowAppearance()
    }
}

extension PrefsWindowController {
    fileprivate func applyWindowAppearance() {
        guard let window else { return }

        let targetAppearance: NSAppearance? =
            switch UserDefaultsManagement.appearanceType {
            case .Light: NSAppearance(named: .aqua)
            case .Dark: NSAppearance(named: .darkAqua)
            case .System, .Custom: nil
            }

        window.appearance = targetAppearance
        window.contentView?.appearance = targetAppearance

        updateWindowBackgroundColors()

        // Ensure subviews refresh their appearance
        sidebarView?.refreshAppearance()
    }

    fileprivate func updateWindowBackgroundColors() {
        guard let window else { return }

        let effectiveAppearance = window.effectiveAppearance
        var backgroundColor: NSColor = .windowBackgroundColor
        effectiveAppearance.performAsCurrentDrawingAppearance {
            backgroundColor = Theme.settingsWindowBackgroundColor
        }

        window.backgroundColor = backgroundColor
    }
}

private final class PrefsContentBackgroundView: NSView {
    override var isFlipped: Bool { true }

    override init(frame frameRect: NSRect) {
        super.init(frame: frameRect)
        commonInit()
    }

    required init?(coder: NSCoder) {
        super.init(coder: coder)
        commonInit()
    }

    private func commonInit() {
        wantsLayer = true
        updateColors()
    }

    override func viewDidMoveToWindow() {
        super.viewDidMoveToWindow()
        updateColors()
    }

    override func viewDidChangeEffectiveAppearance() {
        super.viewDidChangeEffectiveAppearance()
        updateColors()
    }

    private func updateColors() {
        let appearance = window?.effectiveAppearance ?? effectiveAppearance
        let resolvedColor = Theme.settingsContentBackgroundColor.resolvedColor(for: appearance)
        layer?.backgroundColor = resolvedColor.cgColor
    }
}

// MARK: - Custom SplitView for Preferences
final class PrefsSplitView: NSSplitView {
    override func drawDivider(in rect: NSRect) {
        Theme.settingsDividerColor.resolvedColor(for: effectiveAppearance).setFill()

        guard Theme.usesModernSystemChrome else {
            rect.fill()
            return
        }

        NSBezierPath(rect: hairlineRect(in: rect)).fill()
    }

    override func viewDidChangeEffectiveAppearance() {
        super.viewDidChangeEffectiveAppearance()
        needsDisplay = true
    }

    private func hairlineRect(in rect: NSRect) -> NSRect {
        let scale = window?.backingScaleFactor ?? NSScreen.main?.backingScaleFactor ?? 2
        let thickness = 1 / scale

        if isVertical {
            return NSRect(
                x: rect.midX - thickness / 2,
                y: rect.minY,
                width: thickness,
                height: rect.height
            )
        }

        return NSRect(
            x: rect.minX,
            y: rect.midY - thickness / 2,
            width: rect.width,
            height: thickness
        )
    }
}

extension PrefsWindowController {
    fileprivate func prepareWindowForDisplayIfNeeded() {
        guard let window else { return }

        window.contentView?.layoutSubtreeIfNeeded()

        if !hasPreparedWindowForDisplay {
            window.setContentSize(Metrics.windowSize)
            window.center()
            hasPreparedWindowForDisplay = true
        }
    }
}
