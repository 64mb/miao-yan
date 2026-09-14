# V4.2.15 Rathalos 🛡️

1. 修复 macOS 在相邻笔记之间切换时继续显示上一条笔记内容的问题，包括带组合 Unicode 字符的文件名
2. 防止仅导航时重复写入未更改的笔记，避免触发无意义的文件监听刷新与列表重排
3. 替换编辑器缓冲区后立即刷新可见的 TextKit 区域，并新增针对首两条 Unicode 笔记切换的回归测试

---

1. Fixed macOS continuing to show the previous note when switching between adjacent notes, including filenames with combining Unicode characters
2. Prevented unchanged notes from being rewritten during navigation, avoiding unnecessary file-watcher refreshes and list reordering
3. Refreshed the visible TextKit region immediately after replacing the editor buffer and added a regression test for switching the first two Unicode notes
