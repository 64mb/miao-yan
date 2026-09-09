# V4.2.11 Lagiacrus 🍝

1. 修复 macOS 在切换文件夹时重复显示首条笔记的问题
2. 统一 APFS 文件名的 Unicode 规范形式，避免同一笔记因组合字符差异被重复载入
3. 新增包含西里尔字符和组合附加符号文件名的项目重扫回归测试

---

1. Fixed macOS repeatedly showing the first note when switching between folders
2. Canonicalized APFS filename URLs so Unicode composition differences cannot load the same note more than once
3. Added project-rescan regression coverage for filenames containing Cyrillic characters and combining marks
