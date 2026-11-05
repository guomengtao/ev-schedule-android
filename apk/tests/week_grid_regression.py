#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
EV 同步器首页周课表网格 · 回归用例（L2 逻辑验证，纯标准库、可复跑）

运行：
    python3 apk/tests/week_grid_regression.py
退出码：0 = 全部断言通过；1 = 至少一条失败。

对应实现（ev/ev-schedule-android/apk/src/.../HomeActivity.java）：
  * weekGridWithTime()  —— 时间槽收集 / 数值升序排序 / ±5 分钟聚类 / 按天分桶
  * SLOT_TOLERANCE_MIN  —— 本脚本的 TOL，改 Java 常量时这里要同步
  * CourseCache.minutes() / shortTime() —— minutes() / short_time()
  * periodIndex()       —— period_index()
  * renderFocusCard()   —— 焦点卡「第 N 节」与今日进度条的节次口径

⚠️ 边界：这是 L2 仿真，复刻 Java 算法而非在 JVM 上跑真实代码。
   它不能替代 L1 全量构建与 L3 真机冒烟；Java 逻辑改动后请同步本脚本。
   验收依据：截图复盘文档 docs/首页课程时间轴错乱与课程显示错误分析-20261105.md 第五节。
"""
import re
import sys
from collections import defaultdict

TOL = 5                 # 与 HomeActivity.SLOT_TOLERANCE_MIN 保持一致
UNPARSED = 2 ** 31 - 1  # 对应 Java 的 Integer.MAX_VALUE（占位槽排最后）
TOTAL = 0
FAILED = 0


def minutes(t):
    """对应 CourseCache.minutes()[0]：取第一个 HH:MM 的当日分钟数。"""
    ms = re.findall(r'(\d{1,2}):(\d{2})', t or '')
    return int(ms[0][0]) * 60 + int(ms[0][1]) if ms else None


def short_time(t):
    """对应 CourseCache.shortTime()：取开始时间前 5 字符。"""
    return (t or '').strip()[:5]


def build_grid(courses):
    """
    courses: [(day:int, name:str, time:str), ...]
    返回 (slots, alias, buckets)：slots=左侧时间标签，alias=原始 key→簇首 key，
    buckets[day][slot]=课程名列表（P0 起为 List，不再 put 覆盖）。
    """
    # LinkedHashSet 按数据插入顺序收集，再按数值分钟升序（P0）
    raw = list(dict.fromkeys(short_time(c[2]) for c in courses))
    raw.sort(key=lambda s: (1, 0) if minutes(s) is None else (0, minutes(s)))

    # ±TOL 分钟聚类，以簇首为锚而非链式相邻比较（P1）
    alias, slots, anchor = {}, [], -1
    for r in raw:
        v = minutes(r)
        v = UNPARSED if v is None else v
        new_cluster = (not slots) or anchor == UNPARSED or v == UNPARSED \
            or v - anchor > TOL
        if new_cluster:
            slots.append(r)
            anchor = v
        alias[r] = slots[-1]
    if not slots:
        slots.append('全')
        alias['全'] = '全'

    buckets = defaultdict(lambda: defaultdict(list))
    for day, name, t in courses:
        k = alias.get(short_time(t), short_time(t))
        buckets[day][k].append(name)
    return slots, alias, buckets


def period_index(starts, start_minute):
    """对应 HomeActivity.periodIndex()：按「开始时间更早的去重时间槽」计数。"""
    if start_minute is None:
        return 1
    return sum(1 for s in set(starts) if s < start_minute) + 1


def check(label, cond, detail=''):
    global TOTAL, FAILED
    TOTAL += 1
    ok = bool(cond)
    if not ok:
        FAILED += 1
    print('  %s %s%s' % ('PASS' if ok else 'FAIL', label,
                         ('  | ' + detail) if detail else ''))
    return ok


def case(title):
    print('\n%s' % title)


def main():
    print('EV 周课表网格回归 · L2 逻辑验证')
    print('=' * 62)

    # ---- 1. 用户截图的 13 个乱序时间标签（E1 主错误）----
    case('[1/8] 截图 13 个乱序时间标签 → 排序 + 归一')
    shot = ['10:55', '08:00', '08:15', '09:10', '10:25', '12:55', '11:20',
            '14:50', '15:45', '16:30', '18:45', '19:40', '18:50']
    courses = [(0, '课%d' % i, t + '-23:59') for i, t in enumerate(shot)]
    slots, alias, _ = build_grid(courses)
    parsed = [minutes(s) for s in slots if minutes(s) is not None]
    check('时间标签严格递增', all(a < b for a, b in zip(parsed, parsed[1:])),
          ' '.join(slots))
    check('乱序三处全部消失',
          slots.index('10:55') > slots.index('10:25')
          and slots.index('12:55') > slots.index('11:20')
          and slots.index('19:40') > slots.index('18:45'))
    check('13 行归一为 12 行（18:50 并入 18:45）', len(slots) == 12,
          '行数=%d' % len(slots))
    check('晚二 19:40 独立且在最后', alias['19:40'] == '19:40' and slots[-1] == '19:40')

    # ---- 2. 同一时间两门课（E2 静默丢课）----
    case('[2/8] 同时间双课 → 分桶不覆盖，课程总数守恒')
    courses = [(0, '数学', '08:00-08:45'), (0, '物理', '08:00-08:45'),
               (0, '语文', '08:45-09:30')]
    slots, _, buckets = build_grid(courses)
    placed = sum(len(v) for v in buckets[0].values())
    check('3 条课程全部落格（无静默丢课）', placed == 3, '落格数=%d' % placed)
    check('08:00 槽内保留 2 门', len(buckets[0]['08:00']) == 2,
          str(buckets[0]['08:00']))
    check('08:45 槽独立', len(buckets[0]['08:45']) == 1)
    check('时间标签仍严格递增', minutes(slots[0]) < minutes(slots[1]))

    # ---- 3. 跨天 ±5 分钟（E2 晚一拆行）----
    case('[3/8] 跨天 18:45 / 18:50 晚一 → 同一行左右对齐')
    courses = [(0, '晚一', '18:45-19:30'), (1, '晚一', '18:50-19:35'),
               (0, '晚二', '19:40-20:30')]
    slots, alias, buckets = build_grid(courses)
    check('18:50 归一到 18:45', alias['18:50'] == alias['18:45'] == '18:45')
    check('左右两列晚一落在同一行',
          '18:45' in buckets[0] and '18:45' in buckets[1],
          '左=%s 右=%s' % (list(buckets[0]), list(buckets[1])))
    check('无稀疏空槽（只有 2 行）', len(slots) == 2, ' '.join(slots))
    check('晚二仍在晚一之后', slots.index('19:40') > slots.index('18:45'))

    # ---- 4. 占位槽「全」----
    case('[4/8] 解析不出时间的占位槽 → 排最后且不吞其它槽')
    courses = [(0, '数学', '08:00-08:45'), (0, '自习', '全'),
               (0, '语文', '10:00-10:45')]
    slots, alias, buckets = build_grid(courses)
    check('占位槽排最后', slots[-1] == '全', ' '.join(slots))
    check('占位槽单独成簇', alias['全'] == '全')
    check('正常课程仍按数值递增', minutes(slots[0]) < minutes(slots[1]))
    check('占位课也在格子里', '全' in buckets[0])

    # ---- 5. 假期列（空列）----
    case('[5/8] 假期列无课 → 不新增行、其它列课程守恒')
    courses = [(0, '数学', '08:00-08:45'), (2, '语文', '08:00-08:45'),
               (5, '英语', '14:00-14:45')]
    slots, _, buckets = build_grid(courses)
    check('行数由有课列决定（2 行）', len(slots) == 2, ' '.join(slots))
    check('空列不产生额外时间槽', 1 not in buckets and 3 not in buckets)
    check('课程总数守恒', sum(len(v) for col in buckets.values()
                       for v in col.values()) == 3)

    # ---- 6. 防链式串簇 ----
    case('[6/8] 18:45 / 18:50 / 18:55 → 不被无限串成一簇')
    courses = [(0, 'A', '18:45-19:00'), (0, 'B', '18:50-19:05'),
               (0, 'C', '18:55-19:10')]
    slots, alias, _ = build_grid(courses)
    check('分成两簇', len(slots) == 2, ' '.join(slots))
    check('18:50 并入 18:45，18:55 独立',
          alias['18:50'] == '18:45' and alias['18:55'] == '18:55')

    # ---- 7. 每门课都有对应行（无空槽错位）----
    case('[7/8] 全部课程都能定位到某一行（无空槽错位）')
    courses = [(d, '课%d' % i, t + '-23:59')
               for i, (d, t) in enumerate([(0, '08:00'), (0, '10:55'),
                                          (2, '08:00'), (4, '18:50'),
                                          (4, '18:45'), (6, '19:40')])]
    slots, _, buckets = build_grid(courses)
    orphan = [name for day in buckets for slot in buckets[day]
              for name in buckets[day][slot] if slot not in slots]
    check('无孤儿课程', not orphan, str(orphan))
    check('课程总数守恒', sum(len(v) for col in buckets.values()
                       for v in col.values()) == len(courses))
    check('时间标签严格递增',
          all(minutes(a) < minutes(b) for a, b in zip(slots, slots[1:])))

    # ---- 8. 焦点卡节次序号（P2 去重口径）----
    case('[8/8] 焦点卡「第 N 节」按去重时间槽计数')
    starts = [480, 480, 600, 660]   # 08:00 两门 + 10:00 + 11:00
    check('同槽双课都算第 1 节', period_index(starts, 480) == 1)
    check('10:00 是第 2 节（不是第 3 节）', period_index(starts, 600) == 2)
    check('11:00 是第 3 节', period_index(starts, 660) == 3)
    check('总节数 = 去重槽数 3', len(set(starts)) == 3)

    print('\n' + '=' * 62)
    print('结果：8 组用例 / %d 条断言，%s' %
          (TOTAL, '全绿（0 失败）' if FAILED == 0 else '失败 %d 条' % FAILED))
    return 1 if FAILED else 0


if __name__ == '__main__':
    sys.exit(main())
