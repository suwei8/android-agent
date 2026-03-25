# Build And Release Strategy

This repository is developed on an ARM64 host, but APK compilation is not treated as a local responsibility.

## Canonical Strategy

- ARM64 host: edit source, review logs, prepare commits, and run Redroid for local validation
- GitHub Actions: compile APK and publish release assets
- Redroid on OCI ARM: fast local install and smoke test target
- rooted Android device: final validation target for root-only behavior

## Why

The current ARM64 server should not be treated as a standard Android build host.

The practical blocker is not repository code. The blocker is the Android Linux host-tool path used by Gradle resource compilation.

Because of that:

- local ARM64 `./gradlew assembleDebug` is not the source of truth
- GitHub Actions on `ubuntu-latest` is the source of truth for APK production

## Normal CI Build

Any push to `master` or any pull request runs:

- checkout
- Java 17 setup
- Android SDK package install
- `./gradlew assembleDebug`
- artifact upload

Artifact name:

- `android-apk`

Contained files:

- `mobile-agent-debug.apk`
- `mobile-agent-debug.apk.sha256`

## Release Build

There are two supported release paths.

### 1. Tag Release

Push a tag such as:

```bash
git tag v1.0.0
git push origin v1.0.0
```

This publishes a GitHub Release automatically.

### 2. Manual Release

Open the `Android Build` workflow in GitHub Actions and run it manually with:

- `publish_release = true`
- `release_tag = v1.0.0`

## Release Destination

- `https://github.com/suwei8/android-agent/releases`

## Validation Flow

Recommended flow after a release build:

1. Download the APK from GitHub Releases.
2. Install it into Redroid on the ARM64 host for local smoke testing.
3. Validate UI, local API, and Tunnel behavior in Redroid.
4. Validate root-only workflows such as airplane-mode based rotation on the rooted target phone.

## Non-Goals On The ARM64 Host

Do not treat the following as required on the ARM64 server:

- native APK compilation
- local Android host-tool repair work

What the ARM64 server can do well in the current setup:

- run Redroid as the local Android validation target
- host ADB-based install, launch, and API checks against the CI-produced APK

If emulator validation is still needed, use a host that actually supports the Android emulator and build-tools stack.
