# V4.2.10 Gore Magala 🌊

1. 修复 Android 在进程终止后残留 Git 锁文件导致后续同步无法准备本地仓库的问题
2. 恢复过程仅清理应用私有仓库中的过期锁文件，不会修改本地笔记、提交历史、引用或索引
3. 新增 emoji 文件夹重命名、重启、残留锁和真实 JGit 同步准备流程的设备级回归测试

---

1. Fixed Android Git Sync failing to prepare the local repository when stale lock files remain after process termination
2. Recovery removes only stale locks from the app-private repository without changing local notes, commit history, refs, or the index
3. Added device-level regression coverage for emoji folder renames, restart, stale locks, and the real JGit sync preparation path
