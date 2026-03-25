# Project Overview

## Product Intent

The project aims to turn a rooted Android phone into a remotely operated network node.

Target capabilities:

- expose the phone's current network identity and IPv6
- rotate the phone's network identity by reconnecting the cellular data path
- expose a secure remote entrypoint through Cloudflare Tunnel
- eventually host actual proxy services such as SOCKS5 or HTTP proxy nodes

## Current Application Shape

The app is a single Android application module built with:

- Kotlin
- Jetpack Compose
- NanoHTTPD
- Apache Commons Compress

The application is currently a prototype with one activity and several runtime singletons.

## Runtime Components

### 1. Compose UI

File:

- `app/src/main/java/com/mobileagent/demo/MainActivity.kt`

Responsibilities:

- renders the app shell and tab navigation
- shows overview, network, remote, node, and settings screens
- binds UI state to `MobileAgentRuntime` and `TunnelRuntime`
- triggers local actions such as refresh, rotate, bind token, download binary, and start/stop tunnel

Important note:

- the "Nodes" section is largely presentation-only at this stage and does not yet start a real proxy backend

### 2. Local API Runtime

File:

- `app/src/main/java/com/mobileagent/demo/MobileAgentApi.kt`

Responsibilities:

- starts a NanoHTTPD server on `127.0.0.1:18080`
- exposes health and network endpoints
- queues and tracks rotate-IP tasks
- keeps in-memory task records and diagnostics

Implemented endpoints:

- `GET /api/health`
- `GET /api/network/status`
- `POST /api/network/rotate`
- `GET /api/tasks`
- `GET /api/tasks/{taskId}`

### 3. Root Network Controller

File:

- `app/src/main/java/com/mobileagent/demo/RootNetworkController.kt`

Responsibilities:

- gathers active network information from `ConnectivityManager`
- collects IPv6 addresses via shell commands such as `ip -6 addr show`
- finds the preferred interface and address
- toggles airplane mode using root shell commands
- polls for network recovery and reports whether the IPv6 changed

Operational assumptions:

- root access is available
- shell commands such as `cmd connectivity airplane-mode` and `settings put global airplane_mode_on` are permitted on the target device

### 4. Tunnel Runtime

File:

- `app/src/main/java/com/mobileagent/demo/TunnelRuntime.kt`

Responsibilities:

- stores Cloudflare Tunnel token and display domain in shared preferences
- downloads Android `cloudflared` packages from the Termux package repository and extracts the binary
- starts and stops a tunnel process
- tails tunnel logs and summarizes tunnel health
- falls back to a Termux-installed `cloudflared` when the bundled binary fails due to device-specific DNS behavior

Current implementation detail:

- preferred binary source is the bundled Android binary under `/data/local/tmp/cloudflared`
- fallback binary source is `com.termux` under `/data/data/com.termux/files/usr/bin/cloudflared`

## Data Flow

### Network Snapshot Flow

1. UI requests refresh.
2. `MobileAgentRuntime` calls `RootNetworkController.refreshSnapshot()`.
3. Root shell commands collect routing and IPv6 addresses.
4. Result is returned to UI and API clients.

### Rotate IP Flow

1. UI or API calls `POST /api/network/rotate`.
2. `MobileAgentRuntime.submitRotateTask()` creates an in-memory task.
3. `RootNetworkController.rotateIp()` toggles airplane mode and polls for recovery.
4. Snapshot and task state are updated.

### Tunnel Flow

1. User binds a Cloudflare Tunnel token.
2. `TunnelRuntime.start()` chooses a runnable binary source.
3. If bundled `cloudflared` is available, it is tried first.
4. If logs indicate DNS resolution failure against `[::1]:53`, the runtime falls back to the Termux binary when present.
5. Tunnel logs are surfaced to the UI.

## Practical Validation Strategy

### Safe to Validate in Emulator

- Compose UI rendering
- local API startup
- button wiring
- non-root UI state transitions
- tunnel process lifecycle logic where root is not strictly required

### Must Be Validated on a Rooted Real Device

- root shell access
- airplane-mode toggling
- cellular IPv6 recovery and change detection
- Cloudflare Tunnel behavior under real mobile and Wi-Fi networks
- any future proxy service that must egress through the phone's real network

## Known Technical Debt

- many visible strings in the UI and controller code should be normalized because some prior edits introduced mojibake
- `TunnelRuntime.kt` contains a commented legacy implementation block that should be removed once the new implementation is fully validated
- no test suite exists yet
- no service process exists yet for persistent operation outside the foreground activity
