 # EvNotifier 运行「卡住 / 转圈」问题分析

> 定位：EvNotifier（macOS 菜单栏通知工具，`app-auth/tools/ev-notifier/ev_notifier.py`，LaunchAgent `com.evnotifier.agent`）。
> 现象：运行时卡死 / 出现转圈（macOS 彩虹圈 / 面板一直 busy / 语音一直播），本文按「嫌疑度从高到低」给出每个根因的代码依据、为什么会导致卡 / 转圈，以及排查与修复建议。
> 依据版本：代码直接来源 `ev_notifier.py`（阅读时间 2026-10-01），行号以当前文件为准。

---

## 一、运行架构（先看图再找病）

```
用户开面板 / 收到消息
   │
   ├─ AppKit 主线程（rumps 菜单 + DashboardWindow）
   │     _refresh_content() → _build_current_html()    ←── 主线程同步执行
   │     └─> _is_logged_in() 首次会跑 security 子进程
   │     └─> 全量拼 HTML（消息/订单/激活/访客…）→ base64 data URL → setMainFrameURL_
   │
   ├─ redis 订阅线程（redis_loop，pubsub.listen()，socket_timeout=60）
   │     handle_message() / record_message()
   │     └─> notify_macos()  → terminal-notifier（同步，timeout=5）或 osascript（已异步）
   │
   ├─ 语音线程 _voice_worker（单队列串行）
   │     _edge_tts_speak：asyncio.run 合成（≤15s，网络）→ afplay（≤30s）
   │
   ├─ 看门狗线程 _watchdog_loop（60s 周期）
   ├─ 稳态对账线程 _periodic_sync_loop（300s 周期）
   └─ 落盘线程 _message_writer_loop（合并写盘）
```

关键判断：**“卡死 / 转圈”的本质是「有线程在等」，而哪种转圈取决于等在哪条线程上。**

| 表现 | 最可能卡在哪 |
|---|---|
| 整窗/菜单栏都转彩虹圈，点了没反应 | AppKit 主线程被 `_refresh_content` / `_build_current_html` 同步重活堵住 |
| 面板打不开 / 白屏 / 一直 loading | WebView base64 data URL 过大加载失败 |
| 声音一直说、停不下来 | 语音线程队列积压（edge-tts 慢） |
| 消息成波次「突然一下全到」 | redis 订阅线程被同步副进程（terminal-notifier）堵住 |
| 点了没任何反应但通知栏说“已在运行” | PID 锁误判 / 重复实例静默退出 |

---

## 二、根因 A：AppKit 主线程被面板渲染同步堵死（第一大嫌疑）

| 位置 | 代码依据 |
|---|---|
| 面板刷新 | `_refresh_content()`（L6968）→ `_build_current_html()`（L7030）→ `setMainFrameURL_(data URL)`（L6982/L7003） |
| 登录门禁 | `_is_logged_in()` 首次会调 `_keychain_get_token()` → `security` 子进程 `timeout=8`（L215） |
| 渲染上限 | `MSG_RENDER_LIMIT=80` 注释：base64 data URL 过大 → WebKit 加载失败 = 面板打不开（L103+） |

**为什么卡 / 转圈：**

1. `_refresh_content()` 是在 AppKit 主线程（rumps / NSWindow）的栈里走的。它一次把**当前页整个 HTML** 在内存里全量拼出来再 base64，然后让 WebView 整页换 data URL。
2. 命中未登录门禁时，`_is_logged_in()` 是同步等一个 `security` 子进程（最多 8s）——**通知中心、菜单栏、窗口主线程全部一起等**（子进程挂起=整窗转圈）。
3. 只要收到一条新消息就会触发刷新（`_refresh_panel`），消息多、渲染重时，主线程长时间被占满 → 菜单栏图标转圈、窗口僵住。
4. 页面（`messages/visitors/devices…`）会把累计的访问/订单数据一起渲染；`page_visit` 是高频事件，访客页数据滚雪球。

**判别：**
```
# 最后一条此类日志后面伴随「长时间无反应」即可坐实
grep -n "HTML built\|Loading HTML\|setMainFrameURL with data URL" ~/.ev_debug.log
```

**建议修复：**
- 把 `_build_current_html` / `_refresh_content` 从主线程挪到 worker 线程，只把最后产出的 HTML 回投到主线程 `setMainFrameURL_`。
- 面板打开时**先渲染骨架**，数据内容用 `ev://` 协议按 tab 懒加载，别全量一次拼好。
- `_is_logged_in()` 自检结果缓存到内存（已有 `_device_token_probed` 标志），避免每次刷新都起 `security` 子进程。

---

## 三、根因 B：语音线程队列积压（“一直播 / 一直在忙”）

| 位置 | 代码依据 |
|---|---|
| 语音 worker | `_voice_worker()`（L2144）：单 `queue.Queue()` 串行消费 |
| 合成 | `_edge_tts_speak`：`asyncio.run` 合成，`EDGE_TTS_TIMEOUT=15`（L2097/L2111），失败降级 `_say_fallback` |
| 播放 | `afplay` `timeout=30`（L2121） |

**为什么表现为“卡 / 转圈”：**
- `_voice_worker` 是**单线程串行**：每条的耗时 = 合成（网络，最多 15s）+ 播放（最多 30s）。
- `page_visit` 等高频事件会 `enqueue_voice`（`enqueue_voice` L2160）。一旦短时间来一波访问，队列里的语音排长队，`edge-tts` 网络慢时每条都要等满超时再降级 `say`，**听感上就是“一直在转、一直说、停不下来”**。
- 虽然它在 daemon 线程、不会冻结 UI，但会持续占用 CPU / 输出音频，用户感知为“没卡死但一直在转”。

**建议修复：**
- 给语音加**去重 / 合并**：同一批 page_visit 只播摘要（如“新增 5 条访问”），或按时间窗合流。
- 对高频类型（page_visit）根本不入语音队列，只弹窗。
- 给 `_voice_queue` 设上限并丢最旧，避免无限积压。

---

## 四、根因 C：redis 订阅线程被同步副进程堵住（消息延迟 / 卡）

| 位置 | 代码依据 |
|---|---|
| 订阅循环 | `redis_loop()`（L2890）：`for message in pubsub.listen()` 里同步 `handle_message` / `record_message` |
| 弹窗 | `notify_macos()` 里 `subprocess.run([terminal_notifier,…], timeout=5)`（L2260，**同步**） |
| 超时保护 | `socket_timeout=SOCKET_TIMEOUT(60)` 已解决「黑洞连接永久挂住」（L2925） |

**为什么“卡 / 转圈”：**
- 虽然 osascript 已改成异步（L2306 注释：曾挂满 5s 把收消息线程整段堵死），但 **`terminal-notifier` 仍是同步**跑的。通知中心忙时它会挂满 5s，而它跑在 redis 订阅线程上——**这一个 5s 期间整条 `pubsub.listen()` 都不消费** → 消息批量积压，等它恢复后“突然全冒出来”，看起来就是“卡住又好了”。

**建议修复：** 把 `terminal-notifier` 也包进 `threading.Thread`（对 `notify_macos` 整段子进程转发，或至少对 run 子进程异步），和 osascript 一样用 `_run_and_ignore_timeout`。

---

## 五、根因 D：面板 WebView data URL 过大 → 打不开 / 白屏（像卡死）

| 位置 | 代码依据 |
|---|---|
| 灌页面 | `setMainFrameURL_("data:text/html;base64," + b64)`（L6982/L7003） |
| 渲染上限 | `MSG_RENDER_LIMIT=80`：**“base64 data URL 过大 → WebKit 加载失败 = 面板打不开”**（注释明说） |

**为什么“转圈 / 打不开”：**
- WebKit 对超长 data URL 有硬上限。消息里带 base64 大图（图片类 payload）或历史数据滚大后，`data:text/html;base64,…` 整串超限，加载直接失败——面板停在“转圈/空白”，看起来就是没响应。
- 注释里 `MSG_RENDER_LIMIT` 与渲染上限是**两套权衡**：存储无上限但渲染必须限，否则必挂。

**建议修复：** 把大 HTML 写成 `~/.ev_dashboard.html`（已有该落盘路径 L4685），用 `loadFileURL` 加载文件而非 `setMainFrameURL_` 灌超长 data URL；同时对图片类 payload 降采样 / 转外链。

---

## 六、根因 E：重复实例被 PID 锁静默退出（“点了没反应”）

| 位置 | 代码依据 |
|---|---|
| PID 锁 | `_acquire_pid_lock()`（L7879）：检测旧 PID 存活即 `sys.exit(0)` |
| 提示 | `notify_macos("Ev Notifier 已在运行", …)`（L7916） |
| 移交 | `handover_to_launchd()` 后 `sys.exit(0)`（L7927） |

**为什么“像没启动 / 卡住”：** 双击 App 或再跑一遍脚本时，发现旧 PID 在跑就**静默退出**，只发一条系统通知；用户看不到自己的窗口，又以为点了没反应 / 又打开了又没起。

**建议修复：** 命中“已在运行”分支时，把已存在的窗口 `makeKeyAndOrderFront_`（前置已有实例的面板），而不是只弹通知。

---

## 七、排查命令（按顺序）

```bash
# 1. 进程是否在、是不是我们自己脚本
launchctl list | grep evnotifier
ps aux | grep ev_notifier

# 2. 重启后看是否有多个实例 / 频繁 restart（先 X 掉再拉起看干净日志）
killall ev_notifier; sleep 1; launchctl kickstart -k gui/$(id -u)/com.evnotifier.agent

# 3. 主线程 / 面板渲染是否卡
tail -n 200 ~/.ev_debug.log | grep -n "HTML built\|setMainFrameURL\|panel\|security"

# 4. 语音 / 队列是否积压
tail -n 100 ~/.ev_debug.log | grep -n "voice\|fallback\|edge-tts\|afplay"

# 5. 是否是死连接 / 补拉风暴（看 watchdog / RECOVERY / retry）
tail -n 200 ~/.ev_debug.log | grep -n "watchdog\|RECOVERY\|retry\|Periodic"

# 6. 看 CPU/线程：某线程常年在 runloop 里转就是被堵
sample <ev_notifier_pid> 3 > /tmp/ev_sample.txt
grep -n "thread=.*[0-9]" /tmp/ev_sample.txt | head   # 数一下正在跑/阻塞的线程
```

---

## 八、修复优先级建议

| 优先级 | 修复 | 针对 |
|---|---|---|
| P0 | 面板渲染移出主线程 + 文件方式加载 HTML（不做超长 data URL） | 根因 A / D |
| P0 | `_is_logged_in()` 自检结果缓存，不起子进程 | 根因 A |
| P1 | `notify_macos` 的 `terminal-notifier` 也异步化 | 根因 C |
| P1 | 语音队列限长 + 高频访问合并播报 | 根因 B |
| P2 | “已在运行”时前置已有面板而不是静默退出 | 根因 E |

---

## 九、与其它文档 / 记忆的关联

- 运维口径见 `docs/apk-tracking-telemetry-spec.md`（消息投递 = `notify.pushNotification` → EvNotifier → Redis stream，收不到先查 LaunchAgent 存活 + Upstash 可达性）。
- 「消息投递」面板的“卡住”是**投递侧**概念（`message_delivery.status='pending'`，有“补发卡住的” `retry-stuck` 按钮，见 `docs/apk-ui-and-notify-analysis.md`），与本文的**本进程转圈/卡死**是两层：本文讲的是 EvNotifier 进程本身跑不动，别混为一谈。
- 启动自检：`startup_notify_selftest()`（L2311）默认发一条弹窗+一句语音，若它每次都触发“语音一直说”，也是根因 B 的表现之一。

---

## 十、实测复现证据（2026-10-01 00:0X–00:5X，用户提供 `~/.ev_debug.log` + `ps`）

**坐实：根因 A + D 是本次“卡住 / 转圈”的真凶；根因 B 排除。**

1. **面板 HTML / data URL 尺寸超标（根因 D）**
   ```
   00:07:43  HTML built, length=1217966    setMainFrameURL len=1632494   ← 1.2MB HTML / 1.6MB base64
   ```
   常规也频繁出现 `211558` → `296934`（211KB / 297KB）。1.6MB 已非常接近 WebKit 超长 `data:` URL 硬上限 → 面板打不开/白屏，看起来像“卡死”。

2. **主线程同步渲染，每次卡 2 秒多（根因 A）**
   ```
   00:45:37.494 HTML built ── 00:45:39.860 setMainFrameURL   ← 中间隔约 2.4s，全在主线程
   00:50:19.417  HTML built ── 00:50:21.120  setMainFrameURL  ← 也约 2s
   ```
   `_refresh_content()` 用 `setMainFrameURL_` 整体重灌 data URL，构建与跳转都阻塞 AppKit 主线程 → 面板/菜单栏僵 2 秒 + 转圈。刷新频繁（几分钟一次），主线程反复被占。

3. **CPU 爬升、进程常驻 R（根因 A 后果）**
   ```
   ps 采样三次：66406  2.5% R → 1.7% R → 6.1% R   累计 CPU 0:25 → 0:27
   ```
   “转圈”即此类 busy loop 的表现：反复拼大 HTML + WebKit 解析超长 data URL。

4. **进程反复重启（KeepAlive 拉起）**
   ```
   日志 PID：33869(00:05) → 52384(00:18) → 64557(00:30) → 66406(00:40+)
   ```
   PID 约 10 分钟换一次，早期几次应是被卡死/杀/重启（plist `KeepAlive=true` 会立刻拉起）；最后一次 66406 稳定在跑但 CPU 持续爬升。

5. **排除根因 B（语音）**：全量日志没有任何 `voice` / `fallback` / edge-tts / afplay 行 → 语音线程没有积压，不是本次现象。
6. **根因 C（terminal-notifier 同步）**：日志未见明显消息波次延迟证据，处于次要地位，但建议仍按 P1 异步化预防。

> 结论收敛：**先修根因 A/D（面板渲染移出主线程 + 用 `loadFileURL` 读 `~/.ev_dashboard.html` 代替超长 data URL）**，即可消除“转圈 / 卡死 / 面板打不开”。

---

## 十一、修复实现与落地

### 1. 改动目标（一处核心）
文件：`app-auth/tools/ev-notifier/ev_notifier.py`（`DashboardWindow` 面板）

| 改动 | 原来 | 改为 |
|---|---|---|
| 渲染线程 | `_refresh_content()` 在主线程同步 `base64` + `setMainFrameURL_` | 后台线程 `_build_and_apply()` 构建 HTML 并落盘，主线程只 `setMainFrameURL_("file://~/.ev_dashboard.html")` |
| WebKit 灌页 | 超长 `data:` URL（到 1.6MB） | 本地文件 URL（无超长上限） |
| 竞态保护 | 无 | 递增代号 `_refresh_gen`，只应用最新一次构建，丢弃过期构建 |
| 参数化 | `_build_current_html()` 固定读 `self._current_page` | `_build_current_html(page=None)`，后台线程用快照 `page` 构建，避免切 tab 时读到被改的当前页 |

### 2. 关键新代码（插入到 `_refresh_content` 一带）
```python
refresh_gen = 0
refresh_lock = threading.Lock()
DASHBOARD_HTML_FILE = os.path.expanduser("~/.ev_dashboard.html")

def _refresh_content(self):
    ...
    if _HAS_WEBKIT and self._webview:
        page = self._current_page
        threading.Thread(target=self._build_and_apply, args=(page,), daemon=True).start()
    ...

def _build_and_apply(self, page):
    with self._refresh_lock:
        self._refresh_gen += 1
        gen = self._refresh_gen
    html = self._build_current_html(page)
    with self._refresh_lock:
        if gen != self._refresh_gen:      # 过期构建：切过页/又刷过，直接丢弃
            return
    with open(self.DASHBOARD_HTML_FILE, "w") as f:
        f.write(html)
    from PyObjCTools import AppHelper
    AppHelper.callLater(0, lambda u="file://" + self.DASHBOARD_HTML_FILE: self._apply_frame_url(u))

def _apply_frame_url(self, file_url):
    if self._webview is not None:
        self._webview.setMainFrameURL_(file_url)
```

`_build_current_html` 处：
```python
def _build_current_html(self, page=None):
    ...
    page = page or self._current_page
```

### 3. 本仓库内已备好一键补丁脚本
`_patch_evnotifier.py`（工作区根目录）已按上面的设计写好，内置 `assert` 精确匹配 + 只改一次。在**你自己能写的终端**里执行即可（此沙箱对 `app-auth` 目录是只读，无法替你落盘）：

```bash
cd /Users/Banner/Documents/guomengtao/ev-schedule-android
python3 _patch_evnotifier.py        # 输出 PATCHED OK 即成功；若不为 1 处匹配会报错并不写盘
python3 -m py_compile /Users/Banner/Documents/guomengtao/app-auth/tools/ev-notifier/ev_notifier.py && echo COMPILE_OK
```

> 说明：脚本绝对路径指向 `app-auth` 的真实文件，你在自己的终端跑会直接改到它；跑完 `py_compile` 通过即可，然后重启 EvNotifier 验证（`killall ev_notifier` + 重新打开，或 `launchctl kickstart -k gui/$(id -u)/com.evnotifier.agent`）。

### 4. 验证盯这些日志
```bash
tail -n 60 ~/.ev_debug.log | grep -n "HTML built\|setMainFrameURL file URL\|stale build\|write dashboard"
```
- 出现 `HTML built` 后应很快跟进 `setMainFrameURL file URL`（不再是 `with data URL`）；
- 快速切 tab / 高频刷新时出现少量 `stale build #N dropped` 属正常（说明过期构建被丢弃，不再反复重灌主线程）；
- 主窗口、菜单栏不再出现长时间转圈。