# 周视图小插件「反复坏」根因分析与加固方案

> 2026-09-29 · 真机：荣耀 7X（EMUI 8 / Android 8.0，HwWidgetHost + EMUI 桌面）
> 结论先行：坏了两次，是**两个不同根因**，但共同的深层原因是——**插件的真实渲染发生在 EMUI 桌面进程里，我们自己的所有验证手段全部照不到那里**。修复必须在真机桌面上做「渲染回归验证」，否则永远不知道修没修好。

---

## 一、故障时间线与证据

### 第一次坏（09-29 16:12，v0.5.84 时代）
- 现象：添加插件报「加载窗口小工具时出现问题」（EMUI 桌面 toast），槽位空白。
- 证据：`adb logcat` 抓到 `AppWidgetHostView` 的 InflateException 堆栈（`LayoutInflater.createViewFromTag → PhoneLayoutInflater.onCreateView`）。
- 根因：`res/layout/widget_week.xml` 里用了 **`<Space>`**。`Space` 不在 RemoteViews 允许的控件白名单内（只允许 LinearLayout / FrameLayout / RelativeLayout / GridLayout / TextView / Button / ImageView / ProgressBar / Chronometer / AnalogClock / ViewFlipper / ListView / GridView / StackView / TextClock 等），EMUI 桌面 inflate 直接抛异常。
- 修复：`<Space>` 换成等宽 `<TextView>`（commit 7930013）。

### 第二次坏（09-29 17:44 起，v0.5.85–v0.5.89）
- 现象：**同样的 toast 又出现**，槽位空白。
- 证据链（本次通过截图 + 分阶段二分取得）：
  1. `dumpsys appwidget` 显示周插件已正常绑定（`views=RemoteViews@…`）→ 绑定层没问题；
  2. `adb logcat` 再无 InflateException——**EMUI 桌面把异常吞了**（同一 RemoteViews 反复失败不再打日志，第一次的堆栈也随缓冲轮转丢失）；
  3. 每次 `refreshAll` 时桌面进程爆发 `HwNotchUtils: setIconForView iconId is not found (0x00000000)`（伴生噪声，非致命）；
  4. 拉取**设备上实际安装的 APK** 反编译确认布局已是修复版 → 排除"发版没发到"；
  5. **三轮二分实验**（见下文）锁定：`rv.setInt(id, "setGravity", Gravity.CENTER)` 与 `rv.setTextViewTextSize(...)` 这两类 RemoteViews 调用在 EMUI 桌面 apply 时失败。

### 二分实验记录（可复用的定位手法）

| 实验 | build() 内容 | 结果 |
|---|---|---|
| bisect1 (v0.5.90) | 返回今日插件布局（widget_today + Today 的 actions） | ✅ 渲染成功 → **绑定层/ provider 元数据无辜** |
| bisect2 (v0.5.91) | 周布局 XML + 仅 1 个 setOnClickPendingIntent | ✅ 渲染成功 → **widget_week.xml 本身没问题** |
| bisect3 (v0.5.92) | 周布局 + 全部内容 actions（但去掉课程分支的 setTextSize，**漏了假期分支一处**） | ❌ 失败 |
| bisectA (v0.5.93) | 周布局 + 表头/时间/脚注/日期 actions，**跳过全部格子 actions** | ✅ 渲染成功 → **问题锁定在格子 actions** |
| Cycle B (v0.5.96) | 格子 actions 里删除全部 `setInt(id,"setGravity",…)` 与 `setTextTextViewTextSize(...)` | ✅ **完整渲染成功**（课程色块、单字模式、假期列「国庆」全部正常） |

**根因二确认**：`rv.setInt(id, "setGravity", …)` 属于 ReflectionAction（依赖方法上的 `@RemotableViewMethod` 注解），EMUI 8 的桌面 Host 拒绝执行这个反射调用，整个 RemoteViews apply 失败；`setTextViewTextSize`（API 26 才加入 RemoteViews，Today/Next 从未用过）同属高危。**两次故障 = 两个不同的根因，且都藏在「桌面端才执行」的代码里。**

---

## 二、为什么会反复坏（深层原因，6 条）

### 1. 崩溃发生在别人的进程里（根本矛盾）
插件的 XML / RemoteViews 由我们 App 构建生成，但真正 inflate + apply 发生在 **EMUI 桌面进程**。后果：
- `javac` 编译通过 ✔（XML 是运行时 inflate，不参与编译检查）
- App 自身运行零异常 ✔（`onUpdate` / `refreshAll` 正常跑完）
- 我们自己的测试手段**全部绿灯**，但桌面端照样炸。
这是 App 开发里少有的「编译期 + 自进程运行期双重盲区」。

### 2. RemoteViews 的约束是隐性知识
- `<Space>` 不在白名单——写 XML 的人按普通 Android 习惯写，没有任何编译期/lint 提示；
- `setInt(viewId, "setGravity", …)` 这类**反射调用**依赖方法在目标 ROM 上带 `@RemotableViewMethod` 注解——AOSP 有不代表 EMUI 有；
- 这些约束没有写进任何文档/记忆/代码注释，**多会话并行开发时必然有人再踩**。WeekWidgetProvider 本身就是另一个会话引入的功能，交接时没带上这条军规。

### 3. EMUI 桌面吞异常，可观测性极差
- 异常只在**第一次**弹 toast + 打日志，之后同一位置的失败静默；
- toast「加载窗口小工具时出现问题」是唯一用户可见信号，不带任何原因；
- 两次故障的堆栈都没能完整留住（缓冲轮转/吞掉），第一现场难复现。

### 4. 修复未闭环：第一轮没做真机回归
Space 修完后只验证了「编译通过 + 布局标签普查」，**没有在真机桌面上重新渲染验证**。而当时布局里还存在第二颗雷（setGravity/setTextSize 是同一天加的单字模式代码），修了第一颗就宣布修好——第二次自然「又坏了」。用户的观感就是：修了还坏，不可信。

### 5. 无法用 adb 自动化验证渲染
- `APPWIDGET_UPDATE` 是受保护广播，shell 发不出去（SecurityException）；
- 添加插件要在桌面手工长按拖拽，脚本做不了；
- 结果：回归验证依赖人肉，而人肉验证恰恰是最容易被跳过的一步。

### 6. 同构代码有「幸存者偏差」
Today/Next 两个插件一直正常，给了「插件体系没问题」的错觉。实际上它们只是**恰好没用过出事的那几个调用**（setGravity / setTextSize / Space）。凡是新会话给插件加新能力，都在无感知地突破 EMUI 的边界。

---

## 三、加固方案（按优先级）

### P0 · 已完成：根因修复
- 格子 actions 中删除全部 `setInt(id,"setGravity",…)` 与 `setTextTextViewTextSize(...)`（commit 本次）。布局 XML 的 `android:gravity="center"` 本来就居中，这两个调用是冗余的——**删掉即修复**，单字模式在插件上暂用原字号（App 首页的 14sp 放大不受影响，那是普通 View）。
- v0.5.96 已在真机桌面完整渲染验证（截图：课程色块 + 单字 + 国庆列 + 日期带假期名）。

### P1 · build.sh 加插件布局白名单 lint（防再犯）
```bash
# 插件布局只准用 RemoteViews 白名单控件（EMUI 桌面会拒载其它标签）
ALLOWED='LinearLayout|FrameLayout|RelativeLayout|GridLayout|TextView|Button|ImageView|ProgressBar|Chronometer|AnalogClock|ViewFlipper|ListView|GridView|StackView|TextClock|include|merge'
for f in res/layout/widget_*.xml; do
  BAD=$(grep -oE '<[A-Z][A-Za-z]+' "$f" | sed 's/<//' | grep -vE "^($ALLOWED)$")
  if [ -n "$BAD" ]; then echo "❌ $f 含 RemoteViews 非白名单控件: $BAD"; exit 1; fi
done
```
同时 grep 源码禁止 `setGravity`/`setTextTextViewTextSize` 进 widget provider（写死这两个名字）。

### P2 · 真机渲染回归 SOP（修复必做，1 分钟）
1. `adb install -r` 新版；
2. 打开 App 触发一次 `refreshAll`（或设置页切任意插件相关开关）；
3. `adb shell input keyevent KEYCODE_HOME` + `input swipe` 滑到插件页；
4. `adb shell screencap` 截图人眼确认（或比对 toast 文案是否出现）。
> ⚠️ EMUI 的坑：覆盖安装后**已绑定的坏插件可能不自愈**，需删除重新添加一次。

### P3 · 知识固化（已完成：写进项目记忆 + 本文档）
- 插件布局白名单、禁用 setGravity/setTextSize 反射调用、EMUI 吞异常的行为——全部写入 `.codebuddy/memory`，任何会话改 widget 前必读。

---

## 四、遗留说明

- 插件上的单字模式：字号与多字相同（9sp），无法用 RemoteViews 可靠放大（EMUI 拒绝 setTextSize）；若一定要大字，需要双套格子 id 的布局方案，成本高暂不做。App 首页周视图的单字 14sp 放大正常。
- `HwNotchUtils setIconForView iconId 0x00000000` 刷屏为伴生噪声（三个插件都会触发，Today/Next 照常渲染），不处理。
- 相关提交：Space 修复 `7930013`、单字/假期 `fb55c29`、根因修复 v0.5.96（本文档同 commit）。
