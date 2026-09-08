import CLibGit2

public enum CLibGit2Runtime {
    public static var version: String {
        var major: Int32 = 0
        var minor: Int32 = 0
        var revision: Int32 = 0
        git_libgit2_version(&major, &minor, &revision)
        return "\(major).\(minor).\(revision)"
    }

    public static var features: UInt32 {
        UInt32(bitPattern: git_libgit2_features())
    }

    public static var hasHTTPS: Bool {
        features & GIT_FEATURE_HTTPS.rawValue != 0
    }

    public static var hasSSH: Bool {
        features & GIT_FEATURE_SSH.rawValue != 0
    }
}
