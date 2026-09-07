# JGit carries optional desktop integrations that are unreachable in MiaoYan's Android HTTPS-only
# transport. Android deliberately does not provide these Java SE management and SPNEGO classes.
-dontwarn java.lang.ProcessHandle
-dontwarn java.lang.management.ManagementFactory
-dontwarn javax.management.**
-dontwarn org.ietf.jgss.**
