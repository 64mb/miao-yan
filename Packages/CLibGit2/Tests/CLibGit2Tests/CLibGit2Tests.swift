import CLibGit2
import CLibGit2Linkage
import XCTest

final class CLibGit2Tests: XCTestCase {
    func testPinnedVersionAndTransportFeatures() {
        XCTAssertEqual(CLibGit2Runtime.version, "1.9.7")
        XCTAssertTrue(CLibGit2Runtime.hasHTTPS)
        XCTAssertFalse(CLibGit2Runtime.hasSSH)
    }

    func testRuntimeCanInitialize() {
        XCTAssertGreaterThan(git_libgit2_init(), 0)
        XCTAssertGreaterThanOrEqual(git_libgit2_shutdown(), 0)
    }
}
