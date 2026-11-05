#!/usr/bin/env python3
"""Style-literal checker for EV Sync UI spec v1.

Default: inspect only added lines (staged + unstaged diff) and untracked Java
files, so the ~487 legacy literals do not block daily work (spec section 6).
--all: report every Java file, always exit 0 (migration progress only).
"""
import argparse
import pathlib
import re
import subprocess
import sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
SRC = ROOT / "apk" / "src" / "com" / "application" / "watch" / "classschedule"
ALLOW = {"Ui.java", "WatchAppearance.java"}

# ev-ui-android v1.2「设计宪法」B：面向中小学生，用户可见文案禁用工程黑话。
# 只查新增行里的中文字符串字面量；日志行与存量按同一套豁免规则处理。
STRING_RX = re.compile(r'"([^"\\]*(?:\\.[^"\\]*)*)"')
CJK_RX = re.compile(r"[\u4e00-\u9fff]")
LOG_RX = re.compile(r"\bLog\.[dvwie]\s*\(")
K12_HARD = re.compile(
    r"握手|回包|解析|穿戴服务|加白名单|后台应用|设备\s*ID|设备号|设备标识|状态机|签名不一致|"
    r"APK|SDK|错误码|异常|绑定失败|服务不可用|接口|参数|注入|证书")
K12_SOFT = re.compile(
    r"失败|重试|设备|后台|权限|配置|校验|诊断|降级|激活码|兑换码")
RULES = (
    ("hardcoded-color",
     re.compile(r"0x[0-9A-Fa-f]{8}\b|Color\.parseColor\("),
     "Ui.ACCENT / Ui.CARD / Ui.ON_ACCENT ..."),
    ("hardcoded-text-size",
     re.compile(r"\.setTextSize\(\s*(?:[^,()]+,\s*)?\d+(?:\.\d+)?f?\s*\)"),
     "Ui.SP_* with COMPLEX_UNIT_SP"),
    ("hardcoded-corner",
     re.compile(r"\.setCornerRadius\(\s*\d+|Ui\.round\([^,]+,\s*\d+"),
     "Ui.R_CARD / R_CTRL / R_BLOCK"),
    # ev-ui-android v1.3：elevation 收敛到 E0/E1/E2，新增行禁止数字字面量。
    # 放行 setElevation(0f)（重置）与 Ui.* 形式（如 setElevation(Ui.dp(c, E1))）。
    ("hardcoded-elevation",
     re.compile(r"\.setElevation\(\s*(?!0f?\s*\))(?!Ui\.)\d+(?:\.\d+)?f?"),
     "Ui.E0 / E1 / E2 (e.g. setElevation(Ui.dp(c, E1)))"),
)


def run(*args):
    return subprocess.run(args, cwd=ROOT, text=True,
                          capture_output=True).stdout


def added_lines():
    """Yield (path, lineno, text) for added lines in staged + unstaged diff."""
    for extra in (("--cached",), ()):
        path, lineno = None, 0
        for line in run("git", "diff", "-U0", *extra).splitlines():
            if line.startswith("diff --git"):
                path, lineno = None, 0
            elif line.startswith("+++ b/"):
                path = line[6:]
            elif line.startswith("@@"):
                m = re.search(r"\+(\d+)", line)
                lineno = int(m.group(1)) - 1 if m else 0
            elif line.startswith("+") and not line.startswith("+++"):
                lineno += 1
                if path:
                    yield path, lineno, line[1:]
            elif not line.startswith("-") and path:
                lineno += 1


def untracked_java():
    for rel in run("git", "ls-files", "--others", "--exclude-standard").splitlines():
        p = ROOT / rel
        if p.suffix == ".java" and SRC in p.parents:
            text = p.read_text(encoding="utf-8", errors="replace")
            for i, line in enumerate(text.splitlines(), 1):
                yield rel, i, line


def all_java():
    for p in sorted(SRC.glob("*.java")):
        rel = str(p.relative_to(ROOT))
        text = p.read_text(encoding="utf-8", errors="replace")
        for i, line in enumerate(text.splitlines(), 1):
            yield rel, i, line


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--all", action="store_true",
                    help="full report of legacy literals, never fails")
    args = ap.parse_args()
    items = all_java() if args.all else list(added_lines()) + list(untracked_java())
    found, soft, checked, files = [], [], 0, set()
    for rel, lineno, text in items:
        if not rel.endswith(".java") or pathlib.Path(rel).name in ALLOW:
            continue
        if "/classschedule/" not in rel.replace("\\", "/"):
            continue
        checked += 1
        files.add(rel)
        stripped = text.strip()
        if stripped.startswith("//") or stripped.startswith("*"):
            continue
        for name, rx, hint in RULES:
            m = rx.search(text)
            if not m:
                continue
            if name == "hardcoded-color" and m.group(0).upper() == "0X00000000":
                continue  # fully transparent: no token exists yet
            found.append((rel, lineno, name, hint, stripped[:100]))
            break
        if LOG_RX.search(text):
            continue  # 日志与内部变量名不是面向用户的文案
        for m in STRING_RX.finditer(text):
            s = m.group(1)
            if not CJK_RX.search(s):
                continue
            hard = K12_HARD.search(s)
            if hard:
                found.append((rel, lineno, "k12-jargon",
                              "面向中小学生：工程黑话改成人话（规范「设计宪法」B）",
                              "%s  <- %s" % (s[:70], hard.group(0))))
                break
            warn = K12_SOFT.search(s)
            if warn:
                soft.append((rel, lineno, warn.group(0), s[:70]))
                break
    if soft:
        print("[ui-spec] K12 文案软提醒 %d 处（不阻断，建议改成动作句）：" % len(soft))
        for rel, lineno, word, s in soft[:8]:
            print("  %s:%d  「%s」  %s" % (pathlib.Path(rel).name, lineno, word, s))
        if len(soft) > 8:
            print("  ... 其余 %d 处略" % (len(soft) - 8))
    shown = found[:20] if args.all else found
    for rel, lineno, name, hint, src in shown:
        print("[ui-spec] %s:%d  %s  -> %s\n          %s" %
              (pathlib.Path(rel).name, lineno, name, hint, src))
    if args.all and len(found) > len(shown):
        print("  ... 其余 %d 条略（--all 仅看趋势，不阻断）" % (len(found) - len(shown)))
    mode = "full report" if args.all else "added lines"
    if found:
        print("[ui-spec] %d violation(s) / %d line(s) in %d file(s) (%s). "
              "See docs/EV-UI-Android-v1.md (ev-ui-android) §6." %
              (len(found), checked, len(files), mode))
        return 0 if args.all else 1
    print("[ui-spec] OK: %d line(s) checked, 0 violation(s) (%s)." %
          (checked, mode))
    return 0


if __name__ == "__main__":
    sys.exit(main())
