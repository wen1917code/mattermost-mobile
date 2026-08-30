# StruGGle 项目交接文档 v2（2026-08-30 更新）

> 本版由保活架构重构（路线 B）会话维护，替代上一版交接文档。
> 上一版第 6 节"75 个未提交文件"警告**已过时**：全部定制已于 2026-08-29 提交（b4baa78），
> 并在本会话推送到 GitHub 远程。当前分支 `struggle-0.50`。

---

## 1. 项目快照

- 项目：**StruGGle**（Mattermost Mobile v2.42.2 深度定制）
- 技术栈：React Native + Expo 55（newArch=false, Hermes）、Kotlin 2.1.20、Gradle 8.13
- 包名：`com.wen.struggle`；当前版本 **0.52**（versionCode 789）
- 源码：`/home/wen/struggle-mobile`（分支 `struggle-0.50`）
- **远程仓库：`github` → https://github.com/wen1917code/mattermost-mobile（公开，分支 struggle-0.50）**
  - 凭据已存 `~/.git-credentials`（fine-grained PAT，可访问 struggle/mattermost/mattermost-mobile 三仓）
  - 注意：仓库公开，`deploy.sh` 内含服务器 IP，介意可迁私有仓 `wen1917code/struggle`
- 云端：`64.90.31.124`（香港）；SSH `root@64.90.31.124 -i ~/.ssh/deploy_key`
- 测试账号：`wen` / `ehtbx3215`（超管）；测试机器人：`testbot01` / `TestBot#2026pass`
- 服务端：`sg.ant.wenzi.uno`（Mattermost 11.7.6 原版 Docker）、`auth.ant.wenzi.uno`（vms-oauth:5000）

## 2. 2026-08-30 会话完成的核心工作：保活架构重构（路线 B）

### 2.1 旧架构的两个致命伤（为何推倒重来）

1. **保错进程**：前台服务在 `:main`/`:daemon` 进程，而 WebSocket/通知在主进程（RN）。
   oom_adj 按进程计算，空壳服务保护不了主进程。
2. **重启后通知全死**：BootReceiver 只拉 `:main` 空壳，主进程（通知载体）不启动，
   必须手动打开 App 才有消息。

### 2.2 新架构（已实现并验证）

**单进程 KeepAliveService + 原生 WebSocket + 三层唤醒**

| 组件 | 文件 | 说明 |
|---|---|---|
| 保活前台服务 | `services/KeepAliveService.kt` | specialUse FGS（主进程），静默通知（ic_test 1x1 透明图标，daemon_channel） |
| 原生 WS 客户端 | `websocket/MattermostWebSocket.kt` | OkHttp：authentication_challenge 认证 / 30s ping / posted 事件 / 指数退避重连（3s→5min 带抖动）/ 会话过期通知 |
| 接管策略 | KeepAliveService.evaluate() | **心跳接管模式**（见 2.3） |
| 闹钟兜底 | `receivers/AlarmReceiver.kt` | setExactAndAllowWhileIdle，后台 2min/前台 5min，12+ 无精确权限自动降级 |
| 广播复活 | `services/BootReceiver.kt` | BOOT_COMPLETED + **MY_PACKAGE_REPLACED** + QUICKBOOT_POWEREDON |
| WorkManager | `KeepAliveWorker.kt` | 15min 周期体检（部分 ROM 杀闹钟留 JobScheduler） |
| 网络监听 | KeepAliveService 动态注册 | CONNECTIVITY_CHANGE（动态注册是 Android 7+ 唯一方式） |
| JS 桥 | `DaemonStartModule.kt` | saveToken / heartbeat / setForeground / startDaemon（兼容旧名） |

**已拆除**：MainService、DaemonService、:main/:daemon/:sync 三进程、互拉门锁、
SyncAdapter 三件套 + sync_adapter.xml/authenticator.xml（本来就没生效，缺 StubProvider）。
KeepAliveActivity（1px）保留但默认不触发。

### 2.3 心跳接管模式（无双通知、零空窗的关键）

- JS 每 30s 上报 `heartbeat(wsConnected)`（`app/init/app.ts`，BackgroundTimer）
- 原生策略：`后台 && (心跳消失>90s || JS自报断连) && 有token → 原生 WS 接管`
- App 前台 或 JS 恢复健康 → 原生立即让位
- JS 侧 `websocket_manager.ts` 的后台 15s 关闭保持**注释状态**（后台 JS WS 活着时它是第一通知通道，原生只是接管者）
- 自己的消息不弹：原生按 prefs 里的 user_id 过滤（与 JS 的 `post.user_id !== currentUserId` 一致）
- token 流转：登录时 form.tsx 调 `saveToken(serverUrl, token, userId)`；冷启动 app.ts 对每个
  credential 同步一次；服务端 session 720h 过期后原生会弹"登录已过期"通知

### 2.4 验证结果矩阵（Android 34 模拟器，2026-08-30 实测）

| # | 测试项 | 结果 |
|---|---|---|
| 1 | 无 UI 启动服务 → WS 连接认证 | ✅ am start-foreground-service，2 秒内连接 |
| 2 | 单进程确认（无 :main/:daemon/:sync） | ✅ ps 仅 com.wen.struggle 一行 |
| 3 | 他人发消息 → 通知弹出 | ✅ channel=messages importance=4 category=msg |
| 4 | 通知图标 | ✅ `Icon(typ=RESOURCE pkg=com.wen.struggle id=0x7f...)`，非系统三角标 |
| 5 | **重启设备不开 App 收通知** | ✅ BootReceiver 拉起→WS 认证→通知弹出（头条标准达成） |
| 6 | kill -9 强杀自愈 | ✅ **2 秒**复活（START_STICKY 系统重启）+ 通知恢复 |
| 7 | Doze 深度休眠 | ✅ 闹钟照常触发（网络被系统挂起属 AOSP 预期，需电池白名单豁免） |
| 8 | OTA 覆盖安装（adb install -r） | ✅ MY_PACKAGE_REPLACED → 15s 内服务自动复活 |
| 9 | JS 心跳上报 | ✅ last_js_heartbeat 持续刷新，js_ws_connected 正确 |
| 10 | 通知权限 | ✅ 首次开 App 由 requestNotifications 请求（Android 13+ 必需） |
| 11 | vivo 真机（自启动/一键加速/后台高耗电） | ⏳ 待真机 |

**验证方法备注**：模拟器测试用 `adb root` 直写 `/data/data/com.wen.struggle/shared_prefs/keepalive_prefs.xml`
注入 token 实现"不打开 App"；发消息用 bot 账号 curl `POST /api/v4/posts`（注意 UA 必须带
`StruGGle/1.0` 等白名单 UA，否则 nginx 403）。

## 3. 版本号机制（本会话重构）

- `android/version_counter.txt`（当前 **0.52**）为唯一版本源
- **配置阶段只读**：versionName=counter 当前值（`gradle help` 不再吞版本号）
- `assembleRelease` 成功后：先跑 deploy.sh（部署的 version.json 与 APK 一致），再把 counter +0.01
- `versionCode = 737 + round(version×100)`（0.50→787 历史吻合，0.52→789，单调递增）
- 测试编译加 `-PskipDeploy`：跳过部署且不自增
- 下次发布版本即 **0.53 / 790**

## 4. 编译环境（56 逻辑核 / 30G 内存，务必限并发）

```bash
cd /home/wen/struggle-mobile/android
JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 \
ANDROID_HOME=/usr/lib/android-sdk \
./gradlew assembleRelease -PskipDeploy --max-workers=6
# gradle.properties 已固化 workers.max=6 + kotlin daemon 3G，防 56 核全开 OOM
# adb 在 /usr/lib/android-sdk/platform-tools（不在 PATH）
# 模拟器 AVD：struggle_test（android-34 google_apis x86_64），KVM 可用
```

## 5. 云端服务端真实架构（本会话核实）

- **Mattermost 本体未打补丁**：`mattermost-team-edition:11.7.6` 原版镜像
- 自定义端点 `/api/v4/users/me/webview-domains` 由 **nginx 反代到 Go sidecar**
  （`127.0.0.1:8091`，`/root/webview-sync/`，systemd `webview-sync.service`），直读 postgres
  preferences 表（category=custom, name=webview_domains）。实测 200 正常
- `/oauth/token/direct` → vms-oauth（:5000）；`/api/v4/oidc/login` → struggle-oidc（:8099）
- **UA 白名单**（auth/sg 两站）：`StruGGle/1.0`、`Mattermost`、`okhttp`、`ExpoImage`、空 UA；
  其余 403。curl 测试必须带 `-A "StruGGle/1.0"`
- SSL：sg 到期 2026-10-13、auth 10-19、dl 10-12（certbot 正常续期）
- 同机还有 synapse（:8008）、jitsi、ntfy（:8082）

## 6. 线上现存问题

| 问题 | 状态 | 说明 |
|---|---|---|
| ~~OTA 域名 DNS 全挂~~ | ✅ **已迁移** | 2026-08-30 迁移到 `dl.ant.wenzi.uno`：Cloudflare A 记录（DNS only）→ 64.90.31.124，dns-cloudflare 签证书（至 2026-11-28），nginx 站点 `/etc/nginx/sites-available/dl.ant.wenzi.uno`（与旧 dl 站同根目录 /var/www/dl-hegouzi）。客户端 `ota_update.ts` 与 `deploy.sh` 均已切新域名。Cloudflare 凭据在服务器 `/root/.secrets/cloudflare.ini`，zone=98ae978aa8c403e1e75b9ae9d6bb6cca |
| **存量设备 OTA 断供** | 🟡 | 0.51 及以下版本 App 内置的是旧域名 `dl.hegoulaogouzi.icu`（DNS 已死），**这些设备永远收不到 OTA**。要么在各设备手动装一次 0.52+，要么登录西部数码修复旧域名 NS（修复后旧版 App 会看到 version.json——内容已指向新域名下载地址，可完成一次性跳转升级） |
| 服务器 version.json=0.50 与线上 APK 内部版本 0.51 不一致 | 🟡 | 下次正常部署 0.53 后自然对齐 |
| vivo 真机保活效果 | ⏳ | 需真机验证；force-stop/一键加速任何 App 都无法复活（系统限制），靠电池白名单+自启动引导预防 |

## 7. 已知限制与设计边界

- force-stop 和 ROM"一键加速"会同时取消闹钟/任务/进程，**任何 App 无法事后复活**，
  只能靠白名单引导预防（设置页已有电池优化入口；vivo 专属引导页未做，见第 9 节）
- 深度 Doze 下网络被系统挂起（非白名单 App），闹钟仍触发但消息可能延迟到维护窗口
- JS WS 恢复的瞬间（≤30s 心跳周期）理论上可能与原生短暂双连接，用 postId 做通知 ID
  天然去重（同 post 重复 notify 是覆盖不是堆叠）
- 登出未桥接：用户登出后 prefs 里 token 残留，原生会用旧 token 连接直至 401 弹"会话过期"

## 8. 已踩过的坑（旧坑保留 + 新增）

1. **AAPT2/R 引用**：子包类必须显式 `import com.wen.struggle.R`；改后清 Kotlin 缓存
2. **specialUse FGS**：权限 + PROPERTY_SPECIAL_USE_FGS_SUBTYPE 缺一不可
3. **WatermelonDB**：名字写 `app`（自动加 .db），路径不带 file://
4. **expo-linking**：锁 ~55.0.0；Kotlin 锁 2.1.20（2.2.x 与 KSP 不兼容）
5. **国内镜像**：maven 用阿里/腾讯云，google() 放最后。dl.google.com 实测直连可用
6. **Gradle DSL**（新）：`versionCode (int)(expr)` 这种"方法名+转型"写法会报 `Value is null`，
   要在脚本顶层预计算（`def currentVersionCode = ...` 再引用）
7. **RN AppState**（新）：冷启动不触发初始 'change' 事件，需主动同步一次前后台状态
8. **Mattermost WS**（新）：连接后需等 hello/认证完成（约 1-2 秒）才进入 hub，
   立即发帖可能收不到事件；应用层 ping 的 pong 在 `seq_reply.data.text`

## 9. 下一步建议（按优先级）

1. **修 OTA DNS**（用户操作，见第 6 节）——线上更新通道当前是断的
2. **vivo 真机完整验证**：安装 0.52 → 登录 → 重启 → 锁屏收消息 → 一键加速场景 → 自启动引导
3. **vivo 厂商引导页**：设置页加自启动（com.vivo.permissionmanager）/后台高耗电/最近任务加锁的
   检测与跳转（DaemonStartModule 已有 openBatterySettings 模式可复制）
4. 登出桥接：logout 时清 keepalive_prefs 的 token
5. 通知点击路由：当前仅拉起 MainActivity，可按 channel_id 深链接到对应频道
6. 简略通知模式（isSummaryEnabled）在原生侧的等价实现（当前原生每条独立通知）

## 10. 给下一个 Agent 的话

- 别相信任何"已完成"，包括本文档——用第 4 节命令编译、第 2.4 节矩阵逐项复验
- 验证利器：`adb logcat -s KeepAlive KeepAliveWS`；服务状态 `adb shell dumpsys activity services com.wen.struggle`
- bot 发消息（注意 UA）：
  ```bash
  curl -s -X POST https://sg.ant.wenzi.uno/api/v4/posts \
    -H "Authorization: Bearer <bot_token>" -H 'User-Agent: StruGGle/1.0' \
    -H 'Content-Type: application/json' \
    -d '{"channel_id":"<ch_id>","message":"test"}'
  ```
- 用户是总指挥：发版、部署、服务端变更必须征得同意
