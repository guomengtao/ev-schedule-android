# EvNotifier 头部「数据条数 99+」显示逻辑分析与改法

> 定位：EvNotifier（macOS 菜单栏通知工具，`app-auth/tools/ev-notifier/ev_notifier.py`）。
> 现象：菜单栏标题 / 面板侧边栏上未读数据条数超过 99 后只显示成「99+」，而不显示真实条数（比如 124、1245）。
> 依据版本：代码来源 `ev_notifier.py`（阅读时间 2026-10-01），行号以当前文件为准。

---

## 一、一句话结论

「99+」不是数据本身被截断，而是**展示层的一道数字上限（cap）**。真实的未读条数一直都能算出来（函数 `count_unread()` 返回的就是真实整数值），是在拼 HTML / 拼标题时主动写成「超过 99 就显示 99+」。

所以改成显示真实条数（124、1245……）很简单：**把这处「判断 >99 就写 99+」的三元表达式去掉，直接输出 `count_unread()` 的整数值即可。**

这个「99+」限制目前只出现在 **2 个展示位置**，见下文。其余所有统计卡片、角标用的都是真实整数。

---

## 二、未读条数的唯一口径：`count_unread()`

无论哪个位置显示的条数，底层都来自同一个函数（只算 `read == False` 的消息总数）：

```python
def count_unread():
    """I5：唯一未读口径。徽标、导航角标、统计卡全部由它派生。"""
    global _unread_cache
    with _MSG_LOCK:
        if _unread_cache is None:
            _unread_cache = sum(1 for m in load_messages() if not m.get("read", False))
        return _unread_cache
```

- 位置：`ev_notifier.py` L1122 附近。
- 特点：带 `_unread_cache` 内存缓存，`mark_read()` 标记已读时会使缓存失效，因此调用方拿到的永远是真实条数（是 `int`，不是「99+」字符串）。
- 也就是说：**「99+」纯粹发生在调用方拼字符串的那一行，不在数据源头。**

---

## 三、两处「99+」到底在哪里

### 位置 1：菜单栏标题（用户说的「头部的 mac 显示」）

macOS 顶部菜单栏常驻标题由 2 秒一次的定时器 `_update_title` 更新：

```python
@rumps.timer(2)
def _update_title(self, _):
    # I5：徽标由 read 字段派生（内存缓存，消息变更时失效），不再用会话变量
    try:
        unread = count_unread()
    except Exception:
        unread = 0
    if unread > 0:
        self.title = f"Ev {VERSION} ({'99+' if unread > 99 else unread})"
    else:
        self.title = f"Ev {VERSION}"
```

- 位置：`ev_notifier.py` L7509 附近。
- 表现：未读超过 99 时菜单栏标题变成 `Ev vX.Y (99+)`。
- 这就是「头部的 mac 显示」最常见的指代——**macOS 菜单栏顶部的标题数字**。

### 位置 2：面板左侧「消息」导航项的红色角标 badge

侧边栏 HTML 由 `_sidebar_html` 生成：

```python
badge_html = ""
if item["id"] == "messages" and count_unread() > 0:
    badge_html = f'<span class="nav-badge">{"99+" if count_unread() > 99 else count_unread()}</span>'
```

- 位置：`ev_notifier.py` L4905 附近。
- 表现：面板打开后左侧「消息」入口上的红色小圆 badge 在超过 99 时显示「99+」。

> 补充：如果用户说的是面板页头、或者某个 `head/header` 区块里的数字，绝大多数也是走 `count_unread()` 或等效的整型统计，并且**没有做 99+ 上限**。只有上面这两处用了「>99 就 99+」的三元写法。

---

## 四、改成显示真实条数

把两处三元表达式里的「99+ 兜底」去掉，直接输出整数值。

### 改法 1：菜单栏标题

将：

```python
if unread > 0:
    self.title = f"Ev {VERSION} ({'99+' if unread > 99 else unread})"
else:
    self.title = f"Ev {VERSION}"
```

改为（真实条数，含 124、1245 等任意值）：

```python
if unread > 0:
    self.title = f"Ev {VERSION} ({unread})"
else:
    self.title = f"Ev {VERSION}"
```

### 改法 2：侧边栏「消息」角标

将：

```python
badge_html = f'<span class="nav-badge">{"99+" if count_unread() > 99 else count_unread()}</span>'
```

改为：

```python
badge_html = f'<span class="nav-badge">{count_unread()}</span>'
```

### 注意事项（改前想清楚）

| 项目 | 说明 |
|---|---|
| 字数溢出 | 菜单栏是一条很窄的标题，`(1245)` 这种会变宽，但一般仍在菜单栏允许长度内；若担心过长，可只对「菜单栏标题」保留上限，仅放开「面板角标」。两者的阈值/是否上限是独立的，可各改各的。 |
| 刷新频率 | 菜单栏标题每 2 秒由 `_update_title` 重算一次，改成真实条数不会有额外开销。 |
| 性能 | `count_unread()` 走内存缓存，放开上限不会让计算变慢。 |
| 是否影响其它处 | 不改 `count_unread()` 本身，其它统计卡（爱发电、激活、访客等）不受影响，仍显示真实整数。 |

---

## 五、验证方法

1. 改完后重启 EvNotifier（`killall -9 EvNotifier` 后再拉起，或重启 LaunchAgent）。
2. 用应用内的「测试通知」/让后台多推几条未读消息，把未读数量堆到 >99。
3. 观察：
   - 菜单栏标题应出现 `Ev vX.Y (124)`（或更长数字）而不是 `99+`。
   - 打开面板看「消息」角标是否为对应的真实数字。
4. 想恢复「99+」上限，把两行恢复原样即可。

---

## 六、小结

- 数据源头 `count_unread()` 一直返回真实未读条数，「99+」只是 2 个展示位置各自写死的数字上限。
- 需要改的只有两行：`_update_title`（L7516 附近）和 `_sidebar_html`（L4905 附近）。
- 去掉三元表达式里的 `'99+' if ... > 99 else ...` 部分、直接输出整数，即可显示 124、1245 等真实条数。
- 若担心菜单栏空间，可只放开面板角标、菜单栏标题保留上限，两者互相独立。