# 字段级三方合并同步 + 未同步提示按钮 + 呼叫手环反馈 方案

> 规划日期：2026-09-30
> 涉及仓：本仓（ev-schedule-android，安卓同步器）+ 手环仓（tom/class/class，EV 快应用，**需另行征得同意再改**）
> 一句话目标：**不按「整张课程表」定谁为准，按「每条课程、每个字段」三方合并；只有两边改了同一字段且值不同才算冲突。**

---

## 0. 结论速览

| 需求 | 一句话方案 | 是否依赖手环仓改动 |
|---|---|---|
| 课程表栏目上方加「未同步」按钮 | 本地脏标记（dirty）+ 字段级 base 快照，按钮显「已同步 / N 门课未同步」，点击强制同步 | 否（纯 APK 侧） |
| 编辑后立即同步 | `CourseEditActivity.commit()` 后自动触发一次同步；断连则挂起，连上补发 | 否 |
| 按「每条课、每字段」三方合并 | base/local/remote 三方比对 + 5 条合并规则 + 冲突弹窗 | **是**（import 需透传 `id`） |
| 呼叫手环有真实反馈 | EV 侧新增 `call` action，震动后回 `{ok:true,action:"call"}`；APK 侧按 `ok` 字段判定 | **是**（新增 `call` 处理） |

---

## 1. 现状诊断（2026-09-30 代码实测）

### 1.1 数据模型：缺同步元数据

`CourseCache.Course` 目前只有 5 个业务字段，**没有任何同步元数据**：

```java
public String name = "", time = "", teacher = "", location = "";
public int day = -1;
```

缺：`id`（唯一键）、`updated_at`（逻辑版本）、`deleted`（软删除标记）、`device_id`（改动来源）、`base_version`（上次同步版本）。

`ScheduleStore.Schedule` 虽有 `syncedAt`，但那是「整表同步时间」，不是字段级 base。

### 1.2 同步是整表覆盖，无合并

`ScheduleStore.upsertFromWatch()`：

```java
s.courses.clear();
s.courses.addAll(CourseCache.flatten(dayGrouped));   // ← 整表覆盖
```

`HomeActivity.loadProfile()` / `pullSchedule()` / `SyncEngine.pullAndStore()` 都是「拉手环 export → 整表覆盖本地」。**一旦手机也编辑了，两边改的东西互相冲掉，谁后同步谁赢。**

### 1.3 编辑不自动同步

`CourseEditActivity.doCommit()` 只 `ScheduleStore.updateCourses()` 落盘本地，文案提示「需同步到手环才会写回」，**不触发任何上行**。上传到手机的唯一路径是 `TransferActivity` 手动导入（覆盖式）。

### 1.4 呼叫手环的根因（已定位）

- APK `HomeActivity.callBand()` 发 `{"action":"call","text":"请查看手机"}`。
- 手环 `app.ux` 的 `onmessage` 分发里**没有 `call` 分支**，只有：`ping / export / update_settings / chat / list_schedules / get_device_id / activate / import`。
- `call` 落到兜底 `syncHandleImport(connect, parsed)` → `syncExtractCourses` 取不到 `courses` → 回 `{ok:false, reason:"no courses"}`。
- APK 的 `quickSend()` 里 `onReply` **只认「有回包」就显示「已送达 ✓」，不检查 `ok` 字段** → 手机报成功、手环没反应。

> 一句话：不是「没反应」，是手环根本没这个指令，而手机把「收到错误回包」误判成「送达」。

### 1.5 手环 import 不保留 id

`app.ux` 的 `syncToFormatA()`：

```js
result[indexMap[day]].classes.push({
  id: String(idSeq++),          // ← 按导入顺序重新生成，客户端传的 id 被丢弃
  name: ..., time: ..., teacher: ..., location: ..., notes: ...
})
```

三方合并要按 `course_id` 对齐，**必须先让手环侧透传/保存 `id`**，否则每条课的身份在导入时被重置，无法稳定追踪。

### 1.6 手环已有「长震动」通道（chat）

`app.ux` 的 `syncHandleChatIncoming()` 收到 `{"action":"chat"}` 会 `vibrateLong()`（800ms×2 长震动）+ 存 `ev_chat_inbox` + 回 `{ok:true, action:"chat_ack"}`。可临时当作「呼叫手环」用，缺点是会在手环聊天页留一条消息。

---

## 2. 需求拆解

| # | 需求 | 验收标准 |
|---|---|---|
| D1 | 课程表栏目上方「同步状态」按钮 | 编辑后按钮变「N 门课未同步」；未编辑显「已同步」；点击强制同步到手环 |
| D2 | 编辑后立即同步 | 增/删/改课程提交后，自动把手环同步（不需手动再点） |
| D3 | 字段级三方合并 | 不同课自动合、不同字段自动合、同字段冲突才弹窗 |
| D4 | 冲突处理 | 冲突在手机端弹窗：保留手机版 / 保留手环版 |
| D5 | 呼叫手环反馈 | 手环真的震动，且手机能确认「手环已收到并执行」 |

---

## 3. 数据模型增强（字段级同步的地基）

### 3.1 课程对象新增元数据

```java
public static final class Course {
    // 业务字段（不变）
    public String name = "", time = "", teacher = "", location = "";
    public int day = -1;

    // ★ 同步元数据（新增）
    public String id = "";            // 全局唯一键，格式 "c_" + 毫秒 + 随机（生成后终身不变）
    public long updatedAt = 0;        // 最后修改时间（本地时钟，用于「最后修改优先」备选）
    public boolean deleted = false;   // 软删除标记（删课不物理删，打标）
    public String deviceId = "local"; // 最后改动来源（"local" / 手环 nodeId）
}
```

- 旧数据迁移：`fromJson` 里 `id` 为空则补生成一个 `id`，`updatedAt` 补当前时间，`deviceId` 补 `"local"`。**向后兼容，不破坏现有课表**。
- `toJson` 增加这 4 个字段（`deviceId` 建议不写盘，只内存态，避免脏数据泄漏到导出 JSON；写盘仅 `id/updatedAt/deleted`）。

### 3.2 课表对象新增 base 快照

```java
public static final class Schedule {
    // ... 现有字段 ...
    public String baseJson = "";      // 上次同步完成时的课程表快照（JSON 字符串）
    public boolean dirty = false;     // 是否有未同步的本地改动（可由 baseJson 对比实时算，字段留作缓存）
}
```

- `baseJson` 是「三方合并里的 base」的持久化形式：同步成功后，把合并结果的课程列表序列化存进 `baseJson`。
- `dirty` 用于按钮的快速判断，但**真正的判据是 base 对比**（见 §5.1），`dirty` 只是优化，防止每次渲染都跑 diff。

### 3.3 为什么用 JSON 快照而不是逐字段存 base

逐字段存 base（每门课一份 base 副本）内存/存储都翻倍，且要处理「新增课的 base 为空」等边界。用一份 JSON 快照最省事：同步时 `JSON.parse(baseJson)` 得 base 列表，与 local、remote 三方比对即可。

---

## 4. 三方合并算法

### 4.1 输入（三份）

| 份 | 来源 |
|---|---|
| **base** | `Schedule.baseJson`（上次同步成功的快照） |
| **local** | `Schedule.courses`（本机当前课表，含编辑） |
| **remote** | 手环 `export` 回来的 `data.schedule` 摊平（`CourseCache.flatten`） |

三方都按 `id` 归一化成 `Map<id, Course>`。

### 4.2 五条规则（逐条落地）

对 base/local/remote 三份做集合与字段 diff：

```
对每条 id：
  在 base 里？  在 local 里？  在 remote 里？  → 结果
  ─────────────────────────────────────────────────────────
  ❌ ❌ ✅   → remote 新增（手环加课）          → 保留
  ❌ ✅ ❌   → local 新增（手机加课）          → 保留
  ✅ ✅ ✅   → 三处都有 → 逐字段三方合并（§4.3）
  ✅ ✅ ❌   → remote 删了（base 有、local 没变、remote 没了）→ 采纳删除
  ✅ ❌ ✅   → local 删了（base 有、local 没了、remote 没变）→ 采纳删除
  ✅ ❌ ❌   → 两边都删了                        → 删除
  ❌ ✅ ✅   → 两边都新增了同 id（罕见，id 全局唯一不应发生）→ 保留 local
```

### 4.3 字段级合并（对应原需求 5 条规则）

对「三处都有」的课，逐字段比较 `name/time/teacher/location/day`：

| 字段在 local vs base | 字段在 remote vs base | 判定 |
|---|---|---|
| 没变 | 没变 | 用 base 值（三方一致） |
| 没变 | 变了 | **用 remote**（手环改了） |
| 变了 | 没变 | **用 local**（手机改了） |
| 变了 | 变了，且值相同 | 用该值（两边改一样） |
| 变了 | 变了，且值不同 | **冲突**（§4.4） |

这就是原需求里 5 条规则的精化：
1. local 没变 remote 变 → remote ✓
2. remote 没变 local 变 → local ✓
3. 两边改不同课程 → 各自独立处理（集合 diff 自然覆盖）✓
4. 两边改同一课程不同字段 → 字段级各自取变化方 ✓
5. 两边改同一课程同一字段且值不同 → 冲突 ✓

### 4.4 冲突处理（手机端解决）

- **策略：手机为主 + 冲突弹窗**。
- 能自动合并的（不同课/不同字段）静默合并。
- 真冲突（同字段不同值）时，**在手机上弹窗**：
  - 展示：课程名 + 冲突字段 + 「手机值」 vs 「手环值」
  - 选项：「保留手机版」/「保留手环版」
- 手环屏幕小，不适合处理冲突；手环只接收最终结果。

### 4.5 合并结果写回

1. 合并结果 = local（更新后的本机课表）。
2. 若 remote 有改动（手环有新东西）→ 落盘本机 + 刷新插件/提醒。
3. 若 local 有改动（手机有新东西）→ 构造 `import` 上行到手环（**仍覆盖式，但覆盖的是合并后的完整结果，不丢对方改动**）。
4. 同步成功后：`baseJson = 合并结果序列化`，`dirty = false`。

> 关键点：合并后上行是「发合并后的完整课表」，因为 EV 的 `import` 是覆盖式、不支持字段级 patch。字段级合并发生在**手机侧内存**里，产出一个无冲突的完整结果再下发，手环侧无感知。

---

## 5. 同步状态按钮（D1）

### 5.1 未同步判定

```java
// 伪码
boolean hasUnsaved(Schedule s) {
    if (!s.isSync()) return s.courses.size() > 0;   // 纯本地课表：只要有课就算「未同步到手环」
    // sync 课表：local 与 baseJson 逐课逐字段 diff，统计差异课数
    List<Course> base = parseBase(s.baseJson);
    return diffCount(base, s.courses);   // 返回「N 门课未同步」
}
```

- 按钮文案与态：
  - 无差异 → 灰色「已同步 ✓」
  - 有 N 门差异 → 主题色「N 门课未同步」（可点）
  - 未连接手环 → 灰「未连接，暂缓同步」
- 位置：首页周视图上方、课表名行附近（`renderWeek()` 里 `nameRow` 下方插入一行，或并入课表名行右侧）。

### 5.2 点击行为

点击 → 触发「强制同步」：走 `connect`（若未连接）→ 拉 remote → 三方合并 → 上行合并结果 → 更新 base → 刷新按钮。

### 5.3 状态刷新时机

- 编辑保存后（`dirty` 置位）→ 立即重算。
- 同步成功后 → 清零。
- `onResume` / 状态回调 → 重算。

---

## 6. 编辑后立即同步（D2）

### 6.1 触发点

`CourseEditActivity.doCommit()`（新增/编辑/删除都汇聚到这里）落盘后：

```java
ScheduleStore.updateCourses(...);          // 落盘本地（现有）
SyncCoordinator.scheduleAutoSync(ctx);     // ★ 新增：标记需要同步
```

### 6.2 同步策略（断连不丢）

- **已连接**：立即走一次三方同步（同 §5.2）。
- **未连接**：只落盘 + 置 `dirty=true`，按钮显「N 门课未同步」；**不阻塞 UI，不弹失败**。下次连接成功（`SyncEngine.connect` 的 `onFinish(ok)` / `autoReconnect`）后自动补一次同步。
- 无需后台自动轮询（尊重「不搞后台自动同步」的产品底线）：补同步挂在「连接成功」这一自然事件上。

### 6.3 与现有「手动同步」的关系

首页现有 `pullSchedule()`（拉远程覆盖本地）**必须改为走三方合并**，否则自动同步和手动同步两套逻辑会互相打架。统一收敛成一个入口：

```java
SyncCoordinator.syncNow(ctx, callback)   // 唯一同步入口：connect 检查 → 拉 remote → 三方合并 → 上行 → 更新 base
```

`pullSchedule` / `manualSync` / 状态按钮 / 自动补发 都调它。

---

## 7. 协议层改造（手环仓，需另行同意）

### 7.1 import 透传 id（D3 前置，必做）

`app.ux` 的 `syncToFormatA()` 改为：**优先用客户端传入的 `id`，没有才生成**。

```js
result[indexMap[day]].classes.push({
  id: String(c.id || ("c" + Date.now() + "-" + idSeq++)),   // ★ 透传 c.id
  ...
})
```

> 手环侧存储结构里 `classes[].id` 已存在（export 回包里就有 `"id":"1"`），只需在 import 时保留客户端 id 即可，改动面小。

### 7.2 新增 call action（D5，必做）

`app.ux` 的 `onmessage` 增加分支：

```js
if (parsed.action === "call") {
  // 呼叫手环：长震动（响铃能力若 Vela 支持再加）+ 回确认
  vibrateLong();
  syncReply(connect, { ok: true, action: "call", ts: Date.now() });
  return;
}
```

回包 `{ok:true, action:"call"}` 是「手环已收到并执行」的**可靠确认**（比「收到回包」强，因为是手环主动执行后回报）。

### 7.3 可选：export 带 updatedAt / deleted

- `schedule` 里每门课已可带 `id`；若把 `updatedAt`、`deleted` 也透传，手机侧可做「最后修改优先」的更细策略。但**本期不依赖**，手机为主已够用。标注为可选增强。

### 7.4 兼容性

- 旧版 EV（无 `call`）：发 `call` 落兜底回 `{ok:false, reason:"no courses"}` → APK 侧按 `ok=false` 判定「手环不支持呼叫」，降级为 `chat` 通道（§7.5）或提示升级。
- 旧版 EV（import 丢弃 id）：三方合并退化为「整表覆盖」（与现状一致），不崩。

### 7.5 呼叫手环的降级方案（不改 EV 时）

复用 `chat` 通道：`{"action":"chat","id":"...","text":"🔔 呼叫手环","ts":...}`，手环会长震动。代价是聊天页多一条消息。**过渡可用，正式仍建议 7.2 的 `call`。**

---

## 8. 呼叫手环修复（D5）

### 8.1 APK 侧（本仓，可立即改）

1. `HomeActivity.callBand()` 的 `quickSend` 改为**检查回包 `ok` 字段**，而不是「有回包即成功」：

```java
e.send("{\"action\":\"call\"}", new Reply() {
    public void onReply(String r) {
        boolean ok = parse(r).optBoolean("ok", false);
        miniStatus(ok ? "手环已响铃 ✓" : "手环未响应（可能不支持，建议升级 EV）", ok ? Ui.OK : Ui.WARN);
    }
    ...
});
```

2. 顺手修 `quickSend()` 的通用逻辑：onReply 里检查 `ok`，`ok=false` 显示 `reason`。

### 8.2 手环侧（配合 7.2）

新增 `call` 分支，`vibrateLong()` + 回 `{ok:true, action:"call"}`。若 Vela 支持响铃，叠加响铃；不支持就震动（震动已能感知）。

### 8.3 为什么这样算「对接检测」

- 旧行为：手机「发出去了」= 成功（弱确认，且被 `ok:false` 骗了）。
- 新行为：手环「执行了震动」后主动回 `{ok:true, action:"call"}`，手机据此显示「已响铃」—— 这是**端到端确认**，链路任何一环断了都会超时/报错。

---

## 9. 实施路径（分阶段，先 APK 后手环）

### 阶段 0：协议层打通（手环仓，单独一个 commit）
1. `import` 透传 `id`（§7.1）。
2. 新增 `call` action（§7.2）。
3. 手环 bump 版本号 + 出 rpk + 真机验证。

### 阶段 1：数据模型增强（本仓）
1. `Course` 加 `id/updatedAt/deleted`，`toJson/fromJson` 兼容旧数据（空 id 自动补）。
2. `Schedule` 加 `baseJson/dirty`。
3. 新增 `SyncCoordinator`（合并算法 + 唯一同步入口）核心类，先写好 §4 的三方 diff 纯函数 + 单测级自测（可在 `DebugActivity` 加一条「合并算法自检」按钮验证）。

### 阶段 2：同步按钮（本仓）
1. `renderWeek()` 里课表名行下方加「已同步 / N 门课未同步」按钮。
2. 接入 `SyncCoordinator.hasUnsaved()`。
3. 点击 → `syncNow`。

### 阶段 3：编辑后立即同步（本仓）
1. `CourseEditActivity.doCommit()` 尾接 `syncNow`（已连接则同步，未连接置 dirty）。
2. `pullSchedule`/`manualSync`/连接成功回调 统一走 `SyncCoordinator.syncNow`。
3. 冲突弹窗（§4.4）。

### 阶段 4：呼叫手环修复（本仓 + 手环已在阶段 0 完成）
1. APK `callBand` 检查 `ok` 字段。
2. 端到端回归：点呼叫 → 手环震动 → 手机显示「已响铃」。

### 阶段 5：真机回归（必做）
- 手机编辑 1 门课 → 按钮变「1 门课未同步」→ 自动同步 → 手环课表变化 → 按钮变「已同步」。
- 手环端改 1 门课 → 手机手动/自动同步 → 本机更新且不丢手机改动。
- 手机改教室、手环改时间（同一门课）→ 自动合并成「新教室 + 新时间」。
- 手机改时间、手环也改时间且不同 → 手机弹冲突框 → 选手机版 → 手环被覆盖为手机版。
- 呼叫手环 → 手环震动 + 手机确认。

---

## 10. 风险与边界

| 风险 | 应对 |
|---|---|
| 手环仓改动需用户同意 | 阶段 0 单独确认，不混入本仓 commit |
| 旧数据迁移（无 id） | 自动补 id，兼容旧课表；迁移前先 `git` 备份 + 手环 `astrobox_sync_backup` 已自动备份 |
| 手环时钟不可信 | 本方案**不用时间戳裁决冲突**（手机为主），时间戳仅作展示；避免「最后修改优先」的时钟漂移坑 |
| 删除冲突 | 采用「删除优先」+ 显式提示（原需求建议）；手机删了手环还有 → 默认删，弹窗可反悔 |
| import 仍覆盖式 | 合并发生在手机侧，产出无冲突完整结果再下发，手环无感知 |
| 多课表（多套） | base 快照按 `Schedule.id` 各存各的，互不干扰 |
| 同步中用户又编辑 | `syncNow` 加单例锁（类似 `SyncEngine.autoRetryRunning`），同步期间禁改或排队 |
| 旧版 EV 无 call / 丢 id | 降级为 chat 通道 / 整表覆盖，不崩 |

---

## 11. 附：改动文件清单

### 本仓（ev-schedule-android）
- `CourseCache.java`：`Course` 加元数据 + 序列化兼容。
- `ScheduleStore.java`：`Schedule` 加 `baseJson/dirty` + 序列化。
- **新增** `SyncCoordinator.java`：三方合并 + 唯一同步入口 + 未同步判定 + 冲突弹窗。
- `HomeActivity.java`：同步按钮 + `quickSend` 检查 `ok` + 同步入口收敛。
- `CourseEditActivity.java`：`doCommit` 后触发自动同步。

### 手环仓（tom/class/class，需同意）
- `src/app.ux`：`syncToFormatA` 透传 `id`；`onmessage` 新增 `call` 分支。
