# Mobile Agent Demo

Mobile Agent Demo is an Android application prototype for operating a rooted Android phone as a controllable network agent.

The current prototype focuses on two workflows:

- rotate the phone's outbound IPv6 by toggling airplane mode with root privileges
- expose a local control API through Cloudflare Tunnel so a remote operator can inspect network state and trigger IP rotation

## Current Status

This repository is a handoff snapshot, not a polished release.

What already exists:

- a Compose-based control UI
- a local HTTP API powered by NanoHTTPD on `127.0.0.1:18080`
- root command execution for network inspection and airplane-mode based IP rotation
- a bundled `cloudflared` ARM64 binary shipped inside the APK
- a foreground-service based tunnel runner validated on a rooted real device

What is not complete yet:

- no boot-start implementation
- no real SOCKS5 / HTTP proxy node backend yet; the "nodes" area is still mostly UI scaffolding
- no CI, automated tests, or release pipeline

## Repository Layout

- `app/src/main/java/com/mobileagent/demo/MainActivity.kt`
  Compose UI and screen state orchestration.
- `app/src/main/java/com/mobileagent/demo/MobileAgentApi.kt`
  Local control API and in-memory task tracking.
- `app/src/main/java/com/mobileagent/demo/RootNetworkController.kt`
  Root shell integration, IPv6 snapshot collection, and airplane-mode IP rotation.
- `app/src/main/java/com/mobileagent/demo/TunnelRuntime.kt`
  Cloudflare Tunnel binary management and tunnel process lifecycle.
- `docs/PROJECT_OVERVIEW.md`
  Architecture and runtime behavior.
- `docs/AGENT_HANDOFF.md`
  Current state, known blockers, and recommended next steps for the next agent.

## Local API

The embedded API listens on `127.0.0.1:18080`.

Implemented endpoints:

- `GET /api/health`
- `GET /api/network/status`
- `POST /api/network/rotate`
- `GET /api/tasks`
- `GET /api/tasks/{taskId}`

## Development Notes

Recommended next environment:

- Linux ARM64 development server
- command-line Android SDK
- a rooted real device for validation of root-only workflows

## Build

Expected toolchain:

- JDK 17
- Android SDK Platform 35
- Gradle 8.7 compatible environment

Typical commands:

```bash
./gradlew assembleDebug
./gradlew installDebug
```

## Known Risks

- several UI strings in the current source appear to contain mojibake from prior editing and should be normalized
- `TunnelRuntime.kt` currently contains a legacy block that was commented out during an in-progress refactor; functional logic is in the later active implementation
- the latest tunnel fallback refactor was prepared for validation but was not fully rebuilt and re-tested before handoff
