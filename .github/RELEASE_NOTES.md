# V4.2.18 Lagiacrus 🌊

1. 修复 Git 同步资料库中根目录笔记移入废纸篓后，被文件监听器从列表中移除的问题
2. 删除的笔记现在无需重启应用即可在废纸篓中查看和恢复
3. Android 更新器现在按最新发布版本查找带版本号的 APK，不再依赖固定文件名
4. 新增废纸篓回归测试，并在 CI 中运行 Android 测试、模拟器检查和压缩构建

---

1. Fixed the file watcher removing root notes from Trash in Git-synced libraries
2. Deleted notes now remain visible and recoverable in Trash without restarting the app
3. The Android updater now finds the versioned APK from the latest GitHub Release
4. Added Trash regressions and Android unit, emulator, and minified-build CI checks
