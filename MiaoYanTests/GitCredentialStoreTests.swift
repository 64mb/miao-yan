import Foundation
import XCTest

@testable import MiaoYan

final class GitCredentialStoreTests: XCTestCase {
    func testEquivalentRemoteURLsProduceSameStableKey() throws {
        let store = GitCredentialStore(keychain: MemoryKeychainDataStore())
        let first = try store.credentialKey(
            for: try XCTUnwrap(URL(string: "HTTPS://GitHub.COM:443/tw93/MiaoYan.git/"))
        )
        let second = try store.credentialKey(
            for: try XCTUnwrap(URL(string: "https://github.com/tw93/MiaoYan"))
        )

        XCTAssertEqual(first, second)
        XCTAssertEqual(first.account, "v1:https://github.com/tw93/MiaoYan")
    }

    func testSaveReadReplaceAndDeleteCredential() throws {
        let keychain = MemoryKeychainDataStore()
        let store = GitCredentialStore(keychain: keychain)
        let remote = try XCTUnwrap(URL(string: "https://git.example.com/team/repo.git"))
        let first = GitHTTPSCredential(username: "octocat", personalAccessToken: "first-secret")
        let replacement = GitHTTPSCredential(username: "hubot", personalAccessToken: "second-secret")

        XCTAssertNil(try store.credential(for: remote))
        try store.save(first, for: remote)
        XCTAssertEqual(try store.credential(for: remote), first)
        try store.save(replacement, for: remote)
        XCTAssertEqual(try store.credential(for: remote), replacement)
        try store.deleteCredential(for: remote)
        XCTAssertNil(try store.credential(for: remote))
    }

    func testCredentialDescriptionRedactsPersonalAccessToken() {
        let credential = GitHTTPSCredential(username: "octocat", personalAccessToken: "never-print-this")
        XCTAssertTrue(credential.description.contains("<redacted>"))
        XCTAssertFalse(credential.description.contains("never-print-this"))
        XCTAssertFalse(credential.debugDescription.contains("never-print-this"))
        XCTAssertFalse(credential.customMirror.children.compactMap { $0.value as? String }.contains("never-print-this"))
    }

    func testRejectsUnsafeOrIncompleteCredentials() throws {
        let keychain = MemoryKeychainDataStore()
        let store = GitCredentialStore(keychain: keychain)
        let remote = try XCTUnwrap(URL(string: "https://git.example.com/team/repo"))

        XCTAssertThrowsError(try store.credential(for: try XCTUnwrap(URL(string: "http://git.example.com/team/repo"))))
        XCTAssertThrowsError(try store.credential(for: try XCTUnwrap(URL(string: "https://token@git.example.com/team/repo"))))
        XCTAssertThrowsError(try store.credential(for: try XCTUnwrap(URL(string: "https://git.example.com/team/repo?token=secret"))))
        XCTAssertThrowsError(
            try store.save(GitHTTPSCredential(username: " ", personalAccessToken: "token"), for: remote)
        )
        XCTAssertThrowsError(
            try store.save(GitHTTPSCredential(username: "octocat", personalAccessToken: "\n"), for: remote)
        )
        XCTAssertTrue(keychain.storage.isEmpty)
    }

    func testAIKeyIsStoredSeparatelyByEndpoint() throws {
        let keychain = MemoryKeychainDataStore()
        let store = GitAIKeyStore(keychain: keychain)
        let endpoint = try XCTUnwrap(URL(string: "https://api.deepseek.com"))
        XCTAssertNil(try store.apiKey(for: endpoint))
        try store.save("ai-secret", for: endpoint)
        XCTAssertEqual(try store.apiKey(for: endpoint), "ai-secret")
        XCTAssertEqual(keychain.storage.count, 1)
        XCTAssertFalse(keychain.storage.values.contains(Data("octocat".utf8)))
        try store.deleteAPIKey(for: endpoint)
        XCTAssertNil(try store.apiKey(for: endpoint))
    }

    func testAIKeyIdentityNormalizesHostButPreservesCaseSensitivePath() throws {
        let store = GitAIKeyStore(keychain: MemoryKeychainDataStore())
        XCTAssertTrue(
            try store.referencesSameKey(
                XCTUnwrap(URL(string: "HTTPS://MODELS.EXAMPLE.COM:443/TenantA/v1")),
                XCTUnwrap(URL(string: "https://models.example.com/TenantA/v1/"))
            )
        )
        XCTAssertFalse(
            try store.referencesSameKey(
                XCTUnwrap(URL(string: "https://models.example.com/TenantA/v1")),
                XCTUnwrap(URL(string: "https://models.example.com/tenanta/v1"))
            )
        )
    }
}

private final class MemoryKeychainDataStore: KeychainDataStoring {
    struct Key: Hashable {
        let service: String
        let account: String
    }

    var storage: [Key: Data] = [:]

    func save(_ data: Data, service: String, account: String) throws {
        storage[Key(service: service, account: account)] = data
    }

    func read(service: String, account: String) throws -> Data? {
        storage[Key(service: service, account: account)]
    }

    func delete(service: String, account: String) throws {
        storage.removeValue(forKey: Key(service: service, account: account))
    }
}
