#include <jni.h>
#include <android/log.h>
#include <array>
#include <atomic>
#include <cmath>
#include <memory>
#include "EpicenterDSPCore.hpp"
#include "EpicenterHeadphonesCore.hpp"

#define LOG_TAG "EpicenterDSPJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {
using epicenter::EpicenterDSPCore;
using epicenter::HeadphonesBassCore;
constexpr std::size_t PROCESS_CHUNK_FRAMES = 8192;

// Mirrors referencia-ios/EpicenterDSPBridge.mm: both engines live side by side
// and the mode flag decides which one processes a buffer. They never run
// together — in headphones mode the classic core is bypassed entirely.
struct EngineBridge {
    EpicenterDSPCore core;          // classic (car audio / subwoofer)
    HeadphonesBassCore headphones;  // headphones & small speakers
    std::atomic<bool> requestedHeadphonesMode { false };
    std::atomic<bool> enabled { false };
    std::atomic<bool> resetRequested { false };
    std::atomic<float> requestedHeadphonesIntensity { 100.0f };

    // The fields below are owned exclusively by the audio callback. Control
    // calls only publish atomics, so filter state is never mutated concurrently.
    bool activeHeadphonesMode = false;
    float appliedHeadphonesIntensity = -1.0f;
    std::array<std::array<float, PROCESS_CHUNK_FRAMES>, 2> planar {};
};

EngineBridge* fromHandle(jlong handle) {
    return reinterpret_cast<EngineBridge*>(handle);
}
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_epicenter_hifi_dsp_EpicenterDSPNative_nativeInit(JNIEnv*, jobject, jint sampleRate, jint channels) {
    if (sampleRate <= 0) sampleRate = 44100;
    if (channels <= 0) channels = 2;
    auto* bridge = new EngineBridge();
    bridge->core.prepare(static_cast<double>(sampleRate), static_cast<int>(channels));
    bridge->headphones.prepare(static_cast<double>(sampleRate), static_cast<int>(channels));
    LOGI("nativeInit sr=%d ch=%d", sampleRate, channels);
    return reinterpret_cast<jlong>(bridge);
}

extern "C" JNIEXPORT void JNICALL
Java_com_epicenter_hifi_dsp_EpicenterDSPNative_nativeSetEnabled(JNIEnv*, jobject, jlong handle, jboolean enabled) {
    if (auto* bridge = fromHandle(handle)) {
        const bool next = enabled == JNI_TRUE;
        bridge->enabled.store(next, std::memory_order_release);
        bridge->core.setEnabled(next);
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_epicenter_hifi_dsp_EpicenterDSPNative_nativeSetParams(
    JNIEnv*, jobject, jlong handle, jfloat intensity, jfloat sweepFreq, jfloat width, jfloat balance, jfloat volume) {
    if (auto* bridge = fromHandle(handle)) {
        bridge->core.setParameters(intensity, sweepFreq, width, balance, volume);
        // Headphones filter coefficients are applied at the next audio-buffer
        // boundary. Updating them here would race the real-time callback.
        bridge->requestedHeadphonesIntensity.store(intensity, std::memory_order_release);
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_epicenter_hifi_dsp_EpicenterDSPNative_nativeSetHeadphonesMode(
    JNIEnv*, jobject, jlong handle, jboolean headphones) {
    auto* bridge = fromHandle(handle);
    if (!bridge) return;
    const bool next = (headphones == JNI_TRUE);
    bridge->requestedHeadphonesMode.store(next, std::memory_order_release);
    LOGI("nativeSetHeadphonesMode headphones=%d", next ? 1 : 0);
}

extern "C" JNIEXPORT void JNICALL
Java_com_epicenter_hifi_dsp_EpicenterDSPNative_nativeProcessFloatBuffer(
    JNIEnv* env, jobject, jlong handle, jfloatArray buffer, jint channels, jint sampleCount) {
    auto* bridge = fromHandle(handle);
    if (!bridge || !buffer) return;
    const jint sourceChannels = channels;
    if (sourceChannels < 1) return;
    const jint processedChannels = std::min<jint>(sourceChannels, 2);

    // Apply all stateful control changes on the audio thread, between buffers.
    const bool nextHeadphonesMode = bridge->requestedHeadphonesMode.load(std::memory_order_acquire);
    if (bridge->activeHeadphonesMode != nextHeadphonesMode) {
        bridge->activeHeadphonesMode = nextHeadphonesMode;
        bridge->core.reset();
        bridge->headphones.reset();
    }
    if (bridge->resetRequested.exchange(false, std::memory_order_acq_rel)) {
        bridge->core.reset();
        bridge->headphones.reset();
    }
    const float nextIntensity = bridge->requestedHeadphonesIntensity.load(std::memory_order_acquire);
    if (std::fabs(nextIntensity - bridge->appliedHeadphonesIntensity) > 1.0e-4f) {
        bridge->headphones.setIntensity(nextIntensity);
        bridge->appliedHeadphonesIntensity = nextIntensity;
    }

    // Headphones mode with the effect off: leave the buffer untouched so audio
    // passes through unaltered (the classic core is not consulted at all).
    if (bridge->activeHeadphonesMode && !bridge->enabled.load(std::memory_order_acquire)) return;

    // Use the caller's sample count, NOT the array length. The Java side reuses
    // one float[] that only ever grows, so on any buffer smaller than the
    // largest seen so far the tail still holds samples from a previous buffer.
    // Processing that tail fed stale audio into the engines every callback and
    // corrupted the headphones core's bass-period detector and oscillator phase
    // — audible as a wandering sub ("burbujeo").
    const jsize len = env->GetArrayLength(buffer);
    jsize usable = sampleCount > 0 ? sampleCount : len;
    if (usable > len) usable = len;
    if (usable <= 0) return;

    // Frame count must use the real interleaving stride. Capping `channels`
    // before this division corrupted 5.1/7.1 buffers by treating them as stereo.
    const jint frameCount = usable / sourceChannels;
    if (frameCount <= 0) return;

    jboolean isCopy = JNI_FALSE;
    jfloat* interleaved = env->GetFloatArrayElements(buffer, &isCopy);
    if (!interleaved) return;

    // Fixed, preallocated chunks keep the callback free of C++ heap allocations.
    // Only L/R are processed; any additional source channels pass through intact.
    jint frameOffset = 0;
    while (frameOffset < frameCount) {
        const jint chunkFrames = std::min<jint>(
            frameCount - frameOffset,
            static_cast<jint>(PROCESS_CHUNK_FRAMES));
        for (jint i = 0; i < chunkFrames; ++i) {
            const jint sourceFrame = frameOffset + i;
            for (jint ch = 0; ch < processedChannels; ++ch) {
                bridge->planar[static_cast<std::size_t>(ch)][static_cast<std::size_t>(i)] =
                    interleaved[sourceFrame * sourceChannels + ch];
            }
        }

        float* ptrs[2] = { bridge->planar[0].data(), bridge->planar[1].data() };
        if (bridge->activeHeadphonesMode) {
            bridge->headphones.process(ptrs, processedChannels, static_cast<std::size_t>(chunkFrames));
        } else {
            bridge->core.process(ptrs, processedChannels, static_cast<std::size_t>(chunkFrames));
        }

        for (jint i = 0; i < chunkFrames; ++i) {
            const jint sourceFrame = frameOffset + i;
            for (jint ch = 0; ch < processedChannels; ++ch) {
                interleaved[sourceFrame * sourceChannels + ch] =
                    bridge->planar[static_cast<std::size_t>(ch)][static_cast<std::size_t>(i)];
            }
        }
        frameOffset += chunkFrames;
    }

    env->ReleaseFloatArrayElements(buffer, interleaved, 0);
}

extern "C" JNIEXPORT void JNICALL
Java_com_epicenter_hifi_dsp_EpicenterDSPNative_nativeReset(JNIEnv*, jobject, jlong handle) {
    if (auto* bridge = fromHandle(handle)) {
        bridge->resetRequested.store(true, std::memory_order_release);
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_epicenter_hifi_dsp_EpicenterDSPNative_nativeRelease(JNIEnv*, jobject, jlong handle) {
    if (auto* bridge = fromHandle(handle)) {
        delete bridge;
        LOGI("nativeRelease");
    }
}
