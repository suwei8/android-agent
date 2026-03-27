# Cloudflare Tunnel 成功备忘录

日期：2026-03-27

## 结论

当前可稳定工作的链路是：

- APK 内置 `arm64-v8a/libcloudflared.so`
- App 内本地 API：`127.0.0.1:18080`
- App 前台服务触发 root 启动
- Cloudflare Tunnel 对外暴露：`https://android-agent.555606.xyz/api/health`

真机实测结果：

- `POST /api/tunnel/start` 后，`GET /api/tunnel/status` 返回 `statusLabel=已连接`
- 公网访问 `https://android-agent.555606.xyz/api/health` 返回 `HTTP/2 200`

## 关键发现

问题不在二进制本身。

同一份 `cloudflared` 二进制在真机上手工执行可以成功连通，失败点在于 App 内部的启动方式。

已经证实的事实：

- 手工执行 APK 内置路径的 `libcloudflared.so` 可以成功注册多条 `Registered tunnel connection`
- 公网 `api/health` 可以命中手机本地 API
- 旧的长驻交互式 root shell 启动方式会把问题复杂化

## 当前实现

当前 `TunnelRuntime` 使用的方案：

- 二进制来源固定为 APK `nativeLibraryDir` 下的 `libcloudflared.so`
- 启动参数固定包含：
  - `tunnel --no-autoupdate run`
  - `--dns-resolver-addrs 1.1.1.1:53`
  - `--dns-resolver-addrs 8.8.8.8:53`
- Tunnel 由前台服务触发
- root 启动改为一次性命令执行，而不是长驻交互式 shell
- 启动时按策略链尝试：
  - `su -c`
  - `su -M -c`
  - `su -t <adbd-pid> -c`
  - `su -M -t <adbd-pid> -c`

## 真机验证

验证设备：

- `XT2175_2`
- `arm64-v8a`
- Root 已开启

验证步骤：

1. 安装 debug APK
2. 打开应用，确认本地 API 正常
3. 调用 `POST /api/tunnel/start`
4. 检查 `GET /api/tunnel/status`
5. 检查公网 `https://android-agent.555606.xyz/api/health`

成功判据：

- `statusLabel=已连接`
- 日志中出现 `Registered tunnel connection`
- 公网请求返回 `HTTP/2 200`

## 说明

日志里偶尔仍可能看到针对 `[::1]:53` 的 DNS 报错片段，但它不再阻断当前成功链路。判断是否真正成功，应以以下事实为准：

- Tunnel 进程仍在运行
- `statusLabel=已连接`
- 公网健康检查返回 `HTTP/2 200`

## 当前建议

- 后续开发以当前 APK 内置二进制链路为准
- 不再把 Termux 作为产品默认依赖
- 文档和 UI 一律以“APK 内置 native 二进制 + root 前台服务”表述为准
