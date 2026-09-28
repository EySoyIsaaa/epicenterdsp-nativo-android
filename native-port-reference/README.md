# iOS Native EpicenterDSP Reference

This folder contains reference files from the iOS native EpicenterDSP implementation.

Goal:
Port the native iOS Epicenter DSP sound to Android using Android NDK/JNI, while keeping the current Android app stable.

Important rules:
- Do not compile Swift files on Android.
- Do not compile Objective-C++ .mm files on Android.
- Swift and .mm files are reference only.
- The main DSP files to port/compile are:
  - EpicenterDSPCore.hpp
  - EpicenterDSPCore.cpp
- Android should use these files through NDK/CMake/JNI.
- React/Capacitor should remain as UI/control layer only.
- Do not replace or delete the current WebAudio implementation yet.
- Add the native Android DSP behind an experimental flag first.

Reference purpose:
- EpicenterDSPCore.hpp/.cpp: native DSP algorithm and sound behavior.
- EpicenterDSPBridge.h/.mm: parameter mapping and bridge behavior reference.
- NativeAudioEngine.swift: audio buffer feeding, playback behavior and DSP integration reference.
- NativePlaybackController.swift: queue/playback control reference.
- EpicenterNativePlugin.swift and index.d.ts: Capacitor plugin API reference.
- iosNativeAudio.ts and useIosNativeAudioProcessor.ts: frontend integration reference.

Android target:
Create an Android native implementation using:
- C++ DSP core
- Android NDK
- CMake
- JNI wrapper
- ExoPlayer/Media3 AudioProcessor or equivalent native playback path
- MediaSession for background controls later

First phase:
1. Copy EpicenterDSPCore.hpp and EpicenterDSPCore.cpp into android/app/src/main/cpp/
2. Create CMakeLists.txt
3. Create EpicenterDSPJNI.cpp
4. Create Android wrapper class EpicenterDSPNative
5. Verify the app compiles
6. Test processing a small float buffer before integrating with the player