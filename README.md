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
- bundled `cloudflared` download and install logic for Android
- a fallback tunnel runner that can switch to a Termux-installed `cloudflared` when the bundled binary fails on device DNS resolution
- tunnel runtime cleanup now targets ARM Android devices only for bundled `cloudflared` downloads (`arm64-v8a` and `armeabi-v7a`)

What is not complete yet:

- no full end-to-end validation on a rooted physical phone after the latest tunnel refactor
- no stable background service / boot-start implementation
- no real SOCKS5 / HTTP proxy node backend yet; the "nodes" area is still mostly UI scaffolding
- GitHub Actions now owns APK compilation and GitHub Releases publishing, but there are still no automated tests

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

- Linux ARM64 development server for source editing and Redroid-based local validation
- GitHub Actions on `ubuntu-latest` for all APK compilation and release publishing
- OCI ARM host with Redroid for local UI and API smoke tests
- a rooted real device for final validation of root-only workflows

Important limitation:

- the current ARM64 server is not a supported host for the official Android Linux build-tools path used by Gradle resource compilation
- do not treat local `assembleDebug` failures on this ARM64 host as application-code failures
- use GitHub Actions as the canonical build path, then install the produced APK into Redroid for fast local validation
- use a rooted real device for final confirmation of root-only workflows such as airplane-mode based IP rotation

## Build And Release

Canonical build path:

- push commits to GitHub to trigger CI artifact builds
- create and push a tag such as `v1.0.0` to publish an APK to GitHub Releases
- or run the `Android Build` workflow manually with `publish_release=true` and a `release_tag`

Release output location:

- `https://github.com/suwei8/android-agent/releases`

Workflow details:

- CI workflow file: `.github/workflows/android-build.yml`
- artifact name: `android-apk`
- release asset name: `mobile-agent-debug.apk`
- checksum asset: `mobile-agent-debug.apk.sha256`

For step-by-step release usage, see:

- `docs/BUILD_RELEASE.md`

## Known Risks

- the latest `TunnelRuntime.kt` refactor still needs device-side validation even after the legacy block cleanup
- the GitHub Actions pipeline builds on x86_64 runners, so local ARM64 toolchain issues may not reproduce there
