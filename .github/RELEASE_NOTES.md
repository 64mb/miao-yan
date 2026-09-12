# V4.2.12 Barioth 🍝

1. 为启用 Git Sync 的 macOS 资料库加入可同步的 `.Trash/items` 与 `manifest.v1` 回收站
2. 修复删除笔记后 Trash 不会立即出现在侧栏以及内部 `items` 目录错误显示的问题
3. 让恢复、撤销和永久删除同步更新 manifest，并自动清理 payload 已缺失的旧记录
4. 保留普通 macOS 资料库的系统回收站行为，并兼容 Android 传入的文件夹回收站记录

---

1. Added a synchronized `.Trash/items` and `manifest.v1` Trash backend for macOS libraries with Git Sync enabled
2. Fixed Trash not appearing immediately after deleting a note and prevented the internal `items` directory from leaking into the sidebar
3. Kept restore, undo, and permanent delete consistent with the manifest and repaired stale entries whose payloads no longer exist
4. Preserved the system Trash for non-Git libraries and retained Android folder-trash entries
