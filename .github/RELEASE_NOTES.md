# V4.2.9 Zinogre 🧩

1. 修复 macOS 侧栏重命名控件丢失提交动作的问题，文件夹名称现在会可靠地写入磁盘并在重启后保留
2. 缩短 macOS 文件夹重命名输入框并增加右侧间距，使侧栏编辑状态更清晰
3. Android 回归测试现已覆盖带 emoji 的文件夹和笔记名称，包括 push、重启、fetch 与可恢复 checkout

---

1. Fixed a macOS sidebar rename control issue that dropped its commit action, so folder names now persist on disk and survive restart
2. Shortened the macOS folder rename field and added trailing space for a cleaner sidebar editing state
3. Android regression coverage now exercises emoji folder and note names across push, restart, fetch, and recoverable checkout
