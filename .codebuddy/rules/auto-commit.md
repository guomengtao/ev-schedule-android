# 改动后自动提交 GitHub（项目规则）

## 规则

**每完成一次实质改动（代码 / 文档 / 构建脚本 / 设计稿），立即提交并推送到 GitHub，不要等用户再次要求。**

## 仓库

`guomengtao/ev-schedule-android` —— **private**，分支 `main`，用 `gh` CLI 管理。

## 推送方式（必须 SSH）

```
origin  git@github.com:guomengtao/ev-schedule-android.git
```

**HTTPS 推不动**（443 连不上 / `Empty reply from server`）。若报错，先执行一次 `gh auth setup-git`。

## 步骤

```bash
git add -A
# 自检 1：不能有密钥
git diff --cached --name-only | grep -Ei "\.(jks|keystore|pk8|p12)$|private\.pem|certificate\.pem"
# 自检 2：不能有 >1MB 的文件
git commit -m "<type>: <摘要>" -m "<正文>"
git push origin main
```

## 版本号：第三位（patch）+1

- 版本号存在 `apk/version.env`（`VERSION_CODE` / `VERSION_NAME`）
- **`bash apk/build.sh` 会在构建成功后自动 bump**：`VERSION_CODE + 1`、`VERSION_NAME` 第三位 `+ 1`
- 只改文档没有重建时，手动把 `version.env` 的第三位 `+1`，保持"一次改动 = 一次 +1"
- **包名固定 `com.application.watch.classschedule`，绝不能带版本号**（interconnect 要求与快应用 package 完全一致）

## 提交前必查（安全）

`.gitignore` 已排除，但仍要确认没有漏网：

- `*.jks` `*.keystore` `*.pk8` `*.p12` `private.pem` `certificate.pem` `/sign/`
- 构建产物 `apk/out/` `apk/dist/` `*.apk` `*.idsig`
- 大文件 `apk/tools/bcprov.jar`（build.sh 自动下载）

> 这些是签名私钥，一旦推上去等于把 EV 快应用和本 APK 的发布权交出去。
