# Cloudflare Tunnel 修复成功备忘录

日期：2026-03-27

## 结论

本次修复后，真机已可成功启动 Cloudflare Tunnel，并可从公网访问手机本地控制 API。

已验证地址：

- `https://android-agent.555606.xyz/api/health`

成功返回：

```json
{
  "ok": true,
  "server": {
    "running": true,
    "host": "127.0.0.1",
    "port": 18080,
    "localApi": "127.0.0.1:18080",
    "lastError": null
  }
}
```

## 本次问题背景

最初失败日志显示 `cloudflared` 在 Android 上启动后会访问 `[::1]:53` 做 DNS 解析，并出现如下错误：

- `lookup _v2-origintunneld._tcp.argotunnel.com on [::1]:53: read udp [::1]:...->[::1]:53: read: connection refused`

这说明隧道启动阶段存在 DNS 解析问题，导致用户在点击“启动隧道”时看到启动失败。

## 本次代码修改

### 1. AndroidManifest

文件：

- `app/src/main/AndroidManifest.xml`

新增内容：

- `com.termux.permission.RUN_COMMAND`
- `queries` 中声明 `com.termux`

目的：

- 为后续通过 Termux 官方 `RunCommandService` 启动 `cloudflared` 预留权限和包可见性

### 2. TunnelRuntime

文件：

- `app/src/main/java/com/mobileagent/demo/TunnelRuntime.kt`

主要修改点：

- 调整 binary 选择与探测逻辑
- 增加 bundled binary 可执行性探测
- 增加 Termux binary 可用性探测
- 增加 Termux `RUN_COMMAND` service 启动实现
- 增加 Termux 权限检查与 `allow-external-apps` 配置检查
- 移除旧的 `runTermuxCommand()` 包装逻辑

## 真机验证结果

### 1. APK

已构建并安装 debug APK 到真机：

- 设备：`XT2175_2`
- 包名：`com.mobileagent.demo`

### 2. 隧道实际成功状态

最终成功日志来自 bundled binary：

- 日志文件：`/data/local/tmp/mobile-agent-cloudflared.log`
- pid 文件：`/data/local/tmp/mobile-agent-cloudflared.pid`

关键成功日志包含：

- `Registered tunnel connection`
- `Updated to new configuration`

说明：

- 本次最终公网打通时，实际运行的是应用内 bundled `cloudflared`
- Termux 相关支持已补齐，但本次最终打通链路并不是依赖 Termux log 文件完成验证

### 3. 外网验证

已确认以下请求成功命中手机本地 API：

- `curl https://android-agent.555606.xyz/api/health`

返回结果中的 `localApi` 为：

- `127.0.0.1:18080`

说明 Cloudflare Tunnel 已正确把公网域名流量转发到手机本地服务。

## 设备侧补充处理

为支持 Termux 备用方案，本次还在真机上做了两项配置：

- 给 `com.mobileagent.demo` 授予 `com.termux.permission.RUN_COMMAND`
- 在 Termux `termux.properties` 中启用 `allow-external-apps = true`

备注：

- 这两项是 Termux service 方案的前置条件
- 即使本次最终成功使用的是 bundled binary，这些配置也保留了后续切换或回退空间

## 当前状态判断

当前可以认为“启动隧道”问题已修复，依据如下：

- APK 已重新构建并安装
- 真机上 `cloudflared` 进程已启动
- 隧道日志已出现多条 `Registered tunnel connection`
- 公网域名已成功访问到本地 API

## 后续建议

建议后续继续做两件事：

1. 把 UI 中的 binary source 展示与真实运行路径做一次一致性核对，避免界面与实际启动源不一致。
2. 在 `TunnelRuntime` 增加更明确的成功判定与失败归因，区分：
   - bundled DNS 失败
   - Termux 权限缺失
   - Termux `allow-external-apps` 未开启
   - UI 触发成功但状态刷新滞后

## 风险备注

本次验证过程中，真机 ADB 连接在 `uiautomator dump` 等操作时存在多次 `offline` 现象。因此最终成功确认主要基于以下事实，而不是仅依赖 UI 截图：

- 真机日志
- `cloudflared` 进程状态
- 公网 `api/health` 实测返回

