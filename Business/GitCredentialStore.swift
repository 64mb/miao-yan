import Foundation
import Security

struct GitHTTPSCredential: Codable, Equatable, CustomStringConvertible, CustomDebugStringConvertible, CustomReflectable {
    let username: String
    let personalAccessToken: String

    var description: String {
        "GitHTTPSCredential(username: \(username), personalAccessToken: <redacted>)"
    }

    var debugDescription: String { description }

    var customMirror: Mirror {
        Mirror(
            self,
            children: [
                "username": username,
                "personalAccessToken": "<redacted>",
            ]
        )
    }
}

struct GitCredentialKey: Equatable {
    let service: String
    let account: String
}

enum GitCredentialStoreError: Error, Equatable {
    case invalidRemoteURL
    case unsupportedRemoteScheme
    case missingRemoteHost
    case missingRepositoryPath
    case emptyUsername
    case emptyPersonalAccessToken
    case serializationFailed
    case invalidStoredCredential
    case keychain(OSStatus)
}

extension GitCredentialStoreError: LocalizedError {
    var errorDescription: String? {
        switch self {
        case .invalidRemoteURL:
            return "The Git remote URL is invalid."
        case .unsupportedRemoteScheme:
            return "Only HTTPS Git remote URLs are supported."
        case .missingRemoteHost:
            return "The Git remote URL has no host."
        case .missingRepositoryPath:
            return "The Git remote URL has no repository path."
        case .emptyUsername:
            return "The Git username cannot be empty."
        case .emptyPersonalAccessToken:
            return "The personal access token cannot be empty."
        case .serializationFailed:
            return "The Git credential could not be encoded."
        case .invalidStoredCredential:
            return "The stored Git credential is invalid."
        case .keychain(let status):
            let message = SecCopyErrorMessageString(status, nil) as String? ?? "Unknown Keychain error"
            return "Keychain operation failed: \(message) (\(status))."
        }
    }
}

enum KeychainDataStoreError: Error, Equatable {
    case status(OSStatus)
    case unexpectedResult
}

protocol KeychainDataStoring {
    func save(_ data: Data, service: String, account: String) throws
    func read(service: String, account: String) throws -> Data?
    func delete(service: String, account: String) throws
}

/// `SecItem` adapter kept behind a small protocol so credential behavior can be
/// tested without reading or modifying a developer's real login Keychain.
struct SecurityKeychainDataStore: KeychainDataStoring {
    func save(_ data: Data, service: String, account: String) throws {
        let query = baseQuery(service: service, account: account)
        var attributes = query
        attributes[kSecValueData] = data
        attributes[kSecAttrAccessible] = kSecAttrAccessibleWhenUnlocked

        let addStatus = SecItemAdd(attributes as CFDictionary, nil)
        if addStatus == errSecSuccess { return }
        guard addStatus == errSecDuplicateItem else {
            throw KeychainDataStoreError.status(addStatus)
        }

        let updateStatus = SecItemUpdate(query as CFDictionary, [kSecValueData: data] as CFDictionary)
        guard updateStatus == errSecSuccess else {
            throw KeychainDataStoreError.status(updateStatus)
        }
    }

    func read(service: String, account: String) throws -> Data? {
        var query = baseQuery(service: service, account: account)
        query[kSecReturnData] = true
        query[kSecMatchLimit] = kSecMatchLimitOne

        var result: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &result)
        if status == errSecItemNotFound { return nil }
        guard status == errSecSuccess else {
            throw KeychainDataStoreError.status(status)
        }
        guard let data = result as? Data else {
            throw KeychainDataStoreError.unexpectedResult
        }
        return data
    }

    func delete(service: String, account: String) throws {
        let status = SecItemDelete(baseQuery(service: service, account: account) as CFDictionary)
        guard status == errSecSuccess || status == errSecItemNotFound else {
            throw KeychainDataStoreError.status(status)
        }
    }

    private func baseQuery(service: String, account: String) -> [CFString: Any] {
        [
            kSecClass: kSecClassGenericPassword,
            kSecAttrService: service,
            kSecAttrAccount: account,
        ]
    }
}

final class GitCredentialStore {
    private static let keychainService = "com.tw93.miaoyan.git-https-credentials"

    private let keychain: KeychainDataStoring
    private let encoder: JSONEncoder
    private let decoder: JSONDecoder

    init(
        keychain: KeychainDataStoring = SecurityKeychainDataStore(),
        encoder: JSONEncoder = JSONEncoder(),
        decoder: JSONDecoder = JSONDecoder()
    ) {
        self.keychain = keychain
        self.encoder = encoder
        self.decoder = decoder
    }

    func save(_ credential: GitHTTPSCredential, for remoteURL: URL) throws {
        guard !credential.username.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            throw GitCredentialStoreError.emptyUsername
        }
        guard !credential.personalAccessToken.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            throw GitCredentialStoreError.emptyPersonalAccessToken
        }

        let key = try credentialKey(for: remoteURL)
        let data: Data
        do {
            data = try encoder.encode(credential)
        } catch {
            throw GitCredentialStoreError.serializationFailed
        }

        do {
            try keychain.save(data, service: key.service, account: key.account)
        } catch {
            throw mapKeychainError(error)
        }
    }

    func credential(for remoteURL: URL) throws -> GitHTTPSCredential? {
        let key = try credentialKey(for: remoteURL)
        let data: Data?
        do {
            data = try keychain.read(service: key.service, account: key.account)
        } catch {
            throw mapKeychainError(error)
        }

        guard let data else { return nil }
        do {
            let credential = try decoder.decode(GitHTTPSCredential.self, from: data)
            guard !credential.username.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
                !credential.personalAccessToken.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
            else {
                throw GitCredentialStoreError.invalidStoredCredential
            }
            return credential
        } catch let error as GitCredentialStoreError {
            throw error
        } catch {
            throw GitCredentialStoreError.invalidStoredCredential
        }
    }

    func deleteCredential(for remoteURL: URL) throws {
        let key = try credentialKey(for: remoteURL)
        do {
            try keychain.delete(service: key.service, account: key.account)
        } catch {
            throw mapKeychainError(error)
        }
    }

    func credentialKey(for remoteURL: URL) throws -> GitCredentialKey {
        GitCredentialKey(service: Self.keychainService, account: "v1:\(try Self.normalizedRemoteURL(remoteURL))")
    }

    static func normalizedRemoteURL(_ remoteURL: URL) throws -> String {
        guard var components = URLComponents(url: remoteURL, resolvingAgainstBaseURL: false) else {
            throw GitCredentialStoreError.invalidRemoteURL
        }
        guard components.scheme?.lowercased() == "https" else {
            throw GitCredentialStoreError.unsupportedRemoteScheme
        }
        guard let host = components.host, !host.isEmpty else {
            throw GitCredentialStoreError.missingRemoteHost
        }
        guard components.user == nil,
            components.password == nil,
            components.query == nil,
            components.fragment == nil
        else {
            throw GitCredentialStoreError.invalidRemoteURL
        }

        var path = components.percentEncodedPath
        while path.count > 1 && path.hasSuffix("/") { path.removeLast() }
        if path.lowercased().hasSuffix(".git") { path.removeLast(4) }
        guard !path.isEmpty, path != "/" else {
            throw GitCredentialStoreError.missingRepositoryPath
        }

        components.scheme = "https"
        components.host = host.lowercased()
        components.percentEncodedPath = path
        if components.port == 443 { components.port = nil }

        guard let normalizedURL = components.url?.absoluteString else {
            throw GitCredentialStoreError.invalidRemoteURL
        }
        return normalizedURL
    }

    private func mapKeychainError(_ error: Error) -> GitCredentialStoreError {
        guard let keychainError = error as? KeychainDataStoreError else {
            return .keychain(errSecInternalComponent)
        }
        switch keychainError {
        case .status(let status):
            return .keychain(status)
        case .unexpectedResult:
            return .invalidStoredCredential
        }
    }
}

final class GitAIKeyStore {
    private static let keychainService = "com.tw93.miaoyan.git-ai-credentials"
    private let keychain: KeychainDataStoring

    init(keychain: KeychainDataStoring = SecurityKeychainDataStore()) {
        self.keychain = keychain
    }

    func save(_ apiKey: String, for baseURL: URL) throws {
        let trimmed = apiKey.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { throw GitCredentialStoreError.emptyPersonalAccessToken }
        try keychain.save(Data(trimmed.utf8), service: Self.keychainService, account: try account(for: baseURL))
    }

    func apiKey(for baseURL: URL) throws -> String? {
        guard let data = try keychain.read(service: Self.keychainService, account: try account(for: baseURL)) else { return nil }
        guard let value = String(data: data, encoding: .utf8), !value.isEmpty else {
            throw GitCredentialStoreError.invalidStoredCredential
        }
        return value
    }

    func deleteAPIKey(for baseURL: URL) throws {
        try keychain.delete(service: Self.keychainService, account: try account(for: baseURL))
    }

    func referencesSameKey(_ lhs: URL, _ rhs: URL) throws -> Bool {
        try account(for: lhs) == account(for: rhs)
    }

    private func account(for baseURL: URL) throws -> String {
        guard let endpoint = GitAIConfiguration(baseURL: baseURL, model: "validation", prompt: "validation").chatCompletionsURL else {
            throw GitCredentialStoreError.invalidRemoteURL
        }
        guard var components = URLComponents(url: endpoint, resolvingAgainstBaseURL: false) else {
            throw GitCredentialStoreError.invalidRemoteURL
        }
        components.scheme = components.scheme?.lowercased()
        components.host = components.host?.lowercased()
        if components.port == 443 { components.port = nil }
        guard let normalized = components.url?.absoluteString else {
            throw GitCredentialStoreError.invalidRemoteURL
        }
        return "v1:\(normalized)"
    }
}
