# V4.2.13 Gore Magala 🦉

1. 修复 macOS 删除笔记后打开 Trash 时列表暂时为空的问题
2. 让侧栏 Trash 立即关联实际持有新删除笔记的 Git 回收站项目
3. 新增回归测试，确保 Git 回收站条目无需重启应用即可进入当前 Trash 视图

---

1. Fixed macOS showing an empty Trash list immediately after deleting a note
2. Made the sidebar Trash immediately reference the Git Trash project that owns the newly deleted note
3. Added regression coverage ensuring Git Trash entries enter the current Trash view without restarting the app
