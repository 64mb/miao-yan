# V4.2.7 Glavenus 🛠️

1. macOS 文件夹重命名完成后会可靠退出编辑状态，不再让残留输入框抢占后续点击焦点
2. 重命名输入框宽度限制为 220 点，并保留 12 点右侧间距，长名称也不会贴到侧栏边缘
3. 新增回归测试，覆盖字段编辑器生命周期以及不同侧栏宽度下的重命名布局

---

1. macOS folder renaming now reliably exits editing mode so a stale text field cannot steal focus from later clicks
2. The rename field is capped at 220 points with a 12-point trailing inset, keeping long names clear of the sidebar edge
3. Regression tests now cover field-editor retirement and rename layout across sidebar widths
