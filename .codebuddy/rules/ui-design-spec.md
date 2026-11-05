# UI 设计规范（项目规则 · 指针）

**正文唯一真源**：`docs/EV同步器UI设计规范-v1.md`。
按总纲 §3.5a「正文只写一份」，此处**不重复正文**，只留指针与最低限度的硬约束。
适用：`apk/src/com/application/watch/classschedule/` 下所有页面与组件，含以后新增页面。

## 改任一页面前必读正文，并遵守

1. 颜色 / 字号 / 行高 / 间距 / 圆角 / 热区**全部从 `Ui.java` token 取**；新代码禁止写死颜色（`0xFF...` / `Color.parseColor`）、字号、圆角、任意 `setElevation`。
2. 三个强制工厂（不只是审美，是已修 P0 的防回归线）：页面入口 `Ui.screen()`、输入框唯一入口 `Ui.editText()`、onResume 重建只走 `Ui.needRecreate()`。
3. 中文标题用 `Ui.textMedium*`，不用 `bold`；图标走 Lucide 线性 vector，禁止 emoji / Unicode 符号（总纲 §3.14、本仓 `.trae/rules` Icon Policy）。
4. 新增 token 必须**先改 `Ui.java` + 更新规范 §3 表格**，才允许页面使用。
5. 提交前跑 `python3 tools/check-ui-spec.py`；存量 176 处硬编码（`--all` 口径）按「改到才收敛」豁免，脚本只卡新增行。
6. **禁止**新开一份「XX 页全新设计方案」而不回流规范正文 —— 那是风格漂移的源头；新方案只能作为规范附录或修订，并更新 §10 变更日志。
7. 不引入第三方 UI 库（Compose / MDC-Android / XUI / QMUI_Android），理由见规范 §1。

## 验收

真机浅色 / 深色各一屏、中文输入法弹收不黑屏、主题切换不连环重建、44dp 热区；截图归档 `apk/shots/`。
详见规范 §7。
