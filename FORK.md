# FIRERPA app fork（自研包名二开）

本仓库基于官方 `firerpa/app`（tag v1.3）二开，用于承载「卡密（口令）」业务客户端。
与上游保持最小差异，改动清单见下表（后续同步上游时逐条 rebase）。

## 改动清单

| # | 文件 | 改动 |
|---|------|------|
| 1 | `app/build.gradle.kts` | `applicationId`：`com.firerpa` → `com.rpac.console`（正式名称上线前为占位） |
| 2 | `app/src/main/res/values*/strings.xml` | 应用名 FIRERPA → "RPA Console"；新增卡密页文案 |
| 3 | `app/src/main/AndroidManifest.xml` | 加 `android:usesCleartextTraffic="true"`（测试环境业务网关为 HTTP；生产 TLS 后移除） |
| 4 | `app/src/main/res/menu/main_navigation.xml` | 新增 `nav_license` 底部导航项（第 3 个 Tab） |
| 5 | `app/src/main/java/com/firerpa/ui/MainPagerAdapter.kt` | 新增 `pageLicense`（索引 2），页序：Home/Config/License/Log |
| 6 | `app/src/main/res/layout/page_license.xml` | 【新增】卡密激活页：服务器地址/端口/口令/设备ID + 校验激活 + 刷新状态 |
| 7 | `app/src/main/java/com/firerpa/license/LicenseController.kt` | 【新增】卡密页控制器：调用业务网关 `/api/v1/license/verify`、`/me` |
| 8 | `app/src/main/java/com/firerpa/data/AppPrefs.kt` | 新增 `license_*` 配置项（本地持久化） |
| 9 | `app/src/main/java/com/firerpa/update/AppUpdateRepository.kt` | 禁用官方 app 自更新（防止二开包被官方版覆盖）；**lamda 服务端更新仍走官方源** |
| 10 | `gradle/wrapper/gradle-wrapper.properties` | Gradle 分发源换腾讯镜像（services.gradle.org 会跳转 GitHub，国内易卡；上游同步时可还原） |
| 11 | `local.properties` | 本地 SDK 路径（不入库） |
| 12 | `app/src/main/res/menu/main_navigation.xml` | 「卡密」Tab 置顶为第一个（默认启动页）；导航顺序：卡密/主页/配置/日志 | M3 |
| 13 | `app/src/main/java/com/firerpa/license/LicenseApi.kt` | 【新增】业务网关客户端：verify / me / pause / resume / stop / update-accept（卡密页与悬浮窗共用） | M3 |
| 14 | `app/src/main/java/com/firerpa/license/LicenseController.kt` | 卡密页：脚本控制（启动/暂停/结束）+ 非强制更新时显示「立即更新」（调 /update/accept）+ 悬浮窗开关（含权限引导） | M3 |
| 15 | `app/src/main/java/com/firerpa/overlay/OverlayService.kt`、`res/layout/overlay_panel.xml`、`res/drawable/overlay_bg.xml` | 【新增】悬浮窗脚本控制面板：启动/暂停、结束脚本、回主页；标题可拖动；前台服务保活（specialUse） | M3 |
| 16 | `app/src/main/AndroidManifest.xml` | 追加 SYSTEM_ALERT_WINDOW / FOREGROUND_SERVICE(+SPECIAL_USE) / POST_NOTIFICATIONS 权限与 OverlayService 声明 | M3 |
| 17 | `app/src/main/java/com/firerpa/MainActivity.kt`、`app/src/main/java/com/firerpa/ui/MainPagerAdapter.kt` | 默认 Tab 改为卡密；适配器新增首页创建回调（默认 Tab 非主页时同步主页开关/绑定） | M3 |
| 18 | `LicenseController` / `LicenseApi` / `page_license.xml` | 「我的卡密」列表（my-cards）+ 快捷切换（按 card_id，无需明文卡密）+ 绑定情况对话框（卡密绑定设备列表、解绑，含配额提示与错误码中文提示）+ 自助解绑后会话失效自动复位 | M3 |
| 19 | `LicenseController`（lamda 保障） | 激活成功 / 点击启动脚本时，自动确保设备端 lamda 服务在运行（root 执行 launch.sh） | M3 |
| 20 | 悬浮窗改版 | 收起时仅小圆点（可拖动）；点击展开 [启动/暂停][结束][回主页] 三个图标按钮；无文字、无 ✕；删除长按关闭（关闭 = 回 app 关开关） | M3 |
| 21 | `LicenseController.kt` | 卡密错误中文化（设备被拉黑 / 口令输错被锁定）；`/me` 返回 `blacklisted` 时状态面板显示「⚠ 该设备已被管理员拉黑…」；App 更新提示改为版本语义化比较（仅后台版本更高才提示，`1.3-10-gxxxx` 形式正确比较），弹窗当前版本去掉 git 后缀显示 | M3 |
| 22 | 品牌与包名（`strings.xml` ×3、`build.gradle.kts`、`keystore.properties`） | FIRERPA → **AutoRPA**（应用名 / hero 徽章 / 副标题 / 通知标题）；`applicationId`：`com.rpac.console`（占位）→ **`com.autorpa.console`**（正式包名，首发后不可再变更，否则用户无法覆盖安装）；新增独立发布签名 `autorpa-release.jks`（RSA2048 / 30 年；`keystore.properties`、`*.keystore`、`*.jks` 已加入 `.gitignore`，备份于 `..\_release-keys\`） | v1.4 公开发布 |
| 23 | `README.md`、`release/`、`docs/` | 项目介绍 README（功能 / 原理 / 快速开始 / FAQ / 构建 / 发布规范）；`release/AutoRPA-vX.Y.apk` 随 tag 提交 → 用户可直接从仓库下载；`docs/screenshot-*.png` 截图 | v1.4 公开发布 |

## 已知待办（M3 后续）

- [ ] 自动读取 lamda `unique_id`（当前在卡密页手动填写；优先级：lamda WebUI REST > 设备端 Python 客户端 > 自写 gRPC）
- [ ] 首次激活后自动写入/校验 Pigeon 接入配置（当前由服务端 direct 流程负责）
- [ ] 被踢下线检测（定时 `/me`）与界面提示

## 构建

```powershell
cd I:\projects\android_projects\firerpa\app
.\gradlew.bat :app:assembleDebug
# 产物：app\build\outputs\apk\debug\app-debug.apk
# SDK 路径在 local.properties（sdk.dir=I:\windows\android-sdk）
```
