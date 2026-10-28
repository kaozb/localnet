# TinyBlock — 极简 AdGuard DNS 拦截 Android App

一个体积极小（约 30 KB）的本地 VPN 型 DNS 拦截器：订阅 AdGuard 黑白名单，命中黑名单的域名在 DNS 层直接拦截；同时支持自定义**域名重定向**（把域名解析到你指定的 IP）。

## 功能特性

- **订阅管理**：添加 / 更新 / 禁用 / 删除 AdGuard 规则订阅，切换即时生效（无需重启服务）
- **拦截**：命中黑名单的域名返回 NXDOMAIN，无法建立连接；白名单（`@@` 放行规则）优先于拦截
- **域名重定向**：自定义 `域名 → IP` 记录（支持 IPv4 / IPv6，含子域名匹配），优先级最高
- **服务开关**：一键启停本地 VPN，状态与规则数实时显示在界面与通知栏
- **自动发版**：推送到 `main` 分支自动打 tag、构建 APK、发布 GitHub Release（附提交日志）

## 工作原理

App 建立一个只路由 **DNS 流量**的本地 VPN（TUN 只接入 VPN DNS 与常见公共 DNS 地址，其余流量不经过本应用）。DNS 查询按以下优先级处理：

1. **重定向**：命中自定义重定向表 → 返回自定义 A / AAAA 记录
2. **白名单**：命中 `@@` 放行规则 → 正常转发上游 DNS
3. **黑名单**：命中拦截规则 → 返回 NXDOMAIN
4. **其余** → 经受保护的 socket 转发上游 DNS（223.5.5.5，失败自动切换 8.8.8.8）

## 项目结构

```
.
├── .github/workflows/release.yml   # CI：自动打 tag + 构建 + Release
├── TinyBlock/                      # Android 工程
│   ├── signing/mibo.jks            # 固定签名（keystore 密码与 key 别名均为 mibo）
│   ├── app/src/main/java/com/tinyblock/app/
│   │   ├── MainActivity.java       # UI（订阅管理 / 重定向管理 / 服务开关）
│   │   ├── BlockVpnService.java    # VPN 服务：DNS 解析、拦截、重定向、转发
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
| Android SDK | compileSdk **34** / build-tools **34.0.0** / platform-tools | minSdk 21，targetSdk 34 |
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
./gradlew assembleRelease   # 发布包（R8 压缩，约 30KB）→ app/build/outputs/apk/release/app-release.apk
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

## 注意事项

- 拦截在 DNS 层完成；写死 IP 或自带 DoT/DoH 的少数应用可绕过
- 系统开启「私人 DNS」时可能绕过拦截，建议关闭
- 本应用不分流其它流量，正常上网速度不受影响
- `signing/mibo.jks` 与其密码（`mibo`）仅适用于个人使用场景，公开发布请替换为自己的签名
