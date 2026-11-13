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
- GitHub Actions Debug APK build: successful on 2026-11-13 (diagnostic branch).
- Actual Galaxy A22 5G / Android 13 camera and microphone capture.
- Android-to-iPad P2P video/audio and lock-screen media continuity.
- Network behavior through all GAS redirects and restrictive Wi-Fi/mobile networks.
- TURN relay support, automatic reconnect, saved device pairing, quality controls, volume, connection statistics, camera switching, and mute UI.

## How to test after a successful CI build

1. Install the debug APK on the Galaxy A22 5G.
2. Open the app and paste the deployed GAS Web App URL ending in `/exec`.
3. Tap **② WebRTCを開始（診断）** and grant camera/microphone permissions.
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


## Crash-isolation test (new)

The app now has two separate start buttons so the first test does not initialize WebRTC:

1. Tap **① サービスだけ起動（安全テスト）**. This starts a foreground service with a persistent notification only. It does not initialize WebRTC, open the camera, or open the microphone.
2. Confirm that the notification remains visible for at least 30 seconds and after returning to the home screen.
3. Tap **サービスを停止**.
4. Only after the service-only test is stable, enter the GAS URL, grant camera/microphone permissions, and tap **② WebRTCを開始（次の段階）**. This starts the current WebRTC alpha and may still expose the existing initialization issue.

Do not treat a successful service-only test as proof that WebRTC or background camera/microphone capture works. This is a diagnostic split, not a production-ready release. Test builds are not yet verified on the Galaxy A22 5G.


## Crash diagnostics and foreground-service handling

The diagnostic build now saves the most recent RTC initialization stage locally. If Android terminates the process while the service is marked active, the next app launch reports that the previous process appears to have ended unexpectedly. This helps identify whether the last reached step was SDK initialization, EGL, factory creation, PeerConnection creation, camera enumeration, camera startup, audio setup, or SDP creation.

- The room code is shown only after the local SDP offer has been created and its GAS send has been queued. A number displayed during service startup is not treated as proof that RTC is ready.
- Foreground-service startup errors and uncaught Java/Kotlin exceptions are saved in the diagnostic record.
- Tap **診断ログをコピー** and share the copied text when reporting a crash. The report intentionally excludes the GAS URL, FCM token, and media.
- A native SIGSEGV or process kill cannot always be caught by Kotlin. In that case the saved last stage is still useful, but Android Logcat/tombstone output may be required to identify the exact native fault.

## Incoming-call / FCM design status

FCM incoming-call reception is **not implemented in this build**. The current native session is initiated by Android and sends an SDP offer to the iPad. A true incoming-call flow needs additional pieces, not just a Firebase dependency:

1. Register the Android FCM token with a server and securely store the server-side Firebase credentials.
2. Have the iPad/GAS side send a time-sensitive, user-visible high-priority incoming-call notification.
3. Show an incoming-call notification/screen and wait for the user to tap **Answer**.
4. Start the camera/microphone foreground service from the visible answer flow, after permissions have been confirmed.
5. Add the reverse WebRTC negotiation path so Android can receive the iPad's offer and return an answer.

A high-priority FCM message can provide an exemption for starting a foreground service from the background, but it does not mean the app may silently begin camera/microphone capture. Android's while-in-use camera/microphone restrictions still matter; the user-answer flow is the safer design. FCM high-priority messages can also be downgraded by the system if they are not consistently used for time-sensitive, user-visible notifications.

Do not add a loop that continually restarts the camera/microphone service as a substitute for FCM. It can waste battery and will not make a killed app reliably receive calls.
