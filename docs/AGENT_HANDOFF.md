# Agent Handoff

> Historical note:
> This handoff reflects an earlier failed investigation stage and is no longer the current implementation guide.
> It still mentions Termux fallback and emulator-first validation, both of which are no longer the active path.

## Purpose of This Handoff

This document is for the next agent who will continue development on a Linux ARM64 server and primarily use an Android emulator first, then return to a real rooted phone for final validation.

## Snapshot Summary

Project state at handoff:

- Android app prototype exists and is runnable in principle
- local HTTP API exists
- root-based network rotation exists
- Cloudflare Tunnel support exists
- repository was cleaned for GitHub handoff
- latest tunnel runtime refactor was prepared but not fully rebuilt and re-validated before this handoff

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
- `TunnelRuntime.kt` currently contains a large commented legacy implementation block followed by the newer active implementation
- the next agent should clean that file after confirming the new logic compiles and behaves correctly

## Recommended Immediate Next Steps

### 1. Rebuild on the Linux ARM64 Server

Goal:

- confirm the current project compiles cleanly after the tunnel refactor

Checks:

- `./gradlew assembleDebug`
- inspect Kotlin compiler errors, especially in `TunnelRuntime.kt`

### 2. Bring Up an Emulator

Goal:

- validate that the app launches and the UI is navigable

Checks:

- app opens
- local API starts on `127.0.0.1:18080`
- refresh actions do not crash
- remote screen renders correctly

### 3. Decide the Emulator Scope Explicitly

The emulator can validate:

- UI
- local API
- persistence behavior
- tunnel process invocation basics

The emulator cannot validate:

- real root workflows on production hardware
- actual carrier IPv6 behavior
- airplane-mode IP rotation semantics for the target phone

### 4. Clean the Tunnel Runtime Further

Recommended cleanup:

- remove the commented legacy block from `TunnelRuntime.kt`
- normalize naming around binary source selection and log locations
- consider adding a clearer result model for:
  - bundled success
  - bundled DNS failure
  - Termux fallback success
  - total failure

### 5. Normalize String Encoding

A large part of the UI still contains mojibake text from prior edits.

Recommended approach:

- convert user-facing text to clean UTF-8 strings
- move user-visible copy into `strings.xml`
- choose one language baseline, preferably English first for development stability

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

1. Make the repository compile cleanly.
2. Boot the app in emulator and remove crash-level issues.
3. Clean and normalize UI strings.
4. Decide whether the Tunnel fallback belongs in the app long-term or should be replaced by a better DNS strategy.
5. Return to a real rooted phone for full validation.
6. Only after that, implement the actual proxy backend for the Nodes section.

## Open Questions

- Should the product rely on Termux as a supported fallback, or should the app remain fully self-contained?
- Should root-only actions move into a foreground service rather than being activity-bound?
- Should the first shippable milestone focus on:
  - IP rotation control plus tunnel
  - or a real SOCKS5 / HTTP proxy backend

## Risk Register

- current tunnel code may compile but remain behaviorally unverified
- emulator validation may create false confidence for rooted-phone features
- current repository has no tests and no CI
- committing large environment bundles must be avoided; keep the repo source-only
