# V4.2.17 Rajang ⚡️

1. 修复删除当前选中的根目录笔记后，笔记未显示在废纸篓中的问题
2. 废纸篓重新从磁盘加载笔记，避免继续使用已删除的编辑器对象
3. 新增根目录笔记删除回归测试，覆盖文件移动、废纸篓侧栏和笔记状态
4. Android 版本号与 macOS 统一为 4.2.17，不再显示 prototype 标记

---

1. Fixed deleted root notes missing from Trash when they were selected in the editor
2. Reloaded Trash notes from disk instead of reusing a retired editor object
3. Added regression coverage for the file move, Trash sidebar, and note lifecycle
4. Aligned the Android version with macOS at 4.2.17 and removed the prototype label
