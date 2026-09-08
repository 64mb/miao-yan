# V4.2.6 Astalos 🧩

1. macOS Git 同步现在使用跨平台 NFC 路径，文件夹名与笔记名中的 emoji 和 Unicode 字符可被完整保留
2. 旧版 macOS 创建的分解 Unicode 索引项会在下次同步时自动修复，无需手动重命名文件
3. Android 会拦截规范等价的 Unicode 路径冲突，避免同步产生重复或歧义文件

---

1. macOS Git Sync now uses cross-platform NFC paths, preserving emoji and Unicode characters in folder and note names
2. Decomposed Unicode index entries created by older macOS builds are repaired automatically during the next sync without manual renaming
3. Android rejects canonically equivalent Unicode path collisions before they can create duplicate or ambiguous synced files
