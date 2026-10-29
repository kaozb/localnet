# TinyBlock — 极简 AdGuard DNS 拦截 + 消息接收 Android App

一个体积极小（约 53 KB）的本地 VPN 型 DNS 拦截器：订阅 AdGuard 黑白名单，命中黑名单的域名在 DNS 层直接拦截；支持自定义**域名重定向**（把域名解析到你指定的 IP）；内置局域网 **HTTP 消息接收器**，把收到的消息推送为通知、悬浮条、铃声和震动；还支持**短信转发**、**DNS 缓存**、**自定义上游 DNS**、**屏幕常亮**与**深色主题**。

整个项目**零第三方依赖**（不使用 androidx，全部程序化构建 UI），Android 8.0（minSdk 26）起步。

## 功能特性

- **订阅管理**：添加 / 更新 / 禁用 / 删除 AdGuard 规则订阅，切换即时生效（无需重启服务）
- **拦截**：命中黑名单的域名返回 NXDOMAIN；白名单（`@@` 放行规则）优先于拦截
- **域名重定向**：自定义 `域名 → IP` 记录（IPv4 / IPv6，含子域名匹配），优先级最高
- **DNS 缓存**：上游响应缓存 10 分钟（滑动过期，命中自动续期），重复查询零延迟
- **自定义 DNS**：可指定单一上游 DNS（填了就只用它，不做多上游轮询），运行中修改立即生效
- **一键启停**：底部一个圆形快门按钮，按设置页的功能开关启动对应服务（可仅消息接收、仅 DNS 拦截）
- **消息接收**：监听可配置端口，接收 JSON / 表单 / 纯文本，自动写入可复制的消息历史
- **消息提醒**：系统通知、声音、震动、悬浮通知、持续响铃（最长 60 秒 + 手动确认）、自定义铃声，全部独立开关
- **短信转发**：收到短信按配置的 URL + JSON 模板 POST 出去，支持 `{content} {sender} {time} {date}` 变量
- **屏幕常亮**：前台保持亮屏，60 秒无操作全屏黑遮罩模拟锁屏，触摸恢复
- **深色主题**：全局黑白主题切换（含剪切板页），即时生效
- **内置崩溃日志**：崩溃自动记录完整堆栈，下次启动可视化查看、一键复制
- **自动发版**：推送到 `main` 分支自动打 tag、构建 APK、发布 GitHub Release（附提交日志）

## 界面结构

- 无顶部横幅，整个界面为一组页面 + 底部导航：`消息 | 短信 | ⚪启动 | 拦截 | 设置`
- 底部中间的**圆形快门启动按钮**：未启动为深蓝 3D 快门样式（内写「启动」二字），运行中变为**绿色呼吸灯**且文字消失；再次点击全部停止
- 默认停在「拦截」页；可左右滑动或点底部标签切换；返回键从 设置→拦截→短信→消息 逐页回退
- App 锁定竖屏

## 控件设计习惯

本项目的控件不是逐个页面临时凑出来的，遵循下面这套固定方案，新增界面时照抄即可。

### 开关：拟物（Neumorphism）风格

- **外观**：轨道与背景同色，靠 **左上高光 + 右下阴影** 造成凸起；**按下时阴影翻转成内凹**，模拟真实按键的下沉感
- **滑块**：跟随开关状态在轨道内左右滑动，**开启 = 蓝色 `#1A73E8`**，关闭 = 白 / 灰
- **尺寸**：轨道 56×34dp，阴影 blur 3dp / offset 1.5dp，轨道相对控件内缩 5dp
- **黑白两套配色**（`newSwitch()` 统一创建，不在各处 `new`）：

  | 主题 | 轨道底色 | 高光色 | 阴影色 | 关闭态滑块 |
  |---|---|---|---|---|
  | 白天 | `#EDF1F7` | `#FFFFFF` | `#C3CBD9` | `#F7FAFF` |
  | 黑夜 | `#242E3E` | `#34415A` | `#141A24` | `#303C50` |

- **实现约束**：`NeoSwitch extends CompoundButton`，保留系统 Switch 的 API（`setChecked` / `OnCheckedChangeListener`），因此开关逻辑与 UI 分离；自绘控件必须自己 `setClickable(true)`（见「开发踩坑记录」），并 `setLayerType(LAYER_TYPE_SOFTWARE)` 以渲染双色阴影
- **动画**：状态变化走 160ms `ValueAnimator` 过渡；初始化（宽度为 0）时直接到位，不做从关闭态滑开的假动画

### 按钮与列表行

- **点击立即反馈**：一律"先改 UI，再等服务确认"（如启动按钮先变色，界面状态在 `onResume` 时与服务实际状态重新对齐），不让用户对着没反应的界面点第二次
- **列表行三段式**：**删除在左、正文链接在中间、更新在右**；正文链接本身就是开关，**蓝色 = 启用，白色 = 停用**，点一下切换
- **链接只显示可辨识部分**：订阅只显示 URL 最后一段（`filter.txt`），中间省略，域名用 `Ellipsize.MIDDLE`
- **危险操作二次确认**：删除订阅弹 `AlertDialog` 并显示主机名；其余操作直接生效
- **权限做成小按钮**：一排两个，按钮内直接写状态（`已开启` / `去开启`），未授权用红色提示；能在使用中自动申请的系统权限（短信、VPN）不单独列出来
- **状态色统一**：主色蓝 `#1A73E8` 表示可点 / 启用，红色表示危险 / 未授权，灰色表示次要文字；选中态一律"蓝 + 淡底"
- **状态自解释**：圆形启动按钮未启动是深蓝快门（写「启动」），运行中变**绿色呼吸灯**且文字消失；通知栏也提供 `停止响铃` / `停止` 快捷按钮

## 工作原理

App 建立一个只路由 **DNS 流量**的本地 VPN（TUN 只接入 VPN DNS 与常见公共 DNS 地址，其余流量不经过本应用）。DNS 查询按以下优先级处理：

1. **重定向**：命中自定义重定向表 → 返回自定义 A / AAAA 记录
2. **白名单**：命中 `@@` 放行规则 → 正常转发上游 DNS
3. **黑名单**：命中拦截规则 → 返回 NXDOMAIN
4. **缓存**：命中 DNS 缓存（10 分钟滑动过期）→ 直接返回，不再发上游
5. **其余** → 经受保护的 socket 转发上游 DNS（默认 `223.5.5.5`，失败切换 `8.8.8.8`；设置里填了自定义 DNS 则只用那一个）

消息接收与 DNS 拦截运行在同一个前台服务里，但**由设置页的两个独立开关控制**：

- 两个都开：启动时先建 TUN，再监听本机 HTTP 端口；停止时一起关闭
- 只开「消息接收」：不建 VPN、连 VPN 授权弹窗都不请求
- 只开「DNS 拦截」：不监听端口
- 运行中切换开关或修改端口 / DNS / 规则，通过内部 `ACTION_RELOAD` 热重载

## 项目结构

```
.
├── .github/workflows/release.yml   # CI：自动打 tag + 构建 + Release
├── TinyBlock/                      # Android 工程
│   ├── signing/mibo.jks            # 固定签名（keystore 密码与 key 别名均为 mibo）
│   ├── app/src/main/java/com/tinyblock/app/
│   │   ├── MainActivity.java       # UI：五页 + 底部导航 + 主题/常亮/设置
│   │   ├── NeoSwitch.java          # 拟物开关（自绘，区分黑白主题）
│   │   ├── ClipboardActivity.java  # 剪切板页：消息历史，点击复制（跟随深色主题）
│   │   ├── RulesListActivity.java  # 订阅 / 重定向清单独立页
│   │   ├── SwipePager.java         # 零依赖多页滑动容器（强制吸附，不卡中间）
│   │   ├── BlockVpnService.java    # 前台服务：DNS 拦截 + 缓存 + HTTP 接收
│   │   ├── WebhookServer.java      # HTTP 接收引擎（无第三方依赖）
│   │   ├── MessageCenter.java      # 消息合并与投递
│   │   ├── Alerts.java             # 通知 / 声音 / 震动 / 持续响铃
│   │   ├── FloatingManager.java    # 悬浮通知条
│   │   ├── HistoryStore.java       # 消息历史（JSONL）
│   │   ├── WebhookPrefs.java       # 端口、DNS、铃声、开关、短信模板等配置
│   │   ├── SmsReceiver.java        # 系统短信广播接收 → 转发
│   │   ├── SmsForwarder.java       # 短信模板展开 + 异步 POST
│   │   ├── BootReceiver.java       # 开机自启（仅功能开启时拉起服务）
│   │   ├── Rules.java              # AdGuard 规则解析与匹配（HashSet 后缀逐级查找）
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
| Gradle | **8.14.6** | 工程已含 wrapper（`gradlew`）；受限网络下可直接用 mise 安装的 gradle |
| Android Gradle Plugin | **8.7.3** | 定义于 `TinyBlock/build.gradle` |
| Android SDK | compileSdk **34** / build-tools **34.0.0** / platform-tools | minSdk 26，targetSdk 34 |
| Python 3 | 3.14（可选） | 仅 `gen_icons.py` 生成图标用，日常构建不需要 |
| 操作系统 | Linux / Windows / macOS 均可 | CI 使用 ubuntu-latest |

**受限网络（沙箱/代理环境）下的构建配置**——Gradle 下载依赖与 wrapper 需要走 JVM 代理（`GRADLE_OPTS`），`distributionUrl` 直连会超时：

```bash
export JAVA_HOME=/root/.local/share/mise/installs/java/17.0.20+101
export ANDROID_HOME=/tmp/android-sdk
export GRADLE_OPTS="-Dhttp.proxyHost=127.0.0.1 -Dhttp.proxyPort=18080 \
  -Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=18080 \
  -Dhttp.nonProxyHosts=localhost|127.0.0.1"
```

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
./gradlew assembleRelease   # 发布包（R8 压缩，约 53KB）→ app/build/outputs/apk/release/app-release.apk
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
- 已添加的订阅在「拦截」页点查询按钮进入**清单独立页**：每行**左侧删除、中间链接（点一下启用/停用，蓝=启用）、右侧更新**；服务运行中切换即时生效

### 规则语法

| 语法 | 含义 |
|---|---|
| `\|\|example.org^` | 拦截 example.org 及所有子域名 |
| `@@\|\|example.org^` | 放行（优先于拦截规则） |
| `0.0.0.0 example.org` | hosts 格式拦截 |
| `example.org` | 纯域名行拦截 |
| `!` / `#` 开头 | 注释 |

### 域名重定向

在「域名重定向」卡片输入域名和 IP（如 `nas.example.com` → `192.168.1.10`），命中后该域名（含子域名）直接解析到你设置的 IP；重复添加同一域名会更新其 IP。已添加的规则在「拦截」页点查询按钮进入**清单独立页**，点规则本身可临时停用。

### 一键启停

- 底部中间圆形按钮：按「设置 → 功能开关」启动对应服务，再次点击全部停止
- 按钮颜色即状态：**深蓝（含「启动」二字）= 未运行，绿色呼吸灯 = 运行中**
- 两个开关都关闭时点启动会提示先去设置开启

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

### 短信转发

在「短信」页配置（开关也在本页，设置页只有消息接收 / DNS 拦截两个总开关）：

- **POST 地址**：收到短信后向该地址 POST，`Content-Type: application/json; charset=utf-8`
- **JSON 内容**：请求体模板，四个变量按钮点击即插入光标位置：
  - `{content}` 短信内容、`{sender}` 发件人号码
  - `{time}` 秒级时间戳、`{date}` `yyyy-MM-dd HH:mm:ss`
  - URL 里嵌变量也可（自动 URL 编码）；JSON 内原样替换（注意引号转义）
  - JSON 留空用默认模板：`{"sender":"{sender}","content":"{content}","time":{time},"date":"{date}"}`
- **发送测试**：用样例数据实际发一次，返回 HTTP 状态
- 长短信多段会自动合并成一条转发；转发失败后台静默（用「发送测试」验证连通性）
- 首次开启会请求短信权限（`RECEIVE_SMS` 为敏感权限）

### 设置

- **功能开关**：消息接收、DNS 拦截、开机自启（短信转发开关在「短信」页）
- **网络 → 自定义 DNS**：填一个 IP（如 `192.168.1.1`），所有查询只发往它（端口 53）；留空用默认 `223.5.5.5 / 8.8.8.8`；运行中修改立即生效。注意：单一上游没有备用，该地址不可达时解析会失败
- **显示 → 屏幕常亮**：App 在前台保持亮屏；60 秒无操作盖全屏黑遮罩（沉浸式，状态栏/通知栏一起隐藏）模拟锁屏，AMOLED 纯黑像素不发光 + 最低背光，点一下屏幕立即恢复原亮度并重新计时；切后台/失焦自动恢复亮度（不影响下拉栏调亮度）
- **显示 → 深色主题**：黑白切换即时生效，停留当前页，剪切板页同样适配
- **权限**：一排两个小按钮（通知权限、悬浮窗权限），按钮内直接标状态（已开启 / 去开启），点击跳转系统设置；短信权限与 VPN 授权在启用功能时自动申请

## 开发踩坑记录

整个开发过程中实际遇到并解决的问题，按主题归档，便于以后维护时避坑。

### UI 与布局

- **`HorizontalScrollView can host only one direct child` 崩溃**：新版 Android 强制 HorizontalScrollView 只能有一个直接子 View。滑动容器 `SwipePager` 最初把多个页面直接挂在它下面，一启动就闪退。解法：滚动容器下只放一个水平排列容器，页面全部装进容器里，滚动/吸附逻辑不变。教训：**不要依赖容器"碰巧能放多个子 View"的旧行为**。
- **滑动翻页卡在中间**：把吸附交给系统 fling 导致松手后可能停在两页之间。解法：`onTouchEvent` 拦截 UP/CANCEL，禁用父类 fling，按"拖过半页或松手速度 > 900px/s 翻页"判定目标页并 `smoothScrollTo` 强制吸附——任何情况下松手必落在某一页。
- **横屏布局错乱（按钮"丢失"）**：界面按竖屏设计，横屏重建后宽度翻倍，行间距撑大、开关漂移，视觉上像控件消失。解法：Manifest 锁定 `portrait`。教训：**程序化布局要锁定方向，否则所有按屏宽写的尺寸都会失效**。
- **深色主题切换后跳页**：切主题用 `recreate()` 重建界面，默认回到第一页。解法：切换前把当前页码存入 `pendingPage`，重建后恢复。凡涉及 `recreate()` 的配置变更都要考虑"恢复现场"。
- **状态栏适配**：深浅主题下状态栏颜色与图标深浅（`SYSTEM_UI_FLAG_LIGHT_STATUS_BAR`）要成对设置，否则深色主题下状态栏图标看不清。**每个 Activity 都要单独设置**——剪切板页最初就漏了，深色模式下还是白底。
- **自绘开关点不动（`CompoundButton` 缺 `defStyleAttr`）**：把系统 `Switch` 换成自绘 `NeoSwitch`（继承 `CompoundButton`）后，开关能显示但点击无反应。根因在 AOSP：`CompoundButton(Context)` 内部是 `this(context, null)` → `this(context, attrs, 0)`，**`defStyleAttr = 0`**，于是平台样式 `Widget.CompoundButton`（其中 `clickable=true`、`focusable=true`）不会生效；而系统 `Switch` 是靠 `R.attr.switchStyle` 才拿到该样式。`View.onTouchEvent` 在 `ACTION_DOWN` 遇到 `!clickable` 会直接 `break`，`ACTION_UP` 同样提前 `break`，`performClick()` 永不执行 → `CompoundButton.performClick()` → `toggle()` 不触发。解法：构造函数里显式 `setClickable(true)` + `setFocusable(true)`。教训：**自绘控件不能假设平台默认属性会附加上来自带的样式**。
- **软件渲染才能画双色阴影**：`Paint.setShadowLayer` 的双色高光/阴影在硬件加速下表现不可靠，`NeoSwitch` 构造时 `setLayerType(LAYER_TYPE_SOFTWARE, null)`。代价是自绘区域不能走 GPU 加速，但开关尺寸小，实测无影响。
- **零依赖程序化 UI 的取舍**：全部 UI 用 Java 代码构建（无 XML 布局），优点是零依赖、包小；代价是无法用布局预览，改动只能靠构建 + 真机验证，因此更要控制单文件复杂度。

### 服务与 VPN

- **一键启停的融合方式**：HTTP 监听不做成独立服务，而是作为 `BlockVpnService` 内的线程组，TUN 建立成功后再启动监听，停止时一起关闭。避免了两个前台服务、两个常驻通知的复杂度。
- **VPN 授权被拒**：`VpnService.prepare` 返回的授权弹窗被拒后直接 toast 取消，不进入启动流程，避免半启动状态。
- **仅接收模式**：DNS 拦截开关关闭时启动，不建 TUN、不请求 VPN 授权；运行中打开「DNS 拦截」若未授权过 VPN，保持仅接收而不把服务整个停掉。
- **端口 / 配置热重载**：运行中改端口、开关、规则都通过 `ACTION_RELOAD` 通知服务内部重建对应部分，不用重启 VPN。

### 通知与提醒

- **通知渠道声音创建后不可变**：Android 渠道一旦创建，声音/震动设置就无法再改。解法：渠道创建为 `IMPORTANCE_HIGH` 但**声音为 null、震动关闭**，App 自己用 `Ringtone` / `MediaPlayer`（循环）播放铃声——铃声、静音、持续响铃全部由应用层控制。
- **持续响铃**：`MediaPlayer` 循环 + 波形震动，60 秒自动停；通知和悬浮条带「确认」按钮，通过内部 action 停止响铃。

### 屏幕常亮

- **窗口亮度覆盖会压住系统亮度**：用 `window.screenBrightness` 压到最低后，下拉状态栏调亮度无效（窗口覆盖还在），且"不是必然出现"——只有当前窗口还在前台才压住。解法：**窗口一失焦立即撤掉亮度覆盖**，回到前台重新计时。
- **模拟锁屏而非真锁屏**：60 秒无操作后盖全屏黑遮罩（非真锁屏，不打断后台任务），同时窗口亮度压到最低；配合沉浸式隐藏状态栏/导航栏，通知栏也一起被盖住；AMOLED 屏幕纯黑像素不发光，省电效果接近锁屏。唤醒用的那次点击只用于撤遮罩，不透传给下层控件，避免误触按钮。

### DNS 性能

- **规则匹配没有 O(n) 问题**：`Rules.match` 用 `HashSet` 对域名后缀逐级查找（`a.b.c` → `b.c` → `c`），几万条规则也是常数次哈希，不需要字典树。
- **DNS 缓存设计要点**：按 `qname(小写)+qtype` 做键；**滑动过期**——命中就把过期时间重置回 10 分钟后；命中返回时必须**克隆缓存并回填本次查询的事务 ID**（DNS 响应头 2 字节是事务 ID，不同客户端查询 ID 不同，不回填会被系统丢包；并发下不克隆会互相覆盖）；上限 1024 条防膨胀。
- **已知未修**：订阅热加载在 `synchronized` 方法里逐行解析，加载几万行期间 `match()` 会短暂排队（DNS 停顿一两秒）；正确做法是后台解析到新对象再原子替换引用。

### 稳定性

- **无 PC 时的闪退排查**：早期版本在真机上"打开就闪退"且拿不到堆栈。解法：内置 `CrashHandler`，任何未捕获异常把完整堆栈写入 `Android/data/com.tinyblock.app/files/crash.txt` 并在下次启动弹出"崩溃日志页"（可一键复制、可清除继续）。上面第一个 HorizontalScrollView 崩溃就是靠它定位的。

### HTTP 服务（零依赖）

- 手写 `ServerSocket` + 线程池实现：header 上限 64KB、body 上限 1MB、读超时 10 秒、`setReuseAddress(true)`；解析 JSON / 表单 / query / 纯文本四种消息载体，支持 CORS 与 OPTIONS。
- HTTP 的 `Expect: 100-continue` 握手未实现（主流 curl/HTTP 客户端均可正常使用）。

### CI/CD 发版

- **历史改写后 tag 冲突导致不发版**：强推改写 main 历史后，旧 tag 指向的提交被孤立但 tag 仍存在。工作流用 `git describe` 算下一个版本号时只能看到"可达"的 tag，会算出一个**已存在**的版本号（如 v1.0.2），`git tag -a` 直接 `fatal: tag already exists`，整个流水线失败——结果 Release 页面一直停留在旧代码构建的包上。教训：版本号要用 `git tag -l | sort -V` 扫描**全部** tag（不依赖可达性），并在打 tag 前检查已存在则自增重试。
- **排查 CI 包问题的思路**：不要假设"流水线产物 = 最新代码"。先 `git ls-remote origin` 看 tag 指向的提交，再 `unzip -p xxx.apk classes.dex | strings` 检查关键类/组件是否存在，即可判断包的新旧。

## 调试手段

- **内置崩溃日志（首选）**：闪退后再打开 App → 崩溃日志页 → 「复制日志」发出来即可定位；日志文件在 `Android/data/com.tinyblock.app/files/crash.txt`。
- **adb logcat**：电脑连接后 `adb logcat -s AndroidRuntime` 抓闪退堆栈，`adb logcat | grep tinyblock` 看运行日志。
- **APK 静态验证**（确认装到手机上的包对不对）：
  ```bash
  aapt2 dump badging app-release.apk   # 版本、minSdk、权限、Activity 清单
  unzip -p app-release.apk classes.dex | sha256sum   # 对比产物与源码构建是否一致
  ```
- **消息接收联调**：用上面 curl 示例直接打端口，观察通知 / 悬浮条 / 剪切板页是否收到；`{"ok":true}` 响应即服务正常。
- **无模拟器环境**：本仓库的开发环境没有模拟器/真机，运行时行为主要靠「静态审查启动路径 + 构建期 lintVital + 内置崩溃日志兜底」三件套；涉及手势、通知、响铃的改动发布后需真机确认。
- **构建自检清单**：`assembleRelease` 成功 → `aapt2 dump badging` 核对 versionCode/权限 → 真机覆盖安装 → 走一遍「启动 → 收消息 → 深色切换 → 常亮遮罩」主流程。

## 注意事项

- 拦截在 DNS 层完成；写死 IP 或自带 DoT/DoH 的少数应用可绕过
- 系统开启「私人 DNS」时可能绕过拦截，建议关闭
- 本应用不分流其它流量，正常上网速度不受影响
- **自定义 DNS 填了就只用它，没有备用上游**；该地址不可达时解析会失败
- 消息接收端口默认监听 `0.0.0.0` 且没有鉴权、明文传输，**仅在可信局域网使用**，不要直接暴露到公网
- 短信转发失败不重试不补发，网络瞬断时该条消息不会重发
- 锁屏或省电模式可能延迟送达消息，必要时把本应用加入系统的后台白名单
- 最低支持 Android 8.0（minSdk 26）；App 锁定竖屏
- `signing/mibo.jks` 与其密码（`mibo`）仅适用于个人使用场景，公开发布请替换为自己的签名
