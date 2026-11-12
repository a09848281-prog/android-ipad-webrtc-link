# Android Native Track (separate from the working PWA)

This directory is an isolated Android Studio project. The existing GitHub Pages PWA and GAS files remain on the original `main` branch and are not replaced.

## Current status: alpha foundation only

Implemented in this commit:
- Separate Android application module and pinned Gradle/plugin/dependency versions.
- Runtime permission request for camera, microphone, and Android 13 notifications.
- Explicit user-initiated foreground-service startup.
- Persistent service notification with a Stop action.
- Bounded partial wake lock (30 minutes) and cleanup.
- Service uses `START_NOT_STICKY` to avoid restoring an invalid half-connected session.

Not implemented yet:
- WebRTC camera/microphone capture and peer negotiation.
- GAS signaling integration.
- Automatic reconnect, saved device pairing, quality controls, volume, connection statistics, camera switch, and microphone mute.
- Verified lock-screen media continuity.

Do not treat this alpha as a usable replacement for the PWA. The service currently proves only the Android service/permission/notification lifecycle.

## Build

Open this directory in Android Studio (JDK 17, Android SDK 35), sync Gradle, then run the `app` debug configuration. GitHub Actions also runs a debug build on pushes to this branch.

## Crash-prevention rules

1. Do not initialize WebRTC before permissions are granted.
2. Start foreground service only from a visible Activity after an explicit user action.
3. Call `startForeground` immediately in service startup.
4. Do not use `START_STICKY` for an incomplete media session.
5. Release wake locks, camera capturers, audio devices, peer connections, and EGL resources in a single idempotent shutdown path.
6. Keep media operations off the main thread and serialize peer-connection state transitions.
7. Every asynchronous callback must check that its session is still current before touching UI or native resources.
8. Test on the actual Galaxy A22 5G / Android 13 before producing a release APK.

## Background limitations

A foreground service and persistent notification are required for long-running media capture. Android may still restrict camera/microphone access depending on OS version, permission state, OEM policy, battery settings, and how the service was started. This project does not promise capture after force-stop, reboot, permission revocation, or app termination by the system.
