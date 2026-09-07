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

@MainActor
final class GitSyncTerminationReplyGate {
    enum State: Equatable {
        case idle
        case pending
        case replied
    }

    private let reply: () -> Void
    private(set) var state: State = .idle

    init(reply: @escaping () -> Void) {
        self.reply = reply
    }

    func begin() -> Bool {
        guard state == .idle else { return false }
        state = .pending
        return true
    }

    @discardableResult
    func replyOnce() -> Bool {
        guard state == .pending else { return false }
        state = .replied
        reply()
        return true
    }
}

@MainActor
final class GitSyncTerminationController {
    private let timeout: TimeInterval
    private let onTimeout: () -> Void
    private let replyGate: GitSyncTerminationReplyGate
    private var timeoutTask: Task<Void, Never>?

    var state: GitSyncTerminationReplyGate.State { replyGate.state }

    init(timeout: TimeInterval, onTimeout: @escaping () -> Void, reply: @escaping () -> Void) {
        self.timeout = timeout
        self.onTimeout = onTimeout
        replyGate = GitSyncTerminationReplyGate(reply: reply)
    }

    func begin() -> Bool {
        guard replyGate.begin() else { return false }
        timeoutTask = Task { @MainActor [weak self] in
            guard let self else { return }
            do {
                try await Task.sleep(nanoseconds: UInt64(timeout * 1_000_000_000))
            } catch {
                return
            }
            guard state == .pending else { return }
            onTimeout()
            _ = finish()
        }
        return true
    }

    @discardableResult
    func finish() -> Bool {
        guard state == .pending else { return false }
        timeoutTask?.cancel()
        timeoutTask = nil
        return replyGate.replyOnce()
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
