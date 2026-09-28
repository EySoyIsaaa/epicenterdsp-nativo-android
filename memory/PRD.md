# EpicenterDSP — Android 100% Native PRD

## Problem statement
Convert the React/Capacitor audio player (EpicenterDSP) into a 100% Android-native
playback + DSP stack. React/WebView is allowed ONLY for UI. Audio playback and
all DSP processing must be handled natively via:
- ExoPlayer / Media3 (`DefaultAudioSink`)
- `EpicenterAudioProcessor` (Java) → `EpicenterDSPNative` (JNI) → C++ core
- `EqAudioProcessor` (31-band Java biquad chain)

WebAudio / AudioWorklet / `new Audio()` are FORBIDDEN on Android (no silent
fallbacks — fail loudly if native fails).

## Current state (Feb 2026)
- Phase 5.5: WebAudio routing disabled on Android (DONE)
- Phase 6: 31-band native EQ wired into ExoPlayer audio sink (DONE)
- ExoPlayer threading + URI sanitization fixes (DONE)

## P0 Blocking bugs — FIX SESSION
- [x] **BUG 1** (resolved): `nativeInit`/`nativeRelease` spam — fix via cached `nativeAvailableCached`.
- [x] **BUG 2 — ROOT CAUSE FOUND (Feb 2026, follow-up)**:
  - Initial probe `setDebugAudibleGainTest(true, 0.1)` had NO audible effect, proving the
    `EpicenterAudioProcessor` was never in the audio pipeline.
  - Root cause: in `androidx.media3:1.3.1`, `DefaultRenderersFactory.buildAudioSink` has the
    signature `(Context, boolean enableFloatOutput, boolean enableAudioTrackPlaybackParams)` —
    THREE parameters. Our override used a FOUR-parameter signature (with a phantom
    `enableOffload` boolean), so it did NOT actually override the parent. Media3 silently
    used the default `DefaultAudioSink` (without `EpicenterAudioProcessor` /
    `EqAudioProcessor`).
  - Fix: corrected `NativePlaybackController.java` `buildAudioSink` signature to 3 args.
    Forced `setEnableFloatOutput(false)` inside the builder (defensive — PCM_FLOAT input
    would make our processors throw `UnhandledAudioFormatException` and Media3 would skip
    them silently). Added a static `sBuildAudioSinkCallCount` counter to confirm the override
    is invoked at runtime.
  - Diagnostic logs added (all surface as logcat tags `NativePlayer` /
    `EpicenterAudioProcessor`):
      * `buildAudioSink CALLED (call #N)` — fires when Media3 builds the sink.
      * `buildAudioSink RETURNED custom DefaultAudioSink with EpicenterAudioProcessor + EqAudioProcessor in chain`
      * `EpicenterAudioProcessor onConfigure CALLED sr=… ch=… encoding=…`
      * `EpicenterAudioProcessor queueInput FIRST BUFFER bytes=…`
      * `EpicenterAudioProcessor getOutput FIRST OUTPUT bytes=…`
      * `loadTrack source=… buildAudioSinkCalls=N`
      * `play (buildAudioSinkCalls=N epicenterOnConfigure=… queueInput=… getOutput=…)`
  - Engine state now exposes `buildAudioSinkCalls`, `epicenterOnConfigureCalled`,
    `epicenterQueueInputCalled`, `epicenterGetOutputCalled` for in-app diagnostics.
- [x] **BUG 3** (resolved): library re-import gated to 24-hour cooldown.

## P1 Upcoming (Future phases — DO NOT start until user verifies P0)
- Phase 7: Native FX (Reverb, Concert Hall, Spatial, Bass Boost) implemented as
  additional `AudioProcessor` nodes in the DefaultAudioSink chain.
- Phase 8: Full native queue (`setQueue`, `next`, `previous` via ExoPlayer
  `MediaItem` list).
- Phase 9: Native `MediaSession` service + background playback + persistent
  notification.
- Phase 10: UI integration of all native features.

## Code architecture (Android-relevant)
```
/app/
├── android/app/src/main/java/com/epicenter/hifi/
│   ├── EpicenterNativePlugin.java         (Capacitor bridge — UI ↔ native)
│   ├── MusicScannerPlugin.java            (MediaStore scan, Room DB, cache)
│   ├── AppDatabase.java / TrackDao.java / TrackEntity.java   (Room)
│   ├── nativeaudio/
│   │   ├── NativePlaybackController.java  (ExoPlayer + AudioSink chain)
│   │   ├── EpicenterAudioProcessor.java   (Java AudioProcessor + JNI bridge)
│   │   ├── EqAudioProcessor.java          (31-band biquad EQ)
│   │   └── NativeAudioTrack.java / NativeQueueManager.java
│   └── dsp/
│       └── EpicenterDSPNative.java        (JNI bindings + selfTest)
└── client/src/
    ├── hooks/
    │   ├── useAndroidNativeAudioProcessor.ts (Native-only React controller)
    │   ├── useAndroidMusicLibrary.ts          (MusicScanner client)
    │   ├── useAudioQueue.ts                   (Library state from Room)
    │   └── useIntegratedAudioProcessor.ts
    ├── native/
    │   └── epicenterNativeAndroid.ts         (TS Capacitor plugin bindings)
    └── lib/musicLibraryDB.ts                  (Room-backed adapter)
```

## Verification checklist (user)
1. Build & install APK.
2. Open app, play any track. Adjust an EQ slider repeatedly.
   - `adb logcat | grep -E "nativeInit|nativeRelease"` should show ONE init
     pair (from `runSelfTest()` in initialize), then stay flat.
   - `window.epicenterNativeDebugState()` (from devtools / WebView inspector)
     should report `dspInitCount`/`dspReleaseCount` not increasing during
     slider movement.
3. From devtools console:
   `await window.epicenterDebugAudibleGain(true, 0.1)` → volume should drop ~90%.
   `await window.epicenterDebugAudibleGain(false, 1.0)` → volume back to normal.
   If volume does NOT change → JNI/C++ side is the audible-output blocker.
4. Close + reopen the app. Library should appear instantly from Room DB
   without the importing overlay. Logcat should print:
   `[Library] Skipping MediaStore reconcile, last run { ageHours: ... }`.

## Test credentials
Not applicable — no auth in this app.
