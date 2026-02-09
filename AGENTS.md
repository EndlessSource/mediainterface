# AGENTS.md

## Purpose
This repository contains a cross-platform Java media-control library with per-platform modules and native bridge code.
Use this file as the quick operational reference when making changes.

## Project Structure

### Root
- `settings.gradle.kts`: declares all subprojects.
- `build.gradle.kts`: shared repository and publishing setup for Java subprojects.
- `README.md`: public-facing project status and feature matrix.
- `.github/workflows/`: CI and publish workflows.

### Subprojects
- `mediainterface-core`
  - Shared API and provider discovery (`SystemMediaFactory`, `PlatformSupport`, `api/*`, `spi/*`).
  - No platform-specific code.
- `mediainterface-linux`
  - Linux implementation over DBus/MPRIS.
  - Java-only (no JNI).
- `mediainterface-windows`
  - Windows implementation using WinRT via JNI.
  - Native code in `src/native/windows`.
  - Produces architecture-specific DLL resources.
- `mediainterface-macos`
  - macOS implementation using MediaRemote adapter framework + Perl bridge.
  - Native adapter framework in `src/native/macos`.
  - Java adapter/session code in `src/main/java/.../macos`.
- `mediainterface-all`
  - Aggregates all platform modules into one dependency.
- `examples`
  - Runnable demos and example jars.
  - Includes CLI/event-driven/logger/Swing examples.

## Native Build Notes
- Windows native artifacts are built only on Windows runners/hosts.
- macOS native adapter artifacts are built only on macOS runners/hosts.
- Linux module is Java-only.
- Native resources are copied into module resources under `build/resources/main/native/...`.

## Documentation Update Rules

When implementation behavior changes, update `README.md` in the same change.

### Required README sections to keep in sync
- `## Operating system support` feature table.
- Platform/backend table below it.

### Feature table update checklist
For each platform (Linux/Windows/macOS), verify and update rows for:
- Session discovery and multi-session behavior.
- Playback state and now playing fields.
- Artwork support.
- Position behavior (raw vs virtualized vs computed).
- Controls and seek support.
- Polling/event-driven support semantics.
- Event callbacks (`onPlaybackStateChanged`, `onSessionAdded/Removed`, `onNowPlayingChanged`, `onSessionActiveChanged`).
- Configurable intervals.

### Semantics guidance for table wording
- Use `Yes` / `No` / `Partial` only.
- If behavior is emulated/fallback rather than OS-native, reflect that in row naming (for example, distinguish "supported" vs "process system events").
- Keep row names stable unless behavior meaning changed.

### When to update platform/backend table
Update whenever any of these changes:
- Module status/coverage.
- Native backend type.
- Supported architectures.
- Aggregator composition (`mediainterface-all` dependencies).

## Example Update Rules
- If a public-facing behavior changes (position semantics, artwork format, app-name source), update at least one example to reflect expected usage/output.
- Keep example run tasks in `examples/build.gradle.kts` aligned with available example entrypoints.

## Build Verification Minimum
Before considering a change done, run:
- `./gradlew :mediainterface-core:compileJava :mediainterface-linux:compileJava :mediainterface-windows:compileJava :mediainterface-macos:compileJava :examples:compileJava --no-daemon`

If native code changed, also run:
- macOS: `./gradlew :mediainterface-macos:processResources --no-daemon`
- Windows: relevant windows native build tasks on Windows host.

## Commit Hygiene
- Do not leave README support tables stale after feature changes.
- Avoid committing IDE directories/files.
