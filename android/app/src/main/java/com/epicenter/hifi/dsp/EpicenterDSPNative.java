package com.epicenter.hifi.dsp;

import java.util.concurrent.atomic.AtomicLong;

public class EpicenterDSPNative {
    static {
        System.loadLibrary("epicenterdsp");
    }

    // Diagnostic counters: surfaced via plugin getEngineState so we can confirm
    // that the DSP engine is NOT being torn down on every state poll.
    private static final AtomicLong INIT_COUNT = new AtomicLong(0);
    private static final AtomicLong RELEASE_COUNT = new AtomicLong(0);

    public static long getInitCount() { return INIT_COUNT.get(); }
    public static long getReleaseCount() { return RELEASE_COUNT.get(); }

    private long nativeHandle;

    public EpicenterDSPNative(int sampleRate, int channels) {
        nativeHandle = nativeInit(sampleRate, channels);
        INIT_COUNT.incrementAndGet();
    }

    public void setEnabled(boolean enabled) {
        nativeSetEnabled(nativeHandle, enabled);
    }

    public void setParams(float intensity, float sweepFreq, float width, float balance, float volume) {
        nativeSetParams(nativeHandle, intensity, sweepFreq, width, balance, volume);
    }

    /**
     * Selects which bass engine processes audio. {@code true} routes to the
     * headphones core (small drivers); {@code false} keeps the classic Car Audio
     * core. The engines never run together and both are reset on a real change.
     */
    public void setHeadphonesMode(boolean headphones) {
        nativeSetHeadphonesMode(nativeHandle, headphones);
    }

    /**
     * @param sampleCount muestras REALES en el buffer. El array se reutiliza y
     *   solo crece, asi que su longitud puede exceder lo valido; procesar de mas
     *   metia audio viejo al motor y le corrompia el estado.
     */
    public void processFloatBuffer(float[] interleavedBuffer, int channels, int sampleCount) {
        nativeProcessFloatBuffer(nativeHandle, interleavedBuffer, channels, sampleCount);
    }

    public void reset() {
        nativeReset(nativeHandle);
    }

    public void release() {
        if (nativeHandle != 0L) {
            nativeRelease(nativeHandle);
            nativeHandle = 0L;
            RELEASE_COUNT.incrementAndGet();
        }
    }

    public static boolean runSelfTest() {
        return runFormatSelfTest(44100, 2, false)
            && runFormatSelfTest(192000, 2, true)
            && runFormatSelfTest(192000, 6, false);
    }

    private static boolean runFormatSelfTest(int sampleRate, int channels, boolean headphones) {
        EpicenterDSPNative dsp = new EpicenterDSPNative(sampleRate, channels);
        try {
            final int frames = 1024;
            float[] buffer = new float[frames * channels];
            float[] extraChannels = channels > 2 ? new float[buffer.length] : null;
            for (int frame = 0; frame < frames; frame++) {
                final float sample = (float) (0.2 * Math.sin(2.0 * Math.PI * 55.0 * frame / sampleRate));
                buffer[frame * channels] = sample;
                if (channels > 1) buffer[frame * channels + 1] = -sample;
                for (int channel = 2; channel < channels; channel++) {
                    final float untouched = (channel + 1) * 0.01f;
                    buffer[frame * channels + channel] = untouched;
                    extraChannels[frame * channels + channel] = untouched;
                }
            }
            dsp.setEnabled(true);
            dsp.setParams(100f, 45f, 50f, 100f, 100f);
            dsp.setHeadphonesMode(headphones);
            // Exercise the actual source stride, including surround passthrough.
            dsp.processFloatBuffer(buffer, channels, buffer.length);
            for (int frame = 0; frame < frames; frame++) {
                for (int channel = 0; channel < channels; channel++) {
                    final float value = buffer[frame * channels + channel];
                    if (!Float.isFinite(value) || value < -1.0001f || value > 1.0001f) return false;
                    if (channel >= 2 && value != extraChannels[frame * channels + channel]) return false;
                }
            }
            return true;
        } finally {
            dsp.release();
        }
    }

    private native long nativeInit(int sampleRate, int channels);

    private native void nativeSetEnabled(long handle, boolean enabled);

    private native void nativeSetParams(long handle, float intensity, float sweepFreq, float width, float balance, float volume);

    private native void nativeSetHeadphonesMode(long handle, boolean headphones);

    private native void nativeProcessFloatBuffer(long handle, float[] interleavedBuffer, int channels, int sampleCount);

    private native void nativeReset(long handle);

    private native void nativeRelease(long handle);
}
