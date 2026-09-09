# V4.2.8 Tigrex 🐯

1. macOS 文件夹重命名现在会实际移动磁盘上的目录，因此重启应用后名称不会恢复
2. 重命名会同步更新嵌套文件夹、笔记路径和侧栏状态，Git Sync 可正确提交目录删除与新增
3. 新增端到端回归测试，覆盖 emoji 文件夹名、应用重启以及 Git push 后的全新 clone

---

1. macOS folder renaming now moves the directory on disk, so the original name no longer returns after an app restart
2. Renames update nested folders, note paths, and sidebar state so Git Sync commits the matching deletion and addition
3. End-to-end regression coverage now verifies emoji folder names, app restart discovery, and a fresh clone after Git push
