# TinyBlock — 极简 AdGuard DNS 拦截 + 消息接收 Android App

一个体积极小（约 47 KB）的本地 VPN 型 DNS 拦截器：订阅 AdGuard 黑白名单，命中黑名单的域名在 DNS 层直接拦截；同时支持自定义**域名重定向**（把域名解析到你指定的 IP），并内置一个局域网 **HTTP 消息接收器**，把收到的消息推送为通知、悬浮条、铃声和震动。

## 功能特性

- **订阅管理**：添加 / 更新 / 禁用 / 删除 AdGuard 规则订阅，切换即时生效（无需重启服务）
- **拦截**：命中黑名单的域名返回 NXDOMAIN，无法建立连接；白名单（`@@` 放行规则）优先于拦截
- **域名重定向**：自定义 `域名 → IP` 记录（支持 IPv4 / IPv6，含子域名匹配），优先级最高
- **一键启停**：一个按钮同时启动 / 停止 DNS 拦截与消息接收，两者共用同一个前台服务
- **消息接收**：监听可配置端口，接收 JSON / 表单 / 纯文本，自动写入可复制的消息历史
- **消息提醒**：系统通知、声音、震动、悬浮通知、持续响铃（最长 60 秒 + 手动确认）、自定义铃声
- **自动发版**：推送到 `main` 分支自动打 tag、构建 APK、发布 GitHub Release（附提交日志）

## 工作原理

App 建立一个只路由 **DNS 流量**的本地 VPN（TUN 只接入 VPN DNS 与常见公共 DNS 地址，其余流量不经过本应用）。DNS 查询按以下优先级处理：

1. **重定向**：命中自定义重定向表 → 返回自定义 A / AAAA 记录
2. **白名单**：命中 `@@` 放行规则 → 正常转发上游 DNS
3. **黑名单**：命中拦截规则 → 返回 NXDOMAIN
4. **其余** → 经受保护的 socket 转发上游 DNS（223.5.5.5，失败自动切换 8.8.8.8）

消息接收与 DNS 拦截运行在同一个前台服务里：服务启动时先建立 TUN，再监听本机 HTTP 端口；停止时两者一起关闭。

## 项目结构

```
.
├── .github/workflows/release.yml   # CI：自动打 tag + 构建 + Release
├── TinyBlock/                      # Android 工程
│   ├── signing/mibo.jks            # 固定签名（keystore 密码与 key 别名均为 mibo）
│   ├── app/src/main/java/com/tinyblock/app/
│   │   ├── MainActivity.java       # UI：固定头部 + 消息 / 拦截双页
│   │   ├── ClipboardActivity.java  # 剪切板页：消息历史，点击复制
│   │   ├── SwipePager.java         # 零依赖双页滑动容器
│   │   ├── BlockVpnService.java    # 前台服务：DNS 拦截 + HTTP 接收
│   │   ├── WebhookServer.java      # HTTP 接收引擎（无第三方依赖）
│   │   ├── MessageCenter.java      # 消息合并与投递
│   │   ├── Alerts.java             # 通知 / 声音 / 震动 / 持续响铃
│   │   ├── FloatingManager.java    # 悬浮通知条
│   │   ├── HistoryStore.java       # 消息历史（JSONL）
│   │   ├── WebhookPrefs.java       # 端口、铃声、提醒开关、上次计数
│   │   ├── Rules.java              # AdGuard 规则解析与匹配
│   │   ├── Subs.java               # 订阅持久化与下载
│   │   └── RedirectStore.java      # 域名重定向持久化
│   └── app/build.gradle
└── README.md
```

## 环境依赖

当前开发/构建环境信息，本地复刻按此配置即可：

| 组件 | 版本 | 说明 |
|---|---|---|
| JDK | **17**（Temurin 17.0.20） | 运行 Gradle / AGP 必需，不要用 JDK 25 等过新版本 |
| Gradle | **8.14.6** | 工程已含 wrapper（`gradlew`），无需手动安装 |
| Android Gradle Plugin | **8.7.3** | 定义于 `TinyBlock/build.gradle` |
| Android SDK | compileSdk **34** / build-tools **34.0.0** / platform-tools | minSdk 26，targetSdk 34 |
| Python 3 | 3.14（可选） | 仅 `gen_icons.py` 生成图标用，日常构建不需要 |
| 操作系统 | Linux / Windows / macOS 均可 | CI 使用 ubuntu-latest |

## 本地打包步骤

```bash
# 1. 安装 JDK 17（以 mise 为例，也可用系统包管理器）
mise install java@17

# 2. 安装 Android SDK 组件（需先下载 cmdline-tools 并设置 ANDROID_HOME）
yes | "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" \
  "platforms;android-34" "build-tools;34.0.0" "platform-tools"

# 3. 进入工程目录
cd TinyBlock

# 4. 打包（只出 release 包，不再构建 debug 包）
./gradlew assembleRelease   # 发布包（R8 压缩，约 47KB）→ app/build/outputs/apk/release/app-release.apk
./gradlew clean             # 清理构建产物
```

> 也可以直接用 Android Studio 打开 `TinyBlock/` 目录运行。构建产物统一使用 `signing/mibo.jks` 签名，**升级可直接覆盖安装**。以后所有构建/发版都用这个 keystore（密码与别名均为 `mibo`），不要再换签名；如需替换，请同时修改 `TinyBlock/app/build.gradle` 中的 `signingConfigs`。

命令速查：

| 命令 | 作用 |
|---|---|
| `./gradlew assembleRelease` | 构建压缩签名 APK（唯一需要构建的产物） |
| `./gradlew clean` | 清理 |
| `./gradlew wrapper --gradle-version 8.14.6` | 重置 Gradle wrapper |

## CI/CD 自动发版

推送到 **main** 分支即自动触发 [release.yml](.github/workflows/release.yml)：

1. 计算新版本号：基于最近一个 `v*` tag 递增补丁位（首个为 `v1.0.0`）
2. 在触发提交上打 tag 并推送
3. 用 JDK 17 + SDK 34 构建 release APK（使用 `signing/mibo.jks` 签名，不构建 debug 包）
4. 创建 GitHub Release，附件仅 `TinyBlock-<tag>.apk`，Release 描述为**自上个 tag 以来的全部提交日志**及版本对比链接

## 使用说明

### 订阅管理

- 添加订阅链接（`http(s)://` 开头），推荐 AdGuard 官方 DNS 过滤表：
  `https://adguardteam.github.io/AdGuardSDNSFilter/Filters/filter.txt`
- 每条订阅可独立**启用 / 禁用**（开关）、**更新**（重新下载）、**删除**；服务运行中切换即时生效

### 规则语法

| 语法 | 含义 |
|---|---|
| `\|\|example.org^` | 拦截 example.org 及所有子域名 |
| `@@\|\|example.org^` | 放行（优先于拦截规则） |
| `0.0.0.0 example.org` | hosts 格式拦截 |
| `example.org` | 纯域名行拦截 |
| `!` / `#` 开头 | 注释 |

### 域名重定向

在「域名重定向」卡片输入域名和 IP（如 `nas.example.com` → `192.168.1.10`），命中后该域名（含子域名）直接解析到你设置的 IP；重复添加同一域名会更新其 IP。规则开关可临时停用。

### 一键启停

- 头部只有一个按钮：点击后同时启动 VPN 拦截与 HTTP 消息接收，再次点击两者一起停止
- 头部颜色即状态：**淡蓝色 = 未运行，深蓝色 = 运行中**；不再显示状态文字，直接显示拦截规则数与重定向数
- 下方分两页，可左右滑动，也可点「消息 / 拦截」切换；默认停在「拦截」页，向左页滑动查看消息

### 消息接收

在「消息」页设置监听端口（默认随机 10000 以上），页面会显示当前局域网地址，例如 `http://192.168.1.5:12345`。点击地址即可复制。

在同一局域网的电脑或脚本里发送请求（任意路径、任意方法均可）：

```bash
# JSON
curl -X POST http://192.168.1.5:12345 \
  -H "Content-Type: application/json" \
  -d '{"text":"任务执行完成"}'

# 表单
curl -X POST http://192.168.1.5:12345 \
  -d "text=下载完成"

# 纯文本 / query
curl "http://192.168.1.5:12345/?text=hello"

# PowerShell
Invoke-RestMethod -Method Post -Uri "http://192.168.1.5:12345" `
  -ContentType "application/json" -Body '{"text":"hello"}'
```

- 消息会写入「剪切板」页，点击任意一行即可复制全文；默认保留 50 条，可在剪切板页调整
- 提醒方式可独立开关：通知、声音、震动、悬浮通知、持续响铃、自定义铃声
- 持续响铃最长 60 秒，点通知或悬浮条上的「确认」立即停止
- 悬浮通知需要「显示在其他应用上层」权限；消息通知需要通知权限，可在「权限」卡片一键跳转

## 注意事项

- 拦截在 DNS 层完成；写死 IP 或自带 DoT/DoH 的少数应用可绕过
- 系统开启「私人 DNS」时可能绕过拦截，建议关闭
- 本应用不分流其它流量，正常上网速度不受影响
- 消息接收端口默认监听 `0.0.0.0` 且没有鉴权，**仅在可信局域网使用**，不要直接暴露到公网
- 锁屏或省电模式可能延迟送达消息，必要时把本应用加入系统的后台白名单
- 最低支持 Android 8.0（minSdk 26）
- `signing/mibo.jks` 与其密码（`mibo`）仅适用于个人使用场景，公开发布请替换为自己的签名
