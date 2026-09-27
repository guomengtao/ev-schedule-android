# M1 PoC：让手环 EV 课程表【主动】发消息到手机

> 日期：2026-09-26
> 目的：验证「手环 → 手机」这条方向到底成不成立 —— 这是聊天功能能否成立的**唯一分水岭**（此前从未被证实过）。
> 改造仓库：`tom/class/class`（EV 课程表，活跃工作副本）
> 产出 rpk：`tom/class/class/dist/com.application.watch.classschedule.debug.1.6.130.rpk`

---

## 一、改了什么

### 1. 新增 `src/data/chat-bridge.js`（共享桥模块）

**这是整个改造的核心。**

原因：interconnect 的 `connect` 实例原本只是 `app.ux` 里 `initSyncReceiver()` 的**局部变量**，所以 EV 只能在「收到手机消息时回包」，**没法自己开口** —— 这就是"手环→手机"做不出来的根因。

桥模块做的事就两件：`register(connect)` 和 `send(text)`。

### 2. `src/app.ux`

| 改动 | 说明 |
|---|---|
| `require("./data/chat-bridge.js")` | 引入桥 |
| `var _syncConnect = null` | 顺带留个全局引用（便于调试） |
| **`initSyncReceiver()` 里 `chatBridge.register(connect)`** | **关键一行**：EV 一启动就把 connect 交给桥 |
| `onmessage` 新增 `action:"chat"` 分支 | 手机发来的聊天消息 → 存收件箱 + 长震动 + 回 `chat_ack` |
| 新增 `vibrateLong()` | `vibrator.start({duration:800, interval:300, count:2})`，失败回落 `vibrate({mode:"long"})` |
| 新增 `syncSendToPhone(text)` | 薄封装，转调 `chatBridge.send(text)` |

### 3. `src/pages/tools/tools.ux`

新增一个「**发消息到手机**」条目（在「课程表管理 V2」下面），点击即调用 `chatBridge.send("来自手环 <时间>")` 并 toast 提示。

这就是 PoC 的**触发点**。

### 4. `src/data/storage-tables.js`

登记新存储键 `ev_chat_inbox`（聊天收件箱，`[from, text, ts]`，最多 50 条）。

---

## 二、为什么用共享模块，不用全局变量

第一版我写的是 `app.ux` 里定义 `syncSendToPhone()`、页面直接调用。构建时报了：

```
🟠 tools.ux:181:16 'syncSendToPhone' is not defined
```

**快应用的 `app.ux` 顶层函数对页面不一定可见**（`app.ux` 和 page 是不同模块作用域）。
插件时代那份 `EV课程表主动发送测试消息到插件.md` 用的就是全局变量方案 —— 那只是提案，从未真正跑过。

改成 **`require` 同一个模块**：模块缓存保证两边拿到同一实例，最稳。

---

## 三、产物校验

```
dist/com.application.watch.classschedule.debug.1.6.130.rpk   897,034 B
aiot build success: 10354ms
```

解包核对，新代码确实在包里：

| 关键字 | 出现在 |
|---|---|
| `chat_ack` | `app.js` |
| `chatBridge` | `app.js` ×3、`pages/tools/tools.js` ×2 |
| `ev_chat_inbox` | `app.js` ×2 |

### 签名没有被换（这点非常关键）

构建日志：

```
privatekeyPath  is  .../tom/class/.temp_class/sign/private.pem
certificatePath is  .../tom/class/.temp_class/sign/certificate.pem
```

且 `.temp_class/sign/certificate.pem` 与 `class/sign/certificate.pem` 字节完全一致：

```
5eb10ba3cc0ee49c41f59b7f62760811fdaf0c2d76390f80c329862028d7fbfa
证书指纹 SHA-256 = 46:6A:1E:83:…:9A:11
```

→ **与 APK 用的同一把密钥。换包不会破坏 APK ↔ rpk 的配对。**

---

## 四、怎么验证（这一步决定要不要继续投入）

```
① 手机上打开 EV 同步器 v0.5.2，完成一次连接（让 EV 的 onCreate 跑起来、connect 注册进桥）
② 手表上：EV 课程表 → 工具页
③ 点「发消息到手机」
④ 看手机端聊天页
```

### 判读表

| 手机端现象 | 结论 | 下一步 |
|---|---|---|
| **出现 `<< RX` + 弹窗 + 提示音，内容是 `{"action":"chat",...}`** | ✅ **上行成立** | 进 M2：做真正的聊天 UI |
| 完全没反应、无回包 | ❌ **上行不成立** | **降级**：只做「下行提醒通道」，不要投入 M3 |
| 手环 toast 提示「connect 未就绪」 | EV 的 `onCreate` 没跑过 | 先让手机连一次，或重启 EV 再试 |

### 顺带验证下行 chat 分支

反过来从**手机聊天页发一条**：

- 手环**长震动** → 说明 `action:"chat"` 分支 + `vibrateLong()` 都生效
- 手机端回包应变成 **`{"ok":true,"action":"chat_ack",...}`** 而不再是 `{ok:false,reason:"no courses"}`

---

## 五、注意事项

1. **先备份课表**：虽然签名同源（通常不会清数据），但换包有风险 —— 建议先用手机端「导出课程表」存一份 JSON。
2. 这是 **debug 包**（`...debug.1.6.130.rpk`），仅用于 PoC。正式发布走你自己的 `npm run release` 流程。
3. 若手环上已装的 EV 提示需要卸载才能装，属正常；重装后课表可能清空，**务必先备份**。

---

## 六、EV 仓库未提交

`tom/class/class` 的改动**故意留在工作区没有 commit** —— 这是你的主产品仓库，改动涉及 `app.ux`（应用入口），应该由你 review 后再决定何时合入。

涉及文件：

```
src/data/chat-bridge.js        （新增）
src/app.ux                     （修改）
src/pages/tools/tools.ux       （修改）
src/data/storage-tables.js     （修改）
```
