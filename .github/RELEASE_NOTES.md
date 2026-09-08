# V4.2.5 Barioth ⚡️

1. Android 置顶标记改用与操作菜单一致的原生图钉，并在副标题末尾精确对齐
2. macOS Git 同步现在忽略任意层级的 `.DS_Store`，不再因传入的 Finder 元数据阻塞
3. 已被 Git 跟踪的 Finder 元数据会在同步后安全移出索引，同时保留本地文件

---

1. Android pinned notes now use the same native push pin as the action menu, precisely aligned at the end of the subtitle
2. macOS Git Sync now ignores `.DS_Store` at any library depth instead of blocking on incoming Finder metadata
3. Previously tracked Finder metadata is safely removed from the Git index after sync while its local file remains available
