# JGit carries optional desktop integrations that are unreachable in MiaoYan's Android HTTPS-only
# transport. Android deliberately does not provide these Java SE management and SPNEGO classes.
-dontwarn java.lang.ProcessHandle
-dontwarn java.lang.management.ManagementFactory
-dontwarn javax.management.**
-dontwarn org.ietf.jgss.**

# JGit creates this bundle with getDeclaredConstructor() and populates its public String fields by
# reflected field name. R8 cannot infer either access and otherwise breaks Git initialization.
-keep class org.eclipse.jgit.internal.JGitText {
    public <init>();
    public java.lang.String *;
}
