# Ubuntu 20.04 ARM64 开发环境迁移说明

日期：2026-03-27

适用目标环境：

- `Ubuntu 20.04.6 LTS`
- `GNU/Linux 5.15.0-1081-oracle`
- `aarch64`

## 结论

本项目迁移到 `Ubuntu 20.04.6 LTS aarch64` 后，可以把它视为一个适合以下工作的环境：

- 命令行构建 APK
- 安装 Android SDK 命令行工具
- 通过 `adb` 联调真机
- 跑 Gradle、日志分析、接口验证、脚本化验证

但不应默认把它当成一个适合以下工作的环境：

- 本地 Android Studio 图形化开发
- 本地 Android Emulator 作为主验证手段

原因有两个：

1. Android 官方当前说明中，Linux ARM CPU 主机不在 Android Studio 支持范围内。
2. Android Emulator 在 Linux 上的官方加速说明以 Intel VT-x / AMD-V + KVM 为主，并未给出 Linux ARM 主机作为常规本地模拟器开发机的支持路径。

基于这些官方信息，对本项目的建议是：

- 在该 Ubuntu ARM64 环境上使用命令行工具链
- 优先使用真机进行联调
- 如果必须先做模拟器验证，改用受支持的 x86_64 Linux / macOS / Windows 开发机，或使用远程设备服务

## 一、先确认的现实约束

### 1. Android Studio 不应作为默认方案

Android 官方安装页当前写明：

- Linux ARM-based CPU 目前不受 Android Studio 支持

这意味着在 `Ubuntu 20.04.6 aarch64` 上，不应把“装 Android Studio 后本地开发”当成主路线。

对本项目的直接影响：

- 日常开发以 `./gradlew`、`sdkmanager`、`adb` 为主
- IDE 建议改用远程开发方案，或在受支持的桌面主机上使用 Android Studio

### 2. 本地 Emulator 不应作为默认验证手段

Android 官方 Linux Emulator 加速文档当前主要覆盖：

- Intel VT-x
- AMD-V
- KVM

对当前 ARM64 Oracle 主机，应做如下判断：

- 不要假设本地 Emulator 可用
- 不要把“模拟器优先”写成必经流程
- 应优先准备一台可长期在线的 Android 真机

这是基于官方文档的谨慎推断，不是说“绝对不可能运行模拟器”，而是说“不应把它当成稳定主路径”。

## 二、对本项目最重要的迁移策略

建议把开发流程切成两条线：

### 1. Ubuntu ARM64 主机负责

- 拉代码
- 安装 JDK 17
- 安装 Android SDK Command-line Tools
- 执行 `./gradlew assembleDebug`
- 执行 `./gradlew installDebug`
- 通过 `adb` 和 `curl` 做自动化验证
- 分析真机日志和 Cloudflare Tunnel 日志

### 2. 真机负责

- UI 最终验证
- Root 权限行为验证
- `cloudflared` 实际运行验证
- 网络切换与 IPv6 变化验证
- Cloudflare Tunnel 端到端验证

这比“先在 ARM64 机器上跑本地模拟器”更符合当前官方支持边界，也更贴合本项目的真实需求。

## 三、推荐的软件准备

### 1. 基础包

建议先安装：

```bash
sudo apt update
sudo apt install -y \
  git \
  unzip \
  zip \
  curl \
  wget \
  ca-certificates \
  adb \
  openjdk-17-jdk
```

说明：

- `openjdk-17-jdk` 对应本项目当前 Gradle / Kotlin 配置
- `adb` 可以直接用系统包，也可以用 Android SDK 的 `platform-tools`

### 2. Android SDK 命令行工具

建议不要依赖 Android Studio，直接使用官方 command-line tools。

推荐目录：

```bash
mkdir -p ~/android-sdk/cmdline-tools
cd ~/android-sdk
```

下载并解压官方 Linux command-line tools 后，按官方要求整理目录：

```bash
~/android-sdk/cmdline-tools/latest/bin
```

然后安装本项目需要的包：

```bash
export ANDROID_HOME=$HOME/android-sdk
export PATH=$PATH:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools

sdkmanager --licenses
sdkmanager \
  "platform-tools" \
  "platforms;android-35" \
  "build-tools;35.0.0" \
  "cmdline-tools;latest"
```

### 3. 环境变量

建议使用：

```bash
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-arm64
export ANDROID_HOME=$HOME/android-sdk
export PATH=$PATH:$JAVA_HOME/bin:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools
```

建议把上述内容写入：

- `~/.bashrc`
或
- `~/.profile`

注意：

- Android 官方当前推荐使用 `ANDROID_HOME`
- `ANDROID_SDK_ROOT` 已被标记为 deprecated，不建议继续作为主变量

## 四、迁移本项目时必须处理的项目级细节

### 1. `local.properties` 必须改成 Linux 路径

Windows 环境里的 `local.properties` 不能直接照搬。

Ubuntu 上应写成类似：

```properties
sdk.dir=/home/your-user/android-sdk
```

注意点：

- 使用正斜杠 `/`
- 不要用 Windows 盘符路径
- 不要保留反斜杠转义写法

### 2. 使用 `./gradlew`，不要使用 `gradlew.bat`

Ubuntu 上应执行：

```bash
chmod +x gradlew
./gradlew assembleDebug
```

如果仓库是从 Windows 环境迁来的，第一件事就应检查：

- `gradlew` 是否可执行
- shell 脚本是否有 CRLF 行尾问题

推荐修复：

```bash
sed -i 's/\r$//' gradlew
chmod +x gradlew
```

### 3. 不要复用 Windows 下下载的本地工具目录

如果项目目录里残留这些仅适用于 Windows 的内容：

- Windows JDK
- Windows SDK 工具
- `.bat` 依赖路径

应在 Ubuntu 环境中重新安装对应 Linux 版本，而不是继续引用原路径。

本项目之前在 Windows 会话中曾使用仓库内工具目录临时构建；迁移到 Ubuntu 后，不建议继续沿用该思路。

建议原则：

- JDK 用系统安装或单独下载的 Linux ARM64 JDK
- SDK 用 Linux command-line tools 重新安装
- `adb` 优先使用 `platform-tools/adb`

## 五、构建与安装建议流程

推荐最小流程：

```bash
./gradlew assembleDebug
./gradlew installDebug
```

如果只构建不安装：

```bash
./gradlew assembleDebug
```

APK 输出位置：

```text
app/build/outputs/apk/debug/app-debug.apk
```

## 六、ADB 与真机连接注意事项

Android 官方对 Ubuntu 设备调试当前建议：

- 当前用户加入 `plugdev` 组
- 安装 `android-sdk-platform-tools-common` 以获得默认 `udev` 规则

建议执行：

```bash
sudo usermod -aG plugdev $LOGNAME
sudo apt install -y android-sdk-platform-tools-common
```

然后重新登录。

验证连接：

```bash
adb devices -l
```

如果 USB 调试不稳定，建议尽早启用无线调试或固定 USB 线材与端口，避免把问题误判成应用故障。

## 七、关于 Emulator 的具体建议

### 1. 不要把 Emulator 当成迁移验收标准

在这台 Ubuntu ARM64 主机上，迁移成功的标准不应是：

- “本地 Android Studio 正常打开”
- “本地 AVD 正常运行”

而应是：

- `./gradlew assembleDebug` 成功
- `adb install` 成功
- 真机可运行应用
- 本地 API 可访问
- Cloudflare Tunnel 可启动并联通

### 2. 如果确实需要模拟器

建议二选一：

1. 换到官方支持的 x86_64 Linux/macOS/Windows 主机
2. 使用远程 Android 设备服务

对本项目来说，真机的价值本来就高于模拟器，因为以下能力无法在模拟器上得到等价验证：

- Root 行为
- 飞行模式切换
- 真实蜂窝/Wi-Fi 网络恢复
- 真正的 IPv6 切换
- 某些设备特定的 DNS / `cloudflared` 问题

## 八、与本项目强相关的运行注意事项

### 1. 真机 Root 是核心前提

本项目不只是普通 Android UI 工程，还依赖：

- `su`
- 网络状态 shell 命令
- 飞行模式切换
- `cloudflared` 进程管理

因此迁移到 Ubuntu 后，真正应优先保证的是：

- `adb shell su -c id` 可用
- 真机已 root
- Root 授权稳定

### 2. Tunnel 验证不要只看 UI

本项目已出现过这种情况：

- UI 状态未及时反映真实 binary source
- 但公网 `api/health` 已经打通

因此 Ubuntu 环境中的自动化验证建议至少包含三种信号：

1. 真机进程状态
2. 真机日志文件
3. 公网健康检查结果

例如：

```bash
adb shell su -c pidof cloudflared
adb shell su -c tail -n 50 /data/local/tmp/mobile-agent-cloudflared.log
curl --max-time 20 https://android-agent.555606.xyz/api/health
```

### 3. 当前仓库存在一个已知构建提醒

本项目当前使用：

- Android Gradle Plugin `8.5.2`
- `compileSdk = 35`

实际构建可通过，但会出现一条 warning，提示该 AGP 版本测试上限是 `compileSdk 34`。

这不一定会阻塞迁移，但迁移后应记录它，并考虑后续升级 AGP。

## 九、推荐的 Ubuntu ARM64 首次落地步骤

建议按下面顺序执行：

1. 安装 JDK 17、git、unzip、curl、adb。
2. 安装 Android SDK command-line tools。
3. 配置 `JAVA_HOME`、`ANDROID_HOME`、`PATH`。
4. 重写 `local.properties` 为 Linux SDK 路径。
5. 修正 `gradlew` 可执行权限和行尾。
6. 执行 `./gradlew assembleDebug`。
7. 配置 `plugdev` / `udev`，接入真机。
8. 执行 `./gradlew installDebug`。
9. 用 `adb`、日志和公网健康检查完成验收。

## 十、迁移成功的判定标准

建议把“迁移成功”定义为以下条件全部满足：

- Ubuntu ARM64 上 `./gradlew assembleDebug` 成功
- Ubuntu ARM64 上 `./gradlew installDebug` 成功
- 真机 `adb devices -l` 稳定可见
- 应用可在真机启动
- 本地 API 可响应
- Cloudflare Tunnel 可成功建立连接
- 公网域名可命中 `127.0.0.1:18080`

不建议把这些作为强制条件：

- Android Studio 在本机可用
- 本机 Emulator 可用

## 十一、官方参考资料

以下信息基于 Android 官方文档：

- Android Studio 安装与系统要求  
  https://developer.android.com/studio/install

- Android SDK command-line tools / sdkmanager  
  https://developer.android.com/tools/sdkmanager

- Android SDK 环境变量  
  https://developer.android.com/tools/variables

- 在硬件设备上运行应用 / Ubuntu ADB 配置  
  https://developer.android.com/studio/run/device

- Android Emulator 硬件加速说明  
  https://developer.android.com/studio/run/emulator-acceleration

