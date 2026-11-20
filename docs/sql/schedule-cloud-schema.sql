-- ============================================================
-- 课程表云同步 / 网页编辑 —— P1 schema
-- 项目：Supabase kqzkdpmyivtpxkfmibnd
-- 日期：2026-11-20
-- 配套文档：ev/ev-schedule-android/docs/云同步与网页编辑-P1落地计划-20261120.md
--
-- 设计要点（改这块之前先读）：
--   1. 不做行级 courses 表：一张表几十门课，payload 整包替换，与手机端 baseJson 同构。
--   2. 删除只写墓碑（deleted/deleted_at），行不物理删除，至少留 30 天。
--   3. 服务端「没有这条记录」绝不等于「已删除」——调用方不得据此删本地。
--   4. 首次上云以本地为真源，rev 从 1 起。
--   5. 所有读写必须经接口层校验 token/session，禁止 anon key 直查（防按 guid 遍历拖库）。
-- ============================================================

-- ---------- 当前态 ----------
create table if not exists public.schedules (
  guid        text primary key,
  watch_key   text not null,                       -- mac 或 deviceId，仅归属/可读定位，不做鉴权键
  watch_name  text,
  name        text not null,
  rev         integer not null default 1,
  payload     jsonb   not null,                    -- 整张课表快照 {"courses":[...]}
  public_read boolean not null default false,      -- 分享开关，默认关
  deleted     boolean not null default false,      -- 墓碑
  deleted_at  timestamptz,
  ack_rev     integer not null default 0,          -- 安卓端已落盘的最高 rev（三级回执用）
  ack_at      timestamptz,
  updated_at  timestamptz not null default now(),
  updated_by  text not null default 'android'      -- android | web
);

-- ---------- 历史快照（云备份 + 误覆盖回滚 + 推送前存档） ----------
create table if not exists public.schedule_versions (
  id         bigserial primary key,
  guid       text not null references public.schedules(guid) on delete cascade,
  rev        integer not null,
  payload    jsonb not null,
  reason     text not null,                        -- manual | auto | pre-push | pre-restore
  created_at timestamptz not null default now()
);

-- ---------- 编辑/只读凭据（P2 只读链接；P3 起 session 取代 edit 用途） ----------
create table if not exists public.schedule_tokens (
  token      text primary key,                     -- 32 字节随机 base64url
  guid       text not null,   -- 2026-11-20 去掉 on delete cascade：实测「删课表行→级联删 token→客户端永远 401 无法自愈」，token 必须比 schedules 行活得久，撤销靠 revoked 字段
  scope      text not null default 'read',         -- read | edit
  expires_at timestamptz,
  revoked    boolean not null default false,
  created_at timestamptz not null default now()
);

-- ---------- 索引 ----------
create index if not exists idx_versions_guid   on public.schedule_versions(guid, created_at desc);
create index if not exists idx_tokens_guid     on public.schedule_tokens(guid) where revoked = false;
create index if not exists idx_schedules_watch on public.schedules(watch_key, deleted);

-- ---------- RLS ----------
-- 全部关闭：数据只经接口层（Edge Function / 轻后端，持 service_role）访问。
-- 若将来要开 RLS，必须同时给 anon 角色撤权，否则「关了 RLS」和「开了宽松策略」一样危险。
alter table public.schedules         enable row level security;
alter table public.schedule_versions enable row level security;
alter table public.schedule_tokens   enable row level security;

-- 显式策略一条都不建 = 默认全拒。service_role 绕过 RLS，接口层照常读写。
-- 下面这条注释掉的示例说明「只读分享」将来怎么做，不要在 P1 打开：
-- create policy "public read" on public.schedules for select using (public_read = true);

-- ---------- 保留策略提醒（不在此处执行，交给定时任务） ----------
-- 1. 墓碑 30 天后物理清理：delete from schedules where deleted and deleted_at < now() - interval '30 days';
-- 2. 自动备份只留 7 份：按 guid 分组，reason='auto' 按 created_at desc 取前 7，其余删除；
-- 3. 过期 token 清理：delete from schedule_tokens where expires_at < now() or revoked;
