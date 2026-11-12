# Android Native Track (separate from the working PWA)

This directory is an isolated Android Studio project. The existing GitHub Pages PWA and GAS files on `main` are preserved and are not replaced.

## Current status: WebRTC alpha (not yet device-verified)

Implemented in this branch:
- Separate Android application module with pinned Gradle/plugin/WebRTC SDK versions.
- Runtime permission request for camera, microphone, and Android 13 notifications.
- Explicit user-initiated camera/microphone foreground-service startup.
- Persistent notification with a Stop action and bounded 30-minute partial wake lock.
- WebRTC factory, camera capturer, microphone audio track, PeerConnection, SDP offer/answer, and ICE candidate handling.
- GAS signaling compatible with the existing PWA protocol (`offer`, `answer`, `candidate`; 6-digit room code).
- Separate polling and send executors; idempotent cleanup for capture, peer, EGL, and factory resources.

Not yet verified:
- Successful Gradle build of this WebRTC commit.
- Actual Galaxy A22 5G / Android 13 camera and microphone capture.
- Android-to-iPad P2P video/audio and lock-screen media continuity.
- Network behavior through all GAS redirects and restrictive Wi-Fi/mobile networks.
- TURN relay support, automatic reconnect, saved device pairing, quality controls, volume, connection statistics, camera switching, and mute UI.

## How to test after a successful CI build

1. Install the debug APK on the Galaxy A22 5G.
2. Open the app and paste the deployed GAS Web App URL ending in `/exec`.
3. Tap **WebRTC通信を開始** and grant camera/microphone permissions.
4. Keep the app open until the 6-digit room code appears.
5. Open the existing iPad PWA, set the GAS URL, choose iPad mode, enter the code, and join.
6. Confirm video and push-to-talk audio in both directions.
7. Only after foreground testing succeeds, lock the Android screen and repeat the media test.

The current implementation uses public Google STUN servers only. Some networks require a TURN relay, so a failed P2P connection does not necessarily indicate an application bug.

## Crash-prevention rules

1. Do not initialize WebRTC before permissions are granted.
2. Start the camera/microphone foreground service only from a visible Activity after an explicit user action.
3. Call `startForeground` immediately in service startup.
4. Do not use `START_STICKY` for an incomplete media session.
5. Release camera capturers, audio tracks, peer connections, EGL resources, executors, and wake locks through one idempotent shutdown path.
6. Keep network polling and signaling sends off the main thread.
7. Treat asynchronous callbacks as potentially stale after shutdown.
8. Test on the actual Galaxy A22 5G / Android 13 before producing a release APK.

## Background limitations

A foreground service and persistent notification are required for long-running media capture. Android may still restrict camera/microphone access depending on OS version, permission state, OEM policy, battery settings, and how the service was started. This project does not promise capture after force-stop, reboot, permission revocation, or app termination by the system.
