# Agent Handoff

## Purpose of This Handoff

This document is for the next agent who will continue development on a Linux ARM64 server while treating GitHub Actions as the canonical APK build path and Redroid as the local validation target.

## Snapshot Summary

Project state at handoff:

- Android app prototype exists and is runnable in principle
- local HTTP API exists
- root-based network rotation exists
- Cloudflare Tunnel support exists
- repository was cleaned for GitHub handoff
- GitHub Actions is now responsible for APK compilation and GitHub Releases publishing

Update after ARM64 environment cleanup:

- `TunnelRuntime.kt` no longer carries the legacy commented implementation block
- bundled `cloudflared` download selection now targets ARM Android ABIs only (`arm64-v8a` and `armeabi-v7a`)
- current OCI ARM64 host should not be treated as a native Android APK build host
- official Android Linux resource-build tooling in this environment is still unsuitable for canonical local APK builds
- stale local `x86_64` Android `build-tools` were removed from the active SDK path and parked outside it to avoid accidental reuse
- Cloudflare remote tunnel config for `android-agent` was corrected to target the app's local API at `http://127.0.0.1:18080` instead of the stale `localhost:8080` mapping
- Redroid on the OCI ARM64 host was stabilized by fixing `/dev/binder`, `/dev/hwbinder`, and `/dev/vndbinder` permissions during startup, so local APK smoke testing should now target Redroid first

## What Was Verified Before Handoff

### Device Connectivity

- ADB connection to the real device was available during the previous session.

### Tunnel Root Cause Investigation

The main blocker was not an invalid token.

Observed behavior:

- bundled `cloudflared` under `/data/local/tmp/cloudflared` failed during edge discovery
- failure pattern was DNS resolution against `[::1]:53`
- logs contained errors like:
  - `lookup _v2-origintunneld._tcp.argotunnel.com on [::1]:53`
  - `connection refused`

### Counterexample That Proved the Tunnel Itself Was Fine

The same device had Termux installed with its own `cloudflared`.

Termux environment facts:

- package `com.termux` existed
- `cloudflared` existed at `/data/data/com.termux/files/usr/bin/cloudflared`
- Termux resolver config used public DNS servers rather than the broken local loopback resolver

Manual validation result:

- launching `cloudflared tunnel run --token ...` from the Termux environment successfully produced multiple `Registered tunnel connection` log lines

Conclusion:

- Cloudflare Tunnel configuration was valid
- the device-specific execution environment for the bundled binary was the issue

## Code Changes Made During Investigation

The main in-progress code change is in:

- `app/src/main/java/com/mobileagent/demo/TunnelRuntime.kt`

Intent of the refactor:

- keep the bundled Android binary as the preferred path
- detect the `[::1]:53` DNS failure pattern
- fall back to a Termux-installed `cloudflared` if available
- persist the selected binary source in shared preferences

Important caveat:

- the refactor was not fully rebuilt and confirmed before handoff
- the next agent should validate the cleaned active implementation on device, especially the fallback switch from bundled binary to Termux

## Recommended Immediate Next Steps

### 1. Do Not Use The ARM64 Server As The Canonical APK Build Host

Goal:

- avoid wasting time treating host-tool incompatibility as repository breakage

Checks:

- source editing and review are fine on ARM64
- APK production belongs to GitHub Actions
- if local Gradle fails in resource tooling on this host, record it as an environment limitation and move on

### 2. Validate CI Artifact Production

Goal:

- ensure GitHub Actions produces a usable APK artifact or Release asset

Checks:

- push branch changes and inspect the workflow artifact
- for tagged builds, verify the Release asset appears under GitHub Releases
- use the produced APK as the installable binary for device testing

### 3. Use A Real Rooted Device For Meaningful Validation

Primary validation target:

- rooted real phone

Why:

- root shell access
- airplane-mode toggling
- carrier/mobile IPv6 behavior
- real Cloudflare Tunnel behavior on the device network

Emulator usage is optional and secondary, and only makes sense on a host that actually supports the Android emulator and build-tools stack.

### 3a. Use Redroid For Fast Local Validation

Primary local validation target:

- Redroid on the OCI ARM64 host

Why:

- low feedback cost
- no need for a separate x86_64 emulator workstation
- good fit for UI, local API, and tunnel smoke tests
- ADB install and launch are already available in the current environment

### 4. Clean the Tunnel Runtime Further

Recommended cleanup:

- normalize naming around binary source selection and log locations
- consider adding a clearer result model for:
  - bundled success
  - bundled DNS failure
  - Termux fallback success
  - total failure

### 5. Continue UI Copy Normalization

The user-facing app UI is now Chinese-first, but future edits should keep copy consistent and avoid mixed-language regressions.

Recommended approach:

- keep user-facing copy clean UTF-8
- continue moving reusable user-visible copy into `strings.xml` over time
- keep the visible product language Chinese unless requirements change

## Architecture Notes for the Next Agent

### Main UI

- `MainActivity.kt`
- controls tab navigation and state sync with the runtimes

### API Runtime

- `MobileAgentApi.kt`
- local API and rotate-task orchestration

### Network Control

- `RootNetworkController.kt`
- root shell execution, IPv6 discovery, and airplane mode rotation

### Tunnel Control

- `TunnelRuntime.kt`
- tunnel process lifecycle and binary management

## Suggested Development Order

1. Make source changes on the ARM64 host.
2. Use GitHub Actions to produce APKs.
3. Install CI-produced APKs in Redroid first.
4. Then validate root-only behavior on the real rooted phone.
5. Continue cleaning UI strings and runtime messaging.
6. Decide whether the Tunnel fallback belongs in the app long-term or should be replaced by a better DNS strategy.
7. Only after that, implement the actual proxy backend for the Nodes section.

## Open Questions

- Should the product rely on Termux as a supported fallback, or should the app remain fully self-contained?
- Should root-only actions move into a foreground service rather than being activity-bound?
- Should the first shippable milestone focus on:
  - IP rotation control plus tunnel
  - or a real SOCKS5 / HTTP proxy backend

## Risk Register

- current tunnel code may compile but remain behaviorally unverified
- current OCI ARM64 server is not the canonical APK build environment
- emulator validation can create false confidence for rooted-phone features
- current repository has no tests; CI currently covers build and release packaging only
- committing large environment bundles must be avoided; keep the repo source-only
