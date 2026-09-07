import Foundation

@MainActor
final class GitSyncScheduler: NSObject {
    static let interval: TimeInterval = 15 * 60

    typealias SyncOperation = @MainActor (URL) async -> Void

    private let syncOperation: SyncOperation
    private var timer: Timer?
    private var syncTask: Task<Void, Never>?

    private(set) var scheduledRootURL: URL?
    var isSyncRunning: Bool { syncTask != nil }

    init(syncOperation: @escaping SyncOperation) {
        self.syncOperation = syncOperation
    }

    func configure(rootURL: URL?, enabled: Bool) {
        timer?.invalidate()
        timer = nil
        scheduledRootURL = nil

        guard enabled, let rootURL else { return }
        scheduledRootURL = rootURL.standardizedFileURL.resolvingSymlinksInPath()

        let timer = Timer(
            timeInterval: Self.interval,
            target: self,
            selector: #selector(timerDidFire(_:)),
            userInfo: nil,
            repeats: true
        )
        timer.tolerance = 30
        RunLoop.main.add(timer, forMode: .common)
        self.timer = timer
    }

    func stop() {
        timer?.invalidate()
        timer = nil
        scheduledRootURL = nil
    }

    @objc private func timerDidFire(_: Timer) {
        trigger()
    }

    func triggerForTesting() {
        trigger()
    }

    private func trigger() {
        guard syncTask == nil, let rootURL = scheduledRootURL else { return }
        syncTask = Task { @MainActor [weak self] in
            guard let self else { return }
            await syncOperation(rootURL)
            syncTask = nil
        }
    }
}

final class GitSyncTerminationReplyGate: @unchecked Sendable {
    enum State: Equatable {
        case idle
        case pending
        case replied
    }

    private let reply: () -> Void
    private let lock = NSLock()
    private var storedState: State = .idle

    var state: State {
        lock.lock()
        defer { lock.unlock() }
        return storedState
    }

    init(reply: @escaping () -> Void) {
        self.reply = reply
    }

    func begin() -> Bool {
        lock.lock()
        defer { lock.unlock() }
        guard storedState == .idle else { return false }
        storedState = .pending
        return true
    }

    @discardableResult
    func replyOnce(beforeReply: (() -> Void)? = nil) -> Bool {
        lock.lock()
        guard storedState == .pending else {
            lock.unlock()
            return false
        }
        storedState = .replied
        lock.unlock()
        beforeReply?()
        reply()
        return true
    }
}

final class GitSyncTerminationController: @unchecked Sendable {
    private let timeout: TimeInterval
    private let onTimeout: () -> Void
    private let replyGate: GitSyncTerminationReplyGate
    private let timerQueue = DispatchQueue(label: "com.tw93.miaoyan.git-sync-termination")
    private let timerLock = NSLock()
    private var timeoutTimer: DispatchSourceTimer?

    var state: GitSyncTerminationReplyGate.State { replyGate.state }

    init(timeout: TimeInterval, onTimeout: @escaping () -> Void, reply: @escaping () -> Void) {
        self.timeout = timeout
        self.onTimeout = onTimeout
        replyGate = GitSyncTerminationReplyGate(reply: reply)
    }

    func begin() -> Bool {
        guard replyGate.begin() else { return false }
        let timer = DispatchSource.makeTimerSource(queue: timerQueue)
        timer.schedule(deadline: .now() + timeout)
        timer.setEventHandler { [weak self] in
            self?.timeoutDidFire()
        }
        timerLock.lock()
        timeoutTimer = timer
        timerLock.unlock()
        timer.resume()
        return true
    }

    @discardableResult
    func finish() -> Bool {
        guard replyGate.replyOnce() else { return false }
        cancelTimer()
        return true
    }

    private func timeoutDidFire() {
        guard replyGate.replyOnce(beforeReply: onTimeout) else { return }
        cancelTimer()
    }

    private func cancelTimer() {
        timerLock.lock()
        let timer = timeoutTimer
        timeoutTimer = nil
        timerLock.unlock()
        timer?.setEventHandler {}
        timer?.cancel()
    }
}

enum GitSyncTerminationError: LocalizedError {
    case pendingSaveFailed
    case cloudBackedLibrary
    case syncBecameUnavailable
    case timedOut

    var errorDescription: String? {
        switch self {
        case .pendingSaveFailed:
            return "Pending note changes could not be saved before quitting."
        case .cloudBackedLibrary:
            return "Pre-quit Git sync refused a cloud-backed or non-local library."
        case .syncBecameUnavailable:
            return "Pre-quit Git sync became unavailable before it could run."
        case .timedOut:
            return "Pre-quit Git sync exceeded its time limit. The application will quit without waiting longer."
        }
    }
}
