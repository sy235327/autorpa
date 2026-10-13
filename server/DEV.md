# pigeon-fork 本地开发手册（本机 · Windows Docker Desktop）

## 组成

- 本仓库 = `firerpa/pigeon` 二开 fork（服务端，含口令/卡密模块规划）。
- 配套：自研 Android app 壳（待开工）。
- 镜像名：`pigeon-fork:1.0`（本地构建）。

## 构建

```powershell
cd I:\projects\android_projects\firerpa\pigeon-main
docker build --progress=plain -t pigeon-fork:1.0 .
# 构建日志：I:\projects\android_projects\firerpa\pigeon-build.log
```

## 启动

```powershell
cd I:\projects\android_projects\firerpa\pigeon-main\docker
docker compose -f docker-compose.yml up -d
docker compose -f docker-compose.yml logs -f pigeon   # 观察 entry 初始化
```

- 面板：http://127.0.0.1:8000 （默认账号 `admin` / `pigeon`；测试环境纯 HTTP（TLS_DISABLED=true））
- 端口：WEB_PORT=8000 · API_PORT=65000 · FWD_PORT=7000 · MQTT=8883

## 本机设备（MuMu 模拟器）接入约定

- 设备 lamda 服务：模拟器内 `65000`；
- 宿主机映射：**`tcp:65050 → 设备 65000`**
  `adb -s 127.0.0.1:5557 forward tcp:65050 tcp:65000`
- 容器内访问设备 lamda（direct 接入用）：`host.docker.internal:65050`
- 设备访问宿主机服务：MuMu NAT 网关 `10.0.2.2` 或宿主 LAN IP `192.168.1.7`
- 设备标识：lamda `unique_id`（= pigeon `dev_id`）

## 日常操作

- 改代码 → 重新 `docker build` → `docker compose up -d --force-recreate pigeon`
- 数据目录：`docker/data/`（postgres/rabbitmq/user 等运行时数据）
- 上游同步：按 `PATCHES.md` 清单逐条 rebase

## 约定

- 不要提交 `docker/data/`、`docker/.env`、日志；
- 不要用 `git checkout` 批量重置（Windows 行尾）；仓库已配置 `core.autocrlf=false` + `.gitattributes` eol=lf 规则；
- 设备侧 lamda 的访问凭据（cert / ssl-web-credential）后续由 app 壳代持，脚本/人工调试统一走 `65050` 转发。

## 重要：LFS 文件（IPDB.BIN）
- 获取本仓库请用 **git clone + git lfs**；直接下载 GitHub ZIP 会得到 LFS 指针文件（`executor/IPDB.BIN` 仅 134 字节），pigeon 启动会报 `Incorrect IP2Location BIN file format` 并卡在初始化重试。
- 本仓库内该文件已还原为真实文件（约 170MB，sha256 `b9a4f94d557d57ceec49bc21d26d2e144cc5191b2d9e26989be89275c6aefea8`）。

## Web 访问方式
- 测试环境默认 **HTTP**：http://127.0.0.1:8000 （compose 里 TLS_DISABLED=true）。
- 生产启用 HTTPS：把 server.crt / server.key 放进 docker/data/user/，把 TLS_DISABLED 置为 false 后重建容器。



## 投屏 / 网关代理（/d/<domain>/）
- 链路：浏览器 → nginx `/d/<domain>/` →（地址取 Redis `gw:<domain>`）→ 设备 lamda API（HTTPS）。
- 本机模拟器：`DIRECT_GATEWAY_HOST=host.docker.internal`（.env 已配）+ **adb 端口镜像转发**：`adb -s 127.0.0.1:5557 forward tcp:<N> tcp:<N>`（N=设备 service_port）；`DIRECT_API_PORT=53383` 固定该端口（镜像重建+重建容器后生效，防止端口漂移）。
- 排查：`docker compose logs pigeon` 找 `connect() failed ... upstream`；核对 Redis `gw:<domain>` 与转发端口一致。
- 注：上文旧的 `65050→65000` 约定在设备完成入网后已失效（lamda API 端口变为 service_port），以本节为准。


## M2 验收记录（2026-10-12）
- 用例 D 全流程通过：批量开卡(force/confirm) → verify 激活 → 任务自动下发执行（回主页+切任务页冒烟脚本 v1.0.1）→ release 强制更新自动生效 → 切卡（latest→stable 迁移）→ release(confirm) 仅提示不自动升 → accept 后升级（v1.0.2）→ 封卡即时停止（执行行数 798→798）→ 恢复自动续跑（798→820）→ 审计 12 条完整。
- 冒烟脚本：`dev/smoke_home_recents.py`（脚本契约：FireRPABaseTaskExecutor/FireRPATaskExecutor + lamda.client Keys 枚举按键）。
- 已知 quirks：设备心跳线程偶发卡死（lamda.cron.ticker 满并发告警）→ pigeon 判离线但命令通道正常；执行 `/data/local/tmp/restart_lamda.sh` 重启即恢复。


## M3-1 验收记录（2026-10-13）
- app 壳（二开自官方 firerpa/app v1.3，仓库 `../app`，占位包名 `com.rpac.console`）已构建安装 5557；新增「卡密」Tab（服务器地址/端口/口令/设备ID + 校验并激活/刷新状态），本地持久化重启复用。
- 全链路通过：app verify → 服务端 binding 转 active（审计 actor=verify）→ 页面「激活成功/脚本/策略/到期」；`/me` 刷新 → 激活:是 · 设备在线 · 更新可用 1.0.2；lamda 拉起后设备回 online、job_5 自动投递并执行（smoke 续跑）。
- 构建：JDK21 + `I:\windows\android-sdk`；`cd ../app; .\gradlew.bat :app:assembleDebug`；Gradle 分发已切腾讯镜像（见 app/FORK.md 改动清单）。
- 联调 quirks（重要）：
  - 冒烟脚本常驻，每 ~3s 按 HOME/切任务页，UI 自动化前先停 lamda：`su -c 'sh /data/local/tmp/stop_lamda.sh'`（恢复用 restart_lamda.sh）。
  - 官方 app（com.firerpa）常驻且带 lamda 守护，kill 后会被它拉起；联调先 `am force-stop com.firerpa`。
  - 让自研 app 免「安装服务」弹窗并认领现有 lamda：写 `com.rpac.console` 的 prefs（`directory_prefix=2yOBD9`、`installed_version=10.9`）。
  - Android 9+ 明文 HTTP 会被拦：fork 已加 `android:usesCleartextTraffic="true"`（生产 TLS 后移除）。
  - 待办：unique_id 自动读取（当前手动填写，路径调研见方案-v2 风险 2）。

## M3-2 记录（2026-10-13）
- app 端（`../app`）：
  - 「卡密」Tab 置顶为第一个且为默认启动页；卡密页新增脚本控制（启动/暂停、结束）、检测到非强制更新时显示「立即更新」（调 `/update/accept`）、悬浮窗开关。
  - 悬浮窗：默认小圆点（可拖动、纯图标）→ 点击展开 [启动/暂停][结束][回主页]；无 ✕（关闭途径=回 app 关悬浮窗开关）。
  - 「启动脚本」会自动确保设备端 lamda 服务在运行（root 执行 launch.sh），激活成功同样触发。
- server 端（本仓库）：
  - 新增客户端接口 `/license/pause|resume|stop`（X-License-Token）。
  - **关键修复**：卡密激活/恢复时强制作业回 `running`（清 stop_time、重置 DeviceJob）——此前管理端误暂停/停止作业会导致「app 点启动脚本没反应」（任务派发条件要求 Job.state==running）。
  - `/license/cards` 支持 keyword（卡密/设备ID）搜索 + 内联绑定设备；`/me` 返回 `job` 状态。
  - 管理端：卡密页搜索 / 绑定设备点击跳远控（`/d/<domain>/`）/「发布更新」入口（说明强制+确认语义）；脚本页新增「卡密列表」入口（`/license?script_id=`）。
  - `/d/` 远程页注入 `ai-persist.js`：AI 设置（API/提示词/Vision Mode 等）存浏览器 localStorage，换设备自动回填（不经过服务器）。
- quirks（补充）：
  - 管理端作业页的 暂停/停止/继续 会直接影响 license 作业下发；stopped 作业经 API 无法恢复（J7003），现在由卡密激活路径强制恢复。
  - 悬浮窗/冒烟脚本同时存在时做 UI 自动化测试要留意（冒烟每轮按 HOME+RECENTS）。
  - 容器内代码路径：`/usr/lib/python3/dist-packages/executor/...`（docker cp 迭代调试用）。

## M3-3/4 记录（2026-10-13 续）
- **解绑配额**：卡密支持 总次数/每日次数/间隔秒（0=不限）+ 计数；app 自助解绑（`/license/unbind`）、绑定设备查询（`/license/card-devices`）、管理端策略编辑（`/license/card/{id}/unbind-policy`）+ 生成时配置。错误码：`unbind_total_limit_reached` / `unbind_daily_limit_reached` / `unbind_interval_not_reached_{n}s`。**自助解绑本机会立即失效该会话（token 轮换）**。
- **快捷切换**：`/license/switch` 支持 `card_id`（无需明文卡密）；app「我的卡密」列表 = `/license/my-cards`（含 card_id）。
- **版本目标语义（可回滚）**：`/license/release` 支持 `version_id` → 强制组切到任意版本（含回滚）；确认式更新（pending/accept）以**强制组当前版本**为目标版本；管理端入口挪到 脚本详情页「版本历史」每项的「强制更新」按钮（卡密页不再提供发布）。实测：回滚到 1.0.1 → 设备日志真实变 v1.0.1；再发布回最新 → 恢复。
  - 备注：script 2 的 v1.0.3/v1.0.4 代码里日志字符串仍是 "v1.0.2"（复制自 1.0.2 未改），属脚本内容差异，非系统问题。
- **app 新增**：我的卡密列表 + 快捷切换 + 绑定情况对话框（设备列表/解绑/配额提示）+ 解绑后会话自动复位；悬浮窗改为「小圆点→展开三图标」。
- 联调坑：并发 `docker compose build` 会撞容器名（`xxxx_pigeon` 冲突）→ 串行执行；`Select-Object -First` 截断管道可能中断 docker 构建。

## M3-5 记录（2026-10-13 续2）——「激活≠启动」状态机（与 pigeon 作业模型融合）
- **状态机**：激活/切换 → `idle`（已激活·未启动，**不入组**）；启动 → `active`（入组执行）；暂停 → `paused`；结束 → `stopped`；停用 → `kicked`。
- **两层模型**：
  - 作业 Job / 组 Group = 内容与版本（Job.script_id）+ 调度开关（Job.state）：发布/回滚改版本；作业被误停时由「启动」自愈（ensure_license_group_job）。
  - UserDevice.group_id = 设备是否参与（等价"脚本是否在跑"）：启动=入组、暂停/结束/切换=退组+撤销在途任务。
- **切换语义**：activate 先停旧（退组+revoke）→ 目标卡置 idle；被切走的绑定自动标记 `paused`；**同卡重复激活幂等**（运行中不受打扰）。
- **发布目标持久化**：发布（含回滚）时若强制组/作业不存在则自动创建 → 即使还没人启动过，目标版本也不会丢（启动时按目标版本入组）。
- **升级提示**：未运行时无提示（pending_update 依赖组成员关系）；「立即更新」=迁移到强制组（跟随最新版本）。
- **接口**：verify/switch 响应新增 `state`；`/license/me` 的 state 现在可能是 `idle`。
- **验收**：①激活卡1 → `idle` + 组为空 + 无任务下发；②启动 → 入组 4 + 任务开跑；③切换到卡2 → 卡1 自动 `paused`、卡2 `idle`、退组；④启动卡2 → 入组 5 + 执行。✅
- **我的卡密列表固定排序**：按卡密创建时间升序（+id 兜底）并过滤已解绑；行内显示创建时间（避免切换后顺序跳动分不清哪张在跑）。app 端配套显示。✅
- **熄屏会导致按键注入失败**：设备日志 `injectInputEvent failed for displayId 0`，任务 reason 为空但 failed（伴随心跳卡死判离线）。处理：`input keyevent 224` 唤醒 + `svc power stayon true` + `settings put system screen_off_timeout 2147483647`；app 端已在 激活/启动 时自动设置屏幕常亮（root）。

## M3-6 记录（2026-10-13 续3）——更新模型修正 + 批次分组 + 稳定性修复
- **更新模型修正（按用户口径）**：卡密不再分强制/确认；策略是"发布动作"级别：
  - **正常发布版本**：不影响任何设备（设备在自己的版本组里），app 显示"有可用更新"，用户点「立即更新」才迁移。
  - **后台强制更新**（版本历史每项按钮）：`/license/release {script_id, version_id?}` = 把目标版本推给该脚本**全部设备**（运行中立即迁移，未运行记录 run_version_id）；传旧版本即回滚。
  - 机制：**一组一版本**，组名 = `license:<批次分组名>:v<版本id>`；`binding.run_version_id` 记住每台设备运行版本；暂停→启动保持原版本。
  - 实测：发布 v1.0.6（设备仍跑 1.0.5）→ accept 升到 1.0.6 → 强制回滚 1.0.1（设备日志变 v1.0.1）→ 暂停/启动保持 1.0.1 → 强制回最新恢复。
- **批次分组**：`license_batch.group_tag`（默认=批次号，可切换/合并到其他分组名）；同批次卡密激活/启动进同名分组；切换只影响之后的激活/启动，历史设备到「分组」页手动迁移。批次生成卡密不再选更新策略；批次表「分组」列 + 切换弹窗；`POST /api/v1/license/batches {batch_id, group_tag}`（复用路由，免重建镜像）。
- **悬浮窗状态一致性**：`/me`、暂停/结束/更新接受 均以"**设备当前启用的卡**"为准（token 属于设备上任一卡都能正确显示与控制）；悬浮窗每 10s 自动刷新；修复"页面绑定前生命周期回调拿不到 binding 导致悬浮窗不启动"的 bug（bind() 中补 syncOverlaySwitch）。
- **/d/ 投屏偶发 502**：`adb forward tcp:53383` 易失（adb 重启/模拟器抖动即丢）→ 提供看护脚本 `keep_adb_forward.ps1`（每 10s 检查补回）；生产环境用设备直连地址/FRP 不受此影响。

## M3-7 记录（2026-10-13 续4）——卡密页改版 + App 版本管理
- **卡密页（app）改版**：页面只保留 [新增卡密] [查询绑定] 两个弹窗入口 + 状态（**每 5 秒自动刷新**，前台时）+ [启动脚本/暂停][结束脚本] + 悬浮窗开关 + 我的卡密列表；服务器/端口/口令/设备ID 与「查询绑定/解绑」全部移入弹窗。
- **运行控制以"设备当前启用的卡"为准**：`/me`、暂停/结束/更新、**启动(resume)** 全部作用于设备当前启用卡；resume/switch/verify 返回 `session_token`，app 自动对齐会话 → 不会再出现"输入框/会话与正在运行的卡不一致"。
- **App 版本管理**：表 `license_app_release`（版本号/下载地址/备注 单行）；管理端 App 版本弹窗配置；`/license/me` 下发 `app{version,url,note}`；app 启动/刷新时比对自身版本，不一致则弹窗提示「前往下载」（同版本只提示一次，记 `skipped_app_version`）。
  - 二开包为全架构通用 APK（无原生代码；lamda 按设备 ABI 运行时下载）→ 一个下载地址即可。

## M3-8 记录（2026-10-13 · 第5轮：卡密页设备操作 + 设备黑名单 + 卡密防爆破）
- **卡密列表内置设备操作**：绑定设备行内直接 [解绑] [拉黑/解除拉黑]，独立「绑定」Tab 移除；`/license/cards` 的 bindings 增加 `id`（解绑用）与 `blocked`；响应顶层增加 `blacklist` 数组 → 页面上方显示「黑名单」条，可一键解除。
- **设备黑名单**（表 `license_blacklist`）：
  - 管理端 `POST /license/cards {dev_id, blocked(1/0), reason}` 拉黑/解除；拉黑即时撤销在途任务 + 停止下发 + 活动绑定置 `paused`。
  - 拉黑后：**无法使用卡密**（verify/switch → `device_blacklisted`），**无法启动脚本**（resume 拒绝）；`/me` 下发 `blacklisted`，app 显示拉黑横幅。
  - 解除拉黑即恢复（历史绑定保留，用户可重新点「启动脚本」）。
- **卡密防爆破**：同一设备（dev_id）滑动窗口内口令失败达阈值即锁定一段时间：
  - 默认 **5 次 / 锁 600 秒**，可用环境变量覆盖：`LICENSE_VERIFY_MAX_FAILS`、`LICENSE_VERIFY_LOCK_SECONDS`。
  - 计数落在 `license_audit`（action=`verify_fail`）；锁定期内**即使卡密正确**也返回 `verify_locked_retry_in_<秒>s`，继续尝试会延锁；app 中文提示「口令错误次数过多，请约 X 分钟后再试」。
- **批次分组下拉 + 生成即建组**：批次页「分组」改为**下拉选择现有分组**（拉 `/api/v1/group` 自动剥离 `license:` / `:v<版本>` 归并选项）；**批量生成卡密时即创建** `license:<批次号>:v<最新版本>` 分组 + 作业（测试批次 B261013-36R7TA → 分组/作业即建，验证后已清理）；切换分组仍只影响之后的激活/启动。
- **App 更新提示版本比较**：改为数字段语义化比较（仅当后台版本 > 当前版本才提示；git-describe 版本 `1.3-10-gxxxx` 正确比较，回滚旧版本不误报）；弹窗当前版本显示去 git 后缀（`v1.3`）。
- **实测**：`/license/app` 401=已注册；卡密列表 job 字段=`license:B261013-FRXCN3:v8#job`；拉黑→`/me.blacklisted=true` + verify 拒 `device_blacklisted`；解除后恢复；防爆破 5 连错 → `verify_locked_retry_in_601s`（正确卡密同样被拦，清 `verify_fail` 记录后恢复）；app 截图：拉黑横幅 ✅、更新弹窗「v1.5（当前 v1.3）」✅。
- **踩坑（重要）**：镜像重构建完成后的 `up -d --force-recreate` 会**覆盖此前 docker cp 的热更文件**；重建完成后需重新 `docker cp` 最新 py/html 再 `docker restart pigeon`。构建卡死时可用 `Get-CimInstance Win32_Process -Filter "Name='docker.exe'"` 找 compose 进程并 kill（注意可能仍会跑完并触发 recreate）。

## 重要：Cython 编译边界（docker cp 的适用性）
- `build-main.sh` 会把下列文件 **Cython 编译成 `.so`**（运行时优先加载 `.so`，且编译后**删除 `.py`**）：
  `server/service.py`、`executor/queue.py`、`executor/event.py`、`executor/utils.py`、`executor/handlers/event.py`、`executor/config.py`
- → 改这些文件**必须 `docker compose build` + `up -d --force-recreate`**（docker cp 不生效！踩过坑：新路由一直 404）。
- 其余文件（如 `executor/license/*`、`vue/`、`html/`、`nginx.conf`）可以用 `docker cp` + `docker restart pigeon` 快速迭代。

## 前端（Vue）构建
- 目录 `vue/`；流程：`npm install`（国内可加 `--registry=https://registry.npmmirror.com`）→ `npm run build` → 将 `vue/dist/*` 覆盖到 `html/` → 容器内替换 `/var/www/html`（或重建镜像）。
- 已知坑：vite8(rolldown) 在部分镜像源下漏装原生绑定（报 `rolldown-binding.win32-x64-msvc.node` 缺失）；修复：`npm i @rolldown/binding-win32-x64-msvc@<rolldown版本>`（勿提交进 package.json）。
- 卡密管理页：`vue/src/pages/LicensePage.vue`（导航「卡密」；批次/卡/绑定/审计 + 生成卡密向导 + CSV 导出）。

