<div align="center">

# AutoRPA

**开箱即用的安卓 RPA 自动化控制客户端**

卡密（口令）授权 · 一键启动/暂停脚本 · 悬浮窗控制 · 在线更新

![version](https://img.shields.io/badge/version-v1.4-brightgreen)
![platform](https://img.shields.io/badge/platform-Android%208.0%2B-blue)
![license](https://img.shields.io/badge/license-Proprietary-lightgrey)

</div>

---

## 简介

**AutoRPA** 是一套面向安卓设备的 RPA（Robotic Process Automation）自动化控制客户端。

配合授权服务器与设备端自动化内核（lamda），可以实现：

> 运营方在后台批量生成**卡密**并下发**自动化脚本** → 用户在 App 内输入卡密激活设备 → 一键启动脚本，设备自动执行任务。

App 负责「授权 + 控制 + 守护」，真正的自动化执行由设备端内核完成（支持 root / Shizuku / 无障碍等多级权限）。

## 功能特性

| 功能 | 说明 |
|---|---|
| 🔑 卡密激活 | `XXXX-XXXX-XXXX-XXXX` 格式卡密（含校验位），支持永久卡 / 有效期卡 |
| 🔄 卡密切换 | 一设备可绑定多张卡密，随时切换；同时只有一张卡在运行 |
| ▶️ 运行控制 | 启动 / 暂停 / 结束脚本；所有操作以「设备当前启用的卡」为准，不会错位 |
| 🎈 悬浮窗面板 | 默认小圆点（可拖动），点击展开 启动/暂停、结束、回主页 三个图标按钮 |
| 📦 在线更新 | 后台发布新版本后，App 内一键更新（可选，不强制）；支持后台强制更新/回滚 |
| 🔁 换机解绑 | 凭卡密即可查询绑定设备并解绑（可配置解绑次数/间隔限额） |
| 📇 我的卡密 | 卡密列表固定排序，展示状态、脚本、创建时间，支持快捷切换与绑定情况查询 |
| 🛡️ 安全防护 | 卡密错误次数超限自动锁定；设备可被后台拉黑，拉黑后无法使用卡密或运行脚本 |
| 🩺 自动守护 | 内核进程异常退出自动拉起；设备离线自动重启内核；保持屏幕常亮防止注入失败 |

## 工作原理

```
┌──────────────┐        授权/控制 API        ┌───────────────┐
│   AutoRPA    │ ──────────────────────────▶ │   授权服务器    │
│  （本 App）   │ ◀────────────────────────── │ （卡密/脚本/版本）│
└──────┬───────┘        状态/任务下发         └───────────────┘
       │ 启动/停止
       ▼
┌──────────────┐
│  设备端内核     │  ← 自动化脚本（点击/滑动/图像识别/OCR…）
│   (lamda)     │
└──────────────┘
```

- App 本地仅保存配置（激活状态、卡密索引等），业务规则全部由服务器统一管理；
- 脚本按**批次分组**组织，同批次设备进入同一分组；
- 版本按**分组+版本**隔离，支持「用户自选更新」与「后台全量强制更新」两种模式。

## 快速开始

1. **下载安装**  
   从本仓库 [Releases](../../releases) 或 `release/` 目录下载最新 APK（`AutoRPA-vX.Y.apk`），安装到安卓设备（Android 8.0+）。
2. **打开 App** → 进入「卡密」页。
3. **新增卡密**：输入运营方提供的卡密，点击激活（首次激活可能需要等待服务器接入设备）。
4. **启动脚本**：点击「启动脚本」开始运行；需要暂停/结束时在 App 或悬浮窗中操作。
5. 可选：打开「悬浮窗」开关，在任何界面通过小圆点控制脚本。

> 提示：部分设备需要授予悬浮窗权限；自动化执行需要 root / Shizuku / 无障碍等权限，具体以运营方要求为准。

## 常见问题

**Q：提示「设备尚未接入服务器」？**  
运营方需要在服务端完成设备接入（首次安装后会自动上报设备信息）。

**Q：输入卡密提示错误？**  
检查卡密是否输入完整；连续错误 5 次会进入锁定（约 10 分钟），期间即使卡密正确也会被拒绝。

**Q：提示「设备已被管理员拉黑」？**  
请联系运营方解除拉黑。拉黑状态下无法使用卡密、无法启动脚本。

**Q：App 更新提示点了「取消」还会再弹吗？**  
同一版本只提示一次；后台发布更高版本时会再次提示。点击「前往下载」跳转浏览器下载新版本。

**Q：换设备了怎么办？**  
在「查询绑定」中输入卡密，将旧设备解绑后即可在新设备激活（解绑次数由运营方限制）。

## 构建

环境要求：JDK 17+、Android SDK（compileSdk 34）。

```bash
# Debug 构建
./gradlew :app:assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk

# Release 构建（需要签名配置，见下）
./gradlew :app:assembleRelease
# 产物：app/build/outputs/apk/release/app-release.apk
```

**签名说明**：Release 构建读取根目录 `keystore.properties`：

```properties
storeFile=autorpa-release.jks
storePassword=******
keyAlias=autorpa
keyPassword=******
```

密钥文件不入库（见 `.gitignore`）。自行构建发布版时请使用你自己的密钥；**正式版本的包名 `com.autorpa.console` 与签名不可更换**，否则用户无法覆盖安装升级。

**版本号**：由 `git describe --tags --always` 自动生成（如 `v1.4` → 版本号 `1.4`），发布时打 tag 即可。

## 发布规范

- 版本 tag：`vX.Y`（如 `v1.4`）
- 发布内容：源码 + `release/AutoRPA-vX.Y.apk`
- 用户下载地址示例：`https://gitee.com/suyibk/autorpa/raw/master/release/AutoRPA-v1.4.apk`
- 建议同时在 Gitee「发行版」中上传 APK，获得固定下载页

## 目录结构

```
app/
├── app/src/main/java/com/firerpa/       # 主工程（Kotlin）
│   ├── license/                         # 卡密页：激活/切换/状态/悬浮窗/自愈
│   ├── overlay/                         # 悬浮窗服务（小圆点 + 控制面板）
│   ├── data/                            # 本地配置（AppPrefs）
│   ├── service/ …                       # 内核管理（下载/启动/卸载）
│   └── ui/ …                            # 主页/配置/日志页
├── app/src/main/res/                    # 资源（多语言：zh / en）
├── release/                             # 发布版 APK（随 tag 提交）
├── docs/                                # 截图等文档资源
└── keystore.properties                  # 签名配置（不入库）
```

## 免责声明

本项目仅用于**合法授权**场景下的设备自动化管理与个人效率提升。请遵守设备厂商与相关平台的服务条款，勿用于任何违反法律法规的用途。

---

<div align="center">
<sub>基于开源项目 <a href="https://github.com/FIRERPA">FIRERPA</a> 与 lamda 二次开发 · 交流/反馈请提 Issue</sub>
</div>
