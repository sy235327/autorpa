# PATCHES — pigeon-fork 相对上游的改动清单

- 上游基线：`firerpa/pigeon` main 分支（GitHub zip，对应上游 2026-08-30 最后推送）
- 本地基线 commit：`7bd8859`（"upstream snapshot"）
- 规范：对上游既有文件的任何修改都必须记录在本表，供后续上游升级时逐条 rebase；新增文件（如 license 模块）也在此登记目录级条目。

| # | 文件 | 改动 | 原因 | 日期 |
|---|------|------|------|------|
| 1 | `docker/docker-compose.yml` | 移除 `lan-ipvlan` 网络段与 pigeon 服务的挂载；pigeon 服务增加本地构建段（`image: pigeon-fork:1.0` + `build`） | Docker Desktop（Windows）/多数本机环境不支持 ipvlan；本 fork 默认 bridge + 端口映射。恢复办法见文件内注释 | 2026-10-12 |
| 2 | `.gitattributes` | 追加 `*.sh / entry / Dockerfile / *.py / *.yml / *.conf / *.ini / *.lua / requirements.txt` 的 `text eol=lf` 规则 | Windows 开发环境防止 CRLF 污染脚本（容器内以 LF 执行） | 2026-10-12 |
| 3 | `.dockerignore` | 新增（`.git`、`docker/data`、日志不入构建上下文） | 构建上下文瘦身、避免把运行时数据打进镜像 | 2026-10-12 |
| 4 | `docker/.env` | 本地环境配置（**不入库**，模板见 `docker/.env.example`） | 本机部署参数（LAN IP、端口、密码） | 2026-10-12 |
| 5 | `executor/license/`（规划）、`server/service.py`（仅挂载行）、`vue/`（新增页面） | 【预留】口令/卡密模块 | 产品需求（M2 落地时逐条补记） | - |
| 6 | `.gitignore` | 追加 `*.log` | 日志不入库 | 2026-10-12 |
| 7 | `executor/IPDB.BIN` | ZIP 下载得到的 LFS 指针还原为真实文件（178,583,751 B, sha256 b9a4f94d…c6aefea8，取自官方镜像） | GitHub ZIP 不含 LFS 实体，pigeon 初始化报 IP2Location 错误；本仓库按 LFS 正常跟踪 | 2026-10-12 |
| 8 | `entry` / `nginx.conf` / `docker/docker-compose.yml` | 新增 TLS_DISABLED 开关（默认 true=Web 纯 HTTP）；nginx 补 /properties 直通路由 | 测试环境免证书警告；生产置 false 并用 /user 内证书启用 HTTPS | 2026-10-12 |
| 9 | `executor/handlers/event.py` | set_gateway 支持 DIRECT_GATEWAY_HOST 覆盖（仅 direct 模式）；新增 import os | 模拟器/NAT 场景设备上报内网地址容器不可达；本机调试经 adb 端口镜像桥接 | 2026-10-12 |
| 10 | `executor/auth.py` | properties 配置支持 DIRECT_API_PORT 固定设备 API 端口 | 调试时端口稳定，adb 转发不失效 | 2026-10-12 |
| 11 | `server/service.py` | Service.__init__ 追加 license 路由挂载（16 条，仅新增行） | license 模块 API 挂载点 | 2026-10-12 |
| 12 | `executor/initialize.py` | 追加 license 模型导入 | Base.metadata 建表需先注册模型 | 2026-10-12 |
| 13 | `executor/license/*` | 【新增】口令/卡密模块：models / util / engine（设备迁移机器） / api | M2 业务实现 | 2026-10-12 |
| 14 | `executor/license/engine.py`、`executor/license/api.py`、`server/service.py` | activate 增加 action 参数；新增 pause/resume/stop_run 运行控制 + 3 条客户端路由（`/license/pause|resume|stop`，X-License-Token 鉴权） | M3：app 端启动/暂停/结束脚本控制 | 2026-10-13 |
| 15 | `nginx.conf`、`html/__pigeon/ai-persist.js` | /d/ 代理增加 Lua body_filter：向 lamda WebUI HTML 注入同源脚本（AI 设置存 localStorage，跨设备回填）；新增 `/__pigeon/ai-persist.js` 静态路由；代理按要求禁用上游压缩 | 远程控制页 AI 设置/Vision Mode 每次换设备都要重输 | 2026-10-13 |
| 16 | `executor/license/engine.py`、`executor/license/api.py` | 卡密激活/恢复时强制 license 作业回到 running（清 stop_time、重置 DeviceJob）；`/me` 返回 `job` 状态 | 任务派发要求 Job.state==running；管理端误暂停/停止作业后，卡密「启动脚本」表面成功但任务永不下发 | 2026-10-13 |
| 17 | `executor/license/api.py`（cards 接口） | `/license/cards` 支持 `keyword`（卡密/设备ID 模糊搜索）并内联返回每卡的 `bindings`（dev_id/domain/设备在线态） | 管理端卡密列表搜索 + 设备跳远控 | 2026-10-13 |
| 18 | `vue/src/pages/LicensePage.vue`、`ScriptsPage.vue`、`vue/src/lib/locales-*.ts` | 卡密页：搜索、绑定设备可点跳远控、发布更新入口（强制/确认语义说明）；脚本页新增「卡密列表」入口（`/license?script_id=`） | 管理端卡密工作流 | 2026-10-13 |
| 19 | `executor/license/engine.py`、`executor/license/api.py`、`vue/src/components/routes/ScriptDetailPage.vue`、`vue/src/pages/LicensePage.vue` | 发布目标版本语义：`/license/release` 支持 `version_id`（任意版本"强制更新"，可回滚）；确认式更新（pending/accept）以"强制组当前版本"为目标；版本历史每项加「强制更新」按钮；移除卡密页发布入口 | 用户需求：强制更新入口放脚本版本历史、支持回滚 | 2026-10-13 |
| 20 | `executor/license/*`（解绑配额） | 卡密新增解绑配额字段（总/每日/间隔 + 计数）；`/license/card-devices`、`/license/unbind`（app 自助）、`/license/card/{id}/unbind-policy`（管理端）；`switch` 支持 card_id 快捷切换；`my-cards` 返回 card_id | app 端卡密列表/切换/绑定设备/解绑需求 | 2026-10-13 |
| 21 | `executor/license/engine.py`、`executor/license/api.py` | 「激活≠启动」状态机：activate 不再自动入组（state=idle），启动(resume)才入组；切换自动停旧+标记 paused；同卡激活幂等；发布时强制组/作业自动创建（发布目标持久化）；verify/switch 返回 state | 用户要求：激活/切换后需手动点击「启动脚本」 | 2026-10-13 |
| 22 | `executor/license/*`（更新模型修正） | 一组一版本（`license:<批次分组名>:v<版本id>`）；`binding.run_version_id` 记录运行版本；`release` 端点语义=强制更新（全量迁移/回滚）；accept=用户自更新；卡密不再分强制/确认 | 用户口径修正：发布不自动更新任何人，用户可自更新，或后台强制全量 | 2026-10-13 |
| 23 | `executor/license/models.py`、`api.py` | `license_batch.group_tag`（批次分组，默认=批次号，可切换/合并）；批次生成不再需要更新策略；`POST /license/batches` 切换分组；`/me`、pause/stop/accept 以"设备当前启用卡"为准 | 批次即分组 + 悬浮窗状态一致性 | 2026-10-13 |
| 24 | `executor/license/*`、`server/service.py`、`vue/` | App 版本管理：`license_app_release` 表 + `/license/app`（GET/POST）+ `/me` 下发 app 版本信息；管理端「App 版本」弹窗；卡密列表新增「脚本/版本」列；resume 作用于设备当前启用卡并返回 token | app 更新提示 + 卡密可读性 | 2026-10-13 |
| 25 | `executor/license/models.py`、`engine.py`、`api.py` | 设备黑名单（`license_blacklist` 表，拉黑即撤任务/停发/活动绑定 paused；verify/switch/resume 拒绝，`/me` 下发 `blacklisted`）；卡密防爆破（`verify_fail` 审计计数 + 窗口锁定，`LICENSE_VERIFY_MAX_FAILS` / `LICENSE_VERIFY_LOCK_SECONDS` 可配）；`/license/cards` 支持 POST 拉黑/解除，bindings 增加 id/blocked，响应增加 blacklist 数组 | 用户要求：设备拉黑后无法用卡密/拉脚本；输错 5 次限制验证 | 2026-10-13 |
| 26 | `executor/license/api.py`（batch/create）、`vue/src/pages/LicensePage.vue`、`vue/src/lib/locales-*.ts`、`app`（LicenseController.kt） | 生成卡密时即创建批次分组（`license:<批次号>:v<最新版本>`）；批次「分组」改下拉选择现有分组；卡密列表设备行内 [解绑][拉黑]、移除「绑定」Tab；App 版本提示改为语义化版本比较 + 拉黑/锁定错误中文提示 | 用户体验反馈 | 2026-10-13 |
